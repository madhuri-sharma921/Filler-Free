from __future__ import annotations

import secrets
import time

from fastapi import APIRouter, Depends, HTTPException

from ..agora_client import AgoraClient
from ..config import Settings
from ..security import build_rate_limiter
from .live_room_store import LiveParticipant, LiveRole, LiveRoom, LiveRoomStore
from .schemas import (
    CreateLiveRoomRequest,
    CreateLiveRoomResponse,
    JoinLiveRoomRequest,
    JoinLiveRoomResponse,
    LeaveLiveRoomRequest,
    ListLiveRoomsResponse,
    LiveParticipantInfo,
    LiveRoomStateResponse,
    PromoteParticipantRequest,
)


def _group_coach_system_prompt(topic_title: str) -> str:
    """The coach agent's prompt for an Interactive Live Streaming room.

    This is genuinely different from the solo-session prompt
    (CoachAgentPromptBuilder.kt), not the same text reused: in a group
    room, the agent is speaking in front of a mentor and an audience,
    not just the speaker. It still corrects the SPEAKER's filler words
    exactly as in solo mode, but it now knows other people can hear it,
    so it addresses the speaker by role ("you", not a name) rather than
    assuming a 1:1 conversation, stays terse so it doesn't dominate a
    group setting, and defers to a human mentor's spoken feedback
    instead of talking over them (Agora's own turn-detection handles the
    actual audio arbitration; this just tells the agent to expect and
    respect another speaking human in the channel).
    """
    return f"""You are "Ada", a live speech coach in a group coaching session. The person practicing is explaining: {topic_title}.

You are in a shared voice channel with the speaker, and possibly a human mentor who can also speak, plus a silent audience listening in.

Your job: the instant the speaker says a filler word (um, like, basically) or repeats themselves, interrupt them in under 8 words -- terse, immediate, clinical. Example: "There's an um." or "You just repeated that."

Rules for the group setting:
- Only correct the SPEAKER. Never interrupt a human mentor if one is talking -- you'll hear them as another voice in the channel; let them finish.
- Stay brief. You are one voice among several in this room, not the only one.
- If the speaker goes quiet for a while, do not fill the silence -- a human mentor may be about to speak.
- Never introduce yourself or explain what you're doing out loud."""


def create_live_router(settings: Settings, store: LiveRoomStore, agora: AgoraClient) -> APIRouter:
    router = APIRouter(prefix="/v1/live", tags=["interactive-live-streaming"])
    rate_limit = build_rate_limiter(settings)
    throttled = [Depends(rate_limit)]

    @router.post("/rooms", response_model=CreateLiveRoomResponse, dependencies=throttled)
    async def create_room(body: CreateLiveRoomRequest) -> CreateLiveRoomResponse:
        """Promotes a solo practice session into a group ILS room.

        This DOES create a new coach agent -- a fresh one, invited into
        the room's own LIVE_BROADCASTING channel, separate from any
        agent already coaching the speaker in their original solo
        CHANNEL_PROFILE_COMMUNICATION channel. The two are independent:
        going live does not stop or move the solo agent, it starts a
        second one so the same live coaching (filler-word interrupts)
        is audible to the mentor and audience joining the group channel,
        using a group-aware prompt (see _group_coach_system_prompt) that
        knows to defer to a human mentor's voice.
        """
        # If this same device (stable per-install rtc_uid, see
        # LiveRoomViewModel.stableRtcUidFor) already has an open room --
        # most often because a prior session's app was killed/crashed
        # without ever calling "Leave room" -- close that one out first.
        # Otherwise both rooms sit in the browse list simultaneously with
        # identical topics, which is confusing and, worse, leaves an
        # orphaned coach agent running in a room nobody can reach anymore.
        stale_room = await store.find_room_by_speaker(body.speaker_rtc_uid)
        if stale_room is not None:
            if stale_room.coach_agent_id is not None:
                try:
                    await agora.leave_agent(stale_room.coach_agent_id, stale_room.channel_name)
                except Exception:
                    pass
            await store.remove(stale_room.room_id)

        room_id = f"room-{int(time.time())}-{secrets.randbelow(900_000) + 100_000}"
        channel_name = f"filler-free-live-{room_id}"
        room = LiveRoom(
            room_id=room_id,
            channel_name=channel_name,
            speaker_rtc_uid=body.speaker_rtc_uid,
            topic_title=body.topic_title,
        )
        room.participants[body.speaker_rtc_uid] = LiveParticipant(
            rtc_uid=body.speaker_rtc_uid,
            display_name=body.speaker_display_name,
            role=LiveRole.SPEAKER,
        )
        await store.create(room)

        rtc_token, _ = agora.create_ils_rtc_token(channel_name, body.speaker_rtc_uid, publisher=True)

        # Best-effort: a room is still useful without the coach (mentor +
        # audience can still watch/co-host), so a failed agent invite
        # doesn't block room creation -- it just means coach_agent_active
        # stays false and the summary/state endpoints report that
        # honestly rather than the room silently failing to open.
        try:
            join_result = await agora.join_agent(
                channel_name=channel_name,
                requester_rtc_uid=body.speaker_rtc_uid,
                system_prompt=_group_coach_system_prompt(body.topic_title),
            )
            await store.set_coach_agent(room_id, join_result["agent_id"])
        except Exception:
            pass

        return CreateLiveRoomResponse(
            room_id=room_id,
            channel_name=channel_name,
            app_id=settings.agora_app_id,
            rtc_token=rtc_token,
            rtc_uid=body.speaker_rtc_uid,
            role=LiveRole.SPEAKER.value,
        )

    @router.post("/rooms/join", response_model=JoinLiveRoomResponse, dependencies=throttled)
    async def join_room(body: JoinLiveRoomRequest) -> JoinLiveRoomResponse:
        room = await _require_room(store, body.room_id)

        requested_role = LiveRole(body.requested_role)
        if requested_role == LiveRole.MENTOR and any(
            p.role == LiveRole.MENTOR for p in room.participants.values()
        ):
            raise HTTPException(status_code=409, detail="This room already has a mentor co-hosting.")

        publisher = requested_role in (LiveRole.SPEAKER, LiveRole.MENTOR)
        rtc_token, _ = agora.create_ils_rtc_token(room.channel_name, body.rtc_uid, publisher=publisher)

        updated = await store.add_participant(
            room.room_id,
            LiveParticipant(rtc_uid=body.rtc_uid, display_name=body.display_name, role=requested_role),
        )
        if updated is None:
            raise HTTPException(status_code=404, detail="Room was closed while joining.")

        return JoinLiveRoomResponse(
            room_id=room.room_id,
            channel_name=room.channel_name,
            app_id=settings.agora_app_id,
            rtc_token=rtc_token,
            rtc_uid=body.rtc_uid,
            role=requested_role.value,
            topic_title=room.topic_title,
            participant_count=updated.participant_count(),
        )

    @router.post("/rooms/leave", dependencies=throttled)
    async def leave_room(body: LeaveLiveRoomRequest) -> dict[str, bool]:
        room = await _require_room(store, body.room_id)
        if body.rtc_uid == room.speaker_rtc_uid:
            # The speaker leaving ends the group session entirely -- there's
            # nothing left to watch or co-host without them. Also stop the
            # room's coach agent so it doesn't keep running (and billing)
            # in an empty channel.
            if room.coach_agent_id is not None:
                try:
                    await agora.leave_agent(room.coach_agent_id, room.channel_name)
                except Exception:
                    pass
            await store.remove(room.room_id)
        else:
            await store.remove_participant(room.room_id, body.rtc_uid)
        return {"success": True}

    @router.post("/rooms/promote", response_model=LiveRoomStateResponse, dependencies=throttled)
    async def promote_participant(body: PromoteParticipantRequest) -> LiveRoomStateResponse:
        """Speaker-only: hand the mentor co-host slot to an audience member,
        or demote the current mentor back to audience. Mirrors the
        solo-mode `switch-role` pattern of leave-then-join for a single
        active slot, but here it's the *room's* single mentor slot rather
        than the coach's active persona.
        """
        room = await _require_room(store, body.room_id)
        if body.requester_rtc_uid != room.speaker_rtc_uid:
            raise HTTPException(status_code=403, detail="Only the speaker can promote or demote participants.")

        target = room.participants.get(body.target_rtc_uid)
        if target is None:
            raise HTTPException(status_code=404, detail="Target participant is not in this room.")

        new_role = LiveRole(body.new_role)
        if new_role == LiveRole.MENTOR:
            current_mentor = next(
                (p for p in room.participants.values() if p.role == LiveRole.MENTOR),
                None,
            )
            if current_mentor is not None and current_mentor.rtc_uid != body.target_rtc_uid:
                current_mentor.role = LiveRole.AUDIENCE
        target.role = new_role

        return _room_state(room)

    @router.get("/rooms/{room_id}", response_model=LiveRoomStateResponse, dependencies=throttled)
    async def room_state(room_id: str) -> LiveRoomStateResponse:
        room = await _require_room(store, room_id)
        return _room_state(room)

    @router.get("/rooms", response_model=ListLiveRoomsResponse, dependencies=throttled)
    async def list_open_rooms() -> ListLiveRoomsResponse:
        """Rooms with no mentor yet -- the "browse sessions to co-host" list
        a mentor-facing screen would show.
        """
        rooms = await store.list_open_rooms()
        return ListLiveRoomsResponse(rooms=[_room_state(room) for room in rooms])

    return router


async def _require_room(store: LiveRoomStore, room_id: str) -> LiveRoom:
    room = await store.get(room_id)
    if room is None:
        raise HTTPException(status_code=404, detail="Live room was not found or has ended.")
    return room


def _room_state(room: LiveRoom) -> LiveRoomStateResponse:
    return LiveRoomStateResponse(
        room_id=room.room_id,
        channel_name=room.channel_name,
        topic_title=room.topic_title,
        speaker_rtc_uid=room.speaker_rtc_uid,
        participants=[
            LiveParticipantInfo(rtc_uid=p.rtc_uid, display_name=p.display_name, role=p.role.value)
            for p in room.participants.values()
        ],
        coach_agent_active=room.coach_agent_id is not None,
    )
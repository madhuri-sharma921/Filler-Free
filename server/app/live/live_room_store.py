from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from enum import Enum


class LiveRole(str, Enum):
    """Agora RTC broadcaster/audience roles, as used by Interactive Live
    Streaming (channel profile LIVE_BROADCASTING) -- distinct from the
    solo coaching channel, which uses CHANNEL_PROFILE_COMMUNICATION and
    has no role concept at all (everyone there just publishes/subscribes
    equally). A live room's speaker and mentor both join as broadcasters
    (so both are heard); everyone else joins as audience (silent, watch
    + listen only, can be promoted).
    """

    SPEAKER = "speaker"  # the person actually practicing -- always a broadcaster
    MENTOR = "mentor"  # co-host -- a broadcaster, can speak/interject
    AUDIENCE = "audience"  # silent viewer


@dataclass
class LiveParticipant:
    rtc_uid: int
    display_name: str
    role: LiveRole
    joined_at_unix: float = field(default_factory=time.time)


@dataclass
class LiveRoom:
    room_id: str
    channel_name: str
    speaker_rtc_uid: int
    topic_title: str
    created_at_unix: float = field(default_factory=time.time)
    # Bumped on every request that touches this room (join, promote,
    # state read/poll). Used to hide abandoned rooms from the open-rooms
    # browse list -- see list_open_rooms -- without needing a real
    # heartbeat/disconnect mechanism, which the REST-only transport here
    # doesn't have. A speaker whose app crashed or was force-killed
    # without hitting "Leave room" (very common on a real device) leaves
    # a room with nobody polling it; last_seen_unix stops advancing, and
    # it eventually drops out of the browse list on its own.
    last_seen_unix: float = field(default_factory=time.time)
    participants: dict[int, LiveParticipant] = field(default_factory=dict)
    # Mirrors the solo session's single-active-coach invariant (see
    # server/app/session_store.py SessionRecord) -- the ConvoAI agent
    # is still only ever one instance per channel, coaching the speaker;
    # ILS only changes who else is in the channel around that agent.
    coach_agent_id: str | None = None

    def participant_count(self) -> int:
        return len(self.participants)


class LiveRoomStore:
    """Async-safe in-memory store, keyed by room_id.

    Same lifetime scope as SessionStore/LiveStatsStore: single-process,
    no persistence needed for a hackathon deployment.
    """

    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._rooms: dict[str, LiveRoom] = {}

    async def create(self, room: LiveRoom) -> LiveRoom:
        async with self._lock:
            self._rooms[room.room_id] = room
            return room

    async def get(self, room_id: str) -> LiveRoom | None:
        async with self._lock:
            room = self._rooms.get(room_id)
            if room is not None:
                room.last_seen_unix = time.time()
            return room

    async def add_participant(self, room_id: str, participant: LiveParticipant) -> LiveRoom | None:
        async with self._lock:
            room = self._rooms.get(room_id)
            if room is None:
                return None
            room.participants[participant.rtc_uid] = participant
            room.last_seen_unix = time.time()
            return room

    async def remove_participant(self, room_id: str, rtc_uid: int) -> LiveRoom | None:
        async with self._lock:
            room = self._rooms.get(room_id)
            if room is None:
                return None
            room.participants.pop(rtc_uid, None)
            room.last_seen_unix = time.time()
            return room

    async def set_coach_agent(self, room_id: str, agent_id: str | None) -> None:
        async with self._lock:
            room = self._rooms.get(room_id)
            if room is not None:
                room.coach_agent_id = agent_id

    async def list_open_rooms(self, max_age_seconds: float = 120.0) -> list[LiveRoom]:
        """Rooms with no mentor yet and recent activity -- what a mentor
        'browse rooms to join' view shows. A room nobody has polled or
        acted on in max_age_seconds is treated as abandoned and left out,
        rather than piling up forever -- see LiveRoom.last_seen_unix.

        120 seconds (not the half-hour a production deployment might
        want) because the client polls its own room roughly every 2.5s
        while anyone is actually in it (see LiveRoomViewModel's
        ROOM_POLL_INTERVAL_MS) -- a room with a live audience bumps
        last_seen_unix constantly and never goes stale under this
        window, while a room abandoned by a killed/crashed app (no
        "Leave room" call, so it's never explicitly removed) disappears
        from the browse list within about two minutes instead of
        lingering for the rest of a testing session.
        """
        async with self._lock:
            cutoff = time.time() - max_age_seconds
            return [
                room
                for room in self._rooms.values()
                if room.last_seen_unix >= cutoff
                and not any(p.role == LiveRole.MENTOR for p in room.participants.values())
            ]

    async def find_room_by_speaker(self, speaker_rtc_uid: int) -> LiveRoom | None:
        """Finds this device's own currently-open room, if any -- used to
        auto-close a room orphaned by a crashed/killed app (no explicit
        "Leave room" call) before opening a new one for the same speaker,
        rather than leaving two simultaneous "rooms" for the same person
        sitting in the browse list.
        """
        async with self._lock:
            for room in self._rooms.values():
                if room.speaker_rtc_uid == speaker_rtc_uid:
                    return room
            return None

    async def remove(self, room_id: str) -> None:
        async with self._lock:
            self._rooms.pop(room_id, None)
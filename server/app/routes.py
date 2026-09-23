from __future__ import annotations

import random
import secrets
import time
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request, status

from .agora_client import AgoraClient, AgoraTimeoutError, AgoraUpstreamError
from .config import Settings
from .schemas import (
    ActionResponse,
    ActiveCoachResponse,
    AgentActionRequest,
    BootstrapRequest,
    BootstrapResponse,
    CoachRoleInfo,
    HealthResponse,
    JoinRequest,
    JoinResponse,
    RefreshRequest,
    RefreshResponse,
    SwitchRoleRequest,
)
from .security import build_rate_limiter
from .session_store import SessionRecord, SessionStore


def create_router(settings: Settings, store: SessionStore, agora: AgoraClient) -> APIRouter:
    router = APIRouter()
    rate_limit = build_rate_limiter(settings)
    throttled = [Depends(rate_limit)]

    @router.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(
            status="ok",
            version=settings.build_version,
            agora_configured=bool(settings.agora_app_id and settings.agora_app_certificate),
            active_sessions=await store.count(),
        )

    @router.post(
        "/v1/conversation/bootstrap",
        response_model=BootstrapResponse,
        dependencies=throttled,
    )
    async def bootstrap(body: BootstrapRequest) -> BootstrapResponse:
        rtc_uid = body.requester_rtc_uid or random.randint(100_000, 899_999)
        rtm_user_id = body.requester_rtm_user_id or str(rtc_uid)
        if rtm_user_id != str(rtc_uid):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="requester_rtm_user_id must match requester_rtc_uid for the combined RTC/RTM token.",
            )
        channel = f"android-convoai-{int(time.time())}-{secrets.randbelow(900000) + 100000}"
        rtc_token, rtm_token, expires_at = agora.create_user_tokens(channel, rtc_uid)
        await store.put(
            SessionRecord(
                channel_name=channel,
                requester_rtc_uid=rtc_uid,
                requester_rtm_user_id=rtm_user_id,
                token_expires_at_unix=expires_at,
                created_at_unix=int(time.time()),
            )
        )
        return BootstrapResponse(
            app_id=settings.agora_app_id,
            agent_rtc_uid=settings.agent_uid,
            channel_name=channel,
            rtc_token=rtc_token,
            rtm_token=rtm_token,
            requester_rtc_uid=rtc_uid,
            requester_rtm_user_id=rtm_user_id,
            expires_at_unix=expires_at,
        )

    @router.post(
        "/v1/conversation/join",
        response_model=JoinResponse,
        dependencies=throttled,
    )
    async def join(body: JoinRequest) -> JoinResponse:
        async with store.join_guard(body.channel_name):
            record = await require_session(store, body.channel_name)
            if record.requester_rtc_uid != body.requester_rtc_uid:
                raise HTTPException(status_code=400, detail="Requester RTC UID does not match bootstrap.")

            existing_slot = record.agents.get(body.role)
            if existing_slot is not None:
                return JoinResponse(
                    agent_id=existing_slot.agent_id,
                    created_at_unix=record.created_at_unix,
                    status=existing_slot.agent_state,
                    role=body.role,
                    rtc_uid=existing_slot.rtc_uid,
                )

            agent_rtc_uid = settings.rtc_uid_for_role(body.role)
            result = await call_agora(
                agora.join_agent(
                    channel_name=body.channel_name,
                    requester_rtc_uid=body.requester_rtc_uid,
                    agent_profile=body.agent_profile,
                    system_prompt=body.system_prompt,
                    agent_rtc_uid=agent_rtc_uid,
                )
            )
            agent_id = str(result.get("agent_id", "")).strip()
            if not agent_id:
                raise HTTPException(status_code=502, detail="Agora response did not include agent_id.")
            created_at = int(result.get("create_ts") or time.time())
            agent_state = str(result.get("status") or "started")
            await store.set_agent(
                channel_name=body.channel_name,
                role=body.role,
                agent_id=agent_id,
                state=agent_state,
                rtc_uid=agent_rtc_uid,
            )
            return JoinResponse(
                agent_id=agent_id,
                created_at_unix=created_at,
                status=agent_state,
                role=body.role,
                rtc_uid=agent_rtc_uid,
            )

    @router.post(
        "/v1/conversation/switch-role",
        response_model=JoinResponse,
        dependencies=throttled,
    )
    async def switch_role(body: SwitchRoleRequest) -> JoinResponse:
        """Hands the "floor" to a different coach persona.

        The underlying ConvoAI SDK has no native mute/pause for a running
        agent (only start/stop/interrupt), so gated turn-taking between the
        3 coach personas is implemented honestly as leave-then-join: the
        currently active role's agent is stopped, then the requested role's
        agent is started (or resumed, if it already has a live slot from
        earlier in this session). Only one coach is ever actually speaking
        into the channel at a time — this is what avoids 3 overlapping
        audio streams.
        """
        async with store.join_guard(body.channel_name):
            record = await require_session(store, body.channel_name)
            if record.requester_rtc_uid != body.requester_rtc_uid:
                raise HTTPException(status_code=400, detail="Requester RTC UID does not match bootstrap.")

            if record.active_role == body.role and body.role in record.agents:
                slot = record.agents[body.role]
                return JoinResponse(
                    agent_id=slot.agent_id,
                    created_at_unix=record.created_at_unix,
                    status=slot.agent_state,
                    role=body.role,
                    rtc_uid=slot.rtc_uid,
                )

            # Leave whichever coach currently has the floor, if any.
            if record.active_role is not None:
                previous_slot = record.agents.get(record.active_role)
                if previous_slot is not None:
                    await call_agora(agora.leave_agent(previous_slot.agent_id, body.channel_name))
                    await store.remove_agent(body.channel_name, record.active_role)

            existing_slot = record.agents.get(body.role)
            if existing_slot is not None:
                # Role already has a live agent_id from earlier — re-activate
                # it as the floor-holder rather than starting a new session.
                await store.set_agent(
                    channel_name=body.channel_name,
                    role=body.role,
                    agent_id=existing_slot.agent_id,
                    state=existing_slot.agent_state,
                    rtc_uid=existing_slot.rtc_uid,
                )
                return JoinResponse(
                    agent_id=existing_slot.agent_id,
                    created_at_unix=record.created_at_unix,
                    status=existing_slot.agent_state,
                    role=body.role,
                    rtc_uid=existing_slot.rtc_uid,
                )

            agent_rtc_uid = settings.rtc_uid_for_role(body.role)
            result = await call_agora(
                agora.join_agent(
                    channel_name=body.channel_name,
                    requester_rtc_uid=body.requester_rtc_uid,
                    agent_profile=body.agent_profile,
                    system_prompt=body.system_prompt,
                    agent_rtc_uid=agent_rtc_uid,
                )
            )
            agent_id = str(result.get("agent_id", "")).strip()
            if not agent_id:
                raise HTTPException(status_code=502, detail="Agora response did not include agent_id.")
            created_at = int(result.get("create_ts") or time.time())
            agent_state = str(result.get("status") or "started")
            await store.set_agent(
                channel_name=body.channel_name,
                role=body.role,
                agent_id=agent_id,
                state=agent_state,
                rtc_uid=agent_rtc_uid,
            )
            return JoinResponse(
                agent_id=agent_id,
                created_at_unix=created_at,
                status=agent_state,
                role=body.role,
                rtc_uid=agent_rtc_uid,
            )

    @router.get(
        "/v1/conversation/{channel_name}/coaches",
        response_model=ActiveCoachResponse,
        dependencies=throttled,
    )
    async def active_coaches(channel_name: str) -> ActiveCoachResponse:
        record = await require_session(store, channel_name)
        return ActiveCoachResponse(
            active_role=record.active_role,
            roles=[
                CoachRoleInfo(
                    role=role,
                    agent_id=slot.agent_id,
                    rtc_uid=slot.rtc_uid,
                    agent_state=slot.agent_state,
                )
                for role, slot in record.agents.items()
            ],
        )

    @router.post(
        "/v1/conversation/interrupt",
        response_model=ActionResponse,
        dependencies=throttled,
    )
    async def interrupt(body: AgentActionRequest) -> ActionResponse:
        await require_agent(store, body)
        await call_agora(agora.interrupt_agent(body.agent_id, body.channel_name))
        return ActionResponse(success=True, message="Agent interrupted.")

    @router.post(
        "/v1/conversation/leave",
        response_model=ActionResponse,
        dependencies=throttled,
    )
    async def leave(body: AgentActionRequest) -> ActionResponse:
        record = await require_agent(store, body)
        await call_agora(agora.leave_agent(body.agent_id, body.channel_name))
        role = _role_for_agent(record, body.agent_id)
        if role is not None and len(record.agents) > 1:
            # Other coach roles still have live agents in this channel —
            # only drop this one role's slot, keep the session alive.
            await store.remove_agent(body.channel_name, role)
        else:
            # Last (or only) agent in the channel — tear down the whole
            # session, matching the original single-agent behavior.
            await store.remove(body.channel_name)
        return ActionResponse(success=True, message="Agent left the channel.")

    @router.post(
        "/v1/conversation/refresh",
        response_model=RefreshResponse,
        dependencies=throttled,
    )
    async def refresh(body: RefreshRequest) -> RefreshResponse:
        record = await require_session(store, body.channel_name)
        if (
            record.requester_rtc_uid != body.requester_rtc_uid
            or record.requester_rtm_user_id != body.requester_rtm_user_id
        ):
            raise HTTPException(status_code=400, detail="Refresh identity does not match bootstrap.")
        rtc_token, rtm_token, expires_at = agora.create_user_tokens(
            body.channel_name,
            body.requester_rtc_uid,
        )
        record.token_expires_at_unix = expires_at
        await store.put(record)
        return RefreshResponse(
            rtc_token=rtc_token,
            rtm_token=rtm_token,
            expires_at_unix=expires_at,
        )

    return router


async def require_session(store: SessionStore, channel_name: str) -> SessionRecord:
    record = await store.get(channel_name)
    if record is None:
        raise HTTPException(status_code=404, detail="Conversation session was not found or expired.")
    return record


async def require_agent(store: SessionStore, body: AgentActionRequest) -> SessionRecord:
    record = await require_session(store, body.channel_name)
    if _role_for_agent(record, body.agent_id) is None:
        raise HTTPException(status_code=400, detail="Agent ID does not match the conversation session.")
    return record


def _role_for_agent(record: SessionRecord, agent_id: str) -> str | None:
    for role, slot in record.agents.items():
        if slot.agent_id == agent_id:
            return role
    return None


async def call_agora(awaitable: Any) -> Any:
    try:
        return await awaitable
    except AgoraTimeoutError as exc:
        raise HTTPException(status_code=504, detail=str(exc)) from exc
    except AgoraUpstreamError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
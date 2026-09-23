from __future__ import annotations

import asyncio
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass, field


# Default/legacy role: the single coach that existed before multi-agent
# support. Requests that omit `role` (older clients, existing tests) are
# treated as this role, so nothing that talks to a single coach breaks.
DEFAULT_AGENT_ROLE = "delivery"


@dataclass
class AgentSlot:
    agent_id: str
    agent_state: str = "started"
    rtc_uid: int = 0


@dataclass
class SessionRecord:
    channel_name: str
    requester_rtc_uid: int
    requester_rtm_user_id: str
    token_expires_at_unix: int
    created_at_unix: int
    # Multiple coach "roles" can be active in the same channel over the
    # life of a session, but — since the underlying ConvoAI SDK has no
    # native mute/pause — only one is ever actually joined/running at a
    # time (see routes.py `join`/`switch_role`). Keyed by role name, e.g.
    # "delivery", "content", "energy".
    agents: dict[str, AgentSlot] = field(default_factory=dict)
    active_role: str | None = None

    # --- Legacy single-agent view, kept for backward compatibility with
    # any code/tests still reading record.agent_id / record.agent_state
    # directly (they mirror whichever slot is currently active). ---
    @property
    def agent_id(self) -> str | None:
        if self.active_role is None:
            return None
        slot = self.agents.get(self.active_role)
        return slot.agent_id if slot else None

    @property
    def agent_state(self) -> str:
        if self.active_role is None:
            return "bootstrapped"
        slot = self.agents.get(self.active_role)
        return slot.agent_state if slot else "bootstrapped"


class SessionStore:
    def __init__(self, ttl_seconds: int) -> None:
        self._ttl_seconds = ttl_seconds
        self._sessions: dict[str, SessionRecord] = {}
        self._lock = asyncio.Lock()
        self._join_locks: dict[str, asyncio.Lock] = {}

    async def put(self, record: SessionRecord) -> SessionRecord:
        async with self._lock:
            self._cleanup_locked()
            self._sessions[record.channel_name] = record
            return record

    async def get(self, channel_name: str) -> SessionRecord | None:
        async with self._lock:
            self._cleanup_locked()
            return self._sessions.get(channel_name)

    async def set_agent(
        self,
        channel_name: str,
        role: str,
        agent_id: str,
        state: str,
        rtc_uid: int = 0,
    ) -> SessionRecord:
        """Registers/updates the agent slot for `role` and marks it active.

        Since at most one coach is ever actually running at a time (see
        module docstring on SessionRecord.agents), joining a new role
        implicitly makes it the active one; it's the caller's job (see
        routes.py) to have already left the previous active role's agent
        before calling this for a different role.
        """
        async with self._lock:
            record = self._sessions[channel_name]
            record.agents[role] = AgentSlot(agent_id=agent_id, agent_state=state, rtc_uid=rtc_uid)
            record.active_role = role
            return record

    async def update_agent_state(self, channel_name: str, role: str, state: str) -> None:
        async with self._lock:
            record = self._sessions.get(channel_name)
            slot = record.agents.get(role) if record else None
            if slot is not None:
                slot.agent_state = state

    async def remove_agent(self, channel_name: str, role: str) -> None:
        async with self._lock:
            record = self._sessions.get(channel_name)
            if record is None:
                return
            record.agents.pop(role, None)
            if record.active_role == role:
                record.active_role = None

    async def remove(self, channel_name: str) -> None:
        async with self._lock:
            self._sessions.pop(channel_name, None)
            self._join_locks.pop(channel_name, None)

    async def count(self) -> int:
        async with self._lock:
            self._cleanup_locked()
            return len(self._sessions)

    @asynccontextmanager
    async def join_guard(self, channel_name: str):
        async with self._lock:
            lock = self._join_locks.setdefault(channel_name, asyncio.Lock())
        async with lock:
            yield

    def _cleanup_locked(self) -> None:
        cutoff = int(time.time()) - self._ttl_seconds
        expired = [
            channel
            for channel, record in self._sessions.items()
            if record.created_at_unix < cutoff
        ]
        for channel in expired:
            self._sessions.pop(channel, None)
            self._join_locks.pop(channel, None)
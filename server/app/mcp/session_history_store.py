from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field


@dataclass
class ServerSessionRecord:
    """Server-side mirror of the Android Room `SessionRecordEntity`.

    The app already persists full session history locally (Room, see
    fillerfree/history/data/SessionHistoryDatabase.kt) for the on-device
    Progress screen. This store exists for a different reader: the coach
    *agent itself*, running server-side, has no access to the phone's
    local database. Android reports each completed session here (best
    effort, non-blocking) purely so `get_session_history` has something
    real to read -- it is not a replacement for the on-device history.
    """

    topic_title: str
    completed_at_unix: int
    filler_count: int
    repetition_count: int
    top_offender: str | None = None
    stored_at_unix: float = field(default_factory=time.time)


class SessionHistoryStore:
    """Small in-memory ring buffer, most-recent-first, capped in size.

    Not persisted across server restarts -- that's fine, since the
    authoritative history lives on-device. This is a convenience cache
    for the agent's mid-conversation "have I seen this before" tool call.
    """

    def __init__(self, max_records: int = 200) -> None:
        self._lock = asyncio.Lock()
        self._records: list[ServerSessionRecord] = []
        self._max_records = max_records

    async def record(self, item: ServerSessionRecord) -> None:
        async with self._lock:
            self._records.insert(0, item)
            del self._records[self._max_records :]

    async def recent(self, limit: int = 5) -> list[ServerSessionRecord]:
        async with self._lock:
            return list(self._records[: max(0, limit)])

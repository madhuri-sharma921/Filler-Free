from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field


@dataclass
class LiveStats:
    """Rolling stats for one active channel, updated by the Android client.

    The client (FillerFreeViewModel / TranscriptAnalyticsRepositoryImpl)
    already computes these numbers locally for the on-screen scoreboard.
    This store just gives the *agent itself* (via the MCP tool below) a way
    to read the same numbers mid-conversation, e.g. to say "you're at six
    fillers, let's reset" without the app having to smuggle that into the
    transcript as text.
    """

    channel_name: str
    topic_title: str = ""
    filler_count: int = 0
    repetition_count: int = 0
    interruption_count: int = 0
    word_count: int = 0
    duration_ms: int = 0
    top_offender: str | None = None
    updated_at_unix: float = field(default_factory=time.time)


@dataclass
class QueuedPractice:
    channel_name: str
    topic_id: str
    topic_title: str
    reason: str | None = None
    queued_at_unix: float = field(default_factory=time.time)


class LiveStatsStore:
    """Shared, in-process, async-safe store keyed by channel_name.

    Same lifetime/scope assumption as SessionStore: single-process FastAPI
    server, good enough for a hackathon deployment. Nothing here is
    persisted to disk.
    """

    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._stats: dict[str, LiveStats] = {}
        self._queued_practice: dict[str, list[QueuedPractice]] = {}

    async def update_stats(self, stats: LiveStats) -> None:
        async with self._lock:
            stats.updated_at_unix = time.time()
            self._stats[stats.channel_name] = stats

    async def get_stats(self, channel_name: str) -> LiveStats | None:
        async with self._lock:
            return self._stats.get(channel_name)

    async def queue_practice(self, item: QueuedPractice) -> None:
        async with self._lock:
            self._queued_practice.setdefault(item.channel_name, []).append(item)

    async def pop_queued_practice(self, channel_name: str) -> QueuedPractice | None:
        async with self._lock:
            queue = self._queued_practice.get(channel_name)
            if not queue:
                return None
            return queue.pop(0)

    async def peek_queued_practice(self, channel_name: str) -> list[QueuedPractice]:
        async with self._lock:
            return list(self._queued_practice.get(channel_name, []))

    async def clear_channel(self, channel_name: str) -> None:
        async with self._lock:
            self._stats.pop(channel_name, None)
            self._queued_practice.pop(channel_name, None)

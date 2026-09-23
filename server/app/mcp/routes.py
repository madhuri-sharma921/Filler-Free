from __future__ import annotations

import time

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from ..config import Settings
from ..security import build_rate_limiter
from .live_stats_store import LiveStats, LiveStatsStore, QueuedPractice
from .session_history_store import ServerSessionRecord, SessionHistoryStore


class PushLiveStatsRequest(BaseModel):
    channel_name: str = Field(min_length=1, max_length=64)
    topic_title: str = ""
    filler_count: int = 0
    repetition_count: int = 0
    interruption_count: int = 0
    word_count: int = 0
    duration_ms: int = 0
    top_offender: str | None = None


class ReportSessionRequest(BaseModel):
    topic_title: str
    completed_at_unix: int
    filler_count: int
    repetition_count: int
    top_offender: str | None = None


class QueuedPracticeResponse(BaseModel):
    found: bool
    topic_id: str | None = None
    topic_title: str | None = None
    reason: str | None = None


class LiveStatsResponse(BaseModel):
    found: bool
    channel_name: str
    topic_title: str | None = None
    filler_count: int = 0
    repetition_count: int = 0
    interruption_count: int = 0
    word_count: int = 0
    duration_seconds: float = 0.0
    top_offender: str | None = None


def create_mcp_bridge_router(
    settings: Settings,
    live_stats: LiveStatsStore,
    session_history: SessionHistoryStore,
) -> APIRouter:
    """REST endpoints Android calls so the MCP tool server (see
    tool_server.py) has real data to read when the coach agent calls its
    tools mid-conversation. The app already computes these numbers for
    its own on-screen scoreboard (TranscriptAnalyticsRepositoryImpl) --
    this just mirrors the same numbers into the server, best-effort,
    fire-and-forget from the client's perspective.
    """
    router = APIRouter(prefix="/v1/coach-tools", tags=["mcp-bridge"])
    rate_limit = build_rate_limiter(settings)
    throttled = [Depends(rate_limit)]

    @router.post("/live-stats", dependencies=throttled)
    async def push_live_stats(body: PushLiveStatsRequest) -> dict[str, bool]:
        await live_stats.update_stats(
            LiveStats(
                channel_name=body.channel_name,
                topic_title=body.topic_title,
                filler_count=body.filler_count,
                repetition_count=body.repetition_count,
                interruption_count=body.interruption_count,
                word_count=body.word_count,
                duration_ms=body.duration_ms,
                top_offender=body.top_offender,
            )
        )
        return {"success": True}

    @router.get(
        "/live-stats/{channel_name}",
        response_model=LiveStatsResponse,
        dependencies=throttled,
    )
    async def read_live_stats(channel_name: str) -> LiveStatsResponse:
        """Read-only view of the same numbers get_live_stats hands the
        coach agent (see tool_server.py) -- lets anything outside the
        agent itself (the CLI's `start` command, a dashboard, a judge
        watching a terminal) see the live state the agent is reasoning
        over, without consuming or altering it the way pop_queued_practice
        does for practice suggestions.
        """
        stats = await live_stats.get_stats(channel_name)
        if stats is None:
            return LiveStatsResponse(found=False, channel_name=channel_name)
        return LiveStatsResponse(
            found=True,
            channel_name=stats.channel_name,
            topic_title=stats.topic_title,
            filler_count=stats.filler_count,
            repetition_count=stats.repetition_count,
            interruption_count=stats.interruption_count,
            word_count=stats.word_count,
            duration_seconds=round(stats.duration_ms / 1000, 1),
            top_offender=stats.top_offender,
        )

    @router.get(
        "/queued-practice/{channel_name}",
        response_model=QueuedPracticeResponse,
        dependencies=throttled,
    )
    async def pop_queued_practice(channel_name: str) -> QueuedPracticeResponse:
        """Called by the app on the session-summary screen to check whether
        the agent used `queue_practice_topic` mid-session (see
        tool_server.py) -- pops (consumes) the oldest queued item, if any.
        """
        item = await live_stats.pop_queued_practice(channel_name)
        if item is None:
            return QueuedPracticeResponse(found=False)
        return QueuedPracticeResponse(
            found=True,
            topic_id=item.topic_id,
            topic_title=item.topic_title,
            reason=item.reason,
        )

    @router.post("/report-session", dependencies=throttled)
    async def report_session(body: ReportSessionRequest) -> dict[str, bool]:
        await session_history.record(
            ServerSessionRecord(
                topic_title=body.topic_title,
                completed_at_unix=body.completed_at_unix,
                filler_count=body.filler_count,
                repetition_count=body.repetition_count,
                top_offender=body.top_offender,
            )
        )
        return {"success": True}

    return router
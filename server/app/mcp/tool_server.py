from __future__ import annotations

from typing import Any

from mcp.server.fastmcp import FastMCP

from .live_stats_store import LiveStatsStore, QueuedPractice
from .session_history_store import SessionHistoryStore


def build_mcp_tool_server(
    live_stats: LiveStatsStore,
    session_history: SessionHistoryStore,
) -> FastMCP:
    """Builds the MCP server the coach agent calls mid-conversation.

    This is what Agora's Conversational AI Engine means by "MCP
    tool-calling": the LLM decides mid-turn that it needs a fact it
    doesn't have (the running filler count, whether this habit repeated
    last time) and calls one of these tools instead of guessing or asking
    the user to repeat themselves. See CoachAgentPromptBuilder for the
    system-prompt language that tells the agent these tools exist and
    when to reach for them.

    Stateless HTTP is the right mode here: every call from Agora's engine
    is an independent HTTP request carrying its own `channel_name`, and
    there's no per-connection session state the MCP layer itself needs to
    hold -- LiveStatsStore/SessionHistoryStore already own that, keyed by
    channel, independent of any one MCP connection's lifetime.
    """
    mcp = FastMCP(
        "filler-free-coach-tools",
        stateless_http=True,
        json_response=True,
        # This FastMCP instance's ASGI app is mounted at /mcp by main.py
        # (application.mount("/mcp", mcp_tool_server.streamable_http_app())).
        # streamable_http_path defaults to "/mcp" *within* that app, which
        # would put the real endpoint at /mcp/mcp -- setting it to "/"
        # here means the mounted app itself IS the endpoint, so the final
        # public URL is exactly <base_url>/mcp as documented in config.py.
        streamable_http_path="/",
    )

    @mcp.tool(
        description=(
            "Get the user's live speech-coaching stats for the current session: "
            "filler word count, repetition count, interruption count, word count, "
            "elapsed duration, and their single most frequent habit so far. Call "
            "this when you want to reference a specific number instead of "
            "guessing, e.g. before saying something like 'that's your sixth um' "
            "or deciding whether the session has gone on long enough to wrap up."
        )
    )
    async def get_live_stats(channel_name: str) -> dict[str, Any]:
        stats = await live_stats.get_stats(channel_name)
        if stats is None:
            return {"found": False, "channel_name": channel_name}
        return {
            "found": True,
            "channel_name": stats.channel_name,
            "topic_title": stats.topic_title,
            "filler_count": stats.filler_count,
            "repetition_count": stats.repetition_count,
            "interruption_count": stats.interruption_count,
            "word_count": stats.word_count,
            "duration_seconds": round(stats.duration_ms / 1000, 1),
            "top_offender": stats.top_offender,
        }

    @mcp.tool(
        description=(
            "Queue a follow-up practice topic for the user to run immediately "
            "after this session ends, e.g. because they struggled with a "
            "specific habit and a short, targeted redo would help. The app "
            "surfaces this as a suggested 'Try again: <topic>' action on the "
            "session summary screen. topic_id must be one of: "
            "'explain_bug', 'explain_project', 'interview_answer', 'free_talk'. "
            "reason is a short (under 15 words) note shown to the user explaining why."
        )
    )
    async def queue_practice_topic(
        channel_name: str,
        topic_id: str,
        topic_title: str,
        reason: str | None = None,
    ) -> dict[str, Any]:
        await live_stats.queue_practice(
            QueuedPractice(
                channel_name=channel_name,
                topic_id=topic_id,
                topic_title=topic_title,
                reason=reason,
            )
        )
        return {"queued": True, "topic_id": topic_id, "topic_title": topic_title}

    @mcp.tool(
        description=(
            "Get a short summary of the user's most recent past coaching "
            "sessions (topic, date, filler count, top recurring habit). Call "
            "this at the start of a session, or when deciding whether to call "
            "out a habit as 'recurring', to check whether it's actually shown "
            "up before rather than assuming."
        )
    )
    async def get_session_history(limit: int = 5) -> dict[str, Any]:
        records = await session_history.recent(limit=limit)
        return {
            "count": len(records),
            "sessions": [
                {
                    "topic_title": r.topic_title,
                    "completed_at_unix": r.completed_at_unix,
                    "filler_count": r.filler_count,
                    "repetition_count": r.repetition_count,
                    "top_offender": r.top_offender,
                }
                for r in records
            ],
        }

    return mcp

package com.androidengineers.agent_quickstart_android.fillerfree.domain.model

/**
 * A single detected speech issue during a live session.
 * Emitted by [com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase.AnalyzeTranscriptUseCase]
 * as transcript chunks stream in from Agora RTM.
 *
 * [latencyMs] is only meaningful for [SpeechEventType.AGENT_INTERRUPTION]:
 * it is the elapsed time between the user's last transcript chunk and the
 * agent's interrupting chunk arriving — i.e. how fast the coach cut in.
 * Null for FILLER_WORD / REPETITION, or if no prior user chunk was seen yet.
 */
data class SpeechEvent(
    val id: String,
    val type: SpeechEventType,
    val text: String,
    val timestampMs: Long,
    val latencyMs: Long? = null,
)

enum class SpeechEventType {
    FILLER_WORD,
    REPETITION,
    AGENT_INTERRUPTION,
}

/**
 * Aggregated live stats shown in the UI while a session is active.
 *
 * [lastInterruptionLatencyMs] tracks the most recent AGENT_INTERRUPTION's
 * cut-in speed, so the UI can show a live "Cut in: 210ms" style readout
 * without re-deriving it from the event list every recomposition.
 */
data class SessionStats(
    val fillerCount: Int = 0,
    val repetitionCount: Int = 0,
    val interruptionCount: Int = 0,
    val wordCount: Int = 0,
    val durationMs: Long = 0L,
    val lastInterruptionLatencyMs: Long? = null,
) {
    val totalIssues: Int get() = fillerCount + repetitionCount

    fun issuesPerMinute(): Double {
        if (durationMs <= 0) return 0.0
        val minutes = durationMs / 60_000.0
        return if (minutes > 0) totalIssues / minutes else 0.0
    }
}

/**
 * Final summary shown on the end-of-session card.
 *
 * [avgInterruptLatencyMs] is the mean of every real, measured barge-in
 * latency this session (user starts speaking over the agent -> agent
 * actually stops), sourced from AgoraConversationSessionManager. Null if
 * no interruption happened this session.
 */
data class SessionSummary(
    val stats: SessionStats,
    val topOffender: String?,
    val closingTip: String,
    val avgInterruptLatencyMs: Long? = null,
)
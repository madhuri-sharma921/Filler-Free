package com.androidengineers.agent_quickstart_android.fillerfree.domain.model

/** One completed session, as recalled from local history (see SessionHistoryRepository). */
data class PastSessionRecord(
    val completedAtEpochMs: Long,
    val topicId: String,
    val stats: SessionStats,
    val avgInterruptLatencyMs: Long?,
    val topOffender: String?,
)

/**
 * Trend summary across the last N sessions, computed by
 * [com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase.BuildDailyProgressUseCase].
 *
 * [issuesPerMinuteTrend] is oldest-first, one point per session, for a
 * simple sparkline. Deltas compare the most recent session against the
 * average of all prior sessions in the window, not just session-to-session,
 * so a single unusually good/bad session doesn't dominate the read.
 */
data class DailyProgress(
    val sessionCount: Int,
    val issuesPerMinuteTrend: List<Double>,
    val mostCommonFillerWord: String?,
    val latestIssuesPerMinute: Double?,
    val priorAverageIssuesPerMinute: Double?,
    val latestAvgInterruptLatencyMs: Long?,
) {
    /** Positive = improving (fewer issues/min than before). Null if there's no prior baseline yet. */
    val issuesPerMinuteImprovement: Double?
        get() {
            val latest = latestIssuesPerMinute ?: return null
            val prior = priorAverageIssuesPerMinute ?: return null
            return prior - latest
        }
}
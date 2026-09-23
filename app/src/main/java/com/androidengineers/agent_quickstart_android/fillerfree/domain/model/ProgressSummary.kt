package com.androidengineers.agent_quickstart_android.fillerfree.domain.model

enum class TrendDirection { IMPROVING, WORSENING, STEADY, NOT_ENOUGH_DATA }

/**
 * One metric's read across recent sessions: current value (most recent
 * session), and how it's trending relative to the session(s) before it.
 */
data class MetricTrend(
    val label: String,
    val latestValue: Double,
    val direction: TrendDirection,
    val changeDescription: String,
)

/**
 * Cross-session progress read, built by [com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase.BuildProgressSummaryUseCase]
 * from recent [SessionRecord]s. This is what makes the app feel like a
 * coaching relationship instead of a series of disconnected sessions.
 */
data class ProgressSummary(
    val totalSessions: Int,
    val currentStreakDays: Int,
    val issuesPerMinuteTrend: MetricTrend,
    val interruptionsPerMinuteTrend: MetricTrend,
    val recurringHabit: String?,
    val headline: String,
)
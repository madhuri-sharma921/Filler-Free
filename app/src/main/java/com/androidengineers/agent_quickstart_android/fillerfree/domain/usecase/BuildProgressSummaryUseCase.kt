package com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase

import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.MetricTrend
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.ProgressSummary
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionRecord
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.TrendDirection
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure function: no Room/Android dependencies, fully unit testable. Takes
 * session history ordered most-recent-first (matching
 * [com.androidengineers.agent_quickstart_android.fillerfree.domain.repository.SessionHistoryRepository.recentSessions])
 * and builds the cross-session read shown on the progress screen.
 *
 * Trend rule: compares the most recent session's value against the average
 * of the rest of the window. This is intentionally simple (no linear
 * regression, no smoothing) — with typically a handful to a few dozen
 * sessions, a more sophisticated trend model would be fitting noise, not
 * signal.
 */
class BuildProgressSummaryUseCase(
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {

    operator fun invoke(recentFirst: List<SessionRecord>): ProgressSummary {
        val totalSessions = recentFirst.size
        val streak = currentStreakDays(recentFirst)

        val issuesPerMinuteTrend = buildTrend(
            label = "Issues per minute",
            values = recentFirst.map { it.issuesPerMinute() },
            lowerIsBetter = true,
        )
        val interruptionsPerMinuteTrend = buildTrend(
            label = "Cut-ins per minute",
            values = recentFirst.map { rateFor(it.interruptionCount, it.durationMs) },
            lowerIsBetter = true,
        )

        val recurringHabit = recentFirst
            .take(RECURRING_HABIT_WINDOW)
            .mapNotNull { it.topOffender }
            .groupingBy { it }
            .eachCount()
            .filterValues { it >= RECURRING_HABIT_MIN_OCCURRENCES }
            .maxByOrNull { it.value }
            ?.key

        val headline = buildHeadline(totalSessions, streak, issuesPerMinuteTrend, recurringHabit)

        return ProgressSummary(
            totalSessions = totalSessions,
            currentStreakDays = streak,
            issuesPerMinuteTrend = issuesPerMinuteTrend,
            interruptionsPerMinuteTrend = interruptionsPerMinuteTrend,
            recurringHabit = recurringHabit,
            headline = headline,
        )
    }

    private fun rateFor(count: Int, durationMs: Long): Double {
        if (durationMs <= 0) return 0.0
        val minutes = durationMs / 60_000.0
        return if (minutes > 0) count / minutes else 0.0
    }

    private fun buildTrend(label: String, values: List<Double>, lowerIsBetter: Boolean): MetricTrend {
        if (values.isEmpty()) {
            return MetricTrend(label, 0.0, TrendDirection.NOT_ENOUGH_DATA, "No sessions yet.")
        }
        val latest = values.first()
        if (values.size < MIN_SESSIONS_FOR_TREND) {
            return MetricTrend(label, latest, TrendDirection.NOT_ENOUGH_DATA, "One more session unlocks a trend read.")
        }

        val priorAverage = values.drop(1).average()
        val delta = latest - priorAverage
        val improved = if (lowerIsBetter) delta < 0 else delta > 0
        val percentChange = if (priorAverage != 0.0) kotlin.math.abs(delta / priorAverage) * 100 else 0.0

        val direction = when {
            kotlin.math.abs(delta) < STEADY_THRESHOLD -> TrendDirection.STEADY
            improved -> TrendDirection.IMPROVING
            else -> TrendDirection.WORSENING
        }

        val description = when (direction) {
            TrendDirection.IMPROVING -> "Down ${"%.0f".format(percentChange)}% from your recent average — trending the right way."
            TrendDirection.WORSENING -> "Up ${"%.0f".format(percentChange)}% from your recent average."
            TrendDirection.STEADY -> "Holding steady with your recent sessions."
            TrendDirection.NOT_ENOUGH_DATA -> "Not enough sessions yet."
        }

        return MetricTrend(label, latest, direction, description)
    }

    /** Consecutive calendar days, most-recent-first, with at least one session each. */
    private fun currentStreakDays(recentFirst: List<SessionRecord>): Int {
        if (recentFirst.isEmpty()) return 0

        val distinctDays = recentFirst
            .map { dayKey(it.completedAtMs) }
            .distinct()
            .sortedDescending()

        var streak = 1
        var cursor = distinctDays.first()
        for (day in distinctDays.drop(1)) {
            val expectedPrevious = cursor - 1
            if (day == expectedPrevious) {
                streak++
                cursor = day
            } else {
                break
            }
        }

        // If the most recent session wasn't today or yesterday, the streak is broken.
        val today = dayKey(System.currentTimeMillis())
        return if (distinctDays.first() < today - 1) 0 else streak
    }

    private fun dayKey(epochMs: Long): Long {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = epochMs }
        val year = calendar.get(Calendar.YEAR)
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        return year * 1000L + dayOfYear
    }

    private fun buildHeadline(
        totalSessions: Int,
        streakDays: Int,
        issuesPerMinuteTrend: MetricTrend,
        recurringHabit: String?,
    ): String = when {
        totalSessions == 0 -> "Finish your first session to start tracking progress."
        issuesPerMinuteTrend.direction == TrendDirection.IMPROVING && streakDays >= 2 ->
            "$streakDays-day streak, and your issues-per-minute is dropping. Keep going."
        issuesPerMinuteTrend.direction == TrendDirection.IMPROVING ->
            "Your issues-per-minute is dropping session over session."
        recurringHabit != null ->
            "\"$recurringHabit\" keeps showing up across recent sessions — that's the one to target."
        streakDays >= 2 -> "$streakDays-day streak. Consistency is the whole game."
        else -> "$totalSessions session(s) logged. Keep practicing to build a trend."
    }

    companion object {
        private const val MIN_SESSIONS_FOR_TREND = 2
        private const val STEADY_THRESHOLD = 0.15
        private const val RECURRING_HABIT_WINDOW = 5
        private const val RECURRING_HABIT_MIN_OCCURRENCES = 2
    }
}
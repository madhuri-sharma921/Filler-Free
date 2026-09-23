package com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase

import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionStats
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionSummary
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechEvent
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechEventType

/**
 * Pure use case that turns the raw event history + stats into a short,
 * human-readable closing summary for the session-end card.
 */
class BuildSessionSummaryUseCase {

    operator fun invoke(
        stats: SessionStats,
        events: List<SpeechEvent>,
        avgInterruptLatencyMs: Long? = null,
    ): SessionSummary {
        val topOffender = events
            .filter { it.type == SpeechEventType.FILLER_WORD }
            .groupingBy { it.text }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        val tip = when {
            stats.totalIssues == 0 && stats.wordCount > 20 ->
                "Clean run — no fillers or repeats caught. That's a strong, tight explanation."

            stats.interruptionCount == 0 ->
                "No interruptions needed. Try a harder topic next round to push yourself."

            avgInterruptLatencyMs != null && avgInterruptLatencyMs <= FAST_INTERRUPT_LATENCY_MS ->
                "Your reactions were sharp — you cut in around ${avgInterruptLatencyMs}ms on average. " +
                        "That's a confident, decisive pace."

            topOffender != null && stats.fillerCount >= 3 ->
                "Your top habit: \"$topOffender\", used $${stats.fillerCount} time(s). " +
                        "Try replacing it with a short pause instead."

            stats.repetitionCount > 0 ->
                "You circled back to the same point a few times. Try stating it once, then stop."

            else ->
                "Solid attempt. Aim to cut your explanation down by a third next time."
        }

        return SessionSummary(
            stats = stats,
            topOffender = topOffender,
            closingTip = tip,
            avgInterruptLatencyMs = avgInterruptLatencyMs,
        )
    }

    companion object {
        private const val FAST_INTERRUPT_LATENCY_MS = 400L
    }
}
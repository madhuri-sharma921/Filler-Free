package com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase

import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.EmotionLabel
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.EmotionSignal

/**
 * Pure function, same style as AnalyzeTranscriptUseCase: no Android/Agora
 * dependencies, fully unit testable. Derives a coarse emotion label from
 * timing + filler-density signals already available from the transcript
 * stream (word counts per chunk, chunk timestamps, recent filler counts).
 *
 * This is a rules engine on purpose, not an ML model — cheap, explainable,
 * and good enough for real-time coaching feedback.
 */
class DetectEmotionSignalUseCase {

    private data class ChunkRecord(val wordCount: Int, val timestampMs: Long, val fillerCount: Int)

    private val recentChunks = ArrayDeque<ChunkRecord>()

    operator fun invoke(
        chunkText: String,
        chunkFillerCount: Int,
        timestampMs: Long,
    ): EmotionSignal {
        val wordCount = chunkText.split(Regex("\\s+")).count { it.isNotBlank() }
        recentChunks.addLast(ChunkRecord(wordCount, timestampMs, chunkFillerCount))
        while (recentChunks.size > MAX_WINDOW) recentChunks.removeFirst()

        val windowStartMs = recentChunks.first().timestampMs
        val windowDurationMs = (timestampMs - windowStartMs).coerceAtLeast(1L)
        val totalWords = recentChunks.sumOf { it.wordCount }
        val wpm = (totalWords.toDouble() / windowDurationMs) * 60_000.0

        val pauses = recentChunks.zipWithNext { a, b -> b.timestampMs - a.timestampMs }
        val avgPauseMs = if (pauses.isNotEmpty()) pauses.average().toLong() else 0L

        val half = recentChunks.size / 2
        val fillerTrend = if (recentChunks.size >= 4) {
            val firstHalfFillers = recentChunks.take(half).sumOf { it.fillerCount }
            val secondHalfFillers = recentChunks.takeLast(recentChunks.size - half).sumOf { it.fillerCount }
            (secondHalfFillers - firstHalfFillers).toDouble()
        } else {
            0.0
        }

        val label = classify(wpm, fillerTrend, avgPauseMs)

        return EmotionSignal(
            label = label,
            wordsPerMinute = wpm,
            fillerTrend = fillerTrend,
            avgPauseMs = avgPauseMs,
            computedAtMs = timestampMs,
        )
    }

    fun reset() {
        recentChunks.clear()
    }

    private fun classify(wpm: Double, fillerTrend: Double, avgPauseMs: Long): EmotionLabel {
        if (recentChunks.size < MIN_SAMPLES_FOR_LABEL) return EmotionLabel.UNKNOWN
        return when {
            wpm > NERVOUS_WPM_THRESHOLD && fillerTrend > 0 -> EmotionLabel.NERVOUS
            wpm > EXCITED_WPM_THRESHOLD && fillerTrend <= 0 -> EmotionLabel.EXCITED
            avgPauseMs > FRUSTRATED_PAUSE_MS_THRESHOLD && fillerTrend > 0 -> EmotionLabel.FRUSTRATED
            wpm in CONFIDENT_WPM_RANGE && fillerTrend <= 0 -> EmotionLabel.CONFIDENT
            else -> EmotionLabel.FLAT
        }
    }

    companion object {
        private const val MAX_WINDOW = 8 // Reduced from 12 for faster response
        private const val MIN_SAMPLES_FOR_LABEL = 2 // Reduced from 4 for faster initial read

        private const val NERVOUS_WPM_THRESHOLD = 160.0 // Slightly lower for easier detection
        private const val EXCITED_WPM_THRESHOLD = 150.0 // Slightly lower for easier detection
        private const val FRUSTRATED_PAUSE_MS_THRESHOLD = 2_500L // Lowered from 3.5s
        private val CONFIDENT_WPM_RANGE = 100.0..140.0 // Slightly wider range
    }
}
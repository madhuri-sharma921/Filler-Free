package com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase

import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechEvent
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechEventType
import java.util.UUID

/**
 * Pure function-style use case: takes a raw transcript chunk and the recent
 * history, returns any newly detected [SpeechEvent]s. No Android/Agora
 * dependencies here on purpose — fully unit testable.
 *
 * Note: `lastSpokenWord` below is small mutable instance state used purely
 * for immediate word-echo detection (e.g. "Yes. Yes." / "No. No."). This is
 * a deliberate, minimal compromise on the "pure function" doc comment above
 * — if you need this to stay fully stateless, move `lastSpokenWord` into
 * the caller (TranscriptAnalyticsRepositoryImpl) and pass it in/out instead.
 */
class AnalyzeTranscriptUseCase(
    private val fillerWords: Set<String> = DEFAULT_FILLER_WORDS,
) {

    private var lastSpokenWord: String? = null

    /**
     * @param newText the latest chunk of user speech transcript
     * @param recentSentences last few sentences already spoken, oldest first,
     *   used to detect repetition (naive substring/overlap check)
     * @param timestampMs when this chunk arrived
     */
    operator fun invoke(
        newText: String,
        recentSentences: List<String>,
        timestampMs: Long,
    ): List<SpeechEvent> {
        if (newText.isBlank()) return emptyList()

        val events = mutableListOf<SpeechEvent>()
        val normalized = newText.lowercase().trim()
        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }

        // Filler word detection: count occurrences of known filler tokens.
        words.forEach { word ->
            val cleaned = word.trim(',', '.', '!', '?')
            if (cleaned in fillerWords) {
                events += SpeechEvent(
                    id = UUID.randomUUID().toString(),
                    type = SpeechEventType.FILLER_WORD,
                    text = cleaned,
                    timestampMs = timestampMs,
                )
            }
        }

        // Ratio-based repetition detection: naive word-overlap ratio against
        // recent buffered sentences. Good for longer repeated phrases.
        val newWordSet = words.toSet()
        if (newWordSet.size >= 2) {
            for (prior in recentSentences.takeLast(3)) {
                val priorWords = prior.lowercase()
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .toSet()
                if (priorWords.isEmpty()) continue

                val overlap = newWordSet.intersect(priorWords).size
                val overlapRatio = overlap.toDouble() / newWordSet.size
                if (overlapRatio >= REPETITION_OVERLAP_THRESHOLD) {
                    events += SpeechEvent(
                        id = UUID.randomUUID().toString(),
                        type = SpeechEventType.REPETITION,
                        text = newText,
                        timestampMs = timestampMs,
                    )
                    break
                }
            }
        }

        // Exact-match short repeat check against buffered sentences (catches
        // "exception... exception" style echoes across buffer flushes).
        if (recentSentences.isNotEmpty()) {
            val recentEntries = recentSentences.takeLast(3).map { it.lowercase().trim() }
            if (normalized.isNotBlank() && normalized in recentEntries) {
                events += SpeechEvent(
                    id = UUID.randomUUID().toString(),
                    type = SpeechEventType.REPETITION,
                    text = newText,
                    timestampMs = timestampMs,
                )
            }
        }

        // Immediate word-echo detection: catches short back-to-back repeats
        // like "Yes. Yes." or "No. No." regardless of buffer/flush timing,
        // by comparing each incoming word directly against the last word seen.
        val cleanedWords = words.map { it.trim(',', '.', '!', '?') }.filter { it.isNotBlank() }
        for (word in cleanedWords) {
            if (word.length > 1 && word == lastSpokenWord) {
                events += SpeechEvent(
                    id = UUID.randomUUID().toString(),
                    type = SpeechEventType.REPETITION,
                    text = word,
                    timestampMs = timestampMs,
                )
            }
            lastSpokenWord = word
        }

        return events
    }

    companion object {
        private const val REPETITION_OVERLAP_THRESHOLD = 0.7

        val DEFAULT_FILLER_WORDS = setOf(
            "um", "umm", "uh", "uhh", "like", "basically", "actually",
            "literally", "so", "kind", "sort", "you know", "i mean",
            "right", "okay so", "yeah so",
        )
    }
}
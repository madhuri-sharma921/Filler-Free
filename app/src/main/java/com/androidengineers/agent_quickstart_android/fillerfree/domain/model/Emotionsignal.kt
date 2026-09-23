package com.androidengineers.agent_quickstart_android.fillerfree.domain.model


/**
 * A coarse, text-derived proxy for the user's speaking energy. This is NOT
 * audio prosody analysis (no pitch/tone) — it's arithmetic on timing and
 * filler-density signals we already have from the transcript stream. Good
 * enough to feel real in a demo; upgrade path is raw-audio prosody later.
 */
enum class EmotionLabel {
    CONFIDENT,
    NERVOUS,
    EXCITED,
    FRUSTRATED,
    FLAT,
    UNKNOWN,
}

data class EmotionSignal(
    val label: EmotionLabel,
    val wordsPerMinute: Double,
    val fillerTrend: Double,      // positive = fillers increasing recently, negative = decreasing
    val avgPauseMs: Long,
    val computedAtMs: Long,
)
package com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase

import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.EmotionLabel
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.VoiceProsodySample
import kotlin.math.sqrt

/**
 * Pure function, same style as [DetectEmotionSignalUseCase]: no Android/
 * Agora dependencies, fully unit testable. Classifies a rolling window of
 * [VoiceProsodySample]s (raw mic loudness + pitch, no transcript involved)
 * into a coarse [EmotionLabel].
 *
 * This is the real-audio counterpart to the text-timing heuristic: pitch
 * variance and loudness are the actual acoustic correlates of vocal energy
 * and tension, not a proxy computed from how fast words arrive. Still a
 * rules engine on purpose — cheap, explainable, real-time — not an ML model.
 *
 * Rough acoustic reasoning behind the thresholds (typical for a phone mic
 * at conversational distance, 16kHz+ PCM):
 * - Nervous speech tends to run louder AND more pitch-variable (voice
 *   "wobbles" under tension) than a person's own recent baseline.
 * - Confident/flat speech is steady in both loudness and pitch.
 * - Excited speech is loud with a raised, more variable pitch, but without
 *   the erratic short-window jitter nervous speech shows.
 * - Long stretches of near-silence (very low energy, no detected pitch)
 *   read as hesitation/frustration, not "quiet and confident".
 */
class DetectVoiceEmotionUseCase {

    private val recentSamples = ArrayDeque<VoiceProsodySample>()

    operator fun invoke(sample: VoiceProsodySample): EmotionLabel {
        recentSamples.addLast(sample)
        while (recentSamples.size > MAX_WINDOW) recentSamples.removeFirst()
        if (recentSamples.size < MIN_SAMPLES_FOR_LABEL) return EmotionLabel.UNKNOWN

        val voicedSamples = recentSamples.filter { it.pitchHz != null }
        val energyValues = recentSamples.map { it.rmsEnergy }
        val meanEnergy = energyValues.average()

        // Sustained near-silence: not enough signal to say anything about
        // tone, but a long quiet stretch mid-explanation reads as hesitation.
        if (meanEnergy < SILENCE_ENERGY_THRESHOLD) {
            val silentFraction = recentSamples.count { it.rmsEnergy < SILENCE_ENERGY_THRESHOLD }
                .toDouble() / recentSamples.size
            return if (silentFraction > SUSTAINED_SILENCE_FRACTION) EmotionLabel.FRUSTRATED else EmotionLabel.UNKNOWN
        }

        if (voicedSamples.size < MIN_VOICED_FOR_PITCH_READ) return EmotionLabel.UNKNOWN

        val pitches = voicedSamples.mapNotNull { it.pitchHz }
        val meanPitch = pitches.average()
        val pitchStdDev = sqrt(pitches.sumOf { (it - meanPitch) * (it - meanPitch) } / pitches.size)
        // Coefficient of variation: pitch spread relative to the person's own
        // pitch level, so this works across different natural voice registers
        // instead of assuming one fixed Hz range for everyone.
        val pitchVariationRatio = if (meanPitch > 0) pitchStdDev / meanPitch else 0.0

        val loudRelativeToWindow = meanEnergy > LOUD_ENERGY_THRESHOLD

        return when {
            pitchVariationRatio > NERVOUS_PITCH_VARIATION && loudRelativeToWindow -> EmotionLabel.NERVOUS
            pitchVariationRatio > NERVOUS_PITCH_VARIATION -> EmotionLabel.NERVOUS
            loudRelativeToWindow && pitchVariationRatio > EXCITED_PITCH_VARIATION -> EmotionLabel.EXCITED
            pitchVariationRatio < CONFIDENT_PITCH_VARIATION && !loudRelativeToWindow -> EmotionLabel.CONFIDENT
            else -> EmotionLabel.FLAT
        }
    }

    fun reset() {
        recentSamples.clear()
    }

    companion object {
        private const val MAX_WINDOW = 12 // Reduced from 20 for faster response (~3.6s)
        private const val MIN_SAMPLES_FOR_LABEL = 3 // Reduced from 6 for faster initial read
        private const val MIN_VOICED_FOR_PITCH_READ = 2 // Reduced from 4

        private const val SILENCE_ENERGY_THRESHOLD = 300.0 // Lowered for better sensitivity
        private const val SUSTAINED_SILENCE_FRACTION = 0.6 // Lowered from 0.7
        private const val LOUD_ENERGY_THRESHOLD = 3_000.0 // Lowered from 3.5k

        private const val NERVOUS_PITCH_VARIATION = 0.15 // Lowered from 0.18
        private const val EXCITED_PITCH_VARIATION = 0.08 // Lowered from 0.10
        private const val CONFIDENT_PITCH_VARIATION = 0.08 // Raised from 0.07
    }
}
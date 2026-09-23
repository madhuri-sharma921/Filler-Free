package com.androidengineers.agent_quickstart_android.fillerfree.domain.model

/**
 * One short-window (~300ms) read of raw mic prosody: loudness and pitch,
 * computed directly from PCM samples — no transcript/STT involved. This is
 * the actual acoustic signal behind "sounds nervous/confident/flat", as
 * opposed to [EmotionSignal]'s word-timing proxy.
 *
 * @param rmsEnergy root-mean-square amplitude of the window, roughly
 *   proportional to loudness. Typical speech at a normal phone-call
 *   distance lands somewhere in the low thousands out of a 16-bit range;
 *   silence/background noise sits much lower.
 * @param pitchHz estimated fundamental frequency (F0) in Hz for this
 *   window, or null if no clear pitch was found (silence, unvoiced
 *   consonants, or noise — autocorrelation pitch detection is unreliable
 *   on non-periodic audio, so we report "no pitch" rather than guess).
 */
data class VoiceProsodySample(
    val rmsEnergy: Double,
    val pitchHz: Double?,
    val timestampMs: Long,
)
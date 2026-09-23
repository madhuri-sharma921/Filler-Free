package com.androidengineers.agent_quickstart_android.fillerfree.audio

import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.VoiceProsodySample
import io.agora.rtc2.IAudioFrameObserver
import io.agora.rtc2.audio.AudioParams
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Taps Agora's recording-audio-frame callback to get raw PCM straight from
 * the mic — the same audio Agora sends over RTC, observed locally before
 * it ever reaches the network. This is what makes emotion detection a real
 * voice-prosody read instead of the transcript-timing proxy in
 * [com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase.DetectEmotionSignalUseCase].
 *
 * NOTE ON VERIFICATION: this class was written against the documented
 * Agora RTC Android SDK v4 `IAudioFrameObserver` surface (`onRecordAudioFrame`,
 * the `AudioFrame` fields below) without being able to compile against the
 * actual `.aar` in this environment — there was no network access to fetch
 * it for a real build check. Confirm the exact method signatures and
 * `AudioParams` constructor against your resolved `io.agora.rtc:full-sdk:4.3.2`
 * artifact before relying on this; SDK point releases occasionally rename
 * fields or reorder constructor args.
 *
 * Registered on `RtcEngine` via `registerAudioFrameObserver` (see
 * AgoraConversationSessionManager), requesting 16kHz mono PCM16 — plenty
 * for pitch tracking up to ~400Hz and far cheaper to process than the
 * call's native sample rate.
 */
class VoiceProsodyAnalyzer : IAudioFrameObserver {

    private val _prosodySamples = MutableSharedFlow<VoiceProsodySample>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    val prosodySamples: SharedFlow<VoiceProsodySample> = _prosodySamples

    private val windowBuffer = mutableListOf<Short>()
    private var lastEmitMs = 0L

    override fun onRecordAudioFrame(
        channelId: String?,
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer?,
        renderTimeMs: Long,
        avsync_type: Int,
    ): Boolean {
        if (buffer == null || bytesPerSample != 2) return true // expect PCM16; bail out safely otherwise

        val samples = pcm16ToShorts(buffer, samplesPerChannel * channels)
        // Downmix to mono if the frame is multi-channel — pitch/energy don't
        // need stereo separation, and it keeps the windowing math simple.
        val mono = if (channels > 1) downmixToMono(samples, channels) else samples
        windowBuffer.addAll(mono.toList())

        val samplesPerWindow = (samplesPerSec * WINDOW_MS / 1000)
        if (windowBuffer.size >= samplesPerWindow) {
            val windowSamples = windowBuffer.take(samplesPerWindow)
            windowBuffer.subList(0, samplesPerWindow).clear()

            val now = renderTimeMs.takeIf { it > 0 } ?: System.currentTimeMillis()
            if (now - lastEmitMs >= EMIT_THROTTLE_MS) {
                lastEmitMs = now
                val rms = computeRms(windowSamples)
                val pitch = if (rms > MIN_ENERGY_FOR_PITCH) {
                    estimatePitchHz(windowSamples, samplesPerSec)
                } else {
                    null
                }
                _prosodySamples.tryEmit(
                    VoiceProsodySample(rmsEnergy = rms, pitchHz = pitch, timestampMs = now)
                )
            }
        }
        return true
    }

    override fun onPlaybackAudioFrame(
        channelId: String?,
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer?,
        renderTimeMs: Long,
        avsync_type: Int,
    ): Boolean = true // not interested in the agent's own voice, only the user's mic

    override fun onMixedAudioFrame(
        channelId: String?,
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer?,
        renderTimeMs: Long,
        avsync_type: Int,
    ): Boolean = true

    override fun onEarMonitoringAudioFrame(
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer?,
        renderTimeMs: Long,
        avsync_type: Int,
    ): Boolean = true

    override fun onPlaybackAudioFrameBeforeMixing(
        channelId: String?,
        uid: Int,
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer?,
        renderTimeMs: Long,
        avsync_type: Int,
        rtpTimestamp: Int,
    ): Boolean = true

    override fun getObservedAudioFramePosition(): Int = io.agora.rtc2.Constants.AUDIO_ENCODED_FRAME_OBSERVER_POSITION_MIC

    override fun getRecordAudioParams(): AudioParams =
        AudioParams(SAMPLE_RATE_HZ, CHANNELS_MONO, io.agora.rtc2.Constants.RAW_AUDIO_FRAME_OP_MODE_READ_ONLY, SAMPLE_RATE_HZ / 100)

    override fun getPlaybackAudioParams(): AudioParams? = null
    override fun getMixedAudioParams(): AudioParams? = null
    override fun getEarMonitoringAudioParams(): AudioParams? = null

    fun reset() {
        windowBuffer.clear()
        lastEmitMs = 0L
    }

    private fun pcm16ToShorts(buffer: ByteBuffer, sampleCount: Int): ShortArray {
        val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(sampleCount.coerceAtMost(duplicate.remaining() / 2))
        for (i in out.indices) out[i] = duplicate.short
        return out
    }

    private fun downmixToMono(samples: ShortArray, channels: Int): ShortArray {
        val frameCount = samples.size / channels
        val mono = ShortArray(frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0
            for (ch in 0 until channels) sum += samples[frame * channels + ch]
            mono[frame] = (sum / channels).toShort()
        }
        return mono
    }

    private fun computeRms(samples: List<Short>): Double {
        if (samples.isEmpty()) return 0.0
        val sumSquares = samples.sumOf { it.toDouble() * it.toDouble() }
        return sqrt(sumSquares / samples.size)
    }

    /**
     * Autocorrelation-based pitch estimate (a standard, lightweight F0
     * detector — no external DSP library needed). Searches lag values
     * corresponding to human voice range (~70-400Hz), returns the lag with
     * the strongest self-similarity as the fundamental period.
     */
    private fun estimatePitchHz(samples: List<Short>, sampleRateHz: Int): Double? {
        val minLag = sampleRateHz / MAX_VOICE_HZ
        val maxLag = sampleRateHz / MIN_VOICE_HZ
        if (maxLag >= samples.size) return null

        var bestLag = -1
        var bestCorrelation = 0.0
        for (lag in minLag..maxLag) {
            var correlation = 0.0
            for (i in 0 until samples.size - lag) {
                correlation += samples[i] * samples[i + lag]
            }
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestLag = lag
            }
        }

        if (bestLag <= 0) return null
        // Reject weak/noisy correlations rather than reporting a low-confidence pitch.
        val normalizedStrength = bestCorrelation / (samples.sumOf { it.toDouble() * it.toDouble() } + 1.0)
        if (normalizedStrength < MIN_CORRELATION_STRENGTH) return null

        return sampleRateHz.toDouble() / bestLag
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNELS_MONO = 1
        private const val WINDOW_MS = 300
        private const val EMIT_THROTTLE_MS = 300L

        private const val MIN_ENERGY_FOR_PITCH = 300.0
        private const val MIN_VOICE_HZ = 70
        private const val MAX_VOICE_HZ = 400
        private const val MIN_CORRELATION_STRENGTH = 0.35
    }
}
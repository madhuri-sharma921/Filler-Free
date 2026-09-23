package com.androidengineers.agent_quickstart_android.fillerfree.data

import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.EmotionLabel
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.EmotionSignal
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionStats
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionSummary
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechEvent
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechEventType
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.VoiceProsodySample
import com.androidengineers.agent_quickstart_android.fillerfree.domain.repository.TranscriptAnalyticsRepository
import com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase.AnalyzeTranscriptUseCase
import com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase.BuildSessionSummaryUseCase
import com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase.DetectEmotionSignalUseCase
import com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase.DetectVoiceEmotionUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * In-memory implementation: no persistence needed for the hackathon MVP.
 * Everything lives for the duration of one session and resets on [reset].
 *
 * Thread-safety note: this is expected to be driven from a single
 * coroutine/collector (the ViewModel's viewModelScope), matching how
 * ConversationViewModel already collects from AgoraConversationSessionManager.
 */
class TranscriptAnalyticsRepositoryImpl(
    private val analyzeTranscript: AnalyzeTranscriptUseCase = AnalyzeTranscriptUseCase(),
    private val buildSummary: BuildSessionSummaryUseCase = BuildSessionSummaryUseCase(),
    private val detectEmotionSignal: DetectEmotionSignalUseCase = DetectEmotionSignalUseCase(),
    private val detectVoiceEmotion: DetectVoiceEmotionUseCase = DetectVoiceEmotionUseCase(),
) : TranscriptAnalyticsRepository {

    private val _events = MutableSharedFlow<SpeechEvent>(replay = 0, extraBufferCapacity = 32)
    override val events = _events

    private val _stats = MutableStateFlow(SessionStats())
    override val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val _emotionSignal = MutableStateFlow<EmotionSignal?>(null)
    override val emotionSignal: Flow<EmotionSignal> = _emotionSignal.asStateFlow().filterNotNull()

    private val eventHistory = mutableListOf<SpeechEvent>()
    private val recentSentences = ArrayDeque<String>()
    private val sentenceBuffer = StringBuilder()
    private var sessionStartMs: Long? = null

    // Latest real-audio-derived read. Voice takes priority over the
    // text-timing proxy whenever it has a confident (non-UNKNOWN) label —
    // see onUserTranscriptChunk for how the two are blended.
    private var latestVoiceLabel: EmotionLabel = EmotionLabel.UNKNOWN
    private var latestVoiceLabelAtMs: Long = 0L

    override fun onVoiceProsodySample(sample: VoiceProsodySample) {
        latestVoiceLabel = detectVoiceEmotion(sample)
        latestVoiceLabelAtMs = sample.timestampMs

        // Emit immediately from the voice read alone — this is the fix for
        // "shows late/rarely": voice samples arrive on a steady ~300ms
        // cadence regardless of when/whether STT decides to push a
        // transcript delta, so the UI no longer has to wait on transcript
        // chunk timing to get an update. wordsPerMinute/fillerTrend/
        // avgPauseMs stay at their last known text-derived values (or
        // zero, before any transcript has arrived) since this path has no
        // transcript context of its own.
        if (latestVoiceLabel != EmotionLabel.UNKNOWN) {
            val previous = _emotionSignal.value
            _emotionSignal.value = EmotionSignal(
                label = latestVoiceLabel,
                wordsPerMinute = previous?.wordsPerMinute ?: 0.0,
                fillerTrend = previous?.fillerTrend ?: 0.0,
                avgPauseMs = previous?.avgPauseMs ?: 0L,
                computedAtMs = sample.timestampMs,
            )
        }
    }

    override fun onUserTranscriptChunk(text: String, timestampMs: Long) {
        if (sessionStartMs == null) sessionStartMs = timestampMs

        val newEvents = analyzeTranscript(
            newText = text,
            recentSentences = recentSentences.toList(),
            timestampMs = timestampMs,
        )

        sentenceBuffer.append(" ").append(text)
        val bufferWordCount = sentenceBuffer.toString().split(Regex("\\s+")).count { it.isNotBlank() }
        if (bufferWordCount >= 1) {
            recentSentences.addLast(sentenceBuffer.toString().trim())
            sentenceBuffer.clear()
            if (recentSentences.size > MAX_RECENT_SENTENCES) recentSentences.removeFirst()
        }

        newEvents.forEach { event ->
            eventHistory += event
            _events.tryEmit(event)
        }

        val fillerCountThisChunk = newEvents.count { it.type == SpeechEventType.FILLER_WORD }
        val wordDelta = text.split(Regex("\\s+")).count { it.isNotBlank() }
        _stats.update(sessionStartMs, timestampMs) { current ->
            current.copy(
                fillerCount = current.fillerCount + fillerCountThisChunk,
                repetitionCount = current.repetitionCount + newEvents.count { it.type == SpeechEventType.REPETITION },
                wordCount = current.wordCount + wordDelta,
            )
        }

        val textSignal = detectEmotionSignal(
            chunkText = text,
            chunkFillerCount = fillerCountThisChunk,
            timestampMs = timestampMs,
        )

        // Voice wins when it has a fresh (< 2s old) confident read — real
        // prosody is the higher-fidelity signal the user actually asked
        // for; the text-timing proxy is the fallback for when the voice
        // pipeline hasn't accumulated enough samples yet (session start,
        // or a stretch of silence with no pitch to read).
        val voiceIsFreshAndConfident = latestVoiceLabel != EmotionLabel.UNKNOWN &&
                (timestampMs - latestVoiceLabelAtMs) < VOICE_SIGNAL_FRESHNESS_MS
        _emotionSignal.value = if (voiceIsFreshAndConfident) {
            textSignal.copy(label = latestVoiceLabel)
        } else {
            textSignal
        }
    }

    override fun onAgentTranscriptChunk(text: String, timestampMs: Long, userWasSpeaking: Boolean) {
        if (!userWasSpeaking) return

        val event = SpeechEvent(
            id = "${timestampMs}_interrupt",
            type = SpeechEventType.AGENT_INTERRUPTION,
            text = text,
            timestampMs = timestampMs,
        )
        eventHistory += event
        _events.tryEmit(event)

        _stats.update(sessionStartMs, timestampMs) { current ->
            current.copy(interruptionCount = current.interruptionCount + 1)
        }
    }

    override fun reset() {
        eventHistory.clear()
        recentSentences.clear()
        sentenceBuffer.clear()
        sessionStartMs = null
        _stats.value = SessionStats()
        _emotionSignal.value = null
        detectEmotionSignal.reset()
        detectVoiceEmotion.reset()
        latestVoiceLabel = EmotionLabel.UNKNOWN
        latestVoiceLabelAtMs = 0L
    }

    override fun buildSummary(): SessionSummary = buildSummary(_stats.value, eventHistory.toList())

    private fun MutableStateFlow<SessionStats>.update(
        startMs: Long?,
        nowMs: Long,
        block: (SessionStats) -> SessionStats,
    ) {
        val duration = if (startMs != null) nowMs - startMs else 0L
        value = block(value).copy(durationMs = duration)
    }

    companion object {
        private const val MAX_RECENT_SENTENCES = 5
        private const val VOICE_SIGNAL_FRESHNESS_MS = 2_000L
    }
}
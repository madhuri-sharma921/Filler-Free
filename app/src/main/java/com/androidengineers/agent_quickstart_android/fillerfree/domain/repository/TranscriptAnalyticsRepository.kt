package com.androidengineers.agent_quickstart_android.fillerfree.domain.repository

import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.EmotionSignal
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionStats
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionSummary
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechEvent
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.VoiceProsodySample
import kotlinx.coroutines.flow.Flow

/**
 * Clean-architecture boundary: the presentation layer only depends on this
 * interface, never on the concrete transcript-parsing implementation.
 */
interface TranscriptAnalyticsRepository {

    /** Live stream of detected speech events as transcript text arrives. */
    val events: Flow<SpeechEvent>

    /** Live aggregated stats, updated as events come in. */
    val stats: Flow<SessionStats>

    /** Live emotion-signal estimate, updated as user transcript chunks arrive. */
    val emotionSignal: Flow<EmotionSignal>

    /** Feed a new transcript chunk (user speech) into the analyzer. */
    fun onUserTranscriptChunk(text: String, timestampMs: Long)

    /**
     * Feed a new raw-audio prosody sample (mic loudness + pitch, no
     * transcript involved) into the analyzer. When available, this takes
     * priority over the text-timing proxy for [emotionSignal] — see
     * TranscriptAnalyticsRepositoryImpl for the blending rule.
     */
    fun onVoiceProsodySample(sample: VoiceProsodySample)

    /** Feed a new agent transcript chunk in, used to detect interruptions. */
    fun onAgentTranscriptChunk(text: String, timestampMs: Long, userWasSpeaking: Boolean)

    /** Reset all counters for a new session. */
    fun reset()

    /** Build the final summary shown at session end. */
    fun buildSummary(): SessionSummary
}
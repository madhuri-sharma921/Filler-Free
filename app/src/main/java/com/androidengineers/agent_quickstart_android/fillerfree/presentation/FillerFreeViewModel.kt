package com.androidengineers.agent_quickstart_android.fillerfree.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.androidengineers.agent_quickstart_android.data.ConversationRepository
import com.androidengineers.agent_quickstart_android.fillerfree.camera.EyeContactAnalyzer
import com.androidengineers.agent_quickstart_android.fillerfree.config.CoachAgentPromptBuilder
import com.androidengineers.agent_quickstart_android.fillerfree.data.CoachToolsBridgeApi
import com.androidengineers.agent_quickstart_android.fillerfree.data.TranscriptAnalyticsRepositoryImpl
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.EyeContactState
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionRecord
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechEventType
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechTopic
import com.androidengineers.agent_quickstart_android.fillerfree.domain.repository.SessionHistoryRepository
import com.androidengineers.agent_quickstart_android.fillerfree.domain.repository.TranscriptAnalyticsRepository
import com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase.BuildProgressSummaryUseCase
import com.androidengineers.agent_quickstart_android.fillerfree.history.data.RoomSessionHistoryRepository
import com.androidengineers.agent_quickstart_android.model.TranscriptSpeaker
import com.androidengineers.agent_quickstart_android.rtc.AgoraConversationSessionManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Presentation-layer ViewModel for the Filler-Free feature.
 *
 * This composes the quickstart's existing [AgoraConversationSessionManager]
 * + [ConversationRepository] as the single source of truth for the RTC/RTM
 * session, and layers the Filler-Free analytics domain logic on top by
 * observing `sessionManager.snapshot.transcriptTurns` — the real transcript
 * model exposed by the quickstart (see model/ConversationModels.kt).
 *
 * Each [com.androidengineers.agent_quickstart_android.model.TranscriptTurn]
 * carries a stable `key`, a `speaker` (USER/AGENT), free-form `text`, and a
 * `status` (IN_PROGRESS / END / INTERRUPTED). We track which turn keys we've
 * already analyzed so partial (IN_PROGRESS) turns get re-analyzed as they
 * grow, without double-counting finished ones.
 */
class FillerFreeViewModel(
    application: Application,
    private val analyticsRepository: TranscriptAnalyticsRepository = TranscriptAnalyticsRepositoryImpl(),
    private val historyRepository: SessionHistoryRepository = RoomSessionHistoryRepository(application),
    private val buildProgressSummary: BuildProgressSummaryUseCase = BuildProgressSummaryUseCase(),
) : AndroidViewModel(application) {

    private val conversationRepository = ConversationRepository()
    private val sessionManager = AgoraConversationSessionManager(application)
    private val coachToolsBridge = CoachToolsBridgeApi()

    // Owned here (not in the repository) because it wraps a CameraX/ML Kit
    // pipeline with real native resources — same lifecycle rationale as
    // sessionManager owning the RTC/RTM native resources below.
    val eyeContactAnalyzer = EyeContactAnalyzer()

    private val _uiState = MutableStateFlow(FillerFreeUiState())
    val uiState: StateFlow<FillerFreeUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<FillerFreeUiEvent>(Channel.BUFFERED)
    val uiEvents = eventChannel.receiveAsFlow()

    private val activeAgentIds = mutableSetOf<String>()

    // Tracks the last-seen text length per turn key, so we only feed the
    // *new* substring of a growing IN_PROGRESS turn into the analyzer,
    // rather than re-analyzing the whole turn on every partial update.
    private val analyzedLengthByTurnKey = mutableMapOf<String, Int>()

    private val prefs = application.getSharedPreferences("filler_free_prefs", android.content.Context.MODE_PRIVATE)

    init {
        val savedName = prefs.getString("user_name", "") ?: ""
        _uiState.update { it.copy(userName = savedName) }

        viewModelScope.launch {
            analyticsRepository.stats.collect { stats ->
                _uiState.update { it.copy(stats = stats) }
                // Best-effort mirror into the server-side store so the
                // coach agent's get_live_stats tool has fresh numbers to
                // read mid-conversation (see CoachAgentPromptBuilder's
                // TOOLS clause). Never blocks or surfaces errors to the
                // UI -- a failed mirror just means slightly stale tool
                // data, not a broken session.
                val channelName = sessionManager.snapshot.value.channelName
                if (channelName != null) {
                    runCatching {
                        coachToolsBridge.pushLiveStats(
                            channelName = channelName,
                            topicTitle = _uiState.value.selectedTopic?.title.orEmpty(),
                            fillerCount = stats.fillerCount,
                            repetitionCount = stats.repetitionCount,
                            interruptionCount = stats.interruptionCount,
                            wordCount = stats.wordCount,
                            durationMs = stats.durationMs,
                            topOffender = _uiState.value.recentEvents
                                .groupBy { it.text.lowercase() }
                                .maxByOrNull { it.value.size }
                                ?.key,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            analyticsRepository.events.collect { event ->
                _uiState.update { current ->
                    current.copy(recentEvents = (current.recentEvents + event).takeLast(20))
                }
                if (event.type == SpeechEventType.AGENT_INTERRUPTION) {
                    eventChannel.trySend(FillerFreeUiEvent.InterruptionFlash)
                }
            }
        }
        viewModelScope.launch {
            analyticsRepository.emotionSignal.collect { signal ->
                _uiState.update { it.copy(currentEmotion = signal.label) }
                sessionManager.pushCoachSignal(
                    "user_energy=${signal.label} wpm=${signal.wordsPerMinute.toInt()}"
                )
            }
        }

        // Real-audio path: independent of transcript chunk timing, so the
        // emotion read updates on a steady ~300ms cadence from the mic
        // itself rather than waiting on STT to push a transcript delta.
        viewModelScope.launch {
            sessionManager.voiceProsodyAnalyzer.prosodySamples.collect { sample ->
                analyticsRepository.onVoiceProsodySample(sample)
            }
        }

        // Reacts to EyeContactAnalyzer regardless of whether the camera is
        // currently bound — when coaching is off or permission is missing,
        // EyeContactCameraBinding never binds the camera, so this flow just
        // stays at its DISABLED-equivalent starting value. We still gate the
        // displayed state on the toggle here so the UI never shows a stale
        // "Good eye contact" read from a previous session after being
        // switched off mid-flow.
        viewModelScope.launch {
            eyeContactAnalyzer.eyeContactState.collect { rawState ->
                val enabled = _uiState.value.eyeContactCoachingEnabled && _uiState.value.hasCameraPermission
                val displayState = if (enabled) rawState else EyeContactState.DISABLED
                _uiState.update { it.copy(eyeContactState = displayState) }
                if (enabled && displayState == EyeContactState.LOOKING_AWAY) {
                    sessionManager.pushCoachSignal("eye_contact=looking_away")
                }
            }
        }

        viewModelScope.launch {
            sessionManager.snapshot.collect { snapshot ->
                val nowMs = System.currentTimeMillis()
                var liveTranscriptText = _uiState.value.liveTranscript

                for (turn in snapshot.transcriptTurns) {
                    val previousLength = analyzedLengthByTurnKey[turn.key] ?: 0
                    if (turn.text.length <= previousLength) continue
                    val newChunk = turn.text.substring(previousLength)
                    analyzedLengthByTurnKey[turn.key] = turn.text.length

                    when (turn.speaker) {
                        TranscriptSpeaker.USER -> {
                            analyticsRepository.onUserTranscriptChunk(newChunk, nowMs)
                            liveTranscriptText = "$liveTranscriptText $newChunk".trim()
                        }
                        TranscriptSpeaker.AGENT -> {
                            val userWasSpeaking = previousLength == 0 // only count the first chunk of each agent turn
                            analyticsRepository.onAgentTranscriptChunk(newChunk, nowMs, userWasSpeaking)
                        }
                    }
                }

                _uiState.update { it.copy(
                    liveTranscript = liveTranscriptText,
                    agentState = snapshot.agentState,
                    agentStates = it.agentStates + ("primary" to snapshot.agentState)
                ) }
            }
        }
    }

    fun selectTopic(topic: SpeechTopic) {
        _uiState.update { it.copy(selectedTopic = topic) }
    }

    fun setUserName(name: String) {
        _uiState.update { it.copy(userName = name) }
        prefs.edit().putString("user_name", name).apply()
    }

    /** Called from the InSessionScreen toggle. Does not itself request permission. */
    fun setEyeContactCoachingEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(
                eyeContactCoachingEnabled = enabled,
                eyeContactState = if (enabled && it.hasCameraPermission) it.eyeContactState else EyeContactState.DISABLED,
            )
        }
        if (!enabled) eyeContactAnalyzer.reset()
    }

    /** Called from MainActivity after the CAMERA permission result comes back. */
    fun setHasCameraPermission(granted: Boolean) {
        _uiState.update {
            it.copy(
                hasCameraPermission = granted,
                eyeContactState = if (granted && it.eyeContactCoachingEnabled) it.eyeContactState else EyeContactState.DISABLED,
            )
        }
    }

    fun startSession() {
        val topic = _uiState.value.selectedTopic ?: SpeechTopic.FREE_TALK
        analyticsRepository.reset()
        eyeContactAnalyzer.reset()
        analyzedLengthByTurnKey.clear()
        activeAgentIds.clear()
        _uiState.update {
            it.copy(
                screen = FillerFreeUiState.Screen.IN_SESSION,
                isConnecting = true,
                liveTranscript = "",
                recentEvents = emptyList(),
                summary = null,
                errorMessage = null,
            )
        }

        viewModelScope.launch {
            runCatching {
                val bootstrap = conversationRepository.requestSessionBootstrap()
                sessionManager.connect(bootstrap) { channel, rtcUid, rtmUserId ->
                    conversationRepository.renewTokens(
                        channel = channel,
                        rtcUid = rtcUid,
                        rtmUserId = rtmUserId,
                    )
                }

                val requesterRtcUid = sessionManager.snapshot.value.localRtcUid
                    .takeIf { it > 0 }
                    ?.toString()
                    ?: bootstrap.uid

                // Invite 3 specialized agents with a small delay between each
                CoachAgentPromptBuilder.CoachRole.entries.forEach { role ->
                    val prompt = CoachAgentPromptBuilder.build(topic, role, channelName = bootstrap.channel)
                    val inviteResult = conversationRepository.inviteAgent(
                        channelName = bootstrap.channel,
                        requesterRtcUid = requesterRtcUid,
                        systemPrompt = prompt,
                    )
                    activeAgentIds.add(inviteResult.agentId)
                    kotlinx.coroutines.delay(500) // Small delay to avoid race conditions or limits
                }
                
                // Track the first one for basic state updates in sessionManager
                if (activeAgentIds.isNotEmpty()) {
                    sessionManager.setActiveAgentId(activeAgentIds.first())
                }
            }.onSuccess {
                _uiState.update { it.copy(isConnecting = false, isSessionActive = true) }
            }.onFailure { error ->
                sessionManager.disconnect(resetSnapshot = true)
                activeAgentIds.clear()
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        isSessionActive = false,
                        screen = FillerFreeUiState.Screen.TOPIC_SELECT,
                        errorMessage = error.message ?: "Could not start the session.",
                    )
                }
            }
        }
    }

    fun endSession() {
        viewModelScope.launch {
            val channelName = sessionManager.snapshot.value.channelName
            runCatching {
                if (channelName != null) {
                    activeAgentIds.forEach { agentId ->
                        conversationRepository.stopConversation(agentId, channelName)
                    }
                }
            }
            activeAgentIds.clear()
            sessionManager.setActiveAgentId(null)
            sessionManager.disconnect(resetSnapshot = true)

            val summary = analyticsRepository.buildSummary()
            val topic = _uiState.value.selectedTopic ?: SpeechTopic.FREE_TALK

            // Only log a real attempt — a near-empty session (permission
            // denied, immediate hangup) would just add noise to trends.
            if (summary.stats.wordCount >= MIN_WORDS_TO_LOG_SESSION) {
                runCatching {
                    historyRepository.saveSession(
                        SessionRecord(
                            topicId = topic.id,
                            topicTitle = topic.title,
                            completedAtMs = System.currentTimeMillis(),
                            durationMs = summary.stats.durationMs,
                            fillerCount = summary.stats.fillerCount,
                            repetitionCount = summary.stats.repetitionCount,
                            interruptionCount = summary.stats.interruptionCount,
                            wordCount = summary.stats.wordCount,
                            topOffender = summary.topOffender,
                        )
                    )
                }
                // Mirrors the same completed session into the server-side
                // history store (see server/app/mcp/session_history_store.py)
                // so get_session_history has something real to read next
                // time -- the on-device Room DB above stays authoritative
                // for the Progress screen; this is purely for the agent.
                runCatching {
                    coachToolsBridge.reportSession(
                        topicTitle = topic.title,
                        completedAtUnix = System.currentTimeMillis() / 1000,
                        fillerCount = summary.stats.fillerCount,
                        repetitionCount = summary.stats.repetitionCount,
                        topOffender = summary.topOffender,
                    )
                }
            }

            // Check whether the agent used queue_practice_topic mid-session
            // (see CoachAgentPromptBuilder's TOOLS clause). Absent almost
            // always -- most sessions won't trigger it -- so a miss here
            // is the normal case, not an error.
            val suggestedPractice = channelName?.let { name ->
                runCatching { coachToolsBridge.popQueuedPractice(name) }.getOrNull()
            }?.let { queued ->
                QueuedPracticeSuggestion(
                    topicId = queued.topicId,
                    topicTitle = queued.topicTitle,
                    reason = queued.reason,
                )
            }

            _uiState.update {
                it.copy(
                    screen = FillerFreeUiState.Screen.SUMMARY,
                    isSessionActive = false,
                    summary = summary,
                    suggestedPractice = suggestedPractice,
                )
            }
        }
    }

    fun openProgressScreen() {
        viewModelScope.launch {
            val history = runCatching {
                historyRepository.recentSessions(SessionHistoryRepository.DEFAULT_HISTORY_LIMIT)
            }.getOrDefault(emptyList())
            val progress = buildProgressSummary(history)
            _uiState.update { it.copy(screen = FillerFreeUiState.Screen.PROGRESS, progressSummary = progress) }
        }
    }

    fun closeProgressScreen() {
        _uiState.update { it.copy(screen = FillerFreeUiState.Screen.SUMMARY) }
    }

    fun startNewSession() {
        analyticsRepository.reset()
        analyzedLengthByTurnKey.clear()
        _uiState.update {
            it.copy(
                screen = FillerFreeUiState.Screen.TOPIC_SELECT,
                selectedTopic = null,
                isConnecting = false,
                isSessionActive = false,
                liveTranscript = "",
                recentEvents = emptyList(),
                stats = com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionStats(),
                summary = null,
                errorMessage = null,
                currentEmotion = null,
                agentState = com.androidengineers.agent_quickstart_android.model.AgentConversationState.IDLE,
                agentStates = emptyMap()
            )
        }
    }

    override fun onCleared() {
        sessionManager.release()
        eyeContactAnalyzer.close()
        super.onCleared()
    }

    companion object {
        private const val MIN_WORDS_TO_LOG_SESSION = 5
    }
}

private inline fun MutableStateFlow<FillerFreeUiState>.update(
    block: (FillerFreeUiState) -> FillerFreeUiState,
) {
    value = block(value)
}
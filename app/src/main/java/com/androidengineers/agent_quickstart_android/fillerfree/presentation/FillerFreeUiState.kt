package com.androidengineers.agent_quickstart_android.fillerfree.presentation

import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.EmotionLabel
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.EyeContactState
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.ProgressSummary
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionStats
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionSummary
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechEvent
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechTopic
import com.androidengineers.agent_quickstart_android.model.AgentConversationState

/**
 * Single immutable state object for the Filler-Free screen (MVI style,
 * consistent with the existing ConversationUiState pattern in the quickstart).
 */
data class FillerFreeUiState(
    val screen: Screen = Screen.TOPIC_SELECT,
    val selectedTopic: SpeechTopic? = null,
    val topics: List<SpeechTopic> = SpeechTopic.ALL,
    val isConnecting: Boolean = false,
    val isSessionActive: Boolean = false,
    val liveTranscript: String = "",
    val recentEvents: List<SpeechEvent> = emptyList(),
    val stats: SessionStats = SessionStats(),
    val summary: SessionSummary? = null,
    val errorMessage: String? = null,
    val currentEmotion: EmotionLabel? = null,
    val userName: String = "",
    val eyeContactCoachingEnabled: Boolean = false,
    val hasCameraPermission: Boolean = false,
    val eyeContactState: EyeContactState = EyeContactState.DISABLED,
    val progressSummary: ProgressSummary? = null,
    val agentState: AgentConversationState = AgentConversationState.IDLE,
    val agentStates: Map<String, AgentConversationState> = emptyMap(),
    // Set when the coach agent called `queue_practice_topic` mid-session
    // (see CoachAgentPromptBuilder's TOOLS clause + server/app/mcp/tool_server.py).
    // Surfaced as a "Try again: <topic>" suggestion on the summary screen;
    // null means the agent never queued anything this session.
    val suggestedPractice: QueuedPracticeSuggestion? = null,
) {
    enum class Screen {
        TOPIC_SELECT,
        IN_SESSION,
        SUMMARY,
        PROGRESS,
    }
}

data class QueuedPracticeSuggestion(
    val topicId: String,
    val topicTitle: String,
    val reason: String?,
)

/** One-off UI events, distinct from persisted state (e.g. a brief flash/haptic on interruption). */
sealed interface FillerFreeUiEvent {
    data object InterruptionFlash : FillerFreeUiEvent
    data class ShowError(val message: String) : FillerFreeUiEvent
}
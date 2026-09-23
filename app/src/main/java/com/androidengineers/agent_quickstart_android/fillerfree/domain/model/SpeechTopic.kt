package com.androidengineers.agent_quickstart_android.fillerfree.domain.model

/**
 * A pre-set prompt the user can pick before starting a session.
 * The [systemPromptContext] is appended to the base coach system prompt
 * so the agent knows what kind of explanation to expect.
 */
data class SpeechTopic(
    val id: String,
    val title: String,
    val description: String,
    val systemPromptContext: String,
) {
    companion object {
        val EXPLAIN_A_BUG = SpeechTopic(
            id = "explain_bug",
            title = "Explain a bug you fixed",
            description = "Walk through a real bug, root cause, and fix.",
            systemPromptContext = "The user will explain a software bug they fixed. " +
                "Push them toward: what broke, why it broke, how they found it, how they fixed it.",
        )

        val EXPLAIN_A_PROJECT = SpeechTopic(
            id = "explain_project",
            title = "Explain your last project",
            description = "Practice a crisp project walkthrough, like in an interview.",
            systemPromptContext = "The user will explain a project from their work experience. " +
                "Push them toward: the problem, their specific contribution, and the outcome " +
                "with a concrete number if possible.",
        )

        val INTERVIEW_ANSWER = SpeechTopic(
            id = "interview_answer",
            title = "Practice an interview answer",
            description = "Tighten up a behavioral or technical answer.",
            systemPromptContext = "The user is rehearsing an interview answer. " +
                "Push them toward a direct structure: situation, action, result, no meandering.",
        )

        val FREE_TALK = SpeechTopic(
            id = "free_talk",
            title = "Free talk",
            description = "Explain anything on your mind.",
            systemPromptContext = "The user may talk about anything. Apply the same standard: " +
                "clarity, concreteness, no filler, no repetition.",
        )

        val ALL = listOf(EXPLAIN_A_BUG, EXPLAIN_A_PROJECT, INTERVIEW_ANSWER, FREE_TALK)
    }
}

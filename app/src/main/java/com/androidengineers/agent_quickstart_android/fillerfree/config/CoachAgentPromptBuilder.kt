package com.androidengineers.agent_quickstart_android.fillerfree.config

import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechTopic

/**
 * Builds the system prompt sent to the Agora Conversational AI agent when
 * inviting it into the channel (see ConversationAgoraApi.inviteAgent — pass
 * this as the agent's llm system instruction / preset override).
 *
 * This is the single most important file for the hackathon submission:
 * almost all of "Filler-Free"'s product behavior lives here, not in
 * hand-rolled interruption-detection code. Agora's Conversational AI
 * pipeline already supports low-latency barge-in; we just need the agent
 * to be aggressive and terse about *when* it chooses to interrupt.
 */
object CoachAgentPromptBuilder {

    enum class CoachRole {
        GENERAL,
        ENERGY,
        EYE_CONTACT
    }

    fun build(
        topic: SpeechTopic,
        role: CoachRole,
        priorHabit: String? = null,
        channelName: String? = null,
    ): String {
        val memoryClause = priorHabit?.let {
            "\nMEMORY FROM LAST SESSION: Their recurring habit was \"$it\". " +
                    "If it happens again, call it out specifically, e.g. \"There's '$it' again.\""
        } ?: ""

        // Only the Core Coach reaches for tools -- it's the one tracking
        // filler/repetition counts, so it's the one with a reason to check
        // a number instead of guessing. Omitted entirely (rather than
        // included but told not to use it) when no channel name is
        // available, since a tool call needs a real channel_name argument
        // to do anything -- see server/app/mcp/tool_server.py.
        val toolsClause = if (role == CoachRole.GENERAL && !channelName.isNullOrBlank()) {
            """


                TOOLS: You have tools available for this session (channel_name="$channelName").
                - get_live_stats: call this before saying a specific number, e.g. "that's six ums" —
                  don't guess the count from memory, check it.
                - queue_practice_topic: call this if the same habit clearly dominated the session,
                  so they get a targeted redo queued up after this ends.
                - get_session_history: call this once, early, to check whether a habit you're about
                  to call "recurring" has actually shown up in a past session.
                Use these silently — never say "let me check" or mention the tool by name out loud.
            """.trimIndent()
        } else {
            ""
        }

        val roleInstruction = when (role) {
            CoachRole.GENERAL -> """
                You are the "Core Coach". Focus strictly on filler words (um, like, basically) and repetitions.
                Be clinical, brief, and immediate.
            """.trimIndent()
            CoachRole.ENERGY -> """
                You are the "Energy Coach". Your primary focus is the user's emotional state and speaking pace.
                Watch for NERVOUS (fast, high pitch), EXCITED (high energy), CONFIDENT (steady), or FRUSTRATED (long pauses).
                React to the [signal] user_energy you receive.
            """.trimIndent()
            CoachRole.EYE_CONTACT -> """
                You are the "Presence Coach". You watch the user's eye contact and posture.
                React specifically to [signal] eye_contact=looking_away.
                Be encouraging but firm about keeping eyes up.
            """.trimIndent()
        }

        return """
            $roleInstruction
            You are in a live call with the user who is explaining: ${topic.title}.
            $memoryClause
            $toolsClause

            ${topic.systemPromptContext}

            UNIVERSAL RULES:
            - Be EXTREMELY TERSE. Never use more than 10 words.
            - Interrupt immediately when you see your specific trigger.
            - Never mention "system message", "signal", or your role name.
            - Act like a real human coach in the room with them.

            EMOTION SIGNAL AWARENESS (Energy Coach primarily):
            [signal] user_energy=...
            - React with: "Nervous — slow down." or "Great energy, keep it up!" or "Take a breath."

            EYE CONTACT SIGNAL AWARENESS (Presence Coach primarily):
            [signal] eye_contact=looking_away
            - React with: "Eyes on me." or "Don't look down, you've got this."
        """.trimIndent()
    }

    /**
     * Short label sent alongside the prompt for logging/debugging on the
     * quickstart server side, if your ConversationAgoraApi.inviteAgent
     * supports a preset name/tag field.
     */
    fun presetName(topic: SpeechTopic): String = "filler_free_coach_${topic.id}"
}
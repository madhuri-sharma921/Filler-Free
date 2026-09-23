package com.androidengineers.agent_quickstart_android.fillerfree.domain.model

/**
 * The 3 coach personas available in a session (point 3: "multiple AI
 * coaches simultaneously").
 *
 * IMPORTANT HONESTY NOTE: Agora's Conversational AI SDK has no native
 * mute/pause for a running agent session (only start/stop/interrupt — see
 * server/app/agora_client.py). Because of that, only ONE of these 3 coach
 * agents is ever actually joined and speaking into the RTC channel at a
 * time; the other two are "on the bench." Switching roles does a real
 * leave-then-join on the server (see /v1/conversation/switch-role). This
 * still delivers the product feature — 3 distinct coaching personas
 * available across one session, with the transcript/UI clearly showing
 * who's currently speaking — without producing 3 overlapping audio
 * streams, which would be worse UX than one coach at a time.
 *
 * [id] must match a role string configured server-side in Settings.coach_roles
 * (see server/app/config.py) — these are NOT independent; changing one
 * without the other will break RTC UID assignment.
 */
enum class CoachRole(val id: String, val displayName: String) {
    DELIVERY(id = "delivery", displayName = "Delivery Coach"),
    CONTENT(id = "content", displayName = "Content Coach"),
    ENERGY(id = "energy", displayName = "Energy Coach"),
    ;

    companion object {
        val ALL = listOf(DELIVERY, CONTENT, ENERGY)

        fun fromId(id: String?): CoachRole? = ALL.firstOrNull { it.id == id }
    }
}
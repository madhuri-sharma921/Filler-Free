package com.androidengineers.agent_quickstart_android.fillerfree.live.domain

/** Mirrors server/app/live/live_room_store.py LiveRole. */
enum class LiveRole {
    SPEAKER,
    MENTOR,
    AUDIENCE;

    companion object {
        fun fromWire(value: String): LiveRole = when (value.lowercase()) {
            "speaker" -> SPEAKER
            "mentor" -> MENTOR
            else -> AUDIENCE
        }
    }
}

data class LiveRoomConnection(
    val roomId: String,
    val channelName: String,
    val appId: String,
    val rtcToken: String,
    val rtcUid: Int,
    val role: LiveRole,
)

data class LiveParticipant(
    val rtcUid: Int,
    val displayName: String,
    val role: LiveRole,
)

data class LiveRoomState(
    val roomId: String,
    val channelName: String,
    val topicTitle: String,
    val speakerRtcUid: Int,
    val participants: List<LiveParticipant>,
    val coachAgentActive: Boolean,
) {
    val mentor: LiveParticipant? get() = participants.firstOrNull { it.role == LiveRole.MENTOR }
    val audienceCount: Int get() = participants.count { it.role == LiveRole.AUDIENCE }
}

data class OpenLiveRoomSummary(
    val roomId: String,
    val topicTitle: String,
    val speakerRtcUid: Int,
    val audienceCount: Int,
)

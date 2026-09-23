package com.androidengineers.agent_quickstart_android.fillerfree.domain.model



/**
 * A coarse, on-device read of whether the user appears to be looking at
 * the camera (a rough proxy for "looking at the person/interviewer you're
 * practicing in front of"), derived from ML Kit's Face Detection API.
 *
 * IMPORTANT SCOPE NOTE: this is local-only. No camera frames, images, or
 * video are ever uploaded, streamed over Agora, or sent to any server —
 * see VisualCoachManager.kt. This is the "tier 1" version of point 4
 * (visual coaching): a local coaching HUD, not a published video track.
 */
enum class AttentionLabel {
    LOOKING_AT_CAMERA,
    LOOKING_AWAY,
    NO_FACE_DETECTED,
    UNKNOWN,
}

data class AttentionSignal(
    val label: AttentionLabel,
    /** Head yaw (left/right turn) in degrees, from ML Kit's headEulerAngleY. Null if no face. */
    val headYawDegrees: Float?,
    /** How long, in ms, the user has been continuously in [label] as of this reading. */
    val continuousDurationMs: Long,
    val computedAtMs: Long,
)
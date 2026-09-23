package com.androidengineers.agent_quickstart_android.fillerfree.domain.model

/**
 * Coarse, on-device read of whether the user appears to be looking at the
 * camera (i.e. roughly at the phone screen, which is where the transcript
 * lives). Derived entirely from ML Kit face-detection head-pose Euler
 * angles — no frames, landmarks, or face data ever leave the device, and
 * nothing is persisted; see [com.androidengineers.agent_quickstart_android.fillerfree.camera.EyeContactAnalyzer].
 */
enum class EyeContactState {
    /** Face detected, head angles within the "looking at camera" window. */
    GOOD,

    /** Face detected but head is turned/tilted away beyond the threshold. */
    LOOKING_AWAY,

    /** No face currently detected in frame (out of shot, camera occluded, etc.). */
    NO_FACE,

    /** Coaching toggle is off, or camera permission was never granted. */
    DISABLED,
}
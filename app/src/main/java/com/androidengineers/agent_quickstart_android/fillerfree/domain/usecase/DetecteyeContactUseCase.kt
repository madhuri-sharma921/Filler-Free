package com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase

import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.EyeContactState
import kotlin.math.abs

/**
 * Pure function, same style as [DetectEmotionSignalUseCase]: no Android/
 * CameraX/ML Kit dependencies, fully unit testable. Takes head-pose Euler
 * angles for the current frame (or null if no face was found) and returns
 * a debounced [EyeContactState].
 *
 * ML Kit's FaceDetector reports:
 * - headEulerAngleY: left/right turn ("yaw"). 0 = facing camera straight on.
 * - headEulerAngleZ: head tilt ("roll"). 0 = upright.
 *
 * We deliberately ignore small, natural glance-downs (e.g. at a transcript
 * a few inches below the lens) by using a fairly generous yaw window, and
 * debounce state flips across a few consecutive frames so a single blink
 * or a quick glance at the transcript doesn't flash "looking away".
 */
class DetectEyeContactUseCase {

    private var consecutiveAwayFrames = 0
    private var consecutiveNoFaceFrames = 0

    /**
     * @param headEulerAngleY left/right turn in degrees, or null if no face was detected.
     * @param headEulerAngleZ head tilt in degrees; pass 0f if unavailable.
     */
    operator fun invoke(
        headEulerAngleY: Float?,
        headEulerAngleZ: Float = 0f,
    ): EyeContactState {
        if (headEulerAngleY == null) {
            consecutiveAwayFrames = 0
            consecutiveNoFaceFrames++
            return if (consecutiveNoFaceFrames >= NO_FACE_DEBOUNCE_FRAMES) {
                EyeContactState.NO_FACE
            } else {
                EyeContactState.GOOD // don't flash NO_FACE on a single missed frame
            }
        }
        consecutiveNoFaceFrames = 0

        val isTurnedAway = abs(headEulerAngleY) > YAW_THRESHOLD_DEGREES
        val isTiltedAway = abs(headEulerAngleZ) > ROLL_THRESHOLD_DEGREES

        return if (isTurnedAway || isTiltedAway) {
            consecutiveAwayFrames++
            if (consecutiveAwayFrames >= AWAY_DEBOUNCE_FRAMES) {
                EyeContactState.LOOKING_AWAY
            } else {
                EyeContactState.GOOD
            }
        } else {
            consecutiveAwayFrames = 0
            EyeContactState.GOOD
        }
    }

    fun reset() {
        consecutiveAwayFrames = 0
        consecutiveNoFaceFrames = 0
    }

    companion object {
        private const val YAW_THRESHOLD_DEGREES = 22f
        private const val ROLL_THRESHOLD_DEGREES = 25f
        private const val AWAY_DEBOUNCE_FRAMES = 5
        private const val NO_FACE_DEBOUNCE_FRAMES = 8
    }
}
package com.androidengineers.agent_quickstart_android.fillerfree.domain.usecase

import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.CoachRole
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.EmotionLabel

/**
 * Decides which [CoachRole] should currently hold the floor, given the
 * latest emotion signal and recent filler/repetition activity.
 *
 * Deliberately a small rules engine, not an ML model or an LLM call — same
 * philosophy as DetectEmotionSignalUseCase: cheap, explainable, and easy to
 * reason about live. This is the piece that makes "3 coaches in one call"
 * feel automatic rather than requiring the user to manually pick a coach.
 *
 * Throttling is the caller's responsibility (see FillerFreeViewModel),
 * since switching roles means a real server-side leave+join — this use
 * case only answers "who *should* have the floor right now," not "is it
 * time to switch yet."
 */
class RouteCoachRoleUseCase {

    /**
     * @param emotion latest emotion read, or null if not enough data yet
     * @param recentFillerCount fillers detected in roughly the last ~10s window
     * @param recentRepetitionCount repetitions detected in the same window
     * @param currentRole the role currently holding the floor
     */
    operator fun invoke(
        emotion: EmotionLabel?,
        recentFillerCount: Int,
        recentRepetitionCount: Int,
        currentRole: CoachRole,
    ): CoachRole {
        return when {
            // Energy coach takes the floor on a clear emotional signal —
            // this is the most "human coach" read, so it takes priority.
            emotion == EmotionLabel.NERVOUS || emotion == EmotionLabel.FRUSTRATED -> CoachRole.ENERGY

            // Delivery coach takes over when fillers/repeats are actively
            // piling up — mechanical speech-quality issue, not an emotional one.
            recentFillerCount >= DELIVERY_FILLER_THRESHOLD || recentRepetitionCount >= DELIVERY_REPETITION_THRESHOLD ->
                CoachRole.DELIVERY

            // Confident/excited with a clean delivery: hand off to Content,
            // since the user has room to be pushed on substance instead.
            (emotion == EmotionLabel.CONFIDENT || emotion == EmotionLabel.EXCITED) &&
                    recentFillerCount == 0 && recentRepetitionCount == 0 -> CoachRole.CONTENT

            // No strong signal either way: stay put rather than flapping
            // between coaches on noise.
            else -> currentRole
        }
    }

    companion object {
        private const val DELIVERY_FILLER_THRESHOLD = 3
        private const val DELIVERY_REPETITION_THRESHOLD = 2
    }
}
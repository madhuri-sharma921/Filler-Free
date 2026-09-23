package com.androidengineers.agent_quickstart_android.fillerfree.domain.model

/** Domain-layer view of a completed session, independent of the Room entity. */
data class SessionRecord(
    val topicId: String,
    val topicTitle: String,
    val completedAtMs: Long,
    val durationMs: Long,
    val fillerCount: Int,
    val repetitionCount: Int,
    val interruptionCount: Int,
    val wordCount: Int,
    val topOffender: String?,
) {
    val totalIssues: Int get() = fillerCount + repetitionCount

    fun issuesPerMinute(): Double {
        if (durationMs <= 0) return 0.0
        val minutes = durationMs / 60_000.0
        return if (minutes > 0) totalIssues / minutes else 0.0
    }
}
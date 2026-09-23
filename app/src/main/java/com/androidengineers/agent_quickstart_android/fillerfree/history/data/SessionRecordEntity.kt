package com.androidengineers.agent_quickstart_android.fillerfree.history.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per completed session — a flattened snapshot of that session's
 * final [com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionStats]
 * plus enough context (topic, timestamp) to build trends across sessions.
 *
 * Deliberately flat rather than a normalized events table: progress
 * tracking only needs per-session aggregates ("fillers per minute is
 * dropping"), not a full replay of every individual filler word. If a
 * future feature needs individual-event history, that's a separate table,
 * not a reason to complicate this one.
 */
@Entity(tableName = "session_records")
data class SessionRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val topicId: String,
    val topicTitle: String,
    val completedAtMs: Long,
    val durationMs: Long,
    val fillerCount: Int,
    val repetitionCount: Int,
    val interruptionCount: Int,
    val wordCount: Int,
    val topOffender: String?,
)
package com.androidengineers.agent_quickstart_android.fillerfree.domain.repository

import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionRecord

/** Clean-architecture boundary: presentation depends only on this, never on Room directly. */
interface SessionHistoryRepository {
    suspend fun saveSession(record: SessionRecord)
    suspend fun recentSessions(limit: Int = DEFAULT_HISTORY_LIMIT): List<SessionRecord>
    suspend fun totalSessionCount(): Int

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 30
    }
}
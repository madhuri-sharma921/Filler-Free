package com.androidengineers.agent_quickstart_android.fillerfree.history.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SessionHistoryDao {

    @Insert
    suspend fun insert(record: SessionRecordEntity): Long

    /** Most recent sessions first. [limit] bounds how far back progress trends look. */
    @Query("SELECT * FROM session_records ORDER BY completedAtMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SessionRecordEntity>

    @Query("SELECT COUNT(*) FROM session_records")
    suspend fun totalSessionCount(): Int

    @Query("DELETE FROM session_records")
    suspend fun clearAll()
}
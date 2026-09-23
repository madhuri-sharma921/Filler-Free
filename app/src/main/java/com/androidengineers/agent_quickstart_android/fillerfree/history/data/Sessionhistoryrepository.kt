package com.androidengineers.agent_quickstart_android.fillerfree.history.data

import android.content.Context
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionRecord
import com.androidengineers.agent_quickstart_android.fillerfree.domain.repository.SessionHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomSessionHistoryRepository(
    context: Context,
) : SessionHistoryRepository {

    private val dao = SessionHistoryDatabase.getInstance(context).sessionHistoryDao()

    override suspend fun saveSession(record: SessionRecord) {
        withContext(Dispatchers.IO) {
            dao.insert(
                SessionRecordEntity(
                    topicId = record.topicId,
                    topicTitle = record.topicTitle,
                    completedAtMs = record.completedAtMs,
                    durationMs = record.durationMs,
                    fillerCount = record.fillerCount,
                    repetitionCount = record.repetitionCount,
                    interruptionCount = record.interruptionCount,
                    wordCount = record.wordCount,
                    topOffender = record.topOffender,
                )
            )
        }
    }

    override suspend fun recentSessions(limit: Int): List<SessionRecord> =
        withContext(Dispatchers.IO) {
            dao.recent(limit).map { entity ->
                SessionRecord(
                    topicId = entity.topicId,
                    topicTitle = entity.topicTitle,
                    completedAtMs = entity.completedAtMs,
                    durationMs = entity.durationMs,
                    fillerCount = entity.fillerCount,
                    repetitionCount = entity.repetitionCount,
                    interruptionCount = entity.interruptionCount,
                    wordCount = entity.wordCount,
                    topOffender = entity.topOffender,
                )
            }
        }

    override suspend fun totalSessionCount(): Int =
        withContext(Dispatchers.IO) { dao.totalSessionCount() }
}
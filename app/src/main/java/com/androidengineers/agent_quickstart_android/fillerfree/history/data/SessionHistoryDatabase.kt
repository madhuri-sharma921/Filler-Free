package com.androidengineers.agent_quickstart_android.fillerfree.history.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SessionRecordEntity::class], version = 1, exportSchema = false)
abstract class SessionHistoryDatabase : RoomDatabase() {
    abstract fun sessionHistoryDao(): SessionHistoryDao

    companion object {
        @Volatile
        private var instance: SessionHistoryDatabase? = null

        fun getInstance(context: Context): SessionHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SessionHistoryDatabase::class.java,
                    "filler_free_session_history.db",
                ).build().also { instance = it }
            }
    }
}
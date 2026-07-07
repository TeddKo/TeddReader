package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingSessionDao {
    @Upsert
    suspend fun upsertSession(session: ReadingSessionEntity)

    @Query("SELECT * FROM reading_sessions WHERE documentId = :documentId ORDER BY startedAtEpochMillis DESC")
    fun observeSessions(documentId: String): Flow<List<ReadingSessionEntity>>

    @Query("SELECT COALESCE(SUM(activeMillis), 0) FROM reading_sessions WHERE documentId = :documentId")
    suspend fun getTotalActiveMillis(documentId: String): Long
}

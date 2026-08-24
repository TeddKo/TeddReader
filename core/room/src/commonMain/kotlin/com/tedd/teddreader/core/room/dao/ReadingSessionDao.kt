package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reading sessions per document, and their summed active time.
 *
 * [getTotalActiveMillis] sums in SQL and coalesces to zero so a document with no sessions answers 0
 * rather than null — which is every document today, since nothing writes a session (see
 * ReadingStatsRepository).
 */
@Dao
interface ReadingSessionDao {
    /**
     * Writes a reading session, replacing the one with the same id so an open session can be closed.
     *
     * @param session the row to store.
     */
    @Upsert
    suspend fun upsertSession(session: ReadingSessionEntity)

    /**
     * @param documentId the document whose history to watch.
     * @return a flow of its sessions, newest first — empty today, since nothing writes one.
     */
    @Query("SELECT * FROM reading_sessions WHERE documentId = :documentId ORDER BY startedAtEpochMillis DESC")
    fun observeSessions(documentId: String): Flow<List<ReadingSessionEntity>>

    /**
     * @param documentId the document to total.
     * @return summed active reading time, coalesced to 0 so a document with no sessions answers zero rather
     * than null.
     */
    @Query("SELECT COALESCE(SUM(activeMillis), 0) FROM reading_sessions WHERE documentId = :documentId")
    suspend fun getTotalActiveMillis(documentId: String): Long
}

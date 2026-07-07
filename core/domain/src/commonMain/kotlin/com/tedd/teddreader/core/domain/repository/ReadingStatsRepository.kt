package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReadingStats
import kotlinx.coroutines.flow.Flow

data class ReadingSession(
    val id: String,
    val documentId: DocumentId,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val activeMillis: Long,
    val startLocation: ReaderLocation,
    val endLocation: ReaderLocation? = null,
) {
    init {
        require(id.isNotBlank()) { "ReadingSession id must not be blank." }
        require(startedAtEpochMillis >= 0L) { "startedAtEpochMillis must be positive." }
        require(endedAtEpochMillis == null || endedAtEpochMillis >= startedAtEpochMillis) {
            "endedAtEpochMillis must be after startedAtEpochMillis."
        }
        require(activeMillis >= 0L) { "activeMillis must be positive." }
    }
}

interface ReadingStatsRepository {
    fun observeSessions(documentId: DocumentId): Flow<List<ReadingSession>>
    suspend fun recordSession(session: ReadingSession)
    suspend fun getStats(documentId: DocumentId): ReadingStats
}

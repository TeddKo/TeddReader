package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReadingStats
import com.tedd.teddreader.core.data.mapper.toReadingSession
import com.tedd.teddreader.core.data.mapper.toReadingSessionEntity
import com.tedd.teddreader.core.domain.repository.ReadingSession
import com.tedd.teddreader.core.domain.repository.ReadingStatsRepository
import com.tedd.teddreader.core.room.dao.DocumentDao
import com.tedd.teddreader.core.room.dao.ReadingSessionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single(binds = [ReadingStatsRepository::class])
class ReadingStatsRepositoryImpl(
    private val readingSessionDao: ReadingSessionDao,
    private val documentDao: DocumentDao,
) : ReadingStatsRepository {
    override fun observeSessions(documentId: DocumentId): Flow<List<ReadingSession>> =
        readingSessionDao.observeSessions(documentId.value).map { sessions ->
            sessions.map { session -> session.toReadingSession() }
        }

    override suspend fun recordSession(session: ReadingSession) {
        readingSessionDao.upsertSession(session.toReadingSessionEntity())
    }

    override suspend fun getStats(documentId: DocumentId): ReadingStats {
        val document = documentDao.getDocument(documentId.value)
        return ReadingStats(
            documentId = documentId,
            activeMillis = readingSessionDao.getTotalActiveMillis(documentId.value),
            charactersRead = document?.characterCount ?: 0L,
            wordsRead = document?.wordCount ?: 0L,
        )
    }
}

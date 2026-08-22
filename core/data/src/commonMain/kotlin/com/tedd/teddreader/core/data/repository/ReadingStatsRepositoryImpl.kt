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

/**
 * [ReadingStatsRepository] backed by Room: turns the raw log of reading sessions into the numbers a
 * reader sees about how much of a book they have read. It draws on two Room tables — the session log
 * kept by [readingSessionDao] and the per-document character/word counts [documentDao] recorded once
 * when the document was imported — because neither table alone has both halves of "how much time, on
 * how much text".
 *
 * @property readingSessionDao Source of individual reading sessions and their summed active time.
 * @property documentDao Source of the document's own character and word counts.
 */
@Single(binds = [ReadingStatsRepository::class])
class ReadingStatsRepositoryImpl(
    private val readingSessionDao: ReadingSessionDao,
    private val documentDao: DocumentDao,
) : ReadingStatsRepository {
    /**
     * Every recorded reading session for [documentId], newest first, updating as new sessions land.
     *
     * @param documentId The document whose session history to observe.
     * @return A [Flow] of the document's [ReadingSession] list.
     */
    override fun observeSessions(documentId: DocumentId): Flow<List<ReadingSession>> =
        readingSessionDao.observeSessions(documentId.value).map { sessions ->
            sessions.map { session -> session.toReadingSession() }
        }

    /**
     * Appends [session] to the document's reading history. Sessions are never merged or overwritten
     * here; each call to this adds one more entry to the log [observeSessions] and [getStats] both
     * read from.
     *
     * @param session The completed (or in-progress) session to record.
     */
    override suspend fun recordSession(session: ReadingSession) {
        readingSessionDao.upsertSession(session.toReadingSessionEntity())
    }

    /**
     * Aggregates [documentId]'s reading activity into a single snapshot: total active time spent
     * reading, and how much of the document that time covers. The active time is summed across every
     * session on the fly rather than stored, since it changes on every read; the character and word
     * counts are the document's own, measured once at import and otherwise static, and default to `0`
     * when the document row cannot be found (e.g. it was deleted between a caller reading its id and
     * calling this).
     *
     * @param documentId The document to summarize.
     * @return A [ReadingStats] combining live active-time with the document's static size.
     */
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

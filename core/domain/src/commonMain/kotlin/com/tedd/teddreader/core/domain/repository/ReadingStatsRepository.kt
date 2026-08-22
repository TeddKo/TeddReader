package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReadingStats
import kotlinx.coroutines.flow.Flow

/**
 * One stretch of reading in one document: when it began, where it began, and how much of it the reader was
 * actually there for.
 *
 * Active time is stored rather than derived from the endpoints because wall-clock time includes a locked
 * screen and a backgrounded app — time nobody read. The endpoints are kept beside it for ordering and
 * display.
 *
 * Nothing writes one of these today (see [ReadingStatsRepository]), so every reading-time figure the app
 * shows is currently zero.
 *
 * @property id this session's own id, so an open session can be updated as it progresses and closed when
 * it ends.
 * @property documentId the document being read.
 * @property startedAtEpochMillis when the session began, which orders a document's history newest-first.
 * @property endedAtEpochMillis when it ended, or null while it is still open — which is what makes a
 * crash or a force-quit distinguishable from a clean close.
 * @property activeMillis how long the reader was actually reading, which is the figure statistics sum.
 * @property startLocation where in the document the session began.
 * @property endLocation where it ended, or null while the session is open.
 * @throws IllegalArgumentException if [id] is blank, if either timestamp is negative, if
 * [endedAtEpochMillis] precedes [startedAtEpochMillis], or if [activeMillis] is negative.
 */
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

/**
 * Reading history for one document, and the totals derived from it.
 *
 * Totals are a rollup rather than a stored row: reading time comes from summing the sessions, while the
 * character and word counts come from the document itself, so a re-parse that changes a book's length is
 * reflected without a stats migration.
 *
 * **The write half has no caller.** [recordSession] is implemented and reaches the database, but nothing
 * in the app starts, ends or stores a session, so [observeSessions] is always empty and the active time in
 * [getStats] is always zero — the document-info screen shows that zero honestly. The calculation that
 * turned wall-clock time minus idle stretches into [ReadingSession.activeMillis] was removed along with
 * the use-case layer for the same reason; recover it from git history when this feature is wired up rather
 * than writing it again.
 */
interface ReadingStatsRepository {
    /**
     * Follows one document's reading history.
     *
     * @param documentId the document whose sessions to watch.
     * @return a flow of its sessions, newest first, re-emitted on every change — empty today, since
     * nothing records one.
     */
    fun observeSessions(documentId: DocumentId): Flow<List<ReadingSession>>

    /**
     * Stores a session, replacing any session with the same id, so one can be updated as it ends.
     *
     * @param session the session to store.
     */
    suspend fun recordSession(session: ReadingSession)

    /**
     * Rolls up one document's reading totals.
     *
     * @param documentId the document to summarise.
     * @return its summed active reading time plus the book's own character and word counts. The time is
     * zero for a document with no sessions, which is every document today.
     */
    suspend fun getStats(documentId: DocumentId): ReadingStats
}

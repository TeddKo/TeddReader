package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderLocation
import kotlinx.coroutines.flow.Flow

/**
 * Where a reader left off in one document, and what the next open puts them back on.
 *
 * Built around [location] rather than a page number because a page number only means something for one
 * type size on one screen: expressing the position as an absolute place in the document's own text is
 * what lets it survive a font-size change, a re-import, and a device with a different screen.
 * [pageIndex] rides along only so a screen can show a number before it has laid anything out, and the
 * two are allowed to disagree — [location] is the one a resume uses.
 *
 * A caller that has no pagination yet must not save at all rather than save a page number dressed up as
 * an offset: writing that over a real position sends the reader back to page one of the book.
 *
 * @property documentId the document this position belongs to; one position is kept per document.
 * @property location the durable position, in the document's own terms (see [ReaderLocation]). This is
 * what a resume anchors on.
 * @property pageIndex the page the reader was looking at, as it was displayed — for showing progress,
 * never for resuming. Its `total` is "pages known then", which a later pagination may exceed.
 * @property updatedAtEpochMillis when this position was recorded. Stored verbatim and **not maintained
 * today**: the reader's save path passes 0, so anything that wants to order books by "last read" has to
 * start stamping this first.
 * @throws IllegalArgumentException if [updatedAtEpochMillis] is negative.
 */
data class ReadingProgress(
    val documentId: DocumentId,
    val location: ReaderLocation,
    val pageIndex: PageIndex,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(updatedAtEpochMillis >= 0L) { "updatedAtEpochMillis must be positive." }
    }
}

/**
 * The one place a reading position is kept, so every screen agrees on where a book is open.
 *
 * The reader writes here on every page turn and reads once per open; the library and document-info
 * screens read the same row to show progress. Exactly one position exists per document — [saveProgress]
 * replaces it — because "where am I in this book" has one answer, and a history of positions has never
 * been asked for.
 *
 * Null from [getProgress] and [observeProgress] means a book that has never been opened, which is not
 * the same as a book open at its first page: callers use that distinction to decide whether pagination
 * has a resume point to anchor on at all.
 */
interface ReaderRepository {
    /**
     * Follows one document's reading position, for a screen that displays progress while it changes.
     *
     * @param documentId the document to watch.
     * @return a flow that emits the stored position now and again on every later change, emitting null
     * while this book has never been opened.
     */
    fun observeProgress(documentId: DocumentId): Flow<ReadingProgress?>

    /**
     * Reads one document's position once, which is what opening a book does before it lays anything out.
     *
     * @param documentId the document being opened.
     * @return the stored position, or null when this book has never been opened — in which case the
     * reader starts from the beginning rather than from an anchor.
     */
    suspend fun getProgress(documentId: DocumentId): ReadingProgress?

    /**
     * Replaces this document's stored position.
     *
     * @param progress the position to store, whose [ReadingProgress.location] must come from real
     * pagination — see [ReadingProgress] for why a fabricated one is worse than not saving.
     */
    suspend fun saveProgress(progress: ReadingProgress)

    /**
     * Forgets where a book was, so the next open starts it from the beginning.
     *
     * @param documentId the document whose position is dropped.
     */
    suspend fun deleteProgress(documentId: DocumentId)
}

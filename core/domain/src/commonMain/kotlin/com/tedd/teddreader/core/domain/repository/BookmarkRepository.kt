package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import kotlinx.coroutines.flow.Flow

/**
 * A place in a document the reader asked to keep, and what the reader's "saved place" toggle stores.
 *
 * Positions are absolute places in the document's own text for the same reason a reading position is
 * (see [ReadingProgress]): a page number would stop pointing at this passage the moment the font size
 * changed. The id is composed rather than generated, which is what makes saving the same place twice
 * replace one row instead of accumulating duplicates, and lets the reader tell that the page it is
 * showing is already saved without asking storage.
 *
 * @property id `"<documentId>:<location>"`, composed by the caller (ReaderViewModel) — deliberately
 * derived from the position so it is stable and idempotent.
 * @property documentId the document this place belongs to.
 * @property location the saved position, in the document's own terms (see [ReaderLocation]).
 * @property label the reader's own name for this place; null for a place saved by the toggle alone.
 * @property note the reader's own note about the passage; null unless one was written.
 * @property createdAtEpochMillis when the place was saved, which is what orders the bookmarks screen
 * newest-first.
 */
data class Bookmark(
    val id: String,
    val documentId: DocumentId,
    val location: ReaderLocation,
    val label: String? = null,
    val note: String? = null,
    val createdAtEpochMillis: Long,
)

/**
 * Saved places, per document, shared by the reader and the bookmarks screen.
 *
 * The reader subscribes to [observeBookmarks] for the document it has open, so its own "this page is
 * saved" state comes from storage rather than from what it last wrote: one source of truth, and a place
 * saved or removed on another screen shows up in the reader immediately.
 */
interface BookmarkRepository {
    /**
     * Follows the saved places of one document.
     *
     * @param documentId the document whose places to watch.
     * @return a flow of every saved place in the document, newest first, re-emitted on every change.
     */
    fun observeBookmarks(documentId: DocumentId): Flow<List<Bookmark>>

    /**
     * Reads one saved place, for opening straight into it from outside the reader.
     *
     * @param bookmarkId the composed id (see [Bookmark.id]).
     * @return the saved place, or null when nothing is stored under that id.
     */
    suspend fun getBookmark(bookmarkId: String): Bookmark?

    /**
     * Stores a place, replacing any place with the same id.
     *
     * @param bookmark the place to store; because [Bookmark.id] is derived from the position, saving the
     * same page twice is idempotent rather than duplicating it.
     */
    suspend fun saveBookmark(bookmark: Bookmark)

    /**
     * Removes a saved place, which is what the reader's toggle does on a page that already has one.
     *
     * @param bookmarkId the composed id of the place to remove.
     */
    suspend fun deleteBookmark(bookmarkId: String)
}

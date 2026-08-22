package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.domain.repository.BookmarkRepository
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.data.mapper.toBookmark
import com.tedd.teddreader.core.data.mapper.toBookmarkEntity
import com.tedd.teddreader.core.room.dao.BookmarkDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

/**
 * Saved places, backed by the bookmarks table.
 *
 * Nothing here but mapping and delegation, which is the point: a saved place has no rule of its own beyond
 * the composed id its caller builds (see [Bookmark.id]), so this stays data access and the reader keeps the
 * decision about *when* a place is saved.
 *
 * @property bookmarkDao the table this reads and writes.
 */
@Single(binds = [BookmarkRepository::class])
class BookmarkRepositoryImpl(
    private val bookmarkDao: BookmarkDao,
) : BookmarkRepository {
    /**
     * The saved places for [documentId], re-emitted whenever one is added or removed.
     *
     * @param documentId the document whose bookmarks to observe.
     * @return the current list, and every later change, mapped from the stored entity shape.
     */
    override fun observeBookmarks(documentId: DocumentId): Flow<List<Bookmark>> =
        bookmarkDao.observeBookmarks(documentId.value).map { bookmarks -> bookmarks.map { it.toBookmark() } }

    /**
     * One saved place by its id.
     *
     * @param bookmarkId the bookmark's id.
     * @return the bookmark, or null when no row matches [bookmarkId].
     */
    override suspend fun getBookmark(bookmarkId: String): Bookmark? =
        bookmarkDao.getBookmark(bookmarkId)?.toBookmark()

    /**
     * Inserts [bookmark], or replaces the existing row with the same id.
     *
     * @param bookmark the place to save.
     */
    override suspend fun saveBookmark(bookmark: Bookmark) {
        bookmarkDao.upsertBookmark(bookmark.toBookmarkEntity())
    }

    /**
     * Removes the saved place with [bookmarkId], if one exists.
     *
     * @param bookmarkId the bookmark's id.
     */
    override suspend fun deleteBookmark(bookmarkId: String) {
        bookmarkDao.deleteBookmark(bookmarkId)
    }
}

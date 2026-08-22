package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

/**
 * Saved places, newest first, per document.
 *
 * The id is composed by the caller from the document and the position rather than generated here, so
 * saving the same place twice upserts the one row instead of accumulating duplicates.
 */
@Dao
interface BookmarkDao {
    /**
     * Inserts a saved place or replaces the one with the same id.
     *
     * @param bookmark the row to store; because its id is derived from the position, saving the same page
     * twice replaces one row rather than adding another.
     */
    @Upsert
    suspend fun upsertBookmark(bookmark: BookmarkEntity)

    /**
     * @param documentId the document whose places to watch.
     * @return a flow of its saved places, newest first, re-emitted on every change.
     */
    @Query("SELECT * FROM bookmarks WHERE documentId = :documentId ORDER BY createdAtEpochMillis DESC")
    fun observeBookmarks(documentId: String): Flow<List<BookmarkEntity>>

    /**
     * @param bookmarkId the composed id of the place.
     * @return its row, or null when nothing is stored under that id.
     */
    @Query("SELECT * FROM bookmarks WHERE id = :bookmarkId")
    suspend fun getBookmark(bookmarkId: String): BookmarkEntity?

    /**
     * @param bookmarkId the composed id of the place to remove.
     */
    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: String)
}

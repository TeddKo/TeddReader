package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Upsert
    suspend fun upsertBookmark(bookmark: BookmarkEntity)

    @Query("SELECT * FROM bookmarks WHERE documentId = :documentId ORDER BY createdAtEpochMillis DESC")
    fun observeBookmarks(documentId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :bookmarkId")
    suspend fun getBookmark(bookmarkId: String): BookmarkEntity?

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: String)
}

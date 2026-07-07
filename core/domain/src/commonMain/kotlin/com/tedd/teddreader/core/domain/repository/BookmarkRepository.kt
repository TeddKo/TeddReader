package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import kotlinx.coroutines.flow.Flow

data class Bookmark(
    val id: String,
    val documentId: DocumentId,
    val location: ReaderLocation,
    val label: String? = null,
    val note: String? = null,
    val createdAtEpochMillis: Long,
)

interface BookmarkRepository {
    fun observeBookmarks(documentId: DocumentId): Flow<List<Bookmark>>
    suspend fun getBookmark(bookmarkId: String): Bookmark?
    suspend fun saveBookmark(bookmark: Bookmark)
    suspend fun deleteBookmark(bookmarkId: String)
}

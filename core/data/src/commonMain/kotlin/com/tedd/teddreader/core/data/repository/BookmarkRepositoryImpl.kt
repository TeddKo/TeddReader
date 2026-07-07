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

@Single(binds = [BookmarkRepository::class])
class BookmarkRepositoryImpl(
    private val bookmarkDao: BookmarkDao,
) : BookmarkRepository {
    override fun observeBookmarks(documentId: DocumentId): Flow<List<Bookmark>> =
        bookmarkDao.observeBookmarks(documentId.value).map { bookmarks -> bookmarks.map { it.toBookmark() } }

    override suspend fun getBookmark(bookmarkId: String): Bookmark? =
        bookmarkDao.getBookmark(bookmarkId)?.toBookmark()

    override suspend fun saveBookmark(bookmark: Bookmark) {
        bookmarkDao.upsertBookmark(bookmark.toBookmarkEntity())
    }

    override suspend fun deleteBookmark(bookmarkId: String) {
        bookmarkDao.deleteBookmark(bookmarkId)
    }
}

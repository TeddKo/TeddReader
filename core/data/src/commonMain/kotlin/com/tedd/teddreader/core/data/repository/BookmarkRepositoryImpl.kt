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
 * 저장된 위치들, bookmarks 테이블 기반.
 *
 * 여기엔 매핑과 위임 말고는 아무것도 없다는 게 핵심이다: 저장된 위치는 호출자가 만드는 합성 id
 * ([Bookmark.id] 참고) 외에 자체 규칙이 없으므로, 이 클래스는 데이터 접근에 머물고 *언제* 위치를
 * 저장할지에 대한 결정은 리더 쪽이 갖는다.
 *
 * @property bookmarkDao 읽고 쓰는 대상 테이블.
 */
@Single(binds = [BookmarkRepository::class])
class BookmarkRepositoryImpl(
    private val bookmarkDao: BookmarkDao,
) : BookmarkRepository {
    /**
     * [documentId]에 대해 저장된 위치들. 하나가 추가되거나 제거될 때마다 다시 방출된다.
     *
     * @param documentId 북마크를 관찰할 문서.
     * @return 현재 목록과 이후의 모든 변경 사항. 저장된 엔티티 형태로부터 매핑됨.
     */
    override fun observeBookmarks(documentId: DocumentId): Flow<List<Bookmark>> =
        bookmarkDao.observeBookmarks(documentId.value).map { bookmarks -> bookmarks.map { it.toBookmark() } }

    /**
     * id로 조회하는 저장된 위치 하나.
     *
     * @param bookmarkId 북마크의 id.
     * @return 해당 북마크, 또는 [bookmarkId]와 일치하는 행이 없으면 null.
     */
    override suspend fun getBookmark(bookmarkId: String): Bookmark? =
        bookmarkDao.getBookmark(bookmarkId)?.toBookmark()

    /**
     * [bookmark]를 삽입하거나, 같은 id를 가진 기존 행을 대체한다.
     *
     * @param bookmark 저장할 위치.
     */
    override suspend fun saveBookmark(bookmark: Bookmark) {
        bookmarkDao.upsertBookmark(bookmark.toBookmarkEntity())
    }

    /**
     * [bookmarkId]에 해당하는 저장된 위치를 존재할 경우 제거한다.
     *
     * @param bookmarkId 북마크의 id.
     */
    override suspend fun deleteBookmark(bookmarkId: String) {
        bookmarkDao.deleteBookmark(bookmarkId)
    }
}

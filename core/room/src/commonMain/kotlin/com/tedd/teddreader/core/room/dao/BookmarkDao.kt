package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

/**
 * 문서별 저장 위치를 최신순으로 관리합니다.
 *
 * id는 여기서 생성하지 않고 호출자가 문서와 위치로 조합하므로 같은 위치를 두 번 저장해도 중복이 쌓이지 않고
 * 하나의 행을 업서트합니다.
 */
@Dao
interface BookmarkDao {
    /**
     * 저장 위치를 삽입하거나 같은 id의 기존 위치를 교체합니다.
     *
     * @param bookmark 저장할 행입니다. id가 위치에서 파생되므로 같은 페이지를 두 번 저장하면 행을 추가하지 않고
     * 하나의 행을 교체합니다.
     */
    @Upsert
    suspend fun upsertBookmark(bookmark: BookmarkEntity)

    /**
     * @param documentId 저장 위치를 관찰할 문서입니다.
     * @return 저장 위치를 최신순으로 제공하고 변경될 때마다 다시 방출하는 Flow입니다.
     */
    @Query("SELECT * FROM bookmarks WHERE documentId = :documentId ORDER BY createdAtEpochMillis DESC")
    fun observeBookmarks(documentId: String): Flow<List<BookmarkEntity>>

    /**
     * @param bookmarkId 위치에서 조합한 id입니다.
     * @return 해당 행이며, 이 id로 저장된 항목이 없으면 null입니다.
     */
    @Query("SELECT * FROM bookmarks WHERE id = :bookmarkId")
    suspend fun getBookmark(bookmarkId: String): BookmarkEntity?

    /**
     * @param bookmarkId 삭제할 위치에서 조합한 id입니다.
     */
    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmark(bookmarkId: String)
}

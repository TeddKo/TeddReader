package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import kotlinx.coroutines.flow.Flow

/**
 * 독자가 보관하도록 요청한 문서 위치이며, 리더의 "저장한 위치" 토글이 저장하는 값이다.
 *
 * 읽기 위치와 같은 이유로 문서 자체 텍스트의 절대 위치를 사용한다([ReadingProgress] 참고). 페이지 번호는 글꼴
 * 크기가 바뀌는 순간 해당 구절을 가리키지 않게 된다. 식별자는 생성하지 않고 조합한다. 따라서 같은 위치를 두 번
 * 저장해도 중복이 쌓이지 않고 행 하나를 교체하며, 리더는 저장소에 묻지 않고도 표시 중인 페이지가 이미
 * 저장됐는지 알 수 있다.
 *
 * @property id 호출자(ReaderViewModel)가 조합한 `"<documentId>:<location>"`. 위치에서 의도적으로 파생해
 * 안정적이고 멱등성을 갖는다.
 * @property documentId 이 위치가 속한 문서.
 * @property location 문서 자체 기준의 저장 위치([ReaderLocation] 참고).
 * @property label 이 위치에 독자가 붙인 이름. 토글로만 저장한 위치는 null.
 * @property note 해당 구절에 독자가 작성한 메모. 작성하지 않았으면 null.
 * @property createdAtEpochMillis 위치를 저장한 시각. 북마크 화면을 최신순으로 정렬한다.
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
 * 문서별 저장 위치로, 리더와 북마크 화면이 공유한다.
 *
 * 리더는 열어 둔 문서의 [observeBookmarks]를 구독하므로 자체 "이 페이지는 저장됨" 상태는 마지막으로 쓴
 * 값이 아니라 저장소에서 온다. 진실 공급원이 하나이며 다른 화면에서 위치를 저장하거나 제거하면 리더에 즉시
 * 반영된다.
 */
interface BookmarkRepository {
    /**
     * 문서 하나의 저장 위치를 관찰한다.
     *
     * @param documentId 위치를 관찰할 문서.
     * @return 문서의 모든 저장 위치를 최신순으로 제공하고 변경마다 다시 방출하는 플로우.
     */
    fun observeBookmarks(documentId: DocumentId): Flow<List<Bookmark>>

    /**
     * 리더 외부에서 바로 열기 위해 저장 위치 하나를 읽는다.
     *
     * @param bookmarkId 조합된 식별자([Bookmark.id] 참고).
     * @return 저장 위치. 해당 식별자로 저장된 것이 없으면 null.
     */
    suspend fun getBookmark(bookmarkId: String): Bookmark?

    /**
     * 위치를 저장하며 같은 식별자의 위치가 있으면 교체한다.
     *
     * @param bookmark 저장할 위치. [Bookmark.id]는 위치에서 파생되므로 같은 페이지를 두 번 저장해도 중복되지 않고
     * 멱등성을 갖는다.
     */
    suspend fun saveBookmark(bookmark: Bookmark)

    /**
     * 저장 위치를 제거한다. 이미 저장된 페이지에서 리더의 토글이 수행하는 동작이다.
     *
     * @param bookmarkId 제거할 위치의 조합된 식별자.
     */
    suspend fun deleteBookmark(bookmarkId: String)
}

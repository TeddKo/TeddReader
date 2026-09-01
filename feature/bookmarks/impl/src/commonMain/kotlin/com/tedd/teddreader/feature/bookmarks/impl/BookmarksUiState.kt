package com.tedd.teddreader.feature.bookmarks.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.domain.repository.Bookmark
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * 북마크 화면이 현재 렌더링하는 상태이다.
 *
 * @property documentId 이 북마크들이 속한 문서이다. 화면의 뷰 모델이 경로에서 값을 확인하기 전에는
 *   비어 있다.
 * @property bookmarks 화면에 표시되는 순서대로 정렬된 문서의 저장 위치 목록이다.
 * @property editingBookmark 이름 변경/편집 시트에 현재 열려 있는 북마크이며, 표시 중인 시트가 없으면
 *   null이다.
 * @property isLoading [bookmarks]가 저장소에서 실제로 읽은 결과가 아니라 아직 불러오지 않은 초기 기본값을
 *   나타내는지 여부이다.
 * @property errorMessage 최근 실패한 작업을 사용자에게 알리는 메시지이며, 보고할 내용이 없으면 null이다.
 */
@Immutable
data class BookmarksUiState(
    val documentId: String = "",
    val bookmarks: ImmutableList<Bookmark> = persistentListOf(),
    val editingBookmark: Bookmark? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

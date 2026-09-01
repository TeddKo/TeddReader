package com.tedd.teddreader.feature.search.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.SearchResult
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * [SearchViewModel]이 발행하고 [SearchScreen]이 표시하는 검색 화면의 상태 스냅샷이다.
 *
 * @property documentId 검색 중인 문서로, [SearchViewModel.setDocument]가 설정한다.
 * @property query 현재 검색 필드에 입력된 텍스트로, 로컬 Compose 텍스트 필드 상태에만 두지
 * 않고 재구성 후에도 유지되도록 여기에 보관한다.
 * @property results 가장 최근에 완료된 검색에서 찾은 일치 항목을 읽기 순서로 담은 목록이다.
 * 검색을 실행하기 전이나 마지막 검색 결과가 없으면 비어 있다.
 * @property isLoading 검색이 진행 중이면 true이다. 첫 검색을 실행하기 전을 포함해 그 외에는
 * false이다.
 * @property errorMessage 사용자에게 알릴 가장 최근 오류이며, 알릴 오류가 없으면 null이다.
 * 사용자가 새 검색어를 입력하기 시작하면 [SearchViewModel.updateQuery]가 즉시 비운다.
 * @property isSearchUnsupported 현재 문서 형식에 검색할 저장 텍스트가 없으면 true이다. PDF,
 * CBZ, 이미지 같은 시각적 페이지 형식에서 항상 실패할 검색을 실행하게 두지 않고 검색 필드
 * 전체를 비활성화하도록 화면에 알린다.
 */
@Immutable
data class SearchUiState(
    val documentId: String = "",
    val query: String = "",
    val results: ImmutableList<SearchResult> = persistentListOf(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSearchUnsupported: Boolean = false,
)

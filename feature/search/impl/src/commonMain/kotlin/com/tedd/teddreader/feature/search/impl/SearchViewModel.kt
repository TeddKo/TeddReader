package com.tedd.teddreader.feature.search.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.suspendRunCatching
import com.tedd.teddreader.core.domain.usecase.SearchDocumentUseCase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/**
 * 문서 하나에 대한 텍스트 검색을 소유한다: [setDocument]가 검색 대상 문서를 바꾸고 이 문서가
 * 검색을 지원하는지 미리 확인하며, [updateQuery]가 검색어 입력을 받고, [search]가 실제 검색을
 * 실행한다.
 *
 * [setDocument]와 [search]가 여는 코루틴은 suspend 지점을 지난 뒤 [_uiState]에 결과를 반영하기
 * 전에 자신이 시작된 시점의 documentId([search]는 검색어까지)를 다시 대조한다. `Job.cancel()`은
 * 이미 진행 중인 검색을 즉시 멈추지 못하므로, 이 재확인이 없으면 사용자가 이미 다른 문서로
 * 넘어가거나 새 검색어를 입력한 뒤에도 낡은 검색 결과가 화면에 나타날 수 있다.
 *
 * @property searchDocument 문서 안에서 검색어에 매칭되는 결과를 찾는 유스케이스.
 */
@KoinViewModel
class SearchViewModel(
    private val searchDocument: SearchDocumentUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState

    private var searchJob: Job? = null

    /**
     * 검색 대상 문서를 [documentId]로 전환한다. 이미 같은 문서면 아무 것도 하지 않아 입력해 둔
     * 검색어나 진행 중인 검색을 불필요하게 리셋하지 않는다. 그 외에는 진행 중인 검색을 취소하고
     * 검색어·결과·오류를 모두 초기 상태로 되돌린 뒤, 빈 검색어로 [searchDocument]를 한 번
     * 호출해 [SearchUiState.isSearchUnsupported]를 미리 채워 둔다 — 이 플래그는 문서 형식만으로
     * 결정되므로 실제 검색어 없이도 알아낼 수 있다. 응답이 도착했을 때 이미 다른 문서로
     * 전환돼 있으면 결과를 반영하지 않는다.
     *
     * @param documentId 전환할 대상 문서의 id.
     */
    fun setDocument(documentId: String) {
        if (_uiState.value.documentId == documentId) return
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                documentId = documentId,
                query = "",
                results = persistentListOf(),
                isLoading = false,
                errorMessage = null,
                isSearchUnsupported = false,
            )
        }
        searchJob = viewModelScope.launch {
            val result = suspendRunCatching { searchDocument(DocumentId(documentId), "") }
                .getOrElse { throwable ->
                    if (_uiState.value.documentId == documentId) {
                        _uiState.update {
                            it.copy(errorMessage = throwable.message ?: MetadataLoadFailedMessage)
                        }
                    }
                    return@launch
                }
            if (_uiState.value.documentId == documentId) {
                _uiState.update {
                    it.copy(isSearchUnsupported = result.isUnsupported)
                }
            }
        }
    }

    /**
     * 검색 필드의 텍스트를 [query]로 갱신한다. 검색이 진행 중이었다면 그 검색을 취소해, 새
     * 검색어를 입력하는 도중에 이전 검색어의 결과나 오류가 뒤늦게 화면에 나타나지 않도록 한다.
     * 실제 검색은 실행하지 않으며, 호출자가 [search]를 별도로 호출해야 한다.
     *
     * @param query 검색 필드에 새로 입력된 텍스트.
     */
    fun updateQuery(query: String) {
        if (_uiState.value.isLoading) {
            searchJob?.cancel()
        }
        _uiState.update { state ->
            state.copy(
                query = query,
                errorMessage = null,
                isLoading = false,
            )
        }
    }

    /**
     * 현재 검색어로 [searchDocument]를 실행해 [SearchUiState.results]를 갱신한다. 검색어가
     * 비어 있으면 요청을 보내지 않고 결과와 오류만 비운다. 응답을 반영하기 전에는 문서 id와
     * 검색어가 요청 시점과 여전히 같은지 함께 확인하므로, 사용자가 응답을 기다리는 동안
     * 검색어를 바꾸거나 문서를 전환해도 그 낡은 응답이 새 상태를 덮어쓰지 않는다. 성공하면
     * [SearchUiState.query]도 [searchDocument]가 돌려준 트리밍된 검색어로 맞춘다.
     */
    fun search() {
        val state = _uiState.value
        if (state.query.isBlank()) {
            searchJob?.cancel()
            _uiState.update { it.copy(results = persistentListOf(), errorMessage = null, isLoading = false) }
            return
        }

        searchJob?.cancel()
        val requestedQuery = state.query
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = suspendRunCatching { searchDocument(DocumentId(state.documentId), requestedQuery) }
                .getOrElse { throwable ->
                    if (_uiState.value.documentId == state.documentId && _uiState.value.query == requestedQuery) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = throwable.message ?: "Search failed.",
                            )
                        }
                    }
                    return@launch
                }
            if (_uiState.value.documentId == state.documentId && _uiState.value.query == requestedQuery) {
                _uiState.update {
                    it.copy(
                        query = result.query,
                        results = result.results.toImmutableList(),
                        isLoading = false,
                        isSearchUnsupported = result.isUnsupported,
                    )
                }
            }
        }
    }
}

private const val MetadataLoadFailedMessage = "Failed to load document metadata."

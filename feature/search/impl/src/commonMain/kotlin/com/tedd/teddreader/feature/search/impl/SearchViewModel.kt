package com.tedd.teddreader.feature.search.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.domain.usecase.SearchDocumentUseCase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class SearchViewModel(
    private val searchDocument: SearchDocumentUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState

    private var searchJob: Job? = null

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
            val result = runCatching { searchDocument(DocumentId(documentId), "") }
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
            val result = runCatching { searchDocument(DocumentId(state.documentId), requestedQuery) }
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

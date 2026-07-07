package com.tedd.teddreader.feature.search.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.usecase.FindInDocumentUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

private const val PdfSearchUnsupportedMessage = "PDF text search is not available yet."

@KoinViewModel
class SearchViewModel(
    private val findInDocument: FindInDocumentUseCase,
    private val documentRepository: DocumentRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState

    fun setDocument(documentId: String) {
        _uiState.update {
            it.copy(
                documentId = documentId,
                results = emptyList(),
                errorMessage = null,
                unsupportedMessage = null,
            )
        }
        viewModelScope.launch {
            runCatching { documentRepository.getDocument(DocumentId(documentId)) }
                .onSuccess { metadata ->
                    _uiState.update {
                        it.copy(
                            unsupportedMessage = metadata
                                ?.takeIf { document -> document.format == DocumentFormat.PDF }
                                ?.let { PdfSearchUnsupportedMessage },
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(errorMessage = throwable.message ?: "Failed to load document metadata.")
                    }
                }
        }
    }

    fun updateQuery(query: String) {
        _uiState.update { state -> state.copy(query = query, errorMessage = null) }
    }

    fun search() {
        val state = _uiState.value
        if (state.query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), errorMessage = null) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val documentId = runCatching { DocumentId(state.documentId) }
                .getOrElse { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = throwable.message ?: "Invalid document.",
                        )
                    }
                    return@launch
                }
            val metadata = runCatching { documentRepository.getDocument(documentId) }
                .getOrElse { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = throwable.message ?: "Failed to load document metadata.",
                        )
                    }
                    return@launch
                }
            if (metadata?.format == DocumentFormat.PDF) {
                _uiState.update {
                    it.copy(
                        results = emptyList(),
                        isLoading = false,
                        unsupportedMessage = PdfSearchUnsupportedMessage,
                    )
                }
                return@launch
            }

            runCatching {
                findInDocument(documentId, state.query)
            }.onSuccess { results ->
                _uiState.update {
                    it.copy(
                        results = results,
                        isLoading = false,
                        unsupportedMessage = null,
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = throwable.message ?: "Search failed.",
                    )
                }
            }
        }
    }
}

package com.tedd.teddreader.feature.document_info.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.suspendRunCatching
import com.tedd.teddreader.core.domain.repository.ReadingStatsRepository
import com.tedd.teddreader.core.domain.usecase.GetDocumentInfoUseCase
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class DocumentInfoViewModel(
    private val getDocumentInfo: GetDocumentInfoUseCase,
    private val readingStatsRepository: ReadingStatsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DocumentInfoUiState())
    val uiState: StateFlow<DocumentInfoUiState> = _uiState

    private var infoJob: Job? = null
    private var sessionsJob: Job? = null

    fun setDocument(documentIdValue: String) {
        if (_uiState.value.documentId == documentIdValue) return
        val documentId = DocumentId(documentIdValue)
        _uiState.value = DocumentInfoUiState(documentId = documentIdValue)

        infoJob?.cancel()
        infoJob = viewModelScope.launch {
            suspendRunCatching { getDocumentInfo(documentId) }
                .onSuccess { info ->
                    if (_uiState.value.documentId == documentIdValue) {
                        _uiState.update {
                            it.copy(
                                metadata = info.metadata,
                                pageIndex = info.pageIndex,
                                stats = info.stats,
                                isLoading = false,
                            )
                        }
                    }
                }
                .onFailure { throwable ->
                    if (_uiState.value.documentId == documentIdValue) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = throwable.message ?: "Failed to load document info.",
                            )
                        }
                    }
                }
        }

        sessionsJob?.cancel()
        sessionsJob = viewModelScope.launch {
            readingStatsRepository.observeSessions(documentId)
                .catch { throwable ->
                    if (_uiState.value.documentId == documentIdValue) {
                        _uiState.update { it.copy(errorMessage = throwable.message ?: "Failed to load sessions.") }
                    }
                }
                .collect { sessions ->
                    if (_uiState.value.documentId == documentIdValue) {
                        _uiState.update { it.copy(sessions = sessions.toImmutableList()) }
                    }
                }
        }
    }
}

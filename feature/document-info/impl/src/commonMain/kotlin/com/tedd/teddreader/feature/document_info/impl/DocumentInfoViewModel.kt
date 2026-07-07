package com.tedd.teddreader.feature.document_info.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReadingStatsRepository
import com.tedd.teddreader.core.domain.usecase.CalculateReadingStatsUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class DocumentInfoViewModel(
    private val documentRepository: DocumentRepository,
    private val readerRepository: ReaderRepository,
    private val readingStatsRepository: ReadingStatsRepository,
    private val calculateReadingStats: CalculateReadingStatsUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DocumentInfoUiState())
    val uiState: StateFlow<DocumentInfoUiState> = _uiState

    private var sessionsJob: Job? = null

    fun setDocument(documentIdValue: String) {
        if (_uiState.value.documentId == documentIdValue) return
        val documentId = DocumentId(documentIdValue)
        _uiState.value = DocumentInfoUiState(documentId = documentIdValue)

        viewModelScope.launch {
            runCatching {
                val metadata = documentRepository.getDocument(documentId)
                val progress = readerRepository.getProgress(documentId)
                val stats = calculateReadingStats(documentId)
                _uiState.update {
                    it.copy(
                        metadata = metadata,
                        pageIndex = progress?.pageIndex,
                        stats = stats,
                        isLoading = false,
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = throwable.message ?: "Failed to load document info.",
                    )
                }
            }
        }

        sessionsJob?.cancel()
        sessionsJob = viewModelScope.launch {
            readingStatsRepository.observeSessions(documentId)
                .catch { throwable ->
                    _uiState.update { it.copy(errorMessage = throwable.message ?: "Failed to load sessions.") }
                }
                .collect { sessions ->
                    _uiState.update { it.copy(sessions = sessions) }
                }
        }
    }
}

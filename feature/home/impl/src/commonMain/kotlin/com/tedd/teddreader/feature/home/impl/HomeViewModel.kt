package com.tedd.teddreader.feature.home.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class HomeViewModel(
    private val documentRepository: DocumentRepository,
) : ViewModel() {
    private val controls = MutableStateFlow(HomeControls())

    val uiState: StateFlow<HomeUiState> = combine(
        documentRepository.observeRecentDocuments().catch { throwable ->
            emit(emptyList())
            controls.update { it.copy(errorMessage = throwable.message ?: "Failed load recent documents.") }
        },
        controls,
    ) { documents, controls ->
        val visibleDocuments = documents
            .filterBy(controls.formatFilter)
            .sortBy(controls.sort)
        HomeUiState(
            favoriteDocuments = visibleDocuments.filter { it.isBookmarked },
            recentDocuments = visibleDocuments.filterNot { it.isBookmarked },
            hasDocuments = documents.isNotEmpty(),
            sort = controls.sort,
            formatFilter = controls.formatFilter,
            isLoading = false,
            errorMessage = controls.errorMessage,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HomeUiState(),
    )

    fun updateSort(sort: HomeSort) {
        controls.update { it.copy(sort = sort) }
    }

    fun updateFormatFilter(filter: HomeFormatFilter) {
        controls.update { it.copy(formatFilter = filter) }
    }

    fun setDocumentBookmarked(documentId: DocumentId, isBookmarked: Boolean) {
        viewModelScope.launch {
            runCatching {
                val document = documentRepository.getDocument(documentId) ?: return@runCatching
                documentRepository.upsertDocument(document.copy(isBookmarked = isBookmarked))
            }
                .onFailure { controls.update { it.copy(errorMessage = "Failed to update document.") } }
        }
    }

    fun deleteDocument(documentId: DocumentId) {
        viewModelScope.launch {
            runCatching { documentRepository.deleteDocument(documentId) }
                .onFailure { controls.update { it.copy(errorMessage = "Failed to delete document.") } }
        }
    }
}

private data class HomeControls(
    val sort: HomeSort = HomeSort.Recent,
    val formatFilter: HomeFormatFilter = HomeFormatFilter.All,
    val errorMessage: String? = null,
)

private fun List<DocumentMetadata>.filterBy(filter: HomeFormatFilter): List<DocumentMetadata> = when (filter) {
    HomeFormatFilter.All -> this
    HomeFormatFilter.Txt -> filter { it.format == DocumentFormat.TXT }
    HomeFormatFilter.Pdf -> filter { it.format == DocumentFormat.PDF }
    HomeFormatFilter.Epub -> filter { it.format == DocumentFormat.EPUB }
}

private fun List<DocumentMetadata>.sortBy(sort: HomeSort): List<DocumentMetadata> = when (sort) {
    HomeSort.Recent -> sortedByDescending { it.lastOpenedAtEpochMillis ?: it.addedAtEpochMillis }
    HomeSort.Title -> sortedBy { it.location.displayName.lowercase() }
    HomeSort.Format -> sortedWith(compareBy<DocumentMetadata> { it.format.name }.thenBy { it.location.displayName.lowercase() })
}

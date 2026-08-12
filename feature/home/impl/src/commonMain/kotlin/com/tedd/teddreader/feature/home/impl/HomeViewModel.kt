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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class HomeViewModel(
    private val documentRepository: DocumentRepository,
) : ViewModel() {
    private val controls = MutableStateFlow(HomeControls())
    private val recentDocuments = documentRepository.observeRecentDocuments()
        .map { documents -> documents as List<DocumentMetadata>? }
        .onStart { emit(null) }
        .catch { throwable ->
            controls.update { it.copy(errorMessage = throwable.message ?: "Failed load recent documents.") }
            emit(emptyList())
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            null,
        )
    private val documentCoverImages = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    private var attemptedCoverIds: Set<String> = emptySet()
    private var currentDocumentIds: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            recentDocuments.filterNotNull().collect { documents ->
                val documentIds = documents.mapTo(linkedSetOf()) { it.id.value }
                currentDocumentIds = documentIds
                documentCoverImages.update { current -> current.filterKeys { it in documentIds } }
                attemptedCoverIds = attemptedCoverIds.filter { it in documentIds }.toSet()

                documents.asSequence()
                    .filter { it.format == DocumentFormat.PDF || it.format == DocumentFormat.EPUB }
                    .map { it.id.value }
                    .filterNot { id -> id in documentCoverImages.value || id in attemptedCoverIds }
                    .forEach { documentIdValue ->
                        attemptedCoverIds = attemptedCoverIds + documentIdValue
                        viewModelScope.launch {
                            val coverBytes = runCatching {
                                documentRepository.getDocumentCover(DocumentId(documentIdValue))
                            }.getOrNull()
                            if (coverBytes != null && documentIdValue in currentDocumentIds) {
                                documentCoverImages.update { current -> current + (documentIdValue to coverBytes) }
                            }
                        }
                    }
            }
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        recentDocuments,
        controls,
        documentCoverImages,
    ) { documents, controls, coverImages ->
        val visibleDocuments = (documents ?: emptyList())
            .filterBy(controls.formatFilter)
            .sortBy(controls.sort)
        val visibleCoverImages = coverImages.filterKeys { key ->
            visibleDocuments.any { it.id.value == key }
        }
        HomeUiState(
            favoriteDocuments = visibleDocuments.filter { it.isBookmarked },
            recentDocuments = visibleDocuments.filterNot { it.isBookmarked },
            documentCoverImages = visibleCoverImages,
            hasDocuments = documents?.isNotEmpty() == true,
            sort = controls.sort,
            formatFilter = controls.formatFilter,
            isLoading = documents == null,
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
        setDocumentsBookmarked(listOf(documentId), isBookmarked)
    }

    fun setDocumentsBookmarked(documentIds: Collection<DocumentId>, isBookmarked: Boolean) {
        viewModelScope.launch {
            runCatching {
                documentIds.forEach { documentId ->
                    val document = documentRepository.getDocument(documentId) ?: return@forEach
                    if (document.isBookmarked != isBookmarked) {
                        documentRepository.upsertDocument(document.copy(isBookmarked = isBookmarked))
                    }
                }
            }
                .onFailure { controls.update { it.copy(errorMessage = "Failed to update document.") } }
        }
    }

    fun deleteDocument(documentId: DocumentId) {
        deleteDocuments(listOf(documentId))
    }

    fun deleteDocuments(documentIds: Collection<DocumentId>) {
        viewModelScope.launch {
            runCatching {
                documentIds.forEach { documentId -> documentRepository.deleteDocument(documentId) }
            }.onFailure { controls.update { it.copy(errorMessage = "Failed to delete document.") } }
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

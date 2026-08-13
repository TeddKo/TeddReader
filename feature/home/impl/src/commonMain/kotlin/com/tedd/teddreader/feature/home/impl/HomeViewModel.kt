package com.tedd.teddreader.feature.home.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import kotlin.random.Random

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
            recentDocuments.filterNotNull().collectLatest { documents ->
                val documentIds = documents.mapTo(linkedSetOf()) { it.id.value }
                currentDocumentIds = documentIds
                documentCoverImages.update { current -> current.filterKeys { it in documentIds } }
                attemptedCoverIds = attemptedCoverIds.filter { it in documentIds }.toSet()

                documents.asSequence()
                    .filter { it.format == DocumentFormat.PDF || it.format == DocumentFormat.EPUB }
                    .map { it.id.value }
                    .filterNot { id -> id in documentCoverImages.value || id in attemptedCoverIds }
                    .forEach { documentIdValue ->
                        val coverBytes = try {
                            documentRepository.getDocumentCover(DocumentId(documentIdValue))
                        } catch (cancellationException: CancellationException) {
                            throw cancellationException
                        } catch (_: Throwable) {
                            null
                        }
                        attemptedCoverIds = attemptedCoverIds + documentIdValue
                        if (coverBytes != null && documentIdValue in currentDocumentIds) {
                            documentCoverImages.update { current -> current + (documentIdValue to coverBytes) }
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
        val filterMatchedDocuments = (documents ?: emptyList())
            .filterBy(controls.formatFilter)
        val filteredDocuments = filterMatchedDocuments
            .sortBy(controls.sort)
        val visibleCoverImages = coverImages.filterKeys { key ->
            filteredDocuments.any { it.id.value == key }
        }
        HomeUiState(
            favoriteDocuments = filteredDocuments.filter { it.isBookmarked },
            recentDocuments = filterMatchedDocuments
                .filterNot { it.isBookmarked }
                .sortedByDescending { it.lastOpenedAtEpochMillis ?: it.addedAtEpochMillis }
                .take(20),
            libraryDocuments = filteredDocuments,
            libraryFolders = buildLibraryFolders(documents.orEmpty()),
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

    fun createFolder(name: String, documentIds: Collection<DocumentId>): String {
        val trimmedName = name.trim()
        if (trimmedName.isBlank() || documentIds.isEmpty()) return ""
        val folderId = generatedFolderId()
        viewModelScope.launch {
            updateDocumentsFolderMembership(
                documentIds = documentIds,
                folderId = folderId,
                folderName = trimmedName,
            )
        }
        return folderId
    }

    fun moveDocumentsToFolder(documentIds: Collection<DocumentId>, folderId: String) {
        val folder = uiState.value.libraryFolders.firstOrNull { it.id == folderId } ?: return
        viewModelScope.launch {
            updateDocumentsFolderMembership(
                documentIds = documentIds,
                folderId = folder.id,
                folderName = folder.name,
            )
        }
    }

    fun renameFolder(folderId: String, name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return
        viewModelScope.launch {
            runCatching {
                recentDocuments.value.orEmpty()
                    .filter { it.folderId == folderId }
                    .forEach { document ->
                        documentRepository.upsertDocument(
                            document.copy(folderName = trimmedName),
                        )
                    }
            }.onFailure { controls.update { it.copy(errorMessage = "Failed to update folder.") } }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            updateDocumentsFolderMembership(
                documentIds = recentDocuments.value.orEmpty()
                    .filter { it.folderId == folderId }
                    .map(DocumentMetadata::id),
                folderId = null,
                folderName = null,
            )
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

    private suspend fun updateDocumentsFolderMembership(
        documentIds: Collection<DocumentId>,
        folderId: String?,
        folderName: String?,
    ) {
        runCatching {
            documentIds.forEach { documentId ->
                val document = documentRepository.getDocument(documentId) ?: return@forEach
                documentRepository.upsertDocument(
                    document.copy(
                        folderId = folderId,
                        folderName = folderName,
                    ),
                )
            }
        }.onFailure { controls.update { it.copy(errorMessage = "Failed to update folder.") } }
    }

    private fun generatedFolderId(): String =
        "folder-${Random.nextLong().toULong().toString(16)}-${Random.nextLong().toULong().toString(16)}"
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

package com.tedd.teddreader.feature.home.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.ByteArrayLruCache
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.isImportFinished
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.usecase.CreateLibraryFolderUseCase
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val createLibraryFolder: CreateLibraryFolderUseCase,
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

    private val coverCache = ByteArrayLruCache<String>(maxByteCount = 16 * 1024 * 1024)
    private val documentCoverImages = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    private val attemptedCoverIds = linkedSetOf<String>()
    private val inFlightCoverJobs = linkedMapOf<String, Job>()
    private var currentDocumentIds: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            recentDocuments.filterNotNull().collect { documents ->
                val previousDocumentIds = currentDocumentIds
                val documentIds = documents.mapTo(linkedSetOf()) { it.id.value }
                currentDocumentIds = documentIds
                val removedIds = (coverCache.snapshot().keys + attemptedCoverIds + inFlightCoverJobs.keys + previousDocumentIds) - documentIds
                removedIds.forEach { clearCoverState(it, publish = false) }
                publishCoverSnapshot()
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
            favoriteDocuments = filteredDocuments.filter { it.isBookmarked }.toImmutableList(),
            recentDocuments = filterMatchedDocuments
                .filterNot { it.isBookmarked }
                .sortedWith(RecentDocumentOrder)
                .take(20)
                .toImmutableList(),
            libraryDocuments = filteredDocuments.toImmutableList(),
            libraryFolders = buildLibraryFolders(documents.orEmpty()).toImmutableList(),
            documentCoverImages = visibleCoverImages.toImmutableMap(),
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
                documentRepository.setDocumentsBookmarked(documentIds, isBookmarked)
            }.onFailure {
                controls.update { current -> current.copy(errorMessage = "Failed to update document.") }
            }
        }
    }

    fun createFolder(name: String, documentIds: Collection<DocumentId>) {
        viewModelScope.launch {
            runCatching { createLibraryFolder(name, documentIds) }
                .onFailure { controls.update { it.copy(errorMessage = FolderUpdateFailedMessage) } }
        }
    }

    fun moveDocumentsToFolder(documentIds: Collection<DocumentId>, folderId: String) {
        val folder = uiState.value.libraryFolders.firstOrNull { it.id == folderId } ?: return
        viewModelScope.launch {
            runCatching {
                documentRepository.setDocumentsFolder(documentIds, folder.id, folder.name)
            }.onFailure { controls.update { it.copy(errorMessage = FolderUpdateFailedMessage) } }
        }
    }

    fun renameFolder(folderId: String, name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return
        viewModelScope.launch {
            runCatching { documentRepository.renameFolder(folderId, trimmedName) }
                .onFailure { controls.update { it.copy(errorMessage = FolderUpdateFailedMessage) } }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            runCatching { documentRepository.clearFolder(folderId) }
                .onFailure { controls.update { it.copy(errorMessage = FolderUpdateFailedMessage) } }
        }
    }

    fun deleteDocuments(documentIds: Collection<DocumentId>) {
        viewModelScope.launch {
            runCatching {
                documentIds.forEach { documentId -> clearCoverState(documentId.value, publish = false) }
                publishCoverSnapshot()
                documentRepository.deleteDocuments(documentIds)
            }.onFailure {
                controls.update { current -> current.copy(errorMessage = "Failed to delete document.") }
            }
        }
    }

    fun deleteDocument(documentId: DocumentId) {
        deleteDocuments(listOf(documentId))
    }

    fun loadCover(documentId: DocumentId) {
        val document = recentDocuments.value.orEmpty().firstOrNull { it.id == documentId } ?: return
        if (!document.supportsRepositoryCover()) return
        val documentIdValue = documentId.value
        if (documentIdValue in attemptedCoverIds || documentIdValue in inFlightCoverJobs) return
        coverCache[documentIdValue]?.let {
            publishCoverSnapshot()
            return
        }
        inFlightCoverJobs[documentIdValue] = viewModelScope.launch {
            val coverBytes = try {
                documentRepository.getDocumentCover(documentId)
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (_: Throwable) {
                null
            }
            val latestDocument = recentDocuments.value.orEmpty().firstOrNull { it.id == documentId }
            if (coverBytes != null) {
                coverCache.put(documentIdValue, coverBytes)
                if (documentIdValue in currentDocumentIds) publishCoverSnapshot()
            } else if (latestDocument?.isImportFinished == true) {
                attemptedCoverIds += documentIdValue
            }
        }.also { job ->
            job.invokeOnCompletion {
                inFlightCoverJobs.remove(documentIdValue)
            }
        }
    }



    private fun clearCoverState(documentIdValue: String, publish: Boolean = true) {
        inFlightCoverJobs.remove(documentIdValue)?.cancel()
        attemptedCoverIds.remove(documentIdValue)
        coverCache.remove(documentIdValue)
        if (publish) publishCoverSnapshot()
    }

    private fun publishCoverSnapshot() {
        documentCoverImages.value = coverCache.snapshot().filterKeys { it in currentDocumentIds }
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
    HomeFormatFilter.Comic -> filter { it.format == DocumentFormat.CBZ }
    HomeFormatFilter.Image -> filter { it.format == DocumentFormat.IMAGE }
}

private fun List<DocumentMetadata>.sortBy(sort: HomeSort): List<DocumentMetadata> = when (sort) {
    HomeSort.Recent -> sortedWith(RecentDocumentOrder)
    HomeSort.Title -> sortedBy { it.location.displayName.lowercase() }
    HomeSort.Format -> sortedWith(compareBy<DocumentMetadata> { it.format.name }.thenBy { it.location.displayName.lowercase() })
}

private fun DocumentMetadata.supportsRepositoryCover(): Boolean =
    format == DocumentFormat.PDF || format == DocumentFormat.EPUB || format == DocumentFormat.CBZ

private val RecentDocumentOrder: Comparator<DocumentMetadata> =
    compareByDescending { document -> document.lastOpenedAtEpochMillis ?: document.addedAtEpochMillis }

private const val FolderUpdateFailedMessage = "Failed to update folder."

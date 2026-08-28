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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import com.tedd.teddreader.core.common.suspendRunCatching
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

    /**
     * Document-derived home content that changes only with repository rows or controls, allowing cover
     * emissions to reuse its immutable lists instead of sorting and grouping the library again.
     */
    private val library: StateFlow<HomeLibrary> = combine(
        recentDocuments,
        controls,
    ) { documents, controls ->
        buildHomeLibrary(documents, controls)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HomeLibrary(),
    )

    val uiState: StateFlow<HomeUiState> = combine(
        library,
        documentCoverImages,
    ) { library, coverImages ->
        library.toUiState(coverImages)
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
            suspendRunCatching {
                documentRepository.setDocumentsBookmarked(documentIds, isBookmarked)
            }.onFailure {
                controls.update { current -> current.copy(errorMessage = "Failed to update document.") }
            }
        }
    }

    fun createFolder(name: String, documentIds: Collection<DocumentId>) {
        viewModelScope.launch {
            suspendRunCatching { createLibraryFolder(name, documentIds) }
                .onFailure { controls.update { it.copy(errorMessage = FolderUpdateFailedMessage) } }
        }
    }

    fun moveDocumentsToFolder(documentIds: Collection<DocumentId>, folderId: String) {
        val folder = uiState.value.libraryFolders.firstOrNull { it.id == folderId } ?: return
        viewModelScope.launch {
            suspendRunCatching {
                documentRepository.setDocumentsFolder(documentIds, folder.id, folder.name)
            }.onFailure { controls.update { it.copy(errorMessage = FolderUpdateFailedMessage) } }
        }
    }

    fun renameFolder(folderId: String, name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return
        viewModelScope.launch {
            suspendRunCatching { documentRepository.renameFolder(folderId, trimmedName) }
                .onFailure { controls.update { it.copy(errorMessage = FolderUpdateFailedMessage) } }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            suspendRunCatching { documentRepository.clearFolder(folderId) }
                .onFailure { controls.update { it.copy(errorMessage = FolderUpdateFailedMessage) } }
        }
    }

    fun deleteDocuments(documentIds: Collection<DocumentId>) {
        viewModelScope.launch {
            suspendRunCatching {
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

/**
 * The document-derived half of [HomeUiState], computed from the recent-document list and the
 * user's [HomeControls] alone — everything the home screen shows that does not depend on cover
 * bytes. It exists so cover emissions can be merged in without recomputing any of it: the sort,
 * filter, favorite/recent split, and folder grouping run once per document-or-control change in
 * [buildHomeLibrary], and a later cover-only emission reuses the very same
 * [ImmutableList][kotlinx.collections.immutable.ImmutableList] instances held here rather than
 * rebuilding them (see [toUiState]).
 *
 * [visibleDocumentIds] is the membership set the cover map is filtered against — a `Set<String>`
 * so a cover key is tested with a single `contains` instead of scanning [libraryDocuments] per
 * key. It holds exactly the ids in [libraryDocuments], the documents that survive the current
 * format filter, so a cover fetched for a now-hidden document is dropped from the published state
 * rather than shown.
 *
 * @property favoriteDocuments Bookmarked documents, filtered and sorted like [libraryDocuments];
 *   the value handed straight to [HomeUiState.favoriteDocuments].
 * @property recentDocuments The newest 20 non-bookmarked filter-matching documents in
 *   last-opened order; the value handed straight to [HomeUiState.recentDocuments].
 * @property libraryDocuments Every filter-matching document in sort order; the value handed
 *   straight to [HomeUiState.libraryDocuments].
 * @property libraryFolders Folders built from the whole unfiltered document list; the value
 *   handed straight to [HomeUiState.libraryFolders].
 * @property visibleDocumentIds The ids in [libraryDocuments], used to keep only visible covers
 *   when merging with the cover map.
 * @property hasDocuments Whether the library holds any document at all, independent of the
 *   filter; distinguishes an empty library from a filter that matches nothing.
 * @property sort The sort order these lists were built with, echoed into [HomeUiState.sort].
 * @property formatFilter The format filter these lists were built with, echoed into
 *   [HomeUiState.formatFilter].
 * @property isLoading True until the document list has been read at least once.
 * @property errorMessage The most recent load/write failure message, or null.
 */
private data class HomeLibrary(
    val favoriteDocuments: ImmutableList<DocumentMetadata> = persistentListOf(),
    val recentDocuments: ImmutableList<DocumentMetadata> = persistentListOf(),
    val libraryDocuments: ImmutableList<DocumentMetadata> = persistentListOf(),
    val libraryFolders: ImmutableList<LibraryFolder> = persistentListOf(),
    val visibleDocumentIds: Set<String> = emptySet(),
    val hasDocuments: Boolean = false,
    val sort: HomeSort = HomeSort.Recent,
    val formatFilter: HomeFormatFilter = HomeFormatFilter.All,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
) {
    /**
     * Merges this document-derived snapshot with the current cover map into the published
     * [HomeUiState], keeping only covers whose document id is still in [visibleDocumentIds].
     *
     * Every list field is forwarded by reference, so a cover-only emission produces a new
     * [HomeUiState] whose [HomeUiState.libraryDocuments], [HomeUiState.favoriteDocuments],
     * [HomeUiState.recentDocuments], and [HomeUiState.libraryFolders] are the identical instances
     * from the previous emission — the point of splitting the derivation in two. Only
     * [HomeUiState.documentCoverImages] is rebuilt, and only from [coverImages] entries that pass
     * the [visibleDocumentIds] membership test.
     *
     * @param coverImages The current cover bytes keyed by document id, already narrowed by the
     *   view model to loaded documents; narrowed once more here to visible ones.
     * @return The full [HomeUiState] to publish for this document/cover combination.
     */
    fun toUiState(coverImages: Map<String, ByteArray>): HomeUiState {
        val visibleCoverImages = coverImages.filterKeys { it in visibleDocumentIds }
        return HomeUiState(
            favoriteDocuments = favoriteDocuments,
            recentDocuments = recentDocuments,
            libraryDocuments = libraryDocuments,
            libraryFolders = libraryFolders,
            documentCoverImages = visibleCoverImages.toImmutableMap(),
            hasDocuments = hasDocuments,
            sort = sort,
            formatFilter = formatFilter,
            isLoading = isLoading,
            errorMessage = errorMessage,
        )
    }
}

/**
 * Runs the whole document-side derivation once — filter, sort, favorite/recent split, folder
 * grouping, and the visible-id set — for a given recent-document list and [HomeControls].
 * Extracted from the state combine so it happens only when the documents or controls actually
 * change, never on a cover-only emission.
 *
 * A null [documents] means the recent-document flow has not produced its first value yet, which is
 * the sole signal for [HomeLibrary.isLoading]; it is treated as an empty list for every other
 * field. [HomeLibrary.libraryFolders] is built from the unfiltered list on purpose, matching
 * [HomeUiState.libraryFolders]' contract that a folder stays visible even while the format filter
 * hides all of its contents.
 *
 * @param documents The latest recent-document emission, or null before the first one arrives.
 * @param controls The current sort, format filter, and error message.
 * @return The document-derived snapshot for these inputs, ready to merge with the cover map.
 */
private fun buildHomeLibrary(
    documents: List<DocumentMetadata>?,
    controls: HomeControls,
): HomeLibrary {
    val filterMatchedDocuments = (documents ?: emptyList())
        .filterBy(controls.formatFilter)
    val filteredDocuments = filterMatchedDocuments
        .sortBy(controls.sort)
    return HomeLibrary(
        favoriteDocuments = filteredDocuments.filter { it.isBookmarked }.toImmutableList(),
        recentDocuments = filterMatchedDocuments
            .filterNot { it.isBookmarked }
            .sortedWith(RecentDocumentOrder)
            .take(20)
            .toImmutableList(),
        libraryDocuments = filteredDocuments.toImmutableList(),
        libraryFolders = buildLibraryFolders(documents.orEmpty()).toImmutableList(),
        visibleDocumentIds = filteredDocuments.mapTo(linkedSetOf()) { it.id.value },
        hasDocuments = documents?.isNotEmpty() == true,
        sort = controls.sort,
        formatFilter = controls.formatFilter,
        isLoading = documents == null,
        errorMessage = controls.errorMessage,
    )
}

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

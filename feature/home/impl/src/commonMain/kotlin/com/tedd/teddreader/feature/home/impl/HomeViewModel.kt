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
     * 저장소 행이나 컨트롤이 바뀔 때만 달라지는 문서 기반 홈 콘텐츠다. 표지 발행 때 라이브러리를 다시
     * 정렬하고 그룹화하지 않고 이 불변 목록을 재사용할 수 있게 한다.
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
 * 최근 문서 목록과 사용자의 [HomeControls]만으로 계산하는 [HomeUiState]의 문서 기반 절반이다. 홈 화면이
 * 표시하는 항목 중 표지 바이트에 의존하지 않는 모든 것을 담는다. 표지 발행과 병합할 때 다시 계산하지 않기
 * 위해 존재한다. 정렬, 필터, 즐겨찾기/최근 분리와 폴더 그룹화는 문서 또는 컨트롤이 바뀔 때마다
 * [buildHomeLibrary]에서 한 번 실행하고, 이후 표지만 발행되면 여기에 저장된 바로 그
 * [ImmutableList][kotlinx.collections.immutable.ImmutableList] 인스턴스를 다시 만들지 않고 재사용한다
 * ([toUiState] 참고).
 *
 * [visibleDocumentIds]는 표지 map을 거르는 소속 집합이다. `Set<String>`이므로 표지 키마다
 * [libraryDocuments]를 훑지 않고 한 번의 `contains`로 검사한다. 현재 형식 필터를 통과한 문서인
 * [libraryDocuments]의 id만 정확히 담으므로, 가져온 뒤 숨겨진 문서의 표지는 표시하지 않고 발행 상태에서
 * 제거한다.
 *
 * @property favoriteDocuments [libraryDocuments]처럼 필터링하고 정렬한 즐겨찾기 문서.
 *   [HomeUiState.favoriteDocuments]에 그대로 전달한다.
 * @property recentDocuments 필터에 일치하는 즐겨찾기 아닌 문서 중 최근에 연 순서의 최신 20개.
 *   [HomeUiState.recentDocuments]에 그대로 전달한다.
 * @property libraryDocuments 필터에 일치하는 모든 문서를 정렬 순서로 담은 목록.
 *   [HomeUiState.libraryDocuments]에 그대로 전달한다.
 * @property libraryFolders 필터링하지 않은 전체 문서 목록에서 만든 폴더.
 *   [HomeUiState.libraryFolders]에 그대로 전달한다.
 * @property visibleDocumentIds [libraryDocuments]의 id. 표지 map과 병합할 때 표시 가능한 표지만 유지한다.
 * @property hasDocuments 필터와 관계없이 라이브러리에 문서가 하나라도 있는지 여부. 빈 라이브러리와
 *   일치 항목이 없는 필터를 구분한다.
 * @property sort 이 목록들을 만들 때 사용한 정렬 순서. [HomeUiState.sort]에 반영한다.
 * @property formatFilter 이 목록들을 만들 때 사용한 형식 필터. [HomeUiState.formatFilter]에 반영한다.
 * @property isLoading 문서 목록을 한 번 이상 읽을 때까지 true.
 * @property errorMessage 가장 최근의 로드/쓰기 실패 메시지. 없으면 null.
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
     * 이 문서 기반 스냅샷과 현재 표지 map을 발행할 [HomeUiState]로 병합하며, 문서 id가
     * [visibleDocumentIds]에 남아 있는 표지만 유지한다.
     *
     * 모든 목록 필드는 참조 그대로 전달한다. 따라서 표지만 발행되면 새 [HomeUiState]의
     * [HomeUiState.libraryDocuments], [HomeUiState.favoriteDocuments], [HomeUiState.recentDocuments],
     * [HomeUiState.libraryFolders]는 이전 발행과 동일한 인스턴스가 된다. 도출 과정을 둘로 나눈 목적이다.
     * [HomeUiState.documentCoverImages]만 다시 만들며, [visibleDocumentIds] 소속 검사를 통과한
     * [coverImages] 항목만 사용한다.
     *
     * @param coverImages 문서 id를 키로 하는 현재 표지 바이트. 뷰 모델에서 로드된 문서로 이미 범위를 좁혔으며
     *   여기에서 표시 가능한 문서로 한 번 더 좁힌다.
     * @return 이 문서/표지 조합으로 발행할 전체 [HomeUiState].
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
 * 주어진 최근 문서 목록과 [HomeControls]에 대해 필터, 정렬, 즐겨찾기/최근 분리, 폴더 그룹화와 표시 가능한
 * id 집합까지 문서 쪽 도출 전체를 한 번 실행한다. 상태 combine에서 추출하여 표지만 발행될 때는 실행하지
 * 않고 실제 문서나 컨트롤이 바뀔 때만 실행한다.
 *
 * [documents]가 null이면 최근 문서 flow가 아직 첫 값을 만들지 않았다는 뜻이며, 이것만이
 * [HomeLibrary.isLoading]의 신호다. 다른 모든 필드에서는 빈 목록으로 취급한다.
 * [HomeLibrary.libraryFolders]는 의도적으로 필터링하지 않은 목록에서 만든다. 형식 필터가 폴더의 모든
 * 내용을 숨기는 동안에도 폴더는 표시된다는 [HomeUiState.libraryFolders]의 계약과 일치한다.
 *
 * @param documents 가장 최근의 최근 문서 발행 값. 첫 값이 도착하기 전에는 null.
 * @param controls 현재 정렬, 형식 필터와 오류 메시지.
 * @return 이 입력으로 만든 문서 기반 스냅샷. 표지 map과 병합할 준비가 된 값이다.
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

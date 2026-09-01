package com.tedd.teddreader.feature.home.impl

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.usecase.CreateLibraryFolderUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 가짜 저장소를 상대로 [HomeViewModel]의 동작을 처음부터 끝까지 고정한다. 즐겨찾기 변경이 문서를 즐겨찾기와
 * 최근 목록 사이에서 어떻게 옮기는지, 폴더 소속을 어떻게 생성·이동·이름 변경·삭제하는지, 표지를 어떻게
 * 지연 로드하고 문서가 표시될 때만 로드하는지, 순수 layout helper(그리드 행, 라이브러리/폴더 미리보기
 * 제한)가 독립적으로 어떻게 동작하는지 검증한다. 이 중 하나라도 회귀하면 runtime에서 잘못된 화면으로만
 * 나타나는 대신 아래 테스트 중 하나가 실패해야 한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    /** 같은 문서 id를 가진 최근 섹션과 라이브러리 섹션의 동작 대상이 서로 다르게 비교되는지
     * 검증한다. 따라서 한 섹션에서 문서 행을 선택해도 다른 섹션의 중복 행까지 선택되지 않는다. */
    @Test
    fun documentActionTargetDistinguishesSameDocumentAcrossHomeSections() {
        val recent = HomeDocumentActionTarget(HomeDocumentSection.Recent, "document-1")
        val library = HomeDocumentActionTarget(HomeDocumentSection.Library, "document-1")

        assertNotEquals(recent, library)
    }

    /** `homeLibraryGridRows`가 항목을 고정 너비 행으로 묶고 완전한 행이 아니라 짧은 마지막 행만 `null`로
     * 채우는지 검증한다. */
    @Test
    fun homeLibraryGridRowsKeepTwoColumnsAndPadOnlyTheLastRow() {
        assertEquals(
            listOf(listOf(1, 2), listOf(3, 4)),
            homeLibraryGridRows(listOf(1, 2, 3, 4), columns = 2),
        )
        assertEquals(
            listOf(listOf(1, 2), listOf(3, null)),
            homeLibraryGridRows(listOf(1, 2, 3), columns = 2),
        )
    }

    /** 모든 테스트에서 main dispatcher로 설치하는 coroutine dispatcher다. [HomeViewModel]의
     * `viewModelScope` 작업이 실제 thread 대신 `advanceUntilIdle` 아래에서 결정적으로 실행되게 한다. */
    private val dispatcher = StandardTestDispatcher()

    /** 각 테스트 전에 [dispatcher]를 main dispatcher로 설치한다. */
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    /** 테스트 사이에 dispatcher 상태가 새지 않도록 각 테스트 후 실제 main dispatcher를 복원한다. */
    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(repository: DocumentRepository): HomeViewModel =
        HomeViewModel(
            createLibraryFolder = CreateLibraryFolderUseCase(repository),
            documentRepository = repository,
        )

    /** 문서를 즐겨찾기에 추가하면 발행된 `HomeUiState`의 `recentDocuments`에서 빠지고
     * `favoriteDocuments`로 이동하는지 검증한다. */
    @Test
    fun bookmarkChangeMovesDocumentToFavorites() = runTest {
        val repository = FakeDocumentRepository()
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.setDocumentBookmarked(repository.documentId, true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.favoriteDocuments.single().isBookmarked)
        assertEquals(emptyList(), viewModel.uiState.value.recentDocuments)
    }

    /** 문서 묶음을 즐겨찾기에 추가하면 첫 문서만이 아니라 모두 즐겨찾기로 이동하는지 검증한다. */
    @Test
    fun bookmarkDocumentsMovesAllSelectedDocumentsToFavorites() = runTest {
        val repository = FakeDocumentRepository(includeSecondDocument = true)
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.setDocumentsBookmarked(
            listOf(repository.documentId, repository.secondDocumentId),
            true,
        )
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.favoriteDocuments.size)
        assertTrue(viewModel.uiState.value.favoriteDocuments.all(DocumentMetadata::isBookmarked))
        assertEquals(emptyList(), viewModel.uiState.value.recentDocuments)
    }

    /** 모든 선택 문서가 이미 즐겨찾기일 때만 `homeSelectionBookmarkTarget`이 false("unbookmark next")를
     * 반환하고, 하나라도 즐겨찾기가 아니면 즉시 true("bookmark next")를 반환하는지 검증한다. 다음 일괄
     * 즐겨찾기 전환 동작을 결정하는 규칙이다. */
    @Test
    fun bookmarkSelectionTargetReturnsFalseOnlyWhenEverySelectedDocumentIsAlreadyBookmarked() {
        assertFalse(
            homeSelectionBookmarkTarget(
                listOf(
                    bookmarkedDocument("bookmarked-1"),
                    bookmarkedDocument("bookmarked-2"),
                ),
            ),
        )
        assertTrue(
            homeSelectionBookmarkTarget(
                listOf(
                    bookmarkedDocument("bookmarked-1"),
                    recentDocument("recent-1"),
                ),
            ),
        )
    }

    /** 문서 묶음을 즐겨찾기에서 제거하면 모두 `recentDocuments`로 돌아가는지 검증한다. */
    @Test
    fun unbookmarkDocumentsMovesAllSelectedDocumentsToRecents() = runTest {
        val repository = FakeDocumentRepository(
            includeSecondDocument = true,
            secondDocumentFormat = DocumentFormat.PDF,
            initiallyBookmarkedIds = setOf("document-1", "document-2"),
        )
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.setDocumentsBookmarked(
            listOf(repository.documentId, repository.secondDocumentId),
            false,
        )
        advanceUntilIdle()

        assertEquals(emptyList(), viewModel.uiState.value.favoriteDocuments)
        assertEquals(2, viewModel.uiState.value.recentDocuments.size)
        assertTrue(viewModel.uiState.value.recentDocuments.none(DocumentMetadata::isBookmarked))
    }

    /** bulk import가 한 목록에 PDF 여러 개를 발행하더라도 같은 PDF에 대한 표시 카드의 반복 표지 callback이
     * 한 번에 문서 하나의 표지만 처리하는지 검증한다. 최종 결과만 보지 않고 실제 동시성을 관찰하도록
     * [SuspendingCoverDocumentRepository]를 사용한다. */
    @Test
    fun bulkImportedPdfCoversRequestAtMostOneCoverAtATime() = runTest {
        val repository = SuspendingCoverDocumentRepository()
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        repository.emitBulkPdfDocuments(count = 3)
        advanceUntilIdle()

        repeat(3) {
            viewModel.loadCover(DocumentId("bulk-0"))
        }
        advanceUntilIdle()

        assertEquals(1, repository.maxConcurrentCoverRequests)
    }

    /** 문서를 삭제하면 한 섹션에서만 빠지는 것이 아니라 라이브러리 전체에서 제거되는지 검증한다. */
    @Test
    fun deleteRemovesRecentDocument() = runTest {
        val repository = FakeDocumentRepository()
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteDocument(repository.documentId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.hasDocuments)
    }

    /** 문서 묶음을 삭제하면 모두 제거되는지 검증한다. */
    @Test
    fun deleteDocumentsRemovesAllSelectedRecentDocuments() = runTest {
        val repository = FakeDocumentRepository(includeSecondDocument = true)
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteDocuments(listOf(repository.documentId, repository.secondDocumentId))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.hasDocuments)
    }

    /** 표시 카드의 표지 callback이 실제로 저장소 표지를 지원하는 형식(여기서는 PDF)만 요청하고 TXT는
     * 완전히 건너뛰는지 검증한다. */
    @Test
    fun loadsPdfCoverAndSkipsTxtCoverRequests() = runTest {
        val repository = FakeDocumentRepository(includeSecondDocument = true)
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.loadCover(repository.documentId)
        viewModel.loadCover(repository.secondDocumentId)
        advanceUntilIdle()

        assertContentEquals(listOf(repository.documentId.value), repository.coverRequestIds)
        assertContentEquals(repository.pdfCoverBytes, viewModel.uiState.value.documentCoverImages[repository.documentId.value])
        assertFalse(viewModel.uiState.value.documentCoverImages.containsKey(repository.secondDocumentId.value))
    }

    /**
     * 테스트 이름이 가리키는 버그를 검증한다. 점진적으로 가져오는 문서는 표지가 기록되기 전에 라이브러리에
     * 나타나므로 첫 표지 요청이 빈 값으로 돌아온다. 이 빈 응답을 기억하면 책을 추가한 직후 카드가 process를
     * 다시 시작할 때까지 비어 있었다. 가져오기가 끝나고 나중 발행 값이 도착하면 표지를 다시 가져와 재시작
     * 없이 표시하는지 검증한다.
     */
    @Test
    fun coverAppearsOnceTheImportFinishesWithoutRestartingTheApp() = runTest {
        val repository = FakeDocumentRepository()
        repository.coverAvailable = false
        val importing = repository.documents.value.map { document ->
            if (document.id == repository.documentId) document.copy(characterCount = null) else document
        }
        repository.emitDocuments(importing)
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.loadCover(repository.documentId)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.documentCoverImages.containsKey(repository.documentId.value))

        repository.coverAvailable = true
        repository.emitDocuments(
            importing.map { document ->
                if (document.id == repository.documentId) document.copy(characterCount = 1_234L) else document
            },
        )
        advanceUntilIdle()

        viewModel.loadCover(repository.documentId)
        advanceUntilIdle()

        assertContentEquals(
            listOf(repository.documentId.value, repository.documentId.value),
            repository.coverRequestIds,
        )
        assertContentEquals(
            repository.pdfCoverBytes,
            viewModel.uiState.value.documentCoverImages[repository.documentId.value],
        )
    }

    /** 문서를 삭제하면 발행 상태에서 캐시된 표지 바이트도 제거하여 나중에 같은 id를 재사용하는 문서가
     * 오래된 표지를 표시하지 않는지 검증한다. */
    @Test
    fun deleteRemovesLoadedCoverBytes() = runTest {
        val repository = FakeDocumentRepository()
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteDocument(repository.documentId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.documentCoverImages.containsKey(repository.documentId.value))
        assertFalse(viewModel.uiState.value.hasDocuments)
    }

    /** `libraryDocuments`는 필터링되지 않은 모든 문서를 유지하고 `recentDocuments`는 즐겨찾기 아닌 최신
     * 20개로 제한하는지 검증한다. 두 목록이 라이브러리를 얼마나 보여 주는지는 의도적으로 다를 수 있다. */
    @Test
    fun homeStateKeepsAllLibraryDocumentsWhileRecentShowsLatestTwentyNonFavorites() = runTest {
        val repository = FakeDocumentRepository(
            documents = List(25) { index ->
                testDocument(
                    id = "recent-$index",
                    isBookmarked = index < 3,
                    addedAtEpochMillis = index.toLong(),
                    lastOpenedAtEpochMillis = (1_000L + index),
                )
            },
        )
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(25, viewModel.uiState.value.libraryDocuments.size)
        assertEquals(20, viewModel.uiState.value.recentDocuments.size)
        assertTrue(viewModel.uiState.value.recentDocuments.none(DocumentMetadata::isBookmarked))
        assertEquals("recent-24", viewModel.uiState.value.recentDocuments.first().id.value)
        assertEquals("recent-5", viewModel.uiState.value.recentDocuments.last().id.value)
    }

    /** 홈 화면에서 미리 볼 라이브러리 항목 수에 관한 `homeLibraryPreviewLimit` 규칙을 검증한다. compact
     * 휴대전화 layout에서는 4개, layout이 expanded이거나 태블릿이거나 화면을 나누는 display fold가 있으면
     * 8개다. */
    @Test
    fun libraryPreviewUsesFourOnPhoneAndEightOnExpandedLayoutsWithoutAutoFolderMode() {
        val documents = List(10) { index ->
            testDocument(id = "library-$index", isBookmarked = false, addedAtEpochMillis = index.toLong())
        }

        assertEquals(
            4,
            homeLibraryPreviewDocuments(
                documents = documents,
                previewLimit = homeLibraryPreviewLimit(
                    isExpanded = false,
                    isTablet = false,
                    hasSeparatingFold = false,
                ),
            ).size,
        )
        assertEquals(
            8,
            homeLibraryPreviewDocuments(
                documents = documents,
                previewLimit = homeLibraryPreviewLimit(
                    isExpanded = true,
                    isTablet = false,
                    hasSeparatingFold = false,
                ),
            ).size,
        )
        assertEquals(
            8,
            homeLibraryPreviewDocuments(
                documents = documents,
                previewLimit = homeLibraryPreviewLimit(
                    isExpanded = false,
                    isTablet = true,
                    hasSeparatingFold = false,
                ),
            ).size,
        )
        assertEquals(
            8,
            homeLibraryPreviewDocuments(
                documents = documents,
                previewLimit = homeLibraryPreviewLimit(
                    isExpanded = false,
                    isTablet = false,
                    hasSeparatingFold = true,
                ),
            ).size,
        )
    }

    /** `libraryFolderPreviewDocuments`가 요청한 폴더의 문서만 원래 순서로 반환하고 지정한 미리보기 제한에서
     * 자르는지 검증한다. */
    @Test
    fun libraryFolderPreviewDocumentsReturnsOnlyRequestedFolderInSourceOrderAndLimit() {
        val documents = buildList {
            repeat(10) { index ->
                add(
                    testDocument(
                        id = "folder-doc-$index",
                        isBookmarked = false,
                        addedAtEpochMillis = index.toLong(),
                        folderId = "folder-1",
                        folderName = "Folder 1",
                    ),
                )
            }
            add(
                testDocument(
                    id = "other-folder-doc",
                    isBookmarked = false,
                    addedAtEpochMillis = 100L,
                    folderId = "folder-2",
                    folderName = "Folder 2",
                ),
            )
        }

        assertEquals(
            listOf("folder-doc-0", "folder-doc-1", "folder-doc-2", "folder-doc-3"),
            libraryFolderPreviewDocuments(
                documents = documents,
                folderId = "folder-1",
                previewLimit = 4,
            ).map { it.id.value },
        )
        assertEquals(
            listOf(
                "folder-doc-0",
                "folder-doc-1",
                "folder-doc-2",
                "folder-doc-3",
                "folder-doc-4",
                "folder-doc-5",
                "folder-doc-6",
                "folder-doc-7",
            ),
            libraryFolderPreviewDocuments(
                documents = documents,
                folderId = "folder-1",
                previewLimit = 8,
            ).map { it.id.value },
        )
    }

    /** 미리보기가 이미 폴더 전체를 포함하면 `libraryFolderRemainingDocumentCount`가 음수가 되지 않고
     * 0을 최솟값으로 삼는지 검증한다. */
    @Test
    fun libraryFolderRemainingDocumentCountNeverDropsBelowZero() {
        assertEquals(6, libraryFolderRemainingDocumentCount(totalCount = 10, previewCount = 4))
        assertEquals(0, libraryFolderRemainingDocumentCount(totalCount = 4, previewCount = 4))
        assertEquals(0, libraryFolderRemainingDocumentCount(totalCount = 3, previewCount = 4))
    }

    /**
     * 폴더의 전체 lifecycle을 처음부터 끝까지 검증한다. 폴더 생성은 선택한 문서만 정확히 할당하고 다른 것은
     * 바꾸지 않는다. 문서를 폴더로 옮기면 해당 문서의 폴더 필드만 갱신한다. 이름 변경은 소속을 건드리지 않고
     * 모든 구성원의 폴더 이름을 다시 쓴다. 폴더 삭제는 문서 자체를 그대로 두면서 모든 구성원의 폴더 필드를
     * 지운다.
     */
    @Test
    fun createMoveRenameAndDeleteFolderOnlyMutateMembership() = runTest {
        val repository = FakeDocumentRepository(
            documents = listOf(
                testDocument(id = "doc-1", isBookmarked = false, addedAtEpochMillis = 1L),
                testDocument(id = "doc-2", isBookmarked = false, addedAtEpochMillis = 2L),
                testDocument(
                    id = "doc-3",
                    isBookmarked = false,
                    addedAtEpochMillis = 3L,
                    folderId = "folder-old",
                    folderName = "Old Folder",
                ),
            ),
        )
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.createFolder(
            name = "Weekend Reads",
            documentIds = listOf(DocumentId("doc-1"), DocumentId("doc-2")),
        )
        advanceUntilIdle()

        val createdFolderId = repository.requireDocument("doc-1").folderId ?: error("folder not created")
        assertEquals(
            listOf("doc-1", "doc-2"),
            repository.documentsInFolder(createdFolderId).map { it.id.value },
        )
        assertEquals(
            listOf("Weekend Reads"),
            viewModel.uiState.value.libraryFolders.filter { it.id == createdFolderId }.map { it.name },
        )

        viewModel.moveDocumentsToFolder(
            documentIds = listOf(DocumentId("doc-3")),
            folderId = createdFolderId,
        )
        advanceUntilIdle()
        assertEquals(
            createdFolderId,
            repository.requireDocument("doc-3").folderId,
        )

        viewModel.renameFolder(folderId = createdFolderId, name = "Renamed Folder")
        advanceUntilIdle()
        assertEquals(
            setOf("Renamed Folder"),
            repository.documentsInFolder(createdFolderId).mapNotNull { it.folderName }.toSet(),
        )

        viewModel.deleteFolder(createdFolderId)
        advanceUntilIdle()
        assertEquals(
            listOf("doc-1", "doc-2", "doc-3"),
            repository.documentsWithoutFolder().map { it.id.value }.sorted(),
        )
        assertEquals(3, viewModel.uiState.value.libraryDocuments.size)
    }

    /**
     * 형식 필터는 `libraryDocuments`에 보이는 범위만 줄이고 폴더 작업의 대상 범위는 줄이지 않는지 검증한다.
     * 폴더 이름 변경이나 삭제는 현재 필터가 화면에서 숨긴 문서까지 모든 구성원에게 계속 적용돼야 한다.
     */
    @Test
    fun formatFilterOnlyLimitsVisibleDocumentsWhileFolderRenameAndDeleteStillAffectWholeFolder() = runTest {
        val repository = FakeDocumentRepository(
            documents = listOf(
                testDocument(
                    id = "pdf-doc",
                    isBookmarked = false,
                    addedAtEpochMillis = 1L,
                    folderId = "folder-shared",
                    folderName = "Shared Folder",
                    format = DocumentFormat.PDF,
                ),
                testDocument(
                    id = "txt-doc",
                    isBookmarked = false,
                    addedAtEpochMillis = 2L,
                    folderId = "folder-shared",
                    folderName = "Shared Folder",
                    format = DocumentFormat.TXT,
                ),
            ),
        )
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateFormatFilter(HomeFormatFilter.Pdf)
        advanceUntilIdle()
        assertEquals(listOf("pdf-doc"), viewModel.uiState.value.libraryDocuments.map { it.id.value })

        viewModel.renameFolder(folderId = "folder-shared", name = "Renamed Folder")
        advanceUntilIdle()
        assertEquals("Renamed Folder", repository.requireDocument("pdf-doc").folderName)
        assertEquals("Renamed Folder", repository.requireDocument("txt-doc").folderName)

        viewModel.deleteFolder("folder-shared")
        advanceUntilIdle()
        assertEquals(null, repository.requireDocument("pdf-doc").folderId)
        assertEquals(null, repository.requireDocument("pdf-doc").folderName)
        assertEquals(null, repository.requireDocument("txt-doc").folderId)
        assertEquals(null, repository.requireDocument("txt-doc").folderName)
        assertEquals(2, repository.documentsWithoutFolder().size)
        assertEquals(listOf("pdf-doc"), viewModel.uiState.value.libraryDocuments.map { it.id.value })
    }

    /**
     * 표지만 발행된 값을 [HomeUiState]에 병합할 때 문서 기반 목록을 다시 만들지 않는지 검증한다. 표지를 가져온
     * 뒤 새 상태의 [HomeUiState.libraryDocuments], [HomeUiState.favoriteDocuments],
     * [HomeUiState.recentDocuments], [HomeUiState.libraryFolders]는 표지를 가져오기 전 상태가 이미 보유한 것과
     * *같은 인스턴스*여야 하고 [HomeUiState.documentCoverImages]만 새 바이트를 담도록 바뀌어야 한다.
     *
     * 이것이 문서 목록을 표지 map과 별도 flow에서 도출하는 이유 전부다. 다시 하나의
     * `combine(recentDocuments, controls, documentCoverImages)`으로 합치면 표지를 발행할 때마다 모든 목록을
     * 다시 만들고 아래 `assertSame` 검사가 실패한다. 코드를 읽어서 추측한 것이 아니라 실제로 해당 회귀를
     * 실행해 확인했다.
     */
    @Test
    fun coverEmissionKeepsPreviousDocumentListInstances() = runTest {
        val repository = FakeDocumentRepository(includeSecondDocument = true, secondDocumentFormat = DocumentFormat.PDF)
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val beforeCover = viewModel.uiState.value
        assertTrue(beforeCover.documentCoverImages.isEmpty())

        viewModel.loadCover(repository.documentId)
        advanceUntilIdle()

        val afterCover = viewModel.uiState.value
        assertContentEquals(
            repository.pdfCoverBytes,
            afterCover.documentCoverImages[repository.documentId.value],
        )
        assertSame(beforeCover.libraryDocuments, afterCover.libraryDocuments)
        assertSame(beforeCover.favoriteDocuments, afterCover.favoriteDocuments)
        assertSame(beforeCover.recentDocuments, afterCover.recentDocuments)
        assertSame(beforeCover.libraryFolders, afterCover.libraryFolders)
    }

    /**
     * suspend 작업 안에서 발생한 [CancellationException]이 오류 메시지를 만들지 않는지 검증한다.
     * [suspendRunCatching]을 평범한 `runCatching`으로 되돌리면 `onFailure`가 취소를 잡아
     * "Failed to delete document."로 기록한다. 이 assertion이 해당 회귀를 포착한다.
     */
    @Test
    fun cancellationExceptionFromRepositoryDoesNotProduceErrorMessage() = runTest {
        val repository = object : FakeDocumentRepository() {
            override suspend fun deleteDocument(documentId: DocumentId) {
                throw CancellationException("scope cancelled")
            }
        }
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteDocument(repository.documentId)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

}

/**
 * [MutableStateFlow]를 backing으로 사용하는 in-memory [DocumentRepository]다. 테스트가 실행 중간에 새 문서
 * 목록을 넣고([emitDocuments] 참고) 실제 database나 file I/O 없이 [HomeViewModel]의 반응을 관찰할 수
 * 있다. 대부분의 테스트가 바꾸는 두 축인 문서 수와 처음 즐겨찾기된 문서를 모델링한다.
 *
 * @param includeSecondDocument [documentId]의 문서와 함께 두 번째 seed 문서가 있는지 여부.
 * @param secondDocumentFormat 두 번째 문서의 형식. [includeSecondDocument]가 true일 때만 사용한다.
 * @param initiallyBookmarkedIds 처음부터 즐겨찾기할 id.
 * @param documents 두 문서 기본값을 완전히 대체하는 사용자 지정 seed 목록.
 */
private open class FakeDocumentRepository(
    includeSecondDocument: Boolean = false,
    secondDocumentFormat: DocumentFormat = DocumentFormat.TXT,
    initiallyBookmarkedIds: Set<String> = emptySet(),
    documents: List<DocumentMetadata>? = null,
) : DocumentRepository {
    /** 대부분의 테스트가 [documents]에서 찾지 않고 직접 참조하는 기본 seed 문서 id. */
    val documentId = DocumentId("document-1")

    /** 선택적인 두 번째 seed 문서 id. 생성자의 `includeSecondDocument`가 true일 때만 [documents]에 있다. */
    val secondDocumentId = DocumentId("document-2")

    /** 표지를 사용할 수 있을 때 [getDocumentCover]가 [documentId]에 대해 반환하는 고정 바이트. 테스트에서
     * 정확한 바이트가 [HomeViewModel] 상태에 들어갔는지 assertion할 수 있다. */
    val pdfCoverBytes = byteArrayOf(1, 3, 3, 7)

    /** [getDocumentCover]에 요청한 모든 id를 호출 순서대로 담는다. 테스트에서 가져온 문서와 건너뛴 문서를
     * assertion할 수 있다. */
    val coverRequestIds = mutableListOf<String>()

    /** 변경 가능한 backing 목록. 생성자에서 seed하고 [emitDocuments], [upsertDocument],
     * [deleteDocument]로 변경하여 저장소의 실시간 문서 flow를 대신한다. */
    val documents = MutableStateFlow(
        documents ?: buildList {
            add(
                DocumentMetadata(
                    id = documentId,
                    location = DocumentLocation(
                        sourceUri = "file:///document.pdf",
                        displayName = "document.pdf",
                    ),
                    format = DocumentFormat.PDF,
                    addedAtEpochMillis = 1_000L,
                    isBookmarked = documentId.value in initiallyBookmarkedIds,
                ),
            )
            if (includeSecondDocument) {
                add(
                    DocumentMetadata(
                        id = secondDocumentId,
                        location = DocumentLocation(
                            sourceUri = "file:///document-2.txt",
                            displayName = "document-2.txt",
                        ),
                        format = secondDocumentFormat,
                        addedAtEpochMillis = 2_000L,
                        isBookmarked = secondDocumentId.value in initiallyBookmarkedIds,
                    ),
                )
            }
        },
    )

    /** assertion을 위해 id로 문서를 찾으며, 없으면 null을 반환하지 않고 명확히 실패한다. */
    fun requireDocument(id: String): DocumentMetadata =
        documents.value.first { it.id.value == id }

    /** 폴더 변경 뒤 assertion하기 위해 현재 주어진 폴더 id를 가진 문서를 반환한다. */
    fun documentsInFolder(folderId: String): List<DocumentMetadata> =
        documents.value.filter { it.folderId == folderId }

    /** 폴더 삭제가 소속을 남기지 않고 실제로 지웠는지 assertion하기 위한 폴더 없는 문서. */
    fun documentsWithoutFolder(): List<DocumentMetadata> =
        documents.value.filter { it.folderId == null }

    /** [documents]를 [HomeViewModel]이 관찰하는 실시간 문서 flow로 노출한다. */
    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = documents

    /** 실제 저장소와 같은 방식으로 id를 사용해 문서를 찾고, 없으면 null을 반환한다. */
    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        documents.value.firstOrNull { it.id == documentId }

    /** [getDocumentCover]가 바이트를 반환할지 여부. 아직 표지가 기록되지 않은 문서(예: 가져오는 중)를
     * 시뮬레이션하려면 false로 설정한다. */
    var coverAvailable: Boolean = true

    /** 전체 문서 목록을 교체하여 기반 store에 가져오기나 편집이 하나의 새 발행 값으로 도착하는 상황을
     * 시뮬레이션한다. */
    fun emitDocuments(next: List<DocumentMetadata>) {
        documents.value = next
    }

    /** 요청을 [coverRequestIds]에 기록하고 [coverAvailable]일 때 [documentId]에 대해 [pdfCoverBytes]를
     * 반환한다. file을 건드리지 않고 실제 저장소의 문서별 표지 조회를 모방한다. */
    override suspend fun getDocumentCover(documentId: DocumentId): ByteArray? {
        coverRequestIds += documentId.value
        if (!coverAvailable) return null
        return if (documentId == this.documentId) pdfCoverBytes else null
    }

    /** 이 테스트에서는 실행하지 않는다. 이 가짜를 통해 문서 본문을 여는 테스트가 없으므로 null을 반환한다. */
    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = null

    /** 이 테스트에서는 실행하지 않는다. 이 가짜로 pagination하는 테스트가 없으므로 빈 페이지 목록을 반환한다. */
    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: ReaderPageBreaker?,
        anchorOffset: Long?,
        viewportDensity: Float,
    ): List<PageWindow> = emptyList()

    /** 이 테스트에서는 실행하지 않는다. 가져오기는 이 가짜가 지원하는 홈 화면 동작의 범위 밖이므로 호출되면
     * 명확히 실패한다. */
    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument = error("not used")

    /** 문서의 전체 record를 다시 쓰면서 같은 id의 이전 항목을 교체한다. 테스트하는 모든 폴더 및 즐겨찾기
     * 변경에서 read-modify-write의 쓰기 절반이다. */
    override suspend fun upsertDocument(document: DocumentMetadata) {
        documents.value = documents.value.map { current ->
            if (current.id == document.id) document else current
        }
    }

    /** 이 테스트에서는 실행하지 않는다. 여기서는 아무것도 "last opened"를 읽지 않으므로 no-op이다. */
    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit

    /** [documents]에서 문서를 제거한다. 문서가 실제로 사라졌는지 검증할 때 [upsertDocument] 테스트가
     * 의존하는 삭제 counterpart다. */
    override suspend fun deleteDocument(documentId: DocumentId) {
        documents.value = documents.value.filterNot { it.id == documentId }
    }
}

/**
 * [getDocumentCover]가 영원히 반환하지 않는 [DocumentRepository]다. 반복되는 표시 카드 callback이 같은
 * 문서에 대해 만든 in-flight 요청 수를 [bulkImportedPdfCoversRequestAtMostOneCoverAtATime]에서 최종
 * 결과만 보지 않고 관찰할 수 있게 한다.
 */
private class SuspendingCoverDocumentRepository : DocumentRepository {
    /** 완료하지 않으므로 모든 [getDocumentCover] 호출이 테스트가 끝날 때까지 여기에서 suspend된다.
     * [activeCoverRequests]를 관찰할 만큼 오래 높은 값으로 유지하는 장치다. */
    private val coverGate = CompletableDeferred<Unit>()

    /** [emitBulkPdfDocuments]로 seed하는 backing 문서 flow. */
    private val documents = MutableStateFlow<List<DocumentMetadata>>(emptyList())

    /** 현재 [coverGate]에서 suspend 중인 [getDocumentCover] 호출 수. */
    var activeCoverRequests = 0
        private set

    /** [activeCoverRequests]의 high-water mark다. 테스트가 assertion하는 반복 callback 시나리오에서
     * [HomeViewModel]이 동시에 보유한 in-flight 표지 요청의 최댓값이다. */
    var maxConcurrentCoverRequests = 0
        private set

    /** 하나의 발행 값으로 [count]개의 PDF 문서를 seed하여 bulk import가 모두 한꺼번에 도착하는 상황을
     * 시뮬레이션한다. 뷰 모델의 표지 로드 pass가 모든 요청을 병렬로 보내지 않고 스스로 제한하는지 테스트할
     * 수 있다.
     *
     * @param count seed할 문서 수.
     */
    fun emitBulkPdfDocuments(count: Int) {
        documents.value = List(count) { index ->
            DocumentMetadata(
                id = DocumentId("bulk-$index"),
                location = DocumentLocation(
                    sourceUri = "file:///bulk-$index.pdf",
                    displayName = "bulk-$index.pdf",
                ),
                format = DocumentFormat.PDF,
                addedAtEpochMillis = index.toLong(),
            )
        }
    }
    /** [documents]를 [HomeViewModel]이 관찰하는 실시간 문서 flow로 노출한다. */
    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = documents

    /** 이 테스트에서는 실행하지 않는다. 여기서는 id로 문서를 조회하지 않으므로 null을 반환한다. */
    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        documents.value.firstOrNull { it.id == documentId }

    /** 테스트 안에서 끝나지 않는 suspend 전후로 [activeCoverRequests]와 [maxConcurrentCoverRequests]를
     * 추적한다. 최종 반환 값이 아니라 바로 이 호출 지점의 호출자 동시성을 테스트가 관찰하게 한다. */
    override suspend fun getDocumentCover(documentId: DocumentId): ByteArray? {
        activeCoverRequests += 1
        maxConcurrentCoverRequests = maxOf(maxConcurrentCoverRequests, activeCoverRequests)
        try {
            coverGate.await()
            return byteArrayOf(documentId.value.length.toByte())
        } finally {
            activeCoverRequests -= 1
        }
    }

    /** 이 테스트에서는 실행하지 않는다. 이 가짜를 통해 문서 본문을 여는 테스트가 없으므로 null을 반환한다. */
    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = null

    /** 이 테스트에서는 실행하지 않는다. 이 가짜로 pagination하는 테스트가 없으므로 빈 페이지 목록을 반환한다. */
    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: ReaderPageBreaker?,
        anchorOffset: Long?,
        viewportDensity: Float,
    ): List<PageWindow> = emptyList()

    /** 이 테스트에서는 실행하지 않는다. 가져오기는 이 가짜가 지원하는 표지 동시성 동작의 범위 밖이므로
     * 호출되면 명확히 실패한다. */
    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument = error("not used")

    /** 이 테스트에서는 실행하지 않는다. 이 fixture에서는 즐겨찾기나 폴더 이동을 테스트하지 않으므로
     * no-op이다. */
    override suspend fun upsertDocument(document: DocumentMetadata) = Unit

    /** 이 테스트에서는 실행하지 않는다. 여기서는 아무것도 "last opened"를 읽지 않으므로 no-op이다. */
    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit

    /** 이 테스트에서는 실행하지 않는다. 이 fixture에서는 문서를 삭제하는 테스트가 없으므로 no-op이다. */
    override suspend fun deleteDocument(documentId: DocumentId) = Unit
}

/** 나머지 [DocumentMetadata]가 아니라 즐겨찾기 상태만 필요한 테스트를 위한 최소 즐겨찾기 문서. */
private fun bookmarkedDocument(id: String): DocumentMetadata = testDocument(id = id, isBookmarked = true)

/** [bookmarkedDocument]에 대응하는 최소 즐겨찾기 아닌 문서. */
private fun recentDocument(id: String): DocumentMetadata = testDocument(id = id, isBookmarked = false)

/**
 * 테스트가 관심 없는 모든 필드에 적절한 기본값을 넣어 [DocumentMetadata]를 만든다. 각 테스트는 assertion이
 * 실제로 의존하는 소수의 필드만 지정하면 된다.
 *
 * @param id 문서 id. 원본 URI와 표시 이름을 만드는 데도 사용한다.
 * @param isBookmarked 문서가 처음부터 즐겨찾기인지 여부.
 * @param addedAtEpochMillis 문서를 추가한 시각. 정렬 테스트에서 명시적으로 재정의할 수 있도록 고정 timestamp가
 *   기본값이다.
 * @param lastOpenedAtEpochMillis 문서를 마지막으로 연 시각. 한 번도 열지 않았으면 null.
 * @param folderId 문서가 속한 폴더. 없으면 null.
 * @param folderName 폴더 표시 이름. [folderId]가 null이 아닐 때만 반드시 null이 아니어야 한다.
 * @param format 문서 형식. 기본값은 PDF.
 */
private fun testDocument(
    id: String,
    isBookmarked: Boolean,
    addedAtEpochMillis: Long = 1_000L,
    lastOpenedAtEpochMillis: Long? = null,
    folderId: String? = null,
    folderName: String? = null,
    format: DocumentFormat = DocumentFormat.PDF,
): DocumentMetadata = DocumentMetadata(
    id = DocumentId(id),
    location = DocumentLocation(
        sourceUri = "file:///$id.${format.name.lowercase()}",
        displayName = "$id.${format.name.lowercase()}",
    ),
    format = format,
    addedAtEpochMillis = addedAtEpochMillis,
    lastOpenedAtEpochMillis = lastOpenedAtEpochMillis,
    isBookmarked = isBookmarked,
    folderId = folderId,
    folderName = folderName,
)

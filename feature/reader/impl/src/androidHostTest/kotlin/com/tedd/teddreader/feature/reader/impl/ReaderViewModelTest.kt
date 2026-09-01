package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.PaginatedDocument
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderDarkTextArgb
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderNavigation
import com.tedd.teddreader.core.common.model.ReaderNavigationItem
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderSpan
import com.tedd.teddreader.core.common.model.ReaderSpanStyle
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.layoutKey
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.domain.repository.BookmarkRepository
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ImportProgress
import com.tedd.teddreader.core.domain.repository.PaginationProgress
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReaderSettings
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import com.tedd.teddreader.core.domain.usecase.OpenReaderDocumentUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [ReaderViewModel]의 동작을 네 단계 open 파이프라인, progressive-import·progressive-pagination
 * continuation, 범위가 제한된 mount-window 예열, 페이지 내비게이션(상대 이동, 아웃라인/위치 점프와
 * 그 페이지 전환 부수 효과), 즐겨찾기/저장 위치 토글 전반에 걸쳐 고정한다.
 *
 * 모든 테스트는 [dispatcher], 즉 [StandardTestDispatcher]를 통해 뷰 모델을 구동하므로, 뷰 모델이
 * 실행하는 모든 코루틴은 실제 스케줄러에서 경쟁하는 대신 `advanceUntilIdle()`을 통해 결정론적으로
 * 진행된다. 여기 있는 테스트 대부분은 특정 버그가 출시되었다가 고쳐졌기 때문에 존재한다. 각 테스트는
 * 자신이 지키는 회귀가 무엇인지 이름 붙인 자체 KDoc을 가지며, "Pins ..."로 시작하는 것들은 이
 * 프로젝트 자체의 버그 라벨(F1(a), F1(b), F3, f33313b)을 그대로 인용한다. 아래의 fake들 — 무엇보다
 * [FakeDocumentRepository] — 은 정상 경로뿐 아니라 이 뷰 모델이 견뎌내야 하는 구체적인 실패와 동시성
 * 모드를 모델링한다: 비워진 디코딩-블록 캐시, null이 되거나 거짓으로 "완료"라고 하는 pagination
 * 세션, 사라진 문서 행, 그리고 fill이 완전히 정착한 상태만 관찰하는 대신 도중에 붙잡기 위해 얼려둔
 * 백그라운드 warm 호출.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    /** 이 스위트의 모든 테스트가 자신의 뷰 모델 코루틴을 구동하는 [StandardTestDispatcher]. */
    private val dispatcher = StandardTestDispatcher()

    /**
     * 각 테스트 전에 [dispatcher]를 main dispatcher로 설치하여, `viewModelScope.launch`가 이것으로
     * 해석되도록 한다.
     */
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    /**
     * 각 테스트 후 실제 main dispatcher를 복원하여, 이 스위트가 dispatcher override를 남기지 않도록
     * 한다.
     */
    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 기본 정상성 확인: 문서를 열면 저장된 첫 페이지의 텍스트와 인덱스가 발행되고, [DocumentRepository]에
     * open(문서 id와 양수 timestamp)이 기록된다.
     */
    @Test
    fun openDocumentShowsStoredPageText() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId)
        val readerRepository = FakeReaderRepository()
        val viewModel = ReaderViewModel(
            documentRepository = documentRepository,
            bookmarkRepository = FakeBookmarkRepository(),
            readerSettingsRepository = FakeReaderSettingsRepository(),
            readerRepository = readerRepository,
            openReaderDocumentUseCase = OpenReaderDocumentUseCase(documentRepository, readerRepository, FakeReaderSettingsRepository()),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 320, height = 560)
        advanceUntilIdle()

        assertEquals("First stored page", viewModel.uiState.value.pageText)
        assertEquals(PageIndex(current = 0, total = 2), viewModel.uiState.value.pageIndex)
        assertEquals(documentId, documentRepository.lastOpenedDocumentId)
        assertTrue(documentRepository.lastOpenedAtEpochMillis > 0L)
    }

    /** 실패했거나 멈춘 같은 문서의 open은 영영 빈 화면으로 남는 대신 다시 시도할 수 있어야 한다. */
    @Test
    fun failedOpenCanRetryTheSameDocument() = runTest(dispatcher) {
        val documentId = DocumentId("doc-retry")
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            throwOnGetPageWindowsCall = 0,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.errorMessage != null)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(null, viewModel.uiState.value.errorMessage)
        assertEquals("First stored page", viewModel.uiState.value.pageText)
        assertEquals(2, documentRepository.pageWindowRequests)
    }

    /**
     * 기본 정상성 확인: [ReaderLocation.TextOffset]으로 호출한 [ReaderViewModel.moveToLocation]은 그
     * offset을 포함하는 페이지에 도착한다.
     */
    @Test
    fun moveToLocationShowsMatchingPage() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val readerRepository = FakeReaderRepository()
        val documentRepository = FakeDocumentRepository(documentId)
        val viewModel = ReaderViewModel(
            documentRepository = documentRepository,
            bookmarkRepository = FakeBookmarkRepository(),
            readerSettingsRepository = FakeReaderSettingsRepository(),
            readerRepository = readerRepository,
            openReaderDocumentUseCase = OpenReaderDocumentUseCase(documentRepository, readerRepository, FakeReaderSettingsRepository()),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 320, height = 560)
        advanceUntilIdle()

        viewModel.moveToLocation(ReaderLocation.TextOffset(18))
        advanceUntilIdle()

        assertEquals("Second stored page", viewModel.uiState.value.pageText)
        assertEquals(PageIndex(current = 1, total = 2), viewModel.uiState.value.pageIndex)
    }

    /**
     * [ReaderViewModel.moveToLocation]이 visual 문서에 대해 text-offset 전용 조회로 붕괴하지 않도록
     * 지킨다. visual 문서의 아웃라인은 전부 [ReaderLocation.PdfPage] 항목으로 구성되며(
     * `ReaderViewModel.buildOutlineItems` 참고), `ReaderScreen`의 모든 아웃라인 탭이나 jump-location
     * 효과는 오직 이 함수를 통해서만 페이지에 도달한다 — 다른 경로는 없다. `PaginatedDocument.pageOf(location)`
     * (`absoluteOffsetOf`를 통해)은 [ReaderLocation.PdfPage]에 대해 null을 반환하므로, 모든 위치를
     * 이를 통해 직접 해석하는 본문은 PDF/CBZ 아웃라인 탭을, UI는 여전히 제공하지만 실제로는 절대
     * 취할 수 없는 페이지 점프로 조용히 바꿔버려, 제공된 점프 대상은 반드시 어딘가 실제로 도착해야
     * 한다는 리더의 불변 조건을 깨뜨린다. 이 스위트의 다른 모든 moveToLocation 테스트는
     * [ReaderLocation.TextOffset]으로 구동되므로, 그중 어느 것도 이 회귀를 알아채지 못했을 것이다.
     */
    @Test
    fun moveToLocationOnAVisualDocumentJumpsToThatPage() = runTest(dispatcher) {
        val documentId = DocumentId("comic-1")
        val viewModel = createViewModel(
            FakeDocumentRepository(
                documentId = documentId,
                format = DocumentFormat.CBZ,
                pageCount = 6,
            ),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        viewModel.moveToLocation(ReaderLocation.PdfPage(3))
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.pageIndex.current)
    }

    /**
     * [ReaderViewModel.openDocument]의 동기적 초기화를 고정한다: 첫 번째 문서가 아직 로딩 중일 때 두
     * 번째 문서를 열면, 새 문서 자체의 로드가 따라잡을 때까지 이전 문서의 낡은 프레임을 화면에 남겨두는
     * 대신, 이전 문서의 UI 상태 — 텍스트, 현재 페이지, 슬롯, 아웃라인 — 의 모든 필드를 즉시 비워야 한다.
     */
    @Test
    fun openingAnotherDocumentImmediatelyClearsPreviousReaderContent() = runTest(dispatcher) {
        val viewModel = createViewModel(FakeDocumentRepository(DocumentId("doc-1")))

        viewModel.openDocument("doc-1")
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 320, height = 560)
        advanceUntilIdle()
        assertEquals("First stored page", viewModel.uiState.value.pageText)
        assertTrue(viewModel.uiState.value.currentPage.text.isNotEmpty())

        viewModel.openDocument("doc-2")

        assertTrue(viewModel.uiState.value.isLoading)
        assertEquals("", viewModel.uiState.value.pageText)
        assertEquals("", viewModel.uiState.value.currentPage.text)
        assertTrue(viewModel.uiState.value.pageSlots.isEmpty())
        assertTrue(viewModel.uiState.value.outlineItems.isEmpty())
    }

    @Test
    fun openingAnotherDocumentDoesNotReuseThePreviousDocumentsPageBreaker() = runTest(dispatcher) {
        val documentA = DocumentId("doc-breaker-a")
        val documentB = DocumentId("doc-breaker-b")
        val documentRepository = FakeDocumentRepository(
            documentId = documentA,
            paginatedText = "a".repeat(120),
            secondDocumentId = documentB,
            secondPageWindows = listOf(
                PageWindow(
                    pageIndex = PageIndex(current = 0, total = 1),
                    location = ReaderLocation.TextOffset(0),
                    text = "B0",
                    textRange = TextRange(0, 2),
                ),
            ),
        )
        val readerSettingsRepository = FakeReaderSettingsRepository()
        val viewModel = ReaderViewModel(
            documentRepository = documentRepository,
            bookmarkRepository = FakeBookmarkRepository(),
            readerSettingsRepository = readerSettingsRepository,
            readerRepository = FakeReaderRepository(),
            openReaderDocumentUseCase = OpenReaderDocumentUseCase(
                documentRepository,
                FakeReaderRepository(),
                readerSettingsRepository,
            ),
        )

        viewModel.openDocument(documentA.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 320, height = 560)
        advanceUntilIdle()
        assertEquals(true, documentRepository.lastPageBreakerByDocumentId[documentA])

        viewModel.openDocument(documentB.value)
        advanceUntilIdle()

        assertEquals(false, documentRepository.lastPageBreakerByDocumentId[documentB])
    }

    /**
     * `ReaderViewModel.loadOpenState`가 자신의 `DocumentRepository.getPageWindows` 호출 직후,
     * `viewportSize`, `pageBreakerStyle`, 그리고 뷰 모델 자체의 `paginated` 필드를 쓰기 바로 전에
     * 수행하는 [currentDocumentId][ReaderViewModel] 재확인을 고정한다. `Job.cancel()`은 이미 진행
     * 중인 데이터베이스 읽기를 멈출 수 없으므로([ReaderViewModel] 자체의 클래스 문서 참고),
     * `openDocument`가 이전 문서의 job을 동기적으로 취소해도 이미 처리 중인 문서 A의
     * [FakeDocumentRepository.getPageWindows] 호출은 철회되지 않는다 — 문서 B 자체의 open이 이미
     * 완료된 뒤 [FakeDocumentRepository.unfreezeGetPageWindows]로 여기서 그것을 풀어주는 것이 바로 그
     * 늦은 처리를 모델링한다. 재확인이 없다면, A의 처리 중인 읽기는 문서 B의 open이 방금 발행을 마친
     * `paginated` 필드를 조용히 덮어써버린다 — `publishFirstFrame` 자체의 별도 가드가 여전히 A의
     * 프레임을 B의 것 위에 발행하는 것을 거부함에도 말이다. 바로 이 때문에 이 손상은 다른 무언가가
     * 손상된 필드를 읽기 전까지 — 여기서는 [ReaderViewModel.moveNext]를 통한 페이지 넘김 — 보이지
     * 않은 채로 남는다.
     *
     * [FakeDocumentRepository.freezeGetPageWindowsAtCallIndex]는 호출 0 — 문서 A 자체의
     * [FakeDocumentRepository.getPageWindows] 호출 — 을 [CompletableDeferred] 대신 순수한
     * [suspendCoroutine]에 대기시킨다. `CompletableDeferred.await()` 게이트 자체가 취소 가능한
     * 중단 지점이기 때문이다: 문서 A의 open을 그런 게이트에 대기시키면 `openDocument(documentB.value)`가
     * 자신의 job을 취소하는 순간 [kotlinx.coroutines.CancellationException]으로 재개되어, 이 테스트가
     * 고정하려는 가드된 줄들이 아예 실행조차 되지 않으며, 가드가 있든 없든 테스트가 통과했을 것이다 —
     * 이 테스트를 처음 시도했을 때 실제로 저지른 실수다. 문서 B 자체의
     * [FakeDocumentRepository.getPageWindows] 호출(호출 인덱스 1, 다른 모든 id가 받는 "알 수 없는
     * 문서" 빈 응답이 아니라 [FakeDocumentRepository] 자체의 두 번째 문서 지원에서 응답된다)은 같은
     * freeze에 걸리지 않으므로, 문서 B의 open은 여전히 대기 중인 문서 A의 open 아래에서 끝까지
     * 실행된다.
     */
    @Test
    fun openDocumentDoesNotLetAStaleGetPageWindowsReadOverwriteTheNewDocumentsPagination() = runTest(dispatcher) {
        val documentA = DocumentId("doc-a")
        val documentB = DocumentId("doc-b")
        val pageWindowsA = listOf(
            PageWindow(pageIndex = PageIndex(0, 2), location = ReaderLocation.TextOffset(0), text = "Document A page 0", textRange = TextRange(0L, 10L)),
            PageWindow(pageIndex = PageIndex(1, 2), location = ReaderLocation.TextOffset(10), text = "Document A page 1", textRange = TextRange(10L, 20L)),
        )
        val pageWindowsB = listOf(
            PageWindow(pageIndex = PageIndex(0, 2), location = ReaderLocation.TextOffset(0), text = "Document B page 0", textRange = TextRange(0L, 10L)),
            PageWindow(pageIndex = PageIndex(1, 2), location = ReaderLocation.TextOffset(10), text = "Document B page 1", textRange = TextRange(10L, 20L)),
        )
        val documentRepository = FakeDocumentRepository(
            documentId = documentA,
            pageWindows = pageWindowsA,
            freezeGetPageWindowsAtCallIndex = 0,
            secondDocumentId = documentB,
            secondPageWindows = pageWindowsB,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentA.value)
        advanceUntilIdle()

        viewModel.openDocument(documentB.value)
        advanceUntilIdle()
        assertEquals(
            "Document B page 0",
            viewModel.uiState.value.pageText,
            "document B's own open must have published its own first page before A's frozen read is released",
        )

        documentRepository.unfreezeGetPageWindows()
        advanceUntilIdle()

        viewModel.moveNext()
        advanceUntilIdle()

        assertEquals(
            "Document B page 1",
            viewModel.uiState.value.pageText,
            "a page turn after document A's stale getPageWindows read resolves must still read document " +
                "B's own pagination, not document A's, even though A's read landed after B was already open",
        )
    }

    @Test
    fun reloadPagesDoesNotLetAStaleGetPageWindowsReadOverwriteTheNewDocumentsPagination() = runTest(dispatcher) {
        val documentA = DocumentId("doc-a")
        val documentB = DocumentId("doc-b")
        val pageWindowsA = listOf(
            PageWindow(pageIndex = PageIndex(0, 2), location = ReaderLocation.TextOffset(0), text = "Reload A page 0", textRange = TextRange(0L, 10L)),
            PageWindow(pageIndex = PageIndex(1, 2), location = ReaderLocation.TextOffset(10), text = "Reload A page 1", textRange = TextRange(10L, 20L)),
        )
        val pageWindowsB = listOf(
            PageWindow(pageIndex = PageIndex(0, 2), location = ReaderLocation.TextOffset(0), text = "Reload B page 0", textRange = TextRange(0L, 10L)),
            PageWindow(pageIndex = PageIndex(1, 2), location = ReaderLocation.TextOffset(10), text = "Reload B page 1", textRange = TextRange(10L, 20L)),
        )
        val documentRepository = FakeDocumentRepository(
            documentId = documentA,
            pageWindows = pageWindowsA,
            freezeGetPageWindowsAtCallIndex = 1,
            secondDocumentId = documentB,
            secondPageWindows = pageWindowsB,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentA.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 200, height = 400)
        advanceUntilIdle()

        viewModel.openDocument(documentB.value)
        advanceUntilIdle()
        assertEquals("Reload B page 0", viewModel.uiState.value.pageText)

        documentRepository.unfreezeGetPageWindows()
        advanceUntilIdle()

        viewModel.moveNext()
        advanceUntilIdle()

        assertEquals(
            "Reload B page 1",
            viewModel.uiState.value.pageText,
            "a stale reload from the previous document must not overwrite the new document's internal pagination",
        )
    }

    /**
     * 텍스트 문서에 저장된 [ReaderLocation.TextOffset] 진행 상황은, [openDocument] 자체가 추정한
     * 기본 viewport가 아니라 실제 pane 측정을 대상으로 pagination이 실제로 실행된 뒤에 올바른
     * 페이지로 해석되어야 한다.
     */
    @Test
    fun openDocumentRestoresSavedOffsetAfterViewportPagination() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(300))
        val readerRepository = FakeReaderRepository()
        readerRepository.progress = ReadingProgress(
            documentId = documentId,
            location = ReaderLocation.TextOffset(210),
            pageIndex = PageIndex(current = 7, total = 10),
            updatedAtEpochMillis = 0,
        )
        val viewModel = ReaderViewModel(
            documentRepository = documentRepository,
            bookmarkRepository = FakeBookmarkRepository(),
            readerSettingsRepository = FakeReaderSettingsRepository(),
            readerRepository = readerRepository,
            openReaderDocumentUseCase = OpenReaderDocumentUseCase(documentRepository, readerRepository, FakeReaderSettingsRepository()),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()

        assertEquals(PageIndex(current = 7, total = 10), viewModel.uiState.value.pageIndex)
    }

    /**
     * PDF에 저장된 [ReaderLocation.PdfPage] 진행 상황은 open 시 같은 페이지 번호로 해석되어야 한다
     * — visual 문서는 다시 거쳐갈 pagination 과정이 없으므로, 이는 [PaginatedDocument.pageOf]가
     * 아니라 저장된 [PageIndex]에서 직접 해석된다.
     */
    @Test
    fun openDocumentRestoresSavedPdfPage() = runTest(dispatcher) {
        val documentId = DocumentId("doc-pdf")
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            format = DocumentFormat.PDF,
            pageCount = 10,
        )
        val readerRepository = FakeReaderRepository()
        readerRepository.progress = ReadingProgress(
            documentId = documentId,
            location = ReaderLocation.PdfPage(7),
            pageIndex = PageIndex(current = 7, total = 10),
            updatedAtEpochMillis = 0,
        )
        val viewModel = ReaderViewModel(
            documentRepository = documentRepository,
            bookmarkRepository = FakeBookmarkRepository(),
            readerSettingsRepository = FakeReaderSettingsRepository(),
            readerRepository = readerRepository,
            openReaderDocumentUseCase = OpenReaderDocumentUseCase(documentRepository, readerRepository, FakeReaderSettingsRepository()),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertEquals(PageIndex(current = 7, total = 10), viewModel.uiState.value.pageIndex)
    }

    /**
     * CBZ 문서를 열면 [ReaderViewModel.openDocument]의 [DocumentFormat.CBZ] 분기가 시작되며, 이는
     * [DocumentRepository.getVisualPageImages]를 통해 현재 페이지 주변의 visual 페이지 이미지를
     * 미리 로드한다.
     */
    @Test
    fun openComicDocumentLoadsItsVisualPage() = runTest(dispatcher) {
        val documentId = DocumentId("comic-1")
        val imageBytes = byteArrayOf(1, 2, 3)
        val viewModel = createViewModel(
            FakeDocumentRepository(
                documentId = documentId,
                format = DocumentFormat.CBZ,
                pageCount = 1,
                visualPageImages = mapOf(0 to imageBytes),
            ),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertEquals(DocumentFormat.CBZ, viewModel.uiState.value.documentFormat)
        assertContentEquals(imageBytes, viewModel.uiState.value.visualPageImages[0])
    }

    @Test
    fun moveToCachedComicWindowRepublishesTheCurrentWindowSnapshot() = runTest(dispatcher) {
        val documentId = DocumentId("comic-window")
        val images = (0..3).associateWith { page -> byteArrayOf((page + 1).toByte()) }
        val viewModel = createViewModel(
            FakeDocumentRepository(
                documentId = documentId,
                format = DocumentFormat.CBZ,
                pageCount = 4,
                visualPageImages = images,
            ),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        assertEquals(setOf(0, 1, 2, 3), viewModel.uiState.value.visualPageImages.keys)

        viewModel.moveToPage(3)
        advanceUntilIdle()

        assertEquals(setOf(1, 2, 3), viewModel.uiState.value.visualPageImages.keys)
        assertContentEquals(byteArrayOf(4), viewModel.uiState.value.visualPageImages.getValue(3))
    }


    @Test
    fun failedComicWindowFetchClearsStaleImagesOutsideTheNewWindow() = runTest(dispatcher) {
        val documentId = DocumentId("comic-window-fail")
        val images = (0..5).associateWith { page -> byteArrayOf((page + 1).toByte()) }
        val viewModel = createViewModel(
            FakeDocumentRepository(
                documentId = documentId,
                format = DocumentFormat.CBZ,
                pageCount = 6,
                visualPageImages = images,
                throwOnGetVisualPageImagesCall = 1,
            ),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        assertEquals(setOf(0, 1, 2, 3), viewModel.uiState.value.visualPageImages.keys)

        viewModel.moveToPage(5)
        advanceUntilIdle()

        assertEquals(setOf(3), viewModel.uiState.value.visualPageImages.keys)
        assertEquals(setOf(4, 5), viewModel.uiState.value.failedVisualPages)
    }


    /**
     * EPUB 문서를 열면 현재 페이지 자체의 블록(저장된 [PageWindow]에 이미 있는)이 발행되고
     * [DocumentRepository.getEmbeddedImages]를 통해 내장 이미지가 미리 로드되며, 둘 다
     * [ReaderUiState.currentPage]에 도달한다.
     */
    @Test
    fun openEpubDocumentLoadsCurrentPageBlocksAndEmbeddedImages() = runTest(dispatcher) {
        val documentId = DocumentId("epub-1")
        val imageBytes = byteArrayOf(9, 8, 7)
        val block = ReaderBlock(
            kind = ReaderBlockKind.IMAGE,
            range = TextRange(0, 1),
            imageHref = "images/pic.png",
            label = "Cover art",
        )
        val viewModel = createViewModel(
            FakeDocumentRepository(
                documentId = documentId,
                format = DocumentFormat.EPUB,
                readerDocument = ReaderDocument(
                    id = documentId,
                    format = DocumentFormat.EPUB,
                    title = "Stored epub",
                    sections = emptyList(),
                    blocks = listOf(block),
                ),
                pageWindows = listOf(
                    PageWindow(
                        pageIndex = PageIndex(current = 0, total = 1),
                        location = ReaderLocation.TextOffset(0),
                        text = "\n",
                        textRange = TextRange(0, 1),
                        blocks = listOf(block),
                    ),
                ),
                embeddedImages = mapOf("images/pic.png" to imageBytes),
            ),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 320, height = 560)
        advanceUntilIdle()

        assertEquals(listOf(block), viewModel.uiState.value.currentPage.blocks)
        assertContentEquals(imageBytes, viewModel.uiState.value.currentPage.embeddedImages["images/pic.png"])
    }

    @Test
    fun openEpubDocumentPublishesEmbeddedFontFilesAndFailuresAfterFirstFrame() = runTest(dispatcher) {
        val documentId = DocumentId("epub-fonts")
        val block = ReaderBlock(
            kind = ReaderBlockKind.PARAGRAPH,
            range = TextRange(0, 4),
            style = ReaderBlockStyle(fontHref = "fonts/body.otf"),
            spans = listOf(
                ReaderSpan(range = TextRange(0, 4), styleDelta = ReaderSpanStyle(fontHref = "fonts/missing.otf")),
            ),
        )
        val viewModel = createViewModel(
            FakeDocumentRepository(
                documentId = documentId,
                format = DocumentFormat.EPUB,
                readerDocument = ReaderDocument(
                    id = documentId,
                    format = DocumentFormat.EPUB,
                    title = "Stored epub",
                    sections = emptyList(),
                    blocks = listOf(block),
                ),
                pageWindows = listOf(
                    PageWindow(
                        pageIndex = PageIndex(current = 0, total = 1),
                        location = ReaderLocation.TextOffset(0),
                        text = "font",
                        textRange = TextRange(0, 4),
                        blocks = listOf(block),
                    ),
                ),
                embeddedFontFiles = mapOf("fonts/body.otf" to "/tmp/body.otf"),
            ),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertEquals(mapOf("fonts/body.otf" to "/tmp/body.otf"), viewModel.uiState.value.embeddedFontFiles)
        assertEquals(setOf("fonts/missing.otf"), viewModel.uiState.value.failedEmbeddedFontHrefs)
        assertEquals(mapOf("fonts/body.otf" to "/tmp/body.otf"), viewModel.uiState.value.currentPage.embeddedFontFiles)
        assertEquals(setOf("fonts/missing.otf"), viewModel.uiState.value.currentPage.failedEmbeddedFontHrefs)
        assertEquals(
            "fonts/body.otf=loaded|fonts/missing.otf=failed",
            viewModel.uiState.value.style.publisherFontKey,
        )
    }

    /**
     * 내장 폰트 해석은 첫 프레임 이후, opened-at 쓰기가 끝나기 전에 시작되므로, 그 무관한 데이터베이스
     * 쓰기가 여전히 대기 중인 동안에도 색인된 폰트 집합이 layout key를 정착시킬 수 있다. Import
     * continuation은 완료된 아웃라인 순서를 지키기 위해 그 쓰기 뒤에 남아있는다.
     */
    @Test
    fun epubFontResolutionOverlapsMarkDocumentOpenedWrite() = runTest(dispatcher) {
        val documentId = DocumentId("epub-font-overlap")
        val markDocumentOpenedGate = CompletableDeferred<Unit>()
        val block = ReaderBlock(
            kind = ReaderBlockKind.PARAGRAPH,
            range = TextRange(0, 4),
            style = ReaderBlockStyle(fontHref = "fonts/body.otf"),
        )
        val viewModel = createViewModel(
            FakeDocumentRepository(
                documentId = documentId,
                format = DocumentFormat.EPUB,
                readerDocument = ReaderDocument(
                    id = documentId,
                    format = DocumentFormat.EPUB,
                    title = "Font overlap",
                    sections = emptyList(),
                ),
                pageWindows = listOf(
                    PageWindow(
                        pageIndex = PageIndex(current = 0, total = 1),
                        location = ReaderLocation.TextOffset(0),
                        text = "font",
                        textRange = TextRange(0, 4),
                        blocks = listOf(block),
                    ),
                ),
                embeddedFontFiles = mapOf("fonts/body.otf" to "/tmp/body.otf"),
                markDocumentOpenedGate = markDocumentOpenedGate,
            ),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertEquals(
            mapOf("fonts/body.otf" to "/tmp/body.otf"),
            viewModel.uiState.value.embeddedFontFiles,
            "font resolution must finish while markDocumentOpened is still suspended",
        )
        markDocumentOpenedGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun epubPublisherFontKeyDoesNotShrinkWhenTheMountedWindowMoves() = runTest(dispatcher) {
        val documentId = DocumentId("epub-font-key")
        fun page(page: Int, href: String) = PageWindow(
            pageIndex = PageIndex(current = page, total = 2),
            location = ReaderLocation.TextOffset(page.toLong()),
            text = "p$page",
            textRange = TextRange(page.toLong(), page.toLong() + 1),
            blocks = listOf(
                ReaderBlock(
                    kind = ReaderBlockKind.PARAGRAPH,
                    range = TextRange(page.toLong(), page.toLong() + 1),
                    style = ReaderBlockStyle(fontHref = href),
                ),
            ),
        )
        val viewModel = createViewModel(
            FakeDocumentRepository(
                documentId = documentId,
                format = DocumentFormat.EPUB,
                readerDocument = ReaderDocument(
                    id = documentId,
                    format = DocumentFormat.EPUB,
                    title = "Stored epub",
                    sections = emptyList(),
                ),
                pageWindows = listOf(page(0, "fonts/a.otf"), page(1, "fonts/b.otf")),
                embeddedFontFiles = mapOf(
                    "fonts/a.otf" to "/tmp/random-a-1.otf",
                    "fonts/b.otf" to "/tmp/random-b-2.otf",
                ),
            ),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        val firstKey = viewModel.uiState.value.style.publisherFontKey

        viewModel.moveToPage(1)
        advanceUntilIdle()
        val secondKey = viewModel.uiState.value.style.publisherFontKey

        assertEquals("fonts/a.otf=loaded|fonts/b.otf=loaded", firstKey)
        assertEquals(firstKey, secondKey)
    }

    @Test
    fun embeddedFontFetchFailureMarksHrefsFailed() = runTest(dispatcher) {
        val documentId = DocumentId("epub-font-fail")
        val block = ReaderBlock(
            kind = ReaderBlockKind.PARAGRAPH,
            range = TextRange(0, 1),
            style = ReaderBlockStyle(fontHref = "fonts/fail.otf"),
        )
        val viewModel = createViewModel(
            FakeDocumentRepository(
                documentId = documentId,
                format = DocumentFormat.EPUB,
                readerDocument = ReaderDocument(
                    id = documentId,
                    format = DocumentFormat.EPUB,
                    title = "Stored epub",
                    sections = emptyList(),
                ),
                pageWindows = listOf(
                    PageWindow(
                        pageIndex = PageIndex(current = 0, total = 1),
                        location = ReaderLocation.TextOffset(0),
                        text = "x",
                        textRange = TextRange(0, 1),
                        blocks = listOf(block),
                    ),
                ),
                throwOnGetEmbeddedFontFilesCall = 0,
            ),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertEquals(setOf("fonts/fail.otf"), viewModel.uiState.value.failedEmbeddedFontHrefs)
        assertEquals("fonts/fail.otf=failed", viewModel.uiState.value.style.publisherFontKey)
    }

    @Test
    fun transientEmbeddedImagePreloadFailureRetriesOnTheNextPreloadTrigger() = runTest(dispatcher) {
        val documentId = DocumentId("epub-transient-image")
        val imageBytes = byteArrayOf(7, 8, 9)
        val block = ReaderBlock(
            kind = ReaderBlockKind.IMAGE,
            range = TextRange(0, 1),
            imageHref = "images/pic.png",
            label = "Inline art",
        )
        val viewModel = createViewModel(
            FakeDocumentRepository(
                documentId = documentId,
                format = DocumentFormat.EPUB,
                readerDocument = ReaderDocument(
                    id = documentId,
                    format = DocumentFormat.EPUB,
                    title = "Stored epub",
                    sections = emptyList(),
                    blocks = listOf(block),
                ),
                pageWindows = listOf(
                    PageWindow(
                        pageIndex = PageIndex(current = 0, total = 1),
                        location = ReaderLocation.TextOffset(0),
                        text = "\n",
                        textRange = TextRange(0, 1),
                        blocks = listOf(block),
                    ),
                ),
                embeddedImages = mapOf("images/pic.png" to imageBytes),
                throwOnGetEmbeddedImagesCall = 0,
            ),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.currentPage.embeddedImages.isEmpty(),
            "the first preload is forced to fail, so the current page must still have no embedded image bytes yet",
        )

        viewModel.reportMeasuredViewport(width = 320, height = 560)
        advanceUntilIdle()

        assertContentEquals(
            imageBytes,
            viewModel.uiState.value.currentPage.embeddedImages["images/pic.png"],
            "a transient embedded-image fetch failure must be retried on the next preload trigger",
        )
    }

    /**
     * EPUB이 패키지 제목과 navigation을 가지고 있으면, [ReaderUiState.documentTitle]과 아웃라인
     * (heading, 항목 제목, 레벨, 각 항목의 [ReaderLocation.EpubOffset])은 섹션별 폴백이 아니라
     * [readerOutlineItems]를 거쳐 그 navigation에서 온다.
     */
    @Test
    fun epubDocumentUsesStoredTitleAndNavigationOutline() = runTest(dispatcher) {
        val documentId = DocumentId("epub-outline")
        val viewModel = createViewModel(
            FakeDocumentRepository(
                documentId = documentId,
                format = DocumentFormat.EPUB,
                readerDocument = ReaderDocument(
                    id = documentId,
                    format = DocumentFormat.EPUB,
                    title = "Package Title",
                    sections = listOf(
                        ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                        ReaderSection(1, text = "Body", range = TextRange(2, 6), title = "Body"),
                    ),
                    navigation = ReaderNavigation(
                        heading = "Contents",
                        items = listOf(
                            ReaderNavigationItem(title = "Chapter 1", level = 1, spineIndex = 1, offset = 0),
                            ReaderNavigationItem(title = "Scene", level = 2, spineIndex = 1, offset = 3),
                        ),
                    ),
                ),
                pageWindows = listOf(
                    PageWindow(
                        pageIndex = PageIndex(current = 0, total = 1),
                        location = ReaderLocation.EpubOffset(1, 0),
                        text = "Body",
                        textRange = TextRange(2, 6),
                    ),
                ),
            ),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertEquals("Package Title", viewModel.uiState.value.documentTitle)
        assertEquals("Contents", viewModel.uiState.value.outlineHeading)
        assertEquals(listOf("Chapter 1", "Scene"), viewModel.uiState.value.outlineItems.map { it.title })
        assertEquals(listOf(1, 2), viewModel.uiState.value.outlineItems.map { it.level })
        assertEquals(
            listOf(ReaderLocation.EpubOffset(1, 0), ReaderLocation.EpubOffset(1, 3)),
            viewModel.uiState.value.outlineItems.map { it.location },
        )
    }

    /**
     * `ReaderViewModel.continueImportIfIncomplete`의 완료 분기가 지금 고치는 버그를 고정한다: EPUB
     * navigation은 책을 완성하는 import 배치(`DocumentRepositoryImpl.importEpubPhase0`/
     * `finishEpubImport`)에서만 저장된 문서로 해석되므로, 아직 완료되지 않은 import로부터 open 시점에
     * 딱 한 번 발행된 아웃라인은 리더가 앱을 재실행할 때까지 그때 읽었던 그대로 — 비어 있거나
     * section뿐이거나 — 멈춰 있었다. import가 아직 실행 중인 동안의 open은 아직 해석되지 않은
     * navigation을 보여주어서는 안 되며, import를 마치면 재실행 없이 방금 import된 문서로부터
     * 아웃라인(heading 포함)을 다시 발행해야 한다.
     */
    @Test
    fun epubOutlineFillsInFromNavigationOnceProgressiveImportCompletes() = runTest(dispatcher) {
        val documentId = DocumentId("epub-outline-completes")
        val initialSections = listOf(
            ReaderSection(0, text = "Body one", range = TextRange(0, 8), title = "Body one"),
        )
        val importedSections = listOf(
            ReaderSection(1, text = "Body two", range = TextRange(9, 17), title = "Body two"),
        )
        val importNextSectionsGate = CompletableDeferred<Unit>()
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            format = DocumentFormat.EPUB,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.EPUB,
                title = "Completing book",
                sections = initialSections,
                navigation = ReaderNavigation(
                    heading = "Contents",
                    items = listOf(
                        ReaderNavigationItem(title = "Chapter 1", level = 1, spineIndex = 0, offset = 0),
                        ReaderNavigationItem(title = "Chapter 2", level = 1, spineIndex = 1, offset = 0),
                    ),
                ),
            ),
            pageWindows = listOf(
                PageWindow(
                    pageIndex = PageIndex(current = 0, total = 1),
                    location = ReaderLocation.EpubOffset(0, 0),
                    text = "Body one",
                    textRange = TextRange(0, 8),
                ),
            ),
            importComplete = false,
            sectionsAppendedOnImport = importedSections,
            importNextSectionsGate = importNextSectionsGate,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertFalse(
            viewModel.uiState.value.isPaginationComplete,
            "the import must still be running (parked on importNextSectionsGate) at this point for " +
                "this test to pin anything",
        )
        assertTrue(
            viewModel.uiState.value.outlineItems.none { it.title == "Chapter 1" || it.title == "Chapter 2" },
            "navigation not yet resolved by the still-running import must not appear in the outline; " +
                "actual titles were ${viewModel.uiState.value.outlineItems.map { it.title }}",
        )

        importNextSectionsGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.isPaginationComplete,
            "the background continuation must have finished the import by now",
        )
        assertEquals(
            "Contents",
            viewModel.uiState.value.outlineHeading,
            "outline heading must republish from the completed import's navigation, not stay pinned " +
                "to the empty heading the open-time snapshot carried",
        )
        assertEquals(
            listOf("Chapter 1", "Chapter 2"),
            viewModel.uiState.value.outlineItems.map { it.title },
            "once the progressive import completes, the outline must republish from the completed " +
                "document's navigation without waiting for a relaunch",
        )
    }

    /**
     * `ReaderViewModel.openDocument`가 이제 `ReaderViewModel.publishRest`와
     * `ReaderViewModel.startContinuations` 사이에 보장하는 순서를 고정한다: [publishRest] — 그 자체의
     * 중단되는 [DocumentRepository.markDocumentOpened] 쓰기를 포함하여 — 는 [startContinuations]가
     * `ReaderViewModel.continueImportIfIncomplete`를 시작하기 전에 완료까지 실행되어야 한다. 그래야
     * 그 continuation의 완료 분기가 해석된 아웃라인을 [publishRest]보다 먼저 발행해서 [publishRest]
     * 자체의 더 오래된 open 시점 스냅샷에 덮어써지는 일이 절대 없다.
     *
     * 위의 [epubOutlineFillsInFromNavigationOnceProgressiveImportCompletes]는 오직
     * [FakeDocumentRepository.importNextSectionsGate]를 게이트하기 때문에 — 그 테스트에서
     * `publishRest` 자체의 중단이 그보다 먼저 해소되는 것이 보장되는 바로 그 게이트다 — [publishRest]가
     * import continuation의 첫 배치가 도착하기 전에 이미 끝난 경우만을 다룬다. 이 테스트는 대신
     * [FakeDocumentRepository.markDocumentOpenedGate]를 게이트하고 import는 게이트하지 않은 채로
     * 두는데, 이 스위트가 모든 코루틴을 수동으로 진행되는 [dispatcher]를 통해 구동하기 때문에, 이는
     * 완료 분기가 먼저 실행되어 해석된 navigation을 끝까지 발행하도록 강제한다 — `markDocumentOpened`
     * (따라서 [publishRest] 자체의 아웃라인 발행)가 재개되도록 허용되기 엄격히 전에 말이다. 만약
     * `openDocument`가 [publishRest]를 다시 호출하기 전에 continuation을 시작한 적이 있었다면, 여기서
     * [publishRest]가 재개되며 새로운 heading/items를 open 시점에 캡처한 빈 navigation으로 덮어썼을
     * 것이다 — 원래 보고가 그랬던 것처럼 실제 I/O 타이밍에 의존하는 대신, 이 수정 분기가 존재하는
     * 이유인 버그를 결정론적으로 재현하는 것이다.
     */
    @Test
    fun openDocumentDoesNotLetPublishRestClobberAnImportCompletionOutlineRepublish() = runTest(dispatcher) {
        val documentId = DocumentId("epub-outline-races-mark-opened")
        val initialSections = listOf(
            ReaderSection(0, text = "Body one", range = TextRange(0, 8), title = "Body one"),
        )
        val importedSections = listOf(
            ReaderSection(1, text = "Body two", range = TextRange(9, 17), title = "Body two"),
        )
        val markDocumentOpenedGate = CompletableDeferred<Unit>()
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            format = DocumentFormat.EPUB,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.EPUB,
                title = "Racing book",
                sections = initialSections,
                navigation = ReaderNavigation(
                    heading = "Contents",
                    items = listOf(
                        ReaderNavigationItem(title = "Chapter 1", level = 1, spineIndex = 0, offset = 0),
                        ReaderNavigationItem(title = "Chapter 2", level = 1, spineIndex = 1, offset = 0),
                    ),
                ),
            ),
            pageWindows = listOf(
                PageWindow(
                    pageIndex = PageIndex(current = 0, total = 1),
                    location = ReaderLocation.EpubOffset(0, 0),
                    text = "Body one",
                    textRange = TextRange(0, 8),
                ),
            ),
            importComplete = false,
            sectionsAppendedOnImport = importedSections,
            markDocumentOpenedGate = markDocumentOpenedGate,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        markDocumentOpenedGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.isPaginationComplete,
            "the background import continuation must have finished by now, independently of " +
                "markDocumentOpened's own gate",
        )
        assertEquals(
            "Contents",
            viewModel.uiState.value.outlineHeading,
            "the completion branch's fresh outline publish must survive publishRest resuming " +
                "afterward — publishRest must already have finished (including its own outline " +
                "publish) before the completion branch could ever run, not the other way around",
        )
        assertEquals(
            listOf("Chapter 1", "Chapter 2"),
            viewModel.uiState.value.outlineItems.map { it.title },
            "outline items must likewise survive publishRest's later resume, not fall back to the " +
                "open-time section list publishRest originally captured",
        )
    }

    /**
     * 리더는 여전히 책 전체의 페이지 목록을 만들지 않는다: mount된 window만 [ReaderUiState.pageSlots]를
     * 통해 발행되며, 현재 페이지의 텍스트는 즉시 사용 가능하다.
     */
    @Test
    fun openDocumentProvidesMountedPageSlotsAndCurrentPageText() = runTest(dispatcher) {
        val documentId = DocumentId("doc-large")
        val viewModel = createViewModel(
            FakeDocumentRepository(documentId, paginatedText = "a".repeat(300)),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pageSlots.isNotEmpty())
        assertEquals("a".repeat(30), viewModel.uiState.value.currentPage.text)
    }

    /**
     * [ReaderViewModel]을 통한 [PaginatedDocument.chapterTitleAt]의 상속을 고정한다: 챕터 제목은
     * 그 챕터의 첫 페이지뿐 아니라 챕터 전체에서 상단 바에 고정되어 있어야 한다 — 챕터의 첫 페이지에서
     * 같은 챕터의 후속 페이지로 이동해도 제목을 잃어서는 안 된다.
     */
    @Test
    fun epubChapterTitlePersistsAcrossEveryPageOfTheSameChapter() = runTest(dispatcher) {
        val documentId = DocumentId("epub-chapter")
        val chapterStart = PageWindow(
            pageIndex = PageIndex(current = 0, total = 2),
            location = ReaderLocation.EpubOffset(1, 0),
            text = "2 - 1화 기회 (1)\n본문 첫 페이지",
            textRange = TextRange(11, 28),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.HEADING, range = TextRange(11, 22)),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(23, 28)),
            ),
        )
        val laterPage = PageWindow(
            pageIndex = PageIndex(current = 1, total = 2),
            location = ReaderLocation.EpubOffset(1, 17),
            text = "다음 페이지",
            textRange = TextRange(28, 33),
        )
        val viewModel = createViewModel(
            FakeDocumentRepository(
                documentId = documentId,
                format = DocumentFormat.EPUB,
                readerDocument = ReaderDocument(
                    id = documentId,
                    format = DocumentFormat.EPUB,
                    title = "Stored epub",
                    sections = listOf(
                        ReaderSection(0, text = "cover text", range = TextRange(0, 10), title = "Cover"),
                        ReaderSection(1, text = "2 - 1화 기회 (1)\n본문 첫 페이지다음 페이지", range = TextRange(11, 33), title = "2 - 1화 기회 (1)"),
                    ),
                    navigation = ReaderNavigation(
                        heading = "Contents",
                        items = listOf(ReaderNavigationItem(title = "2 - 1화 기회 (1)", level = 1, spineIndex = 1, offset = 0)),
                    ),
                ),
                pageWindows = listOf(chapterStart, laterPage),
            ),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 320, height = 560)
        advanceUntilIdle()

        assertEquals("2 - 1화 기회 (1)", viewModel.uiState.value.currentPage.chapterTitle)
        assertEquals("2 - 1화 기회 (1)\n본문 첫 페이지", viewModel.uiState.value.currentPage.text)

        viewModel.moveToPage(1)
        advanceUntilIdle()

        assertEquals("2 - 1화 기회 (1)", viewModel.uiState.value.currentPage.chapterTitle)
        assertEquals("다음 페이지", viewModel.uiState.value.currentPage.text)
    }

    /** 저장소의 emission은 이미 열려 있는 리더에게 공유된 진실의 원천이다. */
    @Test
    fun repositorySettingsChangesReachAnAlreadyOpenReader() = runTest(dispatcher) {
        val documentId = DocumentId("doc-settings-flow")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(300))
        val settingsRepository = FakeReaderSettingsRepository()
        val viewModel = createViewModel(
            documentRepository = documentRepository,
            readerSettingsRepository = settingsRepository,
        )
        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        val settings = ReaderSettings(
            style = ReaderStyle(fontSizeSp = 22f, fontFamilyName = "serif"),
            pageTurnMode = PageTurnMode.VERTICAL,
            pageAnimation = PageAnimation.PAGE_FLIP,
            autoScrollConfig = AutoScrollConfig(mode = AutoScrollMode.PAGE, speed = 0.7f),
        )
        settingsRepository.emit(settings)
        advanceUntilIdle()

        assertEquals(settings.style, viewModel.uiState.value.style)
        assertEquals(settings.pageTurnMode, viewModel.uiState.value.pageTurnMode)
        assertEquals(settings.pageAnimation, viewModel.uiState.value.pageAnimation)
        assertEquals(settings.autoScrollConfig, viewModel.uiState.value.autoScrollConfig)
    }

    @Test
    fun failedReaderSettingWriteKeepsTheRepositoryValueVisibleWithoutBlockingTheReader() = runTest(dispatcher) {
        val documentId = DocumentId("doc-settings-failure")
        val settingsRepository = FakeReaderSettingsRepository().apply { failStyleWrites = true }
        val viewModel = createViewModel(
            documentRepository = FakeDocumentRepository(documentId),
            readerSettingsRepository = settingsRepository,
        )
        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        viewModel.updateThemeMode(ReaderThemeMode.DARK)
        advanceUntilIdle()

        assertEquals(ReaderThemeMode.PUBLISHER, viewModel.uiState.value.style.themeMode)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun changingOnlyTheThemeDoesNotLayTheBookOutAgain() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(300))
        val readerRepository = FakeReaderRepository()
        val viewModel = ReaderViewModel(
            documentRepository = documentRepository,
            bookmarkRepository = FakeBookmarkRepository(),
            readerSettingsRepository = FakeReaderSettingsRepository(),
            readerRepository = readerRepository,
            openReaderDocumentUseCase = OpenReaderDocumentUseCase(documentRepository, readerRepository, FakeReaderSettingsRepository()),
        )
        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()
        val laidOutBefore = documentRepository.pageWindowRequests

        viewModel.updateThemeMode(ReaderThemeMode.DARK)
        advanceUntilIdle()

        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()

        assertEquals(laidOutBefore, documentRepository.pageWindowRequests)
    }

    /**
     * pane이 새 폰트로 다시 측정하기 전에 [updateFontSize][ReaderViewModel.updateFontSize] 하나만으로는
     * [DocumentRepository.getPageWindows]에 새 레이아웃을 요청하지 않았음을 단언하여,
     * `ReaderViewModel.reloadPages` 안의 스타일 일치 가드(`pageBreakerStyle?.layoutKey() != style.layoutKey()`)를
     * 고정한다. 이 가드가 없으면 `updateStyle`은 여전히 이전 스타일로 측정된 page breaker를 사용해
     * 새 스타일로 책을 레이아웃하게 되어 — 리더가 실제 폰트로는 아직 본 적 없는 페이지에 낡은 측정값이
     * 섞여 들어간다. 이는 이 프로젝트의 리더 불변 조건이 이름으로 콕 집어 지적하는 종류의
     * 스타일/콘텐츠 불일치다. 이 테스트의 원래 버전은 pane의 두 번째, 일치하는 보고 이후에만
     * 단언했는데, 이는 가드가 있든 없든 통과한다 — 그 단언은 결국 reload가 일어난다는 것만 증명할 뿐,
     * 가드가 그때까지 그것을 막았다는 것은 증명하지 못한다. 이 가드가 나중에 통합되었다면 여기 추가된
     * 단언 없이는 눈에 띄지 않고 넘어갔을 것이다. 이전 스타일로 측정된 breaker는 새 스타일을 안전하게
     * reload할 수 없으므로(`reloadPages` 자체의 가드 참고), [ReaderViewModel.updateFontSize]만 실행된
     * 뒤에는 아직 아무것도 `getPageWindows`를 조회하지 않았다 — pane이 새 폰트로 다시 측정해서 일치하는
     * breaker를 보고해야만 비로소 reload가 실제로 실행된다.
     */
    @Test
    fun changingTheFontSizeStillLaysTheBookOutAgain() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(300))
        val readerRepository = FakeReaderRepository()
        val viewModel = ReaderViewModel(
            documentRepository = documentRepository,
            bookmarkRepository = FakeBookmarkRepository(),
            readerSettingsRepository = FakeReaderSettingsRepository(),
            readerRepository = readerRepository,
            openReaderDocumentUseCase = OpenReaderDocumentUseCase(documentRepository, readerRepository, FakeReaderSettingsRepository()),
        )
        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()
        val laidOutBefore = documentRepository.pageWindowRequests

        viewModel.updateFontSize(24f)
        advanceUntilIdle()
        assertEquals(laidOutBefore, documentRepository.pageWindowRequests)

        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()

        assertTrue(documentRepository.pageWindowRequests > laidOutBefore)
    }

    /**
     * 낡은 페이지 조각(stale-page-slice) 결함에 대한 수정을 고정한다: 레이아웃에 영향을 주는 스타일
     * 변경(여기서는 폰트 패밀리)은 [ReaderUiState.style]을 즉시 발행하지만, [ReaderUiState.currentPage]에
     * 담긴 페이지들은 *이전* 스타일로 잘려 있었고, pane이 재구성·재측정하고 새 키에 대한 breaker를
     * 보고할 때까지 그 상태 그대로 남는다 — [changingTheFontSizeStillLaysTheBookOutAgain]이
     * `getPageWindows` 호출 횟수 쪽에서 고정하는 것과 같은 비동기 왕복이다. [ReaderUiState.pageDrawStyle]은
     * 두 페이지 surface가 실제로 그릴 때 쓰는 값이며, 이 테스트는 반대쪽 — 호출 횟수가 아니라 type — 에서
     * 그것을 고정한다. 중간 단언 — [ReaderViewModel.updateFontFamily] 직후 `documentRepository.pageWindowRequests`가
     * 바뀌지 않았다는 것 — 은, 실제로는 아무것도 다시 자르지 않는 fake를 상대로도 세 번째 단언이 의미를
     * 갖도록 만든다: 이는 아직 새 측정값이 도착하지 않았음을 증명하므로, 바로 그 순간
     * [ReaderUiState.pageDrawStyle]이 *이전* layout key로 답하는 것은 스타일을 무시하는 fake의 우연이
     * 아니라 수정이 실제로 그리는 type을 붙잡아 두고 있다는 뜻이다. pane이 다시 보고하고 실제 reload가
     * 도착하면, [ReaderUiState.pageDrawStyle]은 다시 [ReaderUiState.style]과 일치해야 한다 — 이전
     * type에서 새 type으로의 교체는 원자적이며, 그 둘 사이에 발행되는 중간 상태는 없다.
     *
     * 반증(AGENTS.md의 드릴): `ReaderUiState.pageDrawStyle`을 제자리에서 `get() = style`로 무력화하고
     * 이 스위트를 다시 실행한다. 이 테스트의 세 번째 단언만 실패할 수 있다 — `before.style.layoutKey()`와
     * `pageDrawStyle.layoutKey()`를 비교하는 단언으로, 이 테스트에서 [ReaderUiState.pageDrawStyle]을
     * 읽는 유일한 단언이다. 중간 단언은 `documentRepository.pageWindowRequests`를 읽는데, 이는 그
     * 무력화의 영향을 받지 않는 `getPageWindows` 호출 횟수다. [changingTheFontSizeStillLaysTheBookOutAgain]을
     * 포함해 이 클래스의 다른 모든 테스트는 여전히 통과해야 한다.
     */
    @Test
    fun fontFamilyChangeKeepsDrawingTheOldSlicesWithTheirOwnTypeUntilTheReloadLands() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(300))
        val viewModel = createViewModel(documentRepository)
        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()
        val before = viewModel.uiState.value
        val requestsBefore = documentRepository.pageWindowRequests

        viewModel.updateFontFamily("serif")
        advanceUntilIdle()

        assertEquals(
            "serif",
            viewModel.uiState.value.style.fontFamilyName,
            "the chosen font must show up in the style instantly",
        )
        assertEquals(
            requestsBefore,
            documentRepository.pageWindowRequests,
            "nothing has re-measured yet, so the pages on screen are still A's",
        )
        assertEquals(
            before.style.layoutKey(),
            viewModel.uiState.value.pageDrawStyle.layoutKey(),
            "pageDrawStyle must still describe the type the on-screen slices were actually cut under",
        )

        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()

        assertTrue(
            documentRepository.pageWindowRequests > requestsBefore,
            "the pane's remeasurement must have triggered a real reload",
        )
        assertEquals(
            viewModel.uiState.value.style.layoutKey(),
            viewModel.uiState.value.pageDrawStyle.layoutKey(),
            "once the reload lands, the drawn type must agree with the chosen style again",
        )
    }

    /**
     * [fontFamilyChangeKeepsDrawingTheOldSlicesWithTheirOwnTypeUntilTheReloadLands]와 같은
     * 낡은 페이지 조각 수정을, 폰트 패밀리 변경 대신 [ReaderViewModel.updateFontWeight]에 대해
     * 고정한다: 레이아웃에 영향을 주는 스타일 변경(여기서는 weight)은 [ReaderUiState.style]을 즉시
     * 발행하지만, [ReaderUiState.currentPage]에 담긴 페이지들은 *이전* weight로 잘려 있었고, pane이
     * 재구성·재측정하고 새 키에 대한 breaker를 보고할 때까지 그 상태 그대로 남는다.
     * [ReaderUiState.pageDrawStyle]은 두 페이지 surface가 실제로 그릴 때 쓰는 값이며, 이 테스트는
     * 폰트 패밀리 버전과 정확히 같은 방식으로 type 쪽에서 그것을 고정한다. 기본값이 아닌 weight(600)를
     * 의도적으로 골랐다 — weight는 `ReaderDefaultFontWeight`와 다를 때만 [layoutKey]를 바꾸므로
     * (`ReaderModelsTest.nonDefaultFontWeightChangesLayoutKeyButDefaultWeightDoesNot` 참고), 중간
     * 단언의 `getPageWindows` 호출 횟수 확인과 세 번째 단언의 `layoutKey()` 비교 둘 다 관찰하려면
     * 실제 layout-key 변경이 필요하다.
     *
     * 반증(AGENTS.md의 드릴): `ReaderUiState.pageDrawStyle`을 제자리에서 `get() = style`로 무력화하고
     * 이 스위트를 다시 실행한다. 이 테스트의 세 번째 단언만 실패할 수 있다 — `before.style.layoutKey()`와
     * `pageDrawStyle.layoutKey()`를 비교하는 단언이다 —
     * [fontFamilyChangeKeepsDrawingTheOldSlicesWithTheirOwnTypeUntilTheReloadLands]를 포함해 이
     * 클래스의 다른 모든 테스트는 여전히 통과해야 한다.
     */
    @Test
    fun fontWeightChangeKeepsDrawingTheOldSlicesWithTheirOwnTypeUntilTheReloadLands() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(300))
        val viewModel = createViewModel(documentRepository)
        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()
        val before = viewModel.uiState.value
        val requestsBefore = documentRepository.pageWindowRequests

        viewModel.updateFontWeight(600)
        advanceUntilIdle()

        assertEquals(
            600,
            viewModel.uiState.value.style.fontWeight,
            "the chosen weight must show up in the style instantly",
        )
        assertEquals(
            requestsBefore,
            documentRepository.pageWindowRequests,
            "nothing has re-measured yet, so the pages on screen are still the old weight's",
        )
        assertEquals(
            before.style.layoutKey(),
            viewModel.uiState.value.pageDrawStyle.layoutKey(),
            "pageDrawStyle must still describe the type the on-screen slices were actually cut under",
        )

        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()

        assertTrue(
            documentRepository.pageWindowRequests > requestsBefore,
            "the pane's remeasurement must have triggered a real reload",
        )
        assertEquals(
            viewModel.uiState.value.style.layoutKey(),
            viewModel.uiState.value.pageDrawStyle.layoutKey(),
            "once the reload lands, the drawn type must agree with the chosen style again",
        )
    }

    /**
     * 색상만 바뀌는 변경(여기서는 테마)은 layout-key 변경 동안 type을 고정하는 것과 같은 freeze에
     * 절대 걸려서는 안 된다: [ReaderUiState.pageDrawStyle]은 [layoutKey]가 스타일을 환원하는 네 개의
     * 레이아웃 필드만 고정할 뿐, [ReaderStyle.textColor]나 스타일의 나머지 색상 필드는 절대 고정하지
     * 않는다 — 그것들은 pane 보고 없이 살아 있는 [ReaderUiState.style]에서 그대로 흘러 들어온다. 색상은
     * 절대 페이지 나누기를 움직일 수 없기 때문이다. 이것은 "스타일 전체를 pagination을 기다리게 만드는
     * 설계는 회귀다"에 대한 회귀 가드다. 같은 주장의 "재-pagination 없음" 절반은 이미
     * [changingOnlyTheThemeDoesNotLayTheBookOutAgain]이 고정하고 있다.
     *
     * 폰트 패밀리를 테마 *이전에* 먼저 바꾸므로, 두 번째 단언이 실제로 freeze를 시험한다:
     * `updateThemeMode` 하나만으로는 [layoutKey]가 스타일을 환원하는 어떤 필드도 건드리지 않으므로,
     * 테마 변경만 한 뒤 `before.style.layoutKey()`와 살아 있는 스타일의 `layoutKey()`를 비교하면
     * [ReaderUiState.pageDrawStyle]이 무언가를 고정하든 말든 통과했을 것이다 — 그저 테마 변경은
     * 애초에 `layoutKey()`를 절대 움직이지 않는다는 사실을 되풀이할 뿐이다. 폰트 변경을 먼저 쌓으면
     * 고정된 스타일은 두 변경 이전의 type을 여전히 붙잡은 채로 *두 번째*, 무관한 발행(테마)을
     * 견뎌내야 하는데, 이것이 바로 freeze가 실제로 약속하는 바다.
     *
     * 반증(AGENTS.md의 드릴): `ReaderUiState.pageDrawStyle`을 제자리에서 `get() = style`로 무력화하고
     * 이 스위트를 다시 실행한다. 이 테스트의 *두 번째* 단언이 실패한다 — freeze가 사라지면
     * `pageDrawStyle.layoutKey()`는 그 변경 이전에 고정된 키 대신 살아 있는, 폰트가 바뀐 키를
     * 보고하기 때문이다. `textColor`에 대한 첫 번째 단언은 어느 쪽이든 계속 통과한다 — 색상은 freeze가
     * 있든 없든 살아 있는 스타일을 그대로 따르므로, 이 드릴이 시험하는 대상이 아니다.
     */
    @Test
    fun themeChangeReachesPageDrawStyleImmediatelyWithNoPaneReport() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(300))
        val viewModel = createViewModel(documentRepository)
        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()
        val before = viewModel.uiState.value

        viewModel.updateFontFamily("serif")
        advanceUntilIdle()

        viewModel.updateThemeMode(ReaderThemeMode.DARK)
        advanceUntilIdle()

        assertEquals(ReaderColor(ReaderDarkTextArgb), viewModel.uiState.value.pageDrawStyle.textColor)
        assertEquals(
            before.style.layoutKey(),
            viewModel.uiState.value.pageDrawStyle.layoutKey(),
            "the type pinned before the font change must still be what is drawn after an unrelated theme change lands on top of it",
        )
    }

    /**
     * 문서를 재-pagination하는 viewport 크기 변경은 리더를, 페이지 수가 바뀌면 완전히 다른 곳에
     * 도착하게 될 같은 페이지 *인덱스*가 아니라, 지금 그것을 담고 있는 새 페이지로 해석된 같은
     * 읽기 offset에 머무르게 해야 한다.
     */
    @Test
    fun repaginationKeepsCurrentReadingOffset() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(300))
        val readerRepository = FakeReaderRepository()
        val viewModel = ReaderViewModel(
            documentRepository = documentRepository,
            bookmarkRepository = FakeBookmarkRepository(),
            readerSettingsRepository = FakeReaderSettingsRepository(),
            readerRepository = readerRepository,
            openReaderDocumentUseCase = OpenReaderDocumentUseCase(documentRepository, readerRepository, FakeReaderSettingsRepository()),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()
        viewModel.moveToPage(6)
        advanceUntilIdle()

        viewModel.reportMeasuredViewport(width = 600, height = 900)
        advanceUntilIdle()

        assertEquals(PageIndex(current = 3, total = 5), viewModel.uiState.value.pageIndex)
    }

    /**
     * [ReaderViewModel.movePrevious]/[ReaderViewModel.moveNext] 자체의 계약을 고정한다: 이들은 실행될
     * 때 살아 있는 pagination을 기준으로 step만 해석할 뿐, 이전에 캡처된 페이지 인덱스는 절대 쓰지
     * 않는다. 그래서 그 사이에 일어나는 재-pagination(여기서는 폰트나 줄 높이 변경을 대신하는, 문서를
     * 더 짧게 재-pagination하는 viewport 크기 변경)이 대상을 잘못 놓을 수 없다. 새로 짧아진 문서를
     * 넘어서는 step은 마지막 페이지로 clamp되는 대신 완전히 버려진다.
     */
    @Test
    fun relativeMovesResolveAgainstTheLivePaginationInsteadOfClampingToTheLastPage() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(300))
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()
        viewModel.moveToPage(6)
        advanceUntilIdle()

        viewModel.reportMeasuredViewport(width = 600, height = 900)
        advanceUntilIdle()
        val afterRepagination = viewModel.uiState.value.pageIndex
        assertEquals(PageIndex(current = 3, total = 5), afterRepagination)

        viewModel.moveNext(step = 2)
        advanceUntilIdle()
        assertEquals(afterRepagination, viewModel.uiState.value.pageIndex)

        viewModel.moveNext(step = 1)
        advanceUntilIdle()
        assertEquals(4, viewModel.uiState.value.pageIndex.current)

        viewModel.movePrevious(step = 2)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.pageIndex.current)
    }

    @Test
    fun moveToPageDoesNotRestoreAnOldTotalAfterAConcurrentReloadPublishesANewOne() = runTest(dispatcher) {
        val documentId = DocumentId("doc-move-race")
        val initialSections = (0 until 10).map { index ->
            ReaderSection(index, text = "S$index", range = TextRange(index.toLong(), index.toLong() + 1), title = "S$index")
        }
        val importedSections = (10 until 20).map { index ->
            ReaderSection(index, text = "S$index", range = TextRange(index.toLong(), index.toLong() + 1), title = "S$index")
        }
        val importGate = CompletableDeferred<Unit>()
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.EPUB,
                title = "Move race book",
                sections = initialSections,
            ),
            freezeWarmSectionBlocksAtCallIndex = 1,
            importComplete = false,
            importNextSectionsGate = importGate,
            sectionsAppendedOnImport = importedSections,
            pageWindowsFollowLiveSections = true,
        )
        val readerRepository = FakeReaderRepository()
        val readerSettingsRepository = FakeReaderSettingsRepository()
        val viewModel = ReaderViewModel(
            documentRepository = documentRepository,
            bookmarkRepository = FakeBookmarkRepository(),
            readerSettingsRepository = readerSettingsRepository,
            readerRepository = readerRepository,
            openReaderDocumentUseCase = OpenReaderDocumentUseCase(
                documentRepository,
                readerRepository,
                readerSettingsRepository,
            ),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        assertEquals(PageIndex(current = 0, total = 10), viewModel.uiState.value.pageIndex)

        viewModel.moveToPage(6)
        advanceUntilIdle()

        importGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(PageIndex(current = 6, total = 20), viewModel.uiState.value.pageIndex)

        documentRepository.unfreezeWarmSectionBlocks()
        advanceUntilIdle()

        assertEquals(
            PageIndex(current = 6, total = 20),
            viewModel.uiState.value.pageIndex,
            "a delayed moveToPage publish must keep the newer total instead of restoring the pre-reload one",
        )
        assertEquals(PageIndex(current = 6, total = 20), readerRepository.progress?.pageIndex)
    }

    /**
     * 기본 정상성 확인: [ReaderViewModel.toggleFavorite]의 정상 경로는 발행된 플래그와 저장된 문서
     * 둘 다를 뒤집는다.
     */
    @Test
    fun favoriteToggleUpdatesReaderAndDocument() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId)
        val viewModel = createViewModel(documentRepository)
        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        viewModel.toggleFavorite()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFavorite)
        assertTrue(documentRepository.isFavorite)
    }

    /**
     * 기본 정상성 확인: [ReaderViewModel.toggleSavedPlace]는 첫 번째 탭에서 현재 페이지를 null
     * label과 [ReaderLocation.TextOffset] location을 가진 [Bookmark]로 저장하고, 두 번째 탭에서
     * 다시 제거한다.
     */
    @Test
    fun savedPlaceToggleUpdatesCurrentPageState() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val bookmarkRepository = FakeBookmarkRepository()
        val viewModel = createViewModel(
            documentRepository = FakeDocumentRepository(documentId),
            bookmarkRepository = bookmarkRepository,
        )
        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        viewModel.toggleSavedPlace()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isCurrentPageSaved)
        assertEquals(ReaderLocation.TextOffset(0), bookmarkRepository.bookmarks.value.single().location)
        assertEquals(null, bookmarkRepository.bookmarks.value.single().label)

        viewModel.toggleSavedPlace()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isCurrentPageSaved)
    }

    /**
     * [AutoScrollConfig.clampSpeed]의 하한을 고정한다: speed가 0이거나 그 이하일 때
     * [ReaderViewModel.updateAutoScrollSpeed]는 0 자체가 아니라 clamp된 최솟값을 발행하고 저장한다.
     */
    @Test
    fun updateAutoScrollSpeedClampsToMinimum() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val readerSettingsRepository = FakeReaderSettingsRepository()
        val viewModel = createViewModel(
            documentRepository = FakeDocumentRepository(documentId),
            readerSettingsRepository = readerSettingsRepository,
        )

        viewModel.updateAutoScrollSpeed(0f)
        advanceUntilIdle()

        assertEquals(0.01f, viewModel.uiState.value.autoScrollConfig.speed)
        assertEquals(0.01f, readerSettingsRepository.lastAutoScrollConfig?.speed)
    }

    /**
     * `ReaderViewModel.publishFirstFrame`은 저장된 [ReaderSettings.autoScrollConfig]가 활성화되어
     * 있더라도, 방금 연 세션에 대해서는 항상 auto-scroll을 비활성 상태로 발행한다 — 독자는 책을 여는
     * 순간 움직이는 페이지에 도착해서는 절대 안 된다.
     */
    @Test
    fun openDocumentDisablesAutoScrollForReaderSessionEvenWhenSavedSettingIsEnabled() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val viewModel = createViewModel(
            documentRepository = FakeDocumentRepository(documentId),
            readerSettingsRepository = FakeReaderSettingsRepository(
                ReaderSettings(autoScrollConfig = AutoScrollConfig(enabled = true)),
            ),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.autoScrollConfig.enabled)
    }

    /**
     * [AutoScrollConfig.clampSpeed]의 상한을 고정한다: 최대치를 넘는 speed로 호출한
     * [ReaderViewModel.updateAutoScrollSpeed]는 원본 입력이 아니라 clamp된 최댓값을 발행하고 저장한다.
     */
    @Test
    fun updateAutoScrollSpeedClampsToMaximum() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val readerSettingsRepository = FakeReaderSettingsRepository()
        val viewModel = createViewModel(
            documentRepository = FakeDocumentRepository(documentId),
            readerSettingsRepository = readerSettingsRepository,
        )

        viewModel.updateAutoScrollSpeed(2f)
        advanceUntilIdle()

        assertEquals(1f, viewModel.uiState.value.autoScrollConfig.speed)
        assertEquals(1f, readerSettingsRepository.lastAutoScrollConfig?.speed)
    }

    /**
     * `ReaderViewModel.updateAutoScroll`은 auto-scroll이 켜지는 순간 리더 chrome을 숨긴다 — 그렇지
     * 않으면 chrome이 화면에 남아 스스로 움직이는 페이지와 주의를 놓고 다투게 된다.
     */
    @Test
    fun enablingAutoScrollHidesReaderControls() = runTest(dispatcher) {
        val viewModel = createViewModel(
            documentRepository = FakeDocumentRepository(DocumentId("doc-1")),
        )

        viewModel.updateAutoScrollEnabled(true)

        assertTrue(viewModel.uiState.value.autoScrollConfig.enabled)
        assertFalse(viewModel.uiState.value.isControlsVisible)
    }

    /**
     * [ReaderViewModel.stopAutoScroll]은 발행된 플래그를 즉시 동기적으로 비활성화한 다음에야
     * 백그라운드에서 비활성 상태를 저장한다 — UI는 auto-scroll을 활성 상태로 보여주기를 멈추기 위해
     * 쓰기를 기다려서는 안 된다.
     */
    @Test
    fun stopAutoScrollDisablesUiImmediatelyAndPersistsDisabledState() = runTest(dispatcher) {
        val readerSettingsRepository = FakeReaderSettingsRepository()
        val viewModel = createViewModel(
            documentRepository = FakeDocumentRepository(DocumentId("doc-1")),
            readerSettingsRepository = readerSettingsRepository,
        )

        viewModel.updateAutoScrollEnabled(true)
        viewModel.stopAutoScroll()

        assertFalse(viewModel.uiState.value.autoScrollConfig.enabled)

        advanceUntilIdle()

        assertFalse(readerSettingsRepository.lastAutoScrollConfig?.enabled ?: true)
    }

    /**
     * f33313b에 대한 회귀 가드: [ReaderViewModel.openDocument]는 무조건 기본 추정 viewport
     * (`DefaultViewportSize`)를 대상으로 pagination해야 한다. 대신 실제 pane 측정을 기다렸다면,
     * 방금 import된 책 — 페이지가 없으니 pager가 슬롯을 mount하지 않고, 그러니 아무것도 pane을 절대
     * 측정하지 않는 — 은 절대 열리지 않았을 것이다.
     */
    @Test
    fun openDocumentPublishesNonEmptyPagesForAFreshlyImportedDocumentBeforeAnyViewportIsMeasured() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(300))
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pageIndex.total > 0)
        assertTrue(viewModel.uiState.value.currentPage.text.isNotEmpty())
    }

    /**
     * reload를 시작할 수 있는 트리거로 이제 [ReaderViewModel.updatePageBreaker]만 남았다(예전에는
     * 함께 트리거하던 별도의 viewport-size 콜백이 사라졌다). 그래서 실제 pane 보고 하나는 정확히
     * `getPageWindows` 호출 하나로 정착하고, 같은 보고의 반복 — 마치 pane의 effect가 composition
     * 도중 재생된 것처럼 — 은 두 번이 되는 대신 중복 제거된다.
     */
    @Test
    fun oneMeasuredViewportReportTriggersExactlyOneReload() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(300))
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        val requestsAfterOpen = documentRepository.pageWindowRequests

        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()

        assertEquals(requestsAfterOpen + 1, documentRepository.pageWindowRequests)

        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()

        assertEquals(requestsAfterOpen + 1, documentRepository.pageWindowRequests)
    }

    /**
     * Step 6 회귀 가드: 이 수정 전에는 [ReaderViewModel.openDocument]가 항상 하드코딩된 추정
     * viewport를 대상으로 pagination했는데, 이는 저장된 레이아웃의 실제 값과 거의 일치하지 않아, 첫
     * 발행이 pane이 실제로 측정한 뒤에야 고쳐지는 잘못되거나 추정된 total을 실어 날랐다. 해석된
     * 저장된 레이아웃은 대신 바로 그 첫 발행에 도달해야 한다.
     */
    @Test
    fun openDocumentPublishesTheStoredTotalOnTheFirstPublishForAPreviouslyReadBook() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val storedViewport = ViewportSize(widthPx = 300, heightPx = 600)
        val storedWindows = listOf(
            PageWindow(pageIndex = PageIndex(0, 3), location = ReaderLocation.TextOffset(0), text = "Page A", textRange = TextRange(0, 6)),
            PageWindow(pageIndex = PageIndex(1, 3), location = ReaderLocation.TextOffset(6), text = "Page B", textRange = TextRange(6, 12)),
            PageWindow(pageIndex = PageIndex(2, 3), location = ReaderLocation.TextOffset(12), text = "Page C", textRange = TextRange(12, 18)),
        )
        val documentRepository = FakeDocumentRepository(
            documentId,
            pageWindows = storedWindows,
            storedViewportSize = storedViewport,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertEquals(PageIndex(current = 0, total = 3), viewModel.uiState.value.pageIndex)
        assertEquals("Page A", viewModel.uiState.value.pageText)
    }

    /**
     * [ReaderViewModel.openDocument]가 저장된 레이아웃의 viewport를 채택하고 나면, pane의 첫 실제
     * 보고 — 같은 물리 화면이므로 같은 sp 크기 — 는 [ReaderViewModel.updatePageBreaker]의 중복
     * 제거에 의해 이미 답해진 것으로 인식되어야 하며, `getPageWindows`가 이미 캐시해 둔 답을 그저
     * 반복할 reload를 시작해서는 안 된다.
     */
    @Test
    fun matchingMeasuredViewportAfterAdoptingAStoredLayoutDoesNotRepaginate() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val storedViewport = ViewportSize(widthPx = 300, heightPx = 600)
        val storedWindows = listOf(
            PageWindow(pageIndex = PageIndex(0, 3), location = ReaderLocation.TextOffset(0), text = "Page A", textRange = TextRange(0, 6)),
            PageWindow(pageIndex = PageIndex(1, 3), location = ReaderLocation.TextOffset(6), text = "Page B", textRange = TextRange(6, 12)),
            PageWindow(pageIndex = PageIndex(2, 3), location = ReaderLocation.TextOffset(12), text = "Page C", textRange = TextRange(12, 18)),
        )
        val documentRepository = FakeDocumentRepository(
            documentId,
            pageWindows = storedWindows,
            storedViewportSize = storedViewport,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        val requestsAfterOpen = documentRepository.pageWindowRequests

        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()

        assertEquals(requestsAfterOpen, documentRepository.pageWindowRequests)
        assertEquals(PageIndex(current = 0, total = 3), viewModel.uiState.value.pageIndex)
    }

    /**
     * [PageTurnMode]도 [PageAnimation]도 `ReaderLayoutKey`(`ReaderModels.kt` 참고)의 일부가 아니다:
     * 페이지가 어떻게 넘어가거나 애니메이션되든 텍스트는 같은 지점에서 나뉘므로,
     * [ReaderViewModel.updatePageTurnMode]와 [ReaderViewModel.updatePageAnimation]은 저장소에 책을
     * 다시 레이아웃하라고 절대 요청해서는 안 된다.
     */
    @Test
    fun changingPageTurnModeOrPageAnimationProducesNoPaginationRequest() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "Some prose to paginate.")
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        val requestsAfterOpen = documentRepository.pageWindowRequests

        viewModel.updatePageTurnMode(PageTurnMode.VERTICAL)
        viewModel.updatePageAnimation(PageAnimation.CURL_PAGER)
        advanceUntilIdle()

        assertEquals(
            requestsAfterOpen,
            documentRepository.pageWindowRequests,
            "changing page-turn mode or page animation must not trigger any pagination request",
        )
    }

    /**
     * [DocumentRepository.markDocumentOpened]를 사이에 두고 [ReaderViewModel]의 두 발행이 나뉘어
     * 있음을 고정한다: 그 쓰기가 게이트로 열려 있는 상태에서도, 첫 프레임 — 스타일, total, 현재 페이지,
     * 그 텍스트 — 는 이미 올라와 있다. [ReaderViewModel]의 첫 발행이 더 이상 그것을 기다리지 않기
     * 때문이다. 게이트가 풀리고 두 번째 발행이 도착하면(여기서는 `markDocumentOpened`가 완료됨으로써
     * 관찰된다), 이미 리더에 도달한 페이지, 그 텍스트, total은 움직이지 않아야 한다.
     */
    @Test
    fun openDocumentShowsTheFirstPageBeforeMarkDocumentOpenedCompletesAndDoesNotMoveItAfterward() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val markDocumentOpenedGate = CompletableDeferred<Unit>()
        val documentRepository = FakeDocumentRepository(documentId, markDocumentOpenedGate = markDocumentOpenedGate)
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("First stored page", viewModel.uiState.value.pageText)
        val pageIndexBeforeSecondPublish = viewModel.uiState.value.pageIndex
        val pageTextBeforeSecondPublish = viewModel.uiState.value.pageText

        markDocumentOpenedGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(documentId, documentRepository.lastOpenedDocumentId)
        assertEquals(pageIndexBeforeSecondPublish, viewModel.uiState.value.pageIndex)
        assertEquals(pageTextBeforeSecondPublish, viewModel.uiState.value.pageText)
    }

    /**
     * [ReaderViewModel.openDocument]를 private 단계들로 나누어도 그 두 발행이
     * [DocumentRepository.markDocumentOpened]에 대해 상대적으로 순서가 바뀌지 않음을, 외부에서
     * 관찰 가능한 형태로 유일하게 단언한다. `advanceUntilIdle()` 이후 읽은 상태로부터 순서를 추론하는
     * 위의 게이트 기반 테스트와 달리, 이것은 `markDocumentOpened`가 호출되는 바로 그 순간 fake가
     * `uiState.value.isLoading`을 캡처하게 한다 — 그래서 그 호출을 첫 발행보다 앞으로 옮기는 단계
     * 분할이 있었다면, 이 스위트가 이미 허용하는 단순한 타이밍 변화가 아니라 캡처된 값을 true로
     * 뒤집어버렸을 것이다.
     */
    @Test
    fun openMarksTheDocumentOpenedOnlyAfterTheFirstFrameIsPublished() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        var isLoadingWhenMarkedOpened: Boolean? = null
        lateinit var viewModel: ReaderViewModel
        val documentRepository = FakeDocumentRepository(
            documentId,
            onMarkDocumentOpened = { isLoadingWhenMarkedOpened = viewModel.uiState.value.isLoading },
        )
        viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertEquals(documentId, documentRepository.lastOpenedDocumentId)
        assertEquals(false, isLoadingWhenMarkedOpened)
    }

    /**
     * `ReaderViewModel.publishRest` 자체의 재확인을 고정한다: [DocumentRepository.markDocumentOpened]가
     * 게이트로 열려 있는 동안에도, pane의 reload는 자신만의 코루틴에서 실행되어(
     * [ReaderViewModel.updatePageBreaker] 참고) 그 게이트를 기다리지 않는다 — 초기 추정이 예측할 수
     * 없었던 viewport를 측정하고, 게이트가 풀리기 전에 재-pagination한다.(이 reload가 실제로
     * `openDocument`의 `DefaultViewportSize` 추정과 다른 pagination을 만들어내지 않았다면, 이 테스트는
     * 애초에 이 경합을 시험하는 것이 아니다.) 게이트가 풀리고 두 번째 발행(아웃라인, 즐겨찾기, 저장
     * 위치 플래그)이 도착하면, 그것은 reload 자체의 결과 위에 reload 이전의 추정된 pagination을 다시
     * 얹어서는 안 된다.
     */
    @Test
    fun secondPublishDoesNotClobberARepaginationThatLandsWhileMarkDocumentOpenedIsPending() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val markDocumentOpenedGate = CompletableDeferred<Unit>()
        val documentRepository = FakeDocumentRepository(
            documentId,
            paginatedText = "a".repeat(300),
            markDocumentOpenedGate = markDocumentOpenedGate,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        viewModel.reportMeasuredViewport(width = 600, height = 900)
        advanceUntilIdle()
        val reloadedPageIndex = viewModel.uiState.value.pageIndex
        val reloadedPageText = viewModel.uiState.value.pageText
        val reloadedPageSlots = viewModel.uiState.value.pageSlots
        assertEquals(PageIndex(current = 0, total = 5), reloadedPageIndex)
        assertTrue(reloadedPageSlots.isNotEmpty())

        markDocumentOpenedGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(documentId, documentRepository.lastOpenedDocumentId)
        assertEquals(reloadedPageIndex, viewModel.uiState.value.pageIndex)
        assertEquals(reloadedPageText, viewModel.uiState.value.pageText)
        assertEquals(reloadedPageSlots, viewModel.uiState.value.pageSlots)
    }

    /**
     * progressive EPUB import의 phase 0/1이 section을 단 하나라도 커밋하고 나면, 리더는
     * `pageIndex.total == 0`을 절대 봐서는 안 된다 — total은 아직 아무것도 알려진 바 없는 문서에
     * 대해서만 0에 이르러야지, 부분적으로 import된 문서에 대해서는 그래서는 안 된다.
     * `importNextSectionsGate`는, 백그라운드 continuation의 첫 `importNextSections` 호출이 즉시
     * 해소되는 대신 대기하도록 게이트되어 있다 — 그렇지 않으면 `advanceUntilIdle()`이 첫 발행과 같은
     * 패스에서 그것을 소진해버려, 관찰할 "아직 미완료" 순간이 남지 않는다.
     */
    @Test
    fun openDocumentNeverPublishesZeroTotalPagesForAnIncompleteImport() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val importNextSectionsGate = CompletableDeferred<Unit>()
        val documentRepository = FakeDocumentRepository(
            documentId,
            format = DocumentFormat.EPUB,
            pageWindows = listOf(
                PageWindow(
                    pageIndex = PageIndex(current = 0, total = 1),
                    location = ReaderLocation.TextOffset(0),
                    text = "First imported page",
                    textRange = TextRange(0, 20),
                ),
            ),
            importComplete = false,
            importNextSectionsGate = importNextSectionsGate,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isPaginationComplete)
        assertTrue(
            viewModel.uiState.value.pageIndex.total > 0,
            "the reader must never see pageIndex.total==0 once phase 0/1 has committed a section",
        )

        importNextSectionsGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isPaginationComplete)
    }

    /**
     * `ReaderViewModel.continueImportIfIncomplete`에 대한 기본 정상성 확인: 미완료 import는 자신의
     * 백그라운드 continuation을 시작해야 하며(적어도 한 번의 `importNextSections` 호출), 그
     * continuation이 완료를 보고하면, 더 이상 측정할 것이 남지 않았을 때 리더는 완료 전용 reload를
     * 발행하고 pagination 완료를 보고해야 한다.
     */
    @Test
    fun openDocumentStartsBackgroundImportContinuationAndMarksCompleteWhenDone() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(
            documentId,
            format = DocumentFormat.EPUB,
            pageWindows = listOf(
                PageWindow(
                    pageIndex = PageIndex(current = 0, total = 1),
                    location = ReaderLocation.TextOffset(0),
                    text = "First imported page",
                    textRange = TextRange(0, 20),
                ),
            ),
            importComplete = false,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertTrue(
            documentRepository.importNextSectionsCallCount > 0,
            "an incomplete import must start its background continuation",
        )
        assertTrue(
            viewModel.uiState.value.isPaginationComplete,
            "once importNextSections reports done, the reader must be told pagination is complete",
        )
    }

    /**
     * openDocument 자체의 getPageWindows 호출은, 아직 어떤 pane도 크기를 보고하지 않은 동안에는
     * viewportSize=null을 넘긴다(자체 문서 참고: "null을 넘기면 getPageWindows가 정확히 이 스타일에
     * 대해 저장된 가장 최신 레이아웃을 해석하게 한다"). 그래서 복원된 레이아웃의 페이지 수는 바로 그
     * 첫 프레임에 대해 totalPages가 반영하는 값과 정확히 일치한다 — pageWindows.isNotEmpty()는
     * totalPages 계산에서 항상 우선하므로, 어떤 페이지 목록이든 돌아오는 순간 metadata.pageCount나
     * progress.pageIndex.total은 출처에서 제외된다. 하지만 잠시 뒤 도착하는 updatePageBreaker 자체의
     * 보고는 오직 *정확한* 크기 일치에 대해서만 중복 제거된다(그 함수 자체의 문서 참고) — 실제
     * 기기의 pane은 위에서 해석된 값과 반올림 이상으로는 다르지 않은 viewport를 보고할 수 있는데,
     * 그것만으로도 첫 결과를 조용히 대체할 두 번째, 완전한 getPageWindows 호출을 시작하기에 충분하다.
     * 그래서 리더가 결국 보게 되는 total은, 저장된 레이아웃을 복원한 호출이 아니라 우연히 마지막으로
     * 실행되는 getPageWindows 호출이 무엇이냐에 달려 있다.
     */
    @Test
    fun aLaterViewportReportRepublishesTotalPagesOverTheInitiallyRestoredCount() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(400))
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        val totalFromInitialCall = viewModel.uiState.value.pageIndex.total
        assertEquals(1, documentRepository.pageWindowRequests, "openDocument must ask for pages exactly once before any pane report")

        viewModel.reportMeasuredViewport(width = 200, height = 400)
        advanceUntilIdle()
        val totalAfterLaterReport = viewModel.uiState.value.pageIndex.total

        assertEquals(
            2,
            documentRepository.pageWindowRequests,
            "the pane's own report must have started a second getPageWindows call",
        )
        assertNotEquals(
            totalFromInitialCall,
            totalAfterLaterReport,
            "a later report must actually change the measured total for this test to prove anything",
        )
    }

    /**
     * 현재 mount window 밖의 section으로 넘어가는 페이지 전환도 스타일이 입혀진 블록을 보여줘야
     * 한다 — [ReaderViewModel.moveToPage]가 발행 전에 대상을 예열하기 때문이다. pane-report reload는
     * open의 호출 0 다음, warm 호출 1에서 얼려두어, move 자체의 나중 warm만이 페이지 7을 준비할 수
     * 있게 남겨둔다.
     */
    @Test
    fun pageTurnToNeverWarmedSectionPublishesNonEmptyBlocks() = runTest(dispatcher) {
        val documentId = DocumentId("doc-lazy-blocks")
        val sectionCount = 8
        val sections = (0 until sectionCount).map { index ->
            ReaderSection(index, text = "Section $index text", range = TextRange(index * 20L, index * 20L + 19L), title = "Chapter $index")
        }
        val pageWindows = (0 until sectionCount).map { index ->
            PageWindow(
                pageIndex = PageIndex(current = index, total = sectionCount),
                location = ReaderLocation.TextOffset(index * 20L),
                text = "Section $index text",
                textRange = TextRange(index * 20L, index * 20L + 19L),
            )
        }
        val blocksBySection = (0 until sectionCount).associateWith {
            listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 19)))
        }
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.TXT,
                title = "Lazy blocks book",
                sections = sections,
            ),
            pageWindows = pageWindows,
            lazySectionBlocks = blocksBySection,
            freezeWarmSectionBlocksAtCallIndex = 1,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 320, height = 560)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.currentPage.blocks.isNotEmpty())

        viewModel.moveToPage(7)
        advanceUntilIdle()

        assertEquals(7, viewModel.uiState.value.pageIndex.current)
        assertTrue(
            viewModel.uiState.value.currentPage.blocks.isNotEmpty(),
            "a page turn into a section the background fill hasn't reached yet must still warm it before publishing",
        )

        documentRepository.unfreezeWarmSectionBlocks()
        advanceUntilIdle()
    }

    @Test
    fun moveNextPublishesTheNewCurrentPageBeforeItsWarmCompletes() = runTest(dispatcher) {
        val documentId = DocumentId("doc-move-next-publish")
        val pageWindows = (0 until 3).map { index ->
            PageWindow(
                pageIndex = PageIndex(current = index, total = 3),
                location = ReaderLocation.TextOffset(index * 10L),
                text = "Page $index",
                textRange = TextRange(index * 10L, index * 10L + 9L),
            )
        }
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            pageWindows = pageWindows,
            freezeWarmSectionBlocksAtCallIndex = 1,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        viewModel.moveNext()

        assertEquals(1, viewModel.uiState.value.pageIndex.current)

        documentRepository.unfreezeWarmSectionBlocks()
        advanceUntilIdle()
    }

    @Test
    fun openDocumentWarmsOnlyItsMountWindow() = runTest(dispatcher) {
        val documentId = DocumentId("doc-incremental-warm")
        val sectionCount = 50
        val sections = (0 until sectionCount).map { index ->
            ReaderSection(index, text = "Section $index text", range = TextRange(index * 20L, index * 20L + 19L), title = "Chapter $index")
        }
        val pageWindows = (0 until sectionCount).map { index ->
            PageWindow(
                pageIndex = PageIndex(current = index, total = sectionCount),
                location = ReaderLocation.TextOffset(index * 20L),
                text = "Section $index text",
                textRange = TextRange(index * 20L, index * 20L + 19L),
            )
        }
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.TXT,
                title = "Big lazy book",
                sections = sections,
            ),
            pageWindows = pageWindows,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertEquals(1, documentRepository.warmSectionBlocksCalls.size)
        assertTrue(
            documentRepository.warmedSectionsSnapshot().containsAll(setOf(0, 1, 2, 3)),
            "openDocument must warm just the current mount window",
        )
        assertFalse(
            (sectionCount - 1) in documentRepository.warmedSectionsSnapshot(),
            "normal open must not start a whole-book background warm",
        )
    }

    /**
     * import/렌더링 회귀 중 정착 상태 쪽 절반: 이제 중간 [DocumentRepository.importNextSections]
     * 배치들이 활성 prefix 캐시를 살려 두더라도, 완료 경로는 여전히 최종 스냅샷을 재구성하고 그로부터
     * reload하므로, 이미 화면에 있는 페이지는 그 reload 이후에도 여전히 블록이 그대로인 채 돌아와야
     * 한다. 해결책은 사후에 다시 시작하는 fill이 아니다: reloadPages 자체가 이제 발행하려는 mount
     * window를 미리 예열한다(그 함수 자체의 문서 참고). 그래서 이 테스트는 정착 상태를 고정한다 —
     * 이미 화면에 있는 페이지는, 리더가 책을 닫았다 다시 열 때까지 스타일 없이 남는 대신, 완료 reload가
     * 실행되고 나면 그 블록을 보여줘야 한다. 발행 하나와 다음 발행 사이에 무슨 일이 일어나는지는
     * 다루지 않는다 — 그것은 [importCompletionReloadNeverPublishesThePageWithoutItsBlocks]를 참고.
     * `importNextSectionsGate`는 `openDocumentNeverPublishesZeroTotalPagesForAnIncompleteImport`가
     * 백그라운드 continuation을 대기시키는 것과 같은 방식으로 게이트되어 있어서, open 직후의 정상성
     * 확인은 `advanceUntilIdle()`이 둘 다를 우연히 정착시킨 상태가 아니라, 완료 reload 이전의 상태를
     * 엄격하게 관찰한다. 그 open 직후의 단언은 그저 `lazySectionBlocks`가 올바르게 배선되어 있다는
     * 정상성 확인일 뿐이다 — 그 시점에 있는 유일한 페이지에는 `openDocument` 자체의 mount-window
     * warm이 이미 도달해 있다 — 이 테스트가 겨냥하는 회귀가 아니다.
     */
    @Test
    fun importCompletionReloadKeepsBlocksForPageAlreadyOnScreen() = runTest(dispatcher) {
        val documentId = DocumentId("doc-import-invalidate")
        val initialSections = listOf(
            ReaderSection(0, text = "Chapter 0 text", range = TextRange(0L, 19L), title = "Chapter 0"),
        )
        val appendedSections = listOf(
            ReaderSection(1, text = "Chapter 1 text", range = TextRange(20L, 39L), title = "Chapter 1"),
        )
        val pageWindows = listOf(
            PageWindow(
                pageIndex = PageIndex(current = 0, total = 1),
                location = ReaderLocation.TextOffset(0),
                text = "Chapter 0 text",
                textRange = TextRange(0L, 19L),
            ),
        )
        val blocksBySection = mapOf(
            0 to listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 19))),
            1 to listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 19))),
        )
        val importNextSectionsGate = CompletableDeferred<Unit>()
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.TXT,
                title = "Growing book",
                sections = initialSections,
            ),
            pageWindows = pageWindows,
            lazySectionBlocks = blocksBySection,
            importComplete = false,
            importNextSectionsGate = importNextSectionsGate,
            sectionsAppendedOnImport = appendedSections,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.pageSlots.first { it.page == 0 }.blocks.isNotEmpty(),
            "openDocument's own mount-window warm must have already styled the page it published",
        )

        importNextSectionsGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.pageSlots.first { it.page == 0 }.blocks.isNotEmpty(),
            "the final import reload must warm its mount window so the page already on screen " +
                "recovers its blocks instead of sitting " +
                "unstyled until the reader closes and reopens the book",
        )
    }

    /**
     * 이 수정 전체가 대상으로 삼는 결함은 정착 상태 실패가 아니라 일시적인 것이다: import 완료
     * reload는 필요한 section 블록이 아직 디코딩되지 않은 신선한 최종 스냅샷으로부터 재구성될 수
     * 있으며, warm-before-publish 가드가 없다면 그 reload는 나중 발행이 이를 바로잡기 전에 이미
     * 화면에 있는 페이지를 빈 블록 목록으로 내보냈을 것이다. 위의 정착 상태 단언은 그것을 볼 수 없다
     * — advanceUntilIdle()이 모든 것을 소진한 뒤의 uiState.value만 읽기 때문이다. 이 테스트는
     * UnconfinedTestDispatcher 수집기로(uiState는 합쳐지는(conflating) StateFlow이므로, eager한
     * 수집기만이 각각을 관찰한다) 모든 중간 emission을 모아, 그중 어느 것도 현재 화면에 있는 페이지를
     * 빈 블록 목록으로 보여준 적이 없음을 단언한다.
     */
    @Test
    fun importCompletionReloadNeverPublishesThePageWithoutItsBlocks() = runTest(dispatcher) {
        val documentId = DocumentId("doc-import-never-blockless")
        val initialSections = listOf(
            ReaderSection(0, text = "Chapter 0 text", range = TextRange(0L, 19L), title = "Chapter 0"),
        )
        val appendedSections = listOf(
            ReaderSection(1, text = "Chapter 1 text", range = TextRange(20L, 39L), title = "Chapter 1"),
        )
        val pageWindows = listOf(
            PageWindow(
                pageIndex = PageIndex(current = 0, total = 1),
                location = ReaderLocation.TextOffset(0),
                text = "Chapter 0 text",
                textRange = TextRange(0L, 19L),
            ),
        )
        val blocksBySection = mapOf(
            0 to listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 19))),
            1 to listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 19))),
        )
        val importNextSectionsGate = CompletableDeferred<Unit>()
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.TXT,
                title = "Growing book",
                sections = initialSections,
            ),
            pageWindows = pageWindows,
            lazySectionBlocks = blocksBySection,
            importComplete = false,
            importNextSectionsGate = importNextSectionsGate,
            sectionsAppendedOnImport = appendedSections,
        )
        val viewModel = createViewModel(documentRepository)

        val snapshots = mutableListOf<ReaderUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { snapshots += it }
        }

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 320, height = 560)
        advanceUntilIdle()
        importNextSectionsGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            snapshots.none { state ->
                !state.isLoading && state.pageSlots.any { slot -> slot.page == state.pageIndex.current && slot.blocks.isEmpty() }
            },
            "no publish may ever show the page on screen without its blocks: " +
                "${snapshots.map { it.pageSlots.map { s -> s.page to s.blocks.size } }}",
        )
    }

    /**
     * progressive import 도중 알려지게 된 section에 속하는 페이지는, [ReaderViewModel.reloadPages]가
     * 커진 section 목록을 다시 읽고 난 뒤, open 시점의 prefix로부터 물려받은 제목이 아니라 그
     * section 자체의 제목을 보여줘야 한다.
     */
    @Test
    fun chapterTitleForASectionDiscoveredDuringImportIsNotTheOpenTimeSectionsTitle() = runTest(dispatcher) {
        val documentId = DocumentId("doc-import-title")
        val initialSections = listOf(
            ReaderSection(0, text = "Chapter 0 text", range = TextRange(0L, 19L), title = "Chapter 0"),
        )
        val appendedSections = listOf(
            ReaderSection(1, text = "Chapter 1 text", range = TextRange(20L, 39L), title = "Chapter 1"),
        )
        val pageWindows = listOf(
            PageWindow(
                pageIndex = PageIndex(current = 0, total = 2),
                location = ReaderLocation.TextOffset(0),
                text = "Chapter 0 text",
                textRange = TextRange(0L, 19L),
            ),
            PageWindow(
                pageIndex = PageIndex(current = 1, total = 2),
                location = ReaderLocation.TextOffset(20),
                text = "Chapter 1 text",
                textRange = TextRange(20L, 39L),
            ),
        )
        val importNextSectionsGate = CompletableDeferred<Unit>()
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.TXT,
                title = "Growing book",
                sections = initialSections,
            ),
            pageWindows = pageWindows,
            importComplete = false,
            importNextSectionsGate = importNextSectionsGate,
            sectionsAppendedOnImport = appendedSections,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        importNextSectionsGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "Chapter 1",
            viewModel.uiState.value.pageSlots.first { it.page == 1 }.chapterTitle,
            "a page whose section only became known during the import must show that section's own " +
                "title, not the open-time section list's title for whatever range it happened to overlap",
        )
    }

    /**
     * F3을 고정한다: DocumentRepositoryImpl.invalidateDocumentCache는 import 배치 자체의
     * continuation이 한창 걷고 있던 pagination 세션을 null로 만들어, continuePagination이
     * isComplete=true와 sectionsMeasured=0으로 답하게 한다 — 이는 "책이 끝났다"가 아니라 "이 걷기가
     * 더 할 말이 없다"는 신호다. continuePaginationIfIncomplete는 import 자체가 여전히 실행 중인
     * 동안 그것만으로 isPaginationComplete=true를 발행해서는 안 된다.
     */
    @Test
    fun paginationContinuationNeverPublishesCompleteFromASpuriousSignalWhileTheImportIsRunning() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(
            documentId,
            paginatedText = "a".repeat(300),
            importComplete = false,
            paginationSessionAlwaysInvalidated = true,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()

        assertFalse(
            viewModel.uiState.value.isPaginationComplete,
            "a pagination continuation that reports isComplete with nothing measured must not be " +
                "trusted while the import is still running",
        )
    }

    @Test
    fun incompleteOpenShowsZeroPercentInsteadOfAnUnavailableProgressPlaceholder() = runTest(dispatcher) {
        val documentId = DocumentId("doc-progress-incomplete")
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            format = DocumentFormat.EPUB,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.EPUB,
                title = "Growing book",
                sections = listOf(
                    ReaderSection(0, text = "hello", range = TextRange(0L, 5L), title = "One"),
                ),
            ),
            pageWindows = listOf(
                PageWindow(
                    pageIndex = PageIndex(current = 0, total = 1),
                    location = ReaderLocation.TextOffset(0),
                    text = "hello",
                    textRange = TextRange(0L, 5L),
                ),
            ),
            importComplete = false,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.readProgressPercent)
    }

    @Test
    fun reloadPagesKeepsTextProgressStableWhileThePageTotalChanges() = runTest(dispatcher) {
        val documentId = DocumentId("doc-progress-stable")
        val readerRepository = FakeReaderRepository().apply {
            progress = ReadingProgress(
                documentId = documentId,
                location = ReaderLocation.TextOffset(5),
                pageIndex = PageIndex(current = 0, total = 0),
                updatedAtEpochMillis = 0L,
            )
        }
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            paginatedText = "a".repeat(13),
            characterCount = 13L,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.TXT,
                title = "Resizable book",
                sections = listOf(
                    ReaderSection(0, text = "a".repeat(13), range = TextRange(0L, 13L), title = "One"),
                ),
            ),
        )
        val viewModel = createViewModel(documentRepository = documentRepository, readerRepository = readerRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        viewModel.reportMeasuredViewport(width = 50, height = 400)
        advanceUntilIdle()
        val progressAtThreePages = viewModel.uiState.value.readProgressPercent
        assertEquals(3, viewModel.uiState.value.pageIndex.total)

        viewModel.reportMeasuredViewport(width = 30, height = 400)
        advanceUntilIdle()
        assertEquals(5, viewModel.uiState.value.pageIndex.total)
        assertEquals(progressAtThreePages, viewModel.uiState.value.readProgressPercent)

        viewModel.reportMeasuredViewport(width = 40, height = 400)
        advanceUntilIdle()
        assertEquals(4, viewModel.uiState.value.pageIndex.total)
        assertEquals(progressAtThreePages, viewModel.uiState.value.readProgressPercent)
    }

    @Test
    fun importBatchesDoNotReloadUntilCompletionWithoutAPendingNextRequest() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val initialSection = ReaderSection(0, text = "S0", range = TextRange(0L, 2L), title = "S0")
        val batch1 = (1..10).map { index ->
            ReaderSection(index, text = "S$index", range = TextRange(index * 10L, index * 10L + 2L), title = "S$index")
        }
        val batch2 = (11..20).map { index ->
            ReaderSection(index, text = "S$index", range = TextRange(index * 10L, index * 10L + 2L), title = "S$index")
        }
        val gate0 = CompletableDeferred<Unit>()
        val gate1 = CompletableDeferred<Unit>()
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            format = DocumentFormat.EPUB,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.EPUB,
                title = "Progressive book",
                sections = listOf(initialSection),
            ),
            progressiveImportBatches = listOf(batch1, batch2),
            importBatchGates = listOf(gate0, gate1),
            pageWindowsFollowLiveSections = true,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        assertEquals(1, documentRepository.pageWindowRequests)

        gate0.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, documentRepository.pageWindowRequests)
        assertEquals(1, documentRepository.getDocumentCallCount)
        assertEquals(1, viewModel.uiState.value.pageIndex.total)
        assertFalse(viewModel.uiState.value.isPaginationComplete, "the import is not done yet, batch 2 is still pending")

        gate1.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, documentRepository.pageWindowRequests)
        assertEquals(2, documentRepository.getDocumentCallCount)
        assertEquals(21, viewModel.uiState.value.pageIndex.total)
        assertTrue(viewModel.uiState.value.isPaginationComplete)
    }

    @Test
    fun importWithAPendingNextRequestWaitsForTheCompletionReloadAndNeverOverlapsPaginationContinuation() = runTest(dispatcher) {
        val documentId = DocumentId("doc-overlap")
        val importGate = CompletableDeferred<Unit>()
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            format = DocumentFormat.EPUB,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.EPUB,
                title = "Overlap book",
                sections = listOf(
                    ReaderSection(0, text = "S0", range = TextRange(0L, 2L), title = "S0"),
                ),
            ),
            importComplete = false,
            importNextSectionsGate = importGate,
            sectionsAppendedOnImport = listOf(
                ReaderSection(1, text = "S1", range = TextRange(10L, 12L), title = "S1"),
            ),
            pageWindowsFollowLiveSections = true,
            progressivePagination = true,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.moveNext()
        viewModel.reportMeasuredViewport(width = 320, height = 560)
        advanceUntilIdle()

        assertEquals(2, documentRepository.pageWindowRequests)

        importGate.complete(Unit)
        advanceUntilIdle()

        assertFalse(documentRepository.importPaginationOverlapDetected)
        assertEquals(1, documentRepository.continuePaginationCallCount)
        assertEquals(4, documentRepository.pageWindowRequests)
        assertEquals(1, viewModel.uiState.value.pageIndex.current)
    }

    @Test
    fun paginationContinuationReloadsOnlyWhenItFinishesWithoutAPendingMove() = runTest(dispatcher) {
        val documentId = DocumentId("doc-pagination-finish")
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.TXT,
                title = "Pagination book",
                sections = listOf(
                    ReaderSection(0, text = "A", range = TextRange(0L, 1L), title = "0"),
                    ReaderSection(1, text = "B", range = TextRange(1L, 2L), title = "1"),
                    ReaderSection(2, text = "C", range = TextRange(2L, 3L), title = "2"),
                ),
            ),
            progressivePagination = true,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 320, height = 560)
        advanceUntilIdle()

        assertEquals(2, documentRepository.continuePaginationCallCount)
        assertEquals(3, documentRepository.pageWindowRequests)
        assertEquals(3, viewModel.uiState.value.pageIndex.total)
    }

    @Test
    fun pendingMoveNextKeepsTheTargetEmbeddedImagePreloadAsTheFinalWinner() = runTest(dispatcher) {
        val documentId = DocumentId("doc-embedded-pending")
        val initialSection = ReaderSection(0, text = "S0", range = TextRange(0L, 2L), title = "S0")
        val importedSections = (1..5).map { index ->
            ReaderSection(index, text = "S$index", range = TextRange(index.toLong(), index.toLong() + 1), title = "S$index")
        }
        val pageWindows = (0..5).map { index ->
            PageWindow(
                pageIndex = PageIndex(current = index, total = 6),
                location = ReaderLocation.TextOffset(index.toLong()),
                text = "Page $index",
                textRange = TextRange(index.toLong(), index.toLong() + 1),
                blocks = listOf(
                    ReaderBlock(
                        kind = ReaderBlockKind.IMAGE,
                        range = TextRange(index.toLong(), index.toLong() + 1),
                        imageHref = "img-$index",
                    ),
                ),
            )
        }
        val embeddedImages = (0..5).associate { index -> "img-$index" to byteArrayOf(index.toByte()) }
        val importGate = CompletableDeferred<Unit>()
        val embeddedImageGate = CompletableDeferred<Unit>()
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            format = DocumentFormat.EPUB,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.EPUB,
                title = "Embedded pending book",
                sections = listOf(initialSection),
            ),
            pageWindows = pageWindows,
            importComplete = false,
            sectionsAppendedOnImport = importedSections,
            importNextSectionsGate = importGate,
            embeddedImages = embeddedImages,
            freezeEmbeddedImagesAtCallIndex = 1,
            embeddedImageGate = embeddedImageGate,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        viewModel.moveNext(step = 4)
        advanceUntilIdle()

        importGate.complete(Unit)
        advanceUntilIdle()
        embeddedImageGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(4, viewModel.uiState.value.pageIndex.current)
        assertTrue(
            "img-4" in viewModel.uiState.value.currentPage.embeddedImages,
            "the target page's embedded-image preload must remain the final winner after pending navigation",
        )
    }

    /**
     * [PaginatedDocument.chapterTitleAt]의 KDoc이 설명하는 제목 없는 section의 상속을 고정한다: 자체
     * 제목이 없는 section은 챕터 제목 목적으로는 "제목 없음"이 아니다 — 그것이거나 그 이전의 마지막
     * 제목 있는 section의 제목을 상속받는다. `sectionContaining(start)?.title`을 단순하게 붕괴시켜
     * — 가장 늦게 시작하는 것을 고르기 전에 "제목 있는 section만" 필터를 빼버리면 — 페이지 자체의
     * section에 제목이 없는 순간 null로 답하게 될 것이며, 이 테스트는 그것을 잡아내지만
     * [epubChapterTitlePersistsAcrossEveryPageOfTheSameChapter]는 그 테스트의 모든 section이 이미
     * 제목을 가지고 있으므로 잡아내지 못한다.
     */
    @Test
    fun chapterTitleForAnUntitledSectionInheritsTheLastTitledSection() = runTest(dispatcher) {
        val documentId = DocumentId("doc-untitled-section")
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.TXT,
                title = "Untitled section book",
                sections = listOf(
                    ReaderSection(0, text = "Chapter text", range = TextRange(0L, 10L), title = "Chapter 1"),
                    ReaderSection(1, text = "Untitled text", range = TextRange(10L, 20L), title = null),
                ),
            ),
            pageWindows = listOf(
                PageWindow(pageIndex = PageIndex(0, 2), location = ReaderLocation.TextOffset(0), text = "Page 0", textRange = TextRange(0L, 10L)),
                PageWindow(pageIndex = PageIndex(1, 2), location = ReaderLocation.TextOffset(10), text = "Page 1", textRange = TextRange(10L, 20L)),
            ),
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.moveToPage(1)
        advanceUntilIdle()

        assertEquals(
            "Chapter 1",
            viewModel.uiState.value.currentPage.chapterTitle,
            "a page inside an untitled section must inherit the last titled section's title, not null",
        )
    }

    /**
     * 불변 조건 24를 고정한다: reload의 백그라운드 section-warm 요청은 반드시 그 reload 자체의
     * 페이지/section 쌍에서 유도되어야 하며, 타이밍이 다르게 동시에 실행 중인 다른 reload가 그 사이
     * 공유된 `paginated` 필드에 써넣은 무언가에서 유도되어서는 절대 안 된다(`ReaderViewModel.warmMountWindow`
     * 자체의 문서 참고 — "warm은 자신의 발행이 읽어올 그 목록을 건드려야 한다"). import 배치의
     * reload가 그 아래에서 끝까지 실행되어 section 목록을 키우는 동안,
     * [FakeDocumentRepository.freezeWarmSectionBlocksAtCallIndex]로 viewport가 촉발한 reload
     * 자체의 warm 호출을 얼려두고, [FakeDocumentRepository.unfreezeWarmSectionBlocks]로 그것을
     * 풀어준 뒤 — freeze 이전에 캡처된 — 그 얼려진 호출 자체의 기록된 인자를 읽어 구성한다.
     *
     * 아래의 section 분할은 두 reload가 실제로 서로 다른 답을 내도록 골랐다: import 배치 이전에는
     * section 0만 존재하므로 mount window의 모든 페이지가 그것으로 해석된다. 배치 이후에는 페이지
     * 1이 시작하는 바로 그 지점에서 시작하는 두 번째 section이 그 페이지를 대신 차지하여, 같은
     * mount window가 section {0, 1}로 해석된다. viewport reload 자체의 warm이 자기 자신의 로컬
     * 쌍 대신 살아 있는 필드를 읽었다면, 그 얼려진 호출은 그 경로에서 우연히 현재였던 쌍에 대해
     * 기록되었을 것이다 — 이 테스트의 기대는 오직 그것이 자기 자신의, import 이전 쌍을 봤을 때만
     * 성립한다. `freezeWarmSectionBlocksAtCallIndex = 1`은 정확히 viewport reload 자체의 warm을
     * 겨냥한다: 호출 0은 `openDocument` 자체의 발행 전 warm이므로, 호출 1이 아래의 viewport가 촉발한
     * reload 자체의 warm이다.
     */
    @Test
    fun reloadWarmsSectionsDerivedFromItsOwnPageList() = runTest(dispatcher) {
        val documentId = DocumentId("doc-warm-overlap")
        val initialSections = listOf(
            ReaderSection(0, text = "Chapter 0 text", range = TextRange(0L, 10L), title = "Chapter 0"),
        )
        val appendedSections = listOf(
            ReaderSection(1, text = "Chapter 1 text", range = TextRange(5L, 10L), title = "Chapter 1"),
        )
        val pageWindows = listOf(
            PageWindow(pageIndex = PageIndex(0, 2), location = ReaderLocation.TextOffset(0), text = "Page 0", textRange = TextRange(0L, 5L)),
            PageWindow(pageIndex = PageIndex(1, 2), location = ReaderLocation.TextOffset(5), text = "Page 1", textRange = TextRange(5L, 10L)),
        )
        val importNextSectionsGate = CompletableDeferred<Unit>()
        val documentRepository = FakeDocumentRepository(
            documentId = documentId,
            readerDocument = ReaderDocument(
                id = documentId,
                format = DocumentFormat.TXT,
                title = "Overlapping reload book",
                sections = initialSections,
            ),
            pageWindows = pageWindows,
            importComplete = false,
            importNextSectionsGate = importNextSectionsGate,
            sectionsAppendedOnImport = appendedSections,
            freezeWarmSectionBlocksAtCallIndex = 1,
        )
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        viewModel.reportMeasuredViewport(width = 320, height = 560)
        advanceUntilIdle()

        importNextSectionsGate.complete(Unit)
        advanceUntilIdle()

        val frozenCallSections = documentRepository.warmSectionBlocksCalls[1]

        documentRepository.unfreezeWarmSectionBlocks()
        advanceUntilIdle()

        assertEquals(
            setOf(0),
            frozenCallSections,
            "the viewport reload's own warm must request only the section its own page/section pair " +
                "agreed on when it ran, not the section the concurrently-completing import batch added " +
                "underneath it: ${documentRepository.warmSectionBlocksCalls}",
        )
    }

    /**
     * 즐겨찾기 토글은 낙관적으로 발행하므로, 쓰기가 실패하면 플래그를 되돌려 놓아야 한다.
     *
     * rollback이 없다면 행이 사라진 문서에 대해 별이 계속 켜진 채로 남고, 다음에 열면 다시 꺼진
     * 상태로 보인다 — 독자는 앱이 자기 자신과 모순되는 것을 보게 된다.
     */
    @Test
    fun togglingFavoriteRollsBackWhenTheDocumentRowIsGone() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId)
        val viewModel = createViewModel(documentRepository)
        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        val wasFavorite = viewModel.uiState.value.isFavorite

        documentRepository.documentRowMissing = true
        viewModel.toggleFavorite()
        advanceUntilIdle()

        assertEquals(
            wasFavorite,
            viewModel.uiState.value.isFavorite,
            "a failed favourite write must restore the flag the reader saw before the tap",
        )
    }

    /**
     * 저장 위치의 id는 문서 id와 위치의 저장 문자열을, 콜론으로 이어붙인 것이다.
     *
     * 이 형식 덕분에 같은 페이지를 두 번 저장해도 행이 추가되는 대신 하나를 대체한다. 이는 정확히
     * 한 곳에서만 만들어지며, 여기서 이를 고정해 두면 이후의 리팩터가 생성된 id로 바꿔치기해서
     * 토글을 조용히 append로 바꿔버리는 것을 막을 수 있다.
     */
    @Test
    fun savedPlaceIdIsDocumentIdAndLocationStorageString() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId)
        val bookmarkRepository = FakeBookmarkRepository()
        val viewModel = createViewModel(documentRepository, bookmarkRepository)
        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        viewModel.toggleSavedPlace()
        advanceUntilIdle()

        val saved = bookmarkRepository.bookmarks.value.single()
        assertEquals("${documentId.value}:${saved.location.asStorageString()}", saved.id)
    }

    /**
     * [documentRepository]와 연결되고, 테스트가 검사할 필요가 없는 한 나머지 세 협력자에는 그저
     * 버려도 되는 기본값을 쓰는 [ReaderViewModel]을 조립한다 — 그래서 document repository만
     * 신경 쓰는 테스트는 나머지 세 생성자 인자를 스스로 반복하지 않아도 된다.
     *
     * @param documentRepository 이 뷰 모델이 문서를 읽고 쓰는 fake.
     * @param bookmarkRepository 저장 위치를 담는 fake 저장소. 기본값은 새로 만든 빈 것.
     * @param readerSettingsRepository 설정을 담는 fake 저장소. 기본값은 기본 [ReaderSettings]를
     *   담은 것.
     * @return 테스트가 `openDocument`를 호출할 준비가 된 [ReaderViewModel].
     */
    private fun createViewModel(
        documentRepository: FakeDocumentRepository,
        bookmarkRepository: FakeBookmarkRepository = FakeBookmarkRepository(),
        readerSettingsRepository: FakeReaderSettingsRepository = FakeReaderSettingsRepository(),
        readerRepository: FakeReaderRepository = FakeReaderRepository(),
    ): ReaderViewModel {
        return ReaderViewModel(
            documentRepository = documentRepository,
            bookmarkRepository = bookmarkRepository,
            readerSettingsRepository = readerSettingsRepository,
            readerRepository = readerRepository,
            openReaderDocumentUseCase = OpenReaderDocumentUseCase(documentRepository, readerRepository, readerSettingsRepository),
        )
    }
}

/**
 * `updatePageBreaker`가 측정의 유일한 진입점이 된 지금, pane이 실제 크기를 보고하는 것을 대신한다.
 * [FakeDocumentRepository.getPageWindows]는 breaker 자체를 무시한다 — 오직 [ViewportSize]만이 그
 * 자신의 pagination을 이끈다 — 그래서 이 breaker는 실제로 아무것도 측정할 필요가 없다. 그저
 * [ReaderViewModel.updatePageBreaker]의 시그니처를 만족시키기 위해서만 존재한다.
 */
private val FakePageBreaker = ReaderPageBreaker { _, _ -> IntArray(0) }

/**
 * 리더 pane이 측정된 크기 [width] by [height]를 보고하는 것을 흉내 낸다. 실제 composition으로부터
 * [ReaderViewModel.updatePageBreaker]가 받게 될 것과 같은 호출이다. sp와 px 인자 둘 다 같은
 * [ViewportSize]를 받는다. [FakeDocumentRepository]는 오직 크기만 읽을 뿐, 그것이 두 단위 중 어느
 * 것으로 도착했는지는 읽지 않기 때문이다.
 */
private fun ReaderViewModel.reportMeasuredViewport(width: Int, height: Int) {
    val size = ViewportSize(widthPx = width, heightPx = height)
    updatePageBreaker(uiState.value.style, size, size, FakePageBreaker)
}

/**
 * 이 스위트 전체에서 쓰이는 [DocumentRepository]의 테스트 더블이다. 뻔한 정상 경로 필드들 외에도,
 * [ReaderViewModel]이 견뎌내야 하는 실제 저장소 동작의 구체적인 한 구석을 모델링하기 위한 매개변수가
 * 여럿 있다: progressive EPUB import([importComplete], [importNextSectionsGate],
 * [sectionsAppendedOnImport], [progressiveImportBatches], [importBatchGates]), 그 import와 분리된
 * progressive pagination 패스([progressivePagination]), 한창 걷던 도중 무효화된 pagination 세션
 * ([paginationSessionAlwaysInvalidated]), 채택되는 저장된 레이아웃의 viewport([storedViewportSize]),
 * on-demand 블록 디코딩([lazySectionBlocks]), fill 도중 얼려진 백그라운드 warm 호출
 * ([freezeWarmSectionBlocksAtCallIndex]), 그리고 이 문서 자체의 [getPageWindows] 호출이 여전히
 * 처리 중인 동안 열리는 두 번째 문서([freezeGetPageWindowsAtCallIndex], [secondDocumentId],
 * [secondPageWindows]).
 *
 * @property documentId 이 fake가 응답하는 문서. `documentId` 인자를 받는 모든 메서드는 이것과 비교해
 *   확인하며, [getPageWindows]에 [secondDocumentId]가 추가하는 두 항목짜리 조회를 제외하고는 다르면
 *   알 수 없는 문서인 것처럼 응답한다.
 * @property format 아래의 모든 저장된 사실이 그 기준으로 서술되는 문서 형식.
 * @param pageCount 문서 자체에 저장된 페이지 수로, fake의 [metadata] `pageCount` 필드를 초기화하는
 *   데만 쓰인다 — [getPageWindows]가 실제로 pagination하는 페이지 수는
 *   [pageWindows]/[paginatedText]/[progressivePagination]에 의해 별도로 제어된다.
 * @property paginatedText [pageWindows]도 [progressivePagination]도 먼저 응답하지 않을 때,
 *   요청된 viewport에 맞춰 고정 크기 window로 [getPageWindows]가 pagination하는 원본 텍스트.
 * @property visualPageImages CBZ 문서에 대해 [getVisualPageImages]가 응답하는 디코딩된 페이지
 *   이미지.
 * @property embeddedImages EPUB 문서에 대해 [getEmbeddedImages]가 응답하는 디코딩된 내장 이미지.
 * @property throwOnGetEmbeddedImagesCall 설정되면, 그 번호의 [getEmbeddedImages] 호출이 응답하기 전에
 *   throw하게 만들어, 테스트가 일시적인 preload 실패와 그 이후 재시도를 모델링할 수 있게 한다.
 * @property readerDocument [getReaderDocument]가 응답하는 파싱된 문서. null이면 대신
 *   [format]/fake 자체의 metadata로부터 최소한의 placeholder를 만든다.
 * @property pageWindows 설정되면 [getPageWindows]가 직접(선택적으로 [lazySectionBlocks]를 거쳐
 *   필터링되어) 응답하는 고정 페이지 목록.
 * @property freezeGetPageWindowsAtCallIndex 설정되면, [unfreezeGetPageWindows]가 재개할 때까지
 *   — [documentId]와 [secondDocumentId] 모두를 통틀어 세는 — 이 인덱스(0부터 시작)의
 *   [getPageWindows] 호출을 순수한 [suspendCoroutine]에 대기시킨다. [CompletableDeferred] 게이트로는
 *   안 된다: `await()`는 취소 가능한 중단 지점이고, `ReaderViewModel.openDocument`는 새 문서가 열리는
 *   순간 이전 문서의 job을 동기적으로 취소하는데, 이는 대기 중인 호출을 해소되도록 두는 대신
 *   [kotlinx.coroutines.CancellationException]으로 재개해버릴 것이다 — `Job.cancel()`이 실제로
 *   이미 진행 중인 데이터베이스 읽기를 멈출 수 없기 때문에([ReaderViewModel] 자체의 클래스 문서 참고)
 *   이것이 바로 모델링하려는 상황이다.
 * @property secondDocumentId [getPageWindows]가 [secondPageWindows]로 응답하는, [documentId]와
 *   구분되는 두 번째 문서 id — 테스트가 한 fake 인스턴스로 문서 두 개를 동시에 구동할 수 있게 해준다.
 *   예를 들어 첫 번째 문서 자체의 [getPageWindows] 호출이 여전히 [freezeGetPageWindowsAtCallIndex]에
 *   대기 중인 동안 두 번째 문서를 여는 경우다. 다른 모든 메서드는 [secondDocumentId]에 대해 항상 그랬던
 *   것과 같은 "알 수 없는 문서" 방식으로 계속 응답한다 — [getDocument]/[getReaderDocument]는 null,
 *   [isImportComplete]/[isPaginationComplete]는 true — 이는 여기서는 무해하다: 이 스위트의 어떤
 *   테스트도 [secondDocumentId]의 제목, metadata, import 상태를 읽지 않고 오직 그 자신의 pagination만
 *   읽는다.
 * @property secondPageWindows [secondDocumentId]에 대해 [getPageWindows]가 응답하는 페이지 목록 —
 *   일반적인 알 수 없는 문서가 받는 빈 목록과 달리, 실제로 구별 가능한 콘텐츠다. [secondDocumentId]의
 *   존재 의의 전체가 그 자체로 관찰 가능하게 pagination되는 것이기 때문이다.
 * @property markDocumentOpenedGate [markDocumentOpened]를 완료될 때까지 중단시켜, 테스트가 첫 발행과
 *   두 번째 발행 사이의 상태를 엄격하게 관찰할 수 있게 한다(예:
 *   `openDocumentShowsTheFirstPageBeforeMarkDocumentOpenedCompletesAndDoesNotMoveItAfterward` 참고).
 * @property onMarkDocumentOpened [markDocumentOpened]가 호출되는 순간, 자신의 게이트를 기다리거나
 *   무언가를 쓰기 전에 호출된다 — 테스트가 대기 중인 게이트와 `advanceUntilIdle()`로 순서를
 *   추론하는 대신, 바로 그 순간의 살아 있는 ViewModel 상태(예: `uiState.value.isLoading`)를 읽을 수
 *   있게 해준다.
 * @property storedViewportSize 이전에 읽은 책의 저장된 레이아웃이 측정되었던 viewport — pane 크기가
 *   아직 보고되지 않은 채로 `openDocument`가 물어볼 때 채택하도록 [resolveViewportSizeForStyle]이
 *   해석하는 값이다. 그 저장된 레이아웃 자체의 페이지를 대신하는 [pageWindows]와 함께일 때만 의미가
 *   있다.
 * @property importComplete false는 백그라운드 import가 아직 끝나지 않은, progressively-import된
 *   EPUB을 대신한다 — `continueImportIfIncomplete` 참고. [importNextSectionsGate]가 열어두지 않는
 *   한, 아래의 [importNextSections] 호출은 매번 즉시 완료를 보고하므로, 이것만 false로 설정하는
 *   테스트는 continuation 루프를 정확히 한 바퀴만 시험하는 것이다.
 * @property importNextSectionsGate [importNextSections]를 완료될 때까지 중단시킨다 — 위의
 *   [markDocumentOpenedGate]와 같은 발상이다 — `advanceUntilIdle()`이 둘 다를 한 패스에 소진하는
 *   대신, 테스트가 첫 발행과 백그라운드 continuation의 첫 호출 도착 사이의 상태를 관찰할 수 있게
 *   한다.
 * @property lazySectionBlocks 설정되면 section을 인식하는 페이지 목록을 활성화한다: 페이지의 블록은
 *   그것을 소유한 section이 [warmSectionBlocks]에 요청될 때까지(`ReaderViewModel.sectionContaining`과
 *   같은 방식으로 찾는다) 빈 채로 읽히다가, 그 section에 대한 이 맵의 응답으로 바뀐다 — 테스트가
 *   [pageWindows]에 블록을 직접 구워 넣는 대신, 뷰 모델이 페이지를 만들기 전에 그것을 예열함을
 *   증명하는 데 필요한, 실제 저장소가 주는 on-demand 디코딩 `SectionBlocksCache`를 대신한다.
 * @property freezeWarmSectionBlocksAtCallIndex 설정되면, [unfreezeWarmSectionBlocks]까지 이
 *   인덱스(0부터 시작)의 [warmSectionBlocks] 호출을 얼려두어, 결정론적인 warm/reload 경합을
 *   가능하게 한다.
 * @property sectionsAppendedOnImport fake의 첫 실제 [importNextSections] 호출이
 *   [getReaderDocument]의 응답에 덧붙이는 section들 — 이미 살아 있는 캐시는 계속 쓸 수 있는 채로
 *   progressive import가 나중 챕터를 커밋하는 것을 대신하며, 나중의 전체 reload만이 리더가 발행하는
 *   스냅샷을 재구성한다.
 * @property progressiveImportBatches 위에서 [sectionsAppendedOnImport]가 모델링하는 단일한
 *   한 번에-전부 배치 대신, 다중 배치 progressive EPUB import를 모델링한다: 각 [importNextSections]
 *   호출은 항목 하나를 소비해 fake의 살아 있는 section 목록에 덧붙이며, [isImportComplete]는 모든
 *   항목이 사라져야만 true가 된다 — 실제 마지막 배치에서만 non-null이 되는
 *   `documents.importCompletedAtEpochMillis`를 그대로 반영한다.
 * @property importBatchGates [progressiveImportBatches] 항목마다 하나씩, 그 배치가 소비되기 전에
 *   기다리는 선택적 게이트다. 이 fake는 배치 사이에 자체적으로 실제 중단이 없으므로, 여기 게이트가
 *   없으면 import 루프는 하나의 dispatcher 패스에서 모든 배치를 곧장 통과해버려, 동시에 시작된
 *   pagination continuation이 전체 import가 이미 끝나기 전에 실행될 기회를 절대 얻지 못한다 —
 *   실제 I/O에 묶인 import 배치라면 남겨두었을 바로 그 순서다.
 * @property progressivePagination 위의 고정된 [pageWindows]/[paginatedText] 응답 대신, section별
 *   pagination continuation을 모델링한다: [getPageWindows]는 지금까지 실제로 "측정된" section당
 *   페이지 하나를 반환하며, 오직 [continuePagination](`continuePaginationIfIncomplete`를 통해)만이
 *   그 개수를 늘린다 — [importNextSections]가 몇 개의 section을 덧붙였는지와는 분리되어 있으며,
 *   `DocumentRepositoryImpl` 자체의 pagination 세션이 "알려짐"과 "측정됨" 사이에 두는 것과 같은
 *   분리다.
 * @property paginationSessionAlwaysInvalidated 한창 걷던 도중 무효화된 pagination continuation을
 *   모델링한다(`DocumentRepositoryImpl.invalidateDocumentCache` / [continuePagination] 참고):
 *   모든 호출이 실제로는 아무것도 측정하지 않은 채 "isComplete"를 보고하는 반면,
 *   [isPaginationComplete] 자체는 스스로 절대 true가 되지 않는다 — import가 여전히 실행 중인 동안
 *   `continuePaginationIfIncomplete`가 신뢰해서는 안 되는 "거짓 완료" 신호다.
 */
private class FakeDocumentRepository(
    private val documentId: DocumentId,
    private val format: DocumentFormat = DocumentFormat.TXT,
    pageCount: Int = 2,
    private val characterCount: Long? = 31L,
    private val paginatedText: String? = null,
    private val visualPageImages: Map<Int, ByteArray> = emptyMap(),
    private val throwOnGetVisualPageImagesCall: Int? = null,
    private val embeddedImages: Map<String, ByteArray> = emptyMap(),
    private val embeddedFontFiles: Map<String, String> = emptyMap(),
    private val throwOnGetEmbeddedFontFilesCall: Int? = null,
    private val throwOnGetEmbeddedImagesCall: Int? = null,
    private val freezeEmbeddedImagesAtCallIndex: Int? = null,
    private val embeddedImageGate: CompletableDeferred<Unit>? = null,
    private val readerDocument: ReaderDocument? = null,
    private val pageWindows: List<PageWindow>? = null,
    private val throwOnGetPageWindowsCall: Int? = null,
    private val freezeGetPageWindowsAtCallIndex: Int? = null,
    private val secondDocumentId: DocumentId? = null,
    private val secondPageWindows: List<PageWindow>? = null,
    private val markDocumentOpenedGate: CompletableDeferred<Unit>? = null,
    private val onMarkDocumentOpened: () -> Unit = {},
    private val storedViewportSize: ViewportSize? = null,
    private val importComplete: Boolean = true,
    private val importNextSectionsGate: CompletableDeferred<Unit>? = null,
    private val lazySectionBlocks: Map<Int, List<ReaderBlock>>? = null,
    private val freezeWarmSectionBlocksAtCallIndex: Int? = null,
    private val sectionsAppendedOnImport: List<ReaderSection> = emptyList(),
    private val progressiveImportBatches: List<List<ReaderSection>> = emptyList(),
    private val importBatchGates: List<CompletableDeferred<Unit>> = emptyList(),
    private val pageWindowsFollowLiveSections: Boolean = false,
    private val progressivePagination: Boolean = false,
    private val paginationSessionAlwaysInvalidated: Boolean = false,
) : DocumentRepository {
    /** [warmSectionBlocks]가 지금까지 예열된 것으로 기록한 모든 section 인덱스. */
    private val warmedSections = linkedSetOf<Int>()

    /**
     * 지금 당장 발행되는 warm이 실제로 무언가를 기록할 것인지 여부.
     *
     * `DocumentRepositoryImpl.warmSectionBlocks`는 저장소가 지금 들고 있는 캐시 객체를 예열하며,
     * 그런 것이 없으면 0을 반환한다(자체의 `?: return 0` 참고). 이 fake는 그것이 모델링하는 명시적인
     * 전면 폐기 경로에 대해서만 이를 꺼두며, 나중의 `getReaderDocument`/`getPageWindows`만이 새 것을
     * 만든다. 그것을 모델링하는 것이, 둘 사이에 발행된 warm이 요청받은 section을 조용히 계속
     * 기록하는 대신 여기서도 아무 일도 하지 않게 만드는 이유다.
     */
    private var sectionBlocksCacheAlive = true

    /**
     * [getReaderDocument]/[getPageWindows]가 지금 당장 응답하는 section 목록 — [readerDocument]로
     * 주어진 값에서 시작해, 아래의 [importNextSections]가 붙일 대기 중인 section을 찾는 그 한 번에
     * 자란다. `DocumentRepositoryImpl` 자체의 저장된 section 목록이 import 도중 자라는 것과 같은
     * 방식이다.
     */
    private var liveSections: List<ReaderSection> = readerDocument?.sections.orEmpty()

    /**
     * [sectionsAppendedOnImport]에서 가져와, 다음 [importNextSections] 호출로 덧붙여지기를 기다리는
     * section들.
     */
    private var pendingImportSections: List<ReaderSection> = sectionsAppendedOnImport

    /** [importNextSections]가 아직 소비하지 않은 [progressiveImportBatches]의 항목들. */
    private val pendingProgressiveBatches = progressiveImportBatches.toMutableList()

    /** 지금까지 소비된 [progressiveImportBatches]/[importBatchGates]의 항목 개수. */
    private var progressiveBatchIndex = 0

    /**
     * [progressivePagination]이 켜져 있을 때, 실제 page breaker가 지금까지 실제로 측정한
     * [liveSections]의 개수 — `DocumentRepositoryImpl` 자체의 첫 `getPageWindows` 호출이 독자가
     * 재개해 들어간 section만 측정하는 것(자체 문서 참고)과 같은 이유로 1에서 시작한다.
     */
    private var measuredSectionCount = 1
    private var importInFlight = false
    private var paginationInFlight = false

    var importPaginationOverlapDetected = false
        private set

    var continuePaginationCallCount = 0
        private set

    /**
     * [warmSectionBlocks]가 호출될 때마다 받은 모든 인자를 호출 순서대로 담는다 — 특정 호출 자체가
     * 기록한 section을 검사해야 하는 테스트가 읽는다(예: [freezeWarmSectionBlocksAtCallIndex]로
     * 얼려둔 뒤).
     */
    val warmSectionBlocksCalls = mutableListOf<Set<Int>>()

    /**
     * 지금까지 [warmSectionBlocks]가 호출된 횟수. [freezeWarmSectionBlocksAtCallIndex]가 지정하는
     * 호출 인덱스를 인식하는 데 쓰인다.
     */
    private var warmSectionBlocksCallCount = 0

    /** 얼려진 [warmSectionBlocks] 호출이 대기하는 게이트. [unfreezeWarmSectionBlocks]가 완료시킨다. */
    private val warmSectionBlocksFreezeGate = CompletableDeferred<Unit>()

    /** 지금까지 예열된 모든 section의 스냅샷 — [warmedSections]가 계속 바뀌므로 방어적으로 복사한다. */
    fun warmedSectionsSnapshot(): Set<Int> = warmedSections.toSet()

    /** 얼려진 warm을 풀어준다 — [freezeWarmSectionBlocksAtCallIndex]를 쓴 테스트의 끝에서 호출하여,
     * 테스트가 끝날 때 아무것도 중단된 채로 남지 않게 한다. */
    fun unfreezeWarmSectionBlocks() {
        warmSectionBlocksFreezeGate.complete(Unit)
    }

    /**
     * `DocumentRepositoryImpl.warmSectionBlocks`가 수행하는 on-demand 디코딩을 모델링한다:
     * [sectionIndexes]를 [warmedSections]에 기록하고 그중 몇 개가 새로 예열되었는지 답하며,
     * [sectionBlocksCacheAlive]를 존중하고(캐시가 폐기된 것으로 모델링되는 동안에는 0을 답한다),
     * [freezeWarmSectionBlocksAtCallIndex]가 지정하는 호출 인덱스에서 [warmSectionBlocksFreezeGate]에
     * 영원히 멈춰, 테스트가 진행 중인 warm을 관찰할 수 있게 한다.
     */
    override suspend fun warmSectionBlocks(documentId: DocumentId, sectionIndexes: Set<Int>): Int {
        if (documentId != this.documentId) return 0
        if (!sectionBlocksCacheAlive) return 0
        val callIndex = warmSectionBlocksCallCount++
        warmSectionBlocksCalls += sectionIndexes
        if (callIndex == freezeWarmSectionBlocksAtCallIndex) warmSectionBlocksFreezeGate.await()
        val newlyWarmed = (sectionIndexes - warmedSections).size
        warmedSections += sectionIndexes
        return newlyWarmed
    }

    /**
     * 리더가 문서를 여전히 열어둔 채로 라이브러리 행이 삭제된 상태를 모델링한다 — `toggleFavorite`의
     * rollback이 존재하는 이유인 상태다.
     */
    var documentRowMissing = false

    /**
     * 이 문서의 변경 가능한 저장된 metadata 행. [upsertDocument]에 의해 덮어써지며, [documentRowMissing]이
     * 아닌 한 [getDocument]가 응답한다.
     */
    private var metadata = DocumentMetadata(
        id = documentId,
        location = DocumentLocation(
            sourceUri = documentId.value,
            displayName = "Stored book",
            mimeType = "text/plain",
            sizeBytes = 100,
        ),
        format = format,
        addedAtEpochMillis = 1_000,
        pageCount = pageCount,
        characterCount = characterCount.takeIf {
            importComplete &&
                progressiveImportBatches.isEmpty() &&
                sectionsAppendedOnImport.isEmpty()
        },
        wordCount = 6,
    )

    /** [getDocument]가 호출된 횟수. */
    var getDocumentCallCount = 0
        private set

    /**
     * [metadata]가 현재 들고 있는 즐겨찾기 플래그. 뷰 모델을 거치지 않고 테스트가 직접 읽는다.
     */
    val isFavorite: Boolean get() = metadata.isBookmarked

    /**
     * [markDocumentOpened]가 가장 최근에 기록한 문서 id. open이 실제로 쓰였는지 확인하려는 테스트가
     * 읽는다.
     */
    var lastOpenedDocumentId: DocumentId? = null

    /** [markDocumentOpened]가 가장 최근에 기록한 timestamp. */
    var lastOpenedAtEpochMillis: Long = 0L

    /**
     * [metadata]를 담은 단일 원소 목록으로 응답한다. 이 스위트의 어떤 테스트도 이 fake로 문서 하나
     * 이상을 동시에 구동하지 않는다.
     */
    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = flowOf(listOf(metadata))

    /**
     * [documentId]에 대해 [metadata]로 응답하거나, [documentRowMissing]이 행이 삭제된 상태를
     * 모델링하는 동안에는 null로 응답한다.
     */
    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        if (documentRowMissing) {
            null
        } else {
            getDocumentCallCount += 1
            metadata.takeIf { it.id == documentId }
        }

    /**
     * [readerDocument](주어지지 않았으면 [format]/[metadata]로부터 만든 최소한의 placeholder)로
     * 응답하며, 항상 fake의 현재 [liveSections]와 다시 짝지어져, 호출자가 progressive import가
     * 지금까지 덧붙인 것을 그대로 보게 한다. 문서의 구조가 읽히는 순간 `DocumentRepositoryImpl`이
     * 디코딩된-블록 캐시를 재구성하는 것을 그대로 반영하여, [sectionBlocksCacheAlive]도 다시 살아
     * 있는 것으로 표시한다.
     *
     * 모델링된 단일 배치 import([sectionsAppendedOnImport])나 다중 배치 import
     * ([progressiveImportBatches])가 아직 자신의 배치(들)을 소비하지 못한 동안에는 [readerDocument]
     * 자체의 navigation을 빈 [ReaderNavigation]으로 보류한다 — 실제 progressive EPUB import가
     * `DocumentRepositoryImpl.importEpubPhase0`/`finishEpubImport`가 책을 완성하는 배치에서 그것을
     * 해석할 때까지 `ReaderDocument.navigation`을 비워두는 것과 같은 방식이다. [importComplete]
     * 하나만으로는 이를 게이트하지 않는다 — `ReaderViewModel.refreshPaginationCompleteness` 자체의
     * 문서대로: 테스트 더블은 import 완료를, 그것과 일치하기로 약속된 별도 플래그가 아니라
     * [ImportProgress.isComplete]를 통해 모델링한다.
     */
    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? {
        sectionBlocksCacheAlive = true
        val base = readerDocument ?: ReaderDocument(
            id = documentId,
            format = format,
            title = "Stored book",
            sections = emptyList(),
            pageCount = metadata.pageCount ?: 0,
        )
        val navigationWithheld = when {
            progressiveImportBatches.isNotEmpty() -> pendingProgressiveBatches.isNotEmpty()
            sectionsAppendedOnImport.isNotEmpty() -> pendingImportSections.isNotEmpty()
            else -> false
        }
        return base.copy(
            sections = liveSections,
            navigation = if (navigationWithheld) ReaderNavigation() else base.navigation,
        ).takeIf { documentId == this.documentId }
    }

    /** [visualPageImages] 중 요청받은 것들로 응답한다. */
    override suspend fun getVisualPageImages(
        documentId: DocumentId,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> {
        val callIndex = visualPageImageRequests++
        if (callIndex == throwOnGetVisualPageImagesCall) error("visual page fetch failed")
        return visualPageImages.filterKeys(pageIndexes::contains)
    }

    /** [embeddedImages] 중 요청받은 것들로 응답한다. */
    override suspend fun getEmbeddedImages(
        documentId: DocumentId,
        hrefs: Set<String>,
    ): Map<String, ByteArray> {
        val callIndex = embeddedImageRequests++
        if (callIndex == throwOnGetEmbeddedImagesCall) error("embedded image fetch failed")
        if (callIndex == freezeEmbeddedImagesAtCallIndex) embeddedImageGate?.await()
        return embeddedImages.filterKeys(hrefs::contains)
    }

    override suspend fun getEmbeddedFontFiles(
        documentId: DocumentId,
        hrefs: Set<String>,
    ): Map<String, String> {
        val callIndex = embeddedFontFileRequests++
        if (callIndex == throwOnGetEmbeddedFontFilesCall) error("embedded font fetch failed")
        return embeddedFontFiles.filterKeys(hrefs::contains)
    }

    /** 프로덕션 저장소가 응답하는 것과 같은 문서 전체 스캔을, 이 fake의 window들에 대해 수행한다. */
    override suspend fun getReferencedEmbeddedFontHrefs(documentId: DocumentId): Set<String> =
        pageWindows.orEmpty().asSequence()
            .flatMap { window -> window.blocks.asSequence() }
            .flatMap { block ->
                sequenceOf(block.style?.fontHref)
                    .plus(block.spans.asSequence().map { span -> span.styleDelta?.fontHref })
            }
            .filterNotNull()
            .toSet()

    /**
     * [getPageWindows]가 호출된 횟수. reload가 일어났는지 일어나지 않았는지 단언하는 테스트가
     * 읽는다.
     */
    var pageWindowRequests = 0
        private set

    /** 각 문서에 대한 가장 최근 [getPageWindows] 호출이 null이 아닌 breaker와 함께 도착했는지 여부. */
    val lastPageBreakerByDocumentId = mutableMapOf<DocumentId, Boolean>()

    /**
     * 이 fake가 응답하는 모든 문서를 통틀어 세는, 지금까지 [getPageWindows]가 호출된 횟수 —
     * [warmSectionBlocks]에 대해 [warmSectionBlocksCallCount]가 하는 것과 같은 역할이다 —
     * [freezeGetPageWindowsAtCallIndex]가 지정하는 호출 인덱스를 인식하는 데 쓰인다.
     */
    private var getPageWindowsCallCount = 0

    /** 지금까지 [getVisualPageImages]가 호출된 횟수. */
    private var visualPageImageRequests = 0

    /** 지금까지 [getEmbeddedImages]가 호출된 횟수. */
    private var embeddedImageRequests = 0

    /** 지금까지 [getEmbeddedFontFiles]가 호출된 횟수. */
    private var embeddedFontFileRequests = 0

    /**
     * [freezeGetPageWindowsAtCallIndex]로 얼려진 [getPageWindows] 호출이 대기하는, [CompletableDeferred]
     * 대신 [suspendCoroutine]으로 캡처된 순수 continuation이다. 취소 가능한 중단 지점인
     * [CompletableDeferred.await]와 달리, [suspendCoroutine]은 자체 계약상 "즉각적인 취소를 지원하지
     * 않는다" — [unfreezeGetPageWindows]를 통해 이것을 재개하면, `ReaderViewModel.openDocument`가
     * 그것을 시작한 job을 이미 취소했더라도, 대기 중인 호출은 언제나 평범한 코드로 되돌아온다.
     * 취소된 `Job`이 철회할 수 없는 실제 데이터베이스 읽기를 충실히 모델링한다([ReaderViewModel]
     * 자체의 클래스 문서 참고). 현재 얼려진 호출이 없으면 null.
     */
    private var frozenGetPageWindowsContinuation: Continuation<Unit>? = null

    /**
     * [freezeGetPageWindowsAtCallIndex]로 대기 중인 [getPageWindows] 호출을 재개하여, 테스트가 그것을
     * 풀어줄 수 있게 한다 — 보통은 그 아래에서 두 번째 문서 자체의 open을 완료까지 구동한 뒤다.
     * 현재 얼려진 호출이 없으면 아무 일도 하지 않는다.
     */
    fun unfreezeGetPageWindows() {
        frozenGetPageWindowsContinuation?.resume(Unit)
    }

    /**
     * [storedViewportSize]로 응답한다 — 이 문서에 대해 이전에 저장된 레이아웃이 측정되었던 viewport,
     * 없으면 null.
     */
    override suspend fun resolveViewportSizeForStyle(documentId: DocumentId, style: ReaderStyle): ViewportSize? =
        storedViewportSize.takeIf { documentId == this.documentId }

    /**
     * pagination 요청에 대한 fake 자체의 응답 사슬이다. 호출은 먼저 카운트되고, 그 인덱스가
     * [freezeGetPageWindowsAtCallIndex]와 일치하면 [unfreezeGetPageWindows]가 재개할 때까지 취소
     * 불가능하게 대기한다(이유는 [frozenGetPageWindowsContinuation] 자체의 문서 참고). 응답 자체는
     * 다음 순서로 시도된다: [secondDocumentId]는 다른 모든 id가 받는 "알 수 없는 문서" 빈 응답 대신,
     * 자기 자신의 실제 pagination을 가진 동시에 열린 두 번째 문서를 모델링하며 [secondPageWindows]로
     * 직접 응답한다. 그것이 아니면, 알 수 없는 문서나 PDF는 빈 값으로 응답한다(PDF는 텍스트
     * pagination이 없다). [progressivePagination]은 지금까지 [measuredSectionCount]가 측정한
     * 만큼의 페이지만 응답한다. 고정된 [pageWindows]는 on-demand 블록 디코딩을 모델링하기 위해
     * 선택적으로 [lazySectionBlocks]를 거쳐 걸러진 채 직접 응답한다. [paginatedText]는 [paginate]에
     * 의해 고정 크기 window로 나뉜다. 이 모두가 실패하면 고정된 두 페이지짜리 stub이 응답한다. 또한
     * 실제 저장소가 문서를 레이아웃할 때마다 블록을 새로 다시 측정하는 것을 반영하여, 매 호출마다
     * [pageWindowRequests]를 증가시키고 [sectionBlocksCacheAlive]를 다시 살아 있는 것으로 표시한다.
     */
    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: com.tedd.teddreader.core.common.model.ReaderPageBreaker?,
        anchorOffset: Long?,
        viewportDensity: Float,
    ): List<PageWindow> = run {
        pageWindowRequests += 1
        lastPageBreakerByDocumentId[documentId] = pageBreaker != null
        sectionBlocksCacheAlive = true
        val callIndex = getPageWindowsCallCount++
        if (callIndex == throwOnGetPageWindowsCall) error("page windows failed")
        if (callIndex == freezeGetPageWindowsAtCallIndex) {
            suspendCoroutine<Unit> { continuation -> frozenGetPageWindowsContinuation = continuation }
        }
    }.let {
        if (documentId == secondDocumentId) {
        secondPageWindows.orEmpty()
    } else if (documentId != this.documentId || format == DocumentFormat.PDF) {
        emptyList()
    } else if (pageWindowsFollowLiveSections) {
        val total = liveSections.size.coerceAtLeast(1)
        (0 until total).map { index ->
            PageWindow(
                pageIndex = PageIndex(current = index, total = total),
                location = ReaderLocation.TextOffset(index.toLong()),
                text = "Section $index",
                textRange = TextRange(index.toLong(), index.toLong() + 1),
            )
        }
    } else if (progressivePagination) {
        val total = measuredSectionCount.coerceAtMost(liveSections.size).coerceAtLeast(1)
        (0 until total).map { index ->
            PageWindow(
                pageIndex = PageIndex(current = index, total = total),
                location = ReaderLocation.TextOffset(index.toLong()),
                text = "Section $index",
                textRange = TextRange(index.toLong(), index.toLong() + 1),
            )
        }
    } else if (pageWindows != null) {
        lazySectionBlocks?.let { blocksBySection ->
            LazyBlockPageWindows(pageWindows, liveSections, ::warmedSectionsSnapshot, blocksBySection)
        } ?: pageWindows
    } else if (paginatedText != null) {
        paginate(paginatedText, viewportSize ?: ViewportSize(widthPx = 320, heightPx = 560))
    } else {
        listOf(
            PageWindow(
                pageIndex = PageIndex(current = 0, total = 2),
                location = ReaderLocation.TextOffset(0),
                text = "First stored page",
                textRange = TextRange(0, 17),
            ),
            PageWindow(
                pageIndex = PageIndex(current = 1, total = 2),
                location = ReaderLocation.TextOffset(18),
                text = "Second stored page",
                textRange = TextRange(18, 36),
            ),
        )
        }
    }

    /**
     * [text]를 [viewportSize]의 너비에 맞춘 고정 크기 window로 나눈다. 테스트가 정확한 측정이 아니라
     * 그럴듯한 페이지 수만 필요할 때 실제 pagination 엔진을 대신한다.
     */
    private fun paginate(text: String, viewportSize: ViewportSize): List<PageWindow> {
        val charsPerPage = (viewportSize.widthPx / 10).coerceAtLeast(1)
        val starts = (0 until text.length step charsPerPage).toList()
        return starts.mapIndexed { index, start ->
            val end = (start + charsPerPage).coerceAtMost(text.length)
            PageWindow(
                pageIndex = PageIndex(current = index, total = starts.size),
                location = ReaderLocation.TextOffset(start.toLong()),
                text = text.substring(start, end),
                textRange = TextRange(start.toLong(), end.toLong()),
            )
        }
    }

    /**
     * 이 스위트의 어떤 테스트에서도 쓰이지 않는다. 완전히 새 문서를 import하는 것은
     * [ReaderViewModel] 자체 테스트의 범위 밖이다.
     */
    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument = error("not used")

    /**
     * [metadata]를 [document]로 덮어쓴다 — `ReaderViewModel.toggleFavorite`의 쓰기가 이것을 기준으로
     * 검사된다.
     */
    override suspend fun upsertDocument(document: DocumentMetadata) {
        metadata = document
    }

    /**
     * [lastOpenedDocumentId]/[lastOpenedAtEpochMillis]를 쓰기 전에, [onMarkDocumentOpened]로 open을
     * 기록한 다음, 설정되어 있다면 [markDocumentOpenedGate]를 거친다.
     */
    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) {
        onMarkDocumentOpened()
        markDocumentOpenedGate?.await()
        lastOpenedDocumentId = documentId
        lastOpenedAtEpochMillis = openedAtEpochMillis
    }

    /** 이 스위트의 어떤 테스트에서도 쓰이지 않는다. */
    override suspend fun deleteDocument(documentId: DocumentId) = Unit

    /**
     * 고정된 단일 배치 모델에서는 [importComplete]로 응답하고, 다중 배치 모델에서는
     * [progressiveImportBatches]의 모든 항목이 소비되었는지로 응답한다.
     */
    override suspend fun isImportComplete(documentId: DocumentId): Boolean = when {
        documentId != this.documentId -> true
        progressiveImportBatches.isNotEmpty() -> pendingProgressiveBatches.isEmpty()
        else -> importComplete
    }

    /**
     * [importNextSections]가 호출된 횟수. 백그라운드 import continuation이 실제로 시작되었는지
     * 확인하는 테스트가 읽는다.
     */
    var importNextSectionsCallCount = 0
        private set

    /**
     * progressive EPUB import의 한 단계를 모델링한다: 설정되어 있으면 [importNextSectionsGate]를
     * 기다린 다음, [progressiveImportBatches]의 다음 항목을 소비하거나(먼저 자신의
     * [importBatchGates] 항목을 기다린다) [sectionsAppendedOnImport]를 한 번에 덧붙이며, 완료
     * reload가 pagination을 대체할 때까지 이미 살아 있는 예열된 section/캐시는 그대로 둔다 — 실제
     * 저장소의 "제자리에 덧붙이고, 끝날 때 한 번만 무효화하는" import 경로를 그대로 반영한다.
     */
    override suspend fun importNextSections(
        documentId: DocumentId,
        count: Int,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float,
    ): ImportProgress {
        importInFlight = true
        importPaginationOverlapDetected = importPaginationOverlapDetected || paginationInFlight
        try {
            importNextSectionsGate?.await()
            importNextSectionsCallCount += 1
            if (pendingProgressiveBatches.isNotEmpty()) {
                importBatchGates.getOrNull(progressiveBatchIndex)?.await()
                val batch = pendingProgressiveBatches.removeAt(0)
                progressiveBatchIndex += 1
                liveSections = liveSections + batch
                if (pendingProgressiveBatches.isEmpty()) {
                    metadata = metadata.copy(characterCount = metadataCharacterCount())
                }
                return ImportProgress(isComplete = pendingProgressiveBatches.isEmpty(), sectionsImported = batch.size)
            }
            val appended = pendingImportSections
            if (appended.isEmpty()) return ImportProgress(isComplete = true, sectionsImported = 0)
            pendingImportSections = emptyList()
            liveSections = liveSections + appended
            metadata = metadata.copy(characterCount = metadataCharacterCount())
            return ImportProgress(isComplete = true, sectionsImported = appended.size)
        } finally {
            importInFlight = false
        }
    }

    /**
     * [progressivePagination]의 section별 측정 한 단계, 또는 [paginationSessionAlwaysInvalidated]가
     * 대신하는 "거짓 완료" 신호를 모델링한다. 인터페이스 자체의 기본값이 아닌 무언가로 응답하도록
     * 이를 override하는 것은 그 두 플래그 중 하나가 설정되었을 때만 의미가 있다:
     * `DocumentRepository` 자체의 기본 구현(`isComplete = true, sectionsMeasured = 0`)은 둘 다
     * 아닐 때 이미 적용되며, 이것이 바로 두 플래그를 모두 기본값으로 남겨두는 이 스위트의 어떤
     * 테스트도 실제로 `ReaderViewModel.continuePaginationIfIncomplete`를 시작하지 않는 이유다 — 그
     * continuation은 [isPaginationComplete]가 false로 답할 때만 시작되며, 인터페이스 기본값은 절대
     * 그렇게 답하지 않는다.
     */
    override suspend fun continuePagination(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float,
    ): PaginationProgress {
        continuePaginationCallCount += 1
        paginationInFlight = true
        importPaginationOverlapDetected = importPaginationOverlapDetected || importInFlight
        try {
            return when {
                paginationSessionAlwaysInvalidated -> PaginationProgress(isComplete = true, sectionsMeasured = 0)
                !progressivePagination || documentId != this.documentId || pageBreaker == null ||
                    measuredSectionCount >= liveSections.size -> PaginationProgress(isComplete = true, sectionsMeasured = 0)
                else -> {
                    measuredSectionCount += 1
                    PaginationProgress(isComplete = measuredSectionCount >= liveSections.size, sectionsMeasured = 1)
                }
            }
        } finally {
            paginationInFlight = false
        }
    }

    /**
     * [paginationSessionAlwaysInvalidated]가 한창 걷던 도중 무효화된 세션을 모델링할 때는 false로,
     * [progressivePagination]이 꺼져 있을 때는(이어갈 것이 없으므로) true로, 그 외에는
     * [measuredSectionCount]가 [liveSections]를 따라잡았는지로 응답한다.
     */
    override suspend fun isPaginationComplete(documentId: DocumentId): Boolean = when {
        paginationSessionAlwaysInvalidated -> false
        !progressivePagination -> true
        else -> documentId != this.documentId || measuredSectionCount >= liveSections.size
    }

    private fun metadataCharacterCount(): Long =
        characterCount ?: liveSections.maxOfOrNull { it.range.end } ?: 0L
}

/**
 * [DocumentRepositoryImpl]의 on-demand 블록 디코딩(SectionBlocksCache 참고)을 대신한다: 페이지의
 * 블록은 [warmedSections]가 그것을 소유한 section을 예열됨으로 보고할 때까지 빈 채로 읽히다가, 그
 * section에 대한 [blocksBySection]의 응답으로 바뀐다 — [FakeDocumentRepository.getPageWindows]가
 * 다시 호출되는 일 없이, 실제로 복원된 페이지 목록이 자신의 section 블록이 도착하면 페이지를
 * 제자리에서 재구성하는 것과 같은 방식이다.
 *
 * @property pages 블록이 없는, 바탕이 되는 page window들.
 * @property sections 페이지의 시작 offset을 그것을 소유한 section을 찾기 위해 대조하는 section
 *   목록.
 * @property warmedSections 지금까지 어떤 section 인덱스가 예열되었는지의 살아 있는 스냅샷 — 한
 *   번만 캡처되는 것이 아니라 매 [get]마다 새로 조회되므로, 이미 한 번 읽힌 페이지도 그 뒤에
 *   예열된 블록을 여전히 받아올 수 있다.
 * @property blocksBySection section 인덱스를 키로 하는, 각 section 자체의 디코딩된 블록.
 */
private class LazyBlockPageWindows(
    private val pages: List<PageWindow>,
    private val sections: List<ReaderSection>,
    private val warmedSections: () -> Set<Int>,
    private val blocksBySection: Map<Int, List<ReaderBlock>>,
) : AbstractList<PageWindow>() {
    /** [pages]에 있는 페이지 수. 어떤 section이 예열되었는지와는 무관하다. */
    override val size: Int get() = pages.size

    /**
     * [pages]의 [index] 위치의 window. 그 section이 예열되면 블록을 [blocksBySection]에서 읽고,
     * 그렇지 않으면 비어 있다.
     */
    override fun get(index: Int): PageWindow {
        val page = pages[index]
        val start = page.textRange?.start
        val sectionIndex = sections
            .filter { section -> start != null && section.range.start <= start }
            .maxByOrNull { section -> section.range.start }
            ?.index
        val blocks = sectionIndex?.takeIf { it in warmedSections() }?.let(blocksBySection::get).orEmpty()
        return page.copy(blocks = blocks)
    }
}

/**
 * [ReaderRepository]에 대한 단일 문서 테스트 더블이다: [progress]는 이 fake가 아는 유일한 읽기
 * 진행 행을 담으며, [DocumentId]는 완전히 무시한다. 이 스위트의 어떤 테스트도 같은 인스턴스로
 * 문서 하나 이상의 진행 상황을 시험하지 않기 때문이다.
 */
private class FakeReaderRepository : ReaderRepository {
    /**
     * 테스트가 문서를 열기 전에 미리 심어두거나, [saveProgress]가 실행된 뒤에 읽는 저장된 읽기
     * 진행 상황.
     */
    var progress: ReadingProgress? = null

    /**
     * 구독 시점의 [progress]를 스냅샷한 단일 값 flow로 응답한다. 이후의 쓰기에는 갱신되지 않는다.
     */
    override fun observeProgress(documentId: DocumentId): Flow<ReadingProgress?> = MutableStateFlow(progress)

    /** [progress]를 그대로 응답한다. */
    override suspend fun getProgress(documentId: DocumentId): ReadingProgress? = progress

    /**
     * [progress](매개변수)로 [progress]를 덮어쓴다 — [ReaderViewModel] 자체의 progress 쓰기가 이를
     * 기준으로 검사된다.
     */
    override suspend fun saveProgress(progress: ReadingProgress) {
        this.progress = progress
    }

    /** [progress]를 비운다. 이 스위트의 어떤 테스트도 이를 시험하지 않는다. */
    override suspend fun deleteProgress(documentId: DocumentId) {
        progress = null
    }
}

/**
 * 로컬 쓰기와, 이미 열려 있는 리더로의 외부 설정 화면 emission 둘 다를 구동하는 데 쓰이는, 변경
 * 가능한 인메모리 [ReaderSettingsRepository].
 *
 * @param initialSettings [settings]가 처음 발행하는 설정 스냅샷.
 */
private class FakeReaderSettingsRepository(
    initialSettings: ReaderSettings = ReaderSettings(),
) : ReaderSettingsRepository {
    private val state = MutableStateFlow(initialSettings)
    override val settings: Flow<ReaderSettings> = state

    var lastAutoScrollConfig: AutoScrollConfig? = null
    var failStyleWrites: Boolean = false

    fun emit(settings: ReaderSettings) {
        state.value = settings
    }

    override suspend fun updateStyle(style: ReaderStyle) {
        if (failStyleWrites) error("settings write failed")
        state.value = state.value.copy(style = style.copy(publisherFontKey = null))
    }

    override suspend fun updatePageTurnMode(pageTurnMode: com.tedd.teddreader.core.common.model.PageTurnMode) {
        state.value = state.value.copy(pageTurnMode = pageTurnMode)
    }

    override suspend fun updatePageAnimation(pageAnimation: com.tedd.teddreader.core.common.model.PageAnimation) {
        state.value = state.value.copy(pageAnimation = pageAnimation)
    }

    override suspend fun updateAutoScrollConfig(autoScrollConfig: AutoScrollConfig) {
        lastAutoScrollConfig = autoScrollConfig
        state.value = state.value.copy(autoScrollConfig = autoScrollConfig)
    }

    override suspend fun updateAppLanguage(appLanguage: AppLanguage) {
        state.value = state.value.copy(appLanguage = appLanguage)
    }
}

/**
 * 모든 문서 id에 걸쳐 공유되는 단일 인메모리 목록을 기반으로 하는 [BookmarkRepository]의 테스트
 * 더블이다 — 이 스위트의 어떤 테스트도 같은 인스턴스로 문서 하나 이상의 bookmark를 시험하지 않는다.
 */
private class FakeBookmarkRepository : BookmarkRepository {
    /**
     * 현재 담고 있는 저장 위치들. [observeBookmarks]가 모든 쓰기를 실시간으로 반영하도록
     * [MutableStateFlow]로 되어 있다.
     */
    val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())

    /** [documentId]는 무시하고 [bookmarks]로 직접 응답한다. */
    override fun observeBookmarks(documentId: DocumentId): Flow<List<Bookmark>> = bookmarks

    /** [bookmarks] 중 id가 [bookmarkId]와 일치하는 bookmark로 응답하거나, 없으면 null. */
    override suspend fun getBookmark(bookmarkId: String): Bookmark? = bookmarks.value.firstOrNull { it.id == bookmarkId }

    /**
     * [bookmark]와 id가 같은 기존 bookmark가 있으면 대체한 뒤 [bookmark]를 추가한다 — 실제 저장소가
     * 저장 위치에 부여하는 것과 같은, id 기준 대체 의미론이다.
     */
    override suspend fun saveBookmark(bookmark: Bookmark) {
        bookmarks.value = bookmarks.value.filterNot { it.id == bookmark.id } + bookmark
    }

    /** [bookmarks] 중 id가 [bookmarkId]와 일치하는 bookmark가 있으면 제거한다. */
    override suspend fun deleteBookmark(bookmarkId: String) {
        bookmarks.value = bookmarks.value.filterNot { it.id == bookmarkId }
    }
}

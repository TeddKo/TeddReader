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
 * Pins [ReaderViewModel]'s behavior across its four-stage open pipeline, its progressive-import and
 * progressive-pagination continuations, its bounded mount-window warming, page navigation (relative
 * moves, outline/location jumps, and their page-turn side effects), and the favourite/saved-place
 * toggles.
 *
 * Every test drives the view model through [dispatcher], a [StandardTestDispatcher], so every
 * coroutine it launches is stepped deterministically through `advanceUntilIdle()` rather than racing
 * on a real scheduler. Most tests here exist because a specific bug shipped and was fixed; each
 * carries its own KDoc naming which regression it guards, and the ones marked with a "Pins ..."
 * opening line quote this project's own bug labels (F1(a), F1(b), F3, f33313b) verbatim. The fakes
 * below — [FakeDocumentRepository] above all — model not just the happy path but specific failure
 * and concurrency modes this view model has to survive: an emptied decoded-block cache, a nulled or
 * spuriously-"complete" pagination session, a missing document row, and a frozen background-warm
 * call used to catch a fill partway through instead of only ever observing it fully settled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    /** The [StandardTestDispatcher] every test in this suite drives its view model's coroutines through. */
    private val dispatcher = StandardTestDispatcher()

    /**
     * Installs [dispatcher] as the main dispatcher before each test, so `viewModelScope.launch`
     * resolves to it.
     */
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    /**
     * Restores the real main dispatcher after each test, so this suite leaves no dispatcher
     * override behind.
     */
    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Baseline sanity check: opening a document publishes the first stored page's text and index,
     * and records the open (document id and a positive timestamp) with [DocumentRepository].
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

    /** A failed or stalled same-document open must be restartable instead of staying terminally blank. */
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
     * Baseline sanity check: [ReaderViewModel.moveToLocation] with a [ReaderLocation.TextOffset]
     * lands on the page containing that offset.
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
     * Guards [ReaderViewModel.moveToLocation] against ever collapsing into a text-offset-only lookup
     * for a visual document. A visual document's outline is built entirely out of
     * [ReaderLocation.PdfPage] entries (see `ReaderViewModel.buildOutlineItems`), and every outline tap
     * or jump-location effect in `ReaderScreen` reaches a page solely through this function — there is
     * no other path. `PaginatedDocument.pageOf(location)` (via `absoluteOffsetOf`) answers null for a
     * [ReaderLocation.PdfPage], so a body that resolved every location through it directly would
     * silently turn a PDF/CBZ outline tap into a page-jump the UI still offers but can never actually
     * take, breaking the reader invariant that an offered jump target must land somewhere real. Every
     * other moveToLocation test in this suite drives it with a [ReaderLocation.TextOffset], so none of
     * them would notice that regression.
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
     * Pins [ReaderViewModel.openDocument]'s synchronous reset: opening a second document while the
     * first is still loading must immediately clear every field of the previous document's UI state
     * — text, current page, slots, outline — rather than leave a stale frame from the document being
     * left on screen until the new document's own load catches up.
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
     * Pins the [currentDocumentId][ReaderViewModel] re-check `ReaderViewModel.loadOpenState` performs
     * immediately after its `DocumentRepository.getPageWindows` call, right before writing
     * `viewportSize`, `pageBreakerStyle`, and the view model's own `paginated` field. `Job.cancel()`
     * cannot stop a database read already in flight (see [ReaderViewModel]'s own class doc), so
     * `openDocument`'s synchronous cancel of the previous document's job does not retract document A's
     * [FakeDocumentRepository.getPageWindows] call once it is already resolving — releasing it here
     * through [FakeDocumentRepository.unfreezeGetPageWindows], after document B's own open has already
     * completed, models exactly that late resolution. Without the re-check, A's resolving read
     * silently overwrites the `paginated` field document B's open just finished publishing into, even
     * though `publishFirstFrame`'s own, separate guard still refuses to publish A's frame over B's —
     * which is exactly why the corruption stays invisible until something else reads the corrupted
     * field, here a page turn via [ReaderViewModel.moveNext].
     *
     * [FakeDocumentRepository.freezeGetPageWindowsAtCallIndex] parks call 0 — document A's own
     * [FakeDocumentRepository.getPageWindows] call — on a raw [suspendCoroutine] rather than a
     * [CompletableDeferred], because a `CompletableDeferred.await()` gate is itself a cancellable
     * suspension point: parking document A's open on one would resume it with a
     * [kotlinx.coroutines.CancellationException] the instant `openDocument(documentB.value)` cancels
     * its job, so the guarded lines this test means to pin would never run at all, and the test would
     * pass whether or not the guard exists — the mistake a first attempt at this test already made.
     * Document B's own [FakeDocumentRepository.getPageWindows] call (call index 1, answered from
     * [FakeDocumentRepository]'s own second-document support rather than the "unknown document" empty
     * answer every other id gets) is not caught by the same freeze, so document B's open runs to
     * completion underneath document A's still-parked one.
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
     * A text document's stored [ReaderLocation.TextOffset] progress must resolve to the right page
     * once pagination actually runs against a real pane measurement, not just against
     * [openDocument]'s own guessed default viewport.
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
     * A PDF's stored [ReaderLocation.PdfPage] progress must resolve to the same page number on open
     * — a visual document has no pagination pass to resume through, so this is resolved directly
     * from the stored [PageIndex] rather than through [PaginatedDocument.pageOf].
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
     * Opening a CBZ document starts [ReaderViewModel.openDocument]'s [DocumentFormat.CBZ] branch,
     * which preloads visual page images around the current page through
     * [DocumentRepository.getVisualPageImages].
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
     * Opening an EPUB document publishes the current page's own blocks (already present on the
     * stored [PageWindow]) and preloads its embedded images through
     * [DocumentRepository.getEmbeddedImages], both reaching [ReaderUiState.currentPage].
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
     * Embedded-font resolution starts after the first frame but before the opened-at write finishes, so
     * an indexed font set can settle the layout key while that unrelated database write is still parked.
     * Import continuation remains behind the write to preserve completed-outline ordering.
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
     * When an EPUB carries a package title and navigation, [ReaderUiState.documentTitle] and the
     * outline (heading, item titles, levels, and each item's [ReaderLocation.EpubOffset]) come from
     * that navigation via [readerOutlineItems], not from a per-section fallback.
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
     * Pins the bug `ReaderViewModel.continueImportIfIncomplete`'s completion branch now fixes: EPUB
     * navigation is only resolved into the stored document on the import batch that completes the
     * book (`DocumentRepositoryImpl.importEpubPhase0`/`finishEpubImport`), so an outline published
     * only once, at open time, from a not-yet-complete import stayed stuck at whatever it read then —
     * empty or section-only — until the reader relaunched the app. Opening while the import is still
     * running must not show the not-yet-resolved navigation, and finishing the import must republish
     * the outline (heading included) from the freshly imported document without a relaunch.
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
     * Pins the ordering `ReaderViewModel.openDocument` now guarantees between
     * `ReaderViewModel.publishRest` and `ReaderViewModel.startContinuations`: [publishRest] — including
     * its own suspending [DocumentRepository.markDocumentOpened] write — must run to completion before
     * [startContinuations] ever starts `ReaderViewModel.continueImportIfIncomplete`, so that
     * continuation's completion branch can never publish the resolved outline earlier than [publishRest]
     * and have it overwritten by [publishRest]'s own, older open-time snapshot.
     *
     * [epubOutlineFillsInFromNavigationOnceProgressiveImportCompletes] above only ever exercises the
     * case where [publishRest] has already finished before the import continuation's first batch lands,
     * because it gates [FakeDocumentRepository.importNextSectionsGate] — the same gate `publishRest`'s
     * own suspension is guaranteed to resolve ahead of in that test. This test instead gates
     * [FakeDocumentRepository.markDocumentOpenedGate] and leaves the import ungated, which — since this
     * suite drives every coroutine through the manually-stepped [dispatcher] — forces the completion
     * branch to run and publish the resolved navigation to completion first, strictly before
     * `markDocumentOpened` (and therefore [publishRest]'s own outline publish) is allowed to resume. If
     * `openDocument` ever started continuations before calling [publishRest] again, [publishRest]'s
     * resume here would overwrite the fresh heading/items with the empty navigation it captured at open
     * time — reproducing the bug this fix branch exists for, deterministically, rather than depending on
     * real I/O timing the way the original report did.
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
     * The reader still avoids building a whole-book page list: only the mounted window is published
     * through [ReaderUiState.pageSlots], while the current page's text is available immediately.
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
     * Pins [PaginatedDocument.chapterTitleAt]'s inheritance through [ReaderViewModel]: the chapter
     * title stays pinned in the top bar for the whole chapter, not only its first page — moving from
     * the chapter's first page to a later page of the same chapter must not lose the title.
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

    /** A repository emission is the shared source of truth for an already-open reader. */
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
     * Pins the style-match guard inside `ReaderViewModel.reloadPages`
     * (`pageBreakerStyle?.layoutKey() != style.layoutKey()`) in place by asserting, before the pane
     * ever remeasures under the new font, that [updateFontSize][ReaderViewModel.updateFontSize] alone
     * has not asked [DocumentRepository.getPageWindows] for a new layout. Without that guard,
     * `updateStyle` would lay the book out for the new style using a page breaker still measured for
     * the old one — mixing a stale measurement into a page the reader has not yet seen under its real
     * font, the kind of styling/content mismatch this project's reader invariants call out by name.
     * The original version of this test only asserted after the pane's second, matching report, which
     * passes whether or not the guard exists; that assertion proves a reload eventually happens, never
     * that the guard held it back until then. A later consolidation of this guard would slip through
     * unnoticed without the assertion added here. A breaker measured for the old style cannot
     * safely reload the new one (see `reloadPages`' own guard), so nothing has queried
     * `getPageWindows` yet after only [ReaderViewModel.updateFontSize] runs — only once the pane
     * remeasures under the new font and reports a matching breaker does the reload actually run.
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
     * Pins the fix for the stale-page-slice defect: a layout-affecting style change (here, a font
     * family) publishes [ReaderUiState.style] instantly, but the pages held in [ReaderUiState.currentPage]
     * were sliced for the *previous* style and stay that way until the pane recomposes, remeasures, and
     * reports a breaker for the new key — the same asynchronous round-trip
     * [changingTheFontSizeStillLaysTheBookOutAgain] pins from the `getPageWindows`-call-count side.
     * [ReaderUiState.pageDrawStyle] is what the two page surfaces actually draw with, and this test pins
     * it from the opposite side: type, not call count. The middle assertion —
     * `documentRepository.pageWindowRequests` unchanged right after [ReaderViewModel.updateFontFamily] —
     * is what makes the third assertion mean something even against a fake that does not really re-slice
     * anything: it proves no new measurement has landed yet, so [ReaderUiState.pageDrawStyle] answering
     * the *old* layout key at that exact moment is not a coincidence of a fake that ignores style, but
     * the fix actually holding the draw type back. Once the pane reports again and a real reload lands,
     * [ReaderUiState.pageDrawStyle] must agree with [ReaderUiState.style] again — the swap from old type
     * to new type is atomic, with no published state in between the two.
     *
     * Falsification (the AGENTS.md drill): neutralise `ReaderUiState.pageDrawStyle` in place to
     * `get() = style` and re-run this suite. Only this test's third assertion may fail — the one
     * comparing `before.style.layoutKey()` against `pageDrawStyle.layoutKey()`, which is the only
     * assertion in this test that reads [ReaderUiState.pageDrawStyle] at all; the middle assertion
     * reads `documentRepository.pageWindowRequests`, a `getPageWindows` call count untouched by that
     * neutralisation. Every other test in this class, including
     * [changingTheFontSizeStillLaysTheBookOutAgain], must still pass.
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
     * Pins the same stale-page-slice fix as
     * [fontFamilyChangeKeepsDrawingTheOldSlicesWithTheirOwnTypeUntilTheReloadLands], for
     * [ReaderViewModel.updateFontWeight] instead of a font-family change: a layout-affecting style
     * change (here, weight) publishes [ReaderUiState.style] instantly, but the pages held in
     * [ReaderUiState.currentPage] were sliced for the *previous* weight and stay that way until the pane
     * recomposes, remeasures, and reports a breaker for the new key. [ReaderUiState.pageDrawStyle] is
     * what the two page surfaces actually draw with, and this test pins it from the type side exactly
     * the way the font-family version does. A non-default weight (600) is chosen deliberately — weight
     * only moves [layoutKey] when it differs from `ReaderDefaultFontWeight` (see
     * `ReaderModelsTest.nonDefaultFontWeightChangesLayoutKeyButDefaultWeightDoesNot`), so the middle
     * assertion's `getPageWindows`-call-count check and the third assertion's `layoutKey()` comparison
     * both need a real layout-key change to observe.
     *
     * Falsification (the AGENTS.md drill): neutralise `ReaderUiState.pageDrawStyle` in place to
     * `get() = style` and re-run this suite. Only this test's third assertion may fail — the one
     * comparing `before.style.layoutKey()` against `pageDrawStyle.layoutKey()` — while every other test
     * in this class, including
     * [fontFamilyChangeKeepsDrawingTheOldSlicesWithTheirOwnTypeUntilTheReloadLands], must still pass.
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
     * A colour-only change (here, the theme) must never be caught by the same freeze that pins type
     * during a layout-key change: [ReaderUiState.pageDrawStyle] only ever pins the four layout fields
     * [layoutKey] reduces a style to, never [ReaderStyle.textColor] or the rest of a style's colour
     * fields — those ride straight through from the live [ReaderUiState.style], with no pane report
     * required, because colour can never move a page break. This is the regression guard for "a design
     * that makes the whole style wait for pagination is a regression"; the no-repagination half of the
     * same claim is already pinned by [changingOnlyTheThemeDoesNotLayTheBookOutAgain].
     *
     * The font family is changed first, *before* the theme, so the second assertion actually
     * exercises the freeze: `updateThemeMode` alone touches no field [layoutKey] reduces a style to,
     * so comparing `before.style.layoutKey()` against the live style's `layoutKey()` after only a
     * theme change would pass whether or not [ReaderUiState.pageDrawStyle] pins anything — it would
     * just restate that a theme change never moves `layoutKey()` in the first place. Stacking the
     * font change first means the pinned style has to survive a *second*, unrelated publish (the
     * theme) while still holding the type from before either change, which is what the freeze
     * actually promises.
     *
     * Falsification (the AGENTS.md drill): neutralise `ReaderUiState.pageDrawStyle` in place to
     * `get() = style` and re-run this suite. This test's *second* assertion fails, because with the
     * freeze gone `pageDrawStyle.layoutKey()` reports the live, font-changed key instead of the one
     * pinned from before that change. The first assertion, on `textColor`, keeps passing either way —
     * colour rides the live style whether or not the freeze exists, so it is not what this drill
     * exercises.
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
     * A viewport resize that repaginates the document must keep the reader on the same reading
     * offset, resolved into whatever new page now contains it — not on the same page *index*, which
     * would land somewhere else entirely once the page count changes.
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
     * Pins [ReaderViewModel.movePrevious]/[ReaderViewModel.moveNext]'s own contract: they resolve
     * only the step against whatever pagination is live when they run, never a page index captured
     * earlier, so a repagination that happens in between (here, a viewport resize that repaginates
     * the document shorter, standing in for a font or line-height change) cannot misplace the
     * target. A step that would overrun the newly-shorter document is dropped entirely rather than
     * clamped onto the last page.
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
     * Baseline sanity check: [ReaderViewModel.toggleFavorite]'s happy path flips both the published
     * flag and the stored document.
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
     * Baseline sanity check: [ReaderViewModel.toggleSavedPlace] saves the current page as a
     * [Bookmark] with a null label and a [ReaderLocation.TextOffset] location on the first tap, and
     * removes it again on the second.
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
     * Pins [AutoScrollConfig.clampSpeed]'s floor: [ReaderViewModel.updateAutoScrollSpeed] with a
     * speed at or below zero publishes and persists the clamped minimum, not zero itself.
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
     * `ReaderViewModel.publishFirstFrame` always publishes auto-scroll as disabled for a freshly
     * opened session, even when the persisted [ReaderSettings.autoScrollConfig] has it enabled — a
     * reader should never land on a moving page the instant a book opens.
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
     * Pins [AutoScrollConfig.clampSpeed]'s ceiling: [ReaderViewModel.updateAutoScrollSpeed] with a
     * speed above the maximum publishes and persists the clamped maximum, not the raw input.
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
     * `ReaderViewModel.updateAutoScroll` hides the reader chrome the moment auto-scroll turns on —
     * the chrome would otherwise sit on screen fighting for attention with a page moving on its own.
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
     * [ReaderViewModel.stopAutoScroll] disables the published flag immediately, synchronously, and
     * only then persists the disabled state in the background — the UI must not wait on the write to
     * stop showing auto-scroll as enabled.
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
     * Regression guard for f33313b: [ReaderViewModel.openDocument] must paginate against the
     * default guessed viewport (`DefaultViewportSize`) unconditionally. If it instead waited for a
     * real pane measurement, a freshly imported book — no pages, so the pager mounts no slot, so
     * nothing ever measures the pane — would never open.
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
     * [ReaderViewModel.updatePageBreaker] is the only trigger left that can launch a reload (a
     * separate viewport-size callback that used to also trigger one is gone), so one real pane
     * report settles into exactly one `getPageWindows` call, and a repeat of the same report — as
     * if the pane's effect replayed mid-composition — is deduped, not doubled.
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
     * Step 6 regression guard: before this fix, [ReaderViewModel.openDocument] always paginated
     * against a hardcoded guessed viewport, which almost never matched a stored layout's real one,
     * so the first publish carried a wrong/estimated total corrected only once the pane measured
     * for real. A resolved stored layout must reach the very first publish instead.
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
     * Once [ReaderViewModel.openDocument] has adopted a stored layout's viewport, the pane's first
     * real report — the same physical screen, so the same sp size — must be recognised by
     * [ReaderViewModel.updatePageBreaker]'s dedupe as already answered, not launch a reload that
     * would only repeat what `getPageWindows` already cached the answer under.
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
     * Neither [PageTurnMode] nor [PageAnimation] is part of `ReaderLayoutKey` (see `ReaderModels.kt`):
     * the text breaks in the same places no matter how pages turn or animate, so
     * [ReaderViewModel.updatePageTurnMode] and [ReaderViewModel.updatePageAnimation] must never ask
     * the repository to lay the book out again.
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
     * Pins the split between [ReaderViewModel]'s two publishes across
     * [DocumentRepository.markDocumentOpened]: with that write gated open, the first frame — style,
     * total, current page, its text — is already up, because [ReaderViewModel]'s first publish no
     * longer waits for it. Once the gate is released and the second publish lands (observed here by
     * `markDocumentOpened` completing), the page, its text, and the total that already reached the
     * reader must not have moved.
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
     * The one externally observable assertion that splitting [ReaderViewModel.openDocument] into
     * private stages does not reorder its two publishes relative to
     * [DocumentRepository.markDocumentOpened]. Unlike the gate-based test above, which infers the
     * ordering from state read after `advanceUntilIdle()`, this has the fake capture
     * `uiState.value.isLoading` at the exact instant `markDocumentOpened` is called — so a stage split
     * that moved that call ahead of the first publish would flip the captured value to true instead of
     * merely changing timing this suite already tolerates.
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
     * Pins `ReaderViewModel.publishRest`'s own re-check: with [DocumentRepository.markDocumentOpened]
     * gated open, the pane's reload runs on its own coroutine (see [ReaderViewModel.updatePageBreaker])
     * and is not waiting on that gate — it measures a viewport the initial guess could not have
     * predicted and repaginates before the gate is released. (The reload must actually have produced
     * a different pagination from `openDocument`'s guess against `DefaultViewportSize`, or this test
     * would not be exercising the race at all.) Once the gate releases and the second publish
     * (outline, favourite, saved-place flags) lands, it must not put the pre-reload, estimated
     * pagination back over the reload's own result.
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
     * The reader must never see `pageIndex.total == 0` once phase 0/1 of a progressive EPUB import
     * has committed even one section — total only reaches zero for a document nothing knows
     * anything about yet, not a partially-imported one. `importNextSectionsGate` is gated so the
     * background continuation's first `importNextSections` call parks instead of resolving
     * instantly — otherwise `advanceUntilIdle()` drains it in the same pass as the first publish and
     * there is no "still incomplete" moment left to observe.
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
     * Baseline sanity check for `ReaderViewModel.continueImportIfIncomplete`: an incomplete import
     * must start its background continuation (at least one `importNextSections` call), and once
     * that continuation reports done, the reader must publish its completion-only reload and report
     * pagination complete when nothing else is left to measure.
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
     * openDocument's own getPageWindows call passes viewportSize=null whenever no pane has reported a
     * size yet (see its own doc: "Passing null lets getPageWindows resolve the newest layout ever stored
     * for this exact style"), so a restored layout's page count is exactly what totalPages reflects for
     * that very first frame — pageWindows.isNotEmpty() always wins in the totalPages calculation, ruling
     * out metadata.pageCount or progress.pageIndex.total as the source once any page list comes back.
     * But updatePageBreaker's own report, moments later, is only deduplicated against an *exact* size
     * match (see that function's own doc) — a real device's pane can report a viewport that differs from
     * whatever was resolved above by no more than rounding, and that alone is enough to start a second,
     * full getPageWindows call whose result silently replaces the first. The total the reader ends up
     * seeing is therefore whichever getPageWindows call happens to run last, not necessarily the one
     * that restored the stored layout.
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
     * A page turn into a section outside the current mount window must still show styled blocks because
     * [ReaderViewModel.moveToPage] warms its target before publishing. The pane-report reload is frozen
     * at warm call 1 after open's call 0, leaving only the move's own later warm able to prepare page 7.
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
     * The settled-state half of the import/render regression: even though intermediate
     * [DocumentRepository.importNextSections] batches now keep the active prefix cache alive, the
     * completion path still rebuilds the final snapshot and reloads from it, so the page already on
     * screen must still come back with its blocks intact after that reload. The remedy is not a fill
     * restarted after the fact: reloadPages itself now warms the mount window it is about to publish
     * from (see that function's own doc), so this test pins the settled state — the page already on
     * screen must show its blocks once the completion reload has run, not sit unstyled until the
     * reader closes and reopens the book. It says nothing about what happens in between one publish
     * and the next — see [importCompletionReloadNeverPublishesThePageWithoutItsBlocks] for that.
     * `importNextSectionsGate` is gated the same way
     * `openDocumentNeverPublishesZeroTotalPagesForAnIncompleteImport` parks the background
     * continuation, so the sanity check right after open observes the state strictly before the
     * completion reload, not whatever `advanceUntilIdle()` happens to settle both into. The assertion
     * right after that open is only a sanity check that `lazySectionBlocks` is wired correctly —
     * `openDocument`'s own mount-window warm has already reached the only page there is at that point
     * — not the regression this test targets.
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
     * The defect this whole fix is for is transient, not a settled-state failure: the import
     * completion reload can rebuild from a fresh final snapshot whose needed section blocks are not
     * decoded yet, and without the warm-before-publish guard that reload would emit the page already
     * on screen with an empty blocks list before a later publish corrected it. The settled-state
     * assertion above cannot see that — it only reads uiState.value after advanceUntilIdle() drained
     * everything. This test collects every intermediate emission with an
     * UnconfinedTestDispatcher collector (uiState is a conflating StateFlow, so only an eager
     * collector observes each one) and asserts that not one of them ever shows the page currently on
     * screen with an empty blocks list.
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
     * A page whose section became known during progressive import must show that section's title after
     * [ReaderViewModel.reloadPages] re-reads the grown section list, not a title inherited from the
     * open-time prefix.
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
     * Pins F3: DocumentRepositoryImpl.invalidateDocumentCache nulls the pagination session an
     * import batch's own continuation was mid-walk through, so continuePagination answers
     * isComplete=true with sectionsMeasured=0 — a "the walk has nothing left to say" signal, not
     * "the book is done." continuePaginationIfIncomplete must not publish isPaginationComplete=true
     * from that alone while the import itself is still running.
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
     * Pins the untitled-section inheritance [PaginatedDocument.chapterTitleAt]'s KDoc describes: a
     * section with no title of its own is not "untitled" for chapter-title purposes, it inherits the
     * title of the last titled section at or before it. A naive `sectionContaining(start)?.title`
     * collapse — dropping the "titled sections only" filter before picking the latest-starting one —
     * would answer null the moment a page's own section has no title, which this test would catch and
     * [epubChapterTitlePersistsAcrossEveryPageOfTheSameChapter] would not, since every section in that
     * test already carries a title.
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
     * Pins invariant 24: a reload's background section-warm request must be derived from that
     * reload's own page/section pair, never from whatever a differently-timed, concurrently-running
     * reload has since written into the shared `paginated` field (see `ReaderViewModel.warmMountWindow`'s
     * own doc — "the warm has to touch the same list its own publish will read from"). Constructed by
     * freezing the viewport-triggered reload's own warm call with
     * [FakeDocumentRepository.freezeWarmSectionBlocksAtCallIndex] while an import batch's reload runs
     * to completion underneath it and grows the section list, then reading the frozen call's own
     * recorded argument — captured before the freeze — once it is released with
     * [FakeDocumentRepository.unfreezeWarmSectionBlocks].
     *
     * The section split below is chosen so the two reloads actually disagree: before the import batch,
     * only section 0 exists, so every page in the mount window resolves to it; after the batch, a
     * second section starting exactly where page 1 starts takes over that page, so the same mount
     * window resolves to sections {0, 1}. If the viewport reload's own warm ever read the live field
     * instead of its own local pair, its frozen call would have been recorded against whichever
     * pair happened to be current on that path — this test's expectation only holds if it saw its
     * own, pre-import pair. `freezeWarmSectionBlocksAtCallIndex = 1` targets exactly the viewport
     * reload's own warm: call 0 is `openDocument`'s own pre-publish warm, so call 1 is the
     * viewport-triggered reload's own warm below.
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
     * The favourite toggle publishes optimistically, so a failed write has to put the flag back.
     *
     * Without the rollback the star stays lit for a document whose row is gone, and the next open shows it
     * unlit again — the reader sees the app disagree with itself.
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
     * A saved place's id is the document id and the position's storage string, joined by a colon.
     *
     * That format is what makes saving the same page twice replace one row instead of adding another, and it is
     * produced in exactly one place; pinning it here keeps a future refactor from switching to a generated id
     * and silently turning the toggle into an append.
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
     * Assembles a [ReaderViewModel] wired to [documentRepository] and, unless a test needs to
     * inspect them, throwaway defaults for the other three collaborators — so a test that only cares
     * about the document repository does not have to repeat the other three constructor arguments
     * itself.
     *
     * @param documentRepository the fake this view model reads and writes documents through.
     * @param bookmarkRepository the fake saved-place store; defaults to a fresh, empty one.
     * @param readerSettingsRepository the fake settings store; defaults to one holding default
     *   [ReaderSettings].
     * @return a [ReaderViewModel] ready for a test to call `openDocument` on.
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
 * Stands in for the pane reporting its real size now that `updatePageBreaker` is the only entry
 * point for a measurement. [FakeDocumentRepository.getPageWindows] ignores the breaker itself —
 * only the [ViewportSize] drives its own pagination — so this breaker never needs to measure
 * anything real; it exists only to satisfy [ReaderViewModel.updatePageBreaker]'s signature.
 */
private val FakePageBreaker = ReaderPageBreaker { _, _ -> IntArray(0) }

/**
 * Simulates the reader pane reporting a measured size of [width] by [height], the same call
 * [ReaderViewModel.updatePageBreaker] would receive from a real composition. Both the sp and px
 * arguments are given the same [ViewportSize], since [FakeDocumentRepository] only ever reads the
 * size, not which of the two units it arrived as.
 */
private fun ReaderViewModel.reportMeasuredViewport(width: Int, height: Int) {
    val size = ViewportSize(widthPx = width, heightPx = height)
    updatePageBreaker(uiState.value.style, size, size, FakePageBreaker)
}

/**
 * A test double for [DocumentRepository] used across this suite. Beyond the obvious happy-path
 * fields, several parameters exist to model one specific corner of the real repository's behavior
 * that [ReaderViewModel] has to survive: a progressive EPUB import ([importComplete],
 * [importNextSectionsGate], [sectionsAppendedOnImport], [progressiveImportBatches],
 * [importBatchGates]), a progressive pagination pass decoupled from that import
 * ([progressivePagination]), a pagination session invalidated mid-walk
 * ([paginationSessionAlwaysInvalidated]), a stored layout's viewport being adopted
 * ([storedViewportSize]), an on-demand block decode ([lazySectionBlocks]), a background warm
 * call frozen mid-fill ([freezeWarmSectionBlocksAtCallIndex]), and a second document opened while
 * this one's own [getPageWindows] call is still resolving ([freezeGetPageWindowsAtCallIndex],
 * [secondDocumentId], [secondPageWindows]).
 *
 * @property documentId the document this fake answers for; every method that takes a `documentId`
 *   argument checks it against this and answers as if for a different, unknown document otherwise,
 *   except the two-entry lookup [secondDocumentId] adds to [getPageWindows].
 * @property format the document format every stored fact below is described in terms of.
 * @param pageCount the document's own stored page count, used only to seed the fake's [metadata]
 *   `pageCount` field — the count [getPageWindows] actually paginates to is controlled separately,
 *   by [pageWindows]/[paginatedText]/[progressivePagination].
 * @property paginatedText raw text [getPageWindows] paginates into fixed-size windows sized off the
 *   requested viewport, when neither [pageWindows] nor [progressivePagination] answers first.
 * @property visualPageImages the decoded page images [getVisualPageImages] answers with, for a CBZ
 *   document.
 * @property embeddedImages the decoded embedded images [getEmbeddedImages] answers with, for an
 *   EPUB document.
 * @property throwOnGetEmbeddedImagesCall when set, makes that numbered [getEmbeddedImages] call
 *   throw before answering, so a test can model one transient preload failure and the retry after it.
 * @property readerDocument the parsed document [getReaderDocument] answers with; when null, a
 *   minimal placeholder is built from [format]/the fake's own metadata instead.
 * @property pageWindows a fixed page list [getPageWindows] answers with directly (optionally
 *   filtered through [lazySectionBlocks]), when set.
 * @property freezeGetPageWindowsAtCallIndex when set, parks the (0-indexed) [getPageWindows] call at
 *   this index — counted across every document this fake answers for, [documentId] and
 *   [secondDocumentId] alike — on a raw [suspendCoroutine] until [unfreezeGetPageWindows] resumes it.
 *   A [CompletableDeferred] gate would not do: `await()` is a cancellable suspension point, and
 *   `ReaderViewModel.openDocument` cancels the previous document's job synchronously the moment a new
 *   one opens, which would resume a parked call with a [kotlinx.coroutines.CancellationException]
 *   instead of letting it resolve — exactly the case this exists to model, since `Job.cancel()`
 *   cannot actually stop a database read already in flight (see [ReaderViewModel]'s own class doc).
 * @property secondDocumentId a second document id [getPageWindows] answers for with
 *   [secondPageWindows], distinct from [documentId] — lets a test drive two documents through one
 *   fake instance at once, e.g. to open a second document while the first's own [getPageWindows]
 *   call is still parked on [freezeGetPageWindowsAtCallIndex]. Every other method keeps answering
 *   [secondDocumentId] the same "unknown document" way it always has — [getDocument]/[getReaderDocument]
 *   null, [isImportComplete]/[isPaginationComplete] true — which is harmless here: no test in this
 *   suite reads a title, metadata, or import state for [secondDocumentId], only its own pagination.
 * @property secondPageWindows the page list [getPageWindows] answers with for [secondDocumentId] —
 *   real, distinguishable content, unlike the empty list an ordinary unknown document gets, since the
 *   whole point of [secondDocumentId] is to be observably paginated on its own.
 * @property markDocumentOpenedGate suspends [markDocumentOpened] until completed, so a test can
 *   observe the state strictly between the first publish and the second (see e.g.
 *   `openDocumentShowsTheFirstPageBeforeMarkDocumentOpenedCompletesAndDoesNotMoveItAfterward`).
 * @property onMarkDocumentOpened invoked at the moment [markDocumentOpened] is called, before it
 *   awaits its own gate or writes anything — lets a test read live ViewModel state (e.g.
 *   `uiState.value.isLoading`) at that exact instant instead of inferring the ordering from a
 *   suspended gate plus `advanceUntilIdle()`.
 * @property storedViewportSize the viewport a previously-read book's stored layout was measured at
 *   — what [resolveViewportSizeForStyle] resolves for `openDocument` to adopt when it asks with no
 *   pane size reported yet. Only meaningful together with [pageWindows], which stands in for that
 *   stored layout's own pages.
 * @property importComplete false stands in for a progressively-imported EPUB whose background
 *   import hasn't finished yet — see `continueImportIfIncomplete`. Unless [importNextSectionsGate]
 *   holds it open, every call to [importNextSections] below reports done immediately, so a test
 *   that just sets this false is exercising exactly one round of the continuation loop.
 * @property importNextSectionsGate suspends [importNextSections] until completed, the same idea as
 *   [markDocumentOpenedGate] above — lets a test observe the state between the first publish and
 *   the background continuation's first call landing, instead of `advanceUntilIdle()` draining
 *   both in one pass.
 * @property lazySectionBlocks when set, activates a section-aware page list: a page's blocks read
 *   empty until [warmSectionBlocks] has been asked for the section owning it (found the same way
 *   `ReaderViewModel.sectionContaining` does), then flip to this map's answer for that section — the
 *   on-demand decode `SectionBlocksCache` gives the real repository, needed to prove the view model
 *   warms a page before building it instead of a test baking blocks straight into [pageWindows].
 * @property freezeWarmSectionBlocksAtCallIndex when set, freezes the (0-indexed) [warmSectionBlocks]
 *   call at this index until [unfreezeWarmSectionBlocks], allowing deterministic warm/reload races.
 * @property sectionsAppendedOnImport sections the fake's first real [importNextSections] call
 *   appends to what [getReaderDocument] answers — standing in for a progressive import committing a
 *   later chapter while the already-live cache stays usable, with only a later full reload rebuilding
 *   the snapshot the reader publishes from.
 * @property progressiveImportBatches models a multi-batch progressive EPUB import instead of the
 *   single all-at-once batch [sectionsAppendedOnImport] models above: each [importNextSections]
 *   call consumes one entry, appending it to the fake's live section list, and [isImportComplete]
 *   only turns true once every entry is gone — mirroring `documents.importCompletedAtEpochMillis`
 *   only becoming non-null on the real last batch.
 * @property importBatchGates one optional gate per [progressiveImportBatches] entry, awaited before
 *   that batch is consumed. This fake has no real suspension between batches on its own, so without
 *   a gate here the import loop races straight through every batch in one dispatcher pass and never
 *   gives a concurrently-launched pagination continuation a chance to run before the whole import
 *   is already done — exactly the ordering a real, I/O-bound import batch would leave room for.
 * @property progressivePagination models a section-by-section pagination continuation instead of
 *   the fixed [pageWindows]/[paginatedText] answers above: [getPageWindows] returns one page per
 *   section actually "measured" so far, and only [continuePagination] (via
 *   `continuePaginationIfIncomplete`) ever grows that count — decoupled from how many sections
 *   [importNextSections] has appended, the same split `DocumentRepositoryImpl`'s own pagination
 *   session keeps between "known" and "measured."
 * @property paginationSessionAlwaysInvalidated models a pagination continuation whose session was
 *   invalidated mid-walk (see `DocumentRepositoryImpl.invalidateDocumentCache` /
 *   [continuePagination]): every call reports "isComplete" with nothing actually measured, while
 *   [isPaginationComplete] itself never turns true on its own — the "lying complete" signal
 *   `continuePaginationIfIncomplete` must not trust while the import is still running.
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
    /** Every section index [warmSectionBlocks] has recorded as warmed so far. */
    private val warmedSections = linkedSetOf<Int>()

    /**
     * Whether a warm issued right now would actually record anything.
     *
     * `DocumentRepositoryImpl.warmSectionBlocks` warms the cache object the repository is holding
     * right now and returns 0 when there is none (see its own `?: return 0`); this fake only flips
     * that off for the explicit full-drop paths it models, and only a later `getReaderDocument`/
     * `getPageWindows` builds a new one. Modelling that is what makes a warm issued between the two a
     * no-op here too, rather than one that silently still records the sections it was asked for.
     */
    private var sectionBlocksCacheAlive = true

    /**
     * The section list [getReaderDocument]/[getPageWindows] answer with right now — starts at
     * whatever [readerDocument] was given and grows the one time [importNextSections] below finds
     * pending sections to append, the same way `DocumentRepositoryImpl`'s own stored section list
     * grows mid-import.
     */
    private var liveSections: List<ReaderSection> = readerDocument?.sections.orEmpty()

    /**
     * Sections still waiting to be appended by the next [importNextSections] call, drawn from
     * [sectionsAppendedOnImport].
     */
    private var pendingImportSections: List<ReaderSection> = sectionsAppendedOnImport

    /** Entries of [progressiveImportBatches] not yet consumed by [importNextSections]. */
    private val pendingProgressiveBatches = progressiveImportBatches.toMutableList()

    /** How many entries of [progressiveImportBatches]/[importBatchGates] have been consumed so far. */
    private var progressiveBatchIndex = 0

    /**
     * How many of [liveSections] a real page breaker has actually measured so far, when
     * [progressivePagination] is on — starts at 1 for the same reason `DocumentRepositoryImpl`'s own
     * first `getPageWindows` call measures only the section the reader resumed into (see its own
     * doc).
     */
    private var measuredSectionCount = 1
    private var importInFlight = false
    private var paginationInFlight = false

    var importPaginationOverlapDetected = false
        private set

    var continuePaginationCallCount = 0
        private set

    /**
     * Every argument [warmSectionBlocks] was called with, in call order — read by a test that needs
     * to inspect a specific call's own recorded sections (e.g. after freezing it with
     * [freezeWarmSectionBlocksAtCallIndex]).
     */
    val warmSectionBlocksCalls = mutableListOf<Set<Int>>()

    /**
     * How many times [warmSectionBlocks] has been called so far; used to recognise the call index
     * [freezeWarmSectionBlocksAtCallIndex] names.
     */
    private var warmSectionBlocksCallCount = 0

    /** The gate a frozen [warmSectionBlocks] call awaits; completed by [unfreezeWarmSectionBlocks]. */
    private val warmSectionBlocksFreezeGate = CompletableDeferred<Unit>()

    /** Snapshot of every section warmed so far — a defensive copy, since [warmedSections] keeps changing. */
    fun warmedSectionsSnapshot(): Set<Int> = warmedSections.toSet()

    /** Releases a frozen warm — call at the end of a test that used
     * [freezeWarmSectionBlocksAtCallIndex], so nothing is left suspended when the test ends. */
    fun unfreezeWarmSectionBlocks() {
        warmSectionBlocksFreezeGate.complete(Unit)
    }

    /**
     * Models the on-demand decode `DocumentRepositoryImpl.warmSectionBlocks` performs: records
     * [sectionIndexes] into [warmedSections] and answers how many of them were newly warmed,
     * honouring [sectionBlocksCacheAlive] (answers 0 while the cache is modelled as dropped) and
     * pausing forever on [warmSectionBlocksFreezeGate] at the call index
     * [freezeWarmSectionBlocksAtCallIndex] names, so a test can observe an in-flight warm.
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
     * Models the library row having been deleted while the reader still holds the document open — the state
     * `toggleFavorite`'s rollback exists for.
     */
    var documentRowMissing = false

    /**
     * This document's mutable stored metadata row; overwritten by [upsertDocument] and answered by
     * [getDocument] unless [documentRowMissing].
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

    /** How many times [getDocument] has been called. */
    var getDocumentCallCount = 0
        private set

    /**
     * The favourite flag [metadata] currently holds, read directly by a test instead of going
     * through the view model.
     */
    val isFavorite: Boolean get() = metadata.isBookmarked

    /**
     * The document id [markDocumentOpened] most recently recorded, read by a test to confirm the
     * open was written.
     */
    var lastOpenedDocumentId: DocumentId? = null

    /** The timestamp [markDocumentOpened] most recently recorded. */
    var lastOpenedAtEpochMillis: Long = 0L

    /**
     * Answers a single-element list holding [metadata]; no test in this suite drives more than one
     * document through this fake at once.
     */
    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = flowOf(listOf(metadata))

    /**
     * Answers [metadata] for [documentId], or null when [documentRowMissing] models the row having
     * been deleted.
     */
    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        if (documentRowMissing) {
            null
        } else {
            getDocumentCallCount += 1
            metadata.takeIf { it.id == documentId }
        }

    /**
     * Answers [readerDocument] (or a minimal placeholder built from [format]/[metadata] when none
     * was given), always re-paired with the fake's current [liveSections] so a caller sees whatever
     * a progressive import has appended so far. Also marks [sectionBlocksCacheAlive] alive again,
     * mirroring `DocumentRepositoryImpl` rebuilding its decoded-block cache the moment a document's
     * structure is read.
     *
     * Withholds [readerDocument]'s own navigation as an empty [ReaderNavigation] while a modelled
     * single-batch import ([sectionsAppendedOnImport]) or multi-batch import
     * ([progressiveImportBatches]) has not finished consuming its batch(es) yet — the same way a real
     * progressive EPUB import leaves `ReaderDocument.navigation` empty until
     * `DocumentRepositoryImpl.importEpubPhase0`/`finishEpubImport` resolves it on the batch that
     * completes the book. [importComplete] alone does not gate this, per `ReaderViewModel.refreshPaginationCompleteness`'s
     * own doc: a test double models import completion through [ImportProgress.isComplete], not a
     * separate flag promised to agree with it.
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

    /** Answers whichever of [visualPageImages] were asked for. */
    override suspend fun getVisualPageImages(
        documentId: DocumentId,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> {
        val callIndex = visualPageImageRequests++
        if (callIndex == throwOnGetVisualPageImagesCall) error("visual page fetch failed")
        return visualPageImages.filterKeys(pageIndexes::contains)
    }

    /** Answers whichever of [embeddedImages] were asked for. */
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

    /** The same whole-document scan the production repository answers with, over this fake's windows. */
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
     * How many times [getPageWindows] has been called; read by a test asserting a reload did or
     * did not happen.
     */
    var pageWindowRequests = 0
        private set

    /** Whether the most recent [getPageWindows] call for each document arrived with a non-null breaker. */
    val lastPageBreakerByDocumentId = mutableMapOf<DocumentId, Boolean>()

    /**
     * How many times [getPageWindows] has been called so far, counted across every document this
     * fake answers for — the same role [warmSectionBlocksCallCount] plays for [warmSectionBlocks] —
     * used to recognise the call index [freezeGetPageWindowsAtCallIndex] names.
     */
    private var getPageWindowsCallCount = 0

    /** How many times [getVisualPageImages] has been called so far. */
    private var visualPageImageRequests = 0

    /** How many times [getEmbeddedImages] has been called so far. */
    private var embeddedImageRequests = 0

    /** How many times [getEmbeddedFontFiles] has been called so far. */
    private var embeddedFontFileRequests = 0

    /**
     * The raw continuation a [getPageWindows] call frozen by [freezeGetPageWindowsAtCallIndex] is
     * parked on, captured through [suspendCoroutine] rather than a [CompletableDeferred]. Unlike
     * [CompletableDeferred.await], which is a cancellable suspension point, [suspendCoroutine] "does
     * not support prompt cancellation" by its own contract — resuming this through
     * [unfreezeGetPageWindows] always delivers the parked call back into ordinary code, even though
     * `ReaderViewModel.openDocument` may have already cancelled the job that started it, faithfully
     * modelling a real database read a cancelled `Job` cannot retract (see [ReaderViewModel]'s own
     * class doc). Null whenever no call is currently frozen.
     */
    private var frozenGetPageWindowsContinuation: Continuation<Unit>? = null

    /**
     * Resumes the [getPageWindows] call parked by [freezeGetPageWindowsAtCallIndex], so a test can
     * release it — typically after driving a second document's own open to completion underneath it.
     * A no-op if no call is currently frozen.
     */
    fun unfreezeGetPageWindows() {
        frozenGetPageWindowsContinuation?.resume(Unit)
    }

    /**
     * Answers [storedViewportSize] — the viewport a previously stored layout for this document was
     * measured at, or null when there is none.
     */
    override suspend fun resolveViewportSizeForStyle(documentId: DocumentId, style: ReaderStyle): ViewportSize? =
        storedViewportSize.takeIf { documentId == this.documentId }

    /**
     * The fake's own answer chain for a pagination request. The call is first counted and, if its
     * index matches [freezeGetPageWindowsAtCallIndex], parked non-cancellably until
     * [unfreezeGetPageWindows] resumes it (see [frozenGetPageWindowsContinuation]'s own doc for why).
     * The answer itself is then tried in this order: [secondDocumentId] answers [secondPageWindows]
     * directly, modelling a second, concurrently open document with its own real pagination rather
     * than the "unknown document" empty answer every other id gets; failing that, an unknown document
     * or a PDF answers empty (a PDF has no text pagination); [progressivePagination] answers only as
     * many pages as [measuredSectionCount] has measured so far; a fixed [pageWindows] answers
     * directly, optionally filtered through [lazySectionBlocks] to model on-demand block decode;
     * [paginatedText] is split into fixed-size windows by [paginate]; and failing all of those, a
     * fixed two-page stub answers. Also increments [pageWindowRequests] and marks
     * [sectionBlocksCacheAlive] alive again on every call, mirroring the real repository
     * re-measuring blocks fresh each time it lays a document out.
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
     * Splits [text] into fixed-size windows sized off [viewportSize]'s width, standing in for the
     * real pagination engine when a test only needs some non-trivial page count rather than exact
     * measurement.
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
     * Not used by any test in this suite; importing a brand-new document is out of scope for
     * [ReaderViewModel]'s own tests.
     */
    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument = error("not used")

    /**
     * Overwrites [metadata] with [document] — what `ReaderViewModel.toggleFavorite`'s write is
     * checked against.
     */
    override suspend fun upsertDocument(document: DocumentMetadata) {
        metadata = document
    }

    /**
     * Records the open through [onMarkDocumentOpened], then [markDocumentOpenedGate] if one is
     * set, before writing [lastOpenedDocumentId]/[lastOpenedAtEpochMillis].
     */
    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) {
        onMarkDocumentOpened()
        markDocumentOpenedGate?.await()
        lastOpenedDocumentId = documentId
        lastOpenedAtEpochMillis = openedAtEpochMillis
    }

    /** Not used by any test in this suite. */
    override suspend fun deleteDocument(documentId: DocumentId) = Unit

    /**
     * Answers [importComplete] for the fixed single-batch model, or whether every entry of
     * [progressiveImportBatches] has been consumed for the multi-batch model.
     */
    override suspend fun isImportComplete(documentId: DocumentId): Boolean = when {
        documentId != this.documentId -> true
        progressiveImportBatches.isNotEmpty() -> pendingProgressiveBatches.isEmpty()
        else -> importComplete
    }

    /**
     * How many times [importNextSections] has been called; read by a test confirming the
     * background import continuation actually started.
     */
    var importNextSectionsCallCount = 0
        private set

    /**
     * Models one step of a progressive EPUB import: waits on [importNextSectionsGate] if set, then
     * either consumes the next entry of [progressiveImportBatches] (waiting on its own
     * [importBatchGates] entry first) or appends [sectionsAppendedOnImport] in one shot, while
     * leaving the already-live warmed sections/cache alone until a completion reload replaces the
     * pagination — mirroring the real repository's "append in place, invalidate once on finish"
     * import path.
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
     * Models one step of [progressivePagination]'s section-by-section measurement, or the "lying
     * complete" signal [paginationSessionAlwaysInvalidated] stands in for. Overriding this to
     * answer anything but the interface's own default matters only when one of those two flags is
     * set: `DocumentRepository`'s own default implementation (`isComplete = true,
     * sectionsMeasured = 0`) already applies whenever neither is, which is exactly why no test in
     * this suite that leaves both flags at their default ever actually starts
     * `ReaderViewModel.continuePaginationIfIncomplete` — that continuation only starts once
     * [isPaginationComplete] answers false, and the interface default never does.
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
     * Answers false when [paginationSessionAlwaysInvalidated] models a session invalidated
     * mid-walk, true when [progressivePagination] is off (nothing to continue), or otherwise
     * whether [measuredSectionCount] has caught up with [liveSections].
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
 * Stands in for [DocumentRepositoryImpl]'s on-demand block decoding (see SectionBlocksCache): a
 * page's blocks read as empty until [warmedSections] reports its owning section as warmed, then flip
 * to [blocksBySection]'s answer for that section — without [FakeDocumentRepository.getPageWindows]
 * ever being asked again, the same way a real restored page list rebuilds a page in place once its
 * section's blocks arrive.
 *
 * @property pages the underlying page windows, without their blocks.
 * @property sections the section list a page's start offset is matched against to find its owning
 *   section.
 * @property warmedSections a live snapshot of which section indexes have been warmed so far;
 *   queried fresh on every [get], not captured once, so a page already read once can still pick up
 *   blocks warmed afterward.
 * @property blocksBySection every section's own decoded blocks, keyed by section index.
 */
private class LazyBlockPageWindows(
    private val pages: List<PageWindow>,
    private val sections: List<ReaderSection>,
    private val warmedSections: () -> Set<Int>,
    private val blocksBySection: Map<Int, List<ReaderBlock>>,
) : AbstractList<PageWindow>() {
    /** The number of pages in [pages], unaffected by which sections are warmed. */
    override val size: Int get() = pages.size

    /**
     * [pages]'s window at [index], with its blocks read from [blocksBySection] once its section
     * is warmed, empty otherwise.
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
 * A single-document test double for [ReaderRepository]: [progress] holds the one reading-progress
 * row this fake knows about, ignoring [DocumentId] entirely, since no test in this suite exercises
 * more than one document's progress through the same instance.
 */
private class FakeReaderRepository : ReaderRepository {
    /**
     * The stored reading progress a test seeds before opening a document, or reads after
     * [saveProgress] runs.
     */
    var progress: ReadingProgress? = null

    /**
     * Answers a single-value flow snapshotting [progress] at subscription time; not updated on
     * later writes.
     */
    override fun observeProgress(documentId: DocumentId): Flow<ReadingProgress?> = MutableStateFlow(progress)

    /** Answers [progress] as-is. */
    override suspend fun getProgress(documentId: DocumentId): ReadingProgress? = progress

    /**
     * Overwrites [progress] with [progress] (the parameter) — what [ReaderViewModel]'s own
     * progress writes are checked against.
     */
    override suspend fun saveProgress(progress: ReadingProgress) {
        this.progress = progress
    }

    /** Clears [progress]. Not exercised by any test in this suite. */
    override suspend fun deleteProgress(documentId: DocumentId) {
        progress = null
    }
}

/**
 * Mutable in-memory [ReaderSettingsRepository] used to drive both local writes and external
 * settings-screen emissions into an already-open reader.
 *
 * @param initialSettings the first settings snapshot [settings] emits.
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
 * A test double for [BookmarkRepository] backed by a single in-memory list, shared across every
 * document id — no test in this suite exercises more than one document's bookmarks through the same
 * instance.
 */
private class FakeBookmarkRepository : BookmarkRepository {
    /**
     * The saved places currently held; a [MutableStateFlow] so [observeBookmarks] reflects every
     * write live.
     */
    val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())

    /** Answers [bookmarks] directly, ignoring [documentId]. */
    override fun observeBookmarks(documentId: DocumentId): Flow<List<Bookmark>> = bookmarks

    /** Answers the bookmark in [bookmarks] whose id matches [bookmarkId], or null. */
    override suspend fun getBookmark(bookmarkId: String): Bookmark? = bookmarks.value.firstOrNull { it.id == bookmarkId }

    /**
     * Replaces any existing bookmark sharing [bookmark]'s id, then adds [bookmark] — the same
     * replace-by-id semantics the real store gives a saved place.
     */
    override suspend fun saveBookmark(bookmark: Bookmark) {
        bookmarks.value = bookmarks.value.filterNot { it.id == bookmark.id } + bookmark
    }

    /** Removes the bookmark in [bookmarks] whose id matches [bookmarkId], if any. */
    override suspend fun deleteBookmark(bookmarkId: String) {
        bookmarks.value = bookmarks.value.filterNot { it.id == bookmarkId }
    }
}

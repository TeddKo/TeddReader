package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderNavigation
import com.tedd.teddreader.core.common.model.ReaderNavigationItem
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.domain.repository.BookmarkRepository
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ImportProgress
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReaderSettings
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import com.tedd.teddreader.core.domain.usecase.RestoreReadingProgressUseCase
import com.tedd.teddreader.core.domain.usecase.SaveReadingProgressUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun openDocumentShowsStoredPageText() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId)
        val readerRepository = FakeReaderRepository()
        val viewModel = ReaderViewModel(
            documentRepository = documentRepository,
            bookmarkRepository = FakeBookmarkRepository(),
            readerSettingsRepository = FakeReaderSettingsRepository(),
            restoreReadingProgress = RestoreReadingProgressUseCase(readerRepository),
            saveReadingProgress = SaveReadingProgressUseCase(readerRepository),
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

    @Test
    fun moveToLocationShowsMatchingPage() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val readerRepository = FakeReaderRepository()
        val viewModel = ReaderViewModel(
            documentRepository = FakeDocumentRepository(documentId),
            bookmarkRepository = FakeBookmarkRepository(),
            readerSettingsRepository = FakeReaderSettingsRepository(),
            restoreReadingProgress = RestoreReadingProgressUseCase(readerRepository),
            saveReadingProgress = SaveReadingProgressUseCase(readerRepository),
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
        assertTrue(viewModel.uiState.value.documentPages.isEmpty())
        assertTrue(viewModel.uiState.value.outlineItems.isEmpty())
    }

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
            restoreReadingProgress = RestoreReadingProgressUseCase(readerRepository),
            saveReadingProgress = SaveReadingProgressUseCase(readerRepository),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()

        assertEquals(PageIndex(current = 7, total = 10), viewModel.uiState.value.pageIndex)
    }

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
            restoreReadingProgress = RestoreReadingProgressUseCase(readerRepository),
            saveReadingProgress = SaveReadingProgressUseCase(readerRepository),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertEquals(PageIndex(current = 7, total = 10), viewModel.uiState.value.pageIndex)
    }

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

    @Test
    fun openDocumentKeepsDocumentPagesEmptyWhileProvidingCurrentPageSlots() = runTest(dispatcher) {
        val documentId = DocumentId("doc-large")
        val viewModel = createViewModel(
            FakeDocumentRepository(documentId, paginatedText = "a".repeat(300)),
        )

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.documentPages.isEmpty())
        assertTrue(viewModel.uiState.value.pageSlots.isNotEmpty())
        assertEquals("a".repeat(30), viewModel.uiState.value.currentPage.text)
    }

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

        // Pinned in the top bar for the whole chapter, not only its first page.
        assertEquals("2 - 1화 기회 (1)", viewModel.uiState.value.currentPage.chapterTitle)
        assertEquals("다음 페이지", viewModel.uiState.value.currentPage.text)
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
            restoreReadingProgress = RestoreReadingProgressUseCase(readerRepository),
            saveReadingProgress = SaveReadingProgressUseCase(readerRepository),
        )
        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()
        val laidOutBefore = documentRepository.pageWindowRequests

        viewModel.updateThemeMode(ReaderThemeMode.DARK)
        advanceUntilIdle()

        // Symmetric with the font-size test below: the pane still holds a breaker matching the
        // (unchanged) layoutKey, so reporting the same measurement again must dedupe in
        // updatePageBreaker and never reach reloadPages, unlike a real type change.
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()

        assertEquals(laidOutBefore, documentRepository.pageWindowRequests)
    }

    @Test
    fun changingTheFontSizeStillLaysTheBookOutAgain() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(300))
        val readerRepository = FakeReaderRepository()
        val viewModel = ReaderViewModel(
            documentRepository = documentRepository,
            bookmarkRepository = FakeBookmarkRepository(),
            readerSettingsRepository = FakeReaderSettingsRepository(),
            restoreReadingProgress = RestoreReadingProgressUseCase(readerRepository),
            saveReadingProgress = SaveReadingProgressUseCase(readerRepository),
        )
        viewModel.openDocument(documentId.value)
        advanceUntilIdle()
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()
        val laidOutBefore = documentRepository.pageWindowRequests

        viewModel.updateFontSize(24f)
        advanceUntilIdle()

        // A breaker measured for the old style can't safely reload the new one (see reloadPages'
        // guard), so nothing has queried getPageWindows yet — only once the pane remeasures under the
        // new font and reports a matching breaker does the reload actually run.
        viewModel.reportMeasuredViewport(width = 300, height = 600)
        advanceUntilIdle()

        assertTrue(documentRepository.pageWindowRequests > laidOutBefore)
    }

    @Test
    fun repaginationKeepsCurrentReadingOffset() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(300))
        val readerRepository = FakeReaderRepository()
        val viewModel = ReaderViewModel(
            documentRepository = documentRepository,
            bookmarkRepository = FakeBookmarkRepository(),
            readerSettingsRepository = FakeReaderSettingsRepository(),
            restoreReadingProgress = RestoreReadingProgressUseCase(readerRepository),
            saveReadingProgress = SaveReadingProgressUseCase(readerRepository),
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

        // A font or line-height change repaginates the document shorter under the reader.
        viewModel.reportMeasuredViewport(width = 600, height = 900)
        advanceUntilIdle()
        val afterRepagination = viewModel.uiState.value.pageIndex
        assertEquals(PageIndex(current = 3, total = 5), afterRepagination)

        // A two-pane step that overruns the new document is dropped, not clamped onto the last page.
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

    @Test
    fun enablingAutoScrollHidesReaderControls() = runTest(dispatcher) {
        val viewModel = createViewModel(
            documentRepository = FakeDocumentRepository(DocumentId("doc-1")),
        )

        viewModel.updateAutoScrollEnabled(true)

        assertTrue(viewModel.uiState.value.autoScrollConfig.enabled)
        assertFalse(viewModel.uiState.value.isControlsVisible)
    }

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

    @Test
    fun openDocumentPublishesNonEmptyPagesForAFreshlyImportedDocumentBeforeAnyViewportIsMeasured() = runTest(dispatcher) {
        // Regression guard for f33313b: openDocument must paginate against the default guessed
        // viewport unconditionally. If it instead waited for a real pane measurement, a freshly
        // imported book — no pages, so the pager mounts no slot, so nothing ever measures the pane —
        // would never open.
        val documentId = DocumentId("doc-1")
        val documentRepository = FakeDocumentRepository(documentId, paginatedText = "a".repeat(300))
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pageIndex.total > 0)
        assertTrue(viewModel.uiState.value.currentPage.text.isNotEmpty())
    }

    @Test
    fun oneMeasuredViewportReportTriggersExactlyOneReload() = runTest(dispatcher) {
        // updatePageBreaker is the only trigger left that can launch a reload (updateViewportSize is
        // gone), so one real report settles into exactly one getPageWindows call, and a repeat of the
        // same report — as if the pane's effect replayed mid-composition — is deduped, not doubled.
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

    @Test
    fun openDocumentPublishesTheStoredTotalOnTheFirstPublishForAPreviouslyReadBook() = runTest(dispatcher) {
        // Step 6 regression guard: before this fix, openDocument always paginated against a hardcoded
        // guessed viewport, which almost never matched a stored layout's real one, so the first publish
        // carried a wrong/estimated total corrected only once the pane measured for real. A resolved
        // layout must reach the very first publish instead.
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

    @Test
    fun matchingMeasuredViewportAfterAdoptingAStoredLayoutDoesNotRepaginate() = runTest(dispatcher) {
        // Once openDocument has adopted a stored layout's viewport, the pane's first real report --
        // the same physical screen, so the same sp size — must be recognised by updatePageBreaker's
        // dedupe as already answered, not launch a reload that would only repeat what getPageWindows
        // already cached the answer under.
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

    @Test
    fun changingPageTurnModeOrPageAnimationProducesNoPaginationRequest() = runTest(dispatcher) {
        // Neither field is part of ReaderLayoutKey (see ReaderModels.kt): the text breaks in the same
        // places no matter how pages turn or animate, so changing either must never ask the repository
        // to lay the book out again.
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

    @Test
    fun openDocumentShowsTheFirstPageBeforeMarkDocumentOpenedCompletesAndDoesNotMoveItAfterward() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        val markDocumentOpenedGate = CompletableDeferred<Unit>()
        val documentRepository = FakeDocumentRepository(documentId, markDocumentOpenedGate = markDocumentOpenedGate)
        val viewModel = createViewModel(documentRepository)

        viewModel.openDocument(documentId.value)
        advanceUntilIdle()

        // markDocumentOpened (a database write) is still suspended on the gate, but the first frame —
        // style, total, current page, its text — is already up, because it no longer waits for it.
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("First stored page", viewModel.uiState.value.pageText)
        val pageIndexBeforeSecondPublish = viewModel.uiState.value.pageIndex
        val pageTextBeforeSecondPublish = viewModel.uiState.value.pageText

        markDocumentOpenedGate.complete(Unit)
        advanceUntilIdle()

        // The second publish landed (markDocumentOpened observed completing) without moving the page,
        // its text, or the total that already reached the reader.
        assertEquals(documentId, documentRepository.lastOpenedDocumentId)
        assertEquals(pageIndexBeforeSecondPublish, viewModel.uiState.value.pageIndex)
        assertEquals(pageTextBeforeSecondPublish, viewModel.uiState.value.pageText)
    }

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

        // markDocumentOpened (a database write) is still suspended on the gate, but the pane's reload
        // runs on its own coroutine (see updatePageBreaker) and is not waiting on it. It measures a
        // viewport the estimate could not have guessed and repaginates before the gate is released.
        viewModel.reportMeasuredViewport(width = 600, height = 900)
        advanceUntilIdle()
        val reloadedPageIndex = viewModel.uiState.value.pageIndex
        val reloadedPageText = viewModel.uiState.value.pageText
        val reloadedPageSlots = viewModel.uiState.value.pageSlots
        // The reload must actually have produced a different pagination from openDocument's guess
        // against DefaultViewportSize, or this test would not be exercising the race at all.
        assertEquals(PageIndex(current = 0, total = 5), reloadedPageIndex)
        assertTrue(reloadedPageSlots.isNotEmpty())

        markDocumentOpenedGate.complete(Unit)
        advanceUntilIdle()

        // The second publish (outline, favourite, saved-place flags) landed, but must not have put the
        // pre-reload, estimated pagination back.
        assertEquals(documentId, documentRepository.lastOpenedDocumentId)
        assertEquals(reloadedPageIndex, viewModel.uiState.value.pageIndex)
        assertEquals(reloadedPageText, viewModel.uiState.value.pageText)
        assertEquals(reloadedPageSlots, viewModel.uiState.value.pageSlots)
    }

    @Test
    fun openDocumentNeverPublishesZeroTotalPagesForAnIncompleteImport() = runTest(dispatcher) {
        val documentId = DocumentId("doc-1")
        // Gated so the background continuation's first importNextSections call parks instead of
        // resolving instantly — otherwise advanceUntilIdle() drains it in the same pass as the first
        // publish and there is no "still incomplete" moment left to observe.
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

    private fun createViewModel(
        documentRepository: FakeDocumentRepository,
        bookmarkRepository: FakeBookmarkRepository = FakeBookmarkRepository(),
        readerSettingsRepository: FakeReaderSettingsRepository = FakeReaderSettingsRepository(),
    ): ReaderViewModel {
        val readerRepository = FakeReaderRepository()
        return ReaderViewModel(
            documentRepository = documentRepository,
            bookmarkRepository = bookmarkRepository,
            readerSettingsRepository = readerSettingsRepository,
            restoreReadingProgress = RestoreReadingProgressUseCase(readerRepository),
            saveReadingProgress = SaveReadingProgressUseCase(readerRepository),
        )
    }
}

// Stands in for the pane reporting its real size now that updatePageBreaker is the only entry point
// for a measurement; FakeDocumentRepository.getPageWindows ignores the breaker itself; only the
// ViewportSize drives its own pagination, so width/height double as both the sp and px arguments.
private val FakePageBreaker = ReaderPageBreaker { _, _ -> IntArray(0) }

private fun ReaderViewModel.reportMeasuredViewport(width: Int, height: Int) {
    val size = ViewportSize(widthPx = width, heightPx = height)
    updatePageBreaker(uiState.value.style, size, size, FakePageBreaker)
}

private class FakeDocumentRepository(
    private val documentId: DocumentId,
    private val format: DocumentFormat = DocumentFormat.TXT,
    pageCount: Int = 2,
    private val paginatedText: String? = null,
    private val visualPageImages: Map<Int, ByteArray> = emptyMap(),
    private val embeddedImages: Map<String, ByteArray> = emptyMap(),
    private val readerDocument: ReaderDocument? = null,
    private val pageWindows: List<PageWindow>? = null,
    private val markDocumentOpenedGate: CompletableDeferred<Unit>? = null,
    // The viewport a previously-read book's stored layout was measured at — what
    // resolveViewportSizeForStyle resolves for openDocument to adopt when it asks with no pane size
    // reported yet. Only meaningful together with [pageWindows], which stands in for that stored
    // layout's own pages.
    private val storedViewportSize: ViewportSize? = null,
    // False stands in for a progressively-imported EPUB whose background import hasn't finished yet —
    // see continueImportIfIncomplete. Unless importNextSectionsGate holds it open, every call to
    // importNextSections below reports done immediately, so a test that just sets this false is
    // exercising exactly one round of the continuation loop.
    private val importComplete: Boolean = true,
    // Suspends importNextSections until completed, the same idea as markDocumentOpenedGate above —
    // lets a test observe the state between the first publish and the background continuation's
    // first call landing, instead of advanceUntilIdle() draining both in one pass.
    private val importNextSectionsGate: CompletableDeferred<Unit>? = null,
) : DocumentRepository {
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
        characterCount = 31,
        wordCount = 6,
    )
    val isFavorite: Boolean get() = metadata.isBookmarked
    var lastOpenedDocumentId: DocumentId? = null
    var lastOpenedAtEpochMillis: Long = 0L

    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = flowOf(listOf(metadata))

    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        metadata.takeIf { it.id == documentId }

    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? =
        (readerDocument ?: ReaderDocument(
            id = documentId,
            format = format,
            title = "Stored book",
            sections = emptyList(),
            pageCount = metadata.pageCount ?: 0,
        )).takeIf { documentId == this.documentId }

    override suspend fun getVisualPageImages(
        documentId: DocumentId,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> = visualPageImages.filterKeys(pageIndexes::contains)

    override suspend fun getEmbeddedImages(
        documentId: DocumentId,
        hrefs: Set<String>,
    ): Map<String, ByteArray> = embeddedImages.filterKeys(hrefs::contains)

    var pageWindowRequests = 0
        private set

    override suspend fun resolveViewportSizeForStyle(documentId: DocumentId, style: ReaderStyle): ViewportSize? =
        storedViewportSize.takeIf { documentId == this.documentId }

    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: com.tedd.teddreader.core.common.model.ReaderPageBreaker?,
        anchorOffset: Long?,
    ): List<PageWindow> = run { pageWindowRequests += 1 }.let {
        if (documentId != this.documentId || format == DocumentFormat.PDF) {
        emptyList()
    } else if (pageWindows != null) {
        pageWindows
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

    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument = error("not used")

    override suspend fun upsertDocument(document: DocumentMetadata) {
        metadata = document
    }
    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) {
        markDocumentOpenedGate?.await()
        lastOpenedDocumentId = documentId
        lastOpenedAtEpochMillis = openedAtEpochMillis
    }
    override suspend fun deleteDocument(documentId: DocumentId) = Unit

    override suspend fun isImportComplete(documentId: DocumentId): Boolean =
        documentId != this.documentId || importComplete

    var importNextSectionsCallCount = 0
        private set

    override suspend fun importNextSections(
        documentId: DocumentId,
        count: Int,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
    ): ImportProgress {
        importNextSectionsGate?.await()
        importNextSectionsCallCount += 1
        return ImportProgress(isComplete = true, sectionsImported = 0)
    }
}

private class FakeReaderRepository : ReaderRepository {
    var progress: ReadingProgress? = null

    override fun observeProgress(documentId: DocumentId): Flow<ReadingProgress?> = MutableStateFlow(progress)
    override suspend fun getProgress(documentId: DocumentId): ReadingProgress? = progress
    override suspend fun saveProgress(progress: ReadingProgress) {
        this.progress = progress
    }
    override suspend fun deleteProgress(documentId: DocumentId) {
        progress = null
    }
}

private class FakeReaderSettingsRepository(
    initialSettings: ReaderSettings = ReaderSettings(),
) : ReaderSettingsRepository {
    override val settings: Flow<ReaderSettings> = flowOf(initialSettings)
    var lastAutoScrollConfig: AutoScrollConfig? = null

    override suspend fun updateStyle(style: ReaderStyle) = Unit
    override suspend fun updatePageTurnMode(pageTurnMode: com.tedd.teddreader.core.common.model.PageTurnMode) = Unit
    override suspend fun updatePageAnimation(pageAnimation: com.tedd.teddreader.core.common.model.PageAnimation) = Unit
    override suspend fun updateAutoScrollConfig(autoScrollConfig: AutoScrollConfig) {
        lastAutoScrollConfig = autoScrollConfig
    }
    override suspend fun updateAppLanguage(appLanguage: AppLanguage) = Unit
}

private class FakeBookmarkRepository : BookmarkRepository {
    val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())

    override fun observeBookmarks(documentId: DocumentId): Flow<List<Bookmark>> = bookmarks
    override suspend fun getBookmark(bookmarkId: String): Bookmark? = bookmarks.value.firstOrNull { it.id == bookmarkId }
    override suspend fun saveBookmark(bookmark: Bookmark) {
        bookmarks.value = bookmarks.value.filterNot { it.id == bookmark.id } + bookmark
    }
    override suspend fun deleteBookmark(bookmarkId: String) {
        bookmarks.value = bookmarks.value.filterNot { it.id == bookmarkId }
    }
}

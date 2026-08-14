package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.domain.repository.BookmarkRepository
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReaderSettings
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import com.tedd.teddreader.core.domain.usecase.RestoreReadingProgressUseCase
import com.tedd.teddreader.core.domain.usecase.SaveReadingProgressUseCase
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

        viewModel.moveToLocation(ReaderLocation.TextOffset(18))
        advanceUntilIdle()

        assertEquals("Second stored page", viewModel.uiState.value.pageText)
        assertEquals(PageIndex(current = 1, total = 2), viewModel.uiState.value.pageIndex)
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
        viewModel.updateViewportSize(widthPx = 300, heightPx = 600)
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
        viewModel.updateViewportSize(widthPx = 300, heightPx = 600)
        advanceUntilIdle()
        viewModel.moveToPage(6)
        advanceUntilIdle()

        viewModel.updateViewportSize(widthPx = 600, heightPx = 900)
        advanceUntilIdle()

        assertEquals(PageIndex(current = 3, total = 5), viewModel.uiState.value.pageIndex)
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

private class FakeDocumentRepository(
    private val documentId: DocumentId,
    private val format: DocumentFormat = DocumentFormat.TXT,
    pageCount: Int = 2,
    private val paginatedText: String? = null,
    private val visualPageImages: Map<Int, ByteArray> = emptyMap(),
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
        ReaderDocument(
            id = documentId,
            format = format,
            title = "Stored book",
            sections = emptyList(),
            pageCount = metadata.pageCount ?: 0,
        ).takeIf { documentId == this.documentId }

    override suspend fun getVisualPageImages(
        documentId: DocumentId,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> = visualPageImages.filterKeys(pageIndexes::contains)

    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: com.tedd.teddreader.core.common.model.ReaderPageBreaker?,
    ): List<PageWindow> = if (documentId != this.documentId || format == DocumentFormat.PDF) {
        emptyList()
    } else if (paginatedText != null) {
        paginate(paginatedText, viewportSize)
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
        lastOpenedDocumentId = documentId
        lastOpenedAtEpochMillis = openedAtEpochMillis
    }
    override suspend fun deleteDocument(documentId: DocumentId) = Unit
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

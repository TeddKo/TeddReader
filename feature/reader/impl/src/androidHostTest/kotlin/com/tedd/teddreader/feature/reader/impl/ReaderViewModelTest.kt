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

        assertEquals(0.1f, viewModel.uiState.value.autoScrollConfig.speed)
        assertEquals(0.1f, readerSettingsRepository.lastAutoScrollConfig?.speed)
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
) : DocumentRepository {
    private var metadata = DocumentMetadata(
        id = documentId,
        location = DocumentLocation(
            sourceUri = documentId.value,
            displayName = "Stored book",
            mimeType = "text/plain",
            sizeBytes = 100,
        ),
        format = DocumentFormat.TXT,
        addedAtEpochMillis = 1_000,
        pageCount = 2,
        characterCount = 31,
        wordCount = 6,
    )
    val isFavorite: Boolean get() = metadata.isBookmarked

    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = flowOf(listOf(metadata))

    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        metadata.takeIf { it.id == documentId }

    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? =
        ReaderDocument(
            id = documentId,
            format = DocumentFormat.TXT,
            title = "Stored book",
            sections = emptyList(),
            pageCount = 2,
        ).takeIf { documentId == this.documentId }

    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
    ): List<PageWindow> = if (documentId == this.documentId) {
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
    } else {
        emptyList()
    }

    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument = error("not used")

    override suspend fun upsertDocument(document: DocumentMetadata) {
        metadata = document
    }
    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit
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

private class FakeReaderSettingsRepository : ReaderSettingsRepository {
    override val settings: Flow<ReaderSettings> = flowOf(ReaderSettings())
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

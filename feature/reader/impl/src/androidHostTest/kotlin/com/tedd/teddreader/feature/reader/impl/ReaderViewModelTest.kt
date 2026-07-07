package com.tedd.teddreader.feature.reader.impl

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

}

private class FakeDocumentRepository(
    private val documentId: DocumentId,
) : DocumentRepository {
    private val metadata = DocumentMetadata(
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

    override suspend fun upsertDocument(document: DocumentMetadata) = Unit
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
    override suspend fun updateStyle(style: ReaderStyle) = Unit
    override suspend fun updatePageTurnMode(pageTurnMode: com.tedd.teddreader.core.common.model.PageTurnMode) = Unit
    override suspend fun updatePageAnimation(pageAnimation: com.tedd.teddreader.core.common.model.PageAnimation) = Unit
    override suspend fun updateAutoScrollConfig(autoScrollConfig: com.tedd.teddreader.core.common.model.AutoScrollConfig) = Unit
}

private class FakeBookmarkRepository : BookmarkRepository {
    override fun observeBookmarks(documentId: DocumentId): Flow<List<Bookmark>> = flowOf(emptyList())
    override suspend fun getBookmark(bookmarkId: String): Bookmark? = null
    override suspend fun saveBookmark(bookmark: Bookmark) = Unit
    override suspend fun deleteBookmark(bookmarkId: String) = Unit
}

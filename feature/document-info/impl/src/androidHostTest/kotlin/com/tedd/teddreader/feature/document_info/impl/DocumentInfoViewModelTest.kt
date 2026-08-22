package com.tedd.teddreader.feature.document_info.impl

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReadingStats
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import com.tedd.teddreader.core.domain.repository.ReadingSession
import com.tedd.teddreader.core.domain.repository.ReadingStatsRepository
import com.tedd.teddreader.core.domain.usecase.GetDocumentInfoUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentInfoViewModelTest {
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
    fun staleDocumentInfoResponseDoesNotOverwriteNewerDocument() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val awaitResponse: suspend (DocumentId) -> Unit = { documentId ->
            withContext(NonCancellable) {
                when (documentId.value) {
                    "doc-a" -> firstGate.await()
                    "doc-b" -> secondGate.await()
                }
            }
        }
        val documentRepository = FakeDocumentRepository(beforeGetDocument = awaitResponse)
        val readerRepository = FakeReaderRepository(beforeGetProgress = awaitResponse)
        val statsRepository = FakeReadingStatsRepository(beforeGetStats = awaitResponse)
        val viewModel = DocumentInfoViewModel(
            getDocumentInfo = GetDocumentInfoUseCase(documentRepository, readerRepository, statsRepository),
            readingStatsRepository = statsRepository,
        )

        viewModel.setDocument("doc-a")
        advanceUntilIdle()
        viewModel.setDocument("doc-b")
        advanceUntilIdle()

        secondGate.complete(Unit)
        advanceUntilIdle()
        firstGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("doc-b", viewModel.uiState.value.documentId)
        assertEquals("doc-b.epub", viewModel.uiState.value.metadata?.location?.displayName)
        assertEquals(PageIndex(current = 2, total = 8), viewModel.uiState.value.pageIndex)
        assertEquals(2_000L, viewModel.uiState.value.stats?.activeMillis)
        assertNull(viewModel.uiState.value.errorMessage)
    }
}

private class FakeDocumentRepository(
    private val beforeGetDocument: suspend (DocumentId) -> Unit = {},
) : DocumentRepository {
    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = flowOf(emptyList())

    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? {
        beforeGetDocument(documentId)
        return DocumentMetadata(
            id = documentId,
            location = DocumentLocation(
                sourceUri = "file:///${documentId.value}.epub",
                displayName = "${documentId.value}.epub",
                mimeType = "application/epub+zip",
            ),
            format = DocumentFormat.EPUB,
            addedAtEpochMillis = 0L,
        )
    }

    override suspend fun getReaderDocument(documentId: DocumentId) = null
    override suspend fun getPageWindows(documentId: DocumentId, style: com.tedd.teddreader.core.common.model.ReaderStyle, viewportSize: com.tedd.teddreader.core.common.model.ViewportSize?, pageBreaker: com.tedd.teddreader.core.common.model.ReaderPageBreaker?, anchorOffset: Long?) = emptyList<com.tedd.teddreader.core.common.model.PageWindow>()
    override suspend fun importDocument(source: DocumentImportSource, importedAtEpochMillis: Long) = error("not used")
    override suspend fun upsertDocument(document: DocumentMetadata) = Unit
    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit
    override suspend fun deleteDocument(documentId: DocumentId) = Unit
}

private class FakeReaderRepository(
    private val beforeGetProgress: suspend (DocumentId) -> Unit = {},
) : ReaderRepository {
    override fun observeProgress(documentId: DocumentId): Flow<ReadingProgress?> = flowOf(null)

    override suspend fun getProgress(documentId: DocumentId): ReadingProgress? {
        beforeGetProgress(documentId)
        return ReadingProgress(
            documentId = documentId,
            location = ReaderLocation.TextOffset(0L),
            pageIndex = if (documentId.value == "doc-b") PageIndex(2, 8) else PageIndex(1, 4),
            updatedAtEpochMillis = 0L,
        )
    }

    override suspend fun saveProgress(progress: ReadingProgress) = Unit
    override suspend fun deleteProgress(documentId: DocumentId) = Unit
}

private class FakeReadingStatsRepository(
    private val beforeGetStats: suspend (DocumentId) -> Unit = {},
) : ReadingStatsRepository {
    private val sessionFlows = mutableMapOf<String, MutableStateFlow<List<ReadingSession>>>()

    override fun observeSessions(documentId: DocumentId): Flow<List<ReadingSession>> =
        sessionFlows.getOrPut(documentId.value) { MutableStateFlow(emptyList()) }

    override suspend fun recordSession(session: ReadingSession) = Unit

    override suspend fun getStats(documentId: DocumentId): ReadingStats {
        beforeGetStats(documentId)
        return ReadingStats(
            documentId = documentId,
            activeMillis = if (documentId.value == "doc-b") 2_000L else 1_000L,
            charactersRead = 100L,
            wordsRead = 20L,
        )
    }
}

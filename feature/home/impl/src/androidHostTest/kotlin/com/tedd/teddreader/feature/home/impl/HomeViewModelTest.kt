package com.tedd.teddreader.feature.home.impl

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
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
    fun bookmarkChangeMovesDocumentToFavorites() = runTest {
        val repository = FakeDocumentRepository()
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.setDocumentBookmarked(repository.documentId, true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.favoriteDocuments.single().isBookmarked)
        assertEquals(emptyList(), viewModel.uiState.value.recentDocuments)
    }

    @Test
    fun bookmarkDocumentsMovesAllSelectedDocumentsToFavorites() = runTest {
        val repository = FakeDocumentRepository(includeSecondDocument = true)
        val viewModel = HomeViewModel(repository)
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

    @Test
    fun unbookmarkDocumentsMovesAllSelectedDocumentsToRecents() = runTest {
        val repository = FakeDocumentRepository(
            includeSecondDocument = true,
            secondDocumentFormat = DocumentFormat.PDF,
            initiallyBookmarkedIds = setOf("document-1", "document-2"),
        )
        val viewModel = HomeViewModel(repository)
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

    @Test
    fun bulkImportedPdfCoversRequestAtMostOneCoverAtATime() = runTest {
        val repository = SuspendingCoverDocumentRepository()
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        repository.emitBulkPdfDocuments(count = 3)
        advanceUntilIdle()

        assertEquals(1, repository.maxConcurrentCoverRequests)
    }

    @Test
    fun deleteRemovesRecentDocument() = runTest {
        val repository = FakeDocumentRepository()
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteDocument(repository.documentId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.hasDocuments)
    }

    @Test
    fun deleteDocumentsRemovesAllSelectedRecentDocuments() = runTest {
        val repository = FakeDocumentRepository(includeSecondDocument = true)
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteDocuments(listOf(repository.documentId, repository.secondDocumentId))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.hasDocuments)
    }

    @Test
    fun loadsPdfCoverAndSkipsTxtCoverRequests() = runTest {
        val repository = FakeDocumentRepository(includeSecondDocument = true)
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertContentEquals(listOf(repository.documentId.value), repository.coverRequestIds)
        assertContentEquals(repository.pdfCoverBytes, viewModel.uiState.value.documentCoverImages[repository.documentId.value])
        assertFalse(viewModel.uiState.value.documentCoverImages.containsKey(repository.secondDocumentId.value))
    }

    @Test
    fun deleteRemovesLoadedCoverBytes() = runTest {
        val repository = FakeDocumentRepository()
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteDocument(repository.documentId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.documentCoverImages.containsKey(repository.documentId.value))
        assertFalse(viewModel.uiState.value.hasDocuments)
    }
}

private class FakeDocumentRepository(
    includeSecondDocument: Boolean = false,
    secondDocumentFormat: DocumentFormat = DocumentFormat.TXT,
    initiallyBookmarkedIds: Set<String> = emptySet(),
) : DocumentRepository {
    val documentId = DocumentId("document-1")
    val secondDocumentId = DocumentId("document-2")
    val pdfCoverBytes = byteArrayOf(1, 3, 3, 7)
    val coverRequestIds = mutableListOf<String>()
    private val documents = MutableStateFlow(
        buildList {
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

    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = documents
    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        documents.value.firstOrNull { it.id == documentId }

    override suspend fun getDocumentCover(documentId: DocumentId): ByteArray? {
        coverRequestIds += documentId.value
        return if (documentId == this.documentId) pdfCoverBytes else null
    }

    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = null

    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
    ): List<PageWindow> = emptyList()

    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument = error("not used")

    override suspend fun upsertDocument(document: DocumentMetadata) {
        documents.value = documents.value.map { current ->
            if (current.id == document.id) document else current
        }
    }

    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit

    override suspend fun deleteDocument(documentId: DocumentId) {
        documents.value = documents.value.filterNot { it.id == documentId }
    }
}

private class SuspendingCoverDocumentRepository : DocumentRepository {
    private val coverGate = CompletableDeferred<Unit>()
    private val documents = MutableStateFlow<List<DocumentMetadata>>(emptyList())
    var activeCoverRequests = 0
        private set
    var maxConcurrentCoverRequests = 0
        private set

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

    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = documents

    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        documents.value.firstOrNull { it.id == documentId }

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

    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = null

    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
    ): List<PageWindow> = emptyList()

    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument = error("not used")

    override suspend fun upsertDocument(document: DocumentMetadata) = Unit

    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit

    override suspend fun deleteDocument(documentId: DocumentId) = Unit
}

private fun bookmarkedDocument(id: String): DocumentMetadata = testDocument(id = id, isBookmarked = true)

private fun recentDocument(id: String): DocumentMetadata = testDocument(id = id, isBookmarked = false)

private fun testDocument(
    id: String,
    isBookmarked: Boolean,
): DocumentMetadata = DocumentMetadata(
    id = DocumentId(id),
    location = DocumentLocation(
        sourceUri = "file:///$id.pdf",
        displayName = "$id.pdf",
    ),
    format = DocumentFormat.PDF,
    addedAtEpochMillis = 1_000L,
    isBookmarked = isBookmarked,
)

package com.tedd.teddreader.feature.search.impl

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.SearchRepository
import com.tedd.teddreader.core.domain.usecase.SearchDocumentUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
class SearchViewModelTest {
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
    fun pdfSearchMarksUnsupportedAndSkipsSearchRepository() = runTest {
        val documentId = DocumentId("pdf-1")
        val searchRepository = FakeSearchRepository()
        val documentRepository = FakeDocumentRepository(
            metadata = DocumentMetadata(
                id = documentId,
                location = DocumentLocation(
                    sourceUri = "file:///sample.pdf",
                    displayName = "sample.pdf",
                    mimeType = "application/pdf",
                ),
                format = DocumentFormat.PDF,
                addedAtEpochMillis = 0L,
                pageCount = 3,
            ),
        )
        val viewModel = SearchViewModel(
            searchDocument = SearchDocumentUseCase(
                documentRepository = documentRepository,
                searchRepository = searchRepository,
            ),
        )

        viewModel.setDocument(documentId.value)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSearchUnsupported)

        viewModel.updateQuery("needle")
        viewModel.search()
        advanceUntilIdle()

        assertEquals(emptyList(), viewModel.uiState.value.results)
        assertEquals(0, searchRepository.searchCount)
        assertTrue(viewModel.uiState.value.isSearchUnsupported)
    }

    @Test
    fun setDocumentCapabilityCheckDoesNotOverwriteUserTypedQuery() = runTest {
        val documentId = DocumentId("epub-1")
        val gate = CompletableDeferred<Unit>()
        val viewModel = SearchViewModel(
            searchDocument = SearchDocumentUseCase(
                documentRepository = FakeDocumentRepository(
                    metadata = DocumentMetadata(
                        id = documentId,
                        location = DocumentLocation(
                            sourceUri = "file:///sample.epub",
                            displayName = "sample.epub",
                            mimeType = "application/epub+zip",
                        ),
                        format = DocumentFormat.EPUB,
                        addedAtEpochMillis = 0L,
                    ),
                    beforeGetDocument = { gate.await() },
                ),
                searchRepository = FakeSearchRepository(),
            ),
        )

        viewModel.setDocument(documentId.value)
        viewModel.updateQuery("typed while loading")
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("typed while loading", viewModel.uiState.value.query)
        assertFalse(viewModel.uiState.value.isSearchUnsupported)
    }

    @Test
    fun editingQueryCancelsInFlightSearchAndKeepsTypedText() = runTest {
        val documentId = DocumentId("epub-2")
        val searchGate = CompletableDeferred<Unit>()
        val viewModel = SearchViewModel(
            searchDocument = SearchDocumentUseCase(
                documentRepository = FakeDocumentRepository(
                    metadata = DocumentMetadata(
                        id = documentId,
                        location = DocumentLocation(
                            sourceUri = "file:///sample.epub",
                            displayName = "sample.epub",
                            mimeType = "application/epub+zip",
                        ),
                        format = DocumentFormat.EPUB,
                        addedAtEpochMillis = 0L,
                    ),
                ),
                searchRepository = FakeSearchRepository(
                    beforeSearch = { searchGate.await() },
                    results = listOf(
                        SearchResult(
                            documentId = documentId,
                            location = ReaderLocation.TextOffset(0L),
                            snippet = "stale",
                        ),
                    ),
                ),
            ),
        )

        viewModel.setDocument(documentId.value)
        advanceUntilIdle()
        viewModel.updateQuery("first")
        viewModel.search()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoading)

        viewModel.updateQuery("second")
        advanceUntilIdle()
        searchGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("second", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.results.isEmpty())
        assertFalse(viewModel.uiState.value.isLoading)
    }

    /**
     * Verifies that cancelling the search coroutine (via [SearchViewModel.setDocument] replacing
     * the in-flight job) actually terminates the suspended search rather than swallowing
     * [CancellationException]. If `suspendRunCatching` were reverted to plain `runCatching`, the
     * cancellation would be caught inside `.getOrElse`, and the `return@launch` path would
     * execute writing an error message — this assertion would fail.
     */
    @Test
    fun cancellationOfSearchDoesNotProduceErrorMessage() = runTest {
        val documentId = DocumentId("epub-3")
        val searchGate = CompletableDeferred<Unit>()
        val viewModel = SearchViewModel(
            searchDocument = SearchDocumentUseCase(
                documentRepository = FakeDocumentRepository(
                    metadata = DocumentMetadata(
                        id = documentId,
                        location = DocumentLocation(
                            sourceUri = "file:///sample.epub",
                            displayName = "sample.epub",
                            mimeType = "application/epub+zip",
                        ),
                        format = DocumentFormat.EPUB,
                        addedAtEpochMillis = 0L,
                    ),
                ),
                searchRepository = FakeSearchRepository(
                    beforeSearch = { searchGate.await() },
                ),
            ),
        )

        viewModel.setDocument(documentId.value)
        advanceUntilIdle()
        viewModel.updateQuery("query")
        viewModel.search()
        advanceUntilIdle()

        viewModel.setDocument("other-doc")
        advanceUntilIdle()

        searchGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("other-doc", viewModel.uiState.value.documentId)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }
}

private class FakeDocumentRepository(
    private val metadata: DocumentMetadata,
    private val beforeGetDocument: suspend () -> Unit = {},
) : DocumentRepository {
    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = flowOf(listOf(metadata))

    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? {
        beforeGetDocument()
        return metadata.takeIf { it.id == documentId }
    }

    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = null

    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: com.tedd.teddreader.core.common.model.ReaderPageBreaker?,
        anchorOffset: Long?,
        viewportDensity: Float,
    ): List<PageWindow> = emptyList()

    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument = error("not used")

    override suspend fun upsertDocument(document: DocumentMetadata) = Unit
    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit
    override suspend fun deleteDocument(documentId: DocumentId) = Unit
}

private class FakeSearchRepository(
    private val beforeSearch: suspend () -> Unit = {},
    private val results: List<SearchResult> = emptyList(),
) : SearchRepository {
    var searchCount = 0

    override suspend fun findInDocument(
        documentId: DocumentId,
        query: String,
        limit: Int,
    ): List<SearchResult> {
        searchCount += 1
        beforeSearch()
        return results
    }
}

package com.tedd.teddreader.feature.search.impl

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.SearchRepository
import com.tedd.teddreader.core.domain.usecase.FindInDocumentUseCase
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
    fun pdfDocumentShowsUnsupportedStateAndSkipsSearchRepository() = runTest {
        val documentId = DocumentId("pdf-1")
        val searchRepository = FakeSearchRepository()
        val viewModel = SearchViewModel(
            findInDocument = FindInDocumentUseCase(searchRepository),
            documentRepository = FakeDocumentRepository(
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
            ),
        )

        viewModel.setDocument(documentId.value)
        advanceUntilIdle()
        viewModel.updateQuery("needle")
        viewModel.search()
        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.isSearchUnsupported)
        assertEquals(emptyList(), viewModel.uiState.value.results)
        assertEquals(0, searchRepository.searchCount)
    }
}

private class FakeDocumentRepository(
    private val metadata: DocumentMetadata,
) : DocumentRepository {
    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = flowOf(listOf(metadata))
    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        metadata.takeIf { it.id == documentId }

    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = null

    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: com.tedd.teddreader.core.common.model.ReaderPageBreaker?,
        anchorOffset: Long?,
    ): List<PageWindow> = emptyList()

    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument = error("not used")

    override suspend fun upsertDocument(document: DocumentMetadata) = Unit
    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit
    override suspend fun deleteDocument(documentId: DocumentId) = Unit
}

private class FakeSearchRepository : SearchRepository {
    var searchCount = 0

    override suspend fun findInDocument(
        documentId: DocumentId,
        query: String,
        limit: Int,
    ): List<SearchResult> {
        searchCount += 1
        return emptyList()
    }

}

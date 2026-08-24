package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchDocumentUseCaseTest {
    private val documentId = DocumentId("file:///book.epub")

    @Test
    fun trimsQueryAndClampsLimitBeforeDelegating() = runTest {
        val search = RecordingSearchRepository()
        val useCase = SearchDocumentUseCase(FakeDocuments(DocumentFormat.EPUB), search)

        val result = useCase(documentId, "  reader  ", limit = 0)

        assertEquals("reader", result.query)

        assertEquals("reader", search.lastQuery)
        assertEquals(1, search.lastLimit)
    }

    @Test
    fun blankQuerySkipsSearchButStillChecksFormat() = runTest {
        val documents = FakeDocuments(DocumentFormat.EPUB)
        val search = RecordingSearchRepository()
        val useCase = SearchDocumentUseCase(documents, search)

        val result = useCase(documentId, "   ")

        assertTrue(result.results.isEmpty())
        assertFalse(result.isUnsupported)
        assertEquals(1, documents.readCount)
        assertEquals(0, search.callCount)
    }

    @Test
    fun visualDocumentsAreBlockedBeforeSearch() = runTest {
        val search = RecordingSearchRepository()
        val useCase = SearchDocumentUseCase(FakeDocuments(DocumentFormat.PDF), search)

        val result = useCase(documentId, "reader")

        assertTrue(result.results.isEmpty())
        assertTrue(result.isUnsupported)
        assertEquals(0, search.callCount)
    }

    @Test
    fun blankQueryStillReportsUnsupportedForVisualDocuments() = runTest {
        val search = RecordingSearchRepository()
        val useCase = SearchDocumentUseCase(FakeDocuments(DocumentFormat.PDF), search)

        val result = useCase(documentId, "   ")

        assertTrue(result.results.isEmpty())
        assertTrue(result.isUnsupported)
        assertEquals(0, search.callCount)
    }

    private class FakeDocuments(format: DocumentFormat) : DocumentRepository {
        var readCount = 0
        private val metadata = DocumentMetadata(
            id = DocumentId("file:///book.epub"),
            location = DocumentLocation(sourceUri = "file:///book.epub", displayName = "book.epub"),
            format = format,
            addedAtEpochMillis = 1,
        )

        override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = flowOf(listOf(metadata))
        override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? {
            readCount += 1
            return metadata
        }
        override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = null
        override suspend fun getPageWindows(documentId: DocumentId, style: ReaderStyle, viewportSize: ViewportSize?, pageBreaker: ReaderPageBreaker?, anchorOffset: Long?): List<PageWindow> = emptyList()
        override suspend fun importDocument(source: DocumentImportSource, importedAtEpochMillis: Long): ReaderDocument = error("unused")
        override suspend fun upsertDocument(document: DocumentMetadata) = Unit
        override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit
        override suspend fun deleteDocument(documentId: DocumentId) = Unit
    }

    private class RecordingSearchRepository : SearchRepository {
        var callCount = 0
        var lastQuery: String? = null
        var lastLimit: Int? = null

        override suspend fun findInDocument(documentId: DocumentId, query: String, limit: Int): List<SearchResult> {
            callCount += 1
            lastQuery = query
            lastLimit = limit
            return listOf(SearchResult(documentId, "snippet", ReaderLocation.TextOffset(0)))
        }
    }
}

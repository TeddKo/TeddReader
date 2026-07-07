package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.domain.repository.SearchRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FindInDocumentUseCaseTest {
    @Test
    fun blankQueryReturnsEmptyWithoutRepositoryCall() = runTest {
        val repository = FakeSearchRepository()
        val useCase = FindInDocumentUseCase(repository)

        assertEquals(emptyList(), useCase(DocumentId("doc-1"), "   "))
        assertFalse(repository.findCalled)
    }

    @Test
    fun trimsQueryAndCoercesLimit() = runTest {
        val repository = FakeSearchRepository()
        val useCase = FindInDocumentUseCase(repository)

        useCase(DocumentId("doc-1"), "  reader  ", limit = 0)

        assertEquals("reader", repository.lastQuery)
        assertEquals(1, repository.lastLimit)
    }
}

private class FakeSearchRepository : SearchRepository {
    var findCalled = false
    var lastQuery: String? = null
    var lastLimit: Int? = null

    override suspend fun indexDocument(document: ReaderDocument) = Unit

    override suspend fun findInDocument(
        documentId: DocumentId,
        query: String,
        limit: Int,
    ): List<SearchResult> {
        findCalled = true
        lastQuery = query
        lastLimit = limit
        return emptyList()
    }

    override suspend fun clearIndex(documentId: DocumentId) = Unit
}

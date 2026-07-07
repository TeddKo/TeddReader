package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.SearchResult

interface SearchRepository {
    suspend fun indexDocument(document: ReaderDocument)
    suspend fun findInDocument(
        documentId: DocumentId,
        query: String,
        limit: Int = 50,
    ): List<SearchResult>
    suspend fun clearIndex(documentId: DocumentId)
}

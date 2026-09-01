package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.SearchRepository

/** 일반적인 빈 결과 집합과 "지원하지 않음"을 구분해 유지하는 검색 결과다. */
data class SearchDocumentResult(
    val query: String,
    val results: List<SearchResult>,
    val isUnsupported: Boolean,
)

class SearchDocumentUseCase(
    private val documentRepository: DocumentRepository,
    private val searchRepository: SearchRepository,
) {
    suspend operator fun invoke(
        documentId: DocumentId,
        query: String,
        limit: Int = 50,
    ): SearchDocumentResult {
        val trimmedQuery = query.trim()
        val metadata = documentRepository.getDocument(documentId)
            ?: return SearchDocumentResult(query = trimmedQuery, results = emptyList(), isUnsupported = false)
        if (metadata.format.isVisualPageFormat()) {
            return SearchDocumentResult(query = trimmedQuery, results = emptyList(), isUnsupported = true)
        }
        if (trimmedQuery.isBlank()) return SearchDocumentResult(query = "", results = emptyList(), isUnsupported = false)
        return SearchDocumentResult(
            query = trimmedQuery,
            results = searchRepository.findInDocument(documentId, trimmedQuery, limit.coerceAtLeast(1)),
            isUnsupported = false,
        )
    }
}

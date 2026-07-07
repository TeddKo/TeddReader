package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.domain.repository.SearchRepository
import org.koin.core.annotation.Single

@Single
class FindInDocumentUseCase(
    private val searchRepository: SearchRepository,
) {
    suspend operator fun invoke(
        documentId: DocumentId,
        query: String,
        limit: Int = 50,
    ): List<SearchResult> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return emptyList()
        return searchRepository.findInDocument(
            documentId = documentId,
            query = trimmedQuery,
            limit = limit.coerceAtLeast(1),
        )
    }
}

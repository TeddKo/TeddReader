package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.data.mapper.toSearchIndexEntity
import com.tedd.teddreader.core.data.mapper.toSearchResults
import com.tedd.teddreader.core.domain.repository.SearchRepository
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import org.koin.core.annotation.Single

@Single(binds = [SearchRepository::class])
class SearchRepositoryImpl(
    private val searchIndexDao: SearchIndexDao,
) : SearchRepository {
    override suspend fun indexDocument(document: ReaderDocument) {
        searchIndexDao.deleteSearchIndex(document.id.value)
        if (document.sections.isEmpty()) return

        searchIndexDao.upsertSearchIndex(
            document.sections.map { section -> section.toSearchIndexEntity(document.id) },
        )
    }

    override suspend fun findInDocument(
        documentId: DocumentId,
        query: String,
        limit: Int,
    ): List<SearchResult> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return emptyList()

        return searchIndexDao
            .search(documentId.value, trimmedQuery, limit.coerceAtLeast(1))
            .flatMap { entry -> entry.toSearchResults(trimmedQuery) }
            .take(limit.coerceAtLeast(1))
    }

    override suspend fun clearIndex(documentId: DocumentId) {
        searchIndexDao.deleteSearchIndex(documentId.value)
    }
}

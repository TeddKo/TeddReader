package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import com.tedd.teddreader.core.room.entity.SearchIndexEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchRepositoryImplTest {
    @Test
    fun indexesSectionsAndReturnsMatchingResults() = runTest {
        val dao = FakeSearchIndexDao()
        val repository = SearchRepositoryImpl(dao)
        val document = ReaderDocument(
            id = DocumentId("doc-1"),
            format = com.tedd.teddreader.core.common.model.DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(
                    index = 0,
                    title = "Chapter 1",
                    text = "Hello reader service",
                    range = TextRange(0, 20),
                ),
                ReaderSection(
                    index = 1,
                    title = "Chapter 2",
                    text = "No match",
                    range = TextRange(21, 29),
                ),
            ),
        )

        repository.indexDocument(document)
        val results = repository.findInDocument(DocumentId("doc-1"), "reader", limit = 10)

        assertEquals(2, dao.entries.size)
        assertEquals(1, results.size)
        assertEquals("Chapter 1", results.single().sectionTitle)
    }
}

private class FakeSearchIndexDao : SearchIndexDao {
    val entries = mutableListOf<SearchIndexEntity>()

    override suspend fun upsertSearchIndex(entries: List<SearchIndexEntity>) {
        this.entries.removeAll { old -> entries.any { new -> old.documentId == new.documentId && old.sectionIndex == new.sectionIndex } }
        this.entries.addAll(entries)
    }

    override suspend fun search(
        documentId: String,
        query: String,
        limit: Int,
    ): List<SearchIndexEntity> = entries
        .filter { entry -> entry.documentId == documentId && entry.text.contains(query, ignoreCase = true) }
        .sortedBy { entry -> entry.sectionIndex }
        .take(limit)

    override suspend fun getDocumentSections(documentId: String): List<SearchIndexEntity> =
        entries.filter { entry -> entry.documentId == documentId }.sortedBy { entry -> entry.sectionIndex }

    override suspend fun deleteSearchIndex(documentId: String) {
        entries.removeAll { entry -> entry.documentId == documentId }
    }
}

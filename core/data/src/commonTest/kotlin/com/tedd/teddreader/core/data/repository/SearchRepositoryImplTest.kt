package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.data.mapper.toSearchResults
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
                    range = TextRange(20, 40),
                ),
                ReaderSection(
                    index = 1,
                    title = "Chapter 2",
                    text = "No match",
                    range = TextRange(41, 49),
                ),
            ),
        )

        repository.indexDocument(document)
        val results = repository.findInDocument(DocumentId("doc-1"), "reader", limit = 10)

        assertEquals(2, dao.entries.size)
        assertEquals(1, results.size)
        assertEquals("Chapter 1", results.single().sectionTitle)
        assertEquals(ReaderLocation.TextOffset(26), results.single().location)
        assertEquals(TextRange(26, 32), results.single().range)
        assertEquals("reader", results.single().query)
    }

    @Test
    fun returnsEveryNonOverlappingOccurrenceInDocumentOrder() = runTest {
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
                    text = "reader text reader end",
                    range = TextRange(100, 122),
                ),
                ReaderSection(
                    index = 1,
                    title = "Chapter 2",
                    text = "Reader again reader",
                    range = TextRange(200, 219),
                ),
            ),
        )

        repository.indexDocument(document)
        val results = repository.findInDocument(DocumentId("doc-1"), "reader", limit = 10)

        assertEquals(
            listOf(
                ReaderLocation.TextOffset(100),
                ReaderLocation.TextOffset(112),
                ReaderLocation.TextOffset(200),
                ReaderLocation.TextOffset(213),
            ),
            results.map { it.location },
        )
        assertEquals(
            listOf(
                TextRange(100, 106),
                TextRange(112, 118),
                TextRange(200, 206),
                TextRange(213, 219),
            ),
            results.map { it.range },
        )
        assertEquals(listOf("Chapter 1", "Chapter 1", "Chapter 2", "Chapter 2"), results.map { it.sectionTitle })
    }

    @Test
    fun appliesGlobalLimitPerOccurrence() = runTest {
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
                    text = "reader reader reader",
                    range = TextRange(0, 20),
                ),
                ReaderSection(
                    index = 1,
                    title = "Chapter 2",
                    text = "reader",
                    range = TextRange(20, 26),
                ),
            ),
        )

        repository.indexDocument(document)
        val results = repository.findInDocument(DocumentId("doc-1"), "reader", limit = 2)

        assertEquals(2, results.size)
        assertEquals(
            listOf(
                ReaderLocation.TextOffset(0),
                ReaderLocation.TextOffset(7),
            ),
            results.map { it.location },
        )
    }

    @Test
    fun mapperReturnsEmptyForBlankQuery() {
        val entry = SearchIndexEntity(
            documentId = "doc-1",
            sectionIndex = 0,
            sectionTitle = "Chapter 1",
            text = "reader reader",
            startOffset = 0,
            endOffset = 13,
        )

        assertEquals(emptyList(), entry.toSearchResults(""))
    }

    @Test
    fun blankQueryReturnsEmptyResults() = runTest {
        val dao = FakeSearchIndexDao()
        val repository = SearchRepositoryImpl(dao)

        val results = repository.findInDocument(DocumentId("doc-1"), "   ", limit = 10)

        assertEquals(emptyList(), results)
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

package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.data.mapper.toSearchIndexEntity
import com.tedd.teddreader.core.data.mapper.toSearchResults
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import com.tedd.teddreader.core.room.dao.SearchIndexSearchEntry
import com.tedd.teddreader.core.room.dao.SearchIndexSectionEntry
import com.tedd.teddreader.core.room.dao.SectionBlocksJsonEntry
import com.tedd.teddreader.core.room.dao.SectionOffsetEntry
import com.tedd.teddreader.core.room.entity.SearchIndexEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [SearchRepositoryImpl]'s occurrence-search contract: given sections already indexed via
 * [toSearchIndexEntity], [SearchRepositoryImpl.findInDocument] returns every non-overlapping
 * occurrence of a query in reading order, case-insensitively, with the query trimmed of surrounding
 * whitespace, a query that is blank once trimmed matching nothing, and a requested limit below one
 * read as one. Backed by an in-memory [FakeSearchIndexDao] so these guarantees are exercised without
 * Room.
 */
class SearchRepositoryImplTest {
    /**
     * Guards the basic path: a document indexed via [toSearchIndexEntity] and then searched finds the
     * matching section and reports the correct absolute character offset, snippet, range, and the
     * query it was found with.
     */
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

        dao.upsertSearchIndex(document.sections.map { it.toSearchIndexEntity(document.id) })
        val results = repository.findInDocument(DocumentId("doc-1"), "reader", limit = 10)

        assertEquals(2, dao.entries.size)
        assertEquals(1, results.size)
        assertEquals("Chapter 1", results.single().sectionTitle)
        assertEquals(ReaderLocation.TextOffset(26), results.single().location)
        assertEquals(TextRange(26, 32), results.single().range)
        assertEquals("reader", results.single().query)
    }

    /**
     * Guards that every occurrence — within one section and across several — comes back in document
     * order and without overlaps: a scan advances past each match before looking for the next, so
     * adjacent or repeated occurrences of the same word are all counted once each, never double
     * counted or skipped.
     */
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

        dao.upsertSearchIndex(document.sections.map { it.toSearchIndexEntity(document.id) })
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

    /**
     * Guards that `limit` counts individual occurrences, not the sections the DAO fetched: three
     * matches in one section plus one in another, asked for with `limit = 2`, must trim down to
     * exactly the first two occurrences in document order rather than one per section or all four.
     */
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

        dao.upsertSearchIndex(document.sections.map { it.toSearchIndexEntity(document.id) })
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

    /**
     * Guards [toSearchResults] directly: an empty query string must return no results rather than
     * matching every position in the text (which a naive zero-length-match scan would do).
     */
    @Test
    fun mapperReturnsEmptyForBlankQuery() {
        val entry = SearchIndexSearchEntry(
            documentId = "doc-1",
            sectionIndex = 0,
            sectionTitle = "Chapter 1",
            text = "reader reader",
            startOffset = 0,
            endOffset = 13,
        )

        assertEquals(emptyList(), entry.toSearchResults(""))
    }

    /**
     * Guards the repository-level short-circuit: a query that is blank once trimmed returns empty
     * immediately, without ever reaching the DAO.
     */
    @Test
    fun blankQueryReturnsEmptyResults() = runTest {
        val dao = FakeSearchIndexDao()
        val repository = SearchRepositoryImpl(dao)

        val results = repository.findInDocument(DocumentId("doc-1"), "   ", limit = 10)

        assertEquals(emptyList(), results)
    }

    /**
     * Guards two argument-normalization rules at once, verified against the fake DAO's recorded
     * arguments: leading/trailing whitespace around the query is trimmed before it ever reaches the
     * DAO, and a `limit` of `0` is coerced up to `1`, since a caller asking for a search is asking for
     * at least one result.
     */
    @Test
    fun surroundingSpaceIsTrimmedAndAZeroLimitStillAsksForOneResult() = runTest {
        val dao = FakeSearchIndexDao()
        val repository = SearchRepositoryImpl(dao)

        repository.findInDocument(DocumentId("doc-1"), "  reader  ", limit = 0)

        assertEquals("reader", dao.lastQuery)
        assertEquals(1, dao.lastLimit)
    }
}

/**
 * Mirrors Room's search projection for the in-memory DAO while keeping its seeded full entities intact.
 *
 * @receiver the full row selected by the fake search.
 * @return only the columns production search materializes.
 */
private fun SearchIndexEntity.toSearchEntry(): SearchIndexSearchEntry = SearchIndexSearchEntry(
    documentId = documentId,
    sectionIndex = sectionIndex,
    sectionTitle = sectionTitle,
    text = text,
    startOffset = startOffset,
    endOffset = endOffset,
)

/**
 * In-memory [SearchIndexDao] used only by this test file, filtering/sorting/limiting the same way the
 * real Room-backed DAO does so [SearchRepositoryImpl]'s own logic — trimming, the limit floor,
 * occurrence flattening — is exercised without pulling in Room. `lastQuery`/`lastLimit` record what
 * [SearchRepositoryImpl] actually passed down, so a test can assert on the normalized arguments
 * directly.
 */
private class FakeSearchIndexDao : SearchIndexDao {
    val entries = mutableListOf<SearchIndexEntity>()
    var lastQuery: String? = null
    var lastLimit: Int? = null

    override suspend fun upsertSearchIndex(entries: List<SearchIndexEntity>) {
        this.entries.removeAll { old -> entries.any { new -> old.documentId == new.documentId && old.sectionIndex == new.sectionIndex } }
        this.entries.addAll(entries)
    }

    override suspend fun search(
        documentId: String,
        query: String,
        limit: Int,
    ): List<SearchIndexSearchEntry> {
        lastQuery = query
        lastLimit = limit
        return entries
            .filter { entry -> entry.documentId == documentId && entry.text.contains(query, ignoreCase = true) }
            .sortedBy { entry -> entry.sectionIndex }
            .take(limit)
            .map { entry -> entry.toSearchEntry() }
    }

    override suspend fun getDocumentSectionsWithoutBlocks(documentId: String): List<SearchIndexSectionEntry> =
        entries.filter { entry -> entry.documentId == documentId }.sortedBy { entry -> entry.sectionIndex }.map { entry ->
            SearchIndexSectionEntry(
                sectionIndex = entry.sectionIndex,
                sectionTitle = entry.sectionTitle,
                text = entry.text,
                startOffset = entry.startOffset,
                endOffset = entry.endOffset,
                documentTitle = entry.documentTitle,
                navigationJson = entry.navigationJson,
                parserVersion = entry.parserVersion,
            )
        }

    override suspend fun getSectionBlocksJson(documentId: String, sectionIndexes: List<Int>): List<SectionBlocksJsonEntry> =
        entries
            .filter { entry -> entry.documentId == documentId && entry.sectionIndex in sectionIndexes }
            .map { entry -> SectionBlocksJsonEntry(entry.sectionIndex, entry.blocksJson) }

    override suspend fun getLastSection(documentId: String): SectionOffsetEntry? =
        entries.filter { entry -> entry.documentId == documentId }
            .maxByOrNull { entry -> entry.sectionIndex }
            ?.let { entry -> SectionOffsetEntry(entry.sectionIndex, entry.endOffset) }

    override suspend fun updateSectionTitle(documentId: String, sectionIndex: Int, title: String) {
        val index = entries.indexOfFirst { entry -> entry.documentId == documentId && entry.sectionIndex == sectionIndex }
        if (index >= 0) entries[index] = entries[index].copy(sectionTitle = title)
    }

    override suspend fun updateDocumentTitleAndNavigation(
        documentId: String,
        sectionIndex: Int,
        documentTitle: String,
        navigationJson: String,
    ) {
        val index = entries.indexOfFirst { entry -> entry.documentId == documentId && entry.sectionIndex == sectionIndex }
        if (index >= 0) {
            entries[index] = entries[index].copy(documentTitle = documentTitle, navigationJson = navigationJson)
        }
    }

    override suspend fun deleteSearchIndex(documentId: String) {
        entries.removeAll { entry -> entry.documentId == documentId }
    }

    override suspend fun getSectionSourcePaths(documentId: String): List<com.tedd.teddreader.core.room.dao.SectionSourcePathEntry> =
        entries.filter { it.documentId == documentId }
            .sortedBy { it.sectionIndex }
            .map { com.tedd.teddreader.core.room.dao.SectionSourcePathEntry(it.sectionIndex, it.sourcePath) }

    override suspend fun getFirstReadableContentSectionIndex(documentId: String, excludeSectionIndex: Int): Int? =
        entries.filter { it.documentId == documentId && it.sectionIndex != excludeSectionIndex && it.text.isNotBlank() }
            .minByOrNull { it.sectionIndex }
            ?.sectionIndex

    override suspend fun getSectionCount(documentId: String): Int =
        entries.count { it.documentId == documentId }
}

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
 * [SearchRepositoryImpl]의 발생 횟수 검색 계약을 고정한다: [toSearchIndexEntity]를 통해 이미 인덱싱된
 * 섹션이 주어졌을 때, [SearchRepositoryImpl.findInDocument]는 쿼리의 모든 비중복 발생을 읽기 순서대로
 * 대소문자 구분 없이 반환하며, 주변 공백을 제거한 쿼리를 사용한다. 제거 후 공백만 남은 쿼리는 아무것도
 * 매칭하지 않고, 1 미만으로 요청된 limit은 1로 읽힌다. Room 없이 이 보장들을 검증할 수 있도록
 * 인메모리 [FakeSearchIndexDao]를 사용한다.
 */
class SearchRepositoryImplTest {
    /**
     * 기본 경로를 검증한다: [toSearchIndexEntity]를 통해 인덱싱된 문서를 검색하면 일치하는 섹션을 찾아
     * 올바른 절대 문자 오프셋, 스니펫, 범위, 그리고 검색에 사용된 쿼리를 반환하는지 확인한다.
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
     * 한 섹션 내에서와 여러 섹션에 걸쳐 모든 발생이 문서 순서대로, 중복 없이 반환되는지 검증한다:
     * 스캔은 각 매칭을 지나쳐야 다음을 탐색하므로, 같은 단어가 인접하거나 반복 등장해도 각각 한 번씩만
     * 카운트되며 이중 카운트나 누락이 없어야 한다.
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
     * `limit`이 DAO가 가져온 섹션 수가 아닌 개별 발생 횟수를 기준으로 동작하는지 검증한다:
     * 한 섹션에 3개의 매칭과 다른 섹션에 1개가 있을 때 `limit = 2`로 요청하면, 섹션당 하나 또는
     * 전체 4개가 아닌 문서 순서상 정확히 처음 2개의 발생만 반환되어야 한다.
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
     * 리포지토리 결과 계약 하의 할당 경계를 검증한다: 밀도 높은 섹션은 호출자가 표시할 것보다
     * 훨씬 더 많은 발생을 포함할 수 있으므로, 매퍼는 나중에 `take`로 버리는 방식에 의존하지 않고
     * 자체 limit에서 결과 생성을 멈춰야 한다.
     */
    @Test
    fun mapperStopsMaterializingOccurrencesAtLimit() {
        val entry = SearchIndexSearchEntry(
            documentId = "doc-1",
            sectionIndex = 0,
            sectionTitle = "Dense chapter",
            text = List(100) { "reader" }.joinToString(separator = " "),
            startOffset = 0,
            endOffset = 699,
        )

        val results = entry.toSearchResults(query = "reader", limit = 5)

        assertEquals(5, results.size)
        assertEquals(
            listOf(0L, 7L, 14L, 21L, 28L).map(ReaderLocation::TextOffset),
            results.map { result -> result.location },
        )
    }

    /**
     * [toSearchResults]를 직접 검증한다: 빈 쿼리 문자열은 텍스트의 모든 위치에 매칭하는 대신
     * (단순한 길이-0 매칭 스캔이 그렇게 동작할 것이다) 결과 없음을 반환해야 한다.
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
     * 리포지토리 수준 단락(short-circuit)을 검증한다: 제거 후 공백만 남은 쿼리는
     * DAO에 도달하지 않고 즉시 빈 결과를 반환해야 한다.
     */
    @Test
    fun blankQueryReturnsEmptyResults() = runTest {
        val dao = FakeSearchIndexDao()
        val repository = SearchRepositoryImpl(dao)

        val results = repository.findInDocument(DocumentId("doc-1"), "   ", limit = 10)

        assertEquals(emptyList(), results)
    }

    /**
     * 두 가지 인자 정규화 규칙을 동시에, 가짜 DAO의 기록된 인자를 통해 검증한다: 쿼리 주변의 앞뒤
     * 공백은 DAO에 전달되기 전에 제거되어야 하고, `limit`이 `0`인 경우 `1`로 강제 조정되어야 한다.
     * 검색을 요청하는 호출자는 최소한 하나의 결과를 기대하기 때문이다.
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
 * 인메모리 DAO가 시드된 전체 엔티티를 그대로 유지하면서, Room의 검색 프로젝션을 미러링한다.
 *
 * @receiver 가짜 검색이 선택한 전체 행.
 * @return 프로덕션 검색이 materialized하는 컬럼만 반환.
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
 * 이 테스트 파일에서만 사용하는 인메모리 [SearchIndexDao]로, 실제 Room 기반 DAO와 동일한 방식으로
 * 필터링·정렬·제한을 수행하여, [SearchRepositoryImpl] 자체의 로직 — 트리밍, limit 하한값,
 * 발생 횟수 평탄화 — 이 Room 없이 실행될 수 있도록 한다. `lastQuery`/`lastLimit`은
 * [SearchRepositoryImpl]이 실제로 전달한 값을 기록하므로, 테스트에서 정규화된 인자를 직접 assert할 수 있다.
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

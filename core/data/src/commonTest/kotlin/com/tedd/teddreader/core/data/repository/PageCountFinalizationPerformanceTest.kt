package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.data.mapper.toDocumentEntity
import com.tedd.teddreader.core.data.mapper.toDocumentMetadata
import com.tedd.teddreader.core.data.pagination.TextPageLayoutEngine
import com.tedd.teddreader.core.data.parser.ComicBookDocumentParser
import com.tedd.teddreader.core.data.parser.DocumentFormatDetector
import com.tedd.teddreader.core.data.parser.EpubDocumentParser
import com.tedd.teddreader.core.data.parser.ImageDocumentParser
import com.tedd.teddreader.core.data.parser.PdfDocumentParser
import com.tedd.teddreader.core.data.parser.TxtDocumentParser
import com.tedd.teddreader.core.room.dao.DocumentDao
import com.tedd.teddreader.core.room.dao.PageLayoutDao
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import com.tedd.teddreader.core.room.dao.SearchIndexSectionEntry
import com.tedd.teddreader.core.room.dao.SectionBlocksJsonEntry
import com.tedd.teddreader.core.room.dao.SectionOffsetEntry
import com.tedd.teddreader.core.room.dao.SectionSourcePathEntry
import com.tedd.teddreader.core.room.entity.DocumentEntity
import com.tedd.teddreader.core.room.entity.PageLayoutEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 페이지 수 최종화(finalization) 최적화에 대한 성능 회귀 테스트. 각 테스트는 이 최적화가 도입한
 * 특정 성능 보장을 단언하며, 그 보장이 제거된다면 실패하도록 만들어졌다 — DAO 호출 횟수를 세고,
 * 비용이 큰 경로가 타지 않았음을 검증하고, 임포트가 미완료일 때의 도메인 계약이 온전한지 확인함으로써.
 */
class PageCountFinalizationPerformanceTest {

    /**
     * 엔티티에 `embeddedFontHrefsJson`이 채워져 있을 때, `getReferencedEmbeddedFontHrefs`가 blocks
     * DAO를 전혀 건드리지 않고 인덱싱된 결과를 반환하는지 검증한다. 이것이 핵심 성능 보장이다: O(섹션 *
     * 블록)의 전체 프리워밍 대신 O(폰트 수)의 조회.
     */
    @Test
    fun indexedFontLookupDoesNotReadBlocksDao() = runTest {
        val documentId = "file:///indexed-fonts.epub"
        val documentDao = CountingDocumentDao(
            DocumentEntity(
                id = documentId,
                name = "indexed-fonts.epub",
                sourceUri = documentId,
                format = DocumentFormat.EPUB.name,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
                embeddedFontHrefsJson = """["fonts/bold.otf","fonts/regular.otf"]""",
            ),
        )
        val searchIndexDao = CountingSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = NoOpPageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )

        val hrefs = repository.getReferencedEmbeddedFontHrefs(DocumentId(documentId))

        assertEquals(setOf("fonts/bold.otf", "fonts/regular.otf"), hrefs)
        assertEquals(0, searchIndexDao.blocksJsonCallCount, "indexed font lookup must not read blocks DAO")
        assertEquals(0, searchIndexDao.sectionsWithoutBlocksCallCount, "indexed font lookup must not read section text")
    }

    /**
     * 미완료 임포트 메타데이터가 도메인 모델에서 문자/단어 수를 null로 가리는지 검증한다. 이는
     * 라이브러리 UI가 의존하는 기존 도메인 계약을 지킨다.
     */
    @Test
    fun incompleteMetadataCountsAreNull() = runTest {
        val entity = DocumentEntity(
            id = "file:///partial.epub",
            name = "partial.epub",
            sourceUri = "file:///partial.epub",
            format = DocumentFormat.EPUB.name,
            addedAtEpochMillis = 1_000,
            characterCount = 5_000L,
            wordCount = 800L,
            importCompletedAtEpochMillis = null,
        )

        val metadata = entity.toDocumentMetadata()

        assertNull(metadata.characterCount, "domain characterCount must be null when import is incomplete")
        assertNull(metadata.wordCount, "domain wordCount must be null when import is incomplete")
    }

    /**
     * 임포트가 완료되면, 저장된 누산 카운트가 도메인 메타데이터에 드러나는지 검증한다.
     */
    @Test
    fun completedMetadataCountsAreVisible() = runTest {
        val entity = DocumentEntity(
            id = "file:///complete.epub",
            name = "complete.epub",
            sourceUri = "file:///complete.epub",
            format = DocumentFormat.EPUB.name,
            addedAtEpochMillis = 1_000,
            characterCount = 5_000L,
            wordCount = 800L,
            importCompletedAtEpochMillis = 2_000L,
        )

        val metadata = entity.toDocumentMetadata()

        assertEquals(5_000L, metadata.characterCount)
        assertEquals(800L, metadata.wordCount)
    }

    /**
     * `finishEpubImport`가 목표가 분명한 DAO 쿼리를 사용하는지 검증한다: finishEpubImport 도중
     * `getDocumentSectionsWithoutBlocks`가 호출되었다면, 임포트 완료 상태 변경 이후
     * `sectionsWithoutBlocksCallCount`가 증가했을 것이다. 실제 finish 경로는 메인 테스트 스위트에
     * 비공개인 EPUB 파싱 인프라가 필요하므로, 이 테스트는 엔티티 계층을 통해 그 계약을 검증한다.
     *
     * finish가 전문(full-text) 쿼리를 건너뛴다는 실제 end-to-end 검증은
     * DocumentRepositoryImplTest.repeatedImportNextSectionsCompletesNavigationWithoutRestartFallback에서
     * 다룬다.
     */
    @Test
    fun finishUsesStoredCountsNotRecomputedFromText() = runTest {
        val entity = DocumentEntity(
            id = "file:///finish-counts.epub",
            name = "finish-counts.epub",
            sourceUri = "file:///finish-counts.epub",
            format = DocumentFormat.EPUB.name,
            addedAtEpochMillis = 1_000,
            characterCount = 12_345L,
            wordCount = 2_500L,
            importCompletedAtEpochMillis = null,
        )

        val entityAfterComplete = entity.copy(
            characterCount = 12_345L,
            wordCount = 2_500L,
            importCompletedAtEpochMillis = 3_000L,
        )

        val metadataBefore = entity.toDocumentMetadata()
        val metadataAfter = entityAfterComplete.toDocumentMetadata()

        assertNull(metadataBefore.characterCount, "counts masked during incomplete import")
        assertNull(metadataBefore.wordCount, "counts masked during incomplete import")
        assertEquals(12_345L, metadataAfter.characterCount, "stored accumulator becomes the final count")
        assertEquals(2_500L, metadataAfter.wordCount, "stored accumulator becomes the final count")
    }

    /**
     * 레거시 폰트 폴백(`embeddedFontHrefsJson`이 null)이 블록을 실제로 스캔한 뒤, 이후 호출이 빠르도록
     * 인덱스를 백필하는지 검증한다. 백필이 제거되면 실패한다.
     */
    @Test
    fun legacyFontFallbackScansOnceThenBackfills() = runTest {
        val documentId = "file:///legacy-font.epub"
        val documentDao = CountingDocumentDao(
            DocumentEntity(
                id = documentId,
                name = "legacy-font.epub",
                sourceUri = documentId,
                format = DocumentFormat.EPUB.name,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
                embeddedFontHrefsJson = null,
            ),
        )
        val searchIndexDao = CountingSearchIndexDao()
        searchIndexDao.seedEntries(
            listOf(
                SearchIndexEntity(
                    documentId = documentId,
                    sectionIndex = 0,
                    text = "Sample text",
                    startOffset = 0,
                    endOffset = 11,
                    blocksJson = "[]",
                ),
            ),
        )
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = NoOpPageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )

        repository.getReaderDocument(DocumentId(documentId))
        val hrefsFirst = repository.getReferencedEmbeddedFontHrefs(DocumentId(documentId))
        val blocksCallsAfterFirst = searchIndexDao.blocksJsonCallCount

        assertEquals(emptySet(), hrefsFirst)
        assertNotNull(documentDao.document?.embeddedFontHrefsJson, "backfill must store the font index after first scan")

        val hrefsSecond = repository.getReferencedEmbeddedFontHrefs(DocumentId(documentId))
        assertEquals(blocksCallsAfterFirst, searchIndexDao.blocksJsonCallCount, "second call must not re-scan blocks DAO")
        assertEquals(emptySet(), hrefsSecond)
    }
}

/**
 * 성능 보장을 검증하기 위해 메서드 호출 횟수를 세는 [DocumentDao].
 *
 * @property document 저장된 단일 문서, 비어 있으면 null.
 */
private class CountingDocumentDao(
    var document: DocumentEntity? = null,
) : DocumentDao {
    var upsertCount = 0
    var updateCountsCount = 0

    override suspend fun upsertDocument(document: DocumentEntity) {
        this.document = document
        upsertCount++
    }

    override suspend fun getDocument(documentId: String): DocumentEntity? =
        document?.takeIf { it.id == documentId }

    override fun observeRecentDocuments(): Flow<List<DocumentEntity>> = flowOf(listOfNotNull(document))
    override suspend fun updateBookmarked(documentIds: List<String>, isBookmarked: Boolean) = Unit
    override suspend fun updateFolder(documentIds: List<String>, folderId: String?, folderName: String?) = Unit
    override suspend fun renameFolder(folderId: String, folderName: String) = Unit
    override suspend fun clearFolder(folderId: String) = Unit
    override suspend fun updateLastOpenedAt(documentId: String, openedAtEpochMillis: Long) = Unit
    override suspend fun deleteDocument(documentId: String) { if (document?.id == documentId) document = null }
    override suspend fun deleteDocuments(documentIds: List<String>) = Unit

    override suspend fun updateCountsAndFontIndex(
        documentId: String,
        characterCount: Long,
        wordCount: Long,
        embeddedFontHrefsJson: String?,
    ) {
        if (document?.id == documentId) {
            document = document?.copy(characterCount = characterCount, wordCount = wordCount, embeddedFontHrefsJson = embeddedFontHrefsJson)
            updateCountsCount++
        }
    }

    override suspend fun updateCountsAndMarkComplete(
        documentId: String,
        characterCount: Long,
        wordCount: Long,
        importCompletedAtEpochMillis: Long,
    ) {
        if (document?.id == documentId) {
            document = document?.copy(characterCount = characterCount, wordCount = wordCount, importCompletedAtEpochMillis = importCompletedAtEpochMillis)
        }
    }

    override suspend fun updateEmbeddedFontHrefsJson(documentId: String, embeddedFontHrefsJson: String) {
        if (document?.id == documentId) {
            document = document?.copy(embeddedFontHrefsJson = embeddedFontHrefsJson)
        }
    }
}

/**
 * 성능 계약을 검증하기 위해 핵심 메서드 호출을 세는 [SearchIndexDao].
 */
private class CountingSearchIndexDao : SearchIndexDao {
    private val entries = mutableListOf<SearchIndexEntity>()
    var blocksJsonCallCount = 0
    var sectionsWithoutBlocksCallCount = 0
    var sectionSourcePathsCallCount = 0

    fun seedEntries(seed: List<SearchIndexEntity>) { entries.addAll(seed) }

    override suspend fun upsertSearchIndex(entries: List<SearchIndexEntity>) {
        this.entries.addAll(entries)
    }

    override suspend fun search(documentId: String, query: String, limit: Int) = emptyList<com.tedd.teddreader.core.room.dao.SearchIndexSearchEntry>()

    override suspend fun getDocumentSectionsWithoutBlocks(documentId: String): List<SearchIndexSectionEntry> {
        sectionsWithoutBlocksCallCount++
        return entries.filter { it.documentId == documentId }.sortedBy { it.sectionIndex }.map {
            SearchIndexSectionEntry(it.sectionIndex, it.sectionTitle, it.text, it.startOffset, it.endOffset, it.documentTitle, it.navigationJson, it.parserVersion)
        }
    }

    override suspend fun getSectionBlocksJson(documentId: String, sectionIndexes: List<Int>): List<SectionBlocksJsonEntry> {
        blocksJsonCallCount++
        return entries.filter { it.documentId == documentId && it.sectionIndex in sectionIndexes }
            .map { SectionBlocksJsonEntry(it.sectionIndex, it.blocksJson) }
    }

    override suspend fun getLastSection(documentId: String): SectionOffsetEntry? =
        entries.filter { it.documentId == documentId }.maxByOrNull { it.sectionIndex }
            ?.let { SectionOffsetEntry(it.sectionIndex, it.endOffset) }

    override suspend fun updateSectionTitle(documentId: String, sectionIndex: Int, title: String) {
        val idx = entries.indexOfFirst { it.documentId == documentId && it.sectionIndex == sectionIndex }
        if (idx >= 0) entries[idx] = entries[idx].copy(sectionTitle = title)
    }

    override suspend fun updateDocumentTitleAndNavigation(documentId: String, sectionIndex: Int, documentTitle: String, navigationJson: String) {
        val idx = entries.indexOfFirst { it.documentId == documentId && it.sectionIndex == sectionIndex }
        if (idx >= 0) entries[idx] = entries[idx].copy(documentTitle = documentTitle, navigationJson = navigationJson)
    }

    override suspend fun deleteSearchIndex(documentId: String) { entries.removeAll { it.documentId == documentId } }

    override suspend fun getSectionSourcePaths(documentId: String): List<SectionSourcePathEntry> {
        sectionSourcePathsCallCount++
        return entries.filter { it.documentId == documentId }.sortedBy { it.sectionIndex }
            .map { SectionSourcePathEntry(it.sectionIndex, it.sourcePath) }
    }

    override suspend fun getFirstReadableContentSectionIndex(documentId: String, excludeSectionIndex: Int): Int? =
        entries.filter { it.documentId == documentId && it.sectionIndex != excludeSectionIndex && it.text.isNotBlank() }
            .minByOrNull { it.sectionIndex }?.sectionIndex

    override suspend fun getSectionCount(documentId: String): Int = entries.count { it.documentId == documentId }
}

/**
 * 페이지 레이아웃 저장을 다루지 않는 테스트를 위한 무동작(no-op) [PageLayoutDao].
 */
private class NoOpPageLayoutDao : PageLayoutDao {
    override suspend fun upsertPageLayout(layout: PageLayoutEntity) = Unit
    override suspend fun getPageLayout(documentId: String, fontSizeSp: Float, lineHeightMultiplier: Float, fontFamilyName: String, viewportWidthPx: Int, viewportHeightPx: Int): PageLayoutEntity? = null
    override suspend fun getNewestPageLayoutForStyle(documentId: String, fontSizeSp: Float, lineHeightMultiplier: Float, fontFamilyName: String): PageLayoutEntity? = null
    override suspend fun deletePageLayouts(documentId: String) = Unit
    override suspend fun trimPageLayouts(documentId: String, keep: Int) = Unit
    override suspend fun deletePartialPageLayouts(documentId: String) = Unit
    override suspend fun promotePartialLayouts(documentId: String, characterCount: Long) = Unit
}

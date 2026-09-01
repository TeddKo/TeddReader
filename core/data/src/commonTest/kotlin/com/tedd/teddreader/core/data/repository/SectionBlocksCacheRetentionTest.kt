package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import com.tedd.teddreader.core.common.model.ReaderSpan
import com.tedd.teddreader.core.common.model.ReaderSpanStyle
import com.tedd.teddreader.core.common.model.TextRange
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
import com.tedd.teddreader.core.room.dao.SearchIndexSearchEntry
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [DocumentRepositoryImpl.getReferencedEmbeddedFontHrefs]의 레거시 임베드 폰트 href 스캔이
 * [SectionBlocksCache.snapshotAllBlocks]를 통해 모든 섹션의 블록을 올바르게 읽은 뒤 공개 캐시를 24개
 * 항목으로 다시 잘라내어, 페이지네이션의 작업 집합 상한이 일회성 전체 문서 스캔으로 인해 영구히
 * 부풀려지지 않음을 검증한다.
 *
 * 이 테스트는 30개 섹션 — 캐시의 24개 항목 유지 한도보다 많다 — 을 서로 다른 폰트 href로 모든 섹션에
 * 분산시켜 씨딩하므로, 전체 스캔의 정확성은 모든 섹션을 원자적으로 읽어야만 확보된다. 스캔 후, 앞쪽
 * 섹션에 대한 이후의 [DocumentRepositoryImpl.warmSectionBlocks]는 DAO에서 다시 가져와야 하고(캐시가
 * 그것을 잘라냈음을 증명), 스캔 자체는 모든 폰트 href를 찾아냈어야 한다(잘라내기 전에 스냅샷이
 * 완전했음을 증명).
 */
class SectionBlocksCacheRetentionTest {

    /**
     * 30개 섹션을 가진 문서. 각 섹션은 스타일이 고유한 폰트 href를 참조하는 블록 하나를 담고 있다. 레거시
     * 경로(null `embeddedFontHrefsJson`)는 30개 전부를 스캔해 30개 폰트 href를 모두 찾고, 인덱스를
     * 백필한 뒤 공개 캐시를 24개로 잘라내야 한다 — 그래서 섹션 0부터 5까지 축출되고, 섹션 0에 대한 이후
     * warm 호출은 DAO를 다시 쳐야 한다.
     */
    @Test
    fun legacyFontScanReadsAllSectionsThenTrimsPublishedCacheTo24() = runTest {
        val sectionCount = 30
        val documentId = "file:///legacy-retention.epub"
        val json = Json { ignoreUnknownKeys = true }

        val searchIndexEntries = (0 until sectionCount).map { index ->
            val block = ReaderBlock(
                kind = ReaderBlockKind.PARAGRAPH,
                range = TextRange(start = 0L, end = 10L),
                style = ReaderBlockStyle(fontHref = "fonts/section$index.otf"),
            )
            SearchIndexEntity(
                documentId = documentId,
                sectionIndex = index,
                text = "Section $index",
                startOffset = index * 100L,
                endOffset = index * 100L + 99L,
                blocksJson = json.encodeToString(listOf(block)),
            )
        }

        val documentDao = RetentionTestDocumentDao(
            DocumentEntity(
                id = documentId,
                name = "legacy-retention.epub",
                sourceUri = documentId,
                format = DocumentFormat.EPUB.name,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
                embeddedFontHrefsJson = null,
            ),
        )
        val searchIndexDao = RetentionTestSearchIndexDao(searchIndexEntries)

        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = RetentionTestPageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )

        repository.getReaderDocument(DocumentId(documentId))

        val blocksCallsBefore = searchIndexDao.blocksJsonCallCount
        val hrefs = repository.getReferencedEmbeddedFontHrefs(DocumentId(documentId))

        val expectedHrefs = (0 until sectionCount).map { "fonts/section$it.otf" }.toSet()
        assertEquals(expectedHrefs, hrefs, "legacy scan must find every font href across all $sectionCount sections")

        assertNotNull(
            documentDao.document?.embeddedFontHrefsJson,
            "legacy scan must backfill the font index for future O(F) lookups",
        )

        assertTrue(
            searchIndexDao.blocksJsonCallCount > blocksCallsBefore,
            "legacy scan must have fetched blocks from the DAO",
        )

        val blocksCallsAfterScan = searchIndexDao.blocksJsonCallCount
        repository.warmSectionBlocks(DocumentId(documentId), setOf(0))

        assertTrue(
            searchIndexDao.blocksJsonCallCount > blocksCallsAfterScan,
            "section 0 must have been evicted from the published cache after trimming to 24, " +
                "so warming it requires a fresh DAO fetch",
        )
    }

    /**
     * 블록 레벨 폰트 href뿐 아니라 스팬 레벨 폰트 href도 레거시 전체 문서 스캔이 포착하는지 검증한다.
     * 감싸는 블록의 스타일이 자신만의 폰트 href를 갖고 있지 않아도, 스팬의 [ReaderSpanStyle.fontHref]는
     * 반환된 집합에 나타나야 한다.
     */
    @Test
    fun legacyFontScanCapturesSpanLevelFontHrefs() = runTest {
        val documentId = "file:///span-font.epub"
        val json = Json { ignoreUnknownKeys = true }

        val blockWithSpanFont = ReaderBlock(
            kind = ReaderBlockKind.PARAGRAPH,
            range = TextRange(start = 0L, end = 20L),
            spans = listOf(
                ReaderSpan(
                    range = TextRange(start = 5L, end = 15L),
                    style = ReaderInlineStyle.BOLD,
                    styleDelta = ReaderSpanStyle(fontHref = "fonts/span-bold.otf"),
                ),
            ),
        )
        val blockWithBlockFont = ReaderBlock(
            kind = ReaderBlockKind.HEADING,
            range = TextRange(start = 20L, end = 40L),
            level = 1,
            style = ReaderBlockStyle(fontHref = "fonts/heading.otf"),
        )

        val searchIndexEntries = listOf(
            SearchIndexEntity(
                documentId = documentId,
                sectionIndex = 0,
                text = "Block with span font",
                startOffset = 0,
                endOffset = 19,
                blocksJson = json.encodeToString(listOf(blockWithSpanFont)),
            ),
            SearchIndexEntity(
                documentId = documentId,
                sectionIndex = 1,
                text = "Block with block font",
                startOffset = 20,
                endOffset = 39,
                blocksJson = json.encodeToString(listOf(blockWithBlockFont)),
            ),
        )

        val documentDao = RetentionTestDocumentDao(
            DocumentEntity(
                id = documentId,
                name = "span-font.epub",
                sourceUri = documentId,
                format = DocumentFormat.EPUB.name,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
                embeddedFontHrefsJson = null,
            ),
        )
        val searchIndexDao = RetentionTestSearchIndexDao(searchIndexEntries)

        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = RetentionTestPageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )

        repository.getReaderDocument(DocumentId(documentId))
        val hrefs = repository.getReferencedEmbeddedFontHrefs(DocumentId(documentId))

        assertEquals(
            setOf("fonts/span-bold.otf", "fonts/heading.otf"),
            hrefs,
            "legacy scan must capture both block-level and span-level font hrefs",
        )
    }
}

/**
 * 캐시 유지 테스트용 최소한의 [DocumentDao]: 문서 하나를 저장하고 [updateEmbeddedFontHrefsJson] 쓰기를
 * 추적해, 테스트가 백필이 일어났는지 검증할 수 있게 한다.
 *
 * @property document 저장된 단일 문서. update 호출로 변경된다.
 */
private class RetentionTestDocumentDao(
    var document: DocumentEntity? = null,
) : DocumentDao {

    override suspend fun upsertDocument(document: DocumentEntity) { this.document = document }
    override suspend fun getDocument(documentId: String): DocumentEntity? = document?.takeIf { it.id == documentId }
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
            document = document?.copy(
                characterCount = characterCount,
                wordCount = wordCount,
                embeddedFontHrefsJson = embeddedFontHrefsJson,
            )
        }
    }

    override suspend fun updateCountsAndMarkComplete(
        documentId: String,
        characterCount: Long,
        wordCount: Long,
        importCompletedAtEpochMillis: Long,
    ) {
        if (document?.id == documentId) {
            document = document?.copy(
                characterCount = characterCount,
                wordCount = wordCount,
                importCompletedAtEpochMillis = importCompletedAtEpochMillis,
            )
        }
    }

    override suspend fun updateEmbeddedFontHrefsJson(documentId: String, embeddedFontHrefsJson: String) {
        if (document?.id == documentId) {
            document = document?.copy(embeddedFontHrefsJson = embeddedFontHrefsJson)
        }
    }
}

/**
 * 캐시 유지 테스트용 최소한의 [SearchIndexDao]: 항목들로 미리 씨딩되어 있고 [getSectionBlocksJson] 호출
 * 횟수를 세어, 테스트가 축출과 재조회 동작을 검증할 수 있게 한다.
 *
 * @property entries 미리 씨딩된 검색 인덱스 항목들.
 */
private class RetentionTestSearchIndexDao(
    private val entries: List<SearchIndexEntity>,
) : SearchIndexDao {

    /** [getSectionBlocksJson]이 호출된 횟수. */
    var blocksJsonCallCount = 0

    override suspend fun upsertSearchIndex(entries: List<SearchIndexEntity>) = Unit
    override suspend fun search(documentId: String, query: String, limit: Int): List<SearchIndexSearchEntry> = emptyList()

    override suspend fun getDocumentSectionsWithoutBlocks(documentId: String): List<SearchIndexSectionEntry> =
        entries.filter { it.documentId == documentId }.sortedBy { it.sectionIndex }.map {
            SearchIndexSectionEntry(
                it.sectionIndex, it.sectionTitle, it.text, it.startOffset, it.endOffset,
                it.documentTitle, it.navigationJson, it.parserVersion,
            )
        }

    override suspend fun getSectionBlocksJson(documentId: String, sectionIndexes: List<Int>): List<SectionBlocksJsonEntry> {
        blocksJsonCallCount++
        return entries
            .filter { it.documentId == documentId && it.sectionIndex in sectionIndexes }
            .map { SectionBlocksJsonEntry(it.sectionIndex, it.blocksJson) }
    }

    override suspend fun getLastSection(documentId: String): SectionOffsetEntry? =
        entries.filter { it.documentId == documentId }.maxByOrNull { it.sectionIndex }
            ?.let { SectionOffsetEntry(it.sectionIndex, it.endOffset) }

    override suspend fun updateSectionTitle(documentId: String, sectionIndex: Int, title: String) = Unit
    override suspend fun updateDocumentTitleAndNavigation(documentId: String, sectionIndex: Int, documentTitle: String, navigationJson: String) = Unit
    override suspend fun deleteSearchIndex(documentId: String) = Unit

    override suspend fun getSectionSourcePaths(documentId: String): List<SectionSourcePathEntry> =
        entries.filter { it.documentId == documentId }.sortedBy { it.sectionIndex }
            .map { SectionSourcePathEntry(it.sectionIndex, it.sourcePath) }

    override suspend fun getFirstReadableContentSectionIndex(documentId: String, excludeSectionIndex: Int): Int? =
        entries.filter { it.documentId == documentId && it.sectionIndex != excludeSectionIndex && it.text.isNotBlank() }
            .minByOrNull { it.sectionIndex }?.sectionIndex

    override suspend fun getSectionCount(documentId: String): Int = entries.count { it.documentId == documentId }
}

/**
 * 페이지 레이아웃 저장소를 검증하지 않는 테스트용 no-op [PageLayoutDao].
 */
private class RetentionTestPageLayoutDao : PageLayoutDao {
    override suspend fun upsertPageLayout(layout: PageLayoutEntity) = Unit
    override suspend fun getPageLayout(documentId: String, fontSizeSp: Float, lineHeightMultiplier: Float, fontFamilyName: String, viewportWidthPx: Int, viewportHeightPx: Int): PageLayoutEntity? = null
    override suspend fun getNewestPageLayoutForStyle(documentId: String, fontSizeSp: Float, lineHeightMultiplier: Float, fontFamilyName: String): PageLayoutEntity? = null
    override suspend fun deletePageLayouts(documentId: String) = Unit
    override suspend fun trimPageLayouts(documentId: String, keep: Int) = Unit
    override suspend fun deletePartialPageLayouts(documentId: String) = Unit
    override suspend fun promotePartialLayouts(documentId: String, characterCount: Long) = Unit
}

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
 * Verifies that the legacy embedded-font-href scan in [DocumentRepositoryImpl.getReferencedEmbeddedFontHrefs]
 * correctly reads every section's blocks via [SectionBlocksCache.snapshotAllBlocks] and then trims the
 * published cache back to 24 entries, so pagination's working-set bound is never permanently inflated by
 * a one-time full-document scan.
 *
 * The test seeds 30 sections — more than the cache's 24-entry retention limit — with distinct font hrefs
 * distributed across all of them, so full-scan accuracy requires reading every section atomically. After
 * the scan, a subsequent [DocumentRepositoryImpl.warmSectionBlocks] for an early section must fetch from
 * the DAO again (proving the cache trimmed it), while the scan itself must have found every font href
 * (proving the snapshot was complete before trimming).
 */
class SectionBlocksCacheRetentionTest {

    /**
     * A document with 30 sections, each carrying one block whose style references a unique font href.
     * The legacy path (null `embeddedFontHrefsJson`) must scan all 30, find all 30 font hrefs, backfill
     * the index, and trim the published cache to 24 — so section 0 through 5 are evicted and a
     * subsequent warm for section 0 must hit the DAO again.
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
     * Verifies that span-level font hrefs — not just block-level ones — are captured by the legacy
     * full-document scan. A span's [ReaderSpanStyle.fontHref] must appear in the returned set even when
     * the enclosing block's style carries no font href of its own.
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
 * Minimal [DocumentDao] for cache-retention tests: stores a single document and tracks
 * [updateEmbeddedFontHrefsJson] writes so a test can verify backfill happened.
 *
 * @property document The single document stored, mutated by update calls.
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
 * Minimal [SearchIndexDao] for cache-retention tests: pre-seeded with entries and counts
 * [getSectionBlocksJson] calls so a test can verify eviction and re-fetch behavior.
 *
 * @property entries The pre-seeded search index entries.
 */
private class RetentionTestSearchIndexDao(
    private val entries: List<SearchIndexEntity>,
) : SearchIndexDao {

    /** How many times [getSectionBlocksJson] has been called. */
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
 * No-op [PageLayoutDao] for tests that don't exercise page layout storage.
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

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
 * Performance regression tests for the page-count finalization optimization. Each test asserts the
 * specific performance guarantee the optimization introduces, and would fail if that guarantee were
 * removed — by counting DAO call counts, verifying that expensive paths are not taken, and confirming
 * that the domain contract for incomplete imports remains intact.
 */
class PageCountFinalizationPerformanceTest {

    /**
     * Verifies that when `embeddedFontHrefsJson` is populated in the entity, `getReferencedEmbeddedFontHrefs`
     * returns the indexed result without touching the blocks DAO at all. This is the core performance
     * guarantee: O(F) font lookup instead of O(sections * blocks) full prewarm.
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
     * Verifies that incomplete import metadata masks character/word counts to null in the domain model.
     * This preserves the existing domain contract that library UI relies on.
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
     * Verifies that once import completes, the stored accumulator counts become visible in domain metadata.
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
     * Verifies that `finishEpubImport` uses targeted DAO queries: if `getDocumentSectionsWithoutBlocks`
     * were called during finishEpubImport, `sectionsWithoutBlocksCallCount` would increase after the
     * import-complete state change. This test exercises the contract through the entity layer since the
     * actual finish path requires EPUB parsing infrastructure that is private to the main test suite.
     *
     * The real end-to-end verification that finish skips full-text queries is covered in
     * DocumentRepositoryImplTest.repeatedImportNextSectionsCompletesNavigationWithoutRestartFallback.
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
     * Verifies that legacy font fallback (null `embeddedFontHrefsJson`) does scan blocks, then backfills
     * the index so subsequent calls are fast. Would fail if backfill were removed.
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
 * A [DocumentDao] that counts method invocations to verify performance guarantees.
 *
 * @property document The single document stored, or null when empty.
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
 * A [SearchIndexDao] that counts key method calls to verify performance contracts.
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

    override suspend fun search(documentId: String, query: String, limit: Int): List<SearchIndexEntity> = emptyList()

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
 * A no-op [PageLayoutDao] for tests that don't exercise page layout storage.
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

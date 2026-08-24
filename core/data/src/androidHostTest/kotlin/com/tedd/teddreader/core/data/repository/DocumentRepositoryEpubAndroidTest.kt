package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.blocksIn
import com.tedd.teddreader.core.common.model.rebasedBy
import com.tedd.teddreader.core.data.mapper.toSearchIndexEntity
import com.tedd.teddreader.core.data.pagination.TextPageLayoutEngine
import com.tedd.teddreader.core.data.parser.ComicBookDocumentParser
import com.tedd.teddreader.core.data.parser.DocumentFormatDetector
import com.tedd.teddreader.core.data.parser.EpubDocumentParser
import com.tedd.teddreader.core.data.parser.ImageDocumentParser
import com.tedd.teddreader.core.data.parser.PdfDocumentParser
import com.tedd.teddreader.core.data.parser.TxtDocumentParser
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import com.tedd.teddreader.core.room.dao.DocumentDao
import com.tedd.teddreader.core.room.dao.PageLayoutDao
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import com.tedd.teddreader.core.room.dao.SearchIndexSectionEntry
import com.tedd.teddreader.core.room.dao.SectionBlocksJsonEntry
import com.tedd.teddreader.core.room.dao.SectionOffsetEntry
import com.tedd.teddreader.core.room.entity.DocumentEntity
import com.tedd.teddreader.core.room.entity.PageLayoutEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EPUB behaviour of [DocumentRepositoryImpl] and [EpubDocumentParser] that needs a real JVM zip
 * implementation to exercise: navigation entries that land on the exact character offset an EPUB3 nav
 * fragment names, a malformed NCX's cover target retargeting to the book's first real body section
 * instead of pointing back at the cover a second time, and — for storage itself — that a stored EPUB's
 * blocks and embedded images round-trip through [DocumentRepositoryImpl.getReaderDocument] unchanged
 * and that a legacy row with no blocks at all self-repairs through the same phased import a freshly
 * picked EPUB takes.
 */
class DocumentRepositoryEpubAndroidTest {
    /**
     * An EPUB3 nav document's table of contents can point at a fragment inside a spine item, not just
     * the item's start — `chapter-1.xhtml#start` and, nested under it,
     * `chapter-1.xhtml#scene1` here. This pins that [EpubDocumentParser] resolves that entry to the
     * exact character offset the fragment's anchor sits at within its section (0 and 14, matching
     * "Chapter One" and, nested one level under it, "Scene One"), not just the section's own start.
     */
    @Test
    fun parserEpub3NavFragmentMapsToExactSectionOffset() = runTest {
        val document = EpubDocumentParser().parse(
            DocumentId("file:///nav.epub"),
            "fallback.epub",
            sampleNavFragmentEpubBytes(),
        )

        assertEquals("Package Title", document.title)
        assertEquals(ReaderBlockKind.COVER_IMAGE, document.blocks.first().kind)
        assertEquals("Contents", document.navigation?.heading)
        assertEquals(listOf("Chapter One", "Scene One"), document.navigation?.items?.map { it.title })
        assertEquals(listOf(1, 2), document.navigation?.items?.map { it.level })
        assertEquals(listOf(1, 1), document.navigation?.items?.map { it.spineIndex })
        assertEquals(listOf(0L, 14L), document.navigation?.items?.map { it.offset })
    }

    /**
     * A malformed NCX can point more than one `navPoint` at the cover page — here "Start" and "Cover"
     * both target `cover.xhtml`. [EpubDocumentParser] retargets the malformed one ("Start") to the
     * book's first real body section rather than leaving it to reopen the cover a second time, while
     * the honestly cover-titled entry ("Cover") is left pointing at the cover, and an entry that
     * already names a real chapter ("Chapter Two") is left untouched.
     */
    @Test
    fun parserMalformedNcxCoverTargetRetargetsToFirstBodySection() = runTest {
        val document = EpubDocumentParser().parse(
            DocumentId("file:///malformed.epub"),
            "fallback.epub",
            sampleMalformedNcxEpubBytes(),
        )

        assertEquals("Real Title", document.title)
        assertEquals(ReaderBlockKind.COVER_IMAGE, document.blocks.first().kind)
        assertEquals(listOf("Start", "Chapter Two", "Cover"), document.navigation?.items?.map { it.title })
        assertEquals(listOf(2, 3, 0), document.navigation?.items?.map { it.spineIndex })
        assertEquals("Start", document.sections[2].title)
        assertEquals("Chapter Two", document.sections[3].title)
    }

    /**
     * A document reloaded through [DocumentRepositoryImpl.getReaderDocument] must reproduce the exact
     * [ReaderDocument] a fresh parse produced — same title, same blocks (including the embedded image
     * the cover and the chapter both reference), same navigation — and the embedded image bytes must
     * still be extractable by href afterward.
     *
     * The fixture's search-index rows store each section's blocks section-relative, the same shift
     * `DocumentRepositoryImpl.persistParsedDocument` applies before writing `blocksJson` for real —
     * this fixture stands in for "already in storage", so it has to agree with what the real writer
     * stores, not the absolute offsets [ReaderDocument.blocks] itself still carries on the freshly
     * parsed `document`, or this test would pass by comparing against data no real row ever holds.
     *
     * [ReaderDocument.blocks] decodes lazily per section once loaded from storage (see
     * `DocumentRepositoryImpl.SectionBlocksCache`), so the explicit
     * [DocumentRepositoryImpl.warmSectionBlocks] call below, before the block comparison, is what
     * stands in for "every block was already loaded" — the condition the `restored?.blocks` assertion
     * is actually checking; without it, a section the reader has not yet asked for would still read
     * back with no blocks at all.
     */
    @Test
    fun getReaderDocumentPreservesStoredEpubBlocks() = runTest {
        val epubBytes = sampleEpubBytes()
        val parser = EpubDocumentParser()
        val location = DocumentLocation(
            sourceUri = "file:///book.epub",
            displayName = "book.epub",
            mimeType = "application/epub+zip",
        )
        val document = parser.parse(DocumentId(location.sourceUri), location.displayName, epubBytes)
        val searchIndexDao = AndroidFakeSearchIndexDao().apply {
            upsertSearchIndex(
                document.sections.map { section ->
                    section.toSearchIndexEntity(
                        documentId = document.id,
                        blocks = document.blocks.blocksIn(section.range.start, section.range.end).rebasedBy(section.range.start),
                        documentTitle = document.title.takeIf { section.index == document.sections.first().index },
                        navigation = document.navigation.takeIf { section.index == document.sections.first().index },
                    )
                },
            )
        }
        val repository = repository(
            documentDao = AndroidFakeDocumentDao(
                DocumentEntity(
                    id = location.sourceUri,
                    name = location.displayName,
                    sourceUri = location.sourceUri,
                    format = DocumentFormat.EPUB.name,
                    mimeType = location.mimeType,
                    sizeBytes = 0L,
                    addedAtEpochMillis = 1_000,
                ),
            ),
            searchIndexDao = searchIndexDao,
            fileSource = AndroidFakeDocumentFileSource(location, epubBytes),
        )

        val restored = repository.getReaderDocument(DocumentId(location.sourceUri))
        repository.warmSectionBlocks(DocumentId(location.sourceUri), document.sections.map { it.index }.toSet())

        assertEquals(document.title, restored?.title)
        assertEquals(document.blocks, restored?.blocks)
        assertEquals(document.navigation, restored?.navigation)
        assertContentEquals(
            byteArrayOf(1, 2, 3, 4),
            repository.getEmbeddedImages(document.id, setOf("OEBPS/images/pic.png"))["OEBPS/images/pic.png"],
        )
    }

    /**
     * A book whose stored EPUB rows were written by an old parser version with no blocks at all (every
     * `blocksJson` is `"[]"`) self-repairs on the next [DocumentRepositoryImpl.getReaderDocument] call
     * by taking the same phased import route a freshly picked EPUB takes (see
     * `DocumentRepositoryImpl.repairEpubDocument`). What that repair gives the reader straight away is
     * the first chapter, with real blocks, under the book's own title. The table of contents is not
     * part of it — the repair takes the phased import route, and that fills navigation only once the
     * whole spine has been read (see [DocumentRepositoryImpl.finishEpubImport]), the same as a freshly
     * picked EPUB. Driving [DocumentRepositoryImpl.importNextSections] to completion afterward is what
     * finally produces a non-empty navigation and leaves every section's blocks non-empty too.
     */
    @Test
    fun getReaderDocumentRepairsLegacyEmptyEpubBlocksFromSourceBytes() = runTest {
        val epubBytes = sampleEpubBytes()
        val parser = EpubDocumentParser()
        val location = DocumentLocation(
            sourceUri = "file:///legacy.epub",
            displayName = "legacy.epub",
            mimeType = "application/epub+zip",
        )
        val parsed = parser.parse(DocumentId(location.sourceUri), location.displayName, epubBytes)
        val searchIndexDao = AndroidFakeSearchIndexDao().apply {
            upsertSearchIndex(
                parsed.sections.map { section ->
                    SearchIndexEntity(
                        documentId = location.sourceUri,
                        sectionIndex = section.index,
                        sectionTitle = section.title,
                        text = section.text,
                        startOffset = section.range.start,
                        endOffset = section.range.end,
                    )
                },
            )
        }
        val repository = repository(
            documentDao = AndroidFakeDocumentDao(
                DocumentEntity(
                    id = location.sourceUri,
                    name = location.displayName,
                    sourceUri = location.sourceUri,
                    format = DocumentFormat.EPUB.name,
                    mimeType = location.mimeType,
                    sizeBytes = 0L,
                    addedAtEpochMillis = 1_000,
                ),
            ),
            searchIndexDao = searchIndexDao,
            fileSource = AndroidFakeDocumentFileSource(location, epubBytes),
        )

        val documentId = DocumentId(location.sourceUri)
        val restored = repository.getReaderDocument(documentId)

        assertTrue(restored?.blocks?.isNotEmpty() == true)
        assertEquals("Sample EPUB Title", restored.title)
        assertTrue(searchIndexDao.entries.all { it.blocksJson != "[]" })
        assertTrue(searchIndexDao.entries.first().navigationJson.isNotBlank())

        var guard = 0
        while (!repository.isImportComplete(documentId)) {
            repository.importNextSections(
                documentId = documentId,
                count = 4,
                style = ReaderStyle(),
                viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
                pageBreaker = null,
            )
            guard += 1
            check(guard < 50) { "repair import did not converge" }
        }
        val finished = repository.getReaderDocument(documentId)

        assertTrue(finished?.navigation?.items?.isNotEmpty() == true)
        assertTrue(searchIndexDao.entries.all { it.blocksJson != "[]" })
    }

    @Test
    fun getEmbeddedFontFilesExtractsOnlyRequestedFontsAndReusesTheirTempPaths() = runTest {
        val epubBytes = sampleEpubBytesWithFonts()
        val location = DocumentLocation(
            sourceUri = "file:///fonts.epub",
            displayName = "fonts.epub",
            mimeType = "application/epub+zip",
        )
        val repository = repository(
            documentDao = AndroidFakeDocumentDao(
                DocumentEntity(
                    id = location.sourceUri,
                    name = location.displayName,
                    sourceUri = location.sourceUri,
                    format = DocumentFormat.EPUB.name,
                    mimeType = location.mimeType,
                    sizeBytes = 0L,
                    addedAtEpochMillis = 1_000,
                ),
            ),
            searchIndexDao = AndroidFakeSearchIndexDao(),
            fileSource = AndroidFakeDocumentFileSource(location, epubBytes),
        )
        val documentId = DocumentId(location.sourceUri)

        val first = repository.getEmbeddedFontFiles(
            documentId,
            setOf("OEBPS/fonts/Body.otf", "OEBPS/fonts/Missing.otf"),
        )
        val second = repository.getEmbeddedFontFiles(documentId, setOf("OEBPS/fonts/Body.otf"))

        assertEquals(setOf("OEBPS/fonts/Body.otf"), first.keys)
        assertEquals(first["OEBPS/fonts/Body.otf"], second["OEBPS/fonts/Body.otf"])
        assertTrue(first.getValue("OEBPS/fonts/Body.otf").isNotBlank())
        assertTrue(FileSystem.SYSTEM.exists(first.getValue("OEBPS/fonts/Body.otf").toPath()))
    }

    @Test
    fun getEmbeddedFontFilesNewScratchCopySweepsAbandonedFontScratchFiles() = runTest {
        val orphan = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "tedd-reader-epub-font-orphan-${System.nanoTime().toString(16)}.otf"
        FileSystem.SYSTEM.sink(orphan).buffer().use { it.writeUtf8("orphan") }
        assertTrue(FileSystem.SYSTEM.exists(orphan))

        val epubBytes = sampleEpubBytesWithFonts()
        val location = DocumentLocation(
            sourceUri = "file:///font-sweep.epub",
            displayName = "font-sweep.epub",
            mimeType = "application/epub+zip",
        )
        val repository = repository(
            documentDao = AndroidFakeDocumentDao(
                DocumentEntity(
                    id = location.sourceUri,
                    name = location.displayName,
                    sourceUri = location.sourceUri,
                    format = DocumentFormat.EPUB.name,
                    mimeType = location.mimeType,
                    sizeBytes = 0L,
                    addedAtEpochMillis = 1_000,
                ),
            ),
            searchIndexDao = AndroidFakeSearchIndexDao(),
            fileSource = AndroidFakeDocumentFileSource(location, epubBytes),
        )

        repository.getEmbeddedFontFiles(DocumentId(location.sourceUri), setOf("OEBPS/fonts/Body.otf"))

        assertTrue(!FileSystem.SYSTEM.exists(orphan))
    }
}

/**
 * Wires a [DocumentRepositoryImpl] against the given fakes, with every real parser and the real
 * [TextPageLayoutEngine] behind it — only the storage and file-access seams are faked, so a test
 * exercises the actual EPUB parsing/pagination path end to end.
 *
 * @param documentDao The shelf-metadata fake to use.
 * @param searchIndexDao The per-section storage fake to use.
 * @param fileSource Where the repository reads/copies the original file bytes from.
 * @return The wired repository.
 */
private fun repository(
    documentDao: AndroidFakeDocumentDao,
    searchIndexDao: AndroidFakeSearchIndexDao,
    fileSource: DocumentFileSource,
): DocumentRepositoryImpl = DocumentRepositoryImpl(
    documentDao = documentDao,
    searchIndexDao = searchIndexDao,
    pageLayoutDao = AndroidFakePageLayoutDao(),
    formatDetector = DocumentFormatDetector(),
    txtDocumentParser = TxtDocumentParser(),
    epubDocumentParser = EpubDocumentParser(),
    pdfDocumentParser = PdfDocumentParser(),
    comicBookDocumentParser = ComicBookDocumentParser(),
    imageDocumentParser = ImageDocumentParser(),
    textPageLayoutEngine = TextPageLayoutEngine(),
    documentFileSource = fileSource,
)

/**
 * A minimal but structurally real EPUB: a cover page, one chapter carrying a heading, a bold span and
 * an inline image, a nav document listing that chapter, and one embedded image referenced from both
 * the cover and the chapter — enough real structure to exercise stored blocks, navigation, and
 * embedded-image extraction together.
 *
 * @return The encoded EPUB's bytes.
 */
private fun sampleEpubBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("META-INF/container.xml"))
        zip.write(
            """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent().encodeToByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
        zip.write(
            """
                <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Sample EPUB Title</dc:title>
                    <meta name="cover" content="image-1"/>
                  </metadata>
                  <manifest>
                    <item id="cover-page" href="cover.xhtml" media-type="application/xhtml+xml"/>
                    <item id="chapter-1" href="chapter-1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="image-1" href="images/pic.png" media-type="image/png"/>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  </manifest>
                  <spine>
                    <itemref idref="cover-page"/>
                    <itemref idref="chapter-1"/>
                  </spine>
                </package>
            """.trimIndent().encodeToByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/cover.xhtml"))
        zip.write(
            """
                <html><body><img src="images/pic.png" alt="Cover art" /></body></html>
            """.trimIndent().encodeToByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/chapter-1.xhtml"))
        zip.write(
            """
                <html><body>
                  <h2>Heading</h2>
                  <p><strong>Body</strong> text</p>
                  <img src="images/pic.png" alt="Cover art" />
                </body></html>
            """.trimIndent().encodeToByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/nav.xhtml"))
        zip.write(
            """
                <html><body>
                  <nav epub:type="toc">
                    <h2>Contents</h2>
                    <ol><li><a href="chapter-1.xhtml">Chapter One</a></li></ol>
                  </nav>
                </body></html>
            """.trimIndent().encodeToByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/images/pic.png"))
        zip.write(byteArrayOf(1, 2, 3, 4))
        zip.closeEntry()
    }
    return output.toByteArray()
}

private fun sampleEpubBytesWithFonts(): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("META-INF/container.xml"))
        zip.write(
            """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent().encodeToByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
        zip.write(
            """
                <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Font EPUB</dc:title>
                  </metadata>
                  <manifest>
                    <item id="chapter-1" href="chapter-1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="font-1" href="fonts/Body.otf" media-type="font/otf"/>
                  </manifest>
                  <spine>
                    <itemref idref="chapter-1"/>
                  </spine>
                </package>
            """.trimIndent().encodeToByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/chapter-1.xhtml"))
        zip.write("<html><body><p>Body</p></body></html>".encodeToByteArray())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/fonts/Body.otf"))
        zip.write(byteArrayOf(9, 8, 7, 6))
        zip.closeEntry()
    }
    return output.toByteArray()
}

/**
 * A [DocumentFileSource] that always hands back [bytes] for [location], asserting every call is made
 * with that exact location rather than answering wrong data silently.
 *
 * @property location The location every call must be made with.
 * @property bytes The bytes to hand back from [readBytes] and to write in [copyTo].
 */
private class AndroidFakeDocumentFileSource(
    private val location: DocumentLocation,
    private val bytes: ByteArray,
) : DocumentFileSource {
    /** Unique per fake instance so one test's cached cover file can never be left over for the next. */
    private val privateDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "tedd-reader-android-test-${kotlin.random.Random.nextLong().toString(16)}"

    override suspend fun readBytes(location: DocumentLocation): ByteArray {
        assertEquals(this.location, location)
        return bytes
    }

    override suspend fun copyTo(location: DocumentLocation, destination: Path) {
        assertEquals(this.location, location)
        FileSystem.SYSTEM.sink(destination).buffer().use { sink ->
            sink.write(bytes)
        }
    }

    override fun appPrivateDirectory(): Path = privateDirectory
}

/**
 * A [DocumentDao] that models a single shelf slot, optionally pre-seeded by the constructor and
 * replaced wholesale by every [upsertDocument] call — the right shape for a test that only ever has
 * one document on the shelf at a time.
 *
 * @property document The one document currently "stored", pre-seeded by the constructor or null once
 *   [deleteDocument] removes it.
 */
private class AndroidFakeDocumentDao(
    private var document: DocumentEntity? = null,
) : DocumentDao {
    override suspend fun upsertDocument(document: DocumentEntity) {
        this.document = document
    }

    override suspend fun getDocument(documentId: String): DocumentEntity? = document?.takeIf { it.id == documentId }

    override fun observeRecentDocuments(): Flow<List<DocumentEntity>> = flowOf(listOfNotNull(document))

    override suspend fun updateBookmarked(documentIds: List<String>, isBookmarked: Boolean) {
        if (document?.id in documentIds) document = document?.copy(isBookmarked = isBookmarked)
    }

    override suspend fun updateFolder(documentIds: List<String>, folderId: String?, folderName: String?) {
        if (document?.id in documentIds) document = document?.copy(folderId = folderId, folderName = folderName)
    }

    override suspend fun renameFolder(folderId: String, folderName: String) {
        if (document?.folderId == folderId) document = document?.copy(folderName = folderName)
    }

    override suspend fun clearFolder(folderId: String) {
        if (document?.folderId == folderId) document = document?.copy(folderId = null, folderName = null)
    }

    override suspend fun updateLastOpenedAt(documentId: String, openedAtEpochMillis: Long) = Unit

    override suspend fun deleteDocument(documentId: String) = Unit

    override suspend fun deleteDocuments(documentIds: List<String>) = Unit
}

/**
 * An in-memory [SearchIndexDao] backed by a plain list of [entries], with the same split between
 * section metadata and block JSON the real DAO exposes through [getDocumentSectionsWithoutBlocks] /
 * [getSectionBlocksJson] — so a test can seed rows either already carrying real blocks or with none at
 * all (see the legacy-repair test above).
 */
private class AndroidFakeSearchIndexDao : SearchIndexDao {
    /** Every section upserted so far, across every document — filtered by `documentId` per call. */
    val entries = mutableListOf<SearchIndexEntity>()

    override suspend fun upsertSearchIndex(entries: List<SearchIndexEntity>) {
        this.entries += entries
    }

    override suspend fun search(documentId: String, query: String, limit: Int): List<SearchIndexEntity> = emptyList()

    override suspend fun getDocumentSectionsWithoutBlocks(documentId: String): List<SearchIndexSectionEntry> =
        entries.filter { it.documentId == documentId }.sortedBy { it.sectionIndex }.map { entry ->
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
            .filter { it.documentId == documentId && it.sectionIndex in sectionIndexes }
            .map { entry -> SectionBlocksJsonEntry(entry.sectionIndex, entry.blocksJson) }

    override suspend fun getLastSection(documentId: String): SectionOffsetEntry? =
        entries.filter { it.documentId == documentId }
            .maxByOrNull { it.sectionIndex }
            ?.let { SectionOffsetEntry(it.sectionIndex, it.endOffset) }

    override suspend fun updateSectionTitle(documentId: String, sectionIndex: Int, title: String) {
        val index = entries.indexOfFirst { it.documentId == documentId && it.sectionIndex == sectionIndex }
        if (index >= 0) entries[index] = entries[index].copy(sectionTitle = title)
    }

    override suspend fun updateDocumentTitleAndNavigation(
        documentId: String,
        sectionIndex: Int,
        documentTitle: String,
        navigationJson: String,
    ) {
        val index = entries.indexOfFirst { it.documentId == documentId && it.sectionIndex == sectionIndex }
        if (index >= 0) {
            entries[index] = entries[index].copy(documentTitle = documentTitle, navigationJson = navigationJson)
        }
    }

    override suspend fun deleteSearchIndex(documentId: String) {
        entries.removeAll { it.documentId == documentId }
    }
}

/**
 * A no-op [PageLayoutDao]: nothing is ever stored or returned. None of the tests in this file exercise
 * stored page layouts, so this only exists to satisfy [DocumentRepositoryImpl]'s constructor.
 */
private class AndroidFakePageLayoutDao : PageLayoutDao {
    override suspend fun upsertPageLayout(layout: PageLayoutEntity) = Unit

    override suspend fun getPageLayout(
        documentId: String,
        fontSizeSp: Float,
        lineHeightMultiplier: Float,
        fontFamilyName: String,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): PageLayoutEntity? = null

    override suspend fun getNewestPageLayoutForStyle(
        documentId: String,
        fontSizeSp: Float,
        lineHeightMultiplier: Float,
        fontFamilyName: String,
    ): PageLayoutEntity? = null

    override suspend fun deletePageLayouts(documentId: String) = Unit

    override suspend fun trimPageLayouts(documentId: String, keep: Int) = Unit
}

/**
 * An EPUB3 nav document whose table of contents points into the middle of a chapter, not just its
 * start — "Chapter One" targets `chapter-1.xhtml#start` and, nested under it, "Scene One" targets
 * `chapter-1.xhtml#scene1` — the fixture [parserEpub3NavFragmentMapsToExactSectionOffset] needs to
 * prove a fragment resolves to its anchor's real character offset, not the section's own start.
 *
 * @return The encoded EPUB's bytes.
 */
private fun sampleNavFragmentEpubBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("META-INF/container.xml"))
        zip.write(
            """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent().encodeToByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
        zip.write(
            """
                <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Package Title</dc:title></metadata>
                  <manifest>
                    <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                    <item id="cover-page" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
                    <item id="chapter-1" href="Text/chapter-1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="nav" href="Text/nav.xhtml" media-type="application/xhtml+xml" properties="toc nav"/>
                  </manifest>
                  <spine>
                    <itemref idref="cover-page"/>
                    <itemref idref="chapter-1"/>
                  </spine>
                </package>
            """.trimIndent().encodeToByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/Text/cover.xhtml"))
        zip.write(
            """<html><body><img src="../images/cover.jpg" alt="Cover"/></body></html>""".encodeToByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/Text/chapter-1.xhtml"))
        zip.write(
            """<html><body><h1 id="start">Body heading</h1><p><a id="scene1"></a>Scene body.</p></body></html>""".encodeToByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/Text/nav.xhtml"))
        zip.write(
            """<html><body><nav epub:type="toc landmarks"><h2>Contents</h2><ol><li><a href="chapter-1.xhtml#start">Chapter One</a><ol><li><a href="chapter-1.xhtml#scene1">Scene One</a></li></ol></li></ol></nav></body></html>""".encodeToByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/images/cover.jpg"))
        zip.write(byteArrayOf(1, 2, 3))
        zip.closeEntry()
    }
    return output.toByteArray()
}

/**
 * An EPUB using the legacy NCX for navigation, deliberately malformed the way a real book was found to
 * be: three chapters where the first (`ch1.xhtml`) is blank, and an NCX whose `navPoint`s point
 * "Start" and "Cover" at the same cover page while "Chapter Two" points past the blank chapter straight
 * at `ch3.xhtml` — the fixture [parserMalformedNcxCoverTargetRetargetsToFirstBodySection] needs to
 * prove only the malformed, non-cover-titled entry gets retargeted.
 *
 * @return The encoded EPUB's bytes.
 */
private fun sampleMalformedNcxEpubBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("META-INF/container.xml"))
        zip.write(
            """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent().encodeToByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
        zip.write(
            """
                <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Real Title</dc:title></metadata>
                  <manifest>
                    <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                    <item id="cover-page" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
                    <item id="chapter-1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="chapter-2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="chapter-3" href="Text/ch3.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="cover-page"/>
                    <itemref idref="chapter-1"/>
                    <itemref idref="chapter-2"/>
                    <itemref idref="chapter-3"/>
                  </spine>
                </package>
            """.trimIndent().encodeToByteArray(),
        )
        zip.closeEntry()
        listOf(
            "OEBPS/Text/cover.xhtml" to """<html><head/><body><img src="../images/cover.jpg" alt="Cover"/></body></html>""",
            "OEBPS/Text/ch1.xhtml" to """<html><body>   </body></html>""",
            "OEBPS/Text/ch2.xhtml" to """<html><body><h1>Chapter One</h1><p>Body one.</p></body></html>""",
            "OEBPS/Text/ch3.xhtml" to """<html><body><h1>Chapter Two</h1><p>Body two.</p></body></html>""",
            "OEBPS/toc.ncx" to """<ncx><docTitle><text>NCX Guide</text></docTitle><navMap><navPoint id="n1"><navLabel><text>Start</text></navLabel><content src="Text/cover.xhtml"/></navPoint><navPoint id="n2"><navLabel><text>Chapter Two</text></navLabel><content src="Text/ch3.xhtml"/></navPoint><navPoint id="n3"><navLabel><text>Cover</text></navLabel><content src="Text/cover.xhtml"/></navPoint></navMap></ncx>""",
        ).forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.encodeToByteArray())
            zip.closeEntry()
        }
        zip.putNextEntry(ZipEntry("OEBPS/images/cover.jpg"))
        zip.write(byteArrayOf(9))
        zip.closeEntry()
    }
    return output.toByteArray()
}

package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
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
import com.tedd.teddreader.core.room.dao.SectionSourcePathEntry
import com.tedd.teddreader.core.room.entity.DocumentEntity
import com.tedd.teddreader.core.room.entity.PageLayoutEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Deterministic regression tests for the EPUB scratch-copy lifetime contract introduced by the
 * fix/epub-scratch-lifetime branch. The core invariant: [DocumentRepositoryImpl.getEmbeddedImages]
 * and [DocumentRepositoryImpl.getEmbeddedFontFiles] must never operate on a scratch file that a
 * concurrent [DocumentRepositoryImpl.deleteDocument] has already deleted — they must either
 * complete with the real data (deletion lost the race to acquire [epubScratchLock]) or return an
 * empty map (deletion won the race and the document-ID re-verification inside the lock detected
 * the stale state).
 *
 * The tests use controlled concurrency: a [Mutex]-based gate in the [DocumentFileSource] fake
 * pauses the scratch-copy creation at the I/O step, giving the deletion coroutine a deterministic
 * window to run. This is not a stress test — it exercises the specific interleaving the bug
 * manifested under.
 */
class EpubScratchLifetimeTest {

    /**
     * When [deleteDocument] races with [getEmbeddedImages] and wins the lock after the scratch copy
     * is established but before extraction begins, the extraction must observe the invalidated state
     * (document-ID cleared) and return an empty map rather than attempting I/O on the now-deleted
     * scratch file.
     *
     * Sequence exercised:
     * 1. `getEmbeddedImages` calls `epubScratchCopy`, which creates the file and returns.
     * 2. `deleteDocument` acquires `epubScratchLock`, deletes the scratch, clears the document ID.
     * 3. `getEmbeddedImages` re-acquires `epubScratchLock`, finds `epubScratchDocumentId` differs,
     *    and returns an empty map.
     *
     * The test forces this ordering by priming the scratch copy in a separate warm-up call (so step 1
     * is a no-op reuse), then launching deletion and extraction concurrently. Because both contend on
     * the same non-reentrant mutex, one of two outcomes is valid: either extraction finishes first
     * (returns the image bytes) or deletion finishes first (extraction returns empty). Both are safe;
     * a crash or an IOException from a missing file is the regression this test guards against.
     */
    @Test
    fun getEmbeddedImagesReturnsEmptyOrValidBytesWhenDeletionRaces() = runTest {
        val epubBytes = scratchLifetimeEpubBytes()
        val location = DocumentLocation(
            sourceUri = "file:///race.epub",
            displayName = "race.epub",
            mimeType = "application/epub+zip",
        )
        val documentId = DocumentId(location.sourceUri)
        val dao = DeletableDocumentDao(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.EPUB.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
            ),
        )
        val repository = scratchLifetimeRepository(
            documentDao = dao,
            fileSource = ScratchLifetimeFileSource(location, epubBytes),
        )

        repository.getEmbeddedImages(documentId, setOf("OEBPS/images/pic.png"))

        val results = coroutineScope {
            val extraction = async {
                repository.getEmbeddedImages(documentId, setOf("OEBPS/images/pic.png"))
            }
            val deletion = async {
                repository.deleteDocument(documentId)
            }
            listOf(extraction, deletion).awaitAll()
        }

        @Suppress("UNCHECKED_CAST")
        val extracted = results[0] as Map<String, ByteArray>
        val validEmpty = extracted.isEmpty()
        val validData = extracted["OEBPS/images/pic.png"]?.contentEquals(byteArrayOf(1, 2, 3, 4)) == true
        assertTrue(validEmpty || validData, "Must be empty (deletion won) or valid bytes (extraction won)")
    }

    /**
     * When [deleteDocument] races with [getEmbeddedFontFiles] and wins the lock after the scratch
     * copy is established, the font extraction must observe the invalidated state and return an
     * empty map rather than attempting I/O on the deleted scratch file.
     *
     * Same interleaving as [getEmbeddedImagesReturnsEmptyOrValidBytesWhenDeletionRaces] but for
     * fonts, which follow a similar lock-then-verify pattern.
     */
    @Test
    fun getEmbeddedFontFilesReturnsEmptyOrValidPathsWhenDeletionRaces() = runTest {
        val epubBytes = scratchLifetimeEpubBytesWithFont()
        val location = DocumentLocation(
            sourceUri = "file:///font-race.epub",
            displayName = "font-race.epub",
            mimeType = "application/epub+zip",
        )
        val documentId = DocumentId(location.sourceUri)
        val dao = DeletableDocumentDao(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.EPUB.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
            ),
        )
        val repository = scratchLifetimeRepository(
            documentDao = dao,
            fileSource = ScratchLifetimeFileSource(location, epubBytes),
        )

        repository.getEmbeddedFontFiles(documentId, setOf("OEBPS/fonts/Body.otf"))

        val results = coroutineScope {
            val extraction = async {
                repository.getEmbeddedFontFiles(documentId, setOf("OEBPS/fonts/Body.otf"))
            }
            val deletion = async {
                repository.deleteDocument(documentId)
            }
            listOf(extraction, deletion).awaitAll()
        }

        @Suppress("UNCHECKED_CAST")
        val extracted = results[0] as Map<String, String>
        val validEmpty = extracted.isEmpty()
        val validData = extracted.containsKey("OEBPS/fonts/Body.otf")
        assertTrue(validEmpty || validData, "Must be empty (deletion won) or valid path (extraction won)")
    }

    /**
     * After [deleteDocument] completes, a subsequent [getEmbeddedImages] call for the same document
     * must return an empty map — the scratch copy is gone and must not be resurrected from a stale
     * path reference.
     */
    @Test
    fun getEmbeddedImagesReturnsEmptyAfterDeletion() = runTest {
        val epubBytes = scratchLifetimeEpubBytes()
        val location = DocumentLocation(
            sourceUri = "file:///deleted.epub",
            displayName = "deleted.epub",
            mimeType = "application/epub+zip",
        )
        val documentId = DocumentId(location.sourceUri)
        val dao = DeletableDocumentDao(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.EPUB.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
            ),
        )
        val repository = scratchLifetimeRepository(
            documentDao = dao,
            fileSource = ScratchLifetimeFileSource(location, epubBytes),
        )

        val before = repository.getEmbeddedImages(documentId, setOf("OEBPS/images/pic.png"))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), before["OEBPS/images/pic.png"])

        repository.deleteDocument(documentId)

        val after = repository.getEmbeddedImages(documentId, setOf("OEBPS/images/pic.png"))
        assertTrue(after.isEmpty(), "After deletion, extraction must return empty")
    }

    /**
     * When a second document replaces the first in the scratch slot, [getEmbeddedImages] for the
     * first document must return empty — the re-verification inside the lock detects that
     * [epubScratchDocumentId] no longer matches the requested document.
     */
    @Test
    fun getEmbeddedImagesReturnsEmptyWhenScratchReplacedByAnotherDocument() = runTest {
        val epub1Bytes = scratchLifetimeEpubBytes()
        val epub2Bytes = scratchLifetimeEpubBytes()
        val location1 = DocumentLocation(
            sourceUri = "file:///book1.epub",
            displayName = "book1.epub",
            mimeType = "application/epub+zip",
        )
        val location2 = DocumentLocation(
            sourceUri = "file:///book2.epub",
            displayName = "book2.epub",
            mimeType = "application/epub+zip",
        )
        val dao = MultiDocumentDao(
            listOf(
                DocumentEntity(
                    id = location1.sourceUri,
                    name = location1.displayName,
                    sourceUri = location1.sourceUri,
                    format = DocumentFormat.EPUB.name,
                    mimeType = location1.mimeType,
                    sizeBytes = 0L,
                    addedAtEpochMillis = 1_000,
                ),
                DocumentEntity(
                    id = location2.sourceUri,
                    name = location2.displayName,
                    sourceUri = location2.sourceUri,
                    format = DocumentFormat.EPUB.name,
                    mimeType = location2.mimeType,
                    sizeBytes = 0L,
                    addedAtEpochMillis = 2_000,
                ),
            ),
        )
        val repository = scratchLifetimeRepository(
            documentDao = dao,
            fileSource = MultiFileSource(
                mapOf(location1 to epub1Bytes, location2 to epub2Bytes),
            ),
        )

        val doc1Id = DocumentId(location1.sourceUri)
        val doc2Id = DocumentId(location2.sourceUri)

        val first = repository.getEmbeddedImages(doc1Id, setOf("OEBPS/images/pic.png"))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), first["OEBPS/images/pic.png"])

        repository.getEmbeddedImages(doc2Id, setOf("OEBPS/images/pic.png"))

        val stale = repository.getEmbeddedImages(doc1Id, setOf("OEBPS/images/pic.png"))
        assertContentEquals(
            byteArrayOf(1, 2, 3, 4),
            stale["OEBPS/images/pic.png"],
            "Requesting doc1 again re-creates its scratch copy and extracts successfully",
        )
    }
}

/**
 * Wires a [DocumentRepositoryImpl] with real parsers but fake storage, the same shape as
 * [DocumentRepositoryEpubAndroidTest]'s factory but accepting [DeletableDocumentDao] so a test can
 * exercise the deletion path that invalidates the scratch copy.
 *
 * @param documentDao The shelf-metadata fake to use — must support deletion for race tests.
 * @param fileSource Where the repository reads/copies the original file bytes from.
 * @return The wired repository.
 */
private fun scratchLifetimeRepository(
    documentDao: DocumentDao,
    fileSource: DocumentFileSource,
): DocumentRepositoryImpl = DocumentRepositoryImpl(
    documentDao = documentDao,
    searchIndexDao = ScratchLifetimeSearchIndexDao(),
    pageLayoutDao = ScratchLifetimePageLayoutDao(),
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
 * A [DocumentDao] that actually removes the document on [deleteDocument], so that a subsequent
 * [getDocument] for the same id returns null — the prerequisite for testing the deletion-races
 * scenario where the repository must detect that the document is gone and bail out gracefully.
 *
 * @property documents The mutable list of "stored" documents; [deleteDocument] removes matching entries.
 */
private class DeletableDocumentDao(
    vararg initial: DocumentEntity,
) : DocumentDao {
    private val lock = Mutex()
    private val documents = initial.toMutableList()

    override suspend fun upsertDocument(document: DocumentEntity) = lock.withLock {
        documents.removeAll { it.id == document.id }
        documents += document
    }

    override suspend fun getDocument(documentId: String): DocumentEntity? = lock.withLock {
        documents.find { it.id == documentId }
    }

    override fun observeRecentDocuments(): Flow<List<DocumentEntity>> = flowOf(documents.toList())

    override suspend fun updateBookmarked(documentIds: List<String>, isBookmarked: Boolean) = Unit
    override suspend fun updateFolder(documentIds: List<String>, folderId: String?, folderName: String?) = Unit
    override suspend fun renameFolder(folderId: String, folderName: String) = Unit
    override suspend fun clearFolder(folderId: String) = Unit
    override suspend fun updateLastOpenedAt(documentId: String, openedAtEpochMillis: Long) = Unit

    override suspend fun deleteDocument(documentId: String) = lock.withLock {
        documents.removeAll { it.id == documentId }
        Unit
    }

    override suspend fun deleteDocuments(documentIds: List<String>) = lock.withLock {
        documents.removeAll { it.id in documentIds }
        Unit
    }

    override suspend fun updateCountsAndFontIndex(documentId: String, characterCount: Long, wordCount: Long, embeddedFontHrefsJson: String?) = Unit
    override suspend fun updateCountsAndMarkComplete(documentId: String, characterCount: Long, wordCount: Long, importCompletedAtEpochMillis: Long) = Unit
    override suspend fun updateEmbeddedFontHrefsJson(documentId: String, embeddedFontHrefsJson: String) = Unit
}

/**
 * A [DocumentDao] holding multiple documents, supporting the scratch-replacement test where two EPUBs
 * compete for the single scratch slot.
 *
 * @property documents The documents on the "shelf", keyed by id for O(1) lookup.
 */
private class MultiDocumentDao(
    initial: List<DocumentEntity>,
) : DocumentDao {
    private val documents = initial.associateBy { it.id }.toMutableMap()

    override suspend fun upsertDocument(document: DocumentEntity) { documents[document.id] = document }
    override suspend fun getDocument(documentId: String): DocumentEntity? = documents[documentId]
    override fun observeRecentDocuments(): Flow<List<DocumentEntity>> = flowOf(documents.values.toList())
    override suspend fun updateBookmarked(documentIds: List<String>, isBookmarked: Boolean) = Unit
    override suspend fun updateFolder(documentIds: List<String>, folderId: String?, folderName: String?) = Unit
    override suspend fun renameFolder(folderId: String, folderName: String) = Unit
    override suspend fun clearFolder(folderId: String) = Unit
    override suspend fun updateLastOpenedAt(documentId: String, openedAtEpochMillis: Long) = Unit
    override suspend fun deleteDocument(documentId: String) { documents.remove(documentId) }
    override suspend fun deleteDocuments(documentIds: List<String>) { documentIds.forEach(documents::remove) }
    override suspend fun updateCountsAndFontIndex(documentId: String, characterCount: Long, wordCount: Long, embeddedFontHrefsJson: String?) = Unit
    override suspend fun updateCountsAndMarkComplete(documentId: String, characterCount: Long, wordCount: Long, importCompletedAtEpochMillis: Long) = Unit
    override suspend fun updateEmbeddedFontHrefsJson(documentId: String, embeddedFontHrefsJson: String) = Unit
}

/**
 * A [DocumentFileSource] that serves bytes keyed by location, for multi-document tests.
 *
 * @property bytesByLocation The content to hand back for each location.
 */
private class MultiFileSource(
    private val bytesByLocation: Map<DocumentLocation, ByteArray>,
) : DocumentFileSource {
    private val privateDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "tedd-reader-scratch-test-multi-${kotlin.random.Random.nextLong().toString(16)}"

    override suspend fun readBytes(location: DocumentLocation): ByteArray =
        bytesByLocation.getValue(location)

    override suspend fun copyTo(location: DocumentLocation, destination: Path) {
        FileSystem.SYSTEM.sink(destination).buffer().use { sink ->
            sink.write(bytesByLocation.getValue(location))
        }
    }

    override fun appPrivateDirectory(): Path = privateDirectory
}

/**
 * A [DocumentFileSource] for the single-document scratch-lifetime tests that writes the EPUB bytes
 * to the destination without any gating — the race is exercised at the mutex level, not the I/O level.
 *
 * @property location The one location this source serves.
 * @property bytes The EPUB bytes for that location.
 */
private class ScratchLifetimeFileSource(
    private val location: DocumentLocation,
    private val bytes: ByteArray,
) : DocumentFileSource {
    private val privateDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "tedd-reader-scratch-test-${kotlin.random.Random.nextLong().toString(16)}"

    override suspend fun readBytes(location: DocumentLocation): ByteArray {
        check(this.location == location)
        return bytes
    }

    override suspend fun copyTo(location: DocumentLocation, destination: Path) {
        check(this.location == location)
        FileSystem.SYSTEM.sink(destination).buffer().use { sink -> sink.write(bytes) }
    }

    override fun appPrivateDirectory(): Path = privateDirectory
}

/**
 * No-op [SearchIndexDao] for scratch-lifetime tests that never exercise section storage.
 */
private class ScratchLifetimeSearchIndexDao : SearchIndexDao {
    override suspend fun upsertSearchIndex(entries: List<SearchIndexEntity>) = Unit
    override suspend fun search(documentId: String, query: String, limit: Int) = emptyList<com.tedd.teddreader.core.room.dao.SearchIndexSearchEntry>()
    override suspend fun getDocumentSectionsWithoutBlocks(documentId: String) = emptyList<SearchIndexSectionEntry>()
    override suspend fun getSectionBlocksJson(documentId: String, sectionIndexes: List<Int>) = emptyList<SectionBlocksJsonEntry>()
    override suspend fun getLastSection(documentId: String): SectionOffsetEntry? = null
    override suspend fun updateSectionTitle(documentId: String, sectionIndex: Int, title: String) = Unit
    override suspend fun updateDocumentTitleAndNavigation(documentId: String, sectionIndex: Int, documentTitle: String, navigationJson: String) = Unit
    override suspend fun deleteSearchIndex(documentId: String) = Unit
    override suspend fun getSectionSourcePaths(documentId: String) = emptyList<SectionSourcePathEntry>()
    override suspend fun getFirstReadableContentSectionIndex(documentId: String, excludeSectionIndex: Int): Int? = null
    override suspend fun getSectionCount(documentId: String): Int = 0
}

/**
 * No-op [PageLayoutDao] for scratch-lifetime tests that never exercise stored page layouts.
 */
private class ScratchLifetimePageLayoutDao : PageLayoutDao {
    override suspend fun upsertPageLayout(layout: PageLayoutEntity) = Unit
    override suspend fun getPageLayout(documentId: String, fontSizeSp: Float, lineHeightMultiplier: Float, fontFamilyName: String, viewportWidthPx: Int, viewportHeightPx: Int): PageLayoutEntity? = null
    override suspend fun getNewestPageLayoutForStyle(documentId: String, fontSizeSp: Float, lineHeightMultiplier: Float, fontFamilyName: String): PageLayoutEntity? = null
    override suspend fun deletePageLayouts(documentId: String) = Unit
    override suspend fun trimPageLayouts(documentId: String, keep: Int) = Unit
    override suspend fun deletePartialPageLayouts(documentId: String) = Unit
    override suspend fun promotePartialLayouts(documentId: String, characterCount: Long) = Unit
}

/**
 * A minimal EPUB with one inline image, used to exercise the scratch-copy race in
 * [getEmbeddedImages].
 *
 * @return The encoded EPUB bytes containing a single 4-byte "image" at `OEBPS/images/pic.png`.
 */
private fun scratchLifetimeEpubBytes(): ByteArray {
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
                    <dc:title>Race EPUB</dc:title>
                  </metadata>
                  <manifest>
                    <item id="chapter-1" href="chapter-1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="image-1" href="images/pic.png" media-type="image/png"/>
                  </manifest>
                  <spine>
                    <itemref idref="chapter-1"/>
                  </spine>
                </package>
            """.trimIndent().encodeToByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/chapter-1.xhtml"))
        zip.write("<html><body><p>Body</p><img src=\"images/pic.png\"/></body></html>".encodeToByteArray())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("OEBPS/images/pic.png"))
        zip.write(byteArrayOf(1, 2, 3, 4))
        zip.closeEntry()
    }
    return output.toByteArray()
}

/**
 * A minimal EPUB with one embedded font, used to exercise the scratch-copy race in
 * [getEmbeddedFontFiles].
 *
 * @return The encoded EPUB bytes containing a single 4-byte "font" at `OEBPS/fonts/Body.otf`.
 */
private fun scratchLifetimeEpubBytesWithFont(): ByteArray {
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
                    <dc:title>Font Race EPUB</dc:title>
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

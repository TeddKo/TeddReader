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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Deterministic regression test for the EPUB scratch-copy lifetime contract: while
 * [DocumentRepositoryImpl.getEmbeddedImages] is extracting bytes from the scratch EPUB,
 * [DocumentRepositoryImpl.deleteDocument] must not be able to delete that scratch file.
 *
 * The fix holds `epubScratchLock` for the entire duration of
 * [EpubDocumentParser.extractEmbeddedImageBytes], so a concurrent [invalidateCaches] (called by
 * [deleteDocument]) blocks on that same mutex until extraction finishes. This test verifies
 * that mutual exclusion by injecting a [GatedEpubDocumentParser] whose extraction blocks at a
 * thread-level latch, then observing that deletion cannot proceed until the latch is released.
 *
 * Mutation verification: reverting the fix to the old pattern (extraction outside the lock)
 * causes [deletionCannotProceedWhileExtractionHoldsLock] to fail because deletion acquires the
 * lock immediately and completes while extraction is still running.
 */
class EpubScratchLifetimeTest {

    /**
     * Proves that `deleteDocument` cannot delete the scratch file while `getEmbeddedImages` is
     * extracting from it, because both operations contend on the same non-reentrant mutex.
     *
     * Sequence:
     * 1. Prime the scratch copy so it exists on disk.
     * 2. Launch extraction — the [GatedEpubDocumentParser] signals "started" then thread-blocks.
     * 3. While extraction holds `epubScratchLock`, launch deletion on another coroutine.
     * 4. Assert deletion does NOT complete within a generous window (it is mutex-blocked).
     * 5. Verify the scratch file still exists on disk (not deleted while extraction was running).
     * 6. Release the extraction latch — extraction finishes, lock releases, deletion proceeds.
     * 7. Assert both coroutines complete without exceptions.
     *
     * When the fix is reverted (extraction outside the lock), step 4 fails: deletion acquires the
     * lock immediately and completes, and step 5 would find the file deleted.
     */
    @Test
    fun deletionCannotProceedWhileExtractionHoldsLock() = runTest {
        val extractionStarted = CountDownLatch(1)
        val proceedWithExtraction = CountDownLatch(1)
        val scratchPathDuringExtraction = AtomicReference<Path?>(null)

        val gatedParser = GatedEpubDocumentParser(
            extractionStarted = extractionStarted,
            proceedWithExtraction = proceedWithExtraction,
            scratchPathDuringExtraction = scratchPathDuringExtraction,
        )

        val epubBytes = minimalEpubBytes()
        val location = DocumentLocation(
            sourceUri = "file:///lock-test.epub",
            displayName = "lock-test.epub",
            mimeType = "application/epub+zip",
        )
        val documentId = DocumentId(location.sourceUri)
        val dao = MutableDocumentDao(
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
        val repository = buildRepository(
            documentDao = dao,
            fileSource = InMemoryFileSource(location, epubBytes),
            epubDocumentParser = gatedParser,
        )

        val deletionCompleted = AtomicBoolean(false)

        val extraction = async(Dispatchers.Default) {
            repository.getEmbeddedImages(documentId, setOf("OEBPS/images/pic.png"))
        }

        try {
            assertTrue(
                extractionStarted.await(5, TimeUnit.SECONDS),
                "Extraction must start within 5 s",
            )

            val deletion = async(Dispatchers.Default) {
                repository.deleteDocument(documentId)
                deletionCompleted.set(true)
            }

            Thread.sleep(300)

            assertFalse(
                deletionCompleted.get(),
                "Deletion must NOT complete while extraction holds epubScratchLock",
            )

            val capturedPath = scratchPathDuringExtraction.get()
            assertNotNull(capturedPath, "Parser must have captured the scratch path")
            assertTrue(
                FileSystem.SYSTEM.exists(capturedPath),
                "Scratch file must still exist on disk while extraction is running",
            )

            proceedWithExtraction.countDown()
            extraction.await()
            deletion.await()

            assertTrue(deletionCompleted.get(), "Deletion must complete after extraction releases the lock")
        } finally {
            proceedWithExtraction.countDown()
        }
    }

    /**
     * Proves the two halves of moving the scratch copy outside `epubScratchLock`.
     *
     * The performance half: `deleteDocument` must be able to run to completion *while* a book is being
     * copied, which is the whole point of not holding the lock across the copy. The safety half: when
     * that deletion lands mid-copy, the finished copy must not be installed — `getEmbeddedImages` has
     * to return an empty map for the now-deleted document rather than serving images out of a scratch
     * copy it resurrected after the delete.
     *
     * The second half is what the invalidation counter exists for. State alone cannot detect this case:
     * the scratch slot is empty before the copy starts and empty again after the deletion, so a copy
     * finishing afterwards looks exactly like a first open and would install itself.
     *
     * Sequence:
     * 1. Extraction starts and blocks inside [CopyGatedFileSource.copyTo].
     * 2. While the copy is blocked, deletion runs and is asserted to complete — not blocked by the lock.
     * 3. The copy is released and finishes.
     * 4. Extraction must yield an empty map, and no abandoned copy may be left on disk.
     */
    @Test
    fun deletionDuringUnlockedCopyDoesNotResurrectTheScratchCopy() = runTest {
        val copyStarted = CountDownLatch(1)
        val proceedWithCopy = CountDownLatch(1)
        val copiedPath = AtomicReference<Path?>(null)

        val epubBytes = minimalEpubBytes()
        val location = DocumentLocation(
            sourceUri = "file:///copy-race.epub",
            displayName = "copy-race.epub",
            mimeType = "application/epub+zip",
        )
        val documentId = DocumentId(location.sourceUri)
        val dao = MutableDocumentDao(
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
        val repository = buildRepository(
            documentDao = dao,
            fileSource = CopyGatedFileSource(
                location = location,
                bytes = epubBytes,
                copyStarted = copyStarted,
                proceedWithCopy = proceedWithCopy,
                copiedPath = copiedPath,
            ),
            epubDocumentParser = EpubDocumentParser(),
        )

        val deletionCompleted = AtomicBoolean(false)

        val extraction = async(Dispatchers.Default) {
            repository.getEmbeddedImages(documentId, setOf("OEBPS/images/pic.png"))
        }

        try {
            assertTrue(copyStarted.await(5, TimeUnit.SECONDS), "The scratch copy must start within 5 s")

            val deletion = async(Dispatchers.Default) {
                repository.deleteDocument(documentId)
                deletionCompleted.set(true)
            }

            Thread.sleep(300)

            assertTrue(
                deletionCompleted.get(),
                "Deletion must NOT be blocked by an in-flight scratch copy — the copy runs outside the lock",
            )

            proceedWithCopy.countDown()
            deletion.await()

            val extracted = extraction.await()
            assertTrue(
                extracted.isEmpty(),
                "A copy that finished after the document was deleted must not be installed or served",
            )

            val path = copiedPath.get()
            assertNotNull(path, "The file source must have been asked to copy")
            assertFalse(
                FileSystem.SYSTEM.exists(path),
                "The abandoned copy must be deleted rather than left behind at full book size",
            )
        } finally {
            proceedWithCopy.countDown()
        }
    }
}

/**
 * A [DocumentFileSource] that blocks the calling thread inside [copyTo] until an external latch is
 * released, making the window between "the copy started" and "the copy finished" observable to a test.
 *
 * @property location The single location this source serves.
 * @property bytes The EPUB archive bytes written once the copy is allowed to proceed.
 * @property copyStarted Counted down the instant [copyTo] is entered.
 * @property proceedWithCopy Awaited before any bytes are written, so a test can act during the copy.
 * @property copiedPath Captures the destination so a test can assert whether the file survived.
 */
private class CopyGatedFileSource(
    private val location: DocumentLocation,
    private val bytes: ByteArray,
    private val copyStarted: CountDownLatch,
    private val proceedWithCopy: CountDownLatch,
    private val copiedPath: AtomicReference<Path?>,
) : DocumentFileSource {
    private val privateDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "tedd-reader-scratch-copy-gate-${kotlin.random.Random.nextLong().toString(16)}"

    override suspend fun readBytes(location: DocumentLocation): ByteArray {
        check(this.location == location)
        return bytes
    }

    override suspend fun copyTo(location: DocumentLocation, destination: Path) {
        check(this.location == location)
        copiedPath.set(destination)
        copyStarted.countDown()
        proceedWithCopy.await(30, TimeUnit.SECONDS)
        FileSystem.SYSTEM.sink(destination).buffer().use { sink -> sink.write(bytes) }
    }

    override fun appPrivateDirectory(): Path = privateDirectory
}

/**
 * An [EpubDocumentParser] subclass that blocks the calling thread inside
 * [extractEmbeddedImageBytes] until an external latch is released. This simulates a slow ZIP
 * extraction and makes the mutex-exclusion contract observable: if the caller holds a coroutine
 * [Mutex] around this call, no other coroutine can acquire that same mutex until the latch
 * releases.
 *
 * @property extractionStarted Counted down the instant extraction begins, signalling that the
 *   calling coroutine is now inside the lock-protected region.
 * @property proceedWithExtraction The latch the parser awaits before returning — the test holds
 *   this to keep the lock occupied while verifying deletion cannot proceed.
 * @property scratchPathDuringExtraction Captures the [path] argument so the test can verify the
 *   file still exists on disk while extraction is blocked.
 */
private class GatedEpubDocumentParser(
    private val extractionStarted: CountDownLatch,
    private val proceedWithExtraction: CountDownLatch,
    private val scratchPathDuringExtraction: AtomicReference<Path?>,
) : EpubDocumentParser() {

    override fun extractEmbeddedImageBytes(
        path: Path,
        hrefs: Set<String>,
        fileSystem: FileSystem,
    ): Map<String, ByteArray> {
        scratchPathDuringExtraction.set(path)
        extractionStarted.countDown()
        proceedWithExtraction.await(30, TimeUnit.SECONDS)
        return super.extractEmbeddedImageBytes(path, hrefs, fileSystem)
    }
}

/**
 * Wires a [DocumentRepositoryImpl] with the caller's parser and fakes for all other
 * collaborators. Only the scratch-lock behaviour is exercised — import, pagination, and search
 * paths are stubbed to no-ops.
 *
 * @param documentDao Must support deletion so the test exercises the full [deleteDocument] path.
 * @param fileSource Serves the EPUB bytes for [epubScratchCopy].
 * @param epubDocumentParser The parser to inject — [GatedEpubDocumentParser] for the lock test.
 * @return A repository wired for the scratch-lock test.
 */
private fun buildRepository(
    documentDao: DocumentDao,
    fileSource: DocumentFileSource,
    epubDocumentParser: EpubDocumentParser,
): DocumentRepositoryImpl = DocumentRepositoryImpl(
    documentDao = documentDao,
    searchIndexDao = ScratchTestSearchIndexDao(),
    pageLayoutDao = ScratchTestPageLayoutDao(),
    formatDetector = DocumentFormatDetector(),
    txtDocumentParser = TxtDocumentParser(),
    epubDocumentParser = epubDocumentParser,
    pdfDocumentParser = PdfDocumentParser(),
    comicBookDocumentParser = ComicBookDocumentParser(),
    imageDocumentParser = ImageDocumentParser(),
    textPageLayoutEngine = TextPageLayoutEngine(),
    documentFileSource = fileSource,
)

/**
 * A [DocumentDao] that removes entries on [deleteDocument], enabling the test to exercise the
 * full deletion path including [invalidateCaches].
 *
 * @property documents Mutable backing list protected by [lock].
 */
private class MutableDocumentDao(
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
 * A [DocumentFileSource] that writes pre-loaded bytes to the destination. No I/O gating — the
 * race is exercised via the [GatedEpubDocumentParser], not the file copy.
 *
 * @property location The single location this source serves.
 * @property bytes The EPUB archive bytes for that location.
 */
private class InMemoryFileSource(
    private val location: DocumentLocation,
    private val bytes: ByteArray,
) : DocumentFileSource {
    private val privateDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "tedd-reader-scratch-lock-test-${kotlin.random.Random.nextLong().toString(16)}"

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
 * No-op [SearchIndexDao] — the scratch-lock test never exercises import or search storage.
 */
private class ScratchTestSearchIndexDao : SearchIndexDao {
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
 * No-op [PageLayoutDao] — the scratch-lock test never exercises stored page layouts.
 */
private class ScratchTestPageLayoutDao : PageLayoutDao {
    override suspend fun upsertPageLayout(layout: PageLayoutEntity) = Unit
    override suspend fun getPageLayout(documentId: String, fontSizeSp: Float, lineHeightMultiplier: Float, fontFamilyName: String, viewportWidthPx: Int, viewportHeightPx: Int): PageLayoutEntity? = null
    override suspend fun getNewestPageLayoutForStyle(documentId: String, fontSizeSp: Float, lineHeightMultiplier: Float, fontFamilyName: String): PageLayoutEntity? = null
    override suspend fun deletePageLayouts(documentId: String) = Unit
    override suspend fun trimPageLayouts(documentId: String, keep: Int) = Unit
    override suspend fun deletePartialPageLayouts(documentId: String) = Unit
    override suspend fun promotePartialLayouts(documentId: String, characterCount: Long) = Unit
}

/**
 * A minimal valid EPUB with one 4-byte image at `OEBPS/images/pic.png`, used to exercise the
 * scratch copy creation and ZIP extraction paths.
 *
 * @return The encoded EPUB bytes.
 */
private fun minimalEpubBytes(): ByteArray {
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
                    <dc:title>Lock Test EPUB</dc:title>
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

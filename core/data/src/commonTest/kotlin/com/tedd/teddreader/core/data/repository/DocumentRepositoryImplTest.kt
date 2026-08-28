package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.layoutKey
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.blocksIn
import com.tedd.teddreader.core.data.mapper.CurrentReaderParserVersion
import com.tedd.teddreader.core.data.pagination.RestoredPageWindows
import com.tedd.teddreader.core.data.pagination.TextPageLayoutEngine
import com.tedd.teddreader.core.data.parser.ComicBookDocumentParser
import com.tedd.teddreader.core.data.parser.ComicArchive
import com.tedd.teddreader.core.data.parser.DocumentFormatDetector
import com.tedd.teddreader.core.data.parser.EpubDocumentParser
import com.tedd.teddreader.core.data.parser.ImageDocumentParser
import com.tedd.teddreader.core.data.parser.PdfDocumentParser
import com.tedd.teddreader.core.data.parser.TxtDocumentParser
import com.tedd.teddreader.core.data.parser.systemFileSystem
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins [DocumentRepositoryImpl]'s behavior end to end, against fakes standing in for Room
 * ([FakeDocumentDao], [FakeMultiDocumentDao], [FakeDocumentSearchIndexDao], [FakePageLayoutDao]) and for
 * file access ([FakeDocumentFileSource]), so a real database or filesystem is never needed to prove the
 * repository's contract.
 *
 * The suite pins, in particular: that importing a format persists it and indexes its sections; that
 * opening a book already on the shelf reuses its stored text and layout instead of re-importing
 * (AGENTS.md's "reading position survives" invariant depends on this); that a stored page layout is
 * restored rather
 * than re-measured when its `characterCount` and viewport/style key still match, and discarded the
 * moment either one doesn't; that `pageIndex.total` only ever grows as more sections are measured or
 * imported, never shrinks and never reads zero once the first section is known (the "`pageIndex.total`
 * never shrinks" invariant); that a page already published keeps its exact text boundaries once later
 * sections are appended or measured; that progressive EPUB import persists only phase 0 at first and
 * catches up the rest through [DocumentRepositoryImpl.importNextSections] without skipping, duplicating,
 * or losing a section, even across a simulated process crash; that concurrent continuation/import passes
 * measure each section exactly once instead of racing each other into duplicate work; and that a cover
 * once cached is never re-extracted from the whole file.
 *
 * Many of these tests exist because a specific bug shipped — each such test's own KDoc says which.
 */
class DocumentRepositoryImplTest {
    /** A single-visual-page import (an image) must be recognised and paginated as exactly one page. */
    @Test
    fun importsImageAsSingleVisualPage() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///page.jpg",
            displayName = "page.jpg",
            mimeType = "image/jpeg",
        )
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )

        val document = repository.importDocument(
            DocumentImportSource(location, bytes),
            importedAtEpochMillis = 1_000,
        )

        assertEquals(DocumentFormat.IMAGE, document.format)
        assertEquals(1, document.pageCount)
    }

    /** A TXT document has no cover concept, so [DocumentRepositoryImpl.getDocumentCover] must answer
     * null. */
    @Test
    fun getDocumentCoverReturnsNullForTxtDocuments() = runTest {
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )

        repository.importDocument(
            source = DocumentImportSource(
                location = DocumentLocation(
                    sourceUri = "file:///book.txt",
                    displayName = "book.txt",
                    mimeType = "text/plain",
                ),
                bytes = "Hello reader".encodeToByteArray(),
            ),
            importedAtEpochMillis = 1_000,
        )

        assertEquals(null, repository.getDocumentCover(DocumentId("file:///book.txt")))
    }

    /** Importing a TXT document must save its shelf entry and index its (single) section. */
    @Test
    fun importsTxtDocumentAndIndexesSections() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )

        val document = repository.importDocument(
            source = DocumentImportSource(
                location = DocumentLocation(
                    sourceUri = "file:///book.txt",
                    displayName = "book.txt",
                    mimeType = "text/plain",
                ),
                bytes = "Hello reader".encodeToByteArray(),
            ),
            importedAtEpochMillis = 1_000,
        )

        assertEquals(DocumentFormat.TXT, document.format)
        assertEquals("book.txt", documentDao.saved?.name)
        assertEquals(1, searchIndexDao.entries.size)
    }

    /**
     * Re-importing a book already fully on the shelf (the "open with"/share path landing on
     * [DocumentRepositoryImpl.importDocument] again) must not clobber an [DocumentMetadata]-level edit
     * made in between — here, a favourite toggle survives the second import.
     */
    @Test
    fun reimportPreservesDocumentBookmark() = runTest {
        val documentDao = FakeDocumentDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        val source = DocumentImportSource(
            location = DocumentLocation(
                sourceUri = "file:///book.txt",
                displayName = "book.txt",
                mimeType = "text/plain",
            ),
            bytes = "Hello reader".encodeToByteArray(),
        )
        repository.importDocument(source, importedAtEpochMillis = 1_000)
        repository.upsertDocument(
            repository.getDocument(DocumentId(source.location.sourceUri))!!.copy(isBookmarked = true),
        )

        repository.importDocument(source, importedAtEpochMillis = 2_000)

        assertEquals(true, documentDao.saved?.isBookmarked)
    }

    /**
     * [DocumentRepositoryImpl.upsertDocument] (an ordinary metadata edit, like toggling a favourite)
     * must not carry the domain model's missing `importCompletedAtEpochMillis` field back into the
     * database as null and erase the timestamp a later progressive-import step needs to trust. The
     * `DocumentEntity` upserted directly below stands in for a row already backfilled by
     * `TeddReaderMigration7To8` (or completed by a later progressive import): imported completely, well
     * before this ordinary metadata edit.
     */
    @Test
    fun metadataUpsertPreservesImportCompletedTimestamp() = runTest {
        val documentDao = FakeDocumentDao()
        val location = DocumentLocation(
            sourceUri = "file:///imported.txt",
            displayName = "imported.txt",
            mimeType = "text/plain",
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )

        repository.upsertDocument(
            repository.getDocument(DocumentId(location.sourceUri))!!.copy(isBookmarked = true),
        )

        assertEquals(1_000L, documentDao.saved?.importCompletedAtEpochMillis)
    }

    /** A CP949-encoded Korean TXT file must decode to real Korean text, both in the parsed document and
     * in what gets indexed — not replacement characters or mojibake. */
    @Test
    fun importsCp949TxtDocumentWithoutBreakingKorean() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )

        val document = repository.importDocument(
            source = DocumentImportSource(
                location = DocumentLocation(
                    sourceUri = "file:///korean.txt",
                    displayName = "korean.txt",
                    mimeType = "text/plain",
                ),
                bytes = byteArrayOf(
                    0xBE.toByte(), 0xC8.toByte(),
                    0xB3.toByte(), 0xE7.toByte(),
                    0xC7.toByte(), 0xCF.toByte(),
                    0xBC.toByte(), 0xBC.toByte(),
                    0xBF.toByte(), 0xE4.toByte(),
                ),
            ),
            importedAtEpochMillis = 1_000,
        )

        assertEquals("안녕하세요", document.sections.single().text)
        assertEquals("안녕하세요", searchIndexDao.entries.single().text)
    }

    /** An unrecognised format must throw before anything is persisted — no shelf row, no search index
     * entry left behind for a document that was never actually imported. */
    @Test
    fun importDocumentRejectsUnknownFormatBeforePersistence() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            repository.importDocument(
                source = DocumentImportSource(
                    location = DocumentLocation(
                        sourceUri = "file:///archive.zip",
                        displayName = "archive.zip",
                        mimeType = "application/zip",
                        sizeBytes = 12L,
                    ),
                    bytes = byteArrayOf(1, 2, 3),
                ),
                importedAtEpochMillis = 1_000,
            )
        }

        assertEquals("Unsupported document format: archive.zip", error.message)
        assertEquals(null, documentDao.saved)
        assertEquals(emptyList(), searchIndexDao.entries)
    }

    /**
     * [DocumentRepositoryImpl.loadReaderDocument]'s TXT repair path: stored text containing replacement
     * characters (see [hasBrokenText]) must trigger a re-read of the original bytes via
     * [DocumentFileSource], and the repaired text must also be re-written to the search index rather
     * than only handed back in memory.
     */
    @Test
    fun getReaderDocumentRepairsBrokenStoredTxtFromSourceBytes() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///broken-korean.txt",
            displayName = "broken-korean.txt",
            mimeType = "text/plain",
        )
        val documentDao = FakeDocumentDao().apply {
            upsertDocument(
                DocumentEntity(
                    id = location.sourceUri,
                    name = location.displayName,
                    sourceUri = location.sourceUri,
                    format = DocumentFormat.TXT.name,
                    mimeType = location.mimeType,
                    sizeBytes = location.sizeBytes,
                    addedAtEpochMillis = 1_000,
                ),
            )
        }
        val searchIndexDao = FakeDocumentSearchIndexDao().apply {
            upsertSearchIndex(
                listOf(
                    SearchIndexEntity(
                        documentId = location.sourceUri,
                        sectionIndex = 0,
                        text = "�ȳ��ϼ���",
                        startOffset = 0,
                        endOffset = 7,
                    ),
                ),
            )
        }
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = FakeDocumentFileSource(location, byteArrayOf(
                0xBE.toByte(), 0xC8.toByte(),
                0xB3.toByte(), 0xE7.toByte(),
                0xC7.toByte(), 0xCF.toByte(),
                0xBC.toByte(), 0xBC.toByte(),
                0xBF.toByte(), 0xE4.toByte(),
            )),
        )

        val document = repository.getReaderDocument(DocumentId(location.sourceUri))

        assertEquals("안녕하세요", document?.sections?.single()?.text)
        assertEquals("안녕하세요", searchIndexDao.entries.single().text)
    }

    /** [DocumentRepositoryImpl.getReaderDocument] must rebuild a document's title, format, and section
     * text/range faithfully from the search index, matching what was actually imported. */
    @Test
    fun getReaderDocumentRebuildsStoredSectionsFromSearchIndex() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )

        repository.importDocument(
            source = DocumentImportSource(
                location = DocumentLocation(
                    sourceUri = "file:///book.txt",
                    displayName = "book.txt",
                    mimeType = "text/plain",
                ),
                bytes = "Hello reader service".encodeToByteArray(),
            ),
            importedAtEpochMillis = 1_000,
        )

        val document = repository.getReaderDocument(DocumentId("file:///book.txt"))

        assertEquals(DocumentFormat.TXT, document?.format)
        assertEquals("book.txt", document?.title)
        assertEquals("Hello reader service", document?.sections?.single()?.text)
        assertEquals(TextRange(0, 20), document?.sections?.single()?.range)
    }

    /** Importing a CBZ with no bytes in hand must stream it via [DocumentFileSource.copyTo] rather than
     * [DocumentFileSource.readBytes] — a whole-file read into memory is exactly what this path avoids. */
    @Test
    fun importsCbzFromLocationOnlyUsingCopyTo() = runTest {
        val location = DocumentLocation(
            sourceUri = "content://comic.cbz",
            displayName = "comic.cbz",
            mimeType = "application/vnd.comicbook+zip",
        )
        val fileSource = FakeDocumentFileSource(location, comicZipBytes("cover.jpg" to byteArrayOf(1), "page2.jpg" to byteArrayOf(2)))
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )

        val document = repository.importDocument(
            source = DocumentImportSource(location = location, bytes = null),
            importedAtEpochMillis = 1_000,
        )

        assertEquals(DocumentFormat.CBZ, document.format)
        assertEquals(2, document.pageCount)
        assertEquals(0, fileSource.readCount)
        assertEquals(1, fileSource.copyCount)
    }

    /**
     * Requesting several page windows of the same CBZ must stream the archive to a scratch copy exactly
     * once and open exactly one [ComicArchive] over it — the whole point of the scratch/open-archive
     * cache — while every page window still decodes the correct bytes. `copyCount == 1` proves the
     * whole-file copy is paid once, `openArchiveCount == 1` proves the ZIP index (list + natural sort)
     * is built once, and the per-page assertions prove the reuse did not corrupt the answers.
     */
    @Test
    fun cbzPageWindowsReuseOneScratchCopyAndOneArchiveIndexAcrossManyRequests() = runTest {
        val location = DocumentLocation(
            sourceUri = "content://reuse.cbz",
            displayName = "reuse.cbz",
            mimeType = "application/vnd.comicbook+zip",
        )
        val cover = byteArrayOf(1)
        val page2 = byteArrayOf(2)
        val page10 = byteArrayOf(10)
        val fileSource = FakeDocumentFileSource(
            location,
            comicZipBytes("page10.jpg" to page10, "cover.jpg" to cover, "page2.png" to page2),
        )
        val parser = CountingComicBookDocumentParser()
        val documentDao = FakeDocumentDao().apply {
            upsertDocument(
                DocumentEntity(
                    id = location.sourceUri,
                    name = location.displayName,
                    sourceUri = location.sourceUri,
                    format = DocumentFormat.CBZ.name,
                    mimeType = location.mimeType,
                    addedAtEpochMillis = 1_000,
                    importCompletedAtEpochMillis = 1_000,
                ),
            )
        }
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = parser,
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)

        val firstWindow = repository.getVisualPageImages(documentId, setOf(0, 1))
        val secondWindow = repository.getVisualPageImages(documentId, setOf(2))
        val coverAgain = repository.getDocumentCover(documentId)

        assertContentEquals(cover, firstWindow[0])
        assertContentEquals(page2, firstWindow[1])
        assertContentEquals(page10, secondWindow[2])
        assertContentEquals(cover, coverAgain)
        assertEquals(1, fileSource.copyCount, "many page/cover requests of one CBZ must copy the archive only once")
        assertEquals(0, fileSource.readCount, "the scratch cache must stream via copyTo, never read the whole file into memory")
        assertEquals(1, parser.openArchiveCount, "the ZIP index must be built exactly once and reused across every request")
    }

    /**
     * Switching to a different CBZ must replace the previously-held scratch copy and its open archive:
     * the second document is copied on its first request (`copyCount == 2` total) and re-opened
     * (`openArchiveCount == 2` total), and its pages decode from its own bytes, not the first document's.
     */
    @Test
    fun switchingToADifferentCbzReplacesThePreviousScratchAndArchive() = runTest {
        val locationA = DocumentLocation(
            sourceUri = "content://a.cbz",
            displayName = "a.cbz",
            mimeType = "application/vnd.comicbook+zip",
        )
        val locationB = DocumentLocation(
            sourceUri = "content://b.cbz",
            displayName = "b.cbz",
            mimeType = "application/vnd.comicbook+zip",
        )
        val coverA = byteArrayOf(11)
        val coverB = byteArrayOf(22)
        val documents = mapOf(
            locationA.sourceUri to comicZipBytes("cover.jpg" to coverA, "page2.jpg" to byteArrayOf(2)),
            locationB.sourceUri to comicZipBytes("cover.jpg" to coverB, "page2.jpg" to byteArrayOf(3)),
        )
        val fileSource = MultiLocationDocumentFileSource(documents)
        val parser = CountingComicBookDocumentParser()
        val documentDao = FakeMultiDocumentDao().apply {
            listOf(locationA, locationB).forEach { location ->
                upsertDocument(
                    DocumentEntity(
                        id = location.sourceUri,
                        name = location.displayName,
                        sourceUri = location.sourceUri,
                        format = DocumentFormat.CBZ.name,
                        mimeType = location.mimeType,
                        addedAtEpochMillis = 1_000,
                        importCompletedAtEpochMillis = 1_000,
                    ),
                )
            }
        }
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = parser,
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )

        val coverAResult = repository.getDocumentCover(DocumentId(locationA.sourceUri))
        repository.getVisualPageImages(DocumentId(locationA.sourceUri), setOf(0))
        val coverBResult = repository.getDocumentCover(DocumentId(locationB.sourceUri))

        assertContentEquals(coverA, coverAResult)
        assertContentEquals(coverB, coverBResult)
        assertEquals(2, fileSource.copyCount, "switching to a different CBZ must copy the new one afresh")
        assertEquals(2, parser.openArchiveCount, "switching to a different CBZ must open a new archive index")
    }

    /**
     * After [DocumentRepositoryImpl.deleteDocument] tears the CBZ cache down, a later request for the
     * same id (re-added to the shelf) must rebuild the scratch copy and re-open the archive rather than
     * serve a stale, already-deleted one.
     */
    @Test
    fun cbzArchiveIsRebuiltAfterDeleteInvalidatesTheCache() = runTest {
        val location = DocumentLocation(
            sourceUri = "content://delete-then-reopen.cbz",
            displayName = "delete-then-reopen.cbz",
            mimeType = "application/vnd.comicbook+zip",
        )
        val cover = byteArrayOf(7)
        val fileSource = FakeDocumentFileSource(location, comicZipBytes("cover.jpg" to cover, "page2.jpg" to byteArrayOf(2)))
        val parser = CountingComicBookDocumentParser()
        val documentDao = FakeDocumentDao()
        suspend fun addToShelf() = documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.CBZ.name,
                mimeType = location.mimeType,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = parser,
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        addToShelf()

        assertContentEquals(cover, repository.getDocumentCover(documentId))
        assertEquals(1, parser.openArchiveCount)

        repository.deleteDocument(documentId)
        addToShelf()

        assertContentEquals(cover, repository.getDocumentCover(documentId))
        assertEquals(2, fileSource.copyCount, "a delete/invalidate must force the next request to copy afresh")
        assertEquals(2, parser.openArchiveCount, "a delete/invalidate must force the next request to re-open the archive")
    }

    /** [DocumentRepositoryImpl.getPageWindows] must lay out pages from the document actually stored for
     * this id, not some other or default document — the first page's location and index must anchor at
     * the book's real start. */
    @Test
    fun getPageWindowsUsesStoredReaderDocument() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        repository.importDocument(
            source = DocumentImportSource(
                location = DocumentLocation(
                    sourceUri = "file:///long.txt",
                    displayName = "long.txt",
                    mimeType = "text/plain",
                ),
                bytes = "a".repeat(240).encodeToByteArray(),
            ),
            importedAtEpochMillis = 1_000,
        )

        val pages = repository.getPageWindows(
            documentId = DocumentId("file:///long.txt"),
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
        )

        assertEquals(ReaderLocation.TextOffset(0), pages.first().location)
        assertEquals(0, pages.first().pageIndex.current)
        assertEquals(pages.size, pages.first().pageIndex.total)
    }

    /** The same re-import guarantee as [reimportPreservesDocumentBookmark], for folder membership
     * (`folderId`/`folderName`) instead of the favourite flag. */
    @Test
    fun reimportPreservesDocumentFolderMembership() = runTest {
        val documentDao = FakeDocumentDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        val source = DocumentImportSource(
            location = DocumentLocation(
                sourceUri = "file:///book.txt",
                displayName = "book.txt",
                mimeType = "text/plain",
            ),
            bytes = "Hello reader".encodeToByteArray(),
        )
        repository.importDocument(source, importedAtEpochMillis = 1_000)
        repository.upsertDocument(
            repository.getDocument(DocumentId(source.location.sourceUri))!!.copy(
                folderId = "folder-1",
                folderName = "Imported",
            ),
        )

        repository.importDocument(source, importedAtEpochMillis = 2_000)

        assertEquals("folder-1", documentDao.saved?.folderId)
        assertEquals("Imported", documentDao.saved?.folderName)
    }

    /**
     * A fresh [DocumentRepositoryImpl] instance has no in-memory cache at all, only the persisted
     * layout — so its second [DocumentRepositoryImpl.getPageWindows] call can only succeed without
     * measuring (the `poisonBreaker` below fails the test if it is ever invoked) if it actually restores
     * the layout the first instance measured and stored.
     */
    @Test
    fun getPageWindowsRestoresFromStorageWithoutMeasuringOnColdCache() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val location = DocumentLocation(
            sourceUri = "file:///restored.txt",
            displayName = "restored.txt",
            mimeType = "text/plain",
        )
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        newRepository().importDocument(
            source = DocumentImportSource(location = location, bytes = "abcdefghij".repeat(20).encodeToByteArray()),
            importedAtEpochMillis = 1_000,
        )
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + 49) / 50) { page -> page * 50 }
        }
        val firstPages = newRepository().getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style,
            viewportSize = viewportSize,
            pageBreaker = measuringBreaker,
        )
        assertTrue(pageLayoutDao.stored.isNotEmpty())

        val poisonBreaker = ReaderPageBreaker { _, _ -> fail("Stored layout should have been used instead of measuring again.") }
        val secondPages = newRepository().getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style,
            viewportSize = viewportSize,
            pageBreaker = poisonBreaker,
        )

        assertEquals(firstPages, secondPages)
    }

    /** A stored layout must never be handed to a caller asking at a different font size or a different
     * viewport — [DocumentRepositoryImpl.restorePageWindows] keying on the exact style/viewport is what
     * this guards; either kind of mismatch must fall through to a fresh measurement instead. */
    @Test
    fun getPageWindowsDoesNotRestoreWhenLayoutKeyOrViewportChanges() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        val location = DocumentLocation(
            sourceUri = "file:///changed-key.txt",
            displayName = "changed-key.txt",
            mimeType = "text/plain",
        )
        repository.importDocument(
            source = DocumentImportSource(location = location, bytes = "abcdefghij".repeat(20).encodeToByteArray()),
            importedAtEpochMillis = 1_000,
        )
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + 49) / 50) { page -> page * 50 }
        }
        repository.getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style,
            viewportSize = viewportSize,
            pageBreaker = measuringBreaker,
        )

        var calledForChangedFontSize = false
        repository.getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style.copy(fontSizeSp = 24f),
            viewportSize = viewportSize,
            pageBreaker = ReaderPageBreaker { measured, _ ->
                calledForChangedFontSize = true
                intArrayOf(0)
            },
        )
        var calledForChangedViewport = false
        repository.getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style,
            viewportSize = viewportSize.copy(widthPx = 200),
            pageBreaker = ReaderPageBreaker { measured, _ ->
                calledForChangedViewport = true
                intArrayOf(0)
            },
        )

        assertTrue(calledForChangedFontSize, "A changed layoutKey must not restore a layout measured for a different one.")
        assertTrue(calledForChangedViewport, "A changed viewport must not restore a layout measured for a different one.")
    }

    /**
     * Step 6 regression guard: `openDocument` used to seed [DocumentRepositoryImpl.getPageWindows] with
     * a hardcoded guessed viewport that almost never matched a stored row, so this is the failing case
     * the fix targets — given a layout stored at a viewport `V1` and no page breaker, a null
     * `viewportSize` must resolve exactly that row, not fall through to a fresh estimate pass. The first
     * call below measures and stores that `V1` layout with a fresh instance, exactly like an earlier
     * open of this book on this device; the `expected`/`actual` calls that follow both use fresh
     * instances again, with no in-memory cache, so nothing but the stored row itself can answer either
     * one — the `actual` call's null `viewportSize` has to resolve `V1` on its own rather than measuring
     * against some other guess.
     */
    @Test
    fun getPageWindowsWithNullViewportRestoresTheNewestStoredLayoutForTheStyle() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        val location = DocumentLocation(
            sourceUri = "file:///null-viewport.txt",
            displayName = "null-viewport.txt",
            mimeType = "text/plain",
        )
        newRepository().importDocument(
            source = DocumentImportSource(location = location, bytes = "abcdefghij".repeat(20).encodeToByteArray()),
            importedAtEpochMillis = 1_000,
        )
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + 49) / 50) { page -> page * 50 }
        }
        newRepository().getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style,
            viewportSize = viewportSize,
            pageBreaker = measuringBreaker,
        )

        val expected = newRepository().getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style,
            viewportSize = viewportSize,
        )
        val actual = newRepository().getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style,
            viewportSize = null,
        )

        assertEquals(expected, actual)
    }

    /**
     * The null-`viewportSize` resolution
     * [getPageWindowsWithNullViewportRestoresTheNewestStoredLayoutForTheStyle] proves works must still
     * refuse a mismatch on either axis: a stored row measured at `fontSizeSp =
     * 20` must not be picked up for a `null`-viewport query at `fontSizeSp = 24` (a different layout
     * key), and a row stored at one viewport (`V1`) must not be picked up for a different, explicit
     * viewport either — both must fall through to a fresh measurement instead.
     */
    @Test
    fun getPageWindowsWithNullViewportDoesNotRestoreALayoutStoredForADifferentStyleOrViewport() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        val location = DocumentLocation(
            sourceUri = "file:///null-viewport-mismatch.txt",
            displayName = "null-viewport-mismatch.txt",
            mimeType = "text/plain",
        )
        repository.importDocument(
            source = DocumentImportSource(location = location, bytes = "abcdefghij".repeat(20).encodeToByteArray()),
            importedAtEpochMillis = 1_000,
        )
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        repository.getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style,
            viewportSize = viewportSize,
            pageBreaker = ReaderPageBreaker { measured, _ ->
                IntArray((measured.length + 49) / 50) { page -> page * 50 }
            },
        )

        var measuredForDifferentStyle = false
        repository.getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style.copy(fontSizeSp = 24f),
            viewportSize = null,
            pageBreaker = ReaderPageBreaker { measured, _ ->
                measuredForDifferentStyle = true
                IntArray((measured.length + 49) / 50) { page -> page * 50 }
            },
        )
        assertTrue(measuredForDifferentStyle, "A null viewportSize must not resolve a layout stored for a different layout key.")

        var measuredForDifferentViewport = false
        repository.getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style,
            viewportSize = viewportSize.copy(widthPx = 200),
            pageBreaker = ReaderPageBreaker { measured, _ ->
                measuredForDifferentViewport = true
                IntArray((measured.length + 49) / 50) { page -> page * 50 }
            },
        )
        assertTrue(measuredForDifferentViewport, "An explicit, different viewport must not restore a layout measured for another one.")
    }

    /**
     * Regression guard for commit f33313b at the repository layer: a freshly imported book has no
     * stored layout and no breaker yet, so a null `viewportSize` must still fall back to the same
     * default guess a concrete caller used to pass (see [DefaultViewportSize]), not an empty list —
     * otherwise nothing would ever measure the pane that is the only way pagination could improve on
     * this guess.
     */
    @Test
    fun getPageWindowsWithNullViewportAndNoStoredLayoutStillReturnsAnEstimatedPagination() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        val location = DocumentLocation(
            sourceUri = "file:///fresh-import.txt",
            displayName = "fresh-import.txt",
            mimeType = "text/plain",
        )
        repository.importDocument(
            source = DocumentImportSource(location = location, bytes = "a".repeat(240).encodeToByteArray()),
            importedAtEpochMillis = 1_000,
        )

        val pages = repository.getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = null,
        )

        assertTrue(pages.isNotEmpty())
    }

    /**
     * Another app handing over a book that is already here — "open with", a share — lands on
     * [DocumentRepositoryImpl.importDocument] every time. Re-importing used to throw away the text and
     * the measured layout of a book the reader was in the middle of, so a second import of a
     * fully-imported document must be an open, not a re-import: neither the stored page layout nor the
     * stored sections may change.
     */
    @Test
    fun openingADocumentAlreadyOnTheShelfKeepsItsStoredTextAndLayout() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        val source = DocumentImportSource(
            location = DocumentLocation(
                sourceUri = "file:///reparsed.txt",
                displayName = "reparsed.txt",
                mimeType = "text/plain",
            ),
            bytes = "abcdefghij".repeat(20).encodeToByteArray(),
        )
        val documentId = DocumentId(source.location.sourceUri)
        repository.importDocument(source, importedAtEpochMillis = 1_000)
        repository.getPageWindows(
            documentId = documentId,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = ReaderPageBreaker { measured, _ ->
                IntArray((measured.length + 49) / 50) { page -> page * 50 }
            },
        )
        assertTrue(pageLayoutDao.stored.isNotEmpty())
        val storedAfterFirstImport = pageLayoutDao.stored.toList()
        val sectionsAfterFirstImport = searchIndexDao.entries.toList()

        repository.importDocument(source, importedAtEpochMillis = 2_000)

        assertEquals(
            storedAfterFirstImport,
            pageLayoutDao.stored.toList(),
            "opening a book already on the shelf must keep the layout its own measurement produced",
        )
        assertEquals(
            sectionsAfterFirstImport,
            searchIndexDao.entries.toList(),
            "opening a book already on the shelf must not rewrite its stored text",
        )
    }

    /**
     * A re-parse — the one a parser-version bump sends every older book through — can move every
     * character offset in the book, and a layout written before it now describes pages that are not
     * there. [DocumentRepositoryImpl.restorePageWindows]' `characterCount` check is what keeps such a
     * row (the one upserted directly below, with a `characterCount` far from the real document's) from
     * being handed to the reader as if it still fit.
     */
    @Test
    fun storedLayoutWrittenForADifferentCharacterCountIsDiscardedAndMeasuredAgain() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        val source = DocumentImportSource(
            location = DocumentLocation(
                sourceUri = "file:///recounted.txt",
                displayName = "recounted.txt",
                mimeType = "text/plain",
            ),
            bytes = "abcdefghij".repeat(20).encodeToByteArray(),
        )
        val documentId = DocumentId(source.location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 49) / 50) { page -> page * 50 } }
        repository.importDocument(source, importedAtEpochMillis = 1_000)

        pageLayoutDao.upsertPageLayout(
            PageLayoutEntity(
                documentId = documentId.value,
                fontSizeSp = style.fontSizeSp,
                lineHeightMultiplier = style.lineHeightMultiplier,
                fontFamilyName = "",
                viewportWidthPx = viewportSize.widthPx,
                viewportHeightPx = viewportSize.heightPx,
                characterCount = 999_999L,
                pageStartsBlob = encodePageStartsBlob(longArrayOf(0L, 7L, 11L)),
                writtenAtEpochMillis = 2_000,
            ),
        )

        val pages = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)

        assertNotEquals(
            listOf(0L, 7L, 11L),
            pages.mapNotNull { page -> page.textRange?.start },
            "a layout written for a different character count must not decide the reader's pages",
        )
        assertTrue(pages.isNotEmpty(), "the discarded row must be replaced by a fresh measurement")
    }

    /**
     * A section above the engine's bounded measurement limit yields usable estimated pages for the
     * current open, but those starts must never survive as a final measured row. The pre-seeded row
     * models a layout written by an older build that could not distinguish this estimate from a real
     * breaker result; opening must delete it, and the replacement estimate must remain memory-only.
     */
    @Test
    fun oversizedTxtEstimateIsNeitherRestoredNorStoredAsMeasuredLayout() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        val text = "a".repeat(200_001)
        val location = DocumentLocation(
            sourceUri = "file:///oversized.txt",
            displayName = "oversized.txt",
            mimeType = "text/plain",
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 2_000, heightPx = 2_000)
        repository.importDocument(
            source = DocumentImportSource(location = location, bytes = text.encodeToByteArray()),
            importedAtEpochMillis = 1_000,
        )
        pageLayoutDao.upsertPageLayout(
            PageLayoutEntity(
                documentId = documentId.value,
                fontSizeSp = style.fontSizeSp,
                lineHeightMultiplier = style.lineHeightMultiplier,
                fontFamilyName = style.layoutKey().fontFamilyName.orEmpty(),
                viewportWidthPx = viewportSize.widthPx,
                viewportHeightPx = viewportSize.heightPx,
                characterCount = text.length.toLong(),
                pageStartsBlob = encodePageStartsBlob(longArrayOf(0L, 100_000L)),
                writtenAtEpochMillis = 2_000,
            ),
        )
        var breakerCalled = false

        val pages = repository.getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
            pageBreaker = ReaderPageBreaker { _, _ ->
                breakerCalled = true
                intArrayOf(0)
            },
        )

        assertFalse(breakerCalled)
        assertTrue(pages.isNotEmpty())
        assertTrue(pageLayoutDao.stored.isEmpty(), "an oversized estimate must not remain under a measured layout key")
    }

    /**
     * The same restore guarantee as [getPageWindowsRestoresFromStorageWithoutMeasuringOnColdCache], but
     * across five sections (written straight into the search index below, the way a real book's
     * chapters are stored) instead of one — enough that an on-demand restore only building the pages it
     * is asked for is the interesting case, not an accident of there being just one section to begin
     * with. `finishPagination` drives a full measurement to completion the same way a background
     * continuation loop would; a fresh instance's restore must reproduce every one of those pages
     * byte-for-byte.
     *
     * The `DocumentEntity` upserted below is written directly, bypassing `importDocument`/
     * `persistParsedDocument`, to stand in for a plain TXT document already fully on the shelf — not one
     * progressively importing. Its `importCompletedAtEpochMillis` is deliberately non-null: leaving it
     * null would say the opposite, and [DocumentRepositoryImpl.getPageWindows] refuses to persist a
     * layout for a document [DocumentRepositoryImpl.isImportComplete] reports as still incomplete (see
     * `DocumentEntity`'s own doc on that column). Several later tests in this suite build the same
     * stand-in for the same reason.
     */
    @Test
    fun getPageWindowsOnDemandRestoreMatchesEagerMeasurementAcrossManySections() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val location = DocumentLocation(
            sourceUri = "file:///multi-section.txt",
            displayName = "multi-section.txt",
            mimeType = "text/plain",
        )
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        var cursor = 0L
        val sections = (0 until 5).map { index ->
            val text = ('a' + index).toString().repeat(40)
            val range = TextRange(cursor, cursor + text.length)
            cursor = range.end + 1
            SearchIndexEntity(
                documentId = location.sourceUri,
                sectionIndex = index,
                sectionTitle = "Section $index",
                text = text,
                startOffset = range.start,
                endOffset = range.end,
            )
        }
        searchIndexDao.upsertSearchIndex(sections)

        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + 9) / 10) { page -> page * 10 }
        }
        val documentId = DocumentId(location.sourceUri)
        val measuringRepository = newRepository()
        measuringRepository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        val measuredPages = measuringRepository.finishPagination(documentId, style, viewportSize, measuringBreaker)

        val restoredPages = newRepository().getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
        )

        assertEquals(measuredPages.size, restoredPages.size, "restoring from storage must not change the page count")
        assertEquals(measuredPages, restoredPages, "an on-demand restore must reproduce every page byte-for-byte")
    }

    /**
     * [encodePageStartsBlob]/[decodePageStartsBlob]'s little-endian `Int32`-per-offset round trip must
     * hold at large offsets, not just the small ones other tests here use — the 200,000-character `text`
     * below pushes page starts well past the small numbers other tests use, the same territory a real
     * multi-hundred-thousand-character book measures into, including offset 0 for the first page. Only
     * the blob [DocumentRepositoryImpl.storePageWindows] writes can answer a fresh instance's restore.
     */
    @Test
    fun storeThenRestorePreservesPageWindowTextRangesAtLargeOffsets() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val location = DocumentLocation(
            sourceUri = "file:///large-offsets.txt",
            displayName = "large-offsets.txt",
            mimeType = "text/plain",
        )
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        val text = "0123456789".repeat(20_000)
        newRepository().importDocument(
            source = DocumentImportSource(location = location, bytes = text.encodeToByteArray()),
            importedAtEpochMillis = 1_000,
        )
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val pageLength = 5_000
        val measuringBreaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + pageLength - 1) / pageLength) { page -> page * pageLength }
        }
        val measuredPages = newRepository().getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style,
            viewportSize = viewportSize,
            pageBreaker = measuringBreaker,
        )

        val restoredPages = newRepository().getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style,
            viewportSize = viewportSize,
        )

        assertEquals(
            measuredPages.map { it.textRange },
            restoredPages.map { it.textRange },
            "restoring the blob-encoded layout must reproduce every page's text range exactly.",
        )
    }

    /**
     * [DocumentRepositoryImpl.importDocument] must write the cover file for an EPUB that has one, and
     * [DocumentRepositoryImpl.getDocumentCover] must then serve that cached file instead of re-reading
     * the whole document. Import reads bytes straight from `DocumentImportSource`, not from the file
     * source, so the cover file has to exist before `getDocumentCover` is ever called for this to be a
     * real test of the cache rather than of the fallback path.
     */
    @Test
    fun importingAnEpubWithACoverCachesItSoGetDocumentCoverNeverRereadsTheWholeFile() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///cover-book.epub",
            displayName = "cover-book.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleEpubBytesWithCover()
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)

        repository.importDocument(
            source = DocumentImportSource(location = location, bytes = epubBytes),
            importedAtEpochMillis = 1_000,
        )
        assertTrue(
            systemFileSystem().exists(coverFilePath(fileSource, documentId)),
            "importDocument must write the cover file for an EPUB that has a cover.",
        )

        val readCountBeforeCoverRequest = fileSource.readCount
        val cover = repository.getDocumentCover(documentId)

        assertTrue(cover != null && cover.isNotEmpty())
        assertEquals(
            readCountBeforeCoverRequest,
            fileSource.readCount,
            "getDocumentCover must serve the cached cover file instead of re-reading the whole document.",
        )
    }

    /**
     * A book with no cached cover file — `documentDao` below stands in for a book imported before cover
     * caching existed: a document row and search index exist, but nothing ever wrote a cover file for
     * it, the only way that happens today — must fall back to extracting the cover on the first
     * [DocumentRepositoryImpl.getDocumentCover] call, and must cache the result on the way out so a
     * second call does not touch the source file again.
     *
     * The fallback streams the book to a temporary file instead of reading it into a [ByteArray], so
     * this also asserts `readCount` stays at zero: buffering the whole book charged its full size to the
     * heap to reach a single image, which is what could exhaust a low-memory device on an illustrated
     * book of a few hundred megabytes.
     */
    @Test
    fun getDocumentCoverFallsBackForABookImportedBeforeCoverCachingThenCachesItForNextTime() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///legacy-cover.epub",
            displayName = "legacy-cover.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleEpubBytesWithCover()
        val documentDao = FakeDocumentDao().apply {
            upsertDocument(
                DocumentEntity(
                    id = location.sourceUri,
                    name = location.displayName,
                    sourceUri = location.sourceUri,
                    format = DocumentFormat.EPUB.name,
                    mimeType = location.mimeType,
                    sizeBytes = location.sizeBytes,
                    addedAtEpochMillis = 1_000,
                ),
            )
        }
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)

        val firstCover = repository.getDocumentCover(documentId)
        assertTrue(firstCover != null && firstCover.isNotEmpty())
        assertEquals(1, fileSource.copyCount, "with no cached file yet, the first call must fall back to streaming the source once.")
        assertEquals(
            0,
            fileSource.readCount,
            "the fallback must stream the book to a temporary file rather than hold all of it in memory.",
        )

        repository.getDocumentCover(documentId)

        assertEquals(
            1,
            fileSource.copyCount,
            "the fallback must cache the cover on the way out so a second call does not stream the whole file again.",
        )
    }

    /** [DocumentRepositoryImpl.deleteDocument] must also remove the cached cover file — see
     * `invalidateCaches`/`coverFilePath`'s own doc for why deleting the shelf row alone is not enough. */
    @Test
    fun deleteDocumentRemovesTheCachedCoverFile() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///delete-cover.epub",
            displayName = "delete-cover.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleEpubBytesWithCover()
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        repository.importDocument(
            source = DocumentImportSource(location = location, bytes = epubBytes),
            importedAtEpochMillis = 1_000,
        )
        val coverPath = coverFilePath(fileSource, documentId)
        assertTrue(systemFileSystem().exists(coverPath), "the cover file must exist right after import.")

        repository.deleteDocument(documentId)

        assertFalse(systemFileSystem().exists(coverPath), "deleteDocument must remove the cached cover file.")
    }

    /**
     * [coverFilePath]'s hash of the document id must give two different books two different cover
     * paths, so importing both and reading each cover back must not cross-contaminate. Neither the
     * import path nor a cache hit ever calls `readBytes`/`copyTo` here (the cover comes from the bytes
     * passed to `importDocument` directly), so one fake standing in for "the file source" is enough —
     * only its shared `appPrivateDirectory()` matters for this test.
     */
    @Test
    fun twoDocumentsWithDifferentIdsDoNotCollideOnTheSameCoverFile() = runTest {
        val locationA = DocumentLocation(
            sourceUri = "file:///book-a.epub",
            displayName = "book-a.epub",
            mimeType = "application/epub+zip",
        )
        val locationB = DocumentLocation(
            sourceUri = "file:///book-b.epub",
            displayName = "book-b.epub",
            mimeType = "application/epub+zip",
        )
        val coverA = byteArrayOf(1, 2, 3, 4)
        val coverB = byteArrayOf(5, 6, 7, 8, 9)
        val epubBytesA = sampleEpubBytesWithCover(coverBytes = coverA)
        val epubBytesB = sampleEpubBytesWithCover(coverBytes = coverB)
        val fileSource = FakeDocumentFileSource(locationA, epubBytesA)
        val documentDao = FakeMultiDocumentDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )

        repository.importDocument(DocumentImportSource(locationA, epubBytesA), importedAtEpochMillis = 1_000)
        repository.importDocument(DocumentImportSource(locationB, epubBytesB), importedAtEpochMillis = 2_000)

        assertNotEquals(
            coverFilePath(fileSource, DocumentId(locationA.sourceUri)),
            coverFilePath(fileSource, DocumentId(locationB.sourceUri)),
        )
        assertContentEquals(coverA, repository.getDocumentCover(DocumentId(locationA.sourceUri)))
        assertContentEquals(coverB, repository.getDocumentCover(DocumentId(locationB.sourceUri)))
    }

    /**
     * Step 8 regression guard: opening used to `SELECT *` every section's blocksJson before a single
     * page was built. A restore must now only ever fetch section 0's — cover detection needs it eagerly
     * (see `TextPageLayoutEngine.findCoverSection`) — not the other four sections' blocks. This is the
     * fact [SectionBlocksCache]'s own doc calls out as what makes a lazy restore cheap.
     *
     * The `DocumentEntity` upserted below is written directly, bypassing `importDocument`/
     * `persistParsedDocument`, to stand in for a plain TXT document already fully on the shelf, with
     * `importCompletedAtEpochMillis` set for the same reason given in
     * [getPageWindowsOnDemandRestoreMatchesEagerMeasurementAcrossManySections]'s own doc.
     */
    @Test
    fun getPageWindowsRestoringFromStorageOnlyFetchesBlocksJsonForSectionZero() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val location = DocumentLocation(
            sourceUri = "file:///lazy-restore.txt",
            displayName = "lazy-restore.txt",
            mimeType = "text/plain",
        )
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        searchIndexDao.upsertSearchIndex(fiveTxtSectionsWithBlocks(location.sourceUri))
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val documentId = DocumentId(location.sourceUri)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 9) / 10) { page -> page * 10 } }
        val measuringRepository = newRepository()
        measuringRepository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        measuringRepository.finishPagination(documentId, style, viewportSize, measuringBreaker)
        searchIndexDao.blocksJsonQueries.clear()

        newRepository().getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
        )

        assertEquals(
            listOf(listOf(0)),
            searchIndexDao.blocksJsonQueries,
            "restoring from storage must fetch blocksJson only for section 0, not the other four sections",
        )
    }

    /**
     * [DocumentRepositoryImpl.warmSectionBlocks] on a genuine miss (section 2, after a restore that
     * only auto-prewarms section 0) must decode and report exactly the missed section, not the rest of
     * the book, and must not re-fetch a section already decoded on a second call — reporting 0 newly
     * decoded that time, the return value `ReaderViewModel.continueBlockWarmIfIncomplete` relies on to
     * skip re-publishing when a warm changed nothing.
     */
    @Test
    fun warmSectionBlocksOnAMissFetchesExactlyThatSectionAndNoOthers() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val location = DocumentLocation(
            sourceUri = "file:///warm-one-section.txt",
            displayName = "warm-one-section.txt",
            mimeType = "text/plain",
        )
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        searchIndexDao.upsertSearchIndex(fiveTxtSectionsWithBlocks(location.sourceUri))
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val documentId = DocumentId(location.sourceUri)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 9) / 10) { page -> page * 10 } }
        val measuringRepository = newRepository()
        measuringRepository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        measuringRepository.finishPagination(documentId, style, viewportSize, measuringBreaker)

        val repository = newRepository()
        repository.getPageWindows(documentId = documentId, style = style, viewportSize = viewportSize)
        searchIndexDao.blocksJsonQueries.clear()

        val firstWarmCount = repository.warmSectionBlocks(documentId, setOf(2))
        assertEquals(1, firstWarmCount, "a genuine miss must report the one section it actually decoded")

        assertEquals(
            listOf(listOf(2)),
            searchIndexDao.blocksJsonQueries,
            "a miss must fetch exactly the missed section, not the rest of the book",
        )

        val secondWarmCount = repository.warmSectionBlocks(documentId, setOf(2))
        assertEquals(listOf(listOf(2)), searchIndexDao.blocksJsonQueries, "a section already decoded must not be re-fetched")
        assertEquals(0, secondWarmCount, "an already-decoded section must report 0 newly decoded")
    }

    /**
     * Warming 25 sections in bounded batches of 5 — the shape that replaces warming every section in a
     * single call, which risked the flagged SQLite variable-limit in
     * `ReaderViewModel.continueBlockWarmIfIncomplete`'s own doc — must query no more sections than each
     * batch itself asked for, one query per batch rather than one for the whole book, and must decode
     * exactly what a single whole-book warm (`allAtOnce` below, standing in for the old call) would.
     */
    @Test
    fun warmSectionBlocksInBoundedBatchesMatchesOneBigWarm() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val location = DocumentLocation(
            sourceUri = "file:///many-sections.txt",
            displayName = "many-sections.txt",
            mimeType = "text/plain",
        )
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        val sectionCount = 25
        searchIndexDao.upsertSearchIndex(manyTxtSectionsWithBlocks(location.sourceUri, sectionCount))
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val documentId = DocumentId(location.sourceUri)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 9) / 10) { page -> page * 10 } }
        val measuringRepository = newRepository()
        measuringRepository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        measuringRepository.finishPagination(documentId, style, viewportSize, measuringBreaker)

        val allAtOnce = newRepository()
        val allAtOncePages = allAtOnce.getPageWindows(documentId = documentId, style = style, viewportSize = viewportSize)
        allAtOnce.warmSectionBlocks(documentId, (0 until sectionCount).toSet())

        val batched = newRepository()
        val batchedPages = batched.getPageWindows(documentId = documentId, style = style, viewportSize = viewportSize)
        searchIndexDao.blocksJsonQueries.clear()
        val batchSize = 5
        val batches = (0 until sectionCount).chunked(batchSize)
        batches.forEach { batch -> batched.warmSectionBlocks(documentId, batch.toSet()) }

        assertTrue(
            searchIndexDao.blocksJsonQueries.all { it.size <= batchSize },
            "every batched warm must query no more sections than its own batch asked for: ${searchIndexDao.blocksJsonQueries}",
        )
        assertEquals(
            batches.size,
            searchIndexDao.blocksJsonQueries.size,
            "one query per batch, never one query for the whole book",
        )
        assertEquals(
            allAtOncePages.map { it.textRange },
            batchedPages.map { it.textRange },
            "warming in bounded batches must preserve the same restored pages even when the cache retains fewer than every warmed section",
        )
    }

    /**
     * A progressive import batch that appends later sections must not drop the active phase-0
     * section-block cache before completion: a caller that already cached the phase-0 document but has
     * not warmed one of its sections yet still needs that same cache object to decode from after the
     * batch. Section 1 below is chapter 1 from phase 0; the added batch imports chapter 2, but warming
     * chapter 1 afterward must still decode from the pre-existing cache rather than reporting 0 until a
     * full reload rebuilds it.
     */
    @Test
    fun importNextSectionsKeepsCachedPhase0SectionsWarmableUntilCompletion() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///phase0-cache-survives-batch.epub",
            displayName = "phase0-cache-survives-batch.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount = 30)
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)

        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        assertFalse(repository.isImportComplete(documentId), "phase 0 alone must leave later chapters for importNextSections")

        repository.getReaderDocument(documentId)
        searchIndexDao.blocksJsonQueries.clear()

        val progress = repository.importNextSections(documentId, count = 1, style, viewportSize, pageBreaker = null)
        assertFalse(progress.isComplete, "importing one more chapter must still leave this book incomplete")

        assertEquals(
            1,
            repository.warmSectionBlocks(documentId, setOf(1)),
            "a later import batch must not drop the cached phase-0 section-block cache before completion",
        )
        assertEquals(
            listOf(listOf(1)),
            searchIndexDao.blocksJsonQueries,
            "warming a still-unwarmed phase-0 section after a later batch must decode that section from the existing cache",
        )
    }

    /**
     * [warmSectionBlocks] only warms the cache object this repository instance is currently holding.
     * An eager cache drop through `importDocument -> persistParsedDocument -> invalidateCaches` must
     * therefore make the next warm a no-op until something reloads the document and rebuilds that
     * cache.
     */
    @Test
    fun warmSectionBlocksReportsNothingWhileNoDocumentIsLoaded() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val location = DocumentLocation(
            sourceUri = "file:///reimport-drops-the-cache.txt",
            displayName = "reimport-drops-the-cache.txt",
            mimeType = "text/plain",
        )
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
            ),
        )
        searchIndexDao.upsertSearchIndex(fiveTxtSectionsWithBlocks(location.sourceUri))
        val documentId = DocumentId(location.sourceUri)

        repository.getReaderDocument(documentId)
        assertEquals(
            1,
            repository.warmSectionBlocks(documentId, setOf(0)),
            "a loaded document must actually decode the section asked for",
        )

        repository.importDocument(
            source = DocumentImportSource(location = location, bytes = "Reimported paragraph.".encodeToByteArray()),
            importedAtEpochMillis = 2_000,
        )

        assertEquals(
            0,
            repository.warmSectionBlocks(documentId, setOf(0)),
            "warmSectionBlocks must be a no-op while nothing has reloaded the document since the cache was dropped",
        )

        repository.getReaderDocument(documentId)
        assertEquals(
            1,
            repository.warmSectionBlocks(documentId, setOf(0)),
            "once something reloads the document, the same warm must actually decode again",
        )
    }

    @Test
    fun warmSectionBlocksRefreshesRecencyForAlreadyDecodedSections() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val location = DocumentLocation(
            sourceUri = "file:///warm-recency.txt",
            displayName = "warm-recency.txt",
            mimeType = "text/plain",
        )
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        searchIndexDao.upsertSearchIndex(manyTxtSectionsWithBlocks(location.sourceUri, 25))
        val documentId = DocumentId(location.sourceUri)
        repository.getReaderDocument(documentId)

        repository.warmSectionBlocks(documentId, (0 until 24).toSet())
        searchIndexDao.blocksJsonQueries.clear()

        assertEquals(0, repository.warmSectionBlocks(documentId, setOf(0)))
        assertEquals(1, repository.warmSectionBlocks(documentId, setOf(24)))
        searchIndexDao.blocksJsonQueries.clear()

        assertEquals(0, repository.warmSectionBlocks(documentId, setOf(0)))
        assertTrue(searchIndexDao.blocksJsonQueries.isEmpty(), "refreshing an already-decoded section must keep it resident under the cap")
    }

    /**
     * The same byte-for-byte restore guarantee as
     * [getPageWindowsOnDemandRestoreMatchesEagerMeasurementAcrossManySections], for an EPUB with a real
     * synthetic cover section rather than plain TXT sections. `restoringRepository` below, once every
     * section is warmed, stands in for "every section's blocks were loaded eagerly" — the way
     * `SELECT *` used to hand every row's blocksJson over before a single page was built.
     */
    @Test
    fun getPageWindowsOnDemandRestoreMatchesEagerMeasurementForDocumentWithACoverSection() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val location = DocumentLocation(
            sourceUri = "file:///multi-chapter-cover.epub",
            displayName = "multi-chapter-cover.epub",
            mimeType = "application/epub+zip",
        )
        val chapterCount = 4
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount)
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        newRepository().importDocument(
            source = DocumentImportSource(location = location, bytes = epubBytes),
            importedAtEpochMillis = 1_000,
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + 29) / 30) { page -> page * 30 }
        }
        val measuringRepository = newRepository()
        measuringRepository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        val measuredPages = measuringRepository.finishPagination(documentId, style, viewportSize, measuringBreaker)

        val restoringRepository = newRepository()
        val restoredPages = restoringRepository.getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
        )
        restoringRepository.warmSectionBlocks(documentId, (0..chapterCount).toSet())

        assertEquals(
            measuredPages,
            restoredPages,
            "an on-demand restore, once every section is warmed, must reproduce every page byte-for-byte, cover included",
        )
    }

    /**
     * A page built before its own section's blocks arrive must render as "not yet" (empty `blocks`,
     * with four ten-character pages per forty-character section and no cover, page 4 is section 1's
     * first page — a genuine miss right now since only section 0 is prewarmed automatically), then, once
     * [DocumentRepositoryImpl.warmSectionBlocks] fills that section in, complete its blocks without ever
     * moving its `textRange`. A later, unrelated background fill for the rest of the book must not
     * disturb it again — this is the guarantee [SectionBlocksCache]'s own doc calls "a page already
     * shown keeps its text and its blocks."
     */
    @Test
    fun livePaginationPagesRebuildAfterWarmWhenAnEvictedMeasuredSectionReturns() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val location = DocumentLocation(
            sourceUri = "file:///live-session-rebuild.txt",
            displayName = "live-session-rebuild.txt",
            mimeType = "text/plain",
        )
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        searchIndexDao.upsertSearchIndex(manyTxtSectionsWithBlocks(location.sourceUri, 30))
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val onePagePerSection = ReaderPageBreaker { _, _ -> intArrayOf(0) }

        repository.getPageWindows(documentId, style, viewportSize, onePagePerSection)
        repeat(3) { repository.continuePagination(documentId, style, viewportSize, onePagePerSection) }

        val pages = repository.getPageWindows(documentId, style, viewportSize)
        val beforeWarm = pages.first()
        assertTrue(beforeWarm.blocks.isEmpty(), "an evicted measured section must fall back to not-yet blocks before it is rewarmed")

        repository.warmSectionBlocks(documentId, setOf(0))
        val afterWarm = pages.first()
        assertEquals(beforeWarm.textRange, afterWarm.textRange)
        assertTrue(afterWarm.blocks.isNotEmpty(), "warming an evicted measured section must rebuild the same cached page with real blocks")
    }

    @Test
    fun pageBuiltWhileItsSectionIsStillMissingSelfHealsThenStaysStableAfterTheBackgroundFill() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val location = DocumentLocation(
            sourceUri = "file:///self-heal.txt",
            displayName = "self-heal.txt",
            mimeType = "text/plain",
        )
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        searchIndexDao.upsertSearchIndex(fiveTxtSectionsWithBlocks(location.sourceUri))
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 9) / 10) { page -> page * 10 } }
        val documentId = DocumentId(location.sourceUri)
        val measuringRepository = newRepository()
        measuringRepository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        measuringRepository.finishPagination(documentId, style, viewportSize, measuringBreaker)

        val repository = newRepository()
        val pages = repository.getPageWindows(documentId = documentId, style = style, viewportSize = viewportSize)
        val missedPageIndex = 4
        val beforeFill = pages[missedPageIndex]
        assertTrue(beforeFill.blocks.isEmpty(), "a page must render as 'not yet' before its section's blocks arrive")

        repository.warmSectionBlocks(documentId, setOf(1))
        val afterFill = pages[missedPageIndex]
        assertEquals(beforeFill.textRange, afterFill.textRange, "filling in a section must never move where a page's text starts")
        assertTrue(afterFill.blocks.isNotEmpty(), "filling in the missed section must complete the page's blocks")

        repository.warmSectionBlocks(documentId, (0 until 5).toSet())
        val afterBackgroundFill = pages[missedPageIndex]
        assertEquals(afterFill.textRange, afterBackgroundFill.textRange)
        assertEquals(afterFill.blocks, afterBackgroundFill.blocks)
    }

    /**
     * The first test of progressive EPUB import (step 9): written first, before
     * [DocumentRepositoryImpl.importEpubPhase0]/[DocumentRepositoryImpl.importNextSections] existed,
     * this is the test that had to fail to compile against the pre-change `DocumentRepository`
     * interface, since neither [DocumentRepositoryImpl.isImportComplete] nor
     * [DocumentRepositoryImpl.importNextSections] existed for it to call. It pins phase 0 itself: a
     * long enough EPUB must not be complete after phase 0/1's bounded read-ahead alone,
     * `characterCount` must stay null until the import completes, and only that initial prefix of
     * sections must be persisted, not the rest of the spine.
     *
     * `bytes=null` is what exercises the phased path in [DocumentRepositoryImpl.importDocument]: it
     * treats a non-null `bytes` argument as "the caller already has everything, just do the old
     * one-shot parse," and this suite (every progressive-import test below) is specifically testing the
     * case where it does not — a picked file streamed straight from `fileSource` instead.
     */
    @Test
    fun importDocumentForMultiChapterEpubOnlyPersistsPhase0SectionsAndLeavesImportIncomplete() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///progressive.epub",
            displayName = "progressive.epub",
            mimeType = "application/epub+zip",
        )
        val chapterCount = 30
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount = chapterCount)
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)

        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)

        assertFalse(
            repository.isImportComplete(documentId),
            "a long EPUB must not be complete after phase 0/1's bounded read-ahead alone",
        )
        val metadata = repository.getDocument(documentId)
        assertEquals(null, metadata?.characterCount, "characterCount must stay null in domain metadata until the import completes")
        assertTrue(
            (documentDao.saved?.characterCount ?: 0L) > 0L,
            "entity stores a partial accumulator count during incomplete import",
        )
        val persistedSections = searchIndexDao.entries.filter { it.documentId == documentId.value }.map { it.sectionIndex }.sorted()
        assertTrue(
            persistedSections.isNotEmpty() && persistedSections.size < chapterCount + 1,
            "phase 0 must persist only an initial prefix of sections, not the rest of the spine",
        )
        assertEquals((0 until persistedSections.size).toList(), persistedSections)
    }

    @Test
    fun importDocumentForEpubWithNullLeadingSpineStillOpensFirstContentAndNext() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///null-leading-spine.epub",
            displayName = "null-leading-spine.epub",
            mimeType = "application/epub+zip",
        )
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = FakeDocumentFileSource(location, sampleEpubBytesWithCoverAndNullLeadingSpine()),
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val onePagePerSection = ReaderPageBreaker { _, _ -> intArrayOf(0) }

        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        val pages = repository.getPageWindows(documentId, style, viewportSize, onePagePerSection)

        assertTrue(pages.any { it.text.contains("Chapter 2") }, "phase 0 must skip the null leading spine item and open real content")
        assertTrue(pages.any { it.text.contains("Chapter 3") }, "phase 0 must also prepare the next readable section")
    }

    @Test
    fun importDocumentForEpubKeepsReadingPastImageAndShortFrontMatterUntilRealContentIsBuffered() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///phase0-front-matter.epub",
            displayName = "phase0-front-matter.epub",
            mimeType = "application/epub+zip",
        )
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = FakeDocumentFileSource(location, sampleEpubBytesWithImageAndShortFrontMatter()),
        )
        val documentId = DocumentId(location.sourceUri)

        val imported = repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        val stored = requireNotNull(repository.getReaderDocument(documentId))

        assertTrue(imported.sections.any { it.title == "Chapter 3" }, "phase 0 must not stop after image-only/short front matter")
        assertTrue(imported.sections.any { it.title == "Chapter 4" }, "phase 0 must keep buffering later real content")
        assertEquals(imported.sections.map { it.title }, stored.sections.map { it.title })
        assertFalse(repository.isImportComplete(documentId), "the bounded read-ahead must still leave later spine items for background import")
    }

    @Test
    fun importDocumentForFullyReadAheadTwoSectionEpubPersistsNavigationAndNavTitles() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///phase0-navigation.epub",
            displayName = "phase0-navigation.epub",
            mimeType = "application/epub+zip",
        )
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = FakeDocumentFileSource(location, sampleTwoChapterEpubBytesWithCoverAndNavigation()),
        )

        val imported = repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        val documentId = DocumentId(location.sourceUri)
        val restored = requireNotNull(repository.getReaderDocument(documentId))

        assertTrue(repository.isImportComplete(documentId), "a two-section EPUB fully consumed by phase 0 must already be complete")
        assertEquals(listOf("Start Here", "Keep Going"), imported.navigation?.items?.map { it.title } ?: emptyList())
        assertEquals(imported.navigation, restored.navigation)
        assertEquals(listOf("Start Here", "Keep Going"), imported.sections.drop(1).map { it.title })
        assertEquals(listOf("Start Here", "Keep Going"), searchIndexDao.entries.filter { it.documentId == documentId.value }.sortedBy { it.sectionIndex }.drop(1).map { it.sectionTitle })
        assertTrue(documentDao.saved?.characterCount != null, "phase 0 completion must persist final counts too")
    }

    @Test
    fun progressiveEpubImportReusesTheOriginalScratchCopyAcrossAllBatches() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///scratch-reuse.epub",
            displayName = "scratch-reuse.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount = 6)
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)

        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        var guard = 0
        while (!repository.isImportComplete(documentId)) {
            repository.importNextSections(documentId, count = 2, style, viewportSize, pageBreaker = null)
            guard += 1
            check(guard < 10) { "import did not converge" }
        }

        assertEquals(1, fileSource.copyCount, "phase 0 and every continuation batch must reuse the same scratch copy")
    }

    @Test
    fun importNextSectionsContinuesCorrectlyAfterProcessStyleRestartFallbacksCursorOnce() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///cursor-fallback.epub",
            displayName = "cursor-fallback.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount = 6)
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)

        newRepository().importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        val restarted = newRepository()
        var guard = 0
        while (!restarted.isImportComplete(documentId)) {
            restarted.importNextSections(documentId, count = 2, style, viewportSize, pageBreaker = null)
            guard += 1
            check(guard < 10) { "import did not converge after cursor fallback" }
        }

        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6),
            searchIndexDao.entries.filter { it.documentId == documentId.value }.map { it.sectionIndex }.sorted(),
            "a restarted repository must replay once, recover the next spine cursor, and finish without skipping or duplicating sections",
        )
    }

    /**
     * `characterCount` must stay null through every incomplete batch of
     * [DocumentRepositoryImpl.importNextSections] and only become the real total once the whole book
     * has imported — see
     * [importDocumentForMultiChapterEpubOnlyPersistsPhase0SectionsAndLeavesImportIncomplete] for why
     * `bytes=null` is used here.
     */
    @Test
    fun characterCountStaysNullUntilImportNextSectionsCompletesTheBook() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///progressive-charcount.epub",
            displayName = "progressive-charcount.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount = 30)
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val documentDao = FakeDocumentDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle()
        val viewportSize = ViewportSize(widthPx = 320, heightPx = 560)
        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        val metadataAfterPhase0 = repository.getDocument(documentId)
        assertEquals(null, metadataAfterPhase0?.characterCount, "domain metadata characterCount must be null during incomplete import")
        assertTrue((documentDao.saved?.characterCount ?: 0L) > 0L, "entity stores a partial accumulator count")

        repository.importNextSections(documentId, count = 1, style, viewportSize, pageBreaker = null)
        val metadataAfterBatch = repository.getDocument(documentId)
        assertEquals(null, metadataAfterBatch?.characterCount, "characterCount must stay null in domain while any section remains unimported")
        assertFalse(repository.isImportComplete(documentId))

        var guard = 0
        while (!repository.isImportComplete(documentId)) {
            repository.importNextSections(documentId, count = 10, style, viewportSize, pageBreaker = null)
            guard += 1
            check(guard < 20) { "import did not converge" }
        }
        assertTrue((documentDao.saved?.characterCount ?: 0L) > 0L, "characterCount must be the real total once the import completes")
    }

    /**
     * Driving [DocumentRepositoryImpl.importNextSections] to completion, one section per call, must
     * finish the book and produce stored text/titles identical to a direct, one-shot
     * [EpubDocumentParser.parse] of the same bytes — progressive import must not lose, reorder, or
     * corrupt anything relative to the non-progressive path.
     */
    @Test
    fun repeatedImportNextSectionsCompletesNavigationWithoutRestartFallback() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///progressive-nav-complete.epub",
            displayName = "progressive-nav-complete.epub",
            mimeType = "application/epub+zip",
        )
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = FakeDocumentFileSource(location, sampleMultiChapterEpubBytesWithCoverAndNavigation(chapterCount = 4)),
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle()
        val viewportSize = ViewportSize(widthPx = 320, heightPx = 560)

        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        while (!repository.isImportComplete(documentId)) {
            repository.importNextSections(documentId, count = 1, style, viewportSize, pageBreaker = null)
        }
        val restored = requireNotNull(repository.getReaderDocument(documentId))
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4"), restored.navigation?.items?.map { it.title })
    }

    /**
     * Reproduces the first of two cache-invalidation ordering bugs in
     * [DocumentRepositoryImpl.finishEpubImport]: it used to stamp
     * `documents.importCompletedAtEpochMillis` (what [DocumentRepositoryImpl.isImportComplete] reads)
     * several statements before it invalidated the document cache, leaving a window where a reader
     * already holding this book open — its [DocumentRepositoryImpl.getReaderDocument] cache primed while
     * the import was still running — saw [DocumentRepositoryImpl.isImportComplete] answer true while
     * [DocumentRepositoryImpl.getReaderDocument] kept serving the pre-completion document, whose
     * navigation is always empty until this exact step resolves it. Nothing else ever invalidates that
     * cache entry afterwards, so the empty table of contents this produced stuck until the next app
     * relaunch — the same user-visible symptom `fix/outline-after-import` closed by a different route.
     *
     * [FakeDocumentDao.completionStampGate] parks the writer's completion-stamping write at exactly the
     * point the bug lived: after the stamp is visible in storage, before
     * [DocumentRepositoryImpl.finishEpubImport] goes on to invalidate the cache. A fixed
     * [DocumentRepositoryImpl.finishEpubImport] invalidates the cache before writing that stamp at all, so
     * by the time the write reaches the gate the cache has already been cleared and the concurrent
     * [DocumentRepositoryImpl.getReaderDocument] call below is forced to reload with the resolved
     * navigation already in hand — which is what this test asserts.
     *
     * The completing [importNextSections] call below asks for `count = 30`, covering every section
     * phase 0 could possibly have left unread for this 30-chapter book, so that one call is guaranteed
     * to be the batch that finishes the import and reaches [completionStampGate].
     */
    @Test
    fun aReaderCaughtBetweenTheCompletionStampAndTheCacheInvalidationMustNotSeeStaleNavigation() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///completion-cache-race.epub",
            displayName = "completion-cache-race.epub",
            mimeType = "application/epub+zip",
        )
        val documentDao = FakeDocumentDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = FakeDocumentFileSource(location, sampleMultiChapterEpubBytesWithCoverAndNavigation(chapterCount = 30)),
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle()
        val viewportSize = ViewportSize(widthPx = 320, heightPx = 560)

        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        assertFalse(
            repository.isImportComplete(documentId),
            "a 30-chapter EPUB must not be complete after phase 0's bounded read-ahead alone",
        )
        val phase0Document = requireNotNull(repository.getReaderDocument(documentId))
        assertTrue(
            phase0Document.navigation?.items.orEmpty().isEmpty(),
            "a still-importing EPUB must not have resolved navigation yet",
        )

        val completionStampGate = CompletableDeferred<Unit>()
        val completionStampReached = CompletableDeferred<Unit>()
        documentDao.completionStampGate = completionStampGate
        documentDao.completionStampReached = completionStampReached

        val completing = launch {
            repository.importNextSections(documentId, count = 30, style, viewportSize, pageBreaker = null)
        }
        completionStampReached.await()

        assertTrue(repository.isImportComplete(documentId), "the completion stamp must already be visible in storage")
        val servedWhileParked = repository.getReaderDocument(documentId)
        assertFalse(
            servedWhileParked?.navigation?.items.orEmpty().isEmpty(),
            "isImportComplete() already answers true, so getReaderDocument() must not still be serving the " +
                "pre-completion document with no navigation",
        )

        completionStampGate.complete(Unit)
        completing.join()

        val afterCompletion = requireNotNull(repository.getReaderDocument(documentId))
        assertEquals(
            (1..30).map { "Chapter $it" },
            afterCompletion.navigation?.items?.map { it.title },
        )
    }

    /**
     * Reproduces the second cache-invalidation bug: [DocumentRepositoryImpl.getReaderDocument] loads a
     * document outside `documentCacheLock` on purpose, so one slow load never serialises every other
     * document read behind it (see that lock's own doc), then re-acquires the lock only to publish the
     * result. A load that started before some other write invalidated the cache, but that only reaches
     * its own publishing step after the invalidation already ran, used to write its pre-invalidation
     * snapshot straight back into the cache — silently undoing the invalidation it straddled.
     *
     * [FakeDocumentSearchIndexDao.getDocumentSectionsWithoutBlocksGate] parks a
     * [DocumentRepositoryImpl.getReaderDocument] load right after it has read the document's rows (while
     * it still exists) but before it returns them. While it is parked,
     * [DocumentRepositoryImpl.deleteDocument] removes the document entirely, which must invalidate the
     * cache. Releasing the gate then lets the parked load finish and try to publish its now-stale
     * snapshot. A fixed cache refuses that publish because the invalidation bumped
     * `documentCacheGeneration` while the load was in flight, so the subsequent
     * [DocumentRepositoryImpl.getReaderDocument] call below must see the deletion — not the stale
     * snapshot a buggy cache would otherwise have kept alive indefinitely.
     */
    @Test
    fun aLoadThatStraddlesAnInvalidationMustNotLeaveItsStaleSnapshotCached() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///cache-race.txt",
            displayName = "cache-race.txt",
            mimeType = "text/plain",
        )
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        val documentId = DocumentId(location.sourceUri)
        repository.importDocument(
            DocumentImportSource(location, bytes = "Hello reader".encodeToByteArray()),
            importedAtEpochMillis = 1_000,
        )

        val loadGate = CompletableDeferred<Unit>()
        val loadReached = CompletableDeferred<Unit>()
        searchIndexDao.getDocumentSectionsWithoutBlocksGate = loadGate
        searchIndexDao.getDocumentSectionsWithoutBlocksReached = loadReached

        val staleLoad = launch { repository.getReaderDocument(documentId) }
        loadReached.await()

        repository.deleteDocument(documentId)

        loadGate.complete(Unit)
        staleLoad.join()

        assertNull(
            repository.getReaderDocument(documentId),
            "a document deleted while a stale load was in flight must not still be served from that load's cache write",
        )
    }

    /**
     * Reproduces the third cache-invalidation bug, the mirror image of the second one above:
     * [DocumentRepositoryImpl.persistParsedDocument] invalidates the document cache *before* it
     * rewrites a document's stored rows, not after. `documentDao.upsertDocument` makes the row (and
     * `isImportComplete`) visible immediately, but `searchIndexDao.deleteSearchIndex` then empties
     * every section row, and they are not written back until `searchIndexDao.upsertSearchIndex`
     * finishes. A [DocumentRepositoryImpl.getReaderDocument] load that starts in that window reads
     * zero sections; without a second invalidation after the rewrite, nothing would ever clear that
     * torn snapshot back out of the cache, so it would stick until the next app relaunch — the
     * original bug reopened through the writer instead of the reader.
     *
     * [FakeDocumentSearchIndexDao.upsertSearchIndexGate] parks the writer's
     * [DocumentRepositoryImpl.persistParsedDocument] call right after `deleteSearchIndex` has emptied
     * the document's rows but before the freshly parsed rows are written back. This repository is
     * built with no `documentFileSource`, so the TXT repair
     * [DocumentRepositoryImpl.loadReaderDocument] would otherwise attempt on seeing zero sections
     * bails out immediately instead of re-parsing (`DocumentRepositoryImpl.repairTxtDocument` returns
     * null the moment its own file source is missing) — keeping the racing read a single,
     * deterministic load instead of a second concurrent rewrite. That racing
     * [DocumentRepositoryImpl.getReaderDocument] call is made directly, not launched, once the writer
     * is parked, so it publishes the torn, empty snapshot synchronously before the writer resumes. A
     * fixed [DocumentRepositoryImpl.persistParsedDocument] invalidates the cache again after its
     * rewrite completes, clearing that wrongly-published entry; an unfixed one leaves it cached
     * forever, so the [DocumentRepositoryImpl.getReaderDocument] call made below — after the writer has
     * finished and the real rows are in storage — would still return the empty snapshot instead of
     * reloading.
     */
    @Test
    fun aLoadRacingPersistParsedDocumentsRewriteMustNotLeaveTheCacheHoldingTheTornSnapshot() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///persist-race.txt",
            displayName = "persist-race.txt",
            mimeType = "text/plain",
        )
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        val documentId = DocumentId(location.sourceUri)

        val upsertSearchIndexGate = CompletableDeferred<Unit>()
        val upsertSearchIndexReached = CompletableDeferred<Unit>()
        searchIndexDao.upsertSearchIndexGate = upsertSearchIndexGate
        searchIndexDao.upsertSearchIndexReached = upsertSearchIndexReached

        val importing = launch {
            repository.importDocument(
                DocumentImportSource(location, bytes = "Hello reader".encodeToByteArray()),
                importedAtEpochMillis = 1_000,
            )
        }
        upsertSearchIndexReached.await()

        assertTrue(
            repository.isImportComplete(documentId),
            "the metadata row must already be visible before the section rows are written back",
        )
        val servedWhileTorn = repository.getReaderDocument(documentId)
        assertTrue(
            servedWhileTorn?.sections.orEmpty().isEmpty(),
            "a load racing the rewrite must read the torn, momentarily-empty snapshot -- this is what makes the hole real",
        )

        upsertSearchIndexGate.complete(Unit)
        importing.join()

        val afterRewrite = repository.getReaderDocument(documentId)
        assertTrue(
            afterRewrite?.sections.orEmpty().isNotEmpty(),
            "once persistParsedDocument's rewrite has finished, getReaderDocument must not still be serving the torn " +
                "snapshot published while it was in flight",
        )
    }

    @Test
    fun repeatedImportNextSectionsEventuallyCompletesMatchingAFullParse() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///progressive-complete.epub",
            displayName = "progressive-complete.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount = 5)
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle()
        val viewportSize = ViewportSize(widthPx = 320, heightPx = 560)
        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)

        var guard = 0
        while (!repository.isImportComplete(documentId)) {
            repository.importNextSections(documentId, count = 1, style, viewportSize, pageBreaker = null)
            guard += 1
            check(guard < 20) { "import did not converge" }
        }

        val fullParse = EpubDocumentParser().parse(documentId, location.displayName, epubBytes)
        val storedSections = searchIndexDao.entries.filter { it.documentId == documentId.value }.sortedBy { it.sectionIndex }
        assertTrue(repository.isImportComplete(documentId))
        assertEquals(fullParse.characterCount, documentDao.saved?.characterCount)
        assertEquals(fullParse.sections.sortedBy { it.index }.map { it.text }, storedSections.map { it.text })
        assertEquals(fullParse.sections.sortedBy { it.index }.map { it.title }, storedSections.map { it.sectionTitle })
    }

    /**
     * A "crash": nothing survives but what's in `documentDao`/`searchIndexDao` — every call after the
     * first two below uses a brand-new [DocumentRepositoryImpl] instance, with none of the previous
     * ones' in-memory state, and must still finish the book correctly by reading only the stored rows,
     * with no section skipped or duplicated across the simulated crash.
     */
    @Test
    fun importNextSectionsResumesFromStoredRowsAloneAfterASimulatedCrash() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///crash-resume.epub",
            displayName = "crash-resume.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount = 5)
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle()
        val viewportSize = ViewportSize(widthPx = 320, heightPx = 560)
        newRepository().importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        newRepository().importNextSections(documentId, count = 1, style, viewportSize, pageBreaker = null)

        var guard = 0
        while (!newRepository().isImportComplete(documentId)) {
            newRepository().importNextSections(documentId, count = 1, style, viewportSize, pageBreaker = null)
            guard += 1
            check(guard < 20) { "import did not converge" }
        }

        val fullParse = EpubDocumentParser().parse(documentId, location.displayName, epubBytes)
        val storedSections = searchIndexDao.entries.filter { it.documentId == documentId.value }.sortedBy { it.sectionIndex }
        assertEquals(
            fullParse.sections.map { it.index },
            storedSections.map { it.sectionIndex },
            "no section must be skipped or duplicated across the simulated crash",
        )
        assertEquals(fullParse.sections.sortedBy { it.index }.map { it.text }, storedSections.map { it.text })
    }

    /**
     * A page already published (from the cover-plus-chapter-1 measurement `importDocument`'s phase 0
     * leaves behind) must keep its exact boundaries once [DocumentRepositoryImpl.importNextSections]
     * appends more sections. Writing a layout while the import is unfinished is exactly what
     * [DocumentRepositoryImpl.getPageWindows]' own `isImportComplete` guard refuses, so growth is not
     * visible from a second, bare `getPageWindows` call the moment more sections land — it takes the
     * same continuation `finishPagination` stands in for elsewhere in this suite. Re-seeding with
     * `getPageWindows` first measures the anchor section against the now-complete book, then
     * `finishPagination` walks the rest, the same two steps `openDocument` +
     * `continuePaginationIfIncomplete` take.
     */
    @Test
    fun pageTextRangeIsUnchangedAfterImportNextSectionsAppendsMoreSections() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///append-stable.epub",
            displayName = "append-stable.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount = 30)
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)

        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 19) / 20) { page -> page * 20 } }

        val firstPages = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        assertTrue(firstPages.size > 1, "the cover plus chapter 1 must already measure to more than one page")
        val lastPublishedRange = firstPages.last().textRange

        var guard = 0
        while (!repository.isImportComplete(documentId)) {
            repository.importNextSections(documentId, count = 10, style, viewportSize, measuringBreaker)
            guard += 1
            check(guard < 20) { "import did not converge" }
        }
        repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)

        val grownPages = repository.finishPagination(documentId, style, viewportSize, measuringBreaker)
        assertTrue(grownPages.size > firstPages.size, "importing more sections must grow the known page count")
        assertEquals(
            lastPublishedRange,
            grownPages[firstPages.lastIndex].textRange,
            "a page already published must keep its exact boundaries once later sections are appended",
        )
    }

    /**
     * A bounded first measurement must not persist an incomplete session. Once background continuation
     * has measured the current prefix, progressive import may store and append a partial row; after both
     * import and pagination finish, that row must remain available as the promoted final layout.
     */
    @Test
    fun incompletePaginationSessionIsNotStoredBeforeItsPrefixFinishes() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///no-write-mid-import.epub",
            displayName = "no-write-mid-import.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount = 30)
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val pageLayoutDao = FakePageLayoutDao()
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 19) / 20) { page -> page * 20 } }

        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)

        val firstPages = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        assertTrue(firstPages.isNotEmpty(), "phase 0 must already measure something to pin this test on")
        assertFalse(repository.isImportComplete(documentId))
        assertTrue(
            pageLayoutDao.stored.isEmpty(),
            "a bounded session must not be persisted before it has measured its current prefix",
        )

        var guard = 0
        while (!repository.isImportComplete(documentId)) {
            repository.importNextSections(documentId, count = 10, style, viewportSize, measuringBreaker)
            guard += 1
            check(guard < 20) { "import did not converge" }
        }
        repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        repository.finishPagination(documentId, style, viewportSize, measuringBreaker)

        assertTrue(
            pageLayoutDao.stored.isNotEmpty(),
            "once the import has actually finished and pagination has caught up, a real measurement " +
                "must be persisted as usual",
        )
    }

    /**
     * A stored layout [DocumentRepositoryImpl.appendMeasuredPageStarts] finds but cannot extend must be
     * deleted, not left stale for [DocumentRepositoryImpl.restorePageWindows] to trip over later. The
     * row upserted below stands in for one left over from an app version before partial prefix layouts;
     * after deleting it the repository measures the current prefix and stores a version-matched partial
     * replacement so the following batch can append without rebuilding that prefix again.
     */
    @Test
    fun importNextSectionsReplacesAnUnextendableStaleLayoutWithTheCurrentPrefix() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///stale-append.epub",
            displayName = "stale-append.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount = 30)
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val pageLayoutDao = FakePageLayoutDao()
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 19) / 20) { page -> page * 20 } }

        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)

        pageLayoutDao.upsertPageLayout(
            PageLayoutEntity(
                documentId = documentId.value,
                fontSizeSp = style.layoutKey().fontSizeSp,
                lineHeightMultiplier = style.layoutKey().lineHeightMultiplier,
                fontFamilyName = style.layoutKey().fontFamilyName.orEmpty(),
                viewportWidthPx = viewportSize.widthPx,
                viewportHeightPx = viewportSize.heightPx,
                characterCount = 1L,
                pageStartsBlob = null,
                writtenAtEpochMillis = 0L,
            ),
        )

        repository.importNextSections(documentId, count = 1, style, viewportSize, measuringBreaker)

        val replacement = pageLayoutDao.stored.single { it.documentId == documentId.value }
        assertTrue(replacement.isPartial, "an incomplete import must replace the stale row with a partial prefix")
        assertTrue(replacement.characterCount > 1L, "the stale character-count version must not survive replacement")
        assertTrue(replacement.pageStartsBlob != null, "the replacement must contain measured prefix page starts")
    }

    /**
     * Step 10: section-relative block storage. Every section's stored `blocksJson` — from both call
     * sites that write it, [DocumentRepositoryImpl.persistParsedDocument] (phase 0's cover and chapter
     * 1) and [DocumentRepositoryImpl.importNextSections] (chapter 2, the one with the bold span, at a
     * non-zero absolute offset) — must shift back to the original absolute block *and* span ranges once
     * re-absolutized by adding the section's own start back on; both call sites rebase before writing,
     * so both have to be exercised here. `bytes=null` drives phase 0 then the progressive-import loop so
     * both call sites actually run.
     */
    @Test
    fun everySectionsBlocksRoundTripToTheirOriginalAbsoluteRangesAcrossPhase0AndProgressiveImport() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///relative-blocks-progressive.epub",
            displayName = "relative-blocks-progressive.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = epubBytesWithBoldSpanInChapterTwo()
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle()
        val viewportSize = ViewportSize(widthPx = 320, heightPx = 560)

        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        var guard = 0
        while (!repository.isImportComplete(documentId)) {
            repository.importNextSections(documentId, count = 1, style, viewportSize, pageBreaker = null)
            guard += 1
            check(guard < 20) { "import did not converge" }
        }

        val parsedDirectly = EpubDocumentParser().parse(documentId, location.displayName, epubBytes)
        val json = Json
        val storedByIndex = searchIndexDao.entries.associateBy { it.sectionIndex }

        parsedDirectly.sections.forEach { section ->
            val stored = storedByIndex.getValue(section.index)
            val storedBlocks = json.decodeFromString<List<ReaderBlock>>(stored.blocksJson)
            val reAbsolutized = storedBlocks.map { block ->
                block.copy(
                    range = TextRange(block.range.start + section.range.start, block.range.end + section.range.start),
                    spans = block.spans.map { span ->
                        span.copy(
                            range = TextRange(
                                span.range.start + section.range.start,
                                span.range.end + section.range.start,
                            ),
                        )
                    },
                )
            }
            val originalBlocks = parsedDirectly.blocks.blocksIn(section.range.start, section.range.end)
            assertEquals(
                originalBlocks,
                reAbsolutized,
                "section ${section.index}'s stored blocks (and their spans) must shift back to the original absolute ranges",
            )
        }
        assertTrue(
            parsedDirectly.blocks.any { it.spans.isNotEmpty() },
            "the sample EPUB must actually carry a span, or this test proves nothing about span rebasing",
        )
    }

    /**
     * Step 11: progressive pagination for a type that has never been measured. Opening with no stored
     * layout must measure the resumed section first (via `anchorOffset`), plus only the bounded local
     * neighborhood needed for the first backward turn and initial forward-page budget, not the whole book — the
     * 6.4s/13.0s-measured cost [DocumentRepositoryImpl.getPageWindows]' own doc describes.
     * Every fixture section's text is distinct ("aaaa...", "bbbb...", ...), so the breaker's own
     * argument (`countingBreaker` below) proves which section it was called for, not just how many
     * times.
     */
    @Test
    fun openingWithNoStoredLayoutMeasuresOnlyTheResumedSectionBeforePublishing() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val location = DocumentLocation(
            sourceUri = "file:///resume-anchor.txt",
            displayName = "resume-anchor.txt",
            mimeType = "text/plain",
        )
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        val sections = fiveTxtSectionsWithBlocks(location.sourceUri)
        searchIndexDao.upsertSearchIndex(sections)
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuredTexts = mutableListOf<String>()
        val countingBreaker = ReaderPageBreaker { measured, _ ->
            measuredTexts += measured
            IntArray((measured.length + 9) / 10) { page -> page * 10 }
        }

        val pages = repository.getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
            pageBreaker = countingBreaker,
            anchorOffset = sections[2].startOffset,
        )

        assertEquals(3, measuredTexts.size, "opening with no stored layout must measure only the resumed section plus its bounded local neighborhood")
        assertTrue(measuredTexts.contains(sections[2].text), "the resumed section must still be measured first")
        assertTrue(
            measuredTexts.all { it == sections[1].text || it == sections[2].text || it == sections[3].text },
            "only the bounded local neighborhood may be pulled in here",
        )
        assertTrue(pages.isNotEmpty(), "the resumed section's own pages must already be there to show")
        assertFalse(
            repository.isPaginationComplete(documentId),
            "pagination must not be reported complete until every section has been measured",
        )
    }

    /**
     * Measuring one section at a time via [DocumentRepositoryImpl.continuePagination] (driven to
     * completion the same way a background continuation loop would) must produce byte-identical pages
     * to the reference answer — every section laid out in one pass via a direct
     * [TextPageLayoutEngine.paginate] call, the way `getPageWindows` used to before incremental
     * pagination existed — for a book that has a cover section.
     */
    @Test
    fun incrementalPaginationIsByteIdenticalToWholeDocumentMeasurementWithACoverSection() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val location = DocumentLocation(
            sourceUri = "file:///incremental-vs-whole.epub",
            displayName = "incremental-vs-whole.epub",
            mimeType = "application/epub+zip",
        )
        val chapterCount = 4
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount)
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        newRepository().importDocument(
            source = DocumentImportSource(location = location, bytes = epubBytes),
            importedAtEpochMillis = 1_000,
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + 29) / 30) { page -> page * 30 }
        }

        val referenceRepository = newRepository()
        val document = referenceRepository.getReaderDocument(documentId)!!
        referenceRepository.warmSectionBlocks(documentId, (0..chapterCount).toSet())
        val wholeDocumentPages = TextPageLayoutEngine().paginate(
            document = document,
            style = style,
            viewportSize = viewportSize,
            pageBreaker = measuringBreaker,
        )
        assertTrue(wholeDocumentPages.isNotEmpty())

        val incrementalRepository = newRepository()
        incrementalRepository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        val incrementalPages = incrementalRepository.finishPagination(documentId, style, viewportSize, measuringBreaker)

        assertEquals(
            wholeDocumentPages,
            incrementalPages,
            "measuring one section at a time must produce byte-identical pages to measuring the whole book at once",
        )
    }

    /**
     * [DocumentRepositoryImpl.restorePageWindows]' strictly-ascending check must discard a stored row
     * whose page starts walk backwards partway through — even though its `characterCount` still matches
     * and it decodes cleanly — and measure fresh instead. The row upserted below (`longArrayOf(0L, 10L,
     * 20L, 10L, 20L)`) is what a writer bug that appended the same section twice would leave behind.
     */
    @Test
    fun storedLayoutWhosePageStartsDoNotAscendIsDiscardedAndMeasuredAgain() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val location = DocumentLocation(
            sourceUri = "file:///corrupt-page-starts.txt",
            displayName = "corrupt-page-starts.txt",
            mimeType = "text/plain",
        )
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        searchIndexDao.upsertSearchIndex(fiveTxtSectionsWithBlocks(location.sourceUri))
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 9) / 10) { page -> page * 10 } }
        val documentCharacterCount = checkNotNull(repository.getReaderDocument(documentId)?.characterCount)

        pageLayoutDao.upsertPageLayout(
            PageLayoutEntity(
                documentId = documentId.value,
                fontSizeSp = style.fontSizeSp,
                lineHeightMultiplier = style.lineHeightMultiplier,
                fontFamilyName = "",
                viewportWidthPx = viewportSize.widthPx,
                viewportHeightPx = viewportSize.heightPx,
                characterCount = documentCharacterCount,
                pageStartsBlob = encodePageStartsBlob(longArrayOf(0L, 10L, 20L, 10L, 20L)),
                writtenAtEpochMillis = 2_000,
            ),
        )

        val pages = repository.finishPagination(documentId, style, viewportSize, measuringBreaker)

        assertEquals(
            pages.mapNotNull { page -> page.textRange?.start },
            pages.mapNotNull { page -> page.textRange?.start }.sorted(),
            "a discarded row must be replaced by a fresh measurement, whose pages ascend",
        )
        assertNotEquals(
            5,
            pages.size,
            "the corrupt row's own page count must not survive into what the reader is given",
        )
    }

    /**
     * [DocumentRepositoryImpl.paginationContinuationLock]'s reason to exist: a style change starts one
     * continuation pass from `updateStyle` and another from the pane's first breaker report for the new
     * style, so two of them genuinely run at once (see `ReaderViewModel.refreshPaginationCompleteness`).
     * Without the lock, both used to read the same `lowPosition`, measure the same section, and append
     * it twice, leaving a finished pass holding — and storing — up to twice the book's pages. Four
     * concurrent drivers of [DocumentRepositoryImpl.continuePagination] below must still measure each
     * section exactly once, matching a whole-document reference pass.
     */
    @Test
    fun overlappingContinuationPassesMeasureEachSectionExactlyOnce() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val location = DocumentLocation(
            sourceUri = "file:///concurrent-continuation.txt",
            displayName = "concurrent-continuation.txt",
            mimeType = "text/plain",
        )
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        searchIndexDao.upsertSearchIndex(fiveTxtSectionsWithBlocks(location.sourceUri))
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 9) / 10) { page -> page * 10 } }

        val wholeDocumentPages = repository.getPageWindows(
            documentId = documentId,
            style = ReaderStyle(fontSizeSp = 20.5f),
            viewportSize = viewportSize,
            pageBreaker = measuringBreaker,
        ).let { firstSectionOnly ->
            check(firstSectionOnly.isNotEmpty())
            repository.finishPagination(documentId, ReaderStyle(fontSizeSp = 20.5f), viewportSize, measuringBreaker)
        }

        repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        coroutineScope {
            List(4) {
                launch(Dispatchers.Default) {
                    var guard = 0
                    while (!repository.isPaginationComplete(documentId)) {
                        repository.continuePagination(documentId, style, viewportSize, measuringBreaker)
                        guard += 1
                        check(guard < 50) { "pagination did not converge" }
                    }
                }
            }.joinAll()
        }
        val concurrentlyFinishedPages = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)

        assertEquals(
            wholeDocumentPages.map { page -> page.textRange },
            concurrentlyFinishedPages.map { page -> page.textRange },
            "continuation passes running at once must still measure each section exactly once",
        )
    }

    /**
     * AGENTS.md's "`pageIndex.total` never shrinks while a document stays open" invariant, at the
     * repository layer: once the first section is measured, `pageIndex.total` must never read 0, and as
     * [DocumentRepositoryImpl.continuePagination] measures further sections it must never shrink either.
     */
    @Test
    fun paginationTotalNeverShrinksAndNeverReadsZeroOnceTheFirstSectionIsMeasured() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val location = DocumentLocation(
            sourceUri = "file:///total-monotonic.txt",
            displayName = "total-monotonic.txt",
            mimeType = "text/plain",
        )
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        searchIndexDao.upsertSearchIndex(fiveTxtSectionsWithBlocks(location.sourceUri))
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 9) / 10) { page -> page * 10 } }

        val firstPages = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        assertTrue(firstPages.isNotEmpty(), "the resumed section must already publish at least one page")
        var previousTotal = firstPages.first().pageIndex.total
        assertTrue(previousTotal > 0, "total must never read 0 once the first section is measured")

        var guard = 0
        while (!repository.isPaginationComplete(documentId)) {
            val progress = repository.continuePagination(documentId, style, viewportSize, measuringBreaker)
            if (progress.sectionsMeasured > 0) {
                val total = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker).first().pageIndex.total
                assertTrue(total >= previousTotal, "total must never shrink as more sections are measured")
                assertTrue(total > 0, "total must never read 0 once the first section is measured")
                previousTotal = total
            }
            guard += 1
            check(guard < 50) { "pagination did not converge" }
        }
    }

    /**
     * The page shown at open must still exist, with its boundaries unchanged, once the rest of the book
     * is measured progressively. Resuming into the middle section
     * (`anchorOffset = sections[2].startOffset`) exercises both directions
     * [DocumentRepositoryImpl.continuePagination] extends in: sections 0-1 get measured backward,
     * sections 3-4 forward, around this one already-shown page.
     */
    @Test
    fun pageTextRangeIsUnchangedAfterFurtherSectionsAreMeasuredProgressively() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val location = DocumentLocation(
            sourceUri = "file:///progressive-pagination-stable.txt",
            displayName = "progressive-pagination-stable.txt",
            mimeType = "text/plain",
        )
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        val sections = fiveTxtSectionsWithBlocks(location.sourceUri)
        searchIndexDao.upsertSearchIndex(sections)
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 9) / 10) { page -> page * 10 } }

        val firstPages = repository.getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
            pageBreaker = measuringBreaker,
            anchorOffset = sections[2].startOffset,
        )
        val resumedPageRange = firstPages.first().textRange

        var guard = 0
        while (!repository.isPaginationComplete(documentId)) {
            repository.continuePagination(documentId, style, viewportSize, measuringBreaker)
            guard += 1
            check(guard < 50) { "pagination did not converge" }
        }
        val finalPages = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        val resumedPageInFinal = finalPages.firstOrNull { it.textRange == resumedPageRange }

        assertEquals(
            resumedPageRange,
            resumedPageInFinal?.textRange,
            "the page shown at open must still exist, with its boundaries unchanged, once the rest of the book is measured",
        )
    }

    @Test
    fun freshPaginationMeasuresImmediateNextNeighborWhenAnchorSectionHasOnlyOnePage() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val location = DocumentLocation(
            sourceUri = "file:///fresh-neighbor.txt",
            displayName = "fresh-neighbor.txt",
            mimeType = "text/plain",
        )
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        searchIndexDao.upsertSearchIndex(fiveTxtSectionsWithBlocks(location.sourceUri))

        val pages = repository.getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = ReaderPageBreaker { _, _ -> intArrayOf(0) },
        )

        assertTrue(pages.size >= 5, "fresh pagination must prepare at least four pages after the anchor page")
    }

    /**
     * [DocumentRepositoryImpl.loadReaderDocument]'s EPUB repair path, gated by `parserVersion` alone
     * here (the `searchIndexDao` fixture's non-blank `navigationJson` keeps the other gate
     * `loadReaderDocument` also checks from firing, so only the parserVersion gate is under test).
     * `fileSource.copyCount` reaching exactly 1 and `readCount` staying 0 is what proves the repair
     * takes the phased import route, streaming from one copy of the file instead of reading the whole
     * book into memory — the absence of a whole-file read is itself the thing worth asserting. Whatever
     * the repair leaves for the background must finish the way a fresh import's remainder does, ending
     * with the whole book at the current version — not only the chapter shown first — and a second open
     * (a fresh [DocumentRepositoryImpl] instance, so only what the repair actually wrote, not an
     * in-memory document cache, can explain it) must not repair again.
     */
    @Test
    fun bookStoredAtAnOlderParserVersionIsRepairedExactlyOnceOnNextOpen() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///stale-parser-version.epub",
            displayName = "stale-parser-version.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleEpubBytesWithCover()
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val documentDao = FakeDocumentDao().apply {
            upsertDocument(
                DocumentEntity(
                    id = location.sourceUri,
                    name = location.displayName,
                    sourceUri = location.sourceUri,
                    format = DocumentFormat.EPUB.name,
                    mimeType = location.mimeType,
                    addedAtEpochMillis = 1_000,
                    importCompletedAtEpochMillis = 1_000,
                ),
            )
        }
        val searchIndexDao = FakeDocumentSearchIndexDao().apply {
            upsertSearchIndex(
                listOf(
                    SearchIndexEntity(
                        documentId = location.sourceUri,
                        sectionIndex = 0,
                        text = "stale text",
                        startOffset = 0,
                        endOffset = 10,
                        navigationJson = "stands-in-for-non-blank-navigation",
                        parserVersion = CurrentReaderParserVersion - 1,
                    ),
                ),
            )
        }
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)

        val repairingRepository = newRepository()
        val repaired = repairingRepository.getReaderDocument(documentId)
        assertEquals(1, fileSource.copyCount, "a stale parserVersion must trigger exactly one repair")
        assertEquals(0, fileSource.readCount, "a repair must not read the whole book into memory before the reader can draw")
        assertTrue(repaired?.sections?.isNotEmpty() == true, "the repair must actually re-parse the book, not just bump the version")
        assertTrue(
            searchIndexDao.entries.all { it.parserVersion == CurrentReaderParserVersion },
            "every section the repair wrote must be stored at the current parser version",
        )

        var guard = 0
        while (!repairingRepository.isImportComplete(documentId)) {
            repairingRepository.importNextSections(
                documentId = documentId,
                count = 4,
                style = ReaderStyle(),
                viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
                pageBreaker = null,
            )
            guard += 1
            check(guard < 50) { "repair import did not converge" }
        }
        assertTrue(
            searchIndexDao.entries.isNotEmpty() &&
                searchIndexDao.entries.all { it.parserVersion == CurrentReaderParserVersion },
            "the finished repair must leave every stored section at the current parser version",
        )

        newRepository().getReaderDocument(documentId)
        assertEquals(1, fileSource.copyCount, "a second open must not repair again once the stored parserVersion is current")
    }

    /**
     * A real breaker that arrives while progressive import is still running must finish the existing
     * prefix session once and append every later section once. The promoted row must then restore the
     * same page ranges as an independent whole-document measurement; counting by section text makes
     * either remeasuring the prefix or falling back to a completion-time full pass fail this test.
     */
    @Test
    fun progressivePartialLayoutMeasuresEachSectionOnceAndRestoresFinalPages() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///partial-layout-once.epub",
            displayName = "partial-layout-once.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount = 30)
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val progressiveLayouts = FakePageLayoutDao()
        fun repository(pageLayoutDao: PageLayoutDao) = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measurementsByText = mutableMapOf<String, Int>()
        val countingBreaker = ReaderPageBreaker { measured, _ ->
            measurementsByText[measured] = measurementsByText.getOrElse(measured) { 0 } + 1
            IntArray((measured.length + 19) / 20) { page -> page * 20 }
        }
        val progressiveRepository = repository(progressiveLayouts)

        progressiveRepository.importDocument(
            DocumentImportSource(location, bytes = null),
            importedAtEpochMillis = 1_000,
        )
        progressiveRepository.getPageWindows(documentId, style, viewportSize, countingBreaker)
        var guard = 0
        while (!progressiveRepository.isImportComplete(documentId)) {
            progressiveRepository.importNextSections(
                documentId = documentId,
                count = 3,
                style = style,
                viewportSize = viewportSize,
                pageBreaker = countingBreaker,
            )
            guard += 1
            check(guard < 30) { "progressive import did not converge" }
        }

        assertTrue(measurementsByText.isNotEmpty())
        assertTrue(
            measurementsByText.values.all { count -> count == 1 },
            "every content section must be measured exactly once across prefix completion and append: $measurementsByText",
        )
        val promoted = progressiveLayouts.stored.single { layout -> layout.documentId == documentId.value }
        assertFalse(promoted.isPartial, "the final matching prefix layout must be promoted after import completion")

        val restored = repository(progressiveLayouts).getPageWindows(documentId, style, viewportSize)
        val freshLayouts = FakePageLayoutDao()
        val freshRepository = repository(freshLayouts)
        val referenceBreaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + 19) / 20) { page -> page * 20 }
        }
        freshRepository.getPageWindows(documentId, style, viewportSize, referenceBreaker)
        val freshlyMeasured = freshRepository.finishPagination(documentId, style, viewportSize, referenceBreaker)

        assertEquals(
            freshlyMeasured.map { page -> page.textRange },
            restored.map { page -> page.textRange },
            "promoted progressive starts must restore exactly the whole-document measurement",
        )
    }

    /**
     * A document interrupted before schema version 9 has null accumulators even though prefix sections
     * already exist. Its first resumed batch must reconstruct that prefix once instead of starting from
     * zero, otherwise completion publishes counts for only the post-upgrade suffix.
     */
    @Test
    fun legacyIncompleteEpubRebuildsPrefixAccumulatorsBeforeAppending() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///legacy-partial-counts.epub",
            displayName = "legacy-partial-counts.epub",
            mimeType = "application/epub+zip",
        )
        val fileSource = FakeDocumentFileSource(location, sampleMultiChapterEpubBytesWithCover(chapterCount = 30))
        val documentDao = FakeDocumentDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)

        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        val prefixCharacterCount = documentDao.saved?.characterCount ?: error("phase-0 count missing")
        documentDao.saved = documentDao.saved?.copy(
            characterCount = null,
            wordCount = null,
            embeddedFontHrefsJson = null,
        )
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
            check(guard < 30) { "legacy progressive import did not converge" }
        }

        val completedDocument = repository.getReaderDocument(documentId) ?: error("completed document missing")
        val metadata = repository.getDocument(documentId) ?: error("completed metadata missing")
        assertTrue((metadata.characterCount ?: 0L) > prefixCharacterCount)
        assertEquals(completedDocument.characterCount, metadata.characterCount)
        assertEquals(completedDocument.wordCount, metadata.wordCount)
    }

    /**
     * Drives the exact interleaving ReaderViewModel puts a progressively-imported EPUB through in one
     * reading session: openDocument's own two calls (no breaker yet, then the pane's first real report),
     * continueImportIfIncomplete reloading pages after every batch, then continuePaginationIfIncomplete
     * reloading pages after every section it finishes measuring. The page count the reader was actually
     * shown during that live session (whatever the last getPageWindows call returned) must equal what a
     * brand-new repository instance — the same stored rows, none of the in-memory caches — restores for
     * the same style and viewport once the session ends. A device that shows one number live and a
     * different, larger one after a force-stop and reopen is this test failing.
     */
    @Test
    fun liveSessionPageCountMatchesWhatALaterRestoreProduces() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///live-vs-restored.epub",
            displayName = "live-vs-restored.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount = 8)
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 19) / 20) { page -> page * 20 } }

        val repository = newRepository()
        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)

        repository.getPageWindows(documentId, style, viewportSize = null, pageBreaker = null)
        var livePages = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)

        var guard = 0
        while (!repository.isImportComplete(documentId)) {
            val progress = repository.importNextSections(documentId, count = 2, style, viewportSize, measuringBreaker)
            if (progress.sectionsImported > 0) {
                livePages = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
            }
            guard += 1
            check(guard < 20) { "import did not converge" }
        }

        guard = 0
        while (!repository.isPaginationComplete(documentId)) {
            val progress = repository.continuePagination(documentId, style, viewportSize, measuringBreaker)
            if (progress.sectionsMeasured > 0) {
                livePages = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
            }
            guard += 1
            check(guard < 200) { "pagination did not converge" }
        }

        val restoredPages = newRepository().getPageWindows(documentId, style, viewportSize, measuringBreaker)

        assertEquals(
            livePages.map { it.textRange },
            restoredPages.map { it.textRange },
            "the exact pages the reader was shown during the live session must equal what a later restore produces",
        )
    }

    @Test
    fun restoredPageWindowCacheStaysBoundedWhileReadingManyPages() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val location = DocumentLocation(
            sourceUri = "file:///restored-built-cache.txt",
            displayName = "restored-built-cache.txt",
            mimeType = "text/plain",
        )
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        searchIndexDao.upsertSearchIndex(manyTxtSectionsWithBlocks(location.sourceUri, 10))
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray(measured.length) { it } }

        newRepository().getPageWindows(documentId, style, viewportSize, measuringBreaker)
        val restoredPages = newRepository().getPageWindows(documentId, style, viewportSize, measuringBreaker)
        repeat(minOf(100, restoredPages.size)) { index -> restoredPages[index] }

        assertTrue(restoredPages is RestoredPageWindows)
        assertTrue(restoredPages.builtCount <= 16, "restored page materialization cache must stay bounded at 16 pages")
    }

    @Test
    fun restoredPageWindowCacheHitRefreshesRecencyBeforeEviction() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        val location = DocumentLocation(
            sourceUri = "file:///restored-built-lru.txt",
            displayName = "restored-built-lru.txt",
            mimeType = "text/plain",
        )
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        searchIndexDao.upsertSearchIndex(manyTxtSectionsWithBlocks(location.sourceUri, 10))
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray(measured.length) { it } }

        newRepository().getPageWindows(documentId, style, viewportSize, measuringBreaker)
        val restoredPages = newRepository().getPageWindows(documentId, style, viewportSize, measuringBreaker) as RestoredPageWindows

        val original = restoredPages[1]
        repeat(15) { offset -> restoredPages[offset + 2] }
        restoredPages[1]
        restoredPages[17]

        assertSame(original, restoredPages[1], "a cache hit must refresh recency so the touched page survives the next eviction")
        assertTrue(restoredPages.builtCount <= 16)
    }

    @Test
    fun continuePaginationMeasuresABoundedBatchAndReportsActualCount() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val location = DocumentLocation(
            sourceUri = "file:///pagination-batch.txt",
            displayName = "pagination-batch.txt",
            mimeType = "text/plain",
        )
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        documentDao.upsertDocument(
            DocumentEntity(
                id = location.sourceUri,
                name = location.displayName,
                sourceUri = location.sourceUri,
                format = DocumentFormat.TXT.name,
                mimeType = location.mimeType,
                sizeBytes = 0L,
                addedAtEpochMillis = 1_000,
                importCompletedAtEpochMillis = 1_000,
            ),
        )
        val sections = manyTxtSectionsWithBlocks(location.sourceUri, 12)
        searchIndexDao.upsertSearchIndex(sections)
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val onePagePerSection = ReaderPageBreaker { _, _ -> intArrayOf(0) }

        repository.getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
            pageBreaker = onePagePerSection,
            anchorOffset = sections[6].startOffset,
        )
        val progress = repository.continuePagination(documentId, style, viewportSize, onePagePerSection)

        assertTrue(progress.sectionsMeasured in 2..8, "continuePagination must report the actual bounded batch size")
    }

    /**
     * ReaderViewModel.refreshPaginationCompleteness starts continuePaginationIfIncomplete whenever
     * isPaginationComplete is false — it does not wait for isImportComplete first (see its own doc,
     * which only speaks to updatePageBreaker's dedupe, not to import). A pane that reports its breaker
     * a second time (a legitimate, undeduplicated report — the dedupe key is measuredSizePx, which a
     * live layout pass can easily perturb by a pixel) while continueImportIfIncomplete is still running
     * would therefore start a *second*, concurrent driver of the same pagination/import machinery:
     * continuePagination extending [PaginationSession] section by section while importNextSections is
     * still calling invalidateDocumentCache (which nulls out that same paginationSession) and
     * appendMeasuredPageStarts on every batch — and neither loop holds a lock against the other. This
     * regression test drives exactly that interleaving, and checks the result against an independent
     * whole-document reference pass: whatever order the two loops interleave in, what ends up stored
     * must be one measurement of the book, not a duplicate of any section and not a fraction of it.
     * The pagination loop below can only retire once the import has stopped moving, which is the same
     * thing isPaginationComplete now guarantees for the ViewModel's own driver — a loop allowed to
     * retire mid-import leaves the last-created session unwalked and the total pinned to one section.
     *
     * `drainPagination` below stands in for `continuePaginationIfIncomplete`'s own loop: a batch that
     * just nulled the session leaves it with nothing to measure for a moment, so it yields rather than
     * spinning — the ViewModel gets that for free by being cancelled and restarted per batch (see
     * `continueImportIfIncomplete`). It is called once more after the two concurrent launches, standing
     * in for the import's own last act of restarting the continuation once the import has finished (see
     * `ReaderViewModel.continueImportIfIncomplete`'s `refreshPaginationCompleteness(isImportComplete =
     * true)` on the completing batch) — because the concurrent driver is allowed to retire the instant
     * both flags read true, which can be the moment before the import's final reload creates one more
     * single-section session that nothing else would ever walk. The ground-truth reference pass requires
     * `warmSectionBlocks` first: `ReaderDocument.blocks` is a `LazyFlattenedBlocks` over
     * [SectionBlocksCache], which answers empty for any section nothing has prewarmed yet, so an
     * un-warmed reference would silently find no cover block at all and disagree with the real code's
     * own always-prewarmed paths.
     */
    @Test
    fun continuePaginationRacingImportNextSectionsStillStoresTheTruePageCount() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///import-vs-continuation-race.epub",
            displayName = "import-vs-continuation-race.epub",
            mimeType = "application/epub+zip",
        )
        val chapterCount = 10
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount)
        val fileSource = FakeDocumentFileSource(location, epubBytes)
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val pageLayoutDao = FakePageLayoutDao()
        fun newRepository() = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
            pageLayoutDao = pageLayoutDao,
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 19) / 20) { page -> page * 20 } }

        val repository = newRepository()
        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)

        suspend fun drainPagination() {
            var guard = 0
            while (!repository.isPaginationComplete(documentId)) {
                val progress = repository.continuePagination(documentId, style, viewportSize, measuringBreaker)
                if (progress.sectionsMeasured > 0) {
                    repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
                } else {
                    yield()
                }
                guard += 1
                check(guard < 2000) { "pagination did not converge" }
            }
        }

        coroutineScope {
            launch(Dispatchers.Default) {
                var guard = 0
                while (!repository.isImportComplete(documentId)) {
                    val progress = repository.importNextSections(documentId, count = 2, style, viewportSize, measuringBreaker)
                    if (progress.sectionsImported > 0) {
                        repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
                    }
                    guard += 1
                    check(guard < 100) { "import did not converge" }
                }
            }
            launch(Dispatchers.Default) { drainPagination() }
        }
        drainPagination()
        check(repository.isImportComplete(documentId))
        check(repository.isPaginationComplete(documentId))

        val referenceRepository = newRepository()
        val referenceDocument = referenceRepository.getReaderDocument(documentId)!!
        referenceRepository.warmSectionBlocks(documentId, referenceDocument.sections.map { it.index }.toSet())
        val referencePages = TextPageLayoutEngine().paginate(
            document = referenceDocument,
            style = style,
            viewportSize = viewportSize,
            pageBreaker = measuringBreaker,
        )

        val restoredPages = newRepository().getPageWindows(documentId, style, viewportSize, measuringBreaker)

        assertEquals(
            referencePages.size,
            restoredPages.size,
            "a stored page layout must hold exactly one measurement of the book, not a duplicate of any section",
        )
    }

    /**
     * isPaginationComplete answered from the pagination session alone said "complete" for every moment
     * a running import had just nulled that session (see invalidateDocumentCache), and every caller
     * asks it to decide whether to keep the continuation running — so that answer retired the only
     * thing that grows the page count while the book was still being parsed underneath it, leaving the
     * total pinned to whatever single section the last reload measured (see
     * ReaderViewModel.refreshPaginationCompleteness, which starts nothing when this reads true). This
     * test imports only phase 0 — far enough to open and no further, exactly the window in which no
     * pagination session exists yet — and checks that [DocumentRepositoryImpl.isPaginationComplete]
     * still reads false.
     */
    @Test
    fun estimateOnlyOpenDoesNotReportPaginationCompleteUntilARealMeasurementRuns() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///estimated-short-book.txt",
            displayName = "estimated-short-book.txt",
            mimeType = "text/plain",
        )
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
        )
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val onePagePerSection = ReaderPageBreaker { _, _ -> intArrayOf(0) }

        repository.importDocument(
            DocumentImportSource(location, bytes = "short estimated book".encodeToByteArray()),
            importedAtEpochMillis = 1_000,
        )
        repository.getPageWindows(documentId, style, viewportSize, pageBreaker = null)
        assertFalse(repository.isPaginationComplete(documentId), "an estimate-only open must not retire background pagination")

        repository.getPageWindows(documentId, style, viewportSize, onePagePerSection)
        assertTrue(repository.isPaginationComplete(documentId), "a real measurement for the same short book must satisfy pagination")
    }

    @Test
    fun paginationIsNeverReportedCompleteWhileTheImportIsStillUnfinished() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///pagination-completeness-during-import.epub",
            displayName = "pagination-completeness-during-import.epub",
            mimeType = "application/epub+zip",
        )
        val fileSource = FakeDocumentFileSource(location, sampleMultiChapterEpubBytesWithCover(chapterCount = 30))
        val repository = DocumentRepositoryImpl(
            documentDao = FakeDocumentDao(),
            searchIndexDao = FakeDocumentSearchIndexDao(),
            pageLayoutDao = FakePageLayoutDao(),
            formatDetector = DocumentFormatDetector(),
            txtDocumentParser = TxtDocumentParser(),
            epubDocumentParser = EpubDocumentParser(),
            pdfDocumentParser = PdfDocumentParser(),
            comicBookDocumentParser = ComicBookDocumentParser(),
            imageDocumentParser = ImageDocumentParser(),
            textPageLayoutEngine = TextPageLayoutEngine(),
            documentFileSource = fileSource,
        )
        val documentId = DocumentId(location.sourceUri)

        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        check(!repository.isImportComplete(documentId)) { "this test needs a document that is still importing" }

        assertFalse(
            repository.isPaginationComplete(documentId),
            "pagination cannot be complete for a book whose sections are still being parsed",
        )
    }
}

/**
 * A [DocumentFileSource] that always hands back the same [bytes], counting how many times each method
 * was actually called — the counts most tests here assert on to prove a whole-file read was, or was
 * not, paid for.
 *
 * @property expectedLocation The location every call must be made with; a mismatch fails the test via
 *   `assertEquals` inside [readBytes]/[copyTo] rather than answering wrong data silently.
 * @property bytes The bytes to hand back from [readBytes] and to write in [copyTo].
 */
private class FakeDocumentFileSource(
    private val expectedLocation: DocumentLocation,
    private val bytes: ByteArray,
) : DocumentFileSource {
    /** How many times [readBytes] has been called. */
    var readCount: Int = 0

    /** How many times [copyTo] has been called. */
    var copyCount: Int = 0

    /**
     * Unique per fake instance so one test's cached cover file can never be left over for the next —
     * a real device's covers directory is one shared place, but a test's should not be.
     */
    private val privateDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "tedd-reader-test-${Random.nextLong().toString(16)}"

    override suspend fun readBytes(location: DocumentLocation): ByteArray {
        assertEquals(expectedLocation, location)
        readCount += 1
        return bytes
    }

    override suspend fun copyTo(location: DocumentLocation, destination: Path) {
        assertEquals(expectedLocation, location)
        copyCount += 1
        FileSystem.SYSTEM.sink(destination).buffer().use { sink ->
            sink.write(bytes)
        }
    }

    override fun appPrivateDirectory(): Path = privateDirectory
}

/**
 * A [ComicBookDocumentParser] that counts how many times an archive was opened, so a test can prove the
 * CBZ scratch/open-archive cache builds the ZIP index (list + natural sort) exactly once per document
 * and reuses it across every later page/cover request.
 */
private class CountingComicBookDocumentParser : ComicBookDocumentParser() {
    /** How many times [openArchive] has been called — one per distinct scratch copy the cache opened. */
    var openArchiveCount: Int = 0

    override fun openArchive(path: Path): ComicArchive {
        openArchiveCount += 1
        return super.openArchive(path)
    }
}

/**
 * A [DocumentFileSource] backed by a per-location byte map, so a test switching between two CBZs gets
 * each document's own bytes while still counting total [copyTo] calls across both — the count that
 * proves switching documents copies the new one afresh rather than reusing the previous scratch.
 *
 * @property bytesByLocation Each document's bytes, keyed by its `sourceUri`.
 */
private class MultiLocationDocumentFileSource(
    private val bytesByLocation: Map<String, ByteArray>,
) : DocumentFileSource {
    /** How many times [readBytes] has been called across every location. */
    var readCount: Int = 0

    /** How many times [copyTo] has been called across every location. */
    var copyCount: Int = 0

    private val privateDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "tedd-reader-test-${Random.nextLong().toString(16)}"

    override suspend fun readBytes(location: DocumentLocation): ByteArray {
        readCount += 1
        return bytesByLocation.getValue(location.sourceUri)
    }

    override suspend fun copyTo(location: DocumentLocation, destination: Path) {
        copyCount += 1
        FileSystem.SYSTEM.sink(destination).buffer().use { sink ->
            sink.write(bytesByLocation.getValue(location.sourceUri))
        }
    }

    override fun appPrivateDirectory(): Path = privateDirectory
}

/**
 * A ZIP archive (the shape a CBZ file is) containing [entries] verbatim, in order.
 *
 * @param entries Each entry's archive path paired with its raw bytes.
 * @return The encoded ZIP archive's bytes.
 */
private fun comicZipBytes(vararg entries: Pair<String, ByteArray>): ByteArray =
    java.io.ByteArrayOutputStream().use { output ->
        java.util.zip.ZipOutputStream(output).use { zip ->
            entries.forEach { (name, entryBytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(entryBytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

/**
 * A minimal EPUB whose cover is declared purely through the manifest's `cover-image` property — no
 * dedicated cover.xhtml page — so the cover bytes exist independently of whether the reader visits it
 * as a section.
 *
 * @param coverBytes The bytes to store as the cover image.
 * @return The encoded EPUB's bytes: one cover image and one chapter.
 */
private fun sampleEpubBytesWithCover(
    coverBytes: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3),
): ByteArray = java.io.ByteArrayOutputStream().use { output ->
    java.util.zip.ZipOutputStream(output).use { zip ->
        fun entry(name: String, content: ByteArray) {
            zip.putNextEntry(java.util.zip.ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
        entry(
            "META-INF/container.xml",
            """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent().encodeToByteArray(),
        )
        entry(
            "OEBPS/content.opf",
            """
                <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Cover Test Book</dc:title>
                  </metadata>
                  <manifest>
                    <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                    <item id="chapter-1" href="chapter-1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="chapter-1"/>
                  </spine>
                </package>
            """.trimIndent().encodeToByteArray(),
        )
        entry(
            "OEBPS/chapter-1.xhtml",
            "<html><body><h2>Heading</h2><p>Body text.</p></body></html>".encodeToByteArray(),
        )
        entry("OEBPS/images/cover.jpg", coverBytes)
    }
    output.toByteArray()
}

/**
 * [chapterCount] ordinary chapters behind a manifest-declared cover, the same shape as
 * [sampleEpubBytesWithCover] but with enough sections that a lazy restore touching one of them must
 * not touch the rest — the cover always synthesizes as section 0 (see `EpubDocumentParser.parseWithCover`),
 * the chapters become sections 1..[chapterCount].
 *
 * @param chapterCount How many chapters to generate.
 * @return The encoded EPUB's bytes.
 */
private fun sampleMultiChapterEpubBytesWithCover(chapterCount: Int): ByteArray =
    java.io.ByteArrayOutputStream().use { output ->
        java.util.zip.ZipOutputStream(output).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            entry(
                "META-INF/container.xml",
                """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent().encodeToByteArray(),
            )
            val manifestItems = buildString {
                append("""<item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>""")
                for (chapter in 1..chapterCount) {
                    append("""<item id="chapter-$chapter" href="chapter-$chapter.xhtml" media-type="application/xhtml+xml"/>""")
                }
            }
            val spineItems = buildString {
                for (chapter in 1..chapterCount) {
                    append("""<itemref idref="chapter-$chapter"/>""")
                }
            }
            entry(
                "OEBPS/content.opf",
                """
                    <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Multi Chapter Cover Book</dc:title>
                      </metadata>
                      <manifest>
                        $manifestItems
                      </manifest>
                      <spine>
                        $spineItems
                      </spine>
                    </package>
                """.trimIndent().encodeToByteArray(),
            )
            for (chapter in 1..chapterCount) {
                entry(
                    "OEBPS/chapter-$chapter.xhtml",
                    "<html><body><h2>Chapter $chapter</h2><p>${"chapter $chapter text ".repeat(30)}</p></body></html>".encodeToByteArray(),
                )
            }
            entry("OEBPS/images/cover.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3))
        }
        output.toByteArray()
    }

private fun sampleMultiChapterEpubBytesWithCoverAndNavigation(chapterCount: Int): ByteArray =
    java.io.ByteArrayOutputStream().use { output ->
        java.util.zip.ZipOutputStream(output).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            entry(
                "META-INF/container.xml",
                """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent().encodeToByteArray(),
            )
            val manifestItems = buildString {
                append("""<item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>""")
                append("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
                for (chapter in 1..chapterCount) append("""<item id="chapter-$chapter" href="chapter-$chapter.xhtml" media-type="application/xhtml+xml"/>""")
            }
            val spineItems = buildString {
                for (chapter in 1..chapterCount) append("""<itemref idref="chapter-$chapter"/>""")
            }
            val navItems = buildString {
                for (chapter in 1..chapterCount) append("""<li><a href="chapter-$chapter.xhtml">Chapter $chapter</a></li>""")
            }
            entry(
                "OEBPS/content.opf",
                """
                    <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Multi Chapter Nav Book</dc:title>
                      </metadata>
                      <manifest>$manifestItems</manifest>
                      <spine>$spineItems</spine>
                    </package>
                """.trimIndent().encodeToByteArray(),
            )
            entry(
                "OEBPS/nav.xhtml",
                """
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                      <body><nav epub:type="toc"><ol>$navItems</ol></nav></body>
                    </html>
                """.trimIndent().encodeToByteArray(),
            )
            for (chapter in 1..chapterCount) {
                entry(
                    "OEBPS/chapter-$chapter.xhtml",
                    "<html><body><h2>Heading $chapter</h2><p>${"chapter $chapter text ".repeat(30)}</p></body></html>".encodeToByteArray(),
                )
            }
            entry("OEBPS/images/cover.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3))
        }
        output.toByteArray()
    }

private fun sampleTwoChapterEpubBytesWithCoverAndNavigation(): ByteArray =
    java.io.ByteArrayOutputStream().use { output ->
        java.util.zip.ZipOutputStream(output).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            entry(
                "META-INF/container.xml",
                """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent().encodeToByteArray(),
            )
            entry(
                "OEBPS/content.opf",
                """
                    <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Two Chapter Nav Book</dc:title>
                      </metadata>
                      <manifest>
                        <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="chapter-1" href="chapter-1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="chapter-2" href="chapter-2.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="chapter-1"/>
                        <itemref idref="chapter-2"/>
                      </spine>
                    </package>
                """.trimIndent().encodeToByteArray(),
            )
            entry(
                "OEBPS/nav.xhtml",
                """
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                      <body>
                        <nav epub:type="toc">
                          <ol>
                            <li><a href="chapter-1.xhtml">Start Here</a></li>
                            <li><a href="chapter-2.xhtml">Keep Going</a></li>
                          </ol>
                        </nav>
                      </body>
                    </html>
                """.trimIndent().encodeToByteArray(),
            )
            entry(
                "OEBPS/chapter-1.xhtml",
                "<html><body><h2>Chapter 1 Heading</h2><p>${"chapter one text ".repeat(30)}</p></body></html>".encodeToByteArray(),
            )
            entry(
                "OEBPS/chapter-2.xhtml",
                "<html><body><h2>Chapter 2 Heading</h2><p>${"chapter two text ".repeat(30)}</p></body></html>".encodeToByteArray(),
            )
            entry("OEBPS/images/cover.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3))
        }
        output.toByteArray()
    }

private fun sampleEpubBytesWithCoverAndNullLeadingSpine(): ByteArray =
    java.io.ByteArrayOutputStream().use { output ->
        java.util.zip.ZipOutputStream(output).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            entry(
                "META-INF/container.xml",
                """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent().encodeToByteArray(),
            )
            entry(
                "OEBPS/content.opf",
                """
                    <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Null Leading Spine Book</dc:title>
                      </metadata>
                      <manifest>
                        <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                        <item id="missing" href="missing.xhtml" media-type="application/xhtml+xml"/>
                        <item id="chapter-2" href="chapter-2.xhtml" media-type="application/xhtml+xml"/>
                        <item id="chapter-3" href="chapter-3.xhtml" media-type="application/xhtml+xml"/>
                        <item id="chapter-4" href="chapter-4.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="missing"/>
                        <itemref idref="chapter-2"/>
                        <itemref idref="chapter-3"/>
                        <itemref idref="chapter-4"/>
                      </spine>
                    </package>
                """.trimIndent().encodeToByteArray(),
            )
            for (chapter in 2..4) {
                entry(
                    "OEBPS/chapter-$chapter.xhtml",
                    "<html><body><h2>Chapter $chapter</h2><p>${"chapter $chapter text ".repeat(20)}</p></body></html>".encodeToByteArray(),
                )
            }
            entry("OEBPS/images/cover.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3))
        }
        output.toByteArray()
    }

private fun sampleEpubBytesWithImageAndShortFrontMatter(): ByteArray =
    java.io.ByteArrayOutputStream().use { output ->
        java.util.zip.ZipOutputStream(output).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            entry(
                "META-INF/container.xml",
                """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent().encodeToByteArray(),
            )
            val longChapterThree = "chapter three body ".repeat(320)
            val longChapterFour = "chapter four body ".repeat(320)
            val longChapterFive = "chapter five body ".repeat(320)
            entry(
                "OEBPS/content.opf",
                """
                    <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Front Matter Buffer Book</dc:title>
                      </metadata>
                      <manifest>
                        <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                        <item id="chapter-1" href="chapter-1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="chapter-2" href="chapter-2.xhtml" media-type="application/xhtml+xml"/>
                        <item id="chapter-3" href="chapter-3.xhtml" media-type="application/xhtml+xml"/>
                        <item id="chapter-4" href="chapter-4.xhtml" media-type="application/xhtml+xml"/>
                        <item id="chapter-5" href="chapter-5.xhtml" media-type="application/xhtml+xml"/>
                        <item id="illustration" href="images/plate.jpg" media-type="image/jpeg"/>
                      </manifest>
                      <spine>
                        <itemref idref="chapter-1"/>
                        <itemref idref="chapter-2"/>
                        <itemref idref="chapter-3"/>
                        <itemref idref="chapter-4"/>
                        <itemref idref="chapter-5"/>
                      </spine>
                    </package>
                """.trimIndent().encodeToByteArray(),
            )
            entry(
                "OEBPS/chapter-1.xhtml",
                """
                    <html><body><img src="images/plate.jpg" alt="Plate"/></body></html>
                """.trimIndent().encodeToByteArray(),
            )
            entry(
                "OEBPS/chapter-2.xhtml",
                """
                    <html><body><h2>Preface</h2><p>Short note.</p></body></html>
                """.trimIndent().encodeToByteArray(),
            )
            entry(
                "OEBPS/chapter-3.xhtml",
                "<html><body><h2>Chapter 3</h2><p>$longChapterThree</p></body></html>".encodeToByteArray(),
            )
            entry(
                "OEBPS/chapter-4.xhtml",
                "<html><body><h2>Chapter 4</h2><p>$longChapterFour</p></body></html>".encodeToByteArray(),
            )
            entry(
                "OEBPS/chapter-5.xhtml",
                "<html><body><h2>Chapter 5</h2><p>$longChapterFive</p></body></html>".encodeToByteArray(),
            )
            entry("OEBPS/images/cover.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3))
            entry("OEBPS/images/plate.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 4, 5, 6))
        }
        output.toByteArray()
    }

/**
 * Cover + two chapters, chapter two carrying a `<b>` span — enough to prove a block's spans, not just
 * its own range, round-trip through section-relative storage correctly, at a section whose absolute
 * start is not 0 (unlike the cover; see
 * [everySectionsBlocksRoundTripToTheirOriginalAbsoluteRangesAcrossPhase0AndProgressiveImport]).
 *
 * @return The encoded EPUB's bytes.
 */
private fun epubBytesWithBoldSpanInChapterTwo(): ByteArray =
    java.io.ByteArrayOutputStream().use { output ->
        java.util.zip.ZipOutputStream(output).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            entry(
                "META-INF/container.xml",
                """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent().encodeToByteArray(),
            )
            entry(
                "OEBPS/content.opf",
                """
                    <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Bold Span Book</dc:title>
                      </metadata>
                      <manifest>
                        <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                        <item id="chapter-1" href="chapter-1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="chapter-2" href="chapter-2.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="chapter-1"/>
                        <itemref idref="chapter-2"/>
                      </spine>
                    </package>
                """.trimIndent().encodeToByteArray(),
            )
            entry(
                "OEBPS/chapter-1.xhtml",
                "<html><body><h2>Chapter 1</h2><p>Plain chapter one text.</p></body></html>".encodeToByteArray(),
            )
            entry(
                "OEBPS/chapter-2.xhtml",
                "<html><body><h2>Chapter 2</h2><p>Some <b>bold</b> word inside chapter two.</p></body></html>".encodeToByteArray(),
            )
            entry("OEBPS/images/cover.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3))
        }
        output.toByteArray()
    }

/**
 * Drives [DocumentRepositoryImpl.continuePagination] to completion on the same instance that started
 * a progressive pagination pass — the same idiom the progressive-*import* tests already use with
 * `isImportComplete`/`importNextSections` — then hands back the fully-measured pages. The final
 * [DocumentRepositoryImpl.getPageWindows] call is a cache hit (continuePagination already wrote the
 * finished list into the same in-memory cache getPageWindows reads), not a re-measurement.
 *
 * @receiver The repository with an in-flight pagination session for [documentId]/[style]/[viewportSize].
 * @param documentId The document to finish paginating.
 * @param style The style the in-flight session must match.
 * @param viewportSize The viewport the in-flight session must match.
 * @param pageBreaker The real page-breaking measurement to finish measuring with.
 * @return Every page window now known for the document.
 */
private suspend fun DocumentRepositoryImpl.finishPagination(
    documentId: DocumentId,
    style: ReaderStyle,
    viewportSize: ViewportSize,
    pageBreaker: ReaderPageBreaker,
): List<PageWindow> {
    var guard = 0
    while (!isPaginationComplete(documentId)) {
        continuePagination(documentId, style, viewportSize, pageBreaker)
        guard += 1
        check(guard < 50) { "pagination did not converge" }
    }
    return getPageWindows(documentId = documentId, style = style, viewportSize = viewportSize, pageBreaker = pageBreaker)
}

/**
 * Five ordinary TXT sections written straight into the search index, each carrying one real
 * [ReaderBlockKind.PARAGRAPH] block over its own text — enough sections, with real blocksJson, to tell
 * a genuine on-demand fetch apart from an eagerly-loaded one.
 *
 * The block is stored relative to the section's own start (range 0..text.length), matching what
 * `persistParsedDocument`/`importNextSections` now actually write — a fixture standing in for "already
 * in storage" has to agree with the real writer or a decode reads the block at the wrong offset.
 *
 * @param documentId The document these sections belong to.
 * @return The five sections, ready to upsert into a [FakeDocumentSearchIndexDao].
 */
private fun fiveTxtSectionsWithBlocks(documentId: String): List<SearchIndexEntity> {
    val json = Json
    var cursor = 0L
    return (0 until 5).map { index ->
        val text = ('a' + index).toString().repeat(40)
        val range = TextRange(cursor, cursor + text.length)
        cursor = range.end + 1
        SearchIndexEntity(
            documentId = documentId,
            sectionIndex = index,
            sectionTitle = "Section $index",
            text = text,
            startOffset = range.start,
            endOffset = range.end,
            blocksJson = json.encodeToString(
                listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, text.length.toLong()))),
            ),
        )
    }
}

/**
 * Like [fiveTxtSectionsWithBlocks], but for however many sections a test needs — used to exercise
 * warming across more than one batch.
 *
 * @param documentId The document these sections belong to.
 * @param count How many sections to generate.
 * @return The generated sections, ready to upsert into a [FakeDocumentSearchIndexDao].
 */
private fun manyTxtSectionsWithBlocks(documentId: String, count: Int): List<SearchIndexEntity> {
    val json = Json
    var cursor = 0L
    return (0 until count).map { index ->
        val text = ('a' + (index % 26)).toString().repeat(40)
        val range = TextRange(cursor, cursor + text.length)
        cursor = range.end + 1
        SearchIndexEntity(
            documentId = documentId,
            sectionIndex = index,
            sectionTitle = "Section $index",
            text = text,
            startOffset = range.start,
            endOffset = range.end,
            blocksJson = json.encodeToString(
                listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, text.length.toLong()))),
            ),
        )
    }
}

/**
 * A [DocumentDao] that deliberately models only one document at a time: [saved] is a single slot, not
 * a map, so upserting a second document silently replaces the first. This is the right shape for most
 * tests here, which import exactly one book, but it also means a test that imports two books and needs
 * both to still resolve cannot use this fake — see [FakeMultiDocumentDao] for that case.
 */
private class FakeDocumentDao : DocumentDao {
    /** The one document currently "stored", or null once [deleteDocument] removes it. */
    var saved: DocumentEntity? = null

    /**
     * Awaited by [upsertDocument], but only for the specific write that stamps
     * [DocumentEntity.importCompletedAtEpochMillis] for the first time — the write
     * [DocumentRepositoryImpl.finishEpubImport]/[DocumentRepositoryImpl.finishNonProgressiveEpubImport]
     * make. Parks that write right after it becomes visible in [saved], so a test can assert what
     * [DocumentRepositoryImpl.isImportComplete] and [DocumentRepositoryImpl.getReaderDocument] answer in
     * the gap before the caller's next statement runs (see
     * [DocumentRepositoryImplTest.aReaderCaughtBetweenTheCompletionStampAndTheCacheInvalidationMustNotSeeStaleNavigation]).
     * Null (the default) means every write proceeds without pausing.
     */
    var completionStampGate: CompletableDeferred<Unit>? = null

    /**
     * Completed by [upsertDocument] the instant it reaches [completionStampGate], so a test can await
     * this instead of guessing how long the write takes to arrive at the gate.
     */
    var completionStampReached: CompletableDeferred<Unit>? = null

    override suspend fun upsertDocument(document: DocumentEntity) {
        val stampsCompletionNow = saved?.importCompletedAtEpochMillis == null && document.importCompletedAtEpochMillis != null
        saved = document
        if (stampsCompletionNow) {
            completionStampReached?.complete(Unit)
            completionStampGate?.await()
        }
    }

    override suspend fun getDocument(documentId: String): DocumentEntity? =
        saved?.takeIf { it.id == documentId }

    override fun observeRecentDocuments(): Flow<List<DocumentEntity>> =
        flowOf(listOfNotNull(saved))

    override suspend fun updateBookmarked(documentIds: List<String>, isBookmarked: Boolean) {
        if (saved?.id in documentIds) saved = saved?.copy(isBookmarked = isBookmarked)
    }

    override suspend fun updateFolder(documentIds: List<String>, folderId: String?, folderName: String?) {
        if (saved?.id in documentIds) saved = saved?.copy(folderId = folderId, folderName = folderName)
    }

    override suspend fun renameFolder(folderId: String, folderName: String) {
        if (saved?.folderId == folderId) saved = saved?.copy(folderName = folderName)
    }

    override suspend fun clearFolder(folderId: String) {
        if (saved?.folderId == folderId) saved = saved?.copy(folderId = null, folderName = null)
    }

    override suspend fun updateLastOpenedAt(documentId: String, openedAtEpochMillis: Long) {
        saved = saved?.copy(lastOpenedAtEpochMillis = openedAtEpochMillis)
    }

    override suspend fun deleteDocument(documentId: String) {
        if (saved?.id == documentId) saved = null
    }

    override suspend fun deleteDocuments(documentIds: List<String>) {
        if (saved?.id in documentIds) saved = null
    }

    override suspend fun updateCountsAndFontIndex(
        documentId: String,
        characterCount: Long,
        wordCount: Long,
        embeddedFontHrefsJson: String?,
    ) {
        if (saved?.id == documentId) {
            saved = saved?.copy(
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
        if (saved?.id == documentId) {
            saved = saved?.copy(
                characterCount = characterCount,
                wordCount = wordCount,
                importCompletedAtEpochMillis = importCompletedAtEpochMillis,
            )
            completionStampReached?.complete(Unit)
            completionStampGate?.await()
        }
    }

    override suspend fun updateEmbeddedFontHrefsJson(documentId: String, embeddedFontHrefsJson: String) {
        if (saved?.id == documentId) {
            saved = saved?.copy(embeddedFontHrefsJson = embeddedFontHrefsJson)
        }
    }
}

/**
 * Unlike [FakeDocumentDao], keeps every document ever upserted — needed only where a test imports more
 * than one book and needs both to still resolve afterwards.
 */
private class FakeMultiDocumentDao : DocumentDao {
    /** Every document upserted so far, keyed by id. */
    private val documents = mutableMapOf<String, DocumentEntity>()

    override suspend fun upsertDocument(document: DocumentEntity) {
        documents[document.id] = document
    }

    override suspend fun getDocument(documentId: String): DocumentEntity? = documents[documentId]

    override fun observeRecentDocuments(): Flow<List<DocumentEntity>> = flowOf(documents.values.toList())

    override suspend fun updateBookmarked(documentIds: List<String>, isBookmarked: Boolean) {
        documentIds.forEach { id -> documents[id]?.let { documents[id] = it.copy(isBookmarked = isBookmarked) } }
    }

    override suspend fun updateFolder(documentIds: List<String>, folderId: String?, folderName: String?) {
        documentIds.forEach { id -> documents[id]?.let { documents[id] = it.copy(folderId = folderId, folderName = folderName) } }
    }

    override suspend fun renameFolder(folderId: String, folderName: String) {
        documents.replaceAll { _, document -> if (document.folderId == folderId) document.copy(folderName = folderName) else document }
    }

    override suspend fun clearFolder(folderId: String) {
        documents.replaceAll { _, document -> if (document.folderId == folderId) document.copy(folderId = null, folderName = null) else document }
    }

    override suspend fun updateLastOpenedAt(documentId: String, openedAtEpochMillis: Long) {
        documents[documentId]?.let { documents[documentId] = it.copy(lastOpenedAtEpochMillis = openedAtEpochMillis) }
    }

    override suspend fun deleteDocument(documentId: String) {
        documents.remove(documentId)
    }

    override suspend fun deleteDocuments(documentIds: List<String>) {
        documentIds.forEach(documents::remove)
    }

    override suspend fun updateCountsAndFontIndex(
        documentId: String,
        characterCount: Long,
        wordCount: Long,
        embeddedFontHrefsJson: String?,
    ) {
        documents[documentId]?.let {
            documents[documentId] = it.copy(
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
        documents[documentId]?.let {
            documents[documentId] = it.copy(
                characterCount = characterCount,
                wordCount = wordCount,
                importCompletedAtEpochMillis = importCompletedAtEpochMillis,
            )
        }
    }

    override suspend fun updateEmbeddedFontHrefsJson(documentId: String, embeddedFontHrefsJson: String) {
        documents[documentId]?.let {
            documents[documentId] = it.copy(embeddedFontHrefsJson = embeddedFontHrefsJson)
        }
    }
}
/**
 * An in-memory [SearchIndexDao] backed by a plain list of [entries], with no per-column projection the
 * way Room's real query would apply — every override filters or maps [entries] directly, which is what
 * lets [getDocumentSectionsWithoutBlocks] and [getSectionBlocksJson] stay faithful to the real
 * split between section metadata and block JSON despite storing both in the same row.
 */
private class FakeDocumentSearchIndexDao : SearchIndexDao {
    /** Every section upserted so far, across every document — filtered by `documentId` per call. */
    val entries = mutableListOf<SearchIndexEntity>()

    /**
     * Every [getSectionBlocksJson] call's `sectionIndexes` argument, recorded verbatim in call order,
     * so a test can assert exactly which sections a fetch touched — "count fetches" alone would miss a
     * call that asked for the wrong sections.
     */
    val blocksJsonQueries = mutableListOf<List<Int>>()

    /**
     * Awaited by [getDocumentSectionsWithoutBlocks] after it has already taken its snapshot of
     * [entries], so the value it eventually returns is whatever was stored at call time even though the
     * return itself is delayed — modelling a [DocumentRepositoryImpl.getReaderDocument] load that read
     * the pre-rewrite rows but has not yet reached its own cache-publishing step when a concurrent writer
     * rewrites the document (see
     * [DocumentRepositoryImplTest.aLoadThatStraddlesAnInvalidationMustNotLeaveItsStaleSnapshotCached]).
     * Null (the default) means every call returns immediately.
     */
    var getDocumentSectionsWithoutBlocksGate: CompletableDeferred<Unit>? = null

    /** Completed by [getDocumentSectionsWithoutBlocks] the instant it reaches [getDocumentSectionsWithoutBlocksGate]. */
    var getDocumentSectionsWithoutBlocksReached: CompletableDeferred<Unit>? = null

    /**
     * Awaited by [upsertSearchIndex] before it writes its rows into [entries], modelling
     * `DocumentRepositoryImpl.persistParsedDocument`'s write window: by the time this call is made,
     * `deleteSearchIndex` has already emptied every row for the document, and none of the fresh rows
     * are visible until this gate releases (see
     * [DocumentRepositoryImplTest.aLoadRacingPersistParsedDocumentsRewriteMustNotLeaveTheCacheHoldingTheTornSnapshot]).
     * Null (the default) means every call writes immediately.
     */
    var upsertSearchIndexGate: CompletableDeferred<Unit>? = null

    /** Completed by [upsertSearchIndex] the instant it reaches [upsertSearchIndexGate], before writing. */
    var upsertSearchIndexReached: CompletableDeferred<Unit>? = null

    override suspend fun upsertSearchIndex(entries: List<SearchIndexEntity>) {
        upsertSearchIndexReached?.complete(Unit)
        upsertSearchIndexGate?.await()
        this.entries.addAll(entries)
    }

    override suspend fun search(
        documentId: String,
        query: String,
        limit: Int,
    ): List<SearchIndexSearchEntry> = entries.take(limit).map { entry ->
        SearchIndexSearchEntry(
            documentId = entry.documentId,
            sectionIndex = entry.sectionIndex,
            sectionTitle = entry.sectionTitle,
            text = entry.text,
            startOffset = entry.startOffset,
            endOffset = entry.endOffset,
        )
    }

    override suspend fun getDocumentSectionsWithoutBlocks(documentId: String): List<SearchIndexSectionEntry> {
        val snapshot = entries.filter { it.documentId == documentId }.sortedBy { it.sectionIndex }.map { it.toSectionEntry() }
        getDocumentSectionsWithoutBlocksReached?.complete(Unit)
        getDocumentSectionsWithoutBlocksGate?.await()
        return snapshot
    }

    override suspend fun getSectionBlocksJson(documentId: String, sectionIndexes: List<Int>): List<SectionBlocksJsonEntry> {
        blocksJsonQueries += sectionIndexes
        return entries
            .filter { it.documentId == documentId && it.sectionIndex in sectionIndexes }
            .map { SectionBlocksJsonEntry(it.sectionIndex, it.blocksJson) }
    }

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

    override suspend fun getSectionSourcePaths(documentId: String): List<SectionSourcePathEntry> =
        entries.filter { it.documentId == documentId }
            .sortedBy { it.sectionIndex }
            .map { SectionSourcePathEntry(it.sectionIndex, it.sourcePath) }

    override suspend fun getFirstReadableContentSectionIndex(documentId: String, excludeSectionIndex: Int): Int? =
        entries.filter { it.documentId == documentId && it.sectionIndex != excludeSectionIndex && it.text.isNotBlank() }
            .minByOrNull { it.sectionIndex }
            ?.sectionIndex

    override suspend fun getSectionCount(documentId: String): Int =
        entries.count { it.documentId == documentId }
}

/**
 * [SearchIndexEntity] projected down to [SearchIndexSectionEntry] — every column except `blocksJson` —
 * matching what the real `SearchIndexDao.getDocumentSectionsWithoutBlocks` query selects.
 *
 * @receiver The stored entity to project.
 * @return The entity's non-block columns.
 */
private fun SearchIndexEntity.toSectionEntry() = SearchIndexSectionEntry(
    sectionIndex = sectionIndex,
    sectionTitle = sectionTitle,
    text = text,
    startOffset = startOffset,
    endOffset = endOffset,
    documentTitle = documentTitle,
    navigationJson = navigationJson,
    parserVersion = parserVersion,
)

/**
 * An in-memory [PageLayoutDao], upserting by the same (document, style, viewport) key Room's real
 * unique index enforces (see [hasSameKeyAs]), so a test can inspect [stored] directly to assert what a
 * real upsert would have replaced or kept.
 */
private class FakePageLayoutDao : PageLayoutDao {
    /** Every page layout row currently "stored", at most one per (document, style, viewport) key. */
    val stored = mutableListOf<PageLayoutEntity>()

    override suspend fun upsertPageLayout(layout: PageLayoutEntity) {
        stored.removeAll { it.hasSameKeyAs(layout) }
        stored += layout
    }

    override suspend fun getPageLayout(
        documentId: String,
        fontSizeSp: Float,
        lineHeightMultiplier: Float,
        fontFamilyName: String,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): PageLayoutEntity? = stored.firstOrNull {
        it.documentId == documentId &&
            it.fontSizeSp == fontSizeSp &&
            it.lineHeightMultiplier == lineHeightMultiplier &&
            it.fontFamilyName == fontFamilyName &&
            it.viewportWidthPx == viewportWidthPx &&
            it.viewportHeightPx == viewportHeightPx
    }

    override suspend fun getNewestPageLayoutForStyle(
        documentId: String,
        fontSizeSp: Float,
        lineHeightMultiplier: Float,
        fontFamilyName: String,
    ): PageLayoutEntity? = stored.filter {
        it.documentId == documentId &&
            it.fontSizeSp == fontSizeSp &&
            it.lineHeightMultiplier == lineHeightMultiplier &&
            it.fontFamilyName == fontFamilyName
    }.maxByOrNull { it.writtenAtEpochMillis }

    override suspend fun deletePageLayouts(documentId: String) {
        stored.removeAll { it.documentId == documentId }
    }

    override suspend fun trimPageLayouts(documentId: String, keep: Int) {
        val kept = stored.filter { it.documentId == documentId }
            .sortedByDescending { it.writtenAtEpochMillis }
            .take(keep)
        stored.removeAll { it.documentId == documentId && it !in kept }
    }

    /**
     * Whether this row and [other] share the same (document, style, viewport) identity — the key
     * [upsertPageLayout] replaces on, matching Room's real unique index for this table.
     *
     * @receiver One row to compare.
     * @param other The other row to compare.
     * @return True when every key column matches.
     */
    private fun PageLayoutEntity.hasSameKeyAs(other: PageLayoutEntity): Boolean =
        documentId == other.documentId &&
            fontSizeSp == other.fontSizeSp &&
            lineHeightMultiplier == other.lineHeightMultiplier &&
            fontFamilyName == other.fontFamilyName &&
            viewportWidthPx == other.viewportWidthPx &&
            viewportHeightPx == other.viewportHeightPx

    override suspend fun deletePartialPageLayouts(documentId: String) {
        stored.removeAll { it.documentId == documentId && it.isPartial }
    }

    override suspend fun promotePartialLayouts(documentId: String, characterCount: Long) {
        val toPromote = stored.filter { it.documentId == documentId && it.characterCount == characterCount && it.isPartial }
        toPromote.forEach { layout ->
            stored.remove(layout)
            stored.add(layout.copy(isPartial = false))
        }
    }
}

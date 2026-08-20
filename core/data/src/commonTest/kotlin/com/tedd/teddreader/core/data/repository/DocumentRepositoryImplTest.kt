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
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.blocksIn
import com.tedd.teddreader.core.data.mapper.CurrentReaderParserVersion
import com.tedd.teddreader.core.data.pagination.TextPageLayoutEngine
import com.tedd.teddreader.core.data.parser.ComicBookDocumentParser
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
import com.tedd.teddreader.core.room.dao.SearchIndexSectionEntry
import com.tedd.teddreader.core.room.dao.SectionBlocksJsonEntry
import com.tedd.teddreader.core.room.dao.SectionOffsetEntry
import com.tedd.teddreader.core.room.entity.DocumentEntity
import com.tedd.teddreader.core.room.entity.PageLayoutEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertTrue
import kotlin.test.fail

class DocumentRepositoryImplTest {
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

    @Test
    fun metadataUpsertPreservesImportCompletedTimestamp() = runTest {
        val documentDao = FakeDocumentDao()
        val location = DocumentLocation(
            sourceUri = "file:///imported.txt",
            displayName = "imported.txt",
            mimeType = "text/plain",
        )
        // Stands in for a row already backfilled by TeddReaderMigration7To8 (or completed by a later
        // progressive import): imported completely, well before this ordinary metadata edit.
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

        // An ordinary metadata upsert — toggling a favourite, say — must not carry the domain model's
        // missing field back into the database as null and erase the timestamp a later step needs to
        // trust.
        repository.upsertDocument(
            repository.getDocument(DocumentId(location.sourceUri))!!.copy(isBookmarked = true),
        )

        assertEquals(1_000L, documentDao.saved?.importCompletedAtEpochMillis)
    }

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

        // A fresh instance has no in-memory cache at all, only the persisted layout — the second call
        // can only succeed without measuring if it actually restores from storage.
        val poisonBreaker = ReaderPageBreaker { _, _ -> fail("Stored layout should have been used instead of measuring again.") }
        val secondPages = newRepository().getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style,
            viewportSize = viewportSize,
            pageBreaker = poisonBreaker,
        )

        assertEquals(firstPages, secondPages)
    }

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

    @Test
    fun getPageWindowsWithNullViewportRestoresTheNewestStoredLayoutForTheStyle() = runTest {
        // Step 6 regression guard: openDocument used to seed getPageWindows with a hardcoded guessed
        // viewport that almost never matched a stored row, so this is the failing case the fix targets
        // — given a layout stored at V1 and no page breaker, a null viewportSize must resolve exactly
        // that row, not fall through to a fresh estimate pass.
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
        // Measure and store a layout at V1 with a fresh instance, exactly like an earlier open of this
        // book on this device.
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
        // A fresh instance again, with no in-memory cache: nothing but the stored row itself can answer
        // this, since a null viewportSize has to resolve V1 on its own rather than measuring against
        // some other guess.
        val actual = newRepository().getPageWindows(
            documentId = DocumentId(location.sourceUri),
            style = style,
            viewportSize = null,
        )

        assertEquals(expected, actual)
    }

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

        // A different layout key: the stored row was measured at fontSizeSp = 20, not 24, so a null
        // viewportSize query for the new size must not pick it up — it has to measure fresh instead.
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

        // A different, explicit viewport for the same style: the stored row was measured at V1, so
        // asking for a concrete viewport that was never measured must not restore it either.
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

    @Test
    fun getPageWindowsWithNullViewportAndNoStoredLayoutStillReturnsAnEstimatedPagination() = runTest {
        // Regression guard for f33313b at the repository layer: a freshly imported book has no stored
        // layout and no breaker yet, so a null viewportSize must still fall back to the same default
        // guess a concrete caller used to pass, not an empty list — otherwise nothing would ever
        // measure the pane that is the only way pagination could improve on this guess.
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

        // Another app handing over a book that is already here — "open with", a share — lands on
        // importDocument every time. Re-importing threw away the text and the measured layout of a book
        // the reader was in the middle of, so this is now an open, not an import.
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

        // A re-parse — the one a parser-version bump sends every older book through — can move every
        // character offset in the book, and a layout written before it now describes pages that are not
        // there. This is what keeps such a row from being handed to the reader as if it still fit.
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
            ),
        )
        // Five sections written straight into the search index, the way a real book's chapters are
        // stored — enough of them that an on-demand restore only building the pages it is asked for is
        // the interesting case, not an accident of there being just one section to begin with.
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
        // The first call measures only the section the reader resumed into (section 0, with no
        // anchorOffset given) — finishPagination drives the rest the same way a background
        // continuation loop would, before this test asks for the whole-book answer to compare against.
        measuringRepository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        val measuredPages = measuringRepository.finishPagination(documentId, style, viewportSize, measuringBreaker)

        // A fresh instance restores from the persisted layout only, exactly like the cold-cache case
        // above, but this time across several sections instead of one.
        val restoredPages = newRepository().getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
        )

        assertEquals(measuredPages.size, restoredPages.size, "restoring from storage must not change the page count")
        assertEquals(measuredPages, restoredPages, "an on-demand restore must reproduce every page byte-for-byte")
    }

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
        // Large enough that page starts land well past the small numbers other tests use here — the
        // same territory a real multi-hundred-thousand-character book measures into, including offset 0
        // for the first page.
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

        // Fresh instance again: only the blob written by storePageWindows can answer this restore.
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

        // Import reads bytes straight from DocumentImportSource, not from the file source, so the
        // cover file has to exist before getDocumentCover is ever called for this to be a real test of
        // the cache rather than of the fallback path.
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

    @Test
    fun getDocumentCoverFallsBackForABookImportedBeforeCoverCachingThenCachesItForNextTime() = runTest {
        // Stands in for a book imported before this feature existed: a document row and search index
        // exist, but nothing ever wrote a cover file for it — the only way that happens today.
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
        assertEquals(1, fileSource.readCount, "with no cached file yet, the first call must fall back to a full read.")

        repository.getDocumentCover(documentId)

        assertEquals(
            1,
            fileSource.readCount,
            "the fallback must cache the cover on the way out so a second call does not read the whole file again.",
        )
    }

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
        // Neither import path nor a cache hit ever calls readBytes/copyTo here (the cover comes from
        // the bytes passed to importDocument directly), so one fake standing in for "the file source"
        // is enough — only its shared appPrivateDirectory() matters for this test.
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

    @Test
    fun getPageWindowsRestoringFromStorageOnlyFetchesBlocksJsonForSectionZero() = runTest {
        // Step 8 regression guard: opening used to SELECT * every section's blocksJson before a single
        // page was built. A restore must now only ever fetch section 0's — cover detection needs it
        // eagerly (see TextPageLayoutEngine.findCoverSection) — not the other four sections' blocks.
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

        // A fresh instance, no in-memory cache at all — only the persisted layout can answer this.
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
        // Restores; only section 0 is prewarmed automatically (see the test above). Section 2 is a
        // genuine miss right now.
        repository.getPageWindows(documentId = documentId, style = style, viewportSize = viewportSize)
        searchIndexDao.blocksJsonQueries.clear()

        repository.warmSectionBlocks(documentId, setOf(2))

        assertEquals(
            listOf(listOf(2)),
            searchIndexDao.blocksJsonQueries,
            "a miss must fetch exactly the missed section, not the rest of the book",
        )

        // Asking again for a section already decoded must not fetch it a second time.
        repository.warmSectionBlocks(documentId, setOf(2))
        assertEquals(listOf(listOf(2)), searchIndexDao.blocksJsonQueries, "a section already decoded must not be re-fetched")
    }

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

        // A fresh instance restoring from storage, then made to stand in for "every section's blocks
        // were loaded eagerly" — the way SELECT * used to hand every row's blocksJson over before a
        // single page was built — by warming every section before any page is read.
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
        // Four ten-character pages per forty-character section, no cover — page 4 is section 1's first
        // page, a genuine miss right now since only section 0 is prewarmed automatically.
        val missedPageIndex = 4
        val beforeFill = pages[missedPageIndex]
        assertTrue(beforeFill.blocks.isEmpty(), "a page must render as 'not yet' before its section's blocks arrive")

        repository.warmSectionBlocks(documentId, setOf(1))
        val afterFill = pages[missedPageIndex]
        assertEquals(beforeFill.textRange, afterFill.textRange, "filling in a section must never move where a page's text starts")
        assertTrue(afterFill.blocks.isNotEmpty(), "filling in the missed section must complete the page's blocks")

        // An unrelated background fill for the rest of the book must not disturb a page that already
        // has its final answer — a page already shown keeps its text and its blocks.
        repository.warmSectionBlocks(documentId, (0 until 5).toSet())
        val afterBackgroundFill = pages[missedPageIndex]
        assertEquals(afterFill.textRange, afterBackgroundFill.textRange)
        assertEquals(afterFill.blocks, afterBackgroundFill.blocks)
    }

    // --- Progressive EPUB import (step 9) ---
    //
    // Written first, before importEpubPhase0/importNextSections existed: this is the test that had to
    // fail to compile against the pre-change DocumentRepository interface, since neither
    // isImportComplete nor importNextSections existed for it to call.
    @Test
    fun importDocumentForMultiChapterEpubOnlyPersistsPhase0SectionsAndLeavesImportIncomplete() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///progressive.epub",
            displayName = "progressive.epub",
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

        // bytes=null is what exercises the phased path — importDocument treats a non-null bytes
        // argument as "the caller already has everything, just do the old one-shot parse" (see
        // DocumentRepositoryImpl.importDocument), and this suite is specifically testing the case
        // where it does not: a picked file streamed straight from fileSource instead.
        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)

        assertFalse(
            repository.isImportComplete(documentId),
            "a 5-chapter EPUB must not be complete after phase 0/1 (cover + chapter 1) alone",
        )
        assertEquals(null, documentDao.saved?.characterCount, "characterCount must stay null until the import completes")
        assertEquals(
            listOf(0, 1),
            searchIndexDao.entries.filter { it.documentId == documentId.value }.map { it.sectionIndex }.sorted(),
            "phase 0 must persist only the cover section and the first chapter, not the rest of the spine",
        )
    }

    @Test
    fun characterCountStaysNullUntilImportNextSectionsCompletesTheBook() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///progressive-charcount.epub",
            displayName = "progressive-charcount.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount = 5)
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
        // bytes=null is what exercises the phased path — importDocument treats a non-null bytes
        // argument as "the caller already has everything, just do the old one-shot parse" (see
        // DocumentRepositoryImpl.importDocument), and this suite is specifically testing the case
        // where it does not: a picked file streamed straight from fileSource instead.
        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        assertEquals(null, documentDao.saved?.characterCount)

        repository.importNextSections(documentId, count = 1, style, viewportSize, pageBreaker = null)
        assertEquals(null, documentDao.saved?.characterCount, "characterCount must stay null while any section remains unimported")
        assertFalse(repository.isImportComplete(documentId))

        var guard = 0
        while (!repository.isImportComplete(documentId)) {
            repository.importNextSections(documentId, count = 10, style, viewportSize, pageBreaker = null)
            guard += 1
            check(guard < 20) { "import did not converge" }
        }
        assertTrue((documentDao.saved?.characterCount ?: 0L) > 0L, "characterCount must be the real total once the import completes")
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
        // bytes=null is what exercises the phased path — importDocument treats a non-null bytes
        // argument as "the caller already has everything, just do the old one-shot parse" (see
        // DocumentRepositoryImpl.importDocument), and this suite is specifically testing the case
        // where it does not: a picked file streamed straight from fileSource instead.
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
        // bytes=null exercises the phased path — see the other progressive-import tests above.
        newRepository().importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)
        newRepository().importNextSections(documentId, count = 1, style, viewportSize, pageBreaker = null)

        // "Crash": nothing survives but what's in documentDao/searchIndexDao — every later call below
        // is a brand-new repository instance, with none of the previous ones' in-memory state, and
        // must still finish the book correctly by reading only the stored rows.
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

    @Test
    fun pageTextRangeIsUnchangedAfterImportNextSectionsAppendsMoreSections() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///append-stable.epub",
            displayName = "append-stable.epub",
            mimeType = "application/epub+zip",
        )
        val epubBytes = sampleMultiChapterEpubBytesWithCover(chapterCount = 3)
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
        // bytes=null is what exercises the phased path — importDocument treats a non-null bytes
        // argument as "the caller already has everything, just do the old one-shot parse" (see
        // DocumentRepositoryImpl.importDocument), and this suite is specifically testing the case
        // where it does not: a picked file streamed straight from fileSource instead.
        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)

        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 19) / 20) { page -> page * 20 } }

        val firstPages = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        assertTrue(firstPages.size > 1, "the cover plus chapter 1 must already measure to more than one page")
        val lastPublishedRange = firstPages.last().textRange

        repository.importNextSections(documentId, count = 2, style, viewportSize, measuringBreaker)

        val grownPages = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        assertTrue(grownPages.size > firstPages.size, "importing more sections must grow the known page count")
        assertEquals(
            lastPublishedRange,
            grownPages[firstPages.lastIndex].textRange,
            "a page already published must keep its exact boundaries once later sections are appended",
        )
    }

    // --- Step 10: section-relative block storage ---

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

        // bytes=null exercises the phased path: phase 0 persists the cover and chapter 1 through
        // persistParsedDocument, and this loop finishes chapter 2 — the one with the bold span, at a
        // non-zero absolute offset — through importNextSections's own block-storage call. Both call
        // sites now rebase before writing blocksJson, so both have to be exercised here.
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
            // The inverse of the rebase persistParsedDocument/importNextSections apply before storing:
            // shift back by adding the section's own absolute start, block and span ranges alike.
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

    // --- Step 11: progressive pagination for a type that has never been measured ---

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
            ),
        )
        val sections = fiveTxtSectionsWithBlocks(location.sourceUri)
        searchIndexDao.upsertSearchIndex(sections)
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        // Every section's text is distinct ("aaaa...", "bbbb...", ...), so the breaker's own argument
        // proves which section it was called for, not just how many times.
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

        assertEquals(
            listOf(sections[2].text),
            measuredTexts,
            "opening with no stored layout must measure only the section the reader resumes into, not the whole book",
        )
        assertTrue(pages.isNotEmpty(), "the resumed section's own pages must already be there to show")
        assertFalse(
            repository.isPaginationComplete(documentId),
            "pagination must not be reported complete until every section has been measured",
        )
    }

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

        // The reference answer: every section laid out in one pass, the way getPageWindows used to
        // before this change, and the way it still would if TextPageLayoutEngine were asked directly.
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

        // The answer under test: one section measured at a time, driven to completion the same way a
        // background continuation loop would.
        val incrementalRepository = newRepository()
        incrementalRepository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
        val incrementalPages = incrementalRepository.finishPagination(documentId, style, viewportSize, measuringBreaker)

        assertEquals(
            wholeDocumentPages,
            incrementalPages,
            "measuring one section at a time must produce byte-identical pages to measuring the whole book at once",
        )
    }

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
            ),
        )
        searchIndexDao.upsertSearchIndex(fiveTxtSectionsWithBlocks(location.sourceUri))
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 9) / 10) { page -> page * 10 } }
        val documentCharacterCount = checkNotNull(repository.getReaderDocument(documentId)?.characterCount)

        // What a writer bug that appended the same section twice leaves behind: a row that decodes, and
        // whose character count still matches, but whose pages walk backwards partway through.
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

        // A type change starts one continuation pass from updateStyle and another from the pane's first
        // breaker report for the new type, so two of them genuinely run at once (see
        // ReaderViewModel.refreshPaginationCompleteness). Both used to read the same lowPosition, measure
        // the same section, and append it twice, leaving a finished pass holding — and storing — up to
        // twice the book's pages.
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
            ),
        )
        val sections = fiveTxtSectionsWithBlocks(location.sourceUri)
        searchIndexDao.upsertSearchIndex(sections)
        val documentId = DocumentId(location.sourceUri)
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)
        val measuringBreaker = ReaderPageBreaker { measured, _ -> IntArray((measured.length + 9) / 10) { page -> page * 10 } }

        // Resuming into the middle section exercises both directions continuePagination extends in:
        // sections 0-1 get measured backward, sections 3-4 forward, around this one already-shown page.
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
            // Stands in for a row an older parser build wrote: non-blank navigationJson, so only the
            // parserVersion gate (not the other one loadReaderDocument also checks) is under test here.
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

        // The repair takes the phased import route, which streams from one copy of the file instead of
        // reading the whole book into memory — so the count that says "repaired once" is the copy, and
        // the absence of a whole-file read is itself the thing worth asserting.
        val repairingRepository = newRepository()
        val repaired = repairingRepository.getReaderDocument(documentId)
        assertEquals(1, fileSource.copyCount, "a stale parserVersion must trigger exactly one repair")
        assertEquals(0, fileSource.readCount, "a repair must not read the whole book into memory before the reader can draw")
        assertTrue(repaired?.sections?.isNotEmpty() == true, "the repair must actually re-parse the book, not just bump the version")
        assertTrue(
            searchIndexDao.entries.all { it.parserVersion == CurrentReaderParserVersion },
            "every section the repair wrote must be stored at the current parser version",
        )

        // Whatever the repair left for the background finishes the way a fresh import's remainder does,
        // and ends with the whole book at the current version — not only the chapter shown first.
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

        // A fresh instance, so only what the repair actually wrote — not an in-memory document cache —
        // can explain a second open not repairing again.
        newRepository().getReaderDocument(documentId)
        assertEquals(1, fileSource.copyCount, "a second open must not repair again once the stored parserVersion is current")
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
        // bytes=null is what exercises the phased path — see the other progressive-import tests above.
        repository.importDocument(DocumentImportSource(location, bytes = null), importedAtEpochMillis = 1_000)

        // openDocument's very first call: no pane measurement yet, so no breaker and no viewport.
        repository.getPageWindows(documentId, style, viewportSize = null, pageBreaker = null)
        // The pane's first real report (updatePageBreaker) — its own reload is what stores the very
        // first page-layout row, over whatever phase 0 already committed.
        var livePages = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)

        // continueImportIfIncomplete: one batch at a time, reloading pages after every batch that
        // actually imported something — the reader is "reading" while the rest of the book streams in.
        var guard = 0
        while (!repository.isImportComplete(documentId)) {
            val progress = repository.importNextSections(documentId, count = 2, style, viewportSize, measuringBreaker)
            if (progress.sectionsImported > 0) {
                livePages = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
            }
            guard += 1
            check(guard < 20) { "import did not converge" }
        }

        // continuePaginationIfIncomplete: the same reload-after-each-step shape, finishing whatever
        // getPageWindows above could not measure in one call.
        guard = 0
        while (!repository.isPaginationComplete(documentId)) {
            val progress = repository.continuePagination(documentId, style, viewportSize, measuringBreaker)
            if (progress.sectionsMeasured > 0) {
                livePages = repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
            }
            guard += 1
            check(guard < 200) { "pagination did not converge" }
        }

        // Force-stop and reopen: a brand-new instance over the very same stored rows, nothing cached.
        val restoredPages = newRepository().getPageWindows(documentId, style, viewportSize, measuringBreaker)

        assertEquals(
            livePages.size,
            restoredPages.size,
            "the page count the reader was shown during the live session must equal what a later restore produces",
        )
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
     * regression test drives exactly that interleaving; it passes on the current code (measured against
     * an independent whole-document reference pass), so this specific race is not the source of the
     * ~2x page-count bug this suite is investigating, but it is worth keeping as coverage of a hazard
     * the codebase's own comments call out.
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
        // The pane's first real report — same as updatePageBreaker's own reload — measures and stores
        // phase 0's own section before either background loop below ever starts.
        repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)

        coroutineScope {
            // continueImportIfIncomplete.
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
            // continuePaginationIfIncomplete, started concurrently — refreshPaginationCompleteness
            // only checks isPaginationComplete, never isImportComplete, before starting this.
            launch(Dispatchers.Default) {
                var guard = 0
                while (!repository.isPaginationComplete(documentId)) {
                    val progress = repository.continuePagination(documentId, style, viewportSize, measuringBreaker)
                    if (progress.sectionsMeasured > 0) {
                        repository.getPageWindows(documentId, style, viewportSize, measuringBreaker)
                    }
                    guard += 1
                    check(guard < 2000) { "pagination did not converge" }
                }
            }
        }
        check(repository.isImportComplete(documentId))
        check(repository.isPaginationComplete(documentId))

        // Ground truth: every section laid out in one whole-document pass, independent of anything
        // either background loop above did. warmSectionBlocks is required first — ReaderDocument.blocks
        // is a LazyFlattenedBlocks over SectionBlocksCache, which answers empty for any section nothing
        // has prewarmed yet (see SectionBlocksCache's own doc), so an un-warmed reference would silently
        // find no cover block at all and disagree with the real code's own always-prewarmed paths.
        val referenceRepository = newRepository()
        val referenceDocument = referenceRepository.getReaderDocument(documentId)!!
        referenceRepository.warmSectionBlocks(documentId, referenceDocument.sections.map { it.index }.toSet())
        val referencePages = TextPageLayoutEngine().paginate(
            document = referenceDocument,
            style = style,
            viewportSize = viewportSize,
            pageBreaker = measuringBreaker,
        )

        // Force-stop and reopen: a brand-new instance over the very same stored rows, nothing cached.
        val restoredPages = newRepository().getPageWindows(documentId, style, viewportSize, measuringBreaker)

        assertEquals(
            referencePages.size,
            restoredPages.size,
            "a stored page layout must hold exactly one measurement of the book, not a duplicate of any section",
        )
    }
}

private class FakeDocumentFileSource(
    private val expectedLocation: DocumentLocation,
    private val bytes: ByteArray,
) : DocumentFileSource {
    var readCount: Int = 0
    var copyCount: Int = 0

    // Unique per fake instance so one test's cached cover file can never be left over for the next —
    // a real device's covers directory is one shared place, but a test's should not be.
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

/** A minimal EPUB whose cover is declared purely through the manifest's `cover-image` property — no
 * dedicated cover.xhtml page — so the cover bytes exist independently of whether the reader visits it
 * as a section. */
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
 * not touch the rest — the cover always synthesizes as section 0 (see EpubDocumentParser.parseWithCover),
 * the chapters become sections 1..[chapterCount].
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

/**
 * Cover + two chapters, chapter two carrying a `<b>` span — enough to prove a block's spans, not just
 * its own range, round-trip through section-relative storage correctly, at a section whose absolute
 * start is not 0 (unlike the cover, see DocumentRepositoryImplTest's parserVersion/round-trip tests).
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
 * Five ordinary TXT sections written straight into the search index, each carrying one real
 * [ReaderBlockKind.PARAGRAPH] block over its own text — enough sections, with real blocksJson, to tell
 * a genuine on-demand fetch apart from an eagerly-loaded one.
 *
 * The block is stored relative to the section's own start (range 0..text.length), matching what
 * persistParsedDocument/importNextSections now actually write — a fixture standing in for "already in
 * storage" has to agree with the real writer or a decode reads the block at the wrong offset.
 */
/**
 * Drives [DocumentRepositoryImpl.continuePagination] to completion on the same instance that started
 * a progressive pagination pass — the same idiom the progressive-*import* tests already use with
 * `isImportComplete`/`importNextSections` — then hands back the fully-measured pages. The final
 * [DocumentRepositoryImpl.getPageWindows] call is a cache hit (continuePagination already wrote the
 * finished list into the same in-memory cache getPageWindows reads), not a re-measurement.
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

private class FakeDocumentDao : DocumentDao {
    var saved: DocumentEntity? = null

    override suspend fun upsertDocument(document: DocumentEntity) {
        saved = document
    }

    override suspend fun getDocument(documentId: String): DocumentEntity? =
        saved?.takeIf { it.id == documentId }

    override fun observeRecentDocuments(): Flow<List<DocumentEntity>> =
        flowOf(listOfNotNull(saved))

    override suspend fun updateLastOpenedAt(documentId: String, openedAtEpochMillis: Long) {
        saved = saved?.copy(lastOpenedAtEpochMillis = openedAtEpochMillis)
    }

    override suspend fun deleteDocument(documentId: String) {
        if (saved?.id == documentId) saved = null
    }
}

/** Unlike [FakeDocumentDao], keeps every document ever upserted — needed only where a test imports
 * more than one book and needs both to still resolve afterwards. */
private class FakeMultiDocumentDao : DocumentDao {
    private val documents = mutableMapOf<String, DocumentEntity>()

    override suspend fun upsertDocument(document: DocumentEntity) {
        documents[document.id] = document
    }

    override suspend fun getDocument(documentId: String): DocumentEntity? = documents[documentId]

    override fun observeRecentDocuments(): Flow<List<DocumentEntity>> = flowOf(documents.values.toList())

    override suspend fun updateLastOpenedAt(documentId: String, openedAtEpochMillis: Long) {
        documents[documentId]?.let { documents[documentId] = it.copy(lastOpenedAtEpochMillis = openedAtEpochMillis) }
    }

    override suspend fun deleteDocument(documentId: String) {
        documents.remove(documentId)
    }
}

private class FakeDocumentSearchIndexDao : SearchIndexDao {
    val entries = mutableListOf<SearchIndexEntity>()

    // Every call recorded verbatim, so a test can assert exactly which sections a fetch touched —
    // "count fetches" alone would miss a call that asked for the wrong sections.
    val blocksJsonQueries = mutableListOf<List<Int>>()

    override suspend fun upsertSearchIndex(entries: List<SearchIndexEntity>) {
        this.entries.addAll(entries)
    }

    override suspend fun search(
        documentId: String,
        query: String,
        limit: Int,
    ): List<SearchIndexEntity> = entries.take(limit)

    override suspend fun getDocumentSectionsWithoutBlocks(documentId: String): List<SearchIndexSectionEntry> =
        entries.filter { it.documentId == documentId }.sortedBy { it.sectionIndex }.map { it.toSectionEntry() }

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
}

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

private class FakePageLayoutDao : PageLayoutDao {
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

    private fun PageLayoutEntity.hasSameKeyAs(other: PageLayoutEntity): Boolean =
        documentId == other.documentId &&
            fontSizeSp == other.fontSizeSp &&
            lineHeightMultiplier == other.lineHeightMultiplier &&
            fontFamilyName == other.fontFamilyName &&
            viewportWidthPx == other.viewportWidthPx &&
            viewportHeightPx == other.viewportHeightPx
}

package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.data.pagination.TextPageLayoutEngine
import com.tedd.teddreader.core.data.parser.ComicBookDocumentParser
import com.tedd.teddreader.core.data.parser.DocumentFormatDetector
import com.tedd.teddreader.core.data.parser.EpubDocumentParser
import com.tedd.teddreader.core.data.parser.ImageDocumentParser
import com.tedd.teddreader.core.data.parser.PdfDocumentParser
import com.tedd.teddreader.core.data.parser.TxtDocumentParser
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.room.dao.DocumentDao
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import com.tedd.teddreader.core.room.entity.DocumentEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun importsCp949TxtDocumentWithoutBreakingKorean() = runTest {
        val documentDao = FakeDocumentDao()
        val searchIndexDao = FakeDocumentSearchIndexDao()
        val repository = DocumentRepositoryImpl(
            documentDao = documentDao,
            searchIndexDao = searchIndexDao,
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

}

private class FakeDocumentFileSource(
    private val expectedLocation: DocumentLocation,
    private val bytes: ByteArray,
) : DocumentFileSource {
    var readCount: Int = 0
    var copyCount: Int = 0

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

private class FakeDocumentSearchIndexDao : SearchIndexDao {
    val entries = mutableListOf<SearchIndexEntity>()

    override suspend fun upsertSearchIndex(entries: List<SearchIndexEntity>) {
        this.entries.addAll(entries)
    }

    override suspend fun search(
        documentId: String,
        query: String,
        limit: Int,
    ): List<SearchIndexEntity> = entries.take(limit)

    override suspend fun getDocumentSections(documentId: String): List<SearchIndexEntity> =
        entries.filter { it.documentId == documentId }.sortedBy { it.sectionIndex }

    override suspend fun deleteSearchIndex(documentId: String) {
        entries.removeAll { it.documentId == documentId }
    }
}

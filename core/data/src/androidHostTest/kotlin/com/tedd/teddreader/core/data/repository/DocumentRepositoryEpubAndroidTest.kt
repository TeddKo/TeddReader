package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.ReaderBlockKind
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
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import com.tedd.teddreader.core.room.entity.DocumentEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentRepositoryEpubAndroidTest {
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
                        blocks = document.blocks.filter { it.range.start < section.range.end && it.range.end > section.range.start },
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

        assertEquals(document.title, restored?.title)
        assertEquals(document.blocks, restored?.blocks)
        assertEquals(document.navigation, restored?.navigation)
        assertContentEquals(
            byteArrayOf(1, 2, 3, 4),
            repository.getEmbeddedImages(document.id, setOf("OEBPS/images/pic.png"))["OEBPS/images/pic.png"],
        )
    }

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

        val restored = repository.getReaderDocument(DocumentId(location.sourceUri))

        assertTrue(restored?.blocks?.isNotEmpty() == true)
        assertEquals("Sample EPUB Title", restored.title)
        assertTrue(restored.navigation?.items?.isNotEmpty() == true)
        assertTrue(searchIndexDao.entries.all { it.blocksJson != "[]" })
        assertTrue(searchIndexDao.entries.first().navigationJson.isNotBlank())
    }
}

private fun repository(
    documentDao: AndroidFakeDocumentDao,
    searchIndexDao: AndroidFakeSearchIndexDao,
    fileSource: DocumentFileSource,
): DocumentRepositoryImpl = DocumentRepositoryImpl(
    documentDao = documentDao,
    searchIndexDao = searchIndexDao,
    formatDetector = DocumentFormatDetector(),
    txtDocumentParser = TxtDocumentParser(),
    epubDocumentParser = EpubDocumentParser(),
    pdfDocumentParser = PdfDocumentParser(),
    comicBookDocumentParser = ComicBookDocumentParser(),
    imageDocumentParser = ImageDocumentParser(),
    textPageLayoutEngine = TextPageLayoutEngine(),
    documentFileSource = fileSource,
)

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

private class AndroidFakeDocumentFileSource(
    private val location: DocumentLocation,
    private val bytes: ByteArray,
) : DocumentFileSource {
    override suspend fun readBytes(location: DocumentLocation): ByteArray {
        assertEquals(this.location, location)
        return bytes
    }
}

private class AndroidFakeDocumentDao(
    private var document: DocumentEntity? = null,
) : DocumentDao {
    override suspend fun upsertDocument(document: DocumentEntity) {
        this.document = document
    }

    override suspend fun getDocument(documentId: String): DocumentEntity? = document?.takeIf { it.id == documentId }

    override fun observeRecentDocuments(): Flow<List<DocumentEntity>> = flowOf(listOfNotNull(document))

    override suspend fun updateLastOpenedAt(documentId: String, openedAtEpochMillis: Long) = Unit

    override suspend fun deleteDocument(documentId: String) = Unit
}

private class AndroidFakeSearchIndexDao : SearchIndexDao {
    val entries = mutableListOf<SearchIndexEntity>()

    override suspend fun upsertSearchIndex(entries: List<SearchIndexEntity>) {
        this.entries += entries
    }

    override suspend fun search(documentId: String, query: String, limit: Int): List<SearchIndexEntity> = emptyList()

    override suspend fun getDocumentSections(documentId: String): List<SearchIndexEntity> =
        entries.filter { it.documentId == documentId }.sortedBy { it.sectionIndex }

    override suspend fun deleteSearchIndex(documentId: String) {
        entries.removeAll { it.documentId == documentId }
    }
}

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

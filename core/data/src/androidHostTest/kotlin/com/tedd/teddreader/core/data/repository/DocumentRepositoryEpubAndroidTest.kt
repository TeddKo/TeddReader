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
 * 실제 JVM zip 구현이 있어야 확인할 수 있는 [DocumentRepositoryImpl]과 [EpubDocumentParser]의 EPUB 동작을
 * 검증한다: EPUB3 nav 프래그먼트가 명시한 정확한 문자 오프셋에 목차 항목이 도달하는지, 손상된 NCX의 표지
 * 대상이 표지를 두 번 가리키는 대신 책의 첫 실제 본문 섹션으로 재지정되는지, 그리고 — 저장소 자체에 대해서는
 * — 저장된 EPUB의 블록과 임베드 이미지가 [DocumentRepositoryImpl.getReaderDocument]를 거쳐 변형 없이
 * 왕복하는지, 블록이 전혀 없는 레거시 행이 새로 고른 EPUB가 거치는 것과 같은 단계적 임포트로 스스로
 * 복구되는지를 확인한다.
 */
class DocumentRepositoryEpubAndroidTest {
    /**
     * EPUB3 nav 문서의 목차는 spine 항목의 시작뿐 아니라 그 안의 프래그먼트도 가리킬 수 있다 — 여기서는
     * `chapter-1.xhtml#start`와, 그 아래 중첩된 `chapter-1.xhtml#scene1`이 그렇다. 이 테스트는
     * [EpubDocumentParser]가 그 항목을 섹션의 시작이 아니라, 프래그먼트의 앵커가 실제로 위치한 섹션 내
     * 정확한 문자 오프셋(0과 14, 각각 "Chapter One"과 그 한 단계 아래 중첩된 "Scene One"에 대응)으로
     * 해석하는지 고정한다.
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
     * 손상된 NCX는 하나 이상의 `navPoint`가 표지 페이지를 가리키게 할 수 있다 — 여기서는 "Start"와 "Cover"
     * 둘 다 `cover.xhtml`을 대상으로 한다. [EpubDocumentParser]는 손상된 쪽("Start")을 책의 첫 실제 본문
     * 섹션으로 재지정하며, 표지를 두 번째로 다시 열게 두지 않는다. 정직하게 표지라는 제목이 붙은 항목("Cover")은
     * 그대로 표지를 가리키게 두고, 이미 실제 챕터를 명시한 항목("Chapter Two")도 손대지 않는다.
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
     * [DocumentRepositoryImpl.getReaderDocument]로 다시 불러온 문서는 새로 파싱했을 때와 정확히 같은
     * [ReaderDocument]를 재현해야 한다 — 같은 제목, 같은 블록(표지와 챕터가 함께 참조하는 임베드 이미지
     * 포함), 같은 목차 — 그리고 임베드된 이미지 바이트는 그 후에도 href로 여전히 추출 가능해야 한다.
     *
     * 이 픽스처의 검색 인덱스 행들은 각 섹션의 블록을 섹션 상대로 저장한다. 이는
     * `DocumentRepositoryImpl.persistParsedDocument`가 `blocksJson`을 실제로 기록하기 전에 적용하는 것과
     * 같은 이동이다 — 이 픽스처는 "이미 저장소에 있는 상태"를 대신하므로, 실제 기록자가 저장하는 것과 맞아야
     * 하며, 새로 파싱된 `document`가 여전히 갖고 있는 [ReaderDocument.blocks] 자체의 절대 오프셋과 비교하면
     * 안 된다. 그렇게 하면 이 테스트는 실제 어떤 행도 갖고 있지 않은 데이터와 비교해 통과해 버릴 것이다.
     *
     * [ReaderDocument.blocks]는 저장소에서 로드된 뒤 섹션 단위로 지연 디코딩된다(`DocumentRepositoryImpl.SectionBlocksCache`
     * 참고). 그래서 블록 비교 앞에 명시적으로 넣은 [DocumentRepositoryImpl.warmSectionBlocks] 호출이 "모든
     * 블록이 이미 로드됨" — `restored?.blocks` 단언이 실제로 확인하는 조건 — 을 대신한다. 이게 없으면
     * 리더가 아직 요청한 적 없는 섹션은 블록이 전혀 없이 읽혀 돌아올 것이다.
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
     * 예전 파서 버전이 기록한, 블록이 전혀 없는(모든 `blocksJson`이 `"[]"`) 저장된 EPUB 행들을 가진 책은
     * 다음 [DocumentRepositoryImpl.getReaderDocument] 호출에서, 새로 고른 EPUB가 거치는 것과 같은 단계적
     * 임포트 경로를 밟아 스스로 복구된다(`DocumentRepositoryImpl.repairEpubDocument` 참고). 그 복구가 리더에게
     * 즉시 주는 것은 책 자신의 제목 아래, 실제 블록을 가진 첫 챕터다. 목차는 여기 포함되지 않는다 — 복구가
     * 밟는 단계적 임포트 경로는, 새로 고른 EPUB와 마찬가지로 전체 spine을 다 읽어야 목차를 채우기 때문이다
     * ([DocumentRepositoryImpl.finishEpubImport] 참고). 그 뒤 [DocumentRepositoryImpl.importNextSections]를
     * 완료까지 진행시키는 것이 마침내 비어있지 않은 목차를 만들어내고 모든 섹션의 블록도 비어있지 않게 만든다.
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
 * 주어진 페이크들과, 그 뒤의 실제 파서 전부 및 실제 [TextPageLayoutEngine]으로 [DocumentRepositoryImpl]을
 * 연결한다 — 저장소와 파일 접근 경계만 페이크로 대체하므로, 테스트는 실제 EPUB 파싱/페이지네이션 경로를
 * 처음부터 끝까지 실행한다.
 *
 * @param documentDao 사용할 서가 메타데이터 페이크.
 * @param searchIndexDao 사용할 섹션별 저장소 페이크.
 * @param fileSource 저장소가 원본 파일 바이트를 읽거나 복사해 오는 곳.
 * @return 연결이 완료된 저장소.
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
 * 최소한이지만 구조적으로는 진짜인 EPUB: 표지 페이지, 제목·볼드 스팬·인라인 이미지를 담은 챕터 하나, 그
 * 챕터를 나열하는 nav 문서, 표지와 챕터 둘 다에서 참조하는 임베드 이미지 하나 — 저장된 블록, 목차, 임베드
 * 이미지 추출을 함께 검증하기에 충분한 실제 구조다.
 *
 * @return 인코딩된 EPUB의 바이트.
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
 * [location]에 대해 항상 [bytes]를 돌려주는 [DocumentFileSource]. 모든 호출이 잘못된 데이터를 조용히
 * 답하는 대신 정확히 그 위치로 이뤄지는지 단언한다.
 *
 * @property location 모든 호출이 지켜야 하는 위치.
 * @property bytes [readBytes]에서 돌려주고 [copyTo]에서 기록할 바이트.
 */
private class AndroidFakeDocumentFileSource(
    private val location: DocumentLocation,
    private val bytes: ByteArray,
) : DocumentFileSource {
    /** 페이크 인스턴스마다 고유하므로, 한 테스트가 캐시한 표지 파일이 다음 테스트에 절대 남지 않는다. */
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
 * 단일 서가 슬롯을 모델링하는 [DocumentDao]. 생성자로 미리 씨딩할 수 있고, [upsertDocument] 호출마다
 * 전체가 교체된다 — 한 번에 한 문서만 서가에 있는 테스트에 딱 맞는 형태다.
 *
 * @property document 현재 "저장된" 문서 하나. 생성자로 미리 씨딩되거나, [deleteDocument]가 제거하면 null.
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

    override suspend fun updateCountsAndFontIndex(documentId: String, characterCount: Long, wordCount: Long, embeddedFontHrefsJson: String?) {
        if (document?.id == documentId) {
            document = document?.copy(characterCount = characterCount, wordCount = wordCount, embeddedFontHrefsJson = embeddedFontHrefsJson)
        }
    }

    override suspend fun updateCountsAndMarkComplete(documentId: String, characterCount: Long, wordCount: Long, importCompletedAtEpochMillis: Long) {
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
 * 단순 [entries] 목록으로 뒷받침되는, 메모리 내 [SearchIndexDao]. 실제 DAO가
 * [getDocumentSectionsWithoutBlocks] / [getSectionBlocksJson]으로 노출하는 것과 같은, 섹션 메타데이터와
 * 블록 JSON의 분리를 그대로 유지한다 — 그래서 테스트는 이미 실제 블록을 가진 행이든 전혀 없는 행이든(위의
 * 레거시 복구 테스트 참고) 씨딩할 수 있다.
 */
private class AndroidFakeSearchIndexDao : SearchIndexDao {
    /** 지금까지 업서트된 모든 문서에 걸친 모든 섹션 — 호출마다 `documentId`로 필터링된다. */
    val entries = mutableListOf<SearchIndexEntity>()

    override suspend fun upsertSearchIndex(entries: List<SearchIndexEntity>) {
        this.entries += entries
    }

    override suspend fun search(documentId: String, query: String, limit: Int) = emptyList<com.tedd.teddreader.core.room.dao.SearchIndexSearchEntry>()

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

    override suspend fun getSectionSourcePaths(documentId: String): List<com.tedd.teddreader.core.room.dao.SectionSourcePathEntry> =
        entries.filter { it.documentId == documentId }
            .sortedBy { it.sectionIndex }
            .map { com.tedd.teddreader.core.room.dao.SectionSourcePathEntry(it.sectionIndex, it.sourcePath) }

    override suspend fun getFirstReadableContentSectionIndex(documentId: String, excludeSectionIndex: Int): Int? =
        entries.filter { it.documentId == documentId && it.sectionIndex != excludeSectionIndex && it.text.isNotBlank() }
            .minByOrNull { it.sectionIndex }
            ?.sectionIndex

    override suspend fun getSectionCount(documentId: String): Int =
        entries.count { it.documentId == documentId }
}

/**
 * 아무 동작도 하지 않는 [PageLayoutDao]: 아무것도 저장되거나 반환되지 않는다. 이 파일의 어떤 테스트도
 * 저장된 페이지 레이아웃을 검증하지 않으므로, 이는 오직 [DocumentRepositoryImpl]의 생성자를 만족시키기
 * 위해서만 존재한다.
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

    override suspend fun deletePartialPageLayouts(documentId: String) = Unit

    override suspend fun promotePartialLayouts(documentId: String, characterCount: Long) = Unit
}

/**
 * 목차가 챕터 시작뿐 아니라 그 중간을 가리키는 EPUB3 nav 문서 — "Chapter One"은
 * `chapter-1.xhtml#start`를, 그 아래 중첩된 "Scene One"은 `chapter-1.xhtml#scene1`을 대상으로 한다 — 이는
 * [parserEpub3NavFragmentMapsToExactSectionOffset]이 프래그먼트가 섹션 자신의 시작이 아니라 앵커의 실제
 * 문자 오프셋으로 해석됨을 증명하는 데 필요한 픽스처다.
 *
 * @return 인코딩된 EPUB의 바이트.
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
 * 레거시 NCX를 내비게이션에 사용하는 EPUB. 실제로 발견됐던 것과 같은 방식으로 일부러 손상시켰다: 세 챕터 중
 * 첫 번째(`ch1.xhtml`)가 비어 있고, NCX의 `navPoint`들은 "Start"와 "Cover"를 같은 표지 페이지로 향하게
 * 하면서 "Chapter Two"는 빈 챕터를 건너뛰어 바로 `ch3.xhtml`을 가리키게 했다 — 이는
 * [parserMalformedNcxCoverTargetRetargetsToFirstBodySection]이 손상되었으면서 표지라는 제목이 아닌 항목만
 * 재지정됨을 증명하는 데 필요한 픽스처다.
 *
 * @return 인코딩된 EPUB의 바이트.
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

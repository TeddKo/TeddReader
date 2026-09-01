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
 * EPUB 스크래치 복사본 수명 계약에 대한 결정적 회귀 테스트: [DocumentRepositoryImpl.getEmbeddedImages]가
 * 스크래치 EPUB에서 바이트를 추출하는 동안 [DocumentRepositoryImpl.deleteDocument]는 그 스크래치 파일을
 * 삭제할 수 없어야 한다.
 *
 * 이 수정은 [EpubDocumentParser.extractEmbeddedImageBytes] 실행 전체 동안 `epubScratchLock`을 쥐고
 * 있으므로, 동시에 실행되는 [deleteDocument]가 호출하는 [invalidateCaches]는 추출이 끝날 때까지 같은
 * 뮤텍스에서 블록된다. 이 테스트는 [GatedEpubDocumentParser]를 주입해 추출을 스레드 레벨 래치에서
 * 블록시킨 뒤, 그 래치가 풀릴 때까지 삭제가 진행될 수 없음을 관찰함으로써 그 상호 배제를 검증한다.
 *
 * 뮤테이션 검증: 이 수정을 예전 패턴(락 바깥에서 추출)으로 되돌리면
 * [deletionCannotProceedWhileExtractionHoldsLock]이 실패한다. 삭제가 락을 즉시 획득해 추출이 여전히
 * 실행 중인 동안 완료돼 버리기 때문이다.
 */
class EpubScratchLifetimeTest {

    /**
     * `getEmbeddedImages`가 스크래치 파일에서 추출하는 동안 `deleteDocument`가 그 파일을 삭제할 수 없음을
     * 증명한다. 두 작업이 같은 비재진입 뮤텍스를 두고 경합하기 때문이다.
     *
     * 순서:
     * 1. 스크래치 복사본을 미리 만들어 디스크에 존재하게 한다.
     * 2. 추출을 시작한다 — [GatedEpubDocumentParser]가 "시작됨"을 신호로 보낸 뒤 스레드 레벨로 블록된다.
     * 3. 추출이 `epubScratchLock`을 쥐고 있는 동안, 다른 코루틴에서 삭제를 시작한다.
     * 4. 삭제가 넉넉한 시간 안에 완료되지 않음을(뮤텍스에 블록되어 있음을) 단언한다.
     * 5. 스크래치 파일이 디스크에 여전히 존재함을(추출이 실행 중인 동안 삭제되지 않았음을) 검증한다.
     * 6. 추출 래치를 해제한다 — 추출이 끝나고 락이 풀리며 삭제가 진행된다.
     * 7. 두 코루틴 모두 예외 없이 완료됨을 단언한다.
     *
     * 수정이 되돌려지면(락 바깥에서 추출) 4단계가 실패한다: 삭제가 락을 즉시 획득해 완료되고, 5단계에서는
     * 파일이 삭제되어 있는 것을 발견하게 될 것이다.
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
     * `epubScratchLock` 바깥으로 스크래치 복사를 옮긴 것의 두 측면을 증명한다.
     *
     * 성능 측면: `deleteDocument`는 책이 복사되는 *동안*에도 끝까지 실행될 수 있어야 하며, 이것이 바로
     * 복사 중에 락을 쥐지 않는 이유 전부다. 안전성 측면: 그 삭제가 복사 도중에 일어나면, 완료된 복사본이
     * 설치되어서는 안 된다 — `getEmbeddedImages`는 이제 삭제된 문서에 대해 빈 맵을 돌려줘야지, 삭제 이후
     * 되살린 스크래치 복사본에서 이미지를 서빙해서는 안 된다.
     *
     * 두 번째 측면이 바로 무효화 카운터가 존재하는 이유다. 상태만으로는 이 경우를 탐지할 수 없다: 복사가
     * 시작되기 전에 스크래치 슬롯은 비어 있고 삭제 이후에도 다시 비어 있으므로, 그 뒤에 완료되는 복사는
     * 첫 오픈과 완전히 똑같이 보여서 스스로 설치되어 버릴 것이다.
     *
     * 순서:
     * 1. 추출이 시작되고 [CopyGatedFileSource.copyTo] 안에서 블록된다.
     * 2. 복사가 블록되어 있는 동안, 삭제가 실행되며 — 락에 막히지 않고 — 완료됨을 단언한다.
     * 3. 복사가 풀려서 끝난다.
     * 4. 추출은 빈 맵을 내놓아야 하고, 버려진 복사본이 디스크에 남아 있으면 안 된다.
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
 * 외부 래치가 풀릴 때까지 [copyTo] 안에서 호출 스레드를 블록하는 [DocumentFileSource]. "복사가 시작됨"과
 * "복사가 끝남" 사이의 구간을 테스트에서 관찰할 수 있게 한다.
 *
 * @property location 이 소스가 서빙하는 단일 위치.
 * @property bytes 복사가 진행되도록 허용된 뒤 기록되는 EPUB 아카이브 바이트.
 * @property copyStarted [copyTo]에 진입하는 즉시 카운트다운된다.
 * @property proceedWithCopy 어떤 바이트도 기록되기 전에 대기된다. 그래서 테스트가 복사 도중에 동작할 수 있다.
 * @property copiedPath 목적지를 캡처해, 테스트가 파일이 살아남았는지 단언할 수 있게 한다.
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
 * [extractEmbeddedImageBytes] 안에서 외부 래치가 풀릴 때까지 호출 스레드를 블록하는 [EpubDocumentParser]
 * 서브클래스. 느린 ZIP 추출을 시뮬레이션해 뮤텍스 배타 계약을 관찰 가능하게 만든다: 호출자가 이 호출을
 * 코루틴 [Mutex]로 감싸고 있으면, 그 래치가 풀릴 때까지 다른 어떤 코루틴도 같은 뮤텍스를 획득할 수 없다.
 *
 * @property extractionStarted 추출이 시작되는 즉시 카운트다운되어, 호출 코루틴이 이제 락으로 보호되는
 *   구간 안에 있음을 알린다.
 * @property proceedWithExtraction 파서가 반환하기 전에 대기하는 래치 — 테스트는 삭제가 진행될 수 없음을
 *   검증하는 동안 락을 점유 상태로 유지하기 위해 이를 붙잡고 있는다.
 * @property scratchPathDuringExtraction [path] 인자를 캡처해, 추출이 블록된 동안 파일이 여전히 디스크에
 *   존재하는지 테스트가 검증할 수 있게 한다.
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
 * 호출자의 파서와 다른 모든 협력자를 위한 페이크로 [DocumentRepositoryImpl]을 연결한다. 스크래치 락
 * 동작만 검증하며 — 임포트, 페이지네이션, 검색 경로는 no-op으로 스텁된다.
 *
 * @param documentDao 삭제를 지원해야 테스트가 [deleteDocument] 전체 경로를 검증할 수 있다.
 * @param fileSource [epubScratchCopy]에 EPUB 바이트를 서빙한다.
 * @param epubDocumentParser 주입할 파서 — 락 테스트라면 [GatedEpubDocumentParser].
 * @return 스크래치 락 테스트용으로 연결된 저장소.
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
 * [deleteDocument]에서 항목을 제거하는 [DocumentDao]. 테스트가 [invalidateCaches]를 포함한 삭제 전체
 * 경로를 검증할 수 있게 한다.
 *
 * @property documents [lock]으로 보호되는 가변 백업 목록.
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
 * 미리 로드된 바이트를 목적지에 기록하는 [DocumentFileSource]. I/O 게이팅 없음 — 경합은 파일 복사가
 * 아니라 [GatedEpubDocumentParser]를 통해 검증된다.
 *
 * @property location 이 소스가 서빙하는 단일 위치.
 * @property bytes 그 location에 대한 EPUB 아카이브 바이트.
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
 * no-op [SearchIndexDao] — 스크래치 락 테스트는 임포트나 검색 저장소를 전혀 검증하지 않는다.
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
 * no-op [PageLayoutDao] — 스크래치 락 테스트는 저장된 페이지 레이아웃을 전혀 검증하지 않는다.
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
 * `OEBPS/images/pic.png`에 4바이트 이미지 하나를 가진 최소한의 유효한 EPUB. 스크래치 복사본 생성과 ZIP
 * 추출 경로를 검증하는 데 쓰인다.
 *
 * @return 인코딩된 EPUB 바이트.
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

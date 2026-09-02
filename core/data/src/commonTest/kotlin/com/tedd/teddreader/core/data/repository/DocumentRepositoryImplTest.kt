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
 * [DocumentRepositoryImpl]의 동작을 처음부터 끝까지 고정한다. Room을 대신하는 페이크
 * ([FakeDocumentDao], [FakeMultiDocumentDao], [FakeDocumentSearchIndexDao], [FakePageLayoutDao])와
 * 파일 접근을 대신하는 페이크([FakeDocumentFileSource])를 사용하므로, 저장소의 계약을 증명하는 데
 * 실제 데이터베이스나 파일시스템이 전혀 필요하지 않다.
 *
 * 이 스위트가 특히 고정하는 것들: 어떤 포맷을 임포트하면 그것이 저장되고 섹션이 색인된다는 것;
 * 이미 서재에 있는 책을 열면 재임포트 대신 저장된 텍스트와 레이아웃을 재사용한다는 것
 * (AGENTS.md의 "읽던 위치가 유지된다" 불변식이 여기에 의존한다); 저장된 페이지 레이아웃은
 * `characterCount`와 뷰포트/스타일 키가 여전히 일치할 때는 재측정 대신 복원되며, 둘 중 하나라도
 * 일치하지 않는 순간 폐기된다는 것; `pageIndex.total`은 더 많은 섹션이 측정되거나 임포트될수록
 * 커지기만 할 뿐 절대 줄어들지 않고, 첫 섹션이 알려진 이후로는 절대 0을 읽지 않는다는 것
 * ("`pageIndex.total`은 절대 줄어들지 않는다" 불변식); 이미 발행된 페이지는 이후 섹션이
 * 추가되거나 측정되더라도 정확한 텍스트 경계를 그대로 유지한다는 것; 점진적 EPUB 임포트는 처음에는
 * 0단계만 저장하고 나머지는 [DocumentRepositoryImpl.importNextSections]를 통해 건너뛰거나
 * 중복하거나 잃어버리는 일 없이 따라잡는다는 것(시뮬레이션된 프로세스 크래시를 겪더라도); 동시에
 * 진행되는 이어하기/임포트 패스는 서로 경합해 중복 작업을 만드는 대신 각 섹션을 정확히 한 번씩만
 * 측정한다는 것; 그리고 한 번 캐시된 표지는 전체 파일에서 다시 추출되지 않는다는 것.
 *
 * 이 테스트들 중 다수는 실제로 발생했던 특정 버그 때문에 존재한다 — 각 테스트 자신의 KDoc에 어떤
 * 버그인지 적혀 있다.
 */
class DocumentRepositoryImplTest {
    /** 단일 시각 페이지 임포트(이미지)는 정확히 한 페이지로 인식되고 페이지가 나뉘어야 한다. */
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

    /** TXT 문서에는 표지 개념이 없으므로 [DocumentRepositoryImpl.getDocumentCover]는 null을
     * 반환해야 한다. */
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

    /** TXT 문서를 임포트하면 서재 항목을 저장하고 그 (단일) 섹션을 색인해야 한다. */
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
     * 이미 서재에 완전히 올라와 있는 책을 재임포트하는 경우("다른 앱으로 열기"/공유 경로가 다시
     * [DocumentRepositoryImpl.importDocument]에 도달하는 상황) 그 사이에 이뤄진
     * [DocumentMetadata] 수준의 편집을 덮어써서는 안 된다 — 여기서는 즐겨찾기 토글이 두 번째
     * 임포트 이후에도 살아남는지 확인한다.
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
            requireNotNull(repository.getDocument(DocumentId(source.location.sourceUri))).copy(isBookmarked = true),
        )

        repository.importDocument(source, importedAtEpochMillis = 2_000)

        assertEquals(true, documentDao.saved?.isBookmarked)
    }

    /**
     * [DocumentRepositoryImpl.upsertDocument](즐겨찾기 토글 같은 평범한 메타데이터 편집)는 도메인
     * 모델에 없는 `importCompletedAtEpochMillis` 필드를 데이터베이스에 null로 되돌려 써서, 나중의
     * 점진적 임포트 단계가 믿어야 할 타임스탬프를 지워버려서는 안 된다. 바로 아래에서 upsert하는
     * `DocumentEntity`는 `TeddReaderMigration7To8`이 이미 채워 넣은(또는 이후 점진적 임포트로
     * 완료된) 행을 대신한다: 이 평범한 메타데이터 편집이 있기 훨씬 전에 완전히 임포트된 상태다.
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
            requireNotNull(repository.getDocument(DocumentId(location.sourceUri))).copy(isBookmarked = true),
        )

        assertEquals(1_000L, documentDao.saved?.importCompletedAtEpochMillis)
    }

    /** CP949로 인코딩된 한국어 TXT 파일은, 파싱된 문서에서든 색인되는 내용에서든, 대체 문자나
     * 깨진 문자가 아니라 실제 한국어 텍스트로 디코딩되어야 한다. */
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

    /** 인식되지 않는 포맷은 무언가 저장되기 전에 예외를 던져야 한다 — 실제로는 임포트되지 않은
     * 문서에 대해 서재 행도, 검색 색인 항목도 남아 있어서는 안 된다. */
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
     * [DocumentRepositoryImpl.loadReaderDocument]의 TXT 복구 경로: 대체 문자가 포함된 저장된
     * 텍스트(참고: [hasBrokenText])는 [DocumentFileSource]를 통한 원본 바이트 재읽기를 촉발해야
     * 하고, 복구된 텍스트는 메모리상으로만 반환되는 게 아니라 검색 색인에도 다시 기록되어야 한다.
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

    /** [DocumentRepositoryImpl.getReaderDocument]는 검색 색인으로부터 문서의 제목, 포맷, 섹션
     * 텍스트/범위를 실제로 임포트된 내용과 일치하게 충실히 재구성해야 한다. */
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

    /** 손에 바이트가 없는 상태로 CBZ를 임포트할 때는 [DocumentFileSource.readBytes]가 아니라
     * [DocumentFileSource.copyTo]로 스트리밍해야 한다 — 이 경로가 정확히 피하려는 것이 전체 파일을
     * 메모리로 읽어들이는 일이다. */
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
     * 같은 CBZ의 여러 페이지 윈도우를 요청해도 아카이브를 스크래치 사본으로 정확히 한 번만
     * 스트리밍하고 그 위에서 [ComicArchive]를 정확히 한 번만 열어야 한다 — 이것이 바로
     * 스크래치/열린 아카이브 캐시가 존재하는 이유다 — 그러면서도 모든 페이지 윈도우가 여전히
     * 올바른 바이트를 디코딩해야 한다. `copyCount == 1`은 전체 파일 복사 비용이 한 번만 지불됨을,
     * `openArchiveCount == 1`은 ZIP 인덱스(목록 + 자연 정렬)가 한 번만 구축됨을 증명하며,
     * 페이지별 단언들은 이 재사용이 결과를 손상시키지 않았음을 증명한다.
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
     * 다른 CBZ로 전환하면 이전에 보관하던 스크래치 사본과 그 열린 아카이브를 교체해야 한다:
     * 두 번째 문서는 첫 요청 시점에 복사되고(총 `copyCount == 2`) 다시 열리며(총
     * `openArchiveCount == 2`), 그 페이지들은 첫 번째 문서가 아니라 자신의 바이트에서
     * 디코딩된다.
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
     * [DocumentRepositoryImpl.deleteDocument]가 CBZ 캐시를 허문 뒤, 같은 id에 대한 나중 요청은
     * (서재에 다시 추가되었더라도) 이미 삭제된 낡은 것을 그대로 내어주는 대신 스크래치 사본을
     * 다시 구축하고 아카이브를 다시 열어야 한다.
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

    /** [DocumentRepositoryImpl.getPageWindows]는 다른 문서나 기본 문서가 아니라 이 id에 실제로
     * 저장된 문서로부터 페이지를 배치해야 한다 — 첫 페이지의 위치와 인덱스는 책의 실제 시작
     * 지점에 고정되어야 한다. */
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

    /** [reimportPreservesDocumentBookmark]와 동일한 재임포트 보장을, 즐겨찾기 플래그 대신 폴더
     * 소속(`folderId`/`folderName`)에 대해 검증한다. */
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
            requireNotNull(repository.getDocument(DocumentId(source.location.sourceUri))).copy(
                folderId = "folder-1",
                folderName = "Imported",
            ),
        )

        repository.importDocument(source, importedAtEpochMillis = 2_000)

        assertEquals("folder-1", documentDao.saved?.folderId)
        assertEquals("Imported", documentDao.saved?.folderName)
    }

    /**
     * 새로 만든 [DocumentRepositoryImpl] 인스턴스는 메모리 캐시가 전혀 없고 저장된 레이아웃만
     * 있다 — 그러므로 이 인스턴스의 두 번째 [DocumentRepositoryImpl.getPageWindows] 호출이 측정
     * 없이 성공하려면(아래 `poisonBreaker`는 호출되기만 하면 테스트를 실패시킨다) 첫 번째
     * 인스턴스가 측정해 저장한 레이아웃을 실제로 복원해야만 한다.
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

    /** 저장된 레이아웃은 다른 글꼴 크기나 다른 뷰포트로 요청하는 호출자에게 절대 넘겨져서는 안
     * 된다 — [DocumentRepositoryImpl.restorePageWindows]가 정확한 스타일/뷰포트를 키로 사용하는
     * 이유가 바로 이것이며, 이 검증은 그 점을 지킨다; 둘 중 어느 쪽이 어긋나든 대신 새로운
     * 측정으로 넘어가야 한다. */
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
     * 6단계 회귀 방지: 예전 `openDocument`는 [DocumentRepositoryImpl.getPageWindows]에 하드코딩된
     * 추측 뷰포트를 심어 넣었는데, 이는 저장된 행과 거의 절대 일치하지 않았다 — 그래서 이것이
     * 이번 수정이 겨냥하는 실패 케이스다. 뷰포트 `V1`으로 저장된 레이아웃이 있고 페이지
     * 브레이커가 없을 때, null `viewportSize`는 새 추정 패스로 넘어가는 게 아니라 정확히 그 행을
     * 해결해내야 한다. 아래 첫 호출은 이 기기에서 이 책을 예전에 열었을 때와 똑같이 새 인스턴스로
     * 그 `V1` 레이아웃을 측정해 저장한다; 뒤이은 `expected`/`actual` 호출도 둘 다 메모리 캐시가
     * 없는 새 인스턴스를 사용하므로, 저장된 행 자체 말고는 어느 쪽도 답을 낼 수 없다 — `actual`
     * 호출의 null `viewportSize`는 다른 추측으로 측정하는 대신 스스로 `V1`을 해결해내야 한다.
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
     * [getPageWindowsWithNullViewportRestoresTheNewestStoredLayoutForTheStyle]가 증명하는
     * null-`viewportSize` 해결 로직도 두 축 중 어느 쪽이 어긋나든 여전히 거부해야 한다: `fontSizeSp =
     * 20`으로 측정되어 저장된 행은 `fontSizeSp = 24`(다른 레이아웃 키)의 `null` 뷰포트 질의에는
     * 채택되어서는 안 되고, 어떤 뷰포트(`V1`)에 저장된 행 역시 다른 명시적 뷰포트에는 채택되어서는
     * 안 된다 — 둘 다 대신 새로운 측정으로 넘어가야 한다.
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
     * 저장소 계층에서의 회귀 방지: 갓 임포트된 책은 저장된 레이아웃도 브레이커도
     * 아직 없으므로, null `viewportSize`는 빈 목록이 아니라 실제 호출자가 예전에 넘기던 것과 같은
     * 기본 추측값으로 폴백해야 한다(참고: [DefaultViewportSize]) — 그러지 않으면 이 추측을
     * 개선할 유일한 방법인 페이지 측정 자체가 결코 일어나지 않을 것이다.
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
     * 다른 앱이 이미 여기 있는 책을 넘겨주는 경우 — "다른 앱으로 열기", 공유 — 는 매번
     * [DocumentRepositoryImpl.importDocument]에 도달한다. 재임포트는 예전에는 독자가 읽던 중이던
     * 책의 텍스트와 측정된 레이아웃을 버려버렸으므로, 완전히 임포트된 문서의 두 번째 임포트는
     * 재임포트가 아니라 열기여야 한다: 저장된 페이지 레이아웃도 저장된 섹션도 바뀌어서는 안 된다.
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
     * 재파싱 — 파서 버전이 올라갈 때 모든 예전 책이 거치게 되는 그 과정 — 은 책 안의 모든 문자
     * 오프셋을 옮겨버릴 수 있어서, 그 이전에 기록된 레이아웃은 이제 존재하지 않는 페이지들을
     * 가리키게 된다. [DocumentRepositoryImpl.restorePageWindows]의 `characterCount` 검사는
     * 바로 그런 행(아래에서 실제 문서와 동떨어진 `characterCount`로 직접 upsert하는 행)이 마치
     * 여전히 맞는 것처럼 독자에게 넘겨지는 것을 막아준다.
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
     * 엔진의 측정 한도를 넘는 섹션은 현재 열기에서 쓸 만한 추정 페이지들을 내놓지만, 그 시작
     * 지점들이 최종 측정 행으로 살아남아서는 절대 안 된다. 미리 심어둔 행은 이 추정치를 실제
     * 브레이커 결과와 구분하지 못했던 예전 빌드가 기록한 레이아웃을 모사한다; 열기는 그 행을
     * 삭제해야 하고, 대체 추정치는 메모리에만 남아 있어야 한다.
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
     * [getPageWindowsRestoresFromStorageWithoutMeasuringOnColdCache]와 동일한 복원 보장을,
     * 하나가 아니라 다섯 섹션(아래에서 실제 책의 챕터가 저장되는 방식대로 검색 색인에 직접
     * 기록됨)에 걸쳐 검증한다 — 그래야 요청받은 페이지만 만드는 온디맨드 복원이 진짜 흥미로운
     * 케이스가 되며, 섹션이 하나뿐인 우연한 상황이 아니게 된다. `finishPagination`은 백그라운드
     * 이어하기 루프와 똑같은 방식으로 전체 측정을 완료까지 밀어붙인다; 새 인스턴스의 복원은 그
     * 페이지들 하나하나를 바이트 단위로 그대로 재현해야 한다.
     *
     * 아래에서 upsert하는 `DocumentEntity`는 `importDocument`/`persistParsedDocument`를 우회해
     * 직접 기록되며, 점진적으로 임포트되는 중이 아니라 이미 서재에 완전히 올라와 있는 평범한 TXT
     * 문서를 대신한다. `importCompletedAtEpochMillis`는 의도적으로 null이 아니다: null로 두면
     * 정반대를 의미하게 되고, [DocumentRepositoryImpl.getPageWindows]는
     * [DocumentRepositoryImpl.isImportComplete]가 아직 미완료라고 보고하는 문서에 대해서는
     * 레이아웃 저장을 거부한다(그 컬럼에 관한 `DocumentEntity` 자신의 문서 참고). 이 스위트의
     * 뒤이은 여러 테스트도 같은 이유로 동일한 대역을 만든다.
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
     * [encodePageStartsBlob]/[decodePageStartsBlob]의 오프셋당 리틀엔디언 `Int32` 왕복 변환은,
     * 이 파일의 다른 테스트들이 쓰는 작은 값뿐 아니라 큰 오프셋에서도 성립해야 한다 — 아래의
     * 200,000자짜리 `text`는 페이지 시작 지점을 다른 테스트들이 쓰는 작은 숫자를 훌쩍 넘는
     * 영역으로 밀어 넣는데, 이는 실제 수십만 자짜리 책이 측정되는 영역과 같으며, 첫 페이지의
     * 오프셋 0도 포함한다. [DocumentRepositoryImpl.storePageWindows]가 기록하는 블롭만이 새
     * 인스턴스의 복원 질의에 답할 수 있다.
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
     * [DocumentRepositoryImpl.importDocument]는 표지가 있는 EPUB이라면 표지 파일을 기록해야
     * 하고, 그러면 [DocumentRepositoryImpl.getDocumentCover]는 전체 문서를 다시 읽는 대신 그
     * 캐시된 파일을 내어줘야 한다. 임포트는 파일 소스가 아니라 `DocumentImportSource`에서 곧바로
     * 바이트를 읽으므로, 이것이 폴백 경로가 아니라 캐시에 대한 진짜 테스트가 되려면
     * `getDocumentCover`가 호출되기 전에 표지 파일이 이미 존재해야 한다.
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
     * 캐시된 표지 파일이 없는 책 — 아래 `documentDao`는 표지 캐싱이 생기기 전에 임포트된 책을
     * 대신한다: 문서 행과 검색 색인은 존재하지만 표지 파일은 한 번도 기록된 적이 없다, 오늘날
     * 이런 일이 발생하는 유일한 경로다 — 은 첫 [DocumentRepositoryImpl.getDocumentCover] 호출에서
     * 표지 추출로 폴백해야 하고, 두 번째 호출이 원본 파일을 다시 건드리지 않도록 결과를 캐시해야
     * 한다.
     *
     * 이 폴백은 책을 [ByteArray]로 읽어들이는 대신 임시 파일로 스트리밍하므로, 이 테스트는
     * `readCount`가 0으로 유지되는지도 함께 단언한다: 책 전체를 버퍼링하면 이미지 하나를 얻기
     * 위해 그 전체 크기만큼 힙에 부담을 지우게 되는데, 이는 수백 메가바이트짜리 삽화가 많은 책에서
     * 저사양 메모리 기기를 고갈시킬 수 있는 일이다.
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

    /** [DocumentRepositoryImpl.deleteDocument]는 캐시된 표지 파일도 함께 제거해야 한다 — 서재
     * 행만 지우는 것으로는 왜 충분하지 않은지는 `invalidateCaches`/`coverFilePath` 자신의 문서를
     * 참고. */
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

    /** 삭제는 앱 소유 파일을 식별할 수 있는 유일한 행을 지우기 전에 저장된 위치를 스냅샷으로
     * 남겨둬야 한다. */
    @Test
    fun deleteDocumentRemovesItsMaterializedSource() = runTest {
        val location = DocumentLocation(
            sourceUri = "file:///app/documents/materialized.txt",
            displayName = "materialized.txt",
            mimeType = "text/plain",
        )
        val bytes = "materialized".encodeToByteArray()
        val fileSource = FakeDocumentFileSource(location, bytes)
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
        repository.importDocument(DocumentImportSource(location, bytes), importedAtEpochMillis = 1_000)

        repository.deleteDocument(documentId)

        assertEquals(listOf(location), fileSource.deletedMaterializedLocations)
    }

    /** 일괄 삭제는 Room이 선택된 모든 행을 한꺼번에 제거하기 전에 모든 위치를 남겨둬야 한다. */
    @Test
    fun deleteDocumentsRemovesEveryMaterializedSource() = runTest {
        val first = DocumentLocation(
            sourceUri = "file:///app/documents/first.txt",
            displayName = "first.txt",
            mimeType = "text/plain",
        )
        val second = DocumentLocation(
            sourceUri = "file:///app/documents/second.txt",
            displayName = "second.txt",
            mimeType = "text/plain",
        )
        val bytesByLocation = mapOf(
            first.sourceUri to "first".encodeToByteArray(),
            second.sourceUri to "second".encodeToByteArray(),
        )
        val fileSource = MultiLocationDocumentFileSource(bytesByLocation)
        val repository = DocumentRepositoryImpl(
            documentDao = FakeMultiDocumentDao(),
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
        repository.importDocument(DocumentImportSource(first, bytesByLocation.getValue(first.sourceUri)), 1_000)
        repository.importDocument(DocumentImportSource(second, bytesByLocation.getValue(second.sourceUri)), 2_000)

        repository.deleteDocuments(listOf(DocumentId(first.sourceUri), DocumentId(second.sourceUri)))

        assertEquals(setOf(first, second), fileSource.deletedMaterializedLocations.toSet())
    }

    /**
     * [coverFilePath]가 문서 id를 해시한 값은 서로 다른 두 책에 서로 다른 표지 경로를 줘야
     * 하므로, 둘 다 임포트하고 각 표지를 다시 읽었을 때 서로 뒤섞여서는 안 된다. 임포트 경로도
     * 캐시 적중도 여기서는 `readBytes`/`copyTo`를 전혀 호출하지 않으므로(표지는 `importDocument`에
     * 직접 넘긴 바이트에서 나온다), "파일 소스" 하나를 대신하는 페이크 하나로 충분하다 — 이
     * 테스트에서 중요한 건 오직 공유되는 `appPrivateDirectory()`뿐이다.
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
     * 8단계 회귀 방지: 예전에는 열기가 페이지 하나 만들기도 전에 모든 섹션의 blocksJson을
     * `SELECT *`로 가져왔다. 이제 복원은 오직 섹션 0의 것만 가져와야 한다 — 표지 감지가 이를
     * 즉시(eagerly) 필요로 하기 때문이다(참고: `TextPageLayoutEngine.findCoverSection`) — 나머지
     * 네 섹션의 블록은 가져오지 않는다. 이것이 바로 [SectionBlocksCache] 자신의 문서가 지연 복원을
     * 저렴하게 만드는 요인으로 지목하는 사실이다.
     *
     * 아래에서 upsert하는 `DocumentEntity`는 `importDocument`/`persistParsedDocument`를 우회해
     * 직접 기록되며, 이미 서재에 완전히 올라와 있는 평범한 TXT 문서를 대신한다.
     * `importCompletedAtEpochMillis`가 설정된 이유는
     * [getPageWindowsOnDemandRestoreMatchesEagerMeasurementAcrossManySections] 자신의 문서에
     * 나온 것과 같다.
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
     * 진짜 미스(섹션 0만 자동으로 미리 준비하는 복원 이후의 섹션 2)에 대한
     * [DocumentRepositoryImpl.warmSectionBlocks]는 책의 나머지가 아니라 정확히 그 놓친 섹션만
     * 디코딩하고 보고해야 하며, 두 번째 호출에서는 이미 디코딩된 섹션을 다시 가져오지 않아야
     * 한다 — 그때는 새로 디코딩한 것이 0개라고 보고해야 하고, 이 반환값은 워밍이 아무것도 바꾸지
     * 않았을 때 재발행을 건너뛰기 위해 `ReaderViewModel.continueBlockWarmIfIncomplete`가 의존하는
     * 값이다.
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
     * 25개 섹션을 5개씩 제한된 배치로 워밍하는 것 — 이는 모든 섹션을 한 번의 호출로 워밍하던 방식을
     * 대체하는 형태다, 그 방식은 `ReaderViewModel.continueBlockWarmIfIncomplete` 자신의 문서에
     * 언급된 SQLite 변수 개수 제한 문제를 안고 있었다 — 은 각 배치 자신이 요청한 것보다 많은
     * 섹션을 질의해서는 안 되고, 책 전체에 대해 하나가 아니라 배치당 하나의 질의여야 하며, 단일
     * 책 전체 워밍(아래 `allAtOnce`, 예전 호출을 대신함)과 정확히 같은 것을 디코딩해야 한다.
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
     * 나중 섹션을 덧붙이는 점진적 임포트 배치는 완료 전까지 활성 상태인 0단계 섹션-블록 캐시를
     * 떨어뜨려서는 안 된다: 이미 0단계 문서를 캐시했지만 아직 그 섹션 중 하나를 워밍하지 않은
     * 호출자는 배치 이후에도 여전히 같은 캐시 객체로 디코딩해야 한다. 아래 섹션 1은 0단계에서 온
     * 챕터 1이다; 추가된 배치는 챕터 2를 임포트하지만, 그 이후 챕터 1을 워밍하면 완전한 재로드가
     * 다시 구축할 때까지 기다리지 않고 여전히 기존 캐시에서 디코딩해야 한다.
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
     * [warmSectionBlocks]는 이 저장소 인스턴스가 현재 보유한 캐시 객체만 워밍한다.
     * `importDocument -> persistParsedDocument -> invalidateCaches`를 통한 즉각적인 캐시 폐기는
     * 따라서 무언가가 문서를 다시 로드해 그 캐시를 재구축하기 전까지는 다음 워밍을 아무 효과 없는
     * 동작으로 만들어야 한다.
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
     * [getPageWindowsOnDemandRestoreMatchesEagerMeasurementAcrossManySections]와 동일한 바이트
     * 단위 복원 보장을, 평범한 TXT 섹션이 아니라 실제 합성 표지 섹션이 있는 EPUB에 대해
     * 검증한다. 아래 `restoringRepository`는 모든 섹션이 워밍된 뒤에는 "모든 섹션의 블록이 즉시
     * 로드되었던" 상태를 대신한다 — 예전에 `SELECT *`가 페이지 하나 만들기도 전에 모든 행의
     * blocksJson을 넘겨주던 방식과 같다.
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
     * 자신의 섹션 블록이 도착하기 전에 만들어진 페이지는 "아직"으로 렌더링되어야 한다(빈
     * `blocks`, 40자 섹션당 10자짜리 페이지 네 개에 표지가 없는 구성에서 페이지 4는 섹션 1의 첫
     * 페이지다 — 지금은 섹션 0만 자동으로 미리 준비되므로 진짜 미스다), 그런 다음
     * [DocumentRepositoryImpl.warmSectionBlocks]가 그 섹션을 채워 넣으면 `textRange`는 전혀
     * 움직이지 않은 채 블록을 완성해야 한다. 나중에 책의 나머지에 대해 이뤄지는 무관한 백그라운드
     * 채우기는 그것을 다시 건드려서는 안 된다 — 이것이 바로 [SectionBlocksCache] 자신의 문서가
     * "이미 보여진 페이지는 자신의 텍스트와 블록을 유지한다"라고 부르는 보장이다.
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
     * 점진적 EPUB 임포트의 첫 테스트(9단계): [DocumentRepositoryImpl.importEpubPhase0]/
     * [DocumentRepositoryImpl.importNextSections]가 존재하기 전에 먼저 작성되었으며, 변경 전
     * `DocumentRepository` 인터페이스에는 이 테스트가 호출할
     * [DocumentRepositoryImpl.isImportComplete]도 [DocumentRepositoryImpl.importNextSections]도
     * 존재하지 않았으므로 컴파일에 실패해야 했던 테스트다. 이것은 0단계 자체를 고정한다: 충분히
     * 긴 EPUB은 0/1단계의 제한된 선행 읽기만으로는 완료되어서는 안 되고, `characterCount`는
     * 임포트가 완료될 때까지 null로 남아 있어야 하며, 나머지 스파인이 아니라 그 초반 섹션들만
     * 저장되어야 한다.
     *
     * `bytes=null`은 [DocumentRepositoryImpl.importDocument]의 단계적 경로를 실행시키는
     * 요소다: null이 아닌 `bytes` 인자는 "호출자가 이미 모든 것을 가지고 있으니 예전 방식의
     * 단발성 파싱만 하면 된다"로 취급되며, 이 스위트(아래의 모든 점진적 임포트 테스트)는 특히
     * 그렇지 않은 경우 — `fileSource`에서 직접 스트리밍되는 선택된 파일 — 를 테스트한다.
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
     * `characterCount`는 [DocumentRepositoryImpl.importNextSections]의 미완료 배치를 거치는
     * 내내 null로 남아 있어야 하고, 책 전체가 임포트를 마쳤을 때에만 실제 총합이 되어야 한다 —
     * 여기서 왜 `bytes=null`을 쓰는지는
     * [importDocumentForMultiChapterEpubOnlyPersistsPhase0SectionsAndLeavesImportIncomplete] 참고.
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
     * [DocumentRepositoryImpl.importNextSections]를 호출마다 섹션 하나씩 완료까지 진행시키면
     * 책을 끝마쳐야 하고, 같은 바이트를 직접 단발성으로 [EpubDocumentParser.parse]한 것과 동일한
     * 저장된 텍스트/제목을 만들어내야 한다 — 점진적 임포트는 점진적이지 않은 경로에 비해 아무것도
     * 잃거나, 순서를 바꾸거나, 손상시켜서는 안 된다.
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
     * [DocumentRepositoryImpl.finishEpubImport]에 있었던 두 가지 캐시 무효화 순서 버그 중 첫
     * 번째를 재현한다: 예전에는 `documents.importCompletedAtEpochMillis`(
     * [DocumentRepositoryImpl.isImportComplete]가 읽는 값)를 문서 캐시를 무효화하기 몇 문장
     * 전에 찍어버렸는데, 이는 이미 이 책을 열어두고 있던 독자 — 임포트가 여전히 진행 중인
     * 동안 그 [DocumentRepositoryImpl.getReaderDocument] 캐시가 채워져 있던 — 가
     * [DocumentRepositoryImpl.isImportComplete]가 true를 답하는 걸 보면서도
     * [DocumentRepositoryImpl.getReaderDocument]는 여전히 완료 전 문서를 내어주는 창구를
     * 남겼다. 그 문서의 내비게이션은 바로 이 단계가 해결하기 전까지는 항상 비어 있다. 이후로는
     * 그 캐시 항목을 무효화하는 것이 아무것도 없어서, 이렇게 생겨난 빈 목차는 다음 앱 재실행까지
     * 그대로 남았다 — `fix/outline-after-import`가 다른 경로로 해결한 것과 같은, 사용자에게
     * 보이는 증상이다.
     *
     * [FakeDocumentDao.completionStampGate]는 작성자의 완료 스탬프 쓰기를 정확히 버그가
     * 살아있던 지점에 멈춰 세운다: 스탬프가 저장소에 보이게 된 뒤, 그리고
     * [DocumentRepositoryImpl.finishEpubImport]가 캐시를 무효화하러 넘어가기 전. 고쳐진
     * [DocumentRepositoryImpl.finishEpubImport]는 그 스탬프를 아예 쓰기 전에 캐시를
     * 무효화하므로, 쓰기가 게이트에 도달할 즈음에는 캐시가 이미 비워져 있고, 아래의 동시
     * [DocumentRepositoryImpl.getReaderDocument] 호출은 이미 해결된 내비게이션을 손에 쥔 채로
     * 강제로 다시 로드하게 된다 — 이것이 바로 이 테스트가 단언하는 내용이다.
     *
     * 아래의 완료시키는 [importNextSections] 호출은 `count = 30`을 요청하는데, 이는 이
     * 30챕터짜리 책에서 0단계가 남겨두었을 수 있는 모든 섹션을 커버하는 값이므로, 그 한 번의
     * 호출이 임포트를 마치고 [completionStampGate]에 도달하는 배치임이 보장된다.
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
     * 두 번째 캐시 무효화 버그를 재현한다: [DocumentRepositoryImpl.getReaderDocument]는 의도적으로
     * `documentCacheLock` 바깥에서 문서를 로드한다, 그래야 느린 로드 하나가 다른 모든 문서
     * 읽기를 그 뒤로 직렬화시키지 않는다(그 락 자신의 문서 참고), 그런 다음 결과를 발행하기
     * 위해서만 락을 다시 획득한다. 다른 쓰기가 캐시를 무효화하기 전에 시작됐지만 그 무효화가
     * 이미 실행된 이후에야 자신의 발행 단계에 도달하는 로드는, 예전에는 무효화 이전 스냅샷을
     * 그대로 캐시에 다시 써넣어버렸다 — 자신이 걸쳐 있던 무효화를 조용히 되돌려버린 셈이다.
     *
     * [FakeDocumentSearchIndexDao.getDocumentSectionsWithoutBlocksGate]는
     * [DocumentRepositoryImpl.getReaderDocument] 로드를 문서의 행을 읽은 직후(그 문서가 아직
     * 존재하는 동안)이지만 그것을 반환하기 전에 멈춰 세운다. 멈춰 있는 동안
     * [DocumentRepositoryImpl.deleteDocument]가 그 문서를 완전히 제거하는데, 이는 캐시를
     * 무효화해야 한다. 게이트를 풀어주면 멈춰 있던 로드가 끝나면서 이제는 낡아버린 스냅샷을
     * 발행하려 시도한다. 고쳐진 캐시는 로드가 진행되는 동안 무효화가
     * `documentCacheGeneration`을 올렸기 때문에 그 발행을 거부하므로, 아래의 뒤이은
     * [DocumentRepositoryImpl.getReaderDocument] 호출은 — 버그가 있는 캐시라면 무기한 살려두었을
     * 낡은 스냅샷이 아니라 — 그 삭제를 봐야 한다.
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
     * 위 두 번째 것과 거울처럼 대칭되는, 세 번째 캐시 무효화 버그를 재현한다:
     * [DocumentRepositoryImpl.persistParsedDocument]는 문서의 저장된 행들을 다시 쓴 *이후*가
     * 아니라 다시 쓰기 *전에* 문서 캐시를 무효화한다. `documentDao.upsertDocument`는 그 행(과
     * `isImportComplete`)을 즉시 보이게
     * 만들지만, 그 뒤 `searchIndexDao.deleteSearchIndex`는 모든 섹션 행을 비워버리고, 이들은
     * `searchIndexDao.upsertSearchIndex`가 끝날 때까지 다시 기록되지 않는다. 그 사이 창구에서
     * 시작되는 [DocumentRepositoryImpl.getReaderDocument] 로드는 섹션이 0개인 상태를 읽는다;
     * 다시 쓰기 이후의 두 번째 무효화가 없다면 그 찢긴 스냅샷을 캐시에서 다시 지워줄 것이
     * 아무것도 없으므로, 다음 앱 재실행까지 그대로 남을 것이다 — 원래 버그가 독자가 아니라
     * 작성자를 통해 다시 열린 셈이다.
     *
     * [FakeDocumentSearchIndexDao.upsertSearchIndexGate]는 작성자의
     * [DocumentRepositoryImpl.persistParsedDocument] 호출을 `deleteSearchIndex`가 문서의 행을
     * 비운 직후, 그러나 새로 파싱된 행이 다시 기록되기 전에 멈춰 세운다. 이 저장소는
     * `documentFileSource` 없이 만들어졌으므로, 섹션이 0개인 것을 보고
     * [DocumentRepositoryImpl.loadReaderDocument]가 시도했을 TXT 복구는 재파싱 대신 즉시
     * 포기한다(`DocumentRepositoryImpl.repairTxtDocument`는 자신의 파일 소스가 없는 순간 null을
     * 반환한다) — 이는 경합하는 읽기를 두 번째 동시 다시쓰기가 아니라 단일하고 결정적인
     * 로드로 유지해준다. 그 경합하는 [DocumentRepositoryImpl.getReaderDocument] 호출은 작성자가
     * 멈춰 있는 동안 launch가 아니라 직접 이뤄지므로, 작성자가 재개되기 전에 찢기고 빈 스냅샷을
     * 동기적으로 발행한다. 고쳐진 [DocumentRepositoryImpl.persistParsedDocument]는 다시쓰기가
     * 끝난 뒤 캐시를 다시 무효화해 그 잘못 발행된 항목을 지운다; 고치지 않은 것은 그걸 영원히
     * 캐시에 남겨두므로, 작성자가 끝나고 실제 행이 저장소에 들어간 뒤 아래에서 이뤄지는
     * [DocumentRepositoryImpl.getReaderDocument] 호출은 다시 로드하는 대신 여전히 빈 스냅샷을
     * 반환할 것이다.
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
     * "크래시": `documentDao`/`searchIndexDao`에 있는 것 말고는 아무것도 살아남지 않는다 — 아래
     * 첫 두 호출 이후의 모든 호출은 이전 것들의 메모리 상태를 전혀 가지지 않는 완전히 새로운
     * [DocumentRepositoryImpl] 인스턴스를 사용하며, 시뮬레이션된 크래시를 거쳐도 저장된 행만
     * 읽어 책을 올바르게 끝마쳐야 하고, 어떤 섹션도 건너뛰거나 중복해서는 안 된다.
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
     * (표지+챕터1 측정에서 나온, `importDocument`의 0단계가 남기는) 이미 발행된 페이지는
     * [DocumentRepositoryImpl.importNextSections]가 더 많은 섹션을 덧붙이더라도 정확한 경계를
     * 유지해야 한다. 임포트가 끝나지 않은 동안 레이아웃을 쓰는 것은 정확히
     * [DocumentRepositoryImpl.getPageWindows] 자신의 `isImportComplete` 가드가 거부하는
     * 일이므로, 더 많은 섹션이 도착하는 순간 두 번째의 순수한 `getPageWindows` 호출만으로는
     * 성장이 보이지 않는다 — 이 스위트의 다른 곳에서 `finishPagination`이 대신하는 것과 같은
     * 이어하기 과정이 필요하다. `getPageWindows`로 다시 씨앗을 뿌리면 먼저 이제 완료된 책을
     * 대상으로 앵커 섹션을 측정하고, 그다음 `finishPagination`이 나머지를 훑는다 —
     * `openDocument` + `continuePaginationIfIncomplete`가 거치는 것과 같은 두 단계다.
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
     * 범위가 제한된 첫 측정은 미완료 세션을 저장해서는 안 된다. 백그라운드 이어하기가 현재
     * 접두부를 측정하고 나면 점진적 임포트는 부분 행을 저장하고 덧붙일 수 있다; 임포트와 페이지
     * 측정이 둘 다 끝난 뒤에는 그 행이 승격된 최종 레이아웃으로 계속 사용 가능해야 한다.
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
     * [DocumentRepositoryImpl.appendMeasuredPageStarts]가 찾았지만 확장할 수 없는 저장된
     * 레이아웃은 삭제되어야 하며, 나중에 [DocumentRepositoryImpl.restorePageWindows]가 걸려
     * 넘어지도록 낡은 채로 남겨져서는 안 된다. 아래에서 upsert하는 행은 부분 접두부 레이아웃이
     * 생기기 전 앱 버전이 남긴 것을 대신한다; 그것을 삭제한 뒤 저장소는 현재 접두부를 측정해
     * 버전이 맞는 부분 대체본을 저장하므로, 다음 배치는 그 접두부를 다시 구축하지 않고도 덧붙일
     * 수 있다.
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
     * 10단계: 섹션 상대 블록 저장. 각 섹션의 저장된 `blocksJson` — 이를 쓰는 두 호출 지점,
     * [DocumentRepositoryImpl.persistParsedDocument](0단계의 표지와 챕터 1)와
     * [DocumentRepositoryImpl.importNextSections](0이 아닌 절대 오프셋에 있는, 굵은 글씨 스팬이
     * 있는 챕터 2) 모두로부터 — 는 섹션 자신의 시작을 다시 더해 재절대화하면 원래의 절대 블록
     * *및* 스팬 범위로 되돌아가야 한다; 두 호출 지점 모두 쓰기 전에 리베이스하므로, 여기서는
     * 둘 다 검증되어야 한다. `bytes=null`은 0단계와 그 이후 점진적 임포트 루프를 모두 구동시켜
     * 두 호출 지점이 실제로 실행되게 한다.
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
     * 11단계: 아직 한 번도 측정된 적 없는 유형에 대한 점진적 페이지 측정. 저장된 레이아웃이
     * 없는 상태로 열면 (`anchorOffset`을 통해) 이어할 섹션을 먼저 측정해야 하고, 책 전체가
     * 아니라 첫 뒤로 넘기기와 초기 앞으로 넘기기 예산에 필요한 제한된 국소 이웃만 측정해야
     * 한다 — 이는 [DocumentRepositoryImpl.getPageWindows] 자신의 문서가 설명하는 6.4초/13.0초
     * 측정 비용이다. 각 픽스처 섹션의 텍스트는 서로 구별되므로("aaaa...", "bbbb...", ...),
     * 브레이커 자신의 인자(아래 `countingBreaker`)는 몇 번 호출되었는지뿐 아니라 어떤 섹션에
     * 대해 호출되었는지도 증명한다.
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
     * [DocumentRepositoryImpl.continuePagination]을 통해(백그라운드 이어하기 루프와 똑같은
     * 방식으로 완료까지 진행시키며) 한 번에 한 섹션씩 측정하면, 표지 섹션이 있는 책에 대해
     * 기준 답안 — [TextPageLayoutEngine.paginate]를 직접 한 번 호출해 모든 섹션을 한 패스로
     * 배치한 것, 증분 페이지 측정이 생기기 전 `getPageWindows`가 하던 방식과 같다 — 과 바이트
     * 단위로 동일한 페이지들을 만들어내야 한다.
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
        val document = requireNotNull(referenceRepository.getReaderDocument(documentId))
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
     * [DocumentRepositoryImpl.restorePageWindows]의 엄격한 오름차순 검사는 페이지 시작
     * 지점이 도중에 뒤로 걷는 저장된 행을 — 그 `characterCount`가 여전히 일치하고 깔끔하게
     * 디코딩되더라도 — 폐기하고 대신 새로 측정해야 한다. 아래에서 upsert하는 행
     * (`longArrayOf(0L, 10L, 20L, 10L, 20L)`)은 같은 섹션을 두 번 덧붙인 작성자 버그가 남겼을
     * 법한 것이다.
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
     * [DocumentRepositoryImpl.paginationContinuationLock]가 존재하는 이유: 스타일 변경은
     * `updateStyle`에서 이어하기 패스를 하나 시작하고, 새 스타일에 대한 패널의 첫 브레이커
     * 보고에서 또 하나를 시작하므로, 실제로 둘이 동시에 실행된다(참고:
     * `ReaderViewModel.refreshPaginationCompleteness`). 락이 없으면 예전에는 둘 다 같은
     * `lowPosition`을 읽고, 같은 섹션을 측정하고, 그것을 두 번 덧붙여서, 완료된 패스가 책 페이지
     * 수의 최대 두 배를 들고(그리고 저장하고) 있게 되었다. 아래의 네 개의 동시
     * [DocumentRepositoryImpl.continuePagination] 실행자는 그래도 각 섹션을 정확히 한 번씩만
     * 측정해서, 전체 문서 기준 패스와 일치해야 한다.
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
     * 저장소 계층에서의 AGENTS.md "문서가 열려 있는 동안 `pageIndex.total`은 절대 줄어들지
     * 않는다" 불변식: 첫 섹션이 측정되고 나면 `pageIndex.total`은 절대 0을 읽어서는 안 되고,
     * [DocumentRepositoryImpl.continuePagination]이 더 많은 섹션을 측정하는 동안에도 절대
     * 줄어들어서는 안 된다.
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
     * 책의 나머지가 점진적으로 측정되고 나서도 열 때 보여준 페이지는 경계가 바뀌지 않은 채
     * 여전히 존재해야 한다. 중간 섹션으로 이어하기(`anchorOffset = sections[2].startOffset`)는
     * [DocumentRepositoryImpl.continuePagination]이 확장하는 두 방향을 모두 검증한다: 섹션
     * 0-1은 뒤로 측정되고, 섹션 3-4는 앞으로 측정되며, 이미 보여진 이 한 페이지를 둘러싼다.
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
     * [DocumentRepositoryImpl.loadReaderDocument]의 EPUB 복구 경로를, 여기서는 오직
     * `parserVersion`만으로 게이팅해 검증한다(`searchIndexDao` 픽스처의 비어 있지 않은
     * `navigationJson`은 `loadReaderDocument`가 확인하는 또 다른 게이트가 발동하지 않게 막아주므로,
     * parserVersion 게이트만 테스트 대상이 된다). `fileSource.copyCount`가 정확히 1에 도달하고
     * `readCount`가 0으로 유지되는 것이 복구가 단계적 임포트 경로를 타서 책 전체를 메모리로
     * 읽어들이는 대신 파일 사본 하나에서 스트리밍한다는 것을 증명한다 — 전체 파일 읽기가 없다는
     * 사실 자체가 단언할 가치가 있는 것이다. 복구가 백그라운드에 남긴 것은 무엇이든 갓 임포트한
     * 것의 나머지가 끝나는 방식대로 끝나야 하며, 처음 보여준 챕터뿐 아니라 책 전체가 현재
     * 버전이 되어야 한다 — 그리고 두 번째 열기(새 [DocumentRepositoryImpl] 인스턴스이므로 메모리
     * 문서 캐시가 아니라 복구가 실제로 기록한 것만이 설명할 수 있다)는 다시 복구를 해서는 안
     * 된다.
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
     * 점진적 임포트가 여전히 진행 중인 동안 도착하는 실제 브레이커는 기존 접두부 세션을 한 번
     * 끝내고 이후의 모든 섹션을 한 번씩 덧붙여야 한다. 승격된 행은 그런 다음 독립적인 전체 문서
     * 측정과 같은 페이지 범위를 복원해야 한다; 섹션 텍스트로 세는 방식은 접두부를 다시
     * 측정하거나 완료 시점의 전체 패스로 폴백하는 어느 쪽이든 이 테스트를 실패시킨다.
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
     * 스키마 버전 9 이전에 중단된 문서는 접두부 섹션이 이미 존재함에도 누산기가 null이다. 그
     * 문서의 첫 재개 배치는 0부터 시작하는 대신 그 접두부를 한 번 재구성해야 한다, 그러지
     * 않으면 완료 시 업그레이드 이후 접미부에 대한 카운트만 발행하게 된다.
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
     * ReaderViewModel이 한 번의 읽기 세션에서 점진적으로 임포트되는 EPUB을 거치게 만드는
     * 인터리빙을 정확히 재현한다: openDocument 자신의 두 호출(아직 브레이커 없음, 그다음 패널의
     * 첫 실제 보고), 매 배치 후 페이지를 다시 로드하는 continueImportIfIncomplete, 그다음
     * 측정을 마친 섹션마다 페이지를 다시 로드하는 continuePaginationIfIncomplete. 그 라이브
     * 세션 동안 독자에게 실제로 보여진 페이지 수(마지막 getPageWindows 호출이 무엇을
     * 반환했든)는, 세션이 끝난 뒤 완전히 새로운 저장소 인스턴스 — 같은 저장된 행들, 메모리
     * 캐시는 하나도 없는 — 가 같은 스타일과 뷰포트에 대해 복원하는 것과 같아야 한다. 기기가
     * 라이브 상태에서는 어떤 숫자를 보여주다가 강제 종료 후 재열기하면 더 큰 다른 숫자를
     * 보여준다면 이 테스트가 실패한 것이다.
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
     * ReaderViewModel.refreshPaginationCompleteness는 isPaginationComplete가 false이기만 하면
     * continuePaginationIfIncomplete를 시작한다 — 먼저 isImportComplete를 기다리지 않는다(자신의
     * 문서를 참고, 거기서는 updatePageBreaker의 중복 제거만 이야기하지 임포트는 언급하지
     * 않는다). continueImportIfIncomplete가 여전히 실행 중인 동안 패널이 자신의 브레이커를 두
     * 번째로 보고하면(합법적이고 중복 제거되지 않은 보고다 — 중복 제거 키는 measuredSizePx인데,
     * 라이브 레이아웃 패스가 픽셀 하나로 쉽게 흔들 수 있다) 따라서 같은 페이지 측정/임포트
     * 기계장치의 *두 번째*, 동시 구동자가 시작될 것이다: continuePagination이
     * [PaginationSession]을 섹션 단위로 확장하는 동안 importNextSections는 여전히 매 배치마다
     * invalidateDocumentCache(같은 paginationSession을 null로 만드는)와
     * appendMeasuredPageStarts를 호출하고 있다 — 그리고 어느 루프도 서로에 대해 락을 쥐고
     * 있지 않다. 이 회귀 테스트는 정확히 그 인터리빙을 구동하고, 결과를 독립적인 전체 문서
     * 기준 패스와 비교한다: 두 루프가 어떤 순서로 인터리빙되든 최종적으로 저장된 것은 책의
     * 측정 하나여야 하며, 어느 섹션의 중복도 그 일부도 아니어야 한다. 아래의 페이지 측정 루프는
     * 임포트가 움직임을 멈춘 뒤에만 물러날 수 있는데, 이는 지금 isPaginationComplete가
     * ViewModel 자신의 구동자에게 보장하는 것과 같다 — 임포트 도중에 물러나는 것이 허용된
     * 루프는 마지막으로 생성된 세션을 훑지 않은 채로, 총합을 섹션 하나에 고정된 채로 남길
     * 것이다.
     *
     * 아래 `drainPagination`은 `continuePaginationIfIncomplete` 자신의 루프를 대신한다: 방금
     * 세션을 null로 만든 배치는 잠깐 측정할 것이 없는 상태로 남으므로, 스핀하는 대신
     * yield한다 — ViewModel은 배치마다 취소되고 재시작됨으로써(참고: `continueImportIfIncomplete`)
     * 이를 공짜로 얻는다. 두 개의 동시 launch 이후에 한 번 더 호출되는데, 이는 임포트가 끝난
     * 뒤 이어하기를 재시작하는 임포트 자신의 마지막 동작을 대신한다(참고:
     * `ReaderViewModel.continueImportIfIncomplete`가 완료 배치에서 호출하는
     * `refreshPaginationCompleteness(isImportComplete = true)`) — 왜냐하면 동시 구동자는 두
     * 플래그가 모두 true를 읽는 순간 물러나는 것이 허용되는데, 그 순간이 임포트의 마지막
     * 재로드가 그 밖의 아무것도 훑지 않을 단일 섹션 세션을 하나 더 만들기 직전일 수 있기
     * 때문이다. 진짜 정답 기준 패스는 먼저 `warmSectionBlocks`를 필요로 한다:
     * `ReaderDocument.blocks`는 [SectionBlocksCache] 위의 `LazyFlattenedBlocks`인데, 아직
     * 아무도 미리 준비하지 않은 섹션에 대해서는 비어 있는 답을 내놓으므로, 워밍되지 않은
     * 기준은 표지 블록을 전혀 찾지 못한 채 조용히 실제 코드 자신의 항상-미리준비되는 경로와
     * 어긋나게 될 것이다.
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
        val referenceDocument = requireNotNull(referenceRepository.getReaderDocument(documentId))
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
     * 페이지 측정 세션만으로 답한 isPaginationComplete는, 실행 중인 임포트가 방금 그 세션을
     * null로 만든 모든 순간에 대해 "완료"라고 말했었다(참고: invalidateDocumentCache), 그리고
     * 모든 호출자는 이어하기를 계속 실행할지 결정하기 위해 그것을 확인한다 — 그래서 그 답은
     * 그 밑에서 책이 여전히 파싱되는 동안 페이지 수를 키우는 유일한 것을 물러나게 만들어서,
     * 총합을 마지막 재로드가 측정한 어떤 단일 섹션에 고정된 채로 남겼다(참고:
     * ReaderViewModel.refreshPaginationCompleteness, 이는 이 값이 true를 읽으면 아무것도
     * 시작하지 않는다). 이 테스트는 0단계만 임포트한다 — 열기에 충분할 만큼만이고 그 이상은
     * 아니다, 정확히 아직 페이지 측정 세션이 존재하지 않는 창구다 — 그리고
     * [DocumentRepositoryImpl.isPaginationComplete]가 여전히 false를 읽는지 확인한다.
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
 * 항상 같은 [bytes]를 돌려주면서, 각 메서드가 실제로 몇 번 호출되었는지 세는
 * [DocumentFileSource] — 이 파일의 대부분의 테스트가 전체 파일 읽기가 발생했는지 아닌지를
 * 증명하기 위해 단언하는 카운트다.
 *
 * @property expectedLocation 모든 호출이 사용해야 하는 위치; 일치하지 않으면 잘못된 데이터로
 *   조용히 답하는 대신 [readBytes]/[copyTo] 내부의 `assertEquals`를 통해 테스트가 실패한다.
 * @property bytes [readBytes]에서 돌려주고 [copyTo]에 쓸 바이트.
 */
private class FakeDocumentFileSource(
    private val expectedLocation: DocumentLocation,
    private val bytes: ByteArray,
) : DocumentFileSource {
    /** [readBytes]가 호출된 횟수. */
    var readCount: Int = 0

    /** [copyTo]가 호출된 횟수. */
    var copyCount: Int = 0

    /** 서재 행 삭제 이후 저장소가 제거를 요청한 위치들. */
    val deletedMaterializedLocations = mutableListOf<DocumentLocation>()

    /**
     * 페이크 인스턴스마다 고유하므로 한 테스트의 캐시된 표지 파일이 다음 테스트에 절대 남아
     * 있을 수 없다 — 실제 기기의 표지 디렉터리는 공유되는 한 곳이지만, 테스트의 것은 그래서는
     * 안 된다.
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

    /** 프로덕션 파일 소스라면 앱 소유 저장소에서 제거했을 위치를 기록한다. */
    override suspend fun deleteMaterialized(location: DocumentLocation) {
        assertEquals(expectedLocation, location)
        deletedMaterializedLocations += location
    }

    override fun appPrivateDirectory(): Path = privateDirectory
}

/**
 * 아카이브가 몇 번 열렸는지 세는 [ComicBookDocumentParser] — 테스트가 CBZ 스크래치/열린
 * 아카이브 캐시가 문서당 정확히 한 번만 ZIP 인덱스(목록 + 자연 정렬)를 구축하고 이후의
 * 모든 페이지/표지 요청에서 그것을 재사용한다는 것을 증명할 수 있게 해준다.
 */
private class CountingComicBookDocumentParser : ComicBookDocumentParser() {
    /** [openArchive]가 호출된 횟수 — 캐시가 연 서로 다른 스크래치 사본마다 한 번씩. */
    var openArchiveCount: Int = 0

    override fun openArchive(path: Path): ComicArchive {
        openArchiveCount += 1
        return super.openArchive(path)
    }
}

/**
 * 위치별 바이트 맵을 뒷받침하는 [DocumentFileSource] — 두 CBZ 사이를 전환하는 테스트가 각
 * 문서 자신의 바이트를 받으면서도 둘에 걸친 총 [copyTo] 호출 수를 계속 셀 수 있게 해준다 —
 * 이 카운트가 문서 전환이 이전 스크래치를 재사용하는 게 아니라 새 것을 새로 복사한다는 것을
 * 증명한다.
 *
 * @property bytesByLocation 각 문서의 바이트, `sourceUri`를 키로 사용.
 */
private class MultiLocationDocumentFileSource(
    private val bytesByLocation: Map<String, ByteArray>,
) : DocumentFileSource {
    /** 모든 위치에 걸쳐 [readBytes]가 호출된 횟수. */
    var readCount: Int = 0

    /** 모든 위치에 걸쳐 [copyTo]가 호출된 횟수. */
    var copyCount: Int = 0

    /** 일괄 삭제 이후 저장소가 제거를 요청한 위치들. */
    val deletedMaterializedLocations = mutableListOf<DocumentLocation>()

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

    /** 프로덕션 파일 소스라면 앱 소유 저장소에서 제거했을 각 위치를 기록한다. */
    override suspend fun deleteMaterialized(location: DocumentLocation) {
        deletedMaterializedLocations += location
    }

    override fun appPrivateDirectory(): Path = privateDirectory
}

/**
 * [entries]를 순서대로 그대로 담고 있는 ZIP 아카이브(CBZ 파일의 형태).
 *
 * @param entries 각 항목의 아카이브 경로와 그 원시 바이트의 쌍.
 * @return 인코딩된 ZIP 아카이브의 바이트.
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
 * 표지가 오직 매니페스트의 `cover-image` 속성만으로 선언되는 최소한의 EPUB — 전용 cover.xhtml
 * 페이지는 없다 — 그래서 표지 바이트는 독자가 그것을 섹션으로 방문하는지 여부와 무관하게
 * 존재한다.
 *
 * @param coverBytes 표지 이미지로 저장할 바이트.
 * @return 인코딩된 EPUB의 바이트: 표지 이미지 하나와 챕터 하나.
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
 * 매니페스트로 선언된 표지 뒤에 [chapterCount]개의 평범한 챕터를 두는, [sampleEpubBytesWithCover]와
 * 같은 형태지만 그중 하나를 건드리는 지연 복원이 나머지를 건드려서는 안 될 만큼 충분한
 * 섹션을 갖는다 — 표지는 항상 섹션 0으로 합성되고(참고:
 * `EpubDocumentParser.parseWithCover`), 챕터들은 섹션 1..[chapterCount]가 된다.
 *
 * @param chapterCount 생성할 챕터 수.
 * @return 인코딩된 EPUB의 바이트.
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
 * 표지 + 두 챕터, 챕터 2가 `<b>` 스팬을 가지고 있다 — 절대 시작이 0이 아닌 섹션(표지와 달리)에서
 * 블록 자신의 범위뿐 아니라 그 스팬들도 섹션 상대 저장을 올바르게 왕복한다는 것을 증명하기에
 * 충분하다(참고:
 * [everySectionsBlocksRoundTripToTheirOriginalAbsoluteRangesAcrossPhase0AndProgressiveImport]).
 *
 * @return 인코딩된 EPUB의 바이트.
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
 * 점진적 페이지 측정 패스를 시작한 것과 같은 인스턴스에서
 * [DocumentRepositoryImpl.continuePagination]을 완료까지 진행시킨다 — 점진적 *임포트* 테스트가
 * `isImportComplete`/`importNextSections`로 이미 사용하는 것과 같은 관용구다 — 그런 다음 완전히
 * 측정된 페이지들을 돌려준다. 마지막 [DocumentRepositoryImpl.getPageWindows] 호출은 재측정이
 * 아니라 캐시 적중이다(continuePagination이 이미 getPageWindows가 읽는 것과 같은 메모리
 * 캐시에 완성된 목록을 써넣었다).
 *
 * @receiver [documentId]/[style]/[viewportSize]에 대해 진행 중인 페이지 측정 세션을 가진 저장소.
 * @param documentId 페이지 측정을 끝마칠 문서.
 * @param style 진행 중인 세션이 일치해야 하는 스타일.
 * @param viewportSize 진행 중인 세션이 일치해야 하는 뷰포트.
 * @param pageBreaker 측정을 끝마치는 데 쓸 실제 페이지 나누기 측정.
 * @return 이제 이 문서에 대해 알려진 모든 페이지 윈도우.
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
 * 검색 색인에 직접 기록된 다섯 개의 평범한 TXT 섹션, 각각 자신의 텍스트에 걸친 실제
 * [ReaderBlockKind.PARAGRAPH] 블록 하나를 가지고 있다 — 실제 blocksJson과 함께, 진짜 온디맨드
 * 가져오기와 즉시 로드된 것을 구별하기에 충분한 섹션 수다.
 *
 * 블록은 섹션 자신의 시작을 기준으로 상대적으로 저장되며(범위 0..text.length),
 * `persistParsedDocument`/`importNextSections`가 지금 실제로 쓰는 것과 일치한다 — "이미 저장소에
 * 있는" 것을 대신하는 픽스처는 실제 작성자와 일치해야 하며 그러지 않으면 디코딩이 블록을 잘못된
 * 오프셋에서 읽는다.
 *
 * @param documentId 이 섹션들이 속한 문서.
 * @return upsert할 준비가 된 다섯 섹션([FakeDocumentSearchIndexDao]에 upsert).
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
 * [fiveTxtSectionsWithBlocks]와 비슷하지만, 테스트가 필요로 하는 만큼의 섹션 수에 대응한다 —
 * 워밍을 하나 이상의 배치에 걸쳐 검증할 때 사용한다.
 *
 * @param documentId 이 섹션들이 속한 문서.
 * @param count 생성할 섹션 수.
 * @return upsert할 준비가 된 생성된 섹션들([FakeDocumentSearchIndexDao]에 upsert).
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
 * 한 번에 오직 하나의 문서만 의도적으로 모델링하는 [DocumentDao]: [saved]는 맵이 아니라 단일
 * 슬롯이므로, 두 번째 문서를 upsert하면 첫 번째를 조용히 대체한다. 이는 정확히 책 하나만
 * 임포트하는 이 파일 대부분의 테스트에 맞는 형태지만, 두 책을 임포트하고 둘 다 여전히
 * 해석되어야 하는 테스트는 이 페이크를 쓸 수 없다는 뜻이기도 하다 — 그런 경우는
 * [FakeMultiDocumentDao] 참고.
 */
private class FakeDocumentDao : DocumentDao {
    /** 현재 "저장된" 그 하나의 문서, 또는 [deleteDocument]가 제거한 뒤에는 null. */
    var saved: DocumentEntity? = null

    /**
     * [upsertDocument]가 기다리지만, 오직 처음으로
     * [DocumentEntity.importCompletedAtEpochMillis]를 찍는 특정 쓰기에 대해서만 그렇다 —
     * [DocumentRepositoryImpl.finishEpubImport]/[DocumentRepositoryImpl.finishNonProgressiveEpubImport]가
     * 만드는 쓰기다. 그 쓰기가 [saved]에서 보이게 된 직후에 멈춰 세우므로, 테스트는 호출자의
     * 다음 문장이 실행되기 전 그 창구에서 [DocumentRepositoryImpl.isImportComplete]와
     * [DocumentRepositoryImpl.getReaderDocument]가 무엇을 답하는지 단언할 수 있다(참고:
     * [DocumentRepositoryImplTest.aReaderCaughtBetweenTheCompletionStampAndTheCacheInvalidationMustNotSeeStaleNavigation]).
     * null(기본값)은 모든 쓰기가 멈추지 않고 진행됨을 의미한다.
     */
    var completionStampGate: CompletableDeferred<Unit>? = null

    /**
     * [upsertDocument]가 [completionStampGate]에 도달하는 순간 완료되므로, 테스트는 쓰기가
     * 게이트에 도달하는 데 얼마나 걸릴지 추측하는 대신 이것을 기다릴 수 있다.
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
 * [FakeDocumentDao]와 달리, 지금까지 upsert된 모든 문서를 보관한다 — 테스트가 두 권 이상의
 * 책을 임포트하고 이후 둘 다 여전히 해석되어야 할 때만 필요하다.
 */
private class FakeMultiDocumentDao : DocumentDao {
    /** 지금까지 upsert된 모든 문서, id를 키로 사용. */
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
 * [entries]의 평범한 리스트를 뒷받침으로 사용하는 메모리 내 [SearchIndexDao]이며, Room의 실제
 * 쿼리가 적용할 컬럼별 프로젝션은 없다 — 모든 오버라이드는 [entries]를 직접 필터링하거나
 * 매핑하는데, 이것이 [getDocumentSectionsWithoutBlocks]와 [getSectionBlocksJson]이 같은 행에
 * 둘 다 저장되어 있음에도 불구하고 섹션 메타데이터와 블록 JSON 사이의 실제 분리에 충실하게
 * 남아 있게 해준다.
 */
private class FakeDocumentSearchIndexDao : SearchIndexDao {
    /** 모든 문서에 걸쳐 지금까지 upsert된 모든 섹션 — 호출마다 `documentId`로 필터링됨. */
    val entries = mutableListOf<SearchIndexEntity>()

    /**
     * [getSectionBlocksJson]의 각 호출의 `sectionIndexes` 인자를 호출 순서 그대로 기록한다,
     * 그래서 테스트는 가져오기가 정확히 어떤 섹션을 건드렸는지 단언할 수 있다 — "가져오기
     * 횟수"만으로는 잘못된 섹션을 요청한 호출을 놓칠 것이다.
     */
    val blocksJsonQueries = mutableListOf<List<Int>>()

    /**
     * [getDocumentSectionsWithoutBlocks]가 이미 [entries]의 스냅샷을 뜬 뒤에 기다리므로, 반환
     * 자체는 지연되더라도 결국 반환하는 값은 호출 시점에 저장되어 있던 것이다 — 다시쓰기 이전
     * 행을 읽었지만 동시 작성자가 문서를 다시 쓸 때 아직 자신의 캐시 발행 단계에 도달하지
     * 않은 [DocumentRepositoryImpl.getReaderDocument] 로드를 모델링한다(참고:
     * [DocumentRepositoryImplTest.aLoadThatStraddlesAnInvalidationMustNotLeaveItsStaleSnapshotCached]).
     * null(기본값)은 모든 호출이 즉시 반환됨을 의미한다.
     */
    var getDocumentSectionsWithoutBlocksGate: CompletableDeferred<Unit>? = null

    /** [getDocumentSectionsWithoutBlocks]가 [getDocumentSectionsWithoutBlocksGate]에 도달하는 순간 완료된다. */
    var getDocumentSectionsWithoutBlocksReached: CompletableDeferred<Unit>? = null

    /**
     * [upsertSearchIndex]가 자신의 행을 [entries]에 쓰기 전에 기다린다,
     * `DocumentRepositoryImpl.persistParsedDocument`의 쓰기 창구를 모델링한다: 이 호출이
     * 이뤄질 즈음에는 `deleteSearchIndex`가 이미 그 문서의 모든 행을 비워버렸고, 이 게이트가
     * 풀릴 때까지는 새 행 중 어느 것도 보이지 않는다(참고:
     * [DocumentRepositoryImplTest.aLoadRacingPersistParsedDocumentsRewriteMustNotLeaveTheCacheHoldingTheTornSnapshot]).
     * null(기본값)은 모든 호출이 즉시 기록됨을 의미한다.
     */
    var upsertSearchIndexGate: CompletableDeferred<Unit>? = null

    /** [upsertSearchIndex]가 기록하기 전, [upsertSearchIndexGate]에 도달하는 순간 완료된다. */
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
 * [SearchIndexEntity]를 [SearchIndexSectionEntry]로 투영한다 — `blocksJson`을 제외한 모든
 * 컬럼 — 실제 `SearchIndexDao.getDocumentSectionsWithoutBlocks` 쿼리가 선택하는 것과 일치한다.
 *
 * @receiver 투영할 저장된 엔티티.
 * @return 그 엔티티의 블록이 아닌 컬럼들.
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
 * Room의 실제 고유 인덱스가 강제하는 것과 같은 (문서, 스타일, 뷰포트) 키로 upsert하는 메모리 내
 * [PageLayoutDao](참고: [hasSameKeyAs]), 그래서 테스트는 실제 upsert가 무엇을 대체하거나
 * 유지했을지 단언하기 위해 [stored]를 직접 검사할 수 있다.
 */
private class FakePageLayoutDao : PageLayoutDao {
    /** 현재 "저장된" 모든 페이지 레이아웃 행, (문서, 스타일, 뷰포트) 키당 최대 하나. */
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
     * 이 행과 [other]가 같은 (문서, 스타일, 뷰포트) 정체성을 공유하는지 여부 —
     * [upsertPageLayout]이 대체 기준으로 삼는 키이며, 이 테이블에 대한 Room의 실제 고유
     * 인덱스와 일치한다.
     *
     * @receiver 비교할 한 행.
     * @param other 비교 대상이 되는 다른 행.
     * @return 모든 키 컬럼이 일치하면 true.
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

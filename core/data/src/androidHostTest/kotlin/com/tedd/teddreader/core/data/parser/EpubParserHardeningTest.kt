package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.TextRange
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.IOException
import okio.Path
import okio.Sink
import okio.Source
import okio.buffer
import okio.openZip

/**
 * 신뢰할 수 없는 EPUB 아카이브가 텍스트, fallback 챕터, CSS 캐시, 이미지 배치, 임시 파일에
 * 무제한 메모리나 디스크 수명을 만들지 못하는 파서 경계를 검증한다.
 */
class EpubParserHardeningTest {
    /** 압축률이 높은 단일 XHTML이 8 MiB 경계를 넘으면 예외 없이 건너뛰어져 점진적 가져오기가 다음 섹션으로 진행한다. */
    @Test
    fun oversizedSpineEntryIsSkippedWithoutThrowing() {
        val path = writeTemporaryEpub(
            entries = mapOf(
                "META-INF/container.xml" to containerXml().encodeToByteArray(),
                "OEBPS/content.opf" to minimalOpf("chapter.xhtml").encodeToByteArray(),
                "OEBPS/chapter.xhtml" to ByteArray(8 * 1024 * 1024 + 1) { 'a'.code.toByte() },
            ),
        )

        try {
            val container = FileSystem.SYSTEM.openZip(path).let { zip -> openEpubImportContainer(zip, "Oversized") }
            assertNotNull(container)
            assertNull(parseEpubSpineItem(container, 0, 0, 0L))
            EpubDocumentParser().parse(DocumentId("oversized"), "Oversized", path)
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    /** 8 MiB를 넘는 연결 CSS는 무시되고 챕터는 스타일 없이 정상 파싱된다. */
    @Test
    fun oversizedLinkedCssIsIgnoredInsteadOfFailingChapter() {
        val path = writeTemporaryEpub(
            entries = mapOf(
                "META-INF/container.xml" to containerXml().encodeToByteArray(),
                "OEBPS/content.opf" to minimalOpf("chapter.xhtml").encodeToByteArray(),
                "OEBPS/chapter.xhtml" to
                    "<html><head><link rel=\"stylesheet\" href=\"big.css\"/></head><body><p>hello</p></body></html>"
                        .encodeToByteArray(),
                "OEBPS/big.css" to ByteArray(8 * 1024 * 1024 + 1) { ' '.code.toByte() },
            ),
        )

        try {
            val container = FileSystem.SYSTEM.openZip(path).let { zip -> openEpubImportContainer(zip, "Css") }
            assertNotNull(container)
            val section = assertNotNull(parseEpubSpineItem(container, 0, 0, 0L))
            assertEquals("hello", section.section.text)
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    /** 요소 깊이 상한을 넘는 스파인 챕터는 예외 없이 빈 섹션이 되어 가져오기 커서가 전진한다. */
    @Test
    fun overDeepSpineChapterYieldsEmptySectionWithoutThrowing() {
        val path = writeTemporaryEpub(
            entries = mapOf(
                "META-INF/container.xml" to containerXml().encodeToByteArray(),
                "OEBPS/content.opf" to minimalOpf("chapter.xhtml").encodeToByteArray(),
                "OEBPS/chapter.xhtml" to ("<div>".repeat(200) + "text" + "</div>".repeat(200)).encodeToByteArray(),
            ),
        )

        try {
            val container = FileSystem.SYSTEM.openZip(path).let { zip -> openEpubImportContainer(zip, "Deep") }
            assertNotNull(container)
            val section = assertNotNull(parseEpubSpineItem(container, 0, 0, 0L))
            assertEquals("", section.section.text)
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    /** OPF가 없는 fallback은 256개보다 많은 HTML 엔트리를 한꺼번에 적재하지 않는다. */
    @Test
    fun fallbackRejectsTooManyHtmlEntries() {
        val entries = (0..256).associate { index ->
            "text/chapter-$index.xhtml" to "<p>$index</p>".encodeToByteArray()
        }
        val path = writeTemporaryEpub(entries)

        try {
            assertFailsWith<IllegalArgumentException> {
                EpubDocumentParser().parse(DocumentId("fallback-count"), "Fallback", path)
            }
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    /** OPF가 없는 fallback은 엔트리별 한도 안의 XHTML도 누적 16 Mi 문자를 넘으면 거부한다. */
    @Test
    fun fallbackRejectsExcessiveAggregateCharacters() {
        val chapter = ByteArray(6 * 1024 * 1024) { 'a'.code.toByte() }
        val path = writeTemporaryEpub(
            entries = mapOf(
                "text/one.xhtml" to chapter,
                "text/two.xhtml" to chapter,
                "text/three.xhtml" to chapter,
            ),
        )

        try {
            assertFailsWith<IllegalArgumentException> {
                EpubDocumentParser().parse(DocumentId("fallback-size"), "Fallback", path)
            }
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    /** 임베드 이미지 배치는 요청 수와 무관하게 존재하는 모든 href를 돌려준다. */
    @Test
    fun embeddedImageBatchReturnsEveryRequestedHref() {
        val entries = (0..128).associate { index -> "images/$index.png" to ByteArray(4) }
        val path = writeTemporaryEpub(entries)

        try {
            val result = EpubDocumentParser().extractEmbeddedImageBytes(path, entries.keys)
            assertEquals(entries.keys, result.keys)
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    /** 엔트리별 상한을 지킨 이미지들은 누적 바이트가 커도 모두 반환된다. */
    @Test
    fun embeddedImageBatchIgnoresAggregateBytes() {
        val image = ByteArray(7 * 1024 * 1024)
        val entries = (0 until 5).associate { index -> "images/$index.png" to image }
        val path = writeTemporaryEpub(entries)

        try {
            val result = EpubDocumentParser().extractEmbeddedImageBytes(path, entries.keys)
            assertEquals(entries.keys, result.keys)
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    /** nav 문서보다 깊은 디렉터리의 스파인은 접미사 인덱스로 기존 fallback 대상에 해석된다. */
    @Test
    fun navigationResolvesSectionPathBySuffix() {
        val path = writeTemporaryEpub(
            entries = mapOf(
                "META-INF/container.xml" to containerXml().encodeToByteArray(),
                "OEBPS/content.opf" to
                    """
                    <package>
                      <metadata><dc:title>Navigation</dc:title></metadata>
                      <manifest>
                        <item id="chapter" href="text/chapter.xhtml" media-type="application/xhtml+xml"/>
                        <item id="nav" href="../toc.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                      </manifest>
                      <spine><itemref idref="chapter"/></spine>
                    </package>
                    """.trimIndent().encodeToByteArray(),
                "OEBPS/text/chapter.xhtml" to "<p>chapter</p>".encodeToByteArray(),
                "toc.xhtml" to
                    "<nav epub:type=\"toc\"><ol><li><a href=\"chapter.xhtml\">Chapter</a></li></ol></nav>"
                        .encodeToByteArray(),
            ),
        )

        try {
            val document = EpubDocumentParser().parse(DocumentId("navigation"), "Navigation", path)

            assertEquals(0, requireNotNull(document.navigation).items.single().spineIndex)
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    /** 서로 다른 연결 CSS 조합이 계속 나타나도 점진적 임포트 캐시는 처음 16개에서 멈춘다. */
    @Test
    fun progressiveLinkedCssCacheIsBounded() {
        val chapterCount = 17
        val entries = linkedMapOf<String, ByteArray>()
        entries["META-INF/container.xml"] = containerXml().encodeToByteArray()
        entries["OEBPS/content.opf"] = buildString {
            append("<package><metadata><dc:title>CSS</dc:title></metadata><manifest>")
            repeat(chapterCount) { index ->
                append("<item id=\"c$index\" href=\"c$index.xhtml\" media-type=\"application/xhtml+xml\"/>")
                append("<item id=\"s$index\" href=\"s$index.css\" media-type=\"text/css\"/>")
            }
            append("</manifest><spine>")
            repeat(chapterCount) { index -> append("<itemref idref=\"c$index\"/>") }
            append("</spine></package>")
        }.encodeToByteArray()
        repeat(chapterCount) { index ->
            entries["OEBPS/c$index.xhtml"] =
                "<html><head><link rel=\"stylesheet\" href=\"s$index.css\"/></head><body><p>x</p></body></html>"
                    .encodeToByteArray()
            entries["OEBPS/s$index.css"] = "p { color: #00000${index % 10} }".encodeToByteArray()
        }
        val path = writeTemporaryEpub(entries)

        try {
            val container = FileSystem.SYSTEM.openZip(path).let { zip -> openEpubImportContainer(zip, "CSS") }
            assertNotNull(container)
            repeat(chapterCount) { index ->
                parseEpubSpineItem(container, index, index, index * 10L)
            }
            assertEquals(16, container.linkedCssCache.size)
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    /** 임시 EPUB을 소비하는 작업이 예외를 던져도 작업에 전달된 파일은 즉시 삭제된다. */
    @Test
    fun temporaryEpubIsDeletedWhenConsumerFails() {
        var temporaryPath: Path? = null

        assertFailsWith<IllegalStateException> {
            withTemporaryEpubFile(
                bytes = byteArrayOf(1, 2, 3),
                namePrefix = "epub-hardening-test",
                fileSystem = FileSystem.SYSTEM,
            ) { path ->
                temporaryPath = path
                throw IllegalStateException("consumer failure")
            }
        }

        assertFalse(FileSystem.SYSTEM.exists(requireNotNull(temporaryPath)))
    }

    /** 임시 EPUB 기록이 일부 바이트 뒤 실패해도 생성된 부분 파일은 즉시 삭제된다. */
    @Test
    fun temporaryEpubIsDeletedWhenWriteFails() {
        val fileSystem = FailingWriteFileSystem(FileSystem.SYSTEM)

        assertFailsWith<IOException> {
            withTemporaryEpubFile(
                bytes = byteArrayOf(1, 2, 3),
                namePrefix = "epub-hardening-write-test",
                fileSystem = fileSystem,
            ) { error("consumer must not run") }
        }

        assertFalse(FileSystem.SYSTEM.exists(requireNotNull(fileSystem.createdPath)))
    }

    /** 이미지 헤더 읽기의 프로그래밍 오류는 손상 이미지처럼 null로 바뀌지 않고 호출자에게 전파된다. */
    @Test
    fun unexpectedImageReadFailureIsNotSwallowed() {
        val blocks = mutableListOf(
            ReaderBlock(
                kind = ReaderBlockKind.IMAGE,
                range = TextRange(0L, 1L),
                imageHref = "image.png",
            ),
        )

        assertFailsWith<IllegalStateException> {
            fillIntrinsicImageSizes(
                blocks = blocks,
                zip = FailingSourceFileSystem(FileSystem.SYSTEM),
                coverHref = null,
                coverBytes = null,
            )
        }
    }
}

/**
 * [entries]를 실제 플랫폼 ZIP 구현으로 묶고 임시 경로에 기록한다.
 *
 * @param entries ZIP 엔트리 이름에서 압축 전 바이트로의 매핑.
 * @return 호출자가 삭제해야 하는 임시 EPUB 경로.
 */
private fun writeTemporaryEpub(entries: Map<String, ByteArray>): Path {
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "epub-hardening-${kotlin.random.Random.nextLong()}.epub"
    FileSystem.SYSTEM.sink(path).buffer().use { sink -> sink.write(epubBytes(entries)) }
    return path
}

/**
 * [entries]를 메모리 ZIP으로 직렬화한다.
 *
 * @param entries ZIP 엔트리 이름에서 압축 전 바이트로의 매핑.
 * @return 완성된 ZIP 바이트.
 */
private fun epubBytes(entries: Map<String, ByteArray>): ByteArray =
    ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

/** 최소 OPF 경로를 가리키는 OCF 컨테이너 문서. */
private fun containerXml(): String =
    """
    <container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>
    """.trimIndent()

/**
 * 하나의 XHTML [chapterHref]만 스파인에 둔 최소 OPF.
 *
 * @param chapterHref OPF 기준 챕터 경로.
 * @return 파서가 스파인을 찾을 수 있는 OPF 문자열.
 */
private fun minimalOpf(chapterHref: String): String =
    """
    <package>
      <metadata><dc:title>Bounded</dc:title></metadata>
      <manifest><item id="chapter" href="$chapterHref" media-type="application/xhtml+xml"/></manifest>
      <spine><itemref idref="chapter"/></spine>
    </package>
    """.trimIndent()

/**
 * 첫 sink 쓰기에서 한 바이트만 전달한 뒤 실패해 임시파일 선행 기록 오류를 재현한다.
 *
 * @param delegate 실제 임시 파일 생성과 삭제를 수행할 파일 시스템.
 */
private class FailingWriteFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    /** [sink]가 생성하려고 시도한 임시 파일 경로. */
    var createdPath: Path? = null
        private set

    /**
     * 실제 sink를 감싸 첫 쓰기 일부 뒤 [IOException]을 던진다.
     *
     * @param file 생성할 임시 파일 경로.
     * @param mustCreate 파일이 이미 있을 때 실패해야 하는지 여부.
     * @return 부분 쓰기 실패를 주입하는 sink.
     */
    override fun sink(file: Path, mustCreate: Boolean): Sink {
        createdPath = file
        return object : ForwardingSink(super.sink(file, mustCreate)) {
            /**
             * 요청된 바이트 중 하나만 실제 파일에 기록한 뒤 실패한다.
             *
             * @param source 소비할 원본 버퍼.
             * @param byteCount 호출자가 기록하려 한 바이트 수.
             * @throws IOException 부분 기록 직후 항상 발생한다.
             */
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, minOf(1L, byteCount))
                throw IOException("injected write failure")
            }
        }
    }
}

/**
 * 모든 source 열기에서 비-I/O 런타임 오류를 내 파서가 예상 오류만 처리하는지 검증한다.
 *
 * @param delegate 사용되지 않는 나머지 파일시스템 연산을 제공할 구현.
 */
private class FailingSourceFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    /**
     * 이미지 엔트리 열기를 나타내는 모든 호출에서 예상 밖 오류를 발생시킨다.
     *
     * @param file 열려고 한 이미지 경로.
     * @return 정상 반환하지 않는다.
     * @throws IllegalStateException 항상 발생한다.
     */
    override fun source(file: Path): Source = throw IllegalStateException("injected source failure: $file")
}

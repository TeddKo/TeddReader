package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderObjectReplacementChar
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.readerImageSize
import com.tedd.teddreader.core.common.model.standaloneBlocks
import com.tedd.teddreader.core.data.pagination.TextPageLayoutEngine
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * 삽화가 가득한 책 한 권 전체를, 처음부터 끝까지 읽어 마크업이 실제로 말하는 것과 대조 검증한다.
 *
 * 이 픽스처는 이 리더가 받게 되는 EPUB 형태로 만들어졌다: SVG로 감싼 표지, 문장 안에 들어간 글자
 * 세트, 스타일시트의 클래스로만 크기가 정해지는 삽화판, 연속으로 이어지는 삽화판들, 캡션이 붙은
 * 도판, 그리고 구분선. 이 각각은 잘못 그려지고 있던 사례였다.
 */
class EpubMultiImageLayoutTest {
    /**
     * 픽스처 EPUB가 기록되고 다시 읽히는 임시 파일. [EpubDocumentParser.parse]가 이 호출 지점에서
     * 원시 바이트가 아니라 [Path]를 열기 때문이다.
     */
    private val epubPath: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "tedd-multi-image-test.epub"

    /**
     * ([multiImageEpub] 참고) 픽스처 책. [epubPath]에 기록되고 한 번 지연 파싱된 뒤, 이 클래스의
     * 모든 테스트가 공유한다.
     */
    private val document: ReaderDocument by lazy {
        FileSystem.SYSTEM.sink(epubPath).buffer().use { sink -> sink.write(multiImageEpub()) }
        EpubDocumentParser().parse(DocumentId("plates"), "Plates", epubPath)
    }

    /** 각 테스트 이후 스크래치 EPUB 파일을 삭제한다. 애초에 만들어진 적이 없다면 실패를 무시한다. */
    @AfterTest
    fun cleanUp() {
        runCatching { FileSystem.SYSTEM.delete(epubPath) }
    }

    /**
     * 회귀 방지: `<img>`는 HTML에서 인라인 콘텐츠이며, 어떤 리딩 시스템도 이를 자신의 문단 밖으로
     * 옮기지 않는다. 모든 그림을 자신만의 블록으로 배출하던 예전 방식은 글자를 문장 중간에서
     * 찢어내고 그 자리에 빈 줄을 남겼었다; 이 테스트는 그 글자가 대신 문장 자신의 문단 블록 안에
     * 머무르고 결코 독립된 삽화판으로 취급되지 않음을 고정한다.
     */
    @Test
    fun aGlyphWrittenInsideASentenceStaysInThatSentence() {
        val chapter = document.sections.single { it.title == "1화 기회" }
        val glyph = document.blocks.single { it.imageHref?.endsWith("gaiji.png") == true }
        val paragraph = document.blocks.single { block ->
            block.kind == ReaderBlockKind.PARAGRAPH &&
                block.range.start <= glyph.range.start &&
                block.range.end >= glyph.range.end
        }

        val sentence = chapter.text.substring(
            (paragraph.range.start - chapter.range.start).toInt(),
            (paragraph.range.end - chapter.range.start).toInt(),
        )
        assertEquals("앞 문장이 있고 $ReaderObjectReplacementChar 뒤 문장이 이어진다.", sentence)
        assertTrue(glyph !in document.blocks.standaloneBlocks(), "an inline glyph must not be a plate")
    }

    /**
     * 감싸는 요소 안에 유일하게 든 그림은 자신만의 줄에 독립 삽화판이 되며, 그림이 빠져나온 뒤 그
     * 래퍼 자체는 빈 문단 블록을 남기지 않는다.
     */
    @Test
    fun aPictureAloneInItsWrapperIsAPlateOnItsOwnLine() {
        val plate = document.blocks.single { block ->
            block.imageHref?.endsWith("plate1.png") == true && block.imageWidthPercent != null
        }

        assertTrue(plate in document.blocks.standaloneBlocks(), "a picture alone in its div is a plate")
        assertTrue(
            document.blocks.none { block ->
                block.kind == ReaderBlockKind.PARAGRAPH &&
                    block.range.start <= plate.range.start &&
                    block.range.end >= plate.range.end
            },
            "a wrapper holding only a picture must not also record a paragraph",
        )
    }

    /**
     * 연속된 독립 삽화판들은 마크업이 작성한 읽기 순서대로 각자 자신의 줄을 유지하며, 어떤 둘
     * 사이에도 정확히 한 번의 줄바꿈만 있다 — 빈 줄도, 이어붙음도 없다.
     */
    @Test
    fun consecutivePlatesEachKeepTheirOwnLineInReadingOrder() {
        val chapter = document.sections.single { it.title == "2화 연속 삽화" }
        val plates = document.blocks
            .filter { it.kind == ReaderBlockKind.IMAGE && it.range.start >= chapter.range.start }
            .sortedBy { it.range.start }

        assertEquals(
            listOf("plate1.png", "plate 2.png", "plate3.png", "rule.png"),
            plates.map { it.imageHref?.substringAfterLast('/') },
        )
        plates.zipWithNext().forEach { (first, second) ->
            val gap = chapter.text.substring(
                (first.range.end - chapter.range.start).toInt(),
                (second.range.start - chapter.range.start).toInt(),
            )
            assertEquals("\n", gap, "plates ${first.imageHref} and ${second.imageHref} were separated by '$gap'")
        }
        assertTrue(plates.all { it in document.blocks.standaloneBlocks() })
    }

    /**
     * 책 전체의 모든 블록 — 표지와 챕터들을 통틀어 — 은 자기 자신의 시작 오프셋 기준으로 정렬되어
     * 돌아온다 — 처음부터 끝까지의 읽기 순서.
     */
    @Test
    fun blocksComeBackInReadingOrder() {
        val starts = document.blocks.map { it.range.start }
        assertEquals(starts.sorted(), starts, "blocks must be in reading order")
    }

    /**
     * 회귀 방지. 픽스처가 검증하는 스타일시트 규칙마다 단언 하나씩: `.img_full{width:90%}`는
     * `plate1.png`를 그 래퍼를 통해 크기 조정하고; `.img_inline img{width:1.2em}`은 `gaiji.png`에
     * 대해 이미지 셀렉터 자체를 기준으로 해석되며; `div.plate{width:60%}`와 `div.plate
     * img{width:100%}`가 함께 `plate3.png`를 컬럼의 100%가 아니라 60%로 크기 조정한다; 그리고
     * `plate 2.png`에 걸린 `img{max-width:100%}`는 폭이 아니므로 폭으로 읽혀서는 안 된다. 마지막
     * 단언은 퍼센트 인코딩된 경로 `Images/plate%202.png`가 실제 `plate 2.png` 항목으로 해석됨도
     * 함께 확인한다.
     */
    @Test
    fun theStylesheetSizesEachPictureAndPercentEncodedPathsResolve() {
        fun block(name: String) = document.blocks.first { it.imageHref?.endsWith(name) == true }

        assertEquals(0.9f, block("plate1.png").imageWidthPercent)
        assertEquals(1.2f, block("gaiji.png").imageWidthEm)
        assertEquals(0.6f, block("plate3.png").imageWidthPercent)
        assertEquals(null, block("plate 2.png").imageWidthPercent)
        assertEquals("OEBPS/Images/plate 2.png", block("plate 2.png").imageHref)
    }

    /**
     * 다른 무엇도 비율을 선언하지 않을 때, 모든 그림의 종횡비는 자신의 PNG 바이트에서 직접 읽어낸
     * 실제 픽셀 치수에서 나온다.
     */
    @Test
    fun everyPictureCarriesTheSizeReadFromItsOwnBytes() {
        fun ratioOf(name: String) = document.blocks.first { it.imageHref?.endsWith(name) == true }.imageAspectRatio

        assertEquals(600f / 800f, ratioOf("cover.png"))
        assertEquals(1000f / 600f, ratioOf("plate 2.png"))
        assertEquals(640f / 25f, ratioOf("rule.png"))
    }

    /**
     * 크기 조정 범위의 양 극단을 다루는 회귀 방지: 640x25 구분선(25.6:1 종횡비)은 밴드가 아니라
     * 1em 미만의 가느다란 선으로 배치되고; 1.2em으로 선언된 24px 글자는 자신의 픽셀 치수로
     * 스케일되지 않고 정확히 그 크기를 유지하며; 세로로 긴 삽화판은 선언된 폭과 비율을 유지한 채
     * 페이지 높이로 제한되고, 컬럼을 넘어서까지 확대되지 않는다.
     */
    @Test
    fun aRuleKeepsItsHairlineHeightAndAPlateIsNeverBlownUpPastTheColumn() {
        fun boxOf(name: String) = document.blocks.first { it.imageHref?.endsWith(name) == true }
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 30f, emInPx = 22f)

        assertTrue(boxOf("rule.png").heightEm < 1f, "a 25.6:1 rule was ${boxOf("rule.png").heightEm}em tall")
        assertEquals(1.2f, boxOf("gaiji.png").widthEm)
        val plate = boxOf("plate1.png")
        assertTrue(plate.heightEm <= 30f * 0.95f + 0.01f, "plate was ${plate.heightEm}em on a 30em page")
        assertEquals(0.9f, plate.widthEm / 20f, 0.001f)
    }

    /**
     * 페이지네이션은 결코 한 페이지에 자신의 높이보다 많은 삽화판을 담게 하지 않는다 — 한 페이지에서
     * 배치된 모든 독립 이미지 높이의 합은 그 페이지 자신의 높이를 결코 넘지 않는다.
     */
    @Test
    fun noPageIsAskedToHoldMorePicturesThanItHasRoomFor() {
        val style = ReaderStyle(fontSizeSp = 18f, lineHeightMultiplier = 1.5f)
        val viewport = ViewportSize(widthPx = 360, heightPx = 600)
        val pageHeightEm = viewport.heightPx / style.fontSizeSp
        val columnWidthEm = viewport.widthPx / style.fontSizeSp

        val pages = TextPageLayoutEngine().paginate(
            document = document,
            style = style,
            viewportSize = viewport,
        )

        assertTrue(pages.isNotEmpty())
        pages.forEach { page ->
            val plateHeight = page.blocks.standaloneBlocks()
                .filter { it.kind == ReaderBlockKind.IMAGE }
                .sumOf { block ->
                    block.readerImageSize(
                        columnWidthEm = columnWidthEm,
                        maxHeightEm = pageHeightEm,
                        emInPx = style.fontSizeSp,
                    ).heightEm.toDouble()
                }
            assertTrue(
                plateHeight <= pageHeightEm.toDouble(),
                "page ${page.pageIndex.current} reserves ${plateHeight}em of plates on a ${pageHeightEm}em page",
            )
        }
    }

    /**
     * SVG로 감싼 표지는 책의 유일한 합성 표지 섹션이 되고 — 자신의 XHTML이 그 그림 말고는 아무것도
     * 담고 있지 않았으므로 — 자신만의 챕터로 다시 반복되지 않는다; 섹션 제목들은 정확히 표지
     * 자리표시자에 이어 실제 챕터 두 개다.
     */
    @Test
    fun theSvgWrappedCoverBecomesTheCoverPageAndIsNotRepeatedAsAChapter() {
        val coverBlocks = document.blocks.filter { it.kind == ReaderBlockKind.COVER_IMAGE }
        assertEquals(1, coverBlocks.size)
        assertEquals("OEBPS/Images/cover.png", coverBlocks.single().imageHref)
        assertEquals(
            listOf("Plates", "1화 기회", "2화 연속 삽화"),
            document.sections.map { it.title },
        )
    }

    /** `<figure>` 아래의 `<figcaption>`은 자신의 이미지와 함께 묻히지 않고 평범한 텍스트로 읽힌다. */
    @Test
    fun theCaptionUnderAFigureIsReadAsText() {
        val chapter = document.sections.single { it.title == "2화 연속 삽화" }
        assertTrue(chapter.text.contains("그림 설명"))
    }
}

/** 치수 스니퍼가 읽을 수 있는 PNG 헤더: 시그니처에 [width]와 [height]를 담은 IHDR을 붙인 것. */
private fun pngBytes(width: Int, height: Int): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    out.write(byteArrayOf(0, 0, 0, 13))
    out.write("IHDR".toByteArray())
    listOf(width, height).forEach { value ->
        out.write(
            byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            ),
        )
    }
    out.write(byteArrayOf(8, 6, 0, 0, 0))
    return out.toByteArray()
}

/**
 * [entries](컨테이너 경로에서 원시 바이트로의 매핑)를 메모리상의 ZIP 아카이브로 묶는다 — 이 파일 안의
 * 모든 EPUB 픽스처가 조립되는 형태다.
 */
private fun epubZip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/**
 * 이 테스트 클래스 전체가 읽는 픽스처 EPUB를 만든다: SVG로 감싼 표지(`cover.xhtml`), 문장 안에
 * 글자가 인라인으로 들어간 첫 챕터(`1화 기회`), 자신의 래퍼 클래스만으로 크기가 정해지는 삽화판,
 * 구분선, 그리고 연속으로 이어지는 삽화판 세 개 — 그중 하나는 퍼센트 인코딩된 경로
 * (`plate%202.png`) — 와 캡션이 붙은 도판을 가진 두 번째 챕터(`2화 연속 삽화`). 공유 스타일시트는
 * [theStylesheetSizesEachPictureAndPercentEncodedPathsResolve]가 고정하는 바로 그 규칙들을
 * 선언한다: 전체를 아우르는 `img{max-width:100%}`, `.img_full{width:90%}`,
 * `.img_inline img{width:1.2em}`, 그리고 삽화판을 100%가 아니라 60%로 함께 크기 조정하는
 * `div.plate`/`div.plate img`. 모든 이미지 항목은 [pngBytes]를 통한 실제(비록 작더라도) PNG이므로,
 * 치수 스니퍼가 크기를 잴 실제 바이트를 갖게 된다.
 *
 * @return 조립된 EPUB의 원시 바이트. 디스크에 기록되어 파싱될 준비가 된 상태.
 */
private fun multiImageEpub(): ByteArray {
    val container = """
        <?xml version="1.0"?>
        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()

    val opf = """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Plates</dc:title></metadata>
          <manifest>
            <item id="css" href="Styles/style.css" media-type="text/css"/>
            <item id="cover-img" href="Images/cover.png" media-type="image/png" properties="cover-image"/>
            <item id="gaiji" href="Images/gaiji.png" media-type="image/png"/>
            <item id="plate1" href="Images/plate1.png" media-type="image/png"/>
            <item id="plate2" href="Images/plate 2.png" media-type="image/png"/>
            <item id="plate3" href="Images/plate3.png" media-type="image/png"/>
            <item id="rule" href="Images/rule.png" media-type="image/png"/>
            <item id="cover" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
            <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="ch2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine>
            <itemref idref="cover"/>
            <itemref idref="ch1"/>
            <itemref idref="ch2"/>
          </spine>
        </package>
    """.trimIndent()

    val css = """
        img { max-width: 100%; height: auto; }
        .img_full { width: 90%; }
        .img_inline img { width: 1.2em; }
        div.plate img { width: 100%; }
        div.plate { width: 60%; }
    """.trimIndent()

    val coverXhtml = """
        <?xml version="1.0"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><body>
          <div><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
               viewBox="0 0 600 800" preserveAspectRatio="xMidYMid meet">
            <image width="600" height="800" xlink:href="../Images/cover.png"/>
          </svg></div>
        </body></html>
    """.trimIndent()

    val ch1 = """
        <?xml version="1.0"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head>
          <link rel="stylesheet" type="text/css" href="../Styles/style.css"/></head><body>
          <h1>1화 기회</h1>
          <p>앞 문장이 있고 <span class="img_inline"><img src="../Images/gaiji.png" alt="글자"/></span> 뒤 문장이 이어진다.</p>
          <div class="img_full"><img src="../Images/plate1.png" alt="삽화 1"/></div>
          <p>삽화 뒤에 오는 본문 문장.</p>
          <hr/>
          <p>구분선 뒤 본문.</p>
        </body></html>
    """.trimIndent()

    val ch2 = """
        <?xml version="1.0"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head>
          <link rel="stylesheet" type="text/css" href="../Styles/style.css"/></head><body>
          <h1>2화 연속 삽화</h1>
          <p><img src="../Images/plate1.png" alt="연속 1"/></p>
          <p><img src="../Images/plate%202.png" alt="연속 2"/></p>
          <div class="plate"><img src="../Images/plate3.png" alt="연속 3"/></div>
          <figure><img src="../Images/rule.png" alt="장식"/><figcaption>그림 설명</figcaption></figure>
          <p>마지막 본문.</p>
        </body></html>
    """.trimIndent()

    return epubZip(
        "mimetype" to "application/epub+zip".toByteArray(),
        "META-INF/container.xml" to container.toByteArray(),
        "OEBPS/content.opf" to opf.toByteArray(),
        "OEBPS/Styles/style.css" to css.toByteArray(),
        "OEBPS/Images/cover.png" to pngBytes(600, 800),
        "OEBPS/Images/gaiji.png" to pngBytes(24, 24),
        "OEBPS/Images/plate1.png" to pngBytes(1200, 1800),
        "OEBPS/Images/plate 2.png" to pngBytes(1000, 600),
        "OEBPS/Images/plate3.png" to pngBytes(800, 800),
        "OEBPS/Images/rule.png" to pngBytes(640, 25),
        "OEBPS/Text/cover.xhtml" to coverXhtml.toByteArray(),
        "OEBPS/Text/ch1.xhtml" to ch1.toByteArray(),
        "OEBPS/Text/ch2.xhtml" to ch2.toByteArray(),
    )
}

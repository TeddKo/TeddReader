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
 * A whole illustrated book, read end to end, checked against what the markup actually says.
 *
 * The fixture is shaped like the EPUBs this reader is given: a cover wrapped in SVG, a glyph set
 * inside a sentence, a plate sized only by a class in the stylesheet, a run of back-to-back plates, a
 * figure with its caption, and a rule. Each of those is a case that was getting drawn wrong.
 */
class EpubMultiImageLayoutTest {
    private val epubPath: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "tedd-multi-image-test.epub"
    private val document: ReaderDocument by lazy {
        FileSystem.SYSTEM.sink(epubPath).buffer().use { sink -> sink.write(multiImageEpub()) }
        EpubDocumentParser().parse(DocumentId("plates"), "Plates", epubPath)
    }

    @AfterTest
    fun cleanUp() {
        runCatching { FileSystem.SYSTEM.delete(epubPath) }
    }

    @Test
    fun aGlyphWrittenInsideASentenceStaysInThatSentence() {
        // `<img>` is inline content in HTML and no reading system moves it out of its paragraph.
        // Emitting every picture as its own block tore the glyph out of the middle of the sentence and
        // left blank lines where it had been.
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

    @Test
    fun aPictureAloneInItsWrapperIsAPlateOnItsOwnLine() {
        val plate = document.blocks.single { block ->
            block.imageHref?.endsWith("plate1.png") == true && block.imageWidthPercent != null
        }

        assertTrue(plate in document.blocks.standaloneBlocks(), "a picture alone in its div is a plate")
        // No empty paragraph is left behind by the wrapper it was written in.
        assertTrue(
            document.blocks.none { block ->
                block.kind == ReaderBlockKind.PARAGRAPH &&
                    block.range.start <= plate.range.start &&
                    block.range.end >= plate.range.end
            },
            "a wrapper holding only a picture must not also record a paragraph",
        )
    }

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
        // Exactly one line break between them: no empty line, no run-on.
        plates.zipWithNext().forEach { (first, second) ->
            val gap = chapter.text.substring(
                (first.range.end - chapter.range.start).toInt(),
                (second.range.start - chapter.range.start).toInt(),
            )
            assertEquals("\n", gap, "plates ${first.imageHref} and ${second.imageHref} were separated by '$gap'")
        }
        assertTrue(plates.all { it in document.blocks.standaloneBlocks() })
    }

    @Test
    fun blocksComeBackInReadingOrder() {
        val starts = document.blocks.map { it.range.start }
        assertEquals(starts.sorted(), starts, "blocks must be in reading order")
    }

    @Test
    fun theStylesheetSizesEachPictureAndPercentEncodedPathsResolve() {
        fun block(name: String) = document.blocks.first { it.imageHref?.endsWith(name) == true }

        // `.img_full{width:90%}` on the wrapper.
        assertEquals(0.9f, block("plate1.png").imageWidthPercent)
        // `.img_inline img{width:1.2em}` resolves against the image itself.
        assertEquals(1.2f, block("gaiji.png").imageWidthEm)
        // `div.plate{width:60%}` with `div.plate img{width:100%}` is 60% of the column, not 100%.
        assertEquals(0.6f, block("plate3.png").imageWidthPercent)
        // `img{max-width:100%}` is not a width and must not be read as one.
        assertEquals(null, block("plate 2.png").imageWidthPercent)
        assertEquals("OEBPS/Images/plate 2.png", block("plate 2.png").imageHref)
    }

    @Test
    fun everyPictureCarriesTheSizeReadFromItsOwnBytes() {
        fun ratioOf(name: String) = document.blocks.first { it.imageHref?.endsWith(name) == true }.imageAspectRatio

        assertEquals(600f / 800f, ratioOf("cover.png"))
        assertEquals(1000f / 600f, ratioOf("plate 2.png"))
        assertEquals(640f / 25f, ratioOf("rule.png"))
    }

    @Test
    fun aRuleKeepsItsHairlineHeightAndAPlateIsNeverBlownUpPastTheColumn() {
        fun boxOf(name: String) = document.blocks.first { it.imageHref?.endsWith(name) == true }
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 30f, emInPx = 22f)

        // 640x25 at column width is a hairline, not a band.
        assertTrue(boxOf("rule.png").heightEm < 1f, "a 25.6:1 rule was ${boxOf("rule.png").heightEm}em tall")
        // A 24px glyph declared 1.2em stays a glyph.
        assertEquals(1.2f, boxOf("gaiji.png").widthEm)
        // A tall plate is bounded by the page, keeping its proportions.
        val plate = boxOf("plate1.png")
        assertTrue(plate.heightEm <= 30f * 0.95f + 0.01f, "plate was ${plate.heightEm}em on a 30em page")
        assertEquals(0.9f, plate.widthEm / 20f, 0.001f)
    }

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

    @Test
    fun theSvgWrappedCoverBecomesTheCoverPageAndIsNotRepeatedAsAChapter() {
        val coverBlocks = document.blocks.filter { it.kind == ReaderBlockKind.COVER_IMAGE }
        assertEquals(1, coverBlocks.size)
        assertEquals("OEBPS/Images/cover.png", coverBlocks.single().imageHref)
        // The cover XHTML held nothing but that picture, so it is not also a chapter of its own.
        assertEquals(
            listOf("Plates", "1화 기회", "2화 연속 삽화"),
            document.sections.map { it.title },
        )
    }

    @Test
    fun theCaptionUnderAFigureIsReadAsText() {
        val chapter = document.sections.single { it.title == "2화 연속 삽화" }
        assertTrue(chapter.text.contains("그림 설명"))
    }
}

/** A PNG header the dimension sniffer can read: signature plus an IHDR carrying [width] and [height]. */
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

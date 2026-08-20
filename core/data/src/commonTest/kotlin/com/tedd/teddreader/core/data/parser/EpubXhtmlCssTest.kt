package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderFontFamily
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpubXhtmlCssTest {
    private fun parse(xhtml: String, vararg sheets: String) =
        parseXhtmlContent(xhtml = xhtml, css = EpubCss.parse(sheets.toList()))

    @Test
    fun aTagRuleCentresTheChapterTitleTheBookCentres() {
        // Exactly what these books do, and what this reader used to miss entirely.
        val content = parse("<h1>제1장</h1><p>본문</p>", "h1 { text-align: center; text-indent: 0 }")

        val heading = content.blocks.single { it.kind == ReaderBlockKind.HEADING }
        assertEquals(ReaderTextAlign.CENTER, heading.align)
        assertNull(content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.align)
    }

    @Test
    fun aClassRuleReachesTheParagraphCarryingIt() {
        val content = parse(
            """<p class="dedi">인용</p>""",
            ".dedi { font-size: 0.8em; text-indent: 0em; text-align: right }",
        )

        val block = content.blocks.single()
        assertEquals(ReaderTextAlign.END, block.align)
        assertEquals(0.8f, block.style?.fontScale)
        assertEquals(0f, block.style?.textIndentEm)
    }

    @Test
    fun aRuleOnAWrapperReachesTheParagraphInside() {
        val content = parse(
            """<div class="quotebox"><p>안쪽</p></div>""",
            ".quotebox p { font-style: italic; text-align: center }",
        )

        val block = content.blocks.single()
        assertEquals(true, block.style?.italic)
        assertEquals(ReaderTextAlign.CENTER, block.align)
    }

    @Test
    fun markupWrittenOnTheElementBeatsTheStylesheet() {
        val content = parse(
            """<p style="text-align: right">본문</p>""",
            "p { text-align: center }",
        )

        assertEquals(ReaderTextAlign.END, content.blocks.single().align)
    }

    @Test
    fun weightAndFamilyAndLineHeightAreCarried() {
        val content = parse(
            """<p class="code">본문</p>""",
            ".code { font-weight: 700; font-family: 'Courier New', monospace; line-height: 1.8 }",
        )

        val style = content.blocks.single().style
        assertEquals(true, style?.bold)
        assertEquals(ReaderFontFamily.MONOSPACE, style?.fontFamily)
        assertEquals(1.8f, style?.lineHeightScale)
    }

    @Test
    fun aBookNamingItsOwnBundledFaceKeepsTheReadersFont() {
        val content = parse("""<p class="g">본문</p>""", ".g { font-family: G }")

        assertNull(content.blocks.single().style?.fontFamily)
    }

    @Test
    fun aBlockTheStylesheetSaysNothingAboutCarriesNoStyle() {
        val content = parse("<p>본문</p>", "h1 { text-align: center }")

        assertNull(content.blocks.single().style)
    }

    @Test
    fun aMoreSpecificRuleWinsForTheSameBlock() {
        val content = parse(
            """<h1 class="plain">제목</h1>""",
            "h1 { text-align: center } h1.plain { text-align: left }",
        )

        assertEquals(ReaderTextAlign.START, content.blocks.single().align)
    }

    @Test
    fun anImageWrappedInAHeadingStillReadsAsAPicture() {
        // `<h1 class="img_full"><img/></h1>` is how these books ship a full-page plate.
        val content = parse(
            """<h1 class="img_full"><img src="p.jpg" alt=""/></h1>""",
            ".img_full { width: 90%; text-align: center }",
        )

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertEquals("p.jpg", image.imageHref)
        assertTrue(content.text.isNotEmpty())
    }
}

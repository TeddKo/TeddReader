package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderFontFamily
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins how [parseXhtmlContent] applies a chapter's CSS cascade ([EpubCss]) to the blocks it builds:
 * alignment, font styling, and stylesheet-vs-markup precedence, including the case that used to be
 * missed entirely — a plain tag rule reaching a heading with no class at all.
 */
class EpubXhtmlCssTest {
    private fun parse(xhtml: String, vararg sheets: String) =
        parseXhtmlContent(xhtml = xhtml, css = EpubCss.parse(sheets.toList()))

    /**
     * Regression guard: a bare-tag rule (`h1 { text-align: center }`) reaches a heading with no class
     * at all — exactly what these books do, and what this reader used to miss entirely.
     */
    @Test
    fun aTagRuleCentresTheChapterTitleTheBookCentres() {
        val content = parse("<h1>제1장</h1><p>본문</p>", "h1 { text-align: center; text-indent: 0 }")

        val heading = content.blocks.single { it.kind == ReaderBlockKind.HEADING }
        assertEquals(ReaderTextAlign.CENTER, heading.align)
        assertNull(content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.align)
    }

    /** A class rule's alignment, font scale, and indent all reach the paragraph carrying that class. */
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

    /**
     * A rule targeting a descendant (`.quotebox p`) reaches the paragraph nested inside the classed
     * wrapper, not just the wrapper itself.
     */
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

    /**
     * An inline `style` attribute's alignment wins over a conflicting stylesheet rule, the same precedence
     * a browser gives it.
     */
    @Test
    fun markupWrittenOnTheElementBeatsTheStylesheet() {
        val content = parse(
            """<p style="text-align: right">본문</p>""",
            "p { text-align: center }",
        )

        assertEquals(ReaderTextAlign.END, content.blocks.single().align)
    }

    /**
     * Font weight, font family, and line height are all carried from the cascade into the block's style.
     */
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

    /**
     * A book naming its own bundled font face falls back to the reader's own font rather than a guessed
     * substitute.
     */
    @Test
    fun aBookNamingItsOwnBundledFaceKeepsTheReadersFont() {
        val content = parse("""<p class="g">본문</p>""", ".g { font-family: G }")

        assertNull(content.blocks.single().style?.fontFamily)
    }

    /** A block no rule targets carries no style at all, rather than an empty-but-present one. */
    @Test
    fun aBlockTheStylesheetSaysNothingAboutCarriesNoStyle() {
        val content = parse("<p>본문</p>", "h1 { text-align: center }")

        assertNull(content.blocks.single().style)
    }

    /** When two rules could both apply to the same block, the more specific one wins. */
    @Test
    fun aMoreSpecificRuleWinsForTheSameBlock() {
        val content = parse(
            """<h1 class="plain">제목</h1>""",
            "h1 { text-align: center } h1.plain { text-align: left }",
        )

        assertEquals(ReaderTextAlign.START, content.blocks.single().align)
    }

    /**
     * Regression guard: `<h1 class="img_full"><img/></h1>` — how these books ship a full-page plate
     * wrapped in a heading — still reads as a picture, not a heading.
     */
    @Test
    fun anImageWrappedInAHeadingStillReadsAsAPicture() {
        val content = parse(
            """<h1 class="img_full"><img src="p.jpg" alt=""/></h1>""",
            ".img_full { width: 90%; text-align: center }",
        )

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertEquals("p.jpg", image.imageHref)
        assertTrue(content.text.isNotEmpty())
    }

    /** A nested `em` font size compounds through its ancestors, the way CSS defines it: 0.8 × 0.8 = 0.64. */
    @Test
    fun aNestedEmFontSizeCompoundsThroughItsAncestors() {
        val content = parse(
            """<div class="outer"><p>본문</p></div>""",
            ".outer { font-size: 0.8em } p { font-size: 0.8em }",
        )

        assertEquals(0.64f, content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.style?.fontScale ?: 0f, 0.0001f)
    }

    /**
     * A unitless `line-height` inherits as a *factor*: a heading set in larger type gets the factor times
     * its own size, not the body's. Collapsing this into a fixed length is what used to set every
     * large-type block's lines tighter than its glyphs.
     */
    @Test
    fun aUnitlessLineHeightRemultipliesEachElementsOwnSize() {
        val content = parse(
            "<body><h1>제목</h1><p>본문</p></body>",
            "body { line-height: 1.6 } h1 { font-size: 2em }",
        )

        assertEquals(3.2f, content.blocks.single { it.kind == ReaderBlockKind.HEADING }.style?.lineHeightScale)
        assertEquals(1.6f, content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.style?.lineHeightScale)
    }

    /** A `line-height` stated as a length computes once at its declaring element and inherits fixed. */
    @Test
    fun aLineHeightLengthComputesOnceAndInheritsFixed() {
        val content = parse(
            """<div class="lead"><p>본문</p></div>""",
            ".lead { font-size: 1.25em; line-height: 1.2em } p { font-size: 0.8em }",
        )

        // 1.2em × 1.25 = 1.5 base-em at the wrapper; the paragraph inherits that size, not the factor.
        assertEquals(1.5f, content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.style?.lineHeightScale)
    }

    /** An `em` margin resolves against the element's own font size, as CSS defines it. */
    @Test
    fun anEmMarginResolvesAgainstTheElementsOwnSize() {
        val content = parse(
            "<p>본문</p>",
            "p { font-size: 2em; margin-bottom: 1em }",
        )

        assertEquals(2f, content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.style?.marginBottomEm)
    }

    /**
     * `text-decoration` paints across descendants rather than inheriting: a paragraph inside an underlined
     * wrapper is underlined, an element declaring only a strikethrough keeps the ancestor's underline, and
     * `none` on a link switches the paint off for the link.
     */
    @Test
    fun textDecorationPaintsAcrossDescendants() {
        val content = parse(
            """<div class="u"><p>본문 <a href="n.xhtml">링크</a></p></div>""",
            ".u { text-decoration: underline } p { text-decoration: line-through } a { text-decoration: none }",
        )

        val paragraph = content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }
        assertEquals(true, paragraph.style?.underline)
        assertEquals(true, paragraph.style?.lineThrough)
        val link = paragraph.spans.single()
        assertEquals(false, link.cssStyle?.underline)
        assertEquals(false, link.cssStyle?.lineThrough)
    }

    /**
     * A span carries only its *delta* against the block: an inherited font scale already applied by the
     * block must not ride the span too, where the renderer's nested-em resolution would apply it twice.
     */
    @Test
    fun aSpanCarriesOnlyItsDeltaAgainstTheBlock() {
        val content = parse(
            """<div class="s"><p>본문 <span style="font-size: 0.9em">작게</span></p></div>""",
            ".s { font-size: 0.9em }",
        )

        val paragraph = content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }
        assertEquals(0.9f, paragraph.style?.fontScale)
        assertEquals(0.9f, paragraph.spans.single().cssStyle?.fontScale)
    }

    /**
     * Inline-start margins and padding accumulate from every block-level wrapper into the paragraph's
     * inset — how a `<div>`-indented quotation keeps its indent.
     */
    @Test
    fun wrapperInsetsAccumulateIntoTheParagraph() {
        val content = parse(
            """<div class="q"><p style="margin-left: 1em">인용</p></div>""",
            ".q { margin-left: 2em; padding-left: 1em }",
        )

        assertEquals(4f, content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.style?.insetStartEm)
    }

    /**
     * `body`'s spacing becomes the page margin, not a per-paragraph inset — accumulating it too would
     * apply the same space twice.
     */
    @Test
    fun bodySpacingStaysOutOfParagraphInsets() {
        val content = parse(
            "<html><body><p>본문</p></body></html>",
            "body { margin: 2em }",
        )

        assertNull(content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.style?.insetStartEm)
        val body = content.blocks.single { it.isPageContainer }
        assertEquals(2f, body.style?.marginStartEm)
    }

    /**
     * `<p><br/></p>` is a blank-*line* paragraph, not an empty one: a browser draws it one line tall,
     * and it is how these books put space between a chapter-title box and the prose. Dropping it as
     * empty glued the two together.
     */
    @Test
    fun aParagraphOfOnlyLineBreaksKeepsItsBlankLines() {
        val content = parse("<p>본문</p><p><br/></p><p><br/></p><p>다음</p>", "")

        val paragraphs = content.blocks.filter { it.kind == ReaderBlockKind.PARAGRAPH }
        assertEquals(4, paragraphs.size)
        val blank = paragraphs[1]
        assertEquals("\n", content.text.substring(blank.range.start.toInt(), blank.range.end.toInt()))
    }

    /** Text set directly inside a styled wrapper, with no block tag of its own, still takes its styling. */
    @Test
    fun bareTextInsideAStyledWrapperTakesItsStyling() {
        val content = parse(
            """<div class="w">본문</div>""",
            ".w { color: #011689 }",
        )

        // div is itself a block; make the wrapper neutral instead to hit the implicit-block path.
        val neutral = parse(
            """<figure class="w">본문</figure>""",
            ".w { color: #011689; font-size: 0.9em }",
        )
        val block = neutral.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }
        assertEquals(0.9f, block.style?.fontScale)
        assertTrue(content.blocks.isNotEmpty())
    }
}

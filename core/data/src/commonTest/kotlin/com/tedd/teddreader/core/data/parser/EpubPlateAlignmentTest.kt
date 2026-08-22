package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import com.tedd.teddreader.core.common.model.standaloneBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the shape a boxed plate — `<div class="frame"><img/></div>`, the way these books set a logo or
 * an illustration — comes out of the parser in, all the way to the standalone judgement the renderer
 * centres it by. The regression this guards: the wrapper's CONTAINER block used to count as a text
 * block enclosing the image, which demoted the plate to an inline glyph and set it flush left.
 */
class EpubPlateAlignmentTest {
    private val css = """
        .br_img{text-align:center;margin-top:7em;}
        .img_britg{text-align:center;text-indent:0em;margin:0 auto;width:6.5em;display:inline-block;}
        .img_britg img{width:100%;}
    """.trimIndent()

    private val xhtml = """
        <html><body>
          <p>prose before</p>
          <div class="br_img">
            <div class="img_britg">
              <a href="https://example.com"><img alt="" src="logo.jpg"/></a>
              <p>caption</p>
            </div>
          </div>
        </body></html>
    """.trimIndent()

    /** A plate boxed in a styled wrapper is still standalone: only text blocks demote it to inline. */
    @Test
    fun aPlateInsideAStyledWrapperStaysStandalone() {
        val content = parseXhtmlContent(xhtml = xhtml, css = EpubCss.parse(listOf(css)))

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertTrue(content.blocks.any { it.kind == ReaderBlockKind.CONTAINER })
        assertTrue(image in content.blocks.standaloneBlocks())
        assertEquals(ReaderTextAlign.CENTER, image.align)
    }

    /** A picture written inside a sentence is not standalone — its paragraph encloses it. */
    @Test
    fun aPictureInsideASentenceStaysInline() {
        val content = parseXhtmlContent(
            xhtml = """<p>before <img src="glyph.png"/> after</p>""",
        )

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertTrue(image !in content.blocks.standaloneBlocks())
    }

    /** An explicit `text-align: right` reaching the image through the cascade places the plate there. */
    @Test
    fun anExplicitEndAlignmentIsHonored() {
        val content = parseXhtmlContent(
            xhtml = """<div class="right"><img src="logo.jpg"/></div>""",
            css = EpubCss.parse(listOf(".right{text-align:right;}")),
        )

        assertEquals(ReaderTextAlign.END, content.blocks.single { it.kind == ReaderBlockKind.IMAGE }.align)
    }

    /**
     * `text-align: justify`/`left` is prose styling the image merely inherits from body/p defaults;
     * a reading system still centres a plate under it, so the default stands.
     */
    @Test
    fun inheritedProseAlignmentDoesNotDragThePlateToTheMargin() {
        val content = parseXhtmlContent(
            xhtml = """<div class="prose"><img src="logo.jpg"/></div>""",
            css = EpubCss.parse(listOf(".prose{text-align:justify;}")),
        )

        assertEquals(ReaderTextAlign.CENTER, content.blocks.single { it.kind == ReaderBlockKind.IMAGE }.align)
    }
}

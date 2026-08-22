package com.tedd.teddreader.core.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins how both EPUB 3's nav document ([parseEpubNavDocument]) and EPUB 2's NCX ([parseNcxDocument])
 * are parsed into the same [ParsedNavigation] shape: heading label, nested label/level/href per entry,
 * and an inline image's `alt` text folding into its link's own label.
 */
class EpubNavigationParserTest {
    /**
     * A nav document's heading, nested `<li>` levels, and an inline `<img alt>` inside a link's text are
     * all captured correctly.
     */
    @Test
    fun parsesEpub3NavHeadingNestedLabelsAndInlineImageAlt() {
        val parsed = parseEpubNavDocument(
            """
            <html><body>
              <nav epub:type="landmarks toc">
                <h2>Contents</h2>
                <ol>
                  <li><a href="text/ch1.xhtml">Chapter <img src="icon.png" alt="One"/></a>
                    <ol>
                      <li><a href="text/ch1.xhtml#scene">Scene <span>One</span></a></li>
                    </ol>
                  </li>
                </ol>
              </nav>
            </body></html>
            """.trimIndent(),
        )

        assertEquals("Contents", parsed.heading)
        assertEquals(listOf("Chapter One", "Scene One"), parsed.entries.map { it.title })
        assertEquals(listOf(1, 2), parsed.entries.map { it.level })
        assertEquals(listOf("text/ch1.xhtml", "text/ch1.xhtml#scene"), parsed.entries.map { it.href })
    }

    /** An NCX's `docTitle`, and its nested `navPoint` labels and levels, are captured correctly. */
    @Test
    fun parsesNcxDocTitleAndNestedLabels() {
        val parsed = parseNcxDocument(
            """
            <ncx>
              <docTitle><text>Guide</text></docTitle>
              <navMap>
                <navPoint id="n1">
                  <navLabel><text>Chapter 1</text></navLabel>
                  <content src="text/ch1.xhtml"/>
                  <navPoint id="n1-1">
                    <navLabel><text>Scene 1</text></navLabel>
                    <content src="text/ch1.xhtml#scene"/>
                  </navPoint>
                </navPoint>
              </navMap>
            </ncx>
            """.trimIndent(),
        )

        assertEquals("Guide", parsed.heading)
        assertEquals(listOf("Chapter 1", "Scene 1"), parsed.entries.map { it.title })
        assertEquals(listOf(1, 2), parsed.entries.map { it.level })
    }
}

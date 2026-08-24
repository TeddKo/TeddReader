package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [EpubDocumentParser]'s chapter-to-document assembly ([EpubDocumentParser.parseChapters]) and its
 * OPF cover-lookup fallback chain ([findEpubCoverHref]), independent of any real ZIP archive — the
 * archive-opening path ([EpubDocumentParser.parseWithCover]) is covered separately by the
 * androidHostTest suite, which can build a real EPUB file.
 */
class EpubDocumentParserTest {
    private val parser = EpubDocumentParser()

    /**
     * Chapters become sections whose text has the heading pulled out into its own block — it no
     * longer runs into the paragraph that follows it, unlike a naive tag-strip.
     */
    @Test
    fun parsesChaptersIntoReadableSections() {
        val document = parser.parseChapters(
            id = DocumentId("epub-1"),
            title = "Book",
            chapters = listOf(
                EpubChapter("Intro", "<html><body><h1>Intro</h1><p>Hello&nbsp;reader</p></body></html>"),
                EpubChapter("Next", "<p>Second &amp; chapter</p>"),
            ),
        )

        assertEquals(DocumentFormat.EPUB, document.format)
        assertEquals(2, document.sections.size)
        assertEquals("Intro\n\nHello reader", document.sections.first().text)
        assertEquals("Second & chapter", document.sections[1].text)
        assertEquals(
            listOf(ReaderBlockKind.HEADING, ReaderBlockKind.PARAGRAPH, ReaderBlockKind.PARAGRAPH),
            document.blocks.map { it.kind },
        )
    }

    /**
     * Regression guard: each block's range indexes correctly into the text the sections form once
     * joined by a single newline — the same join [EpubDocumentParser.parseChapters] itself performs
     * when it advances its running offset.
     */
    @Test
    fun blockRangesIndexTheSectionsAsTheyAreJoinedForReading() {
        val document = parser.parseChapters(
            id = DocumentId("epub-2"),
            title = "Book",
            chapters = listOf(
                EpubChapter("One", "<p>alpha</p>"),
                EpubChapter("Two", "<p>beta</p>"),
            ),
        )

        val joined = document.sections.joinToString(separator = "\n") { section -> section.text }
        assertEquals(
            listOf("alpha", "beta"),
            document.blocks.map { block -> joined.substring(block.range.start.toInt(), block.range.end.toInt()) },
        )
        assertEquals(
            listOf("alpha", "beta"),
            document.sections.map { section ->
                joined.substring(section.range.start.toInt(), section.range.end.toInt())
            },
        )
    }

    /**
     * EPUB 3's `properties="cover-image"` manifest item is found as the cover — the most explicit of the
     * three ways a book can declare one.
     */
    @Test
    fun findsCoverHrefFromEpub3CoverImageProperty() {
        val opf = """
            <package>
              <manifest>
                <item id="cover" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                <item id="chapter" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
            </package>
        """.trimIndent()

        assertEquals("images/cover.jpg", findEpubCoverHref(opf))
    }

    /**
     * EPUB 2's `<meta name="cover" content="...">` pointer to a manifest id is found as the cover when
     * there is no EPUB 3 property.
     */
    @Test
    fun findsCoverHrefFromEpub2MetaCoverId() {
        val opf = """
            <package>
              <metadata>
                <meta name="cover" content="cover-image-id"/>
              </metadata>
              <manifest>
                <item id="cover-image-id" href="images/cover.png" media-type="image/png"/>
              </manifest>
            </package>
        """.trimIndent()

        assertEquals("images/cover.png", findEpubCoverHref(opf))
    }

    /**
     * When neither EPUB 3 nor EPUB 2 cover metadata is present, a raster item whose own id hints at being a
     * cover is still found.
     */
    @Test
    fun fallsBackToRasterItemWithCoverHint() {
        val opf = """
            <package>
              <manifest>
                <item id="front-cover" href="images/front-cover.webp" media-type="image/webp"/>
              </manifest>
            </package>
        """.trimIndent()

        assertEquals("images/front-cover.webp", findEpubCoverHref(opf))
    }

    /**
     * An OPF with no cover metadata and no cover-hinted item at all resolves to no cover, rather than
     * guessing one.
     */
    @Test
    fun returnsNullWhenNoCoverExists() {
        val opf = """
            <package>
              <manifest>
                <item id="chapter" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
            </package>
        """.trimIndent()

        assertEquals(null, findEpubCoverHref(opf))
    }
}

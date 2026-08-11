package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals

class EpubDocumentParserTest {
    private val parser = EpubDocumentParser()

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
        assertEquals("Intro Hello reader", document.sections.first().text)
        assertEquals("Second & chapter", document.sections[1].text)
    }

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

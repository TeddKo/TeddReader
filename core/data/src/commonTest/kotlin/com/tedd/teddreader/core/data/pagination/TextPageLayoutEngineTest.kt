package com.tedd.teddreader.core.data.pagination

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextPageLayoutEngineTest {
    private val engine = TextPageLayoutEngine()

    @Test
    fun paginatesTextByViewportAndStyle() {
        val document = ReaderDocument(
            id = DocumentId("txt-1"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = "a".repeat(200), range = TextRange(0, 200)),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
        )

        assertTrue(pages.size > 1)
        assertEquals(0, pages.first().pageIndex.current)
        assertEquals(ReaderLocation.TextOffset(0), pages.first().location)
    }

    @Test
    fun paginatedPagesKeepTextContinuous() {
        val text = "abcdefghijklmnopqrstuvwxyz".repeat(20)
        val document = ReaderDocument(
            id = DocumentId("txt-continuous"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 18f),
            viewportSize = ViewportSize(widthPx = 80, heightPx = 100),
        )

        pages.zipWithNext().forEach { (current, next) ->
            assertEquals(current.textRange?.end, next.textRange?.start)
        }
        assertEquals(text, pages.joinToString(separator = "") { page -> page.text })
    }
    @Test
    fun usesConservativePageSizeForWideGlyphs() {
        val text = "가".repeat(100)
        val document = ReaderDocument(
            id = DocumentId("txt-wide"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
        )

        assertTrue(pages.first().text.length <= 25)
    }

    @Test
    fun narrowGlyphsUseTheProportionalAdvanceInsteadOfHalfAnEm() {
        val english = "a".repeat(400)
        val korean = "가".repeat(400)
        val style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f)
        val viewportSize = ViewportSize(widthPx = 480, heightPx = 100)

        fun paginate(text: String) = engine.paginate(
            document = ReaderDocument(
                id = DocumentId(text.first().toString()),
                format = DocumentFormat.TXT,
                title = "Book",
                sections = listOf(
                    ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
                ),
            ),
            style = style,
            viewportSize = viewportSize,
        )

        val englishPages = paginate(english)
        val koreanPages = paginate(korean)

        // 24 em per line over 5 lines: a wide glyph takes one em, a narrow glyph 0.45 em. The old
        // half-em budget would have stopped at 240 narrow glyphs.
        assertEquals(265, englishPages.first().text.length)
        assertEquals(120, koreanPages.first().text.length)
        assertEquals(english, englishPages.joinToString(separator = "") { page -> page.text })
        assertEquals(korean, koreanPages.joinToString(separator = "") { page -> page.text })
    }

    @Test
    fun estimatedLinesWrapAtSpacesLikeTheRendererDoes() {
        // "aaaa bbbb cccc ..." at 10 narrow glyphs per line: a renderer breaks after "aaaa bbbb",
        // never mid-word, so the estimate has to leave the split word for the next line.
        val text = List(20) { index -> ('a' + index % 26).toString().repeat(4) }.joinToString(" ")
        val document = ReaderDocument(
            id = DocumentId("txt-wrap"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 90, heightPx = 40),
        )

        assertEquals("aaaa bbbb ", pages.first().text.take(10))
        assertTrue(pages.all { page -> page.text.isEmpty() || !page.text.startsWith(" ") })
        assertEquals(text, pages.joinToString(separator = "") { page -> page.text })
    }

    @Test
    fun measuredPageBreaksAreUsedVerbatim() {
        val text = "abcdefghij".repeat(60)
        val document = ReaderDocument(
            id = DocumentId("txt-measured"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )
        // Stands in for the reader's own text layout: it reports a page break every 150 characters.
        val renderedPageLength = 150
        val pageBreaker = ReaderPageBreaker { measured ->
            IntArray((measured.length + renderedPageLength - 1) / renderedPageLength) { page ->
                page * renderedPageLength
            }
        }

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = pageBreaker,
        )

        assertTrue(pages.dropLast(1).all { it.text.length == renderedPageLength })
        assertEquals(text, pages.joinToString(separator = "") { page -> page.text })
    }

    @Test
    fun measuredPagesIgnoreTheEstimatedLineCountAcrossFontSizes() {
        val text = "abcdefghij".repeat(60)
        val document = ReaderDocument(
            id = DocumentId("txt-measured-ignores-style"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )
        // A real measurement's page starts do not have to line up with any arithmetic line count;
        // this one grows farther apart than a real layout would, to prove the estimate is ignored.
        val renderedPageLength = 137
        val pageBreaker = ReaderPageBreaker { measured ->
            IntArray((measured.length + renderedPageLength - 1) / renderedPageLength) { page ->
                page * renderedPageLength
            }
        }
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)

        fun paginate(style: ReaderStyle) = engine.paginate(
            document = document,
            style = style,
            viewportSize = viewportSize,
            pageBreaker = pageBreaker,
        )

        val smallFontPages = paginate(ReaderStyle(fontSizeSp = 8f, lineHeightMultiplier = 1f))
        val largeFontPages = paginate(ReaderStyle(fontSizeSp = 40f, lineHeightMultiplier = 3f))

        assertEquals(
            smallFontPages.map { it.textRange },
            largeFontPages.map { it.textRange },
        )
    }

    @Test
    fun explicitLineBreaksDoNotOverflowPageLineCapacity() {
        val text = (1..20).joinToString(separator = "\n") { "x" }
        val document = ReaderDocument(
            id = DocumentId("txt-lines"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
        )

        assertTrue(pages.first().text.lines().count { line -> line.isNotEmpty() } <= 5)
        assertEquals(text, pages.joinToString(separator = "") { page -> page.text })
    }

}

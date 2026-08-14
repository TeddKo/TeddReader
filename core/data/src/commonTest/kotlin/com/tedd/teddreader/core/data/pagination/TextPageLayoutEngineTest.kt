package com.tedd.teddreader.core.data.pagination

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
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

        // 24 em per line over 5 lines: a wide glyph takes one em, a narrow glyph 0.48 em. The old
        // half-em budget would have stopped at 240 narrow glyphs.
        assertEquals(250, englishPages.first().text.length)
        assertEquals(120, koreanPages.first().text.length)
        assertEquals(english, englishPages.joinToString(separator = "") { page -> page.text })
        assertEquals(korean, koreanPages.joinToString(separator = "") { page -> page.text })
    }

    @Test
    fun latinPageFillsTheRenderedViewportWithoutOverrunningIt() {
        val english = "a".repeat(4000)
        val document = ReaderDocument(
            id = DocumentId("txt-latin"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = english, range = TextRange(0, english.length.toLong())),
            ),
        )

        // Reader pane measured on a foldable spread: 393 sp of text width and 753 sp of height at
        // 18 sp / 1.45 line height, so the pane renders 28 lines.
        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 18f, lineHeightMultiplier = 1.45f),
            viewportSize = ViewportSize(widthPx = 393, heightPx = 753),
        )

        val linesPerPage = 28
        val charactersPerLine = pages.first().text.length / linesPerPage
        assertEquals(45, charactersPerLine)
        // Sweeping 16 rendered panes on that device: the half-em budget of 43 left 3 lines of the
        // pane empty, and 48 per line needed 30 rendered lines, pushing the page tail past the clip.
        assertTrue(charactersPerLine > 43)
        assertTrue(charactersPerLine < 48)
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

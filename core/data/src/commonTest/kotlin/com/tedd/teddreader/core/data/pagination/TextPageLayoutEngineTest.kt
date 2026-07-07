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

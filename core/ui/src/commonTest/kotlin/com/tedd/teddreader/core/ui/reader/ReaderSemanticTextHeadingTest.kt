package com.tedd.teddreader.core.ui.reader

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderSemanticTextHeadingTest {
    private val text = "Chapter One\n\nBody text follows."
    private val headingEnd = 11L

    private fun semanticFor(level: Int) = buildReaderSemanticText(
        text = text,
        blocks = listOf(
            ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, headingEnd), level = level),
            ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(13, text.length.toLong())),
        ),
        lineWidthEm = 20f,
    )

    @Test
    fun everyHeadingLevelIsMarkedByABar() {
        (1..6).forEach { level ->
            val rendered = semanticFor(level).annotatedString.text
            assertTrue(
                rendered.startsWith("▌ Chapter One") || rendered.startsWith("▏ Chapter One"),
                "level $level was not marked, got: ${rendered.take(16)}",
            )
        }
    }

    @Test
    fun theBarThinsBelowTheChapterLevels() {
        assertTrue(semanticFor(1).annotatedString.text.startsWith("▌"))
        assertTrue(semanticFor(2).annotatedString.text.startsWith("▌"))
        assertTrue(semanticFor(3).annotatedString.text.startsWith("▏"))
    }

    @Test
    fun theBarIsDecorationAndNeverEntersTheDocumentsOwnText() {
        val semantic = semanticFor(level = 1)

        // The bar's characters map back to where the heading starts, so a search hit, a bookmark and
        // the reading position all keep pointing at text the book actually has.
        assertEquals(0, semantic.sourceOffsetFor(0))
        assertEquals(0, semantic.sourceOffsetFor(1))
        assertEquals(
            text.indexOf("Body"),
            semantic.sourceOffsetFor(semantic.annotatedString.text.indexOf("Body")),
        )
    }

    @Test
    fun aHeadingAddsNoPlaceholderOfItsOwn() {
        assertEquals(emptyList(), semanticFor(level = 1).placeholders)
    }

    @Test
    fun aHeadingWithNoTextIsNotMarked() {
        val semantic = buildReaderSemanticText(
            text = "Body only.",
            blocks = listOf(ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, 0), level = 1)),
            lineWidthEm = 20f,
        )

        assertEquals("Body only.", semantic.annotatedString.text)
    }
}

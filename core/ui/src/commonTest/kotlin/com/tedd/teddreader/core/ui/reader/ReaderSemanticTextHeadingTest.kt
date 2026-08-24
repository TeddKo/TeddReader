package com.tedd.teddreader.core.ui.reader

import androidx.compose.ui.text.font.FontWeight
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that a heading is set the way the document asks and nothing is added to it on the way to the page.
 *
 * A reading system draws a heading with type — heavier, larger, spaced away from the prose — and writes no
 * characters of its own. The renderer used to prefix a bar glyph, which put decoration the book never asked
 * for in front of every title and shifted every rendered offset after it; these cases are what keeps both
 * halves of that from coming back.
 */
class ReaderSemanticTextHeadingTest {
    /** A heading followed by prose, which is the shape every case here renders. */
    private val text = "Chapter One\n\nBody text follows."

    /** Where "Chapter One" ends, so the heading block covers the title and nothing more. */
    private val headingEnd = 11L

    /**
     * @param level the heading level to render at.
     * @return the rendered page for that level, with a text column wide enough that nothing wraps.
     */
    private fun semanticFor(level: Int) = buildReaderSemanticText(
        text = text,
        blocks = listOf(
            ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, headingEnd), level = level),
            ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(13, text.length.toLong())),
        ),
        lineWidthEm = 20f,
    )

    /** A heading renders its own words and nothing else — no bar, no bullet, no marker of the reader's own. */
    @Test
    fun everyHeadingLevelRendersOnlyTheDocumentsOwnWords() {
        (1..6).forEach { level ->
            val rendered = semanticFor(level).annotatedString.text
            assertTrue(
                rendered.startsWith("Chapter One"),
                "level $level had something prefixed to it, got: ${rendered.take(16)}",
            )
        }
    }

    /** What sets a heading apart is its type: bold, and larger the shallower the level. */
    @Test
    fun aHeadingIsSetApartByItsTypeRatherThanByAMarker() {
        val levelOne = semanticFor(1).annotatedString.spanStyles.first { it.start == 0 }
        val levelThree = semanticFor(3).annotatedString.spanStyles.first { it.start == 0 }

        assertEquals(FontWeight.Bold, levelOne.item.fontWeight)
        assertTrue(
            levelOne.item.fontSize.value > levelThree.item.fontSize.value,
            "a level 1 heading should be set larger than a level 3, got ${levelOne.item.fontSize} vs ${levelThree.item.fontSize}",
        )
    }

    /**
     * Every rendered character maps back to the offset it came from, so a search hit, a bookmark and the
     * reading position all keep pointing at text the book actually has.
     */
    @Test
    fun renderedOffsetsStillAddressTheDocumentsOwnText() {
        val semantic = semanticFor(level = 1)

        assertEquals(0, semantic.sourceOffsetFor(0))
        assertEquals(
            text.indexOf("Body"),
            semantic.sourceOffsetFor(semantic.annotatedString.text.indexOf("Body")),
        )
    }

    /** A heading reserves no inline box — only pictures and rules do, and a stray box would occupy a line. */
    @Test
    fun aHeadingAddsNoPlaceholderOfItsOwn() {
        assertEquals(emptyList(), semanticFor(level = 1).placeholders)
    }

    /** An empty heading block, which a malformed document can carry, renders nothing of its own. */
    @Test
    fun aHeadingWithNoTextRendersNothing() {
        val semantic = buildReaderSemanticText(
            text = "Body only.",
            blocks = listOf(ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, 0), level = 1)),
            lineWidthEm = 20f,
        )

        assertEquals("Body only.", semantic.annotatedString.text)
    }
}

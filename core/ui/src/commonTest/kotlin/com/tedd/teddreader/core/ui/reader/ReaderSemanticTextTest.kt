package com.tedd.teddreader.core.ui.reader

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import com.tedd.teddreader.core.common.model.ReaderSpan
import com.tedd.teddreader.core.common.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderSemanticTextTest {
    @Test
    fun helperAddsVisibleSemanticsAndClampsInlineStyles() {
        val text = "Heading\nquote\nitem\ncode\ncell\n\n"
        val semantic = buildReaderSemanticText(
            text = text.substring(8, 30),
            range = TextRange(8, 30),
            blocks = listOf(
                ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, 7), level = 2),
                ReaderBlock(ReaderBlockKind.QUOTE, TextRange(8, 13)),
                ReaderBlock(ReaderBlockKind.LIST_ITEM, TextRange(14, 18), level = 2, label = "3."),
                ReaderBlock(
                    ReaderBlockKind.PREFORMATTED,
                    TextRange(19, 23),
                    spans = listOf(ReaderSpan(TextRange(19, 23), ReaderInlineStyle.MONOSPACE)),
                ),
                ReaderBlock(ReaderBlockKind.TABLE_HEADER_CELL, TextRange(24, 28)),
                ReaderBlock(ReaderBlockKind.IMAGE, TextRange(29, 30), imageHref = "images/pic.png", label = "Alt"),
            ),
        )

        assertTrue(!semantic.annotatedString.text.contains("H2 "))
        assertTrue(semantic.annotatedString.text.startsWith("│ quote"))
        assertTrue(semantic.annotatedString.text.contains("  3. item"))
        assertTrue(semantic.annotatedString.text.contains("cell"))
        assertEquals(1, semantic.placeholders.size)
        assertEquals("images/pic.png", semantic.placeholders.single().href)
        val placeholder = semantic.placeholders.single()
        assertEquals(
            1,
            semantic.annotatedString
                .getStringAnnotations(placeholder.start, placeholder.end)
                .count { it.item == placeholder.id },
        )
        assertEquals(29, semantic.sourceOffsetFor(placeholder.start))
        assertEquals(30, semantic.sourceOffsetFor(placeholder.end))
    }
}

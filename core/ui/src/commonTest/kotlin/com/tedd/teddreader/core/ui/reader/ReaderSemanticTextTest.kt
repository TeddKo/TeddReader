package com.tedd.teddreader.core.ui.reader

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import androidx.compose.ui.text.style.TextAlign
import com.tedd.teddreader.core.common.model.ReaderSpan
import com.tedd.teddreader.core.common.model.ReaderTextAlign
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

    @Test
    fun aStandaloneImageIsItsOwnCentredParagraph() {
        // Without a paragraph of its own the picture shared a line with the prose around it, which is
        // what made the text run across it, and the centring the book asked for was thrown away.
        val text = "before\n\n\nafter"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, 6)),
                ReaderBlock(
                    ReaderBlockKind.IMAGE,
                    TextRange(8, 9),
                    imageHref = "images/plate.jpg",
                    align = ReaderTextAlign.CENTER,
                ),
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(9, 14)),
            ),
        )

        val placeholder = semantic.placeholders.single()
        val imageParagraph = semantic.annotatedString.paragraphStyles.single { range ->
            range.start == placeholder.start && range.end == placeholder.end
        }
        assertEquals(TextAlign.Center, imageParagraph.item.textAlign)
    }

    @Test
    fun aPictureWrittenInsideASentenceStaysInThatParagraph() {
        // `<p>앞 문장이 있고 <img/> 뒤 문장이 이어진다.</p>`: the picture belongs to the paragraph, so it
        // gets no paragraph of its own — giving it one would both break the sentence and overlap the
        // enclosing paragraph style, which AnnotatedString rejects outright.
        val text = "앞 문장이 있고 ￼ 뒤 문장이 이어진다."
        val imageStart = text.indexOf('￼').toLong()
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, text.length.toLong()), align = ReaderTextAlign.JUSTIFY),
                ReaderBlock(
                    ReaderBlockKind.IMAGE,
                    TextRange(imageStart, imageStart + 1),
                    imageHref = "images/gaiji.png",
                    align = ReaderTextAlign.CENTER,
                ),
            ),
        )

        val placeholder = semantic.placeholders.single()
        assertEquals(imageStart.toInt(), placeholder.start)
        // One paragraph, the sentence's own, spanning the picture with it.
        val paragraph = semantic.annotatedString.paragraphStyles.single()
        assertEquals(0, paragraph.start)
        assertEquals(text.length, paragraph.end)
        assertEquals(TextAlign.Justify, paragraph.item.textAlign)
    }

    @Test
    fun aChapterHeadingTheBookDoesNotAlignIsSetFlushLeftBehindItsBar() {
        val text = "2화 기회"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, text.length.toLong()), level = 1)),
        )

        // The bar is what marks a heading now. Centring the words moved them away from it as the
        // title grew, so the heading is set flush left like the prose it introduces.
        assertEquals(TextAlign.Unspecified, semantic.annotatedString.paragraphStyles.single().item.textAlign)
        assertTrue(semantic.annotatedString.text.startsWith("▌ 2화 기회"))
    }
}

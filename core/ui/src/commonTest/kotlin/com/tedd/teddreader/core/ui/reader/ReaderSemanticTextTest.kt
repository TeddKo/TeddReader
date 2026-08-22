package com.tedd.teddreader.core.ui.reader

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.em
import com.tedd.teddreader.core.common.model.ReaderSpan
import androidx.compose.ui.text.AnnotatedString
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import com.tedd.teddreader.core.common.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what a page's rendered text contains, and that every added character still maps back to the document.
 *
 * The cases are the decisions rendering makes that a reader would notice if they broke: what marks a block,
 * how much space is left between two blocks, whether a plate gets its own paragraph, whether a picture inside
 * a sentence stays in it, and where a heading is aligned.
 */
class ReaderSemanticTextTest {
    /**
     * Two paragraphs the book separates by a stated margin are separated by exactly that much, and by one
     * character that draws nothing.
     *
     * The stored text puts a blank line between two blocks, and drawing it costs a whole line of the reader's
     * own line height whatever the book asked for — a book stating `margin-bottom: 10px` (0.625em) had its
     * paragraphs pushed nearly three times that far apart. The gap is one line set to the collapsed margin
     * instead.
     */
    @Test
    fun aStatedMarginBecomesAGapOfExactlyThatHeight() {
        val text = "First.\n\nSecond."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, 6),
                    style = ReaderBlockStyle(marginBottomEm = 0.625f),
                ),
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(8, text.length.toLong()),
                    style = ReaderBlockStyle(marginTopEm = 0f),
                ),
            ),
        )

        assertEquals("First.​Second.", semantic.annotatedString.text)
        val gap = semantic.annotatedString.paragraphStyles.single { it.start == 6 && it.end == 7 }
        assertEquals(0.625.em, gap.item.lineHeight)
        assertEquals(text.indexOf("Second"), semantic.sourceOffsetFor(semantic.annotatedString.text.indexOf("Second")))
    }

    /**
     * A book stating no margin gets no gap where its own indents already separate its paragraphs.
     *
     * `margin: 0` with a first-line indent is the classic setting for running prose, and giving it a blank
     * line anyway spread every such book to about twice its length.
     */
    @Test
    fun aMarginOfZeroLeavesNoGapWhereAnIndentAlreadySeparates() {
        val text = "First.\n\nSecond."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, 6),
                    style = ReaderBlockStyle(marginBottomEm = 0f, textIndentEm = 1f),
                ),
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(8, text.length.toLong()),
                    style = ReaderBlockStyle(marginTopEm = 0f, textIndentEm = 1f),
                ),
            ),
        )

        assertEquals("First.Second.", semantic.annotatedString.text)
        assertEquals(2, semantic.annotatedString.paragraphStyles.size)
    }

    /**
     * Two paragraphs a book separates by neither a gap nor an indent still get the smallest gap that keeps
     * them apart.
     *
     * On the wide page such a book was typeset for, line length alone made the breaks legible; in a phone's
     * column the same setting is a wall of type where one paragraph ends and the next begins mid-line.
     */
    @Test
    fun paragraphsWithNeitherGapNorIndentAreStillToldApart() {
        val text = "First.\n\nSecond."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, 6), style = ReaderBlockStyle(marginBottomEm = 0f)),
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(8, text.length.toLong()),
                    style = ReaderBlockStyle(marginTopEm = 0f, textIndentEm = 0f),
                ),
            ),
        )

        val gap = semantic.annotatedString.paragraphStyles.single { it.start == 6 && it.end == 7 }
        assertEquals(0.35.em, gap.item.lineHeight)
    }

    /** Adjacent margins collapse to the larger of the two, and a padding on either side adds to it. */
    @Test
    fun adjacentMarginsCollapseAndPaddingAddsToThem() {
        val text = "First.\n\nSecond."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, 6),
                    style = ReaderBlockStyle(marginBottomEm = 0.5f, paddingBottomEm = 0.25f),
                ),
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(8, text.length.toLong()),
                    style = ReaderBlockStyle(marginTopEm = 1.5f),
                ),
            ),
        )

        val gap = semantic.annotatedString.paragraphStyles.single { it.start == 6 && it.end == 7 }
        assertEquals(1.75.em, gap.item.lineHeight)
    }

    /**
     * A quotation is inset by the space the book gives it, on every line, with `text-indent` added on the
     * first line only — which is how CSS composes the two.
     */
    @Test
    fun aBlockIsInsetByTheSpaceTheBookGivesIt() {
        val text = "Quoted words."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.QUOTE,
                    TextRange(0, text.length.toLong()),
                    style = ReaderBlockStyle(marginStartEm = 0.5f, paddingStartEm = 1f, textIndentEm = 1f),
                ),
            ),
        )

        val indent = semantic.annotatedString.paragraphStyles.single().item.textIndent
        assertEquals(2.5.em, indent?.firstLine)
        assertEquals(1.5.em, indent?.restLine)
    }
    /**
     * Renders a slice of a document — offsets 8..30 of a longer text — with one block of every kind.
     *
     * Rendering a slice rather than the whole thing is the point: blocks are clamped to the range, the
     * heading that starts before it contributes nothing, and the picture's placeholder still reports the
     * document offsets 29..30 rather than positions inside the slice.
     */
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
        assertTrue(semantic.annotatedString.text.startsWith("quote"))
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

    /**
     * A picture alone in its block gets a paragraph of its own, carrying the alignment the book asked for.
     *
     * Without it the picture shared a line with the prose around it — which is what made the text run across
     * the plate — and the centring the book asked for was thrown away.
     */
    @Test
    fun aStandaloneImageIsItsOwnCentredParagraph() {
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

    /**
     * The mirror image of the case above: `<p>앞 문장이 있고 <img/> 뒤 문장이 이어진다.</p>`.
     *
     * The picture belongs to the sentence, so it gets no paragraph of its own — giving it one would break
     * the sentence and overlap the enclosing paragraph style, which `AnnotatedString` rejects outright. The
     * one paragraph that remains is the sentence's own, spanning the picture with it.
     */
    @Test
    fun aPictureWrittenInsideASentenceStaysInThatParagraph() {
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
        val paragraph = semantic.annotatedString.paragraphStyles.single()
        assertEquals(0, paragraph.start)
        assertEquals(text.length, paragraph.end)
        assertEquals(TextAlign.Start, paragraph.item.textAlign)
    }

    /**
     * A book's `text-align: justify` is honored where justification can actually be set, and falls back to a
     * ragged edge where it cannot.
     *
     * Justifying here can only widen the spaces between words. A Latin column absorbs that; a CJK one has
     * few spaces and breaks between characters instead, so the same setting tore holes across every line of
     * Korean prose wide enough to read as a second column.
     */
    @Test
    fun justificationIsKeptWhereTheColumnCanCarryIt() {
        fun alignmentOf(text: String) = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, text.length.toLong()), align = ReaderTextAlign.JUSTIFY),
            ),
        ).annotatedString.paragraphStyles.single().item.textAlign

        assertEquals(TextAlign.Justify, alignmentOf("Prose set in a language whose lines break at spaces."))
        assertEquals(TextAlign.Start, alignmentOf("공백이 드물고 글자 사이에서 줄이 나뉘는 본문이다."))
    }

    @Test
    fun neutralInlineCssStyleIsApplied() {
        val text = "plain soft"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, text.length.toLong()),
                    spans = listOf(
                        ReaderSpan(
                            range = TextRange(6, 10),
                            style = null,
                            cssStyle = ReaderBlockStyle(fontScale = 0.8f, italic = true),
                        ),
                    ),
                ),
            ),
        )

        val style = semantic.annotatedString.spanStyles.single { it.start == 6 && it.end == 10 }.item
        assertEquals(0.8.em, style.fontSize)
        assertEquals(FontStyle.Italic, style.fontStyle)
    }

    /**
     * A heading the book does not align itself keeps the alignment of the prose around it, and its words are
     * the only thing rendered.
     *
     * Deciding an alignment the book never stated would fight the book's own layout, and prefixing a marker
     * would put a character in front of every title that the document does not have.
     */
    @Test
    fun aChapterHeadingTheBookDoesNotAlignIsLeftAsTheBookWroteIt() {
        val text = "2화 기회"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, text.length.toLong()), level = 1)),
        )

        assertEquals(TextAlign.Unspecified, semantic.annotatedString.paragraphStyles.single().item.textAlign)
        assertEquals("2화 기회", semantic.annotatedString.text)
    }

    @Test
    fun floatedImageKeepsOnlyPostImageSliceInsideItsNestedPlaceholder() {
        val text = "앞 ￼Body tail"
        val imageStart = text.indexOf('￼').toLong()
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, text.length.toLong())),
                ReaderBlock(
                    ReaderBlockKind.IMAGE,
                    TextRange(imageStart, imageStart + 1),
                    imageHref = "images/float.png",
                    float = com.tedd.teddreader.core.common.model.ReaderFloat.START,
                ),
            ),
            floatTextFitter = { request ->
                ReaderFloatPlacement(
                    nestedRange = TextRange(request.imageBlock.range.end, request.imageBlock.range.end + 4),
                    nestedText = ReaderSemanticText(
                        annotatedString = AnnotatedString("Body"),
                        offsetMap = intArrayOf(3, 4, 5, 6, 7),
                        placeholders = emptyList(),
                    ),
                )
            },
        )

        val placeholder = semantic.placeholders.single()
        assertEquals("Body", placeholder.floatContent?.text?.annotatedString?.text)
        assertTrue(semantic.annotatedString.text.startsWith("앞 ￼"))
        assertTrue(semantic.annotatedString.text.endsWith(" tail"))
        assertEquals(2, semantic.sourceOffsetFor(placeholder.start))
        assertEquals(7, semantic.sourceOffsetFor(placeholder.end))
    }

    @Test
    fun containerDecorationsAreOrderedOuterToInnerByLevel() {
        val text = "body"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, text.length.toLong()),
                    level = 2,
                    style = ReaderBlockStyle(boxStyle = com.tedd.teddreader.core.common.model.ReaderBoxStyle(backgroundColor = com.tedd.teddreader.core.common.model.ReaderColor(0xFF222222))),
                ),
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, text.length.toLong()),
                    level = 1,
                    style = ReaderBlockStyle(boxStyle = com.tedd.teddreader.core.common.model.ReaderBoxStyle(backgroundColor = com.tedd.teddreader.core.common.model.ReaderColor(0xFF111111))),
                ),
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, text.length.toLong())),
            ),
            publisherColorsEnabled = true,
        )

        assertEquals(listOf(0xFF111111, 0xFF222222), semantic.containerDecorations.map { it.boxStyle.backgroundColor?.argb })
    }

    @Test
    fun containerBackgroundIsNotDuplicatedIntoBlockSpanBackground() {
        val text = "body"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, text.length.toLong()),
                    style = ReaderBlockStyle(boxStyle = com.tedd.teddreader.core.common.model.ReaderBoxStyle(backgroundColor = com.tedd.teddreader.core.common.model.ReaderColor(0xFF112233))),
                ),
            ),
            publisherColorsEnabled = true,
        )

        assertTrue(semantic.annotatedString.spanStyles.none { it.item.background != androidx.compose.ui.graphics.Color(0xFF112233) })
        assertEquals(1, semantic.containerDecorations.size)
    }

    @Test
    fun publisherFontAndColorStylingCanBeGatedOffForUserFontOverrides() {
        val text = "styled"
        val href = "OPS/fonts/book.otf"
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, text.length.toLong()),
                    style = ReaderBlockStyle(
                        fontHref = href,
                        fontFamily = com.tedd.teddreader.core.common.model.ReaderFontFamily.SERIF,
                        foregroundColor = com.tedd.teddreader.core.common.model.ReaderColor(0xFF112233),
                    ),
                ),
            ),
            embeddedFontFamiliesByHref = mapOf(href to androidx.compose.ui.text.font.FontFamily.Cursive),
            publisherColorsEnabled = false,
            publisherFontsEnabled = false,
        )

        assertTrue(semantic.annotatedString.spanStyles.isEmpty())
    }

    /**
     * A container contributes decorations only — never a span. Its inherited styling is baked into the
     * leaf blocks by the parser, and a container span also covered the zero-width gap characters between
     * paragraphs, which is how underline fragments and mis-sized gaps appeared in the blank space.
     */
    @Test
    fun aContainerContributesNoSpanOfItsOwn() {
        val text = "First.\n\nSecond."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, text.length.toLong()),
                    style = ReaderBlockStyle(
                        fontScale = 0.9f,
                        underline = true,
                        boxStyle = com.tedd.teddreader.core.common.model.ReaderBoxStyle(
                            backgroundColor = com.tedd.teddreader.core.common.model.ReaderColor(0xFF112233),
                        ),
                    ),
                ),
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, 6), style = ReaderBlockStyle(marginBottomEm = 1f)),
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(8, text.length.toLong())),
            ),
            publisherColorsEnabled = true,
        )

        val gapIndex = semantic.annotatedString.text.indexOf('​')
        assertTrue(gapIndex >= 0)
        // The gap character carries only its own size-defining span, never styling from any block.
        assertTrue(
            semantic.annotatedString.spanStyles
                .filter { it.start <= gapIndex && gapIndex < it.end }
                .all { it.start == gapIndex && it.end == gapIndex + 1 },
        )
        // And the container itself produced no text-decoration or font-scale span at all.
        assertTrue(semantic.annotatedString.spanStyles.none { it.item.textDecoration != null })
        assertEquals(1, semantic.containerDecorations.size)
    }

    /**
     * The book's line height rides the reader's slider, anchored at the slider's neutral point: at the
     * default the block draws exactly what the book stated, and doubling the slider doubles it. Replacing
     * the slider's value outright made it dead in styled books; multiplying by its raw value drew every
     * styled book 45% looser than it asked for before the reader touched anything.
     */
    @Test
    fun publisherLineHeightIsExactAtTheDefaultSliderAndScalesWithIt() {
        fun lineHeightAt(multiplier: Float) = buildReaderSemanticText(
            text = "Prose.",
            range = TextRange(0, 6),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, 6),
                    style = ReaderBlockStyle(lineHeightScale = 1.5f),
                ),
            ),
            lineHeightMultiplier = multiplier,
        ).annotatedString.paragraphStyles.single().item.lineHeight

        assertEquals(1.5.em, lineHeightAt(com.tedd.teddreader.core.common.model.ReaderDefaultLineHeightMultiplier))
        assertEquals(
            3.em,
            lineHeightAt(com.tedd.teddreader.core.common.model.ReaderDefaultLineHeightMultiplier * 2f),
        )
    }

    /**
     * A genuine wrapper whose range happens to coincide with its only child's — a chapter-title box
     * holding one heading — keeps its own padding in the gap after it. Treating every range-coincident
     * container as the leaf's twin dropped that reservation while the painter still grew the box by its
     * padding, which drew the box's bottom border straight through the prose below it.
     */
    @Test
    fun aWrapperBoxAroundASingleHeadingReservesItsOwnPadding() {
        val text = "Title\n\nProse."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, 5),
                    style = ReaderBlockStyle(
                        paddingBottomEm = 3f,
                        boxStyle = com.tedd.teddreader.core.common.model.ReaderBoxStyle(
                            borderBottom = com.tedd.teddreader.core.common.model.ReaderBorder(widthPx = 2f),
                        ),
                    ),
                ),
                ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, 5), level = 1),
                ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(7, text.length.toLong())),
            ),
            emInPx = 16f,
        )

        val gap = semantic.annotatedString.paragraphStyles.single { it.item.lineHeight != androidx.compose.ui.unit.TextUnit.Unspecified && it.end == it.start + 1 }
        // Heading default margin (0.67) collapses with the paragraph's (1) to 1, plus the wrapper's own
        // 3em padding and its 2px border (2/16 em).
        assertEquals(1f + 3f + 2f / 16f, gap.item.lineHeight.value, 0.001f)
    }

    /**
     * A styled paragraph's container twin — the CONTAINER block the parser records over exactly the same
     * range — adds nothing to the gap the paragraph's own margin already sizes. Counting both doubled
     * every styled paragraph's spacing.
     */
    @Test
    fun aParagraphsContainerTwinDoesNotDoubleItsGap() {
        val text = "First.\n\nSecond."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.CONTAINER,
                    TextRange(0, 6),
                    style = ReaderBlockStyle(marginBottomEm = 1f),
                ),
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, 6),
                    style = ReaderBlockStyle(marginBottomEm = 1f),
                ),
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(8, text.length.toLong()),
                    style = ReaderBlockStyle(marginTopEm = 0f),
                ),
            ),
        )

        val gap = semantic.annotatedString.paragraphStyles.single { it.start == 6 && it.end == 7 }
        assertEquals(1.em, gap.item.lineHeight)
    }

    /**
     * No span ever covers a placeholder character. A placeholder's reserved box is stated in em, and
     * Compose resolves that em against the font in force at its position — inside a `0.85em` block span
     * the picture was reserved 15% smaller than the size every other consumer computed in base em, which
     * clipped the text fitted beside a float.
     */
    @Test
    fun noSpanCoversAPlaceholderCharacter() {
        val text = "before ￼ after"
        val imageOffset = text.indexOf('￼').toLong()
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, text.length.toLong()),
                    style = ReaderBlockStyle(fontScale = 0.85f),
                ),
                ReaderBlock(
                    ReaderBlockKind.IMAGE,
                    TextRange(imageOffset, imageOffset + 1),
                    imageHref = "img.png",
                ),
            ),
        )

        val placeholderIndex = semantic.placeholders.single().start
        assertTrue(
            semantic.annotatedString.spanStyles.none { it.start <= placeholderIndex && placeholderIndex < it.end },
        )
    }

    /** The parser-accumulated inset is what indents a paragraph, wrappers included. */
    @Test
    fun theAccumulatedInsetIndentsTheParagraph() {
        val text = "Quoted."
        val semantic = buildReaderSemanticText(
            text = text,
            range = TextRange(0, text.length.toLong()),
            blocks = listOf(
                ReaderBlock(
                    ReaderBlockKind.PARAGRAPH,
                    TextRange(0, text.length.toLong()),
                    style = ReaderBlockStyle(insetStartEm = 3f),
                ),
            ),
        )

        val paragraph = semantic.annotatedString.paragraphStyles.single()
        assertEquals(3.em, paragraph.item.textIndent?.restLine)
    }
}

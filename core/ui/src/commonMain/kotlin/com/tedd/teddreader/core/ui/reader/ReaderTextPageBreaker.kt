package com.tedd.teddreader.core.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.layoutKey
import com.tedd.teddreader.core.designsystem.readerTextStyle

/**
 * Page breaker backed by the same text layout the reader page draws with.
 *
 * [widthPx] and [heightPx] must be the drawn text area, so a page holds exactly the lines that fit
 * in it: the break follows measured line boxes rather than a `height / lineHeight` count, which is
 * what keeps the last line whole when the reader's font size or line height changes.
 *
 * ponytail: lays the whole document out once per style/size change. Fine for the documents this
 * reader opens; switch to chunked measurement if a book ever makes that layout too slow.
 */
@Composable
fun rememberReaderPageBreaker(style: ReaderStyle, widthPx: Int, heightPx: Int): ReaderPageBreaker? {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val textStyle = style.readerTextStyle()
    val density = LocalDensity.current
    // Keyed on the type rather than the whole text style: the colour rides along in TextStyle, so a
    // theme switch handed back a different instance and every page in the book was measured again for
    // a change that cannot move a line. The captured style keeps whatever colour it was built with,
    // which is fine because nothing here draws.
    return remember(measurer, style.layoutKey(), widthPx, heightPx, density) {
        // No pane, no measurement. A breaker built before the pane is measured can only answer "I
        // measured nothing", and announcing it hands the reader something that silently disables
        // measured pagination for the whole book — every page then comes from the estimate, which
        // cannot know the line height the book's stylesheet sets.
        if (widthPx <= 0 || heightPx <= 0) return@remember null
        // Same em conversion the render side uses (see EpubPageSurface), so a standalone image is
        // paginated with the exact box it will actually be drawn into.
        val fontPx = with(density) { style.fontSizeSp.sp.toPx() }
        val lineWidthEm = if (fontPx > 0f) widthPx / fontPx else 0f
        val maxHeightEm = if (fontPx > 0f) heightPx / fontPx else 0f
        // An image's intrinsic size is in CSS pixels, which are density-independent, so one em is the
        // font size in dp rather than in device pixels.
        val emInPx = style.fontSizeSp * density.fontScale
        ReaderPageBreaker { text, blocks ->
            if (text.isEmpty()) {
                IntArray(0)
            } else {
                val semanticText = buildReaderSemanticText(
                    text = text,
                    blocks = blocks,
                    lineWidthEm = lineWidthEm,
                    maxHeightEm = maxHeightEm,
                    emInPx = emInPx,
                )
                val layout = measurer.measure(
                    text = semanticText.annotatedString,
                    style = textStyle,
                    constraints = Constraints(maxWidth = widthPx),
                    placeholders = semanticText.placeholders.map { placeholder ->
                        AnnotatedString.Range(
                            item = placeholder.placeholder,
                            start = placeholder.start,
                            end = placeholder.end,
                        )
                    },
                )
                val starts = mutableListOf(0)
                var pageTop = layout.getLineTop(0)
                // The chapter is laid out once, in full, and split by line position; each page is then
                // drawn on its own. The two layouts never agree to the pixel — justified text, and an
                // opening line that is a middle line here but a first line there, shift things a
                // little — so a page filled to the last hairline loses the bottom of its final line.
                // One line is held back, which is the smallest amount that absorbs any disagreement
                // rather than most of it. It costs a page a line where the fit was that tight, and
                // a clipped line costs the reader a line of the book.
                // The first line is only a sample; a chapter mixes heading lines, quote lines and
                // picture lines, and the line that lands on a page boundary may be taller than it.
                // A slack of one page-relative step covers that without depending on which line
                // happened to be measured.
                val firstLineHeight = layout.getLineBottom(0) - layout.getLineTop(0)
                val usableHeight = heightPx - maxOf(firstLineHeight * LineSlack, heightPx * PageSlack)
                for (line in 1 until layout.lineCount) {
                    // A line that would reach past the bottom of the pane starts the next page. Using
                    // the measured box bottom keeps this correct when line boxes are not uniform.
                    if (layout.getLineBottom(line) - pageTop > usableHeight) {
                        starts += semanticText.sourceOffsetFor(layout.getLineStart(line))
                        pageTop = layout.getLineTop(line)
                    }
                }
                starts.toIntArray()
            }
        }
    }
}

/** Lines held back from each page, so the drawn page can differ from the measured one. */
private const val LineSlack = 1.0f

/** Floor on that slack as a share of the page, for pages whose lines vary in height. */
private const val PageSlack = 0.04f

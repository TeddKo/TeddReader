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
fun rememberReaderPageBreaker(style: ReaderStyle, widthPx: Int, heightPx: Int): ReaderPageBreaker {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val textStyle = style.readerTextStyle()
    val density = LocalDensity.current
    return remember(measurer, textStyle, widthPx, heightPx, density) {
        // Same em conversion the render side uses (see EpubPageSurface), so a standalone image is
        // paginated with the exact box it will actually be drawn into.
        val fontPx = with(density) { style.fontSizeSp.sp.toPx() }
        val lineWidthEm = if (fontPx > 0f) widthPx / fontPx else 0f
        val maxHeightEm = if (fontPx > 0f) heightPx / fontPx else 0f
        ReaderPageBreaker { text, blocks ->
            if (widthPx <= 0 || heightPx <= 0 || text.isEmpty()) {
                IntArray(0)
            } else {
                val semanticText = buildReaderSemanticText(
                    text = text,
                    blocks = blocks,
                    lineWidthEm = lineWidthEm,
                    maxHeightEm = maxHeightEm,
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
                for (line in 1 until layout.lineCount) {
                    // A line that would reach past the bottom of the pane starts the next page. Using
                    // the measured box bottom keeps this correct when line boxes are not uniform.
                    if (layout.getLineBottom(line) - pageTop > heightPx) {
                        starts += semanticText.sourceOffsetFor(layout.getLineStart(line))
                        pageTop = layout.getLineTop(line)
                    }
                }
                starts.toIntArray()
            }
        }
    }
}

package com.tedd.teddreader.core.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
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
    return remember(measurer, textStyle, widthPx, heightPx) {
        ReaderPageBreaker { text ->
            if (widthPx <= 0 || heightPx <= 0 || text.isEmpty()) {
                IntArray(0)
            } else {
                val layout = measurer.measure(
                    text = AnnotatedString(text),
                    style = textStyle,
                    constraints = Constraints(maxWidth = widthPx),
                )
                val starts = mutableListOf(0)
                var pageTop = layout.getLineTop(0)
                for (line in 1 until layout.lineCount) {
                    // A line that would reach past the bottom of the pane starts the next page. Using
                    // the measured box bottom keeps this correct when line boxes are not uniform.
                    if (layout.getLineBottom(line) - pageTop > heightPx) {
                        starts += layout.getLineStart(line)
                        pageTop = layout.getLineTop(line)
                    }
                }
                starts.toIntArray()
            }
        }
    }
}

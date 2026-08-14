package com.tedd.teddreader.core.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import com.tedd.teddreader.core.common.model.ReaderLineBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.designsystem.readerTextStyle

/**
 * Line breaker backed by the same text layout the reader page draws with.
 *
 * [widthPx] must be the width of the drawn text area, so the measured breaks are exactly the ones
 * that will appear on screen and a page of N lines fills N lines of the viewport.
 *
 * ponytail: lays the whole document out once per style/width change. Fine for the documents this
 * reader opens; switch to per-page chunked measurement if a book ever makes that layout too slow.
 */
@Composable
fun rememberReaderLineBreaker(style: ReaderStyle, widthPx: Int): ReaderLineBreaker {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val textStyle = style.readerTextStyle()
    return remember(measurer, textStyle, widthPx) {
        ReaderLineBreaker { text ->
            if (widthPx <= 0 || text.isEmpty()) {
                IntArray(0)
            } else {
                val layout = measurer.measure(
                    text = AnnotatedString(text),
                    style = textStyle,
                    constraints = Constraints(maxWidth = widthPx),
                )
                IntArray(layout.lineCount) { line -> layout.getLineStart(line) }
            }
        }
    }
}

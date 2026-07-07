package com.tedd.teddreader.core.data.pagination

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import org.koin.core.annotation.Single

@Single
class TextPageLayoutEngine {
    fun paginate(
        document: ReaderDocument,
        style: ReaderStyle,
        viewportSize: ViewportSize,
    ): List<PageWindow> {
        val fullText = document.sections.joinToString(separator = "\n") { section -> section.text }
        if (fullText.isBlank()) return emptyList()

        val layout = pageLayout(style, viewportSize)
        val ranges = splitPageRanges(
            text = fullText,
            charsPerLine = layout.charsPerLine,
            linesPerPage = layout.linesPerPage,
        )

        return ranges.mapIndexed { index, range ->
            PageWindow(
                pageIndex = PageIndex(current = index, total = ranges.size),
                location = locationFor(document, range.start),
                text = fullText.substring(range.start.toInt(), range.end.toInt()),
                textRange = range,
            )
        }
    }

    private fun pageLayout(style: ReaderStyle, viewportSize: ViewportSize): PageLayout {
        val charWidth = style.fontSizeSp.coerceAtLeast(1f)
        val lineHeight = (style.fontSizeSp * style.lineHeightMultiplier).coerceAtLeast(1f)
        return PageLayout(
            charsPerLine = (viewportSize.widthPx / charWidth).toInt().coerceAtLeast(1),
            linesPerPage = (viewportSize.heightPx / lineHeight).toInt().coerceAtLeast(1),
        )
    }

    private fun splitPageRanges(
        text: String,
        charsPerLine: Int,
        linesPerPage: Int,
    ): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        var start = 0
        while (start < text.length) {
            var index = start
            var usedLines = 0
            var end = start

            while (index < text.length && usedLines < linesPerPage) {
                val newline = text.indexOf('\n', startIndex = index).takeIf { it >= 0 } ?: text.length
                val lineEnd = newline
                val lineChars = lineEnd - index

                if (lineChars == 0) {
                    usedLines += 1
                    index = (newline + 1).coerceAtMost(text.length)
                    end = index
                    continue
                }

                val remainingLines = linesPerPage - usedLines
                val maxChars = charsPerLine * remainingLines
                if (lineChars > maxChars) {
                    end = index + maxChars
                    index = end
                    break
                }

                usedLines += visualLineCount(lineChars, charsPerLine)
                index = lineEnd
                end = index
                if (newline < text.length) {
                    index = newline + 1
                    end = index
                }
            }

            if (end <= start) end = (start + 1).coerceAtMost(text.length)
            ranges += TextRange(start.toLong(), end.toLong())
            start = end
        }
        return ranges
    }

    private fun visualLineCount(charCount: Int, charsPerLine: Int): Int =
        ((charCount + charsPerLine - 1) / charsPerLine).coerceAtLeast(1)

    private fun locationFor(document: ReaderDocument, offset: Long): ReaderLocation =
        when (document.format) {
            DocumentFormat.EPUB -> {
                val section = document.sections.lastOrNull { section -> section.range.start <= offset }
                ReaderLocation.EpubOffset(
                    spineIndex = section?.index ?: 0,
                    offset = (offset - (section?.range?.start ?: 0L)).coerceAtLeast(0L),
                )
            }
            else -> ReaderLocation.TextOffset(offset)
        }
}

private data class PageLayout(
    val charsPerLine: Int,
    val linesPerPage: Int,
)

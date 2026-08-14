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
            widthUnitsPerLine = layout.widthUnitsPerLine,
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
        val emWidth = style.fontSizeSp.coerceAtLeast(1f)
        val lineHeight = (style.fontSizeSp * style.lineHeightMultiplier).coerceAtLeast(1f)
        return PageLayout(
            widthUnitsPerLine = (viewportSize.widthPx * WideGlyphUnits / emWidth).toInt()
                .coerceAtLeast(WideGlyphUnits),
            linesPerPage = (viewportSize.heightPx / lineHeight).toInt().coerceAtLeast(1),
        )
    }

    private fun splitPageRanges(
        text: String,
        widthUnitsPerLine: Int,
        linesPerPage: Int,
    ): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        var start = 0
        while (start < text.length) {
            var index = start
            var usedLines = 0
            var usedWidthUnits = 0
            var end = start

            while (index < text.length && usedLines < linesPerPage) {
                val char = text[index]
                if (char == '\n') {
                    usedLines += 1
                    usedWidthUnits = 0
                    index += 1
                    end = index
                    continue
                }

                val widthUnits = char.widthUnits()
                if (usedWidthUnits + widthUnits > widthUnitsPerLine) {
                    usedLines += 1
                    usedWidthUnits = 0
                    if (usedLines >= linesPerPage) break
                }
                usedWidthUnits += widthUnits
                index += 1
                end = index
            }

            if (end <= start) end = (start + 1).coerceAtMost(text.length)
            ranges += TextRange(start.toLong(), end.toLong())
            start = end
        }
        return ranges
    }

    private fun Char.widthUnits(): Int = if (isWideGlyph()) WideGlyphUnits else NarrowGlyphUnits

    private fun Char.isWideGlyph(): Boolean = this in '\u1100'..'\u11FF' ||
        this in '\u2E80'..'\u303F' ||
        this in '\u3040'..'\u30FF' ||
        this in '\u3100'..'\u312F' ||
        this in '\u3130'..'\u318F' ||
        this in '\u31A0'..'\u31EF' ||
        this in '\u31F0'..'\u4DBF' ||
        this in '\u4E00'..'\u9FFF' ||
        this in '\uA960'..'\uA97F' ||
        this in '\uAC00'..'\uD7FF' ||
        this in '\uF900'..'\uFAFF' ||
        this in '\uFE30'..'\uFE4F' ||
        this in '\uFF01'..'\uFF60' ||
        this in '\uFFE0'..'\uFFE6'

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
    val widthUnitsPerLine: Int,
    val linesPerPage: Int,
)

// Glyph advance in hundredths of an em. A wide (CJK) glyph is exactly one em. A proportional narrow
// glyph is not half an em: measured on the rendered reader (sans-serif Latin prose, 393 sp pane,
// 18 sp text) its advance is ~0.45 em, and word wrapping loses a further ~7% of every line because
// this model packs characters where the renderer must break at spaces. 0.48 em covers both, so a
// page fills the viewport instead of stopping 3 lines short as the old 0.5 em assumption did.
// The budget must stay on the low side: overshooting hides the tail of every page behind the clip.
// ponytail: calibrated constant; only real text measurement gives font-exact fill.
private const val WideGlyphUnits = 100
private const val NarrowGlyphUnits = 48

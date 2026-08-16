package com.tedd.teddreader.core.data.pagination

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.blocksIn
import com.tedd.teddreader.core.common.model.isStandalone
import com.tedd.teddreader.core.common.model.readerImageSize
import kotlin.math.ceil
import org.koin.core.annotation.Single

@Single
class TextPageLayoutEngine {
    fun paginate(
        document: ReaderDocument,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker? = null,
    ): List<PageWindow> {
        val coverSection = document.sections.firstOrNull()?.takeIf { section ->
            document.blocks.any { block ->
                block.kind == ReaderBlockKind.COVER_IMAGE &&
                    block.range.start >= section.range.start &&
                    block.range.end <= (section.range.end.coerceAtLeast(section.range.start + 1))
            }
        }
        val coverPage = coverSection?.let { section ->
            val coverRange = TextRange(section.range.start, section.range.end.coerceAtLeast(section.range.start + 1))
            PageWindow(
                pageIndex = PageIndex(current = 0, total = 1),
                location = ReaderLocation.EpubOffset(section.index, 0),
                text = section.text,
                textRange = coverRange,
                blocks = document.blocks.blocksIn(coverRange.start, coverRange.end),
            )
        }

        val layout = pageLayout(style, viewportSize)
        val contentSections = document.sections.filter { section ->
            coverSection == null || section.index != coverSection.index
        }
        val blocksBySection = groupBlocksBySection(contentSections, document.blocks)
        // ponytail: the cap stays a whole-document one even though measurement is now per chapter.
        // Applying it per chapter would let a long book slip past it a chapter at a time and lay out
        // every one of them, which is the cost this guard exists to avoid; and a book that measured
        // some chapters and estimated others would break its pages two different ways.
        val measuredBreaker = pageBreaker?.takeIf {
            contentSections.sumOf { section -> section.text.length } <= MaxMeasuredContentLengthChars
        }

        // One EPUB spine item is one document of its own, and no reading system runs two of them
        // together on a screen — readium-css and foliate-js both paginate per resource. Paginating
        // each section separately is what puts a chapter's heading at the top of a page instead of
        // halfway down the previous chapter's last page, and it also keeps every text measurement to
        // one chapter rather than laying out the whole book at once.
        val contentPages = contentSections.flatMap { section ->
            val sectionBlocks = blocksBySection[section.index].orEmpty()
            sectionPageRanges(
                section = section,
                sectionBlocks = sectionBlocks,
                layout = layout,
                style = style,
                pageBreaker = measuredBreaker,
            ).map { range ->
                PageWindow(
                    pageIndex = PageIndex(current = 0, total = 0),
                    location = if (document.format == DocumentFormat.EPUB) {
                        ReaderLocation.EpubOffset(section.index, range.start - section.range.start)
                    } else {
                        ReaderLocation.TextOffset(range.start)
                    },
                    text = section.text.substring(
                        (range.start - section.range.start).toInt(),
                        (range.end - section.range.start).toInt(),
                    ),
                    textRange = range,
                    blocks = sectionBlocks.blocksIn(range.start, range.end),
                )
            }
        }
        if (contentPages.isEmpty() && coverPage == null) return emptyList()

        val pages = if (coverPage != null) listOf(coverPage) + contentPages else contentPages
        return pages.mapIndexed { index, page ->
            page.copy(pageIndex = PageIndex(current = index, total = pages.size))
        }
    }

    /**
     * The blocks each section owns, collected in one pass over the document.
     *
     * Asking [blocksIn] once per section instead walks every block for every section, which on a book
     * with hundreds of chapters and tens of thousands of blocks is the slowest thing pagination does.
     */
    private fun groupBlocksBySection(
        sections: List<ReaderSection>,
        blocks: List<ReaderBlock>,
    ): Map<Int, List<ReaderBlock>> {
        if (sections.isEmpty() || blocks.isEmpty()) return emptyMap()
        val ordered = sections.sortedBy { it.range.start }
        val grouped = LinkedHashMap<Int, MutableList<ReaderBlock>>(ordered.size)
        var sectionIndex = 0
        blocks.sortedBy { it.range.start }.forEach { block ->
            while (sectionIndex < ordered.lastIndex && block.range.start >= ordered[sectionIndex].range.end) {
                sectionIndex += 1
            }
            val section = ordered[sectionIndex]
            if (block.range.start < section.range.start || block.range.start > section.range.end) return@forEach
            grouped.getOrPut(section.index) { mutableListOf() } += block
        }
        return grouped
    }

    /**
     * Page boundaries inside one section, as document-absolute ranges.
     *
     * A section with no readable text still yields one page when it carries something to draw — a
     * full-page illustration chapter is exactly that — so its picture is not silently dropped.
     */
    private fun sectionPageRanges(
        section: ReaderSection,
        sectionBlocks: List<ReaderBlock>,
        layout: PageLayout,
        style: ReaderStyle,
        pageBreaker: ReaderPageBreaker?,
    ): List<TextRange> {
        val text = section.text
        val base = section.range.start
        if (text.isEmpty()) return emptyList()
        if (text.isBlank() && sectionBlocks.none { it.kind.isStandalone() }) return emptyList()

        val relativeBlocks = sectionBlocks.map { block -> block.rebasedBy(base) }
        val measuredPageStarts = pageBreaker
            ?.pageStarts(text, relativeBlocks)
            ?.takeIf { it.isNotEmpty() }
        val relativeRanges = if (measuredPageStarts != null) {
            measuredPageRanges(pageStarts = measuredPageStarts, textLength = text.length)
        } else {
            splitPageRanges(
                text = text,
                widthUnitsPerLine = layout.widthUnitsPerLine,
                linesPerPage = layout.linesPerPage,
                standaloneHeights = standaloneBlockLineHeights(
                    blocks = relativeBlocks,
                    layout = layout,
                    style = style,
                ),
            )
        }
        if (relativeRanges.isEmpty()) return listOf(TextRange(base, base + text.length))
        return relativeRanges.map { range -> TextRange(base + range.start, base + range.end) }
    }

    private fun ReaderBlock.rebasedBy(base: Long): ReaderBlock = copy(
        range = TextRange((range.start - base).coerceAtLeast(0L), (range.end - base).coerceAtLeast(0L)),
        spans = spans.map { span ->
            span.copy(
                range = TextRange(
                    (span.range.start - base).coerceAtLeast(0L),
                    (span.range.end - base).coerceAtLeast(0L),
                ),
            )
        },
    )

    private fun pageLayout(style: ReaderStyle, viewportSize: ViewportSize): PageLayout {
        val emWidth = style.fontSizeSp.coerceAtLeast(1f)
        val lineHeight = (style.fontSizeSp * style.lineHeightMultiplier).coerceAtLeast(1f)
        return PageLayout(
            widthUnitsPerLine = (viewportSize.widthPx * WideGlyphUnits / emWidth).toInt()
                .coerceAtLeast(WideGlyphUnits),
            linesPerPage = (viewportSize.heightPx / lineHeight).toInt().coerceAtLeast(1),
        )
    }

    /** The UI measured these boundaries against the real pane, so each page fills it exactly. */
    private fun measuredPageRanges(pageStarts: IntArray, textLength: Int): List<TextRange> =
        pageStarts.mapIndexed { index, start ->
            val end = pageStarts.getOrNull(index + 1) ?: textLength
            TextRange(start.toLong(), end.toLong())
        }

    /**
     * How many lines each standalone block (an image, or a separator rule) occupies once drawn.
     *
     * The renderer sizes a standalone image to the full column width and a height taken from the
     * image's own aspect ratio, capped to the page — see `placeholderFor` in ReaderSemanticText. This
     * mirrors that rule in the line units pagination counts in, so a page that holds an image reserves
     * real room for it. Without this the estimator treated an image as the single newline character it
     * carries, packed a full page of text around it, and the image was clipped by the pane it
     * overflowed. Only used on the estimated path; measured pagination lays the placeholders out for
     * real.
     */
    private fun standaloneBlockLineHeights(
        blocks: List<ReaderBlock>,
        layout: PageLayout,
        style: ReaderStyle,
    ): Map<Int, Int> {
        val columnWidthEm = layout.widthUnitsPerLine.toFloat() / WideGlyphUnits
        val pageHeightEm = layout.linesPerPage * style.lineHeightMultiplier
        val lineHeightEm = style.lineHeightMultiplier.coerceAtLeast(0.1f)
        return blocks
            .filter { it.kind.isStandalone() }
            .associate { block ->
                val size = block.readerImageSize(
                    columnWidthEm = columnWidthEm,
                    maxHeightEm = pageHeightEm,
                    emInPx = style.fontSizeSp,
                )
                val lines = ceil(size.heightEm / lineHeightEm).toInt().coerceAtLeast(1)
                block.range.start.toInt() to lines.coerceAtMost(layout.linesPerPage)
            }
    }

    private fun splitPageRanges(
        text: String,
        widthUnitsPerLine: Int,
        linesPerPage: Int,
        standaloneHeights: Map<Int, Int> = emptyMap(),
    ): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        var start = 0
        while (start < text.length) {
            var index = start
            var usedLines = 0
            var usedWidthUnits = 0
            var end = start

            // Offset just past the last space on the current line, or -1 before the first space.
            var lastWrapOpportunity = -1

            while (index < text.length && usedLines < linesPerPage) {
                val standaloneLines = standaloneHeights[index]
                if (standaloneLines != null) {
                    // An image never splits across pages. If it cannot fit in what is left of this
                    // page it starts the next one, exactly where the renderer would push it.
                    if (usedLines > 0 && usedLines + standaloneLines > linesPerPage) break
                    usedLines += standaloneLines
                    usedWidthUnits = 0
                    lastWrapOpportunity = -1
                    index += 1
                    end = index
                    continue
                }

                val char = text[index]
                if (char == '\n') {
                    usedLines += 1
                    usedWidthUnits = 0
                    lastWrapOpportunity = -1
                    index += 1
                    end = index
                    continue
                }

                val widthUnits = char.widthUnits()
                if (char == ' ') {
                    // A trailing space hangs past the edge instead of wrapping, so it never starts
                    // a new line; it only records where the next wrap may land.
                    usedWidthUnits += widthUnits
                    index += 1
                    end = index
                    lastWrapOpportunity = index
                    continue
                }

                if (usedWidthUnits + widthUnits > widthUnitsPerLine) {
                    // Break at the last space so the model wraps where the renderer wraps. Packing
                    // mid-word instead costs the renderer an extra line per page worth of text.
                    if (lastWrapOpportunity > start) {
                        index = lastWrapOpportunity
                        end = index
                    }
                    usedLines += 1
                    usedWidthUnits = 0
                    lastWrapOpportunity = -1
                    if (usedLines >= linesPerPage) break
                    continue
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

}

private data class PageLayout(
    val widthUnitsPerLine: Int,
    val linesPerPage: Int,
)

// Glyph advance in hundredths of an em. A wide (CJK) glyph is exactly one em. A proportional narrow
// glyph is not half an em: measured on the rendered reader (sans-serif Latin prose, 393 sp pane,
// 18 sp text) its advance is ~0.44 em. Because the line model now wraps at spaces like the renderer
// does, this is the plain advance and carries no extra allowance; it stays a shade pessimistic so
// the model never claims a word fits where the renderer would push it to the next line.
// ponytail: calibrated constant; only real text measurement gives font-exact fill.
private const val WideGlyphUnits = 100
private const val NarrowGlyphUnits = 45

private const val MaxMeasuredContentLengthChars = 200_000

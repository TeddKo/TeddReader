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
import com.tedd.teddreader.core.common.model.rebasedBy
import com.tedd.teddreader.core.common.model.standaloneBlocks
import kotlin.math.ceil
import org.koin.core.annotation.Single

@Single
class TextPageLayoutEngine {
    fun paginate(
        document: ReaderDocument,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker? = null,
        // A real measurement touches every section's text anyway, so the default — grouping the
        // document's own block list once — is already the cheap option here. [reconstruct] is the one
        // that overrides this, with a lookup that can answer for one section without decoding the rest.
        sectionBlocks: (ReaderSection) -> List<ReaderBlock> = defaultSectionBlocks(document),
    ): List<PageWindow> {
        val coverSection = findCoverSection(document, sectionBlocks)
        val coverPage = buildCoverPage(document, coverSection, sectionBlocks)

        val layout = pageLayout(style, viewportSize)
        val contentSections = contentSections(document, coverSection)

        // One EPUB spine item is one document of its own, and no reading system runs two of them
        // together on a screen — readium-css and foliate-js both paginate per resource. Paginating
        // each section separately is what puts a chapter's heading at the top of a page instead of
        // halfway down the previous chapter's last page, and it also keeps every text measurement to
        // one chapter rather than laying out the whole book at once.
        val contentPages = contentSections.flatMap { section ->
            val sectionBlockList = sectionBlocks(section)
            sectionPageRanges(
                section = section,
                sectionBlocks = sectionBlockList,
                layout = layout,
                style = style,
                // The cap belongs to a chapter, because that is the unit being measured. Held against
                // the whole book it ruled out measurement for every long book — and an estimate cannot
                // know the line height the book's own stylesheet sets, so it packed a page with half
                // again as many lines as the page draws and the rest were clipped off the bottom.
                // Laying out one chapter is the price of pages that hold what they say they hold.
                pageBreaker = pageBreaker?.takeIf { section.text.length <= MaxMeasuredContentLengthChars },
            ).map { range -> buildPageWindow(document.format, section, sectionBlockList, range) }
        }
        return assemblePages(coverPage, contentPages)
    }

    /**
     * Rebuilds the exact page list [paginate] would produce from a real measurement, using page starts
     * a measured pass produced and stored earlier: one absolute document offset per content page, in
     * the same order [paginate] emits them, with the cover page excluded (it is always exactly the
     * first section and never needs measuring to rebuild). No text is measured here — a stored start is
     * exactly where the renderer put that page last time, and because no page ever spans two sections,
     * each section's own bounds are enough to tell where its pages end.
     *
     * The list this returns builds a page — and asks [sectionBlocks] to decode that page's section —
     * only the first time something reads it by index, and remembers the result after that. A reader
     * only ever looks at a handful of pages around the one it is showing, so this is the difference
     * between decoding one book's worth of blocks on every open and decoding a handful of sections.
     */
    fun reconstruct(
        document: ReaderDocument,
        contentPageStarts: LongArray,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock> = defaultSectionBlocks(document),
        // Whether sectionBlocks(section) is that section's real, decoded answer right now, or a
        // stand-in returned while a background fetch is still in flight (see
        // DocumentRepositoryImpl.SectionBlocksCache). A page built from a stand-in must stay free to
        // rebuild once the real blocks arrive instead of freezing the stand-in forever; every other
        // caller already hands over a fully-decoded document, so "always ready" changes nothing for them.
        isSectionReady: (Int) -> Boolean = { true },
    ): List<PageWindow> {
        val coverSection = findCoverSection(document, sectionBlocks)
        val coverPage = buildCoverPage(document, coverSection, sectionBlocks)
        val contentSections = contentSections(document, coverSection)
        return RestoredPageWindows(
            coverPage = coverPage,
            contentSections = contentSections,
            contentPageStarts = contentPageStarts,
            format = document.format,
            sectionBlocks = sectionBlocks,
            buildPage = ::buildPageWindow,
            isSectionReady = isSectionReady,
        )
    }

    /**
     * The absolute document offsets [paginate] would give [section] measured entirely on its own — the
     * unit DocumentRepositoryImpl.importNextSections appends to an already-stored pageStartsBlob, so a
     * progressively imported section is measured exactly once instead of by re-measuring the whole book
     * from scratch after every batch. Safe because no page ever spans two sections (see [paginate]), so
     * one section's boundaries never depend on, or move, any other section's.
     */
    fun pageStartsForSection(
        section: ReaderSection,
        sectionBlocks: List<ReaderBlock>,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
    ): LongArray {
        val layout = pageLayout(style, viewportSize)
        val ranges = sectionPageRanges(
            section = section,
            sectionBlocks = sectionBlocks,
            layout = layout,
            style = style,
            pageBreaker = pageBreaker?.takeIf { section.text.length <= MaxMeasuredContentLengthChars },
        )
        return LongArray(ranges.size) { index -> ranges[index].start }
    }

    /** True when [paginate] would give this document a dedicated first page for its cover image. */
    fun hasCoverPage(
        document: ReaderDocument,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock> = defaultSectionBlocks(document),
    ): Boolean = findCoverSection(document, sectionBlocks) != null

    /**
     * [paginate]'s cover-page resolution and content-section split, resolved once and handed to a
     * caller that wants to measure content sections one at a time — see DocumentRepositoryImpl's
     * progressive pagination, which measures the section the reader resumed into before any other and
     * needs exactly this to know which section that is and where the cover page (if any) already ends.
     */
    fun resolveSections(
        document: ReaderDocument,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock> = defaultSectionBlocks(document),
    ): PaginationSections {
        val coverSection = findCoverSection(document, sectionBlocks)
        return PaginationSections(
            coverPage = buildCoverPage(document, coverSection, sectionBlocks),
            contentSections = contentSections(document, coverSection),
        )
    }

    /**
     * [paginate]'s own per-section measurement, standing alone so progressive pagination can grow a
     * result one section at a time instead of laying every section out before the reader sees the
     * first one (see DocumentRepositoryImpl.getPageWindows/continuePagination). Numbered (0, 0) same as
     * [paginate]'s own per-section pass before [assemblePages] renumbers it; the caller renumbers once
     * it knows how many sections it has measured so far.
     */
    fun paginateSection(
        format: DocumentFormat,
        section: ReaderSection,
        sectionBlocks: List<ReaderBlock>,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
    ): List<PageWindow> {
        val layout = pageLayout(style, viewportSize)
        return sectionPageRanges(
            section = section,
            sectionBlocks = sectionBlocks,
            layout = layout,
            style = style,
            pageBreaker = pageBreaker?.takeIf { section.text.length <= MaxMeasuredContentLengthChars },
        ).map { range -> buildPageWindow(format, section, sectionBlocks, range) }
    }

    /**
     * Every block [paginate]/[reconstruct] need, grouped once from the document's own eager list and
     * shifted to read relative to its own section — the same shape [SectionBlocksCache.blocksFor]
     * hands over for a document loaded from storage (see DocumentRepositoryImpl.persistParsedDocument),
     * so [buildPageWindow] never has to know which source a section's blocks came from. Internal rather
     * than private so DocumentRepositoryImpl can compute this once itself and reuse the same closure
     * across many [paginateSection] calls instead of re-grouping the whole book on every one.
     */
    internal fun defaultSectionBlocks(document: ReaderDocument): (ReaderSection) -> List<ReaderBlock> {
        val grouped = groupBlocksBySection(document.sections, document.blocks)
        return { section -> grouped[section.index].orEmpty().rebasedBy(section.range.start) }
    }

    private fun findCoverSection(
        document: ReaderDocument,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock>,
    ): ReaderSection? =
        document.sections.firstOrNull()?.takeIf { section ->
            // A cover, when the book has one, is always the first section's own picture, so finding it
            // never has to look at — or decode — any other section. sectionBlocks(section) reads
            // relative to section.range.start now, so the bound checked here is 0..the section's own
            // length in that same frame, not the section's absolute range.
            val sectionLength = (section.range.end - section.range.start).coerceAtLeast(1L)
            sectionBlocks(section).any { block ->
                block.kind == ReaderBlockKind.COVER_IMAGE &&
                    block.range.start >= 0L &&
                    block.range.end <= sectionLength
            }
        }

    private fun buildCoverPage(
        document: ReaderDocument,
        coverSection: ReaderSection?,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock>,
    ): PageWindow? =
        coverSection?.let { section ->
            val coverRange = TextRange(section.range.start, section.range.end.coerceAtLeast(section.range.start + 1))
            PageWindow(
                pageIndex = PageIndex(current = 0, total = 1),
                location = ReaderLocation.EpubOffset(section.index, 0),
                text = section.text,
                textRange = coverRange,
                // Filter in sectionBlocks' own relative frame, then shift the result back to the
                // absolute offsets PageWindow.blocks has always carried (see buildPageWindow) — written
                // this way, rather than passing coverRange straight through, so the cover section's
                // start always being 0 is not what makes this correct.
                blocks = sectionBlocks(section)
                    .blocksIn(coverRange.start - section.range.start, coverRange.end - section.range.start)
                    .rebasedBy(-section.range.start),
            )
        }

    private fun contentSections(document: ReaderDocument, coverSection: ReaderSection?): List<ReaderSection> =
        document.sections.filter { section -> coverSection == null || section.index != coverSection.index }

    private fun buildPageWindow(
        format: DocumentFormat,
        section: ReaderSection,
        sectionBlocks: List<ReaderBlock>,
        range: TextRange,
    ): PageWindow = PageWindow(
        pageIndex = PageIndex(current = 0, total = 0),
        location = if (format == DocumentFormat.EPUB) {
            ReaderLocation.EpubOffset(section.index, range.start - section.range.start)
        } else {
            ReaderLocation.TextOffset(range.start)
        },
        text = section.text.substring(
            (range.start - section.range.start).toInt(),
            (range.end - section.range.start).toInt(),
        ),
        textRange = range,
        // sectionBlocks reads relative to section.range.start now (see defaultSectionBlocks); filter in
        // that same frame, then shift the result back to absolute — a page's blocks have always
        // addressed the same offsets as its own textRange above, which ReaderSemanticText relies on to
        // locate a block within page.text.
        blocks = sectionBlocks
            .blocksIn(range.start - section.range.start, range.end - section.range.start)
            .rebasedBy(-section.range.start),
    )

    // Internal rather than private so DocumentRepositoryImpl can renumber a progressive pagination's
    // pages-so-far the same way [paginate] numbers a whole-book pass — see [resolveSections]/
    // [paginateSection] — instead of duplicating this renumbering logic a second time.
    internal fun assemblePages(coverPage: PageWindow?, contentPages: List<PageWindow>): List<PageWindow> {
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

        // sectionBlocks already reads relative to this section's own start (see defaultSectionBlocks /
        // DocumentRepositoryImpl.persistParsedDocument) — no rebase needed here on every pagination
        // pass any more; that shift now happens once, when the section was written.
        val measuredPageStarts = pageBreaker
            ?.pageStarts(text, sectionBlocks)
            ?.takeIf { it.isNotEmpty() }
        val relativeRanges = if (measuredPageStarts != null) {
            measuredPageRanges(pageStarts = measuredPageStarts, textLength = text.length)
        } else {
            splitPageRanges(
                text = text,
                widthUnitsPerLine = layout.widthUnitsPerLine,
                linesPerPage = layout.linesPerPage,
                standaloneHeights = standaloneBlockLineHeights(
                    blocks = sectionBlocks,
                    layout = layout,
                    style = style,
                ),
            )
        }
        if (relativeRanges.isEmpty()) return listOf(TextRange(base, base + text.length))
        return relativeRanges.map { range -> TextRange(base + range.start, base + range.end) }
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
            .standaloneBlocks()
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

/** [TextPageLayoutEngine.resolveSections]'s answer: the cover page, if the book has one, and the
 * sections [TextPageLayoutEngine.paginateSection] can measure independently of each other and in any
 * order. */
data class PaginationSections(
    val coverPage: PageWindow?,
    val contentSections: List<ReaderSection>,
)

/**
 * The page list [TextPageLayoutEngine.reconstruct] hands back: every [PageWindow] a real measurement
 * already placed, built the first time something reads it by [get] and kept after that. [size] and
 * page ordering come entirely from [contentPageStarts] — a handful of longs — so the total page count
 * is exact before a single section's blocks are ever decoded.
 */
internal class RestoredPageWindows(
    private val coverPage: PageWindow?,
    private val contentSections: List<ReaderSection>,
    private val contentPageStarts: LongArray,
    private val format: DocumentFormat,
    private val sectionBlocks: (ReaderSection) -> List<ReaderBlock>,
    private val buildPage: (DocumentFormat, ReaderSection, List<ReaderBlock>, TextRange) -> PageWindow,
    private val isSectionReady: (Int) -> Boolean = { true },
) : AbstractList<PageWindow>() {
    private val coverOffset = if (coverPage != null) 1 else 0
    override val size: Int = coverOffset + contentPageStarts.size
    private val built = HashMap<Int, PageWindow>()

    /** How many of [size] pages this instance has actually built — logged to show the saving. */
    val builtCount: Int get() = built.size

    override fun get(index: Int): PageWindow {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index: $index, size: $size")
        built[index]?.let { return it }
        // The cover section is always prewarmed before this list is ever handed out (see
        // DocumentRepositoryImpl.restorePageWindows — cover detection needs it eagerly, not lazily), so
        // the cover page itself has nothing left to wait for. It still needs its pageIndex corrected to
        // the real total, though — buildCoverPage hands over a lone PageIndex(0, 1), the same way
        // assemblePages() rewrites it for the measured path; skipping that here is what left a restored
        // cover page's total stuck at 1 while every other page in the same list carried the real count.
        if (coverPage != null && index == 0) {
            val page = coverPage.copy(pageIndex = PageIndex(current = 0, total = size))
            built[0] = page
            return page
        }
        val contentIndex = index - coverOffset
        val section = contentSections.sectionOwning(contentPageStarts[contentIndex])
        val page = buildAt(index, contentIndex, section)
        // A page built while its own section's blocks are still a stand-in must stay rebuildable — the
        // next read of this same index may land after the real blocks arrived. Once the section is
        // ready this is the page's final answer, and from here it must never change again (a page
        // already shown keeps its blocks — see SectionBlocksCache doc).
        if (isSectionReady(section.index)) built[index] = page
        return page
    }

    private fun buildAt(index: Int, contentIndex: Int, section: ReaderSection): PageWindow {
        val start = contentPageStarts[contentIndex]
        // A page ends where the next stored start is, unless that start belongs to the section
        // after this one — then this page runs to the end of its own section instead, exactly like
        // the per-section walk in [TextPageLayoutEngine.paginate] (no page ever spans two sections).
        val nextStart = contentPageStarts.getOrNull(contentIndex + 1)
        val end = if (nextStart != null && nextStart < section.range.end) nextStart else section.range.end
        val page = buildPage(format, section, sectionBlocks(section), TextRange(start, end))
        return page.copy(pageIndex = PageIndex(current = index, total = size))
    }
}

/** The section whose range a stored page start falls in, found by binary search over its own list. */
private fun List<ReaderSection>.sectionOwning(offset: Long): ReaderSection {
    var lo = 0
    var hi = lastIndex
    var result = this[0]
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val candidate = this[mid]
        if (candidate.range.start <= offset) {
            result = candidate
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return result
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

/** Longest chapter this lays out for real. Beyond it the estimate takes over, imprecise but bounded. */
private const val MaxMeasuredContentLengthChars = 200_000

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

/**
 * Turns a parsed document into the pages a reader turns, and is the only place page boundaries are decided.
 *
 * Every entry point here rests on one rule: **a page never spans two sections.** One EPUB spine item is a
 * document of its own and no reading system runs two of them together on a screen — readium-css and
 * foliate-js both paginate per resource. Paginating per section is what puts a chapter's heading at the top
 * of a page instead of halfway down the previous chapter's last page, and it is also what makes everything
 * else here possible: one section can be measured, stored, restored or appended without touching any other
 * section's boundaries.
 *
 * The class holds no state; it is a `@Single` only so Koin hands the same instance around.
 */
@Single
class TextPageLayoutEngine {
    /**
     * Lays a whole document out into pages, measuring the text when given a real breaker.
     *
     * A cover image, when the document has one, becomes a page of its own before any content is measured;
     * the rest is measured a section at a time and renumbered at the end.
     *
     * Measurement is capped per chapter, not per book: held against the whole book the cap ruled out
     * measurement for every long book, and an estimate cannot know the line height the book's own stylesheet
     * sets — it packed a page with half again as many lines as the page draws and the rest were clipped off
     * the bottom. Laying out one chapter is the price of pages that hold what they say they hold.
     *
     * @param document the parsed document, whose sections may be only what has been imported so far.
     * @param style the reading style; its type decides where lines break.
     * @param viewportSize the box a page is laid out into.
     * @param pageBreaker the reader's own text layout. Null falls back to an estimate, which is honest but
     * coarse — the caller is expected to paginate again once a real measurement exists.
     * @param sectionBlocks how to get one section's block structure. The default groups the document's own
     * eager block list once, which is the cheap option here because a real measurement touches every
     * section's text anyway; [reconstruct] is what overrides it with a lookup that can answer for one
     * section without decoding the rest.
     * @return the pages in reading order, numbered from 0, cover page first when there is one.
     */
    fun paginate(
        document: ReaderDocument,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker? = null,
        viewportDensity: Float = 1f,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock> = defaultSectionBlocks(document),
    ): List<PageWindow> {
        val coverSection = findCoverSection(document, sectionBlocks)
        val coverPage = buildCoverPage(document, coverSection, sectionBlocks)

        val layout = pageLayout(style, viewportSize, viewportDensity)
        val contentSections = contentSections(document, coverSection)

        val contentPages = contentSections.flatMap { section ->
            val sectionBlockList = sectionBlocks(section)
            sectionPageRanges(
                section = section,
                sectionBlocks = sectionBlockList,
                layout = layout,
                style = style,
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
     * The list this returns builds pages on demand and retains only the most recently read windows.
     * A reader only looks at a handful of pages around the visible one, so old windows can be rebuilt
     * instead of keeping one book's worth of page objects alive.
     *
     * @param document the parsed document the stored starts were measured against. The caller must already
     * have established that it is the same document — a stored layout is only valid while the document's
     * character count has not changed (see DocumentRepositoryImpl.restorePageWindows).
     * @param contentPageStarts one absolute document offset per content page, ascending, cover excluded.
     * @param sectionBlocks how to get one section's blocks; the on-demand lookup is the whole point here.
     * @param isSectionReady whether `sectionBlocks(section)` is that section's real, decoded answer right
     * now, or a stand-in returned while a background fetch is still in flight (see
     * DocumentRepositoryImpl.SectionBlocksCache). A page built from a stand-in must stay free to rebuild
     * once the real blocks arrive instead of freezing the stand-in forever; every other caller already
     * hands over a fully-decoded document, so the default "always ready" changes nothing for them.
     * @return the same page list [paginate] would have produced, with each page built on first read.
     */
    fun reconstruct(
        document: ReaderDocument,
        contentPageStarts: LongArray,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock> = defaultSectionBlocks(document),
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
     * The same lazy reconstruction as [reconstruct], but from per-section page starts already grouped by
     * spine order. Used by progressive pagination to avoid flattening every measured section into one big
     * page list before the reader actually asks for those pages.
     */
    internal fun reconstructMeasuredSections(
        format: DocumentFormat,
        coverPage: PageWindow?,
        contentSections: List<ReaderSection>,
        sectionPageStarts: List<LongArray>,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock>,
        isSectionReady: (Int) -> Boolean = { true },
    ): RestoredPageWindows = RestoredPageWindows(
        coverPage = coverPage,
        contentSections = contentSections,
        contentPageStarts = null,
        sectionPageStarts = sectionPageStarts,
        format = format,
        sectionBlocks = sectionBlocks,
        buildPage = ::buildPageWindow,
        isSectionReady = isSectionReady,
    )

    /**
     * The absolute document offsets [paginate] would give [section] measured entirely on its own — the
     * unit DocumentRepositoryImpl.importNextSections appends to an already-stored pageStartsBlob, so a
     * progressively imported section is measured exactly once instead of by re-measuring the whole book
     * from scratch after every batch. Safe because no page ever spans two sections (see [paginate]), so
     * one section's boundaries never depend on, or move, any other section's.
     *
     * @param section the section to measure.
     * @param sectionBlocks that section's blocks, already rebased to it.
     * @param style the reading style being measured for.
     * @param viewportSize the box being measured for.
     * @param pageBreaker the reader's own layout; null yields estimated starts, and a section longer than
     * the measurement cap is estimated even when a breaker is given.
     * @return one absolute document offset per page of this section, ascending.
     */
    fun pageStartsForSection(
        section: ReaderSection,
        sectionBlocks: List<ReaderBlock>,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float = 1f,
    ): LongArray {
        val layout = pageLayout(style, viewportSize, viewportDensity)
        val ranges = sectionPageRanges(
            section = section,
            sectionBlocks = sectionBlocks,
            layout = layout,
            style = style,
            pageBreaker = pageBreaker?.takeIf { section.text.length <= MaxMeasuredContentLengthChars },
        )
        return LongArray(ranges.size) { index -> ranges[index].start }
    }

    /**
     * Whether [paginate] would give this document a dedicated first page for its cover image.
     *
     * @param document the parsed document.
     * @param sectionBlocks how to get a section's blocks; only the cover candidate is inspected.
     * @return true when the document's first section is a cover image and becomes a page of its own.
     */
    fun hasCoverPage(
        document: ReaderDocument,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock> = defaultSectionBlocks(document),
    ): Boolean = findCoverSection(document, sectionBlocks) != null

    /**
     * [paginate]'s cover-page resolution and content-section split, resolved once and handed to a
     * caller that wants to measure content sections one at a time — see DocumentRepositoryImpl's
     * progressive pagination, which measures the section the reader resumed into before any other and
     * needs exactly this to know which section that is and where the cover page (if any) already ends.
     *
     * @param document the parsed document.
     * @param sectionBlocks how to get a section's blocks.
     * @return the cover page, if any, and the content sections in document order.
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
     * [paginate]'s own per-section measurement, standing alone so progressive pagination can grow a result
     * one section at a time instead of laying every section out before the reader sees the first one (see
     * DocumentRepositoryImpl.getPageWindows/continuePagination).
     *
     * @param format the document's format, which decides how a page's location is expressed.
     * @param section the section to measure.
     * @param sectionBlocks that section's blocks.
     * @param style the reading style being measured for.
     * @param viewportSize the box being measured for.
     * @param pageBreaker the reader's own layout; null or an over-cap section yields an estimate.
     * @return this section's pages, numbered (0, 0) exactly as [paginate]'s own per-section pass leaves them
     * before [assemblePages] renumbers — the caller renumbers once it knows how many sections it has
     * measured so far.
     */
    fun paginateSection(
        format: DocumentFormat,
        section: ReaderSection,
        sectionBlocks: List<ReaderBlock>,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float = 1f,
    ): List<PageWindow> {
        val layout = pageLayout(style, viewportSize, viewportDensity)
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
     *
     * @param document the parsed document whose own eager block list is grouped.
     * @return a lookup from section to that section's blocks, rebased to the section's own start.
     */
    internal fun defaultSectionBlocks(document: ReaderDocument): (ReaderSection) -> List<ReaderBlock> {
        val grouped = groupBlocksBySection(document.sections, document.blocks)
        return { section -> grouped[section.index].orEmpty().rebasedBy(section.range.start) }
    }

    /**
     * Finds the section that is the book's cover, if any.
     *
     * A cover, when the book has one, is always the first section's own picture, so this never has to look at
     * — or decode — any other section; on the restore path that saves a database read per section.
     *
     * `sectionBlocks(section)` reads relative to the section's own start, so the bound checked here is
     * `0..the section's own length` in that same frame, not the section's absolute range.
     *
     * @param document the parsed document.
     * @param sectionBlocks how to get a section's blocks.
     * @return the cover section, or null when the first section is not a cover image.
     */
    private fun findCoverSection(
        document: ReaderDocument,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock>,
    ): ReaderSection? =
        document.sections.firstOrNull()?.takeIf { section ->
            val sectionLength = (section.range.end - section.range.start).coerceAtLeast(1L)
            sectionBlocks(section).any { block ->
                block.kind == ReaderBlockKind.COVER_IMAGE &&
                    block.range.start >= 0L &&
                    block.range.end <= sectionLength
            }
        }

    /**
     * Builds the cover's own page.
     *
     * Its blocks are filtered in the section's own relative frame and then shifted back to the absolute
     * offsets `PageWindow.blocks` carries everywhere else (see [buildPageWindow]) — written that way, rather
     * than passing the range straight through, so the cover section's start always being 0 is not what makes
     * it correct.
     *
     * @param document the parsed document.
     * @param coverSection the cover section, or null for a book without one.
     * @param sectionBlocks how to get that section's blocks.
     * @return the cover page numbered (0, 1) — [assemblePages], or RestoredPageWindows, corrects the total —
     * or null when there is no cover.
     */
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
                blocks = sectionBlocks(section)
                    .blocksIn(coverRange.start - section.range.start, coverRange.end - section.range.start)
                    .rebasedBy(-section.range.start),
            )
        }

    /**
     * @param document the parsed document.
     * @param coverSection the section already claimed by the cover page, or null.
     * @return every section that still has to be paginated, in document order.
     */
    private fun contentSections(document: ReaderDocument, coverSection: ReaderSection?): List<ReaderSection> =
        document.sections.filter { section -> coverSection == null || section.index != coverSection.index }

    /**
     * Builds one page from a measured or restored range.
     *
     * A page's location is expressed the way its format names positions: an EPUB page carries its spine item
     * plus a section-relative offset, everything else an absolute text offset.
     *
     * `sectionBlocks` reads relative to the section's own start (see [defaultSectionBlocks]), so the filter
     * happens in that frame and the result is shifted back to absolute — a page's blocks have always
     * addressed the same offsets as its own `textRange`, which ReaderSemanticText relies on to locate a block
     * within the page's text.
     *
     * @param format the document's format, which decides the location's shape.
     * @param section the section this page belongs to; a page never spans two.
     * @param sectionBlocks that section's blocks, in the section's own frame.
     * @param range the page's absolute span.
     * @return the page, numbered (0, 0) until a caller renumbers it.
     */
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
        blocks = sectionBlocks
            .blocksIn(range.start - section.range.start, range.end - section.range.start)
            .rebasedBy(-section.range.start),
    )

    /**
     * Numbers a page list: cover first when present, then content, each page carrying the list's own total.
     *
     * Internal rather than private so DocumentRepositoryImpl can renumber a progressive pagination's
     * pages-so-far exactly the way [paginate] numbers a whole-book pass (see [resolveSections] /
     * [paginateSection]) instead of duplicating the renumbering a second time.
     *
     * @param coverPage the cover page, or null.
     * @param contentPages the content pages in reading order.
     * @return the numbered pages, or an empty list when there are none at all.
     */
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
     * A section with no readable text still yields one page when it carries something to draw — a full-page
     * illustration chapter is exactly that — so its picture is not silently dropped.
     *
     * `sectionBlocks` already reads relative to this section's own start (see [defaultSectionBlocks] and
     * DocumentRepositoryImpl.persistParsedDocument), so no rebase happens here on every pagination pass any
     * more; that shift now happens once, when the section was written.
     *
     * @param section the section to split.
     * @param sectionBlocks its blocks, in its own frame.
     * @param layout the estimated page geometry, used only when there is no measurement.
     * @param style the reading style, used only by the estimate.
     * @param pageBreaker the real measurement, or null to estimate.
     * @return one absolute range per page, or an empty list for a section with nothing to show.
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

    /**
     * The estimated page geometry for a style and viewport: how much glyph width fits on a line, and how many
     * lines on a page. Only the estimate uses this — a measured pass takes its boundaries from the renderer.
     *
     * @param style the reading style.
     * @param viewportSize the box a page is laid out into.
     * @return the estimated geometry, floored so a degenerate style still yields a drawable page.
     */
    private fun pageLayout(style: ReaderStyle, viewportSize: ViewportSize, viewportDensity: Float): PageLayout {
        val emWidth = style.fontSizeSp.coerceAtLeast(1f)
        val lineHeight = (style.fontSizeSp * style.lineHeightMultiplier).coerceAtLeast(1f)
        // The style's sizes are in sp, so the viewport is brought into sp with the density the caller
        // measured it at. A caller with no real pane yet passes an sp-sized guess with density 1.
        val widthSp = viewportSize.widthPx / viewportDensity.coerceAtLeast(0.01f)
        val heightSp = viewportSize.heightPx / viewportDensity.coerceAtLeast(0.01f)
        return PageLayout(
            widthUnitsPerLine = (widthSp * WideGlyphUnits / emWidth).toInt()
                .coerceAtLeast(WideGlyphUnits),
            linesPerPage = (heightSp / lineHeight).toInt().coerceAtLeast(1),
        )
    }

    /**
     * Turns the renderer's own page starts into ranges. The UI measured these against the real pane, so each
     * page fills it exactly.
     *
     * @param pageStarts section-relative start of every page, ascending.
     * @param textLength the section's length, which closes the last range.
     * @return one range per page, in reading order.
     */
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

    /**
     * The estimated split: walks the text counting glyph widths and lines, and closes a page when it is full.
     *
     * It wraps at spaces because the renderer does — packing mid-word instead cost the renderer an extra
     * line's worth of text per page. A trailing space is allowed to hang past the edge rather than wrap, so it
     * never starts a new line and only records where the next wrap may land. An image never splits across
     * pages: if it cannot fit in what is left of this page it starts the next one, exactly where the renderer
     * would push it.
     *
     * @param text the section's text.
     * @param widthUnitsPerLine how much glyph width fits on one line.
     * @param linesPerPage how many lines fit on one page.
     * @param standaloneHeights how many lines each standalone block occupies, keyed by its start offset.
     * @return one section-relative range per page; every page holds at least one character.
     */
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

            var lastWrapOpportunity = -1

            while (index < text.length && usedLines < linesPerPage) {
                val standaloneLines = standaloneHeights[index]
                if (standaloneLines != null) {
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
                    usedWidthUnits += widthUnits
                    index += 1
                    end = index
                    lastWrapOpportunity = index
                    continue
                }

                if (usedWidthUnits + widthUnits > widthUnitsPerLine) {
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

    /**
     * @receiver the character to measure.
     * @return its advance in hundredths of an em — see [WideGlyphUnits] and [NarrowGlyphUnits].
     */
    private fun Char.widthUnits(): Int = if (isWideGlyph()) WideGlyphUnits else NarrowGlyphUnits

    /**
     * @receiver the character to classify.
     * @return true for a full-width glyph: Hangul, Kana, CJK ideographs and the full-width forms — the ranges
     * a reader of Korean, Japanese and Chinese books actually meets.
     */
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

/**
 * [TextPageLayoutEngine.resolveSections]'s answer: the cover page, if the book has one, and the
 * sections [TextPageLayoutEngine.paginateSection] can measure independently of each other and in any
 * order.
 *
 * @property coverPage The document's already-built cover page, or null when the document has none —
 *   see [TextPageLayoutEngine.buildCoverPage].
 * @property contentSections The sections still left to paginate, in document order, with the cover
 *   section already excluded — see [TextPageLayoutEngine.contentSections].
 */
data class PaginationSections(
    val coverPage: PageWindow?,
    val contentSections: List<ReaderSection>,
)

/**
 * The page list [TextPageLayoutEngine.reconstruct] hands back: every [PageWindow] a real measurement
 * already placed, built the first time something reads it by [get] and kept after that. [size] and
 * page ordering come entirely from [contentPageStarts] — a handful of longs — so the total page count
 * is exact before a single section's blocks are ever decoded.
 *
 * @property coverPage The document's already-built cover page, or null when it has none. Handed back
 *   as-is at index 0, with only its `pageIndex` corrected to this list's real [size] — it never needs
 *   measuring to rebuild (see [TextPageLayoutEngine.reconstruct]'s own doc).
 * @property contentSections The document's non-cover sections, in spine order. [get] binary-searches
 *   this (via `sectionOwning`) to find which section a stored page start belongs to, and [buildAt]
 *   falls back to a section's own end when no later stored start already closes the page.
 * @property contentPageStarts One absolute document offset per content page, ascending, cover
 *   excluded — the same array [TextPageLayoutEngine.reconstruct] was handed. [size] and every page's
 *   section and range in [get] come from this array alone, without measuring anything.
 * @property format The document's format, carried through to [buildPage] so a restored page's
 *   location is expressed the same way a freshly measured one would be.
 * @property sectionBlocks How to get one section's blocks. Called only from [buildAt], the first time
 *   a page in that section is actually read — this is what lets this list answer its [size] before a
 *   single section's blocks are decoded.
 * @property buildPage [TextPageLayoutEngine.buildPageWindow], threaded through as a reference so this
 *   class can build a page without reaching into the engine's own private helpers.
 * @property isSectionReady Whether the section a just-built page belongs to is already decoded for
 *   real, not still answering from a stand-in. [get] uses this to decide whether the built page may be
 *   remembered in [built] for good, or must stay free to be rebuilt on a later read once the real
 *   blocks arrive (see class doc and DocumentRepositoryImpl.SectionBlocksCache).
 */
internal class RestoredPageWindows(
    private val coverPage: PageWindow?,
    private val contentSections: List<ReaderSection>,
    private val contentPageStarts: LongArray?,
    private val sectionPageStarts: List<LongArray>? = null,
    private val format: DocumentFormat,
    private val sectionBlocks: (ReaderSection) -> List<ReaderBlock>,
    private val buildPage: (DocumentFormat, ReaderSection, List<ReaderBlock>, TextRange) -> PageWindow,
    private val isSectionReady: (Int) -> Boolean = { true },
) : AbstractList<PageWindow>() {
    /** 1 when [coverPage] exists and occupies index 0, else 0 — shifts every content index [get] looks up. */
    private val coverOffset = if (coverPage != null) 1 else 0

    /** The cover page, if any, plus one entry per stored content page start — exact before any section is decoded. */
    override val size: Int = coverOffset + (contentPageStarts?.size ?: sectionPageStarts.orEmpty().sumOf { it.size })

    /** Pages already built by [get], capped so on-demand rebuild never grows unbounded. */
    private val built = HashMap<Int, PageWindow>()
    private val builtOrder = ArrayDeque<Int>()

    /** Prefix sum of pages per measured section, only needed for the grouped progressive path. */
    private val sectionEndIndexes: IntArray? = sectionPageStarts?.let { starts ->
        IntArray(starts.size).also { endIndexes ->
            var total = 0
            starts.forEachIndexed { index, sectionStarts ->
                total += sectionStarts.size
                endIndexes[index] = total
            }
        }
    }

    /** How many pages the bounded built-page cache currently holds — logged to show the saving. */
    val builtCount: Int get() = built.size

    /**
     * Builds the page at [index] on first read, and remembers it once its section's blocks are real.
     *
     * The cover page has nothing left to wait for — its section is always prewarmed before this list is handed
     * out, because cover detection needs it eagerly (see DocumentRepositoryImpl.restorePageWindows) — but its
     * `pageIndex` still has to be corrected to the real total: `buildCoverPage` hands over a lone
     * `PageIndex(0, 1)`, the same way `assemblePages` rewrites it on the measured path, and skipping that
     * correction is what left a restored cover page's total stuck at 1 while every other page in the same
     * list carried the real count.
     *
     * A page built while its own section's blocks are still a stand-in is deliberately **not** remembered:
     * the next read of this index may land after the real blocks arrived. Once the section is ready the page
     * is final and must never change again (see SectionBlocksCache).
     *
     * @param index the page to read, cover included.
     * @return that page, carrying the list's real total.
     * @throws IndexOutOfBoundsException when [index] falls outside `0 until size`.
     */
    override fun get(index: Int): PageWindow {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index: $index, size: $size")
        built[index]?.let {
            rememberBuilt(index, it)
            return it
        }
        if (coverPage != null && index == 0) {
            val page = coverPage.copy(pageIndex = PageIndex(current = 0, total = size))
            rememberBuilt(0, page)
            return page
        }
        val contentIndex = index - coverOffset
        val (section, page) = if (contentPageStarts != null) {
            val section = contentSections.sectionOwning(contentPageStarts[contentIndex])
            section to buildAt(index, contentIndex, section, contentPageStarts, contentIndex + 1)
        } else {
            val endIndexes = requireNotNull(sectionEndIndexes)
            val sectionPosition = endIndexes.firstIndexGreaterThan(contentIndex)
            val section = contentSections[sectionPosition]
            val starts = requireNotNull(sectionPageStarts)[sectionPosition]
            val sectionContentIndex = contentIndex - if (sectionPosition == 0) 0 else endIndexes[sectionPosition - 1]
            section to buildAt(index, sectionContentIndex, section, starts, sectionContentIndex + 1)
        }
        if (isSectionReady(section.index)) rememberBuilt(index, page)
        return page
    }

    /**
     * Builds one content page from its stored start.
     *
     * A page ends where the next stored start is, unless that start belongs to the section after this one —
     * then the page runs to the end of its own section instead, exactly like the per-section walk in
     * [TextPageLayoutEngine.paginate], since no page ever spans two sections.
     *
     * @param index the page's index in the whole list, cover included, for its `pageIndex`.
     * @param contentIndex the same page's index among the content pages, for the stored starts.
     * @param section the section that owns this page.
     * @return the built page, numbered against the list's real total.
     */
    private fun buildAt(
        index: Int,
        contentIndex: Int,
        section: ReaderSection,
        starts: LongArray,
        nextIndex: Int,
    ): PageWindow {
        val start = starts[contentIndex]
        val nextStart = starts.getOrNull(nextIndex)
        val end = if (nextStart != null && nextStart < section.range.end) nextStart else section.range.end
        val page = buildPage(format, section, sectionBlocks(section), TextRange(start, end))
        return page.copy(pageIndex = PageIndex(current = index, total = size))
    }

    private companion object {
        const val BuiltCacheMaxEntries = 16
    }

    private fun rememberBuilt(index: Int, page: PageWindow) {
        if (built.containsKey(index)) {
            builtOrder.remove(index)
        }
        built[index] = page
        builtOrder.addLast(index)
        while (builtOrder.size > BuiltCacheMaxEntries) {
            built.remove(builtOrder.removeFirst())
        }
    }
}

/**
 * The section whose range a stored page start falls in.
 *
 * Binary search rather than a scan: a restored page is built on demand, so this runs once per page read
 * rather than once per open, and scanning a 500-section book would show on every page turn.
 *
 * @receiver the content sections, ascending and non-overlapping.
 * @param offset an absolute document offset a page starts at.
 * @return the last section starting at or before [offset]; the first section when [offset] precedes them all.
 */
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

private fun IntArray.firstIndexGreaterThan(value: Int): Int {
    var lo = 0
    var hi = lastIndex
    var result = lastIndex
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid] > value) {
            result = mid
            hi = mid - 1
        } else {
            lo = mid + 1
        }
    }
    return result
}

/**
 * The estimated geometry of one page.
 *
 * @property widthUnitsPerLine how much glyph width fits on a line, in hundredths of an em.
 * @property linesPerPage how many lines fit on a page.
 */
private data class PageLayout(
    val widthUnitsPerLine: Int,
    val linesPerPage: Int,
)

/**
 * Glyph advance in hundredths of an em; a wide (CJK) glyph is exactly one em.
 *
 * ponytail: calibrated constant; only real text measurement gives font-exact fill.
 */
private const val WideGlyphUnits = 100

/**
 * A proportional narrow glyph's advance — not half an em: measured on the rendered reader (sans-serif Latin
 * prose, 393 sp pane, 18 sp text) it is ~0.44 em.
 *
 * Because the line model wraps at spaces like the renderer does, this is the plain advance and carries no
 * extra allowance; it stays a shade pessimistic so the model never claims a word fits where the renderer
 * would push it to the next line.
 */
private const val NarrowGlyphUnits = 45

/** Longest chapter this lays out for real. Beyond it the estimate takes over, imprecise but bounded. */
private const val MaxMeasuredContentLengthChars = 200_000

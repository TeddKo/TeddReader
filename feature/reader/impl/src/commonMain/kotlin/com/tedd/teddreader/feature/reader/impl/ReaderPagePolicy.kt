package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import com.tedd.teddreader.core.common.model.layoutKey

/**
 * The pages the pager keeps ready around [currentPage]: two behind, three ahead.
 *
 * One definition, because three separate concerns depend on the window being the same range — the slots the
 * pager mounts, the sections whose block styling is warmed before a page is published, and the images fetched
 * for the pages on screen. When one of them used its own literal range, a change to the radius silently left
 * the others behind.
 *
 * Deliberately not clamped: a caller near the start of the book receives the negative half and filters it
 * against its own list, which keeps this function free of any notion of how long the document is.
 *
 * @param currentPage the page the reader is on.
 * @return the inclusive range of pages to keep ready.
 */
internal fun pagerMountWindow(currentPage: Int): IntRange = currentPage - 2..currentPage + 3

/**
 * The table of contents to show for a document, which differs by what the format can actually offer.
 *
 * A visual document has no headings, so its outline is one entry per page, and every entry is a
 * [ReaderLocation.PdfPage]. That is the only location kind such an outline produces, and a text-offset lookup
 * cannot resolve it — which is why the reader keeps a dedicated branch for it when moving to a location. An
 * outline entry that resolved to nothing would be a tap that silently does nothing, which the reader's own
 * invariants forbid.
 *
 * An EPUB that carries navigation is shown that, with the levels the book declares. Failing that, the
 * sections themselves are the outline, so a document always has one.
 *
 * @param format the document's format, or null while it is unknown.
 * @param readerDocument the parsed document, whose navigation and sections are the entries; null yields an
 * empty outline for a text format.
 * @param totalPages how many pages the outline should cover, used only by the visual case.
 * @return the outline entries in reading order.
 */
internal fun readerOutlineItems(
    format: DocumentFormat?,
    readerDocument: ReaderDocument?,
    totalPages: Int,
): List<ReaderOutlineItem> {
    if (format?.isVisualPageFormat() == true) {
        return (0 until totalPages).map { page ->
            ReaderOutlineItem(
                title = "Page ${page + 1}",
                location = ReaderLocation.PdfPage(page),
            )
        }
    }
    val navigationItems = readerDocument?.navigation?.items.orEmpty()
    if (format == DocumentFormat.EPUB && navigationItems.isNotEmpty()) {
        return navigationItems.map { item ->
            ReaderOutlineItem(
                title = item.title,
                location = ReaderLocation.EpubOffset(item.spineIndex, item.offset),
                level = item.level,
            )
        }
    }
    val sections = readerDocument?.sections.orEmpty()
    return sections.map { section ->
        ReaderOutlineItem(
            title = section.title ?: "Section ${section.index + 1}",
            location = when (format) {
                DocumentFormat.EPUB -> ReaderLocation.EpubOffset(section.index, 0)
                else -> ReaderLocation.TextOffset(section.range.start)
            },
            level = 1,
        )
    }
}

/**
 * What the reader should do with a pane's measurement report.
 *
 * Three outcomes rather than a boolean, because recording a measurement and acting on it are separate
 * decisions: the middle case exists precisely so a report can be believed without paying for a re-measurement.
 */
internal enum class PaneReportOutcome {
    /** The report describes the measurement already held; nothing changes. */
    Ignore,

    /** Record the measurement, but do not lay the book out again — the pages already answer for it. */
    RecordOnly,

    /** A genuinely new measurement: record it and lay the book out again. */
    RecordAndReload,
}

/**
 * Decides what a pane's measurement report means, comparing what the measurement *describes* rather than
 * which instance carries it.
 *
 * The reporting pane moves to a different composition slot on every page turn, and a page effect may compose
 * the page twice while it animates. Treating those fresh instances as new measurements laid the whole document
 * out again on every turn, which is why the first case compares style and pixel box instead.
 *
 * The [RecordOnly] case is the confirmation of an adopted layout. Opening a document adopts a stored layout's
 * viewport, and the style it was measured for, before any pane has reported — the breaker itself is still
 * absent then. The pane's first real report is almost always the same sp size, since it is the same physical
 * screen, so the pages already cached under that size answer for it and reloading would only ask again.
 *
 * @param reportedStyle the style the pane measured with.
 * @param reportedSizePx the pane's real measured pixel box.
 * @param reportedViewportSp the same box in sp, the unit pagination and stored page layouts key on.
 * @param currentBreakerStyle the style the held measurement was made for, or null when none was ever held or
 * adopted.
 * @param currentBreakerSizePx the pixel box of the held measurement, or null when no pane has reported yet.
 * @param hasBreaker whether a real page breaker is held — false while only a stored layout has been adopted.
 * @param currentViewportSp the sp viewport currently in force, which is what an adopted layout wrote.
 * @return which of the three actions the caller should take.
 */
internal fun paneReportOutcome(
    reportedStyle: ReaderStyle,
    reportedSizePx: ViewportSize,
    reportedViewportSp: ViewportSize,
    currentBreakerStyle: ReaderStyle?,
    currentBreakerSizePx: ViewportSize?,
    hasBreaker: Boolean,
    currentViewportSp: ViewportSize,
): PaneReportOutcome {
    val sameStyle = currentBreakerStyle?.layoutKey() == reportedStyle.layoutKey()
    if (sameStyle && currentBreakerSizePx == reportedSizePx) return PaneReportOutcome.Ignore
    if (!hasBreaker && sameStyle && currentViewportSp == reportedViewportSp) return PaneReportOutcome.RecordOnly
    return PaneReportOutcome.RecordAndReload
}

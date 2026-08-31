package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderNavigation
import com.tedd.teddreader.core.common.model.ReaderNavigationItem
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the reader's page policy — the decisions that used to live inside ReaderViewModel and could only be
 * observed by standing up four fake repositories and driving a coroutine dispatcher.
 *
 * Every case here is a rule a reader would notice breaking: which page a resumed book opens on, what the
 * page counter claims before anything is measured, what an outline tap can reach, and whether a pane's
 * repeated size report costs the book a full re-measurement.
 */
class ReaderPagePolicyTest {
    private val style = ReaderStyle(fontSizeSp = 18f)
    private val otherStyle = ReaderStyle(fontSizeSp = 24f)
    private val sizePx = ViewportSize(widthPx = 1080, heightPx = 1920)
    private val otherSizePx = ViewportSize(widthPx = 1080, heightPx = 1600)
    private val viewportSp = ViewportSize(widthPx = 360, heightPx = 640)
    private val otherViewportSp = ViewportSize(widthPx = 360, heightPx = 540)

    /** Two sections, so a section-relative EPUB position has a non-zero base to be resolved against. */
    private fun document(
        format: DocumentFormat = DocumentFormat.EPUB,
        navigation: ReaderNavigation? = null,
        sectionTitles: List<String?> = listOf("Chapter 1", "Chapter 2"),
    ): ReaderDocument = ReaderDocument(
        id = DocumentId("file:///book.epub"),
        format = format,
        title = "Book",
        sections = sectionTitles.mapIndexed { index, title ->
            ReaderSection(
                index = index,
                title = title,
                text = "body",
                range = TextRange(index * 100L, index * 100L + 4L),
            )
        },
        navigation = navigation,
    )

    /** Publisher/system-follow is one adaptive option, never two rows for the same fallback policy. */
    @Test
    fun documentDefaultThemeIsOneOptionAdaptedToTheFormat() {
        val publisherOptions = listOf(
            ReaderThemeMode.PUBLISHER,
            ReaderThemeMode.LIGHT,
            ReaderThemeMode.DARK,
            ReaderThemeMode.SEPIA,
        )
        val readerOptions = listOf(
            ReaderThemeMode.SYSTEM,
            ReaderThemeMode.LIGHT,
            ReaderThemeMode.DARK,
            ReaderThemeMode.SEPIA,
        )

        assertEquals(publisherOptions, readerThemeModeOptions(DocumentFormat.EPUB))
        assertEquals(publisherOptions, readerThemeModeOptions(DocumentFormat.PDF))
        listOf(DocumentFormat.TXT, DocumentFormat.CBZ, DocumentFormat.IMAGE, DocumentFormat.UNKNOWN).forEach { format ->
            assertEquals(readerOptions, readerThemeModeOptions(format))
        }
    }

    /** Legacy values normalize both ways so the single adaptive row is selected for every format. */
    @Test
    fun documentDefaultThemeModeAdaptsToTheFormat() {
        val publisherStyle = ReaderStyle(themeMode = ReaderThemeMode.PUBLISHER)
        val systemStyle = ReaderStyle(themeMode = ReaderThemeMode.SYSTEM)

        assertEquals(ReaderThemeMode.PUBLISHER, readerStyleForDocumentFormat(publisherStyle, DocumentFormat.EPUB).themeMode)
        assertEquals(ReaderThemeMode.PUBLISHER, readerStyleForDocumentFormat(systemStyle, DocumentFormat.EPUB).themeMode)
        assertEquals(ReaderThemeMode.PUBLISHER, readerStyleForDocumentFormat(systemStyle, DocumentFormat.PDF).themeMode)
        assertEquals(ReaderThemeMode.SYSTEM, readerStyleForDocumentFormat(publisherStyle, DocumentFormat.TXT).themeMode)
        assertEquals(
            ReaderThemeMode.DARK,
            readerStyleForDocumentFormat(ReaderStyle(themeMode = ReaderThemeMode.DARK), DocumentFormat.TXT).themeMode,
        )
    }

    @Test
    fun publisherFontMeasurementWaitsForTheCurrentEmbeddedFontFiles() {
        val embeddedFiles = mapOf("fonts/book.woff2" to "/tmp/book.woff2")
        val publisherStyle = ReaderStyle(fontFamilyName = null)
        val overrideStyle = ReaderStyle(fontFamilyName = "serif")

        assertEquals(
            false,
            readerEmbeddedFontsReadyForMeasurement(
                style = publisherStyle,
                areEmbeddedFontsResolved = true,
                embeddedFontFiles = embeddedFiles,
                loadedEmbeddedFontFiles = null,
            ),
        )
        assertEquals(
            true,
            readerEmbeddedFontsReadyForMeasurement(
                style = publisherStyle,
                areEmbeddedFontsResolved = true,
                embeddedFontFiles = embeddedFiles,
                loadedEmbeddedFontFiles = embeddedFiles,
            ),
        )
        assertEquals(
            true,
            readerEmbeddedFontsReadyForMeasurement(
                style = overrideStyle,
                areEmbeddedFontsResolved = false,
                embeddedFontFiles = embeddedFiles,
                loadedEmbeddedFontFiles = null,
            ),
        )
    }

    /** The mount window is the one definition of "pages the pager keeps ready": two back, three forward. */
    @Test
    fun mountWindowSpansTwoPagesBackAndThreeForward() {
        assertEquals(3..8, pagerMountWindow(5))
    }

    /** It is not clamped here — a caller near page zero receives the negative half and filters it itself. */
    @Test
    fun mountWindowIsNotClampedAtTheStartOfTheBook() {
        assertEquals(-2..3, pagerMountWindow(0))
    }

    /**
     * A visual document's outline is one entry per page, and each entry is a [ReaderLocation.PdfPage].
     *
     * That shape is load-bearing: it is the only location kind an outline tap on a PDF or comic produces, and
     * a text-offset lookup cannot resolve it, which is why `moveToLocation` keeps its own branch for it.
     */
    @Test
    fun visualOutlineIsOnePdfPageEntryPerPage() {
        val items = readerOutlineItems(DocumentFormat.CBZ, document(format = DocumentFormat.CBZ), totalPages = 3)

        assertEquals(3, items.size)
        assertEquals(listOf("Page 1", "Page 2", "Page 3"), items.map { it.title })
        assertEquals(
            listOf(ReaderLocation.PdfPage(0), ReaderLocation.PdfPage(1), ReaderLocation.PdfPage(2)),
            items.map { it.location },
        )
    }

    /** A book that carries its own table of contents is shown that, with the levels it declares. */
    @Test
    fun epubOutlineUsesTheBooksOwnNavigationWhenItHasSome() {
        val navigation = ReaderNavigation(
            items = listOf(
                ReaderNavigationItem(title = "Preface", level = 1, spineIndex = 0, offset = 0),
                ReaderNavigationItem(title = "Part I", level = 2, spineIndex = 1, offset = 12),
            ),
        )
        val items = readerOutlineItems(DocumentFormat.EPUB, document(navigation = navigation), totalPages = 9)

        assertEquals(listOf("Preface", "Part I"), items.map { it.title })
        assertEquals(listOf(1, 2), items.map { it.level })
        assertEquals(ReaderLocation.EpubOffset(1, 12), items[1].location)
    }

    /** Without navigation the sections themselves are the outline, so a book always has one. */
    @Test
    fun epubOutlineFallsBackToSections() {
        val items = readerOutlineItems(DocumentFormat.EPUB, document(), totalPages = 9)

        assertEquals(listOf("Chapter 1", "Chapter 2"), items.map { it.title })
        assertEquals(ReaderLocation.EpubOffset(0, 0), items.first().location)
    }

    /** An untitled section still gets a usable label rather than an empty row. */
    @Test
    fun sectionOutlineNamesAnUntitledSectionByItsNumber() {
        val items = readerOutlineItems(
            DocumentFormat.EPUB,
            document(sectionTitles = listOf(null, "Chapter 2")),
            totalPages = 9,
        )

        assertEquals("Section 1", items.first().title)
    }

    /** A plain text document's outline points at absolute text offsets, not spine positions. */
    @Test
    fun textOutlinePointsAtAbsoluteOffsets() {
        val items = readerOutlineItems(DocumentFormat.TXT, document(format = DocumentFormat.TXT), totalPages = 4)

        assertEquals(ReaderLocation.TextOffset(0), items.first().location)
        assertEquals(ReaderLocation.TextOffset(100), items[1].location)
    }

    /**
     * A report describing the measurement the reader already holds is ignored.
     *
     * The reporting pane composes fresh breaker instances on every page turn, and a page effect may compose
     * the page twice while it animates; treating those as new measurements laid the whole document out again
     * on every turn, so the comparison is by what the measurement describes, never by instance.
     */
    @Test
    fun paneReportIsIgnoredWhenItDescribesTheMeasurementAlreadyHeld() {
        assertEquals(
            PaneReportOutcome.Ignore,
            paneReportOutcome(
                reportedStyle = style,
                reportedSizePx = sizePx,
                reportedViewportSp = viewportSp,
                currentBreakerStyle = style,
                currentBreakerSizePx = sizePx,
                hasBreaker = true,
                currentViewportSp = viewportSp,
            ),
        )
    }

    /**
     * The first real report after a stored layout was adopted is recorded without a reload.
     *
     * Opening a document adopts a stored layout's viewport and the style it was measured for before any pane
     * has reported, while the breaker itself is still absent. This report is that adoption's confirmation —
     * the same physical screen, so the same sp size the pages were already cached under — and reloading would
     * only ask for the answer a second time.
     */
    @Test
    fun paneReportIsRecordedWithoutReloadWhenItConfirmsAnAdoptedViewport() {
        assertEquals(
            PaneReportOutcome.RecordOnly,
            paneReportOutcome(
                reportedStyle = style,
                reportedSizePx = sizePx,
                reportedViewportSp = viewportSp,
                currentBreakerStyle = style,
                currentBreakerSizePx = null,
                hasBreaker = false,
                currentViewportSp = viewportSp,
            ),
        )
    }

    /** A report for a different type is a real measurement change: the book has to be laid out again. */
    @Test
    fun paneReportTriggersReloadForADifferentStyle() {
        assertEquals(
            PaneReportOutcome.RecordAndReload,
            paneReportOutcome(
                reportedStyle = otherStyle,
                reportedSizePx = sizePx,
                reportedViewportSp = viewportSp,
                currentBreakerStyle = style,
                currentBreakerSizePx = sizePx,
                hasBreaker = true,
                currentViewportSp = viewportSp,
            ),
        )
    }

    /** So is a report for a different pane size at the same type. */
    @Test
    fun paneReportTriggersReloadForADifferentMeasuredSize() {
        assertEquals(
            PaneReportOutcome.RecordAndReload,
            paneReportOutcome(
                reportedStyle = style,
                reportedSizePx = otherSizePx,
                reportedViewportSp = viewportSp,
                currentBreakerStyle = style,
                currentBreakerSizePx = sizePx,
                hasBreaker = true,
                currentViewportSp = viewportSp,
            ),
        )
    }

    /**
     * A first report whose sp viewport differs from the adopted one is not a confirmation — the pages cached
     * under the adopted size describe a different box, so this one has to be measured.
     */
    @Test
    fun paneReportTriggersReloadWhenTheAdoptedViewportDoesNotMatch() {
        assertEquals(
            PaneReportOutcome.RecordAndReload,
            paneReportOutcome(
                reportedStyle = style,
                reportedSizePx = sizePx,
                reportedViewportSp = otherViewportSp,
                currentBreakerStyle = style,
                currentBreakerSizePx = null,
                hasBreaker = false,
                currentViewportSp = viewportSp,
            ),
        )
    }

    /** With nothing adopted and nothing measured, the very first report is a real measurement. */
    @Test
    fun theFirstReportOfAFreshDocumentTriggersReload() {
        assertEquals(
            PaneReportOutcome.RecordAndReload,
            paneReportOutcome(
                reportedStyle = style,
                reportedSizePx = sizePx,
                reportedViewportSp = viewportSp,
                currentBreakerStyle = null,
                currentBreakerSizePx = null,
                hasBreaker = false,
                currentViewportSp = viewportSp,
            ),
        )
    }

    /** Only the primary pane owns whole-document measurement and viewport reporting. */
    @Test
    fun secondaryPaneDoesNotCreateAPageBreaker() {
        assertEquals(true, readerPagePaneShouldMeasure(reportViewportSize = true))
        assertEquals(false, readerPagePaneShouldMeasure(reportViewportSize = false))
    }
}

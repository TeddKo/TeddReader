package com.tedd.teddreader.feature.reader.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.isImagePageFormat
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import com.tedd.teddreader.core.common.model.withLayoutFieldsOf
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * One rendered page's worth of content and rendering hints, as [ReaderUiState] hands it to the
 * page surfaces (`ReaderPageSurface`, [com.tedd.teddreader.feature.reader.impl.EpubPageSurface],
 * `ImagePageSurface`, `PdfPageSurface`).
 *
 * A page slot exists so the reader can hold the current page plus its immediate neighbours (and,
 * for EPUB, the whole batch pagination has produced so far) without the surfaces needing to know
 * anything about pagination, storage, or import state — they only ever read one of these.
 *
 * @property page The zero-based index this content was paginated for. Callers key lookups
 *   (`ReaderUiState.pageSlot`) on this rather than on list position, since slots are not always
 *   contiguous.
 * @property text Plain-text page content for formats that render as flat text (TXT, and EPUB
 *   before richer block rendering replaced it); empty when [blocks] carries the real content.
 * @property isPdf True when this slot is a rendered PDF page image rather than text.
 * @property documentUri The source URI backing this specific page, when a page can point at a
 *   document different from the one currently open (used by PDF page rendering).
 * @property textRange The half-open offset range into the document's full text that this page's
 *   [text] corresponds to, used to resolve reading positions and locations against the page.
 * @property blocks The structured EPUB content (paragraphs, images, separators) this page was
 *   paginated into. Empty for plain-text formats.
 * @property embeddedImages EPUB images embedded in this book, keyed by their href, decoded and
 *   ready to draw. Only images this page actually references are guaranteed to be present.
 * @property embeddedFontFiles EPUB font files embedded in this book, keyed by their href and resolved
 *   to reusable local file paths. Only fonts this page actually references are guaranteed to be present.
 * @property failedEmbeddedImageHrefs Hrefs from [blocks] whose image bytes could not be decoded,
 *   so the surface can show a label instead of retrying a request that already failed.
 * @property failedEmbeddedFontHrefs Hrefs from [blocks] or span CSS whose font file could not be
 *   resolved, so a renderer can stop waiting for it.
 * @property chapterTitle The heading of the section this page belongs to, when the reader chrome
 *   needs to show it outside of the page body itself.
 * @property isSectionTail True when this page is the last page of its EPUB section — the honest,
 *   by-construction signal `EpubPageSurface` centres a short page on, in place of how much of the
 *   sheet it rendered to fill (see that composable's own doc for why the rendered-height signal
 *   broke on an estimated page, before the type had ever been measured for real).
 */
@Immutable
data class ReaderPageUi(
    val page: Int = 0,
    val text: String = "",
    val isPdf: Boolean = false,
    val documentUri: String? = null,
    val textRange: TextRange? = null,
    val blocks: ImmutableList<ReaderBlock> = persistentListOf(),
    val embeddedImages: ImmutableMap<String, ByteArray> = persistentMapOf(),
    val embeddedFontFiles: ImmutableMap<String, String> = persistentMapOf(),
    val failedEmbeddedImageHrefs: ImmutableSet<String> = persistentSetOf(),
    val failedEmbeddedFontHrefs: ImmutableSet<String> = persistentSetOf(),
    val chapterTitle: String? = null,
    val isSectionTail: Boolean = false,
)

/**
 * The whole state of the reader screen, as [ReaderViewModel][com.tedd.teddreader.feature.reader.impl.ReaderViewModel]
 * publishes it and [ReaderScreen] renders it. `ReaderScreen` is a pure pass-through onto this
 * state and the callbacks the view model exposes — every value shown or interaction offered on
 * screen traces back to a property here.
 *
 * @property documentTitle The title shown in the top bar and status footer.
 * @property documentUri The source URI of the open document, used by page surfaces (PDF, image)
 *   that need to re-read bytes directly rather than through a page slot.
 * @property documentFormat The open document's format, which selects which page surface renders
 *   [currentPage]/[pageSlots] and which visual mode ([isVisualMode], [isImageMode]) applies.
 * @property pageText Plain-text fallback for the current page, used before a page slot exists or
 *   for formats that never populate one.
 * @property pageIndex The current page position and the page count known so far. See
 *   [isPaginationComplete] for what "known so far" means while an import or measurement is still
 *   running — `total` only ever grows for a document that stays open, never shrinks.
 * @property previousPage The page slot immediately before [currentPage], kept so adjacent-page
 *   transitions never wait on a fresh load.
 * @property currentPage The page slot actually on screen.
 * @property nextPage The page slot immediately after [currentPage], for the same reason as
 *   [previousPage].
 * @property pageSlots The full set of page slots the view model currently holds beyond just the
 *   current/previous/next triple — used where a pager needs to look further afield.
 * @property style The active typography/theme style pages are rendered with — what the reader just
 *   chose, and what every picker, preview, and committed-value readout shows. See [pageDrawStyle] for
 *   what the page surfaces actually draw with, which is not always the same value.
 * @property pageLayoutStyle The style [currentPage], [previousPage], [nextPage], and [pageSlots] were
 *   actually paginated under, or null when they were paginated under whatever [style] already says —
 *   the common case, true from the moment a document opens until some other publish changes [style]
 *   ahead of the re-pagination it triggers. Written by
 *   [ReaderViewModel][com.tedd.teddreader.feature.reader.impl.ReaderViewModel] in lockstep with its
 *   own `paginated` field, never read directly outside this class — [pageDrawStyle] is the value
 *   every render-path consumer should read instead.
 * @property isControlsVisible Whether the top bar, bottom bar, and status footer are shown; a tap
 *   in the page area toggles this.
 * @property isLoading True while the document is still being opened; `ReaderScreen` shows a
 *   full-screen loading indicator and renders nothing else while this is true.
 * @property errorMessage Non-null when opening the document failed; shown in place of page
 *   content.
 * @property activeSheet The currently open option sheet (view/font/theme/page-turn/etc.), or null
 *   when no sheet is showing.
 * @property pageTurnMode Whether pages turn along the horizontal or vertical axis.
 * @property pageAnimation The page-turn animation currently selected.
 * @property autoScrollConfig Auto-scroll enablement, mode, and speed.
 * @property outlineHeading The table-of-contents heading text for the open document, if any.
 * @property outlineItems The table-of-contents entries for the open document.
 * @property brightnessOverlayAlpha The alpha of the black overlay drawn over the whole screen to
 *   simulate dimming below the display's own minimum brightness.
 * @property pdfZoom The current PDF/visual zoom factor.
 * @property pdfRotationDegrees The current PDF page rotation.
 * @property keepScreenOn Whether the screen should be prevented from sleeping while reading.
 * @property fullscreen Whether the reader hides system bars entirely.
 * @property showProgress Whether the bottom bar shows the page-position slider and label.
 * @property isPdfMode True when the open document is a PDF, which changes how pages are measured
 *   and rendered relative to text/EPUB documents.
 * @property visualPageImages Decoded page images for visual formats (CBZ, image), keyed by page
 *   index.
 * @property failedVisualPages Visual page indices whose image could not be decoded.
 * @property embeddedFontFiles Resolved EPUB font file paths keyed by href, published at screen scope so
 *   a caller that rebuilds typefaces or the page breaker can do so without retaining font bytes.
 * @property failedEmbeddedFontHrefs EPUB font hrefs that have already failed to resolve, so the UI can
 *   stop waiting for them.
 * @property isFavorite Whether the open document is bookmarked in the library.
 * @property isCurrentPageSaved Whether the current page/location has been explicitly saved as a
 *   place, distinct from the automatically persisted reading position.
 * @property isSavingSettings True while a style/settings change is being persisted, used to
 *   disable option-sheet controls so a second change cannot race the first.
 * @property isPaginationComplete False while a progressively-imported EPUB is still being parsed
 *   and measured in the background (see
 *   [ReaderViewModel.continueImportIfIncomplete][com.tedd.teddreader.feature.reader.impl.ReaderViewModel]),
 *   or while the current style/line-height/typeface has never been measured for this book before
 *   and is still being laid out one section at a time (see `continuePaginationIfIncomplete`).
 *   [pageIndex]'s `total` is therefore "pages known so far," not the whole book, while this is
 *   false — the UI must say so (a trailing "+" on the page count, a disabled-but-visible slider)
 *   rather than show a total that keeps growing silently. Becomes true once both are done,
 *   including for a document that needed neither step.
 */
@Immutable
data class ReaderUiState(
    val documentTitle: String = "Reader",
    val documentUri: String? = null,
    val documentFormat: DocumentFormat = DocumentFormat.UNKNOWN,
    val pageText: String = "",
    val pageIndex: PageIndex = PageIndex(current = 0, total = 0),
    val readProgressPercent: Int = 0,
    val previousPage: ReaderPageUi? = null,
    val currentPage: ReaderPageUi = ReaderPageUi(),
    val nextPage: ReaderPageUi? = null,
    val pageSlots: ImmutableList<ReaderPageUi> = persistentListOf(),
    val style: ReaderStyle = ReaderStyle(),
    val pageLayoutStyle: ReaderStyle? = null,
    /**
     * The page margins the open book's own `html`/`body` styling asks for, resolved once by the
     * view-model — from the first frame when possible — so the pane's padding never flips after the
     * text area has already been measured; a late flip re-paginated the whole book on open.
     */
    val publisherPageMargins: ReaderPageMarginsEm = ReaderPageMarginsEm.Zero,
    /**
     * Whether every embedded font this document references anywhere has been resolved or has failed —
     * the document-wide fact the measurement gate needs. Measuring pages is a whole-book act, so it must
     * wait for the whole book's fonts: gating on the current page's fonts alone let a fontless cover
     * page open the gate and the entire book was measured with fallback type, clipping every page whose
     * real type ran longer. True from the start for formats and font overrides that need no embedded fonts.
     */
    val areEmbeddedFontsResolved: Boolean = false,
    val isControlsVisible: Boolean = true,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val activeSheet: ReaderOptionSheet? = null,
    val pageTurnMode: PageTurnMode = PageTurnMode.HORIZONTAL,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val autoScrollConfig: AutoScrollConfig = AutoScrollConfig(),
    val outlineHeading: String? = null,
    val outlineItems: ImmutableList<ReaderOutlineItem> = persistentListOf(),
    val brightnessOverlayAlpha: Float = 0f,
    val pdfZoom: Float = 1f,
    val pdfRotationDegrees: Float = 0f,
    val keepScreenOn: Boolean = false,
    val fullscreen: Boolean = false,
    val showProgress: Boolean = true,
    val isPdfMode: Boolean = false,
    val visualPageImages: ImmutableMap<Int, ByteArray> = persistentMapOf(),
    val failedVisualPages: ImmutableSet<Int> = persistentSetOf(),
    val embeddedFontFiles: ImmutableMap<String, String> = persistentMapOf(),
    val failedEmbeddedFontHrefs: ImmutableSet<String> = persistentSetOf(),
    val isFavorite: Boolean = false,
    val isCurrentPageSaved: Boolean = false,
    val isSavingSettings: Boolean = false,
    val isPaginationComplete: Boolean = true,
) {
    /** True for PDF or any other document format that pages as whole images rather than text. */
    val isVisualMode: Boolean get() = isPdfMode || documentFormat.isVisualPageFormat()

    /** True for a document format (CBZ) that pages as one decoded image per page. */
    val isImageMode: Boolean get() = documentFormat.isImagePageFormat()

    /**
     * The style to actually draw [currentPage]/[previousPage]/[nextPage]/[pageSlots] with:
     * [pageLayoutStyle]'s type — font family, size, line height — laid over [style]'s colour, theme,
     * and background image, or [style] itself once [pageLayoutStyle] is null and the two already
     * agree.
     *
     * This is what keeps a layout-affecting setting change from clipping or gapping the pages already
     * on screen. Changing font, size, or line height publishes the new [style] to this state
     * synchronously, but the page slices held in [currentPage] and its neighbours were sliced for
     * whatever style was live *before* that change — the pane only remeasures and reports back
     * asynchronously, and until it does there is no fresh slice to show. Reading [style] directly at
     * the two page-drawing call sites would draw those old slices with the new type: on an emulator
     * run that produced pages up to 167px too tall for a 2641px page (bottom lines clipped) or as much
     * as 434px of unfilled page. Reading through this getter instead draws the same old slices with
     * the type they were actually cut for, and the swap to the new type is atomic because
     * [pageLayoutStyle] is published in the same state update as the freshly re-measured slices — see
     * `ReaderPagePane` in `ReaderScreen.kt`, the one composable that must read this instead of [style]
     * for its two draw calls, and must keep reading [style] itself for measurement and for the padding
     * that defines the box being measured.
     *
     * @return [style] with its layout fields replaced by [pageLayoutStyle]'s, or [style] unchanged
     *   when [pageLayoutStyle] is null.
     */
    val pageDrawStyle: ReaderStyle
        get() = pageLayoutStyle?.let { style.withLayoutFieldsOf(it) } ?: style
}

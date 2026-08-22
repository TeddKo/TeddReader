package com.tedd.teddreader.feature.reader.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tedd.teddreader.core.common.ByteArrayLruCache
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.PaginatedDocument
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import com.tedd.teddreader.core.common.model.layoutKey
import com.tedd.teddreader.core.common.model.withThemeMode
import com.tedd.teddreader.core.domain.reader.ImportBatchSectionCount
import com.tedd.teddreader.core.domain.reader.canReportPaginationComplete
import com.tedd.teddreader.core.domain.reader.needsPaginationContinuation
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.domain.repository.BookmarkRepository
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReaderSettings
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import com.tedd.teddreader.core.domain.usecase.OpenReaderDocumentUseCase
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import kotlin.time.Clock

/**
 * Owns one open reader document end to end: loading it, paginating it, keeping its reading
 * position and settings in sync, and publishing everything [ReaderUiState] needs to render it.
 *
 * [currentDocumentId] names the document this instance currently considers open, and every
 * suspending function below re-checks it against its own parameter after every suspension point,
 * before touching [_uiState] or any other field. That check exists because `Job.cancel()` cannot
 * stop a database read or a decode already in flight: cancelling [openDocumentJob] (or any of the
 * background continuation jobs below) only stops a coroutine from reaching its *next* suspension
 * point — it does not retract a read that is already running and about to resolve. Without the
 * re-check, that resolving read would still publish its result: a stale document's metadata,
 * pages, or blocks reaching the UI after the reader has already moved on to a different book. This
 * is a convention, not something the compiler enforces — nothing stops a new suspending function
 * from being added here without the check, and when that happens the failure it reintroduces is
 * silent, not a crash: the app just occasionally shows a page or a title that belongs to the book
 * the reader left, not the one now on screen. Every function below that suspends documents its own
 * re-check for this reason; treat a new one that lacks it as a bug.
 *
 * @property documentRepository where a document's metadata, structure, pagination, and images come
 *   from.
 * @property bookmarkRepository where saved reading places are stored and observed.
 * @property readerSettingsRepository where the reader's persisted style/behaviour settings are
 *   stored and observed.
 * @property readerRepository where the reader's per-document reading progress is stored.
 */
@KoinViewModel
class ReaderViewModel(
    private val documentRepository: DocumentRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val readerSettingsRepository: ReaderSettingsRepository,
    private val readerRepository: ReaderRepository,
    private val openReaderDocumentUseCase: OpenReaderDocumentUseCase,
) : ViewModel() {
    /** The state [ReaderScreen] renders; mutated only through this instance's own `update`/`value =`. */
    private val _uiState = MutableStateFlow(ReaderUiState())

    /** The read-only stream [ReaderScreen] collects — the same flow [_uiState] publishes into. */
    val uiState: StateFlow<ReaderUiState> = _uiState

    /**
     * The document this instance currently considers open, or null before the first [openDocument]
     * call. See this class's own documentation for the re-check convention every suspending
     * function below owes this field.
     */
    private var currentDocumentId: DocumentId? = null

    /**
     * This reader's most recent pagination result — the page windows [documentRepository] measured
     * and the sections they were laid out over, held together as one [PaginatedDocument] so a page's
     * chapter title and section-tail flag are always read against the section list that pagination
     * actually produced them from. Reset to an empty [PaginatedDocument] whenever [openDocument] opens
     * a new document, and reassigned by [loadOpenState] once at open time and by [reloadPages] every
     * time pagination measures more of the book. [reloadPages] can itself be running concurrently from
     * up to four different coroutines that all publish over this same field: a viewport reload
     * ([updatePageBreaker]'s [viewportReloadJob]), an import batch ([continueImportIfIncomplete]'s
     * [importContinuationJob]), a pagination batch ([continuePaginationIfIncomplete]'s
     * [paginationContinuationJob]), and a style change's own launch inside [updateStyle] — which is
     * why a caller about to warm or publish from a page list should prefer the local pair it just
     * measured over reading this field back (see [warmMountWindow]'s own doc for the same reasoning).
     */
    private var paginated: PaginatedDocument = PaginatedDocument()

    /**
     * The reading position as an absolute text offset, independent of any one pagination pass.
     *
     * Page numbers only mean something for the one (style, viewport) pagination that produced
     * them, so the position that has to survive a repagination — a font change, a viewport resize,
     * an import batch appending sections — is tracked here as a raw offset instead. [reloadPages]
     * resolves this back into a page index against whichever fresh [PaginatedDocument] it just
     * measured.
     */
    private var anchorOffset: Long? = null

    /**
     * The viewport pagination and page-layout storage key on — sp, not px. Written by
     * [loadOpenState] when it adopts a stored layout's viewport, and by [updatePageBreaker] when
     * the pane reports a genuinely new size (see that function's own doc for why the unit is sp
     * rather than the pane's real pixel box, which [pageBreakerSize] holds instead).
     */
    private var viewportSize: ViewportSize = DefaultViewportSize

    /**
     * Pixels per sp of the pane [viewportSize] was measured on, 1 until a pane reports. Pagination
     * geometry is keyed on the real pixel box (two displays of different densities can round to the
     * same sp box yet wrap lines differently), and this is what converts it back to the sp the
     * estimator computes in.
     */
    private var paneDensity: Float = 1f

    /** This reader's tagged logger, used for the diagnostic traces sprinkled through this class. */
    private val logger = co.touchlab.kermit.Logger.withTag("Reader")

    /**
     * The rendered text-layout measurement [updatePageBreaker] most recently accepted — what
     * pagination measures against — or null before any pane has reported a size, including while
     * [loadOpenState] has only adopted a stored layout's viewport into [viewportSize] and
     * [pageBreakerStyle] without a real [ReaderPageBreaker] instance to put here yet. Read
     * everywhere through [pageBreakerFor], never directly, because a breaker only describes the
     * pages of the [pageBreakerStyle] it was measured for.
     */
    private var pageBreaker: ReaderPageBreaker? = null

    /**
     * The style [pageBreaker] was measured for, or the style a stored layout was adopted for while
     * [pageBreaker] is still null. Written together with [pageBreakerSize]/[viewportSize] by
     * [loadOpenState] and [updatePageBreaker] — never one without the others, since a mismatched
     * trio would let a previous document's or a previous style's answer silently pass as this one's.
     */
    private var pageBreakerStyle: ReaderStyle? = null

    /**
     * The pane's real measured pixel box behind [pageBreaker] — px, not sp — kept only to recognise
     * a report [updatePageBreaker] has already answered ([PaneReportOutcome.Ignore]); [viewportSize]
     * is the unit pagination and page-layout storage actually key on.
     */
    private var pageBreakerSize: ViewportSize? = null

    /**
     * The in-flight repagination [updatePageBreaker] started for its most recently accepted report;
     * cancelled and replaced, never awaited, so a newer report always wins over an older reload
     * still running.
     */
    private var viewportReloadJob: Job? = null

    /**
     * The in-flight [openDocument] coroutine for [currentDocumentId]; cancelled the moment a newer
     * [openDocument] call starts opening a different document.
     */
    private var openDocumentJob: Job? = null

    /**
     * Drives phase 2+ of a progressive EPUB import (see [continueImportIfIncomplete]) — a plain
     * [viewModelScope] job, not a new subsystem: it stops the moment the reader leaves this document,
     * and the next open picks the import back up from wherever the stored rows say it left off.
     */
    private var importContinuationJob: Job? = null

    /**
     * Drives the rest of a progressive pagination pass (see [continuePaginationIfIncomplete]) — same
     * shape as [importContinuationJob], just for measuring an unmeasured style instead of parsing an
     * unimported section.
     */
    private var paginationContinuationJob: Job? = null

    /** One bounded deferred "next" request kept while total pages are still growing. */
    private var pendingMoveNextStep: Int? = null

    /** The latest in-flight progress save; cancelled and replaced so old writes do not win late. */
    private var saveProgressJob: Job? = null

    /** The final character count once import completes; null while a text import is still incomplete. */
    private var finalCharacterCount: Long? = null

    /**
     * The current document's saved reading places, kept in sync with [bookmarkRepository] by
     * [observeSavedPlaces]; read by [toggleSavedPlace] and [isPageSaved].
     */
    private var savedPlaces: List<Bookmark> = emptyList()

    /**
     * The subscription [observeSavedPlaces] holds on [bookmarkRepository]; cancelled and restarted
     * every time [openDocument] opens a new document.
     */
    private var savedPlacesJob: Job? = null

    /**
     * The in-flight fetch [loadVisualPagesAround] started for the pages currently missing from
     * [visualPageCache]; cancelled and replaced by the next call.
     */
    private var visualPageLoadJob: Job? = null

    /**
     * Decoded page images for a CBZ document, keyed by page index; bounded by a 24 MiB budget while
     * [loadVisualPagesAround] keeps the current mount window protected from eviction.
     */
    private val visualPageCache = ByteArrayLruCache<Int>(VisualPageCacheBudgetBytes)

    /**
     * Page indexes [loadVisualPagesAround] has already tried and failed to decode, so it does not
     * keep re-requesting them every time the reader turns nearby.
     */
    private val failedVisualPages = linkedSetOf<Int>()

    /**
     * The in-flight fetch [loadEmbeddedImagesAround] started for the hrefs currently missing from
     * [embeddedImageCache]; cancelled and replaced by the next call.
     */
    private var embeddedImageLoadJob: Job? = null

    /** The in-flight fetch [loadAllEmbeddedFonts] started for the hrefs currently missing from state. */
    private var embeddedFontLoadJob: Job? = null

    /**
     * Decoded embedded images for an EPUB document, keyed by href; bounded by a 16 MiB budget while
     * [loadEmbeddedImagesAround] protects every href still needed by the current mount window.
     */
    private val embeddedImageCache = ByteArrayLruCache<String>(EmbeddedImageCacheBudgetBytes)

    /** Resolved embedded EPUB font files, keyed by href and kept as temp-file paths rather than bytes. */
    private var embeddedFontFiles: Map<String, String> = emptyMap()

    /**
     * Hrefs [loadEmbeddedImagesAround] has already tried and failed to decode, so it does not keep
     * re-requesting them every time the reader turns nearby.
     */
    private val failedEmbeddedImageHrefs = linkedSetOf<String>()

    /** Hrefs [loadAllEmbeddedFonts] has already tried and failed to resolve. */
    private val failedEmbeddedFontHrefs = linkedSetOf<String>()

    /**
     * Whether every embedded font this document references anywhere has been resolved (or has failed)
     * against the *fully imported* book. Once true, [loadAllEmbeddedFonts] is a no-op — the font set,
     * and with it the layout key, can never change again for this document, which is what keeps the
     * book from re-measuring itself mid-read. Deliberately false while a progressive import is still
     * running: a scan then sees only part of the book, and calling that partial answer final froze
     * books fontless.
     */
    private var allEmbeddedFontsResolved = false

    /**
     * Whether the fonts referenced by the sections known *so far* are loaded (or failed). This is what
     * the measurement gate rides during a progressive import — measuring the imported sections with the
     * fonts they reference is sound even before the whole book exists; the completion pass then
     * finalises the set and re-measures at most once if it actually grew.
     */
    private var embeddedFontsSettled = false

    /**
     * Opens [documentIdValue] as the document this reader shows, replacing whatever was open before.
     *
     * A no-op when [documentIdValue] already names [currentDocumentId] — re-opening the same
     * document would otherwise cancel and restart every job below for nothing. Otherwise, this
     * synchronously cancels every job this instance may be running for the previous document
     * ([openDocumentJob] and every background continuation/preload job it or its continuations
     * started), clears [paginated], [anchorOffset], and both image caches, and publishes a blank
     * loading [ReaderUiState] before anything asynchronous starts — so a caller that opens a second
     * document while the first is still loading never sees a stale frame from the one being left
     * (the property `openingAnotherDocumentImmediatelyClearsPreviousReaderContent` pins in the test
     * suite).
     *
     * The actual load then runs as the four stages [OpenState]'s own documentation describes:
     * [loadOpenState] gathers everything the open needs, [publishFirstFrame] publishes the first
     * frame the reader sees, [startContinuations] starts every background job the rest of the open
     * can need, and [publishRest] records the open and publishes what the first frame did not need.
     * Each of the first three can return early — a null [OpenState], or [publishFirstFrame]
     * answering false — the moment [currentDocumentId] no longer names [documentId], because a
     * newer [openDocument] call has already moved the reader on to a different book; when that
     * happens the remaining stages simply do not run for this call.
     *
     * A [CancellationException] escaping the stages is rethrown rather than turned into an error
     * state, so cancelling [openDocumentJob] behaves like cancelling any other coroutine. Any other
     * [Throwable] is turned into a [ReaderUiState] error — but only once [currentDocumentId] is
     * re-checked one last time, so a failure that arrives after the reader has already moved on to
     * another document does not overwrite that document's state with this one's error.
     *
     * @param documentIdValue the raw id of the document to open.
     */
    fun openDocument(documentIdValue: String) {
        val documentId = DocumentId(documentIdValue)
        if (currentDocumentId == documentId) return
        currentDocumentId = documentId
        openDocumentJob?.cancel()
        viewportReloadJob?.cancel()
        visualPageLoadJob?.cancel()
        embeddedImageLoadJob?.cancel()
        embeddedFontLoadJob?.cancel()
        importContinuationJob?.cancel()
        paginationContinuationJob?.cancel()
        paginated = PaginatedDocument()
        anchorOffset = null
        pageBreaker = null
        pageBreakerStyle = null
        pageBreakerSize = null
        pendingMoveNextStep = null
        saveProgressJob?.cancel()
        finalCharacterCount = null
        visualPageCache.clear()
        failedVisualPages.clear()
        embeddedImageCache.clear()
        failedEmbeddedImageHrefs.clear()
        embeddedFontFiles = emptyMap()
        failedEmbeddedFontHrefs.clear()
        allEmbeddedFontsResolved = false
        embeddedFontsSettled = false
        _uiState.value = ReaderUiState(documentTitle = documentId.value)
        observeSavedPlaces(documentId)

        openDocumentJob = viewModelScope.launch {
            try {
                val state = loadOpenState(documentId) ?: return@launch
                if (!publishFirstFrame(state)) return@launch
                startContinuations(state)
                publishRest(state)
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (throwable: Throwable) {
                if (currentDocumentId != documentId) return@launch
                _uiState.value = ReaderUiState(
                    documentTitle = documentId.value,
                    isLoading = false,
                    errorMessage = throwable.message ?: "Failed to open document.",
                )
            }
        }
    }

    /**
     * Everything one call to [openDocument] discovers about the document being opened, carried from
     * [loadOpenState] through [publishFirstFrame], [startContinuations], and [publishRest] — the same
     * four stages that used to be one 215-line function body running in a single coroutine. Splitting
     * that body into stages changes nothing about what the reader sees: this is a plain holder for the
     * local variables that single coroutine already carried across its own suspension points, with the
     * difference that which stage may read or write which piece of state is now a fact spelled out in
     * a signature instead of something only a full read of the old function body could tell you.
     *
     * Carries [paginated], the page-window list [loadOpenState] just measured, so it is deliberately
     * not a `data class` — indexing a restored page-window list can build and cache a page as a side
     * effect, which makes a generated `equals`/`hashCode`/`toString` both slow and impure (see
     * [PaginatedDocument]'s own doc for the same reasoning).
     *
     * @property documentId the document this open is for.
     * @property metadata the document's stored metadata row, or null if the repository has none.
     * @property readerDocument the document's parsed structure (sections, navigation), or null if the
     *   repository has none yet.
     * @property settings the reader settings in effect for this open.
     * @property documentFormat the document's format; [DocumentFormat.UNKNOWN] when [metadata] is null.
     * @property documentUri the document's own source URI, threaded through unchanged for the UI to
     *   resolve visual-page images against.
     * @property paginated the page windows [loadOpenState] measured, paired with [readerDocument]'s
     *   sections.
     * @property isImportComplete true once the progressive EPUB import has parsed every section; false
     *   means [startContinuations] must keep it going.
     * @property isPaginationMeasured true once a visual document, or a repository-confirmed complete
     *   layout for this style, backs [paginated] — a different fact from [isImportComplete], since a
     *   fully imported book can still have no stored layout for the current style.
     * @property totalPages the page count this open's first frame publishes.
     * @property currentPage the page the reader resumes onto, already clamped into
     *   `0 until totalPages`.
     */
    private class OpenState(
        val documentId: DocumentId,
        val metadata: DocumentMetadata?,
        val readerDocument: ReaderDocument?,
        val settings: ReaderSettings,
        val documentFormat: DocumentFormat,
        val documentUri: String?,
        val paginated: PaginatedDocument,
        val isImportComplete: Boolean,
        val isPaginationMeasured: Boolean,
        val totalPages: Int,
        val currentPage: Int,
    ) {
        /** Whether [documentFormat] is [DocumentFormat.PDF] — suppresses page text, as [openDocument] always did. */
        val isPdfMode: Boolean get() = documentFormat == DocumentFormat.PDF

        /** Whether [documentFormat] is any visual page format (see [isVisualPageFormat]). */
        val isVisualMode: Boolean get() = documentFormat.isVisualPageFormat()
    }

    /**
     * Loads everything one [openDocument] call needs before anything can be shown: the document's
     * stored metadata, its sections, the saved reading progress, the reader settings, whether the
     * progressive EPUB import has finished, and the page windows this style/viewport/breaker
     * combination measures to. Adopts a previously stored layout's viewport into
     * [viewportSize]/[pageBreakerStyle] — both fields together, never just one, because leaving either
     * behind lets a previous document's answer survive and makes the pane's first report for this
     * document match it and skip the reload the document actually needs — and writes the freshly
     * measured pair into [paginated] and the resumed offset into [anchorOffset]. Adopting the pair
     * also means the pane's first real report, almost always the same size since it is the same
     * physical screen, is recognised by [updatePageBreaker] as already answered
     * ([PaneReportOutcome.RecordOnly]) instead of launching a reload that would only repeat what
     * [DocumentRepository.getPageWindows] just cached this exact answer under.
     *
     * The local `isImportComplete` this reads is true for every document except one whose
     * progressive EPUB import has not finished yet (see `ReaderUiState.isPaginationComplete`); it is
     * read here, before the first publish, so the very first frame this open produces already tells
     * the truth about whether the page count it carries is final. `hasReportedPaneSize` is true
     * exactly when no pane belonging to this ViewModel instance has reported a size yet — [pageBreaker]
     * is only ever set by [updatePageBreaker] — and when it is false, this passes `viewportSize = null`
     * to [DocumentRepository.getPageWindows] rather than the guessed [DefaultViewportSize], so that
     * call resolves the newest layout ever stored for this exact style instead of paginating against
     * a guess that almost never matches a stored one; before this existed, that mismatch fell through
     * to a full estimate pass and published the wrong page count as the first frame, corrected only
     * once the pane measured for real. Calling [DocumentRepository.getPageWindows] unconditionally
     * here — rather than waiting for that real pane report first — is also what breaks a deadlock no
     * stored row can help with on its own: with no pages the pager mounts no slot, with no slot
     * nothing ever measures the pane, and the pane is the only thing that ever reports a size, which
     * is exactly the state a freshly imported book starts in. `isPaginationMeasured` further down only
     * reflects whether the current style/viewport already has a real stored measurement, a different
     * fact from `isImportComplete`: a fully imported book can still have no stored layout for this
     * exact style, in which case the [DocumentRepository.getPageWindows] call below only measured the
     * resumed section and `isPaginationMeasured` is false too. The debug log this function ends with
     * states `totalPages` as single pages, which is not what the reader's own counter shows on a wide
     * screen — that one counts two-page spreads (see `ReaderScreen`'s `readerSpreadPageIndex`) — so a
     * log reporting 8977 can sit under a bar reading 4489 with nothing actually wrong.
     *
     * Every suspending read here can outlive a cancelled [Job] (`Job.cancel()` cannot stop a database
     * read already in flight), so this re-checks [currentDocumentId] after the two reads that can race
     * a newer [openDocument] call — the metadata/section/progress/settings/import-completeness batch,
     * and the page-window measurement — and answers null, a predictable absence rather than an
     * exception, instead of building an [OpenState] for a document the reader has already left.
     *
     * @param documentId the document being opened.
     * @return the state the remaining three stages need, or null if [documentId] stopped being the
     *   current document while this was loading.
     */
    private suspend fun loadOpenState(documentId: DocumentId): OpenState? {
        val open = openReaderDocumentUseCase(
            documentId = documentId,
            hasReportedPaneSize = pageBreaker != null,
            viewportSize = viewportSize,
            viewportDensity = paneDensity,
            pageBreaker = pageBreaker,
            pageBreakerStyle = pageBreakerStyle,
        )
        if (currentDocumentId != documentId) return null
        if (!open.isVisualMode && pageBreaker == null) {
            viewportSize = open.rememberedViewportSize ?: DefaultViewportSize
            pageBreakerStyle = open.settings.style.takeIf { open.rememberedViewportSize != null }
        }
        paginated = open.paginated
        finalCharacterCount = open.metadata?.characterCount.takeIf { open.isImportComplete }
        logger.d {
            "opening total=${open.totalPages} single pages from windows=${open.pageWindows.size}, " +
                    "metadata=${open.metadata?.pageCount}, progress=${open.progress?.pageIndex?.total}, " +
                    "paginationMeasured=${open.isPaginationMeasured}"
        }
        anchorOffset = open.anchorOffset

        return OpenState(
            documentId = documentId,
            metadata = open.metadata,
            readerDocument = open.readerDocument,
            settings = open.settings,
            documentFormat = open.documentFormat,
            documentUri = open.documentUri,
            paginated = open.paginated,
            isImportComplete = open.isImportComplete,
            isPaginationMeasured = open.isPaginationMeasured,
            totalPages = open.totalPages,
            currentPage = open.currentPage,
        )
    }

    /**
     * Publishes the first frame the reader sees — style, total, current page, its text and blocks, and
     * the title — the only state [ReaderUiState] needs before it clears [ReaderUiState.isLoading].
     * `ReaderScreen`'s indicator is delayed on top of that flag, so this publish still only carries what
     * the landing page itself needs; everything else this open still has to do — the opened-at write,
     * the outline, the favourite/saved-place flags, the neighbour page slots — happens afterward, in
     * [startContinuations] and [publishRest], so none of it sits in front of the first frame for no
     * reason other than living in the same function.
     *
     * Warms the block styling for exactly the page window the pager is about to mount
     * ([warmMountWindow]/[pagerMountWindow]) before building that page's UI. Section 0's blocks are
     * already ready by the time this runs — [DocumentRepository.getPageWindows] warms it internally
     * for cover detection (see `DocumentRepositoryImpl.restorePageWindows`) — but the resumed page's
     * own section is not, and neither are its neighbours; warming exactly the window `pageSlots()`
     * mounts, before building any page UI from it, is what keeps the first frame from drawing a page
     * with its images or chapter-title formatting still missing. Reusing [pagerMountWindow] — the same
     * range `pageSlots()` uses — rather than a separately guessed radius is the point: whatever
     * `pageSlots()` will actually build, this has already warmed.
     *
     * Re-checks [currentDocumentId] once right after the block-warming suspension and again immediately
     * before the publish itself. The second check has no suspension between it and the first, so it is
     * redundant in practice today — it is kept anyway because dropping it would need a justification
     * this split does not have.
     *
     * @param state the state [loadOpenState] produced for this open.
     * @return true once the first frame has been published; false if [state]'s document stopped being
     *   the current document while this was warming.
     */
    private suspend fun publishFirstFrame(state: OpenState): Boolean {
        val pageWindows = state.paginated.pageWindows
        if (!state.isVisualMode && pageWindows.isNotEmpty()) {
            warmMountWindow(state.documentId, state.currentPage)
        }
        if (currentDocumentId != state.documentId) return false

        val pageIndex = PageIndex(current = state.currentPage, total = state.totalPages)
        val currentPageUi = currentReaderPageUi(
            pageUiContext(
                pageIndex = pageIndex,
                documentUri = state.documentUri,
                isPdfMode = state.isPdfMode,
                paginatedOverride = state.paginated,
            )
        )
        val documentTitle = state.readerDocument?.title
            ?: state.metadata?.location?.displayName
            ?: state.documentId.value

        if (currentDocumentId != state.documentId) return false
        _uiState.update { uiState ->
            uiState.copy(
                documentTitle = documentTitle,
                documentUri = state.documentUri,
                documentFormat = state.documentFormat,
                pageText = currentPageUi.text,
                pageIndex = pageIndex,
                readProgressPercent = readProgressPercentFor(
                    pageIndex = pageIndex,
                    isVisualMode = state.isVisualMode,
                    currentPercent = uiState.readProgressPercent,
                    paginatedDocument = state.paginated,
                ),
                currentPage = currentPageUi,
                style = styleWithPublisherFontKey(state.settings.style, state.documentFormat),
                publisherPageMargins = epubPageContainerMarginsEm(currentPageUi),
                areEmbeddedFontsResolved = state.documentFormat != DocumentFormat.EPUB || embeddedFontsSettled,
                pageTurnMode = state.settings.pageTurnMode,
                pageAnimation = state.settings.pageAnimation,
                autoScrollConfig = state.settings.autoScrollConfig.copy(enabled = false),
                isPdfMode = state.isPdfMode,
                isControlsVisible = true,
                isLoading = false,
                isPaginationComplete = state.isImportComplete && state.isPaginationMeasured,
            )
        }
        return true
    }

    /**
     * Starts every background continuation this open can need, now that the first frame is out: the
     * visual-page/embedded-image preload around [OpenState.currentPage] and the progressive
     * import-or-pagination continuation. None of this may publish anything [publishRest]'s second
     * publish has not already accounted for, and none of it may touch
     * `pageIndex`, `pageText`, or `currentPage`, which [publishFirstFrame] already announced.
     *
     * Declared non-suspend on purpose: the code it replaces — the block between the first and second
     * publish in the un-split [openDocument] — has no suspension point in it, and keeping this function
     * non-suspend makes the compiler enforce that fact. A future suspending call inserted here fails to
     * compile instead of silently reordering the two publishes.
     *
     * Starting [continuePaginationIfIncomplete] is only worth doing once a real breaker has measured
     * the first section — a null [pageBreakerFor] result means the pagination the first frame carried
     * was only an estimate, superseded within a frame or two by the real measurement
     * [updatePageBreaker] triggers on its own, which starts its own progressive pass.
     *
     * @param state the state [loadOpenState] produced for this open.
     */
    private fun startContinuations(state: OpenState) {
        if (state.documentFormat == DocumentFormat.CBZ) loadVisualPagesAround(state.currentPage)
        if (state.documentFormat == DocumentFormat.EPUB) loadEmbeddedImagesAround(state.currentPage)
        if (state.documentFormat == DocumentFormat.EPUB) loadAllEmbeddedFonts()
        if (!state.isImportComplete) {
            paginationContinuationJob?.cancel()
            continueImportIfIncomplete(state.documentId)
        } else if (!state.isPaginationMeasured && pageBreakerFor(state.settings.style) != null) {
            continuePaginationIfIncomplete(state.documentId, state.settings.style)
        }
    }

    /**
     * Records the open, then publishes everything the first frame did not need: the outline, the
     * favourite and saved-place flags, and the neighbour page slots (via [republishSurroundingPages]) —
     * filled in and re-announced the same way [refreshEpubPages] already does for embedded images. Must
     * not touch `pageIndex`, `pageText`, or `currentPage` — [publishFirstFrame] already announced those,
     * and re-touching them here would let this second, later publish silently move the reader.
     *
     * Re-checks [currentDocumentId] once, right after the [markDocumentOpened][DocumentRepository]
     * write — the only suspension in this stage — before touching anything else.
     *
     * @param state the state [loadOpenState] produced for this open.
     */
    private suspend fun publishRest(state: OpenState) {
        documentRepository.markDocumentOpened(
            documentId = state.documentId,
            openedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
        )
        if (currentDocumentId != state.documentId) return
        val outlineItems = readerOutlineItems(
            format = state.metadata?.format,
            readerDocument = state.readerDocument,
            totalPages = state.totalPages,
        )
        republishSurroundingPages()
        _uiState.update { uiState ->
            uiState.copy(
                outlineHeading = state.readerDocument?.navigation?.heading,
                outlineItems = outlineItems.toImmutableList(),
                isFavorite = state.metadata?.isBookmarked == true,
                isCurrentPageSaved = isPageSaved(uiState.pageIndex, uiState.isVisualMode),
            )
        }
    }

    /**
     * The rendered text layout that pagination must agree with, together with the style it was
     * measured for. Pagination waits for a breaker matching the current style, because repaginating
     * a new font size against the previous measurement is exactly what clips the last line.
     *
     * This is the only measurement trigger — the pane used to also report through a separate
     * viewport callback, and because that callback and this one both launched their own reload,
     * one resize produced two `getPageWindows` calls (`Job.cancel()` cannot stop a DB read already
     * in flight). The pane now reports its size once, twice over: [viewportSp] is the sp value
     * pagination and page-layout storage key on — the same unit PageLayoutEntity's
     * viewportWidthPx/viewportHeightPx columns actually hold despite their name — and becomes
     * [viewportSize] below; [measuredSizePx] is the real pixel box, kept only to recognise a report
     * the reader has already answered.
     */
    fun updatePageBreaker(
        style: ReaderStyle,
        viewportSp: ViewportSize,
        measuredSizePx: ViewportSize,
        breaker: ReaderPageBreaker,
        measuredWithFinalFonts: Boolean = true,
    ) {
        // A page break is a pure function of (text, style, font set, pane pixels). A breaker built
        // before the document's font set was final measures with fallback type, and a measurement
        // stored under the final key poisons every later open — restored as if valid, clipping every
        // page whose real type runs longer. Rejecting here, at the single point every measurement
        // passes, is what replaces trusting composition timing across the screen's recompositions.
        if (_uiState.value.documentFormat == DocumentFormat.EPUB &&
            (!measuredWithFinalFonts || !embeddedFontsSettled)
        ) {
            logger.d { "breaker report rejected: embedded fonts not settled yet" }
            return
        }
        // Pagination is keyed on the real pixel box, not the sp box: two panes of different densities
        // (the fold's inner and cover screens) can round to the same sp size yet wrap lines differently,
        // and reusing one's page breaks on the other clipped the other's last lines.
        val outcome = paneReportOutcome(
            reportedStyle = style,
            reportedSizePx = measuredSizePx,
            reportedViewportSp = measuredSizePx,
            currentBreakerStyle = pageBreakerStyle,
            currentBreakerSizePx = pageBreakerSize,
            hasBreaker = pageBreaker != null,
            currentViewportSp = viewportSize,
        )
        if (outcome == PaneReportOutcome.Ignore) {
            logger.d { "breaker report ignored, already measured for $measuredSizePx" }
            return
        }
        if (outcome == PaneReportOutcome.RecordOnly) {
            logger.d { "breaker report accepted without reload, viewport already answered by $viewportSp" }
            pageBreaker = breaker
            pageBreakerStyle = style
            pageBreakerSize = measuredSizePx
            return
        }
        logger.d { "breaker report accepted for $measuredSizePx, previously $pageBreakerSize" }
        pageBreaker = breaker
        pageBreakerStyle = style
        pageBreakerSize = measuredSizePx
        viewportSize = measuredSizePx
        paneDensity = (measuredSizePx.widthPx.toFloat() / viewportSp.widthPx.toFloat()).takeIf { it > 0f && it.isFinite() } ?: 1f
        viewportReloadJob?.cancel()
        viewportReloadJob = viewModelScope.launch {
            reloadPages(style = _uiState.value.style)
            currentDocumentId?.let { documentId ->
                refreshPaginationCompleteness(
                    documentId,
                    style,
                    isImportComplete = documentRepository.isImportComplete(documentId)
                )
            }
        }
    }

    /**
     * Phase 2+ of a progressive EPUB import: repeatedly asks the repository to parse and measure a
     * bounded batch more of the book, in spine order, until it reports done. Intermediate batches do
     * not re-run [reloadPages]; import keeps parsing and leaves pageIndex.total alone until completion.
     * Once the import does finish, one final [reloadPages] publishes the grown page list without ever
     * touching a page already published (see TextPageLayoutEngine/DocumentRepositoryImpl.importNextSections:
     * appending only ever extends the stored page starts).
     * Runs on this ViewModel's own scope, so leaving the reader simply stops it; the next open resumes
     * from whatever the stored rows say is done — no separate scope, no new subsystem.
     *
     * A batch that imported anything also invalidates the repository's whole decoded section-blocks
     * cache (see `DocumentRepositoryImpl.importNextSections`) — not just the newly-imported sections' —
     * so the page the reader is already looking at just as silently loses its blocks as a freshly
     * opened book that has none decoded yet. [reloadPages] itself warms the mount window it is about to
     * publish from (see its own doc), so any publish it makes here already carries the page's
     * own styling instead of a second, corrective publish arriving a beat later. That same [reloadPages]
     * call also only re-measures the section the reader resumed into (see
     * [DocumentRepository.getPageWindows]'s own fallback), because this batch's cache invalidation
     * above wiped whatever pagination session was mid-walk, which is why pagination is not continued
     * again until import completion. This split keeps import and pagination from overlapping while still
     * letting one bounded pending-next request surface a newly imported neighbour as soon as it exists.
     *
     * Import finishing does not by itself mean pagination has: the book may still have no stored layout
     * for this style, in which case [DocumentRepository.getPageWindows] measured only the resumed
     * section and there is more for [refreshPaginationCompleteness] to continue.
     */
    private fun continueImportIfIncomplete(documentId: DocumentId) {
        importContinuationJob?.cancel()
        paginationContinuationJob?.cancel()
        importContinuationJob = viewModelScope.launch {
            while (currentDocumentId == documentId) {
                val style = _uiState.value.style
                val progress = documentRepository.importNextSections(
                    documentId = documentId,
                    count = ImportBatchSectionCount,
                    style = style,
                    viewportSize = viewportSize,
                    viewportDensity = paneDensity,
                    pageBreaker = pageBreakerFor(style),
                )
                if (currentDocumentId != documentId) return@launch
                if (progress.isComplete) {
                    finalCharacterCount = documentRepository.getDocument(documentId)?.characterCount
                    if (currentDocumentId != documentId) return@launch
                    // The whole book exists now, so the font set can finally be called final — a scan
                    // that ran mid-import saw only part of the book and left the flag down on purpose.
                    loadAllEmbeddedFonts()
                    reloadPages(style)
                    refreshPaginationCompleteness(documentId, style, isImportComplete = true)
                    return@launch
                }
            }
        }
    }

    /**
     * The rest of a progressive pagination pass [DocumentRepository.getPageWindows] started but could
     * not finish in one call — see that function's anchorOffset doc. Mirrors
     * [continueImportIfIncomplete]: repeatedly asks the repository to measure another bounded batch,
     * only re-running [reloadPages] when a pending next-page request may now be satisfiable or when
     * the pass reports completion. Stops on its own the moment [style] is no longer current, so a
     * font change started mid-measurement lets its own fresh pass own the ui's
     * isPaginationComplete flag instead of racing this one for it.
     *
     * Pins the fix labelled F3 in this project's bug history: a batch that is still growing the
     * document can invalidate the very pagination session this continuation is walking (see
     * `DocumentRepositoryImpl.invalidateDocumentCache`), and when it does,
     * [DocumentRepository.continuePagination] answers `isComplete = true` with `sectionsMeasured = 0`
     * — a "this walk has nothing left to say" signal, not "the book is done." Publishing
     * `isPaginationComplete` on that alone would tell the reader pagination is finished while the
     * import is still adding sections underneath it. Import and pagination continuations are now
     * mutually exclusive, and trusting "complete" only once [DocumentRepository.isImportComplete]
     * agrees prevents an obsolete session from publishing a false terminal state.
     */
    private fun continuePaginationIfIncomplete(documentId: DocumentId, style: ReaderStyle) {
        paginationContinuationJob?.cancel()
        paginationContinuationJob = viewModelScope.launch {
            while (currentDocumentId == documentId && _uiState.value.style.layoutKey() == style.layoutKey()) {
                val breaker = pageBreakerFor(style) ?: return@launch
                val progress = documentRepository.continuePagination(
                    documentId = documentId,
                    style = style,
                    viewportSize = viewportSize,
                    viewportDensity = paneDensity,
                    pageBreaker = breaker,
                )
                if (currentDocumentId != documentId) return@launch
                if (pendingMoveNextStep != null || progress.isComplete) reloadPages(style)
                if (progress.isComplete) {
                    if (currentDocumentId == documentId && documentRepository.isImportComplete(
                            documentId
                        )
                    ) {
                        _uiState.update { state -> state.copy(isPaginationComplete = true) }
                    }
                    return@launch
                }
            }
        }
    }

    /**
     * Whether the pagination [reloadPages] most recently measured for [style] is actually done and, if
     * not, continues it in the background (see [continuePaginationIfIncomplete]). Called after every
     * event that can start a genuinely new measurement pass on an already-imported document: the
     * pane's first real report for a style, and a font/line-height/typeface change.
     *
     * [isImportComplete] is a caller-supplied fact rather than something this asks the repository for
     * itself, because [continueImportIfIncomplete]'s own completion branch already has the freshest
     * possible answer in [ImportProgress.isComplete] the moment it calls this — asking again could only
     * repeat that same answer in production, and a test double that models isImportComplete()
     * separately from importNextSections()'s return value has no reason to promise they agree.
     */
    private suspend fun refreshPaginationCompleteness(
        documentId: DocumentId,
        style: ReaderStyle,
        isImportComplete: Boolean
    ) {
        if (currentDocumentId != documentId) return
        if (!isImportComplete) {
            _uiState.update { state -> state.copy(isPaginationComplete = false) }
            return
        }
        val isPaginationMeasured = documentRepository.isPaginationComplete(documentId)
        if (needsPaginationContinuation(
                isPaginationMeasured,
                hasMeasurementForStyle = pageBreakerFor(style) != null
            )
        ) {
            continuePaginationIfIncomplete(documentId, style)
            return
        }
        if (canReportPaginationComplete(isImportComplete, isPaginationMeasured)) {
            _uiState.update { state -> state.copy(isPaginationComplete = true) }
        }
    }

    /** Only a measurement made for [style] describes the pages that [style] will actually render. */
    private fun pageBreakerFor(style: ReaderStyle): ReaderPageBreaker? =
        pageBreaker.takeIf { pageBreakerStyle?.layoutKey() == style.layoutKey() }

    /**
     * Toggles whether the reader's chrome (top/bottom bars) is visible, e.g. on a tap in the middle
     * zone.
     */
    fun toggleControls() {
        _uiState.update { state -> state.copy(isControlsVisible = !state.isControlsVisible) }
    }

    /** Opens [sheet] as the active reader option sheet, replacing whichever one was showing. */
    fun showSheet(sheet: ReaderOptionSheet) {
        _uiState.update { state -> state.copy(activeSheet = sheet) }
    }

    /** Closes whichever reader option sheet is currently showing. */
    fun dismissSheet() {
        _uiState.update { state -> state.copy(activeSheet = null) }
    }

    /**
     * Flips the current document's favourite flag, publishing the new state immediately and
     * persisting it in the background.
     *
     * The publish is optimistic: the star flips before [documentRepository] confirms anything,
     * because there is nothing to wait for on the common path. If the write fails — most notably
     * because the document's own row is gone (see `togglingFavoriteRollsBackWhenTheDocumentRowIsGone`
     * in the test suite) — the flag is rolled back to what it was before the tap, and only if
     * [currentDocumentId] still names this document, so a failure that arrives after the reader has
     * moved on to another book does not flip that book's flag instead.
     */
    fun toggleFavorite() {
        val documentId = currentDocumentId ?: return
        val wasFavorite = _uiState.value.isFavorite
        _uiState.update { it.copy(isFavorite = !wasFavorite) }
        viewModelScope.launch {
            runCatching {
                val document = requireNotNull(documentRepository.getDocument(documentId))
                documentRepository.upsertDocument(document.copy(isBookmarked = !wasFavorite))
            }.onFailure {
                if (currentDocumentId == documentId) {
                    _uiState.update { it.copy(isFavorite = wasFavorite) }
                }
            }
        }
    }

    /**
     * Saves the current page as a place, or removes it if it was already saved — publishing the new
     * state immediately, the same optimistic-then-persist shape [toggleFavorite] uses, rolled back on
     * a failed write.
     *
     * A saved place's id is always `"${documentId.value}:${location.asStorageString()}"` — the
     * document id and the position's storage string joined by a colon. That format is what makes
     * saving the same page twice replace one row instead of adding another, and it is produced only
     * here; a future change that switched to a generated id would silently turn this toggle into an
     * append, which is what `savedPlaceIdIsDocumentIdAndLocationStorageString` in the test suite pins
     * against.
     */
    fun toggleSavedPlace() {
        val documentId = currentDocumentId ?: return
        val pageIndex = _uiState.value.pageIndex
        val location = currentLocation(pageIndex)
        val existing = savedPlaces.firstOrNull { it.location == location }
        val savedPlace = Bookmark(
            id = "${documentId.value}:${location.asStorageString()}",
            documentId = documentId,
            location = location,
            label = null,
            createdAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
        )

        savedPlaces = if (existing == null) savedPlaces + savedPlace else savedPlaces - existing
        _uiState.update { it.copy(isCurrentPageSaved = existing == null) }
        viewModelScope.launch {
            runCatching {
                if (existing == null) {
                    bookmarkRepository.saveBookmark(savedPlace)
                } else {
                    bookmarkRepository.deleteBookmark(existing.id)
                }
            }.onFailure {
                savedPlaces =
                    if (existing == null) savedPlaces - savedPlace else savedPlaces + existing
                updateSavedPlaceState()
            }
        }
    }

    /** Publishes whether the device's screen should be kept awake while this reader is on screen. */
    fun updateKeepScreenOn(enabled: Boolean) {
        _uiState.update { state -> state.copy(keepScreenOn = enabled) }
    }

    /** Publishes whether the reader should draw edge-to-edge, hiding the system bars. */
    fun updateFullscreen(enabled: Boolean) {
        _uiState.update { state -> state.copy(fullscreen = enabled) }
    }

    /** Publishes whether the reading-progress indicator should be shown. */
    fun updateShowProgress(enabled: Boolean) {
        _uiState.update { state -> state.copy(showProgress = enabled) }
    }

    /**
     * Applies a new font size through [updateStyle], which decides whether that actually reflows
     * the book.
     */
    fun updateFontSize(fontSizeSp: Float) {
        updateStyle(_uiState.value.style.copy(fontSizeSp = fontSizeSp))
    }

    /**
     * Applies a new line-height multiplier through [updateStyle], which decides whether that
     * actually reflows the book.
     */
    fun updateLineHeight(lineHeightMultiplier: Float) {
        updateStyle(_uiState.value.style.copy(lineHeightMultiplier = lineHeightMultiplier))
    }

    /**
     * Applies a new font family through [updateStyle], which decides whether that actually reflows
     * the book.
     */
    fun updateFontFamily(fontFamilyName: String?) {
        updateStyle(_uiState.value.style.copy(fontFamilyName = fontFamilyName))
    }

    /**
     * Applies a new theme mode through [updateStyle], which decides whether that actually reflows
     * the book.
     */
    fun updateThemeMode(mode: ReaderThemeMode) {
        updateStyle(_uiState.value.style.withThemeMode(mode))
    }

    /**
     * Publishes and persists a new page-turn mode. Not part of [ReaderStyle.layoutKey] — the text
     * breaks in the same places no matter how pages turn — so this never triggers a repagination,
     * unlike [updateStyle].
     */
    fun updatePageTurnMode(mode: PageTurnMode) {
        _uiState.update { state -> state.copy(pageTurnMode = mode) }
        saveReaderSettings { readerSettingsRepository.updatePageTurnMode(mode) }
    }

    /**
     * Publishes and persists a new page-turn animation. Not part of [ReaderStyle.layoutKey] — the
     * text breaks in the same places no matter how pages animate — so this never triggers a
     * repagination, unlike [updateStyle].
     */
    fun updatePageAnimation(animation: PageAnimation) {
        _uiState.update { state -> state.copy(pageAnimation = animation) }
        saveReaderSettings { readerSettingsRepository.updatePageAnimation(animation) }
    }

    /**
     * Enables or disables auto-scroll through [updateAutoScroll], which also hides the reader
     * chrome while it runs.
     */
    fun updateAutoScrollEnabled(enabled: Boolean) {
        updateAutoScroll(_uiState.value.autoScrollConfig.copy(enabled = enabled))
    }

    /** Changes the auto-scroll mode through [updateAutoScroll]. */
    fun updateAutoScrollMode(mode: AutoScrollMode) {
        updateAutoScroll(_uiState.value.autoScrollConfig.copy(mode = mode))
    }

    /**
     * Changes the auto-scroll speed through [updateAutoScroll], clamped by
     * [AutoScrollConfig.clampSpeed].
     */
    fun updateAutoScrollSpeed(speed: Float) {
        updateAutoScroll(
            _uiState.value.autoScrollConfig.copy(
                speed = AutoScrollConfig.clampSpeed(
                    speed
                )
            )
        )
    }

    /**
     * Disables auto-scroll if it is currently running; a no-op otherwise, so a caller need not
     * check first.
     */
    fun stopAutoScroll() {
        if (!_uiState.value.autoScrollConfig.enabled) return
        updateAutoScrollEnabled(false)
    }

    /**
     * Publishes a new brightness-overlay opacity, coerced into `0f..0.8f` — the overlay never goes
     * fully opaque.
     */
    fun updateBrightnessOverlayAlpha(alpha: Float) {
        _uiState.update { state -> state.copy(brightnessOverlayAlpha = alpha.coerceIn(0f, 0.8f)) }
    }

    /**
     * Moves [step] pages back from wherever the reader is right now, clamped to the first page.
     *
     * Takes only the step, never a resolved target index, because a relative move must resolve
     * against the pagination that is current at the moment it actually runs, not the pagination
     * that was current when the caller decided to move. If a caller instead computed a page index up
     * front and handed that to [moveToPage], a font or line-height change repaginating the document
     * in between would leave that index stale, and [moveToPage] would clamp it into the new, shorter
     * document — landing on the last page instead of the intended next one. Resolving the step here,
     * against `_uiState.value.pageIndex` read at call time, is what a repagination in between cannot
     * misplace.
     *
     * @param step how many pages to move back; coerced to at least 1.
     */
    fun movePrevious(step: Int = 1) {
        val pageIndex = _uiState.value.pageIndex
        if (pageIndex.total <= 0) return
        val target = (pageIndex.current - step.coerceAtLeast(1)).coerceAtLeast(0)
        if (target != pageIndex.current) moveToPage(target)
    }

    /**
     * Moves [step] pages forward from wherever the reader is right now. If the target page is already
     * known, it moves there immediately; if the reader is sitting at the known end of a still-growing
     * document, one bounded pending request keeps just the step and retries after the next reload.
     * Otherwise the request is dropped rather than clamped onto the last page — see [movePrevious]'s
     * own doc for why only the step, never a resolved target index, is what this resolves against.
     *
     * @param step how many pages to move forward; coerced to at least 1.
     */
    fun moveNext(step: Int = 1) {
        val pageIndex = _uiState.value.pageIndex
        val normalizedStep = step.coerceAtLeast(1)
        val target = pageIndex.current + normalizedStep
        if (target in 0 until pageIndex.total) {
            pendingMoveNextStep = null
            moveToPage(target)
            return
        }
        if (!_uiState.value.isPaginationComplete) {
            pendingMoveNextStep = maxOf(pendingMoveNextStep ?: 0, normalizedStep)
        }
    }

    /**
     * Moves to the page showing [location], as chosen from the outline, search, or a bookmark.
     *
     * [readerOutlineItems]'s visual-format branch only ever builds outline entries as
     * [ReaderLocation.PdfPage], and [PaginatedDocument.pageOf] resolves that variant to null — it has
     * no absolute offset to give it, since [absoluteOffsetOf] itself answers null for a page number
     * rather than a character offset. The [ReaderLocation.PdfPage] branch below is therefore the only
     * path that ever moves a PDF/CBZ outline tap anywhere; collapsing it into the
     * `paginated.pageOf(location)` call on the line beneath would make every visual-document outline
     * tap a permanent no-op, which is exactly what AGENTS.md's reader invariant — a page turn the UI
     * offers must always advance — forbids.
     *
     * @param location the reading position to move to.
     */
    fun moveToLocation(location: ReaderLocation) {
        val page = when (location) {
            is ReaderLocation.PdfPage -> location.pageIndex
            else -> paginated.pageOf(location) ?: _uiState.value.pageIndex.current
        }
        moveToPage(page)
    }

    /**
     * Publishes [style] immediately and persists it, laying the book out again only when the change
     * actually moves the page breaks.
     *
     * Only a change to [ReaderStyle.layoutKey] — the type-affecting fields — can move where pages
     * break; a colour or background change is still saved and still redraws the current page, but
     * laying the whole book out again for it would cost work the reader would never see reflected in
     * a single visible pixel. When the layout key does change, [reloadPages] itself only measures
     * the section the reader is currently on, the same way a fresh [openDocument] does, because a
     * type nobody has read this book at before has no stored layout to fall back on; finishing the
     * rest of the measurement is handed to [refreshPaginationCompleteness] in the background.
     *
     * @param style the style to publish and persist.
     */
    private fun updateStyle(style: ReaderStyle) {
        val previousStyle = _uiState.value.style
        _uiState.update { state ->
            state.copy(style = styleWithPublisherFontKey(style, state.documentFormat))
        }
        saveReaderSettings {
            readerSettingsRepository.updateStyle(style)
            if (previousStyle.layoutKey() != style.layoutKey()) {
                pendingMoveNextStep = null
                reloadPages(style)
                currentDocumentId?.let { documentId ->
                    refreshPaginationCompleteness(
                        documentId,
                        style,
                        isImportComplete = documentRepository.isImportComplete(documentId)
                    )
                }
            }
        }
    }

    /**
     * Normalizes and publishes [config], persisting it, and hides the reader chrome the moment
     * auto-scroll turns on — the chrome would otherwise sit on screen fighting for attention with a
     * page that is now moving on its own.
     *
     * @param config the auto-scroll configuration to publish; its speed is re-clamped through
     *   [AutoScrollConfig.clampSpeed] regardless of what the caller already applied, so this is safe
     *   to call with a value assembled from an unclamped user input.
     */
    private fun updateAutoScroll(config: AutoScrollConfig) {
        val normalizedConfig = config.copy(speed = AutoScrollConfig.clampSpeed(config.speed))
        _uiState.update { state ->
            state.copy(
                autoScrollConfig = normalizedConfig,
                isControlsVisible = state.isControlsVisible && !normalizedConfig.enabled,
            )
        }
        saveReaderSettings { readerSettingsRepository.updateAutoScrollConfig(normalizedConfig) }
    }

    /**
     * Runs [block] as a persistence write, publishing [ReaderUiState.isSavingSettings] around it and
     * an error message if it throws — the shared shape every settings setter in this class persists
     * through, so each of them does not repeat its own loading/error bookkeeping.
     *
     * @param block the suspending write to perform; any exception it throws is caught and turned
     *   into [ReaderUiState.errorMessage] rather than propagating.
     */
    private fun saveReaderSettings(block: suspend () -> Unit) {
        _uiState.update { state -> state.copy(isSavingSettings = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            errorMessage = throwable.message ?: "Failed to save reader settings."
                        )
                    }
                }
            _uiState.update { state -> state.copy(isSavingSettings = false) }
        }
    }

    /**
     * The UI-ready view of the current page named by [pageIndex], falling back to an empty
     * [ReaderPageUi] when [pageUi] cannot build a real one (an out-of-bounds index, or no window yet).
     *
     * @param pageIndex the pagination to read the current page from.
     * @param documentUri the document's own URI, threaded through for a page that renders an image.
     * @param isPdfMode whether the document is a visual page format, which suppresses page text.
     * @return the current page's UI view, real or empty.
     */
    private fun pageUiContext(
        pageIndex: PageIndex,
        documentUri: String?,
        isPdfMode: Boolean,
        paginatedOverride: PaginatedDocument = paginated,
    ): ReaderPageUiContext = ReaderPageUiContext(
        pageIndex = pageIndex,
        documentUri = documentUri,
        isPdfMode = isPdfMode,
        paginated = paginatedOverride,
        embeddedImages = embeddedImageCache.snapshot(),
        embeddedFontFiles = embeddedFontFiles,
        failedEmbeddedImageHrefs = failedEmbeddedImageHrefs,
        failedEmbeddedFontHrefs = failedEmbeddedFontHrefs,
    )

    /**
     * Warms the blocks [pagerMountWindow] touches around [currentPage] — the one place this logic
     * lives, so openDocument's first publish, [moveToPage] and [reloadPages] all warm exactly what
     * they are about to build from instead of each guessing their own radius. [paginated] defaults to
     * this ViewModel's own field of the same name, but [reloadPages] passes its own local pair
     * explicitly: two reloads can run concurrently (an import batch, a viewport reload, a style
     * change), each about to publish from its own page/section pair, and the warm has to touch the
     * same pair its own publish will read from rather than whatever the field happens to hold when the
     * warm actually runs. Reading a restored page list here is safe even though it builds, but does
     * not cache, a page whose section is not ready yet (DocumentRepositoryImpl's RestoredPageWindows)
     * — an unready page is rebuilt on its very next read, which is exactly what happens once this warm
     * completes.
     *
     * @param documentId the document to warm sections for.
     * @param currentPage the page [pagerMountWindow] centers its warming range on.
     * @param paginated the page/section pair to derive touched sections from; defaults to this
     *   ViewModel's own field but accepts a caller's local pair for the concurrency reason above.
     */
    private suspend fun warmMountWindow(
        documentId: DocumentId,
        currentPage: Int,
        paginated: PaginatedDocument = this.paginated,
    ) {
        val touchedSections = paginated.sectionIndexesFor(pagerMountWindow(currentPage))
        if (touchedSections.isNotEmpty()) documentRepository.warmSectionBlocks(
            documentId,
            touchedSections
        )
    }

    /**
     * Moves the reader to [page], clamped into the current pagination's bounds, publishing it
     * immediately so the pager key changes before any suspend work starts.
     *
     * Updates [anchorOffset] to the target page's own start synchronously — before anything below
     * suspends — publishes the new facing pages right away, then warms the mounted window around the
     * target page in the background. Once that warm returns, a guarded re-publish refreshes the live
     * current page only if this document, location, and page are still current, so a stale coroutine
     * cannot move the reader backwards after a faster later navigation. Also saves the new reading
     * progress through [saveProgress] and starts preloading visual pages and embedded images around
     * the new position.
     *
     * @param page the target page index; coerced into `0..total-1`, or a no-op when the document has
     *   no pages yet.
     */
    fun moveToPage(page: Int) {
        pendingMoveNextStep = null
        moveToPageInternal(page)
    }

    private fun moveToPageInternal(page: Int) {
        val state = _uiState.value
        val total = state.pageIndex.total
        if (total <= 0) return
        val lastPage = (total - 1).coerceAtLeast(0)
        val nextPage = page.coerceIn(0, lastPage)
        anchorOffset = paginated.pageWindows.getOrNull(nextPage)?.textRange?.start
        val nextIndex = PageIndex(current = nextPage, total = total)
        _uiState.update {
            val facing = readerPageFacingUi(
                pageUiContext(
                    pageIndex = nextIndex,
                    documentUri = it.documentUri,
                    isPdfMode = it.isPdfMode,
                ),
            )
            it.copy(
                pageIndex = nextIndex,
                pageText = facing.current.text,
                style = styleWithPublisherFontKey(it.style, it.documentFormat),
                readProgressPercent = readProgressPercentFor(
                    pageIndex = nextIndex,
                    isVisualMode = it.isVisualMode,
                    currentPercent = it.readProgressPercent,
                ),
                previousPage = facing.previous,
                currentPage = facing.current,
                nextPage = facing.next,
                pageSlots = facing.slots,
                isCurrentPageSaved = isPageSaved(nextIndex, it.isVisualMode),
            )
        }
        val expectedLocation = currentLocation(nextIndex, state.isVisualMode)
        val documentId = currentDocumentId
        viewModelScope.launch {
            if (documentId != null && !state.isVisualMode) warmMountWindow(documentId, nextPage)
            if (currentDocumentId != documentId) return@launch
            val liveState = _uiState.value
            if (liveState.pageIndex.current != nextPage) return@launch
            if (currentLocation(
                    liveState.pageIndex,
                    liveState.isVisualMode
                ) != expectedLocation
            ) return@launch
            _uiState.update {
                val facing = readerPageFacingUi(
                    pageUiContext(
                        pageIndex = liveState.pageIndex,
                        documentUri = it.documentUri,
                        isPdfMode = it.isPdfMode,
                    ),
                )
                it.copy(
                    pageText = facing.current.text,
                    previousPage = facing.previous,
                    currentPage = facing.current,
                    nextPage = facing.next,
                    pageSlots = facing.slots,
                    isCurrentPageSaved = isPageSaved(liveState.pageIndex, it.isVisualMode),
                )
            }
            saveProgress(liveState.pageIndex)
            loadVisualPagesAround(liveState.pageIndex.current)
            loadEmbeddedImagesAround(liveState.pageIndex.current)
            loadAllEmbeddedFonts()
        }
    }

    /**
     * Re-announces previousPage, nextPage and every [pageSlots] neighbour from the live pageIndex —
     * without touching pageIndex, pageText or currentPage, which only ever change through a real
     * navigation ([moveToPage]) or the first publish that already announced them (see openDocument).
     * Read positionally off the live state, not a pageIndex captured earlier — updatePageBreaker's
     * reload runs on its own coroutine and can already have published a measured repagination by the
     * time this runs; writing an earlier local here would silently put a stale pagination back.
     */
    private fun republishSurroundingPages() {
        _uiState.update { state ->
            val livePageIndex = state.pageIndex
            val facing = readerPageFacingUi(
                pageUiContext(
                    pageIndex = livePageIndex,
                    documentUri = state.documentUri,
                    isPdfMode = state.isPdfMode,
                ),
            )
            state.copy(
                previousPage = facing.previous,
                nextPage = facing.next,
                pageSlots = facing.slots,
                style = styleWithPublisherFontKey(state.style, state.documentFormat),
            )
        }
    }

    /**
     * Re-measures [style]'s pages for the current document and re-publishes the page the reader is
     * on — called after a font/line-height/typeface change, the pane's first real report for a
     * style, or an import/pagination batch that grew the document further.
     *
     * The section list inside [paginated] is re-read from the repository on every call because a
     * progressive EPUB import appends sections in the background ([DocumentRepository.importNextSections])
     * and the completion reload still swaps in the final [ReaderDocument] snapshot — without this
     * re-read, every other
     * reader of [paginated]'s sections (the chapter-title lookup and [PaginatedDocument.isSectionTail]
     * flag inside [pageUi]) would silently keep working off whatever section list [openDocument] saw
     * at open time for the rest of the session. The
     * [DocumentRepository.getPageWindows] call above already reloaded this same document's
     * [ReaderDocument] when its cache was invalidated, so the read below answers from that same
     * in-memory copy rather than issuing a second one.
     *
     * [paginated] is written here in the same two steps, and the same order, the separate
     * `currentPageWindows`/`currentSections` field writes used to happen in: the fresh page list
     * first, the fresh section list second. That section read is itself the suspending call described
     * above, and every other reader of [paginated] — [moveToPage], [saveProgress], [currentLocation],
     * [loadEmbeddedImagesAround], and [pageUi]'s own default argument — has to see the fresh page list
     * the moment it exists rather than waiting for that read to finish. Collapsing the two writes into
     * one assignment would delay that visibility by exactly the length of the section read, which is
     * an observable behaviour change, not a simplification. [warmMountWindow] is then handed the
     * exact page/section pair this function is about to publish from, not [paginated] itself, because
     * a second reload (an import batch, a viewport reload, another style change) can already be
     * mid-flight against the same field.
     *
     * A page a restored page list builds whose section's blocks are not decoded yet renders as
     * unstyled text — an empty block list from `DocumentRepositoryImpl.SectionBlocksCache.blocksFor`.
     * Intermediate import batches now leave the active prefix cache alone, but the completion reload
     * can still be rebuilding from a fresh final snapshot whose later sections are not decoded yet, so
     * warming exactly the window `pageSlots()` is about to
     * mount, via [warmMountWindow], before the publish below, is what makes that publish carry the
     * page's own styling instead of correcting it a frame later.
     *
     * @param style the style to measure and lay pages out for.
     */
    private suspend fun reloadPages(style: ReaderStyle) {
        val documentId = currentDocumentId ?: return
        if (_uiState.value.isVisualMode) return

        if (pageBreaker != null && pageBreakerFor(style) == null) {
            logger.d { "reload skipped: measurement belongs to another type" }
            return
        }

        val pageWindows = documentRepository.getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
            viewportDensity = paneDensity,
            pageBreaker = pageBreakerFor(style),
            anchorOffset = anchorOffset,
        )
        if (pageWindows.isEmpty()) return
        if (currentDocumentId != documentId || _uiState.value.style.layoutKey() != style.layoutKey()) return

        val currentPage =
            anchorOffset?.let { offset -> PaginatedDocument(pageWindows).pageOf(offset) }
                ?: _uiState.value.pageIndex.current.coerceIn(0, pageWindows.lastIndex)
        paginated = paginated.withPages(pageWindows)
        val freshSections = documentRepository.getReaderDocument(documentId)?.sections
        if (currentDocumentId != documentId || _uiState.value.style.layoutKey() != style.layoutKey()) return
        if (freshSections != null) paginated = paginated.withSections(freshSections)
        val reloaded = PaginatedDocument(pageWindows, freshSections ?: paginated.sections)
        warmMountWindow(documentId, currentPage, reloaded)
        if (currentDocumentId != documentId || _uiState.value.style.layoutKey() != style.layoutKey()) return
        val pageIndex = PageIndex(current = currentPage, total = pageWindows.size)
        _uiState.update {
            val facing = readerPageFacingUi(
                pageUiContext(
                    pageIndex = pageIndex,
                    documentUri = it.documentUri,
                    isPdfMode = false,
                    paginatedOverride = reloaded,
                ),
            )
            it.copy(
                pageIndex = pageIndex,
                pageText = facing.current.text,
                style = styleWithPublisherFontKey(it.style, it.documentFormat),
                readProgressPercent = readProgressPercentFor(
                    pageIndex = pageIndex,
                    isVisualMode = false,
                    currentPercent = it.readProgressPercent,
                    paginatedDocument = reloaded,
                ),
                previousPage = facing.previous,
                currentPage = facing.current,
                nextPage = facing.next,
                pageSlots = facing.slots,
                isCurrentPageSaved = isPageSaved(pageIndex, false),
            )
        }
        loadEmbeddedImagesAround(currentPage)
        loadAllEmbeddedFonts()
        consumePendingMoveNextIfPossible()
    }

    private fun consumePendingMoveNextIfPossible() {
        val step = pendingMoveNextStep ?: return
        val pageIndex = _uiState.value.pageIndex
        val target = pageIndex.current + step
        if (target !in 0 until pageIndex.total) return
        pendingMoveNextStep = null
        moveToPageInternal(target)
    }

    /**
     * Persists [pageIndex] as the reading position to resume to, unless there is nothing real to
     * save yet.
     *
     * Refuses to save when a text document has no pagination yet (`paginated.pageWindows` empty and
     * not a visual document): a text document with no pagination cannot say where the reader
     * actually is, and saving anyway would take [currentLocation]'s fallback — a page number dressed
     * up as a character offset — and write it over the place the reader really left off, sending them
     * back to the first page of the book on the next open. Whatever progress row is already stored,
     * from before this pagination-less state, is a better answer than that fallback, so this simply
     * leaves it alone.
     *
     * @param pageIndex the page to save as the current reading position.
     */
    private fun saveProgress(pageIndex: PageIndex) {
        val documentId = currentDocumentId ?: return
        if (!_uiState.value.isVisualMode && paginated.pageWindows.isEmpty()) return
        saveProgressJob?.cancel()
        saveProgressJob = viewModelScope.launch {
            readerRepository.saveProgress(
                ReadingProgress(
                    documentId = documentId,
                    location = currentLocation(pageIndex),
                    pageIndex = pageIndex,
                    updatedAtEpochMillis = 0L,
                ),
            )
        }
    }

    private fun readProgressPercentFor(
        pageIndex: PageIndex,
        isVisualMode: Boolean,
        currentPercent: Int,
        paginatedDocument: PaginatedDocument = paginated,
    ): Int =
        if (isVisualMode) {
            readerVisualReadProgressPercent(pageIndex)
        } else {
            readerReadProgressPercent(
                location = anchorOffset?.let(ReaderLocation::TextOffset)
                    ?: paginatedDocument.locationAt(pageIndex.current),
                characterCount = finalCharacterCount,
                currentPercent = currentPercent,
            )
        }

    /**
     * Subscribes [savedPlaces] to [bookmarkRepository]'s live bookmark list for [documentId],
     * restarting the subscription (and clearing [savedPlaces] in the meantime) every time
     * [openDocument] opens a document. Called once per open, from [openDocument] itself.
     *
     * @param documentId the document whose saved places should be observed.
     */
    private fun observeSavedPlaces(documentId: DocumentId) {
        savedPlacesJob?.cancel()
        savedPlaces = emptyList()
        savedPlacesJob = viewModelScope.launch {
            bookmarkRepository.observeBookmarks(documentId).collect { bookmarks ->
                savedPlaces = bookmarks
                updateSavedPlaceState()
            }
        }
    }

    /** Re-derives [ReaderUiState.isCurrentPageSaved] for whatever page is live in [_uiState] right now. */
    private fun updateSavedPlaceState() {
        _uiState.update { state ->
            state.copy(isCurrentPageSaved = isPageSaved(state.pageIndex, state.isVisualMode))
        }
    }

    /**
     * Whether [pageIndex] already has a saved place, resolved through [currentLocation] so a saved
     * text offset and a saved page both compare against the right notion of "this page's location."
     *
     * @param pageIndex the page to check.
     * @param isVisualMode whether the document is a visual page format, passed through to
     *   [currentLocation].
     * @return true when [savedPlaces] already contains this page's location.
     */
    private fun isPageSaved(pageIndex: PageIndex, isVisualMode: Boolean): Boolean =
        savedPlaces.any { it.location == currentLocation(pageIndex, isVisualMode) }

    /**
     * The reading position [pageIndex] currently shows, used to save progress and to check whether
     * that page has a saved place.
     *
     * The pagination half of this resolves through [PaginatedDocument.locationAt], which answers from
     * the page's own window and is null for a page [paginated] has no window for yet; the fallback —
     * a page number dressed up as a [ReaderLocation.TextOffset] — stays here rather than moving onto
     * [PaginatedDocument], because it is a UI-level placeholder for "no real location known yet", not
     * a pagination fact the domain type should be answering.
     *
     * @param pageIndex the page to resolve a location for.
     * @param isVisualMode whether the document is a visual page format, in which case the location is
     *   always [ReaderLocation.PdfPage] rather than a text offset.
     */
    private fun currentLocation(
        pageIndex: PageIndex,
        isVisualMode: Boolean = _uiState.value.isVisualMode,
    ): ReaderLocation {
        if (isVisualMode) {
            return ReaderLocation.PdfPage(pageIndex.current)
        }

        return paginated.locationAt(pageIndex.current)
            ?: ReaderLocation.TextOffset(pageIndex.current.toLong())
    }

    /**
     * Fetches the CBZ page images [pagerMountWindow] needs around [centerPage] that are not already
     * in [visualPageCache] or [failedVisualPages], then publishes the merged cache. A no-op for any
     * document that is not [DocumentFormat.CBZ], or before any page count is known.
     *
     * On success, [visualPageCache] keeps every page in the requested mount window protected while
     * trimming older bytes back under its 24 MiB budget; on failure, the missing pages are recorded
     * in [failedVisualPages] so they are not endlessly re-requested.
     *
     * @param centerPage the page [pagerMountWindow] centers the fetch window on.
     */
    private fun loadVisualPagesAround(centerPage: Int) {
        val documentId = currentDocumentId ?: return
        val state = _uiState.value
        if (state.documentFormat != DocumentFormat.CBZ || state.pageIndex.total <= 0) return
        val requestedPages = pagerMountWindow(centerPage)
            .filterTo(linkedSetOf()) { it in 0 until state.pageIndex.total }
        val cachedPages = visualPageCache.snapshot()
        val missingPages = requestedPages - cachedPages.keys - failedVisualPages
        if (missingPages.isEmpty()) {
            _uiState.update {
                it.copy(
                    visualPageImages = cachedPages.filterKeys(requestedPages::contains)
                        .toImmutableMap(),
                    failedVisualPages = failedVisualPages.toImmutableSet(),
                )
            }
            return
        }

        visualPageLoadJob?.cancel()
        visualPageLoadJob = viewModelScope.launch {
            try {
                val loadedPages = documentRepository.getVisualPageImages(documentId, missingPages)
                if (currentDocumentId != documentId) return@launch
                loadedPages.forEach { (page, bytes) ->
                    visualPageCache.put(page, bytes, protectedKeys = requestedPages)
                }
                failedVisualPages += missingPages - loadedPages.keys
                val visualSnapshot = visualPageCache.snapshot()
                _uiState.update {
                    it.copy(
                        visualPageImages = visualSnapshot.filterKeys(requestedPages::contains)
                            .toImmutableMap(),
                        failedVisualPages = failedVisualPages.toImmutableSet(),
                    )
                }
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (_: Throwable) {
                if (currentDocumentId == documentId) {
                    failedVisualPages += missingPages
                    val visualSnapshot = visualPageCache.snapshot()
                    _uiState.update {
                        it.copy(
                            visualPageImages = visualSnapshot.filterKeys(requestedPages::contains)
                                .toImmutableMap(),
                            failedVisualPages = failedVisualPages.toImmutableSet(),
                        )
                    }
                }
            }
        }
    }

    /**
     * Fetches the EPUB embedded images [pagerMountWindow] needs around [centerPage] that are not
     * already in [embeddedImageCache] or [failedEmbeddedImageHrefs], then republishes the pages that
     * needed them via [refreshEpubPages]. A no-op for any document that is not [DocumentFormat.EPUB],
     * or before any page count is known — though [refreshEpubPages] still runs when nothing is
     * missing, so a call that finds every href already cached still re-announces the pages.
     *
     * The window this reads hrefs from matches the window `pageSlots()` actually mounts (see
     * [pagerMountWindow]), so every slot the reader can swipe to preview already has its images
     * requested instead of only the immediate neighbour. On success, [embeddedImageCache] keeps
     * every href the current window still needs (the cover, for instance, if it is revisited later)
     * and evicts only the oldest of the rest once the cache is over its 16 MiB budget —
     * a plain insertion-order LRU used to evict a still-needed image (the cover was always the first
     * one loaded) purely because newer images had since been cached. Archive misses are recorded in
     * [failedEmbeddedImageHrefs] so they are not endlessly re-requested, but a transient fetch failure
     * leaves the hrefs retryable on the next preload.
     *
     * @param centerPage the page [pagerMountWindow] centers the fetch window on.
     */
    private fun loadEmbeddedImagesAround(centerPage: Int) {
        val documentId = currentDocumentId ?: return
        val state = _uiState.value
        if (state.documentFormat != DocumentFormat.EPUB || state.pageIndex.total <= 0) return
        val relevantHrefs = paginated.imageHrefsIn(pagerMountWindow(centerPage))
        val missingHrefs =
            relevantHrefs - embeddedImageCache.snapshot().keys - failedEmbeddedImageHrefs
        if (missingHrefs.isEmpty()) {
            refreshEpubPages()
            return
        }

        embeddedImageLoadJob?.cancel()
        embeddedImageLoadJob = viewModelScope.launch {
            try {
                val loadedImages = documentRepository.getEmbeddedImages(documentId, missingHrefs)
                if (currentDocumentId != documentId) return@launch
                loadedImages.forEach { (href, bytes) ->
                    embeddedImageCache.put(href, bytes, protectedKeys = relevantHrefs)
                }
                failedEmbeddedImageHrefs += missingHrefs - loadedImages.keys
                refreshEpubPages()
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (_: Throwable) {
                if (currentDocumentId == documentId) refreshEpubPages()
            }
        }
    }

    /**
     * Resolves every embedded font the whole document references, once, then republishes the EPUB page
     * state so typefaces and page breaking rebuild from the complete map.
     *
     * Whole-document on purpose: the font set feeds the layout key, and a window-by-window discovery
     * changed that key every time the reader reached a section naming a font it had not seen — each
     * change re-measured the entire book, which the reader experienced as pages flickering, restyling
     * and clipping while it settled. Resolving the full set once means the key changes at most once per
     * document, and [refreshEpubPages] is only worth calling when it actually did.
     */
    private fun loadAllEmbeddedFonts() {
        val documentId = currentDocumentId ?: return
        val state = _uiState.value
        if (state.documentFormat != DocumentFormat.EPUB || state.pageIndex.total <= 0) return
        if (allEmbeddedFontsResolved) return

        embeddedFontLoadJob?.cancel()
        embeddedFontLoadJob = viewModelScope.launch {
            var missingHrefs = emptySet<String>()
            try {
                // The font set can only be called final once the whole book is parsed: scanning during a
                // progressive (re)import sees only the sections stored so far — or none at all mid-repair
                // — and calling that empty answer "resolved" froze the book fontless forever.
                val isImportComplete = documentRepository.isImportComplete(documentId)
                val referencedHrefs = documentRepository.getReferencedEmbeddedFontHrefs(documentId)
                if (currentDocumentId != documentId) return@launch
                missingHrefs = referencedHrefs - embeddedFontFiles.keys - failedEmbeddedFontHrefs
                if (missingHrefs.isNotEmpty()) {
                    val loadedFonts = documentRepository.getEmbeddedFontFiles(documentId, missingHrefs)
                    if (currentDocumentId != documentId) return@launch
                    embeddedFontFiles = embeddedFontFiles + loadedFonts
                    failedEmbeddedFontHrefs += missingHrefs - loadedFonts.keys
                }
                allEmbeddedFontsResolved = isImportComplete
                embeddedFontsSettled = true
                // Republished even when the font key did not change (a book with no embedded fonts at
                // all): the measurement gate waits on areEmbeddedFontsResolved, which only travels with
                // a publish.
                refreshEpubPages()
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (_: Throwable) {
                if (currentDocumentId == documentId) {
                    // Marked failed rather than left pending: a pending referenced font blocks measured
                    // pagination forever (see canMeasureEpubPage), which is worse than falling back to
                    // the reader's own font for this open. The resolved flag stays down so the next
                    // trigger re-runs the (now cheap) scan and can still conclude properly.
                    failedEmbeddedFontHrefs += missingHrefs
                    refreshEpubPages()
                }
            }
        }
    }

    /**
     * Re-announces the current page and its neighbours from the live pagination, the same
     * neighbour-only shape [republishSurroundingPages] uses, plus [ReaderUiState.currentPage] itself
     * — called after [loadEmbeddedImagesAround] or [loadAllEmbeddedFonts] changes what the embedded
     * image/font caches or failure sets hold, so a page whose image or font just finished (or failed to)
     * loading is re-rendered with that outcome.
     */
    private fun refreshEpubPages() {
        _uiState.update {
            val pageIndex = it.pageIndex
            val facing = readerPageFacingUi(
                pageUiContext(
                    pageIndex = pageIndex,
                    documentUri = it.documentUri,
                    isPdfMode = it.isPdfMode,
                ),
            )
            it.copy(
                previousPage = facing.previous,
                currentPage = facing.current,
                nextPage = facing.next,
                pageSlots = facing.slots,
                style = styleWithPublisherFontKey(it.style, it.documentFormat),
                // Held once found: a page still decoding carries no containers, and dropping to zero
                // for it would flip the pane's padding and re-measure the book for nothing.
                publisherPageMargins = it.publisherPageMargins.takeUnless(ReaderPageMarginsEm::isZero)
                    ?: epubPageContainerMarginsEm(facing.current),
                areEmbeddedFontsResolved = it.documentFormat != DocumentFormat.EPUB || embeddedFontsSettled,
                embeddedFontFiles = embeddedFontFiles.toImmutableMap(),
                failedEmbeddedFontHrefs = failedEmbeddedFontHrefs.toImmutableSet(),
            )
        }
    }

    private fun styleWithPublisherFontKey(
        style: ReaderStyle,
        documentFormat: DocumentFormat
    ): ReaderStyle =
        style.copy(publisherFontKey = publisherFontKey(documentFormat))

    private fun publisherFontKey(documentFormat: DocumentFormat): String? {
        if (documentFormat != DocumentFormat.EPUB) return null
        val loaded = embeddedFontFiles.keys.sorted().map { href -> "$href=loaded" }
        val failed = failedEmbeddedFontHrefs.sorted().map { href -> "$href=failed" }
        return (loaded + failed).takeIf(List<String>::isNotEmpty)?.joinToString(separator = "|")
    }
}

/**
 * The viewport [loadOpenState] paginates against before any pane has ever reported a real size.
 *
 * The reader reports its viewport in sp, not px, so this placeholder is phone-sized in sp as well —
 * a px-sized value here would paginate roughly 9x too coarsely, since sp values are numerically much
 * smaller than the pixel dimensions they correspond to on a real device.
 */
private val DefaultViewportSize = ViewportSize(widthPx = 320, heightPx = 560)

/**
 * Byte budget for decoded CBZ page images [loadVisualPagesAround] keeps in [visualPageCache] while
 * protecting the current mount window from eviction.
 */
private const val VisualPageCacheBudgetBytes = 24 * 1024 * 1024

/**
 * Byte budget for decoded EPUB embedded images [loadEmbeddedImagesAround] keeps in
 * [embeddedImageCache] while protecting hrefs the current mount window still needs.
 */
private const val EmbeddedImageCacheBudgetBytes = 16 * 1024 * 1024

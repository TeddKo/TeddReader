package com.tedd.teddreader.feature.reader.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.layoutKey
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import com.tedd.teddreader.core.common.model.withThemeMode
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.domain.repository.BookmarkRepository
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import com.tedd.teddreader.core.domain.usecase.RestoreReadingProgressUseCase
import com.tedd.teddreader.core.domain.usecase.SaveReadingProgressUseCase
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import kotlin.math.abs
import kotlin.time.Clock

@KoinViewModel
class ReaderViewModel(
    private val documentRepository: DocumentRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val readerSettingsRepository: ReaderSettingsRepository,
    private val restoreReadingProgress: RestoreReadingProgressUseCase,
    private val saveReadingProgress: SaveReadingProgressUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    private var currentDocumentId: DocumentId? = null
    private var currentPageWindows: List<PageWindow> = emptyList()
    private var currentSections: List<ReaderSection> = emptyList()

    // Page numbers only mean something for one (style, viewport) pagination, so the reading
    // position is tracked as an absolute text offset that survives re-pagination.
    private var anchorOffset: Long? = null
    // sp, not px — see updatePageBreaker. What getPageWindows and page-layout storage key on.
    private var viewportSize: ViewportSize = DefaultViewportSize
    private val logger = co.touchlab.kermit.Logger.withTag("Reader")
    private var pageBreaker: ReaderPageBreaker? = null
    private var pageBreakerStyle: ReaderStyle? = null
    // px, not sp — the pane's real measured pixel box, compared only to dedupe repeated reports.
    private var pageBreakerSize: ViewportSize? = null
    private var viewportReloadJob: Job? = null
    private var openDocumentJob: Job? = null
    // Drives phase 2+ of a progressive EPUB import (see continueImportIfIncomplete) — a plain
    // viewModelScope job, not a new subsystem: it stops the moment the reader leaves this document,
    // and the next open picks the import back up from wherever the stored rows say it left off.
    private var importContinuationJob: Job? = null
    // Drives the rest of a progressive pagination pass (see continuePaginationIfIncomplete) — same
    // shape as [importContinuationJob], just for measuring an unmeasured style instead of parsing an
    // unimported section.
    private var paginationContinuationJob: Job? = null
    private var savedPlaces: List<Bookmark> = emptyList()
    private var savedPlacesJob: Job? = null
    private var visualPageLoadJob: Job? = null
    private val visualPageCache = linkedMapOf<Int, ByteArray>()
    private val failedVisualPages = linkedSetOf<Int>()
    private var embeddedImageLoadJob: Job? = null
    private val embeddedImageCache = linkedMapOf<String, ByteArray>()
    private val failedEmbeddedImageHrefs = linkedSetOf<String>()

    fun openDocument(documentIdValue: String) {
        val documentId = DocumentId(documentIdValue)
        if (currentDocumentId == documentId) return
        currentDocumentId = documentId
        openDocumentJob?.cancel()
        viewportReloadJob?.cancel()
        visualPageLoadJob?.cancel()
        embeddedImageLoadJob?.cancel()
        importContinuationJob?.cancel()
        paginationContinuationJob?.cancel()
        currentPageWindows = emptyList()
        currentSections = emptyList()
        anchorOffset = null
        visualPageCache.clear()
        failedVisualPages.clear()
        embeddedImageCache.clear()
        failedEmbeddedImageHrefs.clear()
        _uiState.value = ReaderUiState(documentTitle = documentId.value)
        observeSavedPlaces(documentId)

        openDocumentJob = viewModelScope.launch {
            try {
                val metadata = documentRepository.getDocument(documentId)
                val readerDocument = documentRepository.getReaderDocument(documentId)
                val progress = restoreReadingProgress(documentId)
                val settings = readerSettingsRepository.settings.first()
                // True for every document except one whose progressive EPUB import hasn't finished
                // yet (see ReaderUiState.isPaginationComplete) — checked here, before the first
                // publish, so the very first frame already tells the truth about the page count.
                val isImportComplete = documentRepository.isImportComplete(documentId)
                if (currentDocumentId != documentId) return@launch
                val documentFormat = metadata?.format ?: DocumentFormat.UNKNOWN
                val isPdfMode = documentFormat == DocumentFormat.PDF
                val isVisualMode = documentFormat.isVisualPageFormat()
                val documentUri = metadata?.location?.sourceUri
                // pageBreaker is only ever set by updatePageBreaker, so it is still null exactly when no
                // pane belonging to this ViewModel instance has reported a size yet. Passing null lets
                // getPageWindows resolve the newest layout ever stored for this exact style instead of
                // pagination running against viewportSize's guessed default, which almost never matches a
                // stored one and used to fall through to a full estimate pass — publishing the wrong page
                // count as the first frame, corrected only once the pane measured for real. Paginating
                // unconditionally (rather than waiting for that real report first) is still what matters
                // for the deadlock a stored row cannot help with: with no pages the pager mounts no slot,
                // with no slot nothing measures the pane, and the pane is the only thing that ever reports
                // a size — exactly the state a freshly imported book starts in.
                val hasReportedPaneSize = pageBreaker != null
                // Computed early — readerDocument is already in hand, but currentSections/anchorOffset
                // (below) are not set until after pageWindows exists — so a fresh, progressive
                // measurement can anchor on the section the reader is actually resuming into instead of
                // always section 0 (see DocumentRepository.getPageWindows).
                val resumeOffset = if (isVisualMode) null else progress?.location?.let { location ->
                    absoluteOffset(location, readerDocument?.sections.orEmpty())
                }
                val pageWindows = if (isVisualMode) {
                    emptyList()
                } else {
                    documentRepository.getPageWindows(
                        documentId = documentId,
                        style = settings.style,
                        viewportSize = if (hasReportedPaneSize) viewportSize else null,
                        pageBreaker = pageBreakerFor(settings.style),
                        anchorOffset = resumeOffset,
                    )
                }
                if (currentDocumentId != documentId) return@launch
                // A resolved layout carries its own already-measured viewport. Adopting it — and the
                // style it was measured for, since pageBreaker itself is still null; there is no real
                // ReaderPageBreaker instance yet — means the pane's first real report, almost always the
                // same size since it is the same physical screen, is recognised by updatePageBreaker as
                // already answered instead of launching a reload that would only repeat what
                // getPageWindows just cached this exact answer under.
                // Both branches write both fields. Leaving them alone when nothing was remembered
                // keeps a previous document's answer sitting in them, and the pane's first report for
                // this document would then match it and skip the reload the document actually needs.
                if (!isVisualMode && !hasReportedPaneSize) {
                    val remembered = documentRepository.resolveViewportSizeForStyle(documentId, settings.style)
                    viewportSize = remembered ?: DefaultViewportSize
                    pageBreakerStyle = settings.style.takeIf { remembered != null }
                }
                currentPageWindows = pageWindows
                currentSections = readerDocument?.sections.orEmpty()
                // isImportComplete only speaks to whether every section has been parsed yet; a fully
                // imported book can still have no stored layout for this exact style, in which case
                // getPageWindows just measured only the resumed section above and this is false too.
                val isPaginationMeasured = isVisualMode || documentRepository.isPaginationComplete(documentId)

                val metadataPageCount = metadata?.pageCount
                val totalPages = when {
                    pageWindows.isNotEmpty() -> pageWindows.size
                    metadataPageCount != null -> metadataPageCount
                    progress != null -> progress.pageIndex.total
                    else -> 0
                }
                // Single pages, which is not what the reader's own counter shows on a wide screen: that
                // one counts two-page spreads (see ReaderScreen's readerSpreadPageIndex), so a log
                // saying 8977 sits under a bar reading 4489 with nothing wrong anywhere.
                logger.d {
                    "opening total=$totalPages single pages from windows=${pageWindows.size}, " +
                        "metadata=$metadataPageCount, progress=${progress?.pageIndex?.total}, " +
                        "paginationMeasured=$isPaginationMeasured"
                }
                anchorOffset = resumeOffset
                val currentPage = (resumeOffset?.let { pageOfOffset(it, pageWindows) } ?: progress?.pageIndex?.current)
                    ?.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                    ?: 0

                // Section 0's blocks are already ready by now — getPageWindows warms it internally for
                // cover detection (see DocumentRepositoryImpl.restorePageWindows) — but the resumed
                // page's own section is not, and neither are its neighbours. Warming exactly the window
                // pageSlots() mounts, before building any page UI from it, is what keeps the first frame
                // from drawing a page with its images/chapter-title formatting still missing. Reusing
                // pagerMountWindow (the same range pageSlots() uses) rather than a separately guessed
                // radius is the point: whatever pageSlots() will actually build, this already warmed.
                if (!isVisualMode && pageWindows.isNotEmpty()) {
                    val touchedSections = pagerMountWindow(currentPage)
                        .mapNotNull { page -> pageWindows.getOrNull(page)?.textRange?.start }
                        .mapNotNull(::sectionIndexContaining)
                        .toSet()
                    if (touchedSections.isNotEmpty()) {
                        documentRepository.warmSectionBlocks(documentId, touchedSections)
                    }
                }
                if (currentDocumentId != documentId) return@launch

                val pageIndex = PageIndex(current = currentPage, total = totalPages)
                val currentPageUi = currentPageUi(
                    pageIndex = pageIndex,
                    documentUri = documentUri,
                    isPdfMode = isPdfMode,
                    pageWindows = pageWindows,
                )
                val documentTitle = readerDocument?.title ?: metadata?.location?.displayName ?: documentId.value

                // First publish: only what the page the reader lands on needs — style, total, current
                // page, its text and blocks, the title. ReaderScreen composes nothing at all while
                // isLoading is true, so everything below (the opened-at write, the outline, the
                // favourite/saved-place flags, the neighbour page slots) would otherwise sit in front
                // of the first frame for no reason other than living in the same function.
                if (currentDocumentId != documentId) return@launch
                _uiState.update { state ->
                    state.copy(
                        documentTitle = documentTitle,
                        documentUri = documentUri,
                        documentFormat = documentFormat,
                        pageText = currentPageUi.text,
                        pageIndex = pageIndex,
                        currentPage = currentPageUi,
                        style = settings.style,
                        pageTurnMode = settings.pageTurnMode,
                        pageAnimation = settings.pageAnimation,
                        autoScrollConfig = settings.autoScrollConfig.copy(enabled = false),
                        isPdfMode = isPdfMode,
                        isControlsVisible = true,
                        isLoading = false,
                        isPaginationComplete = isImportComplete && isPaginationMeasured,
                    )
                }
                if (documentFormat == DocumentFormat.CBZ) loadVisualPagesAround(currentPage)
                if (documentFormat == DocumentFormat.EPUB) loadEmbeddedImagesAround(currentPage)
                if (!isImportComplete) {
                    continueImportIfIncomplete(documentId)
                } else if (!isPaginationMeasured && pageBreakerFor(settings.style) != null) {
                    // Only worth continuing once a real breaker measured the first section — a null
                    // breaker means this was an estimate, superseded within a frame or two by the real
                    // measurement updatePageBreaker triggers, which starts its own progressive pass.
                    continuePaginationIfIncomplete(documentId, settings.style)
                }

                // Now that the first frame is out, fetch every remaining section's blocks in the
                // background so a later page turn or TOC jump almost never has to wait on one — the
                // same fetch-then-fill shape as loadEmbeddedImagesAround/refreshEpubPages, just for
                // whole sections instead of individual images. A miss that still happens (a jump ahead
                // of this fill) renders as "not yet" and self-heals the next time that page is read —
                // see SectionBlocksCache — so this job is fire-and-forget, not awaited.
                if (!isVisualMode && currentSections.isNotEmpty()) {
                    viewModelScope.launch {
                        documentRepository.warmSectionBlocks(documentId, currentSections.map { it.index }.toSet())
                    }
                }

                // Second publish: the rest, filled in and re-announced the same way refreshEpubPages
                // already does for embedded images — nothing here may touch pageIndex, pageText or
                // currentPage, which already reached the reader in the first publish above.
                documentRepository.markDocumentOpened(
                    documentId = documentId,
                    openedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                )
                if (currentDocumentId != documentId) return@launch
                val outlineItems = buildOutlineItems(
                    format = metadata?.format,
                    readerDocument = readerDocument,
                    totalPages = totalPages,
                )
                _uiState.update { state ->
                    // Read positionally off the live state and currentPageWindows (defaulted below,
                    // same as refreshEpubPages()), not the pageIndex/pageWindows/currentPage captured
                    // above — updatePageBreaker's reload runs on its own coroutine and can already have
                    // published a measured repagination by the time this update lands. Writing the
                    // pre-reload locals here would silently put the estimated pagination back.
                    val livePageIndex = state.pageIndex
                    state.copy(
                        previousPage = pageUi(
                            page = livePageIndex.current - 1,
                            pageIndex = livePageIndex,
                            documentUri = state.documentUri,
                            isPdfMode = state.isPdfMode,
                        ),
                        nextPage = pageUi(
                            page = livePageIndex.current + 1,
                            pageIndex = livePageIndex,
                            documentUri = state.documentUri,
                            isPdfMode = state.isPdfMode,
                        ),
                        pageSlots = pageSlots(
                            currentPage = livePageIndex.current,
                            pageIndex = livePageIndex,
                            documentUri = state.documentUri,
                            isPdfMode = state.isPdfMode,
                        ),
                        outlineHeading = readerDocument?.navigation?.heading,
                        outlineItems = outlineItems.toImmutableList(),
                        isFavorite = metadata?.isBookmarked == true,
                        isCurrentPageSaved = isPageSaved(livePageIndex, state.isVisualMode),
                    )
                }
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
    ) {
        // Compared by what the measurement describes, not by instance. The reporting pane moves to a
        // different composition slot on every page turn, and a page effect may compose the page
        // twice while it animates; treating those fresh instances as new measurements repaginated
        // the whole document on every turn.
        if (pageBreakerStyle?.layoutKey() == style.layoutKey() && pageBreakerSize == measuredSizePx) {
            logger.d { "breaker report ignored, already measured for $measuredSizePx" }
            return
        }
        // openDocument adopts a stored layout's viewport (and the style it was measured for) into
        // viewportSize/pageBreakerStyle before any pane has reported — pageBreaker itself is still null
        // then. This is that adoption's first real confirmation: the same physical screen, so almost
        // always the same sp size getPageWindows already cached pages under. Recording the breaker
        // without relaunching a reload is what keeps that answer from being asked for a second time.
        if (pageBreaker == null && pageBreakerStyle?.layoutKey() == style.layoutKey() && viewportSize == viewportSp) {
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
        viewportSize = viewportSp
        viewportReloadJob?.cancel()
        viewportReloadJob = viewModelScope.launch {
            reloadPages(style = _uiState.value.style)
            currentDocumentId?.let { documentId ->
                refreshPaginationCompleteness(documentId, style, isImportComplete = documentRepository.isImportComplete(documentId))
            }
        }
    }

    /**
     * Phase 2+ of a progressive EPUB import: repeatedly asks the repository to parse and measure a
     * bounded batch more of the book, in spine order, until it reports done. Each batch that actually
     * added sections re-runs [reloadPages] with the *current* style/viewport/breaker — the same
     * pagination call an ordinary font-size change already makes — so pageIndex.total grows to match
     * what is now known without ever touching a page already published (see TextPageLayoutEngine/
     * DocumentRepositoryImpl.importNextSections: appending only ever extends the stored page starts).
     * Runs on this ViewModel's own scope, so leaving the reader simply stops it; the next open resumes
     * from whatever the stored rows say is done — no separate scope, no new subsystem.
     */
    private fun continueImportIfIncomplete(documentId: DocumentId) {
        importContinuationJob?.cancel()
        importContinuationJob = viewModelScope.launch {
            while (currentDocumentId == documentId) {
                val style = _uiState.value.style
                val progress = documentRepository.importNextSections(
                    documentId = documentId,
                    count = ImportBatchSize,
                    style = style,
                    viewportSize = viewportSize,
                    pageBreaker = pageBreakerFor(style),
                )
                if (currentDocumentId != documentId) return@launch
                if (progress.sectionsImported > 0) reloadPages(style)
                if (progress.isComplete) {
                    // Import finishing does not by itself mean pagination has: the book may still have
                    // no stored layout for this style, in which case getPageWindows measured only the
                    // resumed section and there is more to continue (see refreshPaginationCompleteness).
                    refreshPaginationCompleteness(documentId, style, isImportComplete = true)
                    return@launch
                }
            }
        }
    }

    /**
     * The rest of a progressive pagination pass [DocumentRepository.getPageWindows] started but could
     * not finish measuring in one call — see that function's anchorOffset doc. Mirrors
     * [continueImportIfIncomplete]: repeatedly asks the repository to measure one more content section
     * for real, until it reports done, re-running [reloadPages] after each one so pageIndex grows to
     * match what is now known without ever touching a page already published (see
     * DocumentRepositoryImpl.continuePagination — one section's pages depend on nothing but that
     * section). Stops on its own the moment [style] is no longer current, so a font change started
     * mid-measurement lets its own fresh pass own the ui's isPaginationComplete flag instead of racing
     * this one for it.
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
                    pageBreaker = breaker,
                )
                if (currentDocumentId != documentId) return@launch
                if (progress.sectionsMeasured > 0) reloadPages(style)
                if (progress.isComplete) {
                    if (currentDocumentId == documentId) {
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
    private suspend fun refreshPaginationCompleteness(documentId: DocumentId, style: ReaderStyle, isImportComplete: Boolean) {
        if (currentDocumentId != documentId) return
        if (!documentRepository.isPaginationComplete(documentId)) {
            if (pageBreakerFor(style) != null) continuePaginationIfIncomplete(documentId, style)
            return
        }
        if (isImportComplete) {
            _uiState.update { state -> state.copy(isPaginationComplete = true) }
        }
    }

    /** Only a measurement made for [style] describes the pages that [style] will actually render. */
    private fun pageBreakerFor(style: ReaderStyle): ReaderPageBreaker? =
        pageBreaker.takeIf { pageBreakerStyle?.layoutKey() == style.layoutKey() }

    fun toggleControls() {
        _uiState.update { state -> state.copy(isControlsVisible = !state.isControlsVisible) }
    }

    fun showSheet(sheet: ReaderOptionSheet) {
        _uiState.update { state -> state.copy(activeSheet = sheet) }
    }

    fun dismissSheet() {
        _uiState.update { state -> state.copy(activeSheet = null) }
    }

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
                savedPlaces = if (existing == null) savedPlaces - savedPlace else savedPlaces + existing
                updateSavedPlaceState()
            }
        }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        _uiState.update { state -> state.copy(keepScreenOn = enabled) }
    }

    fun updateFullscreen(enabled: Boolean) {
        _uiState.update { state -> state.copy(fullscreen = enabled) }
    }

    fun updateShowProgress(enabled: Boolean) {
        _uiState.update { state -> state.copy(showProgress = enabled) }
    }

    fun updateFontSize(fontSizeSp: Float) {
        updateStyle(_uiState.value.style.copy(fontSizeSp = fontSizeSp))
    }

    fun updateLineHeight(lineHeightMultiplier: Float) {
        updateStyle(_uiState.value.style.copy(lineHeightMultiplier = lineHeightMultiplier))
    }

    fun updateFontFamily(fontFamilyName: String?) {
        updateStyle(_uiState.value.style.copy(fontFamilyName = fontFamilyName))
    }

    fun updateThemeMode(mode: ReaderThemeMode) {
        updateStyle(_uiState.value.style.withThemeMode(mode))
    }

    fun updatePageTurnMode(mode: PageTurnMode) {
        _uiState.update { state -> state.copy(pageTurnMode = mode) }
        saveReaderSettings { readerSettingsRepository.updatePageTurnMode(mode) }
    }

    fun updatePageAnimation(animation: PageAnimation) {
        _uiState.update { state -> state.copy(pageAnimation = animation) }
        saveReaderSettings { readerSettingsRepository.updatePageAnimation(animation) }
    }

    fun updateAutoScrollEnabled(enabled: Boolean) {
        updateAutoScroll(_uiState.value.autoScrollConfig.copy(enabled = enabled))
    }

    fun updateAutoScrollMode(mode: AutoScrollMode) {
        updateAutoScroll(_uiState.value.autoScrollConfig.copy(mode = mode))
    }

    fun updateAutoScrollSpeed(speed: Float) {
        updateAutoScroll(_uiState.value.autoScrollConfig.copy(speed = AutoScrollConfig.clampSpeed(speed)))
    }

    fun stopAutoScroll() {
        if (!_uiState.value.autoScrollConfig.enabled) return
        updateAutoScrollEnabled(false)
    }

    fun updateBrightnessOverlayAlpha(alpha: Float) {
        _uiState.update { state -> state.copy(brightnessOverlayAlpha = alpha.coerceIn(0f, 0.8f)) }
    }

    // A relative move resolves against the pagination that is current when it runs. Letting the
    // caller hand over a page index instead lets a font or line-height change repaginate in
    // between, and moveToPage then clamps that stale index into the new, shorter document — which
    // lands on the last page instead of the next one.
    fun movePrevious(step: Int = 1) {
        val pageIndex = _uiState.value.pageIndex
        if (pageIndex.total <= 0) return
        val target = (pageIndex.current - step.coerceAtLeast(1)).coerceAtLeast(0)
        if (target != pageIndex.current) moveToPage(target)
    }

    fun moveNext(step: Int = 1) {
        val pageIndex = _uiState.value.pageIndex
        val target = pageIndex.current + step.coerceAtLeast(1)
        if (target in 0 until pageIndex.total) moveToPage(target)
    }

    fun moveToLocation(location: ReaderLocation) {
        val page = when (location) {
            is ReaderLocation.PdfPage -> location.pageIndex
            else -> absoluteOffset(location)
                ?.let { offset -> pageOfOffset(offset, currentPageWindows) }
                ?: _uiState.value.pageIndex.current
        }
        moveToPage(page)
    }

    private fun buildOutlineItems(
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

    private fun updateStyle(style: ReaderStyle) {
        val previousStyle = _uiState.value.style
        _uiState.update { state -> state.copy(style = style) }
        saveReaderSettings {
            readerSettingsRepository.updateStyle(style)
            // Only type moves the page breaks. A colour or background change is still saved and still
            // redraws, but laying the book out again for it would cost the whole document.
            if (previousStyle.layoutKey() != style.layoutKey()) {
                reloadPages(style)
                // A type nobody has read this book at before has no stored layout — reloadPages just
                // measured only the section the reader is on, same as a fresh open (see
                // DocumentRepository.getPageWindows). Finish the rest in the background.
                currentDocumentId?.let { documentId ->
                    refreshPaginationCompleteness(documentId, style, isImportComplete = documentRepository.isImportComplete(documentId))
                }
            }
        }
    }

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

    private fun saveReaderSettings(block: suspend () -> Unit) {
        _uiState.update { state -> state.copy(isSavingSettings = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(errorMessage = throwable.message ?: "Failed to save reader settings.")
                    }
                }
            _uiState.update { state -> state.copy(isSavingSettings = false) }
        }
    }

    // A page window is built — and its section's blocks decoded — only the first time something reads
    // it by index (see DocumentRepository.getPageWindows), so scanning pageWindows in order here would
    // force every page up to the match to build just to answer where one offset lands. Binary search
    // over the pages' own start offsets instead touches only the O(log n) pages the search actually
    // visits.
    private fun pageOfOffset(offset: Long, pageWindows: List<PageWindow>): Int? {
        var low = 0
        var high = pageWindows.lastIndex
        while (low <= high) {
            val mid = (low + high) / 2
            val range = pageWindows[mid].textRange ?: return null
            when {
                offset < range.start -> high = mid - 1
                offset >= range.end -> low = mid + 1
                else -> return mid
            }
        }
        return null
    }

    /**
     * EPUB locations are section-relative; pagination works on document-absolute offsets. [sections]
     * defaults to [currentSections], but openDocument calls this before that field is populated for
     * this document — passing the freshly loaded document's own sections lets it resolve the resumed
     * offset early enough to anchor getPageWindows' progressive measurement on it.
     */
    private fun absoluteOffset(location: ReaderLocation, sections: List<ReaderSection> = currentSections): Long? =
        when (location) {
            is ReaderLocation.TextOffset -> location.offset
            is ReaderLocation.EpubOffset -> {
                val sectionStart = sections
                    .firstOrNull { section -> section.index == location.spineIndex }
                    ?.range
                    ?.start
                    ?: 0L
                sectionStart + location.offset
            }
            is ReaderLocation.PdfPage -> null
        }

    private fun currentPageUi(
        pageIndex: PageIndex,
        documentUri: String?,
        isPdfMode: Boolean,
        pageWindows: List<PageWindow> = currentPageWindows,
    ): ReaderPageUi = pageUi(
        page = pageIndex.current,
        pageIndex = pageIndex,
        documentUri = documentUri,
        isPdfMode = isPdfMode,
        pageWindows = pageWindows,
    ) ?: ReaderPageUi(
        page = pageIndex.current,
        isPdf = isPdfMode,
        documentUri = documentUri,
    )

    private fun pageUi(
        page: Int,
        pageIndex: PageIndex,
        documentUri: String?,
        isPdfMode: Boolean,
        pageWindows: List<PageWindow> = currentPageWindows,
    ): ReaderPageUi? {
        if (pageIndex.total <= 0 || page !in 0 until pageIndex.total) return null
        val pageWindow = pageWindows.getOrNull(page)
        // The chapter title stays pinned in the top bar for every page of a chapter, not only its
        // first, so it finds the section that *contains* this page's start rather than requiring an
        // exact match against the section's own start offset.
        val chapterTitle = pageWindow
            ?.takeIf { window -> window.blocks.none { it.kind == com.tedd.teddreader.core.common.model.ReaderBlockKind.COVER_IMAGE } }
            ?.textRange
            ?.start
            ?.let { start ->
                currentSections
                    .filter { section -> section.range.start <= start && section.title != null }
                    .maxByOrNull { section -> section.range.start }
                    ?.title
            }
        // True by construction from where pagination put this page's own boundary, not from how much
        // of the sheet the rendered text happened to fill (see EpubPageSurface) — an estimated
        // pagination under-fills every page it has not measured for real, which used to make every
        // page on a fresh install look short and centre itself until the real measurement replaced it.
        val isSectionTail = pageWindow?.textRange?.let { range ->
            sectionContaining(range.start)?.range?.end == range.end
        } ?: false
        return ReaderPageUi(
            page = page,
            text = if (isPdfMode) "" else pageWindow?.text.orEmpty(),
            isPdf = isPdfMode,
            documentUri = documentUri,
            textRange = pageWindow?.textRange,
            blocks = pageWindow?.blocks.orEmpty().toImmutableList(),
            embeddedImages = pageWindow
                ?.blocks
                .orEmpty()
                .mapNotNull { block -> block.imageHref?.takeIf(embeddedImageCache::containsKey) }
                .associateWith { href -> embeddedImageCache.getValue(href) }
                .toImmutableMap(),
            failedEmbeddedImageHrefs = pageWindow
                ?.blocks
                .orEmpty()
                .mapNotNull { it.imageHref }
                .filter(failedEmbeddedImageHrefs::contains)
                .toImmutableSet(),
            chapterTitle = chapterTitle,
            isSectionTail = isSectionTail,
        )
    }

    private fun pageSlots(
        currentPage: Int,
        pageIndex: PageIndex,
        documentUri: String?,
        isPdfMode: Boolean,
        pageWindows: List<PageWindow> = currentPageWindows,
    ): ImmutableList<ReaderPageUi> = pagerMountWindow(currentPage).mapNotNull { page ->
        pageUi(
            page = page,
            pageIndex = pageIndex,
            documentUri = documentUri,
            isPdfMode = isPdfMode,
            pageWindows = pageWindows,
        )
    }.toImmutableList()

    /**
     * Every page index the pager can put in front of the reader from [currentPage] — the same window
     * [pageSlots] mounts and [loadEmbeddedImagesAround] preloads images for. Named here so
     * openDocument's pre-publish block-warming asks for exactly this window instead of a separately
     * guessed radius.
     */
    private fun pagerMountWindow(currentPage: Int): IntRange = currentPage - 2..currentPage + 3

    /** The section whose absolute range contains [offset] — the last one starting at or before it,
     * since sections are ascending and non-overlapping. Null only if [currentSections] is empty. */
    private fun sectionContaining(offset: Long): ReaderSection? =
        currentSections.filter { section -> section.range.start <= offset }.maxByOrNull { section -> section.range.start }

    /** The section whose absolute range contains [offset], the same containment [pageUi]'s chapter-title
     * lookup already uses — null only if [currentSections] is empty. */
    private fun sectionIndexContaining(offset: Long): Int? = sectionContaining(offset)?.index

    fun moveToPage(page: Int) {
        val state = _uiState.value
        val total = state.pageIndex.total
        if (total <= 0) return
        val lastPage = (total - 1).coerceAtLeast(0)
        val nextPage = page.coerceIn(0, lastPage)
        val nextIndex = PageIndex(current = nextPage, total = total)
        anchorOffset = currentPageWindows.getOrNull(nextPage)?.textRange?.start
        _uiState.update {
            val currentPageUi = currentPageUi(
                pageIndex = nextIndex,
                documentUri = it.documentUri,
                isPdfMode = it.isPdfMode,
            )
            it.copy(
                pageIndex = nextIndex,
                pageText = currentPageUi.text,
                previousPage = pageUi(
                    page = nextPage - 1,
                    pageIndex = nextIndex,
                    documentUri = it.documentUri,
                    isPdfMode = it.isPdfMode,
                ),
                currentPage = currentPageUi,
                nextPage = pageUi(
                    page = nextPage + 1,
                    pageIndex = nextIndex,
                    documentUri = it.documentUri,
                    isPdfMode = it.isPdfMode,
                ),
                pageSlots = pageSlots(
                    currentPage = nextPage,
                    pageIndex = nextIndex,
                    documentUri = it.documentUri,
                    isPdfMode = it.isPdfMode,
                ),
                isCurrentPageSaved = isPageSaved(nextIndex, it.isVisualMode),
            )
        }
        saveProgress(nextIndex)
        loadVisualPagesAround(nextPage)
        loadEmbeddedImagesAround(nextPage)
    }

    private suspend fun reloadPages(style: ReaderStyle) {
        val documentId = currentDocumentId ?: return
        if (_uiState.value.isVisualMode) return

        // A measurement for another style would size the pages wrong; the UI reports a matching
        // breaker moments later and that report drives the reload.
        if (pageBreaker != null && pageBreakerStyle?.layoutKey() != style.layoutKey()) {
            logger.d { "reload skipped: measurement belongs to another type" }
            return
        }

        val pageWindows = documentRepository.getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
            pageBreaker = pageBreakerFor(style),
            anchorOffset = anchorOffset,
        )
        if (pageWindows.isEmpty()) return

        val currentPage = anchorOffset?.let { offset -> pageOfOffset(offset, pageWindows) }
            ?: _uiState.value.pageIndex.current.coerceIn(0, pageWindows.lastIndex)
        currentPageWindows = pageWindows
        val pageIndex = PageIndex(current = currentPage, total = pageWindows.size)
        _uiState.update {
            val currentPageUi = currentPageUi(
                pageIndex = pageIndex,
                documentUri = it.documentUri,
                isPdfMode = false,
                pageWindows = pageWindows,
            )
            it.copy(
                pageIndex = pageIndex,
                pageText = currentPageUi.text,
                previousPage = pageUi(
                    page = currentPage - 1,
                    pageIndex = pageIndex,
                    documentUri = it.documentUri,
                    isPdfMode = false,
                    pageWindows = pageWindows,
                ),
                currentPage = currentPageUi,
                nextPage = pageUi(
                    page = currentPage + 1,
                    pageIndex = pageIndex,
                    documentUri = it.documentUri,
                    isPdfMode = false,
                    pageWindows = pageWindows,
                ),
                documentPages = persistentListOf(),
                pageSlots = pageSlots(
                    currentPage = currentPage,
                    pageIndex = pageIndex,
                    documentUri = it.documentUri,
                    isPdfMode = false,
                    pageWindows = pageWindows,
                ),
                isCurrentPageSaved = isPageSaved(pageIndex, false),
            )
        }
        loadEmbeddedImagesAround(currentPage)
    }

    private fun saveProgress(pageIndex: PageIndex) {
        val documentId = currentDocumentId ?: return
        // A text document with no pagination yet cannot say where the reader is. Saving anyway takes
        // the fallback in currentLocation — a page number dressed up as a character offset — and writes
        // it over the place the reader actually left off, sending them back to the first page of the
        // book. Whatever is already stored is a better answer than that.
        if (!_uiState.value.isVisualMode && currentPageWindows.isEmpty()) return
        viewModelScope.launch {
            saveReadingProgress(
                ReadingProgress(
                    documentId = documentId,
                    location = currentLocation(pageIndex),
                    pageIndex = pageIndex,
                    updatedAtEpochMillis = 0L,
                ),
            )
        }
    }

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

    private fun updateSavedPlaceState() {
        _uiState.update { state ->
            state.copy(isCurrentPageSaved = isPageSaved(state.pageIndex, state.isVisualMode))
        }
    }

    private fun isPageSaved(pageIndex: PageIndex, isVisualMode: Boolean): Boolean =
        savedPlaces.any { it.location == currentLocation(pageIndex, isVisualMode) }

    private fun currentLocation(
        pageIndex: PageIndex,
        isVisualMode: Boolean = _uiState.value.isVisualMode,
    ): ReaderLocation {
        if (isVisualMode) {
            return ReaderLocation.PdfPage(pageIndex.current)
        }

        return currentPageWindows.getOrNull(pageIndex.current)?.location
            ?: ReaderLocation.TextOffset(pageIndex.current.toLong())
    }

    private fun loadVisualPagesAround(centerPage: Int) {
        val documentId = currentDocumentId ?: return
        val state = _uiState.value
        if (state.documentFormat != DocumentFormat.CBZ || state.pageIndex.total <= 0) return
        val requestedPages = (centerPage - 2..centerPage + 3)
            .filterTo(linkedSetOf()) { it in 0 until state.pageIndex.total }
        val missingPages = requestedPages - visualPageCache.keys - failedVisualPages
        if (missingPages.isEmpty()) return

        visualPageLoadJob?.cancel()
        visualPageLoadJob = viewModelScope.launch {
            try {
                val loadedPages = documentRepository.getVisualPageImages(documentId, missingPages)
                if (currentDocumentId != documentId) return@launch
                visualPageCache.putAll(loadedPages)
                failedVisualPages += missingPages - loadedPages.keys
                val retainedPages = visualPageCache.keys
                    .sortedBy { page -> abs(page - _uiState.value.pageIndex.current) }
                    .take(MaxVisualPageCacheSize)
                    .toSet()
                visualPageCache.keys.retainAll(retainedPages)
                _uiState.update {
                    it.copy(
                        visualPageImages = visualPageCache.toImmutableMap(),
                        failedVisualPages = failedVisualPages.toImmutableSet(),
                    )
                }
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (_: Throwable) {
                if (currentDocumentId == documentId) {
                    failedVisualPages += missingPages
                    _uiState.update { it.copy(failedVisualPages = failedVisualPages.toImmutableSet()) }
                }
            }
        }
    }

    private fun loadEmbeddedImagesAround(centerPage: Int) {
        val documentId = currentDocumentId ?: return
        val state = _uiState.value
        if (state.documentFormat != DocumentFormat.EPUB || state.pageIndex.total <= 0) return
        // Matches the pageSlots window the pager actually mounts (see pageSlots()/pagerMountWindow),
        // so every slot the reader can swipe to preview already has its images requested instead of
        // only the immediate neighbor.
        val relevantHrefs = pagerMountWindow(centerPage)
            .filter { it in currentPageWindows.indices }
            .flatMap { page -> currentPageWindows[page].blocks.mapNotNull { it.imageHref } }
            .toSet()
        val missingHrefs = relevantHrefs - embeddedImageCache.keys - failedEmbeddedImageHrefs
        if (missingHrefs.isEmpty()) {
            refreshEpubPages()
            return
        }

        embeddedImageLoadJob?.cancel()
        embeddedImageLoadJob = viewModelScope.launch {
            try {
                val loadedImages = documentRepository.getEmbeddedImages(documentId, missingHrefs)
                if (currentDocumentId != documentId) return@launch
                embeddedImageCache.putAll(loadedImages)
                failedEmbeddedImageHrefs += missingHrefs - loadedImages.keys
                // Keep every image the current window still needs (e.g. the cover, revisited later);
                // only the oldest of the rest ages out once the cache is over budget. Plain insertion-
                // order LRU evicted a still-needed image (the cover was always the first one loaded)
                // just because newer images had since been cached.
                val retainedHrefs = embeddedImageCache.keys.filterTo(linkedSetOf()) { it in relevantHrefs }
                if (retainedHrefs.size < MaxEmbeddedImageCacheSize) {
                    embeddedImageCache.keys.toList().asReversed().forEach { href ->
                        if (retainedHrefs.size >= MaxEmbeddedImageCacheSize) return@forEach
                        retainedHrefs += href
                    }
                }
                embeddedImageCache.keys.retainAll(retainedHrefs)
                refreshEpubPages()
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (_: Throwable) {
                if (currentDocumentId == documentId) {
                    failedEmbeddedImageHrefs += missingHrefs
                    refreshEpubPages()
                }
            }
        }
    }

    private fun refreshEpubPages() {
        _uiState.update {
            val pageIndex = it.pageIndex
            val currentPageUi = currentPageUi(
                pageIndex = pageIndex,
                documentUri = it.documentUri,
                isPdfMode = it.isPdfMode,
            )
            it.copy(
                previousPage = pageUi(
                    page = pageIndex.current - 1,
                    pageIndex = pageIndex,
                    documentUri = it.documentUri,
                    isPdfMode = it.isPdfMode,
                ),
                currentPage = currentPageUi,
                nextPage = pageUi(
                    page = pageIndex.current + 1,
                    pageIndex = pageIndex,
                    documentUri = it.documentUri,
                    isPdfMode = it.isPdfMode,
                ),
                pageSlots = pageSlots(
                    currentPage = pageIndex.current,
                    pageIndex = pageIndex,
                    documentUri = it.documentUri,
                    isPdfMode = it.isPdfMode,
                ),
                documentPages = persistentListOf(),
            )
        }
    }
}

// The reader reports its viewport in sp, not px, so the placeholder used before the first
// measurement is phone-sized in sp; a px-sized value paginates ~9x too coarsely.
private val DefaultViewportSize = ViewportSize(widthPx = 320, heightPx = 560)
private const val MaxVisualPageCacheSize = 8
private const val MaxEmbeddedImageCacheSize = 12

// How many spine items continueImportIfIncomplete asks for per step — small enough that one batch's
// pause is never noticeable next to a page turn, large enough that a 500-chapter book does not need
// dozens of round trips through the ViewModel/repository boundary to finish.
private const val ImportBatchSize = 16

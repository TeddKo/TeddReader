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
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderStyle
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
    private var viewportSize: ViewportSize = DefaultViewportSize
    private var pageBreaker: ReaderPageBreaker? = null
    private var pageBreakerStyle: ReaderStyle? = null
    private var pageBreakerSize: ViewportSize? = null
    private var viewportReloadJob: Job? = null
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
        visualPageLoadJob?.cancel()
        embeddedImageLoadJob?.cancel()
        visualPageCache.clear()
        failedVisualPages.clear()
        embeddedImageCache.clear()
        failedEmbeddedImageHrefs.clear()
        observeSavedPlaces(documentId)

        viewModelScope.launch {
            runCatching {
                val metadata = documentRepository.getDocument(documentId)
                val readerDocument = documentRepository.getReaderDocument(documentId)
                val progress = restoreReadingProgress(documentId)
                val settings = readerSettingsRepository.settings.first()
                val documentFormat = metadata?.format ?: DocumentFormat.UNKNOWN
                val isPdfMode = documentFormat == DocumentFormat.PDF
                val isVisualMode = documentFormat.isVisualPageFormat()
                val documentUri = metadata?.location?.sourceUri
                val pageWindows = if (isVisualMode) {
                    emptyList()
                } else {
                    documentRepository.getPageWindows(
                        documentId = documentId,
                        style = settings.style,
                        viewportSize = viewportSize,
                        pageBreaker = pageBreakerFor(settings.style),
                    )
                }
                currentPageWindows = pageWindows
                currentSections = readerDocument?.sections.orEmpty()
                documentRepository.markDocumentOpened(
                    documentId = documentId,
                    openedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                )

                val metadataPageCount = metadata?.pageCount
                val totalPages = when {
                    pageWindows.isNotEmpty() -> pageWindows.size
                    metadataPageCount != null -> metadataPageCount
                    progress != null -> progress.pageIndex.total
                    else -> 0
                }
                val restoredOffset = if (isVisualMode) null else progress?.location?.let(::absoluteOffset)
                anchorOffset = restoredOffset
                val currentPage = (restoredOffset?.let { pageOfOffset(it, pageWindows) } ?: progress?.pageIndex?.current)
                    ?.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                    ?: 0
                val pageIndex = PageIndex(current = currentPage, total = totalPages)
                val currentPageUi = currentPageUi(
                    pageIndex = pageIndex,
                    documentUri = documentUri,
                    isPdfMode = isPdfMode,
                    pageWindows = pageWindows,
                )
                val outlineItems = buildOutlineItems(
                    format = metadata?.format,
                    sections = readerDocument?.sections.orEmpty(),
                    totalPages = totalPages,
                )
                val documentPages = documentPages(
                    pageIndex = pageIndex,
                    documentUri = documentUri,
                    isVisualMode = isVisualMode,
                    pageWindows = pageWindows,
                )

                ReaderUiState(
                    documentTitle = metadata?.location?.displayName ?: documentId.value,
                    documentUri = documentUri,
                    documentFormat = documentFormat,
                    pageText = currentPageUi.text,
                    pageIndex = pageIndex,
                    previousPage = pageUi(
                        page = currentPage - 1,
                        pageIndex = pageIndex,
                        documentUri = documentUri,
                        isPdfMode = isPdfMode,
                        pageWindows = pageWindows,
                    ),
                    currentPage = currentPageUi,
                    nextPage = pageUi(
                        page = currentPage + 1,
                        pageIndex = pageIndex,
                        documentUri = documentUri,
                        isPdfMode = isPdfMode,
                        pageWindows = pageWindows,
                    ),
                    documentPages = documentPages,
                    pageSlots = pageSlots(
                        currentPage = currentPage,
                        pageIndex = pageIndex,
                        documentUri = documentUri,
                        isPdfMode = isPdfMode,
                        pageWindows = pageWindows,
                    ),
                    style = settings.style,
                    pageTurnMode = settings.pageTurnMode,
                    pageAnimation = settings.pageAnimation,
                    autoScrollConfig = settings.autoScrollConfig.copy(enabled = false),
                    outlineItems = outlineItems,
                    isPdfMode = isPdfMode,
                    isFavorite = metadata?.isBookmarked == true,
                    isCurrentPageSaved = isPageSaved(pageIndex, isVisualMode),
                    isControlsVisible = true,
                    isLoading = false,
                )
            }.onSuccess { state ->
                _uiState.value = state
                if (state.documentFormat == DocumentFormat.CBZ) loadVisualPagesAround(state.pageIndex.current)
                if (state.documentFormat == DocumentFormat.EPUB) loadEmbeddedImagesAround(state.pageIndex.current)
            }.onFailure { throwable ->
                _uiState.value = ReaderUiState(
                    documentTitle = documentId.value,
                    isLoading = false,
                    errorMessage = throwable.message ?: "Failed to open document.",
                )
            }
        }
    }

    fun updateViewportSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val nextViewportSize = ViewportSize(widthPx = widthPx, heightPx = heightPx)
        if (nextViewportSize == viewportSize) return
        viewportSize = nextViewportSize
        viewportReloadJob?.cancel()
        viewportReloadJob = viewModelScope.launch {
            reloadPages(style = _uiState.value.style)
        }
    }

    /**
     * The rendered text layout that pagination must agree with, together with the style it was
     * measured for. Pagination waits for a breaker matching the current style, because repaginating
     * a new font size against the previous measurement is exactly what clips the last line.
     */
    fun updatePageBreaker(style: ReaderStyle, measuredSize: ViewportSize, breaker: ReaderPageBreaker) {
        // Compared by what the measurement describes, not by instance. The reporting pane moves to a
        // different composition slot on every page turn, and a page effect may compose the page
        // twice while it animates; treating those fresh instances as new measurements repaginated
        // the whole document on every turn.
        if (pageBreakerStyle == style && pageBreakerSize == measuredSize) return
        pageBreaker = breaker
        pageBreakerStyle = style
        pageBreakerSize = measuredSize
        viewportReloadJob?.cancel()
        viewportReloadJob = viewModelScope.launch {
            reloadPages(style = _uiState.value.style)
        }
    }

    /** Only a measurement made for [style] describes the pages that [style] will actually render. */
    private fun pageBreakerFor(style: ReaderStyle): ReaderPageBreaker? =
        pageBreaker.takeIf { pageBreakerStyle == style }

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
        sections: List<ReaderSection>,
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
        return sections.map { section ->
            ReaderOutlineItem(
                title = section.title ?: "Section ${section.index + 1}",
                location = when (format) {
                    DocumentFormat.EPUB -> ReaderLocation.EpubOffset(section.index, section.range.start)
                    else -> ReaderLocation.TextOffset(section.range.start)
                },
            )
        }
    }

    private fun updateStyle(style: ReaderStyle) {
        _uiState.update { state -> state.copy(style = style) }
        saveReaderSettings {
            readerSettingsRepository.updateStyle(style)
            reloadPages(style)
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

    private fun pageOfOffset(offset: Long, pageWindows: List<PageWindow>): Int? = pageWindows
        .indexOfFirst { page ->
            val range = page.textRange ?: return@indexOfFirst false
            offset >= range.start && offset < range.end
        }
        .takeIf { index -> index >= 0 }

    /** EPUB locations are section-relative; pagination works on document-absolute offsets. */
    private fun absoluteOffset(location: ReaderLocation): Long? = when (location) {
        is ReaderLocation.TextOffset -> location.offset
        is ReaderLocation.EpubOffset -> {
            val sectionStart = currentSections
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
        return ReaderPageUi(
            page = page,
            text = if (isPdfMode) "" else pageWindows.getOrNull(page)?.text.orEmpty(),
            isPdf = isPdfMode,
            documentUri = documentUri,
            textRange = pageWindows.getOrNull(page)?.textRange,
            blocks = pageWindows.getOrNull(page)?.blocks.orEmpty(),
            embeddedImages = pageWindows.getOrNull(page)
                ?.blocks
                .orEmpty()
                .mapNotNull { block -> block.imageHref?.takeIf(embeddedImageCache::containsKey) }
                .associateWith { href -> embeddedImageCache.getValue(href) },
            failedEmbeddedImageHrefs = pageWindows.getOrNull(page)
                ?.blocks
                .orEmpty()
                .mapNotNull { it.imageHref }
                .filter(failedEmbeddedImageHrefs::contains)
                .toSet(),
        )
    }

    private fun pageSlots(
        currentPage: Int,
        pageIndex: PageIndex,
        documentUri: String?,
        isPdfMode: Boolean,
        pageWindows: List<PageWindow> = currentPageWindows,
    ): List<ReaderPageUi> = (currentPage - 2..currentPage + 3).mapNotNull { page ->
        pageUi(
            page = page,
            pageIndex = pageIndex,
            documentUri = documentUri,
            isPdfMode = isPdfMode,
            pageWindows = pageWindows,
        )
    }

    private fun documentPages(
        pageIndex: PageIndex,
        documentUri: String?,
        isVisualMode: Boolean,
        pageWindows: List<PageWindow> = currentPageWindows,
    ): List<ReaderPageUi> {
        if (isVisualMode || pageIndex.total <= 0) return emptyList()
        return (0 until pageIndex.total).mapNotNull { page ->
            pageUi(
                page = page,
                pageIndex = pageIndex,
                documentUri = documentUri,
                isPdfMode = false,
                pageWindows = pageWindows,
            )
        }
    }

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
        if (pageBreaker != null && pageBreakerStyle != style) return

        val pageWindows = documentRepository.getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
            pageBreaker = pageBreakerFor(style),
        )
        if (pageWindows.isEmpty()) return

        val currentPage = anchorOffset?.let { offset -> pageOfOffset(offset, pageWindows) }
            ?: _uiState.value.pageIndex.current.coerceIn(0, pageWindows.lastIndex)
        currentPageWindows = pageWindows
        val pageIndex = PageIndex(current = currentPage, total = pageWindows.size)
        val documentPages = documentPages(
            pageIndex = pageIndex,
            documentUri = _uiState.value.documentUri,
            isVisualMode = false,
            pageWindows = pageWindows,
        )
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
                documentPages = documentPages,
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
                        visualPageImages = visualPageCache.toMap(),
                        failedVisualPages = failedVisualPages.toSet(),
                    )
                }
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (_: Throwable) {
                if (currentDocumentId == documentId) {
                    failedVisualPages += missingPages
                    _uiState.update { it.copy(failedVisualPages = failedVisualPages.toSet()) }
                }
            }
        }
    }

    private fun loadEmbeddedImagesAround(centerPage: Int) {
        val documentId = currentDocumentId ?: return
        val state = _uiState.value
        if (state.documentFormat != DocumentFormat.EPUB || state.pageIndex.total <= 0) return
        val requestedHrefs = (centerPage - 1..centerPage + 1)
            .filter { it in currentPageWindows.indices }
            .flatMap { page -> currentPageWindows[page].blocks.mapNotNull { it.imageHref } }
            .toSet()
        val missingHrefs = requestedHrefs - embeddedImageCache.keys - failedEmbeddedImageHrefs
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
                val retainedHrefs = embeddedImageCache.keys
                    .toList()
                    .takeLast(MaxEmbeddedImageCacheSize)
                    .toSet()
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
                documentPages = documentPages(
                    pageIndex = pageIndex,
                    documentUri = it.documentUri,
                    isVisualMode = it.isVisualMode,
                ),
            )
        }
    }
}

// The reader reports its viewport in sp, not px, so the placeholder used before the first
// measurement is phone-sized in sp; a px-sized value paginates ~9x too coarsely.
private val DefaultViewportSize = ViewportSize(widthPx = 320, heightPx = 560)
private const val MaxVisualPageCacheSize = 8
private const val MaxEmbeddedImageCacheSize = 12

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
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.withThemeMode
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.domain.repository.BookmarkRepository
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import com.tedd.teddreader.core.domain.usecase.RestoreReadingProgressUseCase
import com.tedd.teddreader.core.domain.usecase.SaveReadingProgressUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
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
    private var viewportSize: ViewportSize = DefaultViewportSize
    private var viewportReloadJob: Job? = null
    private var savedPlaces: List<Bookmark> = emptyList()
    private var savedPlacesJob: Job? = null

    fun openDocument(documentIdValue: String) {
        val documentId = DocumentId(documentIdValue)
        if (currentDocumentId == documentId) return
        currentDocumentId = documentId
        observeSavedPlaces(documentId)

        viewModelScope.launch {
            runCatching {
                val metadata = documentRepository.getDocument(documentId)
                val readerDocument = documentRepository.getReaderDocument(documentId)
                val progress = restoreReadingProgress(documentId)
                val settings = readerSettingsRepository.settings.first()
                val isPdfMode = metadata?.format == DocumentFormat.PDF
                val documentUri = metadata?.location?.sourceUri
                val pageWindows = if (isPdfMode) {
                    emptyList()
                } else {
                    documentRepository.getPageWindows(
                        documentId = documentId,
                        style = settings.style,
                        viewportSize = viewportSize,
                    )
                }
                currentPageWindows = pageWindows

                val metadataPageCount = metadata?.pageCount
                val totalPages = when {
                    pageWindows.isNotEmpty() -> pageWindows.size
                    metadataPageCount != null -> metadataPageCount
                    progress != null -> progress.pageIndex.total
                    else -> 0
                }
                val currentPage = progress
                    ?.pageIndex
                    ?.current
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

                ReaderUiState(
                    documentTitle = metadata?.location?.displayName ?: documentId.value,
                    documentUri = documentUri,
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
                    autoScrollConfig = settings.autoScrollConfig,
                    outlineItems = outlineItems,
                    isPdfMode = isPdfMode,
                    isFavorite = metadata?.isBookmarked == true,
                    isCurrentPageSaved = isPageSaved(pageIndex, isPdfMode),
                    isControlsVisible = true,
                    isLoading = false,
                )
            }.onSuccess { state ->
                _uiState.value = state
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

    fun updateBrightnessOverlayAlpha(alpha: Float) {
        _uiState.update { state -> state.copy(brightnessOverlayAlpha = alpha.coerceIn(0f, 0.8f)) }
    }

    fun movePrevious() {
        moveToPage(_uiState.value.pageIndex.current - 1)
    }

    fun moveNext() {
        moveToPage(_uiState.value.pageIndex.current + 1)
    }

    fun moveToLocation(location: ReaderLocation) {
        val page = when (location) {
            is ReaderLocation.PdfPage -> location.pageIndex
            is ReaderLocation.TextOffset -> pageForOffset(location.offset)
            is ReaderLocation.EpubOffset -> pageForOffset(location.offset)
        }
        moveToPage(page)
    }

    private fun buildOutlineItems(
        format: DocumentFormat?,
        sections: List<ReaderSection>,
        totalPages: Int,
    ): List<ReaderOutlineItem> {
        if (format == DocumentFormat.PDF) {
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
        _uiState.update { state -> state.copy(autoScrollConfig = normalizedConfig) }
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

    private fun pageForOffset(offset: Long): Int = currentPageWindows
        .indexOfFirst { page ->
            val range = page.textRange ?: return@indexOfFirst false
            offset >= range.start && offset < range.end
        }
        .takeIf { index -> index >= 0 }
        ?: _uiState.value.pageIndex.current

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

    fun moveToPage(page: Int) {
        val state = _uiState.value
        val total = state.pageIndex.total
        if (total <= 0) return
        val lastPage = (total - 1).coerceAtLeast(0)
        val nextPage = page.coerceIn(0, lastPage)
        val nextIndex = PageIndex(current = nextPage, total = total)
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
                isCurrentPageSaved = isPageSaved(nextIndex, it.isPdfMode),
            )
        }
        saveProgress(nextIndex)
    }

    private suspend fun reloadPages(style: ReaderStyle) {
        val documentId = currentDocumentId ?: return
        if (_uiState.value.isPdfMode) return

        val pageWindows = documentRepository.getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
        )
        if (pageWindows.isEmpty()) return

        currentPageWindows = pageWindows
        val currentPage = _uiState.value.pageIndex.current.coerceIn(0, pageWindows.lastIndex)
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
            state.copy(isCurrentPageSaved = isPageSaved(state.pageIndex, state.isPdfMode))
        }
    }

    private fun isPageSaved(pageIndex: PageIndex, isPdfMode: Boolean): Boolean =
        savedPlaces.any { it.location == currentLocation(pageIndex, isPdfMode) }

    private fun currentLocation(
        pageIndex: PageIndex,
        isPdfMode: Boolean = _uiState.value.isPdfMode,
    ): ReaderLocation {
        if (isPdfMode) {
            return ReaderLocation.PdfPage(pageIndex.current)
        }

        return currentPageWindows.getOrNull(pageIndex.current)?.location
            ?: ReaderLocation.TextOffset(pageIndex.current.toLong())
    }
}

private val DefaultViewportSize = ViewportSize(widthPx = 1080, heightPx = 1600)

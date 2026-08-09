@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.tedd.teddreader.feature.reader.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.darkReaderStyle
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.readerColors
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddFullScreenLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddModalBottomSheet
import com.tedd.teddreader.core.ui.component.TeddOptionGroup
import com.tedd.teddreader.core.ui.component.TeddRadioRow
import com.tedd.teddreader.core.ui.component.TeddSliderRow
import com.tedd.teddreader.core.ui.component.TeddSwitchRow
import com.tedd.teddreader.core.ui.component.TeddTextField
import com.tedd.teddreader.core.ui.extension.pxToSp
import com.tedd.teddreader.core.ui.reader.ReaderOptionPreview
import com.tedd.teddreader.core.ui.reader.ReaderPageSurface
import com.tedd.teddreader.core.ui.reader.ReaderTopControls
import com.tedd.teddreader.core.ui.system.ReaderSystemBarsEffect
import com.tedd.teddreader.feature.reader.impl.component.ReaderActionMenu
import com.tedd.teddreader.feature.reader.impl.component.ReaderBottomActionBar
import com.tedd.teddreader.feature.reader.impl.component.ReaderPageMoveRequest
import com.tedd.teddreader.feature.reader.impl.component.ReaderPageMovement
import com.tedd.teddreader.feature.reader.impl.component.ReaderPager
import com.tedd.teddreader.feature.reader.impl.component.foundationMovieCarouselDimAlpha
import com.tedd.teddreader.feature.reader.impl.pdf.PdfPageSurface
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

@Composable
fun ReaderRouteScreen(
    documentId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchClick: () -> Unit = {},
    onBookmarksClick: () -> Unit = {},
    onDocumentInfoClick: () -> Unit = {},
    jumpLocation: com.tedd.teddreader.core.common.model.ReaderLocation? = null,
    onJumpLocationConsumed: () -> Unit = {},
    viewModel: ReaderViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var goToPageText by rememberSaveable(documentId, uiState.pageIndex.current) {
        mutableStateOf((uiState.pageIndex.current + 1).toString())
    }
    val committedBrightnessPercent = ((1f - uiState.brightnessOverlayAlpha).coerceIn(0.2f, 1f) * 100f).roundToInt()
    var brightnessDraft by rememberSaveable(documentId, committedBrightnessPercent) {
        mutableStateOf(committedBrightnessPercent.toFloat())
    }
    val committedFontSize = uiState.style.fontSizeSp.roundToInt()
    var fontSizeDraft by rememberSaveable(documentId, committedFontSize) {
        mutableStateOf(committedFontSize.toFloat())
    }
    val committedLineHeightPercent = (uiState.style.lineHeightMultiplier * 100f).roundToInt()
    var lineHeightPercentDraft by rememberSaveable(documentId, committedLineHeightPercent) {
        mutableStateOf(committedLineHeightPercent.toFloat())
    }
    val committedAutoScrollSpeed = uiState.autoScrollConfig.speed.roundToInt().coerceIn(1, 10)
    var autoScrollSpeedDraft by rememberSaveable(documentId, committedAutoScrollSpeed) {
        mutableStateOf(committedAutoScrollSpeed.toFloat())
    }
    var bottomSliderValue by rememberSaveable(documentId, uiState.pageIndex.current, uiState.pageIndex.total) {
        mutableStateOf(uiState.pageIndex.current.toFloat())
    }
    var isActionMenuExpanded by rememberSaveable(documentId) { mutableStateOf(false) }
    val activeSheetScrollState = key(uiState.activeSheet) { rememberScrollState() }
    val modalSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(documentId) {
        viewModel.openDocument(documentId)
    }

    LaunchedEffect(jumpLocation) {
        jumpLocation?.let { location ->
            viewModel.moveToLocation(location)
            onJumpLocationConsumed()
        }
    }

    ReaderScreen(
        uiState = uiState,
        onBack = onBack,
        onToggleControls = viewModel::toggleControls,
        onPreviousPage = viewModel::movePrevious,
        onNextPage = viewModel::moveNext,
        onFavoriteToggle = viewModel::toggleFavorite,
        onActionSelected = { action ->
            when (action) {
                ReaderMenuAction.Search -> onSearchClick()
                ReaderMenuAction.ToggleSavedPlace -> viewModel.toggleSavedPlace()
                ReaderMenuAction.SavedPlaces -> onBookmarksClick()
                ReaderMenuAction.TableOfContents -> viewModel.showSheet(ReaderOptionSheet.TableOfContents)
                ReaderMenuAction.GoToPage -> viewModel.showSheet(ReaderOptionSheet.GoToPage)
                ReaderMenuAction.DocumentInfo -> onDocumentInfoClick()
                ReaderMenuAction.ViewOptions -> viewModel.showSheet(ReaderOptionSheet.View)
                ReaderMenuAction.FontOptions -> viewModel.showSheet(ReaderOptionSheet.Font)
                ReaderMenuAction.ThemeOptions -> viewModel.showSheet(ReaderOptionSheet.Theme)
                ReaderMenuAction.PageTurnOptions -> viewModel.showSheet(ReaderOptionSheet.PageTurn)
                ReaderMenuAction.AutoScrollOptions -> viewModel.showSheet(ReaderOptionSheet.AutoScroll)
                ReaderMenuAction.BrightnessOptions -> viewModel.showSheet(ReaderOptionSheet.Brightness)
                ReaderMenuAction.ControlOptions -> viewModel.showSheet(ReaderOptionSheet.Controls)
            }
        },
        onOptionSheetSelected = viewModel::showSheet,
        onDismissSheet = viewModel::dismissSheet,
        onKeepScreenOnChange = viewModel::updateKeepScreenOn,
        onFullscreenChange = viewModel::updateFullscreen,
        onShowProgressChange = viewModel::updateShowProgress,
        onFontSizeChange = viewModel::updateFontSize,
        onLineHeightChange = viewModel::updateLineHeight,
        onFontFamilyChange = viewModel::updateFontFamily,
        onThemeModeChange = viewModel::updateThemeMode,
        onPageTurnModeChange = viewModel::updatePageTurnMode,
        onPageAnimationChange = viewModel::updatePageAnimation,
        onAutoScrollEnabledChange = viewModel::updateAutoScrollEnabled,
        onAutoScrollModeChange = viewModel::updateAutoScrollMode,
        onAutoScrollSpeedChange = viewModel::updateAutoScrollSpeed,
        onAutoScrollToggle = { viewModel.updateAutoScrollEnabled(!uiState.autoScrollConfig.enabled) },
        onGoToPage = viewModel::moveToPage,
        onMoveToLocation = viewModel::moveToLocation,
        onBrightnessOverlayAlphaChange = viewModel::updateBrightnessOverlayAlpha,
        onViewportSizeChanged = viewModel::updateViewportSize,
        goToPageText = goToPageText,
        onGoToPageTextChange = { value -> goToPageText = value.filter(Char::isDigit).take(6) },
        brightnessDraft = brightnessDraft,
        onBrightnessDraftChange = { brightnessDraft = it.roundToInt().toFloat() },
        fontSizeDraft = fontSizeDraft,
        onFontSizeDraftChange = { fontSizeDraft = it.roundToInt().toFloat() },
        lineHeightPercentDraft = lineHeightPercentDraft,
        onLineHeightPercentDraftChange = {
            lineHeightPercentDraft = (it / LineHeightStepPercent).roundToInt() * LineHeightStepPercent
        },
        autoScrollSpeedDraft = autoScrollSpeedDraft,
        onAutoScrollSpeedDraftChange = { autoScrollSpeedDraft = it.roundToInt().toFloat() },
        bottomSliderValue = bottomSliderValue,
        onBottomSliderValueChange = { bottomSliderValue = it },
        isActionMenuExpanded = isActionMenuExpanded,
        onActionMenuExpandedChange = { isActionMenuExpanded = it },
        activeSheetScrollState = activeSheetScrollState,
        modalSheetState = modalSheetState,
        modifier = modifier,
    )
}

@Composable
fun ReaderScreen(
    uiState: ReaderUiState,
    onBack: () -> Unit,
    onToggleControls: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onActionSelected: (ReaderMenuAction) -> Unit,
    onOptionSheetSelected: (ReaderOptionSheet) -> Unit,
    onDismissSheet: () -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    onShowProgressChange: (Boolean) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onFontFamilyChange: (String?) -> Unit,
    onThemeModeChange: (ReaderThemeMode) -> Unit,
    onPageTurnModeChange: (PageTurnMode) -> Unit,
    onPageAnimationChange: (PageAnimation) -> Unit,
    onAutoScrollEnabledChange: (Boolean) -> Unit,
    onAutoScrollModeChange: (AutoScrollMode) -> Unit,
    onAutoScrollSpeedChange: (Float) -> Unit,
    onAutoScrollToggle: () -> Unit,
    onGoToPage: (Int) -> Unit,
    onMoveToLocation: (ReaderLocation) -> Unit,
    onBrightnessOverlayAlphaChange: (Float) -> Unit,
    onViewportSizeChanged: (Int, Int) -> Unit,
    goToPageText: String,
    onGoToPageTextChange: (String) -> Unit,
    brightnessDraft: Float,
    onBrightnessDraftChange: (Float) -> Unit,
    fontSizeDraft: Float,
    onFontSizeDraftChange: (Float) -> Unit,
    lineHeightPercentDraft: Float,
    onLineHeightPercentDraftChange: (Float) -> Unit,
    autoScrollSpeedDraft: Float,
    onAutoScrollSpeedDraftChange: (Float) -> Unit,
    bottomSliderValue: Float,
    onBottomSliderValueChange: (Float) -> Unit,
    isActionMenuExpanded: Boolean,
    onActionMenuExpandedChange: (Boolean) -> Unit,
    activeSheetScrollState: ScrollState,
    modalSheetState: SheetState,
    modifier: Modifier = Modifier,
) {
    when {
        uiState.isLoading -> TeddFullScreenLoadingIndicator(
            modifier = modifier,
            message = "Opening document",
        )

        uiState.errorMessage != null -> ReaderError(
            message = uiState.errorMessage,
            modifier = modifier,
        )

        else -> ReaderContent(
            uiState = uiState,
            onBack = onBack,
            onToggleControls = onToggleControls,
            onPreviousPage = onPreviousPage,
            onNextPage = onNextPage,
            onFavoriteToggle = onFavoriteToggle,
            onActionSelected = onActionSelected,
            onOptionSheetSelected = onOptionSheetSelected,
            onDismissSheet = onDismissSheet,
            onKeepScreenOnChange = onKeepScreenOnChange,
            onFullscreenChange = onFullscreenChange,
            onShowProgressChange = onShowProgressChange,
            onFontSizeChange = onFontSizeChange,
            onLineHeightChange = onLineHeightChange,
            onFontFamilyChange = onFontFamilyChange,
            onThemeModeChange = onThemeModeChange,
            onPageTurnModeChange = onPageTurnModeChange,
            onPageAnimationChange = onPageAnimationChange,
            onAutoScrollEnabledChange = onAutoScrollEnabledChange,
            onAutoScrollModeChange = onAutoScrollModeChange,
            onAutoScrollSpeedChange = onAutoScrollSpeedChange,
            onAutoScrollToggle = onAutoScrollToggle,
            onGoToPage = onGoToPage,
            onMoveToLocation = onMoveToLocation,
            onBrightnessOverlayAlphaChange = onBrightnessOverlayAlphaChange,
            onViewportSizeChanged = onViewportSizeChanged,
            goToPageText = goToPageText,
            onGoToPageTextChange = onGoToPageTextChange,
            brightnessDraft = brightnessDraft,
            onBrightnessDraftChange = onBrightnessDraftChange,
            fontSizeDraft = fontSizeDraft,
            onFontSizeDraftChange = onFontSizeDraftChange,
            lineHeightPercentDraft = lineHeightPercentDraft,
            onLineHeightPercentDraftChange = onLineHeightPercentDraftChange,
            autoScrollSpeedDraft = autoScrollSpeedDraft,
            onAutoScrollSpeedDraftChange = onAutoScrollSpeedDraftChange,
            bottomSliderValue = bottomSliderValue,
            onBottomSliderValueChange = onBottomSliderValueChange,
            isActionMenuExpanded = isActionMenuExpanded,
            onActionMenuExpandedChange = onActionMenuExpandedChange,
            activeSheetScrollState = activeSheetScrollState,
            modalSheetState = modalSheetState,
            modifier = modifier,
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReaderContent(
    uiState: ReaderUiState,
    onBack: () -> Unit,
    onToggleControls: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onActionSelected: (ReaderMenuAction) -> Unit,
    onOptionSheetSelected: (ReaderOptionSheet) -> Unit,
    onDismissSheet: () -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    onShowProgressChange: (Boolean) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onFontFamilyChange: (String?) -> Unit,
    onThemeModeChange: (ReaderThemeMode) -> Unit,
    onPageTurnModeChange: (PageTurnMode) -> Unit,
    onPageAnimationChange: (PageAnimation) -> Unit,
    onAutoScrollEnabledChange: (Boolean) -> Unit,
    onAutoScrollModeChange: (AutoScrollMode) -> Unit,
    onAutoScrollSpeedChange: (Float) -> Unit,
    onAutoScrollToggle: () -> Unit,
    onGoToPage: (Int) -> Unit,
    onMoveToLocation: (com.tedd.teddreader.core.common.model.ReaderLocation) -> Unit,
    onBrightnessOverlayAlphaChange: (Float) -> Unit,
    onViewportSizeChanged: (Int, Int) -> Unit,
    goToPageText: String,
    onGoToPageTextChange: (String) -> Unit,
    brightnessDraft: Float,
    onBrightnessDraftChange: (Float) -> Unit,
    fontSizeDraft: Float,
    onFontSizeDraftChange: (Float) -> Unit,
    lineHeightPercentDraft: Float,
    onLineHeightPercentDraftChange: (Float) -> Unit,
    autoScrollSpeedDraft: Float,
    onAutoScrollSpeedDraftChange: (Float) -> Unit,
    bottomSliderValue: Float,
    onBottomSliderValueChange: (Float) -> Unit,
    isActionMenuExpanded: Boolean,
    onActionMenuExpandedChange: (Boolean) -> Unit,
    activeSheetScrollState: ScrollState,
    modalSheetState: SheetState,
    modifier: Modifier = Modifier,
) {
    ReaderSystemBarsEffect(
        visible = uiState.isControlsVisible || uiState.activeSheet != null,
        backgroundColor = uiState.style.readerColors().background,
        keepScreenOn = uiState.keepScreenOn,
    )
    val movieTransitionProgress = remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(uiState.style.readerColors().background),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (uiState.pageAnimation == PageAnimation.MOVIE_CAROUSEL) {
                        val dimAlpha = foundationMovieCarouselDimAlpha(movieTransitionProgress.floatValue)
                        if (dimAlpha > 0f) drawRect(Color.Black.copy(alpha = dimAlpha))
                    }
                },
        ) {
            val paneCount = readerPaneCount(maxWidth.value)
            var pageMoveRequest by remember { mutableStateOf<ReaderPageMoveRequest?>(null) }
            var pageMoveRequestId by remember { mutableIntStateOf(0) }
            val requestPageMove: (ReaderPageMovement) -> Unit = { movement ->
                if (pageMoveRequest == null) {
                    pageMoveRequestId += 1
                    pageMoveRequest = ReaderPageMoveRequest(pageMoveRequestId, movement)
                }
            }
            val movePrevious: () -> Unit = {
                val target = (uiState.pageIndex.current - paneCount).coerceAtLeast(0)
                if (target != uiState.pageIndex.current) onGoToPage(target)
            }
            val moveNext: () -> Unit = {
                readerNextPage(
                    currentPage = uiState.pageIndex.current,
                    totalPages = uiState.pageIndex.total,
                    paneCount = paneCount,
                )?.let(onGoToPage)
            }
            val manualMovePrevious: () -> Unit = {
                if (uiState.autoScrollConfig.enabled) onAutoScrollEnabledChange(false)
                movePrevious()
            }
            val manualMoveNext: () -> Unit = {
                if (uiState.autoScrollConfig.enabled) onAutoScrollEnabledChange(false)
                moveNext()
            }

            ReaderAutoScrollEffect(
                uiState = uiState,
                paneCount = paneCount,
                onNextPage = moveNext,
                onStop = { onAutoScrollEnabledChange(false) },
            )

            val toggleControls = {
                if (uiState.autoScrollConfig.enabled) {
                    onAutoScrollEnabledChange(false)
                }
                onToggleControls()
            }

            ReaderPager(
                pageKey = uiState.pageIndex.current,
                pageCount = uiState.pageIndex.total,
                pageStep = paneCount,
                pageTurnMode = uiState.pageTurnMode,
                pageAnimation = uiState.pageAnimation,
                pageMoveRequest = pageMoveRequest,
                onPageMoveRequestConsumed = { requestId ->
                    if (pageMoveRequest?.id == requestId) pageMoveRequest = null
                },
                onPreviousPage = manualMovePrevious,
                onNextPage = manualMoveNext,
                onToggleControls = toggleControls,
                onMovieTransitionProgressChanged = { movieTransitionProgress.floatValue = it },
                modifier = Modifier.readerControlsDragObserver(
                    controlsVisible = uiState.isControlsVisible,
                    onToggleControls = toggleControls,
                ),
            ) { page ->
                if (paneCount == 1) {
                    ReaderPagePane(
                        uiState = uiState,
                        page = page,
                        onViewportSizeChanged = onViewportSizeChanged,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(uiState.style.readerColors().background),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DefaultTeddReaderSpacing.medium),
                    ) {
                        ReaderPagePane(
                            uiState = uiState,
                            page = page,
                            onViewportSizeChanged = onViewportSizeChanged,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        ReaderPagePane(
                            uiState = uiState,
                            page = page + 1,
                            onViewportSizeChanged = onViewportSizeChanged,
                            reportViewportSize = false,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }

            if (uiState.isControlsVisible) {
                ReaderTopControls(
                    title = uiState.documentTitle,
                    style = uiState.style,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding(),
                    titleLabel = "Reading",
                    navigationIcon = {
                        TeddIconButton(onClick = onBack, contentDescription = "Back") {
                            Icon(imageVector = TeddIcons.Back, contentDescription = null)
                        }
                    },
                    actions = {
                        TeddIconButton(
                            onClick = onFavoriteToggle,
                            contentDescription = if (uiState.isFavorite) {
                                "Remove from favorites"
                            } else {
                                "Add to favorites"
                            },
                        ) {
                            Icon(
                                imageVector = if (uiState.isFavorite) {
                                    TeddIcons.BookmarkFilled
                                } else {
                                    TeddIcons.BookmarkOutline
                                },
                                contentDescription = null,
                                tint = if (uiState.isFavorite) {
                                    uiState.style.readerColors().bookmark
                                } else {
                                    uiState.style.readerColors().controlsContent
                                },
                            )
                        }
                        ReaderActionMenu(
                            expanded = isActionMenuExpanded,
                            isCurrentPageSaved = uiState.isCurrentPageSaved,
                            onExpandedChange = onActionMenuExpandedChange,
                            onActionSelected = onActionSelected,
                        )
                    },
                )
                ReaderBottomActionBar(
                    pageIndex = uiState.pageIndex,
                    style = uiState.style,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                    isAutoScrollEnabled = uiState.autoScrollConfig.enabled,
                    showProgress = uiState.showProgress,
                    onAutoScrollToggle = onAutoScrollToggle,
                    onPageSelected = { page ->
                        onAutoScrollEnabledChange(false)
                        onGoToPage(page)
                    },
                    onPreviousPage = {
                        onAutoScrollEnabledChange(false)
                        requestPageMove(ReaderPageMovement.Previous)
                    },
                    onNextPage = {
                        onAutoScrollEnabledChange(false)
                        requestPageMove(ReaderPageMovement.Next)
                    },
                    sliderValue = bottomSliderValue,
                    onSliderValueChange = onBottomSliderValueChange,
                    canGoPrevious = uiState.pageIndex.current > 0,
                    canGoNext = readerNextPage(
                        currentPage = uiState.pageIndex.current,
                        totalPages = uiState.pageIndex.total,
                        paneCount = paneCount,
                    ) != null,
                )
            }

            if (uiState.brightnessOverlayAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = uiState.brightnessOverlayAlpha)),
                )
            }
        }

        uiState.activeSheet?.let { sheet ->
            ReaderActiveSheet(
                sheet = sheet,
                uiState = uiState,
                onDismiss = onDismissSheet,
                onKeepScreenOnChange = onKeepScreenOnChange,
                onFullscreenChange = onFullscreenChange,
                onShowProgressChange = onShowProgressChange,
                onFontSizeChange = onFontSizeChange,
                onLineHeightChange = onLineHeightChange,
                onFontFamilyChange = onFontFamilyChange,
                onThemeModeChange = onThemeModeChange,
                onPageTurnModeChange = onPageTurnModeChange,
                onPageAnimationChange = onPageAnimationChange,
                onAutoScrollEnabledChange = onAutoScrollEnabledChange,
                onAutoScrollModeChange = onAutoScrollModeChange,
                onAutoScrollSpeedChange = onAutoScrollSpeedChange,
                onGoToPage = onGoToPage,
                onMoveToLocation = onMoveToLocation,
                onBrightnessOverlayAlphaChange = onBrightnessOverlayAlphaChange,
                goToPageText = goToPageText,
                onGoToPageTextChange = onGoToPageTextChange,
                brightnessDraft = brightnessDraft,
                onBrightnessDraftChange = onBrightnessDraftChange,
                fontSizeDraft = fontSizeDraft,
                onFontSizeDraftChange = onFontSizeDraftChange,
                lineHeightPercentDraft = lineHeightPercentDraft,
                onLineHeightPercentDraftChange = onLineHeightPercentDraftChange,
                autoScrollSpeedDraft = autoScrollSpeedDraft,
                onAutoScrollSpeedDraftChange = onAutoScrollSpeedDraftChange,
                scrollState = activeSheetScrollState,
                sheetState = modalSheetState,
            )
        }
    }
}

@Composable
private fun ReaderPagePane(
    uiState: ReaderUiState,
    page: Int,
    onViewportSizeChanged: (Int, Int) -> Unit,
    reportViewportSize: Boolean = true,
    windowInsets: WindowInsets = readerSystemBarsInsets().only(WindowInsetsSides.Vertical),
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.readerMargin,
        vertical = DefaultTeddReaderSpacing.large,
    ),
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    if (page !in 0 until uiState.pageIndex.total) {
        Box(modifier = modifier.fillMaxSize().background(uiState.style.readerColors().background))
        return
    }

    if (uiState.isPdfMode) {
        PdfPageSurface(
            pageIndex = uiState.pageIndexFor(page),
            modifier = modifier.fillMaxSize(),
            documentUri = uiState.pageSlot(page)?.documentUri ?: uiState.documentUri,
            zoom = uiState.pdfZoom,
            rotationDegrees = uiState.pdfRotationDegrees,
        )
    } else {
        val viewportModifier = if (reportViewportSize) {
            Modifier.onSizeChanged { size ->
                onViewportSizeChanged(
                    density.pxToSp(size.width.toFloat()).value.roundToInt().coerceAtLeast(1),
                    density.pxToSp(size.height.toFloat()).value.roundToInt().coerceAtLeast(1),
                )
            }
        } else {
            Modifier
        }
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(uiState.style.readerColors().background)
                .windowInsetsPadding(windowInsets)
                .padding(contentPadding)
                .then(viewportModifier),
        ) {
            ReaderPageSurface(
                text = uiState.pageTextFor(page),
                style = uiState.style,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(0.dp),
            )
        }
    }
}

@Composable
private fun ReaderAutoScrollEffect(
    uiState: ReaderUiState,
    paneCount: Int,
    onNextPage: () -> Unit,
    onStop: () -> Unit,
) {
    val config = uiState.autoScrollConfig
    LaunchedEffect(
        config.enabled,
        config.mode,
        config.speed,
        uiState.pageIndex.current,
        uiState.pageIndex.total,
        paneCount,
    ) {
        if (!config.enabled) return@LaunchedEffect
        if (readerNextPage(uiState.pageIndex.current, uiState.pageIndex.total, paneCount) == null) {
            onStop()
            return@LaunchedEffect
        }

        val speed = config.speed.coerceAtLeast(0.1f)
        val delayMillis = (1_000L / speed).toLong().coerceAtLeast(100L)
        delay(delayMillis)
        onNextPage()
    }
}

@Composable
private fun ReaderActiveSheet(
    sheet: ReaderOptionSheet,
    uiState: ReaderUiState,
    onDismiss: () -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    onShowProgressChange: (Boolean) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onFontFamilyChange: (String?) -> Unit,
    onThemeModeChange: (ReaderThemeMode) -> Unit,
    onPageTurnModeChange: (PageTurnMode) -> Unit,
    onPageAnimationChange: (PageAnimation) -> Unit,
    onAutoScrollEnabledChange: (Boolean) -> Unit,
    onAutoScrollModeChange: (AutoScrollMode) -> Unit,
    onAutoScrollSpeedChange: (Float) -> Unit,
    onGoToPage: (Int) -> Unit,
    onMoveToLocation: (ReaderLocation) -> Unit,
    onBrightnessOverlayAlphaChange: (Float) -> Unit,
    goToPageText: String,
    onGoToPageTextChange: (String) -> Unit,
    brightnessDraft: Float,
    onBrightnessDraftChange: (Float) -> Unit,
    fontSizeDraft: Float,
    onFontSizeDraftChange: (Float) -> Unit,
    lineHeightPercentDraft: Float,
    onLineHeightPercentDraftChange: (Float) -> Unit,
    autoScrollSpeedDraft: Float,
    onAutoScrollSpeedDraftChange: (Float) -> Unit,
    scrollState: ScrollState,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
) {
    TeddModalBottomSheet(
        title = sheet.title,
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
        ) {
            if (uiState.isSavingSettings) {
                TeddLoadingIndicator(message = "Saving reader settings")
            }
            when (sheet) {
                ReaderOptionSheet.TableOfContents -> TableOfContentsSheet(
                    uiState = uiState,
                    onLocationClick = { location ->
                        onMoveToLocation(location)
                        onDismiss()
                    },
                )
                ReaderOptionSheet.GoToPage -> GoToPageSheet(
                    uiState = uiState,
                    pageText = goToPageText,
                    onPageTextChange = onGoToPageTextChange,
                    onGoToPage = { page ->
                        onGoToPage(page)
                        onDismiss()
                    },
                )
                ReaderOptionSheet.View -> ViewOptionsSheet(
                    uiState = uiState,
                    onKeepScreenOnChange = onKeepScreenOnChange,
                    onFullscreenChange = onFullscreenChange,
                    onShowProgressChange = onShowProgressChange,
                )
                ReaderOptionSheet.Font -> FontOptionsSheet(
                    uiState = uiState,
                    fontSizeDraft = fontSizeDraft,
                    onFontSizeDraftChange = onFontSizeDraftChange,
                    lineHeightPercentDraft = lineHeightPercentDraft,
                    onLineHeightPercentDraftChange = onLineHeightPercentDraftChange,
                    onFontSizeChange = onFontSizeChange,
                    onLineHeightChange = onLineHeightChange,
                    onFontFamilyChange = onFontFamilyChange,
                )
                ReaderOptionSheet.Theme -> ThemeOptionsSheet(
                    uiState = uiState,
                    onThemeModeChange = onThemeModeChange,
                )
                ReaderOptionSheet.PageTurn -> PageTurnOptionsSheet(
                    uiState = uiState,
                    onPageTurnModeChange = onPageTurnModeChange,
                    onPageAnimationChange = onPageAnimationChange,
                )
                ReaderOptionSheet.AutoScroll -> AutoScrollOptionsSheet(
                    uiState = uiState,
                    speedDraft = autoScrollSpeedDraft,
                    onSpeedDraftChange = onAutoScrollSpeedDraftChange,
                    onEnabledChange = onAutoScrollEnabledChange,
                    onModeChange = onAutoScrollModeChange,
                    onSpeedChange = onAutoScrollSpeedChange,
                )
                ReaderOptionSheet.Brightness -> BrightnessOptionsSheet(
                    uiState = uiState,
                    brightnessDraft = brightnessDraft,
                    onBrightnessDraftChange = onBrightnessDraftChange,
                    onBrightnessOverlayAlphaChange = onBrightnessOverlayAlphaChange,
                )
                ReaderOptionSheet.Controls -> ControlOptionsSheet(
                    uiState = uiState,
                    onShowProgressChange = onShowProgressChange,
                )
            }
        }
    }
}


@Composable
private fun TableOfContentsSheet(
    uiState: ReaderUiState,
    onLocationClick: (com.tedd.teddreader.core.common.model.ReaderLocation) -> Unit,
    modifier: Modifier = Modifier,
) {
    TeddOptionGroup(title = "Contents", modifier = modifier) {
        if (uiState.outlineItems.isEmpty()) {
            Text(
                text = "No table of contents.",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
        } else {
            uiState.outlineItems.forEach { item ->
                TeddButton(
                    text = item.title,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onLocationClick(item.location) },
                )
            }
        }
    }
}

@Composable
private fun GoToPageSheet(
    uiState: ReaderUiState,
    pageText: String,
    onPageTextChange: (String) -> Unit,
    onGoToPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalPages = uiState.pageIndex.total.coerceAtLeast(1)
    val targetPage = pageText.toIntOrNull()?.coerceIn(1, totalPages)

    TeddOptionGroup(
        title = "Go to page",
        modifier = modifier,
        description = "Enter 1-$totalPages.",
    ) {
        TeddTextField(
            value = pageText,
            onValueChange = onPageTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = "Page",
            maxLines = 1,
        )
        TeddButton(
            text = "Go",
            enabled = targetPage != null,
            onClick = { targetPage?.let { onGoToPage(it - 1) } },
        )
    }
}

@Composable
private fun BrightnessOptionsSheet(
    uiState: ReaderUiState,
    brightnessDraft: Float,
    onBrightnessDraftChange: (Float) -> Unit,
    onBrightnessOverlayAlphaChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    TeddOptionGroup(
        title = "Brightness",
        modifier = modifier,
        description = "Dims reader without changing system brightness.",
    ) {
        TeddSliderRow(
            title = "Brightness",
            value = brightnessDraft,
            onValueChange = onBrightnessDraftChange,
            onValueChangeFinished = {
                onBrightnessOverlayAlphaChange((1f - brightnessDraft / 100f).coerceIn(0f, 0.8f))
            },
            valueRange = 20f..100f,
            valueLabel = "${brightnessDraft.roundToInt()}%",
            enabled = !uiState.isSavingSettings,
        )
    }
}

@Composable
private fun ViewOptionsSheet(
    uiState: ReaderUiState,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    onShowProgressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    TeddOptionGroup(title = "Display", modifier = modifier) {
        TeddSwitchRow("Keep screen on", uiState.keepScreenOn, onKeepScreenOnChange, enabled = !uiState.isSavingSettings)
        TeddSwitchRow("Fullscreen reader", uiState.fullscreen, onFullscreenChange, enabled = !uiState.isSavingSettings)
        TeddSwitchRow("Show progress", uiState.showProgress, onShowProgressChange, enabled = !uiState.isSavingSettings)
    }
}

@Composable
private fun FontOptionsSheet(
    uiState: ReaderUiState,
    fontSizeDraft: Float,
    onFontSizeDraftChange: (Float) -> Unit,
    lineHeightPercentDraft: Float,
    onLineHeightPercentDraftChange: (Float) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onFontFamilyChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewStyle = uiState.style.copy(
        fontSizeSp = fontSizeDraft,
        lineHeightMultiplier = lineHeightPercentDraft / 100f,
    )

    TeddOptionGroup(title = "Typography", modifier = modifier) {
        TeddSliderRow(
            title = "Font size",
            value = fontSizeDraft,
            onValueChange = onFontSizeDraftChange,
            onValueChangeFinished = { onFontSizeChange(fontSizeDraft) },
            valueRange = 8f..80f,
            steps = FontSizeSliderSteps,
            valueLabel = "${fontSizeDraft.roundToInt()}sp",
            enabled = !uiState.isSavingSettings,
        )
        TeddSliderRow(
            title = "Line height",
            value = lineHeightPercentDraft,
            onValueChange = onLineHeightPercentDraftChange,
            onValueChangeFinished = { onLineHeightChange(lineHeightPercentDraft / 100f) },
            valueRange = 100f..300f,
            steps = LineHeightSliderSteps,
            valueLabel = "${lineHeightPercentDraft.roundToInt()}%",
            enabled = !uiState.isSavingSettings,
        )
        TeddRadioRow(
            title = "Sans",
            selected = uiState.style.fontFamilyName == null || uiState.style.fontFamilyName == "sans",
            onClick = { onFontFamilyChange(null) },
            enabled = !uiState.isSavingSettings,
        )
        TeddRadioRow(
            title = "Serif",
            selected = uiState.style.fontFamilyName == "serif",
            onClick = { onFontFamilyChange("serif") },
            enabled = !uiState.isSavingSettings,
        )
        TeddRadioRow(
            title = "Mono",
            selected = uiState.style.fontFamilyName == "mono",
            onClick = { onFontFamilyChange("mono") },
            enabled = !uiState.isSavingSettings,
        )
        ReaderOptionPreview(
            style = previewStyle,
            title = "Typography preview",
            description = "가나다 ABC 123 · 문장 간격과 줄 높이 확인",
            previewText = "가나다 ABC 123\n문장 간격과 줄 높이 확인용 텍스트입니다.",
        )
    }
}

@Composable
private fun ThemeOptionsSheet(
    uiState: ReaderUiState,
    onThemeModeChange: (ReaderThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ReaderOptionPreview(
            style = uiState.style,
            title = "Theme preview",
            description = "가나다 ABC 123 · 배경/글자색 확인",
            previewText = "가나다 ABC 123\n눈 피로를 줄이는 색상 대비를 확인합니다.",
        )
        TeddOptionGroup(title = "Theme") {
            listOf(ReaderThemeMode.SYSTEM, ReaderThemeMode.LIGHT, ReaderThemeMode.DARK, ReaderThemeMode.SEPIA).forEach { mode ->
                TeddRadioRow(
                    title = mode.themeLabel,
                    selected = uiState.style.themeMode == mode,
                    onClick = { onThemeModeChange(mode) },
                    enabled = !uiState.isSavingSettings,
                )
            }
        }
    }
}

@Composable
private fun PageTurnOptionsSheet(
    uiState: ReaderUiState,
    onPageTurnModeChange: (PageTurnMode) -> Unit,
    onPageAnimationChange: (PageAnimation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        TeddOptionGroup(title = "Page mode") {
            PageTurnMode.entries.forEach { mode ->
                TeddRadioRow(
                    title = mode.pageTurnLabel,
                    selected = uiState.pageTurnMode == mode,
                    onClick = { onPageTurnModeChange(mode) },
                    enabled = !uiState.isSavingSettings,
                )
            }
        }
        TeddOptionGroup(title = "Animation") {
            PageAnimation.entries.filterNot { it == PageAnimation.SHEET_FLIP }.forEach { animation ->
                TeddRadioRow(
                    title = animation.pageAnimationLabel,
                    selected = uiState.pageAnimation == animation,
                    onClick = { onPageAnimationChange(animation) },
                    enabled = !uiState.isSavingSettings,
                )
            }
        }
    }
}

@Composable
private fun AutoScrollOptionsSheet(
    uiState: ReaderUiState,
    speedDraft: Float,
    onSpeedDraftChange: (Float) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (AutoScrollMode) -> Unit,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    TeddOptionGroup(title = "Auto-scroll", modifier = modifier) {
        TeddSwitchRow("Enabled", uiState.autoScrollConfig.enabled, onEnabledChange, enabled = !uiState.isSavingSettings)
        AutoScrollMode.entries.forEach { mode ->
            TeddRadioRow(
                title = mode.autoScrollLabel,
                selected = uiState.autoScrollConfig.mode == mode,
                onClick = { onModeChange(mode) },
                enabled = !uiState.isSavingSettings,
            )
        }
        TeddSliderRow(
            title = "Speed",
            value = speedDraft,
            onValueChange = onSpeedDraftChange,
            onValueChangeFinished = { onSpeedChange(speedDraft) },
            valueRange = 1f..10f,
            steps = SpeedSliderSteps,
            valueLabel = formatSpeedLabel(speedDraft),
            enabled = !uiState.isSavingSettings,
        )
    }
}

@Composable
private fun ControlOptionsSheet(
    uiState: ReaderUiState,
    onShowProgressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    TeddOptionGroup(
        title = "Bottom bar",
        modifier = modifier,
        description = "Choose what stays visible while you read.",
    ) {
        TeddSwitchRow("Show page progress", uiState.showProgress, onShowProgressChange, enabled = !uiState.isSavingSettings)
    }
}

private const val FontSizeSliderSteps = 71
private const val LineHeightStepPercent = 5f
private const val LineHeightSliderSteps = 39
private const val SpeedSliderSteps = 8

private fun formatSpeedLabel(speed: Float): String = "${speed.roundToInt()}x"

internal fun readerPaneCount(widthDp: Float): Int =
    if (widthDp >= TwoPaneMinWidthDp) 2 else 1

internal fun readerNextPage(currentPage: Int, totalPages: Int, paneCount: Int): Int? =
    (currentPage + paneCount.coerceAtLeast(1)).takeIf { it in 0 until totalPages }

private const val ReaderPaneMinWidthDp = 280f
private const val ReaderPaneGutterDp = 16f
private const val TwoPaneMinWidthDp = ReaderPaneMinWidthDp * 2f + ReaderPaneGutterDp

internal fun ReaderUiState.pageSlot(page: Int): ReaderPageUi? =
    pageSlots.firstOrNull { it.page == page } ?: when {
        previousPage?.page == page -> previousPage
        currentPage.page == page -> currentPage
        nextPage?.page == page -> nextPage
        else -> null
    }

private fun ReaderUiState.pageIndexFor(page: Int): PageIndex {
    if (pageIndex.total <= 0) return pageIndex
    return PageIndex(
        current = page.coerceIn(0, pageIndex.total - 1),
        total = pageIndex.total,
    )
}

private fun ReaderUiState.pageTextFor(page: Int): String {
    val slotText = pageSlot(page)?.text.orEmpty()
    if (slotText.isNotBlank()) return slotText
    return if (page == pageIndex.current) {
        pageText.ifBlank { "No page text." }
    } else {
        ""
    }
}

private val ReaderThemeMode.themeLabel: String
    get() = when (this) {
        ReaderThemeMode.SYSTEM -> "Follow system"
        ReaderThemeMode.LIGHT -> "Light"
        ReaderThemeMode.DARK -> "Dark"
        ReaderThemeMode.SEPIA -> "Sepia"
        ReaderThemeMode.CUSTOM -> "Custom"
    }

private val PageTurnMode.pageTurnLabel: String
    get() = when (this) {
        PageTurnMode.HORIZONTAL -> "Horizontal pages"
        PageTurnMode.VERTICAL -> "Vertical pages"
        PageTurnMode.CONTINUOUS -> "Continuous scroll"
    }

private val PageAnimation.pageAnimationLabel: String
    get() = when (this) {
        PageAnimation.NONE -> "No animation"
        PageAnimation.SLIDE -> "Slide"
        PageAnimation.FADE -> "Fade"
        PageAnimation.SCROLL -> "Scroll"
        PageAnimation.BOOK_CURL -> "Book curl"
        PageAnimation.SHEET_FLIP -> "Slide"
        PageAnimation.FLUID_PAGER -> "Fluid pager"
        PageAnimation.CURL_PAGER -> "Curl pager"
        PageAnimation.CIRCLE_REVEAL -> "Circle reveal"
        PageAnimation.MOVIE_CAROUSEL -> "Movie carousel"
        PageAnimation.PAGE_FLIP -> "Page flip"
    }

private val AutoScrollMode.autoScrollLabel: String
    get() = when (this) {
        AutoScrollMode.PIXEL -> "Smooth scroll"
        AutoScrollMode.PAGE -> "Page by page"
    }

private val ReaderOptionSheet.title: String
    get() = when (this) {
        ReaderOptionSheet.TableOfContents -> "Table of contents"
        ReaderOptionSheet.GoToPage -> "Go to page"
        ReaderOptionSheet.View -> "Display"
        ReaderOptionSheet.Font -> "Typography"
        ReaderOptionSheet.Theme -> "Theme"
        ReaderOptionSheet.PageTurn -> "Page movement"
        ReaderOptionSheet.AutoScroll -> "Auto-scroll"
        ReaderOptionSheet.Brightness -> "Brightness"
        ReaderOptionSheet.Controls -> "Bottom bar"
    }

@Composable
private fun ReaderError(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message)
    }
}

private fun previewReaderUiState(
    documentTitle: String = "Preview Book",
    pageText: String = "가나다 ABC 123\n\n문장 간격과 줄 높이 확인용 텍스트입니다.",
    style: ReaderStyle = ReaderStyle(),
    isControlsVisible: Boolean = true,
    activeSheet: ReaderOptionSheet? = null,
): ReaderUiState = ReaderUiState(
    documentTitle = documentTitle,
    pageText = pageText,
    pageIndex = PageIndex(current = 11, total = 240),
    style = style,
    isControlsVisible = isControlsVisible,
    activeSheet = activeSheet,
    isLoading = false,
)

private fun Modifier.readerControlsDragObserver(
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
): Modifier = composed {
    val latestControlsVisible by rememberUpdatedState(controlsVisible)
    val latestOnToggleControls by rememberUpdatedState(onToggleControls)

    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val controlsVisibleAtStart = latestControlsVisible
            var dragDistance = Offset.Zero
            var toggled = false

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                dragDistance += change.positionChange()
                if (!toggled && controlsVisibleAtStart && dragDistance.getDistance() > viewConfiguration.touchSlop) {
                    latestOnToggleControls()
                    toggled = true
                }
            }
        }
    }
}

@Preview(widthDp = 360, heightDp = 720)
@Composable
private fun ReaderScreenCompactPreview() {
    TeddReaderTheme {
        ReaderScreen(
            uiState = previewReaderUiState(
                documentTitle = "아주 긴 책 제목 Preview Book Title",
                pageText = "가나다 ABC 123\n\n문장 간격과 줄 높이 확인용 텍스트입니다.",
            ),
            onBack = {},
            onToggleControls = {},
            onPreviousPage = {},
            onNextPage = {},
            onFavoriteToggle = {},
            onActionSelected = {},
            onOptionSheetSelected = {},
            onDismissSheet = {},
            onKeepScreenOnChange = {},
            onFullscreenChange = {},
            onShowProgressChange = {},
            onFontSizeChange = {},
            onLineHeightChange = {},
            onFontFamilyChange = {},
            onThemeModeChange = {},
            onPageTurnModeChange = {},
            onPageAnimationChange = {},
            onAutoScrollEnabledChange = {},
            onAutoScrollModeChange = {},
            onAutoScrollSpeedChange = {},
            onAutoScrollToggle = {},
            onGoToPage = {},
            onMoveToLocation = {},
            onBrightnessOverlayAlphaChange = {},
            onViewportSizeChanged = { _, _ -> },
            goToPageText = "1",
            onGoToPageTextChange = {},
            brightnessDraft = 100f,
            onBrightnessDraftChange = {},
            fontSizeDraft = 18f,
            onFontSizeDraftChange = {},
            lineHeightPercentDraft = 150f,
            onLineHeightPercentDraftChange = {},
            autoScrollSpeedDraft = 1f,
            onAutoScrollSpeedDraftChange = {},
            bottomSliderValue = 0f,
            onBottomSliderValueChange = {},
            isActionMenuExpanded = false,
            onActionMenuExpandedChange = {},
            activeSheetScrollState = rememberScrollState(),
            modalSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        )
    }
}

@Preview(widthDp = 360, heightDp = 720)
@Composable
private fun ReaderScreenHiddenChromePreview() {
    TeddReaderTheme {
        ReaderScreen(
            uiState = previewReaderUiState(isControlsVisible = false),
            onBack = {},
            onToggleControls = {},
            onPreviousPage = {},
            onNextPage = {},
            onFavoriteToggle = {},
            onActionSelected = {},
            onOptionSheetSelected = {},
            onDismissSheet = {},
            onKeepScreenOnChange = {},
            onFullscreenChange = {},
            onShowProgressChange = {},
            onFontSizeChange = {},
            onLineHeightChange = {},
            onFontFamilyChange = {},
            onThemeModeChange = {},
            onPageTurnModeChange = {},
            onPageAnimationChange = {},
            onAutoScrollEnabledChange = {},
            onAutoScrollModeChange = {},
            onAutoScrollSpeedChange = {},
            onAutoScrollToggle = {},
            onGoToPage = {},
            onMoveToLocation = {},
            onBrightnessOverlayAlphaChange = {},
            onViewportSizeChanged = { _, _ -> },
            goToPageText = "1",
            onGoToPageTextChange = {},
            brightnessDraft = 100f,
            onBrightnessDraftChange = {},
            fontSizeDraft = 18f,
            onFontSizeDraftChange = {},
            lineHeightPercentDraft = 150f,
            onLineHeightPercentDraftChange = {},
            autoScrollSpeedDraft = 1f,
            onAutoScrollSpeedDraftChange = {},
            bottomSliderValue = 0f,
            onBottomSliderValueChange = {},
            isActionMenuExpanded = false,
            onActionMenuExpandedChange = {},
            activeSheetScrollState = rememberScrollState(),
            modalSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        )
    }
}

@Preview(widthDp = 360, heightDp = 720)
@Composable
private fun ReaderScreenDarkPreview() {
    TeddReaderTheme(darkTheme = true) {
        ReaderScreen(
            uiState = previewReaderUiState(style = darkReaderStyle()),
            onBack = {},
            onToggleControls = {},
            onPreviousPage = {},
            onNextPage = {},
            onFavoriteToggle = {},
            onActionSelected = {},
            onOptionSheetSelected = {},
            onDismissSheet = {},
            onKeepScreenOnChange = {},
            onFullscreenChange = {},
            onShowProgressChange = {},
            onFontSizeChange = {},
            onLineHeightChange = {},
            onFontFamilyChange = {},
            onThemeModeChange = {},
            onPageTurnModeChange = {},
            onPageAnimationChange = {},
            onAutoScrollEnabledChange = {},
            onAutoScrollModeChange = {},
            onAutoScrollSpeedChange = {},
            onAutoScrollToggle = {},
            onGoToPage = {},
            onMoveToLocation = {},
            onBrightnessOverlayAlphaChange = {},
            onViewportSizeChanged = { _, _ -> },
            goToPageText = "1",
            onGoToPageTextChange = {},
            brightnessDraft = 100f,
            onBrightnessDraftChange = {},
            fontSizeDraft = 18f,
            onFontSizeDraftChange = {},
            lineHeightPercentDraft = 150f,
            onLineHeightPercentDraftChange = {},
            autoScrollSpeedDraft = 1f,
            onAutoScrollSpeedDraftChange = {},
            bottomSliderValue = 0f,
            onBottomSliderValueChange = {},
            isActionMenuExpanded = false,
            onActionMenuExpandedChange = {},
            activeSheetScrollState = rememberScrollState(),
            modalSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        )
    }
}

@Preview
@Composable
private fun ReaderScreenPreview() {
    TeddReaderTheme {
        ReaderScreen(
            uiState = ReaderUiState(
                documentTitle = "Preview Book",
                pageText = "Reader page preview\n\nTap the page to show or hide controls.",
                pageIndex = PageIndex(current = 4, total = 20),
                style = ReaderStyle(),
                isLoading = false,
            ),
            onBack = {},
            onToggleControls = {},
            onPreviousPage = {},
            onNextPage = {},
            onFavoriteToggle = {},
            onActionSelected = {},
            onOptionSheetSelected = {},
            onDismissSheet = {},
            onKeepScreenOnChange = {},
            onFullscreenChange = {},
            onShowProgressChange = {},
            onFontSizeChange = {},
            onLineHeightChange = {},
            onFontFamilyChange = {},
            onThemeModeChange = {},
            onPageTurnModeChange = {},
            onPageAnimationChange = {},
            onAutoScrollEnabledChange = {},
            onAutoScrollModeChange = {},
            onAutoScrollSpeedChange = {},
            onAutoScrollToggle = {},
        onGoToPage = {},
        onMoveToLocation = {},
        onBrightnessOverlayAlphaChange = {},
            onViewportSizeChanged = { _, _ -> },
            goToPageText = "1",
            onGoToPageTextChange = {},
            brightnessDraft = 100f,
            onBrightnessDraftChange = {},
            fontSizeDraft = 18f,
            onFontSizeDraftChange = {},
            lineHeightPercentDraft = 150f,
            onLineHeightPercentDraftChange = {},
            autoScrollSpeedDraft = 1f,
            onAutoScrollSpeedDraftChange = {},
            bottomSliderValue = 0f,
            onBottomSliderValueChange = {},
            isActionMenuExpanded = false,
            onActionMenuExpandedChange = {},
            activeSheetScrollState = rememberScrollState(),
            modalSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        )
    }
}

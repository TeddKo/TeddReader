@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.tedd.teddreader.feature.reader.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.DocumentFormat
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
import com.tedd.teddreader.core.designsystem.teddReaderMotion
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
import com.tedd.teddreader.core.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.tedd.teddreader.core.ui.system.ReaderSystemBarsEffect
import com.tedd.teddreader.core.ui.system.rememberDisplayFold
import com.tedd.teddreader.feature.reader.impl.component.ReaderActionMenu
import com.tedd.teddreader.feature.reader.impl.component.ReaderBottomActionBar
import com.tedd.teddreader.feature.reader.impl.component.ReaderPageMoveRequest
import com.tedd.teddreader.feature.reader.impl.component.ReaderPageMovement
import com.tedd.teddreader.feature.reader.impl.component.ReaderPager
import com.tedd.teddreader.feature.reader.impl.component.ReaderStatusFooter
import com.tedd.teddreader.feature.reader.impl.component.foundationMovieCarouselDimAlpha
import com.tedd.teddreader.feature.reader.impl.image.ImagePageSurface
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
    val committedAutoScrollSpeed = uiState.autoScrollConfig.speed.roundToHundredths()
    var autoScrollSpeedDraft by rememberSaveable(documentId, committedAutoScrollSpeed) {
        mutableStateOf(committedAutoScrollSpeed)
    }
    var bottomSliderValue by rememberSaveable(documentId) {
        mutableStateOf(uiState.pageIndex.current.toFloat())
    }
    var isActionMenuExpanded by rememberSaveable(documentId) { mutableStateOf(false) }
    val activeSheetScrollState = key(uiState.activeSheet) { rememberScrollState() }
    val modalSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(documentId) {
        viewModel.openDocument(documentId)
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.stopAutoScroll() }
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
        onAutoScrollSpeedDraftChange = { autoScrollSpeedDraft = it.roundToHundredths() },
        bottomSliderValue = bottomSliderValue,
        onBottomSliderValueChange = { bottomSliderValue = it },
        isActionMenuExpanded = isActionMenuExpanded,
        onActionMenuExpandedChange = { isActionMenuExpanded = it },
        activeSheetScrollState = activeSheetScrollState,
        modalSheetState = modalSheetState,
        batteryPercent = rememberReaderBatteryPercent(),
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
    batteryPercent: Int? = null,
    modifier: Modifier = Modifier,
) {
    when {
        uiState.isLoading -> TeddFullScreenLoadingIndicator(
            modifier = modifier,
            message = stringResource(Res.string.opening_document),
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
            batteryPercent = batteryPercent,
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
    batteryPercent: Int?,
    modifier: Modifier = Modifier,
) {
    ReaderSystemBarsEffect(
        visible = uiState.isControlsVisible || uiState.activeSheet != null,
        backgroundColor = if (uiState.isControlsVisible) {
            uiState.style.readerColors().controls
        } else {
            uiState.style.readerColors().background
        },
        keepScreenOn = uiState.keepScreenOn,
    )
    val movieTransitionProgress = remember { mutableFloatStateOf(0f) }
    val textCommittedFontSize = uiState.style.fontSizeSp.roundToInt()
    var textGestureScale by remember { mutableFloatStateOf(1f) }
    var isTransformGestureActive by remember { mutableStateOf(false) }
    var pdfZoom by rememberSaveable(uiState.documentUri) { mutableFloatStateOf(1f) }
    var pdfPan by remember(uiState.documentUri, uiState.pageIndex.current) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val motion = teddReaderMotion()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(uiState.style.readerColors().background),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
        ) {
            val density = LocalDensity.current
            val systemBarsInsets = readerSystemBarsInsets()
            val displayFold = rememberDisplayFold()
            val paneCount = readerPaneCount(maxWidth.value, maxHeight.value, displayFold)
            val spreadLeftWeight = readerSpreadLeftWeight(maxWidth.value, displayFold)
            val spreadGutter = readerSpreadGutterDp(displayFold, ReaderPaneGutterDp).dp
            val pdfTransform = ReaderPdfTransform(zoom = pdfZoom, pan = pdfPan)
            val contentTransformModifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .graphicsLayer {
                    if (uiState.isVisualMode) {
                        scaleX = pdfTransform.zoom
                        scaleY = pdfTransform.zoom
                        translationX = pdfTransform.pan.x
                        translationY = pdfTransform.pan.y
                    } else {
                        scaleX = textGestureScale
                        scaleY = textGestureScale
                        translationX = 0f
                        translationY = 0f
                    }
                }
            val actionBarPageIndex = readerSpreadPageIndex(
                currentPage = uiState.pageIndex.current,
                totalPages = uiState.pageIndex.total,
                paneCount = paneCount,
            )
            LaunchedEffect(actionBarPageIndex.current, actionBarPageIndex.total) {
                onBottomSliderValueChange(actionBarPageIndex.current.toFloat())
            }
            val actionBarSliderValue = bottomSliderValue
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
            val effectiveAutoScrollMode = readerEffectiveAutoScrollMode(
                mode = uiState.autoScrollConfig.mode,
                isVisualMode = uiState.isVisualMode,
            )
            val autoScrollLineHeightPx = with(density) {
                (uiState.style.fontSizeSp * uiState.style.lineHeightMultiplier).sp.toPx().coerceAtLeast(1f)
            }

            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { viewportSize = it }
                        .drawWithContent {
                            drawContent()
                            if (uiState.pageAnimation == PageAnimation.MOVIE_CAROUSEL) {
                                val dimAlpha = foundationMovieCarouselDimAlpha(movieTransitionProgress.floatValue)
                                if (dimAlpha > 0f) drawRect(Color.Black.copy(alpha = dimAlpha))
                            }
                        },
                ) {
                    ReaderAutoScrollEffect(
                        uiState = uiState,
                        paneCount = paneCount,
                        effectiveMode = effectiveAutoScrollMode,
                        pageAnimation = uiState.pageAnimation,
                        onRequestPageMove = requestPageMove,
                        onStop = { onAutoScrollEnabledChange(false) },
                    )

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
                        onPreviousPage = movePrevious,
                        onNextPage = moveNext,
                        onPageSelected = onGoToPage,
                        onToggleControls = onToggleControls,
                        onDoubleTap = if (uiState.isVisualMode) {
                            { position ->
                                if (uiState.autoScrollConfig.enabled) onAutoScrollEnabledChange(false)
                                val next = readerDoubleTapVisualTransform(
                                    current = pdfTransform,
                                    tapPosition = position,
                                    viewportSize = viewportSize,
                                )
                                pdfZoom = next.zoom
                                pdfPan = next.pan
                            }
                        } else {
                            null
                        },
                        isAutoScrollEnabled = uiState.autoScrollConfig.enabled,
                        effectiveAutoScrollMode = effectiveAutoScrollMode,
                        autoScrollSpeed = uiState.autoScrollConfig.speed,
                        autoScrollLineHeightPx = autoScrollLineHeightPx,
                        autoScrollDensity = density.density,
                        onAutoScrollStop = { onAutoScrollEnabledChange(false) },
                        onMovieTransitionProgressChanged = { movieTransitionProgress.floatValue = it },
                        paneCount = paneCount,
                        spreadGutter = spreadGutter,
                        spreadLeftWeight = spreadLeftWeight,
                        spreadModifier = contentTransformModifier,
                        paneContent = { page, paneModifier ->
                            ReaderPagePane(
                                uiState = uiState,
                                page = page,
                                onViewportSizeChanged = onViewportSizeChanged,
                                reportViewportSize = page == uiState.pageIndex.current,
                                windowInsets = systemBarsInsets.only(WindowInsetsSides.Top),
                                modifier = paneModifier,
                            )
                        },
                        modifier = Modifier
                            .readerPinchZoomGesture(
                                enabled = uiState.pageIndex.total > 0,
                                viewportSize = viewportSize,
                                isVisualMode = uiState.isVisualMode,
                                textStartFontSizeSp = textCommittedFontSize,
                                pdfTransform = pdfTransform,
                                isAutoScrollEnabled = uiState.autoScrollConfig.enabled,
                                onAutoScrollEnabledChange = onAutoScrollEnabledChange,
                                onGestureActiveChange = { isTransformGestureActive = it },
                                onTextGestureScaleChange = { textGestureScale = it },
                                onTextFontSizeCommit = { onFontSizeChange(it.toFloat()) },
                                onPdfTransformChange = { next ->
                                    pdfZoom = next.zoom
                                    pdfPan = next.pan
                                },
                            )
                            .readerControlsDragObserver(
                                controlsVisible = uiState.isControlsVisible,
                                gestureBlocked = isTransformGestureActive,
                                onToggleControls = onToggleControls,
                            ),
                    ) { page ->
                        if (paneCount == 1) {
                            ReaderPagePane(
                                uiState = uiState,
                                page = page,
                                onViewportSizeChanged = onViewportSizeChanged,
                                windowInsets = systemBarsInsets.only(WindowInsetsSides.Top),
                                modifier = contentTransformModifier,
                            )
                        } else {
                            Row(
                                modifier = contentTransformModifier
                                    .background(uiState.style.readerColors().background),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spreadGutter),
                            ) {
                                ReaderPagePane(
                                    uiState = uiState,
                                    page = page,
                                    onViewportSizeChanged = onViewportSizeChanged,
                                    windowInsets = systemBarsInsets.only(WindowInsetsSides.Top),
                                    modifier = Modifier.weight(spreadLeftWeight).fillMaxHeight(),
                                )
                                ReaderPagePane(
                                    uiState = uiState,
                                    page = page + 1,
                                    onViewportSizeChanged = onViewportSizeChanged,
                                    reportViewportSize = false,
                                    windowInsets = systemBarsInsets.only(WindowInsetsSides.Top),
                                    modifier = Modifier.weight(1f - spreadLeftWeight).fillMaxHeight(),
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                    ) {
                        AnimatedVisibility(
                            visible = uiState.isControlsVisible,
                            enter = fadeIn(tween(motion.mediumDurationMs)) +
                                slideInVertically(tween(motion.mediumDurationMs)) { -it / 4 },
                            exit = fadeOut(tween(motion.shortDurationMs)) +
                                slideOutVertically(tween(motion.shortDurationMs)) { -it / 4 },
                        ) {
                            ReaderTopControls(
                                title = uiState.documentTitle,
                                style = uiState.style,
                                windowInsets = systemBarsInsets.only(WindowInsetsSides.Top),
                                titleLabel = stringResource(Res.string.reader_reading_label),
                                navigationIcon = {
                                    TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                                        Icon(imageVector = TeddIcons.Back, contentDescription = null)
                                    }
                                },
                                actions = {
                                    TeddIconButton(
                                        onClick = onFavoriteToggle,
                                        contentDescription = if (uiState.isFavorite) {
                                            stringResource(Res.string.remove_from_favorites)
                                        } else {
                                            stringResource(Res.string.add_to_favorites)
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
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    ) {
                        AnimatedVisibility(
                            visible = uiState.isControlsVisible,
                            enter = fadeIn(tween(motion.mediumDurationMs)) +
                                slideInVertically(tween(motion.mediumDurationMs)) { it / 4 },
                            exit = fadeOut(tween(motion.shortDurationMs)) +
                                slideOutVertically(tween(motion.shortDurationMs)) { it / 4 },
                        ) {
                            ReaderBottomActionBar(
                                pageIndex = actionBarPageIndex,
                                style = uiState.style,
                                isAutoScrollEnabled = uiState.autoScrollConfig.enabled,
                                showProgress = uiState.showProgress,
                                onAutoScrollToggle = onAutoScrollToggle,
                                onPageSelected = { page ->
                                    onGoToPage(
                                        readerSpreadAnchorPage(
                                            selectedSpread = page,
                                            totalPages = uiState.pageIndex.total,
                                            paneCount = paneCount,
                                        ),
                                    )
                                },
                                onPreviousPage = {
                                    requestPageMove(ReaderPageMovement.Previous)
                                },
                                onNextPage = {
                                    requestPageMove(ReaderPageMovement.Next)
                                },
                                sliderValue = actionBarSliderValue,
                                onSliderValueChange = onBottomSliderValueChange,
                                canGoPrevious = uiState.pageIndex.current > 0,
                                canGoNext = readerNextPage(
                                    currentPage = uiState.pageIndex.current,
                                    totalPages = uiState.pageIndex.total,
                                    paneCount = paneCount,
                                ) != null,
                            )
                        }
                    }
                }

                ReaderStatusFooter(
                    title = uiState.documentTitle,
                    readProgressPercent = readerReadProgressPercent(actionBarPageIndex),
                    batteryPercent = batteryPercent,
                    style = uiState.style,
                    windowInsets = systemBarsInsets.only(WindowInsetsSides.Bottom),
                )
            }
        }

        AnimatedVisibility(
            visible = uiState.brightnessOverlayAlpha > 0f,
            enter = fadeIn(tween(motion.shortDurationMs)),
            exit = fadeOut(tween(motion.shortDurationMs)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = uiState.brightnessOverlayAlpha)),
            )
        }

        uiState.activeSheet?.let { sheet ->
            ReaderActiveSheet(
                sheet = sheet,
                uiState = uiState,
                onDismiss = onDismissSheet,
                onKeepScreenOnChange = onKeepScreenOnChange,
                onFullscreenChange = onFullscreenChange,
                onShowProgressChange = onShowProgressChange,
                pdfZoom = pdfZoom,
                onPdfZoomChange = { nextZoom ->
                    val snappedZoom = nextZoom
                        .coerceIn(ReaderPdfZoomRange.start, ReaderPdfZoomRange.endInclusive)
                    val clampedTransform = readerClampedPdfTransform(
                        zoom = snappedZoom,
                        pan = pdfPan,
                        viewportSize = viewportSize,
                    )
                    pdfZoom = clampedTransform.zoom
                    pdfPan = clampedTransform.pan
                },
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
        horizontal = 12.dp,
        vertical = DefaultTeddReaderSpacing.small,
    ),
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    if (page !in 0 until uiState.pageIndex.total) {
        Box(modifier = modifier.fillMaxSize().background(uiState.style.readerColors().background))
        return
    }

    when {
        uiState.isPdfMode -> PdfPageSurface(
            pageIndex = uiState.pageIndexFor(page),
            modifier = modifier.fillMaxSize(),
            documentUri = uiState.pageSlot(page)?.documentUri ?: uiState.documentUri,
            rotationDegrees = uiState.pdfRotationDegrees,
        )

        uiState.isImageMode -> ImagePageSurface(
            page = page,
            imageBytes = uiState.visualPageImages[page],
            sourceUri = uiState.documentUri.takeIf { uiState.documentFormat == DocumentFormat.IMAGE },
            isFailed = page in uiState.failedVisualPages,
            modifier = modifier.fillMaxSize(),
        )

        else -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(uiState.style.readerColors().background)
                    .windowInsetsPadding(windowInsets)
                    .padding(contentPadding)
                    .run {
                        if (!reportViewportSize) {
                            this
                        } else {
                            onSizeChanged { size ->
                                onViewportSizeChanged(
                                    density.pxToSp(size.width.toFloat()).value.roundToInt().coerceAtLeast(1),
                                    density.pxToSp(size.height.toFloat()).value.roundToInt().coerceAtLeast(1),
                                )
                            }
                        }
                    },
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
}

@Composable
private fun ReaderAutoScrollEffect(
    uiState: ReaderUiState,
    paneCount: Int,
    effectiveMode: AutoScrollMode,
    pageAnimation: PageAnimation,
    onRequestPageMove: (ReaderPageMovement) -> Unit,
    onStop: () -> Unit,
) {
    val config = uiState.autoScrollConfig
    LaunchedEffect(
        config.enabled,
        effectiveMode,
        pageAnimation,
        config.speed,
        uiState.pageIndex.current,
        uiState.pageIndex.total,
        paneCount,
    ) {
        if (!config.enabled) return@LaunchedEffect
        val movement = readerAutoScrollPageMovement(effectiveMode, pageAnimation) ?: return@LaunchedEffect
        if (readerNextPage(uiState.pageIndex.current, uiState.pageIndex.total, paneCount) == null) {
            onStop()
            return@LaunchedEffect
        }

        delay(autoScrollPageDelayMillis(config.speed))
        onRequestPageMove(movement)
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
    pdfZoom: Float,
    onPdfZoomChange: (Float) -> Unit,
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
        title = sheet.title(),
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        contentPadding = PaddingValues(bottom = DefaultTeddReaderSpacing.large),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
        ) {
            if (uiState.isSavingSettings) {
                TeddLoadingIndicator(message = stringResource(Res.string.saving_reader_settings))
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
                    pdfZoom = pdfZoom,
                    onPdfZoomChange = onPdfZoomChange,
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
    TeddOptionGroup(title = null, modifier = modifier) {
        if (uiState.outlineItems.isEmpty()) {
            Text(
                text = stringResource(Res.string.no_table_of_contents),
                modifier = Modifier.padding(horizontal = DefaultTeddReaderSpacing.medium),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
        } else {
            uiState.outlineItems.forEachIndexed { index, item ->
                TeddButton(
                    text = item.displayTitle(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = DefaultTeddReaderSpacing.medium,
                            end = DefaultTeddReaderSpacing.medium,
                            bottom = if (index == uiState.outlineItems.lastIndex) 0.dp else DefaultTeddReaderSpacing.small,
                        ),
                    onClick = { onLocationClick(item.location) },
                )
            }
        }
    }
}

private val legacyPageOutlineTitlePattern = Regex("^Page \\d+$")
private val legacySectionOutlineTitlePattern = Regex("^Section \\d+$")

@Composable
private fun ReaderOutlineItem.displayTitle(): String = when {
    legacyPageOutlineTitlePattern.matches(title) -> location.displayLabel()
    legacySectionOutlineTitlePattern.matches(title) -> {
        val sectionNumber = legacySectionOutlineTitlePattern.find(title)
            ?.value
            ?.substringAfter("Section ")
            .orEmpty()
        stringResource(Res.string.outline_section_title, sectionNumber)
    }
    else -> title
}

@Composable
private fun com.tedd.teddreader.core.common.model.ReaderLocation.displayLabel(): String = when (this) {
    is ReaderLocation.PdfPage -> stringResource(Res.string.reader_location_page, pageIndex + 1)
    is ReaderLocation.TextOffset -> stringResource(Res.string.reader_location_text_position, offset + 1)
    is ReaderLocation.EpubOffset -> stringResource(Res.string.reader_location_epub_section, spineIndex + 1)
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
        title = null,
        modifier = modifier,
        description = stringResource(Res.string.go_to_page_description, totalPages),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DefaultTeddReaderSpacing.medium),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DefaultTeddReaderSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeddTextField(
                value = pageText,
                onValueChange = onPageTextChange,
                modifier = Modifier.weight(1f),
                label = stringResource(Res.string.page),
                maxLines = 1,
            )
            TeddButton(
                text = stringResource(Res.string.go),
                modifier = Modifier.heightIn(min = 56.dp),
                enabled = targetPage != null,
                onClick = { targetPage?.let { onGoToPage(it - 1) } },
            )
        }
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
        title = null,
        modifier = modifier,
        description = stringResource(Res.string.brightness_description),
    ) {
        TeddSliderRow(
            title = stringResource(Res.string.brightness),
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
    pdfZoom: Float,
    onPdfZoomChange: (Float) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    onShowProgressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    TeddOptionGroup(title = stringResource(Res.string.display), modifier = modifier) {
        TeddSwitchRow(stringResource(Res.string.keep_screen_on), uiState.keepScreenOn, onKeepScreenOnChange, enabled = !uiState.isSavingSettings)
        TeddSwitchRow(stringResource(Res.string.fullscreen_reader), uiState.fullscreen, onFullscreenChange, enabled = !uiState.isSavingSettings)
        TeddSwitchRow(stringResource(Res.string.show_progress), uiState.showProgress, onShowProgressChange, enabled = !uiState.isSavingSettings)
        if (uiState.isVisualMode) {
            TeddSliderRow(
                title = stringResource(Res.string.visual_zoom),
                value = pdfZoom * 100f,
                onValueChange = { onPdfZoomChange(it / 100f) },
                onValueChangeFinished = null,
                valueRange = 100f..400f,
                steps = 11,
                valueLabel = "${(pdfZoom * 100f).roundToInt()}%",
                enabled = !uiState.isSavingSettings,
            )
        }
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

    TeddOptionGroup(title = null, modifier = modifier) {
        TeddSliderRow(
            title = stringResource(Res.string.font_size),
            value = fontSizeDraft,
            onValueChange = onFontSizeDraftChange,
            onValueChangeFinished = { onFontSizeChange(fontSizeDraft) },
            valueRange = ReaderPinchFontSizeRange,
            steps = FontSizeSliderSteps,
            valueLabel = "${fontSizeDraft.roundToInt()}sp",
            enabled = !uiState.isSavingSettings,
        )
        TeddSliderRow(
            title = stringResource(Res.string.line_height),
            value = lineHeightPercentDraft,
            onValueChange = onLineHeightPercentDraftChange,
            onValueChangeFinished = { onLineHeightChange(lineHeightPercentDraft / 100f) },
            valueRange = 100f..300f,
            steps = LineHeightSliderSteps,
            valueLabel = "${lineHeightPercentDraft.roundToInt()}%",
            enabled = !uiState.isSavingSettings,
        )
        TeddRadioRow(
            title = stringResource(Res.string.font_family_sans),
            selected = uiState.style.fontFamilyName == null || uiState.style.fontFamilyName == "sans",
            onClick = { onFontFamilyChange(null) },
            enabled = !uiState.isSavingSettings,
        )
        TeddRadioRow(
            title = stringResource(Res.string.font_family_serif),
            selected = uiState.style.fontFamilyName == "serif",
            onClick = { onFontFamilyChange("serif") },
            enabled = !uiState.isSavingSettings,
        )
        TeddRadioRow(
            title = stringResource(Res.string.font_family_mono),
            selected = uiState.style.fontFamilyName == "mono",
            onClick = { onFontFamilyChange("mono") },
            enabled = !uiState.isSavingSettings,
        )
        ReaderOptionPreview(
            modifier = Modifier.padding(horizontal = DefaultTeddReaderSpacing.medium),
            style = previewStyle,
            title = stringResource(Res.string.typography_preview_title),
            description = stringResource(Res.string.typography_preview_description),
            previewText = stringResource(Res.string.typography_preview_text),
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
            modifier = Modifier.padding(horizontal = DefaultTeddReaderSpacing.medium),
            style = uiState.style,
            title = stringResource(Res.string.theme_preview_title),
            description = stringResource(Res.string.theme_preview_description),
            previewText = stringResource(Res.string.theme_preview_text),
        )
        TeddOptionGroup(title = null) {
            listOf(ReaderThemeMode.SYSTEM, ReaderThemeMode.LIGHT, ReaderThemeMode.DARK, ReaderThemeMode.SEPIA).forEach { mode ->
                TeddRadioRow(
                    title = mode.themeLabel(),
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
        TeddOptionGroup(title = stringResource(Res.string.page_direction)) {
            readerPageTurnModeOptions.forEach { mode ->
                TeddRadioRow(
                    title = mode.pageTurnLabel(),
                    selected = uiState.pageTurnMode == mode,
                    onClick = { onPageTurnModeChange(mode) },
                    enabled = !uiState.isSavingSettings,
                )
            }
        }
        TeddOptionGroup(title = stringResource(Res.string.default_transition)) {
            readerDefaultTransitionOptions.forEach { animation ->
                TeddRadioRow(
                    title = animation.pageAnimationLabel(),
                    selected = uiState.pageAnimation == animation,
                    onClick = { onPageAnimationChange(animation) },
                    enabled = !uiState.isSavingSettings,
                )
            }
        }
        TeddOptionGroup(title = stringResource(Res.string.page_effects)) {
            readerPageEffectOptions.forEach { animation ->
                TeddRadioRow(
                    title = animation.pageAnimationLabel(),
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
    TeddOptionGroup(title = null, modifier = modifier) {
        TeddSwitchRow(stringResource(Res.string.enabled), uiState.autoScrollConfig.enabled, onEnabledChange, enabled = !uiState.isSavingSettings)
        AutoScrollMode.entries.forEach { mode ->
            val isModeEnabled = !uiState.isSavingSettings && !(uiState.isVisualMode && mode == AutoScrollMode.LINE)
            TeddRadioRow(
                title = mode.autoScrollLabel(),
                selected = uiState.autoScrollConfig.mode == mode,
                onClick = { onModeChange(mode) },
                enabled = isModeEnabled,
            )
        }
        TeddSliderRow(
            title = stringResource(Res.string.speed),
            value = speedDraft,
            onValueChange = onSpeedDraftChange,
            onValueChangeFinished = { onSpeedChange(speedDraft) },
            valueRange = AutoScrollConfig.MIN_SPEED..AutoScrollConfig.MAX_SPEED,
            steps = SpeedSliderSteps,
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
        title = null,
        modifier = modifier,
        description = stringResource(Res.string.bottom_bar_description),
    ) {
        TeddSwitchRow(stringResource(Res.string.show_page_progress), uiState.showProgress, onShowProgressChange, enabled = !uiState.isSavingSettings)
    }
}

private const val FontSizeSliderSteps = 71
private const val LineHeightStepPercent = 5f
private const val LineHeightSliderSteps = 39
private const val SpeedSliderSteps = 98

internal val readerPageTurnModeOptions: List<PageTurnMode> = listOf(
    PageTurnMode.HORIZONTAL,
    PageTurnMode.VERTICAL,
)

internal val readerDefaultTransitionOptions: List<PageAnimation> = listOf(
    PageAnimation.NONE,
    PageAnimation.SLIDE,
    PageAnimation.FADE,
    PageAnimation.SCROLL,
)

internal val readerPageEffectOptions: List<PageAnimation> = listOf(
    PageAnimation.FLUID_PAGER,
    PageAnimation.CURL_PAGER,
    PageAnimation.CIRCLE_REVEAL,
    PageAnimation.MOVIE_CAROUSEL,
    PageAnimation.PAGE_FLIP,
)

internal val readerPageAnimationOptions: List<PageAnimation> =
    readerDefaultTransitionOptions + readerPageEffectOptions

private fun Float.roundToHundredths(): Float =
    (this * 100f).roundToInt().div(100f).coerceIn(AutoScrollConfig.MIN_SPEED, AutoScrollConfig.MAX_SPEED)

internal fun readerNextPage(currentPage: Int, totalPages: Int, paneCount: Int): Int? =
    (currentPage + paneCount.coerceAtLeast(1)).takeIf { it in 0 until totalPages }

internal fun readerEffectiveAutoScrollMode(mode: AutoScrollMode, isVisualMode: Boolean): AutoScrollMode =
    if (isVisualMode && mode == AutoScrollMode.LINE) AutoScrollMode.PAGE else mode

internal fun readerAutoScrollPageMovement(
    mode: AutoScrollMode,
    pageAnimation: PageAnimation,
): ReaderPageMovement? = when (mode) {
    AutoScrollMode.PAGE -> ReaderPageMovement.Next
    AutoScrollMode.PIXEL,
    AutoScrollMode.LINE,
        -> when (pageAnimation) {
            PageAnimation.SCROLL,
            PageAnimation.SLIDE,
            PageAnimation.SHEET_FLIP,
            PageAnimation.FLUID_PAGER,
            PageAnimation.CIRCLE_REVEAL,
            PageAnimation.MOVIE_CAROUSEL,
            PageAnimation.PAGE_FLIP,
            PageAnimation.BOOK_CURL,
            PageAnimation.CURL_PAGER,
                -> null

            PageAnimation.NONE,
            PageAnimation.FADE,
                -> ReaderPageMovement.Next
        }
}

internal fun autoScrollPageDelayMillis(speed: Float): Long =
    (1_000f / AutoScrollConfig.clampSpeed(speed)).toLong()

internal fun autoScrollDistancePx(speed: Float, density: Float, elapsedMillis: Long): Float =
    200f *
        density.coerceAtLeast(0f) *
        AutoScrollConfig.clampSpeed(speed) *
        (elapsedMillis.coerceAtLeast(0L) / 1_000f)

internal fun autoScrollLineDelayMillis(lineHeightPx: Float, pixelsPerSecond: Float): Long =
    ((lineHeightPx.coerceAtLeast(0f) / pixelsPerSecond.coerceAtLeast(1f)) * 1_000f).toLong()

internal fun readerSpreadPageIndex(currentPage: Int, totalPages: Int, paneCount: Int): PageIndex {
    if (totalPages <= 0) return PageIndex(current = 0, total = 0)
    val step = paneCount.coerceAtLeast(1)
    val clampedCurrentPage = currentPage.coerceIn(0, totalPages - 1)
    return PageIndex(
        current = clampedCurrentPage / step,
        total = ((totalPages - 1) / step) + 1,
    )
}

internal fun readerSpreadAnchorPage(selectedSpread: Int, totalPages: Int, paneCount: Int): Int {
    if (totalPages <= 0) return 0
    val step = paneCount.coerceAtLeast(1)
    val maxSpreadIndex = ((totalPages - 1) / step)
    val boundedSpread = selectedSpread.coerceIn(0, maxSpreadIndex)
    return (boundedSpread * step).coerceAtMost(totalPages - 1)
}

internal fun readerReadProgressPercent(pageIndex: PageIndex): Int =
    if (pageIndex.total == 0) {
        0
    } else {
        (((pageIndex.current + 1f) / pageIndex.total) * 100f).roundToInt().coerceIn(0, 100)
    }


internal fun ReaderUiState.pageSlot(page: Int): ReaderPageUi? =
    pageSlots.firstOrNull { it.page == page }
        ?: documentPages.getOrNull(page)
        ?: when {
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

@Composable
private fun ReaderUiState.pageTextFor(page: Int): String {
    val slotText = pageSlot(page)?.text.orEmpty()
    if (slotText.isNotBlank()) return slotText
    return if (page == pageIndex.current) {
        pageText.ifBlank { stringResource(Res.string.no_page_text) }
    } else {
        ""
    }
}

@Composable
private fun ReaderThemeMode.themeLabel(): String = when (this) {
    ReaderThemeMode.SYSTEM -> stringResource(Res.string.follow_system)
    ReaderThemeMode.LIGHT -> stringResource(Res.string.light)
    ReaderThemeMode.DARK -> stringResource(Res.string.dark)
    ReaderThemeMode.SEPIA -> stringResource(Res.string.sepia)
    ReaderThemeMode.CUSTOM -> stringResource(Res.string.custom)
}

@Composable
private fun PageTurnMode.pageTurnLabel(): String = when (this) {
    PageTurnMode.HORIZONTAL -> stringResource(Res.string.horizontal_pages)
    PageTurnMode.VERTICAL,
    PageTurnMode.CONTINUOUS,
        -> stringResource(Res.string.vertical_pages)
}

@Composable
private fun PageAnimation.pageAnimationLabel(): String = when (this) {
    PageAnimation.NONE -> stringResource(Res.string.no_animation)
    PageAnimation.SLIDE,
    PageAnimation.SHEET_FLIP,
        -> stringResource(Res.string.animation_slide)
    PageAnimation.FADE -> stringResource(Res.string.animation_fade)
    PageAnimation.SCROLL -> stringResource(Res.string.animation_scroll)
    PageAnimation.BOOK_CURL,
    PageAnimation.CURL_PAGER,
        -> stringResource(Res.string.animation_curl_pager)
    PageAnimation.FLUID_PAGER -> stringResource(Res.string.animation_fluid_pager)
    PageAnimation.CIRCLE_REVEAL -> stringResource(Res.string.animation_circle_reveal)
    PageAnimation.MOVIE_CAROUSEL -> stringResource(Res.string.animation_movie_carousel)
    PageAnimation.PAGE_FLIP -> stringResource(Res.string.animation_page_flip)
}

@Composable
private fun AutoScrollMode.autoScrollLabel(): String = when (this) {
    AutoScrollMode.PIXEL -> stringResource(Res.string.auto_scroll_smooth_scroll)
    AutoScrollMode.LINE -> stringResource(Res.string.auto_scroll_line_text_only)
    AutoScrollMode.PAGE -> stringResource(Res.string.auto_scroll_page_by_page)
}

@Composable
private fun ReaderOptionSheet.title(): String = when (this) {
    ReaderOptionSheet.TableOfContents -> stringResource(Res.string.table_of_contents)
    ReaderOptionSheet.GoToPage -> stringResource(Res.string.go_to_page)
    ReaderOptionSheet.View -> stringResource(Res.string.sheet_view)
    ReaderOptionSheet.Font -> stringResource(Res.string.typography)
    ReaderOptionSheet.Theme -> stringResource(Res.string.theme)
    ReaderOptionSheet.PageTurn -> stringResource(Res.string.page_movement)
    ReaderOptionSheet.AutoScroll -> stringResource(Res.string.auto_scroll)
    ReaderOptionSheet.Brightness -> stringResource(Res.string.reader_option_brightness)
    ReaderOptionSheet.Controls -> stringResource(Res.string.bottom_bar)
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
    gestureBlocked: Boolean,
    onToggleControls: () -> Unit,
): Modifier = composed {
    val latestControlsVisible by rememberUpdatedState(controlsVisible)
    val latestGestureBlocked by rememberUpdatedState(gestureBlocked)
    val latestOnToggleControls by rememberUpdatedState(onToggleControls)

    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val controlsVisibleAtStart = latestControlsVisible
            var dragDistance = Offset.Zero
            var gestureHandled = false

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (latestGestureBlocked || event.changes.count { it.pressed } > 1) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                dragDistance += change.positionChange()
                if (!gestureHandled && dragDistance.getDistance() > viewConfiguration.touchSlop) {
                    if (controlsVisibleAtStart) {
                        latestOnToggleControls()
                    }
                    gestureHandled = true
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
            batteryPercent = 73,
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
            batteryPercent = 73,
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
            batteryPercent = 73,
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
            batteryPercent = 73,
        )
    }
}

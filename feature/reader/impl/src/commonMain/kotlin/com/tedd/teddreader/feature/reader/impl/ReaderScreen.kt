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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.DrawerValue
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
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
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.darkReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.readerColors
import com.tedd.teddreader.core.designsystem.toColor
import com.tedd.teddreader.core.designsystem.teddReaderMotion
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddFullScreenLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddModalBottomSheet
import com.tedd.teddreader.core.ui.component.TeddOptionGroup
import com.tedd.teddreader.core.ui.component.TeddRadioRow
import com.tedd.teddreader.core.ui.component.TeddSliderRow
import com.tedd.teddreader.core.ui.component.TeddSwitchRow
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.component.TeddTextField
import com.tedd.teddreader.core.ui.extension.pxToSp
import com.tedd.teddreader.core.ui.reader.ReaderOptionPreview
import com.tedd.teddreader.core.ui.reader.ReaderPageSurface
import com.tedd.teddreader.core.ui.reader.rememberReaderPageBreaker
import com.tedd.teddreader.core.ui.reader.loadReaderEmbeddedFontFamilies
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

/**
 * Entry point that wires [ReaderViewModel] into [ReaderScreen]. Like [ReaderScreen] and
 * [ReaderContent] below it, this composable is a pure state-and-callback pass-through to the view
 * model: it collects [ReaderUiState], forwards every user action back as a view model call or an
 * [ReaderMenuAction] branch, and holds no document data of its own.
 *
 * The `rememberSaveable` values declared here (`goToPageText`, `brightnessDraft`, `fontSizeDraft`,
 * `lineHeightPercentDraft`, `autoScrollSpeedDraft`, `bottomSliderValue`,
 * `isActionMenuExpanded`) are drafts: they hold a slider's or text field's value while the user's
 * gesture is still in progress — before it is committed back to the view model — and survive
 * configuration changes on their own, so a drag or a typed digit mid-gesture is not lost. They are
 * not the source of truth once a gesture ends; the view model's own committed state is.
 * `goToPageText`, `brightnessDraft`, `fontSizeDraft`, `lineHeightPercentDraft`, and
 * `autoScrollSpeedDraft` are each keyed on their corresponding committed value, so a committed
 * change from outside the gesture (e.g. settings restored on a different device) resets the draft
 * to match; `bottomSliderValue` is instead kept in sync by an effect inside `ReaderContent`, and
 * `isActionMenuExpanded` is plain per-document UI state with no committed counterpart at all.
 *
 * @param documentId The document to open; changing it re-triggers [ReaderViewModel.openDocument].
 * @param onBack Invoked when the user asks to leave the reader.
 * @param modifier Applied to the resulting [ReaderScreen].
 * @param onSearchClick Invoked when the user picks search from the action menu.
 * @param onBookmarksClick Invoked when the user picks saved places from the action menu.
 * @param onDocumentInfoClick Invoked when the user picks document info from the action menu.
 * @param jumpLocation A location to navigate to once, e.g. from a deep link or a saved place tap;
 * consumed via [onJumpLocationConsumed] so it does not re-fire on recomposition.
 * @param onJumpLocationConsumed Invoked once [jumpLocation] has been applied, so the caller can
 * clear it.
 * @param viewModel The reader's view model; defaults to one resolved through Koin.
 */
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
        onPageBreakerChanged = viewModel::updatePageBreaker,
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
        batteryPercent = rememberReaderBatteryPercent(),
        modifier = modifier,
    )
}

/**
 * Chooses which of the reader's three top-level states to show — a loading indicator while
 * [ReaderUiState.isLoading], an error message when [ReaderUiState.errorMessage] is set, or the real
 * [ReaderContent] otherwise — and passes every other parameter straight through unchanged. Like
 * [ReaderRouteScreen], this composable is a pure state-and-callback pass-through to the view model:
 * it holds no reader state of its own.
 *
 * @param uiState The reader's current state, as published by the view model.
 * @param onBack Invoked when the user asks to leave the reader.
 * @param onToggleControls Invoked when the user taps to show or hide the reading controls.
 * @param onPreviousPage Invoked with the number of panes to step back when the user turns back.
 * @param onNextPage Invoked with the number of panes to step forward when the user turns the page.
 * @param onFavoriteToggle Invoked when the user toggles the favorite/bookmark star.
 * @param onActionSelected Invoked with the action menu's chosen item.
 * @param onOptionSheetSelected Invoked to open one of the option bottom sheets.
 * @param onDismissSheet Invoked when the active option sheet or drawer is dismissed.
 * @param onKeepScreenOnChange Invoked when the keep-screen-on setting is toggled.
 * @param onFullscreenChange Invoked when the fullscreen setting is toggled.
 * @param onShowProgressChange Invoked when the show-progress setting is toggled.
 * @param onFontSizeChange Invoked when the font size is committed.
 * @param onLineHeightChange Invoked when the line height multiplier is committed.
 * @param onFontFamilyChange Invoked when the font family is changed; null selects the default.
 * @param onThemeModeChange Invoked when the reader theme is changed.
 * @param onPageTurnModeChange Invoked when the page-turn direction is changed.
 * @param onPageAnimationChange Invoked when the page-turn animation is changed.
 * @param onAutoScrollEnabledChange Invoked when auto-scroll is turned on or off.
 * @param onAutoScrollModeChange Invoked when the auto-scroll mode is changed.
 * @param onAutoScrollSpeedChange Invoked when the auto-scroll speed is committed.
 * @param onAutoScrollToggle Invoked from the bottom bar's auto-scroll toggle.
 * @param onGoToPage Invoked with a zero-based page index to jump to.
 * @param onMoveToLocation Invoked with a location to jump to, e.g. from the table of contents.
 * @param onBrightnessOverlayAlphaChange Invoked when the brightness overlay's alpha is committed.
 * @param onPageBreakerChanged Invoked once a pane has measured its text area and produced a page
 * breaker for the current style and viewport; defaults to a no-op.
 * @param goToPageText The "go to page" sheet's current text field value.
 * @param onGoToPageTextChange Invoked as the user types in the "go to page" field.
 * @param brightnessDraft The brightness slider's in-progress value, before it is committed.
 * @param onBrightnessDraftChange Invoked as the brightness slider is dragged.
 * @param fontSizeDraft The font size slider's in-progress value, before it is committed.
 * @param onFontSizeDraftChange Invoked as the font size slider is dragged.
 * @param lineHeightPercentDraft The line height slider's in-progress value, as a percentage.
 * @param onLineHeightPercentDraftChange Invoked as the line height slider is dragged.
 * @param autoScrollSpeedDraft The auto-scroll speed slider's in-progress value.
 * @param onAutoScrollSpeedDraftChange Invoked as the auto-scroll speed slider is dragged.
 * @param bottomSliderValue The bottom page-progress slider's current value.
 * @param onBottomSliderValueChange Invoked as the bottom page-progress slider is dragged.
 * @param isActionMenuExpanded Whether the top bar's overflow action menu is open.
 * @param onActionMenuExpandedChange Invoked when the action menu is opened or dismissed.
 * @param activeSheetScrollState Scroll state for the active option sheet's content.
 * @param batteryPercent The device's battery percentage, shown in the status footer, or null if
 * unavailable.
 * @param modifier Applied to whichever of the three states is shown.
 */
@Composable
fun ReaderScreen(
    uiState: ReaderUiState,
    onBack: () -> Unit,
    onToggleControls: () -> Unit,
    onPreviousPage: (step: Int) -> Unit,
    onNextPage: (step: Int) -> Unit,
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
    onPageBreakerChanged: (ReaderStyle, ViewportSize, ViewportSize, ReaderPageBreaker, Boolean) -> Unit = { _, _, _, _, _ -> },
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
    batteryPercent: Int? = null,
    modifier: Modifier = Modifier,
) {
    when {
        uiState.isLoading -> ReaderLoadingState(
            loadingKey = uiState.documentUri ?: uiState.documentTitle,
            style = uiState.style,
            modifier = modifier,
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
            onPageBreakerChanged = onPageBreakerChanged,
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
            batteryPercent = batteryPercent,
            modifier = modifier,
        )
    }
}

/**
 * Renders the actual reading surface once [ReaderScreen] has decided the document is ready: the
 * page pager, the top/bottom control bars, the status footer, the table-of-contents drawer, the
 * brightness overlay, and whichever option sheet is active. Like [ReaderScreen] above it, this
 * composable is a pure state-and-callback pass-through to the view model — the local `remember`s
 * here (pinch/zoom transform, drag gesture state, the page-move request queue) are transient
 * gesture bookkeeping, not a second copy of reader state.
 *
 * The local `movePrevious`/`moveNext` lambdas only forward the step size (the locally computed
 * `paneCount`) to [onPreviousPage]/[onNextPage]; they never resolve a target page index themselves.
 * The view model resolves the step against whichever pagination is live when the move actually
 * runs, so a repagination that lands between the tap and the move being processed cannot cause the
 * wrong page to be selected.
 *
 * @param uiState The reader's current state, as published by the view model.
 * @param onBack Invoked when the user asks to leave the reader.
 * @param onToggleControls Invoked when the user taps to show or hide the reading controls.
 * @param onPreviousPage Invoked with the number of panes to step back when the user turns back.
 * @param onNextPage Invoked with the number of panes to step forward when the user turns the page.
 * @param onFavoriteToggle Invoked when the user toggles the favorite/bookmark star.
 * @param onActionSelected Invoked with the action menu's chosen item.
 * @param onOptionSheetSelected Invoked to open one of the option bottom sheets.
 * @param onDismissSheet Invoked when the active option sheet or drawer is dismissed.
 * @param onKeepScreenOnChange Invoked when the keep-screen-on setting is toggled.
 * @param onFullscreenChange Invoked when the fullscreen setting is toggled.
 * @param onShowProgressChange Invoked when the show-progress setting is toggled.
 * @param onFontSizeChange Invoked when the font size is committed.
 * @param onLineHeightChange Invoked when the line height multiplier is committed.
 * @param onFontFamilyChange Invoked when the font family is changed; null selects the default.
 * @param onThemeModeChange Invoked when the reader theme is changed.
 * @param onPageTurnModeChange Invoked when the page-turn direction is changed.
 * @param onPageAnimationChange Invoked when the page-turn animation is changed.
 * @param onAutoScrollEnabledChange Invoked when auto-scroll is turned on or off.
 * @param onAutoScrollModeChange Invoked when the auto-scroll mode is changed.
 * @param onAutoScrollSpeedChange Invoked when the auto-scroll speed is committed.
 * @param onAutoScrollToggle Invoked from the bottom bar's auto-scroll toggle.
 * @param onGoToPage Invoked with a zero-based page index to jump to.
 * @param onMoveToLocation Invoked with a location to jump to, e.g. from the table of contents.
 * @param onBrightnessOverlayAlphaChange Invoked when the brightness overlay's alpha is committed.
 * @param onPageBreakerChanged Invoked once a pane has measured its text area and produced a page
 * breaker for the current style and viewport.
 * @param goToPageText The "go to page" sheet's current text field value.
 * @param onGoToPageTextChange Invoked as the user types in the "go to page" field.
 * @param brightnessDraft The brightness slider's in-progress value, before it is committed.
 * @param onBrightnessDraftChange Invoked as the brightness slider is dragged.
 * @param fontSizeDraft The font size slider's in-progress value, before it is committed.
 * @param onFontSizeDraftChange Invoked as the font size slider is dragged.
 * @param lineHeightPercentDraft The line height slider's in-progress value, as a percentage.
 * @param onLineHeightPercentDraftChange Invoked as the line height slider is dragged.
 * @param autoScrollSpeedDraft The auto-scroll speed slider's in-progress value.
 * @param onAutoScrollSpeedDraftChange Invoked as the auto-scroll speed slider is dragged.
 * @param bottomSliderValue The bottom page-progress slider's current value.
 * @param onBottomSliderValueChange Invoked as the bottom page-progress slider is dragged, and by
 * this composable's own effect whenever the underlying page index changes.
 * @param isActionMenuExpanded Whether the top bar's overflow action menu is open.
 * @param onActionMenuExpandedChange Invoked when the action menu is opened or dismissed.
 * @param activeSheetScrollState Scroll state for the active option sheet's content.
 * @param batteryPercent The device's battery percentage, shown in the status footer, or null if
 * unavailable.
 * @param modifier Applied to the root of the reading surface.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReaderContent(
    uiState: ReaderUiState,
    onBack: () -> Unit,
    onToggleControls: () -> Unit,
    onPreviousPage: (step: Int) -> Unit,
    onNextPage: (step: Int) -> Unit,
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
    onPageBreakerChanged: (ReaderStyle, ViewportSize, ViewportSize, ReaderPageBreaker, Boolean) -> Unit,
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

    val tocDrawerState = rememberDrawerState(DrawerValue.Closed)
    var tocDrawerSeenOpen by remember(uiState.documentUri) { mutableStateOf(false) }
    LaunchedEffect(uiState.activeSheet) {
        if (uiState.activeSheet == ReaderOptionSheet.TableOfContents) {
            tocDrawerState.open()
        } else {
            tocDrawerSeenOpen = false
            if (tocDrawerState.isOpen) tocDrawerState.close()
        }
    }
    LaunchedEffect(tocDrawerState.currentValue, uiState.activeSheet) {
        if (uiState.activeSheet == ReaderOptionSheet.TableOfContents) {
            if (tocDrawerState.currentValue == DrawerValue.Open) {
                tocDrawerSeenOpen = true
            } else if (tocDrawerSeenOpen && tocDrawerState.currentValue == DrawerValue.Closed) {
                tocDrawerSeenOpen = false
                onDismissSheet()
            }
        }
    }
    // The loaded families are held TOGETHER with the file map they were loaded from. produceState keeps
    // its previous value for the frame between a key change and its producer running, so a value without
    // its provenance let one frame pair the previous (empty) families with a fresh "fonts resolved" flag
    // — and that one frame was enough to measure the whole book in fallback type.
    val resolvedEmbeddedFontFamilies by produceState<Pair<Map<String, String>, Map<String, FontFamily>>?>(
        initialValue = if (uiState.style.fontFamilyName != null) uiState.embeddedFontFiles to emptyMap() else null,
        uiState.embeddedFontFiles,
        uiState.style.fontFamilyName,
    ) {
        val fontFiles = uiState.embeddedFontFiles
        value = if (uiState.style.fontFamilyName != null) {
            fontFiles to emptyMap()
        } else {
            value = null
            fontFiles to withContext(Dispatchers.Default) {
                loadReaderEmbeddedFontFamilies(fontFiles)
            }
        }
    }
    // Complete = the view-model has resolved the whole document's font set AND those exact files are
    // loaded as families here. Measuring pages is a whole-book act, so the gate must carry the whole
    // book's final fonts: any weaker gate measured the book in fallback type, and every page whose real
    // type ran longer was clipped when drawn.
    val embeddedFontResolutionComplete = uiState.areEmbeddedFontsResolved &&
        resolvedEmbeddedFontFamilies?.first == uiState.embeddedFontFiles
    val sharedEmbeddedFontFamilies = resolvedEmbeddedFontFamilies?.second.orEmpty()
    val failedResolvedFontHrefs = remember(
        uiState.embeddedFontFiles,
        embeddedFontResolutionComplete,
        sharedEmbeddedFontFamilies,
    ) {
        if (!embeddedFontResolutionComplete) emptySet()
        else uiState.embeddedFontFiles.keys - sharedEmbeddedFontFamilies.keys
    }

    ModalNavigationDrawer(
        drawerState = tocDrawerState,
        gesturesEnabled = uiState.activeSheet == ReaderOptionSheet.TableOfContents,
        drawerContent = {
            if (uiState.activeSheet == ReaderOptionSheet.TableOfContents) {
                ModalDrawerSheet {
                    TableOfContentsDrawerContent(
                        uiState = uiState,
                        onLocationClick = { location ->
                            onMoveToLocation(location)
                            onDismissSheet()
                        },
                    )
                }
            }
        },
    ) {
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
            val canRequestNextPage = !uiState.isVisualMode && !uiState.isPaginationComplete
            var pageMoveRequest by remember { mutableStateOf<ReaderPageMoveRequest?>(null) }
            var pageMoveRequestId by remember { mutableIntStateOf(0) }
            val requestPageMove: (ReaderPageMovement) -> Unit = { movement ->
                if (pageMoveRequest == null) {
                    pageMoveRequestId += 1
                    pageMoveRequest = ReaderPageMoveRequest(pageMoveRequestId, movement)
                }
            }
            val movePrevious: () -> Unit = { onPreviousPage(paneCount) }
            val moveNext: () -> Unit = { onNextPage(paneCount) }
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
                        canRequestNextPage = canRequestNextPage,
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
                                onPageBreakerChanged = onPageBreakerChanged,
                                embeddedFontFamiliesByHref = sharedEmbeddedFontFamilies,
                                embeddedFontResolutionComplete = embeddedFontResolutionComplete,
                                failedResolvedFontHrefs = failedResolvedFontHrefs,
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
                                onPageBreakerChanged = onPageBreakerChanged,
                                embeddedFontFamiliesByHref = sharedEmbeddedFontFamilies,
                                embeddedFontResolutionComplete = embeddedFontResolutionComplete,
                                failedResolvedFontHrefs = failedResolvedFontHrefs,
                                windowInsets = systemBarsInsets.only(WindowInsetsSides.Top),
                                modifier = contentTransformModifier,
                            )
                        } else {
                            Row(
                                modifier = contentTransformModifier
                                    .background(uiState.style.readerColors().background),
                                horizontalArrangement = Arrangement.spacedBy(spreadGutter),
                            ) {
                                ReaderPagePane(
                                    uiState = uiState,
                                    page = page,
                                    onPageBreakerChanged = onPageBreakerChanged,
                                    embeddedFontFamiliesByHref = sharedEmbeddedFontFamilies,
                                    embeddedFontResolutionComplete = embeddedFontResolutionComplete,
                                    failedResolvedFontHrefs = failedResolvedFontHrefs,
                                    windowInsets = systemBarsInsets.only(WindowInsetsSides.Top),
                                    modifier = Modifier.weight(spreadLeftWeight).fillMaxHeight(),
                                )
                                ReaderPagePane(
                                    uiState = uiState,
                                    page = page + 1,
                                    onPageBreakerChanged = onPageBreakerChanged,
                                    embeddedFontFamiliesByHref = sharedEmbeddedFontFamilies,
                                    embeddedFontResolutionComplete = embeddedFontResolutionComplete,
                                    failedResolvedFontHrefs = failedResolvedFontHrefs,
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
                                titleLabel = null,
                                navigationIcon = {
                                    TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                                        TeddIcon(imageVector = TeddIcons.Back, contentDescription = null)
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
                                        TeddIcon(
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
                                isPaginationComplete = uiState.isPaginationComplete,
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
                                ) != null || canRequestNextPage,
                            )
                        }
                    }
                }

                ReaderStatusFooter(
                    title = uiState.documentTitle,
                    readProgressPercent = uiState.readProgressPercent,
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

        uiState.activeSheet?.takeUnless { it == ReaderOptionSheet.TableOfContents }?.let { sheet ->
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
            )
        }
    }
}

}

/**
 * Renders one page of the document in whatever mode the current format calls for — PDF page,
 * comic/image page, EPUB page, or plain text page — for the page index given by [page]. A page
 * outside the current pagination's range (e.g. the trailing pane in a two-up spread past the last
 * page) draws only the reader background and returns immediately, rather than asking any page
 * surface to render nothing.
 *
 * For the plain-text/EPUB path, this is the only place a measured layout size ever reaches the view
 * model. The pane's own measured text area is the size pagination is keyed off of directly, instead
 * of estimating glyph advances and line counts, so pages break exactly where this pane really
 * renders them. The reporting effect below is keyed on that measured size *and* the page breaker
 * together, not the breaker alone: keying on the breaker alone let the effect for the very first
 * pass — before the pane had been measured, when the breaker still held a zero size — run again
 * after the real measurement had landed, and it then announced that stale zero-size breaker paired
 * with the real size. Every page fell back to the estimate as a result, and because the estimate
 * cannot know the line height a book's own stylesheet sets, pages were packed with half again as
 * many lines as they actually draw, clipping the rest. Keying on both together guarantees the
 * breaker reported is always the one actually built for the size reported alongside it.
 *
 * Pagination and the stored page-layout entity's `viewportWidthPx`/`viewportHeightPx` columns both
 * key on **sp**, not px, despite the column names — so the width/height sent to
 * [onPageBreakerChanged] are converted to sp before being sent. A second, separate [ViewportSize]
 * carrying the real measured pixel box is sent alongside them, used only to let the view model
 * recognise a report it has already answered (its dedupe guard), never for layout math itself.
 * Reporting fires from exactly this one effect now: two separate reports used to fire off of a
 * single resize — this effect, plus a second viewport callback from `onSizeChanged` — each launching
 * its own reload, and `Job.cancel()` could not stop the first reload's database read once it was
 * already in flight, so both landed and the stored page layout was restored from storage twice.
 * Reporting once here removes the race instead of trying to cancel it faster.
 *
 * The chapter title is deliberately not drawn as a running head above the body text here: it is the
 * heading the chapter's own text already carries, and pagination starts every section on a fresh
 * page (see `TextPageLayoutEngine.paginate`), so that heading already lands at the top of the page
 * by itself. Drawing nothing extra above the body is what keeps that rule from producing a
 * duplicated title.
 *
 * @param uiState The reader's current state; supplies the style, format, and page content this pane
 * renders.
 * @param page The zero-based page index this pane should show.
 * @param onPageBreakerChanged Invoked once this pane has measured a non-zero text area, with the
 * style and viewport sizes (in sp, then the real pixel box) the measurement was taken at, and the
 * resulting page breaker.
 * @param reportViewportSize Whether this pane is the one that should report its measured size to
 * [onPageBreakerChanged] at all; false for a spread's non-primary pane, which shares the primary
 * pane's pagination rather than measuring and reporting its own.
 * @param windowInsets Insets applied around this pane's content.
 * @param contentPadding Padding applied inside [windowInsets], around the rendered page content;
 * null resolves to the theme's `readerPageHorizontal` horizontal and `readerPageVertical`
 * vertical insets (see `TeddReaderSpacing`), the reader text-page contract `DESIGN.md` specifies.
 * @param modifier Applied to this pane's root.
 */
@Composable
private fun ReaderPagePane(
    uiState: ReaderUiState,
    page: Int,
    onPageBreakerChanged: (ReaderStyle, ViewportSize, ViewportSize, ReaderPageBreaker, Boolean) -> Unit,
    embeddedFontFamiliesByHref: Map<String, FontFamily>,
    embeddedFontResolutionComplete: Boolean,
    failedResolvedFontHrefs: Set<String>,
    reportViewportSize: Boolean = true,
    windowInsets: WindowInsets = readerSystemBarsInsets().only(WindowInsetsSides.Vertical),
    contentPadding: PaddingValues? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.readerPageHorizontal,
        vertical = spacing.readerPageVertical,
    )
    if (page !in 0 until uiState.pageIndex.total) {
        Box(modifier = modifier.fillMaxSize().background(uiState.style.readerColors().background))
        return
    }

    when {
        uiState.isPdfMode -> PdfPageSurface(
            pageIndex = uiState.pageIndexFor(page),
            modifier = modifier.fillMaxSize(),
            documentUri = uiState.pageSlot(page)?.documentUri ?: uiState.documentUri,
            style = uiState.style,
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
            var textAreaPx by remember { mutableStateOf(IntSize.Zero) }
            val currentSlot = uiState.pageSlot(page)
            val publisherPageMargins = uiState.publisherPageMargins
            val pageBreaker = rememberReaderPageBreaker(
                style = uiState.style,
                widthPx = textAreaPx.width,
                heightPx = textAreaPx.height,
                embeddedFontFamiliesByHref = embeddedFontFamiliesByHref,
                canMeasure = if (uiState.documentFormat == DocumentFormat.EPUB) {
                    // The breaker measures the whole book, so the gate carries the whole book's fonts —
                    // a page-local check let a fontless cover page open the gate for every styled page.
                    embeddedFontResolutionComplete && (
                        currentSlot == null ||
                            canMeasureEpubPage(currentSlot, uiState.style, embeddedFontFamiliesByHref, failedResolvedFontHrefs)
                        )
                } else {
                    true
                },
            )
            LaunchedEffect(pageBreaker, textAreaPx, reportViewportSize, embeddedFontResolutionComplete) {
                if (pageBreaker != null && reportViewportSize && textAreaPx.width > 0 && textAreaPx.height > 0) {
                    onPageBreakerChanged(
                        uiState.style,
                        ViewportSize(
                            widthPx = density.pxToSp(textAreaPx.width.toFloat()).value.roundToInt().coerceAtLeast(1),
                            heightPx = density.pxToSp(textAreaPx.height.toFloat()).value.roundToInt().coerceAtLeast(1),
                        ),
                        ViewportSize(widthPx = textAreaPx.width, heightPx = textAreaPx.height),
                        pageBreaker,
                        uiState.documentFormat != DocumentFormat.EPUB || embeddedFontResolutionComplete,
                    )
                }
            }

            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(
                        (currentSlot?.let { epubPageContainerBackgroundColor(it, uiState.style) }?.toColor())
                            ?: uiState.style.readerColors().background,
                    )
                    .windowInsetsPadding(windowInsets)
                    .padding(resolvedContentPadding)
                    .padding(publisherPageMargins.toPaddingValues(uiState.style.fontSizeSp, density.fontScale))
                    .run {
                        if (!reportViewportSize) {
                            this
                        } else {
                            onSizeChanged { size -> textAreaPx = size }
                        }
                    },
            ) {
                if (uiState.documentFormat == DocumentFormat.EPUB) {
                    EpubPageSurface(
                        page = uiState.pageSlot(page) ?: ReaderPageUi(page = page),
                        style = uiState.style,
                        embeddedFontFamiliesByHref = embeddedFontFamiliesByHref,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
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
}

/**
 * These margins as padding, with one em taken to be the reader's own type size.
 *
 * A book states its page margins in em, which is relative to the type it is set in — so a reader that grows
 * its font grows the margins with it, exactly as the book's own stylesheet would in a browser.
 *
 * @receiver the margins to convert.
 * @param fontSizeSp the reader's type size, which one em is worth.
 * @param fontScale the system's own text scaling, which is what turns that size in sp into one in dp.
 * @return the padding to apply around the text area.
 */
private fun ReaderPageMarginsEm.toPaddingValues(fontSizeSp: Float, fontScale: Float): PaddingValues {
    val em = fontSizeSp * fontScale
    return PaddingValues(
        start = (start * em).dp,
        top = (top * em).dp,
        end = (end * em).dp,
        bottom = (bottom * em).dp,
    )
}

/**
 * Drives auto-scroll's page-turn cadence: while auto-scroll is enabled and the current mode/
 * animation combination advances by turning whole pages rather than by pixel or line scrolling
 * (see [readerAutoScrollPageMovement]), waits [autoScrollPageDelayMillis] and then requests the next
 * page turn. Stops itself via [onStop] once there is no next page to advance to, rather than
 * requesting a move that would have nowhere to go. Restarts its wait whenever any of the effect's
 * keys change, so a speed change or a page turn already in progress does not fire two moves close
 * together.
 *
 * @param uiState The reader's current state; supplies the active auto-scroll config and page index.
 * @param paneCount How many panes are shown per page turn (1 in single-page mode, 2 in a spread).
 * @param effectiveMode The auto-scroll mode actually in effect, after [readerEffectiveAutoScrollMode]
 * has resolved any mode this format cannot support.
 * @param pageAnimation The active page-turn animation, which determines whether pixel/line
 * auto-scroll modes fall back to page-by-page turning.
 * @param onRequestPageMove Invoked with the page movement to perform when it is time to turn.
 * @param onStop Invoked to turn auto-scroll off once there is no further page to advance to.
 */
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

/**
 * Hosts whichever reader option sheet is currently open, in a shared [TeddModalBottomSheet], and
 * dispatches to the one sheet composable matching [sheet]. Also shows a saving indicator while
 * [ReaderUiState.isSavingSettings], so every sheet gets that feedback without repeating it. Every
 * parameter beyond [sheet]/[uiState]/[onDismiss] belongs to one specific sheet and is forwarded
 * straight through to it; this composable does not otherwise interpret them.
 *
 * @param sheet Which option sheet to show.
 * @param uiState The reader's current state, read by every individual sheet.
 * @param onDismiss Invoked to close the sheet.
 * @param onKeepScreenOnChange Forwarded to [ViewOptionsSheet].
 * @param onFullscreenChange Forwarded to [ViewOptionsSheet].
 * @param onShowProgressChange Forwarded to [ViewOptionsSheet] and [ControlOptionsSheet].
 * @param pdfZoom Forwarded to [ViewOptionsSheet].
 * @param onPdfZoomChange Forwarded to [ViewOptionsSheet].
 * @param onFontSizeChange Forwarded to [FontOptionsSheet].
 * @param onLineHeightChange Forwarded to [FontOptionsSheet].
 * @param onFontFamilyChange Forwarded to [FontOptionsSheet].
 * @param onThemeModeChange Forwarded to [ThemeOptionsSheet].
 * @param onPageTurnModeChange Forwarded to [PageTurnOptionsSheet].
 * @param onPageAnimationChange Forwarded to [PageTurnOptionsSheet].
 * @param onAutoScrollEnabledChange Forwarded to [AutoScrollOptionsSheet].
 * @param onAutoScrollModeChange Forwarded to [AutoScrollOptionsSheet].
 * @param onAutoScrollSpeedChange Forwarded to [AutoScrollOptionsSheet].
 * @param onGoToPage Forwarded to [GoToPageSheet].
 * @param onMoveToLocation Forwarded to [TableOfContentsSheet].
 * @param onBrightnessOverlayAlphaChange Forwarded to [BrightnessOptionsSheet].
 * @param goToPageText Forwarded to [GoToPageSheet].
 * @param onGoToPageTextChange Forwarded to [GoToPageSheet].
 * @param brightnessDraft Forwarded to [BrightnessOptionsSheet].
 * @param onBrightnessDraftChange Forwarded to [BrightnessOptionsSheet].
 * @param fontSizeDraft Forwarded to [FontOptionsSheet].
 * @param onFontSizeDraftChange Forwarded to [FontOptionsSheet].
 * @param lineHeightPercentDraft Forwarded to [FontOptionsSheet].
 * @param onLineHeightPercentDraftChange Forwarded to [FontOptionsSheet].
 * @param autoScrollSpeedDraft Forwarded to [AutoScrollOptionsSheet].
 * @param onAutoScrollSpeedDraftChange Forwarded to [AutoScrollOptionsSheet].
 * @param scrollState Scroll state for the sheet's content column.
 * @param modifier Applied to the sheet's content column.
 */
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
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()

    TeddModalBottomSheet(
        title = sheet.title(),
        onDismissRequest = onDismiss,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = spacing.large),
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


/**
 * The table of contents rendered as a side drawer's content, shown by [ReaderContent] via a
 * `ModalNavigationDrawer` while `uiState.activeSheet == ReaderOptionSheet.TableOfContents`. Shows a
 * heading and, when there is nothing to show, an explanatory message in place of an empty list.
 *
 * @param uiState The reader's current state; supplies the outline entries to list.
 * @param onLocationClick Invoked with the location to jump to when an entry is tapped.
 * @param modifier Applied to the drawer's content list.
 */
@Composable
private fun TableOfContentsDrawerContent(
    uiState: ReaderUiState,
    onLocationClick: (com.tedd.teddreader.core.common.model.ReaderLocation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            TeddText(
                text = stringResource(Res.string.table_of_contents),
                modifier = Modifier.padding(
                    start = spacing.medium,
                    end = spacing.medium,
                    top = spacing.medium,
                    bottom = spacing.small,
                ),
                style = typography.titleMedium,
            )
        }
        if (uiState.outlineItems.isEmpty()) {
            item {
                TeddText(
                    text = stringResource(Res.string.no_table_of_contents),
                    modifier = Modifier.padding(horizontal = spacing.medium),
                    style = typography.bodyMedium,
                )
            }
        } else {
            itemsIndexed(uiState.outlineItems) { index, item ->
                NavigationDrawerItem(
                    label = { TeddText(text = item.displayTitle()) },
                    selected = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = spacing.medium + ((item.level.coerceAtLeast(1) - 1) * 12).dp,
                            end = spacing.medium,
                            bottom = if (index == uiState.outlineItems.lastIndex) 0.dp else spacing.small,
                        ),
                    onClick = { onLocationClick(item.location) },
                )
            }
        }
    }
}

/**
 * The table of contents rendered as bottom-sheet content, dispatched from
 * [ReaderActiveSheet]'s `when (sheet)` branch for [ReaderOptionSheet.TableOfContents]. `ReaderContent`
 * currently opens the table of contents as a drawer via [TableOfContentsDrawerContent] instead, and
 * filters `ReaderOptionSheet.TableOfContents` out before calling [ReaderActiveSheet] at all, so this
 * composable's dispatch branch does not currently run in practice.
 *
 * @param uiState The reader's current state; supplies the outline entries to list.
 * @param onLocationClick Invoked with the location to jump to when an entry is tapped.
 * @param modifier Applied to the sheet's content group.
 */
@Composable
private fun TableOfContentsSheet(
    uiState: ReaderUiState,
    onLocationClick: (com.tedd.teddreader.core.common.model.ReaderLocation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    TeddOptionGroup(
        title = uiState.outlineHeading?.takeIf { it.isNotBlank() },
        modifier = modifier,
    ) {
        if (uiState.outlineItems.isEmpty()) {
            TeddText(
                text = stringResource(Res.string.no_table_of_contents),
                modifier = Modifier.padding(horizontal = spacing.medium),
                style = typography.bodyMedium,
            )
        } else {
            uiState.outlineItems.forEachIndexed { index, item ->
                TeddButton(
                    text = item.displayTitle(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = spacing.medium + ((item.level.coerceAtLeast(1) - 1) * 12).dp,
                            end = spacing.medium,
                            bottom = if (index == uiState.outlineItems.lastIndex) 0.dp else spacing.small,
                        ),
                    onClick = { onLocationClick(item.location) },
                )
            }
        }
    }
}

/** Matches a generic, unlocalized page title such as "Page 12" that an outline entry can carry
 * instead of a real label; [displayTitle] replaces a match with today's localized page label so an
 * outline holding one of these still reads naturally in the user's language. */
private val legacyPageOutlineTitlePattern = Regex("^Page \\d+$")

/** Matches a generic, unlocalized section title such as "Section 3" that an outline entry can
 * carry instead of a real label; [displayTitle] replaces a match with today's localized section
 * label, extracting the section number to fill in. */
private val legacySectionOutlineTitlePattern = Regex("^Section \\d+$")

/**
 * The title to show for this outline entry: a generic legacy title (see
 * [legacyPageOutlineTitlePattern] and [legacySectionOutlineTitlePattern]) is replaced with today's
 * localized label so old and new outlines read consistently; any other title is shown as written by
 * the document itself or a newer import pass.
 *
 * @receiver The outline entry whose title is being resolved for display.
 */
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

/**
 * A localized, human-readable label for this location, used by [displayTitle] to stand in for a
 * generic legacy outline title. Each format's location carries a different underlying address (a
 * PDF page index, a plain-text character offset, an EPUB spine index), so the label is built
 * per-variant rather than from one shared field.
 *
 * @receiver The location to describe.
 */
@Composable
private fun com.tedd.teddreader.core.common.model.ReaderLocation.displayLabel(): String = when (this) {
    is ReaderLocation.PdfPage -> stringResource(Res.string.reader_location_page, pageIndex + 1)
    is ReaderLocation.TextOffset -> stringResource(Res.string.reader_location_text_position, offset + 1)
    is ReaderLocation.EpubOffset -> stringResource(Res.string.reader_location_epub_section, spineIndex + 1)
}

/**
 * The "go to page" sheet: a text field for a 1-based page number plus a Go button, enabled only
 * while the typed text resolves to a page within range. Converts the entered 1-based page back to
 * the 0-based index [onGoToPage] expects.
 *
 * @param uiState The reader's current state; supplies the total page count for the range shown and
 * used to clamp the entry.
 * @param pageText The field's current text, owned by the caller so it survives sheet re-opens.
 * @param onPageTextChange Invoked as the user types.
 * @param onGoToPage Invoked with the resolved zero-based page index when Go is tapped.
 * @param modifier Applied to the sheet's content group.
 */
@Composable
private fun GoToPageSheet(
    uiState: ReaderUiState,
    pageText: String,
    onPageTextChange: (String) -> Unit,
    onGoToPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
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
                .padding(horizontal = spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
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
                modifier = Modifier.heightIn(min = spacing.rowHeight),
                enabled = targetPage != null,
                onClick = { targetPage?.let { onGoToPage(it - 1) } },
            )
        }
    }
}

/**
 * The brightness sheet: a single slider that darkens the reader with a black overlay rather than
 * touching the device's own screen brightness. The slider ranges 20-100% so the page can never be
 * dimmed to full black; the overlay alpha is derived from the *committed* percentage only when the
 * drag finishes, not on every intermediate value, and is clamped to 0.8 so some page content always
 * stays visible even at the darkest setting.
 *
 * @param uiState The reader's current state; used here only to disable the slider while settings
 * are saving.
 * @param brightnessDraft The slider's in-progress value, before it is committed.
 * @param onBrightnessDraftChange Invoked as the slider is dragged.
 * @param onBrightnessOverlayAlphaChange Invoked with the resulting overlay alpha once the drag
 * finishes.
 * @param modifier Applied to the sheet's content group.
 */
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

/**
 * The display sheet: the keep-screen-on, fullscreen, and show-progress toggles that apply to every
 * format, plus a zoom slider that only appears in visual (PDF/image) mode, where zoom is a page
 * transform rather than a text-layout concern.
 *
 * @param uiState The reader's current state; determines whether visual mode is active and whether
 * controls should be disabled while saving.
 * @param pdfZoom The current visual-mode zoom level, shown and adjusted by the slider.
 * @param onPdfZoomChange Invoked as the zoom slider is dragged.
 * @param onKeepScreenOnChange Invoked when the keep-screen-on switch is toggled.
 * @param onFullscreenChange Invoked when the fullscreen switch is toggled.
 * @param onShowProgressChange Invoked when the show-progress switch is toggled.
 * @param modifier Applied to the sheet's content group.
 */
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

/**
 * The typography sheet: font size and line height sliders, a font family choice, and a live preview
 * built from the in-progress draft values rather than the committed style, so the user sees the
 * result while still dragging instead of only after releasing the slider.
 *
 * @param uiState The reader's current state; supplies the committed style everything but the
 * preview is based on, and whether controls should be disabled while saving.
 * @param fontSizeDraft The font size slider's in-progress value, before it is committed.
 * @param onFontSizeDraftChange Invoked as the font size slider is dragged.
 * @param lineHeightPercentDraft The line height slider's in-progress value, as a percentage.
 * @param onLineHeightPercentDraftChange Invoked as the line height slider is dragged.
 * @param onFontSizeChange Invoked with the committed font size once the slider drag finishes.
 * @param onLineHeightChange Invoked with the committed line height multiplier once the slider drag
 * finishes.
 * @param onFontFamilyChange Invoked when a font family radio option is chosen; null selects the
 * default.
 * @param modifier Applied to the sheet's content group.
 */
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
    val spacing = teddReaderSpacing()
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
            title = stringResource(Res.string.font_family_document),
            selected = uiState.style.fontFamilyName == null,
            onClick = { onFontFamilyChange(null) },
            enabled = !uiState.isSavingSettings,
        )
        TeddRadioRow(
            title = stringResource(Res.string.font_family_sans),
            selected = uiState.style.fontFamilyName == "sans",
            onClick = { onFontFamilyChange("sans") },
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
            modifier = Modifier.padding(horizontal = spacing.medium),
            style = previewStyle,
            title = stringResource(Res.string.typography_preview_title),
            description = stringResource(Res.string.typography_preview_description),
            previewText = stringResource(Res.string.typography_preview_text),
        )
    }
}

/**
 * The theme sheet: a live preview of the current style followed by a radio choice between the
 * reader's theme modes. [ReaderThemeMode.CUSTOM] is deliberately not offered here as a selectable
 * option — it exists as a state a style can be in, not one this sheet lets the user choose into.
 *
 * @param uiState The reader's current state; supplies the style shown in the preview and the active
 * theme mode.
 * @param onThemeModeChange Invoked when a theme radio option is chosen.
 * @param modifier Applied to the sheet's content column.
 */
@Composable
private fun ThemeOptionsSheet(
    uiState: ReaderUiState,
    onThemeModeChange: (ReaderThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()

    Column(modifier = modifier) {
        ReaderOptionPreview(
            modifier = Modifier.padding(horizontal = spacing.medium),
            style = uiState.style,
            title = stringResource(Res.string.theme_preview_title),
            description = stringResource(Res.string.theme_preview_description),
            previewText = stringResource(Res.string.theme_preview_text),
        )
        TeddOptionGroup(title = null) {
            listOf(ReaderThemeMode.PUBLISHER, ReaderThemeMode.SYSTEM, ReaderThemeMode.LIGHT, ReaderThemeMode.DARK, ReaderThemeMode.SEPIA).forEach { mode ->
                TeddRadioRow(
                    title = mode.themeLabel(uiState.documentFormat),
                    selected = uiState.style.themeMode == mode,
                    onClick = { onThemeModeChange(mode) },
                    enabled = !uiState.isSavingSettings,
                )
            }
        }
    }
}

/**
 * The page-movement sheet: three independent radio groups for the turn direction
 * ([readerPageTurnModeOptions]), the default transition ([readerDefaultTransitionOptions]), and the
 * page-turn effects ([readerPageEffectOptions]) — the second and third groups both write
 * [ReaderUiState.pageAnimation], since a "default transition" and a "page effect" are the same
 * underlying setting presented as two different pickers.
 *
 * @param uiState The reader's current state; supplies the active direction/animation and whether
 * controls should be disabled while saving.
 * @param onPageTurnModeChange Invoked when a direction radio option is chosen.
 * @param onPageAnimationChange Invoked when a transition or effect radio option is chosen.
 * @param modifier Applied to the sheet's content column.
 */
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

/**
 * The auto-scroll sheet: an enabled switch, a mode radio group, and a speed slider. The
 * [AutoScrollMode.LINE] option is disabled in visual (PDF/image) mode, since line-by-line scrolling
 * is a text-layout concept that a visual page has no equivalent of; see
 * [readerEffectiveAutoScrollMode] for how that case is actually handled when it is already active
 * rather than merely offered.
 *
 * @param uiState The reader's current state; supplies the active config and whether visual mode is
 * active.
 * @param speedDraft The speed slider's in-progress value, before it is committed.
 * @param onSpeedDraftChange Invoked as the speed slider is dragged.
 * @param onEnabledChange Invoked when the enabled switch is toggled.
 * @param onModeChange Invoked when a mode radio option is chosen.
 * @param onSpeedChange Invoked with the committed speed once the slider drag finishes.
 * @param modifier Applied to the sheet's content group.
 */
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

/**
 * The bottom-bar sheet: currently a single switch for whether the reading-progress indicator shows
 * in the bottom action bar.
 *
 * @param uiState The reader's current state; supplies whether progress is currently shown and
 * whether the switch should be disabled while saving.
 * @param onShowProgressChange Invoked when the show-progress switch is toggled.
 * @param modifier Applied to the sheet's content group.
 */
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

/** The `Slider` `steps` value for [FontOptionsSheet]'s font size slider, sized so the slider snaps
 * to whole-sp increments across [ReaderPinchFontSizeRange] rather than an arbitrary continuous
 * value. */
private const val FontSizeSliderSteps = 71

/** How coarsely the line-height percentage draft is rounded while dragging, so the committed value
 * always lands on a multiple of 5% instead of an arbitrary fraction. */
private const val LineHeightStepPercent = 5f

/** The `Slider` `steps` value for [FontOptionsSheet]'s line height slider, matching
 * [LineHeightStepPercent] across the slider's 100-300% range so the visual snap points agree with
 * the draft rounding. */
private const val LineHeightSliderSteps = 39

/** The `Slider` `steps` value for [AutoScrollOptionsSheet]'s speed slider, sized so the slider
 * snaps to hundredths across [AutoScrollConfig]'s speed range, matching the precision
 * [Float.roundToHundredths] commits at. */
private const val SpeedSliderSteps = 98

/** The turn-direction choices [PageTurnOptionsSheet] offers. [PageTurnMode.CONTINUOUS] exists as a
 * mode a style can hold but is deliberately not offered here as a choice — [pageTurnLabel] renders
 * it identically to [PageTurnMode.VERTICAL], so this list sticks to the two the user actually picks
 * between. */
internal val readerPageTurnModeOptions: List<PageTurnMode> = listOf(
    PageTurnMode.HORIZONTAL,
    PageTurnMode.VERTICAL,
)

/** The plain, non-decorative transitions [PageTurnOptionsSheet] offers under "default transition" —
 * the animations a reader is unlikely to find distracting for everyday page turns. See
 * [readerPageEffectOptions] for the showier alternatives offered alongside these. */
internal val readerDefaultTransitionOptions: List<PageAnimation> = listOf(
    PageAnimation.NONE,
    PageAnimation.SLIDE,
    PageAnimation.FADE,
    PageAnimation.SCROLL,
)

/** The decorative page-turn effects [PageTurnOptionsSheet] offers under "page effects" — kept
 * separate from [readerDefaultTransitionOptions] so the sheet can group everyday transitions apart
 * from novelty ones, even though both groups write the same [ReaderUiState.pageAnimation]. */
internal val readerPageEffectOptions: List<PageAnimation> = listOf(
    PageAnimation.FLUID_PAGER,
    PageAnimation.CURL_PAGER,
    PageAnimation.CIRCLE_REVEAL,
    PageAnimation.MOVIE_CAROUSEL,
    PageAnimation.PAGE_FLIP,
)

/** Every [PageAnimation] offered anywhere in the reader's UI: [readerDefaultTransitionOptions] and
 * [readerPageEffectOptions] combined, in that order. */
internal val readerPageAnimationOptions: List<PageAnimation> =
    readerDefaultTransitionOptions + readerPageEffectOptions

/**
 * Snaps this speed to the nearest hundredth and clamps it into [AutoScrollConfig]'s valid range, so
 * a drag gesture's raw float never gets committed as a speed with more precision than the slider's
 * [SpeedSliderSteps] can actually represent, and never outside the range the model accepts.
 *
 * @receiver The raw, in-progress speed value from a slider drag.
 */
private fun Float.roundToHundredths(): Float =
    (this * 100f).roundToInt().div(100f).coerceIn(AutoScrollConfig.MIN_SPEED, AutoScrollConfig.MAX_SPEED)

/**
 * The page index [paneCount] panes ahead of [currentPage], or null if that would run past the end
 * of the document — the shared range check every "is there a next page" decision in the reader
 * (auto-scroll stopping, the bottom bar's next button, swipe gestures) is built from.
 *
 * @param currentPage The zero-based page currently shown.
 * @param totalPages The document's total page count.
 * @param paneCount How many pages a single turn advances by; coerced to at least 1.
 * @return The next page index, or null if there is none.
 */
internal fun readerNextPage(currentPage: Int, totalPages: Int, paneCount: Int): Int? =
    (currentPage + paneCount.coerceAtLeast(1)).takeIf { it in 0 until totalPages }

/**
 * Downgrades [AutoScrollMode.LINE] to [AutoScrollMode.PAGE] in visual (PDF/image) mode, since
 * line-by-line scrolling is a text-layout concept a visual page has no notion of; any other mode
 * passes through unchanged. Used to decide what auto-scroll actually does, as distinct from
 * [AutoScrollOptionsSheet], which decides what modes are even offered as a choice.
 *
 * @param mode The mode as configured/selected by the user.
 * @param isVisualMode Whether the current document is in PDF/image (as opposed to text/EPUB) mode.
 * @return The mode auto-scroll should actually run with.
 */
internal fun readerEffectiveAutoScrollMode(mode: AutoScrollMode, isVisualMode: Boolean): AutoScrollMode =
    if (isVisualMode && mode == AutoScrollMode.LINE) AutoScrollMode.PAGE else mode

/**
 * Whether [ReaderAutoScrollEffect] should drive auto-scroll by requesting whole-page turns for the
 * given mode/animation combination, and if so, which direction. Returns null for [AutoScrollMode.PIXEL]/
 * [AutoScrollMode.LINE] paired with an animation that already scrolls or turns continuously on its
 * own (e.g. [PageAnimation.SCROLL] or a curl/flip effect) — those animations drive their own
 * continuous auto-scroll internally, and requesting a page-turn on top of that would double it up.
 *
 * @param mode The effective auto-scroll mode, after [readerEffectiveAutoScrollMode] has resolved
 * it.
 * @param pageAnimation The active page-turn animation.
 * @return The page movement to request, or null if this combination should not drive whole-page
 * turns at all.
 */
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

/**
 * How long [ReaderAutoScrollEffect] should wait before requesting the next whole-page turn in
 * [AutoScrollMode.PAGE], inversely proportional to [speed] so a higher speed setting means a
 * shorter wait between turns.
 *
 * @param speed The configured auto-scroll speed; clamped to [AutoScrollConfig]'s valid range before
 * use.
 * @return The delay in milliseconds.
 */
internal fun autoScrollPageDelayMillis(speed: Float): Long =
    (1_000f / AutoScrollConfig.clampSpeed(speed)).toLong()

/**
 * How many pixels a continuous [AutoScrollMode.PIXEL] scroll should advance over one frame's
 * elapsed time, scaled by [density] so the same [speed] setting scrolls a visually consistent
 * distance regardless of screen density.
 *
 * @param speed The configured auto-scroll speed; clamped to [AutoScrollConfig]'s valid range before
 * use.
 * @param density The display's pixel density.
 * @param elapsedMillis Time elapsed since the last frame.
 * @return The distance to scroll, in pixels.
 */
internal fun autoScrollDistancePx(speed: Float, density: Float, elapsedMillis: Long): Float =
    200f *
        density.coerceAtLeast(0f) *
        AutoScrollConfig.clampSpeed(speed) *
        (elapsedMillis.coerceAtLeast(0L) / 1_000f)

/**
 * How long to wait between line-by-line jumps in [AutoScrollMode.LINE], derived from
 * [autoScrollDistancePx]'s per-second pixel rate so a line's height is crossed in the same time a
 * pixel scroll at the same [speed] would take to cross it, keeping the two modes' perceived speed
 * consistent.
 *
 * @param lineHeightPx The height of one line, in pixels.
 * @param pixelsPerSecond The pixel scroll rate to match, from [autoScrollDistancePx] over one
 * second; coerced to at least 1 to avoid dividing by zero.
 * @return The delay in milliseconds.
 */
internal fun autoScrollLineDelayMillis(lineHeightPx: Float, pixelsPerSecond: Float): Long =
    ((lineHeightPx.coerceAtLeast(0f) / pixelsPerSecond.coerceAtLeast(1f)) * 1_000f).toLong()

/**
 * Converts a raw page index/total into the spread (i.e. group-of-[paneCount]-pages) index/total the
 * bottom bar and status footer actually display, so a two-up spread is numbered and progressed
 * through as one unit instead of the reader appearing to skip every other page number.
 *
 * @param currentPage The zero-based raw page currently shown.
 * @param totalPages The document's total raw page count.
 * @param paneCount How many raw pages make up one spread.
 * @return The current/total spread index, or an all-zero [PageIndex] if there are no pages yet.
 */
internal fun readerSpreadPageIndex(currentPage: Int, totalPages: Int, paneCount: Int): PageIndex {
    if (totalPages <= 0) return PageIndex(current = 0, total = 0)
    val step = paneCount.coerceAtLeast(1)
    val clampedCurrentPage = currentPage.coerceIn(0, totalPages - 1)
    return PageIndex(
        current = clampedCurrentPage / step,
        total = ((totalPages - 1) / step) + 1,
    )
}

/**
 * The inverse of [readerSpreadPageIndex]: converts a spread index the user picked on the bottom
 * bar's slider back into the raw page to actually jump to, so dragging the slider lands on the
 * first raw page of the chosen spread rather than an index the pagination has no page at.
 *
 * @param selectedSpread The spread index chosen on the slider; clamped into range.
 * @param totalPages The document's total raw page count.
 * @param paneCount How many raw pages make up one spread.
 * @return The raw page index to jump to, or 0 if there are no pages yet.
 */
internal fun readerSpreadAnchorPage(selectedSpread: Int, totalPages: Int, paneCount: Int): Int {
    if (totalPages <= 0) return 0
    val step = paneCount.coerceAtLeast(1)
    val maxSpreadIndex = ((totalPages - 1) / step)
    val boundedSpread = selectedSpread.coerceIn(0, maxSpreadIndex)
    return (boundedSpread * step).coerceAtMost(totalPages - 1)
}

@Composable
private fun ReaderLoadingState(
    loadingKey: String,
    style: ReaderStyle,
    modifier: Modifier = Modifier,
) {
    var showIndicator by remember(loadingKey) { mutableStateOf(false) }
    LaunchedEffect(loadingKey) {
        showIndicator = false
        delay(ReaderLoadingIndicatorDelayMillis)
        showIndicator = true
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(style.readerColors().background),
        contentAlignment = Alignment.Center,
    ) {
        if (showIndicator) {
            TeddFullScreenLoadingIndicator(message = stringResource(Res.string.opening_document))
        }
    }
}

/**
 * The percentage shown by [ReaderStatusFooter]'s progress readout for text documents, based on the
 * absolute text offset of the current location rather than on a still-growing page total.
 *
 * @param location The current absolute reading location, if one is known.
 * @param characterCount The final document character count, or null while import is incomplete.
 * @param currentPercent The already-published progress to keep when the final count is still
 *   unknown.
 */
internal fun readerReadProgressPercent(
    location: ReaderLocation?,
    characterCount: Long?,
    currentPercent: Int = 0,
): Int = when {
    characterCount == null -> currentPercent
    characterCount <= 0L -> 0
    location !is ReaderLocation.TextOffset -> currentPercent
    else -> ((location.offset.toDouble() / characterCount.toDouble()) * 100.0)
        .roundToInt()
        .coerceIn(0, 100)
}

/** The percentage shown by [ReaderStatusFooter] for visual page formats, still page-based. */
internal fun readerVisualReadProgressPercent(pageIndex: PageIndex): Int =
    if (pageIndex.total == 0) {
        0
    } else {
        (((pageIndex.current + 1f) / pageIndex.total) * 100f).roundToInt().coerceIn(0, 100)
    }

/**
 * Looks up the rendered content for the given page across the explicit [ReaderUiState.pageSlots]
 * list first, then the previous/current/next single-page cache — so a caller does not need to know
 * which of those the current document format or loading state happens to be using.
 *
 * @receiver The reader's current state.
 * @param page The zero-based page index to look up.
 * @return The page's content, or null if nothing loaded covers this index yet.
 */
internal fun ReaderUiState.pageSlot(page: Int): ReaderPageUi? =
    pageSlots.firstOrNull { it.page == page }
        ?: when {
            previousPage?.page == page -> previousPage
            currentPage.page == page -> currentPage
            nextPage?.page == page -> nextPage
            else -> null
        }

/**
 * Reinterprets [page] as the *current* page of a [PageIndex] carrying this state's total, clamped
 * into range — used where a pane needs a [PageIndex] describing itself rather than the reader as a
 * whole (e.g. handing a specific page to [PdfPageSurface]).
 *
 * @receiver The reader's current state; supplies the total page count.
 * @param page The zero-based page index to describe.
 */
private fun ReaderUiState.pageIndexFor(page: Int): PageIndex {
    if (pageIndex.total <= 0) return pageIndex
    return PageIndex(
        current = page.coerceIn(0, pageIndex.total - 1),
        total = pageIndex.total,
    )
}

/**
 * The plain text to show for the given page: [pageSlot]'s text when there is one, otherwise the
 * reader's currently-loaded [ReaderUiState.pageText] if this *is* the current page (with a
 * placeholder string for a genuinely empty page), or an empty string for any other page not yet
 * loaded into a slot.
 *
 * @receiver The reader's current state.
 * @param page The zero-based page index to look up.
 * @return The page's text, or an empty string if nothing is loaded for it yet.
 */
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

/**
 * The localized label [ThemeOptionsSheet] shows for this theme mode. [ReaderThemeMode.CUSTOM] has a
 * label here even though the sheet never offers it as a choice, so this function stays total over
 * the enum rather than needing a caller-side fallback.
 *
 * @receiver The theme mode to label.
 */
@Composable
private fun ReaderThemeMode.themeLabel(documentFormat: DocumentFormat): String = when (this) {
    ReaderThemeMode.PUBLISHER -> when (documentFormat) {
        DocumentFormat.EPUB -> stringResource(Res.string.epub_style)
        DocumentFormat.PDF -> stringResource(Res.string.pdf_style)
        else -> stringResource(Res.string.document_style)
    }
    ReaderThemeMode.SYSTEM -> stringResource(Res.string.follow_system)
    ReaderThemeMode.LIGHT -> stringResource(Res.string.light)
    ReaderThemeMode.DARK -> stringResource(Res.string.dark)
    ReaderThemeMode.SEPIA -> stringResource(Res.string.sepia)
    ReaderThemeMode.CUSTOM -> stringResource(Res.string.custom)
}

/**
 * The localized label [PageTurnOptionsSheet] shows for this direction. [PageTurnMode.CONTINUOUS]
 * shares [PageTurnMode.VERTICAL]'s label rather than getting its own, since it is not offered as a
 * separate choice in [readerPageTurnModeOptions] and a user should never actually see this branch
 * taken.
 *
 * @receiver The page-turn direction to label.
 */
@Composable
private fun PageTurnMode.pageTurnLabel(): String = when (this) {
    PageTurnMode.HORIZONTAL -> stringResource(Res.string.horizontal_pages)
    PageTurnMode.VERTICAL,
    PageTurnMode.CONTINUOUS,
        -> stringResource(Res.string.vertical_pages)
}

/**
 * The localized label [PageTurnOptionsSheet] shows for this animation. Several enum values
 * deliberately share one label — [PageAnimation.SLIDE]/[PageAnimation.SHEET_FLIP] both read as
 * "slide," [PageAnimation.BOOK_CURL]/[PageAnimation.CURL_PAGER] both read as "curl" — because they
 * are two implementations of what a user experiences as the same visual effect, not two options
 * worth distinguishing by name.
 *
 * @receiver The page-turn animation to label.
 */
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

/**
 * The localized label [AutoScrollOptionsSheet] shows for this mode.
 *
 * @receiver The auto-scroll mode to label.
 */
@Composable
private fun AutoScrollMode.autoScrollLabel(): String = when (this) {
    AutoScrollMode.PIXEL -> stringResource(Res.string.auto_scroll_smooth_scroll)
    AutoScrollMode.LINE -> stringResource(Res.string.auto_scroll_line_text_only)
    AutoScrollMode.PAGE -> stringResource(Res.string.auto_scroll_page_by_page)
}

/**
 * The localized title [ReaderActiveSheet] gives the shared bottom sheet's title bar for this sheet.
 *
 * @receiver The sheet to title.
 */
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

/**
 * The reader's error state: a centered message shown by [ReaderScreen] instead of the reading
 * surface whenever [ReaderUiState.errorMessage] is set.
 *
 * @param message The error text to show, as already resolved/localized by the view model.
 * @param modifier Applied to the message's container.
 */
@Composable
private fun ReaderError(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        TeddText(text = message)
    }
}

/**
 * A minimal [ReaderUiState] builder for the `@Preview` composables below, so each preview only has
 * to name the handful of fields it wants to vary instead of constructing the full state by hand.
 * Defaults to bilingual sample text so both Latin and Hangul glyph rendering are visible in a
 * preview without extra setup.
 *
 * @param documentTitle Shown in the top bar and status footer.
 * @param pageText The current page's plain text.
 * @param style The reader style (theme, font, etc.) to preview.
 * @param isControlsVisible Whether the top/bottom control bars should be shown.
 * @param activeSheet An option sheet to show already open, or null for none.
 */
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

/**
 * Hides the reading controls as soon as a drag gesture clears the touch slop, so swiping to turn a
 * page or pan a zoomed view also dismisses the top/bottom bars in the same motion, instead of
 * requiring a separate tap. Only ever *hides* controls on a drag — it never shows them, and it never
 * acts once [gestureBlocked] reports another gesture (e.g. a pinch/zoom) is already handling this
 * pointer sequence, so the two gesture handlers do not fight over the same touch. Uses
 * [PointerEventPass.Initial] so this observation happens before the pager's own swipe/tap detection
 * downstream, without consuming the event and blocking that detection from also seeing it.
 *
 * @receiver The modifier to chain this pointer input onto.
 * @param controlsVisible Whether the controls are visible right now; read fresh at the start of
 * each gesture via [rememberUpdatedState] so a value captured by an earlier, still-running gesture
 * closure cannot go stale.
 * @param gestureBlocked Whether another gesture handler is already active and this one should stand
 * down.
 * @param onToggleControls Invoked to hide the controls once the drag clears the touch slop.
 */
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

/**
 * Previews the reader at a compact phone width (360x720) with the controls visible and a long,
 * mixed Korean/English document title — worth previewing on its own because a long bilingual title
 * is exactly where top-bar title truncation and mixed-script line metrics are most likely to break,
 * and neither shows up at all with a short, single-script placeholder title.
 */
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
            batteryPercent = 73,
        )
    }
}

/**
 * Previews the reader with the top/bottom control bars and status footer hidden
 * (`isControlsVisible = false`) — worth previewing on its own because that is the reader's default
 * reading state once a user taps to dismiss the chrome, and it is easy for a layout change made
 * against the controls-visible preview to accidentally leave the page content misaligned once the
 * space those bars occupied is given back.
 */
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
            batteryPercent = 73,
        )
    }
}

/**
 * Previews the reader in [darkReaderStyle] under a dark [TeddReaderTheme] — worth previewing on its
 * own because the reader's text/background colors come from the reader style, not the app theme, so
 * a dark-mode regression there (e.g. a hardcoded light color slipping into a new composable) would
 * not show up in any of the other previews here, which all use the light default style.
 */
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
            batteryPercent = 73,
        )
    }
}

/**
 * Previews the reader at Compose Preview's own default device size, rather than the fixed
 * 360x720 phone size the other previews above pin, with a plain English baseline [ReaderUiState]
 * built directly instead of through [previewReaderUiState] — worth keeping separate so a future
 * change to that helper's defaults cannot silently remove coverage of the reader's fully default
 * configuration at a different, unpinned device size.
 */
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
            batteryPercent = 73,
        )
    }
}

private const val ReaderLoadingIndicatorDelayMillis = 150L

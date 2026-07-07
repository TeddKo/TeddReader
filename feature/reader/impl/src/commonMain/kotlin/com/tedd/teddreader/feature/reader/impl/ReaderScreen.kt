package com.tedd.teddreader.feature.reader.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.darkReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.readerColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddFullScreenLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddIconButton
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
import com.tedd.teddreader.feature.reader.impl.component.ReaderPager
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
        onBookmarkToggle = viewModel::toggleBookmark,
        onActionSelected = { action ->
            when (action) {
                ReaderMenuAction.Search -> onSearchClick()
                ReaderMenuAction.Bookmark -> onBookmarksClick()
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
    onBookmarkToggle: () -> Unit,
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
            onBookmarkToggle = onBookmarkToggle,
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
    onBookmarkToggle: () -> Unit,
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
    modifier: Modifier = Modifier,
) {
    ReaderAutoScrollEffect(
        uiState = uiState,
        onNextPage = onNextPage,
        onStop = { onAutoScrollEnabledChange(false) },
    )

    ReaderSystemBarsEffect(
        visible = uiState.isControlsVisible || uiState.activeSheet != null,
        backgroundColor = uiState.style.readerColors().background,
        keepScreenOn = uiState.keepScreenOn,
    )

    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(uiState.style.readerColors().background),
    ) {
        ReaderPager(
            pageKey = uiState.pageIndex.current,
            pageTurnMode = uiState.pageTurnMode,
            pageAnimation = uiState.pageAnimation,
            onPreviousPage = {
                if (uiState.autoScrollConfig.enabled) {
                    onAutoScrollEnabledChange(false)
                }
                onPreviousPage()
            },
            onNextPage = {
                if (uiState.autoScrollConfig.enabled) {
                    onAutoScrollEnabledChange(false)
                }
                onNextPage()
            },
            onToggleControls = {
                if (uiState.autoScrollConfig.enabled) {
                    onAutoScrollEnabledChange(false)
                }
                onToggleControls()
            },
        ) { page ->
            if (uiState.isPdfMode) {
                PdfPageSurface(
                    pageIndex = uiState.pageIndexFor(page),
                    modifier = Modifier.fillMaxSize(),
                    documentUri = uiState.pageSlot(page)?.documentUri ?: uiState.documentUri,
                    zoom = uiState.pdfZoom,
                    rotationDegrees = uiState.pdfRotationDegrees,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(readerSystemBarsInsets().only(WindowInsetsSides.Vertical))
                        .padding(
                            horizontal = DefaultTeddReaderSpacing.readerMargin,
                            vertical = DefaultTeddReaderSpacing.large,
                        )
                        .onSizeChanged { size ->
                            onViewportSizeChanged(
                                density.pxToSp(size.width.toFloat()).value.roundToInt().coerceAtLeast(1),
                                density.pxToSp(size.height.toFloat()).value.roundToInt().coerceAtLeast(1),
                            )
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

        if (uiState.brightnessOverlayAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = uiState.brightnessOverlayAlpha)),
            )
        }

        if (uiState.isControlsVisible) {
            ReaderTopControls(
                title = uiState.documentTitle,
                style = uiState.style,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding(),
                navigationIcon = {
                    TeddIconButton(onClick = onBack, contentDescription = "Back") { Text("←", maxLines = 1) }
                },
                actions = {
                    TeddIconButton(onClick = onBookmarkToggle, contentDescription = "Toggle bookmark") { Text("☆", maxLines = 1) }
                    ReaderActionMenu(onActionSelected = onActionSelected)
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
                    onPreviousPage = {
                    onAutoScrollEnabledChange(false)
                    onPreviousPage()
                },
                onNextPage = {
                    onAutoScrollEnabledChange(false)
                    onNextPage()
                },
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
            )
        }
    }
}

@Composable
private fun ReaderAutoScrollEffect(
    uiState: ReaderUiState,
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
    ) {
        if (!config.enabled) return@LaunchedEffect
        if (uiState.pageIndex.total <= 0 || uiState.pageIndex.current >= uiState.pageIndex.total - 1) {
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
    onMoveToLocation: (com.tedd.teddreader.core.common.model.ReaderLocation) -> Unit,
    onBrightnessOverlayAlphaChange: (Float) -> Unit,
) {
    TeddModalBottomSheet(
        title = sheet.title,
        onDismissRequest = onDismiss,
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
                onEnabledChange = onAutoScrollEnabledChange,
                onModeChange = onAutoScrollModeChange,
                onSpeedChange = onAutoScrollSpeedChange,
            )
            ReaderOptionSheet.Brightness -> BrightnessOptionsSheet(
                uiState = uiState,
                onBrightnessOverlayAlphaChange = onBrightnessOverlayAlphaChange,
            )
            ReaderOptionSheet.Controls -> ControlOptionsSheet(
                uiState = uiState,
                onShowProgressChange = onShowProgressChange,
            )
        }
    }
}


@Composable
private fun TableOfContentsSheet(
    uiState: ReaderUiState,
    onLocationClick: (com.tedd.teddreader.core.common.model.ReaderLocation) -> Unit,
) {
    TeddOptionGroup(title = "Contents") {
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
    onGoToPage: (Int) -> Unit,
) {
    var pageText by remember(uiState.pageIndex.current) { mutableStateOf((uiState.pageIndex.current + 1).toString()) }
    val totalPages = uiState.pageIndex.total.coerceAtLeast(1)
    val targetPage = pageText.toIntOrNull()?.coerceIn(1, totalPages)

    TeddOptionGroup(
        title = "Go to page",
        description = "Enter 1-$totalPages.",
    ) {
        TeddTextField(
            value = pageText,
            onValueChange = { value -> pageText = value.filter(Char::isDigit).take(6) },
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
    onBrightnessOverlayAlphaChange: (Float) -> Unit,
) {
    val committedPercent = (uiState.brightnessOverlayAlpha * 100f).roundToInt()
    var percentDraft by remember(committedPercent) { mutableStateOf(committedPercent.toFloat()) }

    TeddOptionGroup(
        title = "Brightness",
        description = "Dims reader without changing system brightness.",
    ) {
        TeddSliderRow(
            title = "Dim overlay",
            value = percentDraft,
            onValueChange = { value -> percentDraft = value.roundToInt().toFloat() },
            onValueChangeFinished = { onBrightnessOverlayAlphaChange(percentDraft / 100f) },
            valueRange = 0f..80f,
            steps = PercentSliderSteps,
            valueLabel = "${percentDraft.roundToInt()}%",
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
) {
    TeddOptionGroup(title = "Display") {
        TeddSwitchRow("Keep screen on", uiState.keepScreenOn, onKeepScreenOnChange, enabled = !uiState.isSavingSettings)
        TeddSwitchRow("Fullscreen reader", uiState.fullscreen, onFullscreenChange, enabled = !uiState.isSavingSettings)
        TeddSwitchRow("Show progress", uiState.showProgress, onShowProgressChange, enabled = !uiState.isSavingSettings)
        TeddSwitchRow(
            title = "Background image",
            checked = false,
            onCheckedChange = {},
            enabled = false,
            description = "Image picker comes later.",
        )
    }
}

@Composable
private fun FontOptionsSheet(
    uiState: ReaderUiState,
    onFontSizeChange: (Float) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onFontFamilyChange: (String?) -> Unit,
) {
    val committedFontSize = uiState.style.fontSizeSp.roundToInt()
    var fontSizeDraft by remember(committedFontSize) { mutableStateOf(committedFontSize.toFloat()) }
    val committedLineHeightPercent = (uiState.style.lineHeightMultiplier * 100f).roundToInt()
    var lineHeightPercentDraft by remember(committedLineHeightPercent) {
        mutableStateOf(committedLineHeightPercent.toFloat())
    }
    val previewStyle = uiState.style.copy(
        fontSizeSp = fontSizeDraft,
        lineHeightMultiplier = lineHeightPercentDraft / 100f,
    )

    TeddOptionGroup(title = "Typography") {
        TeddSliderRow(
            title = "Font size",
            value = fontSizeDraft,
            onValueChange = { value -> fontSizeDraft = value.roundToInt().toFloat() },
            onValueChangeFinished = { onFontSizeChange(fontSizeDraft) },
            valueRange = 8f..80f,
            steps = FontSizeSliderSteps,
            valueLabel = "${fontSizeDraft.roundToInt()}sp",
            enabled = !uiState.isSavingSettings,
        )
        TeddSliderRow(
            title = "Line height",
            value = lineHeightPercentDraft,
            onValueChange = { value ->
                lineHeightPercentDraft = (value / LineHeightStepPercent).roundToInt() * LineHeightStepPercent
            },
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
) {
    ReaderOptionPreview(
        style = uiState.style,
        title = "Theme preview",
        description = "가나다 ABC 123 · 배경/글자색 확인",
        previewText = "가나다 ABC 123\n눈 피로를 줄이는 색상 대비를 확인합니다.",
    )
    TeddOptionGroup(title = "Theme preset") {
        listOf(ReaderThemeMode.LIGHT, ReaderThemeMode.DARK, ReaderThemeMode.SEPIA).forEach { mode ->
            TeddRadioRow(
                title = mode.name.lowercase(),
                selected = uiState.style.themeMode == mode,
                onClick = { onThemeModeChange(mode) },
                enabled = !uiState.isSavingSettings,
            )
        }
        TeddRadioRow(
            title = "Custom colors",
            selected = uiState.style.themeMode == ReaderThemeMode.CUSTOM,
            onClick = { onThemeModeChange(ReaderThemeMode.CUSTOM) },
                enabled = !uiState.isSavingSettings,
            description = "Color picker comes later.",
        )
    }
}

@Composable
private fun PageTurnOptionsSheet(
    uiState: ReaderUiState,
    onPageTurnModeChange: (PageTurnMode) -> Unit,
    onPageAnimationChange: (PageAnimation) -> Unit,
) {
    TeddOptionGroup(title = "Page mode") {
        PageTurnMode.entries.forEach { mode ->
            TeddRadioRow(
                title = mode.name.lowercase(),
                selected = uiState.pageTurnMode == mode,
                onClick = { onPageTurnModeChange(mode) },
                enabled = !uiState.isSavingSettings,
            )
        }
    }
    TeddOptionGroup(title = "Animation") {
        PageAnimation.entries.forEach { animation ->
            TeddRadioRow(
                title = animation.name.lowercase(),
                selected = uiState.pageAnimation == animation,
                onClick = { onPageAnimationChange(animation) },
                enabled = !uiState.isSavingSettings,
            )
        }
    }
}

@Composable
private fun AutoScrollOptionsSheet(
    uiState: ReaderUiState,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (AutoScrollMode) -> Unit,
    onSpeedChange: (Float) -> Unit,
) {
    val committedSpeed = uiState.autoScrollConfig.speed.roundToInt().coerceIn(1, 10)
    var speedDraft by remember(committedSpeed) { mutableStateOf(committedSpeed.toFloat()) }

    TeddOptionGroup(title = "Auto-scroll") {
        TeddSwitchRow("Enabled", uiState.autoScrollConfig.enabled, onEnabledChange, enabled = !uiState.isSavingSettings)
        AutoScrollMode.entries.forEach { mode ->
            TeddRadioRow(
                title = mode.name.lowercase(),
                selected = uiState.autoScrollConfig.mode == mode,
                onClick = { onModeChange(mode) },
                enabled = !uiState.isSavingSettings,
            )
        }
        TeddSliderRow(
            title = "Speed",
            value = speedDraft,
            onValueChange = { value -> speedDraft = value.roundToInt().toFloat() },
            onValueChangeFinished = { onSpeedChange(speedDraft) },
            valueRange = 1f..10f,
            steps = SpeedSliderSteps,
            valueLabel = formatSpeedLabel(speedDraft),
            enabled = !uiState.isSavingSettings,
        )
        TeddButton(
            text = if (uiState.autoScrollConfig.enabled) "Stop" else "Start",
            enabled = !uiState.isSavingSettings,
            onClick = { onEnabledChange(!uiState.autoScrollConfig.enabled) },
        )
    }
}

@Composable
private fun ControlOptionsSheet(
    uiState: ReaderUiState,
    onShowProgressChange: (Boolean) -> Unit,
) {
    TeddOptionGroup(title = "Controls") {
        TeddSwitchRow("Show progress controls", uiState.showProgress, onShowProgressChange, enabled = !uiState.isSavingSettings)
    }
}

private const val FontSizeSliderSteps = 71
private const val LineHeightStepPercent = 5f
private const val LineHeightSliderSteps = 39
private const val SpeedSliderSteps = 8
private const val PercentSliderSteps = 79

private fun formatSpeedLabel(speed: Float): String = "${speed.roundToInt()}x"

private fun ReaderUiState.pageSlot(page: Int): ReaderPageUi? = when {
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

private val ReaderOptionSheet.title: String
    get() = when (this) {
        ReaderOptionSheet.TableOfContents -> "Table of contents"
        ReaderOptionSheet.GoToPage -> "Go to page"
        ReaderOptionSheet.View -> "View options"
        ReaderOptionSheet.Font -> "Font options"
        ReaderOptionSheet.Theme -> "Theme options"
        ReaderOptionSheet.PageTurn -> "Page turn options"
        ReaderOptionSheet.AutoScroll -> "Auto-scroll options"
        ReaderOptionSheet.Brightness -> "Brightness"
        ReaderOptionSheet.Controls -> "Control options"
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
            onBookmarkToggle = {},
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
            onBookmarkToggle = {},
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
            onBookmarkToggle = {},
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
            onBookmarkToggle = {},
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
        )
    }
}

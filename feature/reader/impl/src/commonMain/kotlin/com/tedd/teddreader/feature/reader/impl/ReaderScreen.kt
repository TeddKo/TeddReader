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
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.tedd.teddreader.core.common.model.resolveSystemTheme
import com.tedd.teddreader.core.common.model.withThemeMode
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
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

/**
 * [ReaderViewModel]을 [ReaderScreen]에 연결하는 진입점. 아래의 [ReaderScreen], [ReaderContent]와
 * 마찬가지로 이 composable은 view model에 대한 순수한 상태·콜백 전달자다: [ReaderUiState]를 수집하고,
 * 모든 사용자 동작을 view model 호출이나 [ReaderMenuAction] 분기로 되돌려 전달하며, 자체적인 문서
 * 데이터는 전혀 보유하지 않는다.
 *
 * 여기서 선언된 `rememberSaveable` 값들(`goToPageText`, `brightnessDraft`, `fontSizeDraft`,
 * `lineHeightPercentDraft`, `autoScrollSpeedDraft`, `bottomSliderValue`, `isActionMenuExpanded`)은
 * 초안(draft)이다: 사용자의 제스처가 view model에 확정되기 전, 진행 중인 동안 슬라이더나 텍스트 필드의
 * 값을 담아두며, 스스로 configuration 변경을 견뎌내어 제스처 도중의 드래그나 입력된 숫자가 사라지지
 * 않도록 한다. 제스처가 끝나면 이들은 더 이상 진실의 원천이 아니며, view model 자체의 확정된 상태가
 * 그것이다. `goToPageText`, `brightnessDraft`, `fontSizeDraft`, `lineHeightPercentDraft`,
 * `autoScrollSpeedDraft`는 각각 대응하는 확정 값에 키가 걸려 있어서, 제스처 바깥에서 온 확정 변경(예:
 * 다른 기기에서 복원된 설정)이 있으면 초안이 그에 맞게 리셋된다; `bottomSliderValue`는 대신
 * `ReaderContent` 내부의 effect가 동기화하며, `isActionMenuExpanded`는 대응하는 확정 값이 전혀 없는
 * 순수한 문서별 UI 상태다.
 *
 * @param documentId 열려는 문서; 바뀌면 [ReaderViewModel.openDocument]를 다시 트리거한다.
 * @param onBack 사용자가 리더를 떠나려 할 때 호출된다.
 * @param modifier 결과로 나오는 [ReaderScreen]에 적용된다.
 * @param onSearchClick 사용자가 액션 메뉴에서 검색을 선택하면 호출된다.
 * @param onBookmarksClick 사용자가 액션 메뉴에서 저장된 위치를 선택하면 호출된다.
 * @param onDocumentInfoClick 사용자가 액션 메뉴에서 문서 정보를 선택하면 호출된다.
 * @param jumpLocation 한 번 이동할 위치(예: 딥링크나 저장된 위치 탭에서 옴); [onJumpLocationConsumed]를
 * 통해 소비되어 recomposition에서 다시 실행되지 않는다.
 * @param onJumpLocationConsumed [jumpLocation]이 적용되고 나면 호출되어, 호출자가 이를 지울 수 있게 한다.
 * @param viewModel 리더의 view model; 기본값은 Koin으로 해석된 것.
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
    val persistedUiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 시스템을 따르도록 설정된 style은 저장소에는 밝은 페이지 색상을 유지하는데, 그것이 따르는 시스템
    // 설정은 선택이 이루어진 후 한참 지나서도 바뀔 수 있기 때문이다. 여기서 한 번 이를 해석해 두면 리더가
    // 그리는 모든 색상이 주변 앱 chrome과 일치하게 된다 — 이렇게 하지 않으면 chrome은 어두워지는데
    // 페이지는 밝은 채로 남아 있었다. 색상만 바뀌고 페이지 레이아웃은 색상이 아니라 활자 설정을 기준으로
    // 하므로 페이지 나누기는 무효화되지 않는다.
    val systemInDarkTheme = isSystemInDarkTheme()
    val uiState = remember(persistedUiState, systemInDarkTheme) {
        persistedUiState.copy(
            style = readerStyleForDocumentFormat(persistedUiState.style, persistedUiState.documentFormat)
                .resolveSystemTheme(systemInDarkTheme),
        )
    }

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
        onFontWeightChange = viewModel::updateFontWeight,
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
 * 리더의 세 가지 최상위 상태 중 무엇을 보여줄지 선택한다 — [ReaderUiState.isLoading]인 동안은 로딩
 * 인디케이터, [ReaderUiState.errorMessage]가 설정되어 있으면 에러 메시지, 그 외에는 실제 [ReaderContent]
 * — 그리고 나머지 모든 파라미터는 변경 없이 그대로 전달한다. [ReaderRouteScreen]과 마찬가지로 이
 * composable은 view model에 대한 순수한 상태·콜백 전달자다: 자체적인 리더 상태를 전혀 보유하지 않는다.
 *
 * @param uiState view model이 발행하는, 리더의 현재 상태.
 * @param onBack 사용자가 리더를 떠나려 할 때 호출된다.
 * @param onToggleControls 사용자가 탭하여 읽기 컨트롤을 보이거나 숨길 때 호출된다.
 * @param onPreviousPage 사용자가 뒤로 넘길 때, 되돌아갈 pane 수와 함께 호출된다.
 * @param onNextPage 사용자가 페이지를 넘길 때, 앞으로 나아갈 pane 수와 함께 호출된다.
 * @param onFavoriteToggle 사용자가 즐겨찾기/책갈피 별표를 토글하면 호출된다.
 * @param onActionSelected 액션 메뉴에서 선택된 항목과 함께 호출된다.
 * @param onOptionSheetSelected 옵션 바텀 시트 중 하나를 열도록 호출된다.
 * @param onDismissSheet 활성 옵션 시트나 드로어가 닫히면 호출된다.
 * @param onKeepScreenOnChange 화면 항상 켜기 설정이 토글되면 호출된다.
 * @param onFullscreenChange 전체 화면 설정이 토글되면 호출된다.
 * @param onShowProgressChange 진행률 표시 설정이 토글되면 호출된다.
 * @param onFontSizeChange 활자 크기가 확정되면 호출된다.
 * @param onLineHeightChange 줄 간격 배율이 확정되면 호출된다.
 * @param onFontFamilyChange 활자 패밀리가 바뀌면 호출된다; null은 기본값을 선택한다.
 * @param onFontWeightChange 활자 굵기가 300, 400, 500, 600 중 하나로 바뀌면 호출된다.
 * @param onThemeModeChange 리더 테마가 바뀌면 호출된다.
 * @param onPageTurnModeChange 페이지 넘김 방향이 바뀌면 호출된다.
 * @param onPageAnimationChange 페이지 넘김 애니메이션이 바뀌면 호출된다.
 * @param onAutoScrollEnabledChange 자동 스크롤이 켜지거나 꺼지면 호출된다.
 * @param onAutoScrollModeChange 자동 스크롤 모드가 바뀌면 호출된다.
 * @param onAutoScrollSpeedChange 자동 스크롤 속도가 확정되면 호출된다.
 * @param onAutoScrollToggle 하단 바의 자동 스크롤 토글에서 호출된다.
 * @param onGoToPage 0-기반 페이지 인덱스와 함께 호출되어 그 페이지로 이동한다.
 * @param onMoveToLocation 목차 등에서 이동할 위치와 함께 호출된다.
 * @param onBrightnessOverlayAlphaChange 밝기 오버레이의 alpha가 확정되면 호출된다.
 * @param onPageBreakerChanged pane이 텍스트 영역을 측정하여 현재 style과 viewport에 대한 page breaker를
 * 만들어내면 호출된다; 기본값은 아무 동작도 하지 않는다.
 * @param goToPageText "페이지로 이동" 시트의 현재 텍스트 필드 값.
 * @param onGoToPageTextChange 사용자가 "페이지로 이동" 필드에 입력하는 동안 호출된다.
 * @param brightnessDraft 밝기 슬라이더의, 아직 확정되지 않은 진행 중인 값.
 * @param onBrightnessDraftChange 밝기 슬라이더가 드래그되는 동안 호출된다.
 * @param fontSizeDraft 활자 크기 슬라이더의, 아직 확정되지 않은 진행 중인 값.
 * @param onFontSizeDraftChange 활자 크기 슬라이더가 드래그되는 동안 호출된다.
 * @param lineHeightPercentDraft 줄 간격 슬라이더의, 백분율로 나타낸 진행 중인 값.
 * @param onLineHeightPercentDraftChange 줄 간격 슬라이더가 드래그되는 동안 호출된다.
 * @param autoScrollSpeedDraft 자동 스크롤 속도 슬라이더의 진행 중인 값.
 * @param onAutoScrollSpeedDraftChange 자동 스크롤 속도 슬라이더가 드래그되는 동안 호출된다.
 * @param bottomSliderValue 하단 페이지 진행률 슬라이더의 현재 값.
 * @param onBottomSliderValueChange 하단 페이지 진행률 슬라이더가 드래그되는 동안 호출된다.
 * @param isActionMenuExpanded 상단 바의 오버플로 액션 메뉴가 열려 있는지 여부.
 * @param onActionMenuExpandedChange 액션 메뉴가 열리거나 닫히면 호출된다.
 * @param activeSheetScrollState 활성 옵션 시트 콘텐츠의 스크롤 상태.
 * @param batteryPercent 상태 표시줄에 표시되는 기기 배터리 퍼센트, 사용할 수 없으면 null.
 * @param modifier 세 상태 중 표시되는 것에 적용된다.
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
    onFontWeightChange: (Int) -> Unit,
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
            onFontWeightChange = onFontWeightChange,
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
 * [ReaderScreen]이 문서가 준비되었다고 판단한 뒤 실제 읽기 화면을 렌더링한다: 페이지 pager, 상단/하단
 * 컨트롤 바, 상태 표시줄, 목차 드로어, 밝기 오버레이, 그리고 활성화된 옵션 시트. 위의 [ReaderScreen]과
 * 마찬가지로 이 composable은 view model에 대한 순수한 상태·콜백 전달자다 — 여기 있는 로컬 `remember`들
 * (핀치/확대 transform, 드래그 제스처 상태, 페이지 이동 요청 큐)은 일시적인 제스처 관리용일 뿐, 리더
 * 상태의 두 번째 사본이 아니다.
 *
 * 로컬 `movePrevious`/`moveNext` 람다는 (로컬에서 계산된 `paneCount`인) 이동 폭만
 * [onPreviousPage]/[onNextPage]로 전달할 뿐, 목표 페이지 인덱스를 직접 결정하지 않는다. view model은
 * 이동이 실제로 실행될 때 그 시점에 유효한 페이지 나누기를 기준으로 이동 폭을 해석하므로, 탭과 이동 처리
 * 사이에 재페이지 나누기가 끼어들어도 잘못된 페이지가 선택되는 일이 없다.
 *
 * @param uiState view model이 발행하는, 리더의 현재 상태.
 * @param onBack 사용자가 리더를 떠나려 할 때 호출된다.
 * @param onToggleControls 사용자가 탭하여 읽기 컨트롤을 보이거나 숨길 때 호출된다.
 * @param onPreviousPage 사용자가 뒤로 넘길 때, 되돌아갈 pane 수와 함께 호출된다.
 * @param onNextPage 사용자가 페이지를 넘길 때, 앞으로 나아갈 pane 수와 함께 호출된다.
 * @param onFavoriteToggle 사용자가 즐겨찾기/책갈피 별표를 토글하면 호출된다.
 * @param onActionSelected 액션 메뉴에서 선택된 항목과 함께 호출된다.
 * @param onOptionSheetSelected 옵션 바텀 시트 중 하나를 열도록 호출된다.
 * @param onDismissSheet 활성 옵션 시트나 드로어가 닫히면 호출된다.
 * @param onKeepScreenOnChange 화면 항상 켜기 설정이 토글되면 호출된다.
 * @param onFullscreenChange 전체 화면 설정이 토글되면 호출된다.
 * @param onShowProgressChange 진행률 표시 설정이 토글되면 호출된다.
 * @param onFontSizeChange 활자 크기가 확정되면 호출된다.
 * @param onLineHeightChange 줄 간격 배율이 확정되면 호출된다.
 * @param onFontFamilyChange 활자 패밀리가 바뀌면 호출된다; null은 기본값을 선택한다.
 * @param onFontWeightChange 활자 굵기가 300, 400, 500, 600 중 하나로 바뀌면 호출된다.
 * @param onThemeModeChange 리더 테마가 바뀌면 호출된다.
 * @param onPageTurnModeChange 페이지 넘김 방향이 바뀌면 호출된다.
 * @param onPageAnimationChange 페이지 넘김 애니메이션이 바뀌면 호출된다.
 * @param onAutoScrollEnabledChange 자동 스크롤이 켜지거나 꺼지면 호출된다.
 * @param onAutoScrollModeChange 자동 스크롤 모드가 바뀌면 호출된다.
 * @param onAutoScrollSpeedChange 자동 스크롤 속도가 확정되면 호출된다.
 * @param onAutoScrollToggle 하단 바의 자동 스크롤 토글에서 호출된다.
 * @param onGoToPage 0-기반 페이지 인덱스와 함께 호출되어 그 페이지로 이동한다.
 * @param onMoveToLocation 목차 등에서 이동할 위치와 함께 호출된다.
 * @param onBrightnessOverlayAlphaChange 밝기 오버레이의 alpha가 확정되면 호출된다.
 * @param onPageBreakerChanged pane이 텍스트 영역을 측정하여 현재 style과 viewport에 대한 page breaker를
 * 만들어내면 호출된다.
 * @param goToPageText "페이지로 이동" 시트의 현재 텍스트 필드 값.
 * @param onGoToPageTextChange 사용자가 "페이지로 이동" 필드에 입력하는 동안 호출된다.
 * @param brightnessDraft 밝기 슬라이더의, 아직 확정되지 않은 진행 중인 값.
 * @param onBrightnessDraftChange 밝기 슬라이더가 드래그되는 동안 호출된다.
 * @param fontSizeDraft 활자 크기 슬라이더의, 아직 확정되지 않은 진행 중인 값.
 * @param onFontSizeDraftChange 활자 크기 슬라이더가 드래그되는 동안 호출된다.
 * @param lineHeightPercentDraft 줄 간격 슬라이더의, 백분율로 나타낸 진행 중인 값.
 * @param onLineHeightPercentDraftChange 줄 간격 슬라이더가 드래그되는 동안 호출된다.
 * @param autoScrollSpeedDraft 자동 스크롤 속도 슬라이더의 진행 중인 값.
 * @param onAutoScrollSpeedDraftChange 자동 스크롤 속도 슬라이더가 드래그되는 동안 호출된다.
 * @param bottomSliderValue 하단 페이지 진행률 슬라이더의 현재 값.
 * @param onBottomSliderValueChange 하단 페이지 진행률 슬라이더가 드래그되는 동안, 그리고 이
 * composable 자체의 effect가 밑바탕 페이지 인덱스가 바뀔 때마다 호출한다.
 * @param isActionMenuExpanded 상단 바의 오버플로 액션 메뉴가 열려 있는지 여부.
 * @param onActionMenuExpandedChange 액션 메뉴가 열리거나 닫히면 호출된다.
 * @param activeSheetScrollState 활성 옵션 시트 콘텐츠의 스크롤 상태.
 * @param batteryPercent 상태 표시줄에 표시되는 기기 배터리 퍼센트, 사용할 수 없으면 null.
 * @param modifier 읽기 화면의 루트에 적용된다.
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
    onFontWeightChange: (Int) -> Unit,
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
    // 로드된 퍼블리셔 패밀리는 일시적인 live/draw 활자 선택이 아니라 파일 집합에 묶어 둔다. 그러면 활자
    // 오버라이드가 퍼블리셔 활자 설정으로 다시 전환되더라도, 실제 패밀리가 준비되기 전에 대체 활자 측정
    // 결과가 새어 나가는 일이 없다.
    val resolvedEmbeddedFontFamilies by produceState<Pair<Map<String, String>, Map<String, FontFamily>>?>(
        initialValue = null,
        uiState.embeddedFontFiles,
    ) {
        val fontFiles = uiState.embeddedFontFiles
        value = null
        value = fontFiles to withContext(Dispatchers.Default) {
            loadReaderEmbeddedFontFamilies(fontFiles)
        }
    }
    val embeddedFontResolutionComplete = readerEmbeddedFontsReadyForMeasurement(
        style = uiState.style,
        areEmbeddedFontsResolved = uiState.areEmbeddedFontsResolved,
        embeddedFontFiles = uiState.embeddedFontFiles,
        loadedEmbeddedFontFiles = resolvedEmbeddedFontFamilies?.first,
    )
    val sharedEmbeddedFontFamilies = remember(resolvedEmbeddedFontFamilies) {
        resolvedEmbeddedFontFamilies?.second?.toImmutableMap() ?: persistentMapOf()
    }
    val failedResolvedFontHrefs = remember(
        uiState.embeddedFontFiles,
        embeddedFontResolutionComplete,
        sharedEmbeddedFontFamilies,
    ) {
        if (!embeddedFontResolutionComplete) persistentSetOf()
        else (uiState.embeddedFontFiles.keys - sharedEmbeddedFontFamilies.keys).toImmutableSet()
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
            // pdfZoom/pdfPan 은 핀치·이동 제스처가 포인터 프레임마다 갱신한다. 여기서 값을 꺼내
            // composition 단계에서 읽으면 이 BoxWithConstraints 본문 전체 — pager 래퍼, 컨트롤
            // AnimatedVisibility, 하단 바 배선 — 가 제스처 내내 프레임마다 재구성된다. 읽기는 아래
            // graphicsLayer 블록 안에 둔다: 그 블록은 placement 단계에서 자기 snapshot 관찰자와 함께
            // 다시 실행되므로, 재구성 없이 레이어만 갱신된다.
            val contentTransformModifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .graphicsLayer {
                    if (uiState.isVisualMode) {
                        scaleX = pdfZoom
                        scaleY = pdfZoom
                        translationX = pdfPan.x
                        translationY = pdfPan.y
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
                (uiState.pageDrawStyle.fontSizeSp * uiState.pageDrawStyle.lineHeightMultiplier).sp.toPx().coerceAtLeast(1f)
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
                        paperColor = uiState.style.readerColors().background,
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
                                    current = ReaderPdfTransform(zoom = pdfZoom, pan = pdfPan),
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
                                pdfTransform = { ReaderPdfTransform(zoom = pdfZoom, pan = pdfPan) },
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
                                chapterTitle = uiState.currentPage.chapterTitle
                                    .takeIf { uiState.documentFormat == DocumentFormat.EPUB },
                                chapterPageIndex = uiState.currentPage.chapterPageIndex
                                    .takeIf { uiState.documentFormat == DocumentFormat.EPUB },
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
                onFontWeightChange = onFontWeightChange,
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
 * 현재 형식이 요구하는 어떤 모드로든 — PDF 페이지, 만화/이미지 페이지, EPUB 페이지, 또는 순수 텍스트 페이지
 * — [page]로 주어진 페이지 인덱스에 대해 문서의 페이지 한 장을 렌더링한다. 현재 페이지 나누기 범위 밖의
 * 페이지(예: 마지막 페이지를 넘어선 2단 spread의 후행 pane)는 어떤 페이지 서피스에도 아무것도 그리라고
 * 요청하지 않고, 리더 배경만 그리고 즉시 반환한다.
 *
 * 순수 텍스트/EPUB 경로에서는, 여기가 측정된 레이아웃 크기가 view model에 도달하는 유일한 곳이다. 글리프
 * 전진폭과 줄 수를 추정하는 대신, 이 pane 자체가 측정한 텍스트 영역이 페이지 나누기가 직접 기준으로 삼는
 * 크기이며, 그래서 페이지는 정확히 이 pane이 실제로 렌더링하는 지점에서 나뉜다. 아래의 보고 effect는
 * breaker 하나만이 아니라 그 측정된 크기 *와* page breaker를 함께 키로 삼는다. breaker만 키로 삼으면, pane이
 * 아직 측정되기 전 breaker가 여전히 크기 0을 가지고 있던 첫 번째 패스의 effect가, 실제 측정값이 도착한 뒤
 * 다시 실행되어 그 낡은 크기 0짜리 breaker를 실제 크기와 짝지어 알리는 문제가 생겼다. 그 결과 모든 페이지가
 * 추정값으로 대체됐는데, 추정값은 책 자체 스타일시트가 설정한 줄 높이를 알 수 없으므로 페이지에는 실제로
 * 그려지는 것보다 절반이나 더 많은 줄이 채워져 나머지가 잘려나갔다. 둘을 함께 키로 삼으면 보고되는 breaker가
 * 항상 함께 보고되는 크기에 대해 실제로 만들어진 것임이 보장된다.
 *
 * 페이지 나누기와 저장된 page-layout 엔티티의 `viewportWidthPx`/`viewportHeightPx` 컬럼은 컬럼 이름과
 * 달리 둘 다 px가 아니라 **sp**를 기준으로 한다 — 그래서 [onPageBreakerChanged]로 보내는 너비/높이는
 * 보내기 전에 sp로 변환된다. 실제 측정된 픽셀 박스를 담은 두 번째, 별도의 [ViewportSize]도 함께 보내지는데,
 * 이는 오직 view model이 이미 응답한 보고를 알아차리게(중복 제거 가드) 하는 용도일 뿐 레이아웃 계산 자체에는
 * 쓰이지 않는다. 보고는 이제 정확히 이 effect 하나에서만 발생한다: 예전에는 하나의 리사이즈에 대해 두 개의
 * 별도 보고 — 이 effect와, `onSizeChanged`에서 오는 두 번째 viewport 콜백 — 가 각자 자신의 reload를
 * 실행했고, `Job.cancel()`은 첫 reload의 데이터베이스 읽기가 이미 진행 중이면 이를 멈출 수 없었기 때문에
 * 둘 다 반영되어 저장된 page layout이 저장소에서 두 번 복원되곤 했다. 여기서 한 번만 보고하는 것이 경쟁을
 * 더 빨리 취소하려 애쓰는 대신 그 경쟁 자체를 없앤다.
 *
 * 챕터 제목은 의도적으로 여기서 본문 위에 running head로 그리지 않는다: 그것은 챕터 자체 텍스트가 이미
 * 담고 있는 표제이며, 페이지 나누기는 모든 섹션을 새 페이지에서 시작하므로(`TextPageLayoutEngine.paginate`
 * 참고) 그 표제는 이미 스스로 페이지 맨 위에 자리 잡는다. 본문 위에 추가로 아무것도 그리지 않는 것이 바로
 * 그 규칙이 제목 중복을 만들어내지 않도록 지켜준다.
 *
 * 여기서는 서로 다른 두 가지 style이 중요하며, 이 둘을 절대 바꿔 쓰면 안 된다. [ReaderUiState.style] —
 * [ReaderUiState.pageDrawStyle]을 거치지 않고 직접 읽는다 — 은 [rememberReaderPageBreaker]가 측정하는
 * 기준이고, [onPageBreakerChanged]가 보고하는 것이며, 아래 텍스트 영역 주변 padding이 계산되는 기준이다:
 * 이것이 바로 [ReaderUiState.pageDrawStyle]이 감춰주는 낡은 조각 창(stale-slice window)을 끝낼 수 있는
 * 유일한 style이며, breaker가 실제로 측정하는 박스인 `textAreaPx`를 정의하는 것이다 — 둘 중 하나라도
 * drawn style에 고정하면 바뀐 설정에 대해 새 breaker가 절대 만들어지지 않거나(리더가 예전 활자에 영구히
 * 갇힘), 리더가 절대 그리지 않는 박스에 대한 측정값을 보고하게 된다. 이 함수가 렌더링하는 두 페이지 서피스
 * — [EpubPageSurface]와 `ReaderPageSurface` — 만이 [ReaderUiState.pageDrawStyle]로 그리므로, 이들이 그리는
 * 조각은 실제로 잘려나간 활자 설정보다 절대 한 발짝 앞서지 않는다.
 *
 * 내장 퍼블리셔 패밀리는 이 함수 위쪽에서 둘 중 어느 style에도 키를 걸지 않고 파일 집합 기준으로 유지된다.
 * live style이 퍼블리셔 활자 설정으로 다시 전환되면, 바로 그 파일들이 패밀리를 만들어낼 때까지 측정은 닫힌
 * 채로 유지된다; 그래서 [ReaderUiState.pageDrawStyle]은 새 breaker가 기다리는 동안에도 대체 활자 페이지
 * 레이아웃이 새어 나가는 일 없이 예전 오버라이드를 계속 그릴 수 있다.
 *
 * @param uiState 리더의 현재 상태; 이 pane이 렌더링하는 style, format, 페이지 콘텐츠를 제공한다.
 * @param page 이 pane이 보여줘야 할 0-기반 페이지 인덱스.
 * @param onPageBreakerChanged 이 pane이 0이 아닌 텍스트 영역을 측정하고 나면, 측정이 이루어진 style과
 * viewport 크기(sp, 그다음 실제 픽셀 박스), 그리고 결과로 나온 page breaker와 함께 호출된다.
 * @param reportViewportSize 이 pane이 측정한 크기를 [onPageBreakerChanged]에 보고해야 하는 pane인지
 * 여부; spread의 주(primary)가 아닌 pane은 자신의 것을 측정하고 보고하는 대신 주 pane의 페이지 나누기를
 * 공유하므로 false다.
 * @param windowInsets 이 pane의 콘텐츠 주위에 적용되는 inset.
 * @param contentPadding [windowInsets] 안쪽, 렌더링된 페이지 콘텐츠 주위에 적용되는 padding; null이면
 * 리더 텍스트 페이지 계약 `DESIGN.md`가 명시하는 테마의 `readerPageHorizontal` 가로 inset과
 * `readerPageVertical` 세로 inset으로 해석된다(`TeddReaderSpacing` 참고).
 * @param modifier 이 pane의 루트에 적용된다.
 */
internal fun readerPagePaneShouldMeasure(reportViewportSize: Boolean): Boolean = reportViewportSize

@Composable
private fun ReaderPagePane(
    uiState: ReaderUiState,
    page: Int,
    onPageBreakerChanged: (ReaderStyle, ViewportSize, ViewportSize, ReaderPageBreaker, Boolean) -> Unit,
    embeddedFontFamiliesByHref: ImmutableMap<String, FontFamily>,
    embeddedFontResolutionComplete: Boolean,
    failedResolvedFontHrefs: ImmutableSet<String>,
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
            documentUri = uiState.documentUri,
            imageBytes = uiState.visualPageImages[page],
            sourceUri = uiState.documentUri.takeIf { uiState.documentFormat == DocumentFormat.IMAGE },
            isFailed = page in uiState.failedVisualPages,
            modifier = modifier.fillMaxSize(),
        )

        else -> {
            var textAreaPx by remember { mutableStateOf(IntSize.Zero) }
            val currentSlot = uiState.pageSlot(page)
            val publisherPageMargins = uiState.publisherPageMargins
            val pageBreaker = if (readerPagePaneShouldMeasure(reportViewportSize)) {
                rememberReaderPageBreaker(
                    style = uiState.style,
                    widthPx = textAreaPx.width,
                    heightPx = textAreaPx.height,
                    embeddedFontFamiliesByHref = embeddedFontFamiliesByHref,
                    canMeasure = if (uiState.documentFormat == DocumentFormat.EPUB) {
                        // breaker는 책 전체를 측정하므로 게이트는 책 전체의 폰트를 함께 짊어진다 —
                        // 페이지 단위로만 검사하면 폰트가 없는 표지 페이지가 style이 적용된 모든
                        // 페이지의 게이트를 열어버릴 수 있었다.
                        embeddedFontResolutionComplete && (
                            currentSlot == null ||
                                canMeasureEpubPage(currentSlot, uiState.style, embeddedFontFamiliesByHref, failedResolvedFontHrefs)
                            )
                    } else {
                        true
                    },
                )
            } else {
                null
            }
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
                val drawStyle = uiState.pageDrawStyle
                if (uiState.documentFormat == DocumentFormat.EPUB) {
                    EpubPageSurface(
                        page = uiState.pageSlot(page) ?: ReaderPageUi(page = page),
                        style = drawStyle,
                        embeddedFontFamiliesByHref = embeddedFontFamiliesByHref,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    ReaderPageSurface(
                        text = uiState.pageTextFor(page),
                        style = drawStyle,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(0.dp),
                    )
                }
            }
        }
    }
}

/**
 * 리더 자체의 활자 크기를 1em으로 삼아 이 여백을 padding으로 나타낸 것.
 *
 * 책은 페이지 여백을 em 단위로 명시하는데, 이는 조판에 쓰인 활자 크기에 상대적이다 — 그래서 활자를 키우는
 * 리더는 브라우저에서 책 자체 스타일시트가 그렇게 하듯 여백도 함께 키운다.
 *
 * @receiver 변환할 여백.
 * @param fontSizeSp 리더의 활자 크기로, 1em에 해당하는 값.
 * @param fontScale 시스템 자체의 텍스트 배율로, 그 크기를 sp에서 dp로 바꾸는 데 쓰인다.
 * @return 텍스트 영역 주위에 적용할 padding.
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
 * 자동 스크롤의 페이지 넘김 주기를 구동한다: 자동 스크롤이 켜져 있고 현재 모드/애니메이션 조합이 픽셀이나
 * 줄 단위 스크롤이 아니라 페이지 전체를 넘겨서 진행하는 동안([readerAutoScrollPageMovement] 참고),
 * [autoScrollPageDelayMillis]만큼 기다린 뒤 다음 페이지 넘김을 요청한다. 더 나아갈 다음 페이지가 없어지면
 * 갈 곳 없는 이동을 요청하는 대신 [onStop]으로 스스로 멈춘다. effect의 키 중 하나라도 바뀌면 대기를
 * 재시작하므로, 속도 변경이나 이미 진행 중인 페이지 넘김이 두 번의 이동을 가깝게 연달아 일으키지 않는다.
 *
 * @param uiState 리더의 현재 상태; 활성 자동 스크롤 설정과 페이지 인덱스를 제공한다.
 * @param paneCount 페이지 넘김마다 보이는 pane 수(단일 페이지 모드에서는 1, spread에서는 2).
 * @param effectiveMode [readerEffectiveAutoScrollMode]가 이 형식이 지원할 수 없는 모드를 해석하고 난 뒤,
 * 실제로 적용 중인 자동 스크롤 모드.
 * @param pageAnimation 활성 페이지 넘김 애니메이션으로, 픽셀/줄 자동 스크롤 모드가 페이지 단위 넘김으로
 * 대체되는지를 결정한다.
 * @param onRequestPageMove 넘길 시점이 되면 수행할 페이지 이동과 함께 호출된다.
 * @param onStop 더 나아갈 페이지가 없어지면 자동 스크롤을 끄도록 호출된다.
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
 * 현재 열려 있는 리더 옵션 시트가 무엇이든 공유되는 [TeddModalBottomSheet] 안에 담아 호스팅하고,
 * [sheet]와 일치하는 시트 composable 하나로 분배한다. [sheet]/[uiState]/[onDismiss]를 제외한 모든
 * 파라미터는 특정 시트 하나에 속하며 그대로 전달될 뿐, 이 composable이 별도로 해석하지 않는다.
 *
 * @param sheet 어떤 옵션 시트를 보여줄지.
 * @param uiState 리더의 현재 상태로, 각 개별 시트가 읽는다.
 * @param onDismiss 시트를 닫도록 호출된다.
 * @param onKeepScreenOnChange [ViewOptionsSheet]로 전달된다.
 * @param onFullscreenChange [ViewOptionsSheet]로 전달된다.
 * @param onShowProgressChange [ViewOptionsSheet]와 [ControlOptionsSheet]로 전달된다.
 * @param pdfZoom [ViewOptionsSheet]로 전달된다.
 * @param onPdfZoomChange [ViewOptionsSheet]로 전달된다.
 * @param onFontSizeChange [FontOptionsSheet]로 전달된다.
 * @param onLineHeightChange [FontOptionsSheet]로 전달된다.
 * @param onFontFamilyChange [FontOptionsSheet]로 전달된다.
 * @param onFontWeightChange [FontOptionsSheet]로 전달된다.
 * @param onThemeModeChange [ThemeOptionsSheet]로 전달된다.
 * @param onPageTurnModeChange [PageTurnOptionsSheet]로 전달된다.
 * @param onPageAnimationChange [PageTurnOptionsSheet]로 전달된다.
 * @param onAutoScrollEnabledChange [AutoScrollOptionsSheet]로 전달된다.
 * @param onAutoScrollModeChange [AutoScrollOptionsSheet]로 전달된다.
 * @param onAutoScrollSpeedChange [AutoScrollOptionsSheet]로 전달된다.
 * @param onGoToPage [GoToPageSheet]로 전달된다.
 * @param onMoveToLocation [TableOfContentsSheet]로 전달된다.
 * @param onBrightnessOverlayAlphaChange [BrightnessOptionsSheet]로 전달된다.
 * @param goToPageText [GoToPageSheet]로 전달된다.
 * @param onGoToPageTextChange [GoToPageSheet]로 전달된다.
 * @param brightnessDraft [BrightnessOptionsSheet]로 전달된다.
 * @param onBrightnessDraftChange [BrightnessOptionsSheet]로 전달된다.
 * @param fontSizeDraft [FontOptionsSheet]로 전달된다.
 * @param onFontSizeDraftChange [FontOptionsSheet]로 전달된다.
 * @param lineHeightPercentDraft [FontOptionsSheet]로 전달된다.
 * @param onLineHeightPercentDraftChange [FontOptionsSheet]로 전달된다.
 * @param autoScrollSpeedDraft [AutoScrollOptionsSheet]로 전달된다.
 * @param onAutoScrollSpeedDraftChange [AutoScrollOptionsSheet]로 전달된다.
 * @param scrollState 시트 콘텐츠 컬럼의 스크롤 상태.
 * @param modifier 시트 콘텐츠 컬럼에 적용된다.
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
    onFontWeightChange: (Int) -> Unit,
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
                    onFontWeightChange = onFontWeightChange,
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
 * 사이드 드로어의 콘텐츠로 렌더링되는 목차로, `uiState.activeSheet == ReaderOptionSheet.TableOfContents`인
 * 동안 [ReaderContent]가 `ModalNavigationDrawer`를 통해 보여준다. 표제를 보여주고, 보여줄 것이 없으면 빈
 * 목록 대신 설명 메시지를 보여준다.
 *
 * @param uiState 리더의 현재 상태; 목록으로 나열할 아웃라인 항목을 제공한다.
 * @param onLocationClick 항목이 탭되면 이동할 위치와 함께 호출된다.
 * @param modifier 드로어의 콘텐츠 목록에 적용된다.
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
            itemsIndexed(uiState.outlineItems, key = { _, item -> item.location }) { index, item ->
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
 * 바텀 시트 콘텐츠로 렌더링되는 목차로, [ReaderOptionSheet.TableOfContents]에 대해
 * [ReaderActiveSheet]의 `when (sheet)` 분기에서 호출된다. `ReaderContent`는 현재 대신
 * [TableOfContentsDrawerContent]를 통해 목차를 드로어로 열며, [ReaderActiveSheet]를 호출하기도 전에
 * `ReaderOptionSheet.TableOfContents`를 걸러내므로, 이 composable의 분기는 실제로는 현재 실행되지 않는다.
 *
 * @param uiState 리더의 현재 상태; 목록으로 나열할 아웃라인 항목을 제공한다.
 * @param onLocationClick 항목이 탭되면 이동할 위치와 함께 호출된다.
 * @param modifier 시트의 콘텐츠 그룹에 적용된다.
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

/** 아웃라인 항목이 실제 라벨 대신 가질 수 있는 "Page 12"와 같은 일반적인, 지역화되지 않은 페이지 제목과
 * 일치한다; [displayTitle]은 일치하는 항목을 오늘의 지역화된 페이지 라벨로 대체하여, 이런 제목을 가진
 * 아웃라인도 사용자의 언어로 자연스럽게 읽히도록 한다. */
private val legacyPageOutlineTitlePattern = Regex("^Page \\d+$")

/** 아웃라인 항목이 실제 라벨 대신 가질 수 있는 "Section 3"과 같은 일반적인, 지역화되지 않은 섹션 제목과
 * 일치한다; [displayTitle]은 일치하는 항목을 오늘의 지역화된 섹션 라벨로 대체하며, 채워 넣을 섹션 번호를
 * 추출한다. */
private val legacySectionOutlineTitlePattern = Regex("^Section \\d+$")

/**
 * 이 아웃라인 항목에 표시할 제목: 일반적인 레거시 제목([legacyPageOutlineTitlePattern]과
 * [legacySectionOutlineTitlePattern] 참고)은 오늘의 지역화된 라벨로 대체되어 오래된 아웃라인과 새
 * 아웃라인이 일관되게 읽히도록 한다; 그 외의 제목은 문서 자체나 더 최근의 import 과정이 작성한 그대로
 * 표시된다.
 *
 * @receiver 표시를 위해 제목이 해석되고 있는 아웃라인 항목.
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
 * 이 위치에 대한, 지역화된 사람이 읽을 수 있는 라벨로, 일반적인 레거시 아웃라인 제목을 대신하기 위해
 * [displayTitle]이 사용한다. 각 형식의 위치는 서로 다른 종류의 실제 주소(PDF 페이지 인덱스, 순수 텍스트
 * 문자 오프셋, EPUB spine 인덱스)를 가지므로, 라벨은 하나의 공유 필드가 아니라 종류별로 만들어진다.
 *
 * @receiver 설명할 위치.
 */
@Composable
private fun com.tedd.teddreader.core.common.model.ReaderLocation.displayLabel(): String = when (this) {
    is ReaderLocation.PdfPage -> stringResource(Res.string.reader_location_page, pageIndex + 1)
    is ReaderLocation.TextOffset -> stringResource(Res.string.reader_location_text_position, offset + 1)
    is ReaderLocation.EpubOffset -> stringResource(Res.string.reader_location_epub_section, spineIndex + 1)
}

/**
 * "페이지로 이동" 시트: 1-기반 페이지 번호를 위한 텍스트 필드와 이동 버튼으로, 입력된 텍스트가 범위 안의
 * 페이지로 해석될 때만 활성화된다. 입력된 1-기반 페이지를 [onGoToPage]가 기대하는 0-기반 인덱스로 다시
 * 변환한다.
 *
 * @param uiState 리더의 현재 상태; 표시되는 범위와 입력값 clamp에 쓰이는 전체 페이지 수를 제공한다.
 * @param pageText 필드의 현재 텍스트로, 시트가 다시 열려도 유지되도록 호출자가 소유한다.
 * @param onPageTextChange 사용자가 입력하는 동안 호출된다.
 * @param onGoToPage 이동 버튼이 탭되면 해석된 0-기반 페이지 인덱스와 함께 호출된다.
 * @param modifier 시트의 콘텐츠 그룹에 적용된다.
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
 * 밝기 시트: 기기 자체의 화면 밝기를 건드리지 않고 검은 오버레이로 리더를 어둡게 하는 슬라이더 하나.
 * 슬라이더는 20~100% 범위여서 페이지가 완전한 검정으로 어두워지는 일이 절대 없다; 오버레이 alpha는 매
 * 중간값이 아니라 드래그가 끝났을 때 *확정된* 퍼센트에서만 유도되며, 가장 어두운 설정에서도 페이지
 * 콘텐츠 일부가 항상 보이도록 0.8로 clamp된다.
 *
 * @param brightnessDraft 슬라이더의, 아직 확정되지 않은 진행 중인 값.
 * @param onBrightnessDraftChange 슬라이더가 드래그되는 동안 호출된다.
 * @param onBrightnessOverlayAlphaChange 드래그가 끝나면 결과로 나온 오버레이 alpha와 함께 호출된다.
 * @param modifier 시트의 콘텐츠 그룹에 적용된다.
 */
@Composable
private fun BrightnessOptionsSheet(
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
        )
    }
}

/**
 * 화면 표시 시트: 모든 형식에 적용되는 화면 항상 켜기, 전체 화면, 진행률 표시 토글, 그리고 확대가
 * 텍스트 레이아웃 문제가 아니라 페이지 transform인 visual(PDF/이미지) 모드에서만 나타나는 확대 슬라이더.
 *
 * @param uiState 리더의 현재 상태; visual 모드가 활성인지를 결정한다.
 * @param pdfZoom 현재 visual 모드 확대 수준으로, 슬라이더로 표시되고 조정된다.
 * @param onPdfZoomChange 확대 슬라이더가 드래그되는 동안 호출된다.
 * @param onKeepScreenOnChange 화면 항상 켜기 스위치가 토글되면 호출된다.
 * @param onFullscreenChange 전체 화면 스위치가 토글되면 호출된다.
 * @param onShowProgressChange 진행률 표시 스위치가 토글되면 호출된다.
 * @param modifier 시트의 콘텐츠 그룹에 적용된다.
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
        TeddSwitchRow(stringResource(Res.string.keep_screen_on), uiState.keepScreenOn, onKeepScreenOnChange)
        TeddSwitchRow(stringResource(Res.string.fullscreen_reader), uiState.fullscreen, onFullscreenChange)
        TeddSwitchRow(stringResource(Res.string.show_progress), uiState.showProgress, onShowProgressChange)
        if (uiState.isVisualMode) {
            TeddSliderRow(
                title = stringResource(Res.string.visual_zoom),
                value = pdfZoom * 100f,
                onValueChange = { onPdfZoomChange(it / 100f) },
                onValueChangeFinished = null,
                valueRange = 100f..400f,
                steps = 11,
                valueLabel = "${(pdfZoom * 100f).roundToInt()}%",
            )
        }
    }
}

/**
 * 활자 설정 시트: 활자 크기와 줄 간격 슬라이더, 활자 패밀리 선택, 그리고 확정된 style이 아니라 진행 중인
 * 초안 값으로 만들어지는 실시간 미리보기 — 그래서 사용자는 슬라이더를 놓은 뒤가 아니라 드래그하는 동안
 * 결과를 볼 수 있다.
 *
 * @param uiState 리더의 현재 상태; 미리보기를 제외한 모든 것의 기준이 되는 확정된 style을 제공한다.
 * @param fontSizeDraft 활자 크기 슬라이더의, 아직 확정되지 않은 진행 중인 값.
 * @param onFontSizeDraftChange 활자 크기 슬라이더가 드래그되는 동안 호출된다.
 * @param lineHeightPercentDraft 줄 간격 슬라이더의, 백분율로 나타낸 진행 중인 값.
 * @param onLineHeightPercentDraftChange 줄 간격 슬라이더가 드래그되는 동안 호출된다.
 * @param onFontSizeChange 슬라이더 드래그가 끝나면 확정된 활자 크기와 함께 호출된다.
 * @param onLineHeightChange 슬라이더 드래그가 끝나면 확정된 줄 간격 배율과 함께 호출된다.
 * @param onFontFamilyChange 활자 패밀리 라디오 옵션이 선택되면 호출된다; null은 기본값을 선택한다.
 * @param onFontWeightChange 활자 굵기 라디오 옵션이 300, 400, 500, 600 중 하나로 선택되면 호출된다.
 * @param modifier 시트의 콘텐츠 그룹에 적용된다.
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
    onFontWeightChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val previewStyle = uiState.style.copy(
        fontSizeSp = fontSizeDraft,
        lineHeightMultiplier = lineHeightPercentDraft / 100f,
    )

    Column(modifier = modifier) {
        TeddOptionGroup(title = null) {
            TeddSliderRow(
                title = stringResource(Res.string.font_size),
                value = fontSizeDraft,
                onValueChange = onFontSizeDraftChange,
                onValueChangeFinished = { onFontSizeChange(fontSizeDraft) },
                valueRange = ReaderPinchFontSizeRange,
                steps = FontSizeSliderSteps,
                valueLabel = "${fontSizeDraft.roundToInt()}sp",
            )
            TeddSliderRow(
                title = stringResource(Res.string.line_height),
                value = lineHeightPercentDraft,
                onValueChange = onLineHeightPercentDraftChange,
                onValueChangeFinished = { onLineHeightChange(lineHeightPercentDraft / 100f) },
                valueRange = 100f..300f,
                steps = LineHeightSliderSteps,
                valueLabel = "${lineHeightPercentDraft.roundToInt()}%",
            )
        }
        TeddOptionGroup(
            title = stringResource(Res.string.font_family),
            description = stringResource(Res.string.font_family_description),
            isSelectableGroup = true,
        ) {
            TeddRadioRow(
                title = stringResource(Res.string.font_family_document),
                selected = uiState.style.fontFamilyName == null,
                onClick = { onFontFamilyChange(null) },
            )
            TeddRadioRow(
                title = stringResource(Res.string.font_family_sans),
                selected = uiState.style.fontFamilyName == "sans",
                onClick = { onFontFamilyChange("sans") },
            )
            TeddRadioRow(
                title = stringResource(Res.string.font_family_serif),
                selected = uiState.style.fontFamilyName == "serif",
                onClick = { onFontFamilyChange("serif") },
            )
            TeddRadioRow(
                title = stringResource(Res.string.font_family_mono),
                selected = uiState.style.fontFamilyName == "mono",
                onClick = { onFontFamilyChange("mono") },
            )
        }
        TeddOptionGroup(
            title = stringResource(Res.string.font_weight),
            description = stringResource(Res.string.font_weight_description),
            isSelectableGroup = true,
        ) {
            TeddRadioRow(
                title = stringResource(Res.string.font_weight_light),
                selected = uiState.style.fontWeight == 300,
                onClick = { onFontWeightChange(300) },
            )
            TeddRadioRow(
                title = stringResource(Res.string.font_weight_regular),
                selected = uiState.style.fontWeight == 400,
                onClick = { onFontWeightChange(400) },
            )
            TeddRadioRow(
                title = stringResource(Res.string.font_weight_medium),
                selected = uiState.style.fontWeight == 500,
                onClick = { onFontWeightChange(500) },
            )
            TeddRadioRow(
                title = stringResource(Res.string.font_weight_semibold),
                selected = uiState.style.fontWeight == 600,
                onClick = { onFontWeightChange(600) },
            )
        }
        ReaderOptionPreview(
            modifier = Modifier.padding(horizontal = spacing.medium),
            style = previewStyle,
            title = stringResource(Res.string.typography_preview_title),
            description = stringResource(Res.string.typography_preview_description),
            previewText = stringResource(Res.string.typography_preview_text),
        )
    }
}

/** 첫 번째 테마 행은 document와 system 대체 모드를 중복시키는 대신 형식에 맞춰 적응한다. */
internal fun readerThemeModeOptions(documentFormat: DocumentFormat): List<ReaderThemeMode> = listOf(
    if (documentFormat.hasPublisherAppearance) ReaderThemeMode.PUBLISHER else ReaderThemeMode.SYSTEM,
    ReaderThemeMode.LIGHT,
    ReaderThemeMode.DARK,
    ReaderThemeMode.SEPIA,
)

/** 레거시 document/system 값을 이 형식이 지원하는 단일 적응형 모드로 정규화한다. */
internal fun readerStyleForDocumentFormat(style: ReaderStyle, documentFormat: DocumentFormat): ReaderStyle =
    if (style.themeMode == ReaderThemeMode.PUBLISHER || style.themeMode == ReaderThemeMode.SYSTEM) {
        style.withThemeMode(
            if (documentFormat.hasPublisherAppearance) ReaderThemeMode.PUBLISHER else ReaderThemeMode.SYSTEM,
        )
    } else {
        style
    }

private val DocumentFormat.hasPublisherAppearance: Boolean
    get() = this == DocumentFormat.EPUB || this == DocumentFormat.PDF

internal fun readerEmbeddedFontsReadyForMeasurement(
    style: ReaderStyle,
    areEmbeddedFontsResolved: Boolean,
    embeddedFontFiles: Map<String, String>,
    loadedEmbeddedFontFiles: Map<String, String>?,
): Boolean =
    style.fontFamilyName != null ||
        (areEmbeddedFontsResolved && (
            embeddedFontFiles.isEmpty() || loadedEmbeddedFontFiles == embeddedFontFiles
        ))

/**
 * 테마 시트: 현재 style의 실시간 미리보기와 그 뒤로 이어지는, 이 문서 형식이 지원하는 선택지들.
 * [ReaderThemeMode.CUSTOM]은 선택 가능한 옵션이 아니라 저장된 상태로만 남는다.
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
        TeddOptionGroup(title = null, isSelectableGroup = true) {
            readerThemeModeOptions(uiState.documentFormat).forEach { mode ->
                TeddRadioRow(
                    title = mode.themeLabel(),
                    selected = uiState.style.themeMode == mode,
                    onClick = { onThemeModeChange(mode) },
                )
            }
        }
    }
}

/**
 * 페이지 넘김 시트: 넘김 방향([readerPageTurnModeOptions]), 기본 전환([readerDefaultTransitionOptions]),
 * 페이지 넘김 효과([readerPageEffectOptions])를 위한 세 개의 독립된 라디오 그룹 — 두 번째와 세 번째
 * 그룹은 둘 다 [ReaderUiState.pageAnimation]에 쓰는데, "기본 전환"과 "페이지 효과"가 서로 다른 두
 * 선택기로 제시되는 같은 밑바탕 설정이기 때문이다.
 *
 * @param uiState 리더의 현재 상태; 활성 방향과 애니메이션을 제공한다.
 * @param onPageTurnModeChange 방향 라디오 옵션이 선택되면 호출된다.
 * @param onPageAnimationChange 전환이나 효과 라디오 옵션이 선택되면 호출된다.
 * @param modifier 시트의 콘텐츠 컬럼에 적용된다.
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
                )
            }
        }
        TeddOptionGroup(title = stringResource(Res.string.default_transition)) {
            readerDefaultTransitionOptions.forEach { animation ->
                TeddRadioRow(
                    title = animation.pageAnimationLabel(),
                    selected = uiState.pageAnimation == animation,
                    onClick = { onPageAnimationChange(animation) },
                )
            }
        }
        TeddOptionGroup(title = stringResource(Res.string.page_effects)) {
            readerPageEffectOptions.forEach { animation ->
                TeddRadioRow(
                    title = animation.pageAnimationLabel(),
                    selected = uiState.pageAnimation == animation,
                    onClick = { onPageAnimationChange(animation) },
                )
            }
        }
    }
}

/**
 * 자동 스크롤 시트: 활성화 스위치, 모드 라디오 그룹, 속도 슬라이더. [AutoScrollMode.LINE] 옵션은
 * visual(PDF/이미지) 모드에서 비활성화되는데, 줄 단위 스크롤은 텍스트 레이아웃 개념이라 visual 페이지에는
 * 대응하는 것이 없기 때문이다; 그 케이스가 단지 제시되는 게 아니라 이미 활성 상태일 때 실제로 어떻게
 * 처리되는지는 [readerEffectiveAutoScrollMode]를 참고한다.
 *
 * @param uiState 리더의 현재 상태; 활성 설정과 visual 모드 활성 여부를 제공한다.
 * @param speedDraft 속도 슬라이더의, 아직 확정되지 않은 진행 중인 값.
 * @param onSpeedDraftChange 속도 슬라이더가 드래그되는 동안 호출된다.
 * @param onEnabledChange 활성화 스위치가 토글되면 호출된다.
 * @param onModeChange 모드 라디오 옵션이 선택되면 호출된다.
 * @param onSpeedChange 슬라이더 드래그가 끝나면 확정된 속도와 함께 호출된다.
 * @param modifier 시트의 콘텐츠 그룹에 적용된다.
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
        TeddSwitchRow(stringResource(Res.string.enabled), uiState.autoScrollConfig.enabled, onEnabledChange)
        AutoScrollMode.entries.forEach { mode ->
            val isModeEnabled = !(uiState.isVisualMode && mode == AutoScrollMode.LINE)
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
        )
    }
}

/**
 * 하단 바 시트: 현재는 읽기 진행률 표시가 하단 액션 바에 보이는지 여부를 위한 스위치 하나뿐이다.
 *
 * @param uiState 리더의 현재 상태; 진행률이 현재 표시되고 있는지를 제공한다.
 * @param onShowProgressChange 진행률 표시 스위치가 토글되면 호출된다.
 * @param modifier 시트의 콘텐츠 그룹에 적용된다.
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
        TeddSwitchRow(stringResource(Res.string.show_page_progress), uiState.showProgress, onShowProgressChange)
    }
}

/** [FontOptionsSheet]의 활자 크기 슬라이더에 쓰이는 `Slider`의 `steps` 값으로, 임의의 연속값이 아니라
 * [ReaderPinchFontSizeRange] 전체에 걸쳐 슬라이더가 정수 sp 단위로 스냅되도록 크기를 정했다. */
private const val FontSizeSliderSteps = 71

/** 드래그하는 동안 줄 간격 백분율 초안이 얼마나 거칠게 반올림되는지로, 확정값이 임의의 소수가 아니라
 * 항상 5%의 배수에 놓이도록 한다. */
private const val LineHeightStepPercent = 5f

/** [FontOptionsSheet]의 줄 간격 슬라이더에 쓰이는 `Slider`의 `steps` 값으로, 슬라이더의 100~300% 범위
 * 전체에 걸쳐 [LineHeightStepPercent]와 일치시켜 시각적 스냅 지점이 초안 반올림과 일치하도록 한다. */
private const val LineHeightSliderSteps = 39

/** [AutoScrollOptionsSheet]의 속도 슬라이더에 쓰이는 `Slider`의 `steps` 값으로, [AutoScrollConfig]의
 * 속도 범위 전체에 걸쳐 [Float.roundToHundredths]가 확정하는 정밀도와 일치하는 100분의 1 단위로
 * 슬라이더가 스냅되도록 크기를 정했다. */
private const val SpeedSliderSteps = 98

/** [PageTurnOptionsSheet]가 제공하는 넘김 방향 선택지들. [PageTurnMode.CONTINUOUS]는 style이 가질 수
 * 있는 모드로 존재하지만 여기서는 의도적으로 선택지로 제공되지 않는다 — [pageTurnLabel]이 이를
 * [PageTurnMode.VERTICAL]과 동일하게 렌더링하므로, 이 목록은 사용자가 실제로 선택하는 둘로 한정한다. */
internal val readerPageTurnModeOptions: List<PageTurnMode> = listOf(
    PageTurnMode.HORIZONTAL,
    PageTurnMode.VERTICAL,
)

/** [PageTurnOptionsSheet]가 "기본 전환" 아래 제공하는, 꾸밈없는 비장식적 전환들 — 평소 페이지를 넘길 때
 * 리더가 거슬려할 가능성이 낮은 애니메이션들이다. 이들과 함께 제공되는 더 화려한 대안은
 * [readerPageEffectOptions]를 참고한다. */
internal val readerDefaultTransitionOptions: List<PageAnimation> = listOf(
    PageAnimation.NONE,
    PageAnimation.SLIDE,
    PageAnimation.FADE,
    PageAnimation.SCROLL,
)

/** [PageTurnOptionsSheet]가 "페이지 효과" 아래 제공하는 장식적 페이지 넘김 효과들 — 두 그룹 모두 같은
 * [ReaderUiState.pageAnimation]에 쓰지만, 시트가 평범한 전환과 특별한 효과를 구분해 묶을 수 있도록
 * [readerDefaultTransitionOptions]와 별도로 둔다. */
internal val readerPageEffectOptions: List<PageAnimation> = listOf(
    PageAnimation.FLUID_PAGER,
    PageAnimation.CURL_PAGER,
    PageAnimation.THREE_D_CURL,
    PageAnimation.CIRCLE_REVEAL,
    PageAnimation.MOVIE_CAROUSEL,
    PageAnimation.PAGE_FLIP,
)

/** 리더 UI 어디서든 제공되는 모든 [PageAnimation]: [readerDefaultTransitionOptions]와
 * [readerPageEffectOptions]를 그 순서로 합친 것. */
internal val readerPageAnimationOptions: List<PageAnimation> =
    readerDefaultTransitionOptions + readerPageEffectOptions

/**
 * 이 속도를 가장 가까운 100분의 1로 스냅하고 [AutoScrollConfig]의 유효 범위로 clamp하여, 드래그 제스처의
 * 원시 float 값이 슬라이더의 [SpeedSliderSteps]가 실제로 표현할 수 있는 것보다 더 높은 정밀도로 확정되는
 * 일도, 모델이 받아들이는 범위 밖으로 확정되는 일도 없도록 한다.
 *
 * @receiver 슬라이더 드래그에서 온, 확정되지 않은 원시 속도값.
 */
private fun Float.roundToHundredths(): Float =
    (this * 100f).roundToInt().div(100f).coerceIn(AutoScrollConfig.MIN_SPEED, AutoScrollConfig.MAX_SPEED)

/**
 * [currentPage]에서 [paneCount] pane만큼 앞선 페이지 인덱스, 문서 끝을 넘어가게 되면 null — 리더에서 "다음
 * 페이지가 있는가"를 판단하는 모든 결정(자동 스크롤 정지, 하단 바의 다음 버튼, 스와이프 제스처)이 이
 * 공유된 범위 검사로부터 만들어진다.
 *
 * @param currentPage 현재 표시 중인 0-기반 페이지.
 * @param totalPages 문서의 전체 페이지 수.
 * @param paneCount 한 번의 넘김이 몇 페이지씩 나아가는지; 최소 1로 coerce된다.
 * @return 다음 페이지 인덱스, 없으면 null.
 */
internal fun readerNextPage(currentPage: Int, totalPages: Int, paneCount: Int): Int? =
    (currentPage + paneCount.coerceAtLeast(1)).takeIf { it in 0 until totalPages }

/**
 * visual(PDF/이미지) 모드에서는 [AutoScrollMode.LINE]을 [AutoScrollMode.PAGE]로 격하시킨다. 줄 단위
 * 스크롤은 텍스트 레이아웃 개념이라 visual 페이지에는 그런 개념이 없기 때문이다; 그 외의 모드는 그대로
 * 통과한다. 어떤 모드가 선택지로조차 제공되는지를 결정하는 [AutoScrollOptionsSheet]와 달리, 자동 스크롤이
 * 실제로 무엇을 할지를 결정하는 데 쓰인다.
 *
 * @param mode 사용자가 설정/선택한 모드.
 * @param isVisualMode 현재 문서가 (텍스트/EPUB이 아니라) PDF/이미지 모드인지 여부.
 * @return 자동 스크롤이 실제로 실행되어야 할 모드.
 */
internal fun readerEffectiveAutoScrollMode(mode: AutoScrollMode, isVisualMode: Boolean): AutoScrollMode =
    if (isVisualMode && mode == AutoScrollMode.LINE) AutoScrollMode.PAGE else mode

/**
 * 주어진 모드/애니메이션 조합에 대해 [ReaderAutoScrollEffect]가 페이지 전체 넘김을 요청해서 자동 스크롤을
 * 구동해야 하는지, 그렇다면 어느 방향인지. 이미 자체적으로 계속 스크롤되거나 넘어가는 애니메이션(예:
 * [PageAnimation.SCROLL]이나 curl/flip 효과)과 짝지어진 [AutoScrollMode.PIXEL]/[AutoScrollMode.LINE]에
 * 대해서는 null을 반환한다 — 그런 애니메이션은 내부적으로 자체 연속 자동 스크롤을 구동하며, 그 위에 페이지
 * 넘김을 또 요청하면 두 배로 겹치게 된다.
 *
 * @param mode [readerEffectiveAutoScrollMode]가 해석하고 난, 실제 적용되는 자동 스크롤 모드.
 * @param pageAnimation 활성 페이지 넘김 애니메이션.
 * @return 요청할 페이지 이동, 이 조합이 페이지 전체 넘김을 전혀 구동하면 안 되면 null.
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
            PageAnimation.THREE_D_CURL,
                -> null

            PageAnimation.NONE,
            PageAnimation.FADE,
                -> ReaderPageMovement.Next
        }
}

/**
 * [AutoScrollMode.PAGE]에서 [ReaderAutoScrollEffect]가 다음 페이지 전체 넘김을 요청하기 전에 얼마나
 * 기다려야 하는지. [speed]에 반비례하여, 속도 설정이 높을수록 넘김 사이 대기 시간이 짧아진다.
 *
 * @param speed 설정된 자동 스크롤 속도; 사용 전에 [AutoScrollConfig]의 유효 범위로 clamp된다.
 * @return 지연 시간(밀리초).
 */
internal fun autoScrollPageDelayMillis(speed: Float): Long =
    (1_000f / AutoScrollConfig.clampSpeed(speed)).toLong()

/**
 * 연속적인 [AutoScrollMode.PIXEL] 스크롤이 한 프레임 경과 시간 동안 몇 픽셀 나아가야 하는지. [density]로
 * 스케일되어, 같은 [speed] 설정이 화면 밀도와 무관하게 시각적으로 일관된 거리만큼 스크롤되도록 한다.
 *
 * @param speed 설정된 자동 스크롤 속도; 사용 전에 [AutoScrollConfig]의 유효 범위로 clamp된다.
 * @param density 디스플레이의 픽셀 밀도.
 * @param elapsedMillis 마지막 프레임 이후 경과한 시간.
 * @return 스크롤할 거리(픽셀).
 */
internal fun autoScrollDistancePx(speed: Float, density: Float, elapsedMillis: Long): Float =
    200f *
        density.coerceAtLeast(0f) *
        AutoScrollConfig.clampSpeed(speed) *
        (elapsedMillis.coerceAtLeast(0L) / 1_000f)

/**
 * [AutoScrollMode.LINE]에서 줄 단위 점프 사이에 얼마나 기다릴지. [autoScrollDistancePx]의 초당 픽셀
 * 속도로부터 유도되어, 같은 [speed]의 픽셀 스크롤이 한 줄 높이를 가로지르는 데 걸리는 것과 같은 시간에
 * 한 줄을 가로지르도록 하여, 두 모드의 체감 속도를 일관되게 유지한다.
 *
 * @param lineHeightPx 한 줄의 높이(픽셀).
 * @param pixelsPerSecond 맞춰야 할 픽셀 스크롤 속도로, [autoScrollDistancePx]의 초당 값; 0으로 나누지
 * 않도록 최소 1로 coerce된다.
 * @return 지연 시간(밀리초).
 */
internal fun autoScrollLineDelayMillis(lineHeightPx: Float, pixelsPerSecond: Float): Long =
    ((lineHeightPx.coerceAtLeast(0f) / pixelsPerSecond.coerceAtLeast(1f)) * 1_000f).toLong()

/**
 * 원시 페이지 인덱스/합계를, 하단 바와 상태 표시줄이 실제로 표시하는 spread(즉 [paneCount]페이지씩 묶은
 * 그룹) 인덱스/합계로 변환하여, 2단 spread가 리더가 페이지 번호를 하나 걸러 건너뛰는 것처럼 보이는 대신
 * 하나의 단위로 번호가 매겨지고 진행되도록 한다.
 *
 * @param currentPage 현재 표시 중인 0-기반 원시 페이지.
 * @param totalPages 문서의 전체 원시 페이지 수.
 * @param paneCount 한 spread를 이루는 원시 페이지 수.
 * @return 현재/전체 spread 인덱스, 아직 페이지가 없으면 모두 0인 [PageIndex].
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
 * [readerSpreadPageIndex]의 역함수: 사용자가 하단 바 슬라이더에서 고른 spread 인덱스를, 실제로 이동할
 * 원시 페이지로 다시 변환하여, 슬라이더를 드래그하면 페이지 나누기에 해당 인덱스가 없는 지점이 아니라
 * 선택된 spread의 첫 원시 페이지에 도달하도록 한다.
 *
 * @param selectedSpread 슬라이더에서 선택된 spread 인덱스; 범위로 clamp된다.
 * @param totalPages 문서의 전체 원시 페이지 수.
 * @param paneCount 한 spread를 이루는 원시 페이지 수.
 * @return 이동할 원시 페이지 인덱스, 아직 페이지가 없으면 0.
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
 * 텍스트 문서에 대해 [ReaderStatusFooter]의 진행률 표시가 보여주는 백분율로, 계속 늘어나는 페이지 합계가
 * 아니라 현재 위치의 절대 텍스트 오프셋을 기준으로 한다.
 *
 * @param location 현재의 절대 읽기 위치, 알려져 있다면.
 * @param characterCount 최종 문서 문자 수, import가 아직 끝나지 않았으면 null.
 * @param currentPercent 최종 문자 수가 아직 알려지지 않았을 때 유지할, 이미 발행된 진행률.
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

/** visual 페이지 형식에 대해 [ReaderStatusFooter]가 보여주는 백분율로, 여전히 페이지 기준이다. */
internal fun readerVisualReadProgressPercent(pageIndex: PageIndex): Int =
    if (pageIndex.total == 0) {
        0
    } else {
        (((pageIndex.current + 1f) / pageIndex.total) * 100f).roundToInt().coerceIn(0, 100)
    }

/**
 * 주어진 페이지의 렌더링된 콘텐츠를 먼저 명시적인 [ReaderUiState.pageSlots] 목록에서, 그다음
 * 이전/현재/다음 단일 페이지 캐시에서 찾는다 — 그래서 호출자는 현재 문서 형식이나 로딩 상태가 마침
 * 그중 어느 것을 쓰고 있는지 알 필요가 없다.
 *
 * @receiver 리더의 현재 상태.
 * @param page 조회할 0-기반 페이지 인덱스.
 * @return 페이지의 콘텐츠, 이 인덱스를 아직 아무것도 로드하지 않았으면 null.
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
 * [page]를, 이 상태의 합계를 담은 [PageIndex]의 *현재* 페이지로 다시 해석한다. 범위로 clamp된다 — 리더
 * 전체가 아니라 자기 자신을 나타내는 [PageIndex]가 필요한 pane에서 쓰인다(예: [PdfPageSurface]에 특정
 * 페이지를 건네줄 때).
 *
 * @receiver 리더의 현재 상태; 전체 페이지 수를 제공한다.
 * @param page 나타낼 0-기반 페이지 인덱스.
 */
private fun ReaderUiState.pageIndexFor(page: Int): PageIndex {
    if (pageIndex.total <= 0) return pageIndex
    return PageIndex(
        current = page.coerceIn(0, pageIndex.total - 1),
        total = pageIndex.total,
    )
}

/**
 * 주어진 페이지에 대해 표시할 순수 텍스트: [pageSlot]에 텍스트가 있으면 그것, 아니면 이 페이지가 *현재*
 * 페이지라면 리더가 현재 로드해 둔 [ReaderUiState.pageText](진짜로 빈 페이지에는 플레이스홀더 문자열을
 * 사용), 슬롯에 아직 로드되지 않은 다른 페이지라면 빈 문자열.
 *
 * @receiver 리더의 현재 상태.
 * @param page 조회할 0-기반 페이지 인덱스.
 * @return 페이지의 텍스트, 아직 아무것도 로드되지 않았으면 빈 문자열.
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
 * [ThemeOptionsSheet]가 이 테마 모드에 대해 보여주는 지역화된 라벨. [ReaderThemeMode.CUSTOM]은 시트가
 * 선택지로 제공하는 일이 절대 없어도 여기서 라벨을 가지는데, 그래야 이 함수가 호출자 쪽 대체값을 필요로
 * 하는 대신 enum 전체에 대해 total하게 유지되기 때문이다.
 *
 * @receiver 라벨을 붙일 테마 모드.
 */
@Composable
private fun ReaderThemeMode.themeLabel(): String = when (this) {
    ReaderThemeMode.PUBLISHER,
    ReaderThemeMode.SYSTEM,
        -> stringResource(Res.string.system_style)
    ReaderThemeMode.LIGHT -> stringResource(Res.string.light)
    ReaderThemeMode.DARK -> stringResource(Res.string.dark)
    ReaderThemeMode.SEPIA -> stringResource(Res.string.sepia)
    ReaderThemeMode.CUSTOM -> stringResource(Res.string.custom)
}

/**
 * [PageTurnOptionsSheet]가 이 방향에 대해 보여주는 지역화된 라벨. [PageTurnMode.CONTINUOUS]는 자기만의
 * 라벨을 갖는 대신 [PageTurnMode.VERTICAL]의 라벨을 공유하는데, [readerPageTurnModeOptions]에서 별도
 * 선택지로 제공되지 않으며 사용자가 실제로 이 분기를 보게 되는 일이 절대 없어야 하기 때문이다.
 *
 * @receiver 라벨을 붙일 페이지 넘김 방향.
 */
@Composable
private fun PageTurnMode.pageTurnLabel(): String = when (this) {
    PageTurnMode.HORIZONTAL -> stringResource(Res.string.horizontal_pages)
    PageTurnMode.VERTICAL,
    PageTurnMode.CONTINUOUS,
        -> stringResource(Res.string.vertical_pages)
}

/**
 * [PageTurnOptionsSheet]가 이 애니메이션에 대해 보여주는 지역화된 라벨. 여러 enum 값이 의도적으로 하나의
 * 라벨을 공유한다 — [PageAnimation.SLIDE]/[PageAnimation.SHEET_FLIP]은 둘 다 "slide"로,
 * [PageAnimation.BOOK_CURL]/[PageAnimation.CURL_PAGER]는 둘 다 "curl"로 읽힌다 — 사용자가 같은 시각
 * 효과로 경험하는 것의 서로 다른 두 구현일 뿐, 이름으로 구분할 가치가 있는 두 선택지가 아니기 때문이다.
 *
 * @receiver 라벨을 붙일 페이지 넘김 애니메이션.
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
    PageAnimation.THREE_D_CURL -> stringResource(Res.string.animation_three_d_curl)
    PageAnimation.FLUID_PAGER -> stringResource(Res.string.animation_fluid_pager)
    PageAnimation.CIRCLE_REVEAL -> stringResource(Res.string.animation_circle_reveal)
    PageAnimation.MOVIE_CAROUSEL -> stringResource(Res.string.animation_movie_carousel)
    PageAnimation.PAGE_FLIP -> stringResource(Res.string.animation_page_flip)
}

/**
 * [AutoScrollOptionsSheet]가 이 모드에 대해 보여주는 지역화된 라벨.
 *
 * @receiver 라벨을 붙일 자동 스크롤 모드.
 */
@Composable
private fun AutoScrollMode.autoScrollLabel(): String = when (this) {
    AutoScrollMode.PIXEL -> stringResource(Res.string.auto_scroll_smooth_scroll)
    AutoScrollMode.LINE -> stringResource(Res.string.auto_scroll_line_text_only)
    AutoScrollMode.PAGE -> stringResource(Res.string.auto_scroll_page_by_page)
}

/**
 * [ReaderActiveSheet]가 이 시트에 대해 공유 바텀 시트의 타이틀 바에 붙이는 지역화된 제목.
 *
 * @receiver 제목을 붙일 시트.
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
 * 리더의 에러 상태: [ReaderUiState.errorMessage]가 설정되어 있을 때마다 읽기 화면 대신 [ReaderScreen]이
 * 보여주는, 가운데 정렬된 메시지.
 *
 * @param message 표시할 에러 텍스트로, view model이 이미 해석/지역화해 둔 것.
 * @param modifier 메시지의 컨테이너에 적용된다.
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
 * 아래 `@Preview` composable들을 위한 최소한의 [ReaderUiState] 빌더로, 각 미리보기가 전체 상태를 직접
 * 구성하는 대신 바꾸고 싶은 몇몇 필드의 이름만 대면 되도록 한다. 별도 설정 없이도 미리보기에서 라틴
 * 문자와 한글 글리프 렌더링이 모두 보이도록 기본값은 두 언어를 섞은 예시 텍스트다.
 *
 * @param documentTitle 상단 바와 상태 표시줄에 표시된다.
 * @param pageText 현재 페이지의 순수 텍스트.
 * @param style 미리 볼 리더 style(테마, 활자 등).
 * @param isControlsVisible 상단/하단 컨트롤 바를 보여줘야 하는지 여부.
 * @param activeSheet 이미 열려 있는 것으로 보여줄 옵션 시트, 없으면 null.
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
 * 드래그 제스처가 touch slop을 넘어서는 순간 읽기 컨트롤을 숨겨서, 페이지를 넘기거나 확대된 화면을
 * 이동시키려는 스와이프가 같은 동작 안에서 상단/하단 바도 함께 사라지게 한다. 별도의 탭이 필요 없다.
 * 드래그에서는 항상 컨트롤을 *숨기기만* 한다 — 절대 보여주지 않으며, [gestureBlocked]가 다른
 * 제스처(예: 핀치/확대)가 이미 이 포인터 시퀀스를 처리하고 있다고 알리면 전혀 동작하지 않아서, 두
 * 제스처 핸들러가 같은 터치를 두고 다투지 않는다. [PointerEventPass.Initial]을 사용하여 이 관찰이
 * pager 자체의 하위 스와이프/탭 감지보다 먼저 일어나며, 이벤트를 소비하여 그 감지가 이벤트를 보지
 * 못하게 막지 않는다.
 *
 * [readerPinchZoomGesture]와 같은 이유로 `Modifier.composed { }`가 아니라 `@Composable` factory이다:
 * `composed`는 modifier 비교에 불투명해서, 이 구간이 재사용되는 대신 페이지 콘텐츠가
 * recomposition될 때마다 다시 구체화됐을 것이다.
 *
 * @receiver 이 포인터 입력을 이어붙일 modifier.
 * @param controlsVisible 컨트롤이 지금 보이고 있는지 여부; 이전의, 여전히 실행 중인 제스처 클로저가
 * 캡처한 값이 낡아버리지 않도록 [rememberUpdatedState]를 통해 각 제스처가 시작될 때마다 새로 읽는다.
 * @param gestureBlocked 다른 제스처 핸들러가 이미 활성 상태여서 이 핸들러가 물러나야 하는지 여부.
 * @param onToggleControls 드래그가 touch slop을 넘어서면 컨트롤을 숨기도록 호출된다.
 */
@Composable
private fun Modifier.readerControlsDragObserver(
    controlsVisible: Boolean,
    gestureBlocked: Boolean,
    onToggleControls: () -> Unit,
): Modifier {
    val latestControlsVisible by rememberUpdatedState(controlsVisible)
    val latestGestureBlocked by rememberUpdatedState(gestureBlocked)
    val latestOnToggleControls by rememberUpdatedState(onToggleControls)

    return pointerInput(Unit) {
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
 * 컨트롤이 보이는 상태로, 좁은 폰 너비(360x720)에서 한글/영문이 섞인 긴 문서 제목으로 리더를 미리
 * 본다 — 두 언어가 섞인 긴 제목이야말로 상단 바 제목 잘림과 혼합 스크립트 줄 측정이 가장 깨지기 쉬운
 * 지점이며, 짧은 단일 스크립트 자리표시자 제목으로는 둘 다 전혀 드러나지 않으므로 별도로 미리 볼 가치가
 * 있다.
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
            onFontWeightChange = {},
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
 * 상단/하단 컨트롤 바와 상태 표시줄을 숨긴(`isControlsVisible = false`) 상태로 리더를 미리 본다 — 이는
 * 사용자가 chrome을 닫으려고 탭하고 나면 리더의 기본 읽기 상태이며, 컨트롤이 보이는 미리보기를 기준으로
 * 만든 레이아웃 변경이 그 바들이 차지하던 공간이 되돌려졌을 때 페이지 콘텐츠를 실수로 어긋나게 두기
 * 쉬우므로 별도로 미리 볼 가치가 있다.
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
            onFontWeightChange = {},
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
 * 다크 [TeddReaderTheme] 아래에서 [darkReaderStyle]로 리더를 미리 본다 — 리더의 텍스트/배경 색상은 앱
 * 테마가 아니라 리더 style에서 오므로, 여기 다른 모든 미리보기가 사용하는 라이트 기본 style에서는 그런
 * 다크 모드 회귀(예: 새 composable에 하드코딩된 밝은 색상이 슬쩍 끼어드는 경우)가 전혀 드러나지 않아서
 * 별도로 미리 볼 가치가 있다.
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
            onFontWeightChange = {},
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
 * 위의 다른 미리보기들이 고정한 360x720 폰 크기가 아니라 Compose Preview 자체의 기본 기기 크기에서,
 * [previewReaderUiState]를 거치지 않고 직접 만든 순수 영문 기본 [ReaderUiState]로 리더를 미리 본다 —
 * 그 헬퍼의 기본값이 나중에 바뀌더라도 고정되지 않은 다른 기기 크기에서의 완전한 기본 구성에 대한
 * 커버리지가 조용히 사라지지 않도록 별도로 유지할 가치가 있다.
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
            onFontWeightChange = {},
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

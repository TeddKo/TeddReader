package com.tedd.teddreader.feature.settings.impl

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.resolveSystemTheme
import com.tedd.teddreader.core.common.model.withThemeMode
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddOptionGroup
import com.tedd.teddreader.core.ui.component.TeddRadioRow
import com.tedd.teddreader.core.ui.component.TeddSliderRow
import com.tedd.teddreader.core.ui.component.TeddSwitchRow
import com.tedd.teddreader.core.ui.reader.ReaderOptionPreview
import com.tedd.teddreader.core.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * 리더 설정 화면의 콘텐츠다. 앱 언어, 읽기 모양새(글꼴, 줄 높이, 글꼴 모음, 글꼴 굵기, 테마),
 * 페이지 넘김 방향, 전환 및 페이지 효과 애니메이션, 핸즈프리 자동 스크롤 옵션을 실시간
 * [ReaderOptionPreview] 아래에서 [TeddOptionGroup]의 스크롤 가능한 열로 배치한다. 이 컴포저블은
 * 설정 뷰 모델에 상태와 콜백을 그대로 전달한다. 자체적으로 확정된 설정을 보관하지 않고
 * [uiState]를 렌더링하며, 모든 변경을 아래 6개 콜백 중 하나로 다시 알린다.
 * [ReaderSettingsUiState.isLoading]이 true이면 설정 그룹을 만들기 전에 로딩 표시기를 대신
 * 보여주고 반환한다.
 *
 * `fontSizeDraft`, `lineHeightPercentDraft`, `autoScrollSpeedDraft`는 임시 값이다. 각각 [uiState]의
 * 확정된 해당 값을 키로 사용해 `remember`하므로 현재 제스처 외부에서 확정값이 바뀔 때마다
 * 그 값에 다시 맞춰진다. 그 사이에는 해당 슬라이더를 드래그 중인 값을 보관한다. 드래그가 끝나
 * `onValueChangeFinished`가 [onStyleChange] 또는 [onAutoScrollConfigChange]로 임시 값을 확정할
 * 때까지 [uiState] 자체는 그대로 유지된다. `previewStyle`은 두 스타일 임시 값으로 만들어지므로
 * 어느 값도 확정되기 전에 슬라이더 위의 실시간 미리보기가 드래그를 따라간다.
 *
 * @param uiState 뷰 모델이 게시한 설정 화면의 현재 상태.
 * @param onStyleChange 스타일에 영향을 주는 컨트롤(글꼴 크기, 줄 높이, 글꼴 모음, 테마)의 값이
 * 확정되면 저장할 리더 스타일과 함께 호출할 콜백.
 * @param onPageTurnModeChange 페이지 방향 라디오 행이 변경될 때 호출할 콜백.
 * @param onPageAnimationChange 기본 전환 또는 페이지 효과 라디오 행이 변경될 때 호출할 콜백.
 * 두 그룹 모두 같은 [ReaderSettingsUiState.pageAnimation]을 기록한다.
 * @param onAutoScrollConfigChange 자동 스크롤 활성화 스위치나 모드가 변경되거나 속도 슬라이더의
 * 값이 확정될 때 호출할 콜백.
 * @param onAppLanguageChange 앱 언어 라디오 행이 변경될 때 호출할 콜백.
 * @param modifier 루트 [Column]에 적용하며, [ReaderSettingsUiState.isLoading]이 true이면 로딩
 * 표시기에 적용할 값.
 */
@Composable
fun ReaderSettingsSheet(
    uiState: ReaderSettingsUiState,
    onStyleChange: (ReaderStyle) -> Unit,
    onPageTurnModeChange: (PageTurnMode) -> Unit,
    onPageAnimationChange: (PageAnimation) -> Unit,
    onAutoScrollConfigChange: (AutoScrollConfig) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    if (uiState.isLoading) {
        TeddLoadingIndicator(
            message = stringResource(Res.string.loading_reader_settings),
            modifier = modifier,
        )
        return
    }

    var fontSizeDraft by remember(uiState.style.fontSizeSp) {
        mutableFloatStateOf(uiState.style.fontSizeSp)
    }
    var lineHeightPercentDraft by remember(uiState.style.lineHeightMultiplier) {
        mutableFloatStateOf(uiState.style.lineHeightMultiplier * 100f)
    }
    var autoScrollSpeedDraft by remember(uiState.autoScrollConfig.speed) {
        mutableFloatStateOf(uiState.autoScrollConfig.speed)
    }
    val previewStyle = uiState.style
        .copy(
            fontSizeSp = fontSizeDraft,
            lineHeightMultiplier = lineHeightPercentDraft / 100f,
        )
        .resolveSystemTheme(isSystemInDarkTheme())

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.sectionGap),
    ) {
        ReaderOptionPreview(
            style = previewStyle,
            title = stringResource(Res.string.reader_preview_title),
            description = stringResource(Res.string.reader_preview_description),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenPadding),
        )

        TeddOptionGroup(
            title = stringResource(Res.string.app_language),
            description = stringResource(Res.string.app_language_description),
            isSelectableGroup = true,
        ) {
            AppLanguage.entries.forEach { language ->
                TeddRadioRow(
                    title = language.displayName(),
                    selected = uiState.appLanguage == language,
                    onClick = { onAppLanguageChange(language) },
                )
            }
        }

        TeddOptionGroup(
            title = stringResource(Res.string.reading_appearance),
            description = stringResource(Res.string.reading_appearance_description),
        ) {
            TeddSliderRow(
                title = stringResource(Res.string.font_size),
                value = fontSizeDraft,
                onValueChange = { fontSizeDraft = it.roundToInt().toFloat() },
                onValueChangeFinished = {
                    onStyleChange(uiState.style.copy(fontSizeSp = fontSizeDraft))
                },
                valueRange = 8f..80f,
                steps = FontSizeSliderSteps,
                valueLabel = "${fontSizeDraft.roundToInt()}sp",
            )
            TeddSliderRow(
                title = stringResource(Res.string.line_height),
                value = lineHeightPercentDraft,
                onValueChange = {
                    lineHeightPercentDraft = (it / LineHeightStepPercent).roundToInt() * LineHeightStepPercent
                },
                onValueChangeFinished = {
                    onStyleChange(uiState.style.copy(lineHeightMultiplier = lineHeightPercentDraft / 100f))
                },
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
                onClick = { onStyleChange(uiState.style.copy(fontFamilyName = null)) },
            )
            TeddRadioRow(
                title = stringResource(Res.string.font_family_sans),
                selected = uiState.style.fontFamilyName == "sans",
                onClick = { onStyleChange(uiState.style.copy(fontFamilyName = "sans")) },
            )
            TeddRadioRow(
                title = stringResource(Res.string.font_family_serif),
                selected = uiState.style.fontFamilyName == "serif",
                onClick = { onStyleChange(uiState.style.copy(fontFamilyName = "serif")) },
            )
            TeddRadioRow(
                title = stringResource(Res.string.font_family_mono),
                selected = uiState.style.fontFamilyName == "mono",
                onClick = { onStyleChange(uiState.style.copy(fontFamilyName = "mono")) },
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
                onClick = { onStyleChange(uiState.style.copy(fontWeight = 300)) },
            )
            TeddRadioRow(
                title = stringResource(Res.string.font_weight_regular),
                selected = uiState.style.fontWeight == 400,
                onClick = { onStyleChange(uiState.style.copy(fontWeight = 400)) },
            )
            TeddRadioRow(
                title = stringResource(Res.string.font_weight_medium),
                selected = uiState.style.fontWeight == 500,
                onClick = { onStyleChange(uiState.style.copy(fontWeight = 500)) },
            )
            TeddRadioRow(
                title = stringResource(Res.string.font_weight_semibold),
                selected = uiState.style.fontWeight == 600,
                onClick = { onStyleChange(uiState.style.copy(fontWeight = 600)) },
            )
        }

        TeddOptionGroup(
            title = stringResource(Res.string.theme),
            description = stringResource(Res.string.theme_description),
            isSelectableGroup = true,
        ) {
            listOf(
                ReaderThemeMode.PUBLISHER,
                ReaderThemeMode.LIGHT,
                ReaderThemeMode.DARK,
                ReaderThemeMode.SEPIA,
            ).forEach { mode ->
                TeddRadioRow(
                    title = mode.displayName(),
                    selected = uiState.style.themeMode == mode ||
                        (mode == ReaderThemeMode.PUBLISHER && uiState.style.themeMode == ReaderThemeMode.SYSTEM),
                    onClick = { onStyleChange(uiState.style.withThemeMode(mode)) },
                )
            }
        }

        TeddOptionGroup(
            title = stringResource(Res.string.page_direction),
            description = stringResource(Res.string.page_movement_description),
            isSelectableGroup = true,
        ) {
            listOf(PageTurnMode.HORIZONTAL, PageTurnMode.VERTICAL).forEach { mode ->
                TeddRadioRow(
                    title = mode.displayName(),
                    selected = uiState.pageTurnMode == mode,
                    onClick = { onPageTurnModeChange(mode) },
                )
            }
        }

        TeddOptionGroup(
            title = stringResource(Res.string.default_transition),
            isSelectableGroup = true,
        ) {
            settingsDefaultTransitionOptions.forEach { animation ->
                TeddRadioRow(
                    title = animation.displayName(),
                    selected = uiState.pageAnimation == animation,
                    onClick = { onPageAnimationChange(animation) },
                )
            }
        }

        TeddOptionGroup(
            title = stringResource(Res.string.page_effects),
            isSelectableGroup = true,
        ) {
            settingsPageEffectOptions.forEach { animation ->
                TeddRadioRow(
                    title = animation.displayName(),
                    selected = uiState.pageAnimation == animation,
                    onClick = { onPageAnimationChange(animation) },
                )
            }
        }

        TeddOptionGroup(
            title = stringResource(Res.string.hands_free_reading),
            description = stringResource(Res.string.hands_free_reading_description),
        ) {
            TeddSwitchRow(
                title = stringResource(Res.string.enabled),
                checked = uiState.autoScrollConfig.enabled,
                onCheckedChange = { enabled ->
                    onAutoScrollConfigChange(uiState.autoScrollConfig.copy(enabled = enabled))
                },
            )
            AutoScrollMode.entries.forEach { mode ->
                TeddRadioRow(
                    title = mode.displayName(),
                    selected = uiState.autoScrollConfig.mode == mode,
                    onClick = { onAutoScrollConfigChange(uiState.autoScrollConfig.copy(mode = mode)) },
                )
            }
            TeddSliderRow(
                title = stringResource(Res.string.speed),
                value = autoScrollSpeedDraft,
                onValueChange = { autoScrollSpeedDraft = it.roundToHundredths() },
                onValueChangeFinished = {
                    onAutoScrollConfigChange(uiState.autoScrollConfig.copy(speed = autoScrollSpeedDraft))
                },
                valueRange = AutoScrollConfig.MIN_SPEED..AutoScrollConfig.MAX_SPEED,
                steps = SpeedSliderSteps,
            )
        }
    }
}

/** 이 시트의 글꼴 크기 슬라이더에 사용하는 `Slider`의 `steps` 값이다. 임의의 연속 값이
 * 아니라 슬라이더의 8-80sp 범위에서 정수 sp 단위로 맞춰지도록 정한다. */
private const val FontSizeSliderSteps = 71

/** 드래그 중 줄 높이 백분율 임시 값을 반올림하는 간격이다. 확정값이 임의의 소수가 아니라
 * 항상 5%의 배수가 되도록 한다. */
private const val LineHeightStepPercent = 5f

/** 이 시트의 줄 높이 슬라이더에 사용하는 `Slider`의 `steps` 값이다. 시각적 맞춤 지점과 임시 값의
 * 반올림이 일치하도록 슬라이더의 100-300% 범위에서 [LineHeightStepPercent]에 맞춘다. */
private const val LineHeightSliderSteps = 39

/** 이 시트의 자동 스크롤 속도 슬라이더에 사용하는 `Slider`의 `steps` 값이다.
 * [Float.roundToHundredths]가 확정하는 정밀도에 맞춰 [AutoScrollConfig]의 속도 범위에서
 * 0.01 단위로 맞춰지도록 정한다. */
private const val SpeedSliderSteps = 98

/** "기본 전환" 옵션 그룹에서 제공하는 단순하고 장식 없는 전환 목록이다. 일상적인 페이지 넘김에서
 * 독자의 주의를 분산시킬 가능성이 낮은 애니메이션을 담는다. 아래 그룹에서 제공하는 더 화려한
 * 대안은 [settingsPageEffectOptions]를 참고한다. */
private val settingsDefaultTransitionOptions = listOf(
    PageAnimation.NONE,
    PageAnimation.SLIDE,
    PageAnimation.FADE,
    PageAnimation.SCROLL,
)

/** "페이지 효과" 옵션 그룹에서 제공하는 장식적인 페이지 넘김 효과 목록이다. 두 그룹이 같은
 * [ReaderSettingsUiState.pageAnimation]을 기록하더라도 일상적인 전환과 색다른 효과를 별도로
 * 묶기 위해 [settingsDefaultTransitionOptions]와 분리한다. */
private val settingsPageEffectOptions = listOf(
    PageAnimation.FLUID_PAGER,
    PageAnimation.CURL_PAGER,
    PageAnimation.THREE_D_CURL,
    PageAnimation.CIRCLE_REVEAL,
    PageAnimation.MOVIE_CAROUSEL,
    PageAnimation.PAGE_FLIP,
)

/**
 * 앱 언어 라디오 그룹에서 이 언어에 표시할 라벨이다. 언어 이름은 앱이 현재 표시하는 로케일과
 * 무관하게 그 언어 자체에서 항상 같은 방식으로 표기되므로 [AppLanguage.ENGLISH]와
 * [AppLanguage.KOREAN]은 문자열 리소스 대신 하드코딩한 리터럴을 사용한다. 언어 이름이 아니라
 * 선택지를 설명하는 [AppLanguage.SYSTEM]만 번역이 필요하다.
 *
 * @receiver 라벨을 표시할 언어.
 */
@Composable
private fun AppLanguage.displayName(): String = when (this) {
    AppLanguage.SYSTEM -> stringResource(Res.string.system_default)
    AppLanguage.ENGLISH -> "English"
    AppLanguage.KOREAN -> "한국어"
}

/**
 * 읽기 모양새 그룹의 테마 라디오 행에서 이 테마 모드에 표시할 라벨이다. 해당 그룹은
 * [ReaderThemeMode.CUSTOM]을 선택지로 제공하지 않지만 여기에는 그 라벨도 정의한다. 따라서
 * 호출 측의 대체 처리 없이 이 함수가 열거형의 모든 값에 대한 결과를 제공한다.
 *
 * @receiver 라벨을 표시할 테마 모드.
 */
@Composable
private fun ReaderThemeMode.displayName(): String = when (this) {
    ReaderThemeMode.PUBLISHER,
    ReaderThemeMode.SYSTEM,
        -> stringResource(Res.string.system_style)
    ReaderThemeMode.LIGHT -> stringResource(Res.string.light)
    ReaderThemeMode.DARK -> stringResource(Res.string.dark)
    ReaderThemeMode.SEPIA -> stringResource(Res.string.sepia)
    ReaderThemeMode.CUSTOM -> stringResource(Res.string.custom)
}

/**
 * 페이지 방향 라디오 그룹에서 이 방향에 표시할 라벨이다. 위의 페이지 방향 그룹은
 * [PageTurnMode.HORIZONTAL]과 [PageTurnMode.VERTICAL]만 선택지로 제공하므로 사용자가 실제로
 * 이 분기를 볼 일은 없다. 이에 따라 [PageTurnMode.CONTINUOUS]에는 별도 라벨을 두지 않고
 * [PageTurnMode.VERTICAL]의 라벨을 함께 사용한다.
 *
 * @receiver 라벨을 표시할 페이지 넘김 방향.
 */
@Composable
private fun PageTurnMode.displayName(): String = when (this) {
    PageTurnMode.HORIZONTAL -> stringResource(Res.string.horizontal)
    PageTurnMode.VERTICAL,
    PageTurnMode.CONTINUOUS,
        -> stringResource(Res.string.vertical)
}

/**
 * 기본 전환과 페이지 효과 라디오 그룹 모두에서 이 애니메이션에 표시할 라벨이다. 여러 열거형 값은
 * 의도적으로 같은 라벨을 사용한다. [PageAnimation.SLIDE]와 [PageAnimation.SHEET_FLIP]은 모두
 * "슬라이드"로, [PageAnimation.BOOK_CURL]과 [PageAnimation.CURL_PAGER]는 모두 "컬"로 표시한다.
 * 사용자가 같은 시각 효과로 경험하는 두 구현이므로 이름으로 구분할 가치가 있는 별도 선택지가
 * 아니기 때문이다.
 *
 * @receiver 라벨을 표시할 페이지 넘김 애니메이션.
 */
@Composable
private fun PageAnimation.displayName(): String = when (this) {
    PageAnimation.NONE -> stringResource(Res.string.animation_none)
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
 * 핸즈프리 읽기 그룹의 자동 스크롤 라디오 행에서 이 모드에 표시할 라벨이다.
 *
 * @receiver 라벨을 표시할 자동 스크롤 모드.
 */
@Composable
private fun AutoScrollMode.displayName(): String = when (this) {
    AutoScrollMode.PIXEL -> stringResource(Res.string.auto_scroll_smooth)
    AutoScrollMode.LINE -> stringResource(Res.string.auto_scroll_line_by_line)
    AutoScrollMode.PAGE -> stringResource(Res.string.auto_scroll_page_by_page)
}

/**
 * 이 속도를 가장 가까운 0.01 단위에 맞추고 [AutoScrollConfig]의 유효 범위로 제한한다. 따라서
 * 드래그 제스처의 가공되지 않은 부동소수점 값이 슬라이더의 [SpeedSliderSteps]로 실제 표현할 수 있는 것보다
 * 정밀한 속도나 모델이 허용하는 범위 밖의 속도로 확정되지 않는다.
 *
 * @receiver 슬라이더 드래그에서 받은 가공되지 않은 진행 중 속도 값.
 */
private fun Float.roundToHundredths(): Float =
    (this * 100f).roundToInt().div(100f).coerceIn(AutoScrollConfig.MIN_SPEED, AutoScrollConfig.MAX_SPEED)

/**
 * 시트 콘텐츠가 렌더링할 수 있는 컴팩트, 기본, 와이드 레이아웃을 확인하는 세 가지 너비의
 * [ReaderSettingsSheet] 미리보기다.
 */
@Preview(widthDp = 280)
@Preview(widthDp = 360)
@Preview(widthDp = 720)
@Composable
private fun ReaderSettingsSheetPreview() {
    TeddReaderTheme {
        ReaderSettingsSheet(
            uiState = ReaderSettingsUiState(isLoading = false),
            onStyleChange = {},
            onPageTurnModeChange = {},
            onPageAnimationChange = {},
            onAutoScrollConfigChange = {},
            onAppLanguageChange = {},
        )
    }
}

package com.tedd.teddreader.feature.settings.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.tedd.teddreader.core.common.model.withThemeMode
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddOptionGroup
import com.tedd.teddreader.core.ui.component.TeddRadioRow
import com.tedd.teddreader.core.ui.component.TeddSliderRow
import com.tedd.teddreader.core.ui.component.TeddSwitchRow
import com.tedd.teddreader.core.ui.reader.ReaderOptionPreview
import com.tedd.teddreader.core.ui.teddString
import kotlin.math.roundToInt

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
            message = teddString("Loading reader settings", "리더 설정을 불러오는 중"),
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
    val previewStyle = uiState.style.copy(
        fontSizeSp = fontSizeDraft,
        lineHeightMultiplier = lineHeightPercentDraft / 100f,
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        ReaderOptionPreview(
            style = previewStyle,
            title = teddString("Reader preview", "리더 미리보기"),
            description = teddString("Current reading appearance.", "현재 읽기 모양입니다."),
            modifier = Modifier.fillMaxWidth(),
        )

        TeddOptionGroup(
            title = teddString("App language", "앱 언어"),
            description = teddString(
                "Choose the language used across the app.",
                "앱 전체에서 사용할 언어를 선택하세요.",
            ),
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
            title = teddString("Reading appearance", "읽기 모양"),
            description = teddString(
                "Text choices that affect comfort and readability.",
                "읽기 편안함과 가독성에 영향을 주는 텍스트 설정입니다.",
            ),
        ) {
            TeddSliderRow(
                title = teddString("Font size", "글자 크기"),
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
                title = teddString("Line height", "줄 높이"),
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
            TeddRadioRow(
                title = teddString("Sans", "고딕"),
                selected = uiState.style.fontFamilyName == null || uiState.style.fontFamilyName == "sans",
                onClick = { onStyleChange(uiState.style.copy(fontFamilyName = null)) },
            )
            TeddRadioRow(
                title = teddString("Serif", "세리프"),
                selected = uiState.style.fontFamilyName == "serif",
                onClick = { onStyleChange(uiState.style.copy(fontFamilyName = "serif")) },
            )
            TeddRadioRow(
                title = teddString("Mono", "고정폭"),
                selected = uiState.style.fontFamilyName == "mono",
                onClick = { onStyleChange(uiState.style.copy(fontFamilyName = "mono")) },
            )
            listOf(
                ReaderThemeMode.SYSTEM,
                ReaderThemeMode.LIGHT,
                ReaderThemeMode.DARK,
                ReaderThemeMode.SEPIA,
            ).forEach { mode ->
                TeddRadioRow(
                    title = mode.displayName(),
                    selected = uiState.style.themeMode == mode,
                    onClick = { onStyleChange(uiState.style.withThemeMode(mode)) },
                )
            }
        }

        TeddOptionGroup(
            title = teddString("Page movement", "페이지 이동"),
            description = teddString(
                "How pages move when you read and navigate.",
                "읽고 이동할 때 페이지가 움직이는 방식을 설정합니다.",
            ),
        ) {
            listOf(PageTurnMode.HORIZONTAL, PageTurnMode.VERTICAL).forEach { mode ->
                TeddRadioRow(
                    title = mode.displayName(),
                    selected = uiState.pageTurnMode == mode,
                    onClick = { onPageTurnModeChange(mode) },
                )
            }
            settingsPageAnimationOptions.forEach { animation ->
                TeddRadioRow(
                    title = animation.displayName(),
                    selected = uiState.pageAnimation == animation,
                    onClick = { onPageAnimationChange(animation) },
                )
            }
        }

        TeddOptionGroup(
            title = teddString("Hands-free reading", "핸즈프리 읽기"),
            description = teddString(
                "Automatic movement when you want the document to keep going.",
                "문서를 자동으로 계속 읽고 싶을 때의 이동 설정입니다.",
            ),
        ) {
            TeddSwitchRow(
                title = teddString("Enabled", "사용"),
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
                title = teddString("Speed", "속도"),
                value = autoScrollSpeedDraft,
                onValueChange = { autoScrollSpeedDraft = it.roundToTenths() },
                onValueChangeFinished = {
                    onAutoScrollConfigChange(uiState.autoScrollConfig.copy(speed = autoScrollSpeedDraft))
                },
                valueRange = AutoScrollConfig.MIN_SPEED..AutoScrollConfig.MAX_SPEED,
                steps = SpeedSliderSteps,
                valueLabel = "${autoScrollSpeedDraft.roundToTenths()}x",
            )
        }
    }
}

private const val FontSizeSliderSteps = 71
private const val LineHeightStepPercent = 5f
private const val LineHeightSliderSteps = 39
private const val SpeedSliderSteps = 8

private val settingsPageAnimationOptions = listOf(
    PageAnimation.NONE,
    PageAnimation.SLIDE,
    PageAnimation.FADE,
    PageAnimation.SCROLL,
    PageAnimation.FLUID_PAGER,
    PageAnimation.CURL_PAGER,
    PageAnimation.CIRCLE_REVEAL,
    PageAnimation.MOVIE_CAROUSEL,
    PageAnimation.PAGE_FLIP,
)

@Composable
private fun AppLanguage.displayName(): String = when (this) {
    AppLanguage.SYSTEM -> teddString("System default", "시스템 기본")
    AppLanguage.ENGLISH -> "English"
    AppLanguage.KOREAN -> "한국어"
}

@Composable
private fun ReaderThemeMode.displayName(): String = when (this) {
    ReaderThemeMode.SYSTEM -> teddString("Follow system", "시스템 설정 따름")
    ReaderThemeMode.LIGHT -> teddString("Light", "라이트")
    ReaderThemeMode.DARK -> teddString("Dark", "다크")
    ReaderThemeMode.SEPIA -> teddString("Sepia", "세피아")
    ReaderThemeMode.CUSTOM -> teddString("Custom", "사용자 지정")
}

@Composable
private fun PageTurnMode.displayName(): String = when (this) {
    PageTurnMode.HORIZONTAL -> teddString("Horizontal", "가로")
    PageTurnMode.VERTICAL,
    PageTurnMode.CONTINUOUS,
        -> teddString("Vertical", "세로")
}

@Composable
private fun PageAnimation.displayName(): String = when (this) {
    PageAnimation.NONE -> teddString("None", "없음")
    PageAnimation.SLIDE,
    PageAnimation.SHEET_FLIP,
        -> teddString("Slide", "슬라이드")
    PageAnimation.FADE -> teddString("Fade", "페이드")
    PageAnimation.SCROLL -> teddString("Scroll", "스크롤")
    PageAnimation.BOOK_CURL,
    PageAnimation.CURL_PAGER,
        -> teddString("Curl pager", "컬 페이지")
    PageAnimation.FLUID_PAGER -> teddString("Fluid pager", "플루이드 페이지")
    PageAnimation.CIRCLE_REVEAL -> teddString("Circle reveal", "원형 전환")
    PageAnimation.MOVIE_CAROUSEL -> teddString("Movie carousel", "무비 캐러셀")
    PageAnimation.PAGE_FLIP -> teddString("Page flip", "페이지 플립")
}

@Composable
private fun AutoScrollMode.displayName(): String = when (this) {
    AutoScrollMode.PIXEL -> teddString("Smooth", "부드럽게")
    AutoScrollMode.LINE -> teddString("Line by line", "한 줄씩")
    AutoScrollMode.PAGE -> teddString("Page by page", "페이지별")
}

private fun Float.roundToTenths(): Float =
    (this * 10f).roundToInt().div(10f).coerceIn(AutoScrollConfig.MIN_SPEED, AutoScrollConfig.MAX_SPEED)

@Preview(widthDp = 280)
@Preview(widthDp = 360)
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

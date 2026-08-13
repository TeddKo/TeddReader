package com.tedd.teddreader.feature.settings.impl

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
import com.tedd.teddreader.core.common.model.withThemeMode
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
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
            title = stringResource(Res.string.reader_preview_title),
            description = stringResource(Res.string.reader_preview_description),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DefaultTeddReaderSpacing.screenPadding),
        )

        TeddOptionGroup(
            title = stringResource(Res.string.app_language),
            description = stringResource(Res.string.app_language_description),
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
            TeddRadioRow(
                title = stringResource(Res.string.font_family_sans),
                selected = uiState.style.fontFamilyName == null || uiState.style.fontFamilyName == "sans",
                onClick = { onStyleChange(uiState.style.copy(fontFamilyName = null)) },
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
            title = stringResource(Res.string.page_direction),
            description = stringResource(Res.string.page_movement_description),
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

private const val FontSizeSliderSteps = 71
private const val LineHeightStepPercent = 5f
private const val LineHeightSliderSteps = 39
private const val SpeedSliderSteps = 98

private val settingsDefaultTransitionOptions = listOf(
    PageAnimation.NONE,
    PageAnimation.SLIDE,
    PageAnimation.FADE,
    PageAnimation.SCROLL,
)

private val settingsPageEffectOptions = listOf(
    PageAnimation.FLUID_PAGER,
    PageAnimation.CURL_PAGER,
    PageAnimation.CIRCLE_REVEAL,
    PageAnimation.MOVIE_CAROUSEL,
    PageAnimation.PAGE_FLIP,
)

@Composable
private fun AppLanguage.displayName(): String = when (this) {
    AppLanguage.SYSTEM -> stringResource(Res.string.system_default)
    AppLanguage.ENGLISH -> "English"
    AppLanguage.KOREAN -> "한국어"
}

@Composable
private fun ReaderThemeMode.displayName(): String = when (this) {
    ReaderThemeMode.SYSTEM -> stringResource(Res.string.follow_system)
    ReaderThemeMode.LIGHT -> stringResource(Res.string.light)
    ReaderThemeMode.DARK -> stringResource(Res.string.dark)
    ReaderThemeMode.SEPIA -> stringResource(Res.string.sepia)
    ReaderThemeMode.CUSTOM -> stringResource(Res.string.custom)
}

@Composable
private fun PageTurnMode.displayName(): String = when (this) {
    PageTurnMode.HORIZONTAL -> stringResource(Res.string.horizontal)
    PageTurnMode.VERTICAL,
    PageTurnMode.CONTINUOUS,
        -> stringResource(Res.string.vertical)
}

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
    PageAnimation.FLUID_PAGER -> stringResource(Res.string.animation_fluid_pager)
    PageAnimation.CIRCLE_REVEAL -> stringResource(Res.string.animation_circle_reveal)
    PageAnimation.MOVIE_CAROUSEL -> stringResource(Res.string.animation_movie_carousel)
    PageAnimation.PAGE_FLIP -> stringResource(Res.string.animation_page_flip)
}

@Composable
private fun AutoScrollMode.displayName(): String = when (this) {
    AutoScrollMode.PIXEL -> stringResource(Res.string.auto_scroll_smooth)
    AutoScrollMode.LINE -> stringResource(Res.string.auto_scroll_line_by_line)
    AutoScrollMode.PAGE -> stringResource(Res.string.auto_scroll_page_by_page)
}

private fun Float.roundToHundredths(): Float =
    (this * 100f).roundToInt().div(100f).coerceIn(AutoScrollConfig.MIN_SPEED, AutoScrollConfig.MAX_SPEED)

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

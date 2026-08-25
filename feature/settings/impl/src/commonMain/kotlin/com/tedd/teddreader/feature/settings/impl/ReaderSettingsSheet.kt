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
 * The reader settings screen's content: app language, reading appearance (font, line height, font
 * family, font weight, theme), page-turn direction, transition and page-effect animation, and hands-free
 * auto-scroll options, laid out as a scrolling column of [TeddOptionGroup]s beneath a live
 * [ReaderOptionPreview]. This composable is a pure state-and-callback pass-through to the settings
 * view model — it renders [uiState] and reports every change back through one of the six
 * callbacks below, holding no committed setting of its own. While
 * [ReaderSettingsUiState.isLoading] is true it shows a loading indicator instead and returns
 * before any of the settings groups are built.
 *
 * `fontSizeDraft`, `lineHeightPercentDraft`, and `autoScrollSpeedDraft` are drafts: each is
 * `remember`ed keyed on its own committed value from [uiState], so it snaps back to match whenever
 * that committed value changes from outside the current gesture, and in between it holds the
 * corresponding slider's in-progress drag value — [uiState] itself is left untouched until the
 * drag ends and `onValueChangeFinished` commits the draft through [onStyleChange] or
 * [onAutoScrollConfigChange]. `previewStyle` is built from the two style drafts so the live preview
 * above the sliders tracks the drag before either draft is committed.
 *
 * @param uiState The settings screen's current state, as published by the view model.
 * @param onStyleChange Invoked with the reader style to commit once a style-affecting control
 * (font size, line height, font family, or theme) settles on a value.
 * @param onPageTurnModeChange Invoked when the page-direction radio row is changed.
 * @param onPageAnimationChange Invoked when either the default-transition or page-effects radio
 * row is changed; both groups write the same [ReaderSettingsUiState.pageAnimation].
 * @param onAutoScrollConfigChange Invoked when the auto-scroll enabled switch or mode is changed,
 * or when the speed slider settles on a value.
 * @param onAppLanguageChange Invoked when the app-language radio row is changed.
 * @param modifier Applied to the root [Column], or to the loading indicator while
 * [ReaderSettingsUiState.isLoading] is true.
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

/** The `Slider` `steps` value for this sheet's font size slider, sized so it snaps to whole-sp
 * increments across the slider's 8-80sp range rather than an arbitrary continuous value. */
private const val FontSizeSliderSteps = 71

/** How coarsely the line-height percentage draft is rounded while dragging, so the committed value
 * always lands on a multiple of 5% instead of an arbitrary fraction. */
private const val LineHeightStepPercent = 5f

/** The `Slider` `steps` value for this sheet's line height slider, matching [LineHeightStepPercent]
 * across the slider's 100-300% range so the visual snap points agree with the draft rounding. */
private const val LineHeightSliderSteps = 39

/** The `Slider` `steps` value for this sheet's auto-scroll speed slider, sized so it snaps to
 * hundredths across [AutoScrollConfig]'s speed range, matching the precision
 * [Float.roundToHundredths] commits at. */
private const val SpeedSliderSteps = 98

/** The plain, non-decorative transitions offered under the "default transition" option group —
 * animations a reader is unlikely to find distracting for everyday page turns. See
 * [settingsPageEffectOptions] for the showier alternatives offered in the group below. */
private val settingsDefaultTransitionOptions = listOf(
    PageAnimation.NONE,
    PageAnimation.SLIDE,
    PageAnimation.FADE,
    PageAnimation.SCROLL,
)

/** The decorative page-turn effects offered under the "page effects" option group, kept separate
 * from [settingsDefaultTransitionOptions] so everyday transitions are grouped apart from novelty
 * ones, even though both groups write the same [ReaderSettingsUiState.pageAnimation]. */
private val settingsPageEffectOptions = listOf(
    PageAnimation.FLUID_PAGER,
    PageAnimation.CURL_PAGER,
    PageAnimation.CIRCLE_REVEAL,
    PageAnimation.MOVIE_CAROUSEL,
    PageAnimation.PAGE_FLIP,
)

/**
 * The label shown for this language in the app-language radio group. [AppLanguage.ENGLISH] and
 * [AppLanguage.KOREAN] are hardcoded literals rather than string resources because a language
 * names itself the same way regardless of which locale the app is currently displaying in; only
 * [AppLanguage.SYSTEM] describes a choice rather than naming a language, so only it needs
 * translation.
 *
 * @receiver The language to label.
 */
@Composable
private fun AppLanguage.displayName(): String = when (this) {
    AppLanguage.SYSTEM -> stringResource(Res.string.system_default)
    AppLanguage.ENGLISH -> "English"
    AppLanguage.KOREAN -> "한국어"
}

/**
 * The label shown for this theme mode in the reading-appearance group's theme radio rows.
 * [ReaderThemeMode.CUSTOM] has a label here even though that group never offers it as a choice, so
 * this function stays total over the enum rather than needing a caller-side fallback.
 *
 * @receiver The theme mode to label.
 */
@Composable
private fun ReaderThemeMode.displayName(): String = when (this) {
    ReaderThemeMode.PUBLISHER -> stringResource(Res.string.document_style)
    ReaderThemeMode.SYSTEM -> stringResource(Res.string.follow_system)
    ReaderThemeMode.LIGHT -> stringResource(Res.string.light)
    ReaderThemeMode.DARK -> stringResource(Res.string.dark)
    ReaderThemeMode.SEPIA -> stringResource(Res.string.sepia)
    ReaderThemeMode.CUSTOM -> stringResource(Res.string.custom)
}

/**
 * The label shown for this direction in the page-direction radio group. [PageTurnMode.CONTINUOUS]
 * shares [PageTurnMode.VERTICAL]'s label rather than getting its own, since the page-direction
 * group above only offers [PageTurnMode.HORIZONTAL] and [PageTurnMode.VERTICAL] as choices and a
 * user should never actually see this branch taken.
 *
 * @receiver The page-turn direction to label.
 */
@Composable
private fun PageTurnMode.displayName(): String = when (this) {
    PageTurnMode.HORIZONTAL -> stringResource(Res.string.horizontal)
    PageTurnMode.VERTICAL,
    PageTurnMode.CONTINUOUS,
        -> stringResource(Res.string.vertical)
}

/**
 * The label shown for this animation across both the default-transition and page-effects radio
 * groups. Several enum values deliberately share one label — [PageAnimation.SLIDE] and
 * [PageAnimation.SHEET_FLIP] both read as "slide," [PageAnimation.BOOK_CURL] and
 * [PageAnimation.CURL_PAGER] both read as "curl" — because they are two implementations of what a
 * user experiences as the same visual effect, not two options worth distinguishing by name.
 *
 * @receiver The page-turn animation to label.
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
    PageAnimation.FLUID_PAGER -> stringResource(Res.string.animation_fluid_pager)
    PageAnimation.CIRCLE_REVEAL -> stringResource(Res.string.animation_circle_reveal)
    PageAnimation.MOVIE_CAROUSEL -> stringResource(Res.string.animation_movie_carousel)
    PageAnimation.PAGE_FLIP -> stringResource(Res.string.animation_page_flip)
}

/**
 * The label shown for this mode in the hands-free reading group's auto-scroll radio rows.
 *
 * @receiver The auto-scroll mode to label.
 */
@Composable
private fun AutoScrollMode.displayName(): String = when (this) {
    AutoScrollMode.PIXEL -> stringResource(Res.string.auto_scroll_smooth)
    AutoScrollMode.LINE -> stringResource(Res.string.auto_scroll_line_by_line)
    AutoScrollMode.PAGE -> stringResource(Res.string.auto_scroll_page_by_page)
}

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
 * Preview of [ReaderSettingsSheet] at three widths, exercising the compact, default, and wide
 * layouts the sheet's content can render at.
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

package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderMotion
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddSlider
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.generated.resources.*
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.reader.ReaderBottomControls
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * The reader's bottom chrome: the page-position slider/label and the previous/next/auto-scroll
 * controls, faded in and out together with the rest of the reader controls.
 *
 * The slider is driven from [sliderValue] rather than owning its own state, so a drag in progress
 * and the page index committed by [onPageSelected] can disagree without fighting each other — the
 * caller is expected to hold the live drag value (e.g. `ReaderScreen`'s `bottomSliderValue`) and only
 * call [onPageSelected] once the gesture finishes. Internally, [latestSelectedPage] mirrors the
 * rounded target page and is what the drag reports back through [onPageSelected], since the slider
 * itself only carries a continuous [Float].
 *
 * @param pageIndex The current page and the total page count the slider's range and label are built
 *   from.
 * @param style The active reader style, forwarded to [ReaderBottomControls] for theming.
 * @param isAutoScrollEnabled True while auto-scroll is running; disables the slider and the
 *   previous/next buttons so a manual page change cannot race the automatic one, and swaps the
 *   auto-scroll button's icon/label to "pause."
 * @param showProgress Whether the page-position row (label + slider) is shown at all; the
 *   previous/next/auto-scroll buttons always show regardless.
 * @param isPaginationComplete False only while the book is still being imported in the background
 *   (see `ReaderUiState.isPaginationComplete`). The slider stays visible but disabled rather than
 *   hidden — a thumb that slides on its own as the total grows is worse than one that waits — and the
 *   page label keeps counting up with a trailing "+" instead of showing a fixed, wrong total.
 * @param onAutoScrollToggle Called when the auto-scroll play/pause button is tapped.
 * @param onPreviousPage Called when the previous-page button is tapped.
 * @param onNextPage Called when the next-page button is tapped.
 * @param onPageSelected Called with the rounded target page once a slider drag finishes.
 * @param sliderValue The slider's current (possibly mid-drag) value, owned by the caller.
 * @param onSliderValueChange Called continuously as the slider is dragged, before the drag finishes.
 * @param modifier The modifier applied to the whole bottom bar.
 * @param windowInsets Insets reserved so the bar avoids system chrome (e.g. the navigation bar).
 * @param canGoPrevious Whether the previous-page button is enabled; defaults to "not on the first
 *   page."
 * @param canGoNext Whether the next-page button is enabled; defaults to "not on the last known
 *   page."
 *
 * `latestSelectedPage` mirrors the rounded target page, so `onPageSelected` reports an `Int` even though
 * the slider only ever produces a continuous `Float`. It is remembered against the last page rather than
 * unconditionally, so a repagination that changes the page count resets it along with the slider's range
 * instead of reporting a stale page number.
 */
@Composable
fun ReaderBottomActionBar(
    pageIndex: PageIndex,
    style: ReaderStyle,
    isAutoScrollEnabled: Boolean,
    showProgress: Boolean,
    isPaginationComplete: Boolean = true,
    onAutoScrollToggle: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onPageSelected: (Int) -> Unit,
    sliderValue: Float,
    onSliderValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    canGoPrevious: Boolean = pageIndex.current > 0,
    canGoNext: Boolean = pageIndex.current < (pageIndex.total - 1).coerceAtLeast(0),
) {
    val spacing = teddReaderSpacing()
    val motion = teddReaderMotion()
    val typography = teddReaderTypography()
    val lastPage = (pageIndex.total - 1).coerceAtLeast(0)
    val sliderRange = if (lastPage > 0) 0f..lastPage.toFloat() else 0f..1f
    val displayedSliderValue = sliderValue.coerceIn(sliderRange.start, sliderRange.endInclusive)
    val selectedPage = displayedSliderValue.roundToInt().coerceIn(0, lastPage)
    var latestSelectedPage by remember(lastPage) { mutableIntStateOf(selectedPage) }

    LaunchedEffect(selectedPage) {
        latestSelectedPage = selectedPage
    }

    val pageLabel = if (pageIndex.total == 0) {
        stringResource(Res.string.page_fraction_zero)
    } else if (isPaginationComplete) {
        "${latestSelectedPage + 1} / ${pageIndex.total}"
    } else {
        "${latestSelectedPage + 1} / ${pageIndex.total}+"
    }

    AnimatedContent(
        modifier = modifier,
        targetState = showProgress,
        transitionSpec = {
            fadeIn(tween(motion.mediumDurationMs)) togetherWith
                fadeOut(tween(motion.shortDurationMs))
        },
        label = "Reader progress visibility",
    ) { progressVisible ->
        ReaderBottomControls(
            style = style,
            windowInsets = windowInsets,
            progress = if (progressVisible) {
                {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xxSmall),
                    ) {
                        TeddText(
                            text = pageLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = typography.readerCaption,
                        )
                        TeddSlider(
                            value = displayedSliderValue,
                            onValueChange = { value ->
                                val boundedValue = value.coerceIn(sliderRange.start, sliderRange.endInclusive)
                                latestSelectedPage = boundedValue.roundToInt().coerceIn(0, lastPage)
                                onSliderValueChange(boundedValue)
                            },
                            onValueChangeFinished = { onPageSelected(latestSelectedPage) },
                            valueRange = sliderRange,
                            enabled = pageIndex.total > 1 && !isAutoScrollEnabled && isPaginationComplete,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                null
            },
            actions = {
                TeddIconButton(
                    onClick = onPreviousPage,
                    enabled = canGoPrevious && !isAutoScrollEnabled,
                    contentDescription = stringResource(Res.string.previous_page),
                ) {
                    TeddIcon(imageVector = TeddIcons.Previous, contentDescription = null)
                }
                TeddIconButton(
                    onClick = onNextPage,
                    enabled = canGoNext && !isAutoScrollEnabled,
                    contentDescription = stringResource(Res.string.next_page),
                ) {
                    TeddIcon(imageVector = TeddIcons.Next, contentDescription = null)
                }
                TeddIconButton(
                    onClick = onAutoScrollToggle,
                    contentDescription = if (isAutoScrollEnabled) stringResource(Res.string.pause_auto_scroll) else stringResource(Res.string.start_auto_scroll),
                ) {
                    TeddIcon(
                        imageVector = if (isAutoScrollEnabled) TeddIcons.Pause else TeddIcons.Play,
                        contentDescription = null,
                    )
                }
            },
        )
    }
}

/** Compose preview of [ReaderBottomActionBar] mid-book, with progress shown and auto-scroll off. */
@Preview(widthDp = 360)
@Composable
private fun ReaderBottomActionBarPreview() {
    TeddReaderTheme {
        ReaderBottomActionBar(
            pageIndex = PageIndex(current = 4, total = 20),
            style = ReaderStyle(),
            isAutoScrollEnabled = false,
            showProgress = true,
            onAutoScrollToggle = {},
            onPreviousPage = {},
            onNextPage = {},
            onPageSelected = {},
            sliderValue = 4f,
            onSliderValueChange = {},
        )
    }
}

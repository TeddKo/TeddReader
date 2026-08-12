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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddSlider
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.reader.ReaderBottomControls
import com.tedd.teddreader.core.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
fun ReaderBottomActionBar(
    pageIndex: PageIndex,
    style: ReaderStyle,
    isAutoScrollEnabled: Boolean,
    showProgress: Boolean,
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
    } else {
        "${latestSelectedPage + 1} / ${pageIndex.total}"
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
                        Text(
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
                            enabled = pageIndex.total > 1 && !isAutoScrollEnabled,
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
                    Icon(imageVector = TeddIcons.Previous, contentDescription = null)
                }
                TeddIconButton(
                    onClick = onNextPage,
                    enabled = canGoNext && !isAutoScrollEnabled,
                    contentDescription = stringResource(Res.string.next_page),
                ) {
                    Icon(imageVector = TeddIcons.Next, contentDescription = null)
                }
                TeddIconButton(
                    onClick = onAutoScrollToggle,
                    contentDescription = if (isAutoScrollEnabled) stringResource(Res.string.pause_auto_scroll) else stringResource(Res.string.start_auto_scroll),
                ) {
                    Icon(
                        imageVector = if (isAutoScrollEnabled) TeddIcons.Pause else TeddIcons.Play,
                        contentDescription = null,
                    )
                }
            },
        )
    }
}

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

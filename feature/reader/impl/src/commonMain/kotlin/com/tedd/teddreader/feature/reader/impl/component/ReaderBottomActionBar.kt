package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddSlider
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.reader.ReaderBottomControls
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
    val typography = teddReaderTypography()
    val lastPage = (pageIndex.total - 1).coerceAtLeast(0)
    val sliderRange = if (lastPage > 0) 0f..lastPage.toFloat() else 0f..1f
    val displayedSliderValue = sliderValue.coerceIn(sliderRange.start, sliderRange.endInclusive)
    val selectedPage = displayedSliderValue.roundToInt().coerceIn(0, lastPage)
    val pageLabel = if (pageIndex.total == 0) {
        "0 / 0"
    } else {
        "${selectedPage + 1} / ${pageIndex.total}"
    }

    ReaderBottomControls(
        style = style,
        modifier = modifier,
        windowInsets = windowInsets,
        progress = if (showProgress) {
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
                            onSliderValueChange(value.coerceIn(sliderRange.start, sliderRange.endInclusive))
                        },
                        onValueChangeFinished = { onPageSelected(selectedPage) },
                        valueRange = sliderRange,
                        enabled = pageIndex.total > 1,
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
                enabled = canGoPrevious,
                contentDescription = "Previous page",
            ) {
                Icon(imageVector = TeddIcons.Previous, contentDescription = null)
            }
            TeddIconButton(
                onClick = onNextPage,
                enabled = canGoNext,
                contentDescription = "Next page",
            ) {
                Icon(imageVector = TeddIcons.Next, contentDescription = null)
            }
            TeddIconButton(
                onClick = onAutoScrollToggle,
                contentDescription = if (isAutoScrollEnabled) "Pause auto-scroll" else "Start auto-scroll",
            ) {
                Icon(
                    imageVector = if (isAutoScrollEnabled) TeddIcons.Pause else TeddIcons.Play,
                    contentDescription = null,
                )
            }
        },
    )
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

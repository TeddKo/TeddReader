package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

@Composable
fun TeddSliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    valueLabel: String? = null,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(DefaultTeddReaderSpacing.medium),
) {
    val typography = teddReaderTypography()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(contentPadding),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = typography.settingTitle,
                )
                if (valueLabel != null) {
                    Text(
                        text = valueLabel,
                        style = typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TeddSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                onValueChangeFinished = onValueChangeFinished,
            )
        }
    }
}

@Composable
fun TeddSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val showThumb = enabled && (isPressed || isDragged)
    val activeTrackColor = MaterialTheme.colorScheme.primary
    val inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier,
        interactionSource = interactionSource,
        thumb = {
            Spacer(
                modifier = Modifier
                    .size(TeddSliderThumbSize)
                    .graphicsLayer { alpha = if (showThumb) 1f else 0f }
                    .background(activeTrackColor, CircleShape),
            )
        },
        track = {
            val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
                .coerceIn(0f, 1f)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TeddSliderTrackHeight),
            ) {
                val strokeWidth = TeddSliderTrackHeight.toPx()
                val radius = strokeWidth / 2f
                val start = Offset(radius, center.y)
                val end = Offset(size.width - radius, center.y)
                val activeEnd = Offset(
                    x = radius + (size.width - strokeWidth) * fraction,
                    y = center.y,
                )
                drawLine(
                    color = inactiveTrackColor,
                    start = start,
                    end = end,
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = activeTrackColor,
                    start = start,
                    end = activeEnd,
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        },
    )
}

private val TeddSliderThumbSize = 24.dp
private val TeddSliderTrackHeight = 8.dp

@Preview
@Composable
private fun TeddSliderRowPreview() {
    TeddPreviewSurface {
        TeddSliderRow(
            title = "Font size",
            value = 18f,
            valueLabel = "18sp",
            valueRange = 12f..32f,
            onValueChange = {},
        )
    }
}

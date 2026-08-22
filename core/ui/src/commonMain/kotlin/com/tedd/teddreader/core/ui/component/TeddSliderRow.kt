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

/**
 * A settings row that labels a [TeddSlider] with a title and an optional formatted value (e.g.
 * "18sp"), stacking them so the row can sit directly inside a [TeddOptionGroup] alongside
 * [TeddSwitchRow]/[TeddRadioRow] without each caller re-building the title-plus-value header by hand.
 *
 * @param title The row's label, shown in [teddReaderTypography]'s `settingTitle` style.
 * @param value The slider's current value; must fall within [valueRange].
 * @param onValueChange Invoked continuously while the slider is dragged, with the new value.
 * @param valueRange The inclusive range [value] can take.
 * @param modifier Modifier applied to the row's root.
 * @param valueLabel A formatted display of [value] (e.g. "18sp") shown at the row's trailing edge;
 * omitted when null.
 * @param steps Number of discrete steps between the ends of [valueRange]; 0 means the slider is
 * continuous.
 * @param enabled Whether the slider responds to drags.
 * @param onValueChangeFinished Invoked once, after a drag gesture ends, distinct from the continuous
 * [onValueChange] — the value passed to [onValueChange] is not repeated here.
 * @param contentPadding Padding between the row's edge and its content.
 */
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

/**
 * The app's slider chrome on top of Material's [Slider]: a thumb that is invisible until the user is
 * actually pressing or dragging it, and a flat two-color track drawn with a [Canvas] instead of
 * [Slider]'s default track/thumb art. This exists because the design calls for the slider to look
 * like a plain track until touched — Material's [Slider] has no built-in way to hide its thumb only
 * while idle, so this redraws both the `thumb` and `track` slots to get that behavior while still
 * delegating all drag/keyboard/state handling to [Slider] itself.
 *
 * @param value The slider's current value; must fall within [valueRange].
 * @param onValueChange Invoked continuously while the slider is dragged, with the new value.
 * @param valueRange The inclusive range [value] can take.
 * @param modifier Modifier applied to the underlying [Slider].
 * @param steps Number of discrete steps between the ends of [valueRange]; 0 means the slider is
 * continuous.
 * @param enabled Whether the slider responds to drags; also gates whether the thumb can ever become
 * visible.
 * @param onValueChangeFinished Invoked once, after a drag gesture ends, distinct from the continuous
 * [onValueChange].
 */
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

/** Diameter of [TeddSlider]'s thumb while it is visible (pressed or dragged). */
private val TeddSliderThumbSize = 24.dp

/** Height of [TeddSlider]'s drawn track line. */
private val TeddSliderTrackHeight = 8.dp

/** Compose preview rendering [TeddSliderRow] with a font-size value and its formatted label. */
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

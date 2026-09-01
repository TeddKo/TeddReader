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
import androidx.compose.material3.Slider
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
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * [TeddSlider]에 제목과 선택적 포맷된 값(예: "18sp")으로 라벨을 붙이는 설정 행으로, 이들을 쌓아
 * [TeddSwitchRow]/[TeddRadioRow]와 나란히 [TeddOptionGroup] 안에 바로 놓일 수 있게 하며, 각 호출자가
 * 제목-값 헤더를 직접 다시 만들 필요가 없게 한다.
 *
 * @param title [teddReaderTypography]의 `settingTitle` 스타일로 표시되는 행의 라벨.
 * @param value 슬라이더의 현재 값. [valueRange] 안에 들어야 한다.
 * @param onValueChange 슬라이더가 드래그되는 동안 새 값과 함께 계속 호출된다.
 * @param valueRange [value]가 가질 수 있는 포함 범위.
 * @param modifier 행 루트에 적용되는 modifier.
 * @param valueLabel 행의 후행 가장자리에 표시되는, [value]의 포맷된 표시(예: "18sp"). null이면
 * 생략된다.
 * @param steps [valueRange] 양 끝 사이의 이산 단계 수. 0이면 슬라이더는 연속적이다.
 * @param enabled 슬라이더가 드래그에 반응할지 여부.
 * @param onValueChangeFinished 드래그 제스처가 끝난 뒤 한 번 호출되며, 연속적인 [onValueChange]와는
 * 구별된다 — [onValueChange]에 전달된 값이 여기서 반복되지는 않는다.
 * @param contentPadding 행 가장자리와 콘텐츠 사이의 패딩. null이면 테마의 medium(모든 방향) 값을
 * 사용한다.
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
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(spacing.medium)
    val typography = teddReaderTypography()
    val colors = teddReaderColors()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(resolvedContentPadding),
    ) {
        CompositionLocalProvider(LocalContentColor provides teddReaderColors().onSurface) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeddText(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = typography.settingTitle,
                )
                if (valueLabel != null) {
                    TeddText(
                        text = valueLabel,
                        style = typography.labelLarge,
                        color = colors.onSurfaceVariant,
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
 * Material의 [Slider] 위에 얹은 앱의 슬라이더 크롬: 사용자가 실제로 누르거나 드래그하기 전까지는
 * 보이지 않는 thumb, 그리고 [Slider]의 기본 track/thumb 그림 대신 [Canvas]로 그린 평평한 2색
 * track이다. 디자인이 슬라이더를 터치되기 전까지는 단순한 track처럼 보이도록 요구하기 때문에 이것이
 * 존재한다 — Material의 [Slider]는 유휴 상태일 때만 thumb를 숨기는 내장 방법이 없으므로, 모든
 * 드래그/키보드/상태 처리는 여전히 [Slider] 자체에 위임한 채 `thumb`와 `track` 슬롯을 다시 그려 그
 * 동작을 얻는다.
 *
 * @param value 슬라이더의 현재 값. [valueRange] 안에 들어야 한다.
 * @param onValueChange 슬라이더가 드래그되는 동안 새 값과 함께 계속 호출된다.
 * @param valueRange [value]가 가질 수 있는 포함 범위.
 * @param modifier 내부 [Slider]에 적용되는 modifier.
 * @param steps [valueRange] 양 끝 사이의 이산 단계 수. 0이면 슬라이더는 연속적이다.
 * @param enabled 슬라이더가 드래그에 반응할지 여부. thumb가 보일 수 있는지 여부도 함께 결정한다.
 * @param onValueChangeFinished 드래그 제스처가 끝난 뒤 한 번 호출되며, 연속적인 [onValueChange]와는
 * 구별된다.
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
    val colors = teddReaderColors()
    val activeTrackColor = colors.primary
    val inactiveTrackColor = colors.onSurface.copy(alpha = 0.18f)

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

/** [TeddSlider]의 thumb가 보이는 동안(눌리거나 드래그될 때)의 지름. */
private val TeddSliderThumbSize = 24.dp

/** [TeddSlider]가 그리는 track 선의 높이. */
private val TeddSliderTrackHeight = 8.dp

/** 글자 크기 값과 포맷된 라벨을 갖춘 [TeddSliderRow]를 렌더링하는 Compose 프리뷰. */
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

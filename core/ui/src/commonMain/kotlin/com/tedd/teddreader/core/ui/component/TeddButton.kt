package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.extension.teddClickable
import com.tedd.teddreader.core.ui.extension.teddSelectable

/**
 * [TeddButton]의 시각적 무게로, 어떤 배경/테두리/콘텐츠 조합이 뒷받침하는지가 아니라 그 액션이 얼마나
 * 주목받을 만한지에 따라 선택한다. 화면은 그 조합을 직접 조립하는 대신 액션마다 값을 하나씩
 * 고르므로, 강조는 각 호출부마다 반복되는 선택이 아니라 디자인 결정으로 남는다.
 */
enum class TeddButtonEmphasis {
    /** 화면에서 가장 무게가 큰 채워진 액션 — 단색 배경. */
    Primary,

    /** 주 액션과 나란히 놓이는 보조 액션 — 윤곽선 스타일이며 [Primary]와 모양·패딩이 같다. */
    Secondary,

    /** 테두리나 채우기가 없는 낮은 강조 액션으로, 닫기나 선택적 동작에 쓰인다. */
    Text,

    /** 오류 색상으로 렌더링되어 되돌릴 수 없는 선택임을 알리는, `Text` 스타일의 액션. */
    Destructive,
}

/**
 * 앱의 단일 버튼 서피스: Material의 `Button`/`OutlinedButton`/`TextButton` 대신 [teddClickable] 위에
 * 직접 만든 [TeddText] 라벨이다. 이 세 Material 컴포넌트는 모두 리플 색상을 버튼 자체의 콘텐츠 색상에서
 * 가져오는데, 이는 이 앱의 리플 정책이 정확히 금지하는 것이다 — 어느 서피스가 눌리든 앱 전체에서
 * 일관된 촉각적 색상을 유지해야 한다([teddClickable] 참고). 각 [emphasis]가 자체 배경, 테두리, 콘텐츠
 * 색상을 선택하면서도 이 단일 리플 색상을 유지하는 유일한 방법이 버튼을 직접 만드는 것이다. 터치
 * 타깃, 모서리 모양, 라벨 스타일을 여기서 한 번 고정해 두어 어떤 [emphasis] 분기도 실수로 다른
 * 분기와 어긋날 수 없게 한다.
 *
 * 라벨은 오직 중앙 정렬을 위해서만 존재하는 `Row`나 `Box` 안에 놓이는 대신 버튼의 루트가 된다.
 * 자식이 하나뿐이고 오버레이가 없으므로, 그런 래퍼가 공짜로 제공했을 정렬은 라벨 자체의 modifier와
 * 파라미터가 이미 표현하는 것과 정확히 같다 — `TextAlign.Center`가 수평으로 중앙 정렬하고,
 * `Modifier.wrapContentHeight(Alignment.CenterVertically)`가 수직으로 중앙 정렬하므로, 두 번째 레이아웃
 * 노드는 아무 것도 얻지 못한다. 이 수직 중앙 정렬은 `padding` 뒤에 적용되는 가장 안쪽 modifier여야
 * 하는데, `defaultMinSize`는 자신이 감싼 것에 전달되는 *제약*만 끌어올릴 뿐 그 최소값보다 작게 끝나는
 * 자식을 스스로 중앙 정렬하지는 않기 때문이다. 이를 빠뜨리면 짧은 라벨이 48dp 터치 타깃의 중앙이
 * 아니라 위쪽에 놓이게 된다. `background`, 테두리, [teddClickable]은 모두 체인에서 `padding`보다
 * 바깥쪽에 있으므로, 채우기·윤곽선·리플이 라벨 자체의 자연스러운 크기로 줄어들지 않고 강제된 전체
 * 높이를 모두 덮는다.
 *
 * @param text [teddReaderTypography]의 `labelLarge` 스타일로 렌더링되는 버튼 라벨.
 * @param onClick 버튼이 탭될 때 호출된다. [enabled]가 false인 동안에는 호출되지 않는다.
 * @param modifier 강제된 48dp 최소 높이가 적용되기 전, 버튼 루트에 적용되는 modifier.
 * @param enabled 버튼이 탭에 반응할지 여부. false이면 비활성 색상으로도 전환된다.
 * @param emphasis 어떤 시각적 처리를 렌더링할지 — [TeddButtonEmphasis] 참고.
 * @param contentPadding 버튼 가장자리와 라벨 사이의 패딩. null이면 테마의 large/small 조합을 사용한다.
 */
@Composable
fun TeddButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasis: TeddButtonEmphasis = TeddButtonEmphasis.Primary,
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.large,
        vertical = spacing.small,
    )
    val typography = teddReaderTypography()
    val colors = teddReaderColors()
    val shape = teddReaderShapes().medium

    val backgroundColor: Color
    val contentColor: Color
    val borderColor: Color?

    when (emphasis) {
        TeddButtonEmphasis.Primary -> {
            backgroundColor = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.1f)
            contentColor = if (enabled) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = 0.38f)
            borderColor = null
        }

        TeddButtonEmphasis.Secondary -> {
            backgroundColor = Color.Transparent
            contentColor = if (enabled) {
                colors.onSurfaceVariant
            } else {
                colors.onSurfaceVariant.copy(alpha = 0.38f)
            }
            borderColor = colors.outlineVariant
        }

        TeddButtonEmphasis.Text -> {
            backgroundColor = Color.Transparent
            contentColor = if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.38f)
            borderColor = null
        }

        TeddButtonEmphasis.Destructive -> {
            backgroundColor = Color.Transparent
            contentColor = if (enabled) colors.error else colors.error.copy(alpha = 0.6f)
            borderColor = null
        }
    }

    TeddText(
        text = text,
        modifier = modifier
            .defaultMinSize(minHeight = spacing.touchTarget)
            .background(color = backgroundColor, shape = shape)
            .run { if (borderColor != null) border(BorderStroke(1.dp, borderColor), shape) else this }
            .teddClickable(onClick = onClick, shape = shape, enabled = enabled, role = Role.Button)
            .padding(resolvedContentPadding)
            .wrapContentHeight(Alignment.CenterVertically),
        style = typography.labelLarge,
        color = contentColor,
        textAlign = TextAlign.Center,
    )
}

/**
 * 필터와 인라인 라벨에 쓰이는 알약 모양 태그. [onClick]이 주어지면 Material의
 * `Surface(selected = ...)` 대신 [teddSelectable] 위에 만든 탭 가능한 알약으로 렌더링된다: 그 서피스의
 * 리플은 다른 모든 Material 클릭 가능 요소와 마찬가지로 `contentColor`에서 색상을 가져오는데 이는 이
 * 앱의 리플 정책이 금지하는 것이고([teddClickable] 참고), 자체 최소 터치 타깃 처리가 탭 타깃과 함께
 * 눈에 보이는 알약도 함께 키워 버려 촘촘한 필터 행의 정렬이 어긋나게 된다.
 *
 * `minimumInteractiveComponentSize`는 그려지는 것을 건드리지 않고 48dp 터치 하한을 확보하며, 이것이
 * 체인에서 가장 바깥에 놓이는 이유의 전부다. semantics 트리에서 측정해 보면: 이것이 있으면 텍스트
 * 노드는 알약 자체의 높이인 25dp — `labelLarge` 한 줄에 양쪽 4dp 패딩을 더한 값 — 로 유지되는 반면,
 * 상호작용을 소유한 노드는 48dp 정사각형으로 측정된다. 이것이 없으면 둘 다 25dp로 줄어든다. 배경,
 * 테두리, 리플은 모두 그 안쪽에 붙으므로 하한이 아니라 알약을 따라간다: 리플은 보이는 모양에 클립된
 * 채로 유지되고 알약은 결코 부풀지 않는다.
 *
 * 여기서는 Compose가 스스로 히트 테스트를 늘려 주는 데 기대는 것으로는 부족하다. 그 대체 동작은
 * 인바운드 터치를 아무 노드도 차지하지 않을 때만 적용되고, 거리로 형제 노드와 경쟁한다 — 이 칩들이
 * 놓이는 필터 행에서는 8dp 간격으로 배치되어 인접한 칩들이 그 간격을 나눠 가지므로 실효 타깃이 48dp에
 * 한참 못 미치게 된다. 공간을 미리 확보해야만 그 하한이 실제로 성립한다.
 *
 * [onClick]이 null이면, 클릭 불가능한 상호작용 modifier조차 순수하게 정보 전달용인 칩이 관여해서는
 * 안 되는 터치/semantics 처리에 관여하게 되므로, 같은 테두리·배경·알약 모양을 대신 일반 [TeddText]
 * 위에 직접 그린다. [selected]는 채우기/콘텐츠 색상을 secondary-container 조합으로 바꾸고,
 * 비상호작용 경로에서는 semantics도 selected로 표시해, 선택 가능한 컨테이너 자체의 `selected`
 * 파라미터에서 공짜로 나왔을 상태를 접근성 서비스가 알릴 수 있게 한다.
 *
 * @param text 한 줄로 표시되며 말줄임표로 잘리는 칩 라벨.
 * @param onClick 칩이 탭될 때 호출된다. null이면 칩은 탭 가능한 알약이 아니라 정적인, 클릭 불가능한
 * 텍스트로 렌더링된다.
 * @param modifier 칩 루트에 적용되는 modifier.
 * @param enabled 칩이 탭에 반응할지 여부. [onClick]이 non-null일 때만 의미가 있다.
 * @param selected 칩을 선택된(secondary-container) 색상으로 그릴지 여부.
 * @param contentPadding 칩 가장자리와 라벨 사이의 패딩. null이면 테마의 medium/xSmall 조합을 사용한다.
 */
@Composable
fun TeddChip(
    text: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.medium,
        vertical = spacing.xSmall,
    )
    val colors = teddReaderColors()
    val backgroundColor = if (selected) {
        colors.secondaryContainer
    } else {
        colors.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        colors.onSecondaryContainer
    } else {
        colors.onSurfaceVariant
    }
    val shape = RoundedCornerShape(percent = 50)

    if (onClick != null) {
        TeddText(
            text = text,
            modifier = modifier
                .minimumInteractiveComponentSize()
                .background(backgroundColor, shape)
                .border(BorderStroke(1.dp, colors.outlineVariant), shape)
                .teddSelectable(
                    selected = selected,
                    onClick = onClick,
                    shape = shape,
                    enabled = enabled,
                )
                .padding(resolvedContentPadding),
            color = contentColor,
            style = teddReaderTypography().labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        TeddText(
            text = text,
            modifier = (if (selected) modifier.semantics { this.selected = true } else modifier)
                .clip(shape)
                .background(backgroundColor)
                .border(BorderStroke(1.dp, colors.outlineVariant), shape)
                .padding(resolvedContentPadding),
            color = contentColor,
            style = teddReaderTypography().labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [TeddButton]을 기본(primary-emphasis) 스타일로 렌더링하는 Compose 프리뷰. */
@Preview
@Composable
private fun TeddButtonPreview() {
    TeddPreviewSurface {
        TeddButton(
            text = "Open document",
            onClick = {},
        )
    }
}

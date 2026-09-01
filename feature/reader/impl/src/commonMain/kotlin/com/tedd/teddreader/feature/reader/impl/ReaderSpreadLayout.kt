package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.ui.system.DisplayFold
import kotlin.math.max
import kotlin.math.min

/**
 * `ReaderScreen`이 현재 창에 대해 배치해야 할, 나란히 놓인 페이지 pane 수: 일반적인 폰 너비 읽기라면 1,
 * 태블릿 너비 창이거나 책처럼 펼쳐진 폴더블이라면 2. 수직으로 분리하는 fold는 너비 임계값 미만이라도
 * pane 2개를 강제하는데, 그런 기기는 이미 자신의 힌지로 나뉘어 있어서 — 그 위에 하나의 연속된 페이지를
 * 배치하면 하드웨어가 강제하는 이음매를 존중하는 대신 텍스트가 가려진 부분을 그대로 관통하게 되기
 * 때문이다.
 *
 * @param widthDp 현재 창 너비(dp).
 * @param heightDp 현재 창 높이(dp). 임계값 검사는 [widthDp] 하나가 아니라 `min(widthDp, heightDp)`와
 *   비교하므로, 기기가 회전했다는 이유만으로 pane 수가 바뀌지 않는다.
 * @param fold 기기의 물리적 디스플레이 fold, 플랫폼이 아무것도 보고하지 않으면 null.
 * @return 2단 spread를 배치하려면 `2`, 단일 페이지면 `1`.
 */
internal fun readerPaneCount(widthDp: Float, heightDp: Float, fold: DisplayFold? = null): Int = when {
    fold != null && fold.isBookSpine() -> 2
    min(widthDp, heightDp) >= TwoPaneMinShortestSideDp -> 2
    else -> 1
}

/**
 * 2-pane spread의 너비 중 왼쪽 페이지에 주어지는 비율로, pane 사이의 gutter가 창을 정확히 가운데서
 * 가르는 대신 fold의 힌지 가림 영역에 놓이도록 선택된다. 기준으로 삼을 수직 fold가 없거나 보고된 창
 * 너비가 양수가 아니면 균등한 [BalancedSpreadWeight] 분할로 대체된다.
 *
 * @param widthDp 현재 창 너비(dp).
 * @param fold 기기의 물리적 디스플레이 fold. 수직 fold만이 비율을 [BalancedSpreadWeight]에서
 *   벗어나게 한다; 수평 fold이거나 fold가 전혀 없으면 spread는 균등하게 나뉜 채로 유지된다.
 * @return [MinSpreadWeight]..[MaxSpreadWeight] 안의 값으로, [widthDp] 중 왼쪽 페이지의 몫.
 */
internal fun readerSpreadLeftWeight(widthDp: Float, fold: DisplayFold?): Float {
    if (fold == null || !fold.isVertical || widthDp <= 0f) return BalancedSpreadWeight
    val leftDp = fold.startDp
    val rightDp = widthDp - fold.endDp
    if (leftDp <= 0f || rightDp <= 0f) return BalancedSpreadWeight
    return (leftDp / (leftDp + rightDp)).coerceIn(MinSpreadWeight, MaxSpreadWeight)
}

/**
 * spread의 두 pane 사이에 렌더링할 gutter 너비(dp). 책처럼 펼쳐진 폴더블에서는 fold 자체의 힌지 가림
 * 영역을 피하도록 넓어지는데, 그 가림 영역보다 좁은 gutter는 페이지 콘텐츠가 힌지 아래에 놓이게 하기
 * 때문이다; 그 외의 모든 창 형태에서는 그대로 통과시켜서 일반적인 태블릿 spread는 평소 읽기 gutter를
 * 유지한다.
 *
 * @param fold 기기의 물리적 디스플레이 fold, 플랫폼이 아무것도 보고하지 않으면 null.
 * @param defaultGutterDp [fold]가 그 이상으로 넓힐 책 형태 fold가 아닐 때 쓰이는, 일반적인 읽기
 *   gutter(dp).
 * @return [defaultGutterDp]와 fold 자체의 가림 두께 중 더 큰 값.
 */
internal fun readerSpreadGutterDp(fold: DisplayFold?, defaultGutterDp: Float): Float =
    if (fold != null && fold.isBookSpine()) max(defaultGutterDp, fold.thicknessDp) else defaultGutterDp

/**
 * 이 fold가 책의 책등처럼 동작할 때 true: 수직이고, 기기가 여전히 평평하게 놓여 있는 동안 플랫폼이 그저
 * 보고만 하는 fold가 아니라 폴더블 기기가 물리적으로 힌지로 갈라놓는 두 pane으로 창을 실제로 분리하는
 * 경우. [readerPaneCount]와 [readerSpreadGutterDp] 둘 다 [DisplayFold.isVertical] 하나만이 아니라 이를
 * 기준으로 폴더블 전용 동작을 게이트하므로, 평평하거나 수평인 fold가 책등으로 착각되는 일이 없다.
 *
 * @receiver 검사 중인 물리적 디스플레이 fold.
 * @return 수직이고 분리하는 fold일 때만 true.
 */
private fun DisplayFold.isBookSpine(): Boolean = isVertical && isSeparating

/** spread를 기울일 힌지가 없을 때 [readerSpreadLeftWeight]가 대체값으로 쓰는, 균등한 50/50 분할. */
private const val BalancedSpreadWeight = 0.5f

/** [readerSpreadLeftWeight]가 왼쪽 페이지에 줄 수 있는 가장 좁은 비율로, 극단적으로 중심에서 벗어난
 * 힌지라도 한쪽 pane을 절대 0으로 짓누를 수 없도록 한다. */
private const val MinSpreadWeight = 0.2f

/** [readerSpreadLeftWeight]가 왼쪽 페이지에 줄 수 있는 가장 넓은 비율 — [MinSpreadWeight]와 대칭을
 * 이루는 경계. */
private const val MaxSpreadWeight = 0.8f

/** 피해야 할 힌지가 없는 기기에서 spread의 두 pane 사이에 놓이는 일반적인 읽기 gutter(dp) —
 * `ReaderScreen`이 [readerSpreadGutterDp]에 넘기는 기본 입력값. */
internal const val ReaderPaneGutterDp = 16f

/** [readerPaneCount]가 이 이상에서는 창을 태블릿 크기로 간주하여, 책 형태 fold가 강제하지 않아도 두
 * pane을 배치하는 짧은 변 너비 기준값(dp). */
private const val TwoPaneMinShortestSideDp = 600f

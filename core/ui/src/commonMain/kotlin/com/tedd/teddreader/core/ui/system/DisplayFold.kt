package com.tedd.teddreader.core.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * 현재 창의 물리적 디스플레이 폴드로, 창을 따라 dp 단위로 측정된다.
 *
 * 폰이나 태블릿은 아무것도 보고하지 않는다. 책 자세의 폴더블은 힌지 폐색이 그 경계인 수직 폴드를
 * 보고하여, 2페이지 레이아웃이 책등에 정확히 거터를 놓을 수 있게 한다 — [thicknessDp] 외에
 * [startDp]/[endDp]/[isVertical]의 유일한 소비자인 `ReaderSpreadLayout` 참고.
 *
 * @property startDp 폴드의 가까운 가장자리로, 창의 시작(수직 폴드라면 왼쪽) 또는 위(수평 폴드라면
 * 위쪽)로부터의 dp 값.
 * @property endDp [startDp]와 같은 축 위의, 폴드의 먼 가장자리; 항상 [startDp] 이상이다.
 * @property isVertical 폴드가 왼쪽에서 오른쪽이 아니라 위에서 아래로 나 있는지 여부(책 자세 분할,
 * 세로축 위의 거터).
 * @property isSeparating 플랫폼이 폴드의 양쪽을 물리적으로 분리된 디스플레이 영역으로 취급하는지
 * 여부(예: 펼쳐진 책 형태 기기), 단지 존재할 뿐 현재 화면을 두 영역으로 분리하고 있지는 않은 폴드와
 * 대비된다.
 */
@Immutable
data class DisplayFold(
    val startDp: Float,
    val endDp: Float,
    val isVertical: Boolean,
    val isSeparating: Boolean,
) {
    /** 폴드의 폐색 너비(dp 단위, [endDp]에서 [startDp]를 뺀 값). 결코 음수가 되지 않는다. */
    val thicknessDp: Float get() = (endDp - startDp).coerceAtLeast(0f)
}

/**
 * 플랫폼으로부터 현재 창의 [DisplayFold]를 읽어들이며, 기기의 폴드 상태가 바뀔 때(예: 힌지 각도가
 * 책 자세로 들어가거나 나올 때) 재컴포지션된다.
 *
 * @return 플랫폼이 보고한 폴드, 또는 폴드가 없는 기기, Android에서 현재 폴딩 기능이 보고되지 않는
 * 경우, 그리고 iOS에서는 조건 없이 null(iOS `actual` 참고).
 */
@Composable
expect fun rememberDisplayFold(): DisplayFold?

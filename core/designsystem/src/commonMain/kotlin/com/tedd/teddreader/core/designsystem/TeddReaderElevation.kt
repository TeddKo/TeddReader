package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 중요도가 같은 두 표면의 높이가 달라지지 않도록 한곳에서 정의한, 각 표면이 페이지 위에 떠 있는
 * 거리입니다.
 *
 * @property none 배경에 붙어 있는 평평한 표면입니다.
 * @property xSmall 구분선 같은 표면에 사용하는 아주 얕은 높이입니다.
 * @property small 평상시 카드의 높이입니다.
 * @property medium 떠 있는 카드나 바의 높이입니다.
 * @property large 시트나 메뉴의 높이입니다.
 * @property xLarge 앱이 그리는 가장 높은 표면인 다이얼로그의 높이입니다.
 */
@Immutable
data class TeddReaderElevation(
    val none: Dp = 0.dp,
    val xSmall: Dp = 1.dp,
    val small: Dp = 2.dp,
    val medium: Dp = 4.dp,
    val large: Dp = 8.dp,
    val xLarge: Dp = 12.dp,
)

/** 호출자가 재정의하지 않을 때 테마가 설치하는 고도 척도입니다. */
val DefaultTeddReaderElevation = TeddReaderElevation()

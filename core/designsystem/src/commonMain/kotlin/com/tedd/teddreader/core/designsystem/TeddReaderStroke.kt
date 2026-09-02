package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 앱이 그리는 가장 얇은 선 — 버튼·카드·칩의 윤곽선과 목록 행·바의 구분선 — 의 굵기입니다.
 *
 * [TeddReaderSpacing]이나 [TeddReaderElevation]과는 다른 별도 척도입니다. 간격은 요소 사이의 거리를,
 * 고도는 표면이 페이지 위로 얼마나 떠 있는지를 나타내지만 이 값은 선 자체의 두께이므로, 그 두 척도의
 * 값을 재사용하면 간격이나 고도를 조정할 때 의도치 않게 선 굵기까지 함께 바뀝니다.
 *
 * 이 값은 컴포지션 중에 읽히므로 `@Immutable`입니다. 이 지정이 없으면 Compose는 같은 값을 다시 받은
 * 소비자의 재컴포지션을 생략할 수 없습니다.
 *
 * @property hairline 윤곽선과 구분선에 공통으로 쓰는 머리카락 굵기입니다. 기기 픽셀 하나는 스케일의 한
 * 단계가 아니라 사람 눈에 보이는 가장 얇은 선의 물리적 하한이므로 값은 하나뿐입니다.
 */
@Immutable
data class TeddReaderStroke(
    val hairline: Dp = 1.dp,
)

/** 호출자가 재정의하지 않을 때 테마가 설치하는 선 굵기 척도입니다. */
val DefaultTeddReaderStroke = TeddReaderStroke()

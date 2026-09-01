package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 에셋 자체의 크기와 관계없이 아이콘 상자가 옆의 텍스트나 컨트롤에 맞도록 정한 아이콘 크기입니다.
 *
 * [small]과 [medium]은 사용자가 조작하는 모든 요소에 디자인 언어가 약속한 두 가지 시각적 크기입니다. 다른
 * 크기로 그린 작업 아이콘을 이들 옆에 두면 잘못된 것처럼 보입니다. 나머지 둘은 본문 크기를 더 추가한 것이
 * 아닙니다. [extraSmall]은 누르는 대신 읽는 글리프용이고, [large]는 하나의 상태 전체를 단독으로 나타내는
 * 아이콘의 디스플레이 크기입니다.
 *
 * @property extraSmall 캡션 텍스트와 인라인으로 배치하며 터치 대상이 아닌 글리프 크기입니다. 리더 상태
 * 푸터의 배터리 표시를 위해 존재합니다. 컨트롤의 탭 영역이 아니라 옆 캡션의 대문자 높이에 맞춰야 하므로
 * 의도적으로 20/24 작업 크기보다 작습니다. [small]로 키우면 한눈에 읽어야 하는 행에서 지나치게 두드러집니다.
 * @property small 행 끝의 어포던스나 칩 안의 글리프처럼, 아이콘이 단독으로 작업을 나타내지 않고 텍스트를
 * 보조하는 곳에 사용하는 크기입니다.
 * @property medium 컨트롤을 단독으로 나타내는 아이콘의 기본 크기입니다.
 * @property large 빈 상태처럼 단독으로 표시하는 아이콘의 크기입니다.
 */
@Immutable
data class TeddReaderIconography(
    val extraSmall: Dp = 16.dp,
    val small: Dp = 20.dp,
    val medium: Dp = 24.dp,
    val large: Dp = 32.dp,
)

/** 호출자가 재정의하지 않을 때 테마가 설치하는 아이콘 척도입니다. */
val DefaultTeddReaderIconography = TeddReaderIconography()

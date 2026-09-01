package com.tedd.teddreader.feature.reader.impl

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

/**
 * 리더 자체 레이아웃이 공간을 예약해야 하는 시스템 바 inset — 리더의 pane과 상단/하단 chrome의 크기와
 * padding을 정해서 상태 바나 내비게이션 바 밑으로 그려지는 일이 없도록 하는 데 쓰인다. 각 플랫폼은 자신의
 * `WindowInsets` API가 "시스템 바"로 간주하는 것을, 그 플랫폼 자체 actual이 정한 방식대로 응답한다.
 *
 * @return 이 플랫폼이 현재 보고하는 시스템 바 inset.
 */
@Composable
internal expect fun readerSystemBarsInsets(): WindowInsets

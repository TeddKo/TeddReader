package com.tedd.teddreader.feature.reader.impl

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable

/**
 * iOS의 해법은 평범하게 visibility를 반영하는 `WindowInsets.systemBars`이다 — 상태 바와 홈 인디케이터가 현재
 * 실제로 보이는 만큼의 영역만 추적하며, Android actual(자체 문서 참고)이 `systemBarsIgnoringVisibility`에게
 * 요구하는 "숨겨져 있어도 바의 크기를 그대로 보고한다"는 동작은 갖지 않는다.
 */
@Composable
internal actual fun readerSystemBarsInsets(): WindowInsets = WindowInsets.systemBars

package com.tedd.teddreader.feature.reader.impl

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.runtime.Composable

/**
 * Android의 해법은 `systemBarsIgnoringVisibility`이다. 이는 현재 실제로 그려지고 있는지와 무관하게 시스템 바의
 * 전체 크기를 보고한다. 일반 `WindowInsets.systemBars`는 Android가 일시적으로 숨기고 있는 바에 대해 0을
 * 보고한다 — 예를 들어 제스처 내비게이션 스와이프로 평소 숨겨진 바가 잠깐 드러나는 동안이나 전체 화면 요청 중에는
 * 그런데, 이렇게 되면 실제 크기가 변할 때만이 아니라 바의 표시 여부가 바뀔 때마다 리더 자체의 예약된 패딩이
 * 들썩이게 된다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal actual fun readerSystemBarsInsets(): WindowInsets = WindowInsets.systemBarsIgnoringVisibility

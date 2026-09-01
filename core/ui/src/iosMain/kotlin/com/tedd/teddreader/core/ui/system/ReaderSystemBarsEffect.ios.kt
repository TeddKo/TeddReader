package com.tedd.teddreader.core.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** iOS 시스템 크롬 테마는 아직 연결되지 않았다; 공통 앱 루트 코드를 플랫폼 중립으로 유지한다. */
@Composable
actual fun SystemBarsThemeEffect(backgroundColor: Color) = Unit

/** iOS 리더의 몰입형/화면 꺼짐 방지 동작은 아직 연결되지 않았다. */
@Composable
actual fun ReaderSystemBarsEffect(
    visible: Boolean,
    keepScreenOn: Boolean,
) = Unit

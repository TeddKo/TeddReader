package com.tedd.teddreader.core.ui.system

import androidx.compose.runtime.Composable

/**
 * iOS는 디스플레이 폴드 API를 제공하지 않는다: iPhone과 iPad는 단일한 평평한 디스플레이만
 * 보고한다. 폴더블 iOS 기기가 등장하면 이 actual만 힌지를 보고하도록 바꾸면 된다; 호출자는 이미
 * 실제 폴드를 처리하고 있다.
 */
@Composable
actual fun rememberDisplayFold(): DisplayFold? = null

package com.tedd.teddreader.app.reader.di

import androidx.compose.runtime.Composable

/**
 * iOS 절반의 그래프는 컴포지션에서 읽어야 하는 플랫폼 입력이 없으므로 아무 동작도 하지 않는다.
 * [PlatformReaderModule]의 iOS `actual`이 노출하는 프로바이더들은 앱 자체의 샌드박스 API만으로
 * 값을 해석하므로, `KoinApplication`보다 먼저 채워 넣어야 할 홀더가 없다. 이 빈 구현은
 * [ProvidePlatformKoinInput]의 expect 계약만 만족시킨다.
 */
@Composable
internal actual fun ProvidePlatformKoinInput() {
}

package com.tedd.teddreader.app.reader.di

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Compose 트리가 실행되는 애플리케이션 `Context`를 읽어 [provideAndroidApplicationContext]로 홀더에
 * 저장한다. [PlatformReaderModule]의 Android `actual`에 있는 `applicationContext` `@Single`
 * 프로바이더가 해석하는 값은 어노테이션 프로세서가 만드는 것이 아니라 이 함수가 채우는 홀더에서
 * 온다.
 *
 * 애플리케이션 컨텍스트는 프로세스 생명주기 동안 바뀌지 않으므로 매 리컴포지션마다 다시 채워도
 * 안전하다.
 */
@Composable
internal actual fun ProvidePlatformKoinInput() {
    provideAndroidApplicationContext(LocalContext.current.applicationContext)
}

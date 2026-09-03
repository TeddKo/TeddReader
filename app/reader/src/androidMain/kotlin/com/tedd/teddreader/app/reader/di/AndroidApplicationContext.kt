package com.tedd.teddreader.app.reader.di

import android.content.Context

/**
 * 컴포지션 루트가 채워 넣기 전까지는 값이 없는, Android 애플리케이션 [Context] 홀더다. Koin
 * 그래프의 모듈 집합을 [ReaderAppModule] 하나로 정적으로 고정해 컴파일러 플러그인이 전체 그래프를
 * 컴파일 타임에 검증할 수 있게 하려면 `Context`를 어노테이션 프로바이더의 생성자 파라미터로 넘길
 * 수 없다. 이 홀더가 [ProvidePlatformKoinInput]과 [PlatformReaderModule]의 Android `actual`에
 * 있는 `applicationContext` 프로바이더 사이에서 값을 중계해 그 제약을 우회한다.
 */
@Volatile
private var applicationContextHolder: Context? = null

/**
 * [ProvidePlatformKoinInput]의 Android `actual`이 컴포지션에서 읽은 애플리케이션 [Context]를
 * 홀더에 저장한다.
 *
 * @param context 저장할 애플리케이션 [Context]다.
 */
internal fun provideAndroidApplicationContext(context: Context) {
    applicationContextHolder = context
}

/**
 * [PlatformReaderModule]의 Android `actual`에 있는 `applicationContext` `@Single` 프로바이더가
 * 그래프에 [Context]를 들여올 때 호출하는 읽기 함수다.
 *
 * @return [provideAndroidApplicationContext]가 저장한 애플리케이션 [Context]다.
 * @throws IllegalStateException [TeddReaderApp][com.tedd.teddreader.app.reader.TeddReaderApp]의
 *   컴포지션 루트를 거치지 않아 [provideAndroidApplicationContext]가 한 번도 호출되지 않은 채로 Koin
 *   그래프가 resolve된 경우 발생한다. `KoinApplication`을 시작하기 전에 `TeddReaderApp()`을 통해
 *   [ProvidePlatformKoinInput]이 호출되었는지 확인해야 한다.
 */
internal fun androidApplicationContext(): Context =
    applicationContextHolder
        ?: throw IllegalStateException(
            "Android Context가 아직 설정되지 않았습니다. TeddReaderApp() 컴포지션 루트를 거치지 않고 " +
                "Koin 그래프를 resolve했는지 확인하세요.",
        )

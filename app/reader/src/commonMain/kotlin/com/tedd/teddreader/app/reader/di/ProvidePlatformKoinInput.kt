package com.tedd.teddreader.app.reader.di

import androidx.compose.runtime.Composable

/**
 * 컴포지션에서만 얻을 수 있는 플랫폼 입력을, Koin 그래프가 resolve되기 전에 플랫폼별 홀더에 채워
 * 넣는 진입점이다. 이 프로젝트는 프로세스 전역 `startKoin()` 대신
 * [TeddReaderApp][com.tedd.teddreader.app.reader.TeddReaderApp]의 `KoinApplication` composable이
 * 그래프를 소유하며, 그 진입점이 로드하는 모듈 집합은 [ReaderAppModule] 하나로 정적으로 고정되어
 * 있어 컴파일러 플러그인이 전체 그래프를 컴파일 타임에 검증할 수 있다. Android `Context`처럼
 * 어노테이션 프로바이더의 생성자 파라미터가 될 수 없는 컴포지션 전용 값은 이렇게 `modules(...)`가
 * 아니라 이 함수가 채우는 플랫폼별 홀더를 거쳐 [PlatformReaderModule]의 해당 `actual`에 있는
 * `@Single` 프로바이더로 들어간다.
 *
 * 반환값이 없으므로 `KoinApplication`에 전달할 모듈이 아니라, `KoinApplication`보다 먼저 호출해
 * 부수효과로 홀더를 채우는 것이 이 함수의 유일한 목적이다.
 */
@Composable
internal expect fun ProvidePlatformKoinInput()

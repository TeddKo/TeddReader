package com.tedd.teddreader.app.reader.di

import org.koin.core.annotation.Module

/**
 * 컴포지션 루트 Koin 그래프 중 플랫폼 API가 있어야 구성할 수 있는 절반을 나타내는 expect
 * 선언이다. 플랫폼의 `DocumentFileSource` 구현, Room 데이터베이스, 리더 환경설정 DataStore처럼
 * commonMain에는 없는 Android `Context`나 iOS 샌드박스 API로만 만들 수 있는 `@Single` 정의를 각
 * 플랫폼의 `actual`이 제공한다. 이 클래스의 정의들은
 * [com.tedd.teddreader.app.reader.di.ReaderAppModule]의 `includes`에 편입되어, 공용 저장소 구현이
 * 의존하지만 commonMain 혼자서는 완성할 수 없는 나머지 바인딩을 채운다.
 */
@Module
expect class PlatformReaderModule()

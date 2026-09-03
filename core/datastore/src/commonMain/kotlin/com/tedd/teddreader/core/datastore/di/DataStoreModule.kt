package com.tedd.teddreader.core.datastore.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

/**
 * `core:datastore`가 그래프에 공급하는 환경설정 접근 계층의 진입점이다.
 *
 * `com.tedd.teddreader.core.datastore` 패키지 전체를 스캔해
 * [ReaderPreferencesDataSource][com.tedd.teddreader.core.datastore.ReaderPreferencesDataSource]를
 * 찾아낸다. 이 패키지에는 직렬화 형식과 데이터소스 외에 다른 하위 패키지가 없으므로 최상위
 * 패키지 하나만 스캔해도 충분하다.
 *
 * [ReaderPreferencesDataSource][com.tedd.teddreader.core.datastore.ReaderPreferencesDataSource]의
 * 생성자가 요구하는 `DataStore<ReaderPreferences>`는 여기서 만들지 않는다 — 그 값을 만드는
 * [createReaderPreferencesDataStore][com.tedd.teddreader.core.datastore.createReaderPreferencesDataStore]는
 * 플랫폼별 파일 경로가 필요해 앱의 플랫폼 모듈이 대신 공급한다. `core:data`가 리포지토리
 * 구현에서 이 데이터소스를 주입받아 사용한다.
 */
@Module
@ComponentScan("com.tedd.teddreader.core.datastore")
class DataStoreModule

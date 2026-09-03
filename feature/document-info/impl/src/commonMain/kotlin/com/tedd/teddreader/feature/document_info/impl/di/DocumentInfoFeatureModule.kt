package com.tedd.teddreader.feature.document_info.impl.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

/**
 * `com.tedd.teddreader.feature.document_info.impl` 패키지를 스캔하여
 * [com.tedd.teddreader.feature.document_info.impl.DocumentInfoViewModel]처럼 `@KoinViewModel`이나
 * `@Single`이 붙은 문서 정보 화면 전용 선언을 [com.tedd.teddreader.app.reader.di.ReaderAppModule]이
 * 결합하는 앱 전역 Koin 그래프에 편입시키는 컴파일러 플러그인 전용 진입점이다. 클래스 본문은 비어
 * 있으며, 이 선언이 존재한다는 사실 자체가 컴파일러 플러그인에게 스캔 대상 패키지를 알려준다.
 */
@Module
@ComponentScan("com.tedd.teddreader.feature.document_info.impl")
class DocumentInfoFeatureModule

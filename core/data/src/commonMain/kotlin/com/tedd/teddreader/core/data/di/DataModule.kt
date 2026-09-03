package com.tedd.teddreader.core.data.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

/**
 * `core:data`가 그래프에 공급하는 파서·페이지네이션·리포지토리 구현들의 진입점이다.
 *
 * `com.tedd.teddreader.core.data` 전체(그리고 `parser`, `pagination`, `repository` 하위
 * 패키지)를 스캔한다. 이 계층은 포맷별 파서, 페이지 레이아웃 엔진, 그리고
 * `core:domain`의 리포지토리 인터페이스를 구현하는 클래스들이 한데 모인 곳이라 패키지 전체를
 * 스캔 경계로 잡는 것이 자연스럽다 — `mapper`, `storage` 하위 패키지에는 애초에 `@Single`이
 * 붙은 클래스가 없고, `DocumentCoverStore`/`EpubImportContainer`/`ComicArchive`/`EpubCss`/
 * `RestoredPageWindows` 같은 내부 협력 객체들은 의도적으로 어떤 리포지토리나 파서가 직접
 * 생성해 쓰는 값 객체이지 그래프가 독립적으로 주입할 대상이 아니므로 애초에 애노테이션이
 * 없어 이 스캔에 걸리지 않는다.
 *
 * 여기서 나오는 바인딩은 `DocumentRepository`, `BookmarkRepository`, `ReaderRepository`,
 * `ReaderSettingsRepository`, `ReadingStatsRepository`, `SearchRepository`(모두
 * [DocumentRepositoryImpl][com.tedd.teddreader.core.data.repository.DocumentRepositoryImpl] 등
 * 각 구현의 상위 타입으로 자동 바인딩됨)와 포맷 감지/파싱 협력자들이며, `core:domain`의
 * 유스 케이스들과 `feature:*:impl` 모듈들이 이 인터페이스만 보고 주입받는다.
 */
@Module
@ComponentScan("com.tedd.teddreader.core.data")
class DataModule

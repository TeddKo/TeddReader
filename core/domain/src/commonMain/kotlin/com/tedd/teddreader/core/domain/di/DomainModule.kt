package com.tedd.teddreader.core.domain.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

/**
 * `core:domain`이 그래프에 공급하는 유스 케이스들의 진입점이다.
 *
 * `com.tedd.teddreader.core.domain` 전체(그리고 그 하위 패키지인 `usecase`)를 스캔한다. 이
 * 계층에는 유스 케이스만 `@Single`로 등록되며, `repository` 하위 패키지에는 인터페이스만 있어
 * 스캔 대상에서 자연히 제외된다 — 그 인터페이스들의 실제 바인딩은 각 구현이 사는
 * `core:data`/`core:datastore` 쪽 모듈이 책임진다. 이 경계를 domain 패키지 전체로 넉넉히 잡은
 * 이유는 이 모듈이 usecase 서브패키지 하나만 좁게 스캔하도록 유지 보수하는 대신, domain 계층에
 * 새 유스 케이스가 추가될 때마다 이 파일을 다시 건드릴 필요가 없게 하기 위해서다.
 *
 * [CreateLibraryFolderUseCase][com.tedd.teddreader.core.domain.usecase.CreateLibraryFolderUseCase],
 * [GetDocumentInfoUseCase][com.tedd.teddreader.core.domain.usecase.GetDocumentInfoUseCase],
 * [OpenReaderDocumentUseCase][com.tedd.teddreader.core.domain.usecase.OpenReaderDocumentUseCase],
 * [SearchDocumentUseCase][com.tedd.teddreader.core.domain.usecase.SearchDocumentUseCase]가 여기서
 * 나오는 바인딩이며, 화면을 구성하는 `feature:*:impl` 모듈들과 `app:reader`가 이들을 생성자로
 * 주입받는다.
 */
@Module
@ComponentScan("com.tedd.teddreader.core.domain")
class DomainModule

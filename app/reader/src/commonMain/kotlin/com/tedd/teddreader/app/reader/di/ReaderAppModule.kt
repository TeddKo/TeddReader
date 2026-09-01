package com.tedd.teddreader.app.reader.di

import androidx.compose.runtime.Composable
import com.tedd.teddreader.core.data.pagination.TextPageLayoutEngine
import com.tedd.teddreader.core.data.parser.ComicBookDocumentParser
import com.tedd.teddreader.core.data.parser.DocumentFormatDetector
import com.tedd.teddreader.core.data.parser.EpubDocumentParser
import com.tedd.teddreader.core.data.parser.ImageDocumentParser
import com.tedd.teddreader.core.data.parser.PdfDocumentParser
import com.tedd.teddreader.core.data.parser.TxtDocumentParser
import com.tedd.teddreader.core.data.repository.BookmarkRepositoryImpl
import com.tedd.teddreader.core.data.repository.DocumentRepositoryImpl
import com.tedd.teddreader.core.data.repository.ReaderRepositoryImpl
import com.tedd.teddreader.core.data.repository.ReaderSettingsRepositoryImpl
import com.tedd.teddreader.core.data.repository.ReadingStatsRepositoryImpl
import com.tedd.teddreader.core.data.repository.SearchRepositoryImpl
import com.tedd.teddreader.core.datastore.ReaderPreferencesDataSource
import com.tedd.teddreader.core.domain.repository.BookmarkRepository
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.domain.repository.ReadingStatsRepository
import com.tedd.teddreader.core.domain.repository.SearchRepository
import com.tedd.teddreader.core.domain.usecase.CreateLibraryFolderUseCase
import com.tedd.teddreader.core.domain.usecase.GetDocumentInfoUseCase
import com.tedd.teddreader.core.domain.usecase.OpenReaderDocumentUseCase
import com.tedd.teddreader.core.domain.usecase.SearchDocumentUseCase
import com.tedd.teddreader.core.room.TeddReaderDatabase
import com.tedd.teddreader.feature.bookmarks.impl.BookmarksViewModel
import com.tedd.teddreader.feature.document_info.impl.DocumentInfoViewModel
import com.tedd.teddreader.feature.home.impl.HomeViewModel
import com.tedd.teddreader.feature.reader.impl.ReaderViewModel
import com.tedd.teddreader.feature.search.impl.SearchViewModel
import com.tedd.teddreader.feature.settings.impl.ReaderSettingsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * 컴포지션 루트의 플랫폼 독립 Koin 모듈을 구성한다. 공유 Room 데이터베이스에서 가져온 DAO, 형식
 * 감지기와 형식별 파서, 페이지 나누기 엔진, 모든 저장소 구현, 각 기능의 ViewModel처럼 플랫폼 API
 * 없이 구성할 수 있고 화면 간에 공유하는 모든 의존성을 포함한다.
 *
 * `koin-annotations` 의존성과 `io.insert-koin.compiler.plugin` 컴파일러 플러그인이 이 모듈의 Gradle
 * 빌드에 연결되어 있지만, `app/reader` 아래에는 `@Single` 또는 `@Module` 어노테이션이 없으며
 * [com.tedd.teddreader.app.reader.TeddReaderApp]도 `koinConfiguration { modules(...) }` 호출에 KSP가
 * 생성한 모듈을 추가하지 않는다. 이 함수의 결과와 [rememberPlatformReaderModule]의 결과만 추가한다.
 * 따라서 아래의 모든 바인딩은 어노테이션 처리 대신 Koin의 `module { }` DSL로 직접 등록된다. 새
 * 의존성은 여기에 행을 추가해야만 접근할 수 있다.
 *
 * 파일을 위에서 아래로 읽을 때 연결된 의존성 그래프가 그대로 드러나도록 바인딩을 리프부터 작성한다.
 * 먼저 DAO, 그다음 DAO와 플랫폼 제공 소스만 사용하는 파서 및 레이아웃 엔진, 이를 사용하는 저장소
 * 구현, 마지막으로 저장소를 사용하는 ViewModel 순서다. Koin의 `single { }`은 지연 생성되며 모듈
 * 정의 시점이 아니라 첫 `get()`에서 타입으로 해석되므로 이 순서는 실제 해석 결과에 영향을 주지
 * 않는다. 동작을 바꾸지 않고 어떤 바인딩이든 목록의 다른 위치로 옮길 수 있지만, 다음 사람이 읽기
 * 쉽도록 의미 있는 순서를 유지한다.
 *
 * 각 기능 ViewModel은 위 저장소들에 `single`이 부여하는 프로세스 전역 싱글턴 수명 대신
 * [org.koin.core.module.dsl.viewModelOf]로 등록한다. 각 ViewModel에는 내비게이션 항목마다 다시
 * 생성되고 해당 항목이 백 스택에서 빠지면 정리되는 `koin-core-viewmodel` 범위의 Android/Compose
 * ViewModel 수명 주기가 필요하기 때문이다. 화면 인스턴스 하나에 속한 상태를 프로세스 전역
 * 싱글턴으로 두는 것은 잘못이다.
 *
 * @return [rememberPlatformReaderModule]의 플랫폼 모듈과 결합할 [Module]이다. 여기에 등록된 저장소는
 *   플랫폼 모듈만 제공하는 `Context` 기반 파일 소스, Room 데이터베이스, 환경설정 DataStore
 *   바인딩에 의존하므로 어느 모듈도 단독으로는 완전하지 않다.
 */
internal fun readerAppModule(): Module = module {
    single { get<TeddReaderDatabase>().documentDao() }
    single { get<TeddReaderDatabase>().readingProgressDao() }
    single { get<TeddReaderDatabase>().bookmarkDao() }
    single { get<TeddReaderDatabase>().readingSessionDao() }
    single { get<TeddReaderDatabase>().searchIndexDao() }
    single { get<TeddReaderDatabase>().pageLayoutDao() }

    single { ReaderPreferencesDataSource(get()) }
    single { DocumentFormatDetector() }
    single { TxtDocumentParser() }
    single { EpubDocumentParser() }
    single { PdfDocumentParser() }
    single { ComicBookDocumentParser() }
    single { ImageDocumentParser() }
    single { TextPageLayoutEngine() }

    single<DocumentRepository> {
        DocumentRepositoryImpl(
            documentDao = get(),
            searchIndexDao = get(),
            pageLayoutDao = get(),
            formatDetector = get(),
            txtDocumentParser = get(),
            epubDocumentParser = get(),
            pdfDocumentParser = get(),
            comicBookDocumentParser = get(),
            imageDocumentParser = get(),
            textPageLayoutEngine = get(),
            documentFileSource = get(),
        )
    }
    single<BookmarkRepository> { BookmarkRepositoryImpl(bookmarkDao = get()) }
    single<ReaderRepository> { ReaderRepositoryImpl(progressDao = get()) }
    single<ReaderSettingsRepository> { ReaderSettingsRepositoryImpl(dataSource = get()) }
    single<ReadingStatsRepository> {
        ReadingStatsRepositoryImpl(
            readingSessionDao = get(),
            documentDao = get(),
        )
    }
    single<SearchRepository> { SearchRepositoryImpl(searchIndexDao = get()) }

    single { CreateLibraryFolderUseCase(documentRepository = get()) }
    single {
        OpenReaderDocumentUseCase(
            documentRepository = get(),
            readerRepository = get(),
            readerSettingsRepository = get(),
        )
    }
    single {
        GetDocumentInfoUseCase(
            documentRepository = get(),
            readerRepository = get(),
            readingStatsRepository = get(),
        )
    }
    single { SearchDocumentUseCase(documentRepository = get(), searchRepository = get()) }

    viewModelOf(::HomeViewModel)
    viewModelOf(::ReaderViewModel)
    viewModelOf(::ReaderSettingsViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::BookmarksViewModel)
    viewModelOf(::DocumentInfoViewModel)
}

/**
 * 컴포지션 루트의 Koin 그래프 중 플랫폼 API가 있어야 구성할 수 있는 절반인 플랫폼의
 * `DocumentFileSource` 구현, Room 데이터베이스, 리더 환경설정 DataStore를 제공한다. commonMain에는
 * 이들을 구성할 `Context`(Android)나 플랫폼 파일 API(iOS)가 없으므로 [readerAppModule]이 직접
 * 제공할 수 없다. 각 대상의 `actual`은 [readerAppModule]의 저장소에 달리 누락될 바인딩만 정확히
 * 제공한다.
 *
 * Android `actual`이 바인딩을 구성할 때 `LocalContext.current`를 읽을 수 있도록 `@Composable`로
 * 선언한다. 각 `actual`은 모듈을 `remember`로 감싸므로 모듈과 그 안의 데이터베이스/DataStore
 * 인스턴스는 재구성 때마다 다시 만들어지지 않고 컴포지션마다 한 번만 만들어진다.
 *
 * @return 단일 `koinConfiguration` 안에서 [readerAppModule]의 결과와 결합할 [Module]이다. 어느
 *   모듈도 단독으로 전체 그래프를 해석하지 못한다.
 */
@Composable
internal expect fun rememberPlatformReaderModule(): Module

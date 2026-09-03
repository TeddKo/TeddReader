package com.tedd.teddreader.app.reader.di

import com.tedd.teddreader.core.data.di.DataModule
import com.tedd.teddreader.core.datastore.di.DataStoreModule
import com.tedd.teddreader.core.domain.di.DomainModule
import com.tedd.teddreader.core.room.di.RoomModule
import com.tedd.teddreader.feature.bookmarks.impl.di.BookmarksFeatureModule
import com.tedd.teddreader.feature.document_info.impl.di.DocumentInfoFeatureModule
import com.tedd.teddreader.feature.home.impl.di.HomeFeatureModule
import com.tedd.teddreader.feature.reader.impl.di.ReaderFeatureModule
import com.tedd.teddreader.feature.search.impl.di.SearchFeatureModule
import com.tedd.teddreader.feature.settings.impl.di.SettingsFeatureModule
import org.koin.core.annotation.Module

/**
 * 컴포지션 루트가 로드하는 단일 진입점으로, 데이터/도메인/DataStore/Room 레이어의 어노테이션
 * 모듈과 여섯 기능의 [HomeFeatureModule], [ReaderFeatureModule], [SettingsFeatureModule],
 * [SearchFeatureModule], [BookmarksFeatureModule], [DocumentInfoFeatureModule], 그리고 플랫폼별
 * `DocumentFileSource`·Room 데이터베이스·리더 환경설정 DataStore를 제공하는
 * [PlatformReaderModule]을 모두 `includes`로 편입시켜 그래프 전체를 하나로 묶는다.
 *
 * [PlatformReaderModule]이 별도의 expect/actual 클래스인 이유는 그 프로바이더들이 Android
 * `Context`나 iOS 샌드박스 API처럼 commonMain에 없는 플랫폼별 입력으로만 구성할 수 있기
 * 때문이다. 그중 Android `Context` 자체는 프로세스 전역 `startKoin()`이 아니라 Compose
 * 컴포지션에서만 얻을 수 있으므로,
 * [TeddReaderApp][com.tedd.teddreader.app.reader.TeddReaderApp]의 컴포지션 루트가
 * `KoinApplication`보다 먼저 호출하는
 * [ProvidePlatformKoinInput][com.tedd.teddreader.app.reader.di.ProvidePlatformKoinInput]이
 * 플랫폼별 홀더에 채워 넣고, [PlatformReaderModule]의 Android `actual`에 있는
 * `applicationContext` `@Single` 프로바이더가 그 홀더를 읽어 그래프에 들여온다. 이 진입점이
 * 로드하는 모듈 집합은 이 클래스 하나로 정적으로 고정되어 컴파일러 플러그인이 전체 그래프를
 * 컴파일 타임에 검증한다.
 *
 * 컴포지션 루트만 로드하는 진입점이라 `internal` 이 자연스러워 보이지만 공개 가시성이어야 한다.
 * 컴파일러 플러그인이 이 클래스마다 `public fun ReaderAppModule.module(): Module` 확장을
 * 생성하는데, Kotlin/Native 의 Objective-C 내보내기는 확장 함수 *자신의* 가시성만 보고 내보낼
 * 대상을 고르고 리시버 클래스의 가시성은 확인하지 않는다. 그래서 리시버가 `internal` 이면
 * 내보내기 대상이 된 공개 확장이 내보내지 않는 클래스를 참조하게 되어 iOS 프레임워크 링크가
 * `AssertionError: Shouldn't be exposed: deserialized class ReaderAppModule` 로 실패한다. 이
 * 프로젝트의 다른 `@Module` 선언이 모두 공개인 것도 같은 이유다.
 */
@Module(
    includes = [
        DataModule::class,
        DomainModule::class,
        DataStoreModule::class,
        RoomModule::class,
        PlatformReaderModule::class,
        HomeFeatureModule::class,
        ReaderFeatureModule::class,
        SettingsFeatureModule::class,
        SearchFeatureModule::class,
        BookmarksFeatureModule::class,
        DocumentInfoFeatureModule::class,
    ],
)
class ReaderAppModule

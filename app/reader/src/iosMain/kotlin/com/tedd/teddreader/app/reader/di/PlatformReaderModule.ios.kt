package com.tedd.teddreader.app.reader.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.datastore.core.DataStore
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import com.tedd.teddreader.core.data.storage.IosDocumentFileSource
import com.tedd.teddreader.core.datastore.ReaderPreferences
import com.tedd.teddreader.core.datastore.createReaderPreferencesDataStore
import com.tedd.teddreader.core.room.TeddReaderDatabase
import com.tedd.teddreader.core.room.createTeddReaderDatabaseBuilder
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * 컴포지션 루트 Koin 그래프의 iOS 절반이다. Android `actual`과 달리 어떤 바인딩도 `Context`에
 * 대응하는 값을 전달받을 필요가 없다. [IosDocumentFileSource]는 샌드박스에 직접 접근하고,
 * `createTeddReaderDatabaseBuilder`와 `createReaderPreferencesDataStore`는 앱 자체의 컨테이너 경로를
 * 직접 해석하므로 이 모듈은 컴포지션 범위 입력 없이 구성할 수 있다.
 *
 * 키 없이 `remember`하므로 이 Composable이 컴포지션에 있는 동안 정확히 한 번만 구성된다. 내부의
 * Room 데이터베이스와 DataStore 인스턴스를 재구성마다 다시 만들지 않고 의도한 프로세스 전역
 * 싱글턴으로 유지한다.
 *
 * @return [com.tedd.teddreader.app.reader.di.readerAppModule]의 저장소가 의존하는 모든 iOS 전용
 *   바인딩을 제공하는 [Module]이다.
 */
@Composable
internal actual fun rememberPlatformReaderModule(): Module = remember {
    module {
        single { IosDocumentFileSource() }
        single<DocumentFileSource> { get<IosDocumentFileSource>() }
        single<TeddReaderDatabase> { createTeddReaderDatabaseBuilder().build() }
        single<DataStore<ReaderPreferences>> { createReaderPreferencesDataStore() }
    }
}

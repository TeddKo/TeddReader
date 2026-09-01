package com.tedd.teddreader.app.reader.di

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import com.tedd.teddreader.core.data.storage.AndroidDocumentFileSource
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import com.tedd.teddreader.core.datastore.ReaderPreferences
import com.tedd.teddreader.core.datastore.createReaderPreferencesDataStore
import com.tedd.teddreader.core.room.TeddReaderDatabase
import com.tedd.teddreader.core.room.createTeddReaderDatabaseBuilder
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Compose 트리가 실행되는 애플리케이션 [Context]로 컴포지션 루트 Koin 그래프의 Android 절반을
 * 구성한다. 그래프의 다른 요소가 사용할 수 있도록 `Context` 자체를 노출하고, SAF 기반
 * [AndroidDocumentFileSource]는 Android 임포터가 Android 전용 메서드를 직접 호출할 수 있도록 구체
 * 타입으로 제공하는 동시에 [com.tedd.teddreader.core.data.repository.DocumentRepositoryImpl]이
 * 의존하는 공유 [DocumentFileSource] 인터페이스로도 바인딩하며, Room 데이터베이스와 리더 환경설정
 * DataStore도 함께 노출한다.
 *
 * 재구성마다 다시 만들지 않고 애플리케이션 컨텍스트를 기준으로 `remember`한다. 열기 비용이 크고
 * 프로세스 전역 싱글턴이어야 하는 데이터베이스와 DataStore는 이 Composable이 컴포지션에 있는 동안
 * 정확히 한 번만 구성된다. `Unit` 대신 `context`를 키로 사용하므로 드물게 애플리케이션 컨텍스트
 * 자체가 바뀌어도 올바르게 다시 구성된다.
 *
 * @return [com.tedd.teddreader.app.reader.di.readerAppModule]의 저장소가 의존하는 모든 Android 전용
 *   바인딩을 제공하는 [Module]이다.
 */
@Composable
internal actual fun rememberPlatformReaderModule(): Module {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        module {
            single<Context> { context }
            single { AndroidDocumentFileSource(get<Context>()) }
            single<DocumentFileSource> { get<AndroidDocumentFileSource>() }
            single<TeddReaderDatabase> { createTeddReaderDatabaseBuilder(get<Context>()).build() }
            single<DataStore<ReaderPreferences>> { createReaderPreferencesDataStore(get<Context>()) }
        }
    }
}

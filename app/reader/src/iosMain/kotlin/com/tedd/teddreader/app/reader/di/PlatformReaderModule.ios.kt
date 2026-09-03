package com.tedd.teddreader.app.reader.di

import androidx.datastore.core.DataStore
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import com.tedd.teddreader.core.data.storage.IosDocumentFileSource
import com.tedd.teddreader.core.datastore.ReaderPreferences
import com.tedd.teddreader.core.datastore.createReaderPreferencesDataStore
import com.tedd.teddreader.core.room.TeddReaderDatabase
import com.tedd.teddreader.core.room.createTeddReaderDatabaseBuilder
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

/**
 * 컴포지션 루트 Koin 그래프의 iOS 전용 절반이다. Android `actual`과 달리 어떤 프로바이더도
 * `Context`에 대응하는 값을 전달받을 필요가 없다. [IosDocumentFileSource]는 샌드박스에 직접
 * 접근하고, `createTeddReaderDatabaseBuilder`와 `createReaderPreferencesDataStore`는 앱 자체의
 * 컨테이너 경로를 직접 해석하므로 이 클래스의 프로바이더들은 파라미터 없이 구성할 수 있다.
 *
 * 이 클래스가 노출하는 각 `@Single` 정의는 다른 Koin 싱글턴과 마찬가지로 `KoinApplication`
 * composable의 그래프가 사는 동안 유지되므로, Room 데이터베이스와 DataStore 인스턴스도 컴포지션당
 * 정확히 한 번만 생성된다.
 */
@Module
actual class PlatformReaderModule {
    /** @return 샌드박스에 직접 접근하는 [DocumentFileSource] 구현인 [IosDocumentFileSource]다. */
    @Single
    fun iosDocumentFileSource(): IosDocumentFileSource = IosDocumentFileSource()

    /**
     * 공유 인터페이스로 같은 인스턴스를 바인딩한다.
     *
     * @param source [iosDocumentFileSource]가 만든 구체 타입 인스턴스다.
     * @return [source]를 그대로 노출하는 [DocumentFileSource] 참조다.
     */
    @Single
    fun documentFileSource(source: IosDocumentFileSource): DocumentFileSource = source

    /** @return 앱 전체가 공유하는 [TeddReaderDatabase] 인스턴스다. */
    @Single
    fun teddReaderDatabase(): TeddReaderDatabase = createTeddReaderDatabaseBuilder().build()

    /**
     * @return [ReaderSettingsRepositoryImpl][com.tedd.teddreader.core.data.repository.ReaderSettingsRepositoryImpl]이
     *   읽고 쓰는 [DataStore]다.
     */
    @Single
    fun readerPreferencesDataStore(): DataStore<ReaderPreferences> = createReaderPreferencesDataStore()
}

package com.tedd.teddreader.app.reader.di

import android.content.Context
import androidx.datastore.core.DataStore
import com.tedd.teddreader.core.data.storage.AndroidDocumentFileSource
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import com.tedd.teddreader.core.datastore.ReaderPreferences
import com.tedd.teddreader.core.datastore.createReaderPreferencesDataStore
import com.tedd.teddreader.core.room.TeddReaderDatabase
import com.tedd.teddreader.core.room.createTeddReaderDatabaseBuilder
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

/**
 * 컴포지션 루트 Koin 그래프의 Android 전용 절반이다. SAF 기반 [AndroidDocumentFileSource]는 Android
 * 임포터가 Android 전용 메서드를 직접 호출할 수 있도록 구체 타입으로 제공하는 동시에
 * [com.tedd.teddreader.core.data.repository.DocumentRepositoryImpl]이 의존하는 공유
 * [DocumentFileSource] 인터페이스로도 바인딩하며, Room 데이터베이스와 리더 환경설정 DataStore도
 * 함께 노출한다. [applicationContext]가 그래프에 들여오는 [Context]는 컴파일러 플러그인의 컴파일
 * 안전성 검사가 화이트리스트로 허용하는 Android 프레임워크 타입이며, 실제 값은
 * [androidApplicationContext]가 읽는 홀더에서 해석된다. 그 홀더는 [ProvidePlatformKoinInput]이
 * `KoinApplication`보다 먼저 채워 넣으므로, 이 클래스의 다른 프로바이더들은 `Context`를 평범한
 * 주입 파라미터로 받기만 하면 된다.
 *
 * 이 클래스가 노출하는 각 `@Single` 정의는 다른 Koin 싱글턴과 마찬가지로 `KoinApplication`
 * composable의 그래프가 사는 동안 유지되므로, 열기 비용이 큰 데이터베이스와 DataStore도 컴포지션당
 * 정확히 한 번만 생성된다.
 */
@Module
actual class PlatformReaderModule {
    /**
     * 컴포지션 루트가 채워 넣은 홀더에서 애플리케이션 [Context]를 읽어 그래프에 들여온다. 다른
     * 프로바이더들은 이 정의 덕분에 `Context`를 어노테이션 프로세서가 만든 것처럼 평범한 주입
     * 파라미터로 받을 수 있다.
     *
     * @return [androidApplicationContext]가 반환하는 애플리케이션 [Context]다.
     */
    @Single
    fun applicationContext(): Context = androidApplicationContext()

    /**
     * SAF로 문서에 접근하는 Android 전용 파일 소스를 만든다.
     *
     * @param context [applicationContext]가 그래프에 들여온 애플리케이션 컨텍스트다.
     * @return SAF 전용 메서드를 노출하는 구체 타입 [AndroidDocumentFileSource]다.
     */
    @Single
    fun androidDocumentFileSource(context: Context): AndroidDocumentFileSource =
        AndroidDocumentFileSource(context)

    /**
     * [DocumentRepositoryImpl][com.tedd.teddreader.core.data.repository.DocumentRepositoryImpl]이
     * 의존하는 공유 인터페이스로 같은 인스턴스를 바인딩한다.
     *
     * @param source [androidDocumentFileSource]가 만든 구체 타입 인스턴스다.
     * @return [source]를 그대로 노출하는 [DocumentFileSource] 참조다.
     */
    @Single
    fun documentFileSource(source: AndroidDocumentFileSource): DocumentFileSource = source

    /**
     * Room 데이터베이스 빌더를 만들어 즉시 빌드한 결과를 노출한다.
     *
     * @param context [applicationContext]가 그래프에 들여온 애플리케이션 컨텍스트다.
     * @return 앱 전체가 공유하는 [TeddReaderDatabase] 인스턴스다.
     */
    @Single
    fun teddReaderDatabase(context: Context): TeddReaderDatabase =
        createTeddReaderDatabaseBuilder(context).build()

    /**
     * 앱 컨테이너 안에 리더 환경설정을 저장하는 DataStore를 노출한다.
     *
     * @param context [applicationContext]가 그래프에 들여온 애플리케이션 컨텍스트다.
     * @return [ReaderSettingsRepositoryImpl][com.tedd.teddreader.core.data.repository.ReaderSettingsRepositoryImpl]이
     *   읽고 쓰는 [DataStore]다.
     */
    @Single
    fun readerPreferencesDataStore(context: Context): DataStore<ReaderPreferences> =
        createReaderPreferencesDataStore(context)
}

package com.tedd.teddreader.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.okio.OkioSerializer
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource

/**
 * [ReaderPreferences]를 JSON으로 읽고 쓰며, 저장된 설정을 다른 코드가 보기 전에 정상화하는 유일한
 * 지점이다.
 *
 * 공통 코드에서 Android와 iOS 모두 실행되므로 플랫폼 파일 API 대신 Okio를 사용한다. [readFrom]과
 * [writeTo]는 Okio 자체 소스와 싱크를 받으며 `use {}`를 사용하도록 다시 작성하면 안 된다. 해당
 * 확장 함수는 Android에서는 컴파일되지만 Kotlin/Native에서는 실패한다.
 *
 * 읽기와 쓰기 양쪽에서 의도적으로 정상화한다. 이전 빌드가 기록한 레거시 열거형 값을 대체 값으로
 * 매핑하고 범위를 벗어난 속도를 제한하므로, 하위 코드는 저장된 값이 더 이상 존재하지 않는 페이저를
 * 가리킬 수 있다는 사실을 알 필요가 없다.
 *
 * 파싱할 수 없는 파일은 `CorruptionException`을 발생시킨다. 이는 앱을 비정상 종료하는 대신
 * [defaultValue]로 교체하라는 DataStore의 신호이며, 앱 실행에 실패하는 것보다 설정을 잃는 편이
 * 낫기 때문이다.
 */
object ReaderPreferencesSerializer : OkioSerializer<ReaderPreferences> {
    /**
     * [readFrom]과 [writeTo]가 사용하는 코덱이다. `ignoreUnknownKeys = true`이므로 이 빌드가
     * 모르는 환경설정을 포함한 새 빌드의 파일도 예외를 발생시켜 나머지 저장 설정을 모두 잃는 대신
     * 정상적으로 디코딩된다. `encodeDefaults = true`이므로 Kotlin 기본값과 우연히 일치하는 필드도
     * 생략하지 않고 모두 명시적으로 기록한다. 따라서 저장 파일은 [ReaderPreferences]의 완전한
     * 스냅샷이며, 읽는 시점의 이 클래스 기본값과 관계없이 같은 방식으로 읽힌다. 모든 기존 저장
     * 파일이 이후에도 동일하게 읽히는지 확인하지 않고 어느 플래그도 바꾸면 안 된다.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 파일이 없거나 손상됐을 때 읽히는 값으로, 모든 환경설정이 기본값이다. */
    override val defaultValue: ReaderPreferences = ReaderPreferences()

    /**
     * @param source 파일의 바이트이며, 파일이 아직 없으면 빈 소스.
     * @return 정상화된 저장 환경설정. 빈 파일이면 [defaultValue].
     * @throws CorruptionException JSON을 파싱할 수 없을 때 발생하며, DataStore에 읽기 실패 대신
     * [defaultValue]를 사용하도록 알린다.
     */
    override suspend fun readFrom(source: BufferedSource): ReaderPreferences = try {
        val raw = source.readUtf8()
        when {
            raw.isBlank() -> defaultValue
            else -> normalize(json.decodeFromString<ReaderPreferences>(raw))
        }
    } catch (exception: SerializationException) {
        throw CorruptionException("Cannot read reader preferences.", exception)
    }

    /**
     * @param t 저장할 환경설정. 먼저 정상화하므로 이전 파일에서 읽은 레거시 값을 그대로 다시
     * 기록하지 않는다.
     * @param sink JSON을 기록할 대상.
     */
    override suspend fun writeTo(t: ReaderPreferences, sink: BufferedSink) {
        sink.writeUtf8(json.encodeToString(normalize(t)))
    }

    /**
     * 더 이상 의미 없는 값을 현재 유효한 값으로 바꾼다.
     *
     * @param preferences 디스크에서 읽었거나 디스크에 기록하려는 환경설정.
     * @return `CONTINUOUS`를 `VERTICAL`로, `BOOK_CURL`을 `CURL_PAGER`로, `SHEET_FLIP`을 `SLIDE`로
     * 해석하고 자동 스크롤 속도를 범위 안으로 제한한 동일 환경설정.
     */
    private fun normalize(preferences: ReaderPreferences): ReaderPreferences = preferences.copy(
        pageTurnMode = when (preferences.pageTurnMode) {
            PageTurnMode.CONTINUOUS -> PageTurnMode.VERTICAL
            else -> preferences.pageTurnMode
        },
        pageAnimation = when (preferences.pageAnimation) {
            PageAnimation.BOOK_CURL -> PageAnimation.CURL_PAGER
            PageAnimation.SHEET_FLIP -> PageAnimation.SLIDE
            else -> preferences.pageAnimation
        },
        autoScrollConfig = preferences.autoScrollConfig.copy(
            speed = AutoScrollConfig.clampSpeed(preferences.autoScrollConfig.speed),
        ),
    )
}

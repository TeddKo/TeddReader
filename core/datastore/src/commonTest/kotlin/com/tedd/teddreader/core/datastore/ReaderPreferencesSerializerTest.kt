package com.tedd.teddreader.core.datastore

import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.sepiaReaderStyle
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 독자의 설정 파일이 포함할 수 있는 내용과 읽은 결과를 규정한다.
 *
 * 여기의 모든 사례는 이전 빌드가 작성한 파일, 새 빌드가 추가한 키, 범위를 벗어난 값에 대한
 * 업그레이드 경로다. 하나라도 잘못 처리하면 업데이트 시 독자의 설정이 조용히 사라지고, 사용자는
 * "앱이 내 글꼴 크기를 잊었다"는 현상으로만 이를 알 수 있다.
 */
class ReaderPreferencesSerializerTest {
    /** 저장된 모든 값이 쓰기와 읽기를 거쳐 변경 없이 유지되는지 검증한다. */
    @Test
    fun preferencesRoundTripThroughJson() = runTest {
        val preferences = ReaderPreferences(
            style = sepiaReaderStyle().copy(fontSizeSp = 24f),
            pageTurnMode = PageTurnMode.VERTICAL,
            appLanguage = AppLanguage.KOREAN,
        )
        val buffer = Buffer()

        ReaderPreferencesSerializer.writeTo(preferences, buffer)

        assertEquals(preferences, ReaderPreferencesSerializer.readFrom(buffer))
    }

    /** 빈 파일을 손상이 아닌 새 설치로 취급해 기본값으로 읽는지 검증한다. */
    @Test
    fun blankJsonReturnsDefaultPreferences() = runTest {
        assertEquals(
            ReaderPreferences(),
            ReaderPreferencesSerializer.readFrom(Buffer()),
        )
    }

    @Test
    fun defaultPreferencesUsePublisherTheme() = runTest {
        assertEquals(ReaderThemeMode.PUBLISHER, ReaderPreferencesSerializer.readFrom(Buffer()).style.themeMode)
    }

    /** 교체된 페이저와 페이지 모드의 값을 대체 값으로 읽는지 검증한다. */
    @Test
    fun legacyJsonValuesReadBackAsCanonicalValues() = runTest {
        val legacyContinuousJson = """{"pageTurnMode":"CONTINUOUS"}"""
        val legacyBookCurlJson = """{"pageAnimation":"BOOK_CURL"}"""
        val legacySheetFlipJson = """{"pageAnimation":"SHEET_FLIP"}"""
        val combinedLegacyJson = """{"pageTurnMode":"CONTINUOUS","pageAnimation":"BOOK_CURL"}"""

        assertEquals(
            PageTurnMode.VERTICAL,
            ReaderPreferencesSerializer.readFrom(Buffer().writeUtf8(legacyContinuousJson)).pageTurnMode,
        )
        assertEquals(
            PageAnimation.CURL_PAGER,
            ReaderPreferencesSerializer.readFrom(Buffer().writeUtf8(legacyBookCurlJson)).pageAnimation,
        )
        assertEquals(
            PageAnimation.SLIDE,
            ReaderPreferencesSerializer.readFrom(Buffer().writeUtf8(legacySheetFlipJson)).pageAnimation,
        )
        assertEquals(
            ReaderPreferences(
                pageTurnMode = PageTurnMode.VERTICAL,
                pageAnimation = PageAnimation.CURL_PAGER,
            ),
            ReaderPreferencesSerializer.readFrom(Buffer().writeUtf8(combinedLegacyJson)),
        )
    }

    /** 디스크에서 읽은 레거시 값을 그대로 다시 쓰지 않아 파일이 스스로 정상화되는지 검증한다. */
    @Test
    fun legacyValuesWriteBackAsCanonicalJson() = runTest {
        val buffer = Buffer()

        ReaderPreferencesSerializer.writeTo(
            ReaderPreferences(
                pageTurnMode = PageTurnMode.CONTINUOUS,
                pageAnimation = PageAnimation.BOOK_CURL,
            ),
            buffer,
        )

        val rawJson = buffer.readUtf8()
        assertFalse(rawJson.contains("CONTINUOUS"))
        assertFalse(rawJson.contains("BOOK_CURL"))
        assertEquals(
            ReaderPreferences(
                pageTurnMode = PageTurnMode.VERTICAL,
                pageAnimation = PageAnimation.CURL_PAGER,
            ),
            ReaderPreferencesSerializer.readFrom(Buffer().writeUtf8(rawJson)),
        )

        val sheetFlipBuffer = Buffer()
        ReaderPreferencesSerializer.writeTo(
            ReaderPreferences(pageAnimation = PageAnimation.SHEET_FLIP),
            sheetFlipBuffer,
        )

        val sheetFlipJson = sheetFlipBuffer.readUtf8()
        assertFalse(sheetFlipJson.contains("SHEET_FLIP"))
        assertTrue(sheetFlipJson.contains("SLIDE"))
    }

    /** 새 빌드가 추가한 키가 이전 파일에 없어도 실패하지 않고 대체 값을 사용하는지 검증한다. */
    @Test
    fun missingAppLanguageDefaultsToSystem() = runTest {
        assertEquals(
            AppLanguage.SYSTEM,
            ReaderPreferencesSerializer.readFrom(Buffer().writeUtf8("""{"style":{}}""")).appLanguage,
        )
    }

    /** 지원 범위 밖의 속도를 읽을 때 제한해 각 화면이 별도로 방어할 필요가 없는지 검증한다. */
    @Test
    fun outOfRangeAutoScrollSpeedReadBackWithinSupportedRange() = runTest {
        assertEquals(
            AutoScrollConfig(enabled = true, mode = AutoScrollMode.PIXEL, speed = 1f),
            ReaderPreferencesSerializer.readFrom(
                Buffer().writeUtf8("""{"autoScrollConfig":{"enabled":true,"mode":"PIXEL","speed":5.0}}"""),
            ).autoScrollConfig,
        )
        assertEquals(
            AutoScrollConfig(enabled = true, mode = AutoScrollMode.PAGE, speed = 0.01f),
            ReaderPreferencesSerializer.readFrom(
                Buffer().writeUtf8("""{"autoScrollConfig":{"enabled":true,"mode":"PAGE","speed":0.005}}"""),
            ).autoScrollConfig,
        )
    }
}

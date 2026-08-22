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
 * Pins what a reader's settings file may contain and what it must read back as.
 *
 * Every case here is an upgrade path: a file written by an older build, a key a newer build added, a value
 * out of range. Getting one wrong loses a reader's settings on update — which is silent, and only visible
 * as "the app forgot my font size".
 */
class ReaderPreferencesSerializerTest {
    /** Everything stored survives a write and read unchanged. */
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

    /** An empty file is a fresh install, not a corrupt one: it reads as the defaults. */
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

    /** Values from replaced pagers and page modes read back as what replaced them. */
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

    /** A legacy value read from disk is not written straight back out; the file heals itself. */
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

    /** A key a newer build added is absent from an older file, and falls back rather than failing. */
    @Test
    fun missingAppLanguageDefaultsToSystem() = runTest {
        assertEquals(
            AppLanguage.SYSTEM,
            ReaderPreferencesSerializer.readFrom(Buffer().writeUtf8("""{"style":{}}""")).appLanguage,
        )
    }

    /** A speed outside the supported range is clamped on read, so no screen has to defend against it. */
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

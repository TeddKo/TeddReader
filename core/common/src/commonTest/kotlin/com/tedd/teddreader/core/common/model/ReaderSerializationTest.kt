package com.tedd.teddreader.core.common.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun readerStyleRoundTripsThroughJson() {
        val style = sepiaReaderStyle().copy(fontSizeSp = 22f)

        assertEquals(style, json.decodeFromString(json.encodeToString(style)))
    }

    @Test
    fun readerLocationRoundTripsThroughJson() {
        val location: ReaderLocation = ReaderLocation.EpubOffset(spineIndex = 2, offset = 32L)

        assertEquals(location, json.decodeFromString<ReaderLocation>(json.encodeToString(location)))
    }

    @Test
    fun readingHistoryRoundTripsThroughJson() {
        val entry = ReadingHistoryEntry(
            documentId = DocumentId("doc"),
            date = LocalDate(2026, 7, 6),
            activeMillis = 1_000L,
            wordsRead = 120L,
        )

        assertEquals(entry, json.decodeFromString(json.encodeToString(entry)))
    }
    @Test
    fun pageAnimationIncludesGoogleAndApplePresets() {
        assertEquals(PageAnimation.GOOGLE_PAGE, json.decodeFromString<PageAnimation>("\"GOOGLE_PAGE\""))
        assertEquals(PageAnimation.APPLE_PAGE, json.decodeFromString<PageAnimation>("\"APPLE_PAGE\""))
    }

}

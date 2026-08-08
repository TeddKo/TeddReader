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
    fun pageAnimationIncludesFoundationPagerPresets() {
        assertEquals(PageAnimation.FLUID_PAGER, json.decodeFromString<PageAnimation>("\"FLUID_PAGER\""))
        assertEquals(PageAnimation.CURL_PAGER, json.decodeFromString<PageAnimation>("\"CURL_PAGER\""))
        assertEquals(PageAnimation.CIRCLE_REVEAL, json.decodeFromString<PageAnimation>("\"CIRCLE_REVEAL\""))
        assertEquals(PageAnimation.MOVIE_CAROUSEL, json.decodeFromString<PageAnimation>("\"MOVIE_CAROUSEL\""))
        assertEquals(PageAnimation.PAGE_FLIP, json.decodeFromString<PageAnimation>("\"PAGE_FLIP\""))
    }

}

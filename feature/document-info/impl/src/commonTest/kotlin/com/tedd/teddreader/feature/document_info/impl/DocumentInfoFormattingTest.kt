package com.tedd.teddreader.feature.document_info.impl

import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReadingStats
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentInfoFormattingTest {
    @Test
    fun `formats size across byte ranges`() {
        assertEquals("999 B", formatSize(999L))
        assertEquals("1.5 KB", formatSize(1_536L))
        assertEquals("5 MB", formatSize(5_242_880L))
    }

    @Test
    fun `formats durations for human reading`() {
        assertEquals("45s", formatDuration(45_000L))
        assertEquals("2m 05s", formatDuration(125_000L))
        assertEquals("1h 01m", formatDuration(3_660_000L))
    }

    @Test
    fun `formats missing values as unknown`() {
        assertEquals("Not available", formatSize(null))
        assertEquals("Not available", formatDuration(null))
        assertEquals("Not available", formatPagePosition(null))
    }

    @Test
    fun `formats page position and reading pace`() {
        val stats = ReadingStats(
            documentId = DocumentId("doc"),
            activeMillis = 120_000L,
            charactersRead = 1_000L,
            wordsRead = 300L,
        )

        assertEquals("4 of 10", formatPagePosition(PageIndex(current = 3, total = 10)))
        assertEquals("150 words/min", formatReadingPace(stats))
    }
    @Test
    fun `formats localized values when optional parameters are provided`() {
        val stats = ReadingStats(
            documentId = DocumentId("doc"),
            activeMillis = 120_000L,
            charactersRead = 1_000L,
            wordsRead = 300L,
        )

        assertEquals("정보 없음", formatCount(null, unavailable = "정보 없음"))
        assertEquals("4 / 10", formatPagePosition(PageIndex(current = 3, total = 10), separator = " / "))
        assertEquals("150 단어/분", formatReadingPace(stats, suffix = " 단어/분"))
    }

}

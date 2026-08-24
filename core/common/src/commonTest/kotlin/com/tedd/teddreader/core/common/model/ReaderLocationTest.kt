package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the storage form of a reading position, per format, in both directions.
 *
 * This round trip is what a resume depends on: [parseReaderLocation] has to return exactly what
 * [ReaderLocation.asStorageString] wrote, or a reader is put back somewhere other than where they stopped.
 * The rejection case is here for the same reason — a negative offset means a corrupt row, and reading it
 * as page one would hide that while losing the reader's place.
 */
class ReaderLocationTest {
    @Test
    fun textLocationRoundTripsThroughStorageString() {
        val location = ReaderLocation.TextOffset(offset = 42L)

        assertEquals(location, parseReaderLocation(location.asStorageString()))
    }

    @Test
    fun epubLocationRoundTripsThroughStorageString() {
        val location = ReaderLocation.EpubOffset(spineIndex = 3, offset = 128L)

        assertEquals(location, parseReaderLocation(location.asStorageString()))
    }

    @Test
    fun pdfLocationRoundTripsThroughStorageString() {
        val location = ReaderLocation.PdfPage(pageIndex = 9)

        assertEquals(location, parseReaderLocation(location.asStorageString()))
    }

    @Test
    fun rejectsNegativeLocations() {
        assertFailsWith<IllegalArgumentException> { ReaderLocation.TextOffset(offset = -1L) }
        assertFailsWith<IllegalArgumentException> { ReaderLocation.EpubOffset(spineIndex = -1, offset = 0L) }
        assertFailsWith<IllegalArgumentException> { ReaderLocation.PdfPage(pageIndex = -1) }
    }
}

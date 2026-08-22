package com.tedd.teddreader.core.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [sniffImageDimensions]'s raster-format header parsing: PNG's fixed-offset IHDR, GIF's logical
 * screen descriptor, and JPEG's marker walk — including the `APPn` segments that must be skipped to
 * find a JPEG's real start-of-frame marker — plus its behavior on unrecognized or truncated bytes.
 */
class ImageDimensionSnifferTest {
    /**
     * PNG's IHDR chunk yields the correct width and height, from a fixture built as: the 8-byte PNG
     * signature, a 4-byte chunk length (13), the ASCII chunk name `IHDR`, then a big-endian 4-byte
     * width (400) and 4-byte height (200).
     */
    @Test
    fun readsWidthAndHeightFromAPngHeader() {
        val bytes = listOf(
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D,
            0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x01, 0x90,
            0x00, 0x00, 0x00, 0xC8,
        ).map(Int::toByte).toByteArray()

        assertEquals(400 to 200, sniffImageDimensions(bytes))
    }

    /**
     * GIF's logical screen descriptor yields the correct little-endian width and height, from a
     * fixture built as: the `GIF89a` signature, a little-endian 2-byte width (800) and 2-byte height
     * (1200), then the packed-fields, background-color-index, and pixel-aspect-ratio bytes the sniffer
     * does not need to read.
     */
    @Test
    fun readsWidthAndHeightFromAGifHeader() {
        val bytes = "GIF89a".encodeToByteArray() + listOf(
            0x20, 0x03,
            0xB0, 0x04,
            0x00, 0x00, 0x00,
        ).map(Int::toByte).toByteArray()

        assertEquals(800 to 1200, sniffImageDimensions(bytes))
    }

    /**
     * A baseline JPEG's SOF0 segment yields the correct width and height, from a fixture built as:
     * SOI (`FFD8`), the SOF0 marker (`FFC0`), a 2-byte segment length (17), a 1-byte sample precision
     * (8), a big-endian 2-byte height (100) and 2-byte width (200), then a 1-byte component count (3)
     * and three 3-byte component descriptors the sniffer does not need to read.
     */
    @Test
    fun readsWidthAndHeightFromABaselineJpegHeader() {
        val bytes = listOf(
            0xFF, 0xD8,
            0xFF, 0xC0,
            0x00, 0x11,
            0x08,
            0x00, 0x64,
            0x00, 0xC8,
            0x03,
            0x01, 0x11, 0x00,
            0x02, 0x11, 0x01,
            0x03, 0x11, 0x01,
        ).map(Int::toByte).toByteArray()

        assertEquals(200 to 100, sniffImageDimensions(bytes))
    }

    /**
     * Regression guard: a JPEG's `APPn` segment(s) ahead of its SOF marker must be skipped over, not
     * mistaken for the frame header. The fixture is built as: SOI (`FFD8`), an `APP0` marker (`FFE0`)
     * with a 2-byte segment length (4) and a 2-byte payload, then the real frame header — SOF2
     * (`FFC2`, progressive), a 2-byte segment length (11), 1-byte precision, and a big-endian 2-byte
     * height (10) and 2-byte width (20).
     */
    @Test
    fun skipsPrecedingAppSegmentsToFindTheJpegSofMarker() {
        val bytes = listOf(
            0xFF, 0xD8,
            0xFF, 0xE0, 0x00, 0x04, 0x00, 0x00,
            0xFF, 0xC2,
            0x00, 0x0B,
            0x08,
            0x00, 0x0A,
            0x00, 0x14,
            0x01, 0x01, 0x11, 0x00,
        ).map(Int::toByte).toByteArray()

        assertEquals(20 to 10, sniffImageDimensions(bytes))
    }

    /** Empty and too-short byte arrays, matching no known signature, return null rather than throwing. */
    @Test
    fun returnsNullForUnrecognizedOrTruncatedBytes() {
        assertNull(sniffImageDimensions(ByteArray(0)))
        assertNull(sniffImageDimensions(byteArrayOf(0x01, 0x02, 0x03)))
    }
}

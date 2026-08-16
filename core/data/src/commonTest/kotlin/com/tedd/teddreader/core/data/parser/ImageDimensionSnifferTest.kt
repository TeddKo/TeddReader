package com.tedd.teddreader.core.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageDimensionSnifferTest {
    @Test
    fun readsWidthAndHeightFromAPngHeader() {
        val bytes = listOf(
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // signature
            0x00, 0x00, 0x00, 0x0D, // chunk length (13)
            0x49, 0x48, 0x44, 0x52, // "IHDR"
            0x00, 0x00, 0x01, 0x90, // width = 400
            0x00, 0x00, 0x00, 0xC8, // height = 200
        ).map(Int::toByte).toByteArray()

        assertEquals(400 to 200, sniffImageDimensions(bytes))
    }

    @Test
    fun readsWidthAndHeightFromAGifHeader() {
        val bytes = "GIF89a".encodeToByteArray() + listOf(
            0x20, 0x03, // width = 800 (little-endian)
            0xB0, 0x04, // height = 1200 (little-endian)
            0x00, 0x00, 0x00, // packed fields, background index, aspect ratio
        ).map(Int::toByte).toByteArray()

        assertEquals(800 to 1200, sniffImageDimensions(bytes))
    }

    @Test
    fun readsWidthAndHeightFromABaselineJpegHeader() {
        val bytes = listOf(
            0xFF, 0xD8, // SOI
            0xFF, 0xC0, // SOF0
            0x00, 0x11, // segment length = 17
            0x08, // precision
            0x00, 0x64, // height = 100
            0x00, 0xC8, // width = 200
            0x03, // component count
            0x01, 0x11, 0x00,
            0x02, 0x11, 0x01,
            0x03, 0x11, 0x01,
        ).map(Int::toByte).toByteArray()

        assertEquals(200 to 100, sniffImageDimensions(bytes))
    }

    @Test
    fun skipsPrecedingAppSegmentsToFindTheJpegSofMarker() {
        val bytes = listOf(
            0xFF, 0xD8, // SOI
            0xFF, 0xE0, 0x00, 0x04, 0x00, 0x00, // APP0, 2-byte payload
            0xFF, 0xC2, // SOF2 (progressive)
            0x00, 0x0B, // segment length = 11
            0x08, // precision
            0x00, 0x0A, // height = 10
            0x00, 0x14, // width = 20
            0x01, 0x01, 0x11, 0x00,
        ).map(Int::toByte).toByteArray()

        assertEquals(20 to 10, sniffImageDimensions(bytes))
    }

    @Test
    fun returnsNullForUnrecognizedOrTruncatedBytes() {
        assertNull(sniffImageDimensions(ByteArray(0)))
        assertNull(sniffImageDimensions(byteArrayOf(0x01, 0x02, 0x03)))
    }
}

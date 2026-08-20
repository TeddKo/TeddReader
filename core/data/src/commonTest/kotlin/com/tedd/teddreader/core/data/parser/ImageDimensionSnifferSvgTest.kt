package com.tedd.teddreader.core.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageDimensionSnifferSvgTest {
    @Test
    fun svgIsMeasuredByItsViewBox() {
        val svg = """<?xml version="1.0"?>
            <svg xmlns="http://www.w3.org/2000/svg" width="100%" height="100%" viewBox="0 0 600 800">
              <image xlink:href="../Images/plate.jpg" width="600" height="800"/>
            </svg>
        """.trimIndent()

        assertEquals(600 to 800, sniffImageDimensions(svg.encodeToByteArray()))
    }

    @Test
    fun viewBoxSeparatedByCommasIsRead() {
        val svg = """<svg viewBox="0,0,120,60"></svg>"""

        assertEquals(120 to 60, sniffImageDimensions(svg.encodeToByteArray()))
    }

    @Test
    fun svgWithoutViewBoxFallsBackToItsFixedWidthAndHeight() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="300px" height="150px"></svg>"""

        assertEquals(300 to 150, sniffImageDimensions(svg.encodeToByteArray()))
    }

    @Test
    fun percentageSizesStateNothingAboutProportionsAndAreRejected() {
        val svg = """<svg width="100%" height="100%"></svg>"""

        assertNull(sniffImageDimensions(svg.encodeToByteArray()))
    }

    @Test
    fun bmpIsMeasuredIncludingTopDownRows() {
        fun bmp(height: Int): ByteArray {
            val bytes = ByteArray(30)
            bytes[0] = 0x42
            bytes[1] = 0x4D
            fun putLE(offset: Int, value: Int) {
                bytes[offset] = (value and 0xFF).toByte()
                bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
                bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
                bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
            }
            putLE(18, 40)
            putLE(22, height)
            return bytes
        }

        assertEquals(40 to 20, sniffImageDimensions(bmp(20)))
        // A negative height only means the rows run top-down; the picture is still 20 tall.
        assertEquals(40 to 20, sniffImageDimensions(bmp(-20)))
    }

    @Test
    fun plainTextIsNotMistakenForAnImage() {
        assertNull(sniffImageDimensions("<html><body>no picture here</body></html>".encodeToByteArray()))
    }
}

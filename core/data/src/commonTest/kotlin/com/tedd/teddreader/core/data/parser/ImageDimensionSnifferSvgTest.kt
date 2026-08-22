package com.tedd.teddreader.core.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [sniffImageDimensions]'s SVG measurement — `viewBox` precedence, fixed-size fallback, and
 * rejecting a percentage size as not a real dimension — alongside two related sniffer cases that ended
 * up in this file too: a BMP's signed, possibly-negative height field, and plain "no signature matched"
 * input.
 */
class ImageDimensionSnifferSvgTest {
    /** A `viewBox` fixes an SVG's measured size even when `width`/`height` are both percentages. */
    @Test
    fun svgIsMeasuredByItsViewBox() {
        val svg = """<?xml version="1.0"?>
            <svg xmlns="http://www.w3.org/2000/svg" width="100%" height="100%" viewBox="0 0 600 800">
              <image xlink:href="../Images/plate.jpg" width="600" height="800"/>
            </svg>
        """.trimIndent()

        assertEquals(600 to 800, sniffImageDimensions(svg.encodeToByteArray()))
    }

    /** A `viewBox` whose four numbers are comma-separated is parsed the same as a space-separated one. */
    @Test
    fun viewBoxSeparatedByCommasIsRead() {
        val svg = """<svg viewBox="0,0,120,60"></svg>"""

        assertEquals(120 to 60, sniffImageDimensions(svg.encodeToByteArray()))
    }

    /** With no `viewBox` at all, fixed pixel `width`/`height` attributes are read as the size instead. */
    @Test
    fun svgWithoutViewBoxFallsBackToItsFixedWidthAndHeight() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="300px" height="150px"></svg>"""

        assertEquals(300 to 150, sniffImageDimensions(svg.encodeToByteArray()))
    }

    /**
     * Regression guard: `width`/`height` given as percentages state nothing about real proportions and must
     * be rejected, not read as a bogus size.
     */
    @Test
    fun percentageSizesStateNothingAboutProportionsAndAreRejected() {
        val svg = """<svg width="100%" height="100%"></svg>"""

        assertNull(sniffImageDimensions(svg.encodeToByteArray()))
    }

    /**
     * Regression guard: a BMP's height field is signed, and a negative value must still measure as
     * the same positive height — only the row storage order (top-down vs. bottom-up) differs.
     */
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
        assertEquals(40 to 20, sniffImageDimensions(bmp(-20)))
    }

    /** Plain text with no image signature at all returns no dimensions rather than a false match. */
    @Test
    fun plainTextIsNotMistakenForAnImage() {
        assertNull(sniffImageDimensions("<html><body>no picture here</body></html>".encodeToByteArray()))
    }
}

package com.tedd.teddreader.feature.reader.impl.pdf

import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import kotlin.test.Test
import kotlin.test.assertNull

class PdfPageSurfaceTest {
    @Test
    fun publisherThemeLeavesPdfColorsUntouched() {
        assertNull(ReaderStyle().pdfThemeLuminanceMatrix())
    }

    @Test
    fun darkThemeRemapsBlackAndWhiteToReaderColors() {
        val style = ReaderStyle(
            textColor = ReaderColor(0xFF102030),
            backgroundColor = ReaderColor(0xFFF0E0D0),
            themeMode = ReaderThemeMode.DARK,
        )

        val matrix = requireNotNull(style.pdfThemeLuminanceMatrix())

        assertColorClose(floatArrayOf(16f, 32f, 48f), applyMatrix(matrix, 0f, 0f, 0f))
        assertColorClose(floatArrayOf(240f, 224f, 208f), applyMatrix(matrix, 255f, 255f, 255f))
    }

    @Test
    fun luminanceMatrixMapsPrimariesByTheirLuminanceWeight() {
        val matrix = luminanceRemapMatrix(
            textRed = 20f,
            textGreen = 40f,
            textBlue = 60f,
            backgroundRed = 220f,
            backgroundGreen = 200f,
            backgroundBlue = 180f,
        )

        assertColorClose(
            applyMatrix(matrix, 255f, 0f, 0f),
            applyMatrix(matrix, 54.213f, 54.213f, 54.213f),
        )
        assertColorClose(
            applyMatrix(matrix, 0f, 255f, 0f),
            applyMatrix(matrix, 182.376f, 182.376f, 182.376f),
        )
        assertColorClose(
            applyMatrix(matrix, 0f, 0f, 255f),
            applyMatrix(matrix, 18.411f, 18.411f, 18.411f),
        )
    }

    private fun applyMatrix(matrix: FloatArray, red: Float, green: Float, blue: Float): FloatArray = floatArrayOf(
        matrix[0] * red + matrix[1] * green + matrix[2] * blue + matrix[4],
        matrix[5] * red + matrix[6] * green + matrix[7] * blue + matrix[9],
        matrix[10] * red + matrix[11] * green + matrix[12] * blue + matrix[14],
    )

    private fun assertColorClose(actual: FloatArray, expected: FloatArray, tolerance: Float = 0.001f) {
        actual.zip(expected).forEach { (a, e) ->
            kotlin.test.assertTrue(kotlin.math.abs(a - e) <= tolerance, "Expected $e ±$tolerance, got $a")
        }
    }
}

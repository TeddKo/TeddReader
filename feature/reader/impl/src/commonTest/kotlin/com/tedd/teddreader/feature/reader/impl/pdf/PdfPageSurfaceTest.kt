package com.tedd.teddreader.feature.reader.impl.pdf

import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PdfPageSurfaceTest {
    /** PDF 원본 비율을 유지하면서 실제 viewport 안에 들어가는 렌더 크기를 검증한다. */
    @Test
    fun renderSizeFitsPageInsideViewport() {
        assertEquals(
            PdfRenderSize(width = 600, height = 800),
            pdfRenderSize(
                pageWidth = 600,
                pageHeight = 800,
                viewportWidth = 1200,
                viewportHeight = 800,
            ),
        )
    }

    /** 비정상적으로 큰 viewport에서도 최대 변과 ARGB 바이트 한도를 동시에 지키는지 검증한다. */
    @Test
    fun renderSizeClampsDimensionAndArgbBytes() {
        val dimensionLimited = requireNotNull(
            pdfRenderSize(
                pageWidth = 1,
                pageHeight = 1,
                viewportWidth = Int.MAX_VALUE,
                viewportHeight = Int.MAX_VALUE,
            ),
        )
        val byteLimited = requireNotNull(
            pdfRenderSize(
                pageWidth = 1_000,
                pageHeight = 1_000,
                viewportWidth = 10_000,
                viewportHeight = 10_000,
                maxDimension = 4_096,
                maxBytes = 16L * 1_024L * 1_024L,
            ),
        )

        assertEquals(PdfRenderSize(width = 4_096, height = 4_096), dimensionLimited)
        assertEquals(PdfRenderSize(width = 2_048, height = 2_048), byteLimited)
        assertEquals(
            16L * 1_024L * 1_024L,
            byteLimited.width.toLong() * byteLimited.height.toLong() * PdfArgbBytesPerPixel,
        )
    }

    /** 매우 긴 단일 축 페이지에서도 최소 한 픽셀 축 때문에 ARGB 바이트 상한을 넘지 않는지 검증한다. */
    @Test
    fun renderSizeClampsExtremeAspectRatioBytes() {
        val renderSize = requireNotNull(
            pdfRenderSize(
                pageWidth = Int.MAX_VALUE,
                pageHeight = 1,
                viewportWidth = Int.MAX_VALUE,
                viewportHeight = Int.MAX_VALUE,
                maxBytes = 4L,
            ),
        )

        assertEquals(PdfRenderSize(width = 1, height = 1), renderSize)
    }

    /** 렌더 입력에 유효한 양의 크기가 없으면 Bitmap 할당을 시도하지 않는 계약을 검증한다. */
    @Test
    fun renderSizeRejectsMissingDimensions() {
        assertNull(pdfRenderSize(pageWidth = 0, pageHeight = 800, viewportWidth = 1_200, viewportHeight = 800))
        assertNull(pdfRenderSize(pageWidth = 600, pageHeight = 800, viewportWidth = 0, viewportHeight = 800))
    }

    /** PDF 렌더 실패 변환이 구조화된 동시성의 취소 신호를 일반 실패로 바꾸지 않는지 검증한다. */
    @Test
    fun renderFailureRethrowsCancellation() {
        assertFailsWith<CancellationException> {
            pdfRenderOrElse(
                render = { throw CancellationException("cancel") },
                onFailure = { "unavailable" },
            )
        }
    }

    /** 일반 렌더 예외는 상세 오류를 UI에 직접 노출하지 않고 고정 실패 상태로 바뀌는 경계를 검증한다. */
    @Test
    fun renderFailureUsesFixedFallback() {
        assertEquals(
            "unavailable",
            pdfRenderOrElse(
                render = { error("private path") },
                onFailure = { "unavailable" },
            ),
        )
    }

    /** Bitmap 렌더 단계가 실패하면 아직 UI로 전달되지 않은 리소스를 즉시 한 번 해제하는지 검증한다. */
    @Test
    fun failedRenderReleasesPendingResource() {
        var releaseCount = 0

        assertFailsWith<IllegalStateException> {
            pdfResourceOrRelease(
                resource = "bitmap",
                release = { releaseCount += 1 },
                render = { error("render failed") },
            )
        }

        assertEquals(1, releaseCount)
    }

    /** 렌더 성공으로 UI에 소유권이 넘어간 리소스를 백그라운드 단계에서 해제하지 않는지 검증한다. */
    @Test
    fun successfulRenderKeepsTransferredResource() {
        var releaseCount = 0

        val result = pdfResourceOrRelease(
            resource = "bitmap",
            release = { releaseCount += 1 },
            render = { "rendered" },
        )

        assertEquals("rendered", result)
        assertEquals(0, releaseCount)
    }

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

package com.tedd.teddreader.core.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [sniffImageDimensions]의 SVG 측정 — `viewBox` 우선순위, 고정 크기 폴백, 퍼센트 크기를 실제 치수가
 * 아니라며 거부하는 것 — 을 고정하며, 이 파일에 함께 들어온 관련 스니퍼 케이스 두 가지도 다룬다: BMP의
 * 부호 있는, 음수일 수도 있는 높이 필드와, "일치하는 시그니처 없음" 평문 입력.
 */
class ImageDimensionSnifferSvgTest {
    /** `viewBox`는 `width`/`height`가 둘 다 퍼센트여도 SVG의 측정 크기를 고정한다. */
    @Test
    fun svgIsMeasuredByItsViewBox() {
        val svg = """<?xml version="1.0"?>
            <svg xmlns="http://www.w3.org/2000/svg" width="100%" height="100%" viewBox="0 0 600 800">
              <image xlink:href="../Images/plate.jpg" width="600" height="800"/>
            </svg>
        """.trimIndent()

        assertEquals(600 to 800, sniffImageDimensions(svg.encodeToByteArray()))
    }

    /** 네 숫자가 쉼표로 구분된 `viewBox`도 공백으로 구분된 것과 똑같이 파싱된다. */
    @Test
    fun viewBoxSeparatedByCommasIsRead() {
        val svg = """<svg viewBox="0,0,120,60"></svg>"""

        assertEquals(120 to 60, sniffImageDimensions(svg.encodeToByteArray()))
    }

    /** `viewBox`가 전혀 없으면, 고정 픽셀 `width`/`height` 속성이 대신 크기로 읽힌다. */
    @Test
    fun svgWithoutViewBoxFallsBackToItsFixedWidthAndHeight() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="300px" height="150px"></svg>"""

        assertEquals(300 to 150, sniffImageDimensions(svg.encodeToByteArray()))
    }

    /**
     * 회귀 가드: 퍼센트로 주어진 `width`/`height`는 실제 비율에 대해 아무것도 말해주지 않으므로, 엉터리
     * 크기로 읽히는 대신 거부되어야 한다.
     */
    @Test
    fun percentageSizesStateNothingAboutProportionsAndAreRejected() {
        val svg = """<svg width="100%" height="100%"></svg>"""

        assertNull(sniffImageDimensions(svg.encodeToByteArray()))
    }

    /**
     * 회귀 가드: BMP의 높이 필드는 부호가 있으며, 음수 값이라도 여전히 같은 양수 높이로 측정되어야 한다
     * — 행 저장 순서(위에서 아래로 vs. 아래에서 위로)만 다를 뿐이다.
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

    /** 이미지 시그니처가 전혀 없는 평문은 잘못된 매치 대신 치수 없음을 반환한다. */
    @Test
    fun plainTextIsNotMistakenForAnImage() {
        assertNull(sniffImageDimensions("<html><body>no picture here</body></html>".encodeToByteArray()))
    }
}

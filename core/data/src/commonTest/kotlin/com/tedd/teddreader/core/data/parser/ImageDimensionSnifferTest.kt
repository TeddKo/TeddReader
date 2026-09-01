package com.tedd.teddreader.core.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [sniffImageDimensions]의 래스터 포맷 헤더 파싱을 고정한다: PNG의 고정 오프셋 IHDR, GIF의 논리
 * 화면 디스크립터, JPEG의 마커 탐색 — `APPn` 세그먼트를 건너뛰어 실제 프레임 시작 마커를
 * 찾는 동작 포함 — 그리고 인식할 수 없거나 잘린 바이트에 대한 동작도 검증한다.
 */
class ImageDimensionSnifferTest {
    /**
     * PNG의 IHDR 청크에서 올바른 너비와 높이를 반환한다. 픽스처 구성: 8바이트 PNG
     * 시그니처, 4바이트 청크 길이(13), ASCII 청크 이름 `IHDR`, 빅엔디언 4바이트
     * 너비(400)와 4바이트 높이(200).
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
     * GIF의 논리 화면 디스크립터에서 리틀엔디언 너비와 높이를 올바르게 반환한다. 픽스처 구성:
     * `GIF89a` 시그니처, 리틀엔디언 2바이트 너비(800)와 2바이트 높이(1200), 이후 스니퍼가
     * 읽지 않아도 되는 packed-fields, background-color-index, pixel-aspect-ratio 바이트.
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
     * 베이스라인 JPEG의 SOF0 세그먼트에서 올바른 너비와 높이를 반환한다. 픽스처 구성:
     * SOI(`FFD8`), SOF0 마커(`FFC0`), 2바이트 세그먼트 길이(17), 1바이트 샘플 정밀도
     * (8), 빅엔디언 2바이트 높이(100)와 2바이트 너비(200), 이후 스니퍼가 읽지 않아도 되는
     * 1바이트 컴포넌트 수(3)와 세 개의 3바이트 컴포넌트 디스크립터.
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
     * 회귀 방지: JPEG의 SOF 마커 앞에 오는 `APPn` 세그먼트는 프레임 헤더로 잘못 인식하지 않고
     * 건너뛰어야 한다. 픽스처 구성: SOI(`FFD8`), 2바이트 세그먼트 길이(4)와 2바이트 페이로드를
     * 가진 `APP0` 마커(`FFE0`), 이후 실제 프레임 헤더인 SOF2(`FFC2`, 프로그레시브),
     * 2바이트 세그먼트 길이(11), 1바이트 정밀도, 빅엔디언 2바이트 높이(10)와 2바이트 너비(20).
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

    /** 알 수 없는 시그니처와 일치하는 빈 배열 또는 너무 짧은 바이트 배열에 대해 예외를 던지지 않고 null을 반환한다. */
    @Test
    fun returnsNullForUnrecognizedOrTruncatedBytes() {
        assertNull(sniffImageDimensions(ByteArray(0)))
        assertNull(sniffImageDimensions(byteArrayOf(0x01, 0x02, 0x03)))
    }
}

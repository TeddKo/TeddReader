package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 형식별 독서 위치의 저장소 형식을 양방향으로 고정한다.
 *
 * 재개는 이 왕복 변환에 의존한다. [parseReaderLocation]이 [ReaderLocation.asStorageString]이 기록한 값과 정확히 같은 값을 반환하지 않으면 독자는 멈춘 곳과 다른 위치로 돌아간다. 거부 경우도 같은 이유로 둔다. 음수 오프셋은 손상된 행이며 이를 첫 페이지로 읽으면 위치를 잃으면서 문제를 숨긴다.
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

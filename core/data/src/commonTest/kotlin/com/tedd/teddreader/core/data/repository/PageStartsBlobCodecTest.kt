package com.tedd.teddreader.core.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [encodePageStartsBlob] / [decodePageStartsBlob]는 Long 배열로 된 JSON을 리틀엔디언 Int32 블롭으로
 * 대체한다 — 16,734페이지 책에서 해당 JSON을 디코딩하는 데 복원 시간 150 ms 대부분이 소요됐다.
 * 오프셋은 `Int` 범위 안에 충분히 들어맞으므로 (이 리더가 여는 실제 책의 최대 크기는 3.5M 문자),
 * 각 페이지 시작 위치는 최대 ~7자리 ASCII 숫자와 JSON 구두점 대신 4바이트만 차지한다.
 */
class PageStartsBlobCodecTest {
    /**
     * 페이지 시작 위치가 없는 엣지 케이스(아직 측정된 페이지가 없는 문서)를 보호한다 —
     * 코덱이 최소 하나의 항목이 존재한다고 가정하는 대신, 빈 블롭으로 인코딩하고 오류 없이 복원할 수 있음을 보장한다.
     */
    @Test
    fun roundTripsAnEmptyPageStartsList() {
        val encoded = encodePageStartsBlob(LongArray(0))

        assertEquals(0, encoded.size)
        assertEquals(emptyList(), decodePageStartsBlob(encoded).toList())
    }

    /**
     * 오프셋 `0` — 유효한 실제 페이지 시작 위치인 맨 첫 번째 문자 — 이 데이터로서 라운드 트립에서
     * 살아남는다는 것을 보호한다. [roundTripsAnEmptyPageStartsList]가 보호하는 "항목 없음" 케이스와 구별된다.
     */
    @Test
    fun roundTripsOffsetZero() {
        val original = longArrayOf(0L)

        assertEquals(original.toList(), decodePageStartsBlob(encodePageStartsBlob(original)).toList())
    }

    /**
     * 실제 책 크기의 오프셋을 보호한다: 3.5M 문자는 이 리더가 여는 실제 책의 최대 크기이며
     * (디자인 노트 참조), [Int] 범위 안에 충분히 들어맞지만 리틀엔디언 `Int32` 표현의 하위 바이트뿐만
     * 아니라 모든 바이트를 인코딩에서 실제로 사용할 만큼 충분히 크다.
     */
    @Test
    fun roundTripsALargeOffsetWellWithinIntRange() {
        val original = longArrayOf(0L, 1_234_567L, 3_500_000L)

        assertEquals(original.toList(), decodePageStartsBlob(encodePageStartsBlob(original)).toList())
    }

    /**
     * 코덱이 설계된 규모에서 동작함을 보호한다: 16,734페이지 분량의 오름차순 페이지 시작 위치들 —
     * 클래스 문서의 실제 수치와 일치 — 이 격리된 케이스만이 아니라 모두 함께 올바르게 라운드 트립함을 검증한다.
     */
    @Test
    fun roundTripsManyAscendingOffsetsLikeARealBook() {
        val original = LongArray(16_734) { index -> index * 210L }

        assertEquals(original.toList(), decodePageStartsBlob(encodePageStartsBlob(original)).toList())
    }
}

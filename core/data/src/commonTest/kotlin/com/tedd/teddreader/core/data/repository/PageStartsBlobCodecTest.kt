package com.tedd.teddreader.core.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [encodePageStartsBlob] / [decodePageStartsBlob] replace a JSON array of longs with a little-endian
 * Int32 blob — on a 16,734-page book, decoding that JSON was most of the restore's 150 ms. Offsets fit
 * comfortably inside `Int` (the largest real book this reader opens is 3.5M characters), so each page
 * start costs 4 bytes instead of up to ~7 ASCII digits plus JSON punctuation.
 */
class PageStartsBlobCodecTest {
    @Test
    fun roundTripsAnEmptyPageStartsList() {
        val encoded = encodePageStartsBlob(LongArray(0))

        assertEquals(0, encoded.size)
        assertEquals(emptyList(), decodePageStartsBlob(encoded).toList())
    }

    @Test
    fun roundTripsOffsetZero() {
        val original = longArrayOf(0L)

        assertEquals(original.toList(), decodePageStartsBlob(encodePageStartsBlob(original)).toList())
    }

    @Test
    fun roundTripsALargeOffsetWellWithinIntRange() {
        // 3.5M characters is the largest real book this reader opens (see design notes) — comfortably
        // inside Int range but large enough to exercise every byte of the little-endian encoding.
        val original = longArrayOf(0L, 1_234_567L, 3_500_000L)

        assertEquals(original.toList(), decodePageStartsBlob(encodePageStartsBlob(original)).toList())
    }

    @Test
    fun roundTripsManyAscendingOffsetsLikeARealBook() {
        val original = LongArray(16_734) { index -> index * 210L }

        assertEquals(original.toList(), decodePageStartsBlob(encodePageStartsBlob(original)).toList())
    }
}

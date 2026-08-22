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
    /**
     * Guards the zero-page-starts edge case — a document with no measured pages yet — round-trips to
     * an empty blob and back without error, rather than the codec assuming at least one entry exists.
     */
    @Test
    fun roundTripsAnEmptyPageStartsList() {
        val encoded = encodePageStartsBlob(LongArray(0))

        assertEquals(0, encoded.size)
        assertEquals(emptyList(), decodePageStartsBlob(encoded).toList())
    }

    /**
     * Guards that offset `0` — the very first character, a legitimate real page start — survives the
     * round trip as data, distinct from the "no entries" case [roundTripsAnEmptyPageStartsList] guards.
     */
    @Test
    fun roundTripsOffsetZero() {
        val original = longArrayOf(0L)

        assertEquals(original.toList(), decodePageStartsBlob(encodePageStartsBlob(original)).toList())
    }

    /**
     * Guards realistic-book-sized offsets: 3.5M characters is the largest real book this reader opens
     * (see design notes), comfortably inside [Int] range but large enough that encoding it exercises
     * every byte of the little-endian `Int32` representation, not just its low-order bytes.
     */
    @Test
    fun roundTripsALargeOffsetWellWithinIntRange() {
        val original = longArrayOf(0L, 1_234_567L, 3_500_000L)

        assertEquals(original.toList(), decodePageStartsBlob(encodePageStartsBlob(original)).toList())
    }

    /**
     * Guards the codec at the scale it was written for: a 16,734-page book's worth of ascending page
     * starts — matching the class doc's real-world figure — all round-trip correctly together, not
     * just in isolation.
     */
    @Test
    fun roundTripsManyAscendingOffsetsLikeARealBook() {
        val original = LongArray(16_734) { index -> index * 210L }

        assertEquals(original.toList(), decodePageStartsBlob(encodePageStartsBlob(original)).toList())
    }
}

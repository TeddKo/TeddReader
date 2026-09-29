package com.tedd.teddreader.core.common

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ByteArrayLruCacheTest {
    @Test
    fun getPromotesAnEntryToMostRecentlyUsed() {
        val cache = ByteArrayLruCache<String>(maxByteCount = 5)
        cache.put("a", byteArrayOf(1, 2))
        cache.put("b", byteArrayOf(3, 4))

        assertContentEquals(byteArrayOf(1, 2), cache["a"])

        cache.put("c", byteArrayOf(5, 6), protectedKeys = setOf("c"))

        assertTrue("a" in cache.snapshot())
        assertFalse("b" in cache.snapshot())
    }

    /** 보호된 단일 배열도 예산보다 크면 기존 캐시를 밀어내지 않고 저장되지 않는다. */
    @Test
    fun putRejectsAProtectedEntryThatAloneExceedsBudget() {
        val cache = ByteArrayLruCache<String>(maxByteCount = 3)

        cache.put("old", byteArrayOf(1, 2))
        cache.put("current", byteArrayOf(3, 4, 5, 6), protectedKeys = setOf("current"))

        assertContentEquals(byteArrayOf(1, 2), cache["old"])
        assertNull(cache["current"])
        assertEquals(2, cache.totalByteCount)
    }

    /** 보호 집합의 합계가 예산을 넘게 만드는 새 항목도 저장되지 않는다. */
    @Test
    fun putRejectsAProtectedEntryWhenProtectedBytesWouldExceedBudget() {
        val cache = ByteArrayLruCache<String>(maxByteCount = 5)

        cache.put("first", byteArrayOf(1, 2, 3), protectedKeys = setOf("first"))
        cache.put(
            "second",
            byteArrayOf(4, 5, 6),
            protectedKeys = setOf("first", "second"),
        )

        assertContentEquals(byteArrayOf(1, 2, 3), cache["first"])
        assertNull(cache["second"])
        assertEquals(3, cache.totalByteCount)
    }

    @Test
    fun removeClearAndSnapshotReflectCurrentContents() {
        val cache = ByteArrayLruCache<String>(maxByteCount = 10)
        val bytes = byteArrayOf(7, 8, 9)
        cache.put("x", bytes)

        assertContentEquals(bytes, cache.remove("x"))
        assertNull(cache["x"])

        cache.put("y", byteArrayOf(1))
        assertEquals(listOf("y"), cache.snapshot().keys.toList())
        cache.clear()
        assertTrue(cache.snapshot().isEmpty())
        assertEquals(0, cache.totalByteCount)
    }
}

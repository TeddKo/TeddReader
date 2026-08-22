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

    @Test
    fun putKeepsTheProtectedCurrentEntryEvenWhenItAloneExceedsBudget() {
        val cache = ByteArrayLruCache<String>(maxByteCount = 3)

        cache.put("old", byteArrayOf(1, 2))
        cache.put("current", byteArrayOf(3, 4, 5, 6), protectedKeys = setOf("current"))

        assertNull(cache["old"])
        assertContentEquals(byteArrayOf(3, 4, 5, 6), cache["current"])
        assertEquals(4, cache.totalByteCount)
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

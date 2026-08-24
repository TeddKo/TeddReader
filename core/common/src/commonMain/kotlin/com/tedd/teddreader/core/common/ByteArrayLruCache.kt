package com.tedd.teddreader.core.common

/**
 * Tiny byte-budgeted LRU.
 *
 * `put` trims against that call's protected keys, so the caller can keep the currently visible entries even
 * when they alone exceed the budget.
 */
class ByteArrayLruCache<K>(
    private val maxByteCount: Int,
) {
    init {
        require(maxByteCount > 0) { "maxByteCount must be positive." }
    }

    private val entries = linkedMapOf<K, ByteArray>()
    private var byteCount = 0

    val size: Int get() = entries.size
    val totalByteCount: Int get() = byteCount

    operator fun get(key: K): ByteArray? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    fun put(key: K, value: ByteArray, protectedKeys: Set<K> = emptySet()) {
        val previous = entries.remove(key)
        if (previous != null) byteCount -= previous.size
        entries[key] = value
        byteCount += value.size
        trimToBudget(protectedKeys)
    }

    fun remove(key: K): ByteArray? {
        val removed = entries.remove(key) ?: return null
        byteCount -= removed.size
        return removed
    }

    fun clear() {
        entries.clear()
        byteCount = 0
    }

    fun snapshot(): Map<K, ByteArray> = LinkedHashMap(entries)

    private fun trimToBudget(protectedKeys: Set<K>) {
        if (byteCount <= maxByteCount) return
        val iterator = entries.entries.iterator()
        while (byteCount > maxByteCount && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in protectedKeys) continue
            byteCount -= entry.value.size
            iterator.remove()
        }
    }
}

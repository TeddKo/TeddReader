package com.tedd.teddreader.core.common

/**
 * 바이트 예산을 기준으로 동작하는 작은 LRU이다.
 *
 * `put`은 해당 호출에서 보호하는 키를 제외하고 정리하므로, 그 항목들만으로 예산을 초과하더라도 호출자는 현재 표시 중인 항목을 유지할 수 있다.
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

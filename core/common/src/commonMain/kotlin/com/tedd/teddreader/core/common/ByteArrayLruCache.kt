package com.tedd.teddreader.core.common

/**
 * 바이트 배열을 정해진 메모리 예산 안에서 최근 사용 순서로 보관한다.
 *
 * 호출자가 현재 화면에 필요한 키를 보호할 수 있지만, 보호 집합 자체가 예산을 넘게 만드는 새 항목은
 * 저장하지 않는다. 따라서 캐시는 항상 [maxByteCount] 이하이며, 저장되지 않은 활성 항목은 호출자가
 * 현재 응답으로 표시하고 다음 필요 시 다시 로드한다.
 *
 * @property maxByteCount 캐시가 보관할 수 있는 전체 바이트 수의 상한으로, 양수여야 한다.
 */
class ByteArrayLruCache<K>(
    private val maxByteCount: Int,
) {
    init {
        require(maxByteCount > 0) { "maxByteCount must be positive." }
    }

    /** 가장 오래 사용된 항목부터 순회하도록 접근 시 순서를 갱신하는 저장소. */
    private val entries = linkedMapOf<K, ByteArray>()

    /** [entries]가 현재 보관하는 배열 크기의 합. */
    private var byteCount = 0

    /** 현재 캐시에 저장된 항목 수. */
    val size: Int get() = entries.size

    /** 현재 캐시에 저장된 전체 바이트 수로, 항상 [maxByteCount] 이하이다. */
    val totalByteCount: Int get() = byteCount

    /**
     * 키의 바이트를 반환하고 해당 항목을 가장 최근 사용 위치로 옮긴다.
     *
     * @param key 조회할 캐시 키.
     * @return 저장된 바이트, 또는 캐시에 없으면 null.
     */
    operator fun get(key: K): ByteArray? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    /**
     * 바이트를 저장한 뒤 보호되지 않은 오래된 항목부터 제거해 예산을 맞춘다.
     *
     * 새 배열 하나가 예산보다 크거나, 새 보호 항목 때문에 보호 집합의 합계가 예산을 넘으면 기존
     * 항목을 바꾸지 않고 새 값을 저장하지 않는다. 보호되지 않은 새 항목은 보호 항목 뒤에 남는
     * 예산이 없으면 정리 과정에서 즉시 제거될 수 있다.
     *
     * @param key 저장할 캐시 키.
     * @param value 저장할 바이트.
     * @param protectedKeys 이번 정리에서 우선 보존할 활성 키 집합.
     */
    fun put(key: K, value: ByteArray, protectedKeys: Set<K> = emptySet()) {
        if (value.size > maxByteCount) return
        if (key in protectedKeys) {
            val protectedByteCount = entries.entries.sumOf { (entryKey, entryValue) ->
                entryValue.size.toLong().takeIf { entryKey != key && entryKey in protectedKeys } ?: 0L
            }
            if (protectedByteCount + value.size > maxByteCount.toLong()) return
        }

        val previous = entries.remove(key)
        if (previous != null) byteCount -= previous.size
        entries[key] = value
        byteCount += value.size
        trimToBudget(protectedKeys)
    }

    /**
     * 키의 항목을 캐시에서 제거한다.
     *
     * @param key 제거할 캐시 키.
     * @return 제거된 바이트, 또는 캐시에 없으면 null.
     */
    fun remove(key: K): ByteArray? {
        val removed = entries.remove(key) ?: return null
        byteCount -= removed.size
        return removed
    }

    /** 저장된 모든 항목을 제거하고 사용량을 0으로 되돌린다. */
    fun clear() {
        entries.clear()
        byteCount = 0
    }

    /**
     * 현재 최근 사용 순서를 유지한 방어적 맵 복사본을 만든다.
     *
     * @return 캐시의 현재 키와 바이트 참조를 담은 독립적인 맵.
     */
    fun snapshot(): Map<K, ByteArray> = LinkedHashMap(entries)

    /**
     * 보호되지 않은 가장 오래된 항목부터 제거해 현재 사용량을 예산 이하로 낮춘다.
     *
     * [put]이 보호 집합의 합계를 먼저 검사하므로 보호 항목만 남은 상태에서 예산을 초과할 수 없다.
     *
     * @param protectedKeys 이번 정리에서 제거하지 않을 활성 키 집합.
     */
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

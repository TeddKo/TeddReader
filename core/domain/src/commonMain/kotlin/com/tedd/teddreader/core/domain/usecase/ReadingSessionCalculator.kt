package com.tedd.teddreader.core.domain.usecase

import org.koin.core.annotation.Single

data class InactiveReadingRange(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
) {
    init {
        require(startEpochMillis >= 0L) { "startEpochMillis must be positive." }
        require(endEpochMillis >= startEpochMillis) { "endEpochMillis must be after startEpochMillis." }
    }
}

@Single
class ReadingSessionCalculator {
    fun activeMillis(
        startedAtEpochMillis: Long,
        endedAtEpochMillis: Long,
        inactiveRanges: List<InactiveReadingRange> = emptyList(),
    ): Long {
        require(startedAtEpochMillis >= 0L) { "startedAtEpochMillis must be positive." }
        require(endedAtEpochMillis >= startedAtEpochMillis) {
            "endedAtEpochMillis must be after startedAtEpochMillis."
        }

        val total = endedAtEpochMillis - startedAtEpochMillis
        val inactive = inactiveRanges
            .map { range ->
                val start = range.startEpochMillis.coerceAtLeast(startedAtEpochMillis)
                val end = range.endEpochMillis.coerceAtMost(endedAtEpochMillis)
                (end - start).coerceAtLeast(0L)
            }
            .sum()

        return (total - inactive).coerceAtLeast(0L)
    }
}

package com.tedd.teddreader.core.domain.usecase

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadingSessionCalculatorTest {
    private val calculator = ReadingSessionCalculator()

    @Test
    fun activeMillisExcludesInactiveRanges() {
        val activeMillis = calculator.activeMillis(
            startedAtEpochMillis = 1_000,
            endedAtEpochMillis = 11_000,
            inactiveRanges = listOf(
                InactiveReadingRange(3_000, 5_000),
                InactiveReadingRange(9_000, 12_000),
            ),
        )

        assertEquals(6_000, activeMillis)
    }
}

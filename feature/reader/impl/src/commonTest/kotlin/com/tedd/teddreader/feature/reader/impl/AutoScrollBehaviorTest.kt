package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.AutoScrollMode
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoScrollBehaviorTest {
    @Test
    fun readerEffectiveAutoScrollModeFallsBackOnlyForPdfLineMode() {
        assertEquals(
            AutoScrollMode.PAGE,
            readerEffectiveAutoScrollMode(mode = AutoScrollMode.LINE, isPdfMode = true),
        )
        assertEquals(
            AutoScrollMode.LINE,
            readerEffectiveAutoScrollMode(mode = AutoScrollMode.LINE, isPdfMode = false),
        )
        assertEquals(
            AutoScrollMode.PIXEL,
            readerEffectiveAutoScrollMode(mode = AutoScrollMode.PIXEL, isPdfMode = true),
        )
        assertEquals(
            AutoScrollMode.PAGE,
            readerEffectiveAutoScrollMode(mode = AutoScrollMode.PAGE, isPdfMode = true),
        )
    }

    @Test
    fun autoScrollPageDelayMillisUsesInverseSecondsPerPage() {
        assertEquals(1_000L, autoScrollPageDelayMillis(speed = 1f))
        assertEquals(10_000L, autoScrollPageDelayMillis(speed = 0.1f))
    }

    @Test
    fun autoScrollDistancePxUsesTwoHundredDpPerSecondAtMaxSpeed() {
        assertEquals(
            400f,
            autoScrollDistancePx(speed = 1f, density = 2f, elapsedMillis = 1_000L),
        )
        assertEquals(
            40f,
            autoScrollDistancePx(speed = 0.1f, density = 2f, elapsedMillis = 1_000L),
        )
    }

    @Test
    fun autoScrollLineDelayMillisDerivesDelayFromLineHeightAndPixelsPerSecond() {
        assertEquals(150L, autoScrollLineDelayMillis(lineHeightPx = 60f, pixelsPerSecond = 400f))
    }
}

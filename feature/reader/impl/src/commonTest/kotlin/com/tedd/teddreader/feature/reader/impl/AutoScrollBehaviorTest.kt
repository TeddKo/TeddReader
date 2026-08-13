package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.feature.reader.impl.component.ReaderPageMovement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoScrollBehaviorTest {
    @Test
    fun readerEffectiveAutoScrollModeFallsBackForVisualLineMode() {
        assertEquals(
            AutoScrollMode.PAGE,
            readerEffectiveAutoScrollMode(mode = AutoScrollMode.LINE, isVisualMode = true),
        )
        assertEquals(
            AutoScrollMode.LINE,
            readerEffectiveAutoScrollMode(mode = AutoScrollMode.LINE, isVisualMode = false),
        )
        assertEquals(
            AutoScrollMode.PIXEL,
            readerEffectiveAutoScrollMode(mode = AutoScrollMode.PIXEL, isVisualMode = true),
        )
        assertEquals(
            AutoScrollMode.PAGE,
            readerEffectiveAutoScrollMode(mode = AutoScrollMode.PAGE, isVisualMode = true),
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

    @Test
    fun readerAutoScrollPageMovementRoutesByModeAndAnimation() {
        PageAnimation.entries.forEach { animation ->
            assertEquals(
                ReaderPageMovement.Next,
                readerAutoScrollPageMovement(AutoScrollMode.PAGE, animation),
            )
        }

        val incrementalAnimations = listOf(
            PageAnimation.SCROLL,
            PageAnimation.SLIDE,
            PageAnimation.SHEET_FLIP,
            PageAnimation.FLUID_PAGER,
            PageAnimation.CIRCLE_REVEAL,
            PageAnimation.MOVIE_CAROUSEL,
            PageAnimation.PAGE_FLIP,
            PageAnimation.BOOK_CURL,
            PageAnimation.CURL_PAGER,
        )
        incrementalAnimations.forEach { animation ->
            assertNull(readerAutoScrollPageMovement(AutoScrollMode.PIXEL, animation))
            assertNull(readerAutoScrollPageMovement(AutoScrollMode.LINE, animation))
        }

        val discreteAnimations = listOf(
            PageAnimation.NONE,
            PageAnimation.FADE,
        )
        discreteAnimations.forEach { animation ->
            assertEquals(
                ReaderPageMovement.Next,
                readerAutoScrollPageMovement(AutoScrollMode.PIXEL, animation),
            )
            assertEquals(
                ReaderPageMovement.Next,
                readerAutoScrollPageMovement(AutoScrollMode.LINE, animation),
            )
        }
    }
}

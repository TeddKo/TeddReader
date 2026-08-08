package com.tedd.teddreader.feature.reader.impl.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderPagerPageTargetTest {
    @Test
    fun requestedMovementUsesTheSameSpreadStepAndBounds() {
        assertEquals(1, readerPagerRequestedPage(3, 10, 2, ReaderPageMovement.Previous))
        assertEquals(5, readerPagerRequestedPage(3, 10, 2, ReaderPageMovement.Next))
        assertEquals(0, readerPagerRequestedPage(1, 10, 2, ReaderPageMovement.Previous))
        assertNull(readerPagerRequestedPage(0, 10, 2, ReaderPageMovement.Previous))
        assertNull(readerPagerRequestedPage(8, 10, 2, ReaderPageMovement.Next))
    }

    @Test
    fun stepTwoMapsAdjacentPagesToBoundedSpreadAnchors() {
        assertEquals(1, readerPagerAdjacentPage(3, 10, 2, -1))
        assertEquals(5, readerPagerAdjacentPage(3, 10, 2, 1))
        assertEquals(0, readerPagerAdjacentPage(1, 10, 2, -1))
        assertEquals(4, readerPagerAdjacentPage(2, 5, 2, 1))
        assertNull(readerPagerAdjacentPage(2, 4, 2, 1))
        assertNull(readerPagerAdjacentPage(8, 10, 2, 1))
        assertEquals(3, readerPagerAdjacentPage(3, 10, 2, 0))
        assertNull(readerPagerAdjacentPage(0, 10, 2, -1))
        assertNull(readerPagerAdjacentPage(9, 10, 2, 1))
    }
}

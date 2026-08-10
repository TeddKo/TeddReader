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

    @Test
    fun dragBoundaryBlocksOnlyMissingDirection() {
        assertEquals(true, foundationPagerShouldBlockDrag(primaryDelta = 24f, hasPreviousPage = false, hasNextPage = true))
        assertEquals(false, foundationPagerShouldBlockDrag(primaryDelta = 24f, hasPreviousPage = true, hasNextPage = false))
        assertEquals(true, foundationPagerShouldBlockDrag(primaryDelta = -24f, hasPreviousPage = true, hasNextPage = false))
        assertEquals(false, foundationPagerShouldBlockDrag(primaryDelta = -24f, hasPreviousPage = false, hasNextPage = true))
        assertEquals(false, foundationPagerShouldBlockDrag(primaryDelta = 0f, hasPreviousPage = false, hasNextPage = false))
    }

    @Test
    fun scrollPagerUsesOnlyAvailableOffsets() {
        assertEquals(listOf(0, 1), readerScrollPageOffsets(hasPreviousPage = false, hasNextPage = true))
        assertEquals(listOf(-1, 0), readerScrollPageOffsets(hasPreviousPage = true, hasNextPage = false))
        assertEquals(listOf(-1, 0, 1), readerScrollPageOffsets(hasPreviousPage = true, hasNextPage = true))
        assertEquals(0, readerScrollCurrentIndex(hasPreviousPage = false))
        assertEquals(1, readerScrollCurrentIndex(hasPreviousPage = true))
    }

    @Test
    fun scrollPagerSettledOffsetRequiresPreviousBoundaryBeforeBackwardTurn() {
        assertNull(readerScrollSettledPageOffset(pageOffset = -1, canScrollBackward = true))
        assertEquals(-1, readerScrollSettledPageOffset(pageOffset = -1, canScrollBackward = false))
        assertEquals(1, readerScrollSettledPageOffset(pageOffset = 1, canScrollBackward = true))
        assertNull(readerScrollSettledPageOffset(pageOffset = 0, canScrollBackward = true))
    }
}

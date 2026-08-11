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
    fun `scroll anchors cover whole document and map page to anchor index`() {
        assertEquals(listOf(0, 1, 2, 3, 4), readerScrollPageAnchors(pageCount = 5, pageStep = 1))
        assertEquals(listOf(0, 2, 4), readerScrollPageAnchors(pageCount = 5, pageStep = 2))
        assertEquals(emptyList(), readerScrollPageAnchors(pageCount = 0, pageStep = 1))

        assertEquals(1, readerScrollAnchorIndex(page = 3, anchors = listOf(0, 2, 4)))
        assertEquals(2, readerScrollAnchorIndex(page = 4, anchors = listOf(0, 2, 4)))
    }

    @Test
    fun foundationPagerDragTargetOffsetFollowsManualFlingContract() {
        assertEquals(
            0,
            foundationPagerDragTargetOffset(
                dragDistancePx = -40f,
                velocityPxPerSecond = -100f,
                viewportExtentPx = 1000f,
                hasPreviousPage = true,
                hasNextPage = true,
            ),
        )
        assertEquals(
            1,
            foundationPagerDragTargetOffset(
                dragDistancePx = -40f,
                velocityPxPerSecond = -3000f,
                viewportExtentPx = 1000f,
                hasPreviousPage = true,
                hasNextPage = true,
            ),
        )
        assertEquals(
            -1,
            foundationPagerDragTargetOffset(
                dragDistancePx = 40f,
                velocityPxPerSecond = 3000f,
                viewportExtentPx = 1000f,
                hasPreviousPage = true,
                hasNextPage = true,
            ),
        )
        assertEquals(
            1,
            foundationPagerDragTargetOffset(
                dragDistancePx = -400f,
                velocityPxPerSecond = -100f,
                viewportExtentPx = 1000f,
                hasPreviousPage = true,
                hasNextPage = true,
            ),
        )
        assertEquals(
            0,
            foundationPagerDragTargetOffset(
                dragDistancePx = -40f,
                velocityPxPerSecond = -3000f,
                viewportExtentPx = 1000f,
                hasPreviousPage = true,
                hasNextPage = false,
            ),
        )
        assertEquals(
            0,
            foundationPagerDragTargetOffset(
                dragDistancePx = 40f,
                velocityPxPerSecond = 3000f,
                viewportExtentPx = 1000f,
                hasPreviousPage = false,
                hasNextPage = true,
            ),
        )
    }
}

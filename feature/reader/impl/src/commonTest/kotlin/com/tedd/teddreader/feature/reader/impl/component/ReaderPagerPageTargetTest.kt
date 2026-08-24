package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.ui.geometry.Offset
import com.tedd.teddreader.core.common.model.PageTurnMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the pure page-target math [ReaderPager.kt][com.tedd.teddreader.feature.reader.impl.component]
 * exposes: adjacent-page resolution for spreads, drag-to-target and tap-zone resolution for the
 * Foundation pager, and scroll-anchor mapping for the continuous-scroll pager. All of it is plain
 * arithmetic on page indices with no Compose dependency, so it is tested directly rather than
 * through a composable harness.
 */
class ReaderPagerPageTargetTest {
    /**
     * Verifies [readerPagerRequestedPage] resolves [ReaderPageMovement.Previous]/[ReaderPageMovement.Next]
     * through the same two-page-step arithmetic and document bounds as [readerPagerAdjacentPage]
     * does directly, including both null-at-the-edge cases.
     */
    @Test
    fun requestedMovementUsesTheSameSpreadStepAndBounds() {
        assertEquals(1, readerPagerRequestedPage(3, 10, 2, ReaderPageMovement.Previous))
        assertEquals(5, readerPagerRequestedPage(3, 10, 2, ReaderPageMovement.Next))
        assertEquals(0, readerPagerRequestedPage(1, 10, 2, ReaderPageMovement.Previous))
        assertNull(readerPagerRequestedPage(0, 10, 2, ReaderPageMovement.Previous))
        assertNull(readerPagerRequestedPage(8, 10, 2, ReaderPageMovement.Next))
    }

    /**
     * Verifies [readerPagerAdjacentPage]'s bounds and step handling for a two-page spread
     * ([pageStep] 2): a normal step in either direction, a step clamped short at the start of the
     * document, a target beyond the last page returning null, a zero offset returning the current
     * page unchanged, and both document edges returning null in their respective direction.
     */
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

    /**
     * Verifies [foundationPagerShouldBlockDrag] blocks a drag only when it points toward a
     * direction with no adjacent page, in either direction, and never blocks a drag of zero delta.
     */
    @Test
    fun dragBoundaryBlocksOnlyMissingDirection() {
        assertEquals(true, foundationPagerShouldBlockDrag(primaryDelta = 24f, hasPreviousPage = false, hasNextPage = true))
        assertEquals(false, foundationPagerShouldBlockDrag(primaryDelta = 24f, hasPreviousPage = true, hasNextPage = false))
        assertEquals(true, foundationPagerShouldBlockDrag(primaryDelta = -24f, hasPreviousPage = true, hasNextPage = false))
        assertEquals(false, foundationPagerShouldBlockDrag(primaryDelta = -24f, hasPreviousPage = false, hasNextPage = true))
        assertEquals(false, foundationPagerShouldBlockDrag(primaryDelta = 0f, hasPreviousPage = false, hasNextPage = false))
    }

    /**
     * Verifies [readerScrollPageAnchors] produces every page as its own anchor at step 1 and every
     * other page at step 2, including an empty document, and that [readerScrollAnchorIndex]
     * resolves a page number to the anchor that owns it.
     */
    @Test
    fun `scroll anchors cover whole document and map page to anchor index`() {
        assertEquals(listOf(0, 1, 2, 3, 4), readerScrollPageAnchors(pageCount = 5, pageStep = 1))
        assertEquals(listOf(0, 2, 4), readerScrollPageAnchors(pageCount = 5, pageStep = 2))
        assertEquals(emptyList(), readerScrollPageAnchors(pageCount = 0, pageStep = 1))

        assertEquals(1, readerScrollAnchorIndex(page = 3, anchors = listOf(0, 2, 4)))
        assertEquals(2, readerScrollAnchorIndex(page = 4, anchors = listOf(0, 2, 4)))
    }

    /**
     * Verifies [foundationPagerDragTargetOffset]'s fling/drag-distance thresholds: a slow, short
     * drag settles back to 0; a fast fling or a long drag commits to the adjacent page in the
     * matching direction; and a commit toward a missing page is clamped back to 0 instead of
     * targeting a page that does not exist.
     */
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

    @Test
    fun nextRequestDispatchesAtKnownEndOnlyWhilePaginationIsIncomplete() {
        assertEquals(
            true,
            readerPagerShouldDispatchRequest(
                targetPage = null,
                movement = ReaderPageMovement.Next,
                canRequestNextPage = true,
            ),
        )
        assertEquals(
            false,
            readerPagerShouldDispatchRequest(
                targetPage = null,
                movement = ReaderPageMovement.Next,
                canRequestNextPage = false,
            ),
        )
        assertEquals(
            false,
            readerPagerShouldDispatchRequest(
                targetPage = null,
                movement = ReaderPageMovement.Previous,
                canRequestNextPage = true,
            ),
        )
    }

    @Test
    fun loadableNextReusesCurrentPageContentInsteadOfLeavingBlank() {
        assertEquals(true, readerPagerCanAdvanceForward(hasActualNextPage = false, canRequestNextPage = true))
        assertEquals(
            12,
            readerPagerDisplayedPage(
                currentPage = 12,
                adjacentPage = null,
                pageOffset = 1,
                canRequestNextPage = true,
            ),
        )
        assertNull(
            readerPagerDisplayedPage(
                currentPage = 12,
                adjacentPage = null,
                pageOffset = 1,
                canRequestNextPage = false,
            ),
        )
    }

    @Test
    fun scrollOverscrollRequestsNextOnlyForForwardDragAtKnownEnd() {
        assertEquals(
            true,
            readerScrollShouldRequestNextOnOverscroll(
                canRequestNextPage = true,
                startedAtEnd = true,
                pageTurnMode = PageTurnMode.VERTICAL,
                drag = Offset(8f, -120f),
            ),
        )
        assertEquals(
            true,
            readerScrollShouldRequestNextOnOverscroll(
                canRequestNextPage = true,
                startedAtEnd = true,
                pageTurnMode = PageTurnMode.HORIZONTAL,
                drag = Offset(-120f, 8f),
            ),
        )
        assertEquals(
            false,
            readerScrollShouldRequestNextOnOverscroll(
                canRequestNextPage = true,
                startedAtEnd = false,
                pageTurnMode = PageTurnMode.VERTICAL,
                drag = Offset(0f, -120f),
            ),
        )
        assertEquals(
            false,
            readerScrollShouldRequestNextOnOverscroll(
                canRequestNextPage = false,
                startedAtEnd = true,
                pageTurnMode = PageTurnMode.VERTICAL,
                drag = Offset(0f, -120f),
            ),
        )
        assertEquals(
            false,
            readerScrollShouldRequestNextOnOverscroll(
                canRequestNextPage = true,
                startedAtEnd = true,
                pageTurnMode = PageTurnMode.VERTICAL,
                drag = Offset(0f, -40f),
            ),
        )
        assertEquals(
            false,
            readerScrollShouldRequestNextOnOverscroll(
                canRequestNextPage = true,
                startedAtEnd = true,
                pageTurnMode = PageTurnMode.VERTICAL,
                drag = Offset(120f, -80f),
            ),
        )
        assertEquals(
            true,
            readerScrollShouldRequestNextOnOverscroll(
                canRequestNextPage = true,
                startedAtEnd = true,
                pageTurnMode = PageTurnMode.VERTICAL,
                drag = Offset.Zero + Offset(0f, -90f) + Offset(0f, -10f),
            ),
        )
    }

    /**
     * Verifies [foundationPagerTapAction]'s F16 fix: a tap at the start or end of a document, where
     * there is no adjacent page to turn to, must fall through to toggling the controls rather than
     * doing nothing at all. Five assertions in order:
     * 1. The previous zone (below 25% of extent) turns back when there is a previous page.
     * 2. The same zone with no previous page (first page of the document) falls through to the
     *    middle zone's behaviour instead of doing nothing — the F16 fix itself.
     * 3. The next zone (above 75% of extent) turns forward when there is a next page.
     * 4. The same zone with no next page (last page of the document) falls through too — end of
     *    book and a swallowed tap must be distinguishable.
     * 5. The middle zone always toggles controls, regardless of which pages are available.
     */
    @Test
    fun tapZoneFallsThroughToToggleControlsWhenTheAdjacentPageIsMissing() {
        assertEquals(
            FoundationPagerTapAction.Previous,
            foundationPagerTapAction(primary = 10f, extent = 100, hasPreviousPage = true, hasNextPage = true),
        )
        assertEquals(
            FoundationPagerTapAction.ToggleControls,
            foundationPagerTapAction(primary = 10f, extent = 100, hasPreviousPage = false, hasNextPage = true),
        )
        assertEquals(
            FoundationPagerTapAction.Next,
            foundationPagerTapAction(primary = 90f, extent = 100, hasPreviousPage = true, hasNextPage = true),
        )
        assertEquals(
            FoundationPagerTapAction.ToggleControls,
            foundationPagerTapAction(primary = 90f, extent = 100, hasPreviousPage = true, hasNextPage = false),
        )
        assertEquals(
            FoundationPagerTapAction.ToggleControls,
            foundationPagerTapAction(primary = 50f, extent = 100, hasPreviousPage = false, hasNextPage = false),
        )
    }
}

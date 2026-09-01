package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.ui.geometry.Offset
import com.tedd.teddreader.core.common.model.PageTurnMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ReaderPager.kt][com.tedd.teddreader.feature.reader.impl.component]가 노출하는 순수 page-target 계산에
 * 대한 단위 테스트다: spread를 위한 인접 페이지 해석, Foundation pager를 위한 drag-to-target과 tap 영역
 * 해석, 연속 스크롤 pager를 위한 scroll-anchor 매핑. 전부 페이지 인덱스에 대한 순수 산술이며 Compose
 * 의존성이 없으므로, composable 하네스를 거치지 않고 직접 테스트한다.
 */
class ReaderPagerPageTargetTest {
    /**
     * [readerPagerRequestedPage]가 [ReaderPageMovement.Previous]/[ReaderPageMovement.Next]를
     * [readerPagerAdjacentPage]가 직접 쓰는 것과 같은 두 페이지 스텝 산술과 문서 경계로 해석하는지,
     * 두 경계에서의 null 케이스를 포함해 검증한다.
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
     * 두 페이지 spread([pageStep] 2)에 대한 [readerPagerAdjacentPage]의 경계와 스텝 처리를 검증한다:
     * 양방향의 일반적인 스텝, 문서 시작에서 짧게 clamp되는 스텝, 마지막 페이지를 넘어서는 대상이 null을
     * 반환하는 경우, offset이 0일 때 현재 페이지를 그대로 반환하는 경우, 그리고 문서 양쪽 경계가 각 방향
     * 에서 null을 반환하는 경우.
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
     * [foundationPagerShouldBlockDrag]가 인접 페이지가 없는 방향을 향할 때만, 양방향 모두에 대해 drag를
     * 막으며, delta가 0인 drag는 절대 막지 않음을 검증한다.
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
     * [readerScrollPageAnchors]가 step 1에서는 모든 페이지를 각자의 anchor로, step 2에서는 한 페이지
     * 걸러 anchor로 만들어내는지 — 빈 문서 포함 — 그리고 [readerScrollAnchorIndex]가 페이지 번호를
     * 그 페이지를 소유한 anchor로 해석하는지 검증한다.
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
     * [foundationPagerDragTargetOffset]의 fling/drag 거리 임계값을 검증한다: 느리고 짧은 drag는 0으로
     * 되돌아가고, 빠른 fling이나 긴 drag는 대응하는 방향의 인접 페이지로 확정되며, 존재하지 않는 페이지를
     * 향한 확정은 그 페이지를 목표로 삼는 대신 0으로 clamp된다.
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
     * [foundationPagerTapAction]의 F16 수정을 검증한다: 넘어갈 인접 페이지가 없는 문서의 시작이나
     * 끝에서의 탭은 아무 일도 하지 않는 대신 controls 토글로 폴스루해야 한다. 순서대로 다섯 개의
     * 단언:
     * 1. 이전 영역(extent의 25% 아래)은 이전 페이지가 있으면 뒤로 넘어간다.
     * 2. 이전 페이지가 없는(문서의 첫 페이지) 같은 영역은 아무 일도 하지 않는 대신 가운데 영역의
     *    동작으로 폴스루한다 — F16 수정 그 자체.
     * 3. 다음 영역(extent의 75% 위)은 다음 페이지가 있으면 앞으로 넘어간다.
     * 4. 다음 페이지가 없는(문서의 마지막 페이지) 같은 영역도 폴스루한다 — 책의 끝과 삼켜진 탭은
     *    구별되어야 한다.
     * 5. 가운데 영역은 어떤 페이지를 쓸 수 있는지와 무관하게 항상 controls를 토글한다.
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

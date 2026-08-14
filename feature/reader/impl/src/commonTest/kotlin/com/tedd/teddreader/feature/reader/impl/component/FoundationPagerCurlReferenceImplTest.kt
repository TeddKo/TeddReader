package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoundationPagerCurlReferenceImplTest {
    @Test
    fun defaultEdgeUsesCurrentPlusAndMinusRotatedVector() {
        val edge = foundationReferenceCurlEdge(
            size = IntSize(100, 200),
            startOffset = Offset(100f, 50f),
            currentOffset = Offset(60f, 70f),
        )

        assertTrue((edge.top - Offset(40f, 30f)).getDistance() < 0.001f)
        assertTrue((edge.bottom - Offset(80f, 110f)).getDistance() < 0.001f)
    }

    @Test
    fun dragDirectionComesFromMovementAnywhereOnThePage() {
        assertEquals(
            FoundationReferenceCurlDirection.Forward,
            foundationReferenceCurlDirection(Offset(20f, 0f), Offset(10f, 0f), true, true),
        )
        assertEquals(
            FoundationReferenceCurlDirection.Forward,
            foundationReferenceCurlDirection(Offset(80f, 0f), Offset(70f, 0f), true, true),
        )
        assertEquals(
            FoundationReferenceCurlDirection.Backward,
            foundationReferenceCurlDirection(Offset(20f, 0f), Offset(30f, 0f), true, true),
        )
        assertEquals(
            FoundationReferenceCurlDirection.Backward,
            foundationReferenceCurlDirection(Offset(80f, 0f), Offset(90f, 0f), true, true),
        )
        assertNull(foundationReferenceCurlDirection(Offset(50f, 0f), Offset(50f, 0f), true, true))
        assertNull(foundationReferenceCurlDirection(Offset(50f, 0f), Offset(60f, 0f), false, true))
        assertNull(foundationReferenceCurlDirection(Offset(50f, 0f), Offset(40f, 0f), true, false))
    }

    @Test
    fun spreadSwipeSpansTheWholeViewportInBothDirections() {
        val forward = FoundationReferenceCurlDirection.Forward
        val backward = FoundationReferenceCurlDirection.Backward

        assertEquals(Offset(SpreadLeafWidth, 80f), spreadLeafOffset(SpreadViewportWidth, forward))
        assertEquals(Offset(0f, 80f), spreadLeafOffset(0f, forward))
        assertEquals(Offset(SpreadLeafWidth, 80f), spreadLeafOffset(0f, backward))
        assertEquals(Offset(0f, 80f), spreadLeafOffset(SpreadViewportWidth, backward))
    }

    @Test
    fun spreadFoldProgressMatchesTheSinglePaneCurlAtEveryPointerPosition() {
        listOf(0f, 250f, 500f, 750f, 1000f).forEach { x ->
            val singlePane = foundationReferenceCurlLeafOffset(
                offset = Offset(x, 80f),
                axis = FoundationReferenceCurlAxis.Horizontal,
                direction = FoundationReferenceCurlDirection.Forward,
                isSpread = false,
                leafScale = 1f,
                leafWidth = SpreadViewportWidth,
            ).x / SpreadViewportWidth

            assertEquals(
                singlePane,
                spreadLeafOffset(x, FoundationReferenceCurlDirection.Forward).x / SpreadLeafWidth,
                0.0001f,
            )
            assertEquals(
                1f - singlePane,
                spreadLeafOffset(x, FoundationReferenceCurlDirection.Backward).x / SpreadLeafWidth,
                0.0001f,
            )
        }
    }

    @Test
    fun spreadSwipeCompletesAtTheSameViewportTravelAsTheSinglePaneCurl() {
        val leafSize = IntSize(SpreadLeafWidth.toInt(), 200)
        fun succeedsAfterViewportTravel(travel: Float) = foundationReferenceCurlDragSucceeds(
            direction = FoundationReferenceCurlDirection.Forward,
            start = spreadLeafOffset(SpreadViewportWidth, FoundationReferenceCurlDirection.Forward),
            end = spreadLeafOffset(
                SpreadViewportWidth - travel,
                FoundationReferenceCurlDirection.Forward,
            ),
            size = leafSize,
            axis = FoundationReferenceCurlAxis.Horizontal,
        )

        assertFalse(succeedsAfterViewportTravel(SpreadViewportWidth * 0.19f))
        assertTrue(succeedsAfterViewportTravel(SpreadViewportWidth * 0.21f))
    }

    @Test
    fun spreadPreviousSwipeReusesForwardCurlGeometry() {
        assertEquals(
            FoundationReferenceCurlDirection.Forward,
            foundationReferenceCurlGeometryDirection(
                FoundationReferenceCurlDirection.Backward,
                isSpread = true,
            ),
        )
        assertEquals(
            FoundationReferenceCurlDirection.Backward,
            foundationReferenceCurlGeometryDirection(
                FoundationReferenceCurlDirection.Backward,
                isSpread = false,
            ),
        )
    }

    @Test
    fun changedPageKeyUsesRestingEdgesBeforeTheResetEffectRuns() {
        assertEquals(
            FoundationReferenceCurlEdge.right(IntSize(100, 200)),
            foundationReferenceVisibleCurlEdge(
                pageKey = 4,
                renderedPageKey = 2,
                animatedEdge = FoundationReferenceCurlEdge.left(IntSize(100, 200)),
                restingEdge = FoundationReferenceCurlEdge.right(IntSize(100, 200)),
            ),
        )
    }

    @Test
    fun horizontalDragCompletesAtTwentyPercentDirectionalTravel() {
        val size = IntSize(100, 200)

        assertFalse(
            foundationReferenceCurlDragSucceeds(
                direction = FoundationReferenceCurlDirection.Forward,
                start = Offset(50f, 0f),
                end = Offset(30.1f, 0f),
                size = size,
                axis = FoundationReferenceCurlAxis.Horizontal,
            ),
        )
        assertTrue(
            foundationReferenceCurlDragSucceeds(
                direction = FoundationReferenceCurlDirection.Forward,
                start = Offset(50f, 0f),
                end = Offset(30f, 0f),
                size = size,
                axis = FoundationReferenceCurlAxis.Horizontal,
            ),
        )
        assertFalse(
            foundationReferenceCurlDragSucceeds(
                direction = FoundationReferenceCurlDirection.Backward,
                start = Offset(50f, 0f),
                end = Offset(69.9f, 0f),
                size = size,
                axis = FoundationReferenceCurlAxis.Horizontal,
            ),
        )
        assertTrue(
            foundationReferenceCurlDragSucceeds(
                direction = FoundationReferenceCurlDirection.Backward,
                start = Offset(50f, 0f),
                end = Offset(70f, 0f),
                size = size,
                axis = FoundationReferenceCurlAxis.Horizontal,
            ),
        )
    }

    @Test
    fun verticalDragUsesTwentyPercentOfTheShorterViewportSide() {
        val portrait = FoundationReferenceCurlAxis.Vertical.canonicalSize(IntSize(100, 200))
        val landscape = FoundationReferenceCurlAxis.Vertical.canonicalSize(IntSize(200, 100))

        assertFalse(
            foundationReferenceCurlDragSucceeds(
                FoundationReferenceCurlDirection.Forward,
                start = Offset(100f, 0f),
                end = Offset(80.1f, 0f),
                size = portrait,
                axis = FoundationReferenceCurlAxis.Vertical,
            ),
        )
        assertTrue(
            foundationReferenceCurlDragSucceeds(
                FoundationReferenceCurlDirection.Forward,
                start = Offset(100f, 0f),
                end = Offset(80f, 0f),
                size = portrait,
                axis = FoundationReferenceCurlAxis.Vertical,
            ),
        )
        assertFalse(
            foundationReferenceCurlDragSucceeds(
                FoundationReferenceCurlDirection.Backward,
                start = Offset(50f, 0f),
                end = Offset(69.9f, 0f),
                size = landscape,
                axis = FoundationReferenceCurlAxis.Vertical,
            ),
        )
        assertTrue(
            foundationReferenceCurlDragSucceeds(
                FoundationReferenceCurlDirection.Backward,
                start = Offset(50f, 0f),
                end = Offset(70f, 0f),
                size = landscape,
                axis = FoundationReferenceCurlAxis.Vertical,
            ),
        )
    }

    @Test
    fun previousCurlPageIsLayeredAboveCurrentPage() {
        assertTrue(foundationReferenceCurlZIndex(-1) > foundationReferenceCurlZIndex(0))
        assertTrue(foundationReferenceCurlZIndex(0) > foundationReferenceCurlZIndex(1))
    }

    @Test
    fun autoScrollTapsAlwaysToggleControlsRegardlessOfZoneOrAvailablePages() {
        val size = IntSize(100, 200)

        assertEquals(
            FoundationReferenceCurlTapAction.ToggleControls,
            foundationReferenceCurlTapAction(
                position = Offset(10f, 100f),
                size = size,
                axis = FoundationReferenceCurlAxis.Horizontal,
                canGoBackward = false,
                canGoForward = true,
                isAutoScrollEnabled = true,
            ),
        )
        assertEquals(
            FoundationReferenceCurlTapAction.ToggleControls,
            foundationReferenceCurlTapAction(
                position = Offset(50f, 100f),
                size = size,
                axis = FoundationReferenceCurlAxis.Horizontal,
                canGoBackward = true,
                canGoForward = true,
                isAutoScrollEnabled = true,
            ),
        )
        assertEquals(
            FoundationReferenceCurlTapAction.ToggleControls,
            foundationReferenceCurlTapAction(
                position = Offset(90f, 100f),
                size = size,
                axis = FoundationReferenceCurlAxis.Horizontal,
                canGoBackward = true,
                canGoForward = false,
                isAutoScrollEnabled = true,
            ),
        )
    }

    @Test
    fun tapsUsePrimaryAxisQuarterZonesAndRespectAvailablePages() {
        val size = IntSize(100, 200)

        assertEquals(
            FoundationReferenceCurlTapAction.Backward,
            foundationReferenceCurlTapAction(Offset(24f, 100f), size, FoundationReferenceCurlAxis.Horizontal, true, true),
        )
        assertEquals(
            FoundationReferenceCurlTapAction.ToggleControls,
            foundationReferenceCurlTapAction(Offset(25f, 100f), size, FoundationReferenceCurlAxis.Horizontal, true, true),
        )
        assertEquals(
            FoundationReferenceCurlTapAction.ToggleControls,
            foundationReferenceCurlTapAction(Offset(50f, 75f), size, FoundationReferenceCurlAxis.Vertical, true, true),
        )
        assertEquals(
            FoundationReferenceCurlTapAction.Forward,
            foundationReferenceCurlTapAction(Offset(50f, 151f), size, FoundationReferenceCurlAxis.Vertical, true, true),
        )
        assertNull(
            foundationReferenceCurlTapAction(Offset(10f, 100f), size, FoundationReferenceCurlAxis.Horizontal, false, true),
        )
        assertNull(
            foundationReferenceCurlTapAction(Offset(90f, 100f), size, FoundationReferenceCurlAxis.Horizontal, true, false),
        )
    }

    private fun spreadLeafOffset(
        x: Float,
        direction: FoundationReferenceCurlDirection,
    ): Offset = foundationReferenceCurlLeafOffset(
        offset = Offset(x, 80f),
        axis = FoundationReferenceCurlAxis.Horizontal,
        direction = direction,
        isSpread = true,
        leafScale = SpreadLeafWidth / SpreadViewportWidth,
        leafWidth = SpreadLeafWidth,
    )

    private companion object {
        const val SpreadViewportWidth = 1000f
        const val SpreadLeafWidth = 500f
    }
}

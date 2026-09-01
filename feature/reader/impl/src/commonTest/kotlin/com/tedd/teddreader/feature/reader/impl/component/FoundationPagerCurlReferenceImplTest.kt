package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.tedd.teddreader.core.common.model.PageTurnMode
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure geometry behind [FoundationPagerCurlReferenceImpl]'s pagecurl
 * interaction: fold-edge construction, drag direction/completion thresholds, the spread-mode
 * leaf-offset scaling that maps full-viewport pointer travel onto the narrower folding leaf, and
 * tap-zone resolution. All of it is plain math with no Compose dependency, so it is tested
 * directly rather than through a composable harness.
 */
class FoundationPagerCurlReferenceImplTest {
    /**
     * Verifies [foundationReferenceCurlEdge] builds the fold's top/bottom edge points as the
     * current pointer position plus and minus a vector rotated 90 degrees from the line to the
     * viewport's near corner.
     */
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

    /**
     * Verifies [foundationReferenceCurlDirection] reads direction purely from which way the
     * pointer moved — leftward anywhere on the page means forward, rightward means backward —
     * and returns null both when there is no movement and when the implied direction has no page
     * to turn to.
     */
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

    /**
     * Verifies [spreadLeafOffset] (via [foundationReferenceCurlLeafOffset] in spread mode) maps
     * the full viewport width onto the leaf's own narrower width: the far viewport edge in the
     * fold's direction always means "fully folded" ([SpreadLeafWidth]) and the opposite edge
     * always means "at rest" (0), regardless of which physical side of the viewport that is for
     * forward vs. backward.
     */
    @Test
    fun spreadSwipeSpansTheWholeViewportInBothDirections() {
        val forward = FoundationReferenceCurlDirection.Forward
        val backward = FoundationReferenceCurlDirection.Backward

        assertEquals(Offset(SpreadLeafWidth, 80f), spreadLeafOffset(SpreadViewportWidth, forward))
        assertEquals(Offset(0f, 80f), spreadLeafOffset(0f, forward))
        assertEquals(Offset(SpreadLeafWidth, 80f), spreadLeafOffset(0f, backward))
        assertEquals(Offset(0f, 80f), spreadLeafOffset(SpreadViewportWidth, backward))
    }

    /**
     * Verifies the spread-mode leaf offset's fold progress (as a fraction of leaf width) matches
     * exactly what a single-pane curl would report as a fraction of viewport width, at every
     * pointer position tested — the scaling in [foundationReferenceCurlLeafOffset] must not
     * change how much the page has folded for a given amount of pointer travel, only where that
     * travel is measured. A backward swipe's progress is the forward swipe's complement, since
     * the spread reuses forward curl geometry for a backward turn (see
     * [foundationReferenceCurlGeometryDirection]).
     */
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

    /**
     * Verifies [foundationReferenceCurlDragSucceeds] requires the same 20%-of-viewport
     * directional travel to complete a spread swipe as it does a single-pane one, even though the
     * spread's leaf itself is narrower — completion is measured against the full viewport the
     * pointer actually travelled across, not the leaf's own width.
     */
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

    /**
     * Verifies [foundationReferenceCurlGeometryDirection] reuses forward curl geometry for a
     * spread's backward (previous-page) swipe, since the spread's only folding leaf is the one on
     * the right, while a single-pane backward swipe keeps its own backward geometry.
     */
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

    /**
     * Verifies [foundationReferenceVisibleCurlEdge] shows the resting edge, not the animated
     * one, once [pageKey] has moved on but [renderedPageKey] has not yet caught up — the window
     * between a turn completing and the reset effect running for the new page.
     */
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

    /**
     * Verifies [foundationReferenceCurlDragSucceeds] on the horizontal axis requires at least 20%
     * of the viewport width of directional travel, in either direction, just under that threshold
     * failing and just at or over it succeeding.
     */
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

    /**
     * Verifies [foundationReferenceCurlDragSucceeds] on the vertical axis measures its 20%
     * threshold against the shorter of the viewport's two sides, in both portrait and landscape
     * orientations, rather than always against one fixed dimension.
     */
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

    /**
     * Verifies [foundationReferenceCurlZIndex] ranks the previous page above the current page,
     * and the current page above the next page, so the folding leaf's stacking order matches a
     * real book's page order.
     */
    @Test
    fun previousCurlPageIsLayeredAboveCurrentPage() {
        assertTrue(foundationReferenceCurlZIndex(-1) > foundationReferenceCurlZIndex(0))
        assertTrue(foundationReferenceCurlZIndex(0) > foundationReferenceCurlZIndex(1))
    }

    /**
     * Verifies [foundationReferenceCurlTapAction] always resolves to toggling the controls while
     * auto-scroll is enabled, regardless of which zone was tapped or which pages are available —
     * a manual tap must not compete with the automatic turn.
     */
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

    /**
     * Verifies [foundationReferenceCurlTapAction]'s quarter-zone resolution along the primary
     * axis for both orientations, including the F16 fix: a tap in a zone with no page to turn to
     * (start or end of the book) falls through to toggling the controls instead of doing nothing
     * at all, the same as the middle zone.
     */
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
        assertEquals(
            FoundationReferenceCurlTapAction.ToggleControls,
            foundationReferenceCurlTapAction(Offset(10f, 100f), size, FoundationReferenceCurlAxis.Horizontal, false, true),
        )
        assertEquals(
            FoundationReferenceCurlTapAction.ToggleControls,
            foundationReferenceCurlTapAction(Offset(90f, 100f), size, FoundationReferenceCurlAxis.Horizontal, true, false),
        )
    }

    /**
     * Verifies the Play Books-style 3D curl always resolves to the horizontal axis, while the
     * standard curl continues to honor the reader's configured vertical direction.
     */
    @Test
    fun threeDimensionalCurlAlwaysUsesHorizontalSwipeAxis() {
        assertEquals(
            FoundationReferenceCurlAxis.Horizontal,
            foundationReferenceCurlAxis(
                pageTurnMode = PageTurnMode.VERTICAL,
                style = FoundationReferenceCurlStyle.ThreeDimensional,
            ),
        )
        assertEquals(
            FoundationReferenceCurlAxis.Vertical,
            foundationReferenceCurlAxis(
                pageTurnMode = PageTurnMode.VERTICAL,
                style = FoundationReferenceCurlStyle.Standard,
            ),
        )
    }

    /** Drag tracking is direct state, not a spring animation queued behind pointer events. */
    @Test
    fun dragUpdateUsesTheLatestPointerEdgeImmediately() {
        val size = IntSize(100, 200)
        val target = foundationReferenceThreeDCurlEdge(size, Offset(43f, 120f))

        val activeDrag = foundationReferenceUpdateDragEdge(
            direction = FoundationReferenceCurlDirection.Forward,
            target = target,
        )

        assertEquals(FoundationReferenceCurlDirection.Forward, activeDrag.direction)
        assertEquals(target, activeDrag.edge)
    }

    /**
     * Verifies the 3D curl accepts only horizontal-dominant movement and fixes forward/backward
     * from the X direction, so a mostly vertical drag cannot start a page turn.
     */
    @Test
    fun threeDimensionalCurlRejectsVerticalDominantDrag() {
        assertEquals(
            FoundationReferenceCurlDirection.Forward,
            foundationReferenceThreeDCurlDirection(
                start = Offset(80f, 50f),
                current = Offset(60f, 55f),
                canGoBackward = true,
                canGoForward = true,
            ),
        )
        assertEquals(
            FoundationReferenceCurlDirection.Backward,
            foundationReferenceThreeDCurlDirection(
                start = Offset(20f, 50f),
                current = Offset(40f, 45f),
                canGoBackward = true,
                canGoForward = true,
            ),
        )
        assertNull(
            foundationReferenceThreeDCurlDirection(
                start = Offset(50f, 20f),
                current = Offset(45f, 80f),
                canGoBackward = true,
                canGoForward = true,
            ),
        )
    }

    /**
     * Verifies the 3D curl derives its rolling crease from X alone: changing pointer Y leaves the
     * edge unchanged, its interior crease remains non-degenerate, and both endpoints exactly match
     * the existing flat rest edges used by the renderer's early returns.
     */
    @Test
    fun threeDimensionalCurlRollingEdgeIgnoresPointerY() {
        val size = IntSize(100, 200)
        val highPointer = foundationReferenceThreeDCurlEdge(size, Offset(50f, 10f))
        val lowPointer = foundationReferenceThreeDCurlEdge(size, Offset(50f, 190f))

        assertEquals(highPointer, lowPointer)
        assertTrue(highPointer.top.x != highPointer.bottom.x)
        assertEquals(FoundationReferenceCurlEdge.left(size), foundationReferenceThreeDCurlEdge(size, Offset(0f, 10f)))
        assertEquals(FoundationReferenceCurlEdge.right(size), foundationReferenceThreeDCurlEdge(size, Offset(100f, 190f)))
    }

    /**
     * Verifies an accepted 3D drag begins at its direction's flat rest edge regardless of where the
     * finger touched: a small rightward backward drag reveals only that small displacement instead
     * of jumping to the pointer's absolute X position, and forward mirrors the same rule.
     */
    @Test
    fun threeDimensionalCurlDragStartsFromRestByDisplacement() {
        val size = IntSize(100, 200)
        val backward = foundationReferenceThreeDCurlDragEdge(
            size = size,
            start = Offset(70f, 20f),
            current = Offset(72f, 180f),
            direction = FoundationReferenceCurlDirection.Backward,
        )
        val forward = foundationReferenceThreeDCurlDragEdge(
            size = size,
            start = Offset(70f, 20f),
            current = Offset(68f, 180f),
            direction = FoundationReferenceCurlDirection.Forward,
        )

        assertEquals(2f, (backward.top.x + backward.bottom.x) / 2f, 0.0001f)
        assertEquals(98f, (forward.top.x + forward.bottom.x) / 2f, 0.0001f)
    }

    /**
     * Verifies the PlayLikeCurl profile uses its reference 25-column sinusoidal texture mesh, leaves every
     * interval unwarped at rest, keeps projected boundaries monotonic and contiguous while bent, and moves
     * the completed page fully beyond the start edge without reversed strips that can tear text.
     */
    @Test
    fun threeDimensionalCurlUsesSinusoidalTextureMesh() {
        val rest = foundationReferenceThreeDCurlStripSpecs(0f)
        val bent = foundationReferenceThreeDCurlStripSpecs(0.5f)
        val complete = foundationReferenceThreeDCurlStripSpecs(1f)

        assertEquals(foundationPagerRenderProfile.threeDCurlGrid, rest.size)
        rest.forEach { strip ->
            assertEquals(strip.sourceStartFraction, strip.destinationStartFraction, 0.0001f)
            assertEquals(strip.sourceEndFraction, strip.destinationEndFraction, 0.0001f)
            assertEquals(1f, strip.verticalScale, 0.0001f)
        }
        bent.zipWithNext().forEach { (left, right) ->
            assertEquals(left.destinationEndFraction, right.destinationStartFraction, 0.0001f)
        }
        assertTrue(bent.all { it.destinationEndFraction > it.destinationStartFraction })
        assertTrue(bent.all { it.depthFraction > 0f })
        assertTrue(bent.all { it.verticalScale > 1f })
        assertTrue(bent.last().destinationEndFraction < 1f)
        assertTrue(complete.maxOf { it.destinationEndFraction } < 0f)
    }

    /**
     * Verifies the 3D curl's front/back/rim/cast lighting exists only while the leaf is bent,
     * peaks at a quarter turn, and vanishes again at both flat orientations.
     */
    @Test
    fun threeDimensionalCurlLightingPeaksWhileLeafIsBent() {
        val open = foundationReferenceThreeDCurlLightingSpec(0f)
        val bent = foundationReferenceThreeDCurlLightingSpec((PI / 2.0).toFloat())
        val closed = foundationReferenceThreeDCurlLightingSpec(PI.toFloat())

        assertEquals(0f, open.frontShadeAlpha)
        assertEquals(0f, open.backShadeAlpha)
        assertEquals(0f, open.rimAlpha)
        assertEquals(0f, open.shadowAlpha)
        assertTrue(bent.frontShadeAlpha > 0f)
        assertTrue(bent.backShadeAlpha > 0f)
        assertTrue(bent.backLightAlpha > 0f)
        assertTrue(bent.rimAlpha > 0f)
        assertTrue(bent.shadowAlpha > 0f)
        assertEquals(0f, closed.frontShadeAlpha, 0.0001f)
        assertEquals(0f, closed.backShadeAlpha, 0.0001f)
        assertEquals(0f, closed.rimAlpha, 0.0001f)
        assertEquals(0f, closed.shadowAlpha, 0.0001f)
    }

    /**
     * Builds the spread-mode leaf offset a pointer at viewport x-coordinate [x] maps to, for
     * [direction], using this test file's fixed [SpreadViewportWidth]/[SpreadLeafWidth] — the
     * shared setup behind the spread-scaling assertions above.
     *
     * @param x The pointer's x position within the full viewport.
     * @param direction Which fold direction the offset is being computed for.
     * @return The corresponding offset in the folding leaf's own coordinate space.
     */
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
        /** The fixed full-viewport width used by every spread-mode assertion in this class. */
        const val SpreadViewportWidth = 1000f

        /** The fixed folding-leaf width (half the viewport) used by every spread-mode assertion. */
        const val SpreadLeafWidth = 500f
    }
}

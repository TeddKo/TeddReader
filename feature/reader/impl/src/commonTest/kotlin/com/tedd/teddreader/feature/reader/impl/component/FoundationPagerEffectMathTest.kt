package com.tedd.teddreader.feature.reader.impl.component

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the pure geometry/animation math behind [FoundationEffectPager]'s page-turn
 * styles (fluid pager, circle reveal, movie carousel, page flip): axis conversion, the fluid
 * edge's point-spring physics, shadow shapes, and z-index/rotation ordering. All of it is plain
 * math with no Compose dependency, so it is tested directly rather than through a composable
 * harness.
 */
class FoundationPagerEffectMathTest {
    /** Verifies [FoundationPagerAxis] only swaps x/y when the axis is vertical. */
    @Test
    fun `axis swaps coordinates only in vertical mode`() {
        assertEquals(FoundationPagerPoint(3f, 7f), FoundationPagerAxis.Horizontal.toCanonical(FoundationPagerPoint(3f, 7f)))
        assertEquals(FoundationPagerPoint(7f, 3f), FoundationPagerAxis.Vertical.toCanonical(FoundationPagerPoint(3f, 7f)))
        assertEquals(FoundationPagerPoint(3f, 7f), FoundationPagerAxis.Vertical.fromCanonical(FoundationPagerPoint(7f, 3f)))
    }

    /** Verifies [foundationPagerLerp] clamps its progress argument to `[0, 1]` before interpolating. */
    @Test
    fun `linear interpolation clamps progress`() {
        assertEquals(10f, foundationPagerLerp(10f, 20f, -1f))
        assertEquals(15f, foundationPagerLerp(10f, 20f, 0.5f))
        assertEquals(20f, foundationPagerLerp(10f, 20f, 2f))
    }

    /**
     * Verifies [foundationTouchCrossAxis] reads the touch's y coordinate for a horizontal pager
     * and its x coordinate for a vertical one, since the cross axis is whichever axis is not the
     * turn direction.
     */
    @Test
    fun `touch cross axis uses y for horizontal and x for vertical`() {
        val size = FoundationPagerSize(width = 100f, height = 200f)

        assertEquals(
            0.25f,
            foundationTouchCrossAxis(FoundationPagerAxis.Horizontal, size, FoundationPagerPoint(90f, 50f)),
        )
        assertEquals(
            0.25f,
            foundationTouchCrossAxis(FoundationPagerAxis.Vertical, size, FoundationPagerPoint(25f, 180f)),
        )
    }

    /** Verifies [foundationTouchCrossAxis] returns the midpoint when there is no recorded touch. */
    @Test
    fun `touch cross axis falls back to center without touch`() {
        assertEquals(
            0.5f,
            foundationTouchCrossAxis(
                FoundationPagerAxis.Horizontal,
                FoundationPagerSize(width = 100f, height = 200f),
                null,
            ),
        )
    }

    /**
     * Verifies [buildFoundationFluidPolygon] anchors the fluid edge's straight side at x=0 for
     * [FoundationFluidSide.Start] and at the far edge for [FoundationFluidSide.End].
     */
    @Test
    fun `fluid edge grows from requested side`() {
        val left = buildFoundationFluidPolygon(
            size = FoundationPagerSize(width = 100f, height = 200f),
            axis = FoundationPagerAxis.Horizontal,
            side = FoundationFluidSide.Start,
            progress = 0.25f,
            touchCrossAxis = 0.5f,
        )
        val right = buildFoundationFluidPolygon(
            size = FoundationPagerSize(width = 100f, height = 200f),
            axis = FoundationPagerAxis.Horizontal,
            side = FoundationFluidSide.End,
            progress = 0.25f,
            touchCrossAxis = 0.5f,
        )

        assertEquals(0f, left.first().x)
        assertEquals(100f, right.first().x)
    }

    /**
     * Verifies the fluid edge deforms furthest at the touch point's cross-axis position: a touch
     * nearer the bottom pushes the point of maximum deformation lower than a touch nearer the top.
     */
    @Test
    fun `fluid touch point moves maximum deformation`() {
        val topTouch = buildFoundationFluidPolygon(
            size = FoundationPagerSize(width = 100f, height = 200f),
            axis = FoundationPagerAxis.Horizontal,
            side = FoundationFluidSide.Start,
            progress = 0.5f,
            touchCrossAxis = 0.2f,
        )
        val bottomTouch = buildFoundationFluidPolygon(
            size = FoundationPagerSize(width = 100f, height = 200f),
            axis = FoundationPagerAxis.Horizontal,
            side = FoundationFluidSide.Start,
            progress = 0.5f,
            touchCrossAxis = 0.8f,
        )

        assertTrue(maxXPoint(topTouch).y < maxXPoint(bottomTouch).y)
    }

    /**
     * Verifies [FoundationFluidEdge]'s spring physics: after ticking toward a touch-centred
     * target, the point nearest the touch position leads both of its neighbours rather than the
     * whole edge moving as a rigid line.
     */
    @Test
    fun `fluid edge physics pulls touch-near point ahead of far point`() {
        val edge = FoundationFluidEdge(pointCount = 5)

        edge.applyTarget(
            side = FoundationFluidSide.Start,
            progress = 0.5f,
            touchCrossAxis = 0.5f,
        )
        repeat(8) { edge.tick(1f) }

        assertTrue(edge.points[2].x > edge.points[0].x)
        assertTrue(edge.points[2].x > edge.points[4].x)
    }

    /** Verifies [FoundationFluidEdge.reset] zeroes every point's position and velocity. */
    @Test
    fun `fluid edge reset clears stale swipe state`() {
        val edge = FoundationFluidEdge(pointCount = 5)

        edge.applyTarget(
            side = FoundationFluidSide.Start,
            progress = 0.5f,
            touchCrossAxis = 0.5f,
        )
        repeat(8) { edge.tick(1f) }
        edge.reset()

        assertTrue(edge.points.all { it.x == 0f })
        assertTrue(edge.points.all { it.velocityX == 0f })
    }

    /**
     * Verifies [foundationActivePageTurn] reports zero progress with no committed side while a
     * gesture is active but has not yet moved enough to reveal its direction, rather than
     * guessing from whichever neighbour's progress happens to be larger.
     */
    @Test
    fun `active turn hides stale progress until drag direction is known`() {
        val turn = foundationActivePageTurn(
            gestureActive = true,
            gestureSide = null,
            previousProgress = 0.6f,
            nextProgress = 0f,
        )

        assertEquals(FoundationFluidSide.Start, turn.side)
        assertEquals(0f, turn.progress)
    }

    /**
     * Verifies [foundationActivePageTurn] reports the gesture's own committed side and that
     * side's progress once a drag direction is known.
     */
    @Test
    fun `active turn uses gesture side once drag direction is known`() {
        val turn = foundationActivePageTurn(
            gestureActive = true,
            gestureSide = FoundationFluidSide.End,
            previousProgress = 0.6f,
            nextProgress = 0.2f,
        )

        assertEquals(FoundationFluidSide.End, turn.side)
        assertEquals(0.2f, turn.progress)
    }

    /**
     * Verifies that once a touch releases, [FoundationFluidEdge]'s point closest to where the
     * touch pulled ahead settles back toward the rest of the edge rather than staying spiked —
     * ticking with `touchActive = false` should collapse most of the lead the active touch built
     * up.
     */
    @Test
    fun `fluid edge release settles touch spike into the whole edge`() {
        val edge = FoundationFluidEdge(pointCount = 5)

        edge.applyTarget(
            side = FoundationFluidSide.Start,
            progress = 0.6f,
            touchCrossAxis = 0.5f,
        )
        repeat(8) { edge.tick(1f) }
        val activeCenterLead = edge.points[2].x - edge.points[0].x

        edge.applyTarget(
            side = FoundationFluidSide.Start,
            progress = 0.6f,
            touchCrossAxis = 0.5f,
            touchActive = false,
        )
        repeat(24) { edge.tick(1f) }
        val releasedCenterLead = edge.points[2].x - edge.points[0].x

        assertTrue(abs(releasedCenterLead) < activeCenterLead / 2f)
    }

    /**
     * Verifies that releasing the touch never lets the touch-near point's lead over its
     * neighbours grow beyond what it already was at release — a spring settling back should
     * not overshoot into a stronger rebound than the touch itself produced.
     */
    @Test
    fun `fluid edge release avoids strong rebound`() {
        val edge = FoundationFluidEdge(pointCount = 5)

        edge.applyTarget(
            side = FoundationFluidSide.Start,
            progress = 0.6f,
            touchCrossAxis = 0.5f,
        )
        repeat(8) { edge.tick(1f) }
        val activeCenterLead = edge.points[2].x - edge.points[0].x

        edge.applyTarget(
            side = FoundationFluidSide.Start,
            progress = 0.6f,
            touchCrossAxis = 0.5f,
            touchActive = false,
        )
        var maxRebound = 0f
        repeat(12) {
            edge.tick(1f)
            maxRebound = max(maxRebound, abs(edge.points[2].x - edge.points[0].x))
        }

        assertTrue(maxRebound < activeCenterLead)
    }

    /**
     * Verifies that once released, every point on [FoundationFluidEdge] moves monotonically
     * toward the resting [progress] target — its distance to the target only shrinks (within
     * [tolerance]) tick over tick and never changes sign, i.e. it settles without oscillating
     * past the target and back.
     */
    @Test
    fun `fluid edge release moves monotonically toward target without crossing`() {
        val edge = FoundationFluidEdge(pointCount = 5)
        val target = 0.6f

        edge.applyTarget(
            side = FoundationFluidSide.Start,
            progress = target,
            touchCrossAxis = 0.5f,
        )
        repeat(8) { edge.tick(1f) }
        edge.applyTarget(
            side = FoundationFluidSide.Start,
            progress = target,
            touchCrossAxis = 0.5f,
            touchActive = false,
        )

        var previousDistances = edge.points.map { target - it.x }
        repeat(24) {
            edge.tick(1f)
            edge.points.forEachIndexed { index, point ->
                val distance = target - point.x
                assertTrue(abs(distance) <= abs(previousDistances[index]) + tolerance)
                assertTrue(distance == 0f || previousDistances[index] == 0f || distance * previousDistances[index] >= -tolerance)
            }
            previousDistances = edge.points.map { target - it.x }
        }
    }

    /**
     * Verifies [buildFoundationFluidShadowPolygon] traces the same curved shape the fluid edge
     * itself settled into, rather than a straight offset line.
     */
    @Test
    fun `fluid shadow follows curved fluid edge`() {
        val edge = FoundationFluidEdge(pointCount = 5)
        edge.applyTarget(
            side = FoundationFluidSide.Start,
            progress = 0.5f,
            touchCrossAxis = 0.5f,
        )
        repeat(8) { edge.tick(1f) }

        val shadow = buildFoundationFluidShadowPolygon(
            size = FoundationPagerSize(width = 100f, height = 200f),
            axis = FoundationPagerAxis.Horizontal,
            side = FoundationFluidSide.Start,
            edge = edge,
            width = 12f,
        )

        assertTrue(shadow[2].x > shadow[0].x)
        assertTrue(shadow[2].x > shadow[4].x)
    }

    /**
     * Verifies [buildFoundationFluidPolygon] swaps which edge (y=0 vs. the far edge) is anchored
     * when the axis is vertical, mirroring the horizontal-axis start/end swap onto the other axis.
     */
    @Test
    fun `vertical fluid edge swaps side axis`() {
        val end = buildFoundationFluidPolygon(
            size = FoundationPagerSize(width = 100f, height = 200f),
            axis = FoundationPagerAxis.Vertical,
            side = FoundationFluidSide.End,
            progress = 0.25f,
            touchCrossAxis = 0.5f,
        )
        val start = buildFoundationFluidPolygon(
            size = FoundationPagerSize(width = 100f, height = 200f),
            axis = FoundationPagerAxis.Vertical,
            side = FoundationFluidSide.Start,
            progress = 0.25f,
            touchCrossAxis = 0.5f,
        )

        assertEquals(200f, end.first().y)
        assertEquals(0f, start.first().y)
    }

    /**
     * Verifies [foundationCircleRevealSpec] anchors the reveal circle's origin at the touch's
     * cross-axis position on whichever edge matches [FoundationFluidSide], for both axes.
     */
    @Test
    fun `circle reveal origin keeps touch cross axis`() {
        val horizontal = foundationCircleRevealSpec(
            size = FoundationPagerSize(width = 100f, height = 200f),
            axis = FoundationPagerAxis.Horizontal,
            side = FoundationFluidSide.End,
            progress = 0.25f,
            touchCrossAxis = 0.3f,
        )
        val vertical = foundationCircleRevealSpec(
            size = FoundationPagerSize(width = 100f, height = 200f),
            axis = FoundationPagerAxis.Vertical,
            side = FoundationFluidSide.Start,
            progress = 0.25f,
            touchCrossAxis = 0.7f,
        )

        assertEquals(100f, horizontal.origin.x, tolerance)
        assertEquals(60f, horizontal.origin.y, tolerance)
        assertEquals(70f, vertical.origin.x, tolerance)
        assertEquals(0f, vertical.origin.y, tolerance)
    }

    /**
     * Verifies [foundationCircleRevealShadowSpec] shares its reveal circle's center and radius
     * with [foundationCircleRevealSpec], and produces an inner radius strictly smaller than the
     * outer one with a positive alpha, so the shadow reads as a ring rather than a filled disc.
     */
    @Test
    fun `circle reveal shadow uses circle boundary`() {
        val shadow = foundationCircleRevealShadowSpec(
            size = FoundationPagerSize(width = 100f, height = 200f),
            axis = FoundationPagerAxis.Horizontal,
            side = FoundationFluidSide.End,
            progress = 0.5f,
            touchCrossAxis = 0.25f,
        )
        val reveal = foundationCircleRevealSpec(
            size = FoundationPagerSize(width = 100f, height = 200f),
            axis = FoundationPagerAxis.Horizontal,
            side = FoundationFluidSide.End,
            progress = 0.5f,
            touchCrossAxis = 0.25f,
        )

        assertEquals(reveal.center, shadow.center)
        assertEquals(reveal.radius, shadow.radius, tolerance)
        assertTrue(shadow.innerRadius < shadow.radius)
        assertTrue(shadow.alpha > 0f)
    }

    /**
     * Verifies [foundationMovieCarouselSpec] only ever offsets the current page when it is the
     * one leaving (via its own [pageOffset]), never the neighbour arriving, and that the arriving
     * neighbour is drawn larger and more opaque than the departing current page mid-transition.
     */
    @Test
    fun `movie carousel only offsets outgoing current page`() {
        val current = foundationMovieCarouselSpec(FoundationPagerPage.Current, 0.8f)
        val next = foundationMovieCarouselSpec(FoundationPagerPage.Next, -0.8f)

        assertEquals(0f, current.translationFraction, tolerance)
        assertEquals(0f, next.translationFraction)
        assertTrue(current.scale < next.scale)
        assertTrue(current.alpha < next.alpha)
    }

    /**
     * Verifies [foundationMovieCarouselShadowSide] casts the incoming shadow from whichever edge
     * the arriving neighbour leads with — the previous page arrives from the end, the next page
     * from the start — and casts none for the current page, which has no leading edge of its own.
     */
    @Test
    fun `movie carousel shadow follows incoming page leading edge`() {
        assertEquals(FoundationFluidSide.End, foundationMovieCarouselShadowSide(FoundationPagerPage.Previous))
        assertEquals(FoundationFluidSide.Start, foundationMovieCarouselShadowSide(FoundationPagerPage.Next))
        assertEquals(null, foundationMovieCarouselShadowSide(FoundationPagerPage.Current))
    }

    /**
     * Verifies [foundationMovieCarouselDimAlpha] is zero at both transition extremes (0 and 1,
     * plus out-of-range inputs clamped to them) and positive partway through, so the dimming
     * overlay peaks mid-transition rather than at rest.
     */
    @Test
    fun `movie carousel dim alpha is clamped and peaks mid transition`() {
        assertEquals(0f, foundationMovieCarouselDimAlpha(0f), tolerance)
        assertEquals(0f, foundationMovieCarouselDimAlpha(1f), tolerance)
        assertEquals(0f, foundationMovieCarouselDimAlpha(-1f), tolerance)
        assertEquals(0f, foundationMovieCarouselDimAlpha(2f), tolerance)
        assertTrue(foundationMovieCarouselDimAlpha(0.5f) > 0f)
    }

    /**
     * Verifies [foundationPageFlipLightingSpec] mirrors only the physical shadow side between
     * forward and backward turns while keeping both directions equally lit.
     */
    @Test
    fun `page flip lighting is symmetric across turn directions`() {
        val next = foundationPageFlipLightingSpec(0.5f)
        val previous = foundationPageFlipLightingSpec(-0.5f)

        assertEquals(FoundationFluidSide.Start, next.side)
        assertEquals(FoundationFluidSide.End, previous.side)
        assertEquals(next.frontShadeAlpha, previous.frontShadeAlpha, tolerance)
        assertEquals(next.backShadeAlpha, previous.backShadeAlpha, tolerance)
        assertEquals(next.castAlpha, previous.castAlpha, tolerance)
        assertEquals(next.contactAlpha, previous.contactAlpha, tolerance)
    }

    /**
     * Verifies a whole-page flip keeps its cast/contact shadow on the physical hinge while the free
     * edge moves across the page. A previous-page turn is a left-to-right swipe hinged at the right
     * edge, so its shadow must originate on the right and cast inward; the forward turn mirrors it.
     */
    @Test
    fun `whole page flip shadow stays on directional hinge`() {
        val next = requireNotNull(
            foundationPageFlipProjectionSpec(
                pageOffset = 0.5f,
                layout = FoundationPageFlipLayout.WholePage,
            ),
        )
        val previous = requireNotNull(
            foundationPageFlipProjectionSpec(
                pageOffset = -0.5f,
                layout = FoundationPageFlipLayout.WholePage,
            ),
        )

        assertEquals(0f, next.shadowEdgeFraction, tolerance)
        assertEquals(FoundationFluidSide.End, next.castDirection)
        assertEquals(1f, previous.shadowEdgeFraction, tolerance)
        assertEquals(FoundationFluidSide.Start, previous.castDirection)
    }

    /**
     * Verifies a split-half leaf moves through the spine onto the opposite receiver half, switching
     * the cast direction after edge-on instead of keeping a sign-derived shadow on the wrong half.
     */
    @Test
    fun `spread page flip shadow crosses the spine with the leaf`() {
        val beforeSpine = requireNotNull(
            foundationPageFlipProjectionSpec(
                pageOffset = 0.25f,
                layout = FoundationPageFlipLayout.SplitHalfFold,
            ),
        )
        val atSpine = requireNotNull(
            foundationPageFlipProjectionSpec(
                pageOffset = 0.5f,
                layout = FoundationPageFlipLayout.SplitHalfFold,
            ),
        )
        val afterSpine = requireNotNull(
            foundationPageFlipProjectionSpec(
                pageOffset = 0.75f,
                layout = FoundationPageFlipLayout.SplitHalfFold,
            ),
        )

        assertTrue(beforeSpine.shadowEdgeFraction > 0.5f)
        assertEquals(FoundationFluidSide.Start, beforeSpine.castDirection)
        assertEquals(0.5f, atSpine.shadowEdgeFraction, tolerance)
        assertEquals(FoundationFluidSide.End, atSpine.castDirection)
        assertTrue(afterSpine.shadowEdgeFraction < 0.5f)
        assertEquals(FoundationFluidSide.End, afterSpine.castDirection)
    }

    /**
     * Verifies projected PAGE_FLIP shadows are absent at every settled endpoint, matching the
     * existing lighting contract and preventing a stale contact line after cancel or completion.
     */
    @Test
    fun `page flip projection vanishes at settled endpoints`() {
        FoundationPageFlipLayout.entries.forEach { layout ->
            assertEquals(null, foundationPageFlipProjectionSpec(0f, layout))
            assertEquals(null, foundationPageFlipProjectionSpec(1f, layout))
            assertEquals(null, foundationPageFlipProjectionSpec(-1f, layout))
        }
    }

    /**
     * Verifies [foundationPageFlipZIndex]'s single-pane stacking order for the whole drag: the
     * current page always ranks above both neighbours; turning back, the arriving previous page
     * must outrank the next page (or it would show through the half the folding page leaves
     * transparent); turning forward the ranking flips the same way; and in neither direction does
     * a neighbour ever reach the current page's own rank.
     */
    @Test
    fun `single pane page flip keeps current sheet above neighbors for whole drag`() {
        assertEquals(3f, foundationPageFlipZIndex(FoundationPagerPage.Current, 0f))
        assertTrue(
            foundationPageFlipZIndex(FoundationPagerPage.Previous, 0.6f) >
                foundationPageFlipZIndex(FoundationPagerPage.Next, -1.4f),
        )
        assertTrue(
            foundationPageFlipZIndex(FoundationPagerPage.Next, -0.6f) >
                foundationPageFlipZIndex(FoundationPagerPage.Previous, 1.4f),
        )
        assertTrue(
            foundationPageFlipZIndex(FoundationPagerPage.Next, -0.6f) <
                foundationPageFlipZIndex(FoundationPagerPage.Current, 0f),
        )
    }

    /**
     * Verifies PAGE_FLIP lighting is absent on both settled pages and peaks while the leaf is
     * edge-on, so no stale dimming remains after either a completed or cancelled turn.
     */
    @Test
    fun `page flip lighting peaks mid turn and vanishes at both ends`() {
        val start = foundationPageFlipLightingSpec(0f)
        val middle = foundationPageFlipLightingSpec(0.5f)
        val end = foundationPageFlipLightingSpec(1f)

        assertEquals(0f, start.castAlpha, tolerance)
        assertEquals(0f, start.contactAlpha, tolerance)
        assertEquals(0f, end.castAlpha, tolerance)
        assertEquals(0f, end.contactAlpha, tolerance)
        assertTrue(middle.castAlpha > 0f)
        assertTrue(middle.contactAlpha > middle.castAlpha)
    }

    /**
     * Verifies the turning leaf's front and back use distinct surface lighting while retaining a
     * visible rim highlight at the fold, rather than applying one flat dim overlay to both faces.
     */
    @Test
    fun `page flip lights front and back surfaces independently`() {
        val lighting = foundationPageFlipLightingSpec(0.5f)

        assertTrue(lighting.frontShadeAlpha > 0f)
        assertTrue(lighting.backShadeAlpha > 0f)
        assertTrue(lighting.frontShadeAlpha != lighting.backShadeAlpha)
        assertTrue(lighting.rimAlpha > 0f)
    }

    /**
     * Verifies [foundationPageFlipHalfSpec] only rotates the half on the side the swipe is
     * folding — a next-turn swipe rotates the left/top half and leaves the right/bottom half flat,
     * and a previous-turn swipe does the opposite.
     */
    @Test
    fun `page flip folds only one half in swipe direction`() {
        val leftNext = foundationPageFlipHalfSpec(FoundationPageFlipHalf.Left, -0.5f)
        val rightNext = foundationPageFlipHalfSpec(FoundationPageFlipHalf.Right, -0.5f)
        val leftPrevious = foundationPageFlipHalfSpec(FoundationPageFlipHalf.Left, 0.5f)
        val rightPrevious = foundationPageFlipHalfSpec(FoundationPageFlipHalf.Right, 0.5f)
        val topNext = foundationPageFlipHalfSpec(FoundationPageFlipHalf.Top, -0.5f)
        val bottomNext = foundationPageFlipHalfSpec(FoundationPageFlipHalf.Bottom, -0.5f)
        val topPrevious = foundationPageFlipHalfSpec(FoundationPageFlipHalf.Top, 0.5f)
        val bottomPrevious = foundationPageFlipHalfSpec(FoundationPageFlipHalf.Bottom, 0.5f)

        assertTrue(leftNext.rotationY > 0f)
        assertEquals(0f, rightNext.rotationY, tolerance)
        assertEquals(0f, leftPrevious.rotationY, tolerance)
        assertTrue(rightPrevious.rotationY < 0f)
        assertTrue(topNext.rotationX < 0f)
        assertEquals(0f, bottomNext.rotationX, tolerance)
        assertEquals(0f, topPrevious.rotationX, tolerance)
        assertTrue(bottomPrevious.rotationX > 0f)
    }

    /**
     * Verifies [foundationSpreadPageFlipSpec] hands off from the outgoing half to the incoming
     * half exactly at the fold's halfway point and continues rotating smoothly through to a full
     * turn, in both the forward and backward directions.
     */
    @Test
    fun `spread page flip continues beyond edge on to a complete turn`() {
        val nextBeforeHinge = foundationSpreadPageFlipSpec(FoundationPagerAxis.Horizontal, 0.25f)
        val nextAfterHinge = foundationSpreadPageFlipSpec(FoundationPagerAxis.Horizontal, 0.75f)
        val nextComplete = foundationSpreadPageFlipSpec(FoundationPagerAxis.Horizontal, 1f)
        val previousAfterHinge = foundationSpreadPageFlipSpec(FoundationPagerAxis.Horizontal, -0.75f)

        assertEquals(FoundationPageFlipHalf.Right, nextBeforeHinge.outgoingHalf)
        assertEquals(-45f, nextBeforeHinge.outgoing.rotationY, tolerance)
        assertTrue(nextBeforeHinge.showOutgoing)
        assertFalse(nextBeforeHinge.showIncoming)

        assertEquals(FoundationPageFlipHalf.Left, nextAfterHinge.incomingHalf)
        assertEquals(45f, nextAfterHinge.incoming.rotationY, tolerance)
        assertFalse(nextAfterHinge.showOutgoing)
        assertTrue(nextAfterHinge.showIncoming)
        assertEquals(0f, nextComplete.incoming.rotationY, tolerance)

        assertEquals(FoundationPageFlipHalf.Right, previousAfterHinge.incomingHalf)
        assertEquals(-45f, previousAfterHinge.incoming.rotationY, tolerance)
    }

    /**
     * Verifies [foundationPageFlipLayout] picks the whole-page fold for a single pane and the
     * split-half fold for a two-page spread.
     */
    @Test
    fun `page flip chooses whole page for single pane and split fold for spreads`() {
        assertEquals(
            FoundationPageFlipLayout.WholePage,
            foundationPageFlipLayout(pageStep = 1, paneCount = 1),
        )
        assertEquals(
            FoundationPageFlipLayout.SplitHalfFold,
            foundationPageFlipLayout(pageStep = 2, paneCount = 2),
        )
    }

    /**
     * Verifies [foundationWholePageFlipSpec] keeps the same rotation sign for a given axis while
     * swapping which corner it pivots around depending on swipe direction, for both the
     * horizontal and vertical axes.
     */
    @Test
    fun `whole page flip keeps rotation sign while swapping pivot by swipe direction`() {
        val horizontalNext = foundationWholePageFlipSpec(
            axis = FoundationPagerAxis.Horizontal,
            pageOffset = 1f,
        )
        val horizontalPrevious = foundationWholePageFlipSpec(
            axis = FoundationPagerAxis.Horizontal,
            pageOffset = -1f,
        )
        val verticalNext = foundationWholePageFlipSpec(
            axis = FoundationPagerAxis.Vertical,
            pageOffset = 1f,
        )
        val verticalPrevious = foundationWholePageFlipSpec(
            axis = FoundationPagerAxis.Vertical,
            pageOffset = -1f,
        )

        assertEquals(0f, horizontalNext.rotationX, tolerance)
        assertEquals(-90f, horizontalNext.rotationY, tolerance)
        assertEquals(0f, horizontalNext.transformOriginX, tolerance)
        assertEquals(0.5f, horizontalNext.transformOriginY, tolerance)

        assertEquals(0f, horizontalPrevious.rotationX, tolerance)
        assertEquals(90f, horizontalPrevious.rotationY, tolerance)
        assertEquals(1f, horizontalPrevious.transformOriginX, tolerance)
        assertEquals(0.5f, horizontalPrevious.transformOriginY, tolerance)

        assertEquals(90f, verticalNext.rotationX, tolerance)
        assertEquals(0f, verticalNext.rotationY, tolerance)
        assertEquals(0.5f, verticalNext.transformOriginX, tolerance)
        assertEquals(0f, verticalNext.transformOriginY, tolerance)

        assertEquals(-90f, verticalPrevious.rotationX, tolerance)
        assertEquals(0f, verticalPrevious.rotationY, tolerance)
        assertEquals(0.5f, verticalPrevious.transformOriginX, tolerance)
        assertEquals(1f, verticalPrevious.transformOriginY, tolerance)
    }

    /**
     * The point with the greatest x among [points] — used to locate a fluid polygon's furthest
     * bulge without assuming which index it lands on.
     *
     * @param points The polygon points to search.
     * @return The point with the maximum x value.
     */
    private fun maxXPoint(points: List<FoundationPagerPoint>): FoundationPagerPoint =
        points.maxBy { it.x }

    /**
     * A float equality assertion with an explicit tolerance, since the spring/trig math under
     * test rarely lands on an exact value.
     *
     * @param expected The expected value.
     * @param actual The value produced by the code under test.
     * @param tolerance The maximum allowed absolute difference between [expected] and [actual].
     */
    private fun assertEquals(expected: Float, actual: Float, tolerance: Float) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected <$expected>, actual <$actual>.")
    }

    private companion object {
        /** The absolute tolerance used by float comparisons throughout this test class. */
        const val tolerance = 0.0001f
    }
}

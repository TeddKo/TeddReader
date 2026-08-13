package com.tedd.teddreader.feature.reader.impl.component

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoundationPagerEffectMathTest {
    @Test
    fun `axis swaps coordinates only in vertical mode`() {
        assertEquals(FoundationPagerPoint(3f, 7f), FoundationPagerAxis.Horizontal.toCanonical(FoundationPagerPoint(3f, 7f)))
        assertEquals(FoundationPagerPoint(7f, 3f), FoundationPagerAxis.Vertical.toCanonical(FoundationPagerPoint(3f, 7f)))
        assertEquals(FoundationPagerPoint(3f, 7f), FoundationPagerAxis.Vertical.fromCanonical(FoundationPagerPoint(7f, 3f)))
    }

    @Test
    fun `linear interpolation clamps progress`() {
        assertEquals(10f, foundationPagerLerp(10f, 20f, -1f))
        assertEquals(15f, foundationPagerLerp(10f, 20f, 0.5f))
        assertEquals(20f, foundationPagerLerp(10f, 20f, 2f))
    }

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

    @Test
    fun `movie carousel only offsets outgoing current page`() {
        val current = foundationMovieCarouselSpec(FoundationPagerPage.Current, 0.8f)
        val next = foundationMovieCarouselSpec(FoundationPagerPage.Next, -0.8f)

        assertEquals(0f, current.translationFraction, tolerance)
        assertEquals(0f, next.translationFraction)
        assertTrue(current.scale < next.scale)
        assertTrue(current.alpha < next.alpha)
    }

    @Test
    fun `movie carousel shadow follows incoming page leading edge`() {
        assertEquals(FoundationFluidSide.End, foundationMovieCarouselShadowSide(FoundationPagerPage.Previous))
        assertEquals(FoundationFluidSide.Start, foundationMovieCarouselShadowSide(FoundationPagerPage.Next))
        assertEquals(null, foundationMovieCarouselShadowSide(FoundationPagerPage.Current))
    }

    @Test
    fun `movie carousel dim alpha is clamped and peaks mid transition`() {
        assertEquals(0f, foundationMovieCarouselDimAlpha(0f), tolerance)
        assertEquals(0f, foundationMovieCarouselDimAlpha(1f), tolerance)
        assertEquals(0f, foundationMovieCarouselDimAlpha(-1f), tolerance)
        assertEquals(0f, foundationMovieCarouselDimAlpha(2f), tolerance)
        assertTrue(foundationMovieCarouselDimAlpha(0.5f) > 0f)
    }

    @Test
    fun `page flip page shadow hinge follows outgoing whole page edge`() {
        val next = foundationWholePageFlipShadowSpec(0.5f)
        val previous = foundationWholePageFlipShadowSpec(-0.5f)

        assertEquals(FoundationFluidSide.Start, next?.side)
        assertEquals(FoundationFluidSide.End, previous?.side)
        assertEquals(null, foundationWholePageFlipShadowSpec(0f))
    }

    @Test
    fun `single pane page flip keeps current sheet above neighbors for whole drag`() {
        assertEquals(3f, foundationPageFlipZIndex(FoundationPagerPage.Current))
        assertEquals(2f, foundationPageFlipZIndex(FoundationPagerPage.Previous))
        assertEquals(2f, foundationPageFlipZIndex(FoundationPagerPage.Next))
    }

    @Test
    fun `whole page shadow contact alpha grows monotonically toward edge on`() {
        val quarter = foundationWholePageFlipShadowSpec(0.25f)
        val half = foundationWholePageFlipShadowSpec(0.5f)
        val full = foundationWholePageFlipShadowSpec(1f)

        assertTrue(quarter != null)
        assertTrue(half != null)
        assertTrue(full != null)
        assertTrue(quarter.contactAlpha > 0f)
        assertTrue(quarter.contactAlpha < half.contactAlpha)
        assertTrue(half.contactAlpha < full.contactAlpha)
    }

    @Test
    fun `whole page shadow keeps ambient shade below hinge contact shade`() {
        val shadow = foundationWholePageFlipShadowSpec(0.5f)

        assertTrue(shadow != null)
        assertTrue(shadow.ambientAlpha > 0f)
        assertTrue(shadow.ambientAlpha < shadow.contactAlpha)
    }

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

    private fun maxXPoint(points: List<FoundationPagerPoint>): FoundationPagerPoint =
        points.maxBy { it.x }

    private fun assertEquals(expected: Float, actual: Float, tolerance: Float) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected <$expected>, actual <$actual>.")
    }

    private companion object {
        const val tolerance = 0.0001f
    }
}

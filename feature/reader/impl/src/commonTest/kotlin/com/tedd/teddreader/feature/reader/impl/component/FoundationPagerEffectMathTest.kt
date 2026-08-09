package com.tedd.teddreader.feature.reader.impl.component

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `page flip page shadow stays on folded half`() {
        assertEquals(FoundationFluidSide.Start, foundationPageFlipShadowSide(-0.5f))
        assertEquals(FoundationFluidSide.End, foundationPageFlipShadowSide(0.5f))
        assertEquals(null, foundationPageFlipShadowSide(0f))
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
    fun `curl drag progress follows primary axis only`() {
        val size = FoundationPagerSize(width = 100f, height = 200f)

        val horizontal = foundationCurlDragProgress(
            axis = FoundationPagerAxis.Horizontal,
            size = size,
            start = FoundationPagerPoint(80f, 20f),
            current = FoundationPagerPoint(50f, 180f),
        )
        val vertical = foundationCurlDragProgress(
            axis = FoundationPagerAxis.Vertical,
            size = size,
            start = FoundationPagerPoint(20f, 160f),
            current = FoundationPagerPoint(90f, 100f),
        )

        assertEquals(0.3f, horizontal, tolerance)
        assertEquals(0.3f, vertical, tolerance)
    }

    @Test
    fun `curl commit threshold is progress based`() {
        assertTrue(!foundationCurlShouldCommit(FoundationCurlCommitThreshold - 0.01f))
        assertTrue(foundationCurlShouldCommit(FoundationCurlCommitThreshold))
    }

    @Test
    fun `curl consumes pointer only after drag starts`() {
        assertTrue(!foundationCurlShouldConsumePointer(0f))
        assertTrue(foundationCurlShouldConsumePointer(0.01f))
    }

    @Test
    fun `curl committed settle resets after page callback`() {
        assertTrue(!foundationCurlShouldResetBeforePageCallback(commit = true))
        assertTrue(foundationCurlShouldResetBeforePageCallback(commit = false))
    }

    @Test
    fun `curl edge uses touch cross axis`() {
        val size = FoundationPagerSize(width = 100f, height = 200f)
        val upper = foundationCurlEdge(
            size = size,
            side = FoundationFluidSide.End,
            progress = 0.5f,
            startCrossAxis = 0.2f,
            currentCrossAxis = 0.2f,
            currentPrimary = 0.5f,
        )
        val lower = foundationCurlEdge(
            size = size,
            side = FoundationFluidSide.End,
            progress = 0.5f,
            startCrossAxis = 0.8f,
            currentCrossAxis = 0.8f,
            currentPrimary = 0.5f,
        )

        assertTrue(upper.top.y < lower.top.y)
    }

    @Test
    fun `curl edge follows active touch primary like reference`() {
        val size = FoundationPagerSize(width = 100f, height = 200f)

        val end = foundationCurlEdge(
            size = size,
            side = FoundationFluidSide.End,
            progress = 0.1f,
            startCrossAxis = 0.5f,
            currentCrossAxis = 0.5f,
            currentPrimary = 0.4f,
        )
        val start = foundationCurlEdge(
            size = size,
            side = FoundationFluidSide.Start,
            progress = 0.1f,
            startCrossAxis = 0.5f,
            currentCrossAxis = 0.5f,
            currentPrimary = 0.4f,
        )

        assertEquals(40f, end.top.x, tolerance)
        assertEquals(40f, end.bottom.x, tolerance)
        assertEquals(40f, start.top.x, tolerance)
        assertEquals(40f, start.bottom.x, tolerance)
    }

    @Test
    fun `curl inactive fallback straightens stale diagonal touch`() {
        val size = FoundationPagerSize(width = 100f, height = 200f)

        val end = foundationCurlEdge(
            size = size,
            side = FoundationFluidSide.End,
            progress = 0.5f,
            startCrossAxis = 0.8f,
            currentCrossAxis = 0.2f,
        )
        val start = foundationCurlEdge(
            size = size,
            side = FoundationFluidSide.Start,
            progress = 0.5f,
            startCrossAxis = 0.8f,
            currentCrossAxis = 0.2f,
        )

        listOf(end, start).forEach { edge ->
            assertEquals(50f, edge.top.x, tolerance)
            assertEquals(0f, edge.top.y, tolerance)
            assertEquals(50f, edge.bottom.x, tolerance)
            assertEquals(size.height, edge.bottom.y, tolerance)
        }
    }

    @Test
    fun `curl edge reaches page edge at completed progress`() {
        val size = FoundationPagerSize(width = 100f, height = 200f)

        val end = foundationCurlEdge(
            size = size,
            side = FoundationFluidSide.End,
            progress = 1f,
            startCrossAxis = 0.5f,
            currentCrossAxis = 0.5f,
        )
        val start = foundationCurlEdge(
            size = size,
            side = FoundationFluidSide.Start,
            progress = 1f,
            startCrossAxis = 0.5f,
            currentCrossAxis = 0.5f,
        )

        assertEquals(0f, end.top.x, tolerance)
        assertEquals(0f, end.bottom.x, tolerance)
        assertEquals(size.width, start.top.x, tolerance)
        assertEquals(size.width, start.bottom.x, tolerance)
    }

    @Test
    fun `curl folded path uses page remainder for start side`() {
        val size = FoundationPagerSize(width = 100f, height = 200f)
        val folded = foundationCurlFoldedPath(
            size = size,
            topCurl = FoundationPagerPoint(30f, 0f),
            bottomCurl = FoundationPagerPoint(30f, 200f),
        )

        assertTrue(folded.any { it.x == size.width })
        assertTrue(folded.none { it.x == 0f })
    }

    @Test
    fun `curl folded path clips over page corner through right edge`() {
        val size = FoundationPagerSize(width = 100f, height = 200f)
        val folded = foundationCurlFoldedPath(
            size = size,
            topCurl = FoundationPagerPoint(130f, 0f),
            bottomCurl = FoundationPagerPoint(50f, 200f),
        )

        assertEquals(4, folded.size)
        assertTrue(folded.all { it.x <= size.width })
        assertEquals(size.width, folded[0].x, tolerance)
        assertEquals(size.width, folded[1].x, tolerance)
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

package com.tedd.teddreader.feature.reader.impl.component

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [FoundationEffectPager]의 페이지 넘김 스타일(fluid pager, circle reveal, movie carousel, page flip)을
 * 뒷받침하는 순수 기하/애니메이션 계산에 대한 단위 테스트다: axis 변환, fluid edge의 point-spring
 * 물리, 그림자 모양, z-index/회전 순서. 전부 Compose 의존성이 없는 순수 수학이므로, composable 하네스를
 * 거치지 않고 직접 테스트한다.
 */
class FoundationPagerEffectMathTest {
    /** [FoundationPagerAxis]가 axis가 vertical일 때만 x/y를 맞바꾸는지 검증한다. */
    @Test
    fun `axis swaps coordinates only in vertical mode`() {
        assertEquals(FoundationPagerPoint(3f, 7f), FoundationPagerAxis.Horizontal.toCanonical(FoundationPagerPoint(3f, 7f)))
        assertEquals(FoundationPagerPoint(7f, 3f), FoundationPagerAxis.Vertical.toCanonical(FoundationPagerPoint(3f, 7f)))
        assertEquals(FoundationPagerPoint(3f, 7f), FoundationPagerAxis.Vertical.fromCanonical(FoundationPagerPoint(7f, 3f)))
    }

    /** [foundationPagerLerp]가 보간하기 전에 progress 인자를 `[0, 1]`로 clamp하는지 검증한다. */
    @Test
    fun `linear interpolation clamps progress`() {
        assertEquals(10f, foundationPagerLerp(10f, 20f, -1f))
        assertEquals(15f, foundationPagerLerp(10f, 20f, 0.5f))
        assertEquals(20f, foundationPagerLerp(10f, 20f, 2f))
    }

    /**
     * [foundationTouchCrossAxis]가 horizontal pager에서는 터치의 y 좌표를, vertical pager에서는 x
     * 좌표를 읽는지 검증한다 — cross axis는 넘김 방향이 아닌 나머지 축이기 때문이다.
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

    /** [foundationTouchCrossAxis]가 기록된 터치가 없을 때 중간점을 반환하는지 검증한다. */
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
     * [buildFoundationFluidPolygon]이 fluid edge의 직선 변을 [FoundationFluidSide.Start]에서는 x=0에,
     * [FoundationFluidSide.End]에서는 먼 쪽 edge에 고정하는지 검증한다.
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
     * fluid edge가 터치 지점의 cross-axis 위치에서 가장 크게 변형되는지 검증한다: 아래쪽에 더 가까운
     * 터치는 최대 변형 지점을 위쪽에 더 가까운 터치보다 더 아래로 밀어낸다.
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
     * [FoundationFluidEdge]의 spring 물리를 검증한다: 터치 중심 target을 향해 tick한 뒤, edge 전체가
     * 강체 직선처럼 움직이는 대신 터치 위치에 가장 가까운 점이 양쪽 이웃보다 앞서 나가야 한다.
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

    /** [FoundationFluidEdge.reset]이 모든 점의 위치와 속도를 0으로 되돌리는지 검증한다. */
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
     * [FoundationFluidEdge.reset] 직후에는 모든 점이 이미 정지 상태(`x = 0`, `progress = 0`)이므로,
     * 그 뒤 [FoundationFluidEdge.tick]을 아무리 반복해도 점이 실제로 움직이지 않고
     * [FoundationFluidEdge.version]도 오르지 않는지 검증한다 — 유휴 상태에서 [version]이 오르면
     * 이를 최상위에서 읽는 [FoundationEffectPager] 전체가 매 프레임 재구성된다.
     */
    @Test
    fun `fluid edge version stays put while idle after reset`() {
        val edge = FoundationFluidEdge(pointCount = 5)
        edge.reset()
        val idleVersion = edge.version

        repeat(30) { edge.tick(1f) }

        assertEquals(idleVersion, edge.version)
    }

    /**
     * 터치를 놓은 뒤에도 target [progress]를 향한 release 보간이 실제로 점을 움직이는 경우에는
     * [FoundationFluidEdge.tick]이 여전히 [FoundationFluidEdge.version]을 올리는지 검증한다 — 유휴
     * 상태에서 [version] 증가를 막은 수정이 release 감쇠 자체를 죽이지 않았다는 회귀 방지다.
     */
    @Test
    fun `fluid edge version advances when release interpolation moves a point`() {
        val edge = FoundationFluidEdge(pointCount = 5)

        edge.applyTarget(
            side = FoundationFluidSide.Start,
            progress = 0.5f,
            touchCrossAxis = 0.5f,
            touchActive = false,
        )
        val versionBeforeTick = edge.version

        edge.tick(1f)

        assertTrue(edge.version > versionBeforeTick)
    }

    /**
     * [foundationActivePageTurn]이, 제스처가 활성 상태이지만 방향을 드러낼 만큼 충분히 움직이지 않은
     * 동안에는, 우연히 더 큰 쪽 이웃의 progress로 추측하는 대신 확정된 side 없이 progress 0을 보고하는지
     * 검증한다.
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
     * [foundationActivePageTurn]이 drag 방향이 확인되면 제스처 자신의 확정된 side와 그 side의
     * progress를 보고하는지 검증한다.
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
     * 터치가 놓이고 나면, 터치가 앞서 끌어당겼던 [FoundationFluidEdge]의 점이 뾰족하게 튀어나온 채로
     * 남는 대신 edge 나머지 쪽으로 다시 가라앉는지 검증한다 — `touchActive = false`로 tick하면 활성
     * 터치가 만들어낸 앞선 정도의 대부분이 무너져야 한다.
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
     * 터치를 놓았을 때 이웃들에 대한 터치 근접 점의 앞선 정도가 놓은 시점보다 더 커지지 않는지
     * 검증한다 — 가라앉는 spring이 터치 자체가 만들어낸 것보다 더 강한 반동으로 오버슈트해서는
     * 안 된다.
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
     * 놓인 뒤 [FoundationFluidEdge]의 모든 점이 정지 [progress] target을 향해 단조롭게 이동하는지
     * 검증한다 — target까지의 거리는 tick마다([tolerance] 이내로) 줄어들기만 하고 부호가 절대
     * 바뀌지 않는다. 즉 target을 지나쳤다 되돌아오는 진동 없이 가라앉는다.
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
     * [buildFoundationFluidShadowPolygon]이 직선 offset이 아니라 fluid edge 자신이 가라앉은 것과 같은
     * 곡선 모양을 그리는지 검증한다.
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
     * [buildFoundationFluidPolygon]이 axis가 vertical일 때 고정되는 edge(y=0 대 먼 쪽 edge)를 바꿔서,
     * horizontal axis의 start/end 교환을 다른 축에도 그대로 반영하는지 검증한다.
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
     * [foundationCircleRevealSpec]이 두 axis 모두에서, reveal 원의 origin을 [FoundationFluidSide]에
     * 대응하는 edge 위 터치의 cross-axis 위치에 고정하는지 검증한다.
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
     * [foundationCircleRevealShadowSpec]이 reveal 원의 중심과 반지름을 [foundationCircleRevealSpec]과
     * 공유하고, 내부 반지름이 외부 반지름보다 확실히 작으면서 alpha가 양수여서, 그림자가 꽉 찬 원판이
     * 아니라 고리처럼 보이는지 검증한다.
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
     * [foundationMovieCarouselSpec]이 현재 페이지가 떠나는 쪽일 때만(자신의 [pageOffset]을 통해)
     * offset되고, 도착하는 이웃은 절대 offset되지 않으며, 전환 도중 도착하는 이웃이 떠나는 현재
     * 페이지보다 더 크고 더 불투명하게 그려지는지 검증한다.
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
     * [foundationMovieCarouselShadowSide]가 도착하는 이웃이 앞세우는 edge에서 들어오는 그림자를
     * 드리우는지 — 이전 페이지는 end에서, 다음 페이지는 start에서 도착한다 — 그리고 자신의 선두
     * edge가 없는 현재 페이지에는 그림자를 드리우지 않는지 검증한다.
     */
    @Test
    fun `movie carousel shadow follows incoming page leading edge`() {
        assertEquals(FoundationFluidSide.End, foundationMovieCarouselShadowSide(FoundationPagerPage.Previous))
        assertEquals(FoundationFluidSide.Start, foundationMovieCarouselShadowSide(FoundationPagerPage.Next))
        assertEquals(null, foundationMovieCarouselShadowSide(FoundationPagerPage.Current))
    }

    /**
     * [foundationMovieCarouselDimAlpha]가 전환의 양쪽 극단(0과 1, 그리고 그 범위 밖 입력이 clamp된
     * 값들)에서는 0이고 중간 지점에서는 양수여서, dimming 오버레이가 정지 상태가 아니라 전환 중간에서
     * 정점을 찍는지 검증한다.
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
     * 기준 그림자가 넘어가는 leaf의 자유 edge 위에 머무르면서, forward와 backward 넘김이 같은 크기와
     * opacity를 유지하는지 검증한다.
     */
    @Test
    fun `page flip shadow follows swipe free edge`() {
        val next = foundationPageFlipShadowSpec(0.5f, FoundationPageFlipLayout.WholePage)
        val previous = foundationPageFlipShadowSpec(-0.5f, FoundationPageFlipLayout.WholePage)

        assertEquals(FoundationFluidSide.End, next.side)
        assertEquals(FoundationFluidSide.Start, previous.side)
        assertEquals(next.outerWidthFraction, previous.outerWidthFraction, tolerance)
        assertEquals(next.innerWidthFraction, previous.innerWidthFraction, tolerance)
        assertEquals(next.opacity, previous.opacity, tolerance)
    }

    /**
     * 전체 페이지 flip이 들어 올려진 leaf의 움직이는 자유 edge에서 드러난 도착 페이지 위로 그림자를
     * 드리우는지 검증한다. forward/left 스와이프는 그 edge 너머 end 쪽을 드러내며, backward/right
     * 스와이프는 edge와 그림자를 페이지 중심을 기준으로 대칭시킨다.
     */
    @Test
    fun `whole page flip shadow follows moving edge onto incoming page`() {
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

        assertTrue(next.shadowEdgeFraction > 0.5f)
        assertEquals(FoundationFluidSide.End, next.castDirection)
        assertEquals(1f, next.shadowEdgeFraction + previous.shadowEdgeFraction, tolerance)
        assertEquals(FoundationFluidSide.Start, previous.castDirection)
    }

    /**
     * 반으로 나뉜 leaf의 그림자가 자신의 움직이는 edge를 따라가며, spine 쪽 불투명한 leaf 아래가
     * 아니라 드러난 receiver 쪽으로 뻗어 나가는지 검증한다.
     */
    @Test
    fun `spread page flip shadow crosses the spine onto uncovered side`() {
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
        assertEquals(FoundationFluidSide.End, beforeSpine.castDirection)
        assertEquals(0.5f, atSpine.shadowEdgeFraction, tolerance)
        assertEquals(FoundationFluidSide.Start, atSpine.castDirection)
        assertTrue(afterSpine.shadowEdgeFraction < 0.5f)
        assertEquals(FoundationFluidSide.Start, afterSpine.castDirection)
    }

    /**
     * 투영된 PAGE_FLIP 그림자가 정지된 모든 끝점에서 존재하지 않아, 기준 그림자 계약과 일치하고
     * 취소나 완료 이후 낡은 띠가 남지 않도록 하는지 검증한다.
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
     * [foundationPageFlipZIndex]의 single-pane 쌓임 순서를 drag 전체에 걸쳐 검증한다: 현재 페이지는
     * 항상 양쪽 이웃보다 위에 있어야 하고, 뒤로 넘길 때는 도착하는 이전 페이지가 다음 페이지보다
     * 순위가 높아야 한다(그렇지 않으면 접히는 페이지가 투명하게 남긴 부분으로 비쳐 보인다). 앞으로
     * 넘길 때는 순위가 같은 방식으로 뒤집히며, 어느 방향에서도 이웃이 현재 페이지 자신의 순위에
     * 도달하지는 않는다.
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
     * StPageFlip 기준 진행을 검증한다: 외부 너비는 leaf 하나의 75%까지 커지고, opacity는 turn
     * 중간에 Harism의 내부 그림자 alpha 0.5로 최대가 되며 양끝에서 0이 된다 — 다른 page-turn
     * 그림자와 같은 `sin` 엔벨로프다. 양끝이 0이어야 leaf가 평평한 상태에서 그림자가 남지 않는다.
     */
    @Test
    fun `page flip shadow follows reference width and opacity progression`() {
        val start = foundationPageFlipShadowSpec(0f, FoundationPageFlipLayout.WholePage)
        val quarter = foundationPageFlipShadowSpec(0.25f, FoundationPageFlipLayout.WholePage)
        val middle = foundationPageFlipShadowSpec(0.5f, FoundationPageFlipLayout.WholePage)
        val end = foundationPageFlipShadowSpec(1f, FoundationPageFlipLayout.WholePage)

        assertEquals(0f, start.outerWidthFraction, tolerance)
        assertEquals(0f, start.opacity, tolerance)
        assertEquals(0.375f, middle.outerWidthFraction, tolerance)
        assertEquals(0.5f, middle.opacity, tolerance)
        assertEquals(0.75f, end.outerWidthFraction, tolerance)
        assertEquals(0f, end.opacity, tolerance)
        assertTrue(quarter.opacity < middle.opacity)
        assertTrue(quarter.opacity > start.opacity)
        assertEquals(middle.outerWidthFraction * 0.75f, middle.innerWidthFraction, tolerance)
    }

    /** spread leaf는 viewport의 절반이므로, 두 기준 그림자 띠도 절반 너비여야 한다. */
    @Test
    fun `spread page flip shadow uses half viewport leaf width`() {
        val whole = foundationPageFlipShadowSpec(0.5f, FoundationPageFlipLayout.WholePage)
        val spread = foundationPageFlipShadowSpec(0.5f, FoundationPageFlipLayout.SplitHalfFold)

        assertEquals(whole.outerWidthFraction * 0.5f, spread.outerWidthFraction, tolerance)
        assertEquals(whole.innerWidthFraction * 0.5f, spread.innerWidthFraction, tolerance)
        assertEquals(whole.opacity, spread.opacity, tolerance)
    }

    /** 내부 띠는 clip 밖으로 벗어나지 않고, 잘려진 각 half의 바깥 자유 edge에 닿아야 한다. */
    @Test
    fun `spread page flip inner shadow follows clipped half free edge`() {
        assertEquals(FoundationFluidSide.Start, foundationPageFlipHalfShadowSide(FoundationPageFlipHalf.Left))
        assertEquals(FoundationFluidSide.End, foundationPageFlipHalfShadowSide(FoundationPageFlipHalf.Right))
        assertEquals(FoundationFluidSide.Start, foundationPageFlipHalfShadowSide(FoundationPageFlipHalf.Top))
        assertEquals(FoundationFluidSide.End, foundationPageFlipHalfShadowSide(FoundationPageFlipHalf.Bottom))
    }

    /**
     * [foundationPageFlipHalfSpec]이 스와이프가 접는 쪽의 half만 회전시키는지 검증한다 — 다음으로
     * 넘기는 스와이프는 left/top half를 회전시키고 right/bottom half는 평평하게 두며, 이전으로
     * 넘기는 스와이프는 그 반대다.
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
     * [foundationSpreadPageFlipSpec]이 정확히 접힘의 절반 지점에서 떠나는 half에서 들어오는 half로
     * 넘겨주고, forward와 backward 양방향 모두에서 완전한 넘김까지 매끄럽게 회전을 이어가는지
     * 검증한다.
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
     * [foundationPageFlipLayout]이 single pane에는 전체 페이지 fold를, 두 페이지 spread에는 split-half
     * fold를 선택하는지 검증한다.
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
     * [foundationWholePageFlipSpec]이 horizontal과 vertical axis 모두에서, 주어진 axis에 대해 같은
     * 회전 부호를 유지하면서 스와이프 방향에 따라 회전 중심 모서리만 바꾸는지 검증한다.
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
     * [points] 중 x가 가장 큰 점 — fluid 다각형에서 가장 크게 부푼 지점을, 그것이 어느 인덱스에
     * 놓이는지 가정하지 않고 찾는 데 쓰인다.
     *
     * @param points 탐색할 다각형 점들.
     * @return x 값이 최대인 점.
     */
    private fun maxXPoint(points: List<FoundationPagerPoint>): FoundationPagerPoint =
        points.maxBy { it.x }

    /**
     * 명시적인 허용 오차를 두는 float 동등성 단언이다 — 테스트 대상인 spring/삼각함수 계산은 정확한
     * 값에 좀처럼 들어맞지 않기 때문이다.
     *
     * @param expected 기대하는 값.
     * @param actual 테스트 대상 코드가 만들어낸 값.
     * @param tolerance [expected]와 [actual] 사이에 허용되는 최대 절대 차이.
     */
    private fun assertEquals(expected: Float, actual: Float, tolerance: Float) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected <$expected>, actual <$actual>.")
    }

    private companion object {
        /** 이 테스트 클래스 전체의 float 비교에서 쓰이는 절대 허용 오차. */
        const val tolerance = 0.0001f
    }
}

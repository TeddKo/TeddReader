package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.tedd.teddreader.core.common.model.PageTurnMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FoundationPagerCurlReferenceImpl]의 pagecurl 인터랙션을 뒷받침하는 순수 기하 계산에 대한 단위
 * 테스트다: 접힘 edge 구성, drag 방향/완료 임계값, 전체 viewport의 포인터 이동을 더 좁은 접히는 leaf로
 * 매핑하는 spread 모드의 leaf-offset 스케일링, tap 영역 해석. 전부 Compose 의존성이 없는 순수 수학이므로,
 * composable 하네스를 거치지 않고 직접 테스트한다.
 */
class FoundationPagerCurlReferenceImplTest {
    /**
     * [foundationReferenceCurlEdge]가 접힘의 위/아래 edge 점을, 현재 포인터 위치에 viewport의 가까운
     * 모서리로 이어지는 선에서 90도 회전한 벡터를 더하고 뺀 값으로 만드는지 검증한다.
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
     * [foundationReferenceCurlDirection]이 방향을 오직 포인터가 움직인 쪽으로만 읽는지 — 페이지 어디서든
     * 왼쪽으로 움직이면 forward, 오른쪽이면 backward — 그리고 움직임이 없을 때와 그 방향으로 넘어갈
     * 페이지가 없을 때 둘 다 null을 반환하는지 검증한다.
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
     * [spreadLeafOffset](spread 모드에서 [foundationReferenceCurlLeafOffset]을 거친다)이 전체 viewport
     * 너비를 leaf 자체의 더 좁은 너비로 매핑하는지 검증한다: 접힘 방향으로 먼 viewport 모서리는 항상
     * "완전히 접힘"([SpreadLeafWidth])을 뜻하고, 반대쪽 모서리는 forward든 backward든 viewport의 물리적
     * 어느 쪽이든 상관없이 항상 "정지 상태"(0)를 뜻한다.
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
     * spread 모드 leaf offset의 접힘 진행도(leaf 너비에 대한 비율)가, 테스트한 모든 포인터 위치에서
     * single-pane curl이 viewport 너비에 대한 비율로 보고하는 값과 정확히 일치하는지 검증한다 —
     * [foundationReferenceCurlLeafOffset]의 스케일링은 주어진 포인터 이동량에 대해 페이지가 얼마나
     * 접혔는지를 바꿔서는 안 되며, 그 이동을 어디서 측정하는지만 바꿔야 한다. backward 스와이프의
     * 진행도는 forward 스와이프의 여집합이다 — spread가 backward 넘김에도 forward curl 기하를 재사용
     * 하기 때문이다([foundationReferenceCurlGeometryDirection] 참고).
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
     * [foundationReferenceCurlDragSucceeds]가 spread의 leaf 자체는 더 좁음에도 불구하고, spread 스와이프를
     * 완료하는 데 single-pane과 같은 viewport 너비 20%의 방향성 이동을 요구하는지 검증한다 — 완료 여부는
     * leaf 자체의 너비가 아니라 포인터가 실제로 가로지른 전체 viewport를 기준으로 측정된다.
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
     * [foundationReferenceCurlGeometryDirection]이 spread의 backward(이전 페이지) 스와이프에도 forward
     * curl 기하를 재사용하는지 검증한다 — spread에서 접히는 leaf는 오른쪽 하나뿐이기 때문이다. 반면
     * single-pane의 backward 스와이프는 자신의 backward 기하를 그대로 유지한다.
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
     * [foundationReferenceVisibleCurlEdge]가, [pageKey]는 이미 넘어갔지만 [renderedPageKey]가 아직
     * 따라잡지 못한 상태 — 넘김이 완료된 시점과 새 페이지에 대한 reset 효과가 실행되는 시점 사이의
     * 구간 — 에서 애니메이션 중인 edge가 아니라 정지 edge를 보여주는지 검증한다.
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
     * horizontal axis에서 [foundationReferenceCurlDragSucceeds]가 양방향 모두 viewport 너비의 최소
     * 20%에 해당하는 방향성 이동을 요구하는지, 그 임계값 바로 아래는 실패하고 그 값이거나 그 이상은
     * 성공하는지 검증한다.
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
     * vertical axis에서 [foundationReferenceCurlDragSucceeds]가 20% 임계값을, 항상 고정된 한 치수가
     * 아니라 portrait와 landscape 방향 모두에서 viewport의 두 변 중 더 짧은 쪽을 기준으로 측정하는지
     * 검증한다.
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
     * [foundationReferenceCurlZIndex]가 이전 페이지를 현재 페이지보다 위로, 현재 페이지를 다음 페이지보다
     * 위로 배치하여, 접히는 leaf의 쌓임 순서가 실제 책의 페이지 순서와 일치하는지 검증한다.
     */
    @Test
    fun previousCurlPageIsLayeredAboveCurrentPage() {
        assertTrue(foundationReferenceCurlZIndex(-1) > foundationReferenceCurlZIndex(0))
        assertTrue(foundationReferenceCurlZIndex(0) > foundationReferenceCurlZIndex(1))
    }

    /**
     * auto-scroll이 활성화된 동안에는 [foundationReferenceCurlTapAction]이 어느 영역을 탭했든, 어떤
     * 페이지를 쓸 수 있든 상관없이 항상 controls 토글로 귀결되는지 검증한다 — 수동 탭이 자동 넘김과
     * 경쟁해서는 안 된다.
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
     * [foundationReferenceCurlTapAction]이 두 방향 모두에서 주축을 따라 1/4씩 영역을 나눠 해석하는지,
     * F16 수정을 포함해 검증한다: 넘어갈 페이지가 없는 영역(책의 시작이나 끝)에서의 탭은 아무 일도 하지
     * 않는 대신, 가운데 영역과 마찬가지로 controls 토글로 폴스루한다.
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
     * Play Books 스타일 3D curl은 항상 horizontal axis로 귀결되고, standard curl은 리더에 설정된
     * vertical 방향을 계속 따르는지 검증한다.
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

    /** drag 추적은 포인터 이벤트 뒤에 대기하는 spring 애니메이션이 아니라 직접적인 state다. */
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
     * 3D curl이 horizontal 방향이 우세한 움직임만 받아들이고 forward/backward를 X 방향에서 고정하여,
     * 대체로 vertical한 drag는 페이지 넘김을 시작할 수 없음을 검증한다.
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
     * 3D curl이 말리는 crease를 오직 X만으로 도출하는지 검증한다: 포인터 Y를 바꿔도 edge는 그대로이며,
     * 내부 crease는 퇴화하지 않고, 양 끝점은 renderer의 조기 반환이 쓰는 기존의 평평한 정지 edge와
     * 정확히 일치한다.
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
     * 받아들여진 3D drag가 손가락이 어디를 터치했든 상관없이 해당 방향의 평평한 정지 edge에서 시작하는지
     * 검증한다: 작은 오른쪽 방향의 backward drag는 포인터의 절대 X 위치로 건너뛰는 대신 그 작은 변위만을
     * 드러내며, forward도 같은 규칙을 그대로 따른다.
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
     * PlayLikeCurl 프로파일이 기준이 되는 25열 sinusoidal texture mesh를 사용하는지, 정지 상태에서는
     * 모든 구간이 뒤틀리지 않는지, 굽어진 상태에서는 투영된 경계가 단조롭고 연속적으로 유지되는지, 완료된
     * 페이지가 텍스트를 찢을 수 있는 뒤집힌 strip 없이 시작 edge를 완전히 벗어나 이동하는지 검증한다.
     */
    @Test
    fun threeDimensionalCurlWrapsTheLeafOnACylinderBehindAFlatRegion() {
        val rest = foundationReferenceThreeDCurlStripSpecs(0f)
        val bent = foundationReferenceThreeDCurlStripSpecs(0.5f)
        val complete = foundationReferenceThreeDCurlStripSpecs(1f)

        assertEquals(foundationPagerRenderProfile.threeDCurlGrid, rest.size)
        rest.forEach { strip ->
            assertEquals(strip.sourceStartFraction, strip.destinationStartFraction, 0.0001f)
            assertEquals(strip.sourceEndFraction, strip.destinationEndFraction, 0.0001f)
            assertEquals(0f, strip.depthFraction, 0.0001f)
            assertFalse(strip.isBackFacing)
        }

        bent.zipWithNext().forEach { (left, right) ->
            assertEquals(left.destinationEndFraction, right.destinationStartFraction, 0.0001f)
        }

        val flat = bent.filter { it.depthFraction == 0f }
        assertTrue(flat.isNotEmpty())
        assertEquals(0f, flat.first().destinationStartFraction, 0.0001f)
        flat.forEach { strip ->
            assertEquals(strip.sourceStartFraction, strip.destinationStartFraction, 0.0001f)
            assertEquals(strip.sourceEndFraction, strip.destinationEndFraction, 0.0001f)
            assertFalse(strip.isBackFacing)
        }

        val wrapped = bent.filter { it.depthFraction > 0f }
        assertTrue(wrapped.isNotEmpty())
        assertTrue(wrapped.any { it.isBackFacing })
        assertTrue(
            wrapped.maxOf { max(it.destinationStartFraction, it.destinationEndFraction) } >
                flat.maxOf { it.destinationEndFraction },
        )
        assertTrue(bent.maxOf { max(it.destinationStartFraction, it.destinationEndFraction) } < 1f)

        assertTrue(complete.all { it.depthFraction < 0.0001f })
        assertEquals(
            -1f,
            complete.minOf { min(it.destinationStartFraction, it.destinationEndFraction) },
            0.0001f,
        )
    }

    /**
     * 3D curl의 front/back/rim/cast 조명이 leaf가 굽어 있는 동안에만 존재하고, 1/4 회전 지점에서
     * 정점을 찍으며, 양쪽 평평한 방향에서 다시 사라지는지 검증한다.
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
     * [foundationReferenceSpreadPaneWidth]가 왼쪽/오른쪽 pane 너비를 gutter를 뺀 나머지에서 나누어,
     * 두 pane과 gutter를 합치면 다시 viewport 전체 너비가 되는지 검증한다 — 힌지가 gutter 위에
     * 정확히 놓인다는 유일한 산술 불변식이다. [foundationReferenceLeafSize]가 반환하는 오른쪽 pane
     * 너비가 [foundationReferenceSpreadPaneWidth]의 rightPx와 일치하는지도 함께 확인한다.
     */
    @Test
    fun spreadPaneWidthsFillTheViewportAroundTheGutter() {
        val canonicalSize = IntSize(SpreadViewportWidth.toInt(), SpreadHeight.toInt())

        listOf(0.5f, 0.2f, 0.8f).forEach { leftWeight ->
            val paneWidths = foundationReferenceSpreadPaneWidth(
                canonicalWidth = canonicalSize.width.toFloat(),
                gutterPx = SpreadGutter,
                leftWeight = leftWeight,
            )

            assertEquals(
                paneWidths.rightPx,
                foundationReferenceLeafSize(canonicalSize, true, SpreadGutter, leftWeight).width,
            )
            assertTrue(
                abs((paneWidths.leftPx + SpreadGutter + paneWidths.rightPx) - canonicalSize.width) <= 1f,
            )
        }
    }

    /**
     * [foundationReferenceSpreadPaneWidth]가 gutter가 viewport보다 넓어 나눌 너비가 0으로 바닥을
     * 찍어도, 두 pane 모두 fold 계산이 뒤집을 수 없는 0px로 붕괴하지 않고 1px 하한을 유지하는지
     * 검증한다.
     */
    @Test
    fun spreadPaneWidthNeverCollapsesBelowOnePixel() {
        val canonicalWidth = 100f
        val oversizedGutterPx = 2000f

        val even = foundationReferenceSpreadPaneWidth(canonicalWidth, oversizedGutterPx, leftWeight = 0.5f)
        assertTrue(even.leftPx >= 1)
        assertTrue(even.rightPx >= 1)

        val allLeft = foundationReferenceSpreadPaneWidth(canonicalWidth, oversizedGutterPx, leftWeight = 0f)
        assertTrue(allLeft.leftPx >= 1)

        val allRight = foundationReferenceSpreadPaneWidth(canonicalWidth, oversizedGutterPx, leftWeight = 1f)
        assertTrue(allRight.rightPx >= 1)
    }

    /**
     * turn이 완료되는 순간(progress = 1) 실린더 반지름이 0으로 수렴해 뒷면이 왜곡 없이 평평하게
     * 안착하고, 그 목적지가 spine 왼쪽으로 정확히 페이지 한 장(`-1`)까지 뻗는지 검증한다 — 그래야
     * 반대쪽 pane을 빈틈없이 덮으며 다음 spread로 스냅 없이 이어진다.
     */
    @Test
    fun spreadBackFaceLandsFlatAcrossTheOppositePaneWhenTheTurnCompletes() {
        val back = foundationReferenceThreeDCurlStripSpecs(1f).filter { it.isBackFacing }

        assertTrue(back.isNotEmpty())
        back.forEach { strip ->
            assertEquals(0f, strip.depthFraction, 0.0001f)
            assertTrue(strip.destinationEndFraction < strip.destinationStartFraction)
        }
        assertEquals(
            -1f,
            back.minOf { min(it.destinationStartFraction, it.destinationEndFraction) },
            0.0001f,
        )
    }

    /**
     * [foundationReferenceThreeDCurlMeshExtent]로 뽑아낸 산술이 [foundationReferenceDrawThreeDCurlMesh]가
     * 인라인으로 하던 계산과 비트 단위로 동일한 결과를 내는지 여러 progress 값에서 검증한다 — 단일
     * pane 3D curl이 이 리팩터로 회귀하지 않는다는 근거다.
     */
    @Test
    fun singlePaneMeshExtentIsUnchangedByTheExtraction() {
        val width = SpreadViewportWidth

        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { progress ->
            val strips = foundationReferenceThreeDCurlStripSpecs(progress)
            val expectedLeft = strips.minOf {
                min(it.destinationStartFraction, it.destinationEndFraction)
            } * width
            val expectedRight = strips.maxOf {
                max(it.destinationStartFraction, it.destinationEndFraction)
            } * width

            val extent = foundationReferenceThreeDCurlMeshExtent(strips, width)

            assertFalse(extent.isEmpty)
            assertEquals(expectedLeft, extent.leftPx, 0.001f)
            assertEquals(expectedRight, extent.rightPx, 0.001f)
        }
    }

    /**
     * spread 안에서 왼쪽/오른쪽 pane 너비의 합에 gutter를 더하면 viewport 너비를 넘지 않고, 왼쪽
     * pane 너비가 "viewport - gutter - 오른쪽 pane 너비"와 일치하는지 검증한다 — leaf가 자신이
     * 속한 pane의 경계를 벗어나 그려지지 않는다는 뜻이다.
     */
    @Test
    fun spreadLeafReachesTheViewportEdgeButNotBeyond() {
        val canonicalWidth = SpreadViewportWidth
        val gutterPx = SpreadGutter

        val paneWidths = foundationReferenceSpreadPaneWidth(canonicalWidth, gutterPx, leftWeight = 0.5f)

        assertTrue(paneWidths.leftPx + gutterPx + paneWidths.rightPx <= canonicalWidth + 1f)
        assertEquals(
            canonicalWidth - gutterPx - paneWidths.rightPx,
            paneWidths.leftPx.toFloat(),
            1f,
        )
    }

    /**
     * [foundationReferenceThreeDCurlProgress]가 spread 전체 너비가 아니라 leaf(자신이 그려지는
     * 목적지 노드) 너비를 기준으로 롤 진행률을 계산해야 함을 고정한다 — Phase 1에서 고쳐야 할 예측된
     * 결함으로, 잘못된 너비를 넘기면 정지 상태(오른쪽 edge)에서도 progress가 0이 아니게 나온다.
     */
    @Test
    fun threeDimensionalCurlProgressMustUseTheLeafWidthNotTheSpreadWidth() {
        val leafSize = IntSize(484, SpreadHeight.toInt())

        assertEquals(
            0f,
            foundationReferenceThreeDCurlProgress(
                FoundationReferenceCurlEdge.right(leafSize),
                leafSize.width.toFloat(),
            ),
            0.0001f,
        )
        assertEquals(
            1f,
            foundationReferenceThreeDCurlProgress(
                FoundationReferenceCurlEdge.left(leafSize),
                leafSize.width.toFloat(),
            ),
            0.0001f,
        )
        assertTrue(
            foundationReferenceThreeDCurlProgress(FoundationReferenceCurlEdge.right(leafSize), 1000f) > 0.5f,
        )
        assertEquals(
            0f,
            foundationReferenceThreeDCurlProgress(FoundationReferenceCurlEdge.right(leafSize), 0f),
            0.0001f,
        )
    }

    /**
     * [foundationReferenceSpreadOtherPaneWidthPx]가 leaf가 사는 pane의 *반대쪽* pane 너비를 내는지,
     * 그것도 힌지가 spread를 비대칭으로 가르는 폴더블 비율에서까지 그러한지 검증한다 — mesh가
     * gutter를 건너 반대쪽 pane 끝까지 닿게 하는 값이라, 작으면 시트가 gutter에서 끊긴 기준선처럼
     * 보이고 크면 반대쪽 pane을 넘어 침범한다.
     *
     * 왼쪽에 사는 leaf(뒤로 가는 turn)를 함께 확인하는 것이 이 테스트의 핵심이다. 이 값을 leaf
     * 너비에 비율을 곱해 역산하던 이전 방식은 leaf 너비가 방향과 무관하게 항상 오른쪽 pane 몫이라
     * 왼쪽 leaf에 대해 틀린 값을 냈고, `leftWeight`가 0.5인 대칭 spread에서만 우연히 맞아
     * 폴더블에서만 드러났다.
     */
    @Test
    fun spreadOtherPaneWidthPicksTheOppositePaneAtEveryHingeSplit() {
        val canonicalWidth = SpreadViewportWidth
        val gutterPx = SpreadGutter

        listOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f).forEach { leftWeight ->
            val paneWidths = foundationReferenceSpreadPaneWidth(canonicalWidth, gutterPx, leftWeight)

            val forwardSpan = foundationReferenceSpreadOtherPaneWidthPx(
                paneWidths,
                FoundationReferenceSpreadPane.Right,
            )
            val backwardSpan = foundationReferenceSpreadOtherPaneWidthPx(
                paneWidths,
                FoundationReferenceSpreadPane.Left,
            )

            assertEquals(paneWidths.leftPx.toFloat(), forwardSpan, 0.0001f)
            assertEquals(paneWidths.rightPx.toFloat(), backwardSpan, 0.0001f)
            assertTrue(forwardSpan + gutterPx >= paneWidths.leftPx.toFloat())
            assertTrue(backwardSpan + gutterPx >= paneWidths.rightPx.toFloat())
        }
    }

    /**
     * [foundationReferenceSpreadFaceNeedsContentMirror]가 style/face/leafHome의 여덟 조합 각각에서
     * 코드가 실제로 적용하는 미러 출처 수(Standard Front는 배치·콘텐츠 미러가 상쇄돼 항상 중립,
     * Standard Back은 `FoundationReferenceCurlFold.applyTo`의 무조건적인 미러가 상쇄되지 않고
     * 남으며, 3D mesh는 `mirrorHorizontally`일 때만 한 번 미러링한다)와 정확히 일치하는 값을
     * 반환하는지 검증한다. 3D는 mesh의 `mirrorHorizontally` 한 번과 뒷면 strip 자신의 음수
     * `scaleX` 한 번이 미러 출처이므로, 그 둘의 합이 홀수가 되는 두 조합(Back/Right,
     * Front/Left)에서만 콘텐츠 미러가 필요하다.
     *
     * 이 테스트는 그 계산 규칙을 표로 재진술한 것일 뿐 실제 렌더링을 검증하지는 않는다 — 값은
     * draw-transform 안에 흩어져 있어 눈으로 확인하기 전까지는 맞는지 알기 어려운 미묘한 미러
     * 규칙을, 이후 리팩터가 조용히 뒤집지 못하도록 계약으로 고정하는 데 있다.
     */
    @Test
    fun spreadFaceContentMirrorParityMatchesTheDrawPaths() {
        val standard = FoundationReferenceCurlStyle.Standard
        val threeD = FoundationReferenceCurlStyle.ThreeDimensional
        val front = FoundationReferenceSpreadFace.Front
        val back = FoundationReferenceSpreadFace.Back
        val left = FoundationReferenceSpreadPane.Left
        val right = FoundationReferenceSpreadPane.Right

        assertFalse(foundationReferenceSpreadFaceNeedsContentMirror(standard, front, right))
        assertFalse(foundationReferenceSpreadFaceNeedsContentMirror(standard, front, left))
        assertFalse(foundationReferenceSpreadFaceNeedsContentMirror(standard, back, right))
        assertTrue(foundationReferenceSpreadFaceNeedsContentMirror(standard, back, left))
        assertFalse(foundationReferenceSpreadFaceNeedsContentMirror(threeD, front, right))
        assertTrue(foundationReferenceSpreadFaceNeedsContentMirror(threeD, front, left))
        assertTrue(foundationReferenceSpreadFaceNeedsContentMirror(threeD, back, right))
        assertFalse(foundationReferenceSpreadFaceNeedsContentMirror(threeD, back, left))
    }

    /**
     * turn이 완료되면(progress = 1) 앞면 strip이 leaf 자신의 pane `[0, width]` 안에서 차지하는
     * 시각적 폭이 없어지는지 검증한다 — 시트 전체가 spine을 넘어갔으므로 앞면은 더 보이지 않는다.
     */
    @Test
    fun spreadFrontFaceHasNoVisibleWidthWhenTheTurnCompletes() {
        val paneWidth = 484f
        val front = foundationReferenceThreeDCurlStripSpecs(1f).filterNot { it.isBackFacing }

        val visible = front.sumOf { strip ->
            val left = min(strip.destinationStartFraction, strip.destinationEndFraction) * paneWidth
            val right = max(strip.destinationStartFraction, strip.destinationEndFraction) * paneWidth
            (min(right, paneWidth) - max(left, 0f)).coerceAtLeast(0f).toDouble()
        }
        assertEquals(0.0, visible, 0.001)
    }

    /**
     * 반대쪽 pane에 호스트되는 뒷면 mesh가, turn 후반에 spine에서 그 pane의 바깥쪽 edge까지 단조롭게
     * 자라나 완료 시점에 pane을 거의 전부 덮는지 검증한다 — 기기 없이 "반대쪽 pane이 함께 반응하고
     * 정확히 안착한다"를 증명하는 테스트다.
     *
     * 반대쪽 pane 배치는 `x' = width + x`이므로(mesh의 `hostsOppositePane` 경로) leaf 프레임에서
     * 음수인 목적지, 즉 spine을 넘어간 부분만 이 pane에서 보인다. 시트는 절반 이상 넘어간 뒤에야
     * spine을 넘어서므로 turn 전반부에 보이는 폭이 0인 것이 정상이다.
     *
     * 비대칭 pane 폭(leftWeight 0.2의 왼쪽 pane인 193px)으로도 반복해, 뒷면이 leaf 폭이 아니라 실제로
     * 그려지는 목적지 pane 폭에 안착함을 고정한다.
     */
    @Test
    fun spreadBackFaceGrowsMonotonicallyFromTheSpineToTheOuterEdge() {
        fun visibleWidth(progress: Float, widthPx: Float): Float {
            val strips = foundationReferenceThreeDCurlStripSpecs(progress).filter { it.isBackFacing }
            if (strips.isEmpty()) return 0f
            val extent = foundationReferenceThreeDCurlMeshExtent(strips, widthPx)
            val left = max(extent.leftPx, -widthPx)
            val right = min(extent.rightPx, 0f)
            return (right - left).coerceAtLeast(0f)
        }

        val evenPaneWidth = 484f
        assertEquals(0f, visibleWidth(0f, evenPaneWidth), 0.0001f)
        assertEquals(0f, visibleWidth(0.5f, evenPaneWidth), 0.0001f)
        assertTrue(visibleWidth(0.8f, evenPaneWidth) > 0f)
        assertTrue(visibleWidth(1f, evenPaneWidth) > evenPaneWidth * 0.9f)
        listOf(0.6f, 0.7f, 0.8f, 0.9f, 1f).zipWithNext().forEach { (a, b) ->
            assertTrue(visibleWidth(a, evenPaneWidth) <= visibleWidth(b, evenPaneWidth) + 0.001f)
        }

        val asymmetricPaneWidth = 193f
        assertEquals(0f, visibleWidth(0f, asymmetricPaneWidth), 0.0001f)
        assertTrue(visibleWidth(1f, asymmetricPaneWidth) > asymmetricPaneWidth * 0.9f)
    }

    /**
     * 이 테스트 파일에 고정된 [SpreadViewportWidth]/[SpreadLeafWidth]를 사용해, viewport x좌표 [x]에
     * 있는 포인터가 [direction]에 대해 매핑되는 spread 모드 leaf offset을 만든다 — 위의 spread 스케일링
     * 단언들이 공유하는 준비 과정이다.
     *
     * @param x 전체 viewport 안에서 포인터의 x 위치.
     * @param direction offset을 계산할 접힘 방향.
     * @return 접히는 leaf 자신의 좌표 공간에서의 대응 offset.
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

    /**
     * turn 정지 상태(progress = 0)에서 뒷면으로 그릴 strip이 하나도 없음을 검증한다 — 시트가
     * 평평하면 감김 각도가 어디서도 `PI / 2`를 넘지 않으므로 뒷면이 존재하지 않는다.
     *
     * [foundationReferenceDrawLeafBack]의 3D 경로는 정확히 이 조건으로 조기 반환해 오프셋스크린
     * 텍스처 기록을 건너뛴다. 이 테스트가 그 가드가 죽은 코드가 아님을 고정한다.
     */
    @Test
    fun spreadBackFaceHasNoStripsWhileTheTurnRests() {
        val strips = foundationReferenceThreeDCurlStripSpecs(0f).filter { it.isBackFacing }

        assertTrue(strips.isEmpty())
        assertTrue(foundationReferenceThreeDCurlMeshExtent(strips, BackFacePaneWidth).isEmpty)
    }

    /**
     * turn이 진행되면 뒷면 strip이 생기고 그 목적지가 소스와 반대 방향으로 흐르는지 검증한다 —
     * 감긴 시트의 뒷면이 좌우 반전되어 보이는 근거이며, 가드가 과도하게 잡아 진행 중인 뒷면까지
     * 지우지 않음을 함께 고정한다.
     */
    @Test
    fun spreadBackFaceStripsAppearReversedOnceTheTurnProgresses() {
        val strips = foundationReferenceThreeDCurlStripSpecs(0.5f).filter { it.isBackFacing }

        assertTrue(strips.isNotEmpty())
        assertFalse(foundationReferenceThreeDCurlMeshExtent(strips, BackFacePaneWidth).isEmpty)
        strips.forEach { strip ->
            assertTrue(strip.destinationEndFraction < strip.destinationStartFraction)
        }
    }

    private companion object {
        /** 이 클래스의 모든 spread 모드 단언에서 쓰이는, 고정된 전체 viewport 너비. */
        const val SpreadViewportWidth = 1000f

        /** 모든 spread 모드 단언에서 쓰이는, 고정된 접히는 leaf 너비(viewport의 절반). */
        const val SpreadLeafWidth = 500f

        /** pane 너비 계산 단언에서 쓰이는, 고정된 gutter 너비. */
        const val SpreadGutter = 32f

        /** pane 너비 계산 단언에서 쓰이는, 고정된 viewport 높이. */
        const val SpreadHeight = 600f

        /** 뒷면 mesh 가시성 단언에서 쓰이는, 고정된 균등 pane 너비. */
        const val BackFacePaneWidth = 484f

        /** 뒷면 mesh 가시성 단언에서 쓰이는, 고정된 비대칭 pane 너비(leftWeight 0.2 기준). */
        const val BackFaceAsymmetricPaneWidth = 193f
    }
}

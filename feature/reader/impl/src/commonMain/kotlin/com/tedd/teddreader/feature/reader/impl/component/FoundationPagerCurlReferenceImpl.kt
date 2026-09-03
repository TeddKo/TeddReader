package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.feature.reader.impl.autoScrollPageDelayMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * pagecurl 1.5.1의 인터랙션·렌더링 상태 머신을 갖춘 Compose Foundation pager 호스트.
 *
 * 두 페이지 spread에서는 바깥쪽 leaf에서만 curl이 동작한다: leaf가 spine을 중심으로 접혀 마주보는
 * 페이지 위에 내려앉으며, 실제 책이 넘어가는 방식과 같다. 단일 pane은 참조 동작을 그대로 유지한다.
 *
 * 두 페이지 spread에서는 leaf가 viewport보다 좁으므로, 포인터 이동량은 이동시키는 대신 leaf 공간으로
 * 스케일된다 — 그래야 스와이프 한 단위당 fold 진행률이 모든 포인터 위치와 양쪽 방향 모두에서 단일
 * pane curl과 동일하게 유지된다.
 *
 * 또한 spread에서는 이전 leaf가 자신의 back face를 마주보는 페이지 위에 그리므로, 실제로 다시
 * 넘어가는 중일 때만 compose되어야 한다; 그렇지 않으면 독자가 보고 있는 페이지를 덮어버리게 된다.
 *
 * @param pageKey 현재 페이지 인덱스.
 * @param pageCount 지금까지 알려진 전체 페이지 수.
 * @param pageStep 한 번의 turn이 몇 페이지를 진행시키는지.
 * @param pageTurnMode 페이지가 가로축과 세로축 중 어느 쪽으로 넘어가는지.
 * @param style 원래의 포인터 추적 curl을 쓸지, 가로 전용 3D 롤링 프로필을 쓸지.
 * @param paperColor 접힌 부분의 뒷면을 채우는 페이지 색으로, 독자가 고른 리더 팔레트의 종이색이다.
 * @param canRequestNextPage 알려진 끝에 있는 텍스트 문서가 페이지 나누기가 아직 끝나지 않은 동안에도
 *   다음 요청을 계속 전달해야 하는지 여부.
 * @param pageMoveRequest 대기 중인 프로그래밍적 페이지 이동 요청, 없으면 null.
 * @param onPageMoveRequestConsumed [pageMoveRequest]의 id와 함께, 그것이 애니메이션되었거나 갈 곳이
 *   없다고 확인된 뒤 호출된다.
 * @param onPreviousPage 뒤로 가는 curl이 완료되면 호출된다.
 * @param onNextPage 앞으로 가는 curl이 완료되면 호출된다.
 * @param onToggleControls 탭이 두 turn 영역 바깥에 떨어지거나 자동 스크롤 도중이면 호출된다.
 * @param onDoubleTap 더블 탭 시 탭 위치와 함께 호출된다; null이면 이를 비활성화한다.
 * @param isAutoScrollEnabled 자동 스크롤이 현재 turn을 구동하고 있는지 여부.
 * @param autoScrollMode 따를 자동 스크롤 모드.
 * @param autoScrollSpeed 설정된 자동 스크롤 속도.
 * @param onAutoScrollStop 자동 스크롤이 문서 끝에 닿아 멈춰야 할 때 호출된다.
 * @param modifier pager의 루트에 적용되는 modifier.
 * @param paneCount 몇 개의 페이지 pane이 나란히 보이는지(spread면 2, 그 외엔 1).
 * @param spreadGutter spread에서 pane 사이에 그려지는 간격.
 * @param spreadLeftWeight spread의 너비 중 왼쪽 pane에 주어지는 비율.
 * @param spreadModifier spread의 row에 적용되는 modifier.
 * @param paneContent spread의 한 pane을 자신만의 modifier로 렌더링한다; 단일 pane이면 null.
 * @param content 단일 pane 케이스에서 주어진 인덱스의 페이지를 렌더링한다.
 */
@Composable
internal fun FoundationPagerCurlReferenceImpl(
    pageKey: Int,
    pageCount: Int,
    pageStep: Int,
    pageTurnMode: PageTurnMode,
    style: FoundationReferenceCurlStyle,
    paperColor: Color,
    canRequestNextPage: Boolean,
    pageMoveRequest: ReaderPageMoveRequest?,
    onPageMoveRequestConsumed: (Int) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleControls: () -> Unit,
    onDoubleTap: ((Offset) -> Unit)?,
    isAutoScrollEnabled: Boolean,
    autoScrollMode: AutoScrollMode,
    autoScrollSpeed: Float,
    onAutoScrollStop: () -> Unit,
    modifier: Modifier = Modifier,
    paneCount: Int = 1,
    spreadGutter: Dp = 0.dp,
    spreadLeftWeight: Float = 0.5f,
    spreadModifier: Modifier = Modifier,
    paneContent: (@Composable (page: Int, modifier: Modifier) -> Unit)? = null,
    content: @Composable (page: Int) -> Unit,
) {
    val axis = foundationReferenceCurlAxis(pageTurnMode, style)
    val pagerState = rememberPagerState(
        initialPage = FoundationReferenceCenterPage,
        pageCount = { FoundationReferencePagerPageCount },
    )
    val scope = rememberCoroutineScope()
    val latestOnPreviousPage by rememberUpdatedState(onPreviousPage)
    val latestOnNextPage by rememberUpdatedState(onNextPage)
    val latestOnToggleControls by rememberUpdatedState(onToggleControls)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportSize = IntSize(constraints.maxWidth, constraints.maxHeight)
        val canonicalSize = axis.canonicalSize(viewportSize)
        val isSpread = paneContent != null &&
            axis == FoundationReferenceCurlAxis.Horizontal &&
            paneCount >= 2
        val gutterPx = with(density) { spreadGutter.toPx() }
        val leafSize = foundationReferenceLeafSize(canonicalSize, isSpread, gutterPx, spreadLeftWeight)
        val paneWidths = foundationReferenceSpreadPaneWidth(
            canonicalWidth = canonicalSize.width.toFloat(),
            gutterPx = gutterPx,
            leftWeight = spreadLeftWeight,
        )
        val leafScale = if (isSpread) {
            leafSize.width / canonicalSize.width.toFloat().coerceAtLeast(1f)
        } else {
            1f
        }
        val leftEdge = FoundationReferenceCurlEdge.left(leafSize)
        val rightEdge = FoundationReferenceCurlEdge.right(leafSize)
        val backwardRestEdge = if (isSpread) rightEdge else leftEdge
        val backwardEndEdge = if (isSpread) leftEdge else FoundationReferenceCurlEdge.end(leafSize)
        val forwardEdge = remember(axis, leafSize) {
            Animatable(
                rightEdge,
                FoundationReferenceCurlEdge.VectorConverter,
                FoundationReferenceCurlEdge.VisibilityThreshold,
            )
        }
        val backwardEdge = remember(axis, leafSize) {
            Animatable(
                backwardRestEdge,
                FoundationReferenceCurlEdge.VectorConverter,
                FoundationReferenceCurlEdge.VisibilityThreshold,
            )
        }
        var renderedPageKey by remember { mutableStateOf(pageKey) }
        var animationJob by remember(axis, leafSize, style) { mutableStateOf<Job?>(null) }
        var activeDrag by remember(axis, leafSize, style) {
            mutableStateOf<FoundationReferenceActiveDrag?>(null)
        }
        val previousPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, -1)
        val nextPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, 1)
        val canGoBackward = previousPage != null
        val canGoForward = readerPagerCanAdvanceForward(nextPage != null, canRequestNextPage)

        suspend fun reset() {
            activeDrag = null
            forwardEdge.snapTo(rightEdge)
            backwardEdge.snapTo(backwardRestEdge)
            if (pagerState.currentPage != FoundationReferenceCenterPage) {
                pagerState.scrollToPage(FoundationReferenceCenterPage)
            }
        }

        fun complete(direction: FoundationReferenceCurlDirection) {
            when (direction) {
                FoundationReferenceCurlDirection.Forward -> {
                    latestOnNextPage()
                    if (nextPage == null && canRequestNextPage) {
                        scope.launch { reset() }
                    }
                }
                FoundationReferenceCurlDirection.Backward -> latestOnPreviousPage()
            }
        }

        fun animateTap(
            direction: FoundationReferenceCurlDirection,
            animationDurationMillis: Int = FoundationReferenceTapDurationMillis,
            onFinished: () -> Unit = {},
        ) {
            animationJob?.cancel()
            animationJob = scope.launch {
                val isThreeD = style == FoundationReferenceCurlStyle.ThreeDimensional
                val geometryDirection = foundationReferenceCurlGeometryDirection(direction, isSpread)
                val edge = if (direction == FoundationReferenceCurlDirection.Forward) forwardEdge else backwardEdge
                val start = if (direction == FoundationReferenceCurlDirection.Forward) rightEdge else backwardRestEdge
                val end = when {
                    geometryDirection == FoundationReferenceCurlDirection.Forward -> leftEdge
                    isThreeD -> rightEdge
                    else -> backwardEndEdge
                }
                var completed = false
                try {
                    reset()
                    edge.animateTo(
                        targetValue = end,
                        animationSpec = if (isThreeD) {
                            foundationReferenceThreeDCurlTapSpec(
                                direction = geometryDirection,
                                size = leafSize,
                                durationMillisOverride = animationDurationMillis,
                            )
                        } else {
                            foundationReferenceTapSpec(
                                direction = geometryDirection,
                                size = leafSize,
                                durationMillisOverride = animationDurationMillis,
                            )
                        },
                    )
                    completed = true
                } finally {
                    withContext(NonCancellable) {
                        if (completed) {
                            complete(direction)
                        } else {
                            edge.snapTo(start)
                            pagerState.scrollToPage(FoundationReferenceCenterPage)
                        }
                        onFinished()
                    }
                }
            }
        }

        LaunchedEffect(pageKey, pageStep, axis, leafSize, style) {
            reset()
            renderedPageKey = pageKey
        }

        LaunchedEffect(pageMoveRequest?.id) {
            val request = pageMoveRequest ?: return@LaunchedEffect
            val direction = when (request.movement) {
                ReaderPageMovement.Previous -> FoundationReferenceCurlDirection.Backward.takeIf { canGoBackward }
                ReaderPageMovement.Next -> FoundationReferenceCurlDirection.Forward.takeIf { canGoForward }
            }
            if (direction == null) {
                onPageMoveRequestConsumed(request.id)
            } else {
                animateTap(direction) { onPageMoveRequestConsumed(request.id) }
            }
        }
        LaunchedEffect(isAutoScrollEnabled, autoScrollMode, autoScrollSpeed, pageKey, nextPage) {
            if (!isAutoScrollEnabled || autoScrollMode == AutoScrollMode.PAGE) return@LaunchedEffect
            if (nextPage == null) {
                onAutoScrollStop()
                return@LaunchedEffect
            }
            animateTap(
                direction = FoundationReferenceCurlDirection.Forward,
                animationDurationMillis = autoScrollPageDelayMillis(autoScrollSpeed).toInt().coerceAtLeast(1),
                onFinished = {},
            )
        }
        LaunchedEffect(isAutoScrollEnabled, autoScrollMode) {
            if (isAutoScrollEnabled && autoScrollMode != AutoScrollMode.PAGE) return@LaunchedEffect
            animationJob?.cancel()
            reset()
        }
        val pagerModifier = Modifier
            .fillMaxSize()
            .run {
                if (isAutoScrollEnabled) {
                    this
                } else {
                    pointerInput(forwardEdge, backwardEdge, canGoForward, canGoBackward, style) {
                        detectFoundationReferenceCurlGestures(
                            axis = axis,
                            canonicalSize = leafSize,
                            isSpread = isSpread,
                            leafScale = leafScale,
                            leafWidth = leafSize.width.toFloat(),
                            scope = scope,
                            forwardEdge = forwardEdge,
                            backwardEdge = backwardEdge,
                            canGoForward = canGoForward,
                            canGoBackward = canGoBackward,
                            onDragStart = {
                                animationJob?.cancel()
                            },
                            onDragEdgeChange = { activeDrag = it },
                            onComplete = { direction ->
                                complete(direction)
                            },
                            style = style,
                        )
                    }
                }
            }
            .pointerInput(canGoForward, canGoBackward, axis, isAutoScrollEnabled, onDoubleTap) {
                detectFoundationReferenceCurlTaps(
                    axis = axis,
                    canGoForward = canGoForward,
                    canGoBackward = canGoBackward,
                    isAutoScrollEnabled = isAutoScrollEnabled,
                    onPageTap = ::animateTap,
                    onToggleControls = { latestOnToggleControls() },
                    onDoubleTap = onDoubleTap,
                )
            }

        val pageContent: @Composable (Int) -> Unit = { pagerPage ->
            val curlGraphicsLayer = rememberGraphicsLayer().apply {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            val pageOffset = pagerPage - FoundationReferenceCenterPage
            val documentPage = readerPagerDisplayedPage(
                currentPage = pageKey,
                adjacentPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, pageOffset),
                pageOffset = pageOffset,
                canRequestNextPage = canRequestNextPage,
            )
            // 이 세 값 — activeDrag, backwardEdge.value, forwardEdge.value — 는 각각 포인터
            // 이벤트마다, 그리고 turn 애니메이션 프레임마다 바뀌는 snapshot state다. 예전에는
            // 여기 composition 본문에서 읽었기 때문에, 드래그나 tap-turn 애니메이션이 도는 내내
            // 이 슬롯이 프레임마다 재구성됐고 그때마다 아래 콘텐츠 선택 분기까지 다시 돌았다.
            // 실제로 이 값이 필요한 곳은 fold를 그리는 drawWithCache 블록 하나뿐이므로, 읽기를
            // 그 안으로 미룬다.
            fun animatedBackwardEdge(): FoundationReferenceCurlEdge = activeDrag
                ?.takeIf { it.direction == FoundationReferenceCurlDirection.Backward }
                ?.edge
                ?: backwardEdge.value

            fun animatedForwardEdge(): FoundationReferenceCurlEdge = activeDrag
                ?.takeIf { it.direction == FoundationReferenceCurlDirection.Forward }
                ?.edge
                ?: forwardEdge.value

            // 여기서 non-null을 돌려주는 오프셋 집합은 아래 `hasLeaf`와 반드시 일치해야 한다.
            fun leafEdgeOrNull(): FoundationReferenceCurlEdge? = when (pageOffset) {
                -1 -> foundationReferenceVisibleCurlEdge(
                    pageKey,
                    renderedPageKey,
                    animatedBackwardEdge(),
                    backwardRestEdge,
                )
                0 -> foundationReferenceVisibleCurlEdge(
                    pageKey,
                    renderedPageKey,
                    animatedForwardEdge(),
                    rightEdge,
                )
                else -> null
            }

            // 이 슬롯이 접히는 leaf를 갖는지는 슬롯 인덱스만으로 정해진다 — 애니메이션 값과 무관하므로
            // 프레임마다 다시 판단할 것이 없다. [leafEdgeOrNull]이 non-null을 돌려주는 오프셋 집합과
            // 반드시 같아야 한다: 한쪽만 바뀌면 아래 requireNotNull이 composition이 아니라 draw
            // 패스에서 터져 원인 추적이 어려워진다.
            val hasLeaf = pageOffset == -1 || pageOffset == 0

            // spread는 leaf edge를 구조적으로 쓴다(어느 절반을 그릴지, 뒤로 가는 슬롯을 건너뛸지).
            // 그 경로는 지금도 composition에서 값을 읽어야 하므로 그대로 두고, 단일 pane 경로만
            // 지연시킨다. 예전에는 이 계산이 isSpread와 무관하게 항상 실행돼, spread가 아닐 때도
            // 애니메이션 값을 composition에서 읽게 만들었다.
            val leafEdge = if (isSpread) leafEdgeOrNull() else null
            val skipSpreadPage = isSpread && foundationReferenceSpreadShouldSkipBackwardSlot(
                isSpread = true,
                pageOffset = pageOffset,
                isBackwardTurnResting = leafEdge == backwardRestEdge,
                isForwardTurnResting = foundationReferenceVisibleCurlEdge(
                    pageKey,
                    renderedPageKey,
                    animatedForwardEdge(),
                    rightEdge,
                ) == rightEdge,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .foundationCancelPagerPlacement(axis, pageOffset)
                    .zIndex(foundationReferenceCurlZIndex(pageOffset))
                    .run {
                        if (isSpread || !hasLeaf) {
                            this
                        } else {
                            foundationReferenceDrawCurl(
                                axis = axis,
                                edgeProvider = { requireNotNull(leafEdgeOrNull()) },
                                style = style,
                                paperColor = paperColor,
                                graphicsLayer = curlGraphicsLayer,
                            )
                        }
                    },
            ) {
                if (documentPage != null && !skipSpreadPage) {
                    if (isSpread) {
                        if (pageOffset == -1) {
                            FoundationReferenceBackwardSpread(
                                previousLeftPage = documentPage,
                                currentLeftPage = pageKey,
                                axis = axis,
                                style = style,
                                leafEdge = requireNotNull(leafEdge),
                                leafSize = leafSize,
                                gutter = spreadGutter,
                                leftWeight = spreadLeftWeight,
                                paneWidths = paneWidths,
                                spreadModifier = spreadModifier,
                                paneContent = requireNotNull(paneContent),
                            )
                        } else {
                            FoundationReferenceSpread(
                                leftPage = documentPage,
                                axis = axis,
                                style = style,
                                leafEdge = leafEdge,
                                leafSize = leafSize,
                                gutter = spreadGutter,
                                leftWeight = spreadLeftWeight,
                                paneWidths = paneWidths,
                                spreadModifier = spreadModifier,
                                paneContent = requireNotNull(paneContent),
                            )
                        }
                    } else {
                        content(documentPage)
                    }
                }
            }
        }

        if (axis == FoundationReferenceCurlAxis.Horizontal) {
            HorizontalPager(
                state = pagerState,
                modifier = pagerModifier,
                userScrollEnabled = false,
                beyondViewportPageCount = 1,
                pageContent = { pagerPage -> pageContent(pagerPage) },
            )
        } else {
            VerticalPager(
                state = pagerState,
                modifier = pagerModifier,
                userScrollEnabled = false,
                beyondViewportPageCount = 1,
                pageContent = { pagerPage -> pageContent(pagerPage) },
            )
        }
    }
}

/**
 * 두 페이지 spread: 고정된 마주보는 페이지와, spine을 중심으로 접히는 바깥쪽 leaf.
 *
 * leaf는 양면을 모두 갖는다. [leftPage] + 1은 앞면에, [leftPage] + 2는 뒷면에 인쇄되어 있어서,
 * 앞으로 접히는 fold는 다음 페이지를 turn이 끝난 뒤 pager가 배치할 바로 그 위치, 즉 마주보는 면에
 * 내려놓는다.
 *
 * 앞면과 뒷면 모두 leaf의 원래 pane(오른쪽) 노드 하나에만 호스트한다. [Box]는 자식의 드로잉을
 * 클립하지 않으므로, mesh에 `spanBeyondSpinePx`로 반대쪽 pane 너비와 gutter를 열어 주면 시트가
 * gutter를 건너 왼쪽 pane까지 하나의 연속된 mesh로 이어진다. 뒷면을 반대쪽 pane 노드에 따로
 * 호스트하면 두 조각 사이의 gutter가 빈 채로 남아 시트가 끊긴 기준선처럼 보인다.
 *
 * 뒷면 콘텐츠는 [foundationReferenceSpreadFaceNeedsContentMirror]가 참을 낼 때 미리 미러링한다
 * (`scaleX = -1f`) — 뒷면 strip은 목적지가 소스와 반대로 흐르므로 텍스처를 `1 - m`에서 읽어야
 * verso가 정방향으로 읽힌다.
 *
 * @param leftPage 왼쪽 pane에 명시적으로 그려지는 정적 페이지.
 * @param axis fold가 가로로 움직이는지 세로로 움직이는지.
 * @param style leaf가 표준 페인팅을 쓸지 3D 조명 프로필을 쓸지 — 뒷면을 어느 pane에 호스트할지도
 *   이 값이 정한다.
 * @param leafEdge leaf의 현재 fold edge, canonical 좌표계 기준; null이면 접히지 않는 밑판
 *   슬롯이라 두 leaf 면 모두 그리지 않는다.
 * @param leafSize curl 기하가 페이지 한 장으로 취급하는 크기로, leaf-face draw 함수가 3D 진행률을
 *   재는 edge 공간이다([foundationReferenceLeafSize] 참고).
 * @param gutter 두 pane 사이의 간격.
 * @param leftWeight spread의 너비 중 왼쪽 pane에 주어지는 비율.
 * @param paneWidths gutter 양옆 두 pane의 너비로, mesh가 spine을 넘어 반대쪽 pane 끝까지 닿는 범위를
 *   [foundationReferenceSpreadOtherPaneWidthPx]로 고르는 데 쓴다.
 * @param spreadModifier spread의 row에 적용되는 modifier.
 * @param paneContent 주어진 modifier로 한 페이지를 pane 안에 렌더링한다.
 */
@Composable
private fun FoundationReferenceSpread(
    leftPage: Int,
    axis: FoundationReferenceCurlAxis,
    style: FoundationReferenceCurlStyle,
    leafEdge: FoundationReferenceCurlEdge?,
    leafSize: IntSize,
    gutter: Dp,
    leftWeight: Float,
    paneWidths: FoundationReferenceSpreadPaneWidths,
    spreadModifier: Modifier,
    paneContent: @Composable (page: Int, modifier: Modifier) -> Unit,
) {
    val curlGraphicsLayer = rememberGraphicsLayer().apply {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    val backGraphicsLayer = rememberGraphicsLayer().apply {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    val leafHome = FoundationReferenceSpreadPane.Right
    val backNeedsContentMirror = foundationReferenceSpreadFaceNeedsContentMirror(
        style,
        FoundationReferenceSpreadFace.Back,
        leafHome,
    )
    val spanBeyondSpinePx = with(LocalDensity.current) {
        foundationReferenceSpreadOtherPaneWidthPx(paneWidths, leafHome) + gutter.toPx()
    }
    Row(
        modifier = spreadModifier,
        horizontalArrangement = Arrangement.spacedBy(gutter),
    ) {
        Box(modifier = Modifier.weight(leftWeight).fillMaxHeight()) {
            paneContent(leftPage, Modifier.fillMaxSize())
        }
        Box(modifier = Modifier.weight(1f - leftWeight).fillMaxHeight()) {
            paneContent(
                leftPage + 1,
                Modifier.fillMaxSize().run {
                    if (leafEdge == null) {
                        this
                    } else {
                        foundationReferenceDrawLeafFront(
                            axis = axis,
                            edge = leafEdge,
                            style = style,
                            leafSize = leafSize,
                            spanBeyondSpinePx = spanBeyondSpinePx,
                            graphicsLayer = curlGraphicsLayer,
                        )
                    }
                },
            )
            if (leafEdge != null) {
                paneContent(
                    leftPage + 2,
                    Modifier
                        .fillMaxSize()
                        .foundationReferenceDrawLeafBack(
                            axis = axis,
                            edge = leafEdge,
                            style = style,
                            leafSize = leafSize,
                            spanBeyondSpinePx = spanBeyondSpinePx,
                            graphicsLayer = backGraphicsLayer,
                        )
                        .run {
                            if (backNeedsContentMirror) graphicsLayer { scaleX = -1f } else this
                        },
                )
            }
        }
    }
}

/**
 * 뒤로 가는 turn이 현재 leaf를 그것이 덮고 있던 이전 페이지 쪽으로 접어 넘기는 동안의 두 페이지
 * spread.
 *
 * 앞면([currentLeftPage])은 항상 왼쪽 pane에 남아 gutter 쪽으로 접혀 줄어든다. 뒷면
 * ([previousLeftPage] + 1)이 놓이는 pane은 [style]에 따라 갈린다. Standard curl은 뒷면을 앞면과
 * 같은 왼쪽 pane 안에, leaf의 아직 평평한 부분을 나타내는 [foundationReferenceDrawLeafFront]로
 * 그려진 [currentLeftPage] 위에 [foundationReferenceDrawLeafBack]으로 쌓아 그린다 — 이는
 * [previousLeftPage]에서 시작하는 앞으로 가는 turn이 그리는 것과 같은 leaf를 —
 * [FoundationReferenceSpread] 참고 — 역순으로 재생하면서, 이 애니메이션이 평평한 상태에서 벗어나는
 * 대신 그 상태로 다가가기 때문에 앞면과 뒷면을 맞바꾼 것이다. 3D curl도 같은 왼쪽 pane 노드에
 * 호스트하고 mesh에 `spanBeyondSpinePx`를 열어, 시트가 gutter를 건너 오른쪽 pane까지 하나의
 * 연속된 mesh로 이어지게 한다.
 *
 * 앞면 호출은 `mirrorHorizontally = true`를 넘기는데, 이 fold는 앞으로 가는 fold의 오른쪽 가장자리
 * 힌지를 거울에 비춘 모습인 왼쪽 가장자리에서 경첩처럼 움직이기 때문이다. Standard 앞면은 배치용
 * 미러와 콘텐츠 안쪽 미러가 상쇄되어 추가 조치가 필요 없지만, 3D 앞면은 mesh 경로가 딱 한 번만
 * 미러링해 상쇄되지 않으므로 [foundationReferenceSpreadFaceNeedsContentMirror]가 참을 반환할 때
 * 콘텐츠에 미러를 한 번 더 넣어 텍스트가 좌우 반전으로 읽히지 않도록 한다.
 *
 * @param previousLeftPage 이 뒤로 가는 turn이 드러내고 있는 페이지.
 * @param currentLeftPage 현재 보이고 있는, leaf가 그로부터 접혀 나가는 중인 페이지.
 * @param axis fold가 가로로 움직이는지 세로로 움직이는지.
 * @param style leaf가 표준 페인팅을 쓸지 3D 조명 프로필을 쓸지 — 뒷면을 어느 pane에 호스트할지와
 *   앞면에 pre-mirror가 필요한지도 이 값이 정한다.
 * @param leafEdge leaf의 현재 fold edge, canonical 좌표계 기준.
 * @param leafSize curl 기하가 페이지 한 장으로 취급하는 크기로, leaf-face draw 함수가 3D 진행률을
 *   재는 edge 공간이다([foundationReferenceLeafSize] 참고).
 * @param gutter 두 pane 사이의 간격.
 * @param leftWeight spread의 너비 중 왼쪽 pane에 주어지는 비율.
 * @param paneWidths gutter 양옆 두 pane의 너비로, mesh가 spine을 넘어 반대쪽 pane 끝까지 닿는 범위를
 *   [foundationReferenceSpreadOtherPaneWidthPx]로 고르는 데 쓴다.
 * @param spreadModifier row에 적용되는 modifier.
 * @param paneContent 주어진 modifier로 한 페이지를 pane 안에 렌더링한다.
 */
@Composable
private fun FoundationReferenceBackwardSpread(
    previousLeftPage: Int,
    currentLeftPage: Int,
    axis: FoundationReferenceCurlAxis,
    style: FoundationReferenceCurlStyle,
    leafEdge: FoundationReferenceCurlEdge,
    leafSize: IntSize,
    gutter: Dp,
    leftWeight: Float,
    paneWidths: FoundationReferenceSpreadPaneWidths,
    spreadModifier: Modifier,
    paneContent: @Composable (page: Int, modifier: Modifier) -> Unit,
) {
    val curlGraphicsLayer = rememberGraphicsLayer().apply {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    val backGraphicsLayer = rememberGraphicsLayer().apply {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    val leafHome = FoundationReferenceSpreadPane.Left
    val frontNeedsContentMirror = foundationReferenceSpreadFaceNeedsContentMirror(
        style,
        FoundationReferenceSpreadFace.Front,
        leafHome,
    )
    val backNeedsContentMirror = foundationReferenceSpreadFaceNeedsContentMirror(
        style,
        FoundationReferenceSpreadFace.Back,
        leafHome,
    )
    val spanBeyondSpinePx = with(LocalDensity.current) {
        foundationReferenceSpreadOtherPaneWidthPx(paneWidths, leafHome) + gutter.toPx()
    }
    Row(
        modifier = spreadModifier,
        horizontalArrangement = Arrangement.spacedBy(gutter),
    ) {
        Box(modifier = Modifier.weight(leftWeight).fillMaxHeight()) {
            paneContent(previousLeftPage, Modifier.fillMaxSize())
            paneContent(
                currentLeftPage,
                Modifier
                    .fillMaxSize()
                    .foundationReferenceDrawLeafFront(
                        axis = axis,
                        edge = leafEdge,
                        style = style,
                        leafSize = leafSize,
                        mirrorHorizontally = true,
                        spanBeyondSpinePx = spanBeyondSpinePx,
                        graphicsLayer = curlGraphicsLayer,
                    )
                    .run {
                        if (frontNeedsContentMirror) graphicsLayer { scaleX = -1f } else this
                    },
            )
            paneContent(
                previousLeftPage + 1,
                Modifier
                    .fillMaxSize()
                    .foundationReferenceDrawLeafBack(
                        axis = axis,
                        edge = leafEdge,
                        style = style,
                        leafSize = leafSize,
                        mirrorHorizontally = true,
                        spanBeyondSpinePx = spanBeyondSpinePx,
                        graphicsLayer = backGraphicsLayer,
                    )
                    .run {
                        if (backNeedsContentMirror) graphicsLayer { scaleX = -1f } else this
                    },
            )
        }
        Box(modifier = Modifier.weight(1f - leftWeight).fillMaxHeight())
    }
}

/**
 * spread의 두 pane이 gutter 양옆에서 각각 차지하는 너비로, [foundationReferenceLeafSize]가 오른쪽
 * pane 몫을 구하려고 위임하는 산술이다. 3D curl 뒷면을 반대쪽 pane 노드 안에 배치하려면 왼쪽 pane
 * 몫도 같은 방식으로 필요하므로, 한쪽만 반환하던 계산에서 양쪽을 함께 낼 수 있도록 분리했다.
 *
 * @param canonicalWidth 축의 canonical(가로 우선) 방향으로 나타낸 viewport 너비.
 * @param gutterPx 두 pane 사이 간격, 픽셀 단위.
 * @param leftWeight 전체 너비 중 왼쪽 pane에 주어지는 비율; 오른쪽이 나머지를 갖기 전에 0..1로
 *   clamp된다.
 * @return gutter를 뺀 나머지를 [leftWeight] 비율로 나눈 왼쪽/오른쪽 pane 너비 — 0 또는 음수 분할이
 *   fold 계산이 뒤집을 수 없는 퇴화된 크기를 만들지 않도록 양쪽 모두 1px로 하한이 걸려 있다.
 */
internal fun foundationReferenceSpreadPaneWidth(
    canonicalWidth: Float,
    gutterPx: Float,
    leftWeight: Float,
): FoundationReferenceSpreadPaneWidths {
    val pagesWidth = (canonicalWidth - gutterPx).coerceAtLeast(0f)
    val clampedLeftWeight = leftWeight.coerceIn(0f, 1f)
    return FoundationReferenceSpreadPaneWidths(
        leftPx = (pagesWidth * clampedLeftWeight).toInt().coerceAtLeast(1),
        rightPx = (pagesWidth * (1f - clampedLeftWeight)).toInt().coerceAtLeast(1),
    )
}

/**
 * [foundationReferenceSpreadPaneWidth]가 계산한, gutter 양옆 두 pane의 너비.
 *
 * @property leftPx 왼쪽 pane에 배정된 너비, 픽셀 단위.
 * @property rightPx 오른쪽 pane에 배정된 너비, 픽셀 단위.
 */
internal data class FoundationReferenceSpreadPaneWidths(
    val leftPx: Int,
    val rightPx: Int,
)

/**
 * spread에서 leaf가 원래 속해 있는 pane — fold가 gutter의 어느 쪽에서 경첩처럼 움직이는지를
 * 가리킨다. 전진 turn은 오른쪽 pane에 있던 leaf가 접혀 나가므로 [Right]이고, 후진 turn은 왼쪽
 * pane에 있던 leaf가 접혀 나가므로 [Left]다([foundationReferenceCurlGeometryDirection] 참고).
 */
internal enum class FoundationReferenceSpreadPane { Left, Right }

/**
 * leaf의 두 면 중 지금 렌더링하는 쪽 — 지금 보이는 면([Front])인지, turn이 끝났을 때 안착할
 * 반대쪽 면([Back])인지.
 */
internal enum class FoundationReferenceSpreadFace { Front, Back }

/**
 * leaf가 사는 pane의 반대쪽 pane 너비 — mesh가 spine을 넘어 얼마나 더 그려야 반대쪽 pane 끝까지
 * 닿는지를 정하는 값이다.
 *
 * 두 pane 너비 중 하나를 고르는 것일 뿐이지만, 이를 leaf 너비에 곱하는 비율로 역산하면 안 된다:
 * `foundationReferenceLeafSize`가 내는 leaf 너비는 방향과 무관하게 항상 오른쪽 pane 몫이므로,
 * 왼쪽에 사는 leaf(뒤로 가는 turn)에 대해 그 역산은 힌지가 spread를 비대칭으로 가르는 폴더블에서
 * 반대쪽 pane 너비가 아닌 값을 낸다 — 시트가 gutter를 건너기 전에 잘리거나 반대쪽 pane을 넘어
 * 침범했던 원인이다. 두 pane 너비는 이미 [foundationReferenceSpreadPaneWidth]가 함께 내주므로
 * 필요한 쪽을 그대로 고른다.
 *
 * @param paneWidths gutter 양옆 두 pane의 너비.
 * @param leafHome 접히는 leaf가 사는 pane.
 * @return [leafHome]의 반대쪽 pane 너비, 픽셀 단위.
 */
internal fun foundationReferenceSpreadOtherPaneWidthPx(
    paneWidths: FoundationReferenceSpreadPaneWidths,
    leafHome: FoundationReferenceSpreadPane,
): Float = when (leafHome) {
    FoundationReferenceSpreadPane.Right -> paneWidths.leftPx.toFloat()
    FoundationReferenceSpreadPane.Left -> paneWidths.rightPx.toFloat()
}

/**
 * [style]/[face]/[leafHome] 조합에서, leaf 콘텐츠 자체에 가로 미러를 한 번 더 적용해야 텍스트가
 * 정방향으로 읽히는지 판정한다.
 *
 * 콘텐츠에 실제로 적용되는 가로 미러의 출처는 셋이다: (1) [foundationReferenceDrawLeafFront]의
 * Standard 경로는 배치용 바깥쪽 `withTransform`과 콘텐츠 바로 앞의 안쪽 `withTransform`이 같은
 * `mirrorHorizontally` 조건으로 짝을 이뤄 항상 상쇄되므로 중립이다. (2)
 * [foundationReferenceDrawLeafBack]의 Standard 경로는 `FoundationReferenceCurlFold.applyTo`가
 * 가로 축에서 무조건 한 번 미러링하고, 그 위를 감싸는 `mirrorHorizontally` 블록이 한 번 더
 * 미러링한다 — 둘 다 콘텐츠를 감싸므로, leaf가 왼쪽에 살아 두 미러가 모두 걸리는 뒤로 가는 turn은
 * 짝수로 상쇄되고, 오른쪽에 살아 `applyTo`만 걸리는 앞으로 가는 turn은 홀수로 남는다. (3) 3D
 * mesh 경로([foundationReferenceDrawThreeDCurlMesh])는 `mirrorHorizontally`일 때만 딱 한 번
 * 미러링한다. Standard 경로에서는 콘텐츠가 정방향으로 읽히려면 이 출처들의 총 적용 횟수가 짝수여야
 * 하므로, 총 횟수가 홀수가 되는 조합에서만 참을 반환한다.
 *
 * 3D mesh 경로도 같은 짝수 규칙을 따르지만 미러 출처의 조합이 다르다. mesh는 `mirrorHorizontally`
 * 일 때 한 번, 뒷면 strip은 목적지가 소스와 반대로 흐르므로 자신의 음수 `scaleX`로 또 한 번
 * 미러링한다. 네 조합의 합계는 이렇게 갈린다:
 *
 * | face / leafHome | mesh 미러 | strip scaleX | 콘텐츠 미러 |
 * |---|---|---|---|
 * | Front / Right | 없음 | 양수 | 불필요 |
 * | Back / Right | 없음 | 음수 | **필요** |
 * | Front / Left | 있음 | 양수 | **필요** |
 * | Back / Left | 있음 | 음수 | 불필요 |
 *
 * 즉 3D에서는 `face == Back`과 `leafHome == Left`가 서로 다를 때만 콘텐츠 미러가 필요하다. turn
 * 도중 뒷면 strip이 좌우로 뒤집혀 배치되는 것 자체는 종이 한 장이 실제로 뒤집히는 모습이며 의도된
 * 결과이고, 콘텐츠 미러는 그 배치 미러를 상쇄해 인쇄된 면이 정방향으로 읽히게 하는 몫이다.
 *
 * @param style 앞면/뒷면을 표준 fold로 그릴지 3D mesh로 그릴지.
 * @param face 렌더링할 면.
 * @param leafHome 접히는 leaf가 사는 pane. 앞뒷면 모두 이 pane 노드 하나에 호스트되므로 판정 기준은
 *   렌더링 pane이 아니라 leaf의 home이다.
 * @return 콘텐츠 쪽에 미러를 한 번 더 적용해야 정방향으로 읽히면 참.
 */
internal fun foundationReferenceSpreadFaceNeedsContentMirror(
    style: FoundationReferenceCurlStyle,
    face: FoundationReferenceSpreadFace,
    leafHome: FoundationReferenceSpreadPane,
): Boolean = when (style) {
    FoundationReferenceCurlStyle.Standard ->
        face == FoundationReferenceSpreadFace.Back && leafHome == FoundationReferenceSpreadPane.Right
    FoundationReferenceCurlStyle.ThreeDimensional ->
        (face == FoundationReferenceSpreadFace.Back) !=
            (leafHome == FoundationReferenceSpreadPane.Left)
}

/**
 * curl 기하 계산이 페이지 한 장으로 취급하는 크기로, viewport가 이미 한 축의 canonical(가로 우선)
 * 방향으로 축소된 것을 전제로 한다.
 *
 * spread 밖에서는 leaf가 pane 전체이므로 [canonicalSize]가 그대로 통과한다. spread 안에서는 왼쪽이
 * 아닌 pane만 실제로 넘어가므로 leaf가 더 좁다: [foundationReferenceSpreadPaneWidth]가 계산하는
 * 오른쪽 pane 몫을 그대로 쓴다.
 *
 * @param canonicalSize 축의 canonical(가로 우선) 방향으로 나타낸 viewport 크기.
 * @param isSpread pager가 두 pane을 나란히 보여주고 있는지 여부.
 * @param gutterPx pane 사이의 간격, 픽셀 단위.
 * @param leftWeight spread의 너비 중 왼쪽 pane에 주어지는 비율; leaf가 나머지를 갖기 전에 0..1로
 *   clamp된다.
 * @return curl edge, fold, hit-testing 계산이 페이지 한 장으로 사용해야 할 크기.
 */
internal fun foundationReferenceLeafSize(
    canonicalSize: IntSize,
    isSpread: Boolean,
    gutterPx: Float,
    leftWeight: Float,
): IntSize {
    if (!isSpread) return canonicalSize
    val paneWidths = foundationReferenceSpreadPaneWidth(
        canonicalWidth = canonicalSize.width.toFloat(),
        gutterPx = gutterPx,
        leftWeight = leftWeight,
    )
    return IntSize(paneWidths.rightPx, canonicalSize.height)
}

/**
 * pager 자체의 페이지별 배치를 되돌려, 세 페이지짜리 window 안의 모든 페이지가 나란히가 아니라 정확히
 * 같은 화면 사각형에 놓이도록 한다.
 *
 * 이 pager의 현재 페이지는 실제로는 전혀 움직이지 않는데도, [HorizontalPager]/[VerticalPager]는
 * 스크롤 축을 따라 N번째 페이지를 N 페이지-크기만큼 떨어진 곳에 배치한다 — curl은 세 페이지(이전,
 * 현재, 다음) 모두 같은 위치에 겹쳐 있어야, [foundationReferenceCurlZIndex]가 스크롤 오프셋이 아니라
 * 깊이로 이들을 합성할 수 있다.
 *
 * @receiver [foundationReferenceCurlZIndex]와 curl 그리기가 적용되기 전, 페이지 자신의 modifier
 *   체인.
 * @param axis pager가 어느 화면 축을 따라 스크롤하는지, 그래야 알맞은 translation이 상쇄된다.
 * @param pageOffset pager의 고정된 가운데 페이지로부터 이 페이지의 오프셋(-1, 0, 또는 +1).
 */
private fun Modifier.foundationCancelPagerPlacement(
    axis: FoundationReferenceCurlAxis,
    pageOffset: Int,
): Modifier = graphicsLayer {
    if (axis == FoundationReferenceCurlAxis.Horizontal) {
        translationX = -pageOffset * size.width
    } else {
        translationY = -pageOffset * size.height
    }
}

/**
 * pager의 고정된 세 슬롯 window에 대한 쌓임 순서: 이전 페이지가 가장 위, 현재 페이지가 가운데, 다음
 * 페이지가 가장 아래.
 *
 * curl fold는 이전과 현재 슬롯에서만 그려진다 — [FoundationPagerCurlReferenceImpl]의 페이지
 * 콘텐츠에서 다음 슬롯의 `leafEdge`는 항상 null이다 — 그래서 둘 중 실제로 접히고 있는 쪽이 자신이
 * 드러내는 페이지 위에 놓여야 하며, 이는 양쪽 방향 모두에서 그렇다; 이는 어떤 turn이 진행 중이든
 * 이 순서가 고정되어 있을 때만 성립한다.
 *
 * @param pageOffset pager의 고정된 가운데 페이지로부터 이 슬롯의 오프셋(-1, 0, 또는 +1).
 * @return -1이 가장 위로, +1이 가장 아래로 정렬되는 z-index.
 */
internal fun foundationReferenceCurlZIndex(pageOffset: Int): Float =
    (1 - pageOffset).toFloat()

/**
 * 진행 중인 하나의 드래그 제스처가 무엇을 하고 있는지로, 드래그가 시작될 때 한 번 결정되어 나머지
 * 기간 동안 그대로 읽힌다.
 *
 * [detectFoundationReferenceCurlGestures]는 첫 포인터 이동이 있기 전에 [direction]을, 그리고 그로부터
 * pager의 두 curl animatable 중 어느 쪽이 적용되는지([edge])를 해석한다; 이 선택을 여기 묶어 두면
 * 나머지 제스처 — 드래그, fling, 취소 — 는 이를 다시 도출하거나 제스처 도중 어느 animatable이
 * 살아있는지에 대해 서로 다르게 판단할 위험을 질 필요가 없다.
 *
 * @property direction 이 드래그가 페이지를 어느 방향으로 넘기고 있는지.
 * @property edge 이 제스처가 구동하는 animatable — [direction]에 따라
 *   [FoundationPagerCurlReferenceImpl]의 forward 또는 backward edge.
 * @property start 취소된 드래그가 되돌아가며 애니메이션할 edge.
 * @property end 성공한 드래그가 애니메이션해 도달하는, turn을 완료시키는 edge.
 */
private data class FoundationReferenceDragConfig(
    val direction: FoundationReferenceCurlDirection,
    val edge: Animatable<FoundationReferenceCurlEdge, AnimationVector4D>,
    val start: FoundationReferenceCurlEdge,
    val end: FoundationReferenceCurlEdge,
)

internal data class FoundationReferenceActiveDrag(
    val direction: FoundationReferenceCurlDirection,
    val edge: FoundationReferenceCurlEdge,
)

/** 포인터 이벤트가 이 값을 동기적으로 교체한다; 드래그-프레임 코루틴이나 spring이 만들어지지 않는다. */
internal fun foundationReferenceUpdateDragEdge(
    direction: FoundationReferenceCurlDirection,
    target: FoundationReferenceCurlEdge,
) = FoundationReferenceActiveDrag(direction, target)

/**
 * 첫 터치부터 해석된 결과 — fling으로 완료, 임계값을 넘겨 드래그, 또는 되돌아가기 — 까지 하나의
 * 페이지 turn 드래그를 구동한다.
 *
 * 방향과 애니메이션할 edge는 드래그가 시작되는 순간, 터치 슬롭 변위만으로 [FoundationReferenceDragConfig]에
 * 고정된다; 그 이후로는 제스처의 어떤 것도 어느 animatable이 구동되는지를 바꿀 수 없다. 그 첫 결정 이후에
 * 관측되는 모든 포인터 위치는 [foundationReferenceCurlLeafOffset]을 통해 leaf 자신의 좌표계로 —
 * fold 기하 계산이 이해하는 유일한 좌표계로 — 변환되며, 그래서 spread의 더 좁은 leaf도 같은 손가락
 * 움직임에 대해 단일 pane과 동일하게 정규화된 이동량을 따라간다.
 *
 * 손을 뗄 때, 기록된 속도는 spline decay로 앞쪽으로 투영되어 손가락의 fling이 어디에 도달했을지를
 * 찾아내고, [foundationReferenceCurlDragSucceeds]는 그 투영된 지점을 페이지 turn 임계값에 대고
 * 판단한다 — 그래서 같은 거리를 이동한 느린 드래그는 완료하지 못하는 turn을, 빠르고 짧은 flick은
 * 완료할 수 있으며, 이는 실제 페이지 넘김이 flick과 느린 밀기에 다르게 반응하는 것과 일치한다.
 *
 * @receiver 제스처 감지와 [splineBasedDecay]가 필요로 하는 코루틴 컨텍스트를 제공하는 pointer input
 *   scope.
 * @param axis pager가 가로로 넘어가는지 세로로 넘어가는지.
 * @param canonicalSize 축의 canonical 방향으로 나타낸 leaf의 크기.
 * @param isSpread pager가 두 pane을 나란히 보여주고 있는지 여부.
 * @param leafScale spread에서 전체 viewport 포인터 이동량 중 leaf 너비 하나만큼의 fold 진행률에
 *   대응하는 비율; spread 밖에서는 쓰이지 않는다.
 * @param leafWidth leaf의 너비로, spread에서 뒤로 가는 드래그의 이동량을 미러링하는 데 쓰인다.
 * @param scope fold 애니메이션이 실행되는 코루틴 scope.
 * @param forwardEdge 앞으로 가는 turn을 구동하는 animatable.
 * @param backwardEdge 뒤로 가는 turn을 구동하는 animatable.
 * @param canGoForward 넘어갈 다음 페이지가 존재하는지 여부.
 * @param canGoBackward 넘어갈 이전 페이지가 존재하는지 여부.
 * @param onDragStart 드래그가 유효한 turn 제스처로 인식되면, fold 애니메이션의 첫 프레임 전에 한 번
 *   호출된다.
 * @param onComplete fold 애니메이션이 완료된 turn을 끝내면, 해석된 방향과 함께 호출된다.
 * @param style 적용 중인 curl 프로필; 3D style은 드래그를 가로 우세 움직임으로 고정하고 crease를
 *   포인터 x만으로 구동하며, 표준 style은 corner-peel curl을 유지한다.
 */
private suspend fun PointerInputScope.detectFoundationReferenceCurlGestures(
    axis: FoundationReferenceCurlAxis,
    canonicalSize: IntSize,
    isSpread: Boolean,
    leafScale: Float,
    leafWidth: Float,
    scope: CoroutineScope,
    forwardEdge: Animatable<FoundationReferenceCurlEdge, AnimationVector4D>,
    backwardEdge: Animatable<FoundationReferenceCurlEdge, AnimationVector4D>,
    canGoForward: Boolean,
    canGoBackward: Boolean,
    onDragStart: () -> Unit,
    onDragEdgeChange: (FoundationReferenceActiveDrag?) -> Unit,
    onComplete: (FoundationReferenceCurlDirection) -> Unit,
    style: FoundationReferenceCurlStyle,
) {
    val velocityTracker = VelocityTracker()
    var config: FoundationReferenceDragConfig? = null
    var startOffset = Offset.Zero
    var currentDragEdge: FoundationReferenceCurlEdge? = null

    detectFoundationReferenceCustomDragGestures(
        onDragStart = { start, current ->
            val direction = if (style == FoundationReferenceCurlStyle.ThreeDimensional) {
                foundationReferenceThreeDCurlDirection(
                    start = axis.toCanonical(start),
                    current = axis.toCanonical(current),
                    canGoBackward = canGoBackward,
                    canGoForward = canGoForward,
                )
            } else {
                foundationReferenceCurlDirection(
                    start = axis.toCanonical(start),
                    current = axis.toCanonical(current),
                    canGoBackward = canGoBackward,
                    canGoForward = canGoForward,
                )
            }
            startOffset = direction?.let {
                foundationReferenceCurlLeafOffset(start, axis, it, isSpread, leafScale, leafWidth)
            } ?: Offset.Zero
            config = direction?.let {
                val geometryDirection = foundationReferenceCurlGeometryDirection(it, isSpread)
                val edge = if (it == FoundationReferenceCurlDirection.Forward) forwardEdge else backwardEdge
                if (geometryDirection == FoundationReferenceCurlDirection.Forward) {
                    FoundationReferenceDragConfig(it, edge, FoundationReferenceCurlEdge.right(canonicalSize), FoundationReferenceCurlEdge.left(canonicalSize))
                } else {
                    FoundationReferenceDragConfig(it, edge, FoundationReferenceCurlEdge.left(canonicalSize), FoundationReferenceCurlEdge.right(canonicalSize))
                }
            }
            if (config != null) {
                onDragStart()
                currentDragEdge = config?.start
                onDragEdgeChange(
                    currentDragEdge?.let { edge ->
                        foundationReferenceUpdateDragEdge(requireNotNull(direction), edge)
                    },
                )
            }
            config != null
        },
        onDragEnd = { endOffset, complete ->
            config?.let { dragConfig ->
                val velocity = velocityTracker.calculateVelocity().let {
                    axis.toCanonical(Offset(it.x, it.y))
                }
                val decay = splineBasedDecay<Offset>(this@detectFoundationReferenceCurlGestures)
                val canonicalEnd = foundationReferenceCurlLeafOffset(
                    endOffset,
                    axis,
                    dragConfig.direction,
                    isSpread,
                    leafScale,
                    leafWidth,
                )
                val flingEnd = decay.calculateTargetValue(
                    Offset.VectorConverter,
                    canonicalEnd,
                    velocity,
                ).let {
                    Offset(
                        it.x.coerceIn(0f, canonicalSize.width.toFloat() - 1f),
                        it.y.coerceIn(0f, canonicalSize.height.toFloat() - 1f),
                    )
                }
                val releaseEdge = currentDragEdge ?: dragConfig.edge.value
                scope.launch {
                    dragConfig.edge.snapTo(releaseEdge)
                    onDragEdgeChange(null)
                    if (
                        complete && foundationReferenceCurlDragSucceeds(
                            direction = foundationReferenceCurlGeometryDirection(dragConfig.direction, isSpread),
                            start = startOffset,
                            end = flingEnd,
                            size = canonicalSize,
                            axis = axis,
                        )
                    ) {
                        var completed = false
                        try {
                            dragConfig.edge.animateTo(dragConfig.end)
                            completed = true
                        } finally {
                            if (completed) {
                                onComplete(dragConfig.direction)
                            } else {
                                dragConfig.edge.snapTo(dragConfig.start)
                            }
                        }
                    } else {
                        try {
                            dragConfig.edge.animateTo(dragConfig.start)
                        } finally {
                            dragConfig.edge.snapTo(dragConfig.start)
                        }
                    }
                }
            }
        },
        onDrag = { change, _ ->
            val dragConfig = config ?: return@detectFoundationReferenceCustomDragGestures
            val current = foundationReferenceCurlLeafOffset(
                change.position,
                axis,
                dragConfig.direction,
                isSpread,
                leafScale,
                leafWidth,
            )
            velocityTracker.addPosition(change.uptimeMillis, current)
            val target = if (style == FoundationReferenceCurlStyle.ThreeDimensional) {
                foundationReferenceThreeDCurlDragEdge(
                    size = canonicalSize,
                    start = startOffset,
                    current = current,
                    direction = foundationReferenceCurlGeometryDirection(
                        dragConfig.direction,
                        isSpread,
                    ),
                )
            } else {
                foundationReferenceCurlEdge(canonicalSize, startOffset, current)
            }
            currentDragEdge = target
            onDragEdgeChange(foundationReferenceUpdateDragEdge(dragConfig.direction, target))
        },
    )
}

/**
 * Compose Foundation 자체의 드래그 제스처 감지기와 같은 모양의 press-drag-release 루프이지만,
 * [onDragStart]가 제스처를 거부할 수 있다.
 *
 * 표준 감지기는 터치 슬롭을 넘기면 언제나 드래그를 받아들이고 시작 콜백에서 아무것도 반환하지
 * 않는다; 이것은 animatable을 구동하거나 부수 효과를 일으키기로 확정하기 전에 [onDragStart]가
 * 터치 슬롭 변위를 살펴보고 그것이 유효한 페이지 turn 시도이기라도 한지(그 방향에 넘어갈 페이지가
 * 없을 수도 있다) 답해야 한다. 거부된 시작은 [onDrag]나 [onDragEnd]를 전혀 호출하지 않고 빠져나간다.
 *
 * @receiver 이 제스처 루프가 감지되는 pointer input scope.
 * @param onDragStart 눌린 위치와 터치 슬롭을 넘긴 뒤의 위치를 받는다; 드래그를 진행해야 하는지
 *   여부를 반환한다.
 * @param onDragEnd 받아들여진 제스처 하나마다 한 번, 마지막으로 알려진 위치와 정상적인
 *   pointer-up(`true`)으로 끝났는지 취소(`false`)로 끝났는지와 함께 호출된다.
 * @param onDrag 시작 이후의 모든 드래그 위치마다, 터치 슬롭 오프셋 자체를 포함해 호출된다.
 */
private suspend fun PointerInputScope.detectFoundationReferenceCustomDragGestures(
    onDragStart: (Offset, Offset) -> Boolean,
    onDragEnd: (Offset, Boolean) -> Unit,
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var drag: PointerInputChange?
        var overSlop = Offset.Zero
        do {
            drag = awaitTouchSlopOrCancellation(down.id) { change, over ->
                change.consume()
                overSlop = over
            }
        } while (drag != null && !drag.isConsumed)
        if (drag != null) {
            if (!onDragStart(down.position, drag.position)) return@awaitEachGesture
            onDrag(drag, overSlop)
            val completed = drag(drag.id) {
                drag = it
                onDrag(it, it.positionChange())
                it.consume()
            }
            onDragEnd(drag.position, completed)
        }
    }
}

/**
 * Compose의 탭/더블 탭 감지를 [foundationReferenceCurlTapAction]의 영역 판단에 연결한다.
 *
 * [detectFoundationReferenceCurlGestures]와 분리되어 있는 이유는 탭과 드래그가 pager modifier
 * 위의 서로 독립된 두 `pointerInput` 블록으로 인식되기 때문이며([FoundationPagerCurlReferenceImpl]
 * 참고), 그래서 터치 슬롭을 전혀 넘지 않은 탭도 이 감지기까지 도달한다.
 *
 * @receiver 이 제스처가 감지되는 pointer input scope.
 * @param axis pager가 가로로 넘어가는지 세로로 넘어가는지.
 * @param canGoForward 넘어갈 다음 페이지가 존재하는지 여부.
 * @param canGoBackward 넘어갈 이전 페이지가 존재하는지 여부.
 * @param isAutoScrollEnabled 자동 스크롤이 실행 중인지 여부로, 실행 중이면 어떤 탭이든 컨트롤을
 *   토글한다.
 * @param onPageTap 탭으로 촉발된 페이지 turn을 애니메이션할 방향과 함께 호출된다.
 * @param onToggleControls 탭이 페이지를 넘기는 대신 리더의 컨트롤을 보이거나 숨겨야 할 때 호출된다.
 * @param onDoubleTap Compose의 탭 감지기로 그대로 전달된다; null이면 더블 탭 처리를 비활성화한다.
 */
private suspend fun PointerInputScope.detectFoundationReferenceCurlTaps(
    axis: FoundationReferenceCurlAxis,
    canGoForward: Boolean,
    canGoBackward: Boolean,
    isAutoScrollEnabled: Boolean,
    onPageTap: (FoundationReferenceCurlDirection) -> Unit,
    onToggleControls: () -> Unit,
    onDoubleTap: ((Offset) -> Unit)?,
) {
    detectTapGestures(
        onDoubleTap = onDoubleTap,
        onTap = { position ->
            when (
                foundationReferenceCurlTapAction(
                    position = position,
                    size = size,
                    axis = axis,
                    canGoBackward = canGoBackward,
                    canGoForward = canGoForward,
                    isAutoScrollEnabled = isAutoScrollEnabled,
                )
            ) {
                FoundationReferenceCurlTapAction.Backward -> onPageTap(FoundationReferenceCurlDirection.Backward)
                FoundationReferenceCurlTapAction.ToggleControls -> onToggleControls()
                FoundationReferenceCurlTapAction.Forward -> onPageTap(FoundationReferenceCurlDirection.Forward)
            }
        },
    )
}

/**
 * curl pager 위의 탭이 무엇을 해야 하는지로, 오직 탭 위치만으로 결정된다.
 *
 * 넘어갈 페이지가 없는 가장자리 영역 — 책의 첫 페이지나 마지막 페이지 — 에서의 탭은 가운데 영역의
 * 탭과 똑같이 컨트롤 토글로 넘어간다. 이 규칙이 존재하는 이유는 이것이 없으면 출시된 버그였기
 * 때문이다: 예전에는 마지막 페이지에서의 탭이 아무 일도 하지 않아서, 독자가 책의 끝인지 삼켜진
 * 탭인지 구분할 수 없었다. 자동 스크롤 도중의 페이지 turn은 스크롤과 충돌하므로, 자동 스크롤은
 * 같은 토글로 곧바로 넘어간다.
 *
 * @param position pane 자신의 좌표계 기준 탭 위치.
 * @param size [axis]와 함께 하나의 canonical 축으로 축소하는 데 쓰이는 pane의 크기.
 * @param axis 이 pager가 어느 방향으로 넘어가는지.
 * @param canGoBackward 현재 페이지 뒤에 페이지가 존재하는지 여부.
 * @param canGoForward 현재 페이지 앞에 페이지가 존재하는지 여부.
 * @param isAutoScrollEnabled 자동 스크롤이 실행 중인지 여부로, 실행 중이면 어떤 탭이든 컨트롤을
 *   토글한다.
 * @return 취해야 할 동작; "아무 것도 하지 않음"은 절대 없다.
 */
internal fun foundationReferenceCurlTapAction(
    position: Offset,
    size: IntSize,
    axis: FoundationReferenceCurlAxis,
    canGoBackward: Boolean,
    canGoForward: Boolean,
    isAutoScrollEnabled: Boolean = false,
): FoundationReferenceCurlTapAction {
    val primary = axis.toCanonical(position).x
    val extent = axis.canonicalSize(size).width
    if (isAutoScrollEnabled) return FoundationReferenceCurlTapAction.ToggleControls
    return when {
        primary < extent * FoundationReferencePreviousTapZoneRatio ->
            if (canGoBackward) FoundationReferenceCurlTapAction.Backward else FoundationReferenceCurlTapAction.ToggleControls
        primary > extent * FoundationReferenceNextTapZoneRatio ->
            if (canGoForward) FoundationReferenceCurlTapAction.Forward else FoundationReferenceCurlTapAction.ToggleControls
        else -> FoundationReferenceCurlTapAction.ToggleControls
    }
}

/**
 * 드래그의 초기 변위가 페이지를 어느 방향으로 넘기려는 것인지, 또는 그 방향에 갈 곳이 없으면 null.
 *
 * canonical(가로 우선) 좌표계에서 [current]를 [start]와 비교하면 하나의 비교로 두 축 모두를
 * 처리할 수 있다: pager가 가로로 배치되었는지 세로로 배치되었는지와 무관하게, 축의 시작 쪽으로
 * 드래그하면 앞으로 가는 turn이고 끝 쪽으로 드래그하면 뒤로 가는 turn이다.
 * [canGoForward]/[canGoBackward]가 그 방향을 배제할 때 null을 반환하는 것은
 * [detectFoundationReferenceCustomDragGestures]의 `onDragStart`가 갈 곳 없는 turn을
 * 애니메이션하는 대신 첫 페이지나 마지막 페이지에서의 드래그를 거부할 수 있게 해준다.
 *
 * @param start 드래그의 시작 위치, canonical 좌표계 기준.
 * @param current 터치 슬롭을 넘긴 뒤의 드래그 위치, canonical 좌표계 기준.
 * @param canGoBackward 이전 페이지가 존재하는지 여부.
 * @param canGoForward 다음 페이지가 존재하는지 여부.
 * @return [FoundationReferenceCurlDirection.Forward] 또는
 *   [FoundationReferenceCurlDirection.Backward], 또는 지시된 방향에 넘어갈 페이지가 없으면 null.
 */
internal fun foundationReferenceCurlDirection(
    start: Offset,
    current: Offset,
    canGoBackward: Boolean,
    canGoForward: Boolean,
): FoundationReferenceCurlDirection? = when {
    current.x < start.x && canGoForward -> FoundationReferenceCurlDirection.Forward
    current.x > start.x && canGoBackward -> FoundationReferenceCurlDirection.Backward
    else -> null
}

/**
 * 리더에 설정된 [pageTurnMode]와 선택된 [style]이 주어졌을 때, curl이 실제로 넘어가는 스와이프 축.
 *
 * Play Books 스타일 [FoundationReferenceCurlStyle.ThreeDimensional]은 거의 수직에 가까운 spine을
 * 중심으로 leaf 하나를 굴리는데, 이는 좌우 페이지 turn으로만 올바르게 읽히는 움직임이다; 이를
 * 세로 스와이프에 강제로 맞추면 leaf가 자신이 굴러가는 방향에 반대로 접히게 되므로, 이 style은
 * [pageTurnMode]와 무관하게 [FoundationReferenceCurlAxis.Horizontal]에 고정된다.
 * [FoundationReferenceCurlStyle.Standard] curl에는 그런 제약이 없어 리더가 선택한 방향을 계속
 * 따른다.
 *
 * @param pageTurnMode 리더에 설정된 turn 방향.
 * @param style 적용 중인 curl 페인팅/인터랙션 프로필.
 * @return 3D style이거나 가로 [pageTurnMode]면 [FoundationReferenceCurlAxis.Horizontal], 세로
 *   [pageTurnMode]에서 표준 style일 때만 [FoundationReferenceCurlAxis.Vertical].
 */
internal fun foundationReferenceCurlAxis(
    pageTurnMode: PageTurnMode,
    style: FoundationReferenceCurlStyle,
): FoundationReferenceCurlAxis = when {
    style == FoundationReferenceCurlStyle.ThreeDimensional -> FoundationReferenceCurlAxis.Horizontal
    pageTurnMode == PageTurnMode.HORIZONTAL -> FoundationReferenceCurlAxis.Horizontal
    else -> FoundationReferenceCurlAxis.Vertical
}

/**
 * 3D curl 드래그가 페이지를 어느 방향으로 넘기려는 것인지로, 우발적인 세로 흔들림이 아니라 명확한
 * 좌우 스와이프일 때만 제스처를 받아들인다.
 *
 * Play Books 스타일 롤은 구조상 가로 방향이므로([foundationReferenceCurlAxis] 참고), 대부분 세로로
 * 움직인 드래그는 애초에 페이지 turn이 아니며 시작되어서도 안 된다 — 이는 세로 이동량이 가로
 * 이동량을 압도하는 모든 제스처를 거부한다(`abs(dy) >= abs(dx)`이며, 이는 순수한 세로 드래그나
 * 움직임이 없는 드래그도 함께 거부한다). 드래그가 가로 우세로 판명되면
 * [foundationReferenceCurlDirection]과 같은 가용성 의미론을 따른다: 왼쪽은 앞으로 가는 turn,
 * 오른쪽은 뒤로 가는 turn이며, 어느 쪽이든 그 방향에 넘어갈 페이지가 없으면 null로 해석된다.
 *
 * @param start 드래그의 시작 위치, canonical 좌표계 기준.
 * @param current 터치 슬롭을 넘긴 뒤의 드래그 위치, canonical 좌표계 기준.
 * @param canGoBackward 이전 페이지가 존재하는지 여부.
 * @param canGoForward 다음 페이지가 존재하는지 여부.
 * @return 해석된 turn 방향, 또는 드래그가 가로 우세가 아니거나 암시된 방향에 갈 곳이 없으면 null.
 */
internal fun foundationReferenceThreeDCurlDirection(
    start: Offset,
    current: Offset,
    canGoBackward: Boolean,
    canGoForward: Boolean,
): FoundationReferenceCurlDirection? {
    val dx = current.x - start.x
    val dy = current.y - start.y
    if (abs(dx) <= abs(dy)) return null
    return foundationReferenceCurlDirection(start, current, canGoBackward, canGoForward)
}

/**
 * [current]에 있는 포인터에 대한 3D curl의 롤링 crease edge로, 오직 x 위치만으로 구동된다.
 *
 * Play Books 스타일 롤은 손가락이 가로로 움직이는 동안 crease를 거의 수직으로 유지하며 페이지를
 * 가로질러 쓸어가므로, 이는 [current]의 y를 완전히 무시한다 — 드래그의 시작 높이를 중심으로
 * 피벗하는 [foundationReferenceCurlEdge]의 corner-peel 구성과 달리, 같은 포인터 x는 페이지
 * 위쪽에서도 아래쪽에서도 같은 crease를 만들어낸다. 두 정확한 끝점은 렌더러의 평평한 정지 edge와
 * 일치하므로, 완전히 쓸린 crease는 그리기 경로에 조기 반환이 이미 단락시켜 놓은 바로 그
 * [FoundationReferenceCurlEdge.left]/[FoundationReferenceCurlEdge.right] 값을 넘겨준다: 왼쪽
 * edge에 닿거나 지난 x는 [FoundationReferenceCurlEdge.left](완전히 말림), 오른쪽 edge에 닿거나
 * 지난 x는 [FoundationReferenceCurlEdge.right](정지 상태)이다.
 *
 * 그 양극단 사이에서 crease는 [current]의 x를 중심으로 한 하나의 거의 수직인 선이며, 더 짧은 leaf
 * 변의 [FoundationReferenceThreeDCurlTiltRatio]만큼 기울어져 있고, 스윕 중간에서 정점을 찍고 양쪽
 * edge에서 0으로 돌아오는 사인 곡선으로 스케일된다. 이 기울기는 내부 crease가 퇴화되지 않도록
 * 유지하며(위/아래 x가 서로 다르다), 평평한 수직 띠 대신 눈에 보이는 기울어짐을 롤에 부여하는 한편,
 * 끝점에서 사라지는 성질은 그것들을 평평한 정지 edge와 정확히 같게 유지한다.
 *
 * @param size 축의 canonical 좌표계 기준 leaf의 크기; 너비는 스윕을 제한하고 높이는 crease가
 *   걸치는 범위다.
 * @param current 포인터 위치; x만 읽힌다.
 * @return 이 포인터 x에 대한 crease edge, 또는 양 끝점에서는 정확히 평평한 정지 edge.
 */
internal fun foundationReferenceThreeDCurlEdge(
    size: IntSize,
    current: Offset,
): FoundationReferenceCurlEdge {
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    val x = current.x
    if (x <= 0f) return FoundationReferenceCurlEdge.left(size)
    if (x >= width) return FoundationReferenceCurlEdge.right(size)
    val tilt = min(width, height) * FoundationReferenceThreeDCurlTiltRatio *
        sin(PI.toFloat() * x / width)
    return FoundationReferenceCurlEdge(
        top = Offset(x - tilt, 0f),
        bottom = Offset(x + tilt, height),
    )
}

/**
 * 포인터의 절대 터치 위치가 초기 변형을 결정하지 않도록, 가로 드래그 이동량을 3D 롤 edge로
 * 변환한다.
 *
 * Play Books 스타일 스와이프는 손가락이 닿은 곳이 어디든 평평한 leaf에서 시작한다. 앞으로 가는
 * 이동은 crease를 오른쪽 정지 edge에서 왼쪽으로 옮기고; 뒤로 가는 이동은 왼쪽 정지 edge에서
 * 오른쪽으로 옮긴다. 터치 지점을 지나쳐 되돌아가면
 * [foundationReferenceThreeDCurlEdge]의 끝점 clamp를 통해 그 방향의 정지 edge에 머무른다.
 * spread 호출자는 자신이 해석한 기하 방향을 넘기므로, 뒤로 가는 spread도 여전히 앞으로 가는
 * 기하로 바깥쪽 leaf를 접는다.
 *
 * @param size canonical 좌표계 기준 leaf의 크기.
 * @param start 드래그가 시작될 때 leaf 좌표계로 매핑된 포인터 위치.
 * @param current 같은 leaf 좌표계 기준 현재 포인터 위치; y는 무시된다.
 * @param direction 렌더링할 fold 기하 방향.
 * @return 드래그의 가로 변위가 도달한 롤링 crease.
 */
internal fun foundationReferenceThreeDCurlDragEdge(
    size: IntSize,
    start: Offset,
    current: Offset,
    direction: FoundationReferenceCurlDirection,
): FoundationReferenceCurlEdge {
    val x = when (direction) {
        FoundationReferenceCurlDirection.Forward -> size.width.toFloat() - (start.x - current.x)
        FoundationReferenceCurlDirection.Backward -> current.x - start.x
    }
    return foundationReferenceThreeDCurlEdge(size, Offset(x, 0f))
}

/**
 * 포인터 위치를 실제로 접히는 leaf의 좌표 공간으로 매핑한다.
 *
 * spread는 전체 viewport 이동량을 이동시키는 대신 더 좁은 leaf로 스케일하므로, viewport 시작
 * edge에 있는 포인터는 항상 "완전히 접힘"을, viewport 끝 edge는 항상 "정지 상태"를 의미한다 —
 * 단일 pane curl이 만들어내는 것과 같은 정규화된 진행률이다. 대신 이동시켰다면 마주보는 pane
 * 전체가 접힌 극단에 고정되어 드래그 시작 시 fold가 튀어 보였을 것이다.
 */
internal fun foundationReferenceCurlLeafOffset(
    offset: Offset,
    axis: FoundationReferenceCurlAxis,
    direction: FoundationReferenceCurlDirection,
    isSpread: Boolean,
    leafScale: Float,
    leafWidth: Float,
): Offset {
    val canonical = axis.toCanonical(offset)
    val x = when {
        !isSpread -> canonical.x
        direction == FoundationReferenceCurlDirection.Forward -> canonical.x * leafScale
        else -> leafWidth - canonical.x * leafScale
    }
    return Offset(x, canonical.y)
}

/**
 * 페이지 turn 방향을 그것을 렌더링해야 할 fold 모양으로 매핑하며, pager가 spread일 때는 항상 뒤로
 * 가는 방향을 앞으로 가는 방향으로 접어 넣는다.
 *
 * spread 밖에서는 두 방향이 서로 반대쪽 edge에서 접힌다 — 앞으로 가는 turn은 오른쪽에서 벗겨지고,
 * 뒤로 가는 turn은 왼쪽에서 벗겨진다 — 그래서 기하 방향은 [direction]과 일대일로 일치한다. spread
 * 안에서는 바깥쪽(오른쪽) leaf만 접히므로, 거기서의 뒤로 가는 turn은 같은 leaf가 앞으로 접히는
 * 것으로 렌더링된다([FoundationPagerCurlReferenceImpl]의 정지·종료 edge를 참고, 이 경우 둘 다
 * leaf의 오른쪽/왼쪽에 고정되어 있다), 그래서 페이지 내비게이션 자체는 뒤로 가는 것이더라도 그
 * fold 모양, 그림자, 탭-애니메이션 spec은 모두 앞으로 가는 기하를 써야 한다.
 *
 * @param direction 독자가 경험하는 대로의 페이지 turn 방향.
 * @param isSpread pager가 두 pane을 나란히 보여주고 있는지 여부.
 * @return 이 turn에 대해 실제로 그려야 할 fold 기하의 방향.
 */
internal fun foundationReferenceCurlGeometryDirection(
    direction: FoundationReferenceCurlDirection,
    isSpread: Boolean,
): FoundationReferenceCurlDirection =
    if (isSpread && direction == FoundationReferenceCurlDirection.Backward) {
        FoundationReferenceCurlDirection.Forward
    } else {
        direction
    }

/**
 * 이 페이지 슬롯을 위해 실제로 그릴 fold edge로, 낡은 애니메이션이 자신이 넘긴 적도 없는 콘텐츠
 * 위로 번지게 두는 대신 그것을 버린다.
 *
 * [pageKey]는 [FoundationPagerCurlReferenceImpl]의 page-key `LaunchedEffect`가 animatable을
 * 재설정하고 [renderedPageKey]를 따라잡을 기회를 갖기 전에 바뀔 수 있다 — 프로그래밍적 점프가
 * 가장 명확한 경우다. 그 틈에서 [animatedEdge]는 여전히 이전 페이지의 turn이 남겨 놓은 fold
 * 상태를 그대로 갖고 있다; 이를 여기서 그리면 새 페이지의 콘텐츠 위로, 시작했어야 할 평평한 정지
 * 상태 대신 남아 있던 fold가 잠깐 스쳐 지나가게 된다.
 *
 * @param pageKey 현재 요청된 페이지.
 * @param renderedPageKey fold animatable이 마지막으로 재설정되었던 페이지.
 * @param animatedEdge animatable의 실시간 값.
 * @param restingEdge 애니메이션을 신뢰할 수 없을 때 대신 쓸 평평한 edge.
 * @return 현재 페이지에 속한다고 알려져 있는 동안은 [animatedEdge], 그렇지 않으면 [restingEdge].
 */
internal fun foundationReferenceVisibleCurlEdge(
    pageKey: Int,
    renderedPageKey: Int,
    animatedEdge: FoundationReferenceCurlEdge,
    restingEdge: FoundationReferenceCurlEdge,
): FoundationReferenceCurlEdge =
    if (pageKey == renderedPageKey) animatedEdge else restingEdge

/**
 * -1 오프셋(이전 페이지) pager 슬롯이 자신의 spread 렌더링을 건너뛰어야 하는지 판정한다.
 *
 * 후진 turn이 진행되지 않는 동안에는 이 슬롯이 자기 뒷면을 현재 페이지 위에 덮는 것을 막아야
 * 하고, 전진 turn이 진행되는 동안에는 이 슬롯이 전진 fold를 가리는 것을 막아야 한다. 두 조건 중
 * 하나라도 해당하면 건너뛴다.
 *
 * @param isSpread pager가 두 pane을 나란히 보여주고 있는지 여부; -1 슬롯 스킵은 spread에서만
 *   의미가 있다.
 * @param pageOffset 이 슬롯이 현재 페이지로부터 떨어진 오프셋 — -1이 아니면 이 판정은 적용되지
 *   않는다.
 * @param isBackwardTurnResting 후진 turn의 leaf edge가 정지 위치에 머물러 있는지 여부.
 * @param isForwardTurnResting 전진 turn의 leaf edge가 정지 위치(오른쪽)에 머물러 있는지 여부.
 * @return -1 슬롯이 자신의 spread 렌더링을 건너뛰어야 하면 참.
 */
internal fun foundationReferenceSpreadShouldSkipBackwardSlot(
    isSpread: Boolean,
    pageOffset: Int,
    isBackwardTurnResting: Boolean,
    isForwardTurnResting: Boolean,
): Boolean = isSpread && pageOffset == -1 && (isBackwardTurnResting || !isForwardTurnResting)

/**
 * 완료되었거나 flung된 드래그가 [direction] 방향으로 충분히 멀리 이동해, 취소된 turn이 아니라
 * 완료된 페이지 turn으로 칠 수 있는지 여부.
 *
 * 이동량과 임계값 모두 canonical 좌표계의 [start]/[end]/[size]로부터 계산되므로, 같은
 * 비율([FoundationReferenceDragThresholdRatio])이 가로 pager와 세로 pager 모두에 똑같이
 * 적용된다. [FoundationReferenceCurlAxis.Vertical]의 경우 임계값은 canonical 너비가 아니라
 * [size]의 두 치수 중 더 작은 쪽에 대해 측정된다 — 세로 pager의 canonical 너비는 화면의 높이인데,
 * 세로로 긴 화면에서 그만큼의 이동을 요구하면 세로 turn이 가로 turn보다 훨씬 완료하기 어려워질
 * 것이다.
 *
 * @param direction 드래그가 어느 방향으로 넘기고 있었는지; 어느 부호의 이동이 전진으로 치는지를
 *   결정한다.
 * @param start 드래그의 시작 위치, canonical 좌표계 기준.
 * @param end 드래그의 최종(또는 투영된 fling) 위치, canonical 좌표계 기준.
 * @param size 드래그가 일어난 leaf의 크기, canonical 좌표계 기준.
 * @param axis pager가 가로로 넘어가는지 세로로 넘어가는지.
 * @return 방향성 이동량이 필요 거리의 [FoundationReferenceDragThresholdRatio]에 도달하면 true.
 */
internal fun foundationReferenceCurlDragSucceeds(
    direction: FoundationReferenceCurlDirection,
    start: Offset,
    end: Offset,
    size: IntSize,
    axis: FoundationReferenceCurlAxis,
): Boolean {
    val directionalTravel = when (direction) {
        FoundationReferenceCurlDirection.Forward -> start.x - end.x
        FoundationReferenceCurlDirection.Backward -> end.x - start.x
    }
    val requiredDistance = when (axis) {
        FoundationReferenceCurlAxis.Horizontal -> size.width.toFloat()
        FoundationReferenceCurlAxis.Vertical -> min(size.width, size.height).toFloat()
    }
    return directionalTravel >= requiredDistance * FoundationReferenceDragThresholdRatio
}

/**
 * [currentOffset]에 있는 손가락에 대한 fold의 crease 선으로, 고전적인 종이접기 구성으로 만들어진다:
 * 당겨지는 페이지 모서리와 손가락의 현재 위치 사이의 수직이등분선.
 *
 * 당겨지는 모서리는 제스처 내내 `(size.width, startOffset.y)` — 드래그가 시작된 높이의 위/오른쪽
 * edge — 에 고정된다; [currentOffset]만 움직인다. 그 모서리를 반환된 edge에 대해 반사하면 항상
 * 정확히 [currentOffset] 위에 떨어지는데, 이것이 바로 fold가 실제 종이가 벗겨지는 방식처럼
 * 손가락을 따라가게 만드는 원리다. [foundationReferenceCurlFold]는 crease의 실제 끝점을 얻기 위해
 * 이 선을 페이지 자신의 위/아래 edge까지 연장한다.
 *
 * @param size 축의 canonical 좌표계 기준 leaf의 크기; 너비(당겨지는 모서리의 x)만 쓰인다.
 * @param startOffset 드래그의 시작 위치, canonical 좌표계 기준; y(당겨지는 모서리의 높이)만
 *   쓰인다.
 * @param currentOffset 손가락의 현재 위치, canonical 좌표계 기준.
 * @return `top`/`bottom`이 [currentOffset]을 지나는 crease 선 위에 있고, 당겨지는 모서리에서
 *   [currentOffset]까지 이어진 선분에 수직인 edge.
 */
internal fun foundationReferenceCurlEdge(
    size: IntSize,
    startOffset: Offset,
    currentOffset: Offset,
): FoundationReferenceCurlEdge {
    val vector = Offset(size.width.toFloat(), startOffset.y) - currentOffset
    val rotatedVector = vector.foundationReferenceRotate((PI / 2).toFloat())
    return FoundationReferenceCurlEdge(
        top = currentOffset - rotatedVector,
        bottom = currentOffset + rotatedVector,
    )
}

/**
 * 탭 또는 자동 스크롤로 촉발된 페이지 turn을 위한 keyframe 애니메이션 spec으로, 두 점짜리
 * animateTo가 만들어낼 선형 edge 변형 대신 그럴듯한 curl을 거쳐 지나가도록 만들어져 있다.
 *
 * leaf의 오른쪽/왼쪽 edge를 반대쪽 끝까지 곧장 보간하면 crease가 직선으로 움직여 curl이 아니라
 * 페이지가 미끄러지는 것처럼 보일 것이다. `middle` — 중간-오른쪽/중간-아래 점에서 나온 대각선
 * crease — 을 앞으로 가는 turn 진행의 1/3 지점에서(또는 대칭적으로, 뒤로 가는 turn이 끝나기 1/3
 * 전에) 거쳐 가게 하면 fold에 실제 호 모양이 생겨, 실제 드래그로 구동되는 curl이 중간 지점에서
 * 어떻게 보이는지와 일치한다.
 *
 * @param direction 탭으로 촉발된 turn이 어느 방향으로 애니메이션되는지; crease가 어느 끝 상태로
 *   움직이는지, `middle` keyframe이 타임라인의 어느 쪽에 놓이는지를 결정한다.
 * @param size crease keyframe이 계산되는 기준이 되는 leaf 크기.
 * @param durationMillisOverride 전체 애니메이션 지속 시간; 자동 스크롤의 페이지당 지연에도
 *   재사용되므로, 양수라고 가정하는 대신 최소 1ms로 강제된다.
 * @return 위에서 설명한 모양들을 거쳐 [FoundationReferenceCurlEdge]의 `top`/`bottom`을 구동하는
 *   keyframes 애니메이션 spec.
 */
private fun foundationReferenceTapSpec(
    direction: FoundationReferenceCurlDirection,
    size: IntSize,
    durationMillisOverride: Int = FoundationReferenceTapDurationMillis,
) = keyframes {
    val totalDurationMillis = durationMillisOverride.coerceAtLeast(1)
    val middleDurationMillis = max(1, totalDurationMillis / 3)
    durationMillis = totalDurationMillis
    val left = FoundationReferenceCurlEdge.left(size)
    val end = FoundationReferenceCurlEdge.end(size)
    val middle = FoundationReferenceCurlEdge(
        top = Offset(size.width.toFloat(), size.height / 2f),
        bottom = Offset(size.width / 2f, size.height.toFloat()),
    )
    if (direction == FoundationReferenceCurlDirection.Forward) {
        end at 0
        middle at middleDurationMillis
    } else {
        left at 0
        middle at totalDurationMillis - middleDurationMillis
    }
}

/**
 * 탭 또는 자동 스크롤로 촉발된 3D curl turn을 위한 keyframe spec으로, 탭으로 이루어진 turn이
 * 표준 curl의 대각선 벗겨짐이 아니라 Play Books 스타일 롤로 읽히도록, 드래그 도중
 * [foundationReferenceThreeDCurlEdge]가 만들어내는 것과 같은 롤링 crease를 거쳐 edge를 진행시킨다.
 *
 * 앞으로 가는 turn은 crease를 오른쪽 정지 edge에서 왼쪽으로 쓸어가고, 뒤로 가는 turn은 반대
 * 방향으로 쓸어가 오른쪽 edge에서 끝난다 — 3D 롤에는 별개의 접힌 모서리 상태가 없으므로, 두
 * 방향 모두 렌더러가 이미 단락시켜 놓은 두 평평한 정지 edge 중 하나로 안착한다. 스윕 중간의
 * keyframe은 페이지의 가로 중심에서 [foundationReferenceThreeDCurlEdge]로부터 샘플링되며, 앞으로
 * 가는 turn 진행의 1/3 지점에, 뒤로 가는 turn이 끝나기 1/3 전에 배치되어, 표준 spec이 대각선
 * 중간을 호로 지나가는 것과 같은 타임라인 지점에서 crease가 가장 기울어진 상태를 지나가게 한다.
 *
 * @param direction 탭으로 촉발된 turn이 어느 방향으로 애니메이션되는지; forward는 왼쪽 정지 edge에,
 *   backward는 오른쪽에 안착한다.
 * @param size crease keyframe이 계산되는 기준이 되는 leaf 크기.
 * @param durationMillisOverride 전체 애니메이션 지속 시간으로, 자동 스크롤의 페이지당 지연에
 *   재사용되므로 최소 1ms로 강제된다.
 * @return 3D 롤링 crease를 거쳐 [FoundationReferenceCurlEdge]를 구동하는 keyframes spec.
 */
private fun foundationReferenceThreeDCurlTapSpec(
    direction: FoundationReferenceCurlDirection,
    size: IntSize,
    durationMillisOverride: Int = FoundationReferenceTapDurationMillis,
) = keyframes {
    val totalDurationMillis = durationMillisOverride.coerceAtLeast(1)
    val middleDurationMillis = max(1, totalDurationMillis / 3)
    durationMillis = totalDurationMillis
    val right = FoundationReferenceCurlEdge.right(size)
    val left = FoundationReferenceCurlEdge.left(size)
    val middle = foundationReferenceThreeDCurlEdge(size, Offset(size.width / 2f, size.height / 2f))
    if (direction == FoundationReferenceCurlDirection.Forward) {
        right at 0
        middle at middleDurationMillis
    } else {
        left at 0
        middle at totalDurationMillis - middleDurationMillis
    }
}

/**
 * [edge]에 적용된 page-curl fold로 spread가 아닌 페이지 한 장을 그린다.
 *
 * [edge]의 두 정지 위치(`left`/`right`)에서는 아무것도 계산되지 않는다 — 페이지는 완전히
 * 숨겨지거나 있는 그대로 그려진다 — 그래서 [foundationReferenceCurlFold]의 fold 계산은 turn이
 * 실제로 진행 중일 때만 실행된다. 그 외에는 페이지의 평평한 나머지 부분이
 * [FoundationReferenceCurlFold.clippedPath]로 클립되어 정상적으로 그려진 다음, 접힌 부분이 fold
 * 자신의 회전·미러링 변환 안에서 두 번째로 그려지며, 그 polygon으로 클립되고 흰색 오버레이로
 * 어두워진다. 단일 pane 모드에는 별도의 back-face 아트워크가 없으므로, 같은 콘텐츠를 다시 그려
 * 뿌옇게 만드는 것이 "종이의 뒷면"을 대신한다 — [FoundationReferenceSpread]의 두 pane curl은
 * 대신 진짜 back-face 콘텐츠를 갖고 있으며 같은 구분을 위해 [foundationReferenceDrawLeafFront]/
 * [foundationReferenceDrawLeafBack]을 사용한다.
 *
 * PlayLikeCurl 스타일의 [FoundationReferenceCurlStyle.ThreeDimensional] 롤은 turn 중간에 완전히
 * 다른 경로를 밟는다: 하나의 평면 반사된 플랩 대신, 앞쪽 가장자리가 보는 사람 쪽으로 휘어지고 뒤쪽
 * 부분은 평평하게 남도록, [foundationReferenceDrawThreeDCurlMesh]의 플랫폼별로 프로파일링된
 * 사인 곡선 텍스처 mesh를 통해 leaf를 렌더링한다. 그 두 정지 위치는 여전히 같은 `left`/`right`
 * 조기 반환으로 단락된다.
 *
 * @receiver 페이지 composable 자신의 modifier 체인.
 * @param axis fold가 가로로 움직이는지 세로로 움직이는지.
 * @param edgeProvider 이 프레임에 그릴 접힌 edge를 돌려준다. 값이 아니라 provider인 이유는,
 *   그래야 호출자가 애니메이션 중인 edge를 composition에서 읽지 않아도 되고 그만큼 슬롯이
 *   프레임마다 재구성되지 않기 때문이다. 그리기 캐시 안에서, **아래 어떤 early return보다도 먼저**
 *   호출해야 한다: `observeReads`는 실제로 실행된 read만 등록하므로, 이 호출이 정지 edge 분기
 *   아래로 내려가면 그 프레임에 구독이 끊겨 curl이 그 자리에서 멈춘다.
 * @param style 표준 curl 페인팅을 유지할지 3D 사인 곡선 텍스처 mesh를 렌더링할지.
 * @param paperColor 접힌 부분의 뒷면을 채우는 페이지 색으로, 독자가 고른 리더 팔레트의 종이색이다.
 * @param graphicsLayer 모든 3D mesh 구간이 재사용하는 오프스크린 페이지 텍스처.
 * @return 선택된 curl 모양을 그리는 modifier.
 */
private fun Modifier.foundationReferenceDrawCurl(
    axis: FoundationReferenceCurlAxis,
    edgeProvider: () -> FoundationReferenceCurlEdge,
    style: FoundationReferenceCurlStyle,
    paperColor: Color,
    graphicsLayer: GraphicsLayer,
): Modifier = drawWithCache {
    // 여기서 읽는다: 이 블록은 자기 snapshot 관찰자와 함께 돌기 때문에, 접힌 edge가 프레임마다
    // 움직여도 재구성 없이 그리기 캐시만 다시 만들어진다. 아래 early return들보다 반드시 위여야
    // 한다 — 이유는 [edgeProvider] 문서 참고.
    val edge = edgeProvider()
    val canonicalSize = axis.canonicalSize(IntSize(size.width.toInt(), size.height.toInt()))
    if (edge == FoundationReferenceCurlEdge.left(canonicalSize)) {
        return@drawWithCache onDrawWithContent { }
    }
    if (edge == FoundationReferenceCurlEdge.right(canonicalSize)) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    if (style == FoundationReferenceCurlStyle.ThreeDimensional &&
        axis == FoundationReferenceCurlAxis.Horizontal
    ) {
        val progress = foundationReferenceThreeDCurlProgress(edge, canonicalSize.width.toFloat())
        val strips = foundationReferenceThreeDCurlStripSpecs(progress)
            .filter { !it.isBackFacing }
        val meshLighting = foundationReferenceThreeDCurlLightingSpec(progress * PI.toFloat())
        return@drawWithCache onDrawWithContent {
            graphicsLayer.record {
                this@onDrawWithContent.drawContent()
            }
            foundationReferenceDrawThreeDCurlMesh(
                strips = strips,
                lighting = meshLighting,
                graphicsLayer = graphicsLayer,
                width = size.width,
                height = size.height,
                mirrorHorizontally = false,
            )
        }
    }
    val fold = foundationReferenceCurlFold(axis, edge, canonicalSize)
        ?: return@drawWithCache onDrawWithContent { drawContent() }
    val lighting = if (style == FoundationReferenceCurlStyle.ThreeDimensional) {
        foundationReferenceThreeDCurlLightingSpec(fold.angle)
    } else {
        null
    }
    val crease = ((edge.top.x + edge.bottom.x) / 2f)
        .coerceIn(0f, canonicalSize.width.toFloat())
    val frontShade = lighting?.let {
        if (axis == FoundationReferenceCurlAxis.Horizontal) {
            Brush.horizontalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = it.frontShadeAlpha)),
                startX = 0f,
                endX = crease.coerceAtLeast(1f),
            )
        } else {
            Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = it.frontShadeAlpha)),
                startY = 0f,
                endY = crease.coerceAtLeast(1f),
            )
        }
    }
    val backShade = lighting?.let {
        if (axis == FoundationReferenceCurlAxis.Horizontal) {
            Brush.horizontalGradient(
                listOf(
                    Color.White.copy(alpha = it.backLightAlpha),
                    Color.Transparent,
                    Color.Black.copy(alpha = it.backShadeAlpha),
                ),
                startX = crease,
                endX = canonicalSize.width.toFloat().coerceAtLeast(crease + 1f),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = it.backLightAlpha),
                    Color.Transparent,
                    Color.Black.copy(alpha = it.backShadeAlpha),
                ),
                startY = crease,
                endY = canonicalSize.width.toFloat().coerceAtLeast(crease + 1f),
            )
        }
    }

    onDrawWithContent {
        clipPath(fold.clippedPath) {
            this@onDrawWithContent.drawContent()
            if (frontShade != null) drawRect(frontShade)
        }
        withTransform({ fold.applyTo(this, axis) }) {
            fold.drawShadow(
                scope = this,
                axis = axis,
                alpha = lighting?.shadowAlpha ?: FoundationReferenceShadowAlpha,
            )
            clipPath(fold.polygon.toPath(axis)) {
                // 종이의 뒷면은 인쇄면이 아니다. 예전에는 여기서 같은 페이지를 다시 그렸지만
                // [FoundationReferenceCurlFold.applyTo]가 가로축을 미러링하므로 그 재렌더는 좌우가
                // 반전된 본문이 되어, 드래그가 진행되는 동안 화면 대부분을 뒤집힌 글자로 덮었다 —
                // 뒷면 strip을 아예 버려 반전된 콘텐츠를 보여주지 않는 3D 롤과 어긋나는 동작이었다.
                // 페이지 색으로 채우면 뒤집힌 글자 없이 종이 한 장으로 읽히고, turn 도중 페이지
                // 콘텐츠를 두 번 그리던 비용도 한 번으로 준다.
                drawRect(paperColor)
                if (backShade == null) {
                    drawRect(Color.White.copy(alpha = FoundationReferenceBackOverlayAlpha))
                } else {
                    drawRect(backShade)
                    drawLine(
                        color = Color.White.copy(alpha = lighting.rimAlpha),
                        start = axis.fromCanonical(fold.polygon.vertices.first()),
                        end = axis.fromCanonical(fold.polygon.vertices.last()),
                        strokeWidth = FoundationReferenceThreeDRimWidthPx,
                    )
                }
            }
        }
    }
}

/**
 * leaf의 평평한 앞면을 그리며, 3D 프로필의 crease 방향 diffuse shade만 추가한다.
 *
 * PlayLikeCurl 스타일 [FoundationReferenceCurlStyle.ThreeDimensional] 롤의 turn 중간에는 대신
 * [foundationReferenceDrawThreeDCurlMesh]의 사인 곡선 텍스처 mesh를 통해 leaf를 렌더링하며,
 * 전체 투영을 미러링해 [mirrorHorizontally]를 반영함으로써 뒤로 가는 spread의 왼쪽 경첩 fold가
 * 앞으로 가는 짝과 일치하도록 한다. 이 3D 진행률은 [leafSize] — forward/backward edge
 * animatable이 구동되는 것과 같은 edge 공간 — 를 기준으로 잰다.
 *
 * 두 정지 위치 단축 판정도 [leafSize]로 비교한다. [edge]는 그 공간에서 생성되고 애니메이션되므로,
 * 호스트 노드에서 도출한 크기([canonicalSize])와 비교하면 두 폭이 갈리는 비대칭 spread에서 정지
 * 위치에 영원히 도달하지 못해 단축이 걸리지 않는다 — 왼쪽 pane에 leaf를 호스트하는 뒤로 가는 turn이
 * 정확히 그 경우다. [canonicalSize]는 그릴 사각형을 정하는 값이므로 Standard fold 계산과 crease
 * clamp에만 남는다.
 *
 * @receiver 페이지 composable의 modifier 체인.
 * @param axis fold가 가로로 움직이는지 세로로 움직이는지.
 * @param edge leaf의 현재 fold edge.
 * @param style 표준 페인팅을 유지할지 3D 사인 곡선 텍스처 mesh를 렌더링할지.
 * @param leafSize curl 기하가 페이지 한 장으로 취급하는 크기로, 3D 진행률 계산이 호스트 노드
 *   크기 대신 이 값을 기준으로 삼는다.
 * @param mirrorHorizontally 뒤로 가는 spread가 이 leaf를 spine을 중심으로 미러링하는지 여부.
 * @param graphicsLayer 모든 3D mesh 구간이 재사용하는 오프스크린 페이지 텍스처.
 * @return 클립되고 선택적으로 조명이 적용된 앞면을 그리는 modifier.
 */
private fun Modifier.foundationReferenceDrawLeafFront(
    axis: FoundationReferenceCurlAxis,
    edge: FoundationReferenceCurlEdge,
    style: FoundationReferenceCurlStyle,
    leafSize: IntSize,
    mirrorHorizontally: Boolean = false,
    spanBeyondSpinePx: Float = 0f,
    graphicsLayer: GraphicsLayer,
): Modifier = drawWithCache {
    val canonicalSize = axis.canonicalSize(IntSize(size.width.toInt(), size.height.toInt()))
    if (edge == FoundationReferenceCurlEdge.left(leafSize)) {
        return@drawWithCache onDrawWithContent { }
    }
    if (edge == FoundationReferenceCurlEdge.right(leafSize)) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    if (style == FoundationReferenceCurlStyle.ThreeDimensional &&
        axis == FoundationReferenceCurlAxis.Horizontal
    ) {
        val progress = foundationReferenceThreeDCurlProgress(edge, leafSize.width.toFloat())
        val strips = foundationReferenceThreeDCurlStripSpecs(progress)
            .filter { !it.isBackFacing }
        val meshLighting = foundationReferenceThreeDCurlLightingSpec(progress * PI.toFloat())
        return@drawWithCache onDrawWithContent {
            graphicsLayer.record {
                this@onDrawWithContent.drawContent()
            }
            foundationReferenceDrawThreeDCurlMesh(
                strips = strips,
                lighting = meshLighting,
                graphicsLayer = graphicsLayer,
                width = size.width,
                height = size.height,
                mirrorHorizontally = mirrorHorizontally,
                spanBeyondSpinePx = spanBeyondSpinePx,
            )
        }
    }
    val fold = foundationReferenceCurlFold(axis, edge, canonicalSize)
        ?: return@drawWithCache onDrawWithContent { drawContent() }
    val lighting = if (style == FoundationReferenceCurlStyle.ThreeDimensional) {
        foundationReferenceThreeDCurlLightingSpec(fold.angle)
    } else {
        null
    }
    val crease = ((edge.top.x + edge.bottom.x) / 2f)
        .coerceIn(0f, canonicalSize.width.toFloat())
    val frontShade = lighting?.let {
        if (axis == FoundationReferenceCurlAxis.Horizontal) {
            Brush.horizontalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = it.frontShadeAlpha)),
                startX = 0f,
                endX = crease.coerceAtLeast(1f),
            )
        } else {
            Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = it.frontShadeAlpha)),
                startY = 0f,
                endY = crease.coerceAtLeast(1f),
            )
        }
    }

    onDrawWithContent {
        withTransform({ if (mirrorHorizontally) scale(-1f, 1f) }) {
            clipPath(fold.clippedPath) {
                withTransform({ if (mirrorHorizontally) scale(-1f, 1f) }) {
                    this@onDrawWithContent.drawContent()
                }
                if (frontShade != null) drawRect(frontShade)
            }
        }
    }
}

/**
 * leaf의 뒷면을 그린다.
 *
 * 표준 [FoundationReferenceCurlStyle.Standard] 경로는 crease를 기준으로 반사된 접힌 뒷면을
 * 그리며, 종이 조명, 독립적인 뒷면 shade, rim highlight, 각도에 따른 cast shadow를 포함한다.
 *
 * PlayLikeCurl 스타일 [FoundationReferenceCurlStyle.ThreeDimensional] 롤은 앞면과 같은
 * [foundationReferenceDrawThreeDCurlMesh] 사인 곡선 텍스처 mesh로 뒷면을 그리되,
 * [foundationReferenceThreeDCurlProgress]가 낸 앞면 진행률을
 * [foundationReferenceSpreadBackFaceProgress]로 보수(`1 - progress`)를 취해 뒤집은 값을 넘긴다 —
 * 그래야 앞면이 gutter로 접혀 들어가는 동안 뒷면이 gutter에서부터 자라나 반대쪽 pane에 정확히
 * 안착한다. 호출자([FoundationReferenceSpread]/[FoundationReferenceBackwardSpread])는 이 뒷면을
 * 앞면과 다른 목적지 pane의 노드에 호스트하므로, mesh의 목적지 `width`/`height`는 그 노드 자신의
 * `size`를 그대로 쓴다 — [leafSize]는 오직 [foundationReferenceThreeDCurlProgress]가 앞면과 같은
 * edge 공간에서 진행률을 재는 데만 쓰인다. 보수 진행률의 mesh가 노드 안에서 완전히 클램프되어
 * 사라지는 구간([foundationReferenceThreeDCurlMeshExtent]가 비어 있다고 판정하는 경우)에는
 * 오프스크린 텍스처를 기록하지 않고 그대로 반환해, turn 전반부에 불필요한 기록 비용이 들지 않게
 * 한다.
 *
 * @receiver 페이지 composable의 modifier 체인.
 * @param axis fold가 가로로 움직이는지 세로로 움직이는지.
 * @param edge leaf의 현재 fold edge.
 * @param style 표준 페인팅을 유지할지 3D 사인 곡선 텍스처 mesh를 렌더링할지.
 * @param leafSize curl 기하가 페이지 한 장으로 취급하는 크기로, 이 뒷면이 호스트되는 노드의 크기와
 *   다를 수 있는 edge 공간이다 — [foundationReferenceThreeDCurlProgress]는 반드시 이 값을 기준으로
 *   진행률을 재야, 앞면이 정지해 있을 때 뒷면도 정지 상태(진행률 0)로 일치한다.
 * @param mirrorHorizontally 뒤로 가는 spread가 이 leaf를 spine을 중심으로 미러링하는지 여부.
 * @param graphicsLayer 3D mesh 구간이 재사용하는, 이 뒷면 전용 오프스크린 페이지 텍스처.
 * @return 변환되고 선택적으로 조명이 적용된 뒷면을 그리는 modifier.
 */
private fun Modifier.foundationReferenceDrawLeafBack(
    axis: FoundationReferenceCurlAxis,
    edge: FoundationReferenceCurlEdge,
    style: FoundationReferenceCurlStyle,
    leafSize: IntSize,
    mirrorHorizontally: Boolean = false,
    spanBeyondSpinePx: Float = 0f,
    graphicsLayer: GraphicsLayer,
): Modifier = drawWithCache {
    val canonicalSize = axis.canonicalSize(IntSize(size.width.toInt(), size.height.toInt()))
    if (edge == FoundationReferenceCurlEdge.right(leafSize)) {
        return@drawWithCache onDrawWithContent { }
    }
    if (style == FoundationReferenceCurlStyle.ThreeDimensional &&
        axis == FoundationReferenceCurlAxis.Horizontal
    ) {
        val progress = foundationReferenceThreeDCurlProgress(edge, leafSize.width.toFloat())
        val strips = foundationReferenceThreeDCurlStripSpecs(progress)
            .filter { it.isBackFacing }
        if (strips.isEmpty()) {
            return@drawWithCache onDrawWithContent { }
        }
        val meshLighting = foundationReferenceThreeDCurlLightingSpec(progress * PI.toFloat())
        return@drawWithCache onDrawWithContent {
            graphicsLayer.record {
                this@onDrawWithContent.drawContent()
            }
            foundationReferenceDrawThreeDCurlMesh(
                strips = strips,
                lighting = meshLighting,
                graphicsLayer = graphicsLayer,
                width = size.width,
                height = size.height,
                mirrorHorizontally = mirrorHorizontally,
                spanBeyondSpinePx = spanBeyondSpinePx,
            )
        }
    }
    val fold = foundationReferenceCurlFold(axis, edge, canonicalSize)
        ?: return@drawWithCache onDrawWithContent { }
    val lighting = if (style == FoundationReferenceCurlStyle.ThreeDimensional) {
        foundationReferenceThreeDCurlLightingSpec(fold.angle)
    } else {
        null
    }
    val crease = ((edge.top.x + edge.bottom.x) / 2f)
        .coerceIn(0f, canonicalSize.width.toFloat())
    val backShade = lighting?.let {
        if (axis == FoundationReferenceCurlAxis.Horizontal) {
            Brush.horizontalGradient(
                listOf(
                    Color.White.copy(alpha = it.backLightAlpha),
                    Color.Transparent,
                    Color.Black.copy(alpha = it.backShadeAlpha),
                ),
                startX = crease,
                endX = canonicalSize.width.toFloat().coerceAtLeast(crease + 1f),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = it.backLightAlpha),
                    Color.Transparent,
                    Color.Black.copy(alpha = it.backShadeAlpha),
                ),
                startY = crease,
                endY = canonicalSize.width.toFloat().coerceAtLeast(crease + 1f),
            )
        }
    }

    onDrawWithContent {
        withTransform({ if (mirrorHorizontally) scale(-1f, 1f) }) {
            withTransform({ fold.applyTo(this, axis) }) {
                fold.drawShadow(
                    scope = this,
                    axis = axis,
                    alpha = lighting?.shadowAlpha ?: FoundationReferenceShadowAlpha,
                )
                clipPath(fold.polygon.toPath(axis)) {
                    this@onDrawWithContent.drawContent()
                    if (backShade != null) {
                        drawRect(backShade)
                        drawLine(
                            color = Color.White.copy(alpha = lighting.rimAlpha),
                            start = axis.fromCanonical(fold.polygon.vertices.first()),
                            end = axis.fromCanonical(fold.polygon.vertices.last()),
                            strokeWidth = FoundationReferenceThreeDRimWidthPx,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 하나의 crease 위치에 대해 계산된 fold 기하: [foundationReferenceDrawCurl]과 leaf-face modifier가
 * 평평한 나머지 부분, 접힌 부분, 그리고 그 그림자를 그리는 데 필요한 모든 것.
 *
 * [polygon]/[angle]/[pivot]은 페이지의 좌표계가 아니라 접힌 부분 자신의 그리기 프레임으로 이를
 * 설명한다: [applyTo]는 감싸는 `withTransform` 블록 안에서 먼저 실행되어, [pivot]을 중심으로
 * 그리기 scope를 미러링하고 회전시켜야 한다, 그래야 변환이 적용되어 있는 동안 곧바로 클립으로
 * 쓰이는 [polygon]과 그 클립 안에 그려지는 페이지 자신의 똑바로 선 콘텐츠가 둘 다 접힌 종이가
 * 실제로 놓이는 위치에 떨어진다; 그런 다음 블록이 끝나면 `withTransform`이 변환되지 않은 scope를
 * 복원한다.
 *
 * @property clippedPath 페이지의 평평한, 아직 접히지 않은 영역으로, 곧바로 클립 경로로 쓸 준비가
 *   되어 있다.
 * @property polygon fold 자신의(사전-[applyTo]) 프레임 기준, 접힌 영역의 윤곽.
 * @property angle fold가 얼마나 열리도록 회전했는지, 라디안 단위.
 * @property pivot fold가 경첩처럼 움직이는 중심점, canonical 좌표계 기준.
 * @property shadowOffset fold로부터의 그림자 오프셋으로, 이미 [angle]에 맞춰 회전되어 있다.
 * @property shadowRadius 그림자의 블러 반경, 픽셀 단위.
 */
private class FoundationReferenceCurlFold(
    val clippedPath: Path,
    val polygon: FoundationPagerCurlPolygon,
    val angle: Float,
    val pivot: Offset,
    val shadowOffset: Offset,
    val shadowRadius: Float,
) {
    /**
     * [scope]를 접힌 부분 자신의 그리기 프레임으로 옮긴다: [pivot]을 중심으로 [angle]만큼
     * 미러링·회전시켜서, 이 호출 뒤에 그려지는 [polygon]과 페이지 자신의 콘텐츠가 평평한 페이지가
     * 놓을 위치가 아니라 접힌 종이가 실제로 놓이는 위치에 떨어지게 한다.
     *
     * 미러 축과 회전 부호는 축마다 뒤바뀐다. [FoundationReferenceCurlAxis]의 세로 케이스가 별도의
     * 공식 집합을 도출하는 대신 x/y를 맞바꿔 같은 가로-축 fold 계산을 재사용하기 때문이다 — 다른
     * 축을 미러링하고 각도의 부호를 뒤집는 것이, 같은 edge 움직임에 대해 세로 fold가 가로 fold와
     * 같은 시각적 방향으로 넘어가도록 유지해 준다.
     *
     * @param scope fold의 미러링·회전을 적용할 그리기 변환.
     * @param axis fold가 실제로 렌더링되는 화면 축.
     */
    fun applyTo(scope: DrawTransform, axis: FoundationReferenceCurlAxis) {
        if (axis == FoundationReferenceCurlAxis.Horizontal) {
            scope.scale(-1f, 1f, pivot = pivot)
            scope.rotateRad(angle, pivot = pivot)
        } else {
            scope.scale(1f, -1f, pivot = pivot)
            scope.rotateRad(-angle, pivot = pivot)
        }
    }

    /**
     * 접힌 부분의 드롭 섀도를 그리며, 플랫폼 캔버스만이 shadow layer를 블러 처리할 수 있으므로
     * 플랫폼별 [drawFoundationPagerCurlShadow]로 위임한다.
     *
     * @param scope 그림자를 렌더링할 draw scope.
     * @param axis fold가 렌더링되는 화면 축으로, 플랫폼 구현이 [polygon]을 화면 좌표로 다시
     *   변환할 수 있도록 전달된다.
     * @param alpha 선택된 시각적 style과 fold 각도에 대한 cast shadow의 불투명도.
     */
    fun drawShadow(
        scope: DrawScope,
        axis: FoundationReferenceCurlAxis,
        alpha: Float,
    ) {
        scope.drawFoundationPagerCurlShadow(
            polygon = polygon,
            axis = axis,
            radius = shadowRadius,
            shadowOffset = shadowOffset,
            color = Color.Black.copy(alpha = alpha.coerceIn(0f, 1f)),
        )
    }
}

/**
 * [edge]에 담긴 원시적이고 경계 없는 fold 선을 페이지 한 장에 대한 실제 fold 기하로 바꾼다: crease가
 * 이 페이지 자신의 경계를 어디서 가로지르는지, 접힌 부분이 얼마나 회전해 열렸는지, 그 그림자가
 * 어디로 떨어지는지. 이는 모든 curl 그리기 경로 —
 * [foundationReferenceDrawCurl], [foundationReferenceDrawLeafFront],
 * [foundationReferenceDrawLeafBack] — 가 자신의 렌더링을 만들어내는 근거가 되는 단 하나의 계산이다.
 *
 * [edge]는 방향과 crease가 지나가는 한 점만 지니고 있을 뿐([foundationReferenceCurlEdge] 참고),
 * crease가 페이지와 만나는 지점은 담고 있지 않다 — 이는 페이지의 위/아래 edge와 교차시켜 풀어야
 * 한다. 거기서 null 결과가 나온다는 것은 fold 선이 정확히 수평이어서 두 edge 모두와 평행하고,
 * 따라서 단일 교차점이 없다는 뜻이다; 모든 호출자는 이를 "fold 없음"으로 취급해, 퇴화된 경로로
 * 클립하는 대신 페이지를 평평하게 그린다.
 *
 * 교차점의 x는 최소 0으로 clamp된다(`topCurlOffset`/`bottomCurlOffset`), 페이지 자신의 왼쪽 edge를
 * 지나쳐 이동한 드래그 — overscroll되었거나 빠르게 flung된 제스처 — 는 그렇지 않으면 crease를
 * 페이지 왼쪽 바깥으로 투영시켰을 것이기 때문이다; clamp는 이를 [foundationReferenceCurlPolygon]과
 * 클립 경로에 그것들이 클립하는 영역 바깥의 crease를 넘기는 대신 edge에 고정한다.
 *
 * `angle`은 crease 선 자체 기울기의 두 배인데, 당겨지는 모서리를 각도 θ인 선에 대해 반사하면 접힌
 * 종이가 2θ만큼 회전하기 때문이다 — 이는 애초에 [foundationReferenceCurlEdge]의 수직이등분선 구성이
 * 작동하게 만드는 것과 같은 관계다. `pivot`은 그 회전([FoundationReferenceCurlFold.applyTo])을
 * crease의 아래쪽 끝점에 고정한다. `shadowOffset`은 같은 각도만큼 회전되어, 페이지가 회전한 뒤에는
 * 이상해 보였을 고정된 화면-공간 오프셋 대신, 그림자가 fold가 열리는 동안에도 그것에 대해 일관된
 * 방향으로 계속 떨어지도록 한다.
 *
 * @receiver [FoundationReferenceShadowOffsetX]와 [FoundationReferenceShadowRadius]를 dp에서
 *   픽셀로 해석하는 데 필요한, modifier의 draw-cache scope.
 * @param axis fold가 가로로 움직이는지 세로로 움직이는지; pivot과 shadow offset을 canonical
 *   좌표계에서 화면 좌표계로 다시 변환하는 데 쓰인다.
 * @param edge 이 페이지에 대해 풀어야 할, canonical 좌표계 기준의 (경계 없는) fold 선.
 * @param canonicalSize 축의 canonical 방향 기준 페이지의 크기.
 * @return fold의 전체 기하, 또는 [edge]가 정확히 수평이어서 페이지의 위/아래 edge와의 교차가
 *   정의되지 않으면 null.
 */
private fun CacheDrawScope.foundationReferenceCurlFold(
    axis: FoundationReferenceCurlAxis,
    edge: FoundationReferenceCurlEdge,
    canonicalSize: IntSize,
): FoundationReferenceCurlFold? {
    val width = canonicalSize.width.toFloat()
    val height = canonicalSize.height.toFloat()
    val topIntersection = foundationReferenceLineIntersection(
        Offset.Zero,
        Offset(width, 0f),
        edge.top,
        edge.bottom,
    ) ?: return null
    val bottomIntersection = foundationReferenceLineIntersection(
        Offset(0f, height),
        Offset(width, height),
        edge.top,
        edge.bottom,
    ) ?: return null

    val topCurlOffset = Offset(max(0f, topIntersection.x), topIntersection.y)
    val bottomCurlOffset = Offset(max(0f, bottomIntersection.x), bottomIntersection.y)
    val lineVector = topCurlOffset - bottomCurlOffset
    val angle = PI.toFloat() - atan2(lineVector.y, lineVector.x) * 2f
    return FoundationReferenceCurlFold(
        clippedPath = listOf(
            Offset.Zero,
            topCurlOffset,
            bottomCurlOffset,
            Offset(0f, height),
        ).foundationReferencePath(axis),
        polygon = foundationReferenceCurlPolygon(width, height, topCurlOffset, bottomCurlOffset),
        angle = angle,
        pivot = axis.fromCanonical(bottomCurlOffset),
        shadowOffset = axis.fromCanonical(
            Offset(-FoundationReferenceShadowOffsetX.toPx(), 0f)
                .foundationReferenceRotate(2f * PI.toFloat() - angle),
        ),
        shadowRadius = FoundationReferenceShadowRadius.toPx(),
    )
}

/**
 * 페이지의 접힌 영역의 윤곽: crease([topCurlOffset]-[bottomCurlOffset])와 페이지 자신의 오른쪽
 * edge 사이의 부분을, canonical 좌표계에서의 닫힌 polygon으로 나타낸 것.
 * [foundationReferenceCurlFold]는 이를 접힌 부분을 그리는 패스의 클립 모양으로도,
 * [drawFoundationPagerCurlShadow]가 그림자를 드리우는 모양으로도 사용한다.
 *
 * 일반적인 경우는 crease의 위/아래 점이 이미 페이지 안쪽에 있다고 취급하고
 * ([topCurlOffset]/[bottomCurlOffset].x < [width]), 각각을 같은 높이의 오른쪽 edge로 곧장
 * 투영해 polygon을 닫는다, 그래서 crease-top, crease-top 높이의 오른쪽-edge, 페이지의
 * 오른쪽-아래 모서리, crease-bottom(또는 대칭적으로 만들어진 위/아래 동등물)로 이루어진 단순한
 * 사각형이 된다.
 *
 * crease 점이 오른쪽 edge를 지나쳐 밀려나면 — [foundationReferenceCurlFold]는 crease의 x를 최소
 * 0으로만 clamp할 뿐 [width]로 최대 clamp하지는 않으므로, 이는 fold가 완료에 가까워질수록
 * 일어난다 — 그 모서리는 더 이상 직접 추가할 수 있는, 페이지 안의 의미 있는 위치를 갖지 않는다.
 * `endSideIntersection`은 대신 (경계를 벗어났을 수 있는 점이 아니라 연장된) crease 선이 실제로
 * 오른쪽 edge를 어디서 가로지르는지 찾아, 그 분기가 여전히 평소의 정점 두 개를 추가하도록 같은
 * 점을 두 번 기여한다; 그 결과는 잘못 만들어진 polygon이 아니라 그 모서리에서 퇴화된, 길이가
 * 0인 edge이며, 이는 fold가 가장 극단적인 상태에 있을 때만 나타나는 그리기 아티팩트로 충분히
 * 작아서 고치지 않고 둘 만하다.
 *
 * @param width canonical 좌표계 기준 페이지의 너비; 오른쪽 edge의 x이기도 하다.
 * @param height canonical 좌표계 기준 페이지의 높이.
 * @param topCurlOffset crease가 페이지의 위쪽 edge를 가로지르는 지점(또는 그 너머, 오른쪽 edge를
 *   지난 지점).
 * @param bottomCurlOffset crease가 페이지의 아래쪽 edge를 가로지르는 지점(또는 그 너머, 오른쪽
 *   edge를 지난 지점).
 * @return 접힌 영역의 윤곽으로, 항상 닫힌 4점 polygon.
 */
private fun foundationReferenceCurlPolygon(
    width: Float,
    height: Float,
    topCurlOffset: Offset,
    bottomCurlOffset: Offset,
): FoundationPagerCurlPolygon {
    /**
     * crease 선이 실제로 페이지의 오른쪽 edge를 가로지르는 지점을, 호출한 분기가 여전히 평소의
     * 정점 두 개를 기여하도록 두 배로 만든 것; crease가 그 edge와 정확히 평행하면 비어 있다.
     */
    fun endSideIntersection(): List<Offset> {
        val offset = foundationReferenceLineIntersection(
            topCurlOffset,
            bottomCurlOffset,
            Offset(width, 0f),
            Offset(width, height),
        ) ?: return emptyList()
        return listOf(offset, offset)
    }
    return FoundationPagerCurlPolygon(buildList {
        if (topCurlOffset.x < width) {
            add(topCurlOffset)
            add(Offset(width, topCurlOffset.y))
        } else {
            addAll(endSideIntersection())
        }
        if (bottomCurlOffset.x < width) {
            add(Offset(width, height))
            add(bottomCurlOffset)
        } else {
            addAll(endSideIntersection())
        }
    })
}

/**
 * 각 점 쌍을 경계가 있는 선분이 아니라 선을 정의하는 것으로 취급했을 때, 두 무한한 선이 교차하는
 * 지점 — 이 파일이 다루는 fold crease는 페이지 자신의 edge와 교차되기 전까지는 개념적으로
 * 무한하므로, 여기 있는 모든 호출자는 선분-클립 형태가 아니라 선-선 형태가 필요하다.
 *
 * @param line1a 첫 번째 선 위의 한 점.
 * @param line1b 첫 번째 선 위의, 구별되는 두 번째 점.
 * @param line2a 두 번째 선 위의 한 점.
 * @param line2b 두 번째 선 위의, 구별되는 두 번째 점.
 * @return 교차점, 또는 두 선이 평행하거나(또는 동일하거나) 단일 교차점이 없으면 null.
 */
internal fun foundationReferenceLineIntersection(
    line1a: Offset,
    line1b: Offset,
    line2a: Offset,
    line2b: Offset,
): Offset? {
    val denominator = (line1a.x - line1b.x) * (line2a.y - line2b.y) -
            (line1a.y - line1b.y) * (line2a.x - line2b.x)
    if (denominator == 0f) return null
    val x1 = (line1a.x * line1b.y - line1a.y * line1b.x) * (line2a.x - line2b.x)
    val x2 = (line1a.x - line1b.x) * (line2a.x * line2b.y - line2a.y * line2b.x)
    val y1 = (line1a.x * line1b.y - line1a.y * line1b.x) * (line2a.y - line2b.y)
    val y2 = (line1a.y - line1b.y) * (line2a.x * line2b.y - line2a.y * line2b.x)
    return Offset((x1 - x2) / denominator, (y1 - y2) / denominator)
}

/**
 * fold의 crease 선을, 그것이 페이지의 위/아래 edge를 가로지르는 두 점으로 나타낸 것 —
 * [FoundationPagerCurlReferenceImpl]의 animatable들이 페이지 turn을 애니메이션하기 위해 구동하는
 * 값이며, [foundationReferenceCurlFold]가 나머지 fold 기하를 풀어내는 근거.
 *
 * @property top crease가 페이지의 위쪽 edge와 만나는 지점, canonical 좌표계 기준.
 * @property bottom crease가 페이지의 아래쪽 edge와 만나는 지점, canonical 좌표계 기준.
 */
internal data class FoundationReferenceCurlEdge(
    val top: Offset,
    val bottom: Offset,
) {
    /**
     * 이 타입에 대한 [Animatable]의 변환, 페이지 turn이 그 사이를 애니메이션하는 고정된 edge
     * 위치들, 그리고 [Animatable]에게 도착했음을 알려주는 [VisibilityThreshold].
     */
    companion object {
        /**
         * [FoundationReferenceCurlEdge]의 두 offset을 하나의 4-요소 벡터로 취급함으로써
         * [Animatable]이 이를 보간할 수 있게 하며, 그래서 [top]과 [bottom]은 각각 독립적으로
         * 애니메이션 값들 사이를 선형으로 움직인다.
         */
        val VectorConverter: TwoWayConverter<FoundationReferenceCurlEdge, AnimationVector4D> = TwoWayConverter(
            convertToVector = { AnimationVector4D(it.top.x, it.top.y, it.bottom.x, it.bottom.y) },
            convertFromVector = { FoundationReferenceCurlEdge(Offset(it.v1, it.v2), Offset(it.v3, it.v4)) },
        )
        /**
         * [Animatable]이 curl edge에 대해 눈에 보이는 움직임으로 취급하는, 요소별 최소 변화량;
         * 이 타입만을 위해 따로 고른 것이 아니라 [Offset] 자신의 기본값을 재사용한다.
         */
        val VisibilityThreshold = FoundationReferenceCurlEdge(
            Offset.VisibilityThreshold,
            Offset.VisibilityThreshold,
        )

        /**
         * 페이지 왼쪽에 있는 edge(`top`/`bottom` 모두 x = 0) — 앞으로 가는 turn의 완료 위치이며,
         * spread 밖에서는 뒤로 가는 turn의 정지 위치이기도 하다.
         */
        fun left(size: IntSize): FoundationReferenceCurlEdge = FoundationReferenceCurlEdge(
            Offset.Zero,
            Offset(0f, size.height.toFloat()),
        )

        /**
         * 페이지 오른쪽에 있는 edge(`top`/`bottom` 모두 x = [size]의 너비) — 앞으로 가는 turn의
         * 정지 위치이며, spread에서는 뒤로 가는 turn의 정지 위치이기도 하다
         * ([foundationReferenceCurlGeometryDirection] 참고).
         */
        fun right(size: IntSize): FoundationReferenceCurlEdge = FoundationReferenceCurlEdge(
            Offset(size.width.toFloat(), 0f),
            Offset(size.width.toFloat(), size.height.toFloat()),
        )

        /**
         * 페이지 오른쪽-아래 모서리의 한 점으로 접힌 edge — spread 밖에서 탭으로 촉발된 뒤로 가는
         * turn이 애니메이션해 도달하는 목표로, 드래그로 구동되는 turn이 쓰는 단순한 [left]/[right]
         * 정지 위치와는 다르다.
         */
        fun end(size: IntSize): FoundationReferenceCurlEdge = FoundationReferenceCurlEdge(
            Offset(size.width.toFloat(), size.height.toFloat()),
            Offset(size.width.toFloat(), size.height.toFloat()),
        )
    }
}

/**
 * curl 인터랙션·렌더링 프로필을 고른다.
 *
 * [Standard]는 기존의 포인터 추적 corner peel을 그대로 유지한다. [ThreeDimensional]은 turn을
 * 가로 스와이프로 고정하고, 포인터 x만으로 거의 수직인 롤링 crease를 구동하며, 앞/뒤 shading,
 * 종이 bounce light, crease highlight, 동적 cast shadow를 추가한다.
 */
internal enum class FoundationReferenceCurlStyle {
    Standard,
    ThreeDimensional,
}

/**
 * 하나의 fold 각도에 대해 3D curl 렌더러가 사용하는 추가 조명 강도.
 *
 * @property frontShadeAlpha crease 근처의 평평한 앞면 위에 얹히는 diffuse shade.
 * @property backShadeAlpha 접힌 뒷면에 적용되는 어두운 성분.
 * @property backLightAlpha 접힌 뒷면에 적용되는 종이색 bounce light.
 * @property rimAlpha fold 선 위에 직접 그려지는 좁은 highlight.
 * @property shadowAlpha 들려 올라간 leaf 아래에 드리우는 cast-shadow 불투명도.
 */
internal data class FoundationReferenceThreeDCurlLightingSpec(
    val frontShadeAlpha: Float,
    val backShadeAlpha: Float,
    val backLightAlpha: Float,
    val rimAlpha: Float,
    val shadowAlpha: Float,
)

/**
 * 반사된 leaf 각도로부터 3D curl의 추가 조명을 해석한다. 사인 곡선은 시트가 어느 쪽 방향으로든
 * 평평할 때는 모든 효과가 사라지고 가장 눈에 띄게 휘어져 있을 때 정점을 찍게 만들어, turn의
 * 시작이나 끝에서 낡은 어둡기가 남아 있지 않도록 한다.
 *
 * @param foldAngleRadians curl 기하가 만들어내는 fold 반사 각도, 라디안 단위.
 * @return 이 프레임에 대한 앞면, 뒷면, rim, cast-shadow 강도.
 */
internal fun foundationReferenceThreeDCurlLightingSpec(
    foldAngleRadians: Float,
): FoundationReferenceThreeDCurlLightingSpec {
    val intensity = abs(sin(foldAngleRadians)).coerceIn(0f, 1f)
    return FoundationReferenceThreeDCurlLightingSpec(
        frontShadeAlpha = 0.16f * intensity,
        backShadeAlpha = 0.12f * intensity,
        backLightAlpha = 0.24f * intensity,
        rimAlpha = 0.32f * intensity,
        shadowAlpha = 0.34f * intensity,
    )
}

/**
 * PlayLikeCurl 사인 곡선 텍스처 mesh 안의 세로 소스 구간 하나와, 그것이 투영된 화면 범위.
 *
 * 참조 구현은 사인파가 깊이를 바꾸는 동안 모든 컬럼을 전면을 향한 채 순서대로 유지한다. 공유되는
 * 목적지 경계는 인접한 구간들을 연속되게 만든다; 음수인 목적지는 단순히 페이지의 그 부분이
 * viewport 시작점을 넘어 이동했다는 뜻이다. 렌더러는 클리핑이 독립적으로 래스터화된 글리프를
 * 절대 쪼개지 않도록 모든 구간을 하나의 오프스크린 페이지 텍스처에서 샘플링한다.
 *
 * @property sourceStartFraction 평평한 텍스처에서 이 구간이 시작되는 leaf-너비 비율.
 * @property sourceEndFraction 다음 소스 경계로, [sourceStartFraction]에서 정확히 grid 한 칸
 *   뒤다.
 * @property destinationStartFraction [sourceStartFraction]의 원근 투영.
 * @property destinationEndFraction [sourceEndFraction]의 원근 투영으로, 다음 구간의 시작과
 *   공유된다.
 * @property depthFraction 카메라 쪽으로의, 이 구간의 최대 실린더 깊이, leaf-너비 단위.
 * @property isBackFacing 이 구간의 표면 법선이 카메라에서 돌아섰는지 — 실린더 감김 각도가 `PI / 2`를
 *   지나면 참이 되며, 이 구간은 앞면이 아니라 뒷면으로 그려야 한다.
 */
internal data class FoundationReferenceThreeDCurlStripSpec(
    val sourceStartFraction: Float,
    val sourceEndFraction: Float,
    val destinationStartFraction: Float,
    val destinationEndFraction: Float,
    val depthFraction: Float,
    val isBackFacing: Boolean,
)

/**
 * 반지름 [FoundationReferenceThreeDCurlRadius]의 실린더에 시트를 감아 leaf를 투영한다.
 *
 * 기하는 세 구간이다. spine(source 0)부터 crease까지는 **평면**이고 목적지가 소스와 같다 — 그래서
 * 페이지의 이 부분은 turn 내내 제자리에 고정된다. crease부터 호 길이 `PI * radius`까지는 실린더에
 * **감긴 구간**으로, 감김 각도 `theta = (source - crease) / radius`에 대해 목적지는
 * `crease + radius * sin(theta)`, 깊이는 `radius * (1 - cos(theta))`다. 그 뒤로는 시트가 다시
 * **평면**이 되어 crease 왼쪽으로 뻗어 나가며, 목적지가 소스가 늘어나는 만큼 줄어든다.
 *
 * 표면 법선은 theta만큼 돌아가므로 `theta < PI / 2`인 구간만 카메라를 향한다 — 그 지점을 넘어선
 * 구간은 [FoundationReferenceThreeDCurlStripSpec.isBackFacing]으로 표시되어 뒷면으로 그려진다.
 * 목적지가 소스와 반대로 줄어들기 때문에 뒷면 콘텐츠는 좌우 반전되어 나타나며, 이는 종이 한 장이
 * 실제로 뒤집히는 모습이다.
 *
 * crease는 시트의 끝(tip)이 progress에 대해 `1 - 2 * progress`로 선형 이동하도록 역산한
 * `1 - progress - PI * radius / 2`다. 그래서 tip은 progress 0.5에서 정확히 spine을 통과하고, turn
 * 후반부 내내 시트가 반대쪽 pane을 덮으며 넘어간다 — 책장 한 장이 반대쪽 페이지를 덮는 동작이며,
 * 이 선형성이 없으면 시트가 spine 쪽으로 줄어들다 사라지는 것처럼 보인다. 정지 상태에서는 반지름이
 * 0이라 crease가 1이 되어 매핑이 항등이고, 완료 시점에는 tip이 `-1`에 놓여 반대쪽 pane을 빈틈없이
 * 덮는다.
 *
 * 반지름은 시작과 끝 [FoundationReferenceThreeDCurlRadiusRampEnd] 구간에서 각각 0으로 수렴한다:
 * 정지와 완료 양쪽에서 시트가 평평해야 다음 spread로 스냅 없이 이어진다. 반지름이 0으로 가는
 * 극한은 실린더가 아니라 날카로운 접힘이며(`theta = PI`), 목적지는 `crease - along`이 되어 접힌
 * 부분이 crease를 기준으로 그대로 반사된다.
 *
 * 모든 경계는 한 번씩만 계산되어 인접 구간들이 공유한다.
 *
 * @param progress 평평한 현재 페이지에서의 0부터 viewport를 떠난 뒤의 1까지의 turn 진행률; 이
 *   범위 밖의 값은 clamp된다.
 * @return 비트 단위로 동일한 공유 경계를 가진, 플랫폼 프로필의 순서 있는 구간들. 앞면과 뒷면
 *   구간이 모두 들어 있으므로 호출자는 [FoundationReferenceThreeDCurlStripSpec.isBackFacing]으로
 *   걸러 쓴다.
 */
internal fun foundationReferenceThreeDCurlStripSpecs(
    progress: Float,
): List<FoundationReferenceThreeDCurlStripSpec> {
    val clamped = progress.coerceIn(0f, 1f)
    val ramp = FoundationReferenceThreeDCurlRadiusRampEnd
    val radius = FoundationReferenceThreeDCurlRadius *
        min(1f, clamped / ramp) * min(1f, (1f - clamped) / ramp)
    val grid = foundationPagerRenderProfile.threeDCurlGrid
    val wrapArc = PI.toFloat() * radius
    val crease = 1f - clamped - wrapArc / 2f
    val boundaries = FloatArray(grid + 1)
    val depths = FloatArray(grid + 1)
    val wrapped = BooleanArray(grid + 1)
    val angles = FloatArray(grid + 1)
    for (index in boundaries.indices) {
        val source = index.toFloat() / grid
        val along = source - crease
        val theta = when {
            along <= 0f -> 0f
            radius <= FoundationReferenceThreeDCurlFlatEpsilon -> PI.toFloat()
            else -> min(along / radius, PI.toFloat())
        }
        val depth = radius * (1f - cos(theta))
        boundaries[index] = if (along <= 0f) {
            source
        } else {
            crease + radius * sin(theta) - max(0f, along - wrapArc)
        }
        depths[index] = depth
        wrapped[index] = along > 0f
        angles[index] = theta
    }
    return List(grid) { index ->
        FoundationReferenceThreeDCurlStripSpec(
            sourceStartFraction = index.toFloat() / grid,
            sourceEndFraction = (index + 1).toFloat() / grid,
            destinationStartFraction = boundaries[index],
            destinationEndFraction = boundaries[index + 1],
            depthFraction = max(depths[index], depths[index + 1]),
            isBackFacing = wrapped[index] && wrapped[index + 1] &&
                (angles[index] + angles[index + 1]) / 2f > PI.toFloat() / 2f,
        )
    }
}

/**
 * 롤링 3D crease가 그 [FoundationReferenceCurlEdge] 값으로부터, 얼마나 진행했는지를
 * [foundationReferenceThreeDCurlStripSpecs]의 progress 입력값으로 나타낸 것.
 *
 * 3D crease는 평균 x를 정지 상태의 leaf 오른쪽 edge에서 완료 시점의 왼쪽 edge까지 쓸어간다. 그
 * 위치를 `1 - x / width`로 변환하면 참조 사인파, 가로 이동, 드래그, 탭, 자동 스크롤 경로가 쓰는
 * 것과 같은 0..1 위상이 나온다.
 *
 * @param edge leaf의 현재 crease, canonical 좌표계 기준.
 * @param width leaf의 너비, canonical 픽셀 단위; 0 이하의 너비는 0으로 나누는 대신 0인 progress를
 *   낳는다.
 * @return [edge]에 대한 0..1 범위의 롤 진행률.
 */
internal fun foundationReferenceThreeDCurlProgress(
    edge: FoundationReferenceCurlEdge,
    width: Float,
): Float {
    if (width <= 0f) return 0f
    val crease = (edge.top.x + edge.bottom.x) / 2f
    return (1f - crease / width).coerceIn(0f, 1f)
}

/**
 * [strips]가 목적지 노드 안에서 실제로 차지하는 가로 범위로, [foundationReferenceDrawThreeDCurlMesh]가
 * cast shadow와 front-shade 그라데이션을 어디에 그릴지 정하는 값과 같은 clamp된 픽셀 범위다.
 *
 * 값은 clamp하지 않는다: 뒷면을 반대쪽 pane에 놓는 배치는 spine 왼쪽(음수 목적지)을 실제로 보이는
 * 영역으로 쓰므로, 노드 폭으로 잘라내면 그 영역이 사라진 것으로 오판하게 된다.
 *
 * @property leftPx 보이는 mesh의 왼쪽 끝, leaf 프레임 픽셀 값. [strips]가 비어 있으면 의미 없는 0.
 * @property rightPx 보이는 mesh의 오른쪽 끝, leaf 프레임 픽셀 값. [strips]가 비어 있으면 의미 없는 0.
 * @property isEmpty 그릴 strip 자체가 없음 — [strips] 리스트가 비어 있을 때만 참이 된다. 앞면은
 *   turn 완료 직전, 뒷면은 정지 상태에서 각각 자기 쪽 strip이 하나도 없어 참이 된다.
 */
internal data class FoundationReferenceThreeDCurlMeshExtent(
    val leftPx: Float,
    val rightPx: Float,
    val isEmpty: Boolean,
)

/**
 * [strips]가 목적지 노드 안에서 실제로 차지하는 가로 범위를 계산한다 —
 * [foundationReferenceDrawThreeDCurlMesh]가 인라인으로 하던 clamp 산술을 그대로 옮긴 것으로, mesh가
 * 비어 있을 때의 조기 반환 지점을 결과 값의 [FoundationReferenceThreeDCurlMeshExtent.isEmpty]로
 * 표현한다.
 *
 * @param strips [foundationReferenceThreeDCurlStripSpecs]가 만든 순서 있는 목적지 구간들.
 * @param width mesh를 그리는 목적지 노드의 너비, 픽셀 단위다. [foundationReferenceThreeDCurlProgress]가
 *   받는 leaf 너비와 반드시 같은 값은 아니다 — 이 너비는 mesh가 실제로 그려지는 사각형이고, progress의
 *   너비는 fold 진행률을 측정하는 edge 공간이므로, spread에서 두 pane의 폭이 다르면 서로 갈라진다.
 *   둘을 섞어 넘기면 mesh가 잘못된 지점에서 clamp된다.
 * @return [strips]가 비어 있으면 [FoundationReferenceThreeDCurlMeshExtent.isEmpty]가 참인 값; 그
 *   외에는 `[0, width]`로 clamp된 왼쪽/오른쪽 끝을 담은 값.
 */
internal fun foundationReferenceThreeDCurlMeshExtent(
    strips: List<FoundationReferenceThreeDCurlStripSpec>,
    width: Float,
): FoundationReferenceThreeDCurlMeshExtent {
    val meshLeft = strips.minOfOrNull {
        min(it.destinationStartFraction, it.destinationEndFraction)
    }?.times(width)
        ?: return FoundationReferenceThreeDCurlMeshExtent(leftPx = 0f, rightPx = 0f, isEmpty = true)
    val meshRight = strips.maxOf {
        max(it.destinationStartFraction, it.destinationEndFraction)
    } * width
    return FoundationReferenceThreeDCurlMeshExtent(
        leftPx = meshLeft,
        rightPx = meshRight,
        isEmpty = false,
    )
}

/**
 * strip마다 텍스트를 다시 래스터화하지 않고, 하나의 오프스크린 페이지 텍스처로부터 PlayLikeCurl
 * 투영을 그린다.
 *
 * [graphicsLayer]는 전체 페이지를 한 번만 기록한다. 그런 다음 순서 있는 소스 구간들은 공유된
 * 목적지 범위로 오직 가로 텍스처 변환만 적용한다; 반 픽셀의 클립 겹침이 래스터 반올림을 감춘다.
 * 페이지 전체에 걸친 하나의 세로 원근 스케일이, 이전에는 인접한 글리프 조각을 서로 다른 양만큼
 * 옮겨 보고된 세로 절단을 만들어냈던 strip별 y 스케일링을 대체한다. 조명은 보이는 시트 전체에
 * 걸친 하나의 매끄러운 그라데이션이며, cast shadow는 움직이는 바깥쪽 edge에서 시작한다. 뒤로
 * 가는 spread는 [mirrorHorizontally]를 통해 완성된 그리기를 미러링한다.
 *
 * @receiver 기록된 페이지 텍스처를 재생하는 draw scope.
 * @param strips [foundationReferenceThreeDCurlStripSpecs]가 만든 순서 있는 소스·목적지 구간들.
 * @param lighting 이 프레임에 대한 매끄러운 시트 조명과 cast-shadow 강도.
 * @param graphicsLayer 모든 구간이 공유하는 오프스크린 페이지 텍스처.
 * @param width leaf의 너비, 픽셀 단위.
 * @param height leaf의 높이, 픽셀 단위.
 * cast shadow는 시트의 선행 엣지 바깥쪽에 깔린다. 앞면은 spine에서 먼 쪽(롤이 있는
 * `visibleRight`) 바깥, 뒷면은 spine을 넘어간 끝(`visibleLeft`) 바깥이다. 두 면 모두
 * `visibleRight`를 쓰면 뒷면의 그림자가 spine 쪽 시트 안으로 들어가, 전진에서는 거의 보이지 않고
 * 후진에서는 미러 때문에 드러난 페이지 위로 옮겨가 방향에 따라 다르게 보인다.
 *
 * @param mirrorHorizontally 이 노드에서 leaf의 spine이 노드의 오른쪽 edge에 있는지 여부. mesh는
 *   spine을 x = 0에 두고 계산되므로, 참이면 배치가 좌우로 뒤집힌다.
 * @param spanBeyondSpinePx spine(leaf 프레임 x = 0)을 넘어 이 노드 밖까지 mesh가 그려도 되는 거리,
 *   픽셀 단위. spread에서는 반대쪽 pane 너비에 gutter를 더한 값이다. leaf는 자기 pane 노드 하나에만
 *   호스트되고 Compose의 Box는 자식 드로잉을 클립하지 않으므로, 이 값만 열어 주면 시트가 gutter를
 *   건너 반대쪽 pane까지 끊김 없이 이어진다.
 */
private fun ContentDrawScope.foundationReferenceDrawThreeDCurlMesh(
    strips: List<FoundationReferenceThreeDCurlStripSpec>,
    lighting: FoundationReferenceThreeDCurlLightingSpec,
    graphicsLayer: GraphicsLayer,
    width: Float,
    height: Float,
    mirrorHorizontally: Boolean,
    spanBeyondSpinePx: Float = 0f,
) {
    val meshExtent = foundationReferenceThreeDCurlMeshExtent(strips, width)
    if (meshExtent.isEmpty) return
    val visibleLeft = meshExtent.leftPx
    val visibleRight = meshExtent.rightPx
    val castsShadowBeyondTip = strips.all { it.isBackFacing }
    val shadowSpread = width * FoundationReferenceThreeDCurlShadowSpread
    val shadowStart = if (castsShadowBeyondTip) visibleLeft else visibleRight
    val shadowEnd = if (castsShadowBeyondTip) {
        shadowStart - shadowSpread
    } else {
        shadowStart + shadowSpread
    }
    val clipLow = -spanBeyondSpinePx
    val clipHigh = width
    withTransform({ if (mirrorHorizontally) scale(-1f, 1f) }) {
        val shadowLeft = min(shadowStart, shadowEnd).coerceIn(clipLow, clipHigh)
        val shadowRight = max(shadowStart, shadowEnd).coerceIn(clipLow, clipHigh)
        if (lighting.shadowAlpha > 0f && shadowRight > shadowLeft) {
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = lighting.shadowAlpha), Color.Transparent),
                    startX = shadowStart,
                    endX = shadowEnd,
                ),
                topLeft = Offset(shadowLeft, 0f),
                size = Size(shadowRight - shadowLeft, height),
            )
        }
        strips.forEach { strip ->
            val destStart = strip.destinationStartFraction * width
            val destEnd = strip.destinationEndFraction * width
            val left = min(destStart, destEnd).coerceIn(clipLow, clipHigh)
            val right = max(destStart, destEnd).coerceIn(clipLow, clipHigh)
            if (right - left < FoundationReferenceThreeDCurlFlatEpsilon) return@forEach
            val sourceStart = strip.sourceStartFraction * width
            val sourceEnd = strip.sourceEndFraction * width
            val sourceSpan = sourceEnd - sourceStart
            if (abs(sourceSpan) < FoundationReferenceThreeDCurlFlatEpsilon) return@forEach
            val scaleX = (destEnd - destStart) / sourceSpan
            clipRect(
                left = (left - FoundationReferenceThreeDCurlSeamOverlapPx).coerceAtLeast(clipLow),
                top = 0f,
                right = (right + FoundationReferenceThreeDCurlSeamOverlapPx).coerceAtMost(clipHigh),
                bottom = height,
            ) {
                withTransform({ translate(destStart - sourceStart, 0f) }) {
                    withTransform({ scale(scaleX, 1f, pivot = Offset(sourceStart, 0f)) }) {
                        drawLayer(graphicsLayer)
                    }
                }
            }
        }
        if (lighting.frontShadeAlpha > 0f && visibleRight > visibleLeft) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = lighting.backLightAlpha * 0.5f),
                        Color.Black.copy(alpha = lighting.frontShadeAlpha),
                        Color.Transparent,
                    ),
                    startX = visibleLeft,
                    endX = visibleRight,
                ),
                topLeft = Offset(visibleLeft, 0f),
                size = Size(visibleRight - visibleLeft, height),
            )
        }
    }
}

/**
 * 페이지 turn이 문서를 어느 방향으로 이동하는지: [Forward]는 다음 페이지 쪽으로, [Backward]는
 * 이전 페이지 쪽으로. 이는 독자에게 보이는 방향이다; [foundationReferenceCurlGeometryDirection]은
 * 이를 fold 자신이 실제로 렌더링하는 방향으로 매핑하며, 이는 spread에서는 다를 수 있다.
 */
internal enum class FoundationReferenceCurlDirection { Forward, Backward }

/**
 * [foundationReferenceCurlTapAction]이 결정하는, curl pager 위의 탭 하나가 해야 할 일: 이전
 * 페이지로 turn([Backward]), 다음 페이지로 turn([Forward]), 또는 탭이 어느 turn 영역에도
 * 떨어지지 않거나 갈 곳이 없을 때 리더의 컨트롤을 보이거나 숨기기([ToggleControls]).
 */
internal enum class FoundationReferenceCurlTapAction { Backward, ToggleControls, Forward }

/**
 * 페이지 turn이 어느 화면 축을 따라 움직이는지, 그리고 실제 화면 좌표와 이 파일의 fold 계산
 * 사이의 변환. 이 계산은 가로 turn에 대해 한 번만 작성되고, 모든 공식을 중복시키는 대신
 * 너비/높이와 x/y를 맞바꿔 [Vertical]에 재사용된다.
 *
 * [canonicalSize]/[toCanonical]은 그 공유 프레임으로 변환한다; [fromCanonical]은 그 반대로
 * 변환한다. [Horizontal]에서는 양쪽 방향 모두 항등 변환이다; [Vertical]에서는 각각 두 성분을
 * 맞바꾸므로, 세로 turn의 "너비"는 화면의 높이이고 그 "x"는 화면의 y다.
 */
internal enum class FoundationReferenceCurlAxis {
    Horizontal,
    Vertical,
    ;

    /**
     * fold 계산이 바라보는 그대로의 [size]: [Vertical]에서는 너비/높이가 맞바뀌어, 화면 방향과
     * 무관하게 turn 축은 항상 "너비"가 된다.
     */
    fun canonicalSize(size: IntSize): IntSize = when (this) {
        Horizontal -> size
        Vertical -> IntSize(size.height, size.width)
    }

    /** fold 계산이 바라보는 그대로의 [offset]: [canonicalSize]와 같은 이유로 [Vertical]에서는 x/y가 맞바뀐다. */
    fun toCanonical(offset: Offset): Offset = when (this) {
        Horizontal -> offset
        Vertical -> Offset(offset.y, offset.x)
    }

    /**
     * [toCanonical]의 역함수: canonical 공간의 [offset]을 실제 화면 좌표로 다시 변환한다. x/y를
     * 두 번 맞바꾸면 항등 변환이 되므로, 마침 [toCanonical]과 같은 맞바꾸기가 된다.
     */
    fun fromCanonical(offset: Offset): Offset = when (this) {
        Horizontal -> offset
        Vertical -> Offset(offset.y, offset.x)
    }
}

/**
 * canonical 좌표계 기준, 점들의 닫힌 루프로 나타낸 접힌 영역의 윤곽 — [foundationReferenceCurlPolygon]이
 * 만들어내고 [FoundationReferenceCurlFold.polygon]이 fold를 클립하고 그림자를 드리우는 데 쓰는 것.
 *
 * @property vertices 루프를 따라 순서대로 나열된 polygon의 모서리들.
 */
internal data class FoundationPagerCurlPolygon(val vertices: List<Offset>) {
    /**
     * [offset]만큼 옮겨진 [vertices]로, API-28 이전 Android의 shadow 경로가 블러를 잘라내지 않고
     * 담을 수 있을 만큼 큰, 안쪽으로 들어간 bitmap에 그릴 때 쓰인다.
     */
    fun translate(offset: Offset): FoundationPagerCurlPolygon =
        FoundationPagerCurlPolygon(vertices.map { it + offset })

    /**
     * 각 정점 자신의 법선을 따라 [value]만큼 바깥쪽으로 확장된 이 polygon의 복사본으로, 그림자가
     * 그려지는 실루엣을 키워, 그것이 fold 자신의 edge에서 잘리는 대신 그 너머로 블러가 번질 여지를
     * 갖도록 하는 데 쓰인다.
     *
     * 각 정점 법선은 인접한 두 edge 법선의 평균이다(루프의 첫 정점과 마지막 정점이 이웃으로
     * 취급되도록 [wrap]을 통해 계산된다), 이것이 각 edge를 독립적으로 오프셋해 모서리에 틈이나
     * 겹침을 남기는 대신, 모깎인 모서리의 확장이 올바르게 바깥쪽을 가리키도록 유지해 준다.
     *
     * @param value 바깥쪽으로 얼마나 확장할지, 픽셀 단위; [drawFoundationPagerCurlShadow]가
     *   그림자의 블러 반경을 넘긴다.
     * @return 원본과 같은 정점 수와 순서를 가진, 확장된 polygon.
     */
    fun offset(value: Float): FoundationPagerCurlPolygon {
        val edgeNormals = List(vertices.size) { index ->
            val edge = vertices[wrap(index + 1)] - vertices[wrap(index)]
            Offset(edge.y, -edge.x).foundationReferenceNormalized()
        }
        val vertexNormals = List(vertices.size) { index ->
            (edgeNormals[wrap(index - 1)] + edgeNormals[wrap(index)]).foundationReferenceNormalized()
        }
        return FoundationPagerCurlPolygon(
            vertices.mapIndexed { index, vertex -> vertex + vertexNormals[index] * value },
        )
    }

    /** [axis]를 통해 canonical 좌표계에서 화면 좌표계로 다시 변환된, 그릴 수 있는 [Path]로서의 이 polygon. */
    fun toPath(axis: FoundationReferenceCurlAxis): Path = vertices.foundationReferencePath(axis)

    /** [index]를 `0 until vertices.size`로 감싸서, 첫 정점과 마지막 정점이 이웃으로 취급되게 한다. */
    private fun wrap(index: Int): Int = ((index % vertices.size) + vertices.size) % vertices.size
}

internal data class FoundationPagerRenderProfile(
    val threeDCurlGrid: Int,
    val curlShadowLayers: Int,
)

internal expect val foundationPagerRenderProfile: FoundationPagerRenderProfile

/**
 * 접힌 부분의 드롭 섀도를 그리며, 플랫폼마다 하나씩 `expect`되어 있다. Compose Multiplatform의
 * 공통 [DrawScope]에는 모양을 블러 처리해 그림자로 만드는 공유된 방법이 없기 때문이다 — 각
 * 플랫폼의 actual은 자신만의 네이티브 캔버스 API에 의존한다(예를 들어 Android actual은
 * `android.graphics.Paint`에 shadow layer를 설정한다).
 *
 * @receiver 그림자를 렌더링할, 화면 좌표계 기준 draw scope.
 * @param polygon canonical 좌표계 기준, 접힌 영역의 윤곽; actual 구현은 블러가 fold의 edge 너머로
 *   번질 여지를 갖도록 이를 [radius]만큼 스스로 확장할 것으로 기대된다
 *   ([FoundationPagerCurlPolygon.offset] 참고).
 * @param axis [polygon]을 canonical 좌표계에서 화면 좌표계로 변환하는 데 필요하다.
 * @param radius 그림자의 블러 반경, 픽셀 단위.
 * @param shadowOffset 그림자가 fold로부터 얼마나 떨어져 있는지, 픽셀 단위.
 * @param color 알파를 포함한 그림자의 색상.
 */
internal expect fun DrawScope.drawFoundationPagerCurlShadow(
    polygon: FoundationPagerCurlPolygon,
    axis: FoundationReferenceCurlAxis,
    radius: Float,
    shadowOffset: Offset,
    color: Color,
)

/**
 * 이 점들을 순서대로 [Path]로 연결하며, 먼저 [axis]를 통해 각각을 canonical 좌표계에서 화면
 * 좌표계로 변환한다. 경로를 첫 점으로 명시적으로 닫지는 않는다 — 여기 있는 모든 호출자는 결과를
 * 오직 클립 모양으로만 쓰는데, 클립은 열린 윤곽과 닫힌 윤곽을 똑같이 취급한다.
 *
 * @receiver 연결되어야 할 순서대로, canonical 좌표계 기준의 polygon 점들.
 * @param axis 각 점을 화면 좌표계로 다시 변환하는 데 필요하다.
 */
private fun List<Offset>.foundationReferencePath(axis: FoundationReferenceCurlAxis): Path = Path().apply {
    this@foundationReferencePath.forEachIndexed { index, point ->
        val actual = axis.fromCanonical(point)
        if (index == 0) moveTo(actual.x, actual.y) else lineTo(actual.x, actual.y)
    }
}

/**
 * 표준 2D 회전 행렬을 사용해, 원점을 중심으로 [angle] 라디안만큼 회전된 이 벡터.
 *
 * @receiver 회전시킬 벡터로, 화면 위치가 아니라 원점에 대한 상대값으로 취급된다.
 * @param angle 회전량, 라디안 단위.
 */
private fun Offset.foundationReferenceRotate(angle: Float): Offset {
    val sin = sin(angle)
    val cos = cos(angle)
    return Offset(x * cos - y * sin, x * sin + y * cos)
}

/**
 * 단위 길이로 스케일된 이 벡터, 또는 이미 길이가 0이면 0으로 나누는 대신 그대로 둔 것.
 */
private fun Offset.foundationReferenceNormalized(): Offset {
    val distance = getDistance()
    return if (distance != 0f) this / distance else this
}

/**
 * pager는 항상 정확히 이만큼의 가상 페이지 — 이전, 현재, 다음 — 를 갖는다.
 * [FoundationPagerCurlReferenceImpl]은 내부 pager를 절대 스크롤하지 않고, 대신 그 세 슬롯을 직접
 * 쌓고 접기 때문이다.
 */
private const val FoundationReferencePagerPageCount = 3

/**
 * pager의 인덱스는 제스처 생명주기 내내 여기에 고정된다; 이 파일은 pager 스크롤 위치 대신 fold
 * animatable을 통해 페이지 turn을 구동한다.
 */
private const val FoundationReferenceCenterPage = 1

/** 탭으로 촉발된 페이지 turn이 기본적으로 애니메이션되는 시간, 밀리초 단위. */
private const val FoundationReferenceTapDurationMillis = 450

/**
 * pane의 왼쪽/위쪽 1/4 지점에서의 탭([foundationReferenceCurlTapAction])은 이전 페이지로 넘어간다.
 */
private const val FoundationReferencePreviousTapZoneRatio = 0.25f

/**
 * pane의 오른쪽/아래쪽 1/4 지점에서의 탭([foundationReferenceCurlTapAction])은 다음 페이지로
 * 넘어간다; 이것과 [FoundationReferencePreviousTapZoneRatio] 사이의 가운데 절반은 대신 컨트롤을
 * 토글한다.
 */
private const val FoundationReferenceNextTapZoneRatio = 0.75f

/**
 * 드래그나 fling이 취소가 아니라 완료된 turn으로 치기 전에 필요한 거리
 * ([foundationReferenceCurlDragSucceeds])의 이 비율만큼은 이동해야 한다.
 */
private const val FoundationReferenceDragThresholdRatio = 0.2f

/**
 * 단일 pane fold가 다시 그린 콘텐츠 위에 그려지는 흰색 오버레이가 얼마나 불투명한지
 * ([foundationReferenceDrawCurl]) — 앞 페이지가 변화 없이 그대로 비쳐 보이는 대신 종이 한 장의
 * 뒷면으로 읽힐 만큼 충분히 높다.
 */
private const val FoundationReferenceBackOverlayAlpha = 0.9f

/**
 * fold의 드롭 섀도 색상의 알파([FoundationReferenceCurlFold.drawShadow]) — 딱딱한 실루엣이 아니라
 * 부드러운 cast shadow로 읽힐 만큼 충분히 낮다.
 */
private const val FoundationReferenceShadowAlpha = 0.2f

/** 3D Curl crease highlight의 화면-공간 너비, 픽셀 단위. */
private const val FoundationReferenceThreeDRimWidthPx = 2f

/**
 * 최대 3D 롤링-crease 기울기로 쓰이는, 더 짧은 leaf 변의 비율. 참조 PlayLikeCurl mesh는 `0.18`
 * curl radius를 사용한다; 같은 정규화된 양을 적용하면 고정된 픽셀 거리로 좁은 페이지를 과도하게
 * 기울이는 대신, 휴대폰·태블릿·spread leaf 전반에 걸쳐 근사치가 일관되게 유지된다.
 */
private const val FoundationReferenceThreeDCurlTiltRatio = 0.18f

/**
 * 시트가 감기는 실린더의 반지름으로, leaf 너비에 대한 비율이다. 감김 호 길이 `PI * radius`와 최대
 * 깊이 `2 * radius`를 함께 결정한다. 비율로 두기 때문에 단일 페이지와 spread leaf에서 curl의
 * 굵기가 같게 보인다.
 */
private const val FoundationReferenceThreeDCurlRadius = 0.18f

/**
 * 실린더 반지름을 0에서 [FoundationReferenceThreeDCurlRadius]까지 끌어올리는 초기 progress 구간.
 * 0에서 시작하므로 정지 상태의 매핑이 정확히 항등이고, turn이 시작되면서 crease가 날카로운
 * 접힘에서 굵은 롤로 부드럽게 자란다.
 */
private const val FoundationReferenceThreeDCurlRadiusRampEnd = 0.20f

/**
 * [foundationReferenceThreeDCurlStripSpecs]와 [foundationReferenceDrawThreeDCurlMesh]가 0이
 * 아니라고 취급하는 가장 작은 실린더 반지름, 컬럼 너비, 또는 소스 span으로, 이보다 작으면 사라지는
 * span으로 나누는 대신 컬럼을 평평하게 그리거나 건너뛴다.
 */
private const val FoundationReferenceThreeDCurlFlatEpsilon = 1e-4f

/**
 * 인접한 목적지 클립 사이의 서브픽셀 겹침. 두 strip 모두 같은 캡처된 페이지 layer를 샘플링하므로,
 * 이 반 픽셀 여유는 글리프를 다시 그리거나 독립적으로 클립하지 않고도 래스터 반올림을 감싼다.
 */
private const val FoundationReferenceThreeDCurlSeamOverlapPx = 0.5f

/**
 * mesh의 투영된 앞쪽 edge를 지나쳐, leaf 너비에 대한 비율로, [foundationReferenceDrawThreeDCurlMesh]의
 * cast-shadow 그라데이션이 얼마나 멀리까지 옅어지는지 — 들려 올라간 종이가 드러내고 있는 페이지 위에
 * 드리우는 부드러운 반음영.
 */
private const val FoundationReferenceThreeDCurlShadowSpread = 0.12f

/** fold 그림자의 블러 반경([FoundationReferenceCurlFold.shadowRadius]). */
private val FoundationReferenceShadowRadius = 15.dp

/**
 * [foundationReferenceCurlFold]가 이를 부호를 뒤집고 fold 자신의 각도에 맞춰 회전시키기 전, dp
 * 단위의 fold 그림자 변위; 호출부가 그 부호를 뒤집으므로 크기만 중요하다.
 */
private val FoundationReferenceShadowOffsetX = (-5).dp

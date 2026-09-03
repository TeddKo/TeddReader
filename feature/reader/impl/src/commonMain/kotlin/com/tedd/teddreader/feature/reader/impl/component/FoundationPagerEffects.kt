package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.feature.reader.impl.autoScrollDistancePx
import com.tedd.teddreader.feature.reader.impl.autoScrollLineDelayMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 이 style이 슬롯별 transform을 구동하기 위해 pager의 실시간 스크롤 오프셋을 읽는지 여부.
 * 읽지 않는 style은 슬롯이 프레임마다 재구성될 이유가 없으므로, 트랜지션 modifier를 아예
 * 붙이지 않는다.
 *
 * `when`을 `else` 없이 남겨 두어, [PageAnimation]에 항목이 추가되면 여기와
 * [readsGestureState] 두 곳이 컴파일 에러로 드러나게 한다 — 새 애니메이션이 조용히 잘못된
 * 기본값을 물려받는 대신 자기 특성을 스스로 밝히도록 강제한다.
 */
private val PageAnimation.readsPagerOffset: Boolean
    get() = when (this) {
        PageAnimation.FLUID_PAGER,
        PageAnimation.CIRCLE_REVEAL,
        PageAnimation.MOVIE_CAROUSEL,
        PageAnimation.PAGE_FLIP,
            -> true

        PageAnimation.NONE,
        PageAnimation.SLIDE,
        PageAnimation.FADE,
        PageAnimation.SCROLL,
        PageAnimation.BOOK_CURL,
        PageAnimation.SHEET_FLIP,
        PageAnimation.CURL_PAGER,
        PageAnimation.THREE_D_CURL,
            -> false
    }

/**
 * 이 style이 원시 포인터 위치([FoundationPagerGestureTracker])를 읽는지 여부. 읽지 않는
 * style에서는 제스처 추적용 `pointerInput`을 설치조차 하지 않는다 — 추적기는 이제 포인터 이벤트마다
 * 재구성을 일으키지는 않지만, 여전히 이벤트마다 snapshot을 갱신해 그것을 읽는 그리기 캐시를
 * 무효화하므로, 아무도 읽지 않는 style에서는 순수한 낭비다.
 *
 * `else`가 없는 이유는 [readsPagerOffset]과 같다.
 */
private val PageAnimation.readsGestureState: Boolean
    get() = when (this) {
        PageAnimation.FLUID_PAGER,
        PageAnimation.CIRCLE_REVEAL,
        PageAnimation.MOVIE_CAROUSEL,
            -> true

        PageAnimation.NONE,
        PageAnimation.SLIDE,
        PageAnimation.FADE,
        PageAnimation.SCROLL,
        PageAnimation.BOOK_CURL,
        PageAnimation.SHEET_FLIP,
        PageAnimation.CURL_PAGER,
        PageAnimation.THREE_D_CURL,
        PageAnimation.PAGE_FLIP,
            -> false
    }

/**
 * [PageAnimation.SLIDE]/[PageAnimation.SHEET_FLIP]/[PageAnimation.FLUID_PAGER]/
 * [PageAnimation.CIRCLE_REVEAL]/[PageAnimation.MOVIE_CAROUSEL]/[PageAnimation.PAGE_FLIP]의
 * 배후에 있는 pager: 3개의 슬롯(이전/현재/다음, [FoundationPagerPage] 참고)으로 고정된 Foundation
 * `HorizontalPager`/`VerticalPager`로, turn 사이에는 항상 [FoundationCenterPage]에 위치하며,
 * [pageAnimation]별 커스텀 트랜지션 modifier가 일반 Foundation 스와이프 위에 fold/reveal/carousel/
 * flip 효과를 그린다.
 *
 * **pager를 문서 상태와 동기화된 채로 가운데에 유지하기.** pager 자신의 [pagerState]는 0..2 범위의
 * 슬롯 인덱스를 추적하는 반면, 문서의 실제 페이지는 한 단계 위 `ReaderViewModel`의 [pageKey]에
 * 담겨 있다. turn 하나는 리더의 페이지와 pager의 슬롯 둘 다 가운데로 이동시켜야 하지만, 그 둘을 같은
 * 순간에 쓸 수는 없다: 이동은 view model을 거쳐 나갔다가 이후 프레임에서 새 [pageKey] 상태로
 * 되돌아온다. 세 슬롯의 콘텐츠를 [pageKey]로부터 곧바로 그린다면, 새 [pageKey]가 따라잡을 때까지
 * 페이지가 이미 안착된 스크롤 위치에 그대로 남아 있는 모습이 보이게 된다 — turn마다 순간적으로 잘못된
 * 텍스트가 보이는 것이다. `renderedPageKey` 변수는 이를 막기 위해 존재한다: 세 슬롯은 [pageKey]가
 * 아니라 `renderedPageKey`로부터 그려지며, `renderedPageKey`는 [pageKey]의 변경과 pager를 가운데로
 * 되돌리는 동작이 같은 프레임에 화면에 함께 도달했을 때만 앞으로 나아간다(아래
 * `LaunchedEffect(pageKey, pageCount, pageStep)` 블록 참고 — 이 블록이 `renderedPageKey`를
 * 재설정하는 동시에 pager를 가운데로 되돌린다).
 *
 * 같은 effect가 pager를 `scrollToPage`가 아니라
 * `pagerState.requestScrollToPage(FoundationCenterPage)`로 가운데로 되돌린다: `requestScrollToPage`는
 * pager 자신의 위치를 즉시 옮기고 레이아웃이 다음 측정 패스에서 따라잡도록 남겨 두므로, 가운데로
 * 되돌아가는 동작과 슬롯이 보여줄 내용을 정하는 `renderedPageKey` 갱신이 하나가 다른 하나를 뒤따르는
 * 대신 같은 프레임에 함께 반영된다.
 *
 * **안착된 스크롤을 정확히 한 번만 소비하기.** `LaunchedEffect(pagerState, pageKey,
 * renderedPageKey, pageCount, pageStep, isManualDragInProgress)` 블록은 pager가 슬롯 0이나 2에
 * 안착하는 것(완료된 turn)을 지켜보다가 이를 [onPreviousPage]/[onNextPage]를 통해 위로 보고한다.
 * `pageKey != renderedPageKey`인 동안에는 항상 그대로 빠져나오는데, 두 키가 어긋나 있다는 것은
 * 방금 보고한 turn이 아직 새 [pageKey] 상태로 되돌아오는 중이며 pager는 여전히 그것을 기다리며
 * 가운데를 벗어난 채 멈춰 있다는 뜻이고, 같은 안착 슬롯을 두 번째로 보고하면 turn 한 번에 리더가
 * 페이지 두 개를 이동하게 되기 때문이다. 안착 슬롯이 한 번 보고되고 나면, 더 갈 곳이 없는 turn(이전/
 * 다음 페이지가 없음)만이 pager를 스스로 가운데로 되돌린다 — 그 밖의 모든 경우는 새 키가 도착하면
 * 위의 `renderedPageKey`가 구동하는 effect가 되돌려 놓는다.
 *
 * **탭 영역.** [tapModifier]의 `onTap`은 `foundationPagerTapAction`을 통해 탭 위치를
 * [FoundationPagerTapAction.Previous]/[FoundationPagerTapAction.Next]/
 * [FoundationPagerTapAction.ToggleControls]로 해석한다. 이전/다음 영역에서의 탭인데 그 방향에
 * 인접 페이지가 없으면(책의 시작이나 끝) 가운데 영역과 마찬가지로 컨트롤 토글로 넘어간다 — 탭은
 * 결코 조용히 삼켜져서는 안 되며, 이 fallthrough가 생기기 전에는 마지막 페이지에서 정확히 그런 일이
 * 벌어졌었다.
 *
 * @param pageKey 현재 페이지 인덱스.
 * @param pageCount 지금까지 알려진 전체 페이지 수.
 * @param pageStep 한 번의 turn이 몇 페이지를 진행시키는지.
 * @param pageTurnMode 페이지가 가로축과 세로축 중 어느 쪽으로 넘어가는지.
 * @param pageAnimation 이 pager가 지원하는 스타일 중 현재 활성화된 것으로, 슬롯마다 적용되는
 *   트랜지션 modifier를 결정한다.
 * @param canRequestNextPage 알려진 끝에 있는 텍스트 문서가 페이지 나누기가 아직 끝나지 않은
 *   동안에도 다음 요청을 계속 전달해야 하는지 여부.
 * @param pageMoveRequest 대기 중인 프로그래밍적 페이지 이동 요청, 없으면 null.
 * @param onPageMoveRequestConsumed [pageMoveRequest]의 id와 함께, 그것이 애니메이션되었거나 갈
 *   곳이 없다고 확인된 뒤 호출된다.
 * @param onPreviousPage 문서 시작 쪽으로의 turn이 안착하면 호출된다.
 * @param onNextPage 문서 끝 쪽으로의 turn이 안착하면 호출된다.
 * @param onToggleControls 탭이 두 turn 영역 바깥에 떨어지거나, 자동 스크롤 도중이면 호출된다.
 * @param onDoubleTap 더블 탭 시 탭 위치와 함께 호출된다; null이면 이를 비활성화한다.
 * @param isAutoScrollEnabled 자동 스크롤이 현재 turn을 구동하고 있는지 여부.
 * @param autoScrollMode 따를 자동 스크롤 모드.
 * @param autoScrollSpeed 설정된 자동 스크롤 속도.
 * @param autoScrollLineHeightPx line 모드 자동 스크롤이 쓰는, 현재 style의 픽셀 단위 줄 높이.
 * @param autoScrollDensity 자동 스크롤 속도를 픽셀로 환산하는 데 쓰이는 화면 밀도.
 * @param onAutoScrollStop 자동 스크롤이 문서 끝에 닿아 멈춰야 할 때 호출된다.
 * @param onMovieTransitionProgressChanged movie-carousel 트랜지션의 진행률과 함께 호출된다.
 * @param paneCount 몇 개의 페이지 pane이 나란히 보이는지(spread면 2, 그 외엔 1); page-flip
 *   style에서 [FoundationPageFlipLayout.WholePage]와 [FoundationPageFlipLayout.SplitHalfFold]
 *   중 무엇을 쓸지를 결정한다.
 * @param modifier pager의 루트에 적용되는 modifier.
 * @param content 주어진 인덱스의 페이지를 렌더링한다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FoundationEffectPager(
    pageKey: Int,
    pageCount: Int,
    pageStep: Int,
    pageTurnMode: PageTurnMode,
    pageAnimation: PageAnimation,
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
    autoScrollLineHeightPx: Float,
    autoScrollDensity: Float,
    onAutoScrollStop: () -> Unit,
    onMovieTransitionProgressChanged: (Float) -> Unit,
    paneCount: Int = 1,
    modifier: Modifier = Modifier,
    content: @Composable (page: Int) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = FoundationCenterPage,
        pageCount = { FoundationPagerPageCount },
    )
    val axis = if (pageTurnMode == PageTurnMode.HORIZONTAL) {
        FoundationPagerAxis.Horizontal
    } else {
        FoundationPagerAxis.Vertical
    }
    val readsPagerOffset = pageAnimation.readsPagerOffset
    val readsGestureState = pageAnimation.readsGestureState
    val fluidEdge = remember { FoundationFluidEdge(FoundationFluidPointCount) }
    val gesture = remember { FoundationPagerGestureTracker() }
    val coroutineScope = rememberCoroutineScope()
    val settleAnimationSpec = remember {
        tween<Float>(FoundationPagerSettleMillis, easing = FastOutSlowInEasing)
    }
    var renderedPageKey by remember { mutableStateOf(pageKey) }
    val previousPage = readerPagerAdjacentPage(renderedPageKey, pageCount, pageStep, -1)
    val nextPage = readerPagerAdjacentPage(renderedPageKey, pageCount, pageStep, 1)
    val canGoForward = readerPagerCanAdvanceForward(nextPage != null, canRequestNextPage)
    val pageFlipLayout = foundationPageFlipLayout(pageStep = pageStep, paneCount = paneCount)
    var isManualDragInProgress by remember { mutableStateOf(false) }
    val manualDragDistancePx = remember { floatArrayOf(0f) }

    /**
     * 손을 뗀 뒤 pager를 목표 슬롯으로 데려가는 진행 중인 정착 애니메이션과, 그것을 시작시킨
     * 드래그의 세대 번호. 둘 다 관찰되지 않는 홀더인 이유는 제스처 콜백에서 갱신되기 때문이다 —
     * snapshot state였다면 포인터를 뗄 때마다 재구성을 일으켰을 것이다.
     *
     * 세대 번호는 낡은 정착 애니메이션이 자기 뒷정리로 새 드래그를 망가뜨리는 것을 막는다:
     * 예전에는 정착이 끝나거나 취소될 때 무조건 `isManualDragInProgress`를 false로 되돌렸는데,
     * 그 사이에 사용자가 다시 드래그를 시작했다면 그 false가 진행 중인 드래그 위에 덮어써져
     * 안착 보고 effect가 아직 끝나지도 않은 turn을 페이지 이동으로 보고할 수 있었다.
     */
    val settleJob = remember { arrayOfNulls<Job>(1) }
    val dragGeneration = remember { intArrayOf(0) }
    LaunchedEffect(pageMoveRequest?.id) {
        val request = pageMoveRequest ?: return@LaunchedEffect
        try {
            val targetPagerPage = when (request.movement) {
                ReaderPageMovement.Previous -> FoundationPreviousPage.takeIf { previousPage != null }
                ReaderPageMovement.Next -> FoundationNextPage.takeIf { canGoForward }
            }
            if (targetPagerPage != null) {
                pagerState.animateScrollToPage(
                    page = targetPagerPage,
                    animationSpec = settleAnimationSpec,
                )
            }
        } finally {
            onPageMoveRequestConsumed(request.id)
        }
    }

    LaunchedEffect(pageKey, pageStep, axis) {
        fluidEdge.reset()
        if (pagerState.currentPage != FoundationCenterPage) {
            pagerState.requestScrollToPage(FoundationCenterPage)
        }
        renderedPageKey = pageKey
    }
    LaunchedEffect(isAutoScrollEnabled) {
        if (isAutoScrollEnabled) {
            gesture.reset()
            fluidEdge.reset()
        }
    }
    LaunchedEffect(pageAnimation) {
        var previousFrameMillis = 0L
        while (pageAnimation == PageAnimation.FLUID_PAGER) {
            withFrameMillis { frameMillis ->
                if (previousFrameMillis != 0L) {
                    fluidEdge.tick((frameMillis - previousFrameMillis).coerceAtLeast(0L) / FoundationFrameMillis)
                }
                previousFrameMillis = frameMillis
            }
        }
    }
    val latestOnPreviousPage by rememberUpdatedState(onPreviousPage)
    val latestOnNextPage by rememberUpdatedState(onNextPage)
    val latestOnMovieTransitionProgressChanged by rememberUpdatedState(onMovieTransitionProgressChanged)
    LaunchedEffect(pagerState, pageKey, renderedPageKey, pageCount, pageStep, isManualDragInProgress) {
        if (pageKey != renderedPageKey) return@LaunchedEffect
        snapshotFlow { Triple(pagerState.currentPage, pagerState.isScrollInProgress, isManualDragInProgress) }
            .filter { (_, isScrollInProgress, manualInProgress) -> !isScrollInProgress && !manualInProgress }
            .map { (page, _, _) -> page }
            .distinctUntilChanged()
            .collect { page ->
                when (page) {
                    FoundationPreviousPage -> {
                        if (previousPage != null) {
                            latestOnPreviousPage()
                        } else {
                            pagerState.requestScrollToPage(FoundationCenterPage)
                        }
                    }
                    FoundationNextPage -> {
                        when {
                            nextPage != null -> latestOnNextPage()
                            canRequestNextPage -> {
                                latestOnNextPage()
                                pagerState.requestScrollToPage(FoundationCenterPage)
                            }
                            else -> {
                                onAutoScrollStop()
                                pagerState.requestScrollToPage(FoundationCenterPage)
                            }
                        }
                    }
                }
            }
    }
    LaunchedEffect(
        pagerState,
        pageKey,
        pageCount,
        pageStep,
        pageAnimation,
        isAutoScrollEnabled,
        autoScrollMode,
        autoScrollSpeed,
        autoScrollLineHeightPx,
        autoScrollDensity,
    ) {
        if (!isAutoScrollEnabled || autoScrollMode == AutoScrollMode.PAGE) return@LaunchedEffect
        if (nextPage == null) {
            onAutoScrollStop()
            return@LaunchedEffect
        }

        pagerState.foundationAutoScroll(
            mode = autoScrollMode,
            speed = autoScrollSpeed,
            density = autoScrollDensity,
            lineHeightPx = autoScrollLineHeightPx,
        )
    }
    LaunchedEffect(pageAnimation, pagerState) {
        if (pageAnimation != PageAnimation.MOVIE_CAROUSEL) {
            latestOnMovieTransitionProgressChanged(0f)
            return@LaunchedEffect
        }

        try {
            snapshotFlow {
                max(
                    pagerState.foundationAdjacentProgress(FoundationPreviousPage),
                    pagerState.foundationAdjacentProgress(FoundationNextPage),
                )
            }
                .distinctUntilChanged()
                .collect { latestOnMovieTransitionProgressChanged(it) }
        } finally {
            latestOnMovieTransitionProgressChanged(0f)
        }
    }

    val gestureModifier = if (isAutoScrollEnabled || !readsGestureState) {
        Modifier
    } else {
        Modifier.pointerInput(axis, previousPage != null, canGoForward) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressedChange = event.changes.firstOrNull { it.pressed }
                    when {
                        pressedChange == null -> gesture.release()
                        !gesture.pressed -> {
                            fluidEdge.reset()
                            gesture.press(pressedChange.position)
                        }
                        else -> {
                            val position = pressedChange.position
                            val blockDrag = foundationPagerShouldBlockDrag(
                                primaryDelta = axis.primary(position) - axis.primary(gesture.start),
                                hasPreviousPage = previousPage != null,
                                hasNextPage = canGoForward,
                            )
                            if (blockDrag) {
                                event.changes.forEach { it.consume() }
                            }
                            gesture.move(if (blockDrag) gesture.start else position, axis)
                        }
                    }
                }
            }
        }
    }

    val manualDragModifier = if (isAutoScrollEnabled) {
        Modifier
    } else {
        Modifier.draggable(
            orientation = if (axis == FoundationPagerAxis.Horizontal) Orientation.Horizontal else Orientation.Vertical,
            state = rememberDraggableState { delta ->
                val nextDistance = manualDragDistancePx[0] + delta
                val blocked = foundationPagerShouldBlockDrag(
                    primaryDelta = nextDistance,
                    hasPreviousPage = previousPage != null,
                    hasNextPage = canGoForward,
                )
                if (!blocked) {
                    manualDragDistancePx[0] = nextDistance
                    pagerState.dispatchRawDelta(-delta)
                }
            },
            onDragStarted = {
                dragGeneration[0]++
                settleJob[0]?.cancel()
                settleJob[0] = null
                isManualDragInProgress = true
                manualDragDistancePx[0] = 0f
            },
            onDragStopped = { velocity ->
                val generation = dragGeneration[0]
                val targetOffset = foundationPagerDragTargetOffset(
                    dragDistancePx = manualDragDistancePx[0],
                    velocityPxPerSecond = velocity,
                    viewportExtentPx = pagerState.layoutInfo.viewportSize.let {
                        if (axis == FoundationPagerAxis.Horizontal) it.width.toFloat() else it.height.toFloat()
                    },
                    hasPreviousPage = previousPage != null,
                    hasNextPage = canGoForward,
                )
                manualDragDistancePx[0] = 0f
                settleJob[0] = coroutineScope.launch {
                    try {
                        pagerState.animateScrollToPage(
                            page = (FoundationCenterPage + targetOffset).coerceIn(0, FoundationPagerPageCount - 1),
                            animationSpec = settleAnimationSpec,
                        )
                    } finally {
                        if (dragGeneration[0] == generation) {
                            isManualDragInProgress = false
                            manualDragDistancePx[0] = 0f
                        }
                    }
                }
            },
        )
    }

    val tapModifier = Modifier.pointerInput(axis, pagerState, isAutoScrollEnabled, onToggleControls, onDoubleTap, previousPage, nextPage, canRequestNextPage) {
        detectTapGestures(
            onDoubleTap = onDoubleTap,
            onTap = { position ->
                if (isAutoScrollEnabled) {
                    onToggleControls()
                } else {
                    val primary = if (axis == FoundationPagerAxis.Horizontal) position.x else position.y
                    val extent = if (axis == FoundationPagerAxis.Horizontal) size.width else size.height
                    when (
                        foundationPagerTapAction(
                            primary = primary,
                            extent = extent,
                            hasPreviousPage = previousPage != null,
                            hasNextPage = canGoForward,
                        )
                    ) {
                        FoundationPagerTapAction.Previous -> coroutineScope.launch {
                            pagerState.animateScrollToPage(
                                page = FoundationPreviousPage,
                                animationSpec = settleAnimationSpec,
                            )
                        }

                        FoundationPagerTapAction.Next -> coroutineScope.launch {
                            pagerState.animateScrollToPage(
                                page = FoundationNextPage,
                                animationSpec = settleAnimationSpec,
                            )
                        }

                        FoundationPagerTapAction.ToggleControls -> onToggleControls()
                    }
                }
            },
        )
    }

    val pagerModifier = modifier
        .fillMaxSize()
        .then(gestureModifier)
        .then(manualDragModifier)
        .then(tapModifier)

    /**
     * 슬롯 하나의 전체 렌더링으로, 가로/세로 pager 분기가 같은 본문을 두 벌로 들고 있지 않도록
     * 한 곳에 모아 둔다. 두 벌이던 시절 이 본문은 글자 하나 다르지 않은 40여 줄이었고, 한쪽만
     * 고치는 실수를 부르는 자리였다.
     */
    val pageSlot: @Composable (pagerPage: Int) -> Unit = { pagerPage ->
        // 연속값(오프셋·진행률)은 composition에서 읽지 않는다. 읽는 순간 이 슬롯이 스크롤 프레임마다
        // 재구성되기 때문이다. 대신 provider로 넘겨 graphicsLayer/drawWithCache 블록 안에서 읽게
        // 하고, composition은 아래 이산 [FoundationPagerTurnState]만 관찰한다.
        //
        // PAGE_FLIP의 fold 기하는 슬롯 트리 자체를 가르는 구조적 임계값 — `0`, 부호, `0.5` — 에
        // 매달려 있다. 그 임계값들만 이산 [FoundationPageFlipPhase]로 뽑아 composition이 관찰하게
        // 하고, 회전·그림자 같은 연속값은 다른 style과 마찬가지로 provider로 넘긴다. 이제 flip 슬롯도
        // 임계값을 넘을 때만 재구성된다.
        val isPageFlip = pageAnimation == PageAnimation.PAGE_FLIP
        val offsetProvider: () -> Float = { pagerState.foundationOffsetForPage(pagerPage) }
        val flipPhaseState by remember(pagerState, pagerPage) {
            derivedStateOf { foundationPageFlipPhase(pagerState.foundationOffsetForPage(pagerPage)) }
        }
        // PAGE_FLIP이 아닐 때는 위 파생 상태를 아예 읽지 않는다 — 읽으면 그 style들이 필요도 없는
        // 임계값 변화에 재구성된다.
        val flipPhase = if (isPageFlip) flipPhaseState else FoundationPageFlipPhase.Resting
        val turnState by remember(pagerState, gesture) {
            derivedStateOf {
                val phase = gesture.phase
                val turn = foundationActivePageTurn(
                    gestureActive = phase.active,
                    gestureSide = phase.side,
                    previousProgress = pagerState.foundationAdjacentProgress(FoundationPreviousPage),
                    nextProgress = pagerState.foundationAdjacentProgress(FoundationNextPage),
                )
                FoundationPagerTurnState(side = turn.side, isActive = turn.progress > 0f)
            }
        }
        // turnState.side 로 진행률을 골라 오면 안 된다: [foundationActivePageTurn]에는 "드래그는
        // 활성인데 방향이 아직 확정되지 않았으면 진행률을 0으로 보고한다"는 규칙이 있고, 그 규칙이
        // 바로 방향이 정해지기 전 한 프레임 동안 반대쪽 이웃이 비치는 것을 막는다. composition에서
        // 고른 side를 draw 시점에 그대로 쓰면 그 사이 상태가 바뀌었을 때 규칙이 빠진다. 같은 함수를
        // draw 시점에 다시 돌려 예전 값과 정확히 같게 만든다.
        val turnProvider: () -> FoundationActivePageTurn = {
            val phase = gesture.phase
            foundationActivePageTurn(
                gestureActive = phase.active,
                gestureSide = phase.side,
                previousProgress = pagerState.foundationAdjacentProgress(FoundationPreviousPage),
                nextProgress = pagerState.foundationAdjacentProgress(FoundationNextPage),
            )
        }
        val incomingPage = if (
            !isPageFlip ||
            pageFlipLayout != FoundationPageFlipLayout.SplitHalfFold ||
            flipPhase.isResting
        ) {
            null
        } else if (flipPhase.isForward) {
            readerPagerDisplayedPage(renderedPageKey, nextPage, 1, canRequestNextPage)
        } else {
            previousPage
        }
        FoundationPageFlipAwareBox(
            pageAnimation = pageAnimation,
            axis = axis,
            flipPhase = flipPhase,
            offsetProvider = offsetProvider,
            pageFlipLayout = pageFlipLayout,
            isCurrentPage = pagerPage == FoundationCenterPage,
            modifier = if (readsPagerOffset) {
                Modifier
                    .fillMaxSize()
                    .foundationEffectPageModifier(
                        pagerPage = pagerPage,
                        axis = axis,
                        pageAnimation = pageAnimation,
                        offsetProvider = offsetProvider,
                        turnState = turnState,
                        turnProvider = turnProvider,
                        flipPhase = flipPhase,
                        gesture = gesture,
                        fluidEdge = fluidEdge,
                    )
            } else {
                Modifier.fillMaxSize()
            },
            documentPage = readerPagerDisplayedPage(
                currentPage = renderedPageKey,
                adjacentPage = readerPagerAdjacentPage(
                    renderedPageKey,
                    pageCount,
                    pageStep,
                    pagerPage - FoundationCenterPage,
                ),
                pageOffset = pagerPage - FoundationCenterPage,
                canRequestNextPage = canRequestNextPage,
            ),
            incomingPage = incomingPage,
            content = content,
        )
    }

    if (axis == FoundationPagerAxis.Vertical) {
        VerticalPager(
            state = pagerState,
            modifier = pagerModifier,
            userScrollEnabled = false,
            beyondViewportPageCount = 1,
        ) { pagerPage -> pageSlot(pagerPage) }
    } else {
        HorizontalPager(
            state = pagerState,
            modifier = pagerModifier,
            userScrollEnabled = false,
            beyondViewportPageCount = 1,
        ) { pagerPage -> pageSlot(pagerPage) }
    }
}

/**
 * [PageAnimation.BOOK_CURL]/[PageAnimation.CURL_PAGER]의 배후에 있는 pager: 실제 pagecurl
 * 제스처와 렌더링 상태 머신을 소유하는 [FoundationPagerCurlReferenceImpl]로 그대로 위임하는 얇은
 * 래퍼다. 이 래퍼가 존재하는 이유는 오직 [ReaderPager]가 [FoundationEffectPager]에 대해서와 같은
 * 방식으로 [PageAnimation]에 따라 분기할 수 있도록 하기 위함이며, 그래서 호출자마다 curl 구현의
 * 이름을 알 필요가 없다.
 *
 * @param pageKey 현재 페이지 인덱스.
 * @param pageCount 지금까지 알려진 전체 페이지 수.
 * @param pageStep 한 번의 turn이 몇 페이지를 진행시키는지.
 * @param pageTurnMode 페이지가 가로축과 세로축 중 어느 쪽으로 넘어가는지.
 * @param style 기존 curl 렌더러가 표준 모습을 쓸지, 3D Curl 옵션의 더 강한 front/back/rim 조명을
 *   쓸지.
 * @param paperColor 접힌 부분의 뒷면을 채우는 페이지 색으로, 독자가 고른 리더 팔레트의 종이색이다.
 * @param canRequestNextPage 알려진 끝에 있는 텍스트 문서가 페이지 나누기가 아직 끝나지 않은
 *   동안에도 다음 요청을 계속 전달해야 하는지 여부.
 * @param pageMoveRequest 대기 중인 프로그래밍적 페이지 이동 요청, 없으면 null.
 * @param onPageMoveRequestConsumed [pageMoveRequest]의 id와 함께, 그것이 애니메이션되었거나 갈
 *   곳이 없다고 확인된 뒤 호출된다.
 * @param onPreviousPage 뒤로 가는 curl이 완료되면 호출된다.
 * @param onNextPage 앞으로 가는 curl이 완료되면 호출된다.
 * @param onToggleControls 탭이 두 turn 영역 바깥에 떨어지거나, 자동 스크롤 도중이면 호출된다.
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
 * @param content 단일 pane인 경우 주어진 인덱스의 페이지를 렌더링한다.
 */
@Composable
internal fun FoundationCurlPager(
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
    FoundationPagerCurlReferenceImpl(
        pageKey = pageKey,
        pageCount = pageCount,
        pageStep = pageStep,
        pageTurnMode = pageTurnMode,
        style = style,
        paperColor = paperColor,
        canRequestNextPage = canRequestNextPage,
        pageMoveRequest = pageMoveRequest,
        onPageMoveRequestConsumed = onPageMoveRequestConsumed,
        onPreviousPage = onPreviousPage,
        onNextPage = onNextPage,
        onToggleControls = onToggleControls,
        onDoubleTap = onDoubleTap,
        isAutoScrollEnabled = isAutoScrollEnabled,
        autoScrollMode = autoScrollMode,
        autoScrollSpeed = autoScrollSpeed,
        onAutoScrollStop = onAutoScrollStop,
        modifier = modifier,
        paneCount = paneCount,
        spreadGutter = spreadGutter,
        spreadLeftWeight = spreadLeftWeight,
        spreadModifier = spreadModifier,
        paneContent = paneContent,
        content = content,
    )
}

/**
 * 자동 스크롤을 [mode]에 맞춰 pager의 스크롤로 흘려보낸다. 취소되거나 pager가 더 이상 앞으로
 * 갈 수 없을 때까지 돌아오지 않는다.
 *
 * [FoundationEffectPager]의 `LaunchedEffect` 본문에서 떼어낸 것으로, 그 composable은 이미
 * pager 동기화·제스처·탭·슬롯 렌더링을 함께 지고 있었다. 여기 있는 것은 그중 자동 스크롤
 * 하나뿐이며, pager와 속도 설정 말고는 아무것에도 기대지 않는다.
 *
 * @receiver 스크롤을 흘려보낼 대상 pager.
 * @param mode 따를 자동 스크롤 모드; [AutoScrollMode.PAGE]는 여기서 다루지 않는다(페이지 단위
 *   이동은 turn 요청 경로가 처리한다).
 * @param speed 설정된 자동 스크롤 속도.
 * @param density 속도를 픽셀로 환산하는 데 쓰이는 화면 밀도.
 * @param lineHeightPx line 모드가 한 걸음으로 삼는 현재 style의 픽셀 단위 줄 높이; 최소 1로
 *   고정된다.
 */
private suspend fun PagerState.foundationAutoScroll(
    mode: AutoScrollMode,
    speed: Float,
    density: Float,
    lineHeightPx: Float,
) {
    when (mode) {
        AutoScrollMode.PIXEL -> scroll {
            var lastFrameMillis = 0L
            while (currentCoroutineContext().isActive) {
                val frameMillis = withFrameMillis { it }
                if (lastFrameMillis != 0L) {
                    val distancePx = autoScrollDistancePx(
                        speed = speed,
                        density = density,
                        elapsedMillis = frameMillis - lastFrameMillis,
                    )
                    if (distancePx > 0f) {
                        val consumed = scrollBy(distancePx)
                        if (consumed == 0f && !canScrollForward) break
                    }
                }
                lastFrameMillis = frameMillis
            }
        }

        AutoScrollMode.LINE -> {
            val stepPx = lineHeightPx.coerceAtLeast(1f)
            val delayMillis = autoScrollLineDelayMillis(
                lineHeightPx = stepPx,
                pixelsPerSecond = autoScrollDistancePx(
                    speed = speed,
                    density = density,
                    elapsedMillis = 1_000L,
                ).coerceAtLeast(1f),
            )
            scroll {
                while (currentCoroutineContext().isActive) {
                    val consumed = scrollBy(stepPx)
                    if (consumed == 0f && !canScrollForward) break
                    delay(delayMillis)
                }
            }
        }

        AutoScrollMode.PAGE -> Unit
    }
}

/**
 * [pageAnimation]이 [PageAnimation.PAGE_FLIP]이고 이 슬롯이 current-page 슬롯일 때 pager 슬롯의
 * 콘텐츠를 page-flip fold box로 감싸며, 그 외에는 [content]를 그대로 그린다. current 슬롯만
 * fold되는 이유는 이웃 슬롯들이 바로 그 fold가 아래에서 드러내는 대상이기 때문이다 — 이웃까지
 * fold하면 효과가 두 배가 되어 버린다.
 *
 * fold의 모든 레이어는 렌더링할 페이지를 `@Composable () -> Unit` 래퍼가 아니라 페이지 인덱스로
 * 받는다. pager 슬롯은 드래그 프레임마다 재구성되므로, 여기서 래퍼 람다를 만들면 프레임마다
 * identity가 바뀌어 그 아래 페이지 트리 전체가 skip되지 못하고 다시 구성된다 — split-half fold는
 * 같은 콘텐츠를 세 레이어에서 부르므로 프레임당 페이지 구성이 세 번 일어난다. 인덱스와 [content]는
 * 둘 다 안정적이므로 호출 지점이 그대로 skip된다.
 *
 * @param pageAnimation 현재 적용 중인 page-turn 애니메이션; [PageAnimation.PAGE_FLIP]일 때만
 *   fold를 일으킨다.
 * @param axis fold가 가로축과 세로축 중 어느 쪽으로 도는지.
 * @param flipPhase fold의 구조를 가르는 이산 임계값들.
 * @param offsetProvider 이 슬롯의 부호 있는 오프셋을 돌려준다; 회전과 그림자가 layer/draw 블록
 *   안에서 호출한다.
 * @param pageFlipLayout fold가 whole-page turn인지 두 pane짜리 split-half fold인지.
 * @param isCurrentPage 이 슬롯이 pager의 current-page 슬롯인지 여부.
 * @param modifier box에 적용되는 modifier.
 * @param documentPage 이 슬롯 자신이 그리는 페이지 인덱스, 그릴 페이지가 없으면 null.
 * @param incomingPage fold가 진행됨에 따라 드러나는 이웃 페이지의 인덱스로,
 *   [FoundationPageFlipLayout.SplitHalfFold]에서만 쓰인다; 그런 이웃이 없으면 null.
 * @param content 주어진 인덱스의 페이지를 렌더링한다.
 */
@Composable
private fun FoundationPageFlipAwareBox(
    pageAnimation: PageAnimation,
    axis: FoundationPagerAxis,
    flipPhase: FoundationPageFlipPhase,
    offsetProvider: () -> Float,
    pageFlipLayout: FoundationPageFlipLayout,
    isCurrentPage: Boolean,
    modifier: Modifier,
    documentPage: Int?,
    incomingPage: Int?,
    content: @Composable (page: Int) -> Unit,
) {
    if (pageAnimation == PageAnimation.PAGE_FLIP && isCurrentPage) {
        when (pageFlipLayout) {
            FoundationPageFlipLayout.WholePage -> {
                FoundationWholePageFlipBox(
                    axis = axis,
                    flipPhase = flipPhase,
                    offsetProvider = offsetProvider,
                    modifier = modifier,
                    documentPage = documentPage,
                    content = content,
                )
            }
            FoundationPageFlipLayout.SplitHalfFold -> {
                FoundationSpreadPageFlipBox(
                    axis = axis,
                    flipPhase = flipPhase,
                    offsetProvider = offsetProvider,
                    modifier = modifier,
                    documentPage = documentPage,
                    incomingPage = incomingPage,
                    content = content,
                )
            }
        }
    } else {
        Box(modifier = modifier) {
            if (documentPage != null) content(documentPage)
        }
    }
}

/**
 * 두 pane짜리 split-half fold: spread의 한 절반은 실제로 펼쳐진 책의 페이지처럼 spine을 따라
 * 경첩을 이루며 넘어가고, 나머지 절반은 평평하게 남는다.
 *
 * leaf가 내려앉는 절반은 fold가 진행되는 내내 outgoing(현재 보이는) 페이지를 그 아래에 깔고
 * 있으며, incoming 이웃 페이지는 leaf 자신의 뒷면이 충분히 회전해 그 절반을 덮고 나서야
 * (`spec.showIncoming`) 같은 절반 위에 그려진다. incoming 페이지를 조건 없이 그 자리에 깔아
 * 두었다면 드래그가 시작되는 순간 그 절반이 새 페이지로 바뀌어 버려, turn이 끝나기도 전에 먼 쪽
 * 페이지가 바뀐 것처럼 보였을 것이다. leaf가 들려 올라가는 절반은 의도적으로 자신만의 깔개를
 * 두지 않는다: 도착하는 pager 페이지가 이미 그 바로 아래 놓여 있으므로, 그것을 드러내는 것이
 * 실제 책이 그 자리에서 보여주는 모습이기 때문이다.
 *
 * @param axis fold가 가로축과 세로축 중 어느 쪽으로 도는지.
 * @param flipPhase 어느 절반이 나가고 들어오는지, 그리고 각각이 지금 그려지는지를 가르는 이산
 *   임계값들.
 * @param offsetProvider 부호 있는 오프셋을 돌려준다; 절반들의 회전과 그림자가 layer/draw 블록
 *   안에서 이 값으로 계산된다.
 * @param modifier box에 적용되는 modifier.
 * @param documentPage 현재 페이지의 인덱스, 그릴 페이지가 없으면 null.
 * @param incomingPage fold가 진행됨에 따라 드러나는 이웃 페이지의 인덱스; null이거나 fold가
 *   안착해 있으면 fold 없이 [documentPage]를 그대로 그린다.
 * @param content 주어진 인덱스의 페이지를 렌더링한다.
 */
@Composable
private fun FoundationSpreadPageFlipBox(
    axis: FoundationPagerAxis,
    flipPhase: FoundationPageFlipPhase,
    offsetProvider: () -> Float,
    modifier: Modifier = Modifier,
    documentPage: Int?,
    incomingPage: Int?,
    content: @Composable (page: Int) -> Unit,
) {
    if (flipPhase.isResting || incomingPage == null) {
        Box(modifier = modifier) { if (documentPage != null) content(documentPage) }
        return
    }
    // 세 조각(깔개, 나가는 leaf, 들어오는 leaf)을 항상 구성하고, **어느 절반인지·보이는지·회전**을
    // 전부 그리기 시점 오프셋 하나에서 뽑는다.
    //
    // 진행률로 갈라 조건부로 구성하던 예전 방식은 두 가지를 망가뜨렸다. (1) `0.5` 임계값이
    // composition 조건이 되어 turn마다 슬롯이 그 지점에서 재구성됐다. (2) 더 나쁜 쪽 — 판정은
    // composition, 회전은 그리기 시점 오프셋에서 나오는데 `PagerState`의 스크롤 위치는 measure
    // 단계에서 갱신되므로 둘이 한 프레임 어긋난다. `incomingOffset`은 진행률에 역방향 단조라, turn이
    // 끝나며 pager가 가운데로 스냅하는 프레임에는 composition이 아직 "들어오는 절반을 그려라"라고
    // 말하는 동안 그리기 오프셋은 이미 `0`이어서 회전이 180°로 튀었다.
    //
    // 절반 배정까지 provider로 미루는 이유도 같다. composition이 부호로 절반을 고정해 두면, 드래그가
    // 안착점을 가로질러 방향을 뒤집는 프레임에 그리기 시점 절반과 어긋나 양쪽 leaf가 함께 사라지고 한
    // 절반이 빈 채로 남는다. 이제 절반도 같은 오프셋에서 나오므로 그런 상태가 존재하지 않는다.
    //
    // 세 조각을 늘 구성하는 비용은 낮다 — 페이지 호출 지점은 skippable이고 인덱스도 그대로다. 다만
    // 안 보이는 leaf는 배치되지 않으므로(아래 [FoundationPageFlipHalfBox] 참고) 그리기와 히트
    // 테스트에서 모두 빠진다.
    val shadowProvider = {
        foundationPageFlipShadowSpec(offsetProvider(), FoundationPageFlipLayout.SplitHalfFold)
    }
    fun spec() = foundationSpreadPageFlipSpec(axis, offsetProvider())
    Box(modifier = modifier) {
        FoundationPageFlipHalfBox(
            axis = axis,
            halfProvider = { spec().incomingHalf },
            rotationProvider = { FoundationPageFlipHalfSpec(0f, 0f) },
            page = documentPage,
            content = content,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .foundationPageFlipProjectedShadow(
                    axis = axis,
                    offsetProvider = offsetProvider,
                    layout = FoundationPageFlipLayout.SplitHalfFold,
                ),
        ) {}
        FoundationPageFlipHalfBox(
            axis = axis,
            halfProvider = { spec().outgoingHalf },
            rotationProvider = { spec().let { if (it.showOutgoing) it.outgoing else null } },
            shadowProvider = shadowProvider,
            page = documentPage,
            content = content,
        )
        FoundationPageFlipHalfBox(
            axis = axis,
            halfProvider = { spec().incomingHalf },
            rotationProvider = { spec().let { if (it.showIncoming) it.incoming else null } },
            shadowProvider = shadowProvider,
            page = incomingPage,
            content = content,
        )
    }
}

/**
 * 단일 pane짜리 whole-page fold: [FoundationSpreadPageFlipBox]처럼 경첩으로 이어진 두 절반으로
 * 나뉘는 대신, 페이지 전체가 뻣뻣한 한 장의 시트처럼 자신의 바깥쪽 edge를 축으로 회전한다.
 *
 * 회전하는 앞면 아래에 깔리는 다음/이전 페이지는 이 box가 다시 그리지 않는다: 그 페이지는
 * 이웃 pager 슬롯이 이미 렌더링하고 있고, 그 슬롯은 `cancelTranslation`을 통해 정확히 이 슬롯
 * 아래에 겹쳐 놓여 있다([foundationPageFlipZIndex] 참고). 여기서 한 번 더 그리면 같은 페이지를
 * 프레임마다 두 번 구성하게 되고, 드래그가 안착 지점을 가로질러 방향을 바꿀 때마다 그 사본이
 * 통째로 폐기·재구성되어 turn 도중 눈에 띄는 끊김을 남긴다.
 *
 * @param axis fold가 가로축과 세로축 중 어느 쪽으로 도는지.
 * @param flipPhase 이 fold가 아직 안착해 있는지를 가르는 이산 임계값.
 * @param offsetProvider 부호 있는 오프셋을 돌려준다; [foundationWholePageFlipSpec]과
 *   [foundationPageFlipShadowSpec]이 layer/draw 블록 안에서 이 값으로 회전·피벗·그림자를 구한다.
 * @param modifier box에 적용되는 modifier.
 * @param documentPage 회전하는 앞면에 그려지는 outgoing 페이지의 인덱스, 없으면 null.
 * @param content 주어진 인덱스의 페이지를 렌더링한다.
 */
@Composable
private fun FoundationWholePageFlipBox(
    axis: FoundationPagerAxis,
    flipPhase: FoundationPageFlipPhase,
    offsetProvider: () -> Float,
    modifier: Modifier = Modifier,
    documentPage: Int?,
    content: @Composable (page: Int) -> Unit,
) {
    if (flipPhase.isResting) {
        Box(modifier = modifier) { if (documentPage != null) content(documentPage) }
        return
    }
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .foundationPageFlipProjectedShadow(
                    axis = axis,
                    offsetProvider = offsetProvider,
                    layout = FoundationPageFlipLayout.WholePage,
                ),
        ) {}
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val transform = foundationWholePageFlipSpec(axis, offsetProvider())
                    cameraDistance = FoundationCameraDistance
                    transformOrigin = TransformOrigin(transform.transformOriginX, transform.transformOriginY)
                    rotationX = transform.rotationX
                    rotationY = transform.rotationY
                }
                .foundationPageFlipInnerShadow(
                    axis = axis,
                    shadowProvider = {
                        foundationPageFlipShadowSpec(offsetProvider(), FoundationPageFlipLayout.WholePage)
                    },
                    foldFractionProvider = {
                        val transform = foundationWholePageFlipSpec(axis, offsetProvider())
                        if (axis == FoundationPagerAxis.Horizontal) {
                            transform.transformOriginX
                        } else {
                            transform.transformOriginY
                        }
                    },
                ),
        ) {
            if (documentPage != null) content(documentPage)
        }
    }
}

/**
 * split-half fold가 사용하는, 페이지의 4분의 1 또는 절반 크기 깔개 하나. 움직이는 leaf는 참조
 * 구현의 클리핑된 inner shadow를 받으며, 평평하게 고정된 깔개는 [shadowProvider]가 null을 낸다.
 *
 * @param axis 이 절반이 놓인 pager의 축 — inner shade가 어느 방향으로 옅어지는지를 정한다.
 * @param halfProvider 이 프레임에 이 box가 앉힐 사분면/절반을 돌려준다; 클리핑 모양과 inner shade의
 *   side가 여기서 나온다. 값이 아니라 provider인 이유는 [FoundationSpreadPageFlipBox]의 주석 참고.
 * @param rotationProvider 적용할 회전을 돌려준다. `null`은 이 절반이 이 프레임에 보이지 않아야 함을
 *   뜻하며, 그 경우 배치되지 않는다 — 그리기와 히트 테스트에서 함께 빠지므로, 안 보이는 절반이 그
 *   아래 콘텐츠의 포인터 입력을 가로채지 않는다. alpha만 0으로 두면 히트 테스트에는 그대로 남는다.
 * @param modifier 이 클리핑된 절반에 추가로 적용되는 레이아웃이나 그리기.
 * @param shadowProvider 이 프레임에 대한 참조 outer/inner 치수를 돌려준다; 고정된 깔개는 기본값인
 *   null 반환 provider를 쓴다.
 * @param page 이 절반에 앉히는 페이지의 인덱스, 없으면 null.
 * @param content 주어진 인덱스의 페이지를 렌더링한다.
 */
@Composable
private fun FoundationPageFlipHalfBox(
    axis: FoundationPagerAxis,
    halfProvider: () -> FoundationPageFlipHalf,
    rotationProvider: () -> FoundationPageFlipHalfSpec?,
    modifier: Modifier = Modifier,
    shadowProvider: () -> FoundationPageFlipShadowSpec? = { null },
    page: Int?,
    content: @Composable (page: Int) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // 가시성을 배치 단계에서 판정한다: 배치하지 않으면 그리기와 히트 테스트에서 모두 빠지고,
            // 그러면서도 노드는 구성된 채로 남아 재구성이 다시 늘지 않는다. 이 블록은 레이아웃
            // snapshot 관찰자 안에서 돌기 때문에 오프셋이 바뀌면 재구성 없이 다시 실행된다.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    if (rotationProvider() != null) placeable.place(0, 0)
                }
            }
            .graphicsLayer {
                val spec = rotationProvider()
                shape = foundationPageFlipShape(halfProvider())
                clip = true
                cameraDistance = FoundationCameraDistance
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                rotationX = spec?.rotationX ?: 0f
                rotationY = spec?.rotationY ?: 0f
            }
            .foundationPageFlipInnerShadow(
                axis = axis,
                shadowProvider = {
                    shadowProvider()?.copy(side = foundationPageFlipHalfShadowSide(halfProvider()))
                },
                foldFractionProvider = { FoundationPageFlipSpineFraction },
            ),
    ) {
        if (page != null) content(page)
    }
}

/**
 * 움직이는 leaf 위에 fold(경첩)에서 자유 edge 쪽으로 옅어지는 shade를 그린다. 너비는 outer
 * shadow의 75%이고, 정지점은 [foundationPageFlipInnerShadowStops]가 낸다.
 *
 * fold 위치는 [foldFractionProvider]로 받는다. 축의 양 끝(0 또는 1)로는 표현할 수 없기 때문이다:
 * split-half spread에서 접히는 절반의 fold는 spine, 즉 `0.5`에 있고 자유 edge만 축의 끝에 있다.
 * 축의 끝을 fold로 삼으면 띠가 그 절반의 clip 밖에 그려져 전부 잘려 사라진다.
 *
 * @receiver 이 shade가 덧붙는 modifier 체인.
 * @param axis shade가 가로축과 세로축 중 어느 쪽을 따라 옅어지는지.
 * @param shadowProvider 이 프레임의 치수를 돌려준다. `side`는 leaf의 자유 edge 쪽으로, 띠가
 *   fold에서 그쪽으로 뻗는다. null이거나 알파/너비가 0이면 아무것도 그리지 않는다. 그리기 캐시
 *   안에서만 호출된다.
 * @param foldFractionProvider 축을 따라 fold가 놓인 위치를 돌려준다, `[0, 1]` 범위.
 * @return shade를 그리는 modifier.
 */
private fun Modifier.foundationPageFlipInnerShadow(
    axis: FoundationPagerAxis,
    shadowProvider: () -> FoundationPageFlipShadowSpec?,
    foldFractionProvider: () -> Float,
): Modifier = drawWithCache {
    // 두 provider는 어떤 early return보다도 먼저 호출해야 한다: `observeReads`는 실제로 실행된
    // read만 등록하므로, 아래 분기 밑으로 내려가면 그림자가 사라진 프레임에서 구독이 끊겨 이후
    // 갱신을 놓친다.
    val shadow = shadowProvider()
    val foldFraction = foldFractionProvider()
    val side = shadow?.side
    val alpha = shadow?.opacity ?: 0f
    val extent = if (axis == FoundationPagerAxis.Horizontal) size.width else size.height
    val band = extent * (shadow?.innerWidthFraction ?: 0f)
    if (side == null || alpha <= 0f || band <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }

    val fold = (foldFraction * extent).coerceIn(0f, extent)
    val inner = if (side == FoundationFluidSide.End) fold + band else fold - band
    val colorStops = foundationPageFlipInnerShadowStops(foundationPageFlipFoldSide(side), alpha)
    val brush = if (axis == FoundationPagerAxis.Horizontal) {
        Brush.horizontalGradient(
            colorStops = colorStops,
            startX = min(fold, inner),
            endX = max(fold, inner),
        )
    } else {
        Brush.verticalGradient(
            colorStops = colorStops,
            startY = min(fold, inner),
            endY = max(fold, inner),
        )
    }

    onDrawWithContent {
        drawContent()
        if (axis == FoundationPagerAxis.Horizontal) {
            val left = min(fold, inner)
            drawRect(brush = brush, topLeft = Offset(left, 0f), size = Size(abs(fold - inner), size.height))
        } else {
            val top = min(fold, inner)
            drawRect(brush = brush, topLeft = Offset(0f, top), size = Size(size.width, abs(fold - inner)))
        }
    }
}

/**
 * [pagerPage]에 대한 슬롯별 트랜지션 modifier를 만든다. [pageAnimation]에 따라 fluid-reveal,
 * circle-reveal, movie-carousel, page-flip의 transform/shadow/z-index 조합 중 하나로 분기하며,
 * 그 외의 애니메이션(다른 곳에서 [AnimatedContent]/일반 Foundation 스와이프가 전부 처리한다)에는
 * 여기서 별도의 modifier를 주지 않는다.
 *
 * 연속값은 값이 아니라 provider로 받는다. 값으로 받으면 호출자가 그것을 composition에서 읽어야 하고,
 * 그러면 스크롤이 진행되는 매 프레임 슬롯이 재구성된다. provider는 그 읽기를 graphicsLayer(placement)와
 * drawWithCache(draw) 블록 안으로 미루므로, 재구성 없이 레이어와 그리기 캐시만 갱신된다.
 *
 * composition이 여전히 알아야 하는 것 — z-index 값, 그리고 이웃을 아예 숨길지 여부 — 은 전부
 * 이산값이라 [turnState] 하나에 담아 넘긴다.
 *
 * @receiver 이 트랜지션이 덧붙는 modifier 체인.
 * @param pagerPage pager 안에서 이 슬롯의 인덱스(0, 1, 또는 2).
 * @param axis pager가 가로축과 세로축 중 어느 쪽으로 도는지.
 * @param pageAnimation 적용할 트랜지션 style.
 * @param offsetProvider 이 슬롯이 pager의 안착 위치로부터 갖는 부호 있는 오프셋을 돌려준다.
 *   값이 아니라 provider인 이유는 위 문단과 같다.
 * @param turnState 어느 이웃 쪽 turn이 활성이고 그것이 진행 중인지 — 이 빌더가 composition 시점에
 *   실제로 필요로 하는 전부다.
 * @param turnProvider 그 프레임의 활성 turn(어느 이웃 쪽인지와 진행률)을 돌려준다. 진행률만이 아니라
 *   side까지 돌려주는 이유는, composition의 gate와 draw의 값이 한 프레임 어긋날 수 있기 때문이다 —
 *   `PagerState`의 스크롤 위치는 measure 단계에서 갱신되므로 draw는 그 프레임에 이미 새 값을 보지만
 *   composition은 다음 프레임에야 본다. 그리기 블록이 side를 다시 대조해야, fling 도중 방향이 뒤집힌
 *   프레임에서 엉뚱한 이웃이 번쩍이지 않는다.
 * @param flipPhase PAGE_FLIP의 쌓임 순서를 가르는 이산 상태. z-index는 [Modifier.zIndex]가
 *   composition 시점 값을 요구하므로 provider로 미룰 수 없고, 이산값으로 받아야 프레임마다
 *   재구성되지 않는다.
 * @param gesture fluid-reveal과 circle-reveal 기하를 구동하는 수동 드래그/터치 상태.
 * @param fluidEdge fluid-reveal style을 위한, 공유되는 spring 애니메이션 edge 모양.
 * @return [pageAnimation]에 대해 이 슬롯의 transform, shadow, z-index를 적용하는 modifier.
 */
private fun Modifier.foundationEffectPageModifier(
    pagerPage: Int,
    axis: FoundationPagerAxis,
    pageAnimation: PageAnimation,
    offsetProvider: () -> Float,
    turnState: FoundationPagerTurnState,
    turnProvider: () -> FoundationActivePageTurn,
    flipPhase: FoundationPageFlipPhase,
    gesture: FoundationPagerGestureTracker,
    fluidEdge: FoundationFluidEdge,
): Modifier {
    val page = FoundationPagerPage.fromPagerPage(pagerPage)
    val activeSide = turnState.side
    val isTurnActive = turnState.isActive

    val cancelTranslation = Modifier.graphicsLayer {
        val pageOffset = offsetProvider()
        if (axis == FoundationPagerAxis.Horizontal) {
            translationX = size.width * pageOffset
        } else {
            translationY = size.height * pageOffset
        }
    }

    return when (pageAnimation) {
        PageAnimation.FLUID_PAGER -> cancelTranslation
            .zIndex(foundationRevealZIndex(page, activeSide, isTurnActive))
            .then(
                foundationRevealModifier(page, activeSide, isTurnActive) {
                    Modifier.foundationFluidClip(
                        axis = axis,
                        side = page.side,
                        turnProvider = turnProvider,
                        gesture = gesture,
                        fluidEdge = fluidEdge,
                    )
                },
            )
            .then(foundationFluidShadow(page, axis, activeSide, isTurnActive, turnProvider, fluidEdge))

        PageAnimation.CIRCLE_REVEAL -> cancelTranslation
            .zIndex(foundationRevealZIndex(page, activeSide, isTurnActive))
            .then(
                foundationRevealModifier(page, activeSide, isTurnActive) {
                    Modifier.foundationCircleRevealClip(axis, page.side, turnProvider, gesture)
                },
            )
            .then(foundationCircleRevealShadow(page, axis, activeSide, isTurnActive, turnProvider, gesture))

        PageAnimation.MOVIE_CAROUSEL -> Modifier
            .zIndex(foundationMovieZIndex(page, activeSide, isTurnActive))
            .foundationMovieCarouselLayer(axis, page, offsetProvider)
            .foundationMovieCarouselShadow(axis, page, offsetProvider)

        PageAnimation.PAGE_FLIP -> cancelTranslation
            .zIndex(foundationPageFlipZIndex(page, flipPhase.isApproaching))

        else -> Modifier
    }
}

/**
 * 슬롯별 트랜지션이 composition 시점에 알아야 하는 turn 상태 전부 — 둘 다 이산값이라, 스크롤이
 * 진행되는 동안에도 임계값을 넘을 때만 바뀐다.
 *
 * @property side 활성 turn이 어느 쪽(start/end) 이웃을 드러내고 있는지.
 * @property isActive 그 turn이 실제로 진행 중인지(진행률이 `0`보다 큰지).
 */
@Immutable
private data class FoundationPagerTurnState(
    val side: FoundationFluidSide,
    val isActive: Boolean,
)

/**
 * PAGE_FLIP의 fold가 composition 시점에 필요로 하는 구조적 임계값들 — 전부 이산값이라, 스크롤이
 * 진행되는 동안에도 임계값을 넘을 때만 바뀐다. 회전·피벗·그림자 같은 연속값은 여기 없다; 그것들은
 * offset provider를 통해 layer/draw 블록 안에서 계산된다.
 *
 * @property isResting fold가 아직 시작되지 않았는지(오프셋이 정확히 `0`). 참이면 슬롯은 fold 없이
 *   페이지를 그대로 그린다.
 * @property isForward turn이 다음 페이지 방향인지. 어느 이웃 페이지를 incoming으로 구성할지를
 *   결정한다 — 그건 페이지 인덱스 선택이라 composition에 남을 수밖에 없다. 절반 배정과 피벗은 이
 *   값이 아니라 그리기 시점 오프셋에서 나온다([FoundationSpreadPageFlipBox] 참고).
 * @property isApproaching 이 슬롯이 화면으로 다가오는 중인지(크기가 `1`보다 작은지). 쌓임 순서에서
 *   멀어지는 이웃보다 위로 올라갈지를 가른다.
 */
@Immutable
internal data class FoundationPageFlipPhase(
    val isResting: Boolean,
    val isForward: Boolean,
    val isApproaching: Boolean,
) {
    companion object {
        /**
         * 손대지 않은 fold. PAGE_FLIP이 아닌 style에 넘기는 값이기도 하다.
         *
         * [foundationPageFlipPhase]로 정의해 오프셋 `0`이 내는 값과 어긋날 수 없게 한다.
         */
        val Resting = foundationPageFlipPhase(0f)
    }
}

/**
 * 부호 있는 슬롯 오프셋에서 fold의 구조적 임계값들을 뽑아낸다.
 *
 * 진행률의 `0.5` 임계값은 여기 없다 — 어느 절반을 그릴지는 그리기 시점 오프셋에서 정해진다. 그 판단을
 * composition으로 끌어올리면 판정과 회전이 서로 다른 프레임의 값에서 나와, turn이 끝나는 프레임에
 * 들어오는 절반이 180° 뒤집힌 채 한 번 그려질 수 있다.
 *
 * @param pageOffset 이 슬롯이 자기 안착 위치로부터 갖는 부호 있는 오프셋, 페이지 단위.
 * @return 이 오프셋에서 fold가 갖는 구조.
 */
internal fun foundationPageFlipPhase(pageOffset: Float): FoundationPageFlipPhase =
    FoundationPageFlipPhase(
        isResting = pageOffset == 0f,
        isForward = pageOffset >= 0f,
        isApproaching = abs(pageOffset) < 1f,
    )

/**
 * split-half fold에서 current 페이지의 leaf가 접혀 나가는 절반.
 *
 * [foundationSpreadPageFlipSpec]과 [FoundationSpreadPageFlipBox]가 같은 규칙을 각자 인코딩하지
 * 않도록 떼어냈다: 전자는 연속 오프셋에서, 후자는 이산 [FoundationPageFlipPhase]에서 같은 답을
 * 얻어야 한다.
 *
 * @param axis fold가 가로축과 세로축 중 어느 쪽으로 도는지.
 * @param isNext turn이 다음 페이지 방향인지.
 * @return leaf가 들려 나가는 절반.
 */
internal fun foundationSpreadPageFlipOutgoingHalf(
    axis: FoundationPagerAxis,
    isNext: Boolean,
): FoundationPageFlipHalf = when (axis) {
    FoundationPagerAxis.Horizontal -> if (isNext) FoundationPageFlipHalf.Right else FoundationPageFlipHalf.Left
    FoundationPagerAxis.Vertical -> if (isNext) FoundationPageFlipHalf.Bottom else FoundationPageFlipHalf.Top
}

/**
 * split-half fold에서 incoming 이웃이 내려앉는 절반 — [foundationSpreadPageFlipOutgoingHalf]의
 * 반대편이다.
 *
 * @param axis fold가 가로축과 세로축 중 어느 쪽으로 도는지.
 * @param isNext turn이 다음 페이지 방향인지.
 * @return leaf가 접혀 들어가는 절반.
 */
internal fun foundationSpreadPageFlipIncomingHalf(
    axis: FoundationPagerAxis,
    isNext: Boolean,
): FoundationPageFlipHalf = when (axis) {
    FoundationPagerAxis.Horizontal -> if (isNext) FoundationPageFlipHalf.Left else FoundationPageFlipHalf.Right
    FoundationPagerAxis.Vertical -> if (isNext) FoundationPageFlipHalf.Top else FoundationPageFlipHalf.Bottom
}

/**
 * fluid-reveal edge를 따라, 지금 드러나고 있는 이웃 쪽으로 그려지는 cast + contact shadow로,
 * current 페이지이거나 turn이 비활성/완료 상태면 modifier를 전혀 적용하지 않는다.
 *
 * @param page 이 shadow가 그려질 pager 슬롯.
 * @param axis turn이 가로축과 세로축 중 어느 쪽으로 진행되는지.
 * @param activeSide 활성 turn이 어느 쪽(start/end)에서 드러나고 있는지.
 * @param isTurnActive 그 turn이 실제로 진행 중인지; 아니면 modifier를 아예 붙이지 않는다.
 * @param turnProvider 그 프레임의 활성 turn을 돌려준다; 그리기 캐시 안에서만 호출된다. side를 여기서
 *   다시 대조하는 이유는 composition의 gate가 한 프레임 뒤질 수 있기 때문이다 — 그 프레임에
 *   turn이 다른 이웃으로 넘어갔다면 이 슬롯은 아무것도 그리지 않아야 한다.
 * @param fluidEdge shadow가 따라 그리는, 공유되는 spring 애니메이션 edge 모양.
 * @return shadow를 그리는 modifier, 또는 이 슬롯에 보여줄 shadow가 없으면 변경되지 않은
 *   [Modifier].
 */
private fun foundationFluidShadow(
    page: FoundationPagerPage,
    axis: FoundationPagerAxis,
    activeSide: FoundationFluidSide,
    isTurnActive: Boolean,
    turnProvider: () -> FoundationActivePageTurn,
    fluidEdge: FoundationFluidEdge,
): Modifier {
    if (page == FoundationPagerPage.Current) return Modifier
    if (page.side != activeSide || !isTurnActive) return Modifier
    return Modifier.drawWithCache {
        @Suppress("UNUSED_VARIABLE")
        val version = fluidEdge.version
        val turn = turnProvider()
        if (turn.side != page.side) {
            return@drawWithCache onDrawWithContent { drawContent() }
        }
        val clampedProgress = turn.progress.coerceIn(0f, 1f)
        val castAlpha = FoundationRevealShadowAlpha * sin(clampedProgress * PI.toFloat())
        val contactAlpha = (castAlpha * 1.25f).coerceAtMost(0.32f)
        if (castAlpha <= 0f) {
            return@drawWithCache onDrawWithContent { drawContent() }
        }

        val canonicalSize = axis.toCanonicalSize(FoundationPagerSize(size.width, size.height))
        val edgePoints = fluidEdge.canonicalEdgePoints(canonicalSize, activeSide)
        val castPath = foundationFluidShadowBand(
            edgePoints = edgePoints,
            canonicalSize = canonicalSize,
            side = activeSide,
            width = FoundationRevealShadowWidth,
        ).fromCanonical(axis).toPath()
        val contactPath = foundationFluidShadowBand(
            edgePoints = edgePoints,
            canonicalSize = canonicalSize,
            side = activeSide,
            width = FoundationRevealContactShadowWidth,
        ).fromCanonical(axis).toPath()

        onDrawWithContent {
            drawContent()
            drawPath(castPath, Color.Black.copy(alpha = castAlpha * 0.55f))
            drawPath(contactPath, Color.Black.copy(alpha = contactAlpha))
        }
    }
}

/**
 * 커지는 원이 드러내고 있는 이웃 쪽으로 지워지는 shadow — [foundationFluidShadow]의
 * [PageAnimation.CIRCLE_REVEAL]판으로, 같은 cast-plus-contact 방식을 쓰되 fluid edge의 경로
 * 대신 원의 반지름을 따른다. current 페이지이거나 turn이 비활성/완료 상태면 modifier를 전혀
 * 적용하지 않는다.
 *
 * @param page 이 shadow가 그려질 pager 슬롯.
 * @param axis turn이 가로축과 세로축 중 어느 쪽으로 진행되는지.
 * @param activeSide 활성 turn이 어느 쪽(start/end)에서 드러나고 있는지.
 * @param isTurnActive 그 turn이 실제로 진행 중인지; 아니면 modifier를 아예 붙이지 않는다.
 * @param turnProvider 그 프레임의 활성 turn을 돌려준다; 그리기 캐시 안에서만 호출된다. side를 여기서
 *   다시 대조하는 이유는 composition의 gate가 한 프레임 뒤질 수 있기 때문이다 — 그 프레임에
 *   turn이 다른 이웃으로 넘어갔다면 이 슬롯은 아무것도 그리지 않아야 한다.
 * @param gesture 원의 원점을 터치 지점에 고정하는 수동 드래그/터치 상태.
 * @return shadow를 그리는 modifier, 또는 이 슬롯에 보여줄 shadow가 없으면 변경되지 않은
 *   [Modifier].
 */
private fun foundationCircleRevealShadow(
    page: FoundationPagerPage,
    axis: FoundationPagerAxis,
    activeSide: FoundationFluidSide,
    isTurnActive: Boolean,
    turnProvider: () -> FoundationActivePageTurn,
    gesture: FoundationPagerGestureTracker,
): Modifier {
    if (page == FoundationPagerPage.Current) return Modifier
    if (page.side != activeSide || !isTurnActive) return Modifier
    return Modifier.drawWithCache {
        val turn = turnProvider()
        if (turn.side != page.side) {
            return@drawWithCache onDrawWithContent { drawContent() }
        }
        val progress = turn.progress
        val touchCrossAxis = foundationTouchCrossAxis(
            axis = axis,
            size = FoundationPagerSize(size.width, size.height),
            touch = gesture.touchPoint(),
        )
        val spec = foundationCircleRevealShadowSpec(
            size = FoundationPagerSize(size.width, size.height),
            axis = axis,
            side = activeSide,
            progress = progress,
            touchCrossAxis = touchCrossAxis,
        )
        if (spec.radius <= 0f || spec.alpha <= 0f) {
            return@drawWithCache onDrawWithContent { drawContent() }
        }

        val center = Offset(spec.center.x, spec.center.y)
        val innerFraction = (spec.innerRadius / spec.radius).coerceIn(0f, 1f)
        val shadowBrush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                innerFraction to Color.Transparent,
                1f to Color.Black.copy(alpha = spec.alpha),
            ),
            center = center,
            radius = spec.radius,
        )

        onDrawWithContent {
            drawContent()
            drawCircle(
                brush = shadowBrush,
                radius = spec.radius,
                center = center,
            )
        }
    }
}

/**
 * [PageAnimation.MOVIE_CAROUSEL] style에서 들어오는 이웃 페이지의 옆면을 따라 edge shadow를
 * 그려서, 화면 밖에서 미끄러져 들어오는 페이지가 current 페이지 위에 납작하게 나타나는 대신 그
 * edge 아래를 지나는 것처럼 보이게 한다. current 페이지이거나, 현재 들어오는 중이 아닌 슬롯이거나,
 * turn이 이미 안착했으면 shadow를 그리지 않는다.
 *
 * @receiver 이 shadow가 덧붙는 modifier 체인.
 * @param axis carousel이 가로축과 세로축 중 어느 쪽으로 움직이는지.
 * @param page 이 shadow가 그려질 pager 슬롯.
 * @param offsetProvider 이 슬롯이 pager의 안착 위치로부터 갖는 부호 있는 오프셋을 돌려준다,
 *   `[-1, 1]` 범위; 부호는 이 슬롯이 들어오는 중인지를, 크기는 shadow가 얼마나 옅어졌는지를
 *   결정한다. 그리기 캐시 안에서만 호출된다.
 * @return shadow를 그리는 modifier, 또는 이 슬롯에 보여줄 shadow가 없으면 변경되지 않은
 *   receiver.
 */
private fun Modifier.foundationMovieCarouselShadow(
    axis: FoundationPagerAxis,
    page: FoundationPagerPage,
    offsetProvider: () -> Float,
): Modifier = drawWithCache {
    val pageOffset = offsetProvider()
    val shadowSide = foundationMovieCarouselShadowSide(page)
    val isIncomingPage = when (page) {
        FoundationPagerPage.Previous -> pageOffset > 0f
        FoundationPagerPage.Next -> pageOffset < 0f
        FoundationPagerPage.Current -> false
    }
    val progress = (1f - abs(pageOffset)).coerceIn(0f, 1f)
    if (shadowSide == null || !isIncomingPage || progress <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }

    val edgeAlpha = (FoundationMovieEdgeShadowAlpha * sin(progress * PI.toFloat())).coerceAtLeast(0f)
    val isHorizontal = axis == FoundationPagerAxis.Horizontal
    val extent = if (isHorizontal) size.width else size.height
    val bandStart = when (shadowSide) {
        FoundationFluidSide.Start -> 0f
        FoundationFluidSide.End -> (extent - FoundationMovieShadowWidth).coerceAtLeast(0f)
    }
    val bandEnd = (bandStart + FoundationMovieShadowWidth).coerceAtMost(extent)
    val colors = when (shadowSide) {
        FoundationFluidSide.Start -> listOf(Color.Black.copy(alpha = edgeAlpha), Color.Transparent)
        FoundationFluidSide.End -> listOf(Color.Transparent, Color.Black.copy(alpha = edgeAlpha))
    }
    val brush = if (isHorizontal) {
        Brush.horizontalGradient(colors = colors, startX = bandStart, endX = bandEnd)
    } else {
        Brush.verticalGradient(colors = colors, startY = bandStart, endY = bandEnd)
    }
    val band = (bandEnd - bandStart).coerceAtLeast(0f)
    val topLeft = if (isHorizontal) Offset(bandStart, 0f) else Offset(0f, bandStart)
    val bandSize = if (isHorizontal) Size(band, size.height) else Size(size.width, band)

    onDrawWithContent {
        drawContent()
        drawRect(brush = brush, topLeft = topLeft, size = bandSize)
    }
}

/**
 * [Modifier.foundationMovieCarouselShadow]의 [PageAnimation.MOVIE_CAROUSEL] shadow가 [page]에
 * 대해 어느 edge에 붙는지: previous-page 슬롯은 current 페이지가 멀어져 감에 따라 뒤쪽(end)
 * edge에서 덮이고, next-page 슬롯은 current 페이지가 다가옴에 따라 앞쪽(start) edge에서 덮이며,
 * current 페이지 자신은 이런 edge shadow를 드리우지 않는다.
 *
 * @param page shadow의 side를 구할 대상 pager 슬롯.
 * @return shadow가 붙는 side, 또는 current 페이지면 null.
 */
internal fun foundationMovieCarouselShadowSide(page: FoundationPagerPage): FoundationFluidSide? = when (page) {
    FoundationPagerPage.Previous -> FoundationFluidSide.End
    FoundationPagerPage.Next -> FoundationFluidSide.Start
    FoundationPagerPage.Current -> null
}

/**
 * movie-carousel 페이지가 멀어질 때 그 위에 그려지는 어둡게 하는 오버레이의 alpha로, turn의
 * 중간점(`progress == 0.5`)에서 정점을 찍고 양쪽 끝에서는 half-sine 곡선을 따라 0으로 옅어진다 —
 * [foundationFluidShadow]와 그 형제 함수들이 자신의 cast shadow에 쓰는 것과 같은 모양이다.
 *
 * @param progress turn이 얼마나 진행됐는지, `[0, 1]` 범위.
 * @return 오버레이의 alpha, `[0, FoundationMovieShadowAlpha]` 범위.
 */
internal fun foundationMovieCarouselDimAlpha(progress: Float): Float =
    (FoundationMovieShadowAlpha * sin(progress.coerceIn(0f, 1f) * PI.toFloat())).coerceAtLeast(0f)

/**
 * StPageFlip의 outer shadow를 드러난 페이지 위에 그린다. 너비는 turn 진행률에 선형으로 비례해
 * 커지고, 불투명도는 선형으로 옅어지며, 띠는 움직이는 leaf의 edge에서 시작해 투명해질 때까지
 * 이어진다. 드러난 쪽(receiver)을 고르는 것은 기존 투영 계산이 그대로 담당한다.
 *
 * 오프셋을 값이 아니라 [offsetProvider]로 받아 두 spec을 그리기 캐시 안에서 구한다: 그래야
 * 호출하는 fold box가 연속 오프셋을 composition에서 읽지 않아도 되고, 그만큼 슬롯이 프레임마다
 * 재구성되지 않는다.
 */
private fun Modifier.foundationPageFlipProjectedShadow(
    axis: FoundationPagerAxis,
    offsetProvider: () -> Float,
    layout: FoundationPageFlipLayout,
): Modifier = drawWithCache {
    // [foundationPageFlipInnerShadow]와 같은 이유로 어떤 early return보다 먼저 읽는다.
    val pageOffset = offsetProvider()
    val shadow = foundationPageFlipShadowSpec(pageOffset, layout)
    val projection = foundationPageFlipProjectionSpec(pageOffset, layout)
    val extent = if (axis == FoundationPagerAxis.Horizontal) size.width else size.height
    val castWidth = (extent * shadow.outerWidthFraction).coerceAtMost(extent)
    if (projection == null || shadow.opacity <= 0f || castWidth <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }

    val contactPx = (projection.shadowEdgeFraction * extent).coerceIn(0f, extent)
    val castStart = when (projection.castDirection) {
        FoundationFluidSide.Start -> (contactPx - castWidth).coerceIn(0f, extent)
        FoundationFluidSide.End -> contactPx
    }
    val castEnd = when (projection.castDirection) {
        FoundationFluidSide.Start -> contactPx
        FoundationFluidSide.End -> (contactPx + castWidth).coerceIn(0f, extent)
    }
    val colors = when (projection.castDirection) {
        FoundationFluidSide.Start -> listOf(Color.Transparent, Color.Black.copy(alpha = shadow.opacity))
        FoundationFluidSide.End -> listOf(Color.Black.copy(alpha = shadow.opacity), Color.Transparent)
    }

    onDrawWithContent {
        drawContent()
        if (axis == FoundationPagerAxis.Horizontal) {
            drawRect(
                brush = Brush.horizontalGradient(colors = colors, startX = castStart, endX = castEnd),
                topLeft = Offset(castStart, 0f),
                size = Size((castEnd - castStart).coerceAtLeast(0f), size.height),
            )
        } else {
            drawRect(
                brush = Brush.verticalGradient(colors = colors, startY = castStart, endY = castEnd),
                topLeft = Offset(0f, castStart),
                size = Size(size.width, (castEnd - castStart).coerceAtLeast(0f)),
            )
        }
    }
}

/**
 * 진행 중인 수동 드래그가 어느 side로 향하는 중인지로, 제스처가 활성 상태가 아니거나 아직
 * turn 축을 따라 방향을 확정할 만큼 충분히 움직이지 않았으면 null이다.
 *
 * @param axis 드래그를 가로축과 세로축 중 어느 쪽으로 읽는지.
 * @param start 제스처가 시작된 위치.
 * @param current 제스처의 현재 위치.
 * @return 드래그가 향하는 side, 또는 아직 확정된 방향이 없으면 null.
 */
private fun foundationGestureSide(
    axis: FoundationPagerAxis,
    start: Offset,
    current: Offset,
): FoundationFluidSide? {
    val delta = axis.primary(current) - axis.primary(start)
    if (abs(delta) < FoundationGestureDirectionThresholdPx) return null
    return if (delta < 0f) FoundationFluidSide.End else FoundationFluidSide.Start
}

/**
 * 활성 turn의 대상이 어느 이웃이고 얼마나 진행됐는지를 판단한다. 제스처 상태 전체가 아니라
 * [FoundationPagerGestureTracker.phase]가 이미 뽑아 둔 두 값만 받으므로, 실제 제스처/pager 없이도
 * 유닛 테스트할 수 있다: 드래그가 활성 상태이지만 아직
 * side를 확정하지 못한 동안에는, 어느 이웃의 pager progress가 우연히 더 높은지를 가져다 쓰는
 * 대신 보고하는 turn의 progress를 0으로 둔다 — 그렇지 않으면 한 방향으로 움직이기 시작한
 * 드래그가 방향이 확정되기 전 한 프레임 동안 다른 쪽 이웃의 shadow/reveal을 잠깐 비출 수 있다.
 *
 * @param gestureActive 수동 드래그가 현재 진행 중인지 여부.
 * @param gestureSide 드래그가 확정한 side, 또는 활성 상태이지만 방향이 없거나 드래그 자체가
 *   없으면 null.
 * @param previousProgress previous-page turn이 얼마나 안착했는지, `[0, 1]` 범위.
 * @param nextProgress next-page turn이 얼마나 안착했는지, `[0, 1]` 범위.
 * @return 현재 활성 상태인 turn의 side와 progress.
 */
internal fun foundationActivePageTurn(
    gestureActive: Boolean,
    gestureSide: FoundationFluidSide?,
    previousProgress: Float,
    nextProgress: Float,
): FoundationActivePageTurn {
    val progressSide = if (nextProgress >= previousProgress) {
        FoundationFluidSide.End
    } else {
        FoundationFluidSide.Start
    }
    if (gestureActive && gestureSide == null) {
        return FoundationActivePageTurn(progressSide, 0f)
    }

    val activeSide = gestureSide ?: progressSide
    val activeProgress = when (activeSide) {
        FoundationFluidSide.Start -> previousProgress
        FoundationFluidSide.End -> nextProgress
    }
    return FoundationActivePageTurn(activeSide, activeProgress)
}

/**
 * fluid-reveal과 circle-reveal style의 쌓임 순서: current 페이지는 안착한 이웃 위에 놓이지만,
 * 지금 드러나고 있는 이웃은 current 페이지 위로 올라와서, 커지는 fold/원이 그 아래 페이지에 잘리는
 * 대신 그 위로 들려 올라오는 것처럼 보이게 한다.
 *
 * 값은 순서만 가르는 이산값이다. 예전에는 활성 이웃이 `2f + progress`를 받아 프레임마다 값이
 * 달라졌고, [Modifier.zIndex]는 composition 시점 값을 요구하므로 그 슬롯이 프레임마다 재구성됐다 —
 * `2f`와 `3f` 사이에 놓일 슬롯이 없으니 그 소수부는 어떤 순서도 바꾸지 못했다.
 *
 * @param page 배치할 대상 pager 슬롯.
 * @param activeSide 활성 turn이 어느 쪽(start/end)에서 드러나고 있는지.
 * @param isTurnActive 활성 turn이 실제로 진행 중인지; 아니면 이웃은 쌓임 순서에서 맨 뒤로 간다.
 * @return [page]의 z-index.
 */
private fun foundationRevealZIndex(
    page: FoundationPagerPage,
    activeSide: FoundationFluidSide,
    isTurnActive: Boolean,
): Float = when {
    page == FoundationPagerPage.Current -> 1f
    !isTurnActive -> 0f
    page.side == activeSide -> 2f
    else -> 0f
}

/**
 * fluid-reveal 또는 circle-reveal 이웃 슬롯의 클리핑 모양: current 페이지는 결코 클리핑되지
 * 않고, 비활성이거나 아직 진행되지 않은 이웃은 활성 turn 아래에 전체 프레임으로 그대로 보이는
 * 대신 [Modifier.foundationHiddenWhenInactive]를 통해 완전히 숨겨지며, 활성 side의 이웃은
 * [clip]이 만들어 주는 모양으로 클리핑된다.
 *
 * style별 클리핑을 enum과 style 전용 파라미터로 분기하는 대신 [clip]으로 받는 이유는, 그래야
 * 각 호출자가 자기 style이 실제로 쓰는 것만 넘기기 때문이다 — 예전 형태는 circle 호출자에게도
 * fluid 전용 파라미터를 노출했고, 그쪽에서 채우지 않은 그 자리가 쓰이지도 않을 edge를 새로
 * 할당하는 폴백으로 이어졌다. [clip]은 실제로 클리핑이 필요할 때만 호출된다.
 *
 * @param page 이 modifier가 적용될 pager 슬롯.
 * @param activeSide 활성 turn이 어느 쪽(start/end)에서 드러나고 있는지.
 * @param isTurnActive 활성 turn이 실제로 진행 중인지; 아니면 이웃은 클리핑 대신 완전히 숨겨진다.
 * @param clip 이 슬롯이 실제로 드러나는 중일 때 쓸 style별 클리핑 modifier.
 * @return 이 슬롯을 자신의 reveal 모양으로 클리핑하는 modifier, 이를 숨기는 modifier, 또는
 *   current 페이지면 변경되지 않은 [Modifier].
 */
private inline fun foundationRevealModifier(
    page: FoundationPagerPage,
    activeSide: FoundationFluidSide,
    isTurnActive: Boolean,
    clip: () -> Modifier,
): Modifier {
    if (page == FoundationPagerPage.Current) return Modifier
    if (page.side != activeSide || !isTurnActive) return Modifier.foundationHiddenWhenInactive(true)
    return clip()
}

/**
 * 활성 reveal이 아직 닿지 않은 이웃 슬롯을 단순히 클리핑하지 않는 것을 넘어 완전히 투명하게
 * 만든다 — 전체 프레임으로 그대로 보이게 두면, fluid edge나 원이 실제로 닿았을 때 비로소
 * 나타나는 대신 current 페이지와 납작하게 겹쳐 보일 것이다.
 *
 * @receiver 이 가시성이 덧붙는 modifier 체인.
 * @param hidden 이 슬롯을 숨겨야 하는지 여부.
 * @return [hidden]이면 alpha를 0으로 적용한 receiver, 아니면 변경되지 않은 receiver.
 */
private fun Modifier.foundationHiddenWhenInactive(hidden: Boolean): Modifier = if (hidden) {
    graphicsLayer { alpha = 0f }
} else {
    this
}

/**
 * [PageAnimation.FLUID_PAGER]를 위해 콘텐츠를 [FoundationFluidEdge]의 현재 물결 edge 모양으로
 * 클리핑한다. [foundationCircleRevealClip]과 마찬가지로 [clip]의 [Shape]가 아니라
 * `drawWithCache`/[clipPath]로 그린다: [Shape] 판은 호출마다 익명 객체를 새로 할당했고, 그
 * 인스턴스가 매 프레임 바뀌는 탓에 레이어가 프레임마다 무효화됐다. 또한 [FoundationFluidEdge.version]을
 * 여기 캐시 블록 안에서 읽으므로, edge가 제자리에서 변할 때 재구성 없이 그리기만 다시 돈다 —
 * 예전에는 이 카운터를 [FoundationEffectPager] 본문에서 읽어 pager 전체가 프레임마다
 * 재구성됐다.
 *
 * [fluidEdge]의 목표를 기록하는 `applyTarget` 호출은 슬롯 크기를 알아야 하므로 여전히 여기
 * 그리기 경로에 있다. 목표를 뒤쫓는 물리 시뮬레이션 자체는 [FoundationEffectPager] 안의
 * `LaunchedEffect(pageAnimation)` 루프가 프레임마다 한 번씩 돌린다.
 *
 * @receiver 이 클리핑이 덧붙는 modifier 체인.
 * @param axis turn이 가로축과 세로축 중 어느 쪽으로 진행되는지.
 * @param side fluid edge가 어느 side(start/end)에서 전진하는지.
 * @param turnProvider 그 프레임의 활성 turn을 돌려준다; 그리기 캐시 안에서만 호출된다. side를 여기서
 *   다시 대조하는 이유는 composition의 gate가 한 프레임 뒤질 수 있기 때문이다 — 그 프레임에
 *   turn이 다른 이웃으로 넘어갔다면 이 슬롯은 아무것도 그리지 않아야 한다.
 * @param gesture 터치 지점이 edge의 부풀어 오름을 조종하는, 수동 드래그/터치 상태.
 * @param fluidEdge 이 클리핑이 구동하는 동시에 읽는, 공유되는 spring 애니메이션 edge 모양.
 * @return receiver를 fluid edge의 현재 모양으로 클리핑하는 modifier.
 */
private fun Modifier.foundationFluidClip(
    axis: FoundationPagerAxis,
    side: FoundationFluidSide,
    turnProvider: () -> FoundationActivePageTurn,
    gesture: FoundationPagerGestureTracker,
    fluidEdge: FoundationFluidEdge,
): Modifier = drawWithCache {
    @Suppress("UNUSED_VARIABLE")
    val version = fluidEdge.version
    val turn = turnProvider()
    if (turn.side != side) {
        return@drawWithCache onDrawWithContent { }
    }
    val sizeModel = FoundationPagerSize(size.width, size.height)
    fluidEdge.applyTarget(
        side = side,
        progress = turn.progress,
        touchCrossAxis = foundationTouchCrossAxis(
            axis = axis,
            size = sizeModel,
            touch = gesture.touchPoint(),
        ),
        touchActive = gesture.pressed,
    )
    val path = buildFoundationFluidPolygon(
        size = sizeModel,
        axis = axis,
        side = side,
        edge = fluidEdge,
    ).toPath()
    onDrawWithContent {
        clipPath(path) {
            this@onDrawWithContent.drawContent()
        }
    }
}

/**
 * [PageAnimation.CIRCLE_REVEAL]을 위해 콘텐츠를 커지는 원으로 클리핑하며, ([foundationFluidClip]과
 * 달리) [clip]이 아니라 [clipPath]/`drawWithCache`로 그린다. 원은 프레임마다 유지되는 물리
 * 상태가 필요 없기 때문인데 — 그 기하는 [progress]와 터치 지점만의 순수 함수여서, 캐시가
 * 무효화될 때마다 새로 계산된다.
 *
 * @receiver 이 클리핑이 덧붙는 modifier 체인.
 * @param axis turn이 가로축과 세로축 중 어느 쪽으로 진행되는지.
 * @param side 원의 원점이 어느 side에 놓이는지.
 * @param turnProvider 그 프레임의 활성 turn을 돌려준다; 그리기 캐시 안에서만 호출된다. side를 여기서
 *   다시 대조하는 이유는 composition의 gate가 한 프레임 뒤질 수 있기 때문이다 — 그 프레임에
 *   turn이 다른 이웃으로 넘어갔다면 이 슬롯은 아무것도 그리지 않아야 한다.
 * @param gesture 원의 원점을 고정하는 터치 지점을 가진, 수동 드래그/터치 상태.
 * @return receiver를 원의 현재 모양으로 클리핑하는 modifier.
 */
private fun Modifier.foundationCircleRevealClip(
    axis: FoundationPagerAxis,
    side: FoundationFluidSide,
    turnProvider: () -> FoundationActivePageTurn,
    gesture: FoundationPagerGestureTracker,
): Modifier = drawWithCache {
    val turn = turnProvider()
    if (turn.side != side) {
        return@drawWithCache onDrawWithContent { }
    }
    val progress = turn.progress
    val touchCrossAxis = foundationTouchCrossAxis(
        axis = axis,
        size = FoundationPagerSize(size.width, size.height),
        touch = gesture.touchPoint(),
    )
    val path = buildFoundationCircleRevealPath(
        size = FoundationPagerSize(size.width, size.height),
        axis = axis,
        side = side,
        progress = progress,
        touchCrossAxis = touchCrossAxis,
    )
    onDrawWithContent {
        clipPath(path) {
            this@onDrawWithContent.drawContent()
        }
    }
}

/**
 * [PageAnimation.MOVIE_CAROUSEL] transform: [foundationMovieCarouselSpec]을 통해 멀어지는
 * 페이지를 축소하고 옅어지게 한다. 또한 [axis]를 따라 이동+3D 기울임 분기도 가지고 있는데,
 * 이는 페이지가 멀어지면서 옆으로 미끄러지고 뷰어에게서 등을 돌리듯 기울게 만들되,
 * [FoundationCameraDistance]로 완전히 기울었을 때도 원근 단축이 어안렌즈처럼 왜곡되지 않고
 * 은은하게 유지되도록 한다 — 그런데 [FoundationMovieTranslationRatio]가 현재 `0f`이므로
 * `spec.translationFraction`은 항상 0이고 이 분기는 지금은 어떤 이동이나 회전도 적용하지
 * 않는다; 눈에 보이게 동작하는 것은 scale과 alpha뿐이다.
 *
 * @receiver 이 transform이 덧붙는 modifier 체인.
 * @param axis carousel이 가로축과 세로축 중 어느 쪽으로 움직이는지; (현재는 작동하지 않는)
 *   기울임 분기가 어느 이동/회전 쌍을 적용할지를 결정한다.
 * @param page 이 transform이 적용될 pager 슬롯.
 * @param offsetProvider 이 슬롯이 pager의 안착 위치로부터 갖는 부호 있는 오프셋을 돌려준다,
 *   `[-1, 1]` 범위; 레이어 블록 안에서만 호출된다.
 * @return carousel의 scale과 alpha를 적용하는 modifier(그리고 [FoundationMovieTranslationRatio]가
 *   언젠가 0이 아니게 되면 이동/기울임도).
 */
private fun Modifier.foundationMovieCarouselLayer(
    axis: FoundationPagerAxis,
    page: FoundationPagerPage,
    offsetProvider: () -> Float,
): Modifier = graphicsLayer {
    val spec = foundationMovieCarouselSpec(page, offsetProvider())
    scaleX = spec.scale
    scaleY = spec.scale
    alpha = spec.alpha
    cameraDistance = FoundationCameraDistance
    if (axis == FoundationPagerAxis.Horizontal) {
        translationX = size.width * spec.translationFraction
        rotationY = -spec.translationFraction * FoundationMovieRotationDegrees
    } else {
        translationY = size.height * spec.translationFraction
        rotationX = spec.translationFraction * FoundationMovieRotationDegrees
    }
}

/**
 * [PageAnimation.MOVIE_CAROUSEL]의 쌓임 순서: current 페이지 위로 활발히 미끄러져 들어오는
 * 페이지가 가장 위에 놓이고, current 페이지는 손대지 않은 먼 이웃 위에 놓이며, 활성 turn이
 * 닿지 않은 이웃은 맨 뒤에 놓인다 — fluid/circle style에 대한 [foundationRevealZIndex]의
 * 로직을 그대로 따르되, current 페이지가 고정된 중간 순위 대신 항상 비활성 이웃보다 위에
 * 있다는 점이 다르다.
 *
 * @param page 배치할 대상 pager 슬롯.
 * @param activeSide 활성 turn이 어느 쪽(start/end)에서 오는 중인지.
 * @param isTurnActive 활성 turn이 실제로 진행 중인지; 아니면 이웃은 쌓임 순서에서 맨 뒤로 간다.
 * @return [page]의 z-index.
 */
private fun foundationMovieZIndex(
    page: FoundationPagerPage,
    activeSide: FoundationFluidSide,
    isTurnActive: Boolean,
): Float = when {
    !isTurnActive && page == FoundationPagerPage.Current -> 2f
    !isTurnActive -> 0f
    page.side == activeSide -> 3f
    page == FoundationPagerPage.Current -> 2f
    else -> 0f
}

/**
 * page-flip 애니메이션의 쌓임 순서로, 여기서는 pager 자신의 이동이 상쇄되어 있다.
 *
 * 아무것도 이동하지 않으므로 세 페이지 모두 같은 자리에 놓이고, 오직 이 순서만이 그들을
 * 구분한다. 두 이웃에 같은 인덱스를 주면 composition 순서가 승패를 갈랐고, 그 결과 먼 쪽
 * 이웃이 이겨서 — 뒤로 turn할 때 접히는 페이지가 투명하게 남기는 절반 너머로 *다음* 페이지가
 * 비쳐 보였다. 지금 다가오고 있는 이웃을 위로 올리는 것이 이를 고친다.
 *
 * 이웃의 값은 이산값이다. 예전에는 `2f - abs(pageOffset)`이라 프레임마다 달라졌고,
 * [Modifier.zIndex]는 composition 시점 값을 요구하므로 세 슬롯이 프레임마다 재구성됐다 — 정작
 * 순서를 가르는 것은 "다가오는 이웃인가" 하나뿐이다.
 *
 * 판별 기준이 오프셋의 부호가 아니라 크기인 이유: [PagerState.foundationOffsetForPage]는 각
 * 슬롯이 *자기* 인덱스로부터 얼마나 떨어졌는지를 재므로, previous 슬롯의 오프셋은 늘 양수 쪽,
 * next 슬롯은 늘 음수 쪽이다. 부호는 슬롯마다 고정된 상수라 아무것도 가르지 못하고, 두 이웃에 같은
 * 값을 주어 위 문단의 bleed-through를 그대로 되살린다. 실제로 움직이는 것은 크기이며, 다가오는
 * 이웃만이 `1`보다 작아진다.
 *
 * @param page 세 페이지 중 어느 것을 배치하는 중인지.
 * @param isApproaching [page] 슬롯이 화면으로 다가오는 중인지
 *   ([FoundationPageFlipPhase.isApproaching]).
 * @return z-index: current 페이지는 항상 맨 위에, 다가오는 이웃이 멀어지는 이웃 위에.
 */
internal fun foundationPageFlipZIndex(
    page: FoundationPagerPage,
    isApproaching: Boolean,
): Float = when (page) {
    FoundationPagerPage.Current -> 3f
    FoundationPagerPage.Previous, FoundationPagerPage.Next -> if (isApproaching) 2f else 1f
}

/**
 * [FoundationPageFlipHalfBox]나 [foundationPageFlipShape] 호출이 콘텐츠를 앉히는 대상이,
 * 페이지의 어느 사분면([FoundationPageFlipLayout.WholePage]의 hinge shadow용)인지 어느
 * 절반([FoundationPageFlipLayout.SplitHalfFold]의 spine용)인지.
 */
internal enum class FoundationPageFlipHalf {
    Top,
    Bottom,
    Left,
    Right,
}

/** 클리핑된 절반의 바깥쪽 자유 edge로, StPageFlip의 inner shadow가 여기서 시작된다. */
internal fun foundationPageFlipHalfShadowSide(half: FoundationPageFlipHalf): FoundationFluidSide = when (half) {
    FoundationPageFlipHalf.Left,
    FoundationPageFlipHalf.Top,
        -> FoundationFluidSide.Start
    FoundationPageFlipHalf.Right,
    FoundationPageFlipHalf.Bottom,
        -> FoundationFluidSide.End
}

/**
 * [Modifier.foundationPageFlipInnerShadow]가 쓰는 그라데이션 정지점으로, [side]에서 가장 진하고
 * 반대쪽으로 갈수록 단조롭게 옅어진다. 호출자는 [side]에 fold 쪽을 넘긴다.
 *
 * 정지점은 반드시 단조여야 한다. 이전 구현은 StPageFlip의 종이 하이라이트를 흉내 내려고 진한 띠
 * 두 개 사이에 알파 0.05짜리 정지점을 끼워 넣었는데, 2D 띠에서는 하이라이트가 아니라 밝은 수직
 * 선으로 보였다. 게다가 첫 정지점이 `0.05f`여서 0..5% 구간이 최대 알파 단색으로 클램프돼 자유
 * edge에 딱딱한 경계가 생겼다 — 지금은 첫 정지점을 `0f`에 둔다.
 *
 * @param side shade가 가장 진한 쪽이 이 축의 시작 쪽인지 끝 쪽인지.
 * @param alpha [side]에서의 최대 shade 알파.
 * @return [Brush.horizontalGradient]/[Brush.verticalGradient]에 그대로 넘길 정지점으로, 축을 따라
 *   오프셋이 증가하는 순서다.
 */
internal fun foundationPageFlipInnerShadowStops(
    side: FoundationFluidSide,
    alpha: Float,
): Array<Pair<Float, Color>> {
    val mid = alpha * FoundationPageFlipInnerMidAlphaRatio
    return if (side == FoundationFluidSide.Start) {
        arrayOf(
            0f to Color.Black.copy(alpha = alpha),
            FoundationPageFlipInnerMidStop to Color.Black.copy(alpha = mid),
            1f to Color.Transparent,
        )
    } else {
        arrayOf(
            0f to Color.Transparent,
            1f - FoundationPageFlipInnerMidStop to Color.Black.copy(alpha = mid),
            1f to Color.Black.copy(alpha = alpha),
        )
    }
}

/**
 * leaf가 접히는 fold(경첩) 쪽으로, 자유 edge의 반대편이다.
 *
 * 종이가 들리면 fold 근처가 자기 자신에 가려 가장 어둡고 자유 edge로 갈수록 밝아진다. 참조
 * 구현(StPageFlip)은 inner shadow를 자유 edge에 두지만 실제 종이와는 반대여서, shade의 가장
 * 진한 쪽을 fold로 옮긴다. [Modifier.foundationPageFlipInnerShadow]는 띠가 fold에서 자유 edge
 * 쪽으로 뻗으므로, 그 띠 안에서 가장 진한 정지점이 놓일 쪽을 이 함수로 고른다.
 *
 * @param freeEdge [foundationPageFlipHalfShadowSide]가 내는 leaf의 자유 edge 쪽.
 * @return 그 반대편, 즉 fold 쪽.
 */
internal fun foundationPageFlipFoldSide(freeEdge: FoundationFluidSide): FoundationFluidSide = when (freeEdge) {
    FoundationFluidSide.Start -> FoundationFluidSide.End
    FoundationFluidSide.End -> FoundationFluidSide.Start
}

/** [PageAnimation.PAGE_FLIP]이 페이지 전체를 한 장의 시트로 접는지, 경첩으로 이어진 두 절반으로 나누는지. */
internal enum class FoundationPageFlipLayout {
    /** 단일 pane이 자신의 바깥쪽 edge를 축으로 뻣뻣한 한 장의 시트처럼 넘어간다; [FoundationWholePageFlipBox] 참고. */
    WholePage,

    /** 두 pane짜리 spread가 자신의 spine을 따라 한 번에 한 절반씩 접힌다; [FoundationSpreadPageFlipBox] 참고. */
    SplitHalfFold,
}

/**
 * [foundationWholePageFlipSpec]이 계산하는, [FoundationPageFlipLayout.WholePage] turn의
 * 회전과 피벗 모서리.
 *
 * @property rotationX 페이지가 가로축을 중심으로 회전하는 각도, degree 단위.
 * @property rotationY 페이지가 세로축을 중심으로 회전하는 각도, degree 단위.
 * @property transformOriginX 피벗의 가로 위치로, 페이지 너비에 대한 비율, `[0, 1]` 범위.
 * @property transformOriginY 피벗의 세로 위치로, 페이지 높이에 대한 비율, `[0, 1]` 범위.
 */
internal data class FoundationWholePageFlipSpec(
    val rotationX: Float,
    val rotationY: Float,
    val transformOriginX: Float,
    val transformOriginY: Float,
)

/**
 * PAGE_FLIP의 outer cast shadow와 움직이는 leaf의 inner shadow를 위한, 참조 구현 기반 치수.
 *
 * @property side 움직이는 자유 edge, 또는 손대지 않은 시작 위치면 null.
 * @property outerWidthFraction 전체 viewport 크기에 대한 비율로 나타낸 outer-shadow 너비.
 * @property innerWidthFraction 전체 viewport 크기에 대한 비율로 나타낸 inner-shadow 너비.
 * @property opacity 공유되는 shadow 불투명도.
 */
internal data class FoundationPageFlipShadowSpec(
    val side: FoundationFluidSide?,
    val outerWidthFraction: Float,
    val innerWidthFraction: Float,
    val opacity: Float,
)

/**
 * StPageFlip의 `0.75 * leafWidth * progress` outer 너비 공식을 구현한다. 최대 alpha는 Harism의
 * page-curl shadow 색상을 따르며, split spread는 leaf 하나가 spread의 절반을 차지하므로
 * viewport의 절반을 쓴다.
 *
 * opacity는 `maxAlpha * sin(progress * PI)`로 turn 중간에 최대가 되고 양끝에서 0이 된다 —
 * [foundationFluidShadow], [Modifier.foundationMovingEdgeShadow], 3D curl의 cast shadow가 쓰는
 * 것과 같은 엔벨로프다. StPageFlip 원본의 `maxOpacity * (1 - progress)`는 leaf가 아직 들리지도
 * 않은 turn 시작에서 그림자가 최대였고, 시트가 가장 많이 들려 있는 후반에 오히려 옅어졌다.
 */
internal fun foundationPageFlipShadowSpec(
    pageOffset: Float,
    layout: FoundationPageFlipLayout,
): FoundationPageFlipShadowSpec {
    val offset = pageOffset.coerceIn(-1f, 1f)
    val progress = abs(offset)
    val leafWidthFraction = when (layout) {
        FoundationPageFlipLayout.WholePage -> 1f
        FoundationPageFlipLayout.SplitHalfFold -> 0.5f
    }
    val outerWidth = FoundationPageFlipOuterWidthRatio * leafWidthFraction * progress
    return FoundationPageFlipShadowSpec(
        side = when {
            offset > 0f -> FoundationFluidSide.End
            offset < 0f -> FoundationFluidSide.Start
            else -> null
        },
        outerWidthFraction = outerWidth,
        innerWidthFraction = outerWidth * FoundationPageFlipInnerWidthRatio,
        opacity = FoundationPageFlipMaxShadowAlpha * sin(progress * PI.toFloat()),
    )
}

/**
 * PAGE_FLIP의 contact/cast shadow가 어디서 시작해 그 아래 페이지 위 어느 방향으로 뻗는지를
 * 나타낸다. 두 레이아웃 모두 움직이는 자유 edge를 사용하며 incoming 페이지의 드러난 쪽으로
 * shadow를 드리운다. [Modifier.foundationPageFlipProjectedShadow]는 두 필드를 그대로
 * 사용한다.
 *
 * @property shadowEdgeFraction contact와 cast shadow가 turn 축을 따라 시작되는 위치.
 * @property castDirection [shadowEdgeFraction]으로부터 cast가 뻗어 나가는, 드러난 쪽.
 */
internal data class FoundationPageFlipProjectionSpec(
    val shadowEdgeFraction: Float,
    val castDirection: FoundationFluidSide,
)

/**
 * PAGE_FLIP 프레임 하나에 대한 shadow/contact edge와 cast 방향을 계산하며, 완료되거나
 * 취소된 turn 뒤에 낡은 띠가 남지 않도록 안착한 끝점에서는 null을 반환한다.
 *
 * whole-page leaf는 [foundationWholePageFlipSpec]과 같은 방향의 피벗을 쓰며, 그 자유 edge는
 * 회전을 투영한 코사인 값을 따라 그 피벗 쪽으로 쓸어간다. cast는 leaf에서 멀어져, 그 움직이는
 * edge 너머로 드러난 incoming 페이지 위로 뻗는다.
 *
 * split-half leaf는 항상 spine의 `0.5` 지점을 피벗으로 삼는다. 그 자유 edge는 outgoing
 * 절반의 바깥쪽 edge에서 시작해, 정면으로 선 채 spine에 이르렀다가, fold가 완료됨에 따라
 * 반대쪽 incoming 절반으로 `0.5 + direction * 0.5 * cos(progress * PI)`를 따라 이어진다.
 * 여기서 `direction`은 outgoing 쪽(정방향 turn이면 끝 쪽으로, 역방향이면 시작 쪽으로)이다.
 * receiver는 edge가 spine을 넘기 전/후 실제로 위치한 절반을 따르며, cast는 spine 쪽
 * 불투명한 leaf 아래가 아니라 그 드러난 절반 안쪽으로 더 뻗어 나간다. 정확히 정면으로 선
 * 중간점에서는 움직이는 edge가 spine 자체 위에 놓이는데(`cos(PI/2) = 0`), 이때 receiver는
 * 결정론적으로 incoming 절반(leaf가 넘어가는 쪽) 쪽으로 결정되므로, 프레임이 모호해지는 일은
 * 없다.
 *
 * @param pageOffset `[-1, 1]` 범위의 부호 있는 pager progress; 크기는 turn progress를,
 *   부호는 turn 방향을 나타낸다. 범위 밖의 값은 고정된다.
 * @param layout turn이 페이지 전체를 한 장으로 접는지 spine을 따라 나누는지.
 * @return 이 프레임의 움직이는-edge 투영, 또는 turn이 `0`이나 `±1`에 안착했으면 null.
 */
internal fun foundationPageFlipProjectionSpec(
    pageOffset: Float,
    layout: FoundationPageFlipLayout,
): FoundationPageFlipProjectionSpec? {
    val offset = pageOffset.coerceIn(-1f, 1f)
    val progress = abs(offset)
    if (progress == 0f || progress == 1f) return null

    return when (layout) {
        FoundationPageFlipLayout.WholePage -> {
            val pivot = if (offset > 0f) 0f else 1f
            val free = 1f - pivot
            val movingEdge = pivot + (free - pivot) * cos(progress * (PI.toFloat() / 2f))
            val cast = if (pivot == 0f) FoundationFluidSide.End else FoundationFluidSide.Start
            FoundationPageFlipProjectionSpec(
                shadowEdgeFraction = movingEdge,
                castDirection = cast,
            )
        }

        FoundationPageFlipLayout.SplitHalfFold -> {
            val direction = if (offset > 0f) 1f else -1f
            val movingEdge = 0.5f + direction * 0.5f * cos(progress * PI.toFloat())
            val incomingSide = if (direction > 0f) FoundationFluidSide.Start else FoundationFluidSide.End
            val receiver = when {
                movingEdge > 0.5f + FoundationPageFlipSpineEpsilon -> FoundationFluidSide.End
                movingEdge < 0.5f - FoundationPageFlipSpineEpsilon -> FoundationFluidSide.Start
                else -> incomingSide
            }
            val cast = receiver
            FoundationPageFlipProjectionSpec(
                shadowEdgeFraction = movingEdge,
                castDirection = cast,
            )
        }
    }
}

/**
 * [foundationPageFlipHalfSpec]이 계산하는, [FoundationPageFlipLayout.SplitHalfFold] turn에서
 * 자리잡은 절반/사분면 하나의 회전.
 *
 * @property rotationX 그 절반이 가로축을 중심으로 회전하는 각도, degree 단위.
 * @property rotationY 그 절반이 세로축을 중심으로 회전하는 각도, degree 단위.
 */
internal data class FoundationPageFlipHalfSpec(
    val rotationX: Float,
    val rotationY: Float,
)

/**
 * [foundationSpreadPageFlipSpec]이 계산하는, 진행 중인 [FoundationPageFlipLayout.SplitHalfFold]
 * turn의 전체 레이아웃: 어느 절반이 접혀 나가는 중인지, incoming 이웃이 어느 절반에 내려앉는지,
 * 각각의 회전, 그리고 각각이 현재 보이는지 여부.
 *
 * @property outgoingHalf current 페이지의 leaf가 접혀 나가는 절반.
 * @property incomingHalf incoming 이웃이 드러나는 절반 — leaf가 충분히 회전해 덮고 나면
 *   leaf가 접혀 들어가는 바로 그 절반.
 * @property outgoing outgoing leaf의 현재 회전.
 * @property incoming leaf의 뒷면 아래로 보이게 됐을 때 incoming 이웃의 회전.
 * @property showOutgoing outgoing leaf가 아직 앞에 있는지, 즉 progress가 절반 이하인지.
 * @property showIncoming incoming 이웃이 드러났는지, 즉 progress가 절반 이상인지.
 */
internal data class FoundationSpreadPageFlipSpec(
    val outgoingHalf: FoundationPageFlipHalf,
    val incomingHalf: FoundationPageFlipHalf,
    val outgoing: FoundationPageFlipHalfSpec,
    val incoming: FoundationPageFlipHalfSpec,
    val showOutgoing: Boolean,
    val showIncoming: Boolean,
)

/**
 * [PageAnimation.PAGE_FLIP]을 위해 [FoundationPageFlipLayout.WholePage]와
 * [FoundationPageFlipLayout.SplitHalfFold] 중 하나를 고른다: 한 번에 페이지 하나씩 넘어가는
 * 단일 페이지는 한 장의 시트로 접히는 반면, 다중 pane spread(2단 레이아웃, 또는 한 스텝에
 * 페이지 두 장 이상을 넘기는 경우)는 각 pane을 자신의 spine을 따라 접는다. 한 장짜리
 * fold에는 spread를 앉힐 자연스러운 경첩이 없기 때문이다.
 *
 * @param pageStep 한 번의 turn이 몇 페이지를 진행시키는지; 최소 1로 고정된다.
 * @param paneCount 몇 개의 페이지 pane이 나란히 보이는지; 최소 1로 고정된다.
 * @return 둘 다 정확히 1일 때만 [FoundationPageFlipLayout.WholePage], 그 외에는
 *   [FoundationPageFlipLayout.SplitHalfFold].
 */
internal fun foundationPageFlipLayout(pageStep: Int, paneCount: Int): FoundationPageFlipLayout =
    if (pageStep.coerceAtLeast(1) == 1 && paneCount.coerceAtLeast(1) == 1) {
        FoundationPageFlipLayout.WholePage
    } else {
        FoundationPageFlipLayout.SplitHalfFold
    }

/**
 * [FoundationWholePageFlipBox]가 사용하는, [FoundationPageFlipLayout.WholePage] turn의 회전과
 * 피벗: 페이지는 자신이 넘어가는 방향에서 먼 쪽에 있는 자신의 모서리 하나를 중심으로 피벗해서,
 * 자기 중심을 축으로 회전하는 대신 그 edge에 달린 경첩에서 흔들리는 것처럼 보이게 한다.
 *
 * @param axis fold가 가로축과 세로축 중 어느 쪽으로 도는지.
 * @param pageOffset 이 슬롯이 pager의 안착 위치로부터 갖는 부호 있는 오프셋, `[-1, 1]` 범위;
 *   사용 전에 이 범위로 고정된다.
 * @return 이 오프셋에 대한 회전과 피벗 모서리.
 */
internal fun foundationWholePageFlipSpec(
    axis: FoundationPagerAxis,
    pageOffset: Float,
): FoundationWholePageFlipSpec {
    val offset = pageOffset.coerceIn(-1f, 1f)
    return when (axis) {
        FoundationPagerAxis.Horizontal -> if (offset <= 0f) {
            FoundationWholePageFlipSpec(
                rotationX = 0f,
                rotationY = -offset * FoundationWholePageFlipRotationDegrees,
                transformOriginX = 1f,
                transformOriginY = 0.5f,
            )
        } else {
            FoundationWholePageFlipSpec(
                rotationX = 0f,
                rotationY = -offset * FoundationWholePageFlipRotationDegrees,
                transformOriginX = 0f,
                transformOriginY = 0.5f,
            )
        }

        FoundationPagerAxis.Vertical -> if (offset <= 0f) {
            FoundationWholePageFlipSpec(
                rotationX = offset * FoundationWholePageFlipRotationDegrees,
                rotationY = 0f,
                transformOriginX = 0.5f,
                transformOriginY = 1f,
            )
        } else {
            FoundationWholePageFlipSpec(
                rotationX = offset * FoundationWholePageFlipRotationDegrees,
                rotationY = 0f,
                transformOriginX = 0.5f,
                transformOriginY = 0f,
            )
        }
    }
}

/**
 * [FoundationPageFlipLayout.SplitHalfFold] turn에서 절반/사분면 하나의 회전: turn이 향하는
 * 쪽의 절반만 회전하며([pageOffset]의 부호가 먹이는 `startOffset`/`endOffset` 중 하나를
 * 통해), 그래서 활성 leaf가 아닌 절반은 다른 절반의 움직임이 번져 들어오는 대신 회전 0인
 * 평평한 상태로 남는다.
 *
 * @param half 이 회전을 계산할 대상 절반/사분면.
 * @param pageOffset 이 슬롯이 pager의 안착 위치로부터 갖는 부호 있는 오프셋, `[-1, 1]` 범위;
 *   사용 전에 이 범위로 고정된다.
 * @return 이 오프셋에서 [half]의 회전.
 */
internal fun foundationPageFlipHalfSpec(
    half: FoundationPageFlipHalf,
    pageOffset: Float,
): FoundationPageFlipHalfSpec {
    val offset = pageOffset.coerceIn(-1f, 1f)
    val startOffset = max(offset, 0f)
    val endOffset = min(offset, 0f)
    return when (half) {
        FoundationPageFlipHalf.Top -> FoundationPageFlipHalfSpec(
            rotationX = endOffset * FoundationPageFlipRotationDegrees,
            rotationY = 0f,
        )
        FoundationPageFlipHalf.Bottom -> FoundationPageFlipHalfSpec(
            rotationX = startOffset * FoundationPageFlipRotationDegrees,
            rotationY = 0f,
        )
        FoundationPageFlipHalf.Left -> FoundationPageFlipHalfSpec(
            rotationX = 0f,
            rotationY = -(endOffset * FoundationPageFlipRotationDegrees),
        )
        FoundationPageFlipHalf.Right -> FoundationPageFlipHalfSpec(
            rotationX = 0f,
            rotationY = -(startOffset * FoundationPageFlipRotationDegrees),
        )
    }
}

/**
 * [FoundationSpreadPageFlipBox]가 사용하는, [FoundationPageFlipLayout.SplitHalfFold] turn의
 * 전체 레이아웃: 어느 절반이 접혀 나가는지와 incoming 이웃이 어느 절반에 떠오르는지를 알아낸
 * 뒤, 각각을 [foundationPageFlipHalfSpec]에 넘겨 자신의 회전을 구하게 한다. incoming 절반
 * 자신의 오프셋은 outgoing progress를 거울에 비춘 값(`incomingOffset`)으로 계산되어,
 * outgoing leaf가 밖으로 흔들려 나가는 것과 반대쪽에서 안으로 흔들려 들어와
 * `progress == 0.5`인 가운데서 만난다 — [showOutgoing]/[showIncoming]이 어느 절반을 위에
 * 그릴지 뒤바뀌는 바로 그 지점이다.
 *
 * @param axis fold가 가로축과 세로축 중 어느 쪽으로 도는지.
 * @param pageOffset 이 슬롯이 pager의 안착 위치로부터 갖는 부호 있는 오프셋, `[-1, 1]` 범위;
 *   사용 전에 이 범위로 고정된다.
 * @return outgoing/incoming 절반, 그 회전, 그리고 현재 보이는 쪽.
 */
internal fun foundationSpreadPageFlipSpec(
    axis: FoundationPagerAxis,
    pageOffset: Float,
): FoundationSpreadPageFlipSpec {
    val offset = pageOffset.coerceIn(-1f, 1f)
    val progress = abs(offset)
    val isNext = offset >= 0f
    val outgoingHalf = foundationSpreadPageFlipOutgoingHalf(axis, isNext)
    val incomingHalf = foundationSpreadPageFlipIncomingHalf(axis, isNext)
    val incomingOffset = if (isNext) -(1f - progress) else 1f - progress
    return FoundationSpreadPageFlipSpec(
        outgoingHalf = outgoingHalf,
        incomingHalf = incomingHalf,
        outgoing = foundationPageFlipHalfSpec(outgoingHalf, offset),
        incoming = foundationPageFlipHalfSpec(incomingHalf, incomingOffset),
        showOutgoing = progress <= 0.5f,
        showIncoming = progress >= 0.5f,
    )
}

/**
 * [FoundationPageFlipHalfBox]의 깔개를 위한 클리핑 [Shape]로, [FoundationPageFlipHalf]마다
 * 페이지 사각형의 한 사분면/절반이다. 각 모양은 사각형이 box 자신의 크기에만 좌우될 뿐 어떤
 * 프레임별 상태에도 좌우되지 않으므로, 호출마다 새로 할당하는 대신 공유되는 `val`에 담긴
 * 평범한 사각형이다.
 *
 * @param half 클리핑할 대상 사분면/절반.
 * @return 그에 대응하는 공유 모양 상수.
 */
private fun foundationPageFlipShape(half: FoundationPageFlipHalf): Shape = when (half) {
    FoundationPageFlipHalf.Top -> FoundationPageFlipTopShape
    FoundationPageFlipHalf.Bottom -> FoundationPageFlipBottomShape
    FoundationPageFlipHalf.Left -> FoundationPageFlipLeftShape
    FoundationPageFlipHalf.Right -> FoundationPageFlipRightShape
}

/** 페이지 사각형의 윗쪽 절반으로, [FoundationPageFlipHalf.Top]용. */
private val FoundationPageFlipTopShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, 0f, size.width, size.height / 2f))
}

/** 페이지 사각형의 아래쪽 절반으로, [FoundationPageFlipHalf.Bottom]용. */
private val FoundationPageFlipBottomShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, size.height / 2f, size.width, size.height))
}

/** 페이지 사각형의 왼쪽 절반으로, [FoundationPageFlipHalf.Left]용. */
private val FoundationPageFlipLeftShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, 0f, size.width / 2f, size.height))
}

/** 페이지 사각형의 오른쪽 절반으로, [FoundationPageFlipHalf.Right]용. */
private val FoundationPageFlipRightShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(size.width / 2f, 0f, size.width, size.height))
}

/**
 * [foundationMovieCarouselSpec]이 계산하는, [PageAnimation.MOVIE_CAROUSEL] 페이지의
 * scale/alpha/이동.
 *
 * @property translationFraction 페이지 자신의 크기에 대한 비율로 나타낸 이동량;
 *   [FoundationMovieTranslationRatio]가 `0f`로 유지되는 동안은 항상 `0f`이다
 *   ([Modifier.foundationMovieCarouselLayer] 참고).
 * @property scale 적용할 균일한 배율, `[FoundationMovieMinScale, 1]` 범위.
 * @property alpha 적용할 alpha, `[FoundationMovieMinAlpha, 1]` 범위.
 */
internal data class FoundationMovieCarouselSpec(
    val translationFraction: Float,
    val scale: Float,
    val alpha: Float,
)

/**
 * [PageAnimation.MOVIE_CAROUSEL] 페이지의 scale/alpha/이동: turn에 따라 축소되고 옅어지는
 * 것은 오직 current 페이지뿐이다 — 이웃 슬롯은 자신의 [pageOffset]과 무관하게 여기서 항상
 * 오프셋 0을 보고하는데, 이는 그것이 incoming 페이지이며 outgoing current 페이지가 멀어져
 * 가는 것과 같은 먼 상태에서 축소되며 들어오는 대신 완전한 크기/alpha로 도착해야 하기
 * 때문이다.
 *
 * @param page 이 spec이 적용될 pager 슬롯.
 * @param pageOffset 이 슬롯이 pager의 안착 위치로부터 갖는 부호 있는 오프셋, `[-1, 1]` 범위;
 *   [page]가 [FoundationPagerPage.Current]일 때만 읽힌다.
 * @return 이 슬롯의 scale, alpha, 이동 비율.
 */
internal fun foundationMovieCarouselSpec(
    page: FoundationPagerPage,
    pageOffset: Float,
): FoundationMovieCarouselSpec {
    val outgoingOffset = if (page == FoundationPagerPage.Current) {
        pageOffset.coerceIn(-1f, 1f)
    } else {
        0f
    }
    val distance = abs(outgoingOffset)
    return FoundationMovieCarouselSpec(
        translationFraction = outgoingOffset * FoundationMovieTranslationRatio,
        scale = foundationPagerLerp(1f, FoundationMovieMinScale, distance),
        alpha = foundationPagerLerp(1f, FoundationMovieMinAlpha, distance),
    )
}

/**
 * 손을 뗀 뒤 수동 드래그가 어디에 안착해야 하는지: 충분히 빠른 fling은 드래그가 얼마나
 * 멀리 이동했는지와 무관하게 turn을 확정하며, 그렇지 않으면 드래그가 viewport의 일정
 * 비율을 넘어야 turn이 확정된다; 두 임계값 중 어느 쪽도 넘지 못하면 가운데로 되돌아간다.
 * 인접 페이지가 없는 쪽으로의 turn은 두 임계값을 모두 만족해도 결코 확정되지 않는데,
 * pager가 내려앉을 곳이 없기 때문이다.
 *
 * @param dragDistancePx 수동 드래그가 얼마나 이동했는지, 픽셀 단위; 부호가 방향을 나타낸다.
 * @param velocityPxPerSecond 드래그를 놓을 때의 속도, 초당 픽셀 단위; 부호가 방향을
 *   나타낸다.
 * @param viewportExtentPx turn 축을 따른 pager의 viewport 크기, 픽셀 단위; 임계값이
 *   0이 되지 않도록 최소 1로 고정된다.
 * @param hasPreviousPage 뒤로 넘어갈 페이지가 있는지 여부.
 * @param hasNextPage 앞으로 넘어갈 페이지가 있는지 여부.
 * @return 이전 페이지에 안착하려면 `-1`, 다음 페이지면 `1`, 가운데로 되돌아가려면 `0`.
 */
internal fun foundationPagerDragTargetOffset(
    dragDistancePx: Float,
    velocityPxPerSecond: Float,
    viewportExtentPx: Float,
    hasPreviousPage: Boolean,
    hasNextPage: Boolean,
): Int {
    val extent = viewportExtentPx.coerceAtLeast(1f)
    val rawTarget = when {
        abs(velocityPxPerSecond) >= FoundationManualFlingVelocityThresholdPxPerSecond -> if (velocityPxPerSecond < 0f) 1 else -1
        abs(dragDistancePx) >= extent * FoundationManualDragDistanceThresholdRatio -> if (dragDistancePx < 0f) 1 else -1
        else -> 0
    }
    return when {
        rawTarget < 0 && !hasPreviousPage -> 0
        rawTarget > 0 && !hasNextPage -> 0
        else -> rawTarget
    }
}

/**
 * [primaryDelta] 방향으로 움직이는 수동 드래그를 pager에 넘기는 대신 소비하여 막아야
 * 하는지 — [FoundationPagerGestureTracker]에 값을 대는 제스처 추적용 `pointerInput`과 실제
 * `draggable` modifier 양쪽에서 쓰여서, 넘어갈 곳이 없는 책의 시작이나 끝 쪽으로의
 * 드래그가 pager를 첫/마지막 슬롯 너머로 끌고 갈 수 없게 한다.
 *
 * @param primaryDelta 지금까지 turn 축을 따라 이동한 드래그의 양, 픽셀 단위; 부호가
 *   방향을 나타낸다.
 * @param hasPreviousPage 뒤로 넘어갈 페이지가 있는지 여부.
 * @param hasNextPage 앞으로 넘어갈 페이지가 있는지 여부.
 * @return 드래그를 막아야 하는지 여부.
 */
internal fun foundationPagerShouldBlockDrag(
    primaryDelta: Float,
    hasPreviousPage: Boolean,
    hasNextPage: Boolean,
): Boolean = when {
    primaryDelta > 0f -> !hasPreviousPage
    primaryDelta < 0f -> !hasNextPage
    else -> false
}

/** [foundationPagerTapAction]이 결정하는, pager 위의 탭이 해야 할 일. */
internal enum class FoundationPagerTapAction { Previous, ToggleControls, Next }

/**
 * 페이지 위의 탭이 해야 할 일을, 오직 탭의 위치만으로 결정한다.
 *
 * 유닛 테스트가 가능하도록 제스처 핸들러에서 따로 떼어냈는데, 이것이 담고 있는 규칙이
 * 한때 출시된 버그였기 때문이다: 인접 페이지가 없는 edge 영역에서의 탭 — 책의 첫
 * 페이지나 마지막 페이지 — 은 예전에는 아무 일도 하지 않았다. 지금은 가운데에서의 탭과
 * 마찬가지로 컨트롤 토글로 넘어간다. 반환 타입이 null을 허용하지 않는 enum인 이유가
 * 바로 이것이다: "아무 일도 하지 않음"은 이 결정이 표현할 수 있는 상태가 아니다.
 *
 * @param primary turn 축을 따른 탭의 위치, 픽셀 단위.
 * @param extent 같은 축을 따른 pane의 크기.
 * @param hasPreviousPage 뒤로 넘어갈 페이지가 있는지 여부.
 * @param hasNextPage 앞으로 넘어갈 페이지가 있는지 여부.
 * @return 취할 동작; 결코 "아무 일도 하지 않음"이 아니다.
 */
internal fun foundationPagerTapAction(
    primary: Float,
    extent: Int,
    hasPreviousPage: Boolean,
    hasNextPage: Boolean,
): FoundationPagerTapAction = when {
    primary < extent * FoundationPreviousTapZoneRatio ->
        if (hasPreviousPage) FoundationPagerTapAction.Previous else FoundationPagerTapAction.ToggleControls
    primary > extent * FoundationNextTapZoneRatio ->
        if (hasNextPage) FoundationPagerTapAction.Next else FoundationPagerTapAction.ToggleControls
    else -> FoundationPagerTapAction.ToggleControls
}

/**
 * 이 값(초당 픽셀)을 넘는 손 뗄 때의 속도부터는 [foundationPagerDragTargetOffset]이
 * 드래그가 얼마나 이동했는지와 무관하게 수동 드래그를 turn으로 확정한다 — 손가락을 아주
 * 살짝만 움직인 빠른 플릭도 의도된 페이지 turn으로 읽힌다.
 */
private const val FoundationManualFlingVelocityThresholdPxPerSecond = 1000f

/**
 * 자격을 갖춘 fling 속도가 없을 때, [foundationPagerDragTargetOffset]이 가운데로
 * 되돌리는 대신 turn으로 확정하기까지 수동 드래그가 이동해야 하는, viewport 크기에 대한
 * 비율.
 */
private const val FoundationManualDragDistanceThresholdRatio = 0.25f

/**
 * fluid-edge와 circle-reveal 기하가 읽는 수동 제스처 상태로, **갱신 빈도에 따라 두 층으로 나뉜다.**
 *
 * 예전에는 위치와 활성 여부를 통째로 담은 하나의 `data class`를 snapshot state에 담아 포인터
 * 이벤트마다 새로 써 넣었다. 그 상태는 composition에서 읽히므로, 손가락이 움직이는 내내 — 프레임이
 * 아니라 *포인터 샘플* 빈도로, 고주사율 디지타이저에서는 프레임보다 잦게 — 세 슬롯 전부가
 * 재구성됐다. 정작 composition이 실제로 필요로 하는 값은 "지금 드래그 중인가"와 "어느 쪽으로
 * 향하는가" 둘뿐이고, 그 둘은 드래그가 시작되거나 끝나거나 방향 임계값을 넘을 때만 바뀐다.
 *
 * 그래서 터치 좌표는 snapshot state로 두되 **오직 `drawWithCache` build block 안에서만** 읽고,
 * composition이 읽는 것은 [phase] 하나뿐이며 그 값도 실제로 달라질 때만 쓴다. 어느 composition도
 * 좌표를 읽지 않으므로 포인터 이벤트는 재구성을 일으키지 않고, 그 블록들은
 * `CacheDrawModifierNode`의 관찰자를 통해 그리기 캐시만 다시 만든다.
 *
 * 좌표를 아예 snapshot에서 빼지 않은 이유가 있다: reveal 기하가 고정하는 것은 turn 축이 아니라
 * **cross axis** 위치([foundationTouchCrossAxis])다. 손가락이 turn 축과 수직으로만 움직이면 pager의
 * 오프셋은 한 비트도 변하지 않고, 문서 끝에서 [foundationPagerShouldBlockDrag]가 드래그를 막는
 * 동안에도 마찬가지다 — 그 상황에서 좌표가 관찰되지 않으면 reveal이 손가락을 따라가지 못하고 그
 * 자리에 얼어붙는다.
 */
@Stable
private class FoundationPagerGestureTracker {
    /**
     * 현재 제스처가 시작된 위치. 포인터 루프의 드래그 차단 판정이 델타를 재는 기준이라 밖에서도 읽는다.
     * [press]와 [reset]에서만 바뀌므로 snapshot으로 두어도 프레임 비용이 없고, 그래야 이 클래스의
     * `@Stable` 계약 — 모든 public 프로퍼티 변경이 snapshot으로 통지된다 — 이 실제로 성립한다.
     */
    var start: Offset by mutableStateOf(Offset.Zero)
        private set

    /**
     * 가장 최근에 알려진 터치 위치. 손을 뗀 뒤에도 유지되어, 놓인 터치도 [touchPoint]를 통해 계속
     * 보고할 지점을 갖는다 — reveal이 원점으로 되돌아가는 대신 손가락이 있던 곳에 머문다.
     */
    private var touch: Offset by mutableStateOf(Offset.Zero)

    /** 마지막 [reset] 이후 어떤 제스처든 있었는지; "한 번도 터치되지 않음"과 "터치되었다가 놓임"을 가른다. */
    private var touched: Boolean by mutableStateOf(false)

    /** 포인터가 지금 눌려 있는지 여부. fluid edge가 spring 시뮬레이션과 release 보간을 가르는 데 읽는다. */
    var pressed: Boolean by mutableStateOf(false)
        private set

    /**
     * composition이 읽는 유일한 값: 이 제스처가 활성인지와 어느 side로 향하는지. 값이 실제로 달라질
     * 때만 쓰이므로, 포인터 이벤트 자체는 재구성을 일으키지 않는다.
     */
    var phase: FoundationPagerGesturePhase by mutableStateOf(FoundationPagerGesturePhase())
        private set

    /** 새 제스처의 시작을 [position]에 기록한다. */
    fun press(position: Offset) {
        start = position
        touch = position
        touched = true
        pressed = true
        updatePhase(FoundationPagerGesturePhase(active = true, side = null))
    }

    /**
     * 진행 중인 제스처를 [position]으로 옮기고, [axis]를 따라 방향이 확정됐는지 다시 판정한다.
     *
     * @param position 새 포인터 위치; 드래그가 차단된 프레임에서는 호출자가 [start]를 그대로 넘겨
     *   위치를 고정한다.
     * @param axis 방향을 어느 축으로 읽는지.
     */
    fun move(position: Offset, axis: FoundationPagerAxis) {
        touch = position
        updatePhase(
            FoundationPagerGesturePhase(
                active = true,
                side = foundationGestureSide(axis, start, position),
            ),
        )
    }

    /**
     * 포인터가 떨어졌음을 기록한다. [touch]는 [touchPoint]가 계속 보고할 수 있도록 유지되지만,
     * [phase]는 side까지 함께 지워진다.
     *
     * side를 남기면 안 되는 이유: [foundationActivePageTurn]은 `gestureSide ?: progressSide`로
     * 폴백하므로, 손을 뗀 뒤에도 남은 side가 pager의 실제 진행 방향을 계속 덮어쓴다. 뒤로 드래그했다
     * 놓은 다음 하단 바의 "다음 페이지" 버튼으로 turn하면 — 그 터치는 이 pointerInput 밖이라 [press]가
     * 불리지 않는다 — 활성 side가 여전히 start로 읽혀, 정작 드러나야 할 next 슬롯이
     * [foundationHiddenWhenInactive]로 완전히 숨겨진 채 turn이 진행된다. 예전 구현에서 side를 내주던
     * [foundationGestureSide] 호출부가 `!active`면 무조건 null을 반환했던 것과 같은 상태로 되돌린다.
     */
    fun release() {
        if (!pressed) return
        pressed = false
        updatePhase(FoundationPagerGesturePhase())
    }

    /** 모든 것을 손대지 않은 상태로 되돌린다 — 자동 스크롤이 제스처 구동 효과를 인계받을 때 쓰인다. */
    fun reset() {
        start = Offset.Zero
        touch = Offset.Zero
        touched = false
        pressed = false
        updatePhase(FoundationPagerGesturePhase())
    }

    /**
     * fluid/circle reveal 기하가 고정해야 할 지점.
     *
     * @return 현재 또는 마지막 터치 지점, 또는 아직 한 번도 터치되지 않았으면 null.
     */
    fun touchPoint(): FoundationPagerPoint? {
        if (!touched) return null
        return FoundationPagerPoint(touch.x, touch.y)
    }

    /** [next]가 지금 값과 다를 때만 [phase]에 쓴다 — 이 가드가 포인터 이벤트를 재구성에서 떼어낸다. */
    private fun updatePhase(next: FoundationPagerGesturePhase) {
        if (phase != next) phase = next
    }
}

/**
 * [FoundationPagerGestureTracker]에서 composition이 실제로 읽는 저빈도 부분.
 *
 * @property active 수동 드래그가 현재 진행 중인지 여부.
 * @property side 드래그가 확정한 방향, 또는 아직 방향 임계값을 넘지 않았거나 드래그가 없으면 null.
 */
@Immutable
private data class FoundationPagerGesturePhase(
    val active: Boolean = false,
    val side: FoundationFluidSide? = null,
)

/**
 * [PageAnimation.FLUID_PAGER]가 쓰는, 물결치는 천 같은 edge를 모델링하는 1차원
 * spring-mass 체인: 페이지의 cross axis(`[0, 1]` 범위의 `y`)를 따라 고르게 배치된
 * [FoundationFluidPointCount]개의 점 각각이 자신의 수평 변위(`x`, `[0, 1]` 범위의 페이지
 * 너비 비율)와 속도를 가지며, 이는 [tick]이 매 프레임 적분한다. [applyTarget]은 edge가
 * 향하는 곳 — turn의 progress와 그것을 당기는 터치 지점 — 만을 기록할 뿐이며, 실제로
 * 어떤 점이든 움직이는 곳은 오직 [tick]뿐으로, [PageAnimation.FLUID_PAGER]가 활성인
 * 동안 [FoundationEffectPager] 안의 `LaunchedEffect(pageAnimation)` 루프에서 프레임마다
 * 한 번씩 호출된다. [version]이 존재하는 이유는 오직, 앞서 [points]를 캡처해 둔
 * `drawWithCache`/[Shape] 블록에 언제 다시 계산해야 하는지 알려주기 위해서다 — 점들은
 * 리스트가 교체되는 대신 제자리에서 변경되므로, 그렇지 않으면 그것들이 바뀌었다는 것을
 * Compose에 알려줄 다른 수단이 없다.
 *
 * @param pointCount edge를 이루는 점의 개수; [points]의 크기이기도 하다.
 */
internal class FoundationFluidEdge(pointCount: Int = FoundationFluidPointCount) {
    /**
     * `y = 0`부터 `y = 1`까지 페이지의 cross axis를 따라 고르게 배치된 체인의 점들로,
     * 각각 정지 상태(`x = 0`)에서 시작한다. 교체되는 대신 [tick]과 [reset]에 의해
     * 제자리에서 변경되므로, 호출자는 변경에 언제 반응해야 하는지 알려면 [version]을
     * 읽어야 한다.
     */
    val points: List<FoundationFluidPoint> = List(pointCount) { index ->
        FoundationFluidPoint(y = index.toFloat() / (pointCount - 1).toFloat())
    }

    /**
     * [tick]이나 [reset]이 [points]를 제자리에서 변경할 때마다 증가하는 변경 카운터로,
     * 이 값을 캡처해 둔 `drawWithCache`/[Shape] 블록이 [points] 리스트 참조 자체는
     * 결코 바뀌지 않는데도 다시 계산해야 함을 알 수 있게 한다. [tick]은 점이 실제로
     * 움직인 경우에만 이 값을 올리므로, edge가 이미 정지한 뒤에는 [tick]을 계속
     * 호출해도 값이 그대로다.
     */
    var version by mutableStateOf(0)
        private set

    /** [applyTarget]이 마지막으로 활성이라고 전달받은 side; 이 값이 바뀌면 [points]의 모든 점이 재설정된다. */
    private var activeSide = FoundationFluidSide.Start

    /** [applyTarget]이 마지막으로 기록한 turn progress로, [tick]이 touch-tension 목표값으로 읽는다. */
    private var progress = 0f

    /** [applyTarget]이 마지막으로 기록한 터치의 cross-axis 위치로, [tick]이 각 점의 터치 영향력에 가중치를 매기는 데 읽는다. */
    private var touchCrossAxis = 0.5f

    /** 마지막 [applyTarget] 호출 시점에 터치가 활성이었는지 여부; [tick]의 어느 분기가 실행될지를 결정한다. */
    private var touchActive = false

    /**
     * 모든 점을 정지 상태로 되돌리고 [activeSide]/[progress]/[touchCrossAxis]/[touchActive]를
     * 시작 값으로 지워서, 이전 turn에서 남은 낡은 부풀어 오름이나 속도가 다음 turn으로
     * 번져 들어갈 수 없게 한다.
     */
    fun reset() {
        activeSide = FoundationFluidSide.Start
        progress = 0f
        touchCrossAxis = 0.5f
        touchActive = false
        points.forEach { point ->
            point.x = 0f
            point.velocityX = 0f
        }
        version++
    }

    /**
     * edge가 향해야 할 곳 — 어느 side가 활성인지, turn이 얼마나 진행됐는지, 터치가
     * cross axis를 따라 어디에 있는지 — 을 기록할 뿐, 점 자체는 움직이지 않는다; 이
     * 목표를 실제로 매 프레임 뒤쫓는 것은 [tick]이다. [side]가 이전에 활성이던 것과
     * 다르면 모든 점의 위치와 속도를 재설정하는데, 한 이웃을 드러내며 쌓인 부풀어
     * 오름은 활성 side가 바뀌고 나면 의미가 없어지기 때문이다. [touchActive]가 true에서
     * false로 바뀌는 순간 속도(위치는 아니고)를 지워서, 터치가 떨어졌을 때 [tick]이
     * 전환하는 release-and-settle 보간으로 드래그의 관성이 넘어가지 않도록 한다.
     *
     * @param side edge가 어느 side(start/end)에서 전진하는지.
     * @param progress turn이 얼마나 진행됐는지, `[0, 1]` 범위; 이 범위로 고정된다.
     * @param touchCrossAxis 터치가 페이지의 cross axis를 따라 어디에 있는지, `[0, 1]`
     *   범위; 이 범위로 고정된다.
     * @param touchActive 손가락이 현재 눌려 있는지 여부; false면 [tick]을 spring
     *   시뮬레이션 대신 release-and-settle 보간으로 전환한다.
     */
    fun applyTarget(
        side: FoundationFluidSide,
        progress: Float,
        touchCrossAxis: Float,
        touchActive: Boolean = true,
    ) {
        if (side != activeSide) {
            activeSide = side
            points.forEach { point ->
                point.x = 0f
                point.velocityX = 0f
            }
        }
        if (this.touchActive && !touchActive) {
            points.forEach { point ->
                point.velocityX = 0f
            }
        }
        this.progress = progress.coerceIn(0f, 1f)
        this.touchCrossAxis = touchCrossAxis.coerceIn(0f, 1f)
        this.touchActive = touchActive
    }

    /**
     * spring-mass 체인을 한 프레임만큼 진행시킨다. 이는 explicit-Euler spring-mass
     * 시뮬레이션이지, 이 파일 다른 곳의 순수 시각적 상수들처럼 느낌으로 조정된 값들의
     * 모음이 아니다 — 이 상수들은 서로 상호작용하며, 하나를 바꾸면 나머지가 의미하는
     * 바가 달라진다.
     *
     * 터치가 활성이 아닌 동안에는, 모든 점이 그저 [progress] 쪽으로 보간될 뿐이다
     * (`point.x += (progress - point.x) * releaseFraction`, 속도는 0으로 초기화된다) —
     * 그 비율이 무엇을 뜻하는지는 아래 [FoundationFluidReleaseDamping]을 참고한다. 점이
     * 이미 [progress]에 수렴해 이 보간으로도 위치가 바뀌지 않으면 [points]는 실제로
     * 변경되지 않은 것이므로, 이 분기는 [version]을 올리지 않는다.
     * 터치가 활성인 동안에는, 대신 모든 점이 속도가 감쇠되어 적용되기 전 네 종류의
     * 힘을 받는다: `x = 0` 쪽으로 다시 당기는 edge-tension 항(가중치
     * [FoundationFluidEdgeTension]); [progress]가 [FoundationFluidCompleteThreshold]를
     * 넘고 나면 `x = 1` 쪽으로 당기는 far-edge-tension 항(가중치
     * [FoundationFluidFarEdgeTension]) — 두 가중치는 설계상 같은 크기이고 서로 반대
     * 방향으로 당기는, turn이 거의 끝나갈 때만 먼 쪽을 개입시키는 의도적으로 대칭인
     * 복원력이다; [progress] 자신 쪽으로 향하는 touch-tension 항으로,
     * [FoundationFluidTouchTension]과 `influence`(터치의 cross-axis 위치로부터
     * [FoundationFluidTouchRadius]에서 0이 되는 선형 감쇠)로 가중된다; 그리고 각
     * *이웃* 점 쪽으로 당기는 항으로, [FoundationFluidPointTension]으로 가중된다.
     * 내부의 점은 이 이웃 항을 두 번 — 양쪽에서 한 번씩 — 받으므로 실효 이웃 결합은
     * `2 × FoundationFluidPointTension` = 0.50으로, [FoundationFluidTouchTension]의
     * 0.10의 다섯 배인 반면, 끝점(인덱스 0 또는 마지막 인덱스)은 이웃 항을 하나만
     * 받는다. 이 비대칭이 바로 edge가 위아래 끝에서는 내부만큼 터치를 날카롭게
     * 따라가는 대신 프레임 안으로 평평하게 풀리는 이유다: 끌어당기는 결합이 절반뿐이라
     * 끝점은 자신의 훨씬 약한 edge tension에 지배되고, 터치의 부풀어 오름은 모서리를
     * 잡아끄는 뾰족한 형태가 아니라 고정된 지점을 중심으로 휘어지는 매끄러운 시트처럼
     * 보인다.
     *
     * [FoundationFluidPointCount]와 [FoundationFluidTouchRadius]는 독립적이지 않다:
     * `[0, 1]` 범위의 `y`에 걸쳐 25개의 점이 퍼져 있을 때 점 사이 간격은
     * `1 / (pointCount - 1)` ≈ 0.0417이므로, 0.24의 터치 반경은 터치 주변 25개 점 중
     * 대략 `2 × 0.24 / 0.0417` ≈ 12개를 아우른다. 둘 중 하나만 바꾸면 영향력 커널이
     * 앨리어싱을 일으킨다 — 반경 안의 점이 너무 적으면 부풀어 오름이 매끄럽지 않고
     * 각져 보이고, 너무 많으면 터치가 자신의 국소적인 모양을 완전히 잃는다.
     *
     * 여기서 안정성은 제안이 아니라 실질적인 제약이다. 터치 영향력이 최대일 때 내부
     * 점에 작용하는 계수를 모두 더하면 — edge tension(0.01) + 두 배가 된 point
     * tension(0.50) + touch tension(0.10) — 틱당 유효 강성이 약 0.61이 되며,
     * [frameUnits]는 최대 1.5로 고정되어 강성×시간간격의 곱을 1 미만으로
     * 유지한다(`0.61 × 1.5 ≈ 0.915`). 이 갱신 규칙을 수치적으로 돌려 보면
     * [FoundationFluidPointTension] 값이 약 0.40까지는 — 실제 적용된 0.25보다 넉넉히
     * 높은 값까지 — 수렴하고(속도가 0을 향해 감쇠), 0.50에서는 울리기 시작하며(속도가
     * 감쇠를 멈추고 대신 진동), 대략 0.55 이상부터는 완전히 발산한다(속도가 한없이
     * 커짐); 같은 수치 검증에 따르면 [frameUnits]의 상한을 올리는 것도 실제 적용된
     * tension에서 동등한 불안정화 효과를 낳는데, 이를 지배하는 것은 두 요인 중 하나가
     * 아니라 강성×시간간격의 곱이기 때문이다. 이 함수 끝에서 각 점의 위치에 적용되는
     * `coerceIn(0f, 1f)`만이 그 발산과 `NaN` 또는 화면 밖으로 벗어난 모양 사이를 막아
     * 주는 유일한 장치다 — 시스템이 일단 불안정해지면 속도 자체는 얼마든지 커질 수
     * 있으며, 고정되는 것은 그것이 움직이는 위치뿐이다.
     *
     * 이름이 비슷해 감쇠처럼 보이는 두 상수는 실제로는 서로 다른 역할을 하며, 이 둘을
     * 혼동하면 엉뚱한 손잡이를 돌리는 셈이 된다. [FoundationFluidDamping](0.90)은
     * 터치가 활성인 동안 적용되는 프레임당 *속도* 감쇠다(`velocityX *=
     * FoundationFluidDamping.pow(t)`, 진짜 곱셈 계수다; `.pow(t)`는 이를 실제
     * 시간(wall-clock) 틱 단위가 아니라 [FoundationFrameMillis] 단위로 표현해
     * 프레임레이트와 무관하게 만든다) — 그 속도 곱셈 계수는 약 9.5프레임(60 fps에서
     * 약 158 ms) 뒤에 `1/e`로 떨어진다. [FoundationFluidReleaseDamping](0.82)은 감쇠
     * 계수가 전혀 아니다: 이는 `1 - 0.82.pow(t)`로 읽히는, *이 프레임에 좁혀지는,
     * 목표까지 남은 거리의 비율*이며, 터치가 놓인 뒤 각 점을 [progress] 쪽으로 곧장
     * 보간할 때만 쓰인다. 같은 방식으로 읽으면 그 남은-거리 비율은 약 5프레임(약
     * 84 ms) 뒤에 `1/e`에 이르고 약 23프레임(약 387 ms) 뒤에 99%가 좁혀진다.
     * [FoundationFluidCompleteThreshold](0.82)는 우연히 [FoundationFluidReleaseDamping]과
     * 수치가 같지만, 둘은 서로 무관한 것을 관장한다 — 하나는 위에서 far-edge
     * tension을 켜는 progress 임계값이고, 다른 하나는 release 이후 안착을 위한
     * 프레임당 lerp 비율이다 — 그리고 그 일치는 우연일 뿐 공유되는 상수가 아니므로,
     * 하나를 바꾼다고 다른 하나가 함께 움직일 것이라 기대해서는 안 된다.
     *
     * @param frameUnits 마지막 tick 이후 [FoundationFrameMillis] 길이의 프레임 하나
     *   중 얼마가 흘렀는지; `[0.1, 1.5]` 범위로 고정되어, 긴 정지(예: 드롭된
     *   프레임)가 spring을 불안정으로 오버슈트시킬 수 없고 거의 0에 가까운 delta가
     *   이를 멈춰 세울 수 없게 한다.
     */
    fun tick(frameUnits: Float = 1f) {
        val t = frameUnits.coerceIn(0.1f, 1.5f)
        if (!touchActive) {
            val releaseFraction = 1f - FoundationFluidReleaseDamping.pow(t)
            var moved = false
            points.forEach { point ->
                point.velocityX = 0f
                val next = point.x + (progress - point.x) * releaseFraction
                if (next != point.x) {
                    point.x = next
                    moved = true
                }
            }
            if (moved) version++
            return
        }

        val dampingT = FoundationFluidDamping.pow(t)
        val farEdgeTension = if (progress > FoundationFluidCompleteThreshold) {
            FoundationFluidFarEdgeTension
        } else {
            0f
        }

        points.forEachIndexed { index, point ->
            point.velocityX -= point.x * FoundationFluidEdgeTension * t
            point.velocityX += (1f - point.x) * farEdgeTension * t

            val influence = (1f - abs(point.y - touchCrossAxis) / FoundationFluidTouchRadius)
                .coerceIn(0f, 1f)
            point.velocityX += (progress - point.x) * FoundationFluidTouchTension * influence * t

            if (index > 0) {
                point.velocityX += (points[index - 1].x - point.x) * FoundationFluidPointTension * t
            }
            if (index < points.lastIndex) {
                point.velocityX += (points[index + 1].x - point.x) * FoundationFluidPointTension * t
            }
            point.velocityX *= dampingT
        }

        points.forEach { point ->
            point.x = (point.x + point.velocityX * t).coerceIn(0f, 1f)
        }
        version++
    }
}

/**
 * [FoundationFluidEdge]의 spring-mass 체인 안 질점 하나. `x`와 `velocityX`가 불변이 아니라
 * `var`인 이유는 [FoundationFluidEdge.tick]/[FoundationFluidEdge.reset]이 매 프레임 새
 * `List`를 할당하는 대신 체인을 제자리에서 변경할 수 있게 하기 위해서다.
 *
 * @property x 점의 수평 변위로, `[0, 1]` 범위의 페이지 너비 비율; `0`은 프레임에 평평하게
 *   붙어 쉬는 상태를, `1`은 완전히 전진한 상태를 뜻한다.
 * @property y 페이지의 cross axis를 따른 점의 고정 위치로, `[0, 1]` 범위의 비율; 생성된
 *   뒤로는 결코 변경되지 않는다.
 * @property velocityX [FoundationFluidEdge.tick] 프레임 단위당, [x]의 현재 변화율.
 */
internal data class FoundationFluidPoint(
    var x: Float = 0f,
    val y: Float = 0f,
    var velocityX: Float = 0f,
)

/**
 * [foundationActivePageTurn]이 판단한, 어느 이웃 쪽 turn이 현재 활성인지와 그것이 얼마나
 * 진행됐는지.
 *
 * @property side 활성 turn이 어느 쪽(start/end)에서 드러나고 있는지.
 * @property progress 활성 turn이 얼마나 진행됐는지, `[0, 1]` 범위.
 */
internal data class FoundationActivePageTurn(
    val side: FoundationFluidSide,
    val progress: Float,
)

/**
 * pager 슬롯 자신의 로컬 좌표 공간(픽셀 단위, [FoundationPagerAxis]에 의해 turn 축 기준으로
 * canonical화됨) 안의 2차원 점으로, fluid/circle-reveal 기하 전반에서 Compose의 [Offset]
 * 대신 쓰여 그 위의 계산이 `Density`/`LayoutDirection` receiver를 필요로 하지 않게 한다.
 *
 * @property x 점의 수평 성분, 픽셀 단위(canonical화된 뒤에는 pager의 주 축을 따른 값).
 * @property y 점의 수직 성분, 픽셀 단위(canonical화된 뒤에는 pager의 cross axis를 따른 값).
 */
internal data class FoundationPagerPoint(
    val x: Float,
    val y: Float,
) {
    /** 성분별 합으로, 같은 좌표 공간의 두 오프셋을 합칠 때 쓰인다. */
    operator fun plus(other: FoundationPagerPoint): FoundationPagerPoint = FoundationPagerPoint(x + other.x, y + other.y)

    /** 성분별 차로, 한 점을 다른 점 기준으로 측정할 때 쓰인다. */
    operator fun minus(other: FoundationPagerPoint): FoundationPagerPoint = FoundationPagerPoint(x - other.x, y - other.y)

    /** [value]만큼의 균일한 배율로, 한 점을 다른 점 쪽으로 보간하거나 스케일할 때 쓰인다. */
    operator fun times(value: Float): FoundationPagerPoint = FoundationPagerPoint(x * value, y * value)
}

/**
 * pager 슬롯 자신의 크기로, [FoundationPagerAxis]에 의해 turn 축 기준으로 canonical화되며,
 * Compose의 [Size]를 본뜨되 draw scope 밖에서도 쓸 수 있다.
 *
 * @property width 슬롯 자신의 가로축을 따른 크기, 픽셀 단위.
 * @property height 슬롯 자신의 세로축을 따른 크기, 픽셀 단위.
 */
internal data class FoundationPagerSize(
    val width: Float,
    val height: Float,
)

/**
 * pager가 가로축과 세로축 중 어느 쪽으로 도는지. 이 파일의 기하 계산 대부분은 두 축
 * 모두에 대해 모든 모양/polygon 계산을 중복하는 대신, "canonical"한 가로-turn 좌표
 * 공간에 한 번만 작성된 뒤 이 enum의 `toCanonical`/`fromCanonical` 헬퍼를 통해 세로
 * pager를 위해 다시 맞바꿔진다.
 */
internal enum class FoundationPagerAxis {
    Horizontal,
    Vertical,
    ;

    /**
     * [point]를 이 축의 실제 좌표 공간에서, fluid-edge/polygon 기하가 작성된 canonical한
     * 가로-turn 공간으로 매핑한다 — [Horizontal]에서는 아무 일도 하지 않고, [Vertical]에서는
     * x/y를 맞바꾼다. 자기 자신의 역함수이므로 역방향 매핑으로도 쓰인다.
     *
     * @param point 이 축의 실제 좌표 공간 안의 점.
     * @return canonical한 가로-turn 공간 안의 같은 점.
     */
    fun toCanonical(point: FoundationPagerPoint): FoundationPagerPoint = when (this) {
        Horizontal -> point
        Vertical -> FoundationPagerPoint(point.y, point.x)
    }

    /**
     * [point]를 canonical한 가로-turn 공간에서 이 축의 실제 좌표 공간으로 다시 매핑한다.
     * x/y 맞바꿈은 자기 자신의 역함수이므로 [toCanonical]에 위임한다.
     *
     * @param point canonical한 가로-turn 공간 안의 점.
     * @return 이 축의 실제 좌표 공간 안의 같은 점.
     */
    fun fromCanonical(point: FoundationPagerPoint): FoundationPagerPoint = toCanonical(point)

    /**
     * [toCanonical]의 [FoundationPagerSize]판: [Vertical]에서 width/height를 맞바꿔서,
     * 크기에 의존하는 기하를 canonical한 가로-turn 크기 기준으로 한 번만 작성할 수 있게
     * 한다.
     *
     * @param size 이 축의 실제 방향 기준 슬롯의 크기.
     * @return canonical한 가로-turn 방향 기준의 같은 크기.
     */
    fun toCanonicalSize(size: FoundationPagerSize): FoundationPagerSize = when (this) {
        Horizontal -> size
        Vertical -> FoundationPagerSize(width = size.height, height = size.width)
    }

    /**
     * Compose [Offset]을 canonical한 가로-turn 공간에서 이 축의 실제 좌표 공간으로 다시
     * 매핑한다 — [fromCanonical]의 [Offset]판으로, 호출자가 [FoundationPagerPoint]가 아니라
     * Compose 자신의 offset 타입으로 이미 작업하고 있을 때 쓰인다.
     *
     * @param point canonical한 가로-turn 공간 안의 offset.
     * @return 이 축의 실제 좌표 공간 안의 같은 offset.
     */
    fun fromCanonical(point: Offset): Offset = when (this) {
        Horizontal -> point
        Vertical -> Offset(point.y, point.x)
    }

    /**
     * [point]의, 이 축의 주(turn 방향) 성분: [Horizontal]이면 `x`, [Vertical]이면 `y`.
     * 호출자가 자신만의 축-의존 분기를 두지 않고도 turn 방향을 따라 제스처 델타와 드래그
     * 거리를 읽는 데 쓰인다.
     *
     * @param point 읽을 offset.
     * @return 이 축의 turn 방향을 따른 [point]의 성분.
     */
    fun primary(point: Offset): Float = when (this) {
        Horizontal -> point.x
        Vertical -> point.y
    }
}

/**
 * effect(reveal, shadow, fold)가 turn 축의 어느 끝에 고정되어 있거나 그로부터 전진하는지:
 * [Start]는 이전 페이지 쪽, [End]는 다음 페이지 쪽.
 */
internal enum class FoundationFluidSide {
    Start,
    End,
}

/**
 * 원시 Foundation pager 인덱스와 무관한 pager 슬롯의 정체성으로, reveal/shadow/z-index
 * 목적으로 그것이 놓이는 [FoundationFluidSide]와 짝지어진다. [Current]가 중립값이 아니라
 * [FoundationFluidSide.End]로 지정된 이유는, 이 파일의 side별 로직 대부분
 * (`page.side == activeSide` 검사)이 "활성 side의 이웃"과 "그 외 전부"만 구분하면 되고,
 * [Current]는 reveal의 대상으로서 [activeSide] 비교와 결코 일치하지 않으며 그 아래 놓인
 * 페이지로서만 일치하기 때문이다.
 *
 * @property pagerPage 이 슬롯에 대응하는 원시 Foundation pager 인덱스
 *   ([FoundationPreviousPage]/[FoundationCenterPage]/[FoundationNextPage]).
 * @property side reveal/shadow/z-index 비교를 위해 이 슬롯이 놓이는 side.
 */
internal enum class FoundationPagerPage(
    val pagerPage: Int,
    val side: FoundationFluidSide,
) {
    Previous(FoundationPreviousPage, FoundationFluidSide.Start),
    Current(FoundationCenterPage, FoundationFluidSide.End),
    Next(FoundationNextPage, FoundationFluidSide.End),
    ;

    /** 원시 pager 슬롯 인덱스로부터의 역조회를 담아, 그 매핑이 자신이 만들어내는 타입과 함께 살아 있게 한다. */
    companion object {
        /**
         * 원시 Foundation pager 슬롯 인덱스에 대응하는 [FoundationPagerPage].
         *
         * @param page 원시 pager 인덱스로, [FoundationPreviousPage],
         *   [FoundationCenterPage], 또는 [FoundationNextPage] 중 하나로 예상된다.
         * @return 대응하는 [FoundationPagerPage]; [FoundationPreviousPage]나
         *   [FoundationNextPage]가 아닌 인덱스는 모두 [Current]로 취급된다.
         */
        fun fromPagerPage(page: Int): FoundationPagerPage = when (page) {
            FoundationPreviousPage -> Previous
            FoundationNextPage -> Next
            else -> Current
        }
    }
}

/**
 * [page]로부터 이 pager의 실시간 스크롤 오프셋으로, 정수 페이지 수에 소수 progress를
 * 더한 값이다: [page]에 정확히 안착해 있으면 `0`, 더 높은 인덱스 쪽으로 멀어지며 스크롤
 * 중이면 양수, 더 낮은 인덱스 쪽이면 음수다. 이 파일의 슬롯별 transform 각각이 주어진
 * 슬롯이 현재 turn 도중 얼마나 진행됐는지 알기 위해 읽는 기본 구성 요소다.
 *
 * @receiver 실시간 스크롤 상태를 읽을 대상 pager.
 * @param page 오프셋을 측정할 기준 pager 슬롯 인덱스.
 * @return [page]로부터의 부호 있는 오프셋, 페이지 단위.
 */
@OptIn(ExperimentalFoundationApi::class)
fun PagerState.foundationOffsetForPage(page: Int): Float =
    (currentPage - page) + currentPageOffsetFraction

/**
 * [page] 쪽으로의 turn이 현재 얼마나 안착했는지를, 오프셋이 어느 방향으로 진행되든
 * `0`(손대지 않음)에서 `1`([page]에 정확히 도달)로 오르는 값으로 나타낸다 —
 * reveal/shadow 계산 대부분이 원시 부호 있는 오프셋 대신 원하는, 부호를 신경 쓰지 않는
 * [foundationOffsetForPage]의 대응물이다.
 *
 * @receiver 실시간 스크롤 상태를 읽을 대상 pager.
 * @param page progress를 측정할 기준 pager 슬롯 인덱스.
 * @return [page] 쪽으로의 turn progress, `[0, 1]` 범위.
 */
@OptIn(ExperimentalFoundationApi::class)
fun PagerState.foundationAdjacentProgress(page: Int): Float =
    (1f - abs(foundationOffsetForPage(page))).coerceIn(0f, 1f)

/**
 * [start]에서 [stop]까지의 선형 보간으로, 범위를 벗어난 progress(예: overscroll)를 넘긴
 * 호출자가 [stop]을 넘어 오버슈트할 수 없도록 [progress]를 먼저 `[0, 1]`로 고정한다.
 *
 * @param start `progress == 0`일 때의 값.
 * @param stop `progress == 1`일 때의 값.
 * @param progress 보간 비율; 사용 전에 `[0, 1]`로 고정된다.
 * @return [start]와 [stop] 사이의 보간된 값.
 */
internal fun foundationPagerLerp(start: Float, stop: Float, progress: Float): Float {
    val fraction = progress.coerceIn(0f, 1f)
    return start + (stop - start) * fraction
}

/**
 * [touch]가 turn 방향에 수직인 축을 따라 어디에 있는지를, 그 cross-axis 크기에 대한
 * 비율로 나타낸다 — fluid edge의 touch-tension 항과 circle-reveal의 원점이 둘 다
 * 고정하는 좌표로, 페이지는 [axis]를 따라 넘어가지만 터치는 다른 차원 어디에든 놓일 수
 * 있기 때문이다.
 *
 * @param axis turn이 가로축과 세로축 중 어느 쪽으로 진행되는지; [touch]의 성분 중 어느
 *   것이 cross-axis 성분인지를 결정한다.
 * @param size 원시 터치 좌표를 비율로 정규화하는 데 쓰이는 슬롯의 크기.
 * @param touch 현재 터치 지점, 또는 없으면 null.
 * @return 터치의 cross-axis 위치, `[0, 1]` 범위; [touch]가 null이거나 관련
 *   [size] 크기가 0이면 `0.5`(가운데).
 */
internal fun foundationTouchCrossAxis(
    axis: FoundationPagerAxis,
    size: FoundationPagerSize,
    touch: FoundationPagerPoint?,
): Float {
    if (touch == null) return 0.5f
    val cross = when (axis) {
        FoundationPagerAxis.Horizontal -> touch.y
        FoundationPagerAxis.Vertical -> touch.x
    }
    val extent = when (axis) {
        FoundationPagerAxis.Horizontal -> size.height
        FoundationPagerAxis.Vertical -> size.width
    }
    if (extent <= 0f) return 0.5f
    return (cross / extent).coerceIn(0f, 1f)
}

/**
 * [foundationCircleRevealSpec]이 계산하는, [PageAnimation.CIRCLE_REVEAL]에서 커지는
 * 원의 기하.
 *
 * @property origin 원의 고정된 시작점으로, 터치의 cross-axis 위치에서 페이지의 가까운
 *   edge 위 — `progress == 0`일 때 reveal이 놓일 자리다.
 * @property center 원의 현재 중심으로, [progress]가 진행됨에 따라 [origin]에서 페이지
 *   중심 쪽으로 옮겨간다.
 * @property radius 원의 현재 반지름, 픽셀 단위.
 */
internal data class FoundationCircleRevealSpec(
    val origin: FoundationPagerPoint,
    val center: FoundationPagerPoint,
    val radius: Float,
)

/**
 * [foundationCircleRevealShadowSpec]이 계산하는, [PageAnimation.CIRCLE_REVEAL]에서 커지는
 * 원의 edge 바로 안쪽에 그려지는 shadow ring.
 *
 * @property center shadow ring의 중심으로, reveal 원 자신의 중심과 같다.
 * @property radius shadow ring의 바깥 반지름으로, reveal 원 자신의 반지름과 같다.
 * @property innerRadius shadow ring의 그라디언트가 옅어지기 시작하는 지점으로, [radius]
 *   안쪽.
 * @property alpha shadow ring의 바깥쪽 edge에서의 최대 alpha.
 */
internal data class FoundationCircleRevealShadowSpec(
    val center: FoundationPagerPoint,
    val radius: Float,
    val innerRadius: Float,
    val alpha: Float,
)

/**
 * [PageAnimation.CIRCLE_REVEAL]에서 커지는 원의 기하: 원은 터치의 cross-axis 위치에서
 * 페이지의 진행 방향 edge 위의 한 점인 [origin]에서 시작하며, [progress]가 1을 향해
 * 진행됨에 따라 그 중심은 페이지 자신의 중심 쪽으로 옮겨간다 — 그래서 reveal은 평범한
 * 방사형 wipe처럼 고정된 모서리에서 커지는 대신, 커지는 동시에 중심도 옮겨간다.
 *
 * @param size 슬롯의 크기.
 * @param axis turn이 가로축과 세로축 중 어느 쪽으로 진행되는지; [origin]이 어느 edge에
 *   놓이는지를 결정한다.
 * @param side reveal이 어느 side(start/end)에서 전진하는지; [axis]와 함께 [origin]이
 *   어느 edge에 놓이는지를 결정하는 또 다른 요인.
 * @param progress turn이 얼마나 진행됐는지, `[0, 1]` 범위; 이 범위로 고정된다.
 * @param touchCrossAxis 터치가 페이지의 cross axis를 따라 어디에 있는지, `[0, 1]` 범위;
 *   이 범위로 고정되며, [origin]을 edge를 따라 위치시킨다.
 * @return 원의 origin, 현재 center, 현재 radius.
 */
internal fun foundationCircleRevealSpec(
    size: FoundationPagerSize,
    axis: FoundationPagerAxis,
    side: FoundationFluidSide,
    progress: Float,
    touchCrossAxis: Float,
): FoundationCircleRevealSpec {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val clampedTouch = touchCrossAxis.coerceIn(0f, 1f)
    val origin = when (axis) {
        FoundationPagerAxis.Horizontal -> FoundationPagerPoint(
            x = if (side == FoundationFluidSide.End) size.width else 0f,
            y = size.height * clampedTouch,
        )
        FoundationPagerAxis.Vertical -> FoundationPagerPoint(
            x = size.width * clampedTouch,
            y = if (side == FoundationFluidSide.End) size.height else 0f,
        )
    }
    val center = FoundationPagerPoint(
        x = size.width / 2f - ((size.width / 2f - origin.x) * (1f - clampedProgress)),
        y = size.height / 2f - ((size.height / 2f - origin.y) * (1f - clampedProgress)),
    )
    return FoundationCircleRevealSpec(
        origin = origin,
        center = center,
        radius = hypot(size.width, size.height) * 0.5f * clampedProgress,
    )
}

/**
 * [PageAnimation.CIRCLE_REVEAL]의 shadow ring으로, [foundationCircleRevealSpec]이 계산하는
 * 것과 같은 원에서 파생된다: 원의 edge 바로 안쪽에 있는, 너비
 * [FoundationCircleRevealShadowWidth] 픽셀짜리 고리이며, alpha는 이 파일의 다른 reveal
 * shadow들과 같은 half-sine build/fade 곡선을 따른다.
 *
 * @param size 슬롯의 크기.
 * @param axis turn이 가로축과 세로축 중 어느 쪽으로 진행되는지.
 * @param side reveal이 어느 side(start/end)에서 전진하는지.
 * @param progress turn이 얼마나 진행됐는지, `[0, 1]` 범위.
 * @param touchCrossAxis 터치가 페이지의 cross axis를 따라 어디에 있는지, `[0, 1]` 범위.
 * @return shadow ring의 center, radius, inner radius, alpha.
 */
internal fun foundationCircleRevealShadowSpec(
    size: FoundationPagerSize,
    axis: FoundationPagerAxis,
    side: FoundationFluidSide,
    progress: Float,
    touchCrossAxis: Float,
): FoundationCircleRevealShadowSpec {
    val reveal = foundationCircleRevealSpec(
        size = size,
        axis = axis,
        side = side,
        progress = progress,
        touchCrossAxis = touchCrossAxis,
    )
    val alpha = FoundationCircleRevealShadowAlpha * sin(progress.coerceIn(0f, 1f) * PI.toFloat())
    return FoundationCircleRevealShadowSpec(
        center = reveal.center,
        radius = reveal.radius,
        innerRadius = (reveal.radius - FoundationCircleRevealShadowWidth).coerceAtLeast(0f),
        alpha = alpha,
    )
}

/**
 * [foundationCircleRevealSpec]의 원으로부터 만들어지는, [PageAnimation.CIRCLE_REVEAL]의
 * 클리핑 경로 — [Modifier.foundationCircleRevealClip]이 호출 지점에서 타원 생성
 * 보일러플레이트를 반복하지 않고 평범한 [Path]를 `clipPath`에 넘길 수 있도록 존재하는
 * 얇은 래퍼다.
 *
 * @param size 슬롯의 크기.
 * @param axis turn이 가로축과 세로축 중 어느 쪽으로 진행되는지.
 * @param side reveal이 어느 side(start/end)에서 전진하는지.
 * @param progress turn이 얼마나 진행됐는지, `[0, 1]` 범위.
 * @param touchCrossAxis 터치가 페이지의 cross axis를 따라 어디에 있는지, `[0, 1]` 범위.
 * @return reveal 원의 현재 기하와 일치하는 타원형 [Path].
 */
internal fun buildFoundationCircleRevealPath(
    size: FoundationPagerSize,
    axis: FoundationPagerAxis,
    side: FoundationFluidSide,
    progress: Float,
    touchCrossAxis: Float,
): Path {
    val spec = foundationCircleRevealSpec(
        size = size,
        axis = axis,
        side = side,
        progress = progress,
        touchCrossAxis = touchCrossAxis,
    )
    return Path().apply {
        addOval(Rect(center = Offset(spec.center.x, spec.center.y), radius = spec.radius))
    }
}

/**
 * pager의 실시간, spring 애니메이션되는 edge를 읽는 대신, [progress]/[touchCrossAxis]로
 * 곧장 구동되어 기본 프레임 단위로 8틱만큼 안착시킨, 새로 만들어 한 번 쓰고 버릴
 * [FoundationFluidEdge]로부터 fluid-edge reveal polygon을 만든다. 실제 제스처/애니메이션
 * 루프를 연결하지 않고도, 알려진 progress에서의 polygon을 검증하는 테스트처럼 한 세트의
 * 입력에 대한 fluid 모양의 정지 상태 기하를 원하는 호출자를 위한 것이다; 실제 서비스용
 * 그리기는 대신 이미 살아 있는 [FoundationFluidEdge]를 받는, 아래의 형제 오버로드를
 * 거치며, [FoundationFluidEdge.tick]이 실제로 프레임마다 애니메이션하는 공유 edge를
 * 사용한다.
 *
 * @param size 슬롯의 크기.
 * @param axis turn이 가로축과 세로축 중 어느 쪽으로 진행되는지.
 * @param side fluid edge가 어느 side(start/end)에서 전진하는지.
 * @param progress turn이 얼마나 진행됐는지, `[0, 1]` 범위; edge가 쉬어야 할 목표값이다.
 * @param touchCrossAxis 터치가 페이지의 cross axis를 따라 어디에 있는지, `[0, 1]` 범위.
 * @param pointCount 한 번 쓰고 버릴 edge를 몇 개의 점으로 만들지.
 * @return [progress]에 안착했을 때 fluid edge의 polygon 윤곽.
 */
internal fun buildFoundationFluidPolygon(
    size: FoundationPagerSize,
    axis: FoundationPagerAxis,
    side: FoundationFluidSide,
    progress: Float,
    touchCrossAxis: Float,
    pointCount: Int = FoundationFluidPointCount,
): List<FoundationPagerPoint> {
    val edge = FoundationFluidEdge(pointCount)
    edge.applyTarget(side, progress, touchCrossAxis)
    repeat(8) { edge.tick() }
    return buildFoundationFluidPolygon(
        size = size,
        axis = axis,
        side = side,
        edge = edge,
    )
}

/**
 * [edge]의 현재, 이미 애니메이션된 점 위치들로부터 fluid-edge reveal polygon을 만든다:
 * ([side]에 따른) 먼 쪽 프레임 경계를, edge 자신의 점들이 가까운 쪽을 따라 내려오며
 * 닫아내는 형태로, [axis]로 다시 맞바꿔진 canonical한 가로-turn 공간에서 만든다. 이것이
 * 실제 서비스용 그리기가 쓰는 오버로드로 — 클리핑 모양을 위해서는
 * [buildFoundationFluidPath]를 통해, shadow polygon을 위해서는
 * [buildFoundationFluidShadowPolygon]에서 직접 — 새 edge를 안착시키는 대신 [edge]가 지금
 * 어떤 상태에 있든 그것을 그대로 읽는다.
 *
 * @param size 슬롯의 크기.
 * @param axis turn이 가로축과 세로축 중 어느 쪽으로 진행되는지.
 * @param side fluid edge가 어느 side(start/end)에서 전진하는지; 반대쪽의 프레임 모서리가
 *   polygon을 닫아낸다.
 * @param edge 현재 점 위치를 따라 그릴 대상 fluid edge.
 * @return [edge]의 현재 모양을 나타내는 polygon 윤곽.
 */
internal fun buildFoundationFluidPolygon(
    size: FoundationPagerSize,
    axis: FoundationPagerAxis,
    side: FoundationFluidSide,
    edge: FoundationFluidEdge,
): List<FoundationPagerPoint> {
    val canonicalSize = axis.toCanonicalSize(size)
    val edgePoints = edge.canonicalEdgePoints(canonicalSize, side)
    val canonicalPoints = when (side) {
        FoundationFluidSide.Start -> buildList {
            add(FoundationPagerPoint(0f, 0f))
            addAll(edgePoints)
            add(FoundationPagerPoint(0f, canonicalSize.height))
        }
        FoundationFluidSide.End -> buildList {
            add(FoundationPagerPoint(canonicalSize.width, 0f))
            addAll(edgePoints)
            add(FoundationPagerPoint(canonicalSize.width, canonicalSize.height))
        }
    }
    return canonicalPoints.fromCanonical(axis)
}

/**
 * fluid edge의 현재 모양 뒤로 이어지는, 너비 [width] 픽셀짜리 띠로,
 * [foundationFluidShadow]의 cast와 contact shadow(각각 다른 [width]로 shadow마다 한 번씩
 * 호출됨)를 위한 윤곽으로 쓰인다. edge 자신의 점들과, 그것을 edge의 원점 쪽으로
 * [width]만큼 옮긴 두 번째 사본을 취한 뒤, 열린 띠가 아니라 하나의 닫힌 고리로 두 점
 * 집합을 이어 붙여서, 결과가 단순한 곡선이 아니라 채울 수 있는 polygon이 되게 한다.
 *
 * @param size 슬롯의 크기.
 * @param axis turn이 가로축과 세로축 중 어느 쪽으로 진행되는지.
 * @param side fluid edge가 어느 side(start/end)에서 전진하는지.
 * @param edge 현재 점 위치를 따라 그릴 대상 fluid edge.
 * @param width shadow 띠의 너비, 픽셀 단위.
 * @return shadow 띠의 닫힌 polygon 윤곽.
 */
internal fun buildFoundationFluidShadowPolygon(
    size: FoundationPagerSize,
    axis: FoundationPagerAxis,
    side: FoundationFluidSide,
    edge: FoundationFluidEdge,
    width: Float,
): List<FoundationPagerPoint> {
    val canonicalSize = axis.toCanonicalSize(size)
    return foundationFluidShadowBand(
        edgePoints = edge.canonicalEdgePoints(canonicalSize, side),
        canonicalSize = canonicalSize,
        side = side,
        width = width,
    ).fromCanonical(axis)
}

/**
 * [edge]의 점들을 canonical한 가로-turn 좌표계의 픽셀 위치로 옮긴 것으로, reveal polygon과
 * shadow 띠가 모두 같은 첫 단계를 밟는다. 두 빌더가 각자 이 변환을 되풀이하면 cast/contact
 * shadow 한 쌍만으로도 프레임마다 점 변환이 네 번 돌아간다.
 *
 * @receiver 현재 점 위치를 읽을 대상 fluid edge.
 * @param canonicalSize canonical한 가로-turn 방향 기준 슬롯의 크기.
 * @param side fluid edge가 어느 side(start/end)에서 전진하는지.
 * @return canonical 좌표계 기준 edge의 점들.
 */
private fun FoundationFluidEdge.canonicalEdgePoints(
    canonicalSize: FoundationPagerSize,
    side: FoundationFluidSide,
): List<FoundationPagerPoint> = points.map { point ->
    val x = (canonicalSize.width * point.x).coerceIn(0f, canonicalSize.width)
    val y = canonicalSize.height * point.y
    when (side) {
        FoundationFluidSide.Start -> FoundationPagerPoint(x, y)
        FoundationFluidSide.End -> FoundationPagerPoint(canonicalSize.width - x, y)
    }
}

/**
 * [edgePoints]와, 그것을 edge의 원점 쪽으로 [width]만큼 옮긴 사본을 하나의 닫힌 고리로 이어
 * 붙인 shadow 띠. canonical 좌표계 그대로 반환하므로, 같은 [edgePoints]로 서로 다른 [width]의
 * 띠를 여러 개 만들 때 점 변환을 한 번만 치른다.
 *
 * @param edgePoints canonical 좌표계 기준 edge의 점들.
 * @param canonicalSize canonical한 가로-turn 방향 기준 슬롯의 크기.
 * @param side fluid edge가 어느 side(start/end)에서 전진하는지.
 * @param width shadow 띠의 너비, 픽셀 단위.
 * @return canonical 좌표계 기준, 띠의 닫힌 polygon 윤곽.
 */
private fun foundationFluidShadowBand(
    edgePoints: List<FoundationPagerPoint>,
    canonicalSize: FoundationPagerSize,
    side: FoundationFluidSide,
    width: Float,
): List<FoundationPagerPoint> {
    val shadowPoints = edgePoints.map { point ->
        val shadowX = when (side) {
            FoundationFluidSide.Start -> point.x + width
            FoundationFluidSide.End -> point.x - width
        }.coerceIn(0f, canonicalSize.width)
        FoundationPagerPoint(shadowX, point.y)
    }
    return edgePoints + shadowPoints.asReversed()
}

/**
 * canonical한 가로-turn 공간의 이 점들을 [axis]의 실제 방향으로 되돌린다. [Horizontal]에서는
 * 맞바꿈이 항등이므로 리스트와 점을 전부 새로 만드는 대신 receiver를 그대로 돌려준다 — 이
 * 변환은 fluid 경로에서 프레임마다 점 수십 개 단위로 돌아간다.
 *
 * @receiver canonical한 가로-turn 공간 안 polygon의 점들.
 * @param axis 되돌릴 대상 축.
 * @return [axis]의 실제 방향 기준 점들.
 */
private fun List<FoundationPagerPoint>.fromCanonical(
    axis: FoundationPagerAxis,
): List<FoundationPagerPoint> = when (axis) {
    FoundationPagerAxis.Horizontal -> this
    FoundationPagerAxis.Vertical -> map(axis::fromCanonical)
}


/**
 * 이 점 리스트를 닫힌 [Path]로 그린다: 첫 점으로 `moveTo`한 뒤 그 다음 모든 점으로
 * `lineTo`하고, 다시 시작점으로 `close()`한다 — 이미 실제 화면 좌표계에 있든 아직
 * canonical한 가로-turn 공간에 있든, 이 파일에서 만들어지는 모든 fluid-edge polygon이
 * 공유하는 마지막 단계다.
 *
 * @receiver 이어붙일 순서대로 나열된 polygon의 점들.
 * @return receiver를 따라 그리는 닫힌 [Path].
 */
private fun List<FoundationPagerPoint>.toPath(): Path = Path().apply {
    forEachIndexed { index, point ->
        if (index == 0) {
            moveTo(point.x, point.y)
        } else {
            lineTo(point.x, point.y)
        }
    }
    close()
}

/**
 * [FoundationPagerPage.Previous] 슬롯의 원시 Foundation pager 인덱스. 이 상수와 다음 세
 * 상수는 전혀 "조정된" 값이 아니다: 각각은 시스템 안의 다른 무언가에 의해 — 여기서는
 * pager의 고정된 3-슬롯(이전/현재/다음) 레이아웃에 의해 — 고정되어 있으므로, 그것이
 * 파생된 근거를 바꾸지 않은 채 하나만 바꾸면 단순히 달라지는 것이 아니라 틀리게 된다.
 */
private const val FoundationPreviousPage = 0

/** [FoundationPagerPage.Current] 슬롯의 원시 Foundation pager 인덱스로, pager가 turn 사이에 항상 다시 안착하는 슬롯이기도 하다. */
private const val FoundationCenterPage = 1

/** [FoundationPagerPage.Next] 슬롯의 원시 Foundation pager 인덱스 — pager의 고정된 3-슬롯 레이아웃. */
private const val FoundationNextPage = 2

/** pager의 고정된 슬롯 수(이전/현재/다음); 항상 3이며, [pageCount][FoundationEffectPager]의 함수가 아니다. */
private const val FoundationPagerPageCount = 3

/**
 * previous-page 영역과 가운데 toggle-controls 영역 사이의 탭 경계로, turn 축을 따른
 * pane 크기에 대한 비율이다. [FoundationNextTapZoneRatio]와 짝지어져 대칭적인 25/50/25
 * 분할을 이룬다 — previous/next-page 영역이 같은 너비를 가지고 그 정확히 사이에 toggle
 * 영역이 놓이도록 둘의 합은 `1`이어야 한다; `foundationPagerTapAction` 참고.
 */
private const val FoundationPreviousTapZoneRatio = 0.25f

/**
 * 가운데 toggle-controls 영역과 next-page 영역 사이의 탭 경계로, turn 축을 따른 pane
 * 크기에 대한 비율이다. [FoundationPreviousTapZoneRatio]와 짝지어져 같은 대칭적인
 * 25/50/25 분할을 이룬다; `foundationPagerTapAction` 참고.
 */
private const val FoundationNextTapZoneRatio = 0.75f

/**
 * 안착 애니메이션(이웃으로의 프로그래밍적 이동, 또는 turn을 확정한 뒤 손을 뗀 수동
 * 드래그)이 걸리는 시간, 밀리초 단위. 위아래의 상수들과 달리 이는 파생값이나 물리량이
 * 아니라, 느낌을 위해 고른 애니메이션 지속 시간이라는 진짜 조정값이다.
 */
private const val FoundationPagerSettleMillis = 220

/**
 * [foundationGestureSide]에서 수동 드래그가 방향을 확정하기까지 turn 축을 따라 필요한
 * 최소 이동량, 픽셀 단위. 드래그를 방향성 있는 것으로 취급하기까지 얼마나 여유를 둘지에
 * 대한 조정값이지, 파생값이 아니다.
 */
private const val FoundationGestureDirectionThresholdPx = 1f

/**
 * [FoundationFluidEdge]의 체인을 기본값으로 이루는 점의 개수. 이 상수와 다음 여덟 개의
 * 상수는 fluid-edge spring 물리량이다: [FoundationFluidEdge.tick]의 KDoc이 이들을 —
 * 서로 어떻게 상호작용하는지, 안정성 한계, 그리고 이름이 비슷한 두 감쇠 상수의 서로
 * 다른 역할까지 — 하나의 시스템으로 함께 문서화하는데, 이 중 하나를 바꾸면 나머지가
 * 의미하는 바가 달라지기 때문이다. 아래 각 상수의 한 줄짜리 설명은 그 상수 자신의
 * 역할만 다시 밝힐 뿐이다; 그 파생 과정과 그 뒤의 수치 검증은 [FoundationFluidEdge.tick]을
 * 참고한다. 이 값은 [FoundationFluidTouchRadius]와 상호작용한다: 점 사이 간격은
 * `1 / (pointCount - 1)`이며, 이는 터치 반경이 실제로 몇 개의 점을 아우르는지를
 * 결정한다.
 */
private const val FoundationFluidPointCount = 25

/** [FoundationFluidEdge.tick]의 touch-tension 항이, 체인의 cross-axis 크기에 대한 비율로, 영향력이 0으로 떨어지기 전까지 얼마나 멀리 미치는지. */
private const val FoundationFluidTouchRadius = 0.24f

/** 60 fps에서 프레임 하나의 지속 시간, 밀리초 단위로, 선택된 값이 아니라 프레임레이트로부터 파생된 값이다 — [FoundationFluidEdge.tick]의 `frameUnits`를 실제 경과 시간으로 정규화하는 데 쓰인다. */
private const val FoundationFrameMillis = 1000f / 60f

/** 모든 [FoundationFluidEdge] 점을 `x = 0` 쪽으로 다시 당기는 복원력의 가중치; [FoundationFluidEdge.tick] 참고. */
private const val FoundationFluidEdgeTension = 0.01f

/** [FoundationFluidCompleteThreshold]를 넘은 뒤 모든 [FoundationFluidEdge] 점을 `x = 1` 쪽으로 당기는 복원력의 가중치; 설계상 [FoundationFluidEdgeTension]과 크기가 같고 반대 방향으로 당긴다. */
private const val FoundationFluidFarEdgeTension = 0.01f

/** [FoundationFluidEdge] 점을 터치의 목표 progress 쪽으로 당기는 힘의 가중치로, [FoundationFluidTouchRadius] 안에서 터치와의 근접도에 따라 스케일된다; [FoundationFluidEdge.tick] 참고. */
private const val FoundationFluidTouchTension = 0.10f

/** [FoundationFluidEdge] 점을 각 이웃 점 쪽으로 당기는 힘의 가중치; 내부 점에서는 두 배가 되며, 이는 [FoundationFluidEdge.tick]의 안정성 분석에서 핵심적인 부분이다. */
private const val FoundationFluidPointTension = 0.25f

/** 터치가 활성인 동안 [FoundationFluidEdge] 점에 적용되는 프레임당 속도 감쇠 계수; 그 e-folding 시간과 [FoundationFluidReleaseDamping]과 근본적으로 어떻게 다른지는 [FoundationFluidEdge.tick] 참고. */
private const val FoundationFluidDamping = 0.90f

/** 손을 뗀 [FoundationFluidEdge]가 목표 쪽으로 다시 보간될 때, 프레임당 좁혀지는 남은 거리의 비율; [FoundationFluidCompleteThreshold]와 수치가 같은 것은 순전히 우연이다 — [FoundationFluidEdge.tick] 참고. */
private const val FoundationFluidReleaseDamping = 0.82f

/** 이 값을 넘는 turn progress부터 [FoundationFluidEdge.tick]이 far-edge tension을 활성화한다; [FoundationFluidReleaseDamping]과 수치가 같은 것은 순전히 우연이며, 둘은 서로 무관한 동작을 관장한다. */
private const val FoundationFluidCompleteThreshold = 0.82f

/**
 * 이 파일의 모든 3D 기울임/회전([FoundationWholePageFlipBox], [FoundationPageFlipHalfBox],
 * [Modifier.foundationMovieCarouselLayer])이 쓰는 `graphicsLayer` 카메라 거리: 완전히
 * 회전했을 때 원근 단축이 페이지를 어안렌즈처럼 왜곡하는 대신 은은하게 유지되도록 하는
 * 조정값이지, 파생값이 아니다.
 */
private const val FoundationCameraDistance = 64f

/**
 * [Modifier.foundationMovieCarouselLayer]의 (현재는 작동하지 않는,
 * [FoundationMovieTranslationRatio] 참고) 회전 분기가 완전히 멀어진
 * [PageAnimation.MOVIE_CAROUSEL] 페이지에 적용할 최대 기울임 각도, degree 단위. 그
 * 기울임이 얼마나 기울어질지에 대한 조정값이지, 파생값이 아니다.
 */
private const val FoundationMovieRotationDegrees = 12f

/**
 * [Modifier.foundationMovieCarouselLayer]가 멀어지는 [PageAnimation.MOVIE_CAROUSEL]
 * 페이지를 이동시킬, 페이지 자신의 크기에 대한 비율. 현재 `0f`이며, 이는
 * `FoundationMovieCarouselSpec.translationFraction`을 항상 0으로 만들어 그 이동과 짝을
 * 이루는 회전을 완전히 비활성화한다 — 이 style에서 현재 눈에 보이는 것은 scale/alpha
 * 축소뿐이다.
 */
private const val FoundationMovieTranslationRatio = 0f

/** `foundationMovieCarouselSpec`에 따라, 완전히 멀어진 [PageAnimation.MOVIE_CAROUSEL] 페이지가 축소되는 최소 scale. carousel이 얼마나 축소될지에 대한 조정값이지, 파생값이 아니다. */
private const val FoundationMovieMinScale = 0.9f

/** `foundationMovieCarouselSpec`에 따라, 완전히 멀어진 [PageAnimation.MOVIE_CAROUSEL] 페이지가 옅어지는 최소 alpha. carousel이 얼마나 옅어질지에 대한 조정값이지, 파생값이 아니다. */
private const val FoundationMovieMinAlpha = 0.55f

/**
 * 기하학적으로 반 바퀴, degree 단위: `foundationPageFlipHalfSpec`에 따라
 * [FoundationPageFlipLayout.SplitHalfFold]의 절반이 처음부터 끝까지 훑는 회전각. 위쪽
 * [FoundationPreviousPage] 그룹과 한 부류다 — flip의 기하로부터 파생된 값이지, 조정된
 * 값이 아니다.
 */
private const val FoundationPageFlipRotationDegrees = 180f

/**
 * 기하학적으로 4분의 1 바퀴, degree 단위: `foundationWholePageFlipSpec`에 따라
 * [FoundationPageFlipLayout.WholePage] 시트가 처음부터 끝까지 훑는 회전각. flip의
 * 기하로부터 파생된 값이지, 조정된 값이 아니다.
 */
private const val FoundationWholePageFlipRotationDegrees = 90f

/**
 * fluid-reveal의 cast shadow([foundationFluidShadow])의 최대 alpha. 이 상수와 이
 * 파일의 나머지 상수들은 이 파일의 cast/contact/hinge shadow 중 하나를 위한 단순한
 * alpha이거나 픽셀 너비로, 오직 shadow가 어떻게 보이는지만 보고 눈대중으로 고른
 * 값이다 — 위쪽의 fluid-edge 상수들과 달리 그 뒤에 어떤 공식이나 비율이나 물리적
 * 유도 과정도 없다.
 */
private const val FoundationRevealShadowAlpha = 0.28f

/** fluid-reveal의 cast shadow([foundationFluidShadow])의 너비, 픽셀 단위; 눈대중으로 고른 값이며 공식은 없다. */
private const val FoundationRevealShadowWidth = 58f

/** fluid-reveal의 더 좁은 contact shadow([foundationFluidShadow])의 너비, 픽셀 단위; 눈대중으로 고른 값이며 공식은 없다. */
private const val FoundationRevealContactShadowWidth = 3f

/** circle-reveal의 shadow ring(`foundationCircleRevealShadowSpec`)의 최대 alpha; 눈대중으로 고른 값이며 공식은 없다. */
private const val FoundationCircleRevealShadowAlpha = 0.22f

/** circle-reveal의 shadow ring(`foundationCircleRevealShadowSpec`)의 너비, 픽셀 단위; 눈대중으로 고른 값이며 공식은 없다. */
private const val FoundationCircleRevealShadowWidth = 30f

/** movie-carousel의 어둡게 하는 오버레이(`foundationMovieCarouselDimAlpha`)의 최대 alpha; 눈대중으로 고른 값이며 공식은 없다. */
private const val FoundationMovieShadowAlpha = 0.16f

/** movie-carousel의 incoming-edge shadow([Modifier.foundationMovieCarouselShadow])의 최대 alpha; 눈대중으로 고른 값이며 공식은 없다. */
private const val FoundationMovieEdgeShadowAlpha = 0.28f

/** movie-carousel의 incoming-edge shadow([Modifier.foundationMovieCarouselShadow])의 너비, 픽셀 단위; 눈대중으로 고른 값이며 공식은 없다. */
private const val FoundationMovieShadowWidth = 54f

/** progress가 최대일 때, leaf 하나에 대한 StPageFlip의 outer-shadow 상대 너비. */
private const val FoundationPageFlipOuterWidthRatio = 0.75f

/** StPageFlip의 inner-shadow가 outer shadow에 대해 갖는 상대 너비. */
private const val FoundationPageFlipInnerWidthRatio = 0.75f

/** Harism page-curl의 최대 inner shadow alpha. */
private const val FoundationPageFlipMaxShadowAlpha = 0.5f

/** inner shadow가 자유 edge 알파에서 중간 정지점 알파로 떨어지는 비율. */
private const val FoundationPageFlipInnerMidAlphaRatio = 0.4f

/** inner shadow의 중간 정지점 위치로, 자유 edge에서 띠 너비에 대한 비율. */
private const val FoundationPageFlipInnerMidStop = 0.35f

/** split-half spread의 spine 위치로, 접히는 절반의 fold가 여기 있다. */
private const val FoundationPageFlipSpineFraction = 0.5f

/** 결정론적인 receiver 선택에 쓰이는, 정규화된 spread spine 주변의 수치 허용 오차. */
private const val FoundationPageFlipSpineEpsilon = 0.0001f

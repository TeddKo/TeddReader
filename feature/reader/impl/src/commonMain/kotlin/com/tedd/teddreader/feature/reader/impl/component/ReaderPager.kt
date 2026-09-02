package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.feature.reader.impl.autoScrollDistancePx
import com.tedd.teddreader.feature.reader.impl.autoScrollLineDelayMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * [pageAnimation]에 맞는 pager 구현으로 위임한다: 연속 스크롤([ReaderScrollPager]),
 * slide/fluid/circle-reveal/movie-carousel/page-flip을 위한 커스텀 트랜지션이 적용된 Foundation
 * `HorizontalPager`/`VerticalPager`(`FoundationEffectPager`), curl style을 위한 pagecurl 상태 머신
 * (`FoundationCurlPager`), 또는 나머지 [PageAnimation] 값들을 위해 바로 아래 구현된 단순 크로스페이드/
 * 무-애니메이션 경로.
 *
 * 현재 [pageAnimation]을 처리하는 경로가 무엇이든 자신만의 제스처, page-move-request 소비, 자동 스크롤
 * 구동을 스스로 갖는다; 아래의 단순 폴백 경로([AnimatedContent]를 통한 `fadeIn`/`fadeOut`)는
 * [PageAnimation.NONE]과 [PageAnimation.FADE]를 뒷받침한다.
 *
 * @param pageKey 현재 페이지 인덱스; 이 pager가 이를 향해/이로부터 애니메이션하는 값.
 * @param pageCount 지금까지 알려진 전체 페이지 수.
 * @param pageStep 한 번의 turn이 몇 페이지를 진행시키는지 — 단일 pane이면 1, 두 페이지 spread면 2.
 * @param pageTurnMode 페이지가 가로축과 세로축 중 어느 쪽으로 넘어가는지.
 * @param pageAnimation 렌더링할 turn 애니메이션으로, 이것이 위임 구현을 고른다.
 * @param paperColor 접힌 부분의 뒷면을 채우는 페이지 색으로, 독자가 고른 리더 팔레트의 종이색이다.
 * @param canRequestNextPage 알려진 끝에 있는 텍스트 문서가 페이지 나누기가 아직 끝나지 않은 동안에도
 *   view model에 다음 요청을 계속 전달해야 하는지 여부.
 * @param pageMoveRequest 대기 중인 프로그래밍적 페이지 이동 요청(하단 바의 이전/다음 버튼에서 옴), 없으면
 *   null.
 * @param onPageMoveRequestConsumed [pageMoveRequest]의 id와 함께, 그것이 처리되었거나 갈 곳이 없다고
 *   확인된 뒤 호출된다.
 * @param onPreviousPage 문서 시작 쪽으로 한 걸음 이동하기 위해 호출된다.
 * @param onNextPage 문서 끝 쪽으로 한 걸음 이동하기 위해 호출된다.
 * @param onPageSelected 직접 선택된 페이지 인덱스와 함께 호출된다([ReaderScrollPager]의 앵커 보고에
 *   쓰인다).
 * @param onToggleControls 탭이 두 turn 영역 바깥에 떨어지거나, 자동 스크롤이 활성화된 동안 어떤 탭이든
 *   발생하면 호출된다.
 * @param onDoubleTap 더블 탭 시 탭 위치와 함께 호출된다; null이면 더블 탭 처리를 비활성화한다(visual/PDF
 *   모드의 확대에 쓰인다).
 * @param isAutoScrollEnabled 자동 스크롤이 현재 페이지 넘김을 구동하고 있는지 여부.
 * @param effectiveAutoScrollMode 따를 자동 스크롤 모드로, 콘텐츠가 텍스트인지 visual인지에 대해 이미
 *   해석되어 있다(`readerEffectiveAutoScrollMode` 참고).
 * @param autoScrollSpeed 설정된 자동 스크롤 속도.
 * @param autoScrollLineHeightPx line 모드 자동 스크롤이 쓰는, 현재 style의 픽셀 단위 줄 높이.
 * @param autoScrollDensity 자동 스크롤 속도를 픽셀로 환산하는 데 쓰이는 화면 밀도.
 * @param onAutoScrollStop 자동 스크롤이 문서 끝에 닿아 멈춰야 할 때 호출된다.
 * @param onMovieTransitionProgressChanged movie-carousel 트랜지션의 진행률과 함께 호출되며, 애니메이션
 *   뒤 콘텐츠를 어둡게 하는 호출자를 위한 것이다.
 * @param modifier pager의 루트에 적용되는 modifier.
 * @param paneCount 몇 개의 페이지 pane이 나란히 보이는지(spread면 2, 그 외엔 1).
 * @param spreadGutter spread에서 pane 사이에 그려지는 간격.
 * @param spreadLeftWeight spread의 너비 중 왼쪽 pane에 주어지는 비율.
 * @param spreadModifier spread의 row에 적용되는 modifier로, curl pager에만 전달된다.
 * @param paneContent spread의 한 pane을 자신만의 modifier로 렌더링한다; 단일 pane이거나 spread를
 *   지원하지 않는 위임 pager라면 null.
 * @param content spread가 아닌 위임 구현을 위해 주어진 인덱스의 페이지를 렌더링한다.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun ReaderPager(
    pageKey: Int,
    pageCount: Int,
    pageStep: Int = 1,
    pageTurnMode: PageTurnMode,
    pageAnimation: PageAnimation,
    paperColor: Color,
    canRequestNextPage: Boolean,
    pageMoveRequest: ReaderPageMoveRequest?,
    onPageMoveRequestConsumed: (Int) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onToggleControls: () -> Unit,
    onDoubleTap: ((Offset) -> Unit)?,
    isAutoScrollEnabled: Boolean,
    effectiveAutoScrollMode: AutoScrollMode,
    autoScrollSpeed: Float,
    autoScrollLineHeightPx: Float,
    autoScrollDensity: Float,
    onAutoScrollStop: () -> Unit,
    onMovieTransitionProgressChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
    paneCount: Int = 1,
    spreadGutter: Dp = 0.dp,
    spreadLeftWeight: Float = 0.5f,
    spreadModifier: Modifier = Modifier,
    paneContent: (@Composable (page: Int, modifier: Modifier) -> Unit)? = null,
    content: @Composable (page: Int) -> Unit,
) {
    when (pageAnimation) {
        PageAnimation.SCROLL -> {
            ReaderScrollPager(
                pageKey = pageKey,
                pageCount = pageCount,
                pageStep = pageStep,
                pageTurnMode = pageTurnMode,
                canRequestNextPage = canRequestNextPage,
                pageMoveRequest = pageMoveRequest,
                onPageMoveRequestConsumed = onPageMoveRequestConsumed,
                onPageSelected = onPageSelected,
                onNextPage = onNextPage,
                onToggleControls = onToggleControls,
                onDoubleTap = onDoubleTap,
                isAutoScrollEnabled = isAutoScrollEnabled,
                autoScrollMode = effectiveAutoScrollMode,
                autoScrollSpeed = autoScrollSpeed,
                autoScrollLineHeightPx = autoScrollLineHeightPx,
                autoScrollDensity = autoScrollDensity,
                onAutoScrollStop = onAutoScrollStop,
                modifier = modifier,
                content = content,
            )
            return
        }

        PageAnimation.SLIDE,
        PageAnimation.SHEET_FLIP,
        PageAnimation.FLUID_PAGER,
        PageAnimation.CIRCLE_REVEAL,
        PageAnimation.MOVIE_CAROUSEL,
        PageAnimation.PAGE_FLIP,
            -> {
            FoundationEffectPager(
                pageKey = pageKey,
                pageCount = pageCount,
                pageStep = pageStep,
                pageTurnMode = pageTurnMode,
                pageAnimation = pageAnimation,
                canRequestNextPage = canRequestNextPage,
                pageMoveRequest = pageMoveRequest,
                onPageMoveRequestConsumed = onPageMoveRequestConsumed,
                onPreviousPage = onPreviousPage,
                onNextPage = onNextPage,
                onToggleControls = onToggleControls,
                onDoubleTap = onDoubleTap,
                isAutoScrollEnabled = isAutoScrollEnabled,
                autoScrollMode = effectiveAutoScrollMode,
                autoScrollSpeed = autoScrollSpeed,
                autoScrollLineHeightPx = autoScrollLineHeightPx,
                autoScrollDensity = autoScrollDensity,
                onAutoScrollStop = onAutoScrollStop,
                onMovieTransitionProgressChanged = onMovieTransitionProgressChanged,
                paneCount = paneCount,
                modifier = modifier,
                content = content,
            )
            return
        }

        PageAnimation.BOOK_CURL,
        PageAnimation.CURL_PAGER,
        PageAnimation.THREE_D_CURL -> {
            FoundationCurlPager(
                pageKey = pageKey,
                pageCount = pageCount,
                pageStep = pageStep,
                pageTurnMode = pageTurnMode,
                style = if (pageAnimation == PageAnimation.THREE_D_CURL) {
                    FoundationReferenceCurlStyle.ThreeDimensional
                } else {
                    FoundationReferenceCurlStyle.Standard
                },
                paperColor = paperColor,
                canRequestNextPage = canRequestNextPage,
                pageMoveRequest = pageMoveRequest,
                onPageMoveRequestConsumed = onPageMoveRequestConsumed,
                onPreviousPage = onPreviousPage,
                onNextPage = onNextPage,
                onToggleControls = onToggleControls,
                onDoubleTap = onDoubleTap,
                isAutoScrollEnabled = isAutoScrollEnabled,
                autoScrollMode = effectiveAutoScrollMode,
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
            return
        }

        else -> Unit
    }

    val previousPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, -1)
    val nextPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, 1)
    val canGoNext = readerPagerCanAdvanceForward(nextPage != null, canRequestNextPage)

    LaunchedEffect(pageMoveRequest?.id) {
        val request = pageMoveRequest ?: return@LaunchedEffect
        try {
            val targetPage = readerPagerRequestedPage(pageKey, pageCount, pageStep, request.movement)
            if (readerPagerShouldDispatchRequest(targetPage, request.movement, canRequestNextPage)) {
                when (request.movement) {
                    ReaderPageMovement.Previous -> onPreviousPage()
                    ReaderPageMovement.Next -> onNextPage()
                }
            }
        } finally {
            onPageMoveRequestConsumed(request.id)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(pageTurnMode, isAutoScrollEnabled, previousPage != null, canGoNext, onPreviousPage, onNextPage) {
                detectReaderSwipe(
                    pageTurnMode = pageTurnMode,
                    isAutoScrollEnabled = isAutoScrollEnabled,
                    canGoPrevious = previousPage != null,
                    canGoNext = canGoNext,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage,
                )
            }
            .pointerInput(isAutoScrollEnabled, previousPage != null, canGoNext, onPreviousPage, onNextPage, onToggleControls, onDoubleTap) {
                detectTapGestures(
                    onDoubleTap = onDoubleTap,
                    onTap = { position ->
                        handleTap(
                            position = position,
                            isAutoScrollEnabled = isAutoScrollEnabled,
                            canGoPrevious = previousPage != null,
                            canGoNext = canGoNext,
                            onPreviousPage = onPreviousPage,
                            onNextPage = onNextPage,
                            onToggleControls = onToggleControls,
                        )
                    },
                )
            },
    ) {
        AnimatedContent(
            targetState = pageKey,
            transitionSpec = {
                when (pageAnimation) {
                    PageAnimation.NONE -> fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    PageAnimation.FADE -> fadeIn(tween(140)) togetherWith fadeOut(tween(140))
                    PageAnimation.BOOK_CURL,
                    PageAnimation.THREE_D_CURL,
                    PageAnimation.SLIDE,
                    PageAnimation.SHEET_FLIP,
                    PageAnimation.SCROLL,
                    PageAnimation.FLUID_PAGER,
                    PageAnimation.CURL_PAGER,
                    PageAnimation.CIRCLE_REVEAL,
                    PageAnimation.MOVIE_CAROUSEL,
                    PageAnimation.PAGE_FLIP,
                        -> fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                }
            },
            label = "ReaderPagerPage",
        ) { page ->
            content(page)
        }
    }
}

/**
 * [PageAnimation.SCROLL]을 뒷받침하는 연속 스크롤 pager: 별개의
 * `HorizontalPager`/`VerticalPager` 대신 페이지 앵커([readerScrollPageAnchors] 참고)로 이루어진
 * `LazyColumn`/`LazyRow`이며, 그래서 spread의 두 pane이 하나의 연속적으로 흐르는 리스트 항목처럼 함께
 * 스크롤된다.
 *
 * 사용자가 스크롤하는 동안에는 리스트 자체의 스크롤 위치가 현재 페이지의 진실 공급원이다:
 * [onPageSelected]는 `listState.firstVisibleItemIndex`로부터 구동되며, [pageKey] 변경(재
 * 페이지 나누기, 또는 다른 곳에서의 페이지 이동)은 진행 중인 fling이 모두 가라앉은 뒤에만 리스트에
 * 되돌려 동기화된다 — 동기화를 포기하는 대신 기다리는 이유는, 재 페이지 나누기가 스크롤 도중에 일어날
 * 수 있고, 여기서 포기하면 리스트가 새 페이지 나누기가 아니라 이전 페이지 나누기에 속했던 항목
 * 인덱스에 그대로 멈춰 있게 되기 때문이다.
 *
 * 앵커 보고 effect의 `.drop(1)`은 반대 방향에서 같은 이유로 존재한다:
 * `snapshotFlow { listState.firstVisibleItemIndex }`는 재시작될 때마다 자신의 현재 값을 즉시
 * 다시 흘려보내는데, [anchors] 자체가 방금 바뀌었을 때(재 페이지 나누기) 그 다시 흘러나온 값은
 * *이전* 페이지 나누기가 리스트를 멈춰 두었던 인덱스다. 이를 [onPageSelected]로 보고하면 오래된
 * 인덱스를 새 앵커에 대고 해석해 리더를 거기 우연히 있는 아무 페이지로나 보내게 된다 — 최악의 경우
 * 첫 페이지로. 그래서 이 첫 번째의 낡은 값은 버려지고 그 이후의 진짜 스크롤만 보고된다.
 *
 * @param pageKey 리스트가 계속 스크롤되어 맞춰야 할 현재 페이지 인덱스.
 * @param pageCount 지금까지 알려진 전체 페이지 수.
 * @param pageStep 하나의 스크롤 앵커가 몇 페이지를 진행시키는지 — 단일 pane이면 1, spread면 2.
 * @param pageTurnMode 리스트가 세로로 스크롤되는지 가로로 스크롤되는지.
 * @param pageMoveRequest 대기 중인 프로그래밍적 페이지 이동 요청, 없으면 null.
 * @param onPageMoveRequestConsumed [pageMoveRequest]의 id와 함께, 그것이 처리된 뒤 호출된다.
 * @param onPageSelected 스크롤 뒤 리스트가 멈춰 선 페이지 앵커와 함께 호출된다.
 * @param onToggleControls 탭이 두 turn 영역 바깥에 떨어지거나 자동 스크롤 도중이면 호출된다.
 * @param onDoubleTap 더블 탭 시 탭 위치와 함께 호출된다; null이면 이를 비활성화한다.
 * @param isAutoScrollEnabled 자동 스크롤이 현재 리스트를 구동하고 있는지 여부.
 * @param autoScrollMode 따를 자동 스크롤 모드.
 * @param autoScrollSpeed 설정된 자동 스크롤 속도.
 * @param autoScrollLineHeightPx line 모드 자동 스크롤이 쓰는, 현재 style의 픽셀 단위 줄 높이.
 * @param autoScrollDensity 자동 스크롤 속도를 픽셀로 환산하는 데 쓰이는 화면 밀도.
 * @param onAutoScrollStop 자동 스크롤이 문서 끝에 닿아 멈춰야 할 때 호출된다.
 * @param modifier 리스트의 루트에 적용되는 modifier.
 * @param content 주어진 앵커 인덱스의 페이지를 렌더링한다.
 */
@Composable
private fun ReaderScrollPager(
    pageKey: Int,
    pageCount: Int,
    pageStep: Int,
    pageTurnMode: PageTurnMode,
    canRequestNextPage: Boolean,
    pageMoveRequest: ReaderPageMoveRequest?,
    onPageMoveRequestConsumed: (Int) -> Unit,
    onPageSelected: (Int) -> Unit,
    onNextPage: () -> Unit,
    onToggleControls: () -> Unit,
    onDoubleTap: ((Offset) -> Unit)?,
    isAutoScrollEnabled: Boolean,
    autoScrollMode: AutoScrollMode,
    autoScrollSpeed: Float,
    autoScrollLineHeightPx: Float,
    autoScrollDensity: Float,
    onAutoScrollStop: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (page: Int) -> Unit,
) {
    val anchors = readerScrollPageAnchors(pageCount = pageCount, pageStep = pageStep)
    val currentAnchorIndex = readerScrollAnchorIndex(page = pageKey, anchors = anchors)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentAnchorIndex)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pageKey, anchors) {
        if (anchors.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }.first { scrolling -> !scrolling }
        if (listState.firstVisibleItemIndex != currentAnchorIndex) {
            listState.scrollToItem(currentAnchorIndex)
        }
    }
    LaunchedEffect(pageMoveRequest?.id, pageKey, pageCount, pageStep, anchors) {
        val request = pageMoveRequest ?: return@LaunchedEffect
        try {
            val targetPage = readerPagerRequestedPage(pageKey, pageCount, pageStep, request.movement)
            if (targetPage != null) {
                val targetIndex = readerScrollAnchorIndex(page = targetPage, anchors = anchors)
                if (targetIndex != listState.firstVisibleItemIndex) {
                    listState.animateScrollToItem(targetIndex)
                }
            } else if (request.movement == ReaderPageMovement.Next && canRequestNextPage) {
                onNextPage()
            }
        } finally {
            onPageMoveRequestConsumed(request.id)
        }
    }
    LaunchedEffect(listState, anchors) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .drop(1)
            .distinctUntilChanged()
            .collect { index ->
                anchors.getOrNull(index)?.let(onPageSelected)
            }
    }
    LaunchedEffect(
        listState,
        anchors,
        isAutoScrollEnabled,
        autoScrollMode,
        autoScrollSpeed,
        autoScrollLineHeightPx,
        autoScrollDensity,
    ) {
        if (!isAutoScrollEnabled || autoScrollMode == AutoScrollMode.PAGE) return@LaunchedEffect
        if (anchors.isEmpty()) {
            onAutoScrollStop()
            return@LaunchedEffect
        }
        if (listState.firstVisibleItemIndex >= anchors.lastIndex && !listState.canScrollForward) {
            onAutoScrollStop()
            return@LaunchedEffect
        }

        when (autoScrollMode) {
            AutoScrollMode.PIXEL -> {
                var lastFrameNanos = 0L
                while (isActive) {
                    val frameNanos = withFrameNanos { it }
                    if (lastFrameNanos != 0L) {
                        val elapsedMillis = (frameNanos - lastFrameNanos) / 1_000_000L
                        val distancePx = autoScrollDistancePx(
                            speed = autoScrollSpeed,
                            density = autoScrollDensity,
                            elapsedMillis = elapsedMillis,
                        )
                        if (distancePx > 0f) listState.scrollBy(distancePx)
                        if (listState.firstVisibleItemIndex >= anchors.lastIndex && !listState.canScrollForward) {
                            onAutoScrollStop()
                            break
                        }
                    }
                    lastFrameNanos = frameNanos
                }
            }

            AutoScrollMode.LINE -> {
                val lineHeightPx = autoScrollLineHeightPx.coerceAtLeast(1f)
                val pixelsPerSecond = autoScrollDistancePx(
                    speed = autoScrollSpeed,
                    density = autoScrollDensity,
                    elapsedMillis = 1_000L,
                ).coerceAtLeast(1f)
                val delayMillis = autoScrollLineDelayMillis(
                    lineHeightPx = lineHeightPx,
                    pixelsPerSecond = pixelsPerSecond,
                )
                while (isActive) {
                    if (listState.firstVisibleItemIndex >= anchors.lastIndex && !listState.canScrollForward) {
                        onAutoScrollStop()
                        break
                    }
                    listState.animateScrollBy(lineHeightPx)
                    if (listState.firstVisibleItemIndex >= anchors.lastIndex && !listState.canScrollForward) {
                        onAutoScrollStop()
                        break
                    }
                    delay(delayMillis)
                }
            }

            AutoScrollMode.PAGE -> Unit
        }
    }

    val tapModifier = Modifier.pointerInput(
        isAutoScrollEnabled,
        pageTurnMode,
        canRequestNextPage,
        onNextPage,
        onToggleControls,
        onDoubleTap,
        anchors,
    ) {
        detectTapGestures(
            onDoubleTap = onDoubleTap,
            onTap = { position ->
                if (isAutoScrollEnabled) {
                    onToggleControls()
                } else {
                    val primary = if (isVerticalMode(pageTurnMode)) position.y else position.x
                    val extent = if (isVerticalMode(pageTurnMode)) size.height else size.width
                    val currentIndex = listState.firstVisibleItemIndex.coerceIn(0, anchors.lastIndex.coerceAtLeast(0))
                    when {
                        primary < extent * PreviousTapZoneRatio -> {
                            if (currentIndex > 0) {
                                coroutineScope.launch { listState.animateScrollToItem(currentIndex - 1) }
                            }
                        }

                        primary > extent * NextTapZoneRatio -> {
                            if (currentIndex < anchors.lastIndex) {
                                coroutineScope.launch { listState.animateScrollToItem(currentIndex + 1) }
                            } else if (canRequestNextPage) {
                                onNextPage()
                            }
                        }

                        else -> onToggleControls()
                    }
                }
            },
        )
    }
    val forwardOverscrollModifier = Modifier.pointerInput(
        isAutoScrollEnabled,
        canRequestNextPage,
        pageTurnMode,
        listState,
    ) {
        if (isAutoScrollEnabled || !canRequestNextPage) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startedAtEnd = !listState.canScrollForward
            var drag = Offset.Zero
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                drag += change.position - change.previousPosition
                if (!change.pressed) break
            }
            if (readerScrollShouldRequestNextOnOverscroll(
                    canRequestNextPage = canRequestNextPage,
                    startedAtEnd = startedAtEnd,
                    pageTurnMode = pageTurnMode,
                    drag = drag,
                )
            ) {
                onNextPage()
            }
        }
    }
    val scrollModifiers = modifier.fillMaxSize().then(tapModifier).then(forwardOverscrollModifier)

    if (isVerticalMode(pageTurnMode)) {
        LazyColumn(
            state = listState,
            modifier = scrollModifiers,
            userScrollEnabled = !isAutoScrollEnabled,
            overscrollEffect = null,
        ) {
            items(count = anchors.size, key = { index -> anchors[index] }) { index ->
                Box(modifier = Modifier.fillParentMaxSize()) {
                    content(anchors[index])
                }
            }
        }
    } else {
        LazyRow(
            state = listState,
            modifier = scrollModifiers,
            userScrollEnabled = !isAutoScrollEnabled,
            overscrollEffect = null,
        ) {
            items(count = anchors.size, key = { index -> anchors[index] }) { index ->
                Box(modifier = Modifier.fillParentMaxSize()) {
                    content(anchors[index])
                }
            }
        }
    }
}

/**
 * 한 번에 하나의 제스처를 지켜보며 [TouchSlopPx]를 넘는 스와이프인지 살피고, 이동한 채로 제스처가
 * 끝나면 [handleSwipe]를 통해 이를 페이지 변경으로 바꾼다. 드래그를 감지할 자신만의 pager 위젯이 없는
 * 단순 fade/무-애니메이션 pager 경로가 사용한다.
 *
 * @param pageTurnMode 스와이프를 측정하는 축(가로/세로).
 * @param isAutoScrollEnabled 자동 스크롤이 실행 중인지 여부; 실행 중인 동안에는 수동 turn이 자동
 *   turn과 경쟁하게 되므로 스와이프를 완전히 무시한다.
 * @param onPreviousPage 스와이프가 이전 페이지 turn으로 해석되면 호출된다.
 * @param onNextPage 스와이프가 다음 페이지 turn으로 해석되면 호출된다.
 */
private suspend fun PointerInputScope.detectReaderSwipe(
    pageTurnMode: PageTurnMode,
    isAutoScrollEnabled: Boolean,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var drag = Offset.Zero
        var moved = false

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { change -> change.id == down.id } ?: break
            if (!change.pressed) break
            drag += change.positionChange()
            if (abs(drag.x) > TouchSlopPx || abs(drag.y) > TouchSlopPx) moved = true
            if (moved) change.consume()
        }

        if (moved) {
            if (!isAutoScrollEnabled) {
                handleSwipe(pageTurnMode, drag, canGoPrevious, canGoNext, onPreviousPage, onNextPage)
            }
        }
    }
}

/**
 * 완료된 드래그를 페이지 turn으로 해석하며, 드래그가 turn 축을 따라 [SwipeThresholdPx]를 넘어서고
 * 가로지르는 방향보다 그 축을 따르는 방향으로 더 많이 움직였을 것을 요구한다, 그래야 가로 pager에서
 * 대부분 세로로 움직인 드래그(또는 그 반대)가 turn으로 잘못 읽히지 않는다.
 *
 * @param pageTurnMode 드래그를 측정하는 축.
 * @param drag 제스처 동안 누적된 전체 드래그 오프셋.
 * @param onPreviousPage 드래그가 이전 페이지 turn으로 해석되면 호출된다.
 * @param onNextPage 드래그가 다음 페이지 turn으로 해석되면 호출된다.
 */
private fun PointerInputScope.handleSwipe(
    pageTurnMode: PageTurnMode,
    drag: Offset,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val primary = if (isVerticalMode(pageTurnMode)) drag.y else drag.x
    val cross = if (isVerticalMode(pageTurnMode)) drag.x else drag.y
    if (abs(primary) > SwipeThresholdPx && abs(primary) > abs(cross)) {
        if (primary < 0f) {
            if (canGoNext) onNextPage()
        } else if (canGoPrevious) {
            onPreviousPage()
        }
    }
}

/**
 * 단순 fade/무-애니메이션 pager 위의 탭을, 어느 가로 영역([PreviousTapZoneRatio],
 * [NextTapZoneRatio] 참고)에 떨어졌는지에 따라 이전 페이지 turn, 다음 페이지 turn, 또는 컨트롤 토글로
 * 해석한다.
 *
 * @param position pager 안에서의 탭 위치.
 * @param isAutoScrollEnabled 자동 스크롤이 실행 중인지 여부; 실행 중인 동안에는 페이지를 넘겨 자동
 *   turn과 경쟁하는 대신 모든 탭이 컨트롤을 토글한다.
 * @param onPreviousPage 탭이 이전 페이지 영역에 떨어지면 호출된다.
 * @param onNextPage 탭이 다음 페이지 영역에 떨어지면 호출된다.
 * @param onToggleControls 탭이 가운데 영역에 떨어지거나 자동 스크롤이 활성화된 동안이면 호출된다.
 */
private fun PointerInputScope.handleTap(
    position: Offset,
    isAutoScrollEnabled: Boolean,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleControls: () -> Unit,
) {
    when {
        isAutoScrollEnabled -> onToggleControls()
        position.x < size.width * PreviousTapZoneRatio -> if (canGoPrevious) onPreviousPage() else onToggleControls()
        position.x > size.width * NextTapZoneRatio -> if (canGoNext) onNextPage() else onToggleControls()
        else -> onToggleControls()
    }
}

internal fun readerScrollShouldRequestNextOnOverscroll(
    canRequestNextPage: Boolean,
    startedAtEnd: Boolean,
    pageTurnMode: PageTurnMode,
    drag: Offset,
): Boolean {
    if (!canRequestNextPage || !startedAtEnd) return false
    val primary = if (isVerticalMode(pageTurnMode)) drag.y else drag.x
    val cross = if (isVerticalMode(pageTurnMode)) drag.x else drag.y
    return primary < -SwipeThresholdPx && abs(primary) > abs(cross)
}

/**
 * [pageTurnMode]가 페이지를 가로가 아니라 세로축을 따라 배치하는지 여부(별개의 세로 모드와, 항상
 * 세로인 연속 스크롤 모드 둘 다 포함).
 *
 * @param pageTurnMode 확인할 page-turn 모드.
 * @return [PageTurnMode.VERTICAL] 또는 [PageTurnMode.CONTINUOUS]면 true.
 */
private fun isVerticalMode(pageTurnMode: PageTurnMode): Boolean =
    pageTurnMode == PageTurnMode.VERTICAL || pageTurnMode == PageTurnMode.CONTINUOUS

/**
 * [currentPage]에서 [pageStep] 페이지 단위로 [pageOffset]만큼 페이지 스텝을 이동했을 때 도달하는
 * 페이지로, `[0, pageCount)` 범위로 제한되며, 그런 페이지가 없으면 null이다.
 *
 * 여기서 null은 예외적으로 따로 처리해야 할 경계 케이스가 아니라 그 자체로 의미를 갖는 결과다: 모든
 * 호출자(`FoundationEffectPager`의 이전/다음 조회, [readerPagerRequestedPage],
 * `foundationPagerTapAction`의 영역 해석)는 null을 "그 방향에 인접 페이지가 없음"으로 취급하고
 * 아무것도 하지 않는 대신 가운데 영역 탭이 받는 것과 같은 동작 — 컨트롤 토글 — 로 넘어간다. 탭이나
 * 드래그는 문서의 시작이나 끝에서 결코 조용히 삼켜져서는 안 되는데, null이 아닌 신호만 쓰던 예전
 * 방식은 정확히 이를 허용했다.
 *
 * @param currentPage 이동을 시작할 페이지.
 * @param pageCount 지금까지 알려진 전체 페이지 수.
 * @param pageStep 하나의 page-offset 스텝이 몇 페이지를 아우르는지 — 단일 pane이면 1, 두 페이지
 *   spread면 2.
 * @param pageOffset 몇 페이지 스텝을 이동할지, 음수면 시작 쪽으로, 양수면 끝 쪽으로; 0이면
 *   [currentPage] 자체를 반환한다(경계 검사됨).
 * @return 목표 페이지 인덱스, 또는 [currentPage]가 범위를 벗어났거나 이동 결과가 `[0, pageCount)`
 *   바깥으로 나가면 null.
 */
internal fun readerPagerAdjacentPage(
    currentPage: Int,
    pageCount: Int,
    pageStep: Int,
    pageOffset: Int,
): Int? {
    if (pageCount <= 0 || currentPage !in 0 until pageCount) return null
    if (pageOffset == 0) return currentPage

    val step = pageStep.coerceAtLeast(1)
    val target = if (pageOffset < 0) {
        currentPage - min(step, currentPage)
    } else {
        if (step > pageCount - 1 - currentPage) return null
        currentPage + step
    }
    return target.takeIf { it != currentPage }
}

/**
 * [ReaderPageMovement] 방향에 특화된 [readerPagerAdjacentPage]로, 대기 중인
 * [ReaderPageMoveRequest]를 이동이 실제로 실행되는 시점에 살아 있는 페이지 나누기에 대고 해석하는 데
 * 쓰인다.
 *
 * @param currentPage 이동을 시작할 페이지.
 * @param pageCount 지금까지 알려진 전체 페이지 수.
 * @param pageStep 하나의 turn이 몇 페이지를 아우르는지.
 * @param movement 어느 방향으로 이동할지.
 * @return 목표 페이지 인덱스, 또는 그 방향에 페이지가 없으면 null; null이 호출자에게 무엇을 의미하는지는
 *   [readerPagerAdjacentPage]를 참고한다.
 */
internal fun readerPagerRequestedPage(
    currentPage: Int,
    pageCount: Int,
    pageStep: Int,
    movement: ReaderPageMovement,
): Int? = readerPagerAdjacentPage(currentPage, pageCount, pageStep, movement.pageOffset)


internal fun readerPagerCanAdvanceForward(
    hasActualNextPage: Boolean,
    canRequestNextPage: Boolean,
): Boolean = hasActualNextPage || canRequestNextPage

internal fun readerPagerShouldDispatchRequest(
    targetPage: Int?,
    movement: ReaderPageMovement,
    canRequestNextPage: Boolean,
): Boolean = when (movement) {
    ReaderPageMovement.Previous -> targetPage != null
    ReaderPageMovement.Next -> targetPage != null || canRequestNextPage
}

internal fun readerPagerDisplayedPage(
    currentPage: Int,
    adjacentPage: Int?,
    pageOffset: Int,
    canRequestNextPage: Boolean,
): Int? = when {
    adjacentPage != null -> adjacentPage
    pageOffset > 0 && canRequestNextPage -> currentPage
    else -> null
}

/**
 * 하나의 프로그래밍적 페이지 turn 요청(하단 바의 이전/다음 버튼에서 옴)으로, 소비될 때까지 pager
 * 자신의 상태를 통해 실려 다닌다.
 *
 * @property id [movement]만으로는 구분되지 않는 요청 식별자로, 그래서 두 번째로 들어온 동일한
 *   요청(예: 첫 turn이 끝나기 전에 "다음"을 두 번 탭하는 경우)이 아무 효과 없는 반복으로 조용히
 *   무시되지 않는다.
 * @property movement 어느 방향으로 이동할지.
 */
internal data class ReaderPageMoveRequest(
    val id: Int,
    val movement: ReaderPageMovement,
)

/**
 * 프로그래밍적 페이지 이동이 요청할 수 있는 두 방향.
 *
 * @property pageOffset [movement]가 대응하는, 부호 있는 page-step 오프셋으로,
 *   [readerPagerAdjacentPage]/[readerPagerRequestedPage]가 직접 소비한다.
 */
internal enum class ReaderPageMovement(val pageOffset: Int) {
    Previous(-1),
    Next(1),
}

/**
 * [ReaderScrollPager]의 리스트가 스크롤 정지 지점으로 취급하는 페이지 인덱스 — 0부터 시작해
 * [pageStep] 번째마다 하나씩 — 그래서 spread의 두 pane이 두 개가 아니라 하나의 공유된 리스트 항목에
 * 놓인다.
 *
 * @param pageCount 지금까지 알려진 전체 페이지 수.
 * @param pageStep 하나의 스크롤 앵커가 몇 페이지를 아우르는지.
 * @return 오름차순으로 정렬된 앵커 페이지 인덱스 목록; [pageCount]가 0이면 비어 있다.
 */
internal fun readerScrollPageAnchors(pageCount: Int, pageStep: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    val step = pageStep.coerceAtLeast(1)
    return (0 until pageCount step step).toList()
}

/**
 * [page]를 소유하는 [anchors] 안의 인덱스 — 그것과 같거나 그 이전의 마지막 앵커 — 로, 페이지
 * 번호를 [ReaderScrollPager]가 스크롤되어야 할 리스트 위치로 해석하는 데 쓰인다.
 *
 * @param page 해석할 페이지.
 * @param anchors [readerScrollPageAnchors]가 만든 오름차순 앵커 목록.
 * @return 소유하는 앵커의 인덱스, 또는 [anchors]가 비어 있거나 [page]가 모든 앵커보다 앞서면 0.
 */
internal fun readerScrollAnchorIndex(page: Int, anchors: List<Int>): Int {
    if (anchors.isEmpty()) return 0
    return anchors.indexOfLast { anchor -> anchor <= page }
        .takeIf { it >= 0 }
        ?: 0
}

/** [detectReaderSwipe]가 제스처를 스와이프로 취급하기까지 필요한 최소 드래그 거리(픽셀). */
private const val TouchSlopPx = 8f

/** [handleSwipe]가 페이지를 넘기기까지 turn 축을 따라 필요한 최소 드래그 거리(픽셀). */
private const val SwipeThresholdPx = 72f

/** pager 전체 길이 중, 시작 가장자리로부터 [handleTap]이 "이전"으로 취급하는 비율. */
private const val PreviousTapZoneRatio = 0.28f

/** pager 전체 길이 중, 시작 가장자리로부터 이 비율을 넘어서면 [handleTap]이 탭을 "다음"으로 취급한다. */
private const val NextTapZoneRatio = 0.72f

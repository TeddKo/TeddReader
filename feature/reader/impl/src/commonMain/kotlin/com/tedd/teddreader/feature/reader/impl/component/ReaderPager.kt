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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun ReaderPager(
    pageKey: Int,
    pageCount: Int,
    pageStep: Int = 1,
    pageTurnMode: PageTurnMode,
    pageAnimation: PageAnimation,
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
                pageMoveRequest = pageMoveRequest,
                onPageMoveRequestConsumed = onPageMoveRequestConsumed,
                onPageSelected = onPageSelected,
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
        PageAnimation.CURL_PAGER -> {
            FoundationCurlPager(
                pageKey = pageKey,
                pageCount = pageCount,
                pageStep = pageStep,
                pageTurnMode = pageTurnMode,
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

    LaunchedEffect(pageMoveRequest?.id) {
        val request = pageMoveRequest ?: return@LaunchedEffect
        try {
            if (readerPagerRequestedPage(pageKey, pageCount, pageStep, request.movement) != null) {
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
            .pointerInput(pageTurnMode, isAutoScrollEnabled, onPreviousPage, onNextPage) {
                detectReaderSwipe(
                    pageTurnMode = pageTurnMode,
                    isAutoScrollEnabled = isAutoScrollEnabled,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage,
                )
            }
            .pointerInput(isAutoScrollEnabled, onPreviousPage, onNextPage, onToggleControls, onDoubleTap) {
                detectTapGestures(
                    onDoubleTap = onDoubleTap,
                    onTap = { position ->
                        handleTap(
                            position = position,
                            isAutoScrollEnabled = isAutoScrollEnabled,
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

@Composable
private fun ReaderScrollPager(
    pageKey: Int,
    pageCount: Int,
    pageStep: Int,
    pageTurnMode: PageTurnMode,
    pageMoveRequest: ReaderPageMoveRequest?,
    onPageMoveRequestConsumed: (Int) -> Unit,
    onPageSelected: (Int) -> Unit,
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
        if (anchors.isEmpty() || listState.isScrollInProgress) return@LaunchedEffect
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
            }
        } finally {
            onPageMoveRequestConsumed(request.id)
        }
    }
    LaunchedEffect(listState, anchors) {
        snapshotFlow { listState.firstVisibleItemIndex }
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

    val tapModifier = Modifier.pointerInput(isAutoScrollEnabled, pageTurnMode, onToggleControls, onDoubleTap, anchors) {
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
                            }
                        }

                        else -> onToggleControls()
                    }
                }
            },
        )
    }

    if (isVerticalMode(pageTurnMode)) {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize().then(tapModifier),
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
            modifier = modifier.fillMaxSize().then(tapModifier),
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

private suspend fun PointerInputScope.detectReaderSwipe(
    pageTurnMode: PageTurnMode,
    isAutoScrollEnabled: Boolean,
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
                handleSwipe(pageTurnMode, drag, onPreviousPage, onNextPage)
            }
        }
    }
}

private fun PointerInputScope.handleSwipe(
    pageTurnMode: PageTurnMode,
    drag: Offset,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val primary = if (isVerticalMode(pageTurnMode)) drag.y else drag.x
    val cross = if (isVerticalMode(pageTurnMode)) drag.x else drag.y
    if (abs(primary) > SwipeThresholdPx && abs(primary) > abs(cross)) {
        if (primary < 0f) onNextPage() else onPreviousPage()
    }
}

private fun PointerInputScope.handleTap(
    position: Offset,
    isAutoScrollEnabled: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleControls: () -> Unit,
) {
    when {
        isAutoScrollEnabled -> onToggleControls()
        position.x < size.width * PreviousTapZoneRatio -> onPreviousPage()
        position.x > size.width * NextTapZoneRatio -> onNextPage()
        else -> onToggleControls()
    }
}

private fun isVerticalMode(pageTurnMode: PageTurnMode): Boolean =
    pageTurnMode == PageTurnMode.VERTICAL || pageTurnMode == PageTurnMode.CONTINUOUS

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

internal fun readerPagerRequestedPage(
    currentPage: Int,
    pageCount: Int,
    pageStep: Int,
    movement: ReaderPageMovement,
): Int? = readerPagerAdjacentPage(currentPage, pageCount, pageStep, movement.pageOffset)

internal data class ReaderPageMoveRequest(
    val id: Int,
    val movement: ReaderPageMovement,
)

internal enum class ReaderPageMovement(val pageOffset: Int) {
    Previous(-1),
    Next(1),
}

internal fun readerScrollPageAnchors(pageCount: Int, pageStep: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    val step = pageStep.coerceAtLeast(1)
    return (0 until pageCount step step).toList()
}

internal fun readerScrollAnchorIndex(page: Int, anchors: List<Int>): Int {
    if (anchors.isEmpty()) return 0
    return anchors.indexOfLast { anchor -> anchor <= page }
        .takeIf { it >= 0 }
        ?: 0
}
private const val TouchSlopPx = 8f
private const val SwipeThresholdPx = 72f
private const val PreviousTapZoneRatio = 0.28f
private const val NextTapZoneRatio = 0.72f

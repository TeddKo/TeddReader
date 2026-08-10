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
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.feature.reader.impl.autoScrollDistancePx
import com.tedd.teddreader.feature.reader.impl.autoScrollLineDelayMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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
    onToggleControls: () -> Unit,
    isAutoScrollEnabled: Boolean,
    effectiveAutoScrollMode: AutoScrollMode,
    autoScrollSpeed: Float,
    autoScrollLineHeightPx: Float,
    autoScrollDensity: Float,
    onAutoScrollStop: () -> Unit,
    onAutoScrollAdvance: () -> Unit,
    onMovieTransitionProgressChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
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
                onPreviousPage = onPreviousPage,
                onNextPage = onNextPage,
                onToggleControls = onToggleControls,
                isAutoScrollEnabled = isAutoScrollEnabled,
                autoScrollMode = effectiveAutoScrollMode,
                autoScrollSpeed = autoScrollSpeed,
                autoScrollLineHeightPx = autoScrollLineHeightPx,
                autoScrollDensity = autoScrollDensity,
                onAutoScrollStop = onAutoScrollStop,
                onAutoScrollAdvance = onAutoScrollAdvance,
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
                isAutoScrollEnabled = isAutoScrollEnabled,
                autoScrollMode = effectiveAutoScrollMode,
                autoScrollSpeed = autoScrollSpeed,
                autoScrollLineHeightPx = autoScrollLineHeightPx,
                autoScrollDensity = autoScrollDensity,
                onAutoScrollStop = onAutoScrollStop,
                onMovieTransitionProgressChanged = onMovieTransitionProgressChanged,
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
                modifier = modifier,
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
            .pointerInput(pageTurnMode, onPreviousPage, onNextPage, onToggleControls) {
                detectReaderGesture(
                    pageTurnMode = pageTurnMode,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage,
                    onToggleControls = onToggleControls,
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
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleControls: () -> Unit,
    isAutoScrollEnabled: Boolean,
    autoScrollMode: AutoScrollMode,
    autoScrollSpeed: Float,
    autoScrollLineHeightPx: Float,
    autoScrollDensity: Float,
    onAutoScrollStop: () -> Unit,
    onAutoScrollAdvance: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (page: Int) -> Unit,
) {
    val previousPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, -1)
    val nextPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, 1)
    val pageOffsets = readerScrollPageOffsets(
        hasPreviousPage = previousPage != null,
        hasNextPage = nextPage != null,
    )
    val currentIndex = readerScrollCurrentIndex(hasPreviousPage = previousPage != null)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pageKey, pageCount, pageStep, currentIndex) {
        listState.scrollToItem(currentIndex)
    }
    LaunchedEffect(pageMoveRequest?.id) {
        val request = pageMoveRequest ?: return@LaunchedEffect
        try {
            val targetIndex = when (request.movement) {
                ReaderPageMovement.Previous -> pageOffsets.indexOf(-1).takeIf { it >= 0 }
                ReaderPageMovement.Next -> pageOffsets.indexOf(1).takeIf { it >= 0 }
            }
            if (targetIndex != null) listState.animateScrollToItem(targetIndex)
        } finally {
            onPageMoveRequestConsumed(request.id)
        }
    }
    LaunchedEffect(listState, pageKey, pageCount, pageStep, isAutoScrollEnabled, autoScrollMode) {
        snapshotFlow {
            Triple(
                pageOffsets.getOrNull(listState.firstVisibleItemIndex),
                listState.canScrollBackward,
                listState.isScrollInProgress,
            )
        }
            .filter { (_, _, isScrollInProgress) -> !isScrollInProgress }
            .map { (pageOffset, canScrollBackward, _) ->
                readerScrollSettledPageOffset(
                    pageOffset = pageOffset,
                    canScrollBackward = canScrollBackward,
                )
            }
            .distinctUntilChanged()
            .collect { pageOffset ->
                when (pageOffset) {
                    -1 -> if (previousPage != null) onPreviousPage()
                    1 -> when {
                        nextPage == null -> onAutoScrollStop()
                        isAutoScrollEnabled && autoScrollMode != AutoScrollMode.PAGE -> onAutoScrollAdvance()
                        else -> onNextPage()
                    }
                }
            }
    }
    LaunchedEffect(
        listState,
        pageKey,
        pageCount,
        pageStep,
        isAutoScrollEnabled,
        autoScrollMode,
        autoScrollSpeed,
        autoScrollLineHeightPx,
        autoScrollDensity,
    ) {
        if (!isAutoScrollEnabled || autoScrollMode == AutoScrollMode.PAGE) return@LaunchedEffect
        if (nextPage == null && !listState.canScrollForward) {
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
                        if (nextPage == null && !listState.canScrollForward) {
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
                    if (nextPage == null && !listState.canScrollForward) {
                        onAutoScrollStop()
                        break
                    }
                    listState.animateScrollBy(lineHeightPx)
                    if (nextPage == null && !listState.canScrollForward) {
                        onAutoScrollStop()
                        break
                    }
                    delay(delayMillis)
                }
            }

            AutoScrollMode.PAGE -> Unit
        }
    }

    val tapModifier = Modifier.pointerInput(pageTurnMode, onToggleControls, previousPage, nextPage) {
        detectTapGestures { position ->
            val primary = if (isVerticalMode(pageTurnMode)) position.y else position.x
            val extent = if (isVerticalMode(pageTurnMode)) size.height else size.width
            when {
                primary < extent * PreviousTapZoneRatio -> {
                    if (previousPage != null) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(pageOffsets.indexOf(-1))
                        }
                    }
                }

                primary > extent * NextTapZoneRatio -> {
                    if (nextPage != null) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(pageOffsets.indexOf(1))
                        }
                    }
                }

                else -> onToggleControls()
            }
        }
    }

    if (isVerticalMode(pageTurnMode)) {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize().then(tapModifier),
            overscrollEffect = null,
        ) {
            items(pageOffsets) { pageOffset ->
                Box(modifier = Modifier.fillParentMaxSize()) {
                    val documentPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, pageOffset)
                    if (documentPage != null) content(documentPage)
                }
            }
        }
    } else {
        LazyRow(
            state = listState,
            modifier = modifier.fillMaxSize().then(tapModifier),
            overscrollEffect = null,
        ) {
            items(pageOffsets) { pageOffset ->
                Box(modifier = Modifier.fillParentMaxSize()) {
                    val documentPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, pageOffset)
                    if (documentPage != null) content(documentPage)
                }
            }
        }
    }
}

private suspend fun PointerInputScope.detectReaderGesture(
    pageTurnMode: PageTurnMode,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleControls: () -> Unit,
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
            handleSwipe(pageTurnMode, drag, onPreviousPage, onNextPage)
        } else {
            handleTap(down.position, onPreviousPage, onNextPage, onToggleControls)
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
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleControls: () -> Unit,
) {
    when {
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

internal fun readerScrollPageOffsets(hasPreviousPage: Boolean, hasNextPage: Boolean): List<Int> = buildList {
    if (hasPreviousPage) add(-1)
    add(0)
    if (hasNextPage) add(1)
}

internal fun readerScrollCurrentIndex(hasPreviousPage: Boolean): Int = if (hasPreviousPage) 1 else 0

internal fun readerScrollSettledPageOffset(pageOffset: Int?, canScrollBackward: Boolean): Int? = when (pageOffset) {
    -1 -> if (canScrollBackward) null else -1
    1 -> 1
    else -> null
}
private const val TouchSlopPx = 8f
private const val SwipeThresholdPx = 72f
private const val PreviousTapZoneRatio = 0.28f
private const val NextTapZoneRatio = 0.72f

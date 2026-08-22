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
 * Dispatches to the pager implementation that matches [pageAnimation]: continuous scroll
 * ([ReaderScrollPager]), a Foundation `HorizontalPager`/`VerticalPager` with a custom transition
 * (`FoundationEffectPager`) for slide/fluid/circle-reveal/movie-carousel/page-flip, the pagecurl
 * state machine (`FoundationCurlPager`) for curl styles, or the plain cross-fade/no-animation path
 * implemented directly below for the remaining [PageAnimation] values.
 *
 * Whichever path handles the current [pageAnimation] owns its own gestures, page-move-request
 * consumption, and auto-scroll driving; the plain fallback path below (`fadeIn`/`fadeOut` via
 * [AnimatedContent]) is what backs [PageAnimation.NONE] and [PageAnimation.FADE].
 *
 * @param pageKey The current page index; the value this pager animates toward/from.
 * @param pageCount The total number of pages known so far.
 * @param pageStep How many pages one turn advances — 1 for a single pane, 2 for a two-page spread.
 * @param pageTurnMode Whether pages turn along the horizontal or vertical axis.
 * @param pageAnimation The turn animation to render, which selects the delegate implementation.
 * @param canRequestNextPage Whether a text document at its known end should still forward a next
 *   request to the view model while pagination remains incomplete.
 * @param pageMoveRequest A pending programmatic page-move request (from the bottom bar's
 *   previous/next buttons), or null when none is outstanding.
 * @param onPageMoveRequestConsumed Called with [pageMoveRequest]'s id once it has been acted on
 *   or found to have nowhere to go.
 * @param onPreviousPage Called to move one step toward the start of the document.
 * @param onNextPage Called to move one step toward the end of the document.
 * @param onPageSelected Called with a page index chosen directly (used by [ReaderScrollPager]'s
 *   anchor reporting).
 * @param onToggleControls Called when a tap lands outside both turn zones, or when any tap
 *   happens while auto-scroll is enabled.
 * @param onDoubleTap Called with the tap position on a double-tap; null disables double-tap
 *   handling (used for zoom in visual/PDF modes).
 * @param isAutoScrollEnabled Whether auto-scroll is currently driving page turns.
 * @param effectiveAutoScrollMode The auto-scroll mode to honor, already resolved for whether the
 *   content is text or visual (see `readerEffectiveAutoScrollMode`).
 * @param autoScrollSpeed The configured auto-scroll speed.
 * @param autoScrollLineHeightPx The current style's line height in pixels, used by line-mode
 *   auto-scroll.
 * @param autoScrollDensity The display density, used to convert auto-scroll speed into pixels.
 * @param onAutoScrollStop Called when auto-scroll reaches the end of the document and must stop.
 * @param onMovieTransitionProgressChanged Called with the movie-carousel transition's progress,
 *   for callers that dim content behind the animation.
 * @param modifier The modifier applied to the pager's root.
 * @param paneCount How many page panes are shown side by side (2 for a spread, 1 otherwise).
 * @param spreadGutter The gap drawn between panes in a spread.
 * @param spreadLeftWeight The fraction of a spread's width given to its left pane.
 * @param spreadModifier The modifier applied to a spread's row, forwarded only to the curl pager.
 * @param paneContent Renders one pane of a spread with its own modifier; null for a single pane
 *   or for delegate pagers that do not support spreads.
 * @param content Renders the page at the given index for non-spread delegates.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun ReaderPager(
    pageKey: Int,
    pageCount: Int,
    pageStep: Int = 1,
    pageTurnMode: PageTurnMode,
    pageAnimation: PageAnimation,
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
        PageAnimation.CURL_PAGER -> {
            FoundationCurlPager(
                pageKey = pageKey,
                pageCount = pageCount,
                pageStep = pageStep,
                pageTurnMode = pageTurnMode,
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
 * The continuous-scroll pager backing [PageAnimation.SCROLL]: a `LazyColumn`/`LazyRow` of page
 * anchors (see [readerScrollPageAnchors]) rather than a discrete `HorizontalPager`/`VerticalPager`,
 * so a spread's two panes scroll together as one continuously-flowing list item.
 *
 * The list's own scroll position is the source of truth for the current page while the user is
 * scrolling: [onPageSelected] is driven from `listState.firstVisibleItemIndex`, and [pageKey]
 * changes (a repagination, or a page move from elsewhere) are synced back onto the list only
 * after any fling in progress settles — waiting rather than abandoning the sync, because
 * repagination can land mid-scroll, and giving up here would leave the list parked on an item
 * index that belonged to the old pagination instead of the new one.
 *
 * The anchor-reporting effect's `.drop(1)` exists for the same reason from the other direction:
 * `snapshotFlow { listState.firstVisibleItemIndex }` replays its current value immediately on
 * every restart, and when [anchors] itself just changed (a repagination), that replayed value is
 * the index the *previous* pagination left the list parked on. Reporting it through
 * [onPageSelected] would resolve an old index against the new anchors and send the reader to
 * whatever page happens to sit there — page one, in the worst case — so the first, stale value is
 * dropped and only a real scroll after that is ever reported.
 *
 * @param pageKey The current page index to keep the list scrolled to.
 * @param pageCount The total number of pages known so far.
 * @param pageStep How many pages one scroll anchor advances — 1 for a single pane, 2 for a spread.
 * @param pageTurnMode Whether the list scrolls vertically or horizontally.
 * @param pageMoveRequest A pending programmatic page-move request, or null when none is
 *   outstanding.
 * @param onPageMoveRequestConsumed Called with [pageMoveRequest]'s id once it has been acted on.
 * @param onPageSelected Called with the page anchor the list has settled on after a scroll.
 * @param onToggleControls Called when a tap lands outside both turn zones, or during auto-scroll.
 * @param onDoubleTap Called with the tap position on a double-tap; null disables it.
 * @param isAutoScrollEnabled Whether auto-scroll is currently driving the list.
 * @param autoScrollMode The auto-scroll mode to honor.
 * @param autoScrollSpeed The configured auto-scroll speed.
 * @param autoScrollLineHeightPx The current style's line height in pixels, used by line-mode
 *   auto-scroll.
 * @param autoScrollDensity The display density, used to convert auto-scroll speed into pixels.
 * @param onAutoScrollStop Called when auto-scroll reaches the end of the document and must stop.
 * @param modifier The modifier applied to the list's root.
 * @param content Renders the page at a given anchor index.
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
 * Watches one gesture at a time for a swipe past [TouchSlopPx] and, once the gesture ends having
 * moved, turns it into a page change via [handleSwipe]. Used by the plain fade/no-animation pager
 * path, which has no pager widget of its own to detect drags for it.
 *
 * @param pageTurnMode Which axis (horizontal/vertical) a swipe is measured along.
 * @param isAutoScrollEnabled Whether auto-scroll is running; a swipe is ignored entirely while it
 *   is, since a manual turn would race the automatic one.
 * @param onPreviousPage Called when the swipe resolves to a previous-page turn.
 * @param onNextPage Called when the swipe resolves to a next-page turn.
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
 * Resolves a completed drag into a page turn, requiring the drag to clear [SwipeThresholdPx]
 * along the turn axis and to be more along that axis than across it, so a mostly-vertical drag on
 * a horizontal pager (or vice versa) is not misread as a turn.
 *
 * @param pageTurnMode Which axis the drag is measured along.
 * @param drag The total drag offset accumulated over the gesture.
 * @param onPreviousPage Called when the drag resolves to a previous-page turn.
 * @param onNextPage Called when the drag resolves to a next-page turn.
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
 * Resolves a tap on the plain fade/no-animation pager into a previous-page turn, a next-page
 * turn, or a controls toggle, based on which horizontal zone (see [PreviousTapZoneRatio],
 * [NextTapZoneRatio]) it landed in.
 *
 * @param position The tap position within the pager.
 * @param isAutoScrollEnabled Whether auto-scroll is running; every tap toggles controls while it
 *   is, rather than turning a page and racing the automatic turn.
 * @param onPreviousPage Called when the tap lands in the previous-page zone.
 * @param onNextPage Called when the tap lands in the next-page zone.
 * @param onToggleControls Called when the tap lands in the middle zone, or while auto-scroll is
 *   enabled.
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
 * Whether [pageTurnMode] lays pages out along the vertical axis (both the discrete vertical mode
 * and the always-vertical continuous-scroll mode) rather than horizontally.
 *
 * @param pageTurnMode The page-turn mode to check.
 * @return True for [PageTurnMode.VERTICAL] or [PageTurnMode.CONTINUOUS].
 */
private fun isVerticalMode(pageTurnMode: PageTurnMode): Boolean =
    pageTurnMode == PageTurnMode.VERTICAL || pageTurnMode == PageTurnMode.CONTINUOUS

/**
 * The page reached by moving [pageOffset] page-steps of [pageStep] pages away from [currentPage],
 * bounded to `[0, pageCount)`, or null when there is no such page.
 *
 * Null is the load-bearing result here, not an edge case to special-case away: every caller
 * (`FoundationEffectPager`'s previous/next lookups, [readerPagerRequestedPage],
 * `foundationPagerTapAction`'s zone resolution) treats null as "no adjacent page in that
 * direction" and falls through to the same behavior a middle-zone tap gets — toggling the
 * controls — rather than doing nothing at all. A tap or drag must never be silently swallowed at
 * the start or end of a document, which is exactly the bug (F16) a non-null-only signal used to
 * allow.
 *
 * @param currentPage The page to move from.
 * @param pageCount The total number of pages known so far.
 * @param pageStep How many pages one page-offset step covers — 1 for a single pane, 2 for a
 *   two-page spread.
 * @param pageOffset How many page-steps to move, negative toward the start, positive toward the
 *   end; 0 returns [currentPage] itself (bounds-checked).
 * @return The target page index, or null when [currentPage] is out of bounds or the move would
 *   land outside `[0, pageCount)`.
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
 * [readerPagerAdjacentPage] specialized to a [ReaderPageMovement] direction, used to resolve a
 * pending [ReaderPageMoveRequest] against pagination that is live when the move actually runs.
 *
 * @param currentPage The page to move from.
 * @param pageCount The total number of pages known so far.
 * @param pageStep How many pages one turn covers.
 * @param movement Which direction to move.
 * @return The target page index, or null when there is no page in that direction; see
 *   [readerPagerAdjacentPage] for what null means to callers.
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
 * A single programmatic page-turn request (from the bottom bar's previous/next buttons), carried
 * through a pager's own state until it is consumed.
 *
 * @property id A request identity distinct from [movement] alone, so a second identical request
 *   (e.g. tapping "next" twice before the first turn finishes) is not silently ignored as a
 *   no-op repeat.
 * @property movement Which direction to move.
 */
internal data class ReaderPageMoveRequest(
    val id: Int,
    val movement: ReaderPageMovement,
)

/**
 * The two directions a programmatic page move can request.
 *
 * @property pageOffset The signed page-step offset [movement] corresponds to, consumed directly
 *   by [readerPagerAdjacentPage]/[readerPagerRequestedPage].
 */
internal enum class ReaderPageMovement(val pageOffset: Int) {
    Previous(-1),
    Next(1),
}

/**
 * The page indices [ReaderScrollPager]'s list treats as scroll stops — every [pageStep]'th page,
 * starting at 0 — so a spread's two panes land on one shared list item instead of two.
 *
 * @param pageCount The total number of pages known so far.
 * @param pageStep How many pages one scroll anchor covers.
 * @return The ascending list of anchor page indices; empty when [pageCount] is 0.
 */
internal fun readerScrollPageAnchors(pageCount: Int, pageStep: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    val step = pageStep.coerceAtLeast(1)
    return (0 until pageCount step step).toList()
}

/**
 * The index into [anchors] that owns [page] — the last anchor at or before it — used to resolve a
 * page number into the list position [ReaderScrollPager] should be scrolled to.
 *
 * @param page The page to resolve.
 * @param anchors The ascending anchor list from [readerScrollPageAnchors].
 * @return The owning anchor's index, or 0 when [anchors] is empty or [page] precedes every
 *   anchor.
 */
internal fun readerScrollAnchorIndex(page: Int, anchors: List<Int>): Int {
    if (anchors.isEmpty()) return 0
    return anchors.indexOfLast { anchor -> anchor <= page }
        .takeIf { it >= 0 }
        ?: 0
}

/** Minimum drag distance, in pixels, before [detectReaderSwipe] treats a gesture as a swipe. */
private const val TouchSlopPx = 8f

/** Minimum drag distance, in pixels, along the turn axis before [handleSwipe] turns a page. */
private const val SwipeThresholdPx = 72f

/** The fraction of the pager's extent, from its start edge, that [handleTap] treats as "previous." */
private const val PreviousTapZoneRatio = 0.28f

/** The fraction of the pager's extent, from its start edge, beyond which [handleTap] treats a tap as "next." */
private const val NextTapZoneRatio = 0.72f

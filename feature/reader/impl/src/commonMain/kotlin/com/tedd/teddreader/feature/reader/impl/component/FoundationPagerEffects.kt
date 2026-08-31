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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The pager behind [PageAnimation.SLIDE]/[PageAnimation.SHEET_FLIP]/[PageAnimation.FLUID_PAGER]/
 * [PageAnimation.CIRCLE_REVEAL]/[PageAnimation.MOVIE_CAROUSEL]/[PageAnimation.PAGE_FLIP]: a
 * Foundation `HorizontalPager`/`VerticalPager` fixed to 3 slots (previous/current/next, see
 * [FoundationPagerPage]) that always sits at [FoundationCenterPage] between turns, with a custom
 * transition modifier per [pageAnimation] drawing the fold/reveal/carousel/flip effect over the
 * plain Foundation swipe.
 *
 * **Keeping the pager centred in sync with document state.** The pager's own [pagerState] tracks
 * a 0..2 slot index, while the document's actual page lives in [pageKey] one level up in
 * `ReaderViewModel`. A turn has to move both — the reader's page and the pager's slot back to
 * centre — but they cannot be written at the same instant: the move leaves through the view model
 * and comes back as new [pageKey] state on a later frame. Drawing the three slots' content
 * directly from [pageKey] would show the page being left at its already-settled scroll position
 * until the new [pageKey] caught up — the wrong text visible for a moment on every turn. The
 * `renderedPageKey` variable exists to prevent that: the three slots are drawn from
 * `renderedPageKey`, not [pageKey] directly, and `renderedPageKey` only moves on once [pageKey]'s
 * change and the pager's re-centring reach the screen in the same frame (see the
 * `LaunchedEffect(pageKey, pageCount, pageStep)` block below, which resets `renderedPageKey` and
 * re-centres the pager together).
 *
 * That same effect re-centres the pager with `pagerState.requestScrollToPage(FoundationCenterPage)`
 * rather than `scrollToPage`: `requestScrollToPage` moves the pager's own position at once and
 * leaves the layout to catch up on the next measure pass, so the snap-back and the `renderedPageKey`
 * update driving what the slots show both land in the same frame instead of one after the other.
 *
 * **Consuming a settled scroll exactly once.** The `LaunchedEffect(pagerState, pageKey,
 * renderedPageKey, pageCount, pageStep, isManualDragInProgress)` block watches for the pager
 * settling on slot 0 or 2 (a completed turn) and reports it upward via [onPreviousPage]/
 * [onNextPage]. It bails out whenever `pageKey != renderedPageKey`: two keys out of step means the
 * turn just reported is still on its way back as new [pageKey] state, the pager is still parked
 * off-centre waiting for it, and reporting that same resting slot a second time would move the
 * reader two pages for one turn. Once a settled slot is reported, only a turn with nowhere left to
 * go (no previous/next page) puts the pager back to centre itself — every other case is snapped
 * back by the `renderedPageKey`-driven effect above once the new key arrives.
 *
 * **Tap zones.** [tapModifier]'s `onTap` resolves the tap position into
 * [FoundationPagerTapAction.Previous]/[FoundationPagerTapAction.Next]/
 * [FoundationPagerTapAction.ToggleControls] via `foundationPagerTapAction`. A tap in the
 * previous/next zone with no adjacent page in that direction (the start or end of the book) falls
 * through to toggling the controls the same as the middle zone — a tap must never be silently
 * swallowed, which is exactly what used to happen on the last page before this fell through (F16).
 *
 * @param pageKey The current page index.
 * @param pageCount The total number of pages known so far.
 * @param pageStep How many pages one turn advances.
 * @param pageTurnMode Whether pages turn along the horizontal or vertical axis.
 * @param pageAnimation Which of the styles this pager supports is currently active; selects the
 *   transition modifier applied per slot.
 * @param canRequestNextPage Whether a text document at its known end should still forward a next
 *   request while pagination remains incomplete.
 * @param pageMoveRequest A pending programmatic page-move request, or null when none is
 *   outstanding.
 * @param onPageMoveRequestConsumed Called with [pageMoveRequest]'s id once it has been animated
 *   or found to have nowhere to go.
 * @param onPreviousPage Called once a turn toward the start of the document settles.
 * @param onNextPage Called once a turn toward the end of the document settles.
 * @param onToggleControls Called when a tap lands outside both turn zones, or during auto-scroll.
 * @param onDoubleTap Called with the tap position on a double-tap; null disables it.
 * @param isAutoScrollEnabled Whether auto-scroll is currently driving turns.
 * @param autoScrollMode The auto-scroll mode to honor.
 * @param autoScrollSpeed The configured auto-scroll speed.
 * @param autoScrollLineHeightPx The current style's line height in pixels, used by line-mode
 *   auto-scroll.
 * @param autoScrollDensity The display density, used to convert auto-scroll speed into pixels.
 * @param onAutoScrollStop Called when auto-scroll reaches the end of the document and must stop.
 * @param onMovieTransitionProgressChanged Called with the movie-carousel transition's progress.
 * @param paneCount How many page panes are shown side by side (2 for a spread, 1 otherwise);
 *   selects between [FoundationPageFlipLayout.WholePage] and
 *   [FoundationPageFlipLayout.SplitHalfFold] for the page-flip style.
 * @param modifier The modifier applied to the pager's root.
 * @param content Renders the page at the given index.
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
    val fluidEdge = remember { FoundationFluidEdge(FoundationFluidPointCount) }
    val fluidVersion = fluidEdge.version
    var gestureState by remember { mutableStateOf(FoundationPagerGestureState()) }
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
            gestureState = FoundationPagerGestureState()
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

        when (autoScrollMode) {
            AutoScrollMode.PIXEL -> {
                pagerState.scroll {
                    var lastFrameMillis = 0L
                    while (isActive) {
                        val frameMillis = withFrameMillis { it }
                        if (lastFrameMillis != 0L) {
                            val elapsedMillis = frameMillis - lastFrameMillis
                            val distancePx = autoScrollDistancePx(
                                speed = autoScrollSpeed,
                                density = autoScrollDensity,
                                elapsedMillis = elapsedMillis,
                            )
                            if (distancePx > 0f) {
                                val consumed = scrollBy(distancePx)
                                if (consumed == 0f && !pagerState.canScrollForward) {
                                    break
                                }
                            }
                        }
                        lastFrameMillis = frameMillis
                    }
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
                pagerState.scroll {
                    while (isActive) {
                        val consumed = scrollBy(lineHeightPx)
                        if (consumed == 0f && !pagerState.canScrollForward) {
                            break
                        }
                        delay(delayMillis)
                    }
                }
            }

            AutoScrollMode.PAGE -> Unit
        }
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

    val gestureModifier = if (isAutoScrollEnabled) {
        Modifier
    } else {
        Modifier.pointerInput(axis, previousPage != null, canGoForward) {
            var localGesture = FoundationPagerGestureState()
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressedChange = event.changes.firstOrNull { it.pressed }
                    if (pressedChange != null) {
                        val position = pressedChange.position
                        val primaryDelta = if (axis == FoundationPagerAxis.Horizontal) {
                            position.x - localGesture.start.x
                        } else {
                            position.y - localGesture.start.y
                        }
                        val blockDrag = localGesture.active && foundationPagerShouldBlockDrag(
                            primaryDelta = primaryDelta,
                            hasPreviousPage = previousPage != null,
                            hasNextPage = canGoForward,
                        )
                        if (blockDrag) {
                            event.changes.forEach { it.consume() }
                        }
                        localGesture = if (localGesture.active) {
                            val current = if (blockDrag) localGesture.start else position
                            localGesture.copy(current = current, last = current)
                        } else {
                            fluidEdge.reset()
                            FoundationPagerGestureState(
                                start = position,
                                current = position,
                                last = position,
                                active = true,
                                touched = true,
                            )
                        }
                        gestureState = localGesture
                    } else if (localGesture.active) {
                        localGesture = localGesture.copy(active = false)
                        gestureState = localGesture
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
                isManualDragInProgress = true
                manualDragDistancePx[0] = 0f
            },
            onDragStopped = { velocity ->
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
                coroutineScope.launch {
                    try {
                        pagerState.animateScrollToPage(
                            page = (FoundationCenterPage + targetOffset).coerceIn(0, FoundationPagerPageCount - 1),
                            animationSpec = settleAnimationSpec,
                        )
                    } finally {
                        isManualDragInProgress = false
                        manualDragDistancePx[0] = 0f
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

    /** Builds one slot's per-frame transition modifier, shared between the horizontal and vertical pager branches below so it is not written out twice. */
    fun pageModifier(pagerPage: Int): Modifier {
        val pageOffset = pagerState.foundationOffsetForPage(pagerPage)
        return Modifier
            .fillMaxSize()
            .foundationEffectPageModifier(
                pagerState = pagerState,
                pagerPage = pagerPage,
                axis = axis,
                pageAnimation = pageAnimation,
                pageOffset = pageOffset,
                gestureState = gestureState,
                fluidEdge = fluidEdge,
                fluidVersion = fluidVersion,
            )
    }

    val pagerModifier = modifier
        .fillMaxSize()
        .then(gestureModifier)
        .then(manualDragModifier)
        .then(tapModifier)

    if (axis == FoundationPagerAxis.Vertical) {
        VerticalPager(
            state = pagerState,
            modifier = pagerModifier,
            userScrollEnabled = false,
            beyondViewportPageCount = 1,
        ) { pagerPage ->
            val pageOffset = pagerState.foundationOffsetForPage(pagerPage)
            val incomingPage = when {
                pageOffset > 0f -> readerPagerDisplayedPage(renderedPageKey, nextPage, 1, canRequestNextPage)
                pageOffset < 0f -> previousPage
                else -> null
            }
            FoundationPageFlipAwareBox(
                pageAnimation = pageAnimation,
                axis = axis,
                pageOffset = pageOffset,
                pageFlipLayout = pageFlipLayout,
                isCurrentPage = pagerPage == FoundationCenterPage,
                modifier = pageModifier(pagerPage),
                incomingContent = incomingPage?.let { page -> { content(page) } },
            ) {
                val documentPage = readerPagerDisplayedPage(
                    currentPage = renderedPageKey,
                    adjacentPage = readerPagerAdjacentPage(
                        renderedPageKey,
                        pageCount,
                        pageStep,
                        pagerPage - FoundationCenterPage,
                    ),
                    pageOffset = pagerPage - FoundationCenterPage,
                    canRequestNextPage = canRequestNextPage,
                )
                if (documentPage != null) content(documentPage)
            }
        }
    } else {
        HorizontalPager(
            state = pagerState,
            modifier = pagerModifier,
            userScrollEnabled = false,
            beyondViewportPageCount = 1,
        ) { pagerPage ->
            val pageOffset = pagerState.foundationOffsetForPage(pagerPage)
            val incomingPage = when {
                pageOffset > 0f -> readerPagerDisplayedPage(renderedPageKey, nextPage, 1, canRequestNextPage)
                pageOffset < 0f -> previousPage
                else -> null
            }
            FoundationPageFlipAwareBox(
                pageAnimation = pageAnimation,
                axis = axis,
                pageOffset = pageOffset,
                pageFlipLayout = pageFlipLayout,
                isCurrentPage = pagerPage == FoundationCenterPage,
                modifier = pageModifier(pagerPage),
                incomingContent = incomingPage?.let { page -> { content(page) } },
            ) {
                val documentPage = readerPagerDisplayedPage(
                    currentPage = renderedPageKey,
                    adjacentPage = readerPagerAdjacentPage(
                        renderedPageKey,
                        pageCount,
                        pageStep,
                        pagerPage - FoundationCenterPage,
                    ),
                    pageOffset = pagerPage - FoundationCenterPage,
                    canRequestNextPage = canRequestNextPage,
                )
                if (documentPage != null) content(documentPage)
            }
        }
    }
}

/**
 * The pager behind [PageAnimation.BOOK_CURL]/[PageAnimation.CURL_PAGER]: a thin pass-through onto
 * [FoundationPagerCurlReferenceImpl], which owns the actual pagecurl gesture and rendering state
 * machine. This wrapper exists only so [ReaderPager] can dispatch on [PageAnimation] the same way
 * it does for [FoundationEffectPager], without every caller needing to know the curl
 * implementation's name.
 *
 * @param pageKey The current page index.
 * @param pageCount The total number of pages known so far.
 * @param pageStep How many pages one turn advances.
 * @param pageTurnMode Whether pages turn along the horizontal or vertical axis.
 * @param style Whether the existing curl renderer uses its standard appearance or the stronger
 *   front/back/rim lighting of the 3D Curl option.
 * @param canRequestNextPage Whether a text document at its known end should still forward a next
 *   request while pagination remains incomplete.
 * @param pageMoveRequest A pending programmatic page-move request, or null when none is
 *   outstanding.
 * @param onPageMoveRequestConsumed Called with [pageMoveRequest]'s id once it has been animated
 *   or found to have nowhere to go.
 * @param onPreviousPage Called once a backward curl completes.
 * @param onNextPage Called once a forward curl completes.
 * @param onToggleControls Called when a tap lands outside both turn zones, or during auto-scroll.
 * @param onDoubleTap Called with the tap position on a double-tap; null disables it.
 * @param isAutoScrollEnabled Whether auto-scroll is currently driving turns.
 * @param autoScrollMode The auto-scroll mode to honor.
 * @param autoScrollSpeed The configured auto-scroll speed.
 * @param onAutoScrollStop Called when auto-scroll reaches the end of the document and must stop.
 * @param modifier The modifier applied to the pager's root.
 * @param paneCount How many page panes are shown side by side (2 for a spread, 1 otherwise).
 * @param spreadGutter The gap drawn between panes in a spread.
 * @param spreadLeftWeight The fraction of a spread's width given to its left pane.
 * @param spreadModifier The modifier applied to a spread's row.
 * @param paneContent Renders one pane of a spread with its own modifier; null for a single pane.
 * @param content Renders the page at the given index for the single-pane case.
 */
@Composable
internal fun FoundationCurlPager(
    pageKey: Int,
    pageCount: Int,
    pageStep: Int,
    pageTurnMode: PageTurnMode,
    style: FoundationReferenceCurlStyle,
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
 * Wraps a pager slot's content in the page-flip fold box when [pageAnimation] is
 * [PageAnimation.PAGE_FLIP] and this is the current-page slot, otherwise draws [content] plain.
 * Only the current slot folds because the neighbour slots are what the fold reveals underneath —
 * folding them too would double the effect.
 *
 * @param pageAnimation The active page-turn animation; only [PageAnimation.PAGE_FLIP] triggers
 *   the fold.
 * @param axis Whether the fold turns along the horizontal or vertical axis.
 * @param pageOffset This slot's signed offset from the pager's settled position, in `[-1, 1]`.
 * @param pageFlipLayout Whether the fold is a whole-page turn or a two-pane split-half fold.
 * @param isCurrentPage Whether this slot is the pager's current-page slot.
 * @param modifier The modifier applied to the box.
 * @param incomingContent The neighbour page's content revealed as the fold progresses, used only
 *   by [FoundationPageFlipLayout.SplitHalfFold]; null when there is no such neighbour.
 * @param content This slot's own page content.
 */
@Composable
private fun FoundationPageFlipAwareBox(
    pageAnimation: PageAnimation,
    axis: FoundationPagerAxis,
    pageOffset: Float,
    pageFlipLayout: FoundationPageFlipLayout,
    isCurrentPage: Boolean,
    modifier: Modifier,
    incomingContent: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (pageAnimation == PageAnimation.PAGE_FLIP && isCurrentPage) {
        when (pageFlipLayout) {
            FoundationPageFlipLayout.WholePage -> {
                FoundationWholePageFlipBox(
                    axis = axis,
                    pageOffset = pageOffset,
                    modifier = modifier,
                    incomingContent = incomingContent,
                    content = content,
                )
            }
            FoundationPageFlipLayout.SplitHalfFold -> {
                FoundationSpreadPageFlipBox(
                    axis = axis,
                    pageOffset = pageOffset,
                    modifier = modifier,
                    incomingContent = incomingContent,
                    content = content,
                )
            }
        }
    } else {
        Box(modifier = modifier) {
            content()
        }
    }
}

/**
 * The two-pane split-half fold: one half of the spread flips like a page in a real open book,
 * hinged along the spine, while the other half stays flat.
 *
 * The half the leaf lands on is seated with the outgoing (currently visible) page underneath it
 * for the whole fold, and the incoming neighbour page is drawn on top of that same half only once
 * the leaf's own back face has rotated far enough to cover it (`spec.showIncoming`). Seating the
 * incoming page there unconditionally instead made that half swap to the new page the moment a
 * drag started, which read as the far page changing before the turn instead of at the end of it.
 * The half the leaf lifts off deliberately gets no seat of its own: the arriving pager page lies
 * directly underneath it already, and uncovering that is what a real book shows there.
 *
 * @param axis Whether the fold turns along the horizontal or vertical axis.
 * @param pageOffset This slot's signed offset from the pager's settled position, in `[-1, 1]`.
 * @param modifier The modifier applied to the box.
 * @param incomingContent The neighbour page's content, revealed as the fold progresses; a null or
 *   zero [pageOffset] draws [content] plain with no fold.
 * @param content The current page's content.
 */
@Composable
private fun FoundationSpreadPageFlipBox(
    axis: FoundationPagerAxis,
    pageOffset: Float,
    modifier: Modifier = Modifier,
    incomingContent: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (pageOffset == 0f || incomingContent == null) {
        Box(modifier = modifier) { content() }
        return
    }
    val spec = foundationSpreadPageFlipSpec(axis, pageOffset)
    val lighting = foundationPageFlipLightingSpec(pageOffset)
    Box(modifier = modifier) {
        FoundationPageFlipHalfBox(
            half = spec.incomingHalf,
            spec = FoundationPageFlipHalfSpec(0f, 0f),
            modifier = Modifier.foundationPageFlipProjectedShadow(
                axis = axis,
                pageOffset = pageOffset,
                layout = FoundationPageFlipLayout.SplitHalfFold,
            ),
            surface = FoundationPageFlipSurface.Back,
            lighting = lighting,
            content = content,
        )
        if (spec.showOutgoing) {
            FoundationPageFlipHalfBox(
                half = spec.outgoingHalf,
                spec = spec.outgoing,
                surface = FoundationPageFlipSurface.Front,
                lighting = lighting,
                content = content,
            )
        }
        if (spec.showIncoming) {
            FoundationPageFlipHalfBox(
                half = spec.incomingHalf,
                spec = spec.incoming,
                surface = FoundationPageFlipSurface.Back,
                lighting = lighting,
                content = incomingContent,
            )
        }
    }
}

/**
 * The single-pane whole-page fold: the entire page rotates about its outer edge like a stiff
 * sheet, rather than splitting into two hinged halves the way [FoundationSpreadPageFlipBox] does.
 *
 * @param axis Whether the fold turns along the horizontal or vertical axis.
 * @param pageOffset This slot's signed offset from the pager's settled position, in `[-1, 1]`,
 *   which drives the rotation and pivot corner via [foundationWholePageFlipSpec].
 * @param modifier The modifier applied to the box.
 * @param incomingContent The next/previous page seated below the rotating front face; null while
 *   settled or when no neighbour exists.
 * @param content The outgoing page drawn on the rotating front face.
 */
@Composable
private fun FoundationWholePageFlipBox(
    axis: FoundationPagerAxis,
    pageOffset: Float,
    modifier: Modifier = Modifier,
    incomingContent: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (pageOffset == 0f) {
        Box(modifier = modifier) { content() }
        return
    }
    val transform = foundationWholePageFlipSpec(axis = axis, pageOffset = pageOffset)
    val lighting = foundationPageFlipLightingSpec(pageOffset)
    Box(modifier = modifier) {
        if (incomingContent != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .foundationPageFlipProjectedShadow(
                        axis = axis,
                        pageOffset = pageOffset,
                        layout = FoundationPageFlipLayout.WholePage,
                    )
                    .foundationPageFlipSurfaceLighting(
                        axis = axis,
                        lighting = lighting,
                        surface = FoundationPageFlipSurface.Back,
                    ),
            ) {
                incomingContent()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    cameraDistance = FoundationCameraDistance
                    transformOrigin = TransformOrigin(transform.transformOriginX, transform.transformOriginY)
                    rotationX = transform.rotationX
                    rotationY = transform.rotationY
                }
                .foundationPageFlipSurfaceLighting(
                    axis = axis,
                    lighting = lighting,
                    surface = FoundationPageFlipSurface.Front,
                ),
        ) {
            content()
        }
    }
}

/**
 * One quarter- or half-page seat used by the split-half fold: clips [content] to [half]'s
 * rectangle, rotates it by [spec], and applies [foundationPageFlipSurfaceLighting] for the visible
 * physical face.
 *
 * @param half Which quadrant/half of the page this box seats.
 * @param spec The rotation to apply.
 * @param modifier Additional drawing applied to this clipped half, such as its underlying cast shadow.
 * @param surface Whether this half exposes the physical front or back face.
 * @param lighting Unified lighting intensities for the current turn progress.
 * @param content The content seated in this half — the current page, the incoming neighbour, or
 *   the neighbour's back face, depending on the caller.
 */
@Composable
private fun FoundationPageFlipHalfBox(
    half: FoundationPageFlipHalf,
    spec: FoundationPageFlipHalfSpec,
    modifier: Modifier = Modifier,
    surface: FoundationPageFlipSurface,
    lighting: FoundationPageFlipLightingSpec,
    content: @Composable () -> Unit,
) {
    val axis = when (half) {
        FoundationPageFlipHalf.Left,
        FoundationPageFlipHalf.Right,
            -> FoundationPagerAxis.Horizontal
        FoundationPageFlipHalf.Top,
        FoundationPageFlipHalf.Bottom,
            -> FoundationPagerAxis.Vertical
    }
    val surfaceLighting = lighting.copy(
        side = when (half) {
            FoundationPageFlipHalf.Left,
            FoundationPageFlipHalf.Top,
                -> FoundationFluidSide.End
            FoundationPageFlipHalf.Right,
            FoundationPageFlipHalf.Bottom,
                -> FoundationFluidSide.Start
        },
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                shape = foundationPageFlipShape(half)
                clip = true
                cameraDistance = FoundationCameraDistance
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                rotationX = spec.rotationX
                rotationY = spec.rotationY
            }
            .foundationPageFlipSurfaceLighting(
                axis = axis,
                lighting = surfaceLighting,
                surface = surface,
            ),
    ) {
        content()
    }
}

/**
 * Lights one PAGE_FLIP leaf surface independently: the front receives the stronger diffuse shade,
 * the back receives a lighter paper shade, and both carry the same narrow turning-edge highlight.
 *
 * @receiver The modifier chain this surface lighting is appended to.
 * @param axis Whether the turning edge runs vertically or horizontally on screen.
 * @param lighting Unified lighting intensities for the current turn frame.
 * @param surface Whether this content is the physical front or back of the leaf.
 * @return A modifier drawing content with its face-specific gradient and rim highlight.
 */
private fun Modifier.foundationPageFlipSurfaceLighting(
    axis: FoundationPagerAxis,
    lighting: FoundationPageFlipLightingSpec,
    surface: FoundationPageFlipSurface,
): Modifier = drawWithCache {
    val side = lighting.side
    val shadeAlpha = when (surface) {
        FoundationPageFlipSurface.Front -> lighting.frontShadeAlpha
        FoundationPageFlipSurface.Back -> lighting.backShadeAlpha
    }
    if (side == null || (shadeAlpha <= 0f && lighting.rimAlpha <= 0f)) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    val extent = if (axis == FoundationPagerAxis.Horizontal) size.width else size.height
    val band = extent * FoundationPageFlipHingeWidthRatio
    val edge = if (side == FoundationFluidSide.Start) 0f else extent
    val inner = if (side == FoundationFluidSide.Start) band else extent - band
    val colors = if (side == FoundationFluidSide.Start) {
        listOf(Color.Black.copy(alpha = shadeAlpha), Color.Transparent)
    } else {
        listOf(Color.Transparent, Color.Black.copy(alpha = shadeAlpha))
    }
    val shadeBrush = if (axis == FoundationPagerAxis.Horizontal) {
        Brush.horizontalGradient(colors, startX = min(edge, inner), endX = max(edge, inner))
    } else {
        Brush.verticalGradient(colors, startY = min(edge, inner), endY = max(edge, inner))
    }

    onDrawWithContent {
        drawContent()
        if (surface == FoundationPageFlipSurface.Back) {
            drawRect(Color.White.copy(alpha = lighting.rimAlpha * FoundationPageFlipBackLightRatio))
        }
        if (axis == FoundationPagerAxis.Horizontal) {
            val left = min(edge, inner)
            drawRect(
                brush = shadeBrush,
                topLeft = Offset(left, 0f),
                size = Size(abs(edge - inner), size.height),
            )
            drawRect(
                color = Color.White.copy(alpha = lighting.rimAlpha),
                topLeft = Offset(
                    x = if (side == FoundationFluidSide.Start) 0f else size.width - FoundationPageFlipRimWidth,
                    y = 0f,
                ),
                size = Size(FoundationPageFlipRimWidth, size.height),
            )
        } else {
            val top = min(edge, inner)
            drawRect(
                brush = shadeBrush,
                topLeft = Offset(0f, top),
                size = Size(size.width, abs(edge - inner)),
            )
            drawRect(
                color = Color.White.copy(alpha = lighting.rimAlpha),
                topLeft = Offset(
                    x = 0f,
                    y = if (side == FoundationFluidSide.Start) 0f else size.height - FoundationPageFlipRimWidth,
                ),
                size = Size(size.width, FoundationPageFlipRimWidth),
            )
        }
    }
}

/**
 * Builds the per-slot transition modifier for [pagerPage], dispatching on [pageAnimation] to the
 * fluid-reveal, circle-reveal, movie-carousel, or page-flip transform/shadow/z-index combination;
 * any other animation (handled entirely by [AnimatedContent]/plain Foundation swipe elsewhere)
 * gets no extra modifier here.
 *
 * @receiver The modifier chain this transition is appended to.
 * @param pagerState The Foundation pager whose scroll progress drives the transition.
 * @param pagerPage This slot's index within [pagerState] (0, 1, or 2).
 * @param axis Whether the pager turns along the horizontal or vertical axis.
 * @param pageAnimation Which transition style to apply.
 * @param pageOffset This slot's signed offset from the pager's settled position.
 * @param gestureState The manual drag/touch state driving fluid- and circle-reveal geometry.
 * @param fluidEdge The shared spring-animated edge shape for the fluid-reveal style.
 * @param fluidVersion A change counter for [fluidEdge], read (but not directly used) inside
 *   `drawWithCache` blocks so they invalidate whenever the mutable edge shape changes.
 * @return A modifier applying this slot's transform, shadow, and z-index for [pageAnimation].
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.foundationEffectPageModifier(
    pagerState: PagerState,
    pagerPage: Int,
    axis: FoundationPagerAxis,
    pageAnimation: PageAnimation,
    pageOffset: Float,
    gestureState: FoundationPagerGestureState,
    fluidEdge: FoundationFluidEdge,
    fluidVersion: Int,
): Modifier {
    val page = FoundationPagerPage.fromPagerPage(pagerPage)
    val previousProgress = pagerState.foundationAdjacentProgress(FoundationPreviousPage)
    val nextProgress = pagerState.foundationAdjacentProgress(FoundationNextPage)
    val activeTurn = foundationActivePageTurn(
        axis = axis,
        gestureState = gestureState,
        previousProgress = previousProgress,
        nextProgress = nextProgress,
    )
    val activeSide = activeTurn.side
    val activeProgress = activeTurn.progress

    val cancelTranslation = Modifier.graphicsLayer {
        if (axis == FoundationPagerAxis.Horizontal) {
            translationX = size.width * pageOffset
        } else {
            translationY = size.height * pageOffset
        }
    }

    return when (pageAnimation) {
        PageAnimation.FLUID_PAGER -> cancelTranslation
            .zIndex(foundationRevealZIndex(page, activeSide, activeProgress))
            .then(
                foundationRevealModifier(
                    page = page,
                    axis = axis,
                    activeSide = activeSide,
                    progress = activeProgress,
                    gestureState = gestureState,
                    style = FoundationRevealStyle.Fluid,
                    fluidEdge = fluidEdge,
                    fluidVersion = fluidVersion,
                ),
            )
            .then(foundationFluidShadow(page, axis, activeSide, activeProgress, fluidEdge, fluidVersion))

        PageAnimation.CIRCLE_REVEAL -> cancelTranslation
            .zIndex(foundationRevealZIndex(page, activeSide, activeProgress))
            .then(
                foundationRevealModifier(
                    page = page,
                    axis = axis,
                    activeSide = activeSide,
                    progress = activeProgress,
                    gestureState = gestureState,
                    style = FoundationRevealStyle.Circle,
                ),
            )
            .then(foundationCircleRevealShadow(page, axis, activeSide, activeProgress, gestureState))

        PageAnimation.MOVIE_CAROUSEL -> Modifier
            .zIndex(foundationMovieZIndex(page, activeSide, activeProgress))
            .foundationMovieCarouselLayer(axis, page, pageOffset)
            .foundationMovieCarouselShadow(axis, page, pageOffset)

        PageAnimation.PAGE_FLIP -> cancelTranslation
            .zIndex(foundationPageFlipZIndex(page, pageOffset))

        else -> Modifier
    }
}

/**
 * The cast + contact shadow drawn along the fluid-reveal edge onto whichever neighbour is being
 * revealed, or no modifier at all for the current page or an inactive/complete turn.
 *
 * @param page Which pager slot this shadow would be drawn on.
 * @param axis Whether the turn runs along the horizontal or vertical axis.
 * @param activeSide Which side (start/end) the active turn is revealing from.
 * @param progress How far the active turn has progressed, in `[0, 1]`.
 * @param fluidEdge The shared spring-animated edge shape the shadow traces.
 * @param fluidVersion A change counter read inside the `drawWithCache` block so it invalidates
 *   whenever [fluidEdge]'s mutable shape changes.
 * @return A modifier drawing the shadow, or [Modifier] unchanged when this slot has no shadow to
 *   show.
 */
private fun foundationFluidShadow(
    page: FoundationPagerPage,
    axis: FoundationPagerAxis,
    activeSide: FoundationFluidSide,
    progress: Float,
    fluidEdge: FoundationFluidEdge,
    fluidVersion: Int,
): Modifier {
    if (page == FoundationPagerPage.Current) return Modifier
    if (page.side != activeSide || progress <= 0f) return Modifier
    return Modifier.drawWithCache {
        @Suppress("UNUSED_VARIABLE")
        val version = fluidVersion
        val clampedProgress = progress.coerceIn(0f, 1f)
        val castAlpha = FoundationRevealShadowAlpha * sin(clampedProgress * PI.toFloat())
        val contactAlpha = (castAlpha * 1.25f).coerceAtMost(0.32f)
        if (castAlpha <= 0f) {
            return@drawWithCache onDrawWithContent { drawContent() }
        }

        val sizeModel = FoundationPagerSize(size.width, size.height)
        val castPath = buildFoundationFluidShadowPolygon(
            size = sizeModel,
            axis = axis,
            side = activeSide,
            edge = fluidEdge,
            width = FoundationRevealShadowWidth,
        ).toPath()
        val contactPath = buildFoundationFluidShadowPolygon(
            size = sizeModel,
            axis = axis,
            side = activeSide,
            edge = fluidEdge,
            width = FoundationRevealContactShadowWidth,
        ).toPath()

        onDrawWithContent {
            drawContent()
            drawPath(castPath, Color.Black.copy(alpha = castAlpha * 0.55f))
            drawPath(contactPath, Color.Black.copy(alpha = contactAlpha))
        }
    }
}

/**
 * The shadow cast onto whichever neighbour the growing circle is revealing — the
 * [PageAnimation.CIRCLE_REVEAL] counterpart of [foundationFluidShadow], using the same
 * cast-plus-contact idea but following the circle's radius instead of the fluid edge's path.
 * Draws no modifier at all for the current page or an inactive/complete turn.
 *
 * @param page Which pager slot this shadow would be drawn on.
 * @param axis Whether the turn runs along the horizontal or vertical axis.
 * @param activeSide Which side (start/end) the active turn is revealing from.
 * @param progress How far the active turn has progressed, in `[0, 1]`.
 * @param gestureState The manual drag/touch state that anchors the circle's origin at the touch
 *   point.
 * @return A modifier drawing the shadow, or [Modifier] unchanged when this slot has no shadow to
 *   show.
 */
private fun foundationCircleRevealShadow(
    page: FoundationPagerPage,
    axis: FoundationPagerAxis,
    activeSide: FoundationFluidSide,
    progress: Float,
    gestureState: FoundationPagerGestureState,
): Modifier {
    if (page == FoundationPagerPage.Current) return Modifier
    if (page.side != activeSide || progress <= 0f) return Modifier
    return Modifier.drawWithCache {
        val touchCrossAxis = foundationTouchCrossAxis(
            axis = axis,
            size = FoundationPagerSize(size.width, size.height),
            touch = gestureState.touchPoint(),
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
 * Draws an edge shadow along the side of an incoming neighbour page in the
 * [PageAnimation.MOVIE_CAROUSEL] style, so a page sliding in from off-screen reads as passing
 * under the current page's edge instead of appearing flatly on top of it. Draws no shadow for the
 * current page, for a slot that is not currently incoming, or once the turn has settled.
 *
 * @receiver The modifier chain this shadow is appended to.
 * @param axis Whether the carousel moves along the horizontal or vertical axis.
 * @param page Which pager slot this shadow would be drawn on.
 * @param pageOffset This slot's signed offset from the pager's settled position, in `[-1, 1]`;
 *   its sign decides whether the slot is incoming and its magnitude how far the shadow has faded.
 * @return A modifier drawing the shadow, or the receiver unchanged when this slot has no shadow to
 *   show.
 */
private fun Modifier.foundationMovieCarouselShadow(
    axis: FoundationPagerAxis,
    page: FoundationPagerPage,
    pageOffset: Float,
): Modifier = drawWithCache {
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
    val shadowWidth = FoundationMovieShadowWidth

    onDrawWithContent {
        drawContent()
        if (axis == FoundationPagerAxis.Horizontal) {
            val left = if (shadowSide == FoundationFluidSide.Start) {
                0f
            } else {
                (size.width - shadowWidth).coerceAtLeast(0f)
            }
            val right = (left + shadowWidth).coerceAtMost(size.width)
            val colors = when (shadowSide) {
                FoundationFluidSide.Start -> listOf(Color.Black.copy(alpha = edgeAlpha), Color.Transparent)
                FoundationFluidSide.End -> listOf(Color.Transparent, Color.Black.copy(alpha = edgeAlpha))
            }
            drawRect(
                brush = Brush.horizontalGradient(colors = colors, startX = left, endX = right),
                topLeft = Offset(left, 0f),
                size = Size((right - left).coerceAtLeast(0f), size.height),
            )
        } else {
            val top = if (shadowSide == FoundationFluidSide.Start) {
                0f
            } else {
                (size.height - shadowWidth).coerceAtLeast(0f)
            }
            val bottom = (top + shadowWidth).coerceAtMost(size.height)
            val colors = when (shadowSide) {
                FoundationFluidSide.Start -> listOf(Color.Black.copy(alpha = edgeAlpha), Color.Transparent)
                FoundationFluidSide.End -> listOf(Color.Transparent, Color.Black.copy(alpha = edgeAlpha))
            }
            drawRect(
                brush = Brush.verticalGradient(colors = colors, startY = top, endY = bottom),
                topLeft = Offset(0f, top),
                size = Size(size.width, (bottom - top).coerceAtLeast(0f)),
            )
        }
    }
}

/**
 * Which edge the [PageAnimation.MOVIE_CAROUSEL] shadow in [Modifier.foundationMovieCarouselShadow]
 * hugs for [page]: a previous-page slot is covered from its trailing (end) edge as the current
 * page slides away from it, a next-page slot from its leading (start) edge as the current page
 * slides toward it, and the current page itself casts no such edge shadow.
 *
 * @param page Which pager slot the shadow side is being resolved for.
 * @return The side the shadow anchors to, or null for the current page.
 */
internal fun foundationMovieCarouselShadowSide(page: FoundationPagerPage): FoundationFluidSide? = when (page) {
    FoundationPagerPage.Previous -> FoundationFluidSide.End
    FoundationPagerPage.Next -> FoundationFluidSide.Start
    FoundationPagerPage.Current -> null
}

/**
 * The alpha of the darkening overlay drawn over a movie-carousel page as it recedes, peaking at
 * the midpoint of the turn (`progress == 0.5`) and fading to nothing at either end via a
 * half-sine curve — the same shape [foundationFluidShadow] and its siblings use for their cast
 * shadows.
 *
 * @param progress How far the turn has progressed, in `[0, 1]`.
 * @return The overlay alpha, in `[0, FoundationMovieShadowAlpha]`.
 */
internal fun foundationMovieCarouselDimAlpha(progress: Float): Float =
    (FoundationMovieShadowAlpha * sin(progress.coerceIn(0f, 1f) * PI.toFloat())).coerceAtLeast(0f)

/**
 * Draws PAGE_FLIP's wide cast shadow and narrow contact shadow on the page underneath the raised
 * leaf. Whole-page turns project from an outer edge; spread turns project from the center spine.
 * Both use [foundationPageFlipLightingSpec], so the shadow widens and darkens toward edge-on then
 * disappears completely on both settled pages.
 *
 * @receiver The underlying page modifier this projected shadow is appended to.
 * @param axis Whether the fold turns along the horizontal or vertical axis.
 * @param pageOffset Signed turn progress in `[-1, 1]`.
 * @param layout Whether the shadow starts at an outer edge or the spread spine.
 * @return A modifier drawing the page and the projected cast/contact shadow above it.
 */
private fun Modifier.foundationPageFlipProjectedShadow(
    axis: FoundationPagerAxis,
    pageOffset: Float,
    layout: FoundationPageFlipLayout,
): Modifier = drawWithCache {
    val lighting = foundationPageFlipLightingSpec(pageOffset)
    val shadowSide = if (layout == FoundationPageFlipLayout.WholePage) {
        lighting.side
    } else {
        when (lighting.side) {
            FoundationFluidSide.Start -> FoundationFluidSide.End
            FoundationFluidSide.End -> FoundationFluidSide.Start
            null -> null
        }
    }
    val alpha = lighting.castAlpha
    if (shadowSide == null || alpha <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }

    val lift = (lighting.castAlpha / FoundationPageFlipMaxCastAlpha).coerceIn(0f, 1f)
    val extent = if (axis == FoundationPagerAxis.Horizontal) size.width else size.height
    val castWidth = (FoundationPageFlipPageShadowWidth + extent * FoundationPageFlipCastGrowthRatio * lift)
        .coerceAtMost(extent)

    onDrawWithContent {
        drawContent()
        when (layout) {
            FoundationPageFlipLayout.WholePage -> {
                if (axis == FoundationPagerAxis.Horizontal) {
                    val bandWidth = castWidth
                    if (shadowSide == FoundationFluidSide.Start) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Black.copy(alpha = alpha), Color.Transparent),
                                startX = 0f,
                                endX = bandWidth,
                            ),
                            topLeft = Offset.Zero,
                            size = Size(bandWidth, size.height),
                        )
                    } else {
                        val left = (size.width - bandWidth).coerceAtLeast(0f)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = alpha)),
                                startX = left,
                                endX = size.width,
                            ),
                            topLeft = Offset(left, 0f),
                            size = Size((size.width - left).coerceAtLeast(0f), size.height),
                        )
                    }
                } else {
                    val bandHeight = castWidth
                    if (shadowSide == FoundationFluidSide.Start) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = alpha), Color.Transparent),
                                startY = 0f,
                                endY = bandHeight,
                            ),
                            topLeft = Offset.Zero,
                            size = Size(size.width, bandHeight),
                        )
                    } else {
                        val top = (size.height - bandHeight).coerceAtLeast(0f)
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = alpha)),
                                startY = top,
                                endY = size.height,
                            ),
                            topLeft = Offset(0f, top),
                            size = Size(size.width, (size.height - top).coerceAtLeast(0f)),
                        )
                    }
                }
            }

            FoundationPageFlipLayout.SplitHalfFold -> if (axis == FoundationPagerAxis.Horizontal) {
                val centerX = size.width / 2f
                if (shadowSide == FoundationFluidSide.Start) {
                    val left = (centerX - castWidth).coerceAtLeast(0f)
                    val right = centerX.coerceAtMost(size.width)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = alpha)),
                            startX = left,
                            endX = right,
                        ),
                        topLeft = Offset(left, 0f),
                        size = Size((right - left).coerceAtLeast(0f), size.height),
                    )
                } else {
                    val left = centerX
                    val right = (centerX + castWidth).coerceAtMost(size.width)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Black.copy(alpha = alpha), Color.Transparent),
                            startX = left,
                            endX = right,
                        ),
                        topLeft = Offset(left, 0f),
                        size = Size((right - left).coerceAtLeast(0f), size.height),
                    )
                }
            } else {
                val centerY = size.height / 2f
                if (shadowSide == FoundationFluidSide.Start) {
                    val top = (centerY - castWidth).coerceAtLeast(0f)
                    val bottom = centerY.coerceAtMost(size.height)
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = alpha)),
                            startY = top,
                            endY = bottom,
                        ),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, (bottom - top).coerceAtLeast(0f)),
                    )
                } else {
                    val top = centerY
                    val bottom = (centerY + castWidth).coerceAtMost(size.height)
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = alpha), Color.Transparent),
                            startY = top,
                            endY = bottom,
                        ),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, (bottom - top).coerceAtLeast(0f)),
                    )
                }
            }
        }

        val contactOffset = when (layout) {
            FoundationPageFlipLayout.WholePage -> if (shadowSide == FoundationFluidSide.Start) {
                0f
            } else {
                (extent - FoundationPageFlipContactWidth).coerceAtLeast(0f)
            }
            FoundationPageFlipLayout.SplitHalfFold ->
                (extent / 2f - FoundationPageFlipContactWidth / 2f).coerceAtLeast(0f)
        }
        if (axis == FoundationPagerAxis.Horizontal) {
            drawRect(
                color = Color.Black.copy(alpha = lighting.contactAlpha),
                topLeft = Offset(contactOffset, 0f),
                size = Size(FoundationPageFlipContactWidth.coerceAtMost(size.width), size.height),
            )
        } else {
            drawRect(
                color = Color.Black.copy(alpha = lighting.contactAlpha),
                topLeft = Offset(0f, contactOffset),
                size = Size(size.width, FoundationPageFlipContactWidth.coerceAtMost(size.height)),
            )
        }
    }
}

/**
 * A general-purpose cast-plus-contact edge shadow, parameterised over which side it hugs and how
 * wide/strong the cast and contact bands are, unlike the fixed-constant shadows such as
 * [foundationFluidShadow]. It has no call site in this file or its tests as of this writing — kept
 * as the reusable shape those fixed shadows were factored from, not currently wired into any of
 * the page-turn styles.
 *
 * @receiver The modifier chain this shadow is appended to.
 * @param axis Whether the shadow's edge runs along the horizontal or vertical axis.
 * @param side Which side (start/end) the edge advances from as [progress] grows.
 * @param progress How far the edge has advanced, in `[0, 1]`.
 * @param maxAlpha The cast shadow's alpha at full intensity (`progress == 1`); the contact band is
 *   derived from this and capped independently.
 * @param castWidth The cast shadow's width in pixels.
 * @param contactWidth The narrower, darker contact band's width in pixels.
 * @return A modifier drawing the shadow, or the receiver unchanged when [progress] is zero.
 */
private fun Modifier.foundationMovingEdgeShadow(
    axis: FoundationPagerAxis,
    side: FoundationFluidSide,
    progress: Float,
    maxAlpha: Float,
    castWidth: Float,
    contactWidth: Float,
): Modifier = drawWithCache {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val castAlpha = maxAlpha * sin(clampedProgress * PI.toFloat())
    val contactAlpha = (castAlpha * 1.35f).coerceAtMost(0.42f)
    if (castAlpha <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }

    onDrawWithContent {
        drawContent()
        if (axis == FoundationPagerAxis.Horizontal) {
            val edge = when (side) {
                FoundationFluidSide.Start -> size.width * clampedProgress
                FoundationFluidSide.End -> size.width * (1f - clampedProgress)
            }
            val castLeft = when (side) {
                FoundationFluidSide.Start -> edge
                FoundationFluidSide.End -> edge - castWidth
            }.coerceIn(0f, size.width)
            val castRight = (castLeft + castWidth).coerceAtMost(size.width)
            val contactLeft = when (side) {
                FoundationFluidSide.Start -> edge - contactWidth / 2f
                FoundationFluidSide.End -> edge - contactWidth / 2f
            }.coerceIn(0f, size.width)
            val contactRight = (contactLeft + contactWidth).coerceAtMost(size.width)
            val castColors = when (side) {
                FoundationFluidSide.Start -> listOf(
                    Color.Black.copy(alpha = castAlpha),
                    Color.Black.copy(alpha = castAlpha * 0.30f),
                    Color.Transparent,
                )
                FoundationFluidSide.End -> listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = castAlpha * 0.30f),
                    Color.Black.copy(alpha = castAlpha),
                )
            }
            drawRect(
                brush = Brush.horizontalGradient(colors = castColors, startX = castLeft, endX = castRight),
                topLeft = Offset(castLeft, 0f),
                size = Size((castRight - castLeft).coerceAtLeast(0f), size.height),
            )
            drawRect(
                color = Color.Black.copy(alpha = contactAlpha),
                topLeft = Offset(contactLeft, 0f),
                size = Size((contactRight - contactLeft).coerceAtLeast(0f), size.height),
            )
        } else {
            val edge = when (side) {
                FoundationFluidSide.Start -> size.height * clampedProgress
                FoundationFluidSide.End -> size.height * (1f - clampedProgress)
            }
            val castTop = when (side) {
                FoundationFluidSide.Start -> edge
                FoundationFluidSide.End -> edge - castWidth
            }.coerceIn(0f, size.height)
            val castBottom = (castTop + castWidth).coerceAtMost(size.height)
            val contactTop = (edge - contactWidth / 2f).coerceIn(0f, size.height)
            val contactBottom = (contactTop + contactWidth).coerceAtMost(size.height)
            val castColors = when (side) {
                FoundationFluidSide.Start -> listOf(
                    Color.Black.copy(alpha = castAlpha),
                    Color.Black.copy(alpha = castAlpha * 0.30f),
                    Color.Transparent,
                )
                FoundationFluidSide.End -> listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = castAlpha * 0.30f),
                    Color.Black.copy(alpha = castAlpha),
                )
            }
            drawRect(
                brush = Brush.verticalGradient(colors = castColors, startY = castTop, endY = castBottom),
                topLeft = Offset(0f, castTop),
                size = Size(size.width, (castBottom - castTop).coerceAtLeast(0f)),
            )
            drawRect(
                color = Color.Black.copy(alpha = contactAlpha),
                topLeft = Offset(0f, contactTop),
                size = Size(size.width, (contactBottom - contactTop).coerceAtLeast(0f)),
            )
        }
    }
}

/**
 * Which side an in-progress manual drag is turning toward, or null when the gesture is not active
 * or has not yet moved far enough along the turn axis to commit to a direction.
 *
 * @param axis Whether the drag is read along the horizontal or vertical axis.
 * @param gestureState The current manual drag/touch state.
 * @return The side the drag is turning toward, or null when there is no committed direction yet.
 */
private fun foundationGestureSide(
    axis: FoundationPagerAxis,
    gestureState: FoundationPagerGestureState,
): FoundationFluidSide? {
    if (!gestureState.active) return null
    val delta = axis.primary(gestureState.current) - axis.primary(gestureState.start)
    if (abs(delta) < FoundationGestureDirectionThresholdPx) return null
    return if (delta < 0f) FoundationFluidSide.End else FoundationFluidSide.Start
}

/**
 * Resolves which neighbour is the active turn's target and how far along it is, reading the
 * gesture side directly off [gestureState] via [foundationGestureSide]. A thin Compose-side
 * wrapper around the pure, unit-testable overload below — kept separate so the drag-direction
 * lookup does not have to be duplicated at every call site inside a `drawWithCache` block.
 *
 * @param axis Whether the turn runs along the horizontal or vertical axis.
 * @param gestureState The current manual drag/touch state.
 * @param previousProgress How settled the previous-page turn is, in `[0, 1]`.
 * @param nextProgress How settled the next-page turn is, in `[0, 1]`.
 * @return The side and progress of whichever turn is currently active.
 */
private fun foundationActivePageTurn(
    axis: FoundationPagerAxis,
    gestureState: FoundationPagerGestureState,
    previousProgress: Float,
    nextProgress: Float,
): FoundationActivePageTurn = foundationActivePageTurn(
    gestureActive = gestureState.active,
    gestureSide = foundationGestureSide(axis, gestureState),
    previousProgress = previousProgress,
    nextProgress = nextProgress,
)

/**
 * The pure decision behind [foundationActivePageTurn]'s Compose-side overload, factored out so it
 * is unit-testable without a real gesture/pager: while a drag is active but has not yet committed
 * to a side, the turn it reports has zero progress rather than borrowing whichever neighbour's
 * pager progress happens to be higher — otherwise a drag that starts moving in one direction could
 * flash the other neighbour's shadow/reveal for a frame before the direction settles.
 *
 * @param gestureActive Whether a manual drag is currently in progress.
 * @param gestureSide Which side the drag has committed to, or null if it is active but
 *   undirected, or if there is no drag at all.
 * @param previousProgress How settled the previous-page turn is, in `[0, 1]`.
 * @param nextProgress How settled the next-page turn is, in `[0, 1]`.
 * @return The side and progress of whichever turn is currently active.
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
 * Stacking order for the fluid- and circle-reveal styles: the current page sits above a settled
 * neighbour, but the neighbour actively being revealed rises above the current page as the reveal
 * progresses, so the growing fold/circle appears to lift off the page underneath it rather than
 * being cut off by it.
 *
 * @param page Which pager slot is being placed.
 * @param activeSide Which side (start/end) the active turn is revealing from.
 * @param progress How far the active turn has progressed, in `[0, 1]`.
 * @return The z-index for [page].
 */
private fun foundationRevealZIndex(
    page: FoundationPagerPage,
    activeSide: FoundationFluidSide,
    progress: Float,
): Float = when {
    page == FoundationPagerPage.Current -> 1f
    progress <= 0f -> 0f
    page.side == activeSide -> 2f + progress
    else -> 0f
}

/**
 * The clip shape for a fluid- or circle-reveal neighbour slot: the current page is never clipped,
 * an inactive or not-yet-progressing neighbour is hidden entirely via
 * [Modifier.foundationHiddenWhenInactive] rather than left visible full-frame underneath the
 * active turn, and the neighbour on the active side is clipped to the growing fluid edge or circle
 * per [style].
 *
 * @param page Which pager slot this modifier is for.
 * @param axis Whether the turn runs along the horizontal or vertical axis.
 * @param activeSide Which side (start/end) the active turn is revealing from.
 * @param progress How far the active turn has progressed, in `[0, 1]`.
 * @param gestureState The manual drag/touch state driving the clip's touch-anchored geometry.
 * @param style Whether the reveal is a fluid edge or a growing circle.
 * @param fluidEdge The shared spring-animated edge shape for [FoundationRevealStyle.Fluid]; a
 *   fresh, unused edge is created if null since only the fluid path reads it.
 * @param fluidVersion A change counter for [fluidEdge], read (but not directly used) inside the
 *   clip shape so it invalidates whenever the mutable edge shape changes.
 * @return A modifier clipping this slot to its reveal shape, hiding it, or [Modifier] unchanged
 *   for the current page.
 */
private fun foundationRevealModifier(
    page: FoundationPagerPage,
    axis: FoundationPagerAxis,
    activeSide: FoundationFluidSide,
    progress: Float,
    gestureState: FoundationPagerGestureState,
    style: FoundationRevealStyle,
    fluidEdge: FoundationFluidEdge? = null,
    fluidVersion: Int = 0,
): Modifier {
    if (page == FoundationPagerPage.Current) return Modifier
    if (page.side != activeSide || progress <= 0f) return Modifier.foundationHiddenWhenInactive(true)
    return when (style) {
        FoundationRevealStyle.Fluid -> Modifier.foundationFluidClip(
            axis = axis,
            side = page.side,
            progress = progress,
            gestureState = gestureState,
            fluidEdge = fluidEdge ?: FoundationFluidEdge(FoundationFluidPointCount),
            fluidVersion = fluidVersion,
        )
        FoundationRevealStyle.Circle -> Modifier.foundationCircleRevealClip(axis, page.side, progress, gestureState)
    }
}

/**
 * Fully transparent, rather than merely un-clipped, for a neighbour slot the active reveal has not
 * reached — leaving it visible full-frame would show it flatly overlapping the current page
 * instead of appearing only once the fluid edge or circle actually reaches it.
 *
 * @receiver The modifier chain this visibility is appended to.
 * @param hidden Whether this slot should be hidden.
 * @return The receiver with zero alpha applied when [hidden], or the receiver unchanged otherwise.
 */
private fun Modifier.foundationHiddenWhenInactive(hidden: Boolean): Modifier = if (hidden) {
    graphicsLayer { alpha = 0f }
} else {
    this
}

/** Which shape [foundationRevealModifier] clips a revealing neighbour to. */
private enum class FoundationRevealStyle {
    /** Clipped to [FoundationFluidEdge]'s spring-animated wavy edge, for [PageAnimation.FLUID_PAGER]. */
    Fluid,

    /** Clipped to a growing circle anchored at the touch point, for [PageAnimation.CIRCLE_REVEAL]. */
    Circle,
}

/**
 * Clips content to [FoundationFluidEdge]'s current wavy edge shape for [PageAnimation.FLUID_PAGER].
 * The [Shape] is a fresh anonymous object per call, but it drives [fluidEdge] toward this call's
 * [side]/[progress]/touch target every time Compose asks it for an outline
 * ([Shape.createOutline]) — `applyTarget` only records the target, the physics that chases it
 * still runs once per frame in the `LaunchedEffect(pageAnimation)` loop in [FoundationEffectPager].
 *
 * @receiver The modifier chain this clip is appended to.
 * @param axis Whether the turn runs along the horizontal or vertical axis.
 * @param side Which side (start/end) the fluid edge advances from.
 * @param progress How far the active turn has progressed, in `[0, 1]`; the edge's resting target.
 * @param gestureState The manual drag/touch state whose touch point steers the edge's bulge.
 * @param fluidEdge The shared spring-animated edge shape this clip both drives and reads.
 * @param fluidVersion A change counter for [fluidEdge], captured (but not read) purely so the
 *   outline is recomputed whenever the mutable edge shape changes.
 * @return A modifier clipping the receiver to the fluid edge's current shape.
 */
private fun Modifier.foundationFluidClip(
    axis: FoundationPagerAxis,
    side: FoundationFluidSide,
    progress: Float,
    gestureState: FoundationPagerGestureState,
    fluidEdge: FoundationFluidEdge,
    fluidVersion: Int,
): Modifier = clip(
    object : Shape {
        @Suppress("UNUSED_VARIABLE")
        private val version = fluidVersion

        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
            val sizeModel = FoundationPagerSize(size.width, size.height)
            val touchCrossAxis = foundationTouchCrossAxis(
                axis = axis,
                size = sizeModel,
                touch = gestureState.touchPoint(),
            )
            fluidEdge.applyTarget(
                side = side,
                progress = progress,
                touchCrossAxis = touchCrossAxis,
                touchActive = gestureState.active,
            )
            return Outline.Generic(
                buildFoundationFluidPath(
                    size = size,
                    axis = axis,
                    side = side,
                    edge = fluidEdge,
                ),
            )
        }
    },
)

/**
 * Clips content to the growing circle for [PageAnimation.CIRCLE_REVEAL], drawn with
 * [clipPath]/`drawWithCache` rather than [clip] (unlike [foundationFluidClip]) because the circle
 * needs no persistent per-frame physics state — its geometry is a pure function of [progress] and
 * the touch point, recomputed fresh whenever the cache invalidates.
 *
 * @receiver The modifier chain this clip is appended to.
 * @param axis Whether the turn runs along the horizontal or vertical axis.
 * @param side Which side (start/end) the circle's origin sits on.
 * @param progress How far the active turn has progressed, in `[0, 1]`; drives the circle's radius.
 * @param gestureState The manual drag/touch state whose touch point anchors the circle's origin.
 * @return A modifier clipping the receiver to the circle's current shape.
 */
private fun Modifier.foundationCircleRevealClip(
    axis: FoundationPagerAxis,
    side: FoundationFluidSide,
    progress: Float,
    gestureState: FoundationPagerGestureState,
): Modifier = drawWithCache {
    val touchCrossAxis = foundationTouchCrossAxis(
        axis = axis,
        size = FoundationPagerSize(size.width, size.height),
        touch = gestureState.touchPoint(),
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
 * The [PageAnimation.MOVIE_CAROUSEL] transform: scales and fades a receding page down via
 * [foundationMovieCarouselSpec]. It also carries a translate-and-3D-tilt branch along [axis],
 * meant to slide the page sideways and pitch it away from the viewer as it recedes, with
 * [FoundationCameraDistance] keeping the perspective foreshortening subtle rather than
 * fisheye-distorting it at full tilt — but [FoundationMovieTranslationRatio] is currently `0f`, so
 * `spec.translationFraction` is always zero and this branch presently applies no translation or
 * rotation; only the scale and alpha are visibly active.
 *
 * @receiver The modifier chain this transform is appended to.
 * @param axis Whether the carousel moves along the horizontal or vertical axis; selects which
 *   translation/rotation pair the (currently inert) tilt branch would apply to.
 * @param page Which pager slot this transform is for.
 * @param pageOffset This slot's signed offset from the pager's settled position, in `[-1, 1]`.
 * @return A modifier applying the carousel's scale and alpha (and translation/tilt, if
 *   [FoundationMovieTranslationRatio] is ever made non-zero).
 */
private fun Modifier.foundationMovieCarouselLayer(
    axis: FoundationPagerAxis,
    page: FoundationPagerPage,
    pageOffset: Float,
): Modifier = graphicsLayer {
    val spec = foundationMovieCarouselSpec(page, pageOffset)
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
 * Stacking order for [PageAnimation.MOVIE_CAROUSEL]: the page actively sliding in over the current
 * page sits highest, the current page sits above the untouched far neighbour, and a neighbour with
 * no active turn touching it sits at the back — mirroring [foundationRevealZIndex]'s logic for the
 * fluid/circle styles, but with the current page always above an inactive neighbour instead of at
 * a fixed middle rank.
 *
 * @param page Which pager slot is being placed.
 * @param activeSide Which side (start/end) the active turn is coming from.
 * @param progress How far the active turn has progressed, in `[0, 1]`.
 * @return The z-index for [page].
 */
private fun foundationMovieZIndex(
    page: FoundationPagerPage,
    activeSide: FoundationFluidSide,
    progress: Float,
): Float = when {
    progress <= 0f && page == FoundationPagerPage.Current -> 2f
    progress <= 0f -> 0f
    page.side == activeSide -> 3f
    page == FoundationPagerPage.Current -> 2f
    else -> 0f
}

/**
 * Stacking order for the page-flip animation, where the pager's own translation is cancelled.
 *
 * Because nothing is translated, all three pages sit on the same spot and only this order separates them.
 * Giving both neighbours the same index let composition order decide, and the neighbour on the far side
 * won — turning back then showed the *next* page through the half the folding page leaves transparent.
 * Ranking the neighbours by how near each is to being the page on screen is what fixes that.
 *
 * @param page which of the three pages is being placed.
 * @param pageOffset how far the pager has moved from [page], in pages.
 * @return the z-index: the current page always on top, a neighbour rising as it approaches the screen.
 */
internal fun foundationPageFlipZIndex(page: FoundationPagerPage, pageOffset: Float): Float = when (page) {
    FoundationPagerPage.Current -> 3f
    FoundationPagerPage.Previous, FoundationPagerPage.Next -> 2f - abs(pageOffset).coerceIn(0f, 1f)
}

/** Which physical face of a PAGE_FLIP leaf is currently visible and therefore lit. */
internal enum class FoundationPageFlipSurface {
    Front,
    Back,
}

/**
 * Which quadrant (for [FoundationPageFlipLayout.WholePage]'s hinge shadow) or half (for
 * [FoundationPageFlipLayout.SplitHalfFold]'s spine) of the page a [FoundationPageFlipHalfBox] or
 * [foundationPageFlipShape] call is seating content into.
 */
internal enum class FoundationPageFlipHalf {
    Top,
    Bottom,
    Left,
    Right,
}

/** Whether [PageAnimation.PAGE_FLIP] folds the whole page as one sheet, or splits it into two hinged halves. */
internal enum class FoundationPageFlipLayout {
    /** A single pane turns as one stiff sheet about its outer edge; see [FoundationWholePageFlipBox]. */
    WholePage,

    /** A two-pane spread folds along its own spine, one half at a time; see [FoundationSpreadPageFlipBox]. */
    SplitHalfFold,
}

/**
 * The rotation and pivot corner for a [FoundationPageFlipLayout.WholePage] turn, as computed by
 * [foundationWholePageFlipSpec].
 *
 * @property rotationX The page's rotation about the horizontal axis, in degrees.
 * @property rotationY The page's rotation about the vertical axis, in degrees.
 * @property transformOriginX The pivot's horizontal position, as a fraction of page width in
 *   `[0, 1]`.
 * @property transformOriginY The pivot's vertical position, as a fraction of page height in
 *   `[0, 1]`.
 */
internal data class FoundationWholePageFlipSpec(
    val rotationX: Float,
    val rotationY: Float,
    val transformOriginX: Float,
    val transformOriginY: Float,
)

/**
 * Unified PAGE_FLIP lighting for the turning leaf and the page underneath it.
 *
 * @property side The physical edge the cast/contact shadow hugs, or null while settled.
 * @property frontShadeAlpha Diffuse shade over the outgoing leaf's front face.
 * @property backShadeAlpha Lighter shade over the incoming leaf's back face.
 * @property rimAlpha Highlight intensity directly on the turning edge.
 * @property castAlpha Wide shadow opacity projected onto the underlying page.
 * @property contactAlpha Narrow shadow opacity at the leaf's contact edge.
 */
internal data class FoundationPageFlipLightingSpec(
    val side: FoundationFluidSide?,
    val frontShadeAlpha: Float,
    val backShadeAlpha: Float,
    val rimAlpha: Float,
    val castAlpha: Float,
    val contactAlpha: Float,
)

/**
 * Computes one PAGE_FLIP lighting frame from signed turn progress. Every intensity follows a
 * half-sine: zero on both settled pages and strongest while the sheet is edge-on. The sign changes
 * only the physical shadow edge, keeping forward/backward and horizontal/vertical lighting equal.
 *
 * @param pageOffset Signed pager progress in `[-1, 1]`; values outside are clamped.
 * @return Surface and projected-shadow intensities for this turn frame.
 */
internal fun foundationPageFlipLightingSpec(pageOffset: Float): FoundationPageFlipLightingSpec {
    val offset = pageOffset.coerceIn(-1f, 1f)
    val progress = abs(offset)
    val intensity = if (progress == 0f || progress == 1f) {
        0f
    } else {
        sin(progress * PI.toFloat()).coerceIn(0f, 1f)
    }
    return FoundationPageFlipLightingSpec(
        side = when {
            offset > 0f -> FoundationFluidSide.Start
            offset < 0f -> FoundationFluidSide.End
            else -> null
        },
        frontShadeAlpha = 0.22f * intensity,
        backShadeAlpha = 0.10f * intensity,
        rimAlpha = 0.18f * intensity,
        castAlpha = FoundationPageFlipMaxCastAlpha * intensity,
        contactAlpha = 0.34f * intensity,
    )
}

/**
 * The rotation for one seated half/quadrant of a [FoundationPageFlipLayout.SplitHalfFold] turn, as
 * computed by [foundationPageFlipHalfSpec].
 *
 * @property rotationX The half's rotation about the horizontal axis, in degrees.
 * @property rotationY The half's rotation about the vertical axis, in degrees.
 */
internal data class FoundationPageFlipHalfSpec(
    val rotationX: Float,
    val rotationY: Float,
)

/**
 * The full layout for a [FoundationPageFlipLayout.SplitHalfFold] turn in progress, as computed by
 * [foundationSpreadPageFlipSpec]: which half is folding away, which half the incoming neighbour
 * lands on, their individual rotations, and whether each is currently visible.
 *
 * @property outgoingHalf The half the current page's leaf is folding off of.
 * @property incomingHalf The half the incoming neighbour is revealed on — the same half the leaf
 *   folds onto once it has rotated far enough to cover it.
 * @property outgoing The outgoing leaf's current rotation.
 * @property incoming The incoming neighbour's rotation once it becomes visible underneath the
 *   leaf's back face.
 * @property showOutgoing Whether the outgoing leaf is still in front, i.e. progress is at most
 *   halfway.
 * @property showIncoming Whether the incoming neighbour has been uncovered, i.e. progress is at
 *   least halfway.
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
 * Chooses between [FoundationPageFlipLayout.WholePage] and [FoundationPageFlipLayout.SplitHalfFold]
 * for [PageAnimation.PAGE_FLIP]: a single page turning one page at a time folds as a whole sheet,
 * while a multi-pane spread (a 2-up layout, or turning more than one page per step) folds each pane
 * along its own spine instead, since a whole-sheet fold has no natural hinge to seat a spread on.
 *
 * @param pageStep How many pages one turn advances; coerced to at least 1.
 * @param paneCount How many page panes are shown side by side; coerced to at least 1.
 * @return [FoundationPageFlipLayout.WholePage] only when both are exactly 1, otherwise
 *   [FoundationPageFlipLayout.SplitHalfFold].
 */
internal fun foundationPageFlipLayout(pageStep: Int, paneCount: Int): FoundationPageFlipLayout =
    if (pageStep.coerceAtLeast(1) == 1 && paneCount.coerceAtLeast(1) == 1) {
        FoundationPageFlipLayout.WholePage
    } else {
        FoundationPageFlipLayout.SplitHalfFold
    }

/**
 * The rotation and pivot for a [FoundationPageFlipLayout.WholePage] turn, used by
 * [FoundationWholePageFlipBox]: the page pivots about whichever of its own corners is on the far
 * side from the direction it is turning, so it reads as swinging on a hinge at that edge rather
 * than rotating about its own center.
 *
 * @param axis Whether the fold turns along the horizontal or vertical axis.
 * @param pageOffset This slot's signed offset from the pager's settled position, in `[-1, 1]`;
 *   coerced into that range before use.
 * @return The rotation and pivot corner for this offset.
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
 * The rotation for one half/quadrant of a [FoundationPageFlipLayout.SplitHalfFold] turn: only the
 * half on the side the turn is heading toward rotates (via `startOffset`/`endOffset`, whichever
 * one [pageOffset]'s sign feeds), so a half that is not the active leaf stays flat at zero
 * rotation instead of the other half's motion bleeding into it.
 *
 * @param half Which half/quadrant this rotation is being computed for.
 * @param pageOffset This slot's signed offset from the pager's settled position, in `[-1, 1]`;
 *   coerced into that range before use.
 * @return The rotation for [half] at this offset.
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
 * The full layout for a [FoundationPageFlipLayout.SplitHalfFold] turn, used by
 * [FoundationSpreadPageFlipBox]: works out which half is folding away and which the incoming
 * neighbour surfaces on, then hands each to [foundationPageFlipHalfSpec] for its own rotation. The
 * incoming half's own offset is computed as the mirror image of the outgoing progress
 * (`incomingOffset`) so it swings in from the opposite side as the outgoing leaf swings out,
 * meeting in the middle at `progress == 0.5` — the same point [showOutgoing]/[showIncoming] swap
 * which half is drawn on top.
 *
 * @param axis Whether the fold turns along the horizontal or vertical axis.
 * @param pageOffset This slot's signed offset from the pager's settled position, in `[-1, 1]`;
 *   coerced into that range before use.
 * @return The outgoing/incoming halves, their rotations, and which is currently visible.
 */
internal fun foundationSpreadPageFlipSpec(
    axis: FoundationPagerAxis,
    pageOffset: Float,
): FoundationSpreadPageFlipSpec {
    val offset = pageOffset.coerceIn(-1f, 1f)
    val progress = abs(offset)
    val isNext = offset >= 0f
    val outgoingHalf = when (axis) {
        FoundationPagerAxis.Horizontal -> if (isNext) FoundationPageFlipHalf.Right else FoundationPageFlipHalf.Left
        FoundationPagerAxis.Vertical -> if (isNext) FoundationPageFlipHalf.Bottom else FoundationPageFlipHalf.Top
    }
    val incomingHalf = when (axis) {
        FoundationPagerAxis.Horizontal -> if (isNext) FoundationPageFlipHalf.Left else FoundationPageFlipHalf.Right
        FoundationPagerAxis.Vertical -> if (isNext) FoundationPageFlipHalf.Top else FoundationPageFlipHalf.Bottom
    }
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
 * The clip [Shape] for [FoundationPageFlipHalfBox]'s seat, one quadrant/half of the page rectangle
 * per [FoundationPageFlipHalf]. Each shape is a plain rectangle held in a shared `val` rather than
 * allocated per call, since the rectangle only depends on the box's own size, not on any per-frame
 * state.
 *
 * @param half Which quadrant/half to clip to.
 * @return The matching shared shape constant.
 */
private fun foundationPageFlipShape(half: FoundationPageFlipHalf): Shape = when (half) {
    FoundationPageFlipHalf.Top -> FoundationPageFlipTopShape
    FoundationPageFlipHalf.Bottom -> FoundationPageFlipBottomShape
    FoundationPageFlipHalf.Left -> FoundationPageFlipLeftShape
    FoundationPageFlipHalf.Right -> FoundationPageFlipRightShape
}

/** The top half of the page rectangle, for [FoundationPageFlipHalf.Top]. */
private val FoundationPageFlipTopShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, 0f, size.width, size.height / 2f))
}

/** The bottom half of the page rectangle, for [FoundationPageFlipHalf.Bottom]. */
private val FoundationPageFlipBottomShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, size.height / 2f, size.width, size.height))
}

/** The left half of the page rectangle, for [FoundationPageFlipHalf.Left]. */
private val FoundationPageFlipLeftShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, 0f, size.width / 2f, size.height))
}

/** The right half of the page rectangle, for [FoundationPageFlipHalf.Right]. */
private val FoundationPageFlipRightShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(size.width / 2f, 0f, size.width, size.height))
}

/**
 * The scale/alpha/translation for a [PageAnimation.MOVIE_CAROUSEL] page, as computed by
 * [foundationMovieCarouselSpec].
 *
 * @property translationFraction The fraction of the page's own size to translate it by; always
 *   `0f` while [FoundationMovieTranslationRatio] stays `0f` (see
 *   [Modifier.foundationMovieCarouselLayer]).
 * @property scale The uniform scale to apply, in `[FoundationMovieMinScale, 1]`.
 * @property alpha The alpha to apply, in `[FoundationMovieMinAlpha, 1]`.
 */
internal data class FoundationMovieCarouselSpec(
    val translationFraction: Float,
    val scale: Float,
    val alpha: Float,
)

/**
 * The scale/alpha/translation for a [PageAnimation.MOVIE_CAROUSEL] page: only the current page
 * shrinks and fades as it turns — a neighbour slot always reports zero offset here regardless of
 * its own [pageOffset], because it is the incoming page and should arrive at full size/alpha, not
 * shrink in from the same distant state the outgoing current page is receding to.
 *
 * @param page Which pager slot this spec is for.
 * @param pageOffset This slot's signed offset from the pager's settled position, in `[-1, 1]`;
 *   only read when [page] is [FoundationPagerPage.Current].
 * @return The scale, alpha, and translation fraction for this slot.
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
 * Where a manual drag should settle once released: a fast enough fling commits to a turn
 * regardless of how far the drag travelled, otherwise the drag must have crossed a fraction of
 * the viewport for the turn to commit; anything short of either threshold snaps back to center. A
 * turn toward a side with no adjacent page never commits, even if both thresholds are met, since
 * there is nowhere for the pager to land.
 *
 * @param dragDistancePx How far the manual drag has moved, in pixels; sign indicates direction.
 * @param velocityPxPerSecond The drag's release velocity, in pixels per second; sign indicates
 *   direction.
 * @param viewportExtentPx The pager's viewport size along the turn axis, in pixels; coerced to at
 *   least 1 to avoid a zero-extent threshold.
 * @param hasPreviousPage Whether there is a page to turn back to.
 * @param hasNextPage Whether there is a page to turn forward to.
 * @return `-1` to settle on the previous page, `1` on the next page, or `0` to snap back to
 *   center.
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
 * Whether a manual drag moving in [primaryDelta]'s direction should be consumed and blocked rather
 * than handed to the pager — used both for the gesture-tracking `pointerInput` that feeds
 * [FoundationPagerGestureState] and for the actual `draggable` modifier, so a drag toward the
 * start or end of the book with nothing to turn to cannot drag the pager past its first/last slot.
 *
 * @param primaryDelta The drag's movement along the turn axis so far, in pixels; sign indicates
 *   direction.
 * @param hasPreviousPage Whether there is a page to turn back to.
 * @param hasNextPage Whether there is a page to turn forward to.
 * @return Whether the drag should be blocked.
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

/** What a tap on the pager should do, as decided by [foundationPagerTapAction]. */
internal enum class FoundationPagerTapAction { Previous, ToggleControls, Next }

/**
 * What a tap on the page should do, decided from the tap's position alone.
 *
 * Factored out of the gesture handler so it is unit-testable, because the rule it carries was a shipped
 * bug (F16): a tap in an edge zone with no adjacent page — the first or last page of the book — used to do
 * nothing at all. It now falls through to toggling the controls, the same as a tap in the middle. The
 * return type is a non-null enum for that reason: "do nothing" is not a state this decision can express.
 *
 * @param primary the tap's position along the turn axis, in pixels.
 * @param extent the pane's size along that same axis.
 * @param hasPreviousPage whether there is a page to turn back to.
 * @param hasNextPage whether there is a page to turn forward to.
 * @return the action to take; never "nothing".
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
 * The release velocity, in pixels per second, above which [foundationPagerDragTargetOffset] commits
 * a manual drag to a turn regardless of how far it travelled — a fast flick that only barely moved
 * the finger still reads as an intentional page turn.
 */
private const val FoundationManualFlingVelocityThresholdPxPerSecond = 1000f

/**
 * The fraction of the viewport's extent a manual drag must travel, absent a qualifying fling
 * velocity, before [foundationPagerDragTargetOffset] commits it to a turn instead of snapping back
 * to center.
 */
private const val FoundationManualDragDistanceThresholdRatio = 0.25f

/**
 * The manual gesture state read by the fluid-edge, circle-reveal, and drag-blocking logic to know
 * where a touch is and whether it is still down. Tracked separately from Foundation's own pager
 * gesture handling because those effects need the raw pointer position (for the touch-anchored
 * bulge/circle origin), not just the pager's settled scroll offset.
 *
 * @property start Where the current gesture began, in local coordinates.
 * @property current The gesture's live position while [active] is true.
 * @property last The gesture's last known position, retained once [active] becomes false so a
 *   released touch still has a point to report via [touchPoint].
 * @property active Whether a pointer is currently down.
 * @property touched Whether any gesture has occurred since this state was last reset; distinguishes
 *   "never touched" from "touched and released" so [touchPoint]/[startPoint] can report null only
 *   for the former.
 */
private data class FoundationPagerGestureState(
    val start: Offset = Offset.Zero,
    val current: Offset = Offset.Zero,
    val last: Offset = Offset.Zero,
    val active: Boolean = false,
    val touched: Boolean = false,
) {
    /**
     * The point the fluid/circle reveal geometry should anchor to: the live position while a
     * touch is down, or the last known position once it has been released, so the reveal keeps
     * following where the finger was instead of snapping back to the origin.
     *
     * @return The current or last touch point, or null if [touched] is false.
     */
    fun touchPoint(): FoundationPagerPoint? {
        if (!touched) return null
        val touch = if (active) current else last
        return FoundationPagerPoint(touch.x, touch.y)
    }

    /**
     * Where this gesture began. Currently has no call site in this file or its tests — [start] is
     * read directly at the one place ([FoundationEffectPager]'s gesture-tracking `pointerInput`)
     * that needs the gesture's origin.
     *
     * @return The gesture's starting point, or null if [touched] is false.
     */
    fun startPoint(): FoundationPagerPoint? {
        if (!touched) return null
        return FoundationPagerPoint(start.x, start.y)
    }
}

/**
 * A 1D spring-mass chain modelling the wavy, cloth-like edge used by [PageAnimation.FLUID_PAGER]:
 * [FoundationFluidPointCount] points spaced evenly along the page's cross axis (`y` in `[0, 1]`),
 * each carrying its own horizontal displacement (`x`, a fraction of page width in `[0, 1]`) and
 * velocity, integrated every frame by [tick]. [applyTarget] only records where the edge is
 * heading — the turn's progress and the touch point pulling on it; [tick] is the only place any
 * point actually moves, called once per frame from the `LaunchedEffect(pageAnimation)` loop in
 * [FoundationEffectPager] while [PageAnimation.FLUID_PAGER] is active. [version] exists purely so
 * a `drawWithCache`/[Shape] block that captured [points] earlier can be told when to recompute —
 * the points are mutated in place rather than the list being replaced, so nothing else would
 * otherwise signal Compose that they changed.
 *
 * @param pointCount How many points make up the edge; also [points]' size.
 */
internal class FoundationFluidEdge(pointCount: Int = FoundationFluidPointCount) {
    /**
     * The chain's points, evenly spaced along the page's cross axis from `y = 0` to `y = 1`, each
     * starting at rest (`x = 0`). Mutated in place by [tick] and [reset] rather than replaced, so
     * a caller must read [version] to know when to react to a change.
     */
    val points: List<FoundationFluidPoint> = List(pointCount) { index ->
        FoundationFluidPoint(y = index.toFloat() / (pointCount - 1).toFloat())
    }

    /**
     * A change counter bumped every time [tick] or [reset] mutates [points] in place, so a
     * `drawWithCache`/[Shape] block that captured this value knows to recompute even though the
     * [points] list reference itself never changes.
     */
    var version by mutableStateOf(0)
        private set

    /** The side [applyTarget] was last told is active; a change here resets every point in [points]. */
    private var activeSide = FoundationFluidSide.Start

    /** The turn progress [applyTarget] last recorded, read by [tick] as the touch-tension target. */
    private var progress = 0f

    /** The touch's cross-axis position [applyTarget] last recorded, read by [tick] to weight each point's touch influence. */
    private var touchCrossAxis = 0.5f

    /** Whether a touch was active as of the last [applyTarget] call; selects which branch of [tick] runs. */
    private var touchActive = false

    /**
     * Snaps every point back to rest and clears [activeSide]/[progress]/[touchCrossAxis]/
     * [touchActive] to their starting values, so a stale bulge or velocity left over from the
     * previous turn cannot bleed into the next one.
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
     * Records where the edge should be heading — which side is active, how far the turn has
     * progressed, and where the touch sits along the cross axis — without moving any point
     * itself; [tick] is what actually chases this target every frame. Resets every point's
     * position and velocity when [side] differs from the previously active one, since a bulge
     * built up while revealing one neighbour has no meaning once the active side flips. Clears
     * velocity (but not position) the moment [touchActive] goes from true to false, so drag
     * momentum does not carry into the release-and-settle interpolation [tick] switches to once
     * the touch is up.
     *
     * @param side Which side (start/end) the edge is advancing from.
     * @param progress How far the turn has progressed, in `[0, 1]`; clamped into that range.
     * @param touchCrossAxis Where the touch sits along the page's cross axis, in `[0, 1]`; clamped
     *   into that range.
     * @param touchActive Whether a finger is currently down; false switches [tick] to the
     *   release-and-settle interpolation instead of the spring simulation.
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
     * Advances the spring-mass chain by one frame. This is an explicit-Euler spring-mass
     * simulation, not a set of values tuned by feel the way the pure visual constants elsewhere in
     * this file are — these constants interact with each other, and changing one shifts what the
     * others mean.
     *
     * While no touch is active, every point simply interpolates toward [progress]
     * (`point.x += (progress - point.x) * releaseFraction`, with velocity zeroed) — see
     * [FoundationFluidReleaseDamping] below for what that fraction means. While a touch is active,
     * every point instead receives four kinds of force before its velocity is damped and applied:
     * an edge-tension term pulling it back toward `x = 0` (weight [FoundationFluidEdgeTension]);
     * once [progress] has passed [FoundationFluidCompleteThreshold], a far-edge-tension term
     * pulling it toward `x = 1` (weight [FoundationFluidFarEdgeTension]) — the two weights are
     * equal by design and pull in opposite directions, a deliberately symmetric restoring force
     * that only engages the far side once the turn is nearly complete; a touch-tension term toward
     * [progress] itself, weighted by [FoundationFluidTouchTension] and by `influence`, a linear
     * falloff that reaches zero at [FoundationFluidTouchRadius] from the touch's cross-axis
     * position; and a term pulling it toward each *neighbouring* point, weighted by
     * [FoundationFluidPointTension]. An interior point receives that neighbour term twice — once
     * from each side — so its effective neighbour coupling is `2 × FoundationFluidPointTension` =
     * 0.50, five times [FoundationFluidTouchTension]'s 0.10, while an endpoint (index 0 or the
     * last index) receives only one neighbour term. That asymmetry is why the edge relaxes flat
     * into the frame at the top and bottom instead of following the touch as sharply as the
     * interior does: with only half the coupling pulling it along, an endpoint is dominated by its
     * own much weaker edge tension, and the touch bulge reads as a smooth sheet flexing around a
     * fixed anchor rather than a spike dragging the corners with it.
     *
     * [FoundationFluidPointCount] and [FoundationFluidTouchRadius] are not independent: with 25
     * points spread over `y` in `[0, 1]`, the spacing between points is `1 / (pointCount - 1)` ≈
     * 0.0417, so a touch radius of 0.24 spans roughly `2 × 0.24 / 0.0417` ≈ 12 of the 25 points
     * around the touch. Changing either constant alone without the other aliases the influence
     * kernel — too few points under the radius and the bulge looks faceted instead of smooth; too
     * many and the touch loses its local shape entirely.
     *
     * Stability here is a real constraint, not a suggestion. Summing the coefficients acting on an
     * interior point at full touch influence — edge tension (0.01) + doubled point tension (0.50)
     * + touch tension (0.10) — gives an effective per-tick stiffness of about 0.61, and
     * [frameUnits] is clamped to at most 1.5, keeping the stiffness-times-timestep product under 1
     * (`0.61 × 1.5 ≈ 0.915`). Running this update rule numerically confirms it converges (velocity
     * decays toward zero) for [FoundationFluidPointTension] values up to about 0.40 — comfortably
     * above the shipped 0.25 — starts to ring (velocity stops decaying and oscillates instead) at
     * 0.50, and diverges outright (velocity growing without bound) from roughly 0.55 upward; the
     * same numerical check shows raising the [frameUnits] clamp has an equivalent destabilizing
     * effect at the shipped tension, since it is the stiffness-times-timestep product that governs
     * this, not either factor alone. The `coerceIn(0f, 1f)` on each point's position at the end of
     * this function is the only thing standing between that divergence and a `NaN` or off-screen
     * shape — the velocity itself is free to grow arbitrarily large once the system is unstable;
     * only the position it moves is clamped.
     *
     * The two damping-looking constants play different roles despite the similar name, and mixing
     * them up moves the wrong knob. [FoundationFluidDamping] (0.90) is a per-frame *velocity* decay
     * applied while the touch is active (`velocityX *= FoundationFluidDamping.pow(t)`, a genuine
     * multiplier; the `.pow(t)` makes it frame-rate-independent by expressing it per
     * [FoundationFrameMillis] rather than per wall-clock tick) — its velocity multiplier falls to
     * `1/e` after about 9.5 frames, roughly 158 ms at 60 fps. [FoundationFluidReleaseDamping]
     * (0.82) is not a decay multiplier at all: it is read as `1 - 0.82.pow(t)`, the *fraction of
     * the remaining distance to the target closed this frame*, used only once the touch is
     * released to interpolate each point straight toward [progress]. Read the same way, that
     * remaining-distance fraction reaches `1/e` after about 5 frames (roughly 84 ms) and 99% closed
     * after about 23 frames (roughly 387 ms). [FoundationFluidCompleteThreshold] (0.82) happens to
     * equal [FoundationFluidReleaseDamping] numerically, but the two gate unrelated things — one is
     * a progress threshold that switches on the far-edge tension above, the other is a per-frame
     * lerp fraction for the post-release settle — and that equality is coincidental, not a shared
     * constant; changing one should never be expected to move the other.
     *
     * @param frameUnits How much of one [FoundationFrameMillis]-long frame elapsed since the last
     *   tick; coerced to `[0.1, 1.5]` so a long pause (e.g. a dropped frame) cannot overshoot the
     *   spring into instability and a near-zero delta cannot stall it.
     */
    fun tick(frameUnits: Float = 1f) {
        val t = frameUnits.coerceIn(0.1f, 1.5f)
        if (!touchActive) {
            val releaseFraction = 1f - FoundationFluidReleaseDamping.pow(t)
            points.forEach { point ->
                point.velocityX = 0f
                point.x += (progress - point.x) * releaseFraction
            }
            version++
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
 * One mass point in [FoundationFluidEdge]'s spring-mass chain. `x` and `velocityX` are `var`
 * rather than immutable so [FoundationFluidEdge.tick]/[FoundationFluidEdge.reset] can mutate the
 * chain in place every frame instead of allocating a fresh `List` each time.
 *
 * @property x The point's horizontal displacement, a fraction of page width in `[0, 1]`; `0`
 *   means resting flat against the frame, `1` fully advanced.
 * @property y The point's fixed position along the page's cross axis, a fraction in `[0, 1]`;
 *   never mutated after construction.
 * @property velocityX The point's current rate of change of [x] per [FoundationFluidEdge.tick]
 *   frame unit.
 */
internal data class FoundationFluidPoint(
    var x: Float = 0f,
    val y: Float = 0f,
    var velocityX: Float = 0f,
)

/**
 * Which neighbouring turn is currently active and how far it has progressed, as resolved by
 * [foundationActivePageTurn].
 *
 * @property side Which side (start/end) the active turn is revealing from.
 * @property progress How far the active turn has progressed, in `[0, 1]`.
 */
internal data class FoundationActivePageTurn(
    val side: FoundationFluidSide,
    val progress: Float,
)

/**
 * A 2D point in a pager slot's own local coordinate space (pixels, canonicalised to the turn axis
 * by [FoundationPagerAxis]), used throughout the fluid/circle-reveal geometry in place of
 * Compose's [Offset] so that math on it does not need a `Density`/`LayoutDirection` receiver.
 *
 * @property x The point's horizontal component, in pixels (or, once canonicalised, along the
 *   pager's primary axis).
 * @property y The point's vertical component, in pixels (or, once canonicalised, along the
 *   pager's cross axis).
 */
internal data class FoundationPagerPoint(
    val x: Float,
    val y: Float,
) {
    /** Component-wise sum, used when combining two offsets in the same coordinate space. */
    operator fun plus(other: FoundationPagerPoint): FoundationPagerPoint = FoundationPagerPoint(x + other.x, y + other.y)

    /** Component-wise difference, used when measuring one point relative to another. */
    operator fun minus(other: FoundationPagerPoint): FoundationPagerPoint = FoundationPagerPoint(x - other.x, y - other.y)

    /** Uniform scale by [value], used when interpolating or scaling a point toward another. */
    operator fun times(value: Float): FoundationPagerPoint = FoundationPagerPoint(x * value, y * value)
}

/**
 * A pager slot's own size, canonicalised to the turn axis by [FoundationPagerAxis], mirroring
 * Compose's [Size] but usable outside a draw scope.
 *
 * @property width The slot's size along its own horizontal axis, in pixels.
 * @property height The slot's size along its own vertical axis, in pixels.
 */
internal data class FoundationPagerSize(
    val width: Float,
    val height: Float,
)

/**
 * Whether a pager turns along the horizontal or vertical axis. Most of this file's geometry is
 * written once in a "canonical" horizontal-turn coordinate space and then swapped back for a
 * vertical pager via this enum's `toCanonical`/`fromCanonical` helpers, rather than duplicating
 * every shape/polygon computation for both axes.
 */
internal enum class FoundationPagerAxis {
    Horizontal,
    Vertical,
    ;

    /**
     * Maps [point] from this axis's real coordinate space into the canonical horizontal-turn
     * space that the fluid-edge/polygon geometry is written in — a no-op for [Horizontal], an
     * x/y swap for [Vertical]. Self-inverse, so it also serves as the reverse mapping.
     *
     * @param point The point in this axis's real coordinate space.
     * @return The same point in canonical horizontal-turn space.
     */
    fun toCanonical(point: FoundationPagerPoint): FoundationPagerPoint = when (this) {
        Horizontal -> point
        Vertical -> FoundationPagerPoint(point.y, point.x)
    }

    /**
     * Maps [point] from canonical horizontal-turn space back into this axis's real coordinate
     * space. Delegates to [toCanonical], which is its own inverse for an x/y swap.
     *
     * @param point The point in canonical horizontal-turn space.
     * @return The same point in this axis's real coordinate space.
     */
    fun fromCanonical(point: FoundationPagerPoint): FoundationPagerPoint = toCanonical(point)

    /**
     * The [FoundationPagerSize] equivalent of [toCanonical]: swaps width/height for [Vertical] so
     * size-dependent geometry can be written once against a canonical horizontal-turn size.
     *
     * @param size The slot's size in this axis's real orientation.
     * @return The same size in canonical horizontal-turn orientation.
     */
    fun toCanonicalSize(size: FoundationPagerSize): FoundationPagerSize = when (this) {
        Horizontal -> size
        Vertical -> FoundationPagerSize(width = size.height, height = size.width)
    }

    /**
     * The Compose [Size] equivalent of [toCanonicalSize]. Currently has no call site in this file
     * or its tests — the fluid/circle geometry uses the [FoundationPagerSize] overload above
     * throughout — kept alongside it for a caller that only has a raw [Size] on hand.
     *
     * @param size The slot's size in this axis's real orientation.
     * @return The same size in canonical horizontal-turn orientation.
     */
    fun toCanonicalSize(size: Size): Size = when (this) {
        Horizontal -> size
        Vertical -> Size(size.height, size.width)
    }

    /**
     * Maps a Compose [Offset] from canonical horizontal-turn space back into this axis's real
     * coordinate space — the [Offset] counterpart of [fromCanonical], used when the caller is
     * already working in Compose's own offset type rather than [FoundationPagerPoint].
     *
     * @param point The offset in canonical horizontal-turn space.
     * @return The same offset in this axis's real coordinate space.
     */
    fun fromCanonical(point: Offset): Offset = when (this) {
        Horizontal -> point
        Vertical -> Offset(point.y, point.x)
    }

    /**
     * This axis's primary (turn-direction) component of [point]: `x` for [Horizontal], `y` for
     * [Vertical]. Used to read gesture deltas and drag distances along the turn direction without
     * a caller needing its own axis-dependent branch.
     *
     * @param point The offset to read.
     * @return The component of [point] along this axis's turn direction.
     */
    fun primary(point: Offset): Float = when (this) {
        Horizontal -> point.x
        Vertical -> point.y
    }
}

/**
 * Which end of the turn axis an effect (a reveal, a shadow, a fold) is anchored to or advancing
 * from: [Start] toward the previous page, [End] toward the next page.
 */
internal enum class FoundationFluidSide {
    Start,
    End,
}

/**
 * A pager slot's identity independent of its raw Foundation pager index, paired with the
 * [FoundationFluidSide] it sits on for reveal/shadow/z-index purposes. [Current] is assigned
 * [FoundationFluidSide.End] rather than a neutral value because most of the per-side logic in this
 * file (`page.side == activeSide` checks) only needs to distinguish "the neighbour on the active
 * side" from "everything else," and [Current] never matches an [activeSide] comparison as the
 * subject of a reveal, only as the page underneath one.
 *
 * @property pagerPage The raw Foundation pager index this slot corresponds to
 *   ([FoundationPreviousPage]/[FoundationCenterPage]/[FoundationNextPage]).
 * @property side Which side this slot sits on for reveal/shadow/z-index comparisons.
 */
internal enum class FoundationPagerPage(
    val pagerPage: Int,
    val side: FoundationFluidSide,
) {
    Previous(FoundationPreviousPage, FoundationFluidSide.Start),
    Current(FoundationCenterPage, FoundationFluidSide.End),
    Next(FoundationNextPage, FoundationFluidSide.End),
    ;

    /** Holds the lookup back from a raw pager slot index, so the mapping lives with the type it produces. */
    companion object {
        /**
         * The [FoundationPagerPage] matching a raw Foundation pager slot index.
         *
         * @param page A raw pager index, expected to be [FoundationPreviousPage],
         *   [FoundationCenterPage], or [FoundationNextPage].
         * @return The matching [FoundationPagerPage]; any index other than
         *   [FoundationPreviousPage] or [FoundationNextPage] is treated as [Current].
         */
        fun fromPagerPage(page: Int): FoundationPagerPage = when (page) {
            FoundationPreviousPage -> Previous
            FoundationNextPage -> Next
            else -> Current
        }
    }
}

/**
 * This pager's live scroll offset from [page], in whole pages plus fractional progress: `0` while
 * [page] is exactly settled at the top, positive while scrolling away from it toward a higher
 * index, negative toward a lower one. The building block every per-slot transform in this file
 * reads to know how mid-turn a given slot currently is.
 *
 * @receiver The pager whose live scroll state is being read.
 * @param page The pager slot index to measure the offset from.
 * @return The signed offset from [page], in pages.
 */
@OptIn(ExperimentalFoundationApi::class)
fun PagerState.foundationOffsetForPage(page: Int): Float =
    (currentPage - page) + currentPageOffsetFraction

/**
 * How settled a turn toward [page] currently is, as a value that rises from `0` (untouched) to
 * `1` (exactly on [page]) regardless of which direction the offset runs — the sign-agnostic
 * counterpart to [foundationOffsetForPage] that most reveal/shadow math wants instead of the raw
 * signed offset.
 *
 * @receiver The pager whose live scroll state is being read.
 * @param page The pager slot index to measure progress toward.
 * @return The turn's progress toward [page], in `[0, 1]`.
 */
@OptIn(ExperimentalFoundationApi::class)
fun PagerState.foundationAdjacentProgress(page: Int): Float =
    (1f - abs(foundationOffsetForPage(page))).coerceIn(0f, 1f)

/**
 * Linear interpolation from [start] to [stop], clamping [progress] into `[0, 1]` first so a
 * caller passing an out-of-range progress (e.g. overscroll) cannot overshoot past [stop].
 *
 * @param start The value at `progress == 0`.
 * @param stop The value at `progress == 1`.
 * @param progress The interpolation fraction; clamped into `[0, 1]` before use.
 * @return The interpolated value between [start] and [stop].
 */
internal fun foundationPagerLerp(start: Float, stop: Float, progress: Float): Float {
    val fraction = progress.coerceIn(0f, 1f)
    return start + (stop - start) * fraction
}

/**
 * Where [touch] sits along the axis perpendicular to the turn direction, as a fraction of that
 * cross-axis extent — the coordinate the fluid edge's touch-tension term and the circle-reveal's
 * origin both anchor to, since a page turns along [axis] but the touch can land anywhere across
 * the other dimension.
 *
 * @param axis Whether the turn runs along the horizontal or vertical axis; selects which of
 *   [touch]'s components is the cross-axis one.
 * @param size The slot's size, used to normalise the raw touch coordinate into a fraction.
 * @param touch The current touch point, or null when there is none.
 * @return The touch's cross-axis position, in `[0, 1]`; `0.5` (the center) when [touch] is null
 *   or the relevant [size] extent is zero.
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
 * Where [touch] sits along the turn axis itself, as a fraction of that extent — the
 * [foundationTouchCrossAxis] counterpart for the axis a page actually turns along, rather than the
 * one perpendicular to it. Currently has no call site in this file or its tests; none of the
 * shipped reveal/shadow styles need the touch's position along the turn direction, only across it.
 *
 * @param axis Whether the turn runs along the horizontal or vertical axis; selects which of
 *   [touch]'s components is the primary one.
 * @param size The slot's size, used to normalise the raw touch coordinate into a fraction.
 * @param touch The current touch point, or null when there is none.
 * @return The touch's primary-axis position, in `[0, 1]`, or null when [touch] is null or the
 *   relevant [size] extent is zero.
 */
internal fun foundationTouchPrimaryAxis(
    axis: FoundationPagerAxis,
    size: FoundationPagerSize,
    touch: FoundationPagerPoint?,
): Float? {
    if (touch == null) return null
    val primary = when (axis) {
        FoundationPagerAxis.Horizontal -> touch.x
        FoundationPagerAxis.Vertical -> touch.y
    }
    val extent = when (axis) {
        FoundationPagerAxis.Horizontal -> size.width
        FoundationPagerAxis.Vertical -> size.height
    }
    if (extent <= 0f) return null
    return (primary / extent).coerceIn(0f, 1f)
}

/**
 * The growing circle's geometry for [PageAnimation.CIRCLE_REVEAL], as computed by
 * [foundationCircleRevealSpec].
 *
 * @property origin The circle's fixed starting point, on the page's near edge at the touch's
 *   cross-axis position — where the reveal would sit at `progress == 0`.
 * @property center The circle's current center, which drifts from [origin] toward the page
 *   center as [progress] advances.
 * @property radius The circle's current radius, in pixels.
 */
internal data class FoundationCircleRevealSpec(
    val origin: FoundationPagerPoint,
    val center: FoundationPagerPoint,
    val radius: Float,
)

/**
 * The shadow ring drawn just inside the growing circle's edge for [PageAnimation.CIRCLE_REVEAL],
 * as computed by [foundationCircleRevealShadowSpec].
 *
 * @property center The shadow ring's center, matching the reveal circle's own center.
 * @property radius The shadow ring's outer radius, matching the reveal circle's own radius.
 * @property innerRadius Where the shadow ring's gradient starts fading in from, inside [radius].
 * @property alpha The shadow ring's peak alpha at its outer edge.
 */
internal data class FoundationCircleRevealShadowSpec(
    val center: FoundationPagerPoint,
    val radius: Float,
    val innerRadius: Float,
    val alpha: Float,
)

/**
 * The growing circle's geometry for [PageAnimation.CIRCLE_REVEAL]: the circle starts at [origin],
 * a point on the page's leading edge at the touch's cross-axis position, and its center drifts
 * toward the page's own center as [progress] advances toward 1 — so the reveal both grows and
 * recentres, rather than growing from a fixed corner the way a plain radial wipe would.
 *
 * @param size The slot's size.
 * @param axis Whether the turn runs along the horizontal or vertical axis; selects which edge
 *   [origin] sits on.
 * @param side Which side (start/end) the reveal is advancing from; the other determinant, with
 *   [axis], of which edge [origin] sits on.
 * @param progress How far the turn has progressed, in `[0, 1]`; clamped into that range.
 * @param touchCrossAxis Where the touch sits along the page's cross axis, in `[0, 1]`; clamped
 *   into that range; positions [origin] along the edge.
 * @return The circle's origin, current center, and current radius.
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
 * The shadow ring for [PageAnimation.CIRCLE_REVEAL], derived from the same circle
 * [foundationCircleRevealSpec] computes: a ring [FoundationCircleRevealShadowWidth] pixels wide
 * just inside the circle's edge, with alpha following the same half-sine build/fade curve as the
 * other reveal shadows in this file.
 *
 * @param size The slot's size.
 * @param axis Whether the turn runs along the horizontal or vertical axis.
 * @param side Which side (start/end) the reveal is advancing from.
 * @param progress How far the turn has progressed, in `[0, 1]`.
 * @param touchCrossAxis Where the touch sits along the page's cross axis, in `[0, 1]`.
 * @return The shadow ring's center, radius, inner radius, and alpha.
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
 * The clip path for [PageAnimation.CIRCLE_REVEAL], built from [foundationCircleRevealSpec]'s
 * circle — a thin wrapper that exists so [Modifier.foundationCircleRevealClip] can hand a plain
 * [Path] to `clipPath` without repeating the oval-construction boilerplate at its call site.
 *
 * @param size The slot's size.
 * @param axis Whether the turn runs along the horizontal or vertical axis.
 * @param side Which side (start/end) the reveal is advancing from.
 * @param progress How far the turn has progressed, in `[0, 1]`.
 * @param touchCrossAxis Where the touch sits along the page's cross axis, in `[0, 1]`.
 * @return An oval [Path] matching the reveal circle's current geometry.
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
 * Builds a fluid-edge reveal polygon from a fresh, throwaway [FoundationFluidEdge] driven straight
 * to [progress]/[touchCrossAxis] and settled with 8 ticks at the default frame unit, rather than
 * reading the pager's live, spring-animated edge. Meant for callers that want the fluid shape's
 * resting geometry for one set of inputs — such as a test asserting on the polygon at a known
 * progress — without wiring up a real gesture/animation loop; production drawing goes through the
 * sibling overload below that takes an already-live [FoundationFluidEdge] instead, using the
 * shared edge that [FoundationFluidEdge.tick] actually animates frame by frame.
 *
 * @param size The slot's size.
 * @param axis Whether the turn runs along the horizontal or vertical axis.
 * @param side Which side (start/end) the fluid edge advances from.
 * @param progress How far the turn has progressed, in `[0, 1]`; the edge's resting target.
 * @param touchCrossAxis Where the touch sits along the page's cross axis, in `[0, 1]`.
 * @param pointCount How many points the throwaway edge is built with.
 * @return The polygon outline of the fluid edge once settled at [progress].
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
 * Builds the fluid-edge reveal polygon from [edge]'s current, already-animated point positions:
 * the frame boundary on the far side (start or end, per [side]) closed off by the edge's own
 * points down its near side, in canonical horizontal-turn space swapped back to [axis]. This is
 * the overload production drawing actually uses — via [buildFoundationFluidPath] for the clip
 * shape and directly for the shadow polygons in [buildFoundationFluidShadowPolygon] — reading
 * whatever state [edge] is in right now rather than settling a fresh one.
 *
 * @param size The slot's size.
 * @param axis Whether the turn runs along the horizontal or vertical axis.
 * @param side Which side (start/end) the fluid edge advances from; the frame corners on the
 *   opposite side close off the polygon.
 * @param edge The fluid edge whose current point positions to trace.
 * @return The polygon outline of [edge]'s current shape.
 */
internal fun buildFoundationFluidPolygon(
    size: FoundationPagerSize,
    axis: FoundationPagerAxis,
    side: FoundationFluidSide,
    edge: FoundationFluidEdge,
): List<FoundationPagerPoint> {
    val canonicalSize = axis.toCanonicalSize(size)
    val edgePoints = edge.points.map { point ->
        val x = (canonicalSize.width * point.x).coerceIn(0f, canonicalSize.width)
        val y = canonicalSize.height * point.y
        when (side) {
            FoundationFluidSide.Start -> FoundationPagerPoint(x, y)
            FoundationFluidSide.End -> FoundationPagerPoint(canonicalSize.width - x, y)
        }
    }
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
    return canonicalPoints.map(axis::fromCanonical)
}

/**
 * The clip path for [PageAnimation.FLUID_PAGER], built from [edge]'s current polygon — the thin
 * [Path]-producing wrapper [Modifier.foundationFluidClip]'s [Shape] calls on every
 * [Shape.createOutline], taking a Compose [Size] directly since that is what the outline callback
 * receives.
 *
 * @param size The slot's size, as given to a [Shape.createOutline] call.
 * @param axis Whether the turn runs along the horizontal or vertical axis.
 * @param side Which side (start/end) the fluid edge advances from.
 * @param edge The fluid edge whose current point positions to trace.
 * @return A closed [Path] tracing [edge]'s current polygon.
 */
private fun buildFoundationFluidPath(
    size: Size,
    axis: FoundationPagerAxis,
    side: FoundationFluidSide,
    edge: FoundationFluidEdge,
): Path = buildFoundationFluidPolygon(
    size = FoundationPagerSize(size.width, size.height),
    axis = axis,
    side = side,
    edge = edge,
).toPath()

/**
 * A band [width] pixels wide trailing behind the fluid edge's current shape, used as the outline
 * for [foundationFluidShadow]'s cast and contact shadows (called once per shadow with a different
 * [width] for each). Built by taking the edge's own points and a second copy of them offset by
 * [width] toward the edge's origin side, then joining the two point sets into one closed ring
 * rather than an open strip, so the result is a fillable polygon rather than just a curve.
 *
 * @param size The slot's size.
 * @param axis Whether the turn runs along the horizontal or vertical axis.
 * @param side Which side (start/end) the fluid edge advances from.
 * @param edge The fluid edge whose current point positions to trace.
 * @param width How wide the shadow band is, in pixels.
 * @return The closed polygon outline of the shadow band.
 */
internal fun buildFoundationFluidShadowPolygon(
    size: FoundationPagerSize,
    axis: FoundationPagerAxis,
    side: FoundationFluidSide,
    edge: FoundationFluidEdge,
    width: Float,
): List<FoundationPagerPoint> {
    val canonicalSize = axis.toCanonicalSize(size)
    val edgePoints = edge.points.map { point ->
        val x = (canonicalSize.width * point.x).coerceIn(0f, canonicalSize.width)
        val y = canonicalSize.height * point.y
        when (side) {
            FoundationFluidSide.Start -> FoundationPagerPoint(x, y)
            FoundationFluidSide.End -> FoundationPagerPoint(canonicalSize.width - x, y)
        }
    }
    val shadowPoints = edgePoints.map { point ->
        val shadowX = when (side) {
            FoundationFluidSide.Start -> point.x + width
            FoundationFluidSide.End -> point.x - width
        }.coerceIn(0f, canonicalSize.width)
        FoundationPagerPoint(shadowX, point.y)
    }
    return (edgePoints + shadowPoints.asReversed()).map(axis::fromCanonical)
}


/**
 * Traces this list of points as a closed [Path]: a `moveTo` the first point, a `lineTo` every
 * point after it, then `close()` back to the start — the shared final step for every fluid-edge
 * polygon built in this file, whether it is already in real screen coordinates or still in
 * canonical horizontal-turn space.
 *
 * @receiver The polygon's points, in the order they should be joined.
 * @return A closed [Path] tracing the receiver.
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
 * [toPath] for a polygon still expressed in canonical horizontal-turn space, mapping every point
 * back to [axis]'s real orientation first. Currently has no call site in this file or its tests —
 * every polygon builder here maps its points back to [axis] itself before calling the no-argument
 * [toPath] directly, rather than deferring that mapping to this overload.
 *
 * @receiver The polygon's points in canonical horizontal-turn space.
 * @param axis The axis to map the points back into before tracing.
 * @return A closed [Path] tracing the receiver in [axis]'s real orientation.
 */
private fun List<FoundationPagerPoint>.toPath(axis: FoundationPagerAxis): Path =
    map(axis::fromCanonical).toPath()

/**
 * The [FoundationPagerPage.Previous] slot's raw Foundation pager index. This and the next three
 * constants are not "tuned" at all: each is fixed by something else in the system — here, the
 * pager's fixed 3-slot (previous/current/next) layout — so changing one without changing what it
 * is derived from would make it wrong rather than merely different.
 */
private const val FoundationPreviousPage = 0

/** The [FoundationPagerPage.Current] slot's raw Foundation pager index, and the slot the pager always settles back to between turns. */
private const val FoundationCenterPage = 1

/** The [FoundationPagerPage.Next] slot's raw Foundation pager index — the pager's fixed 3-slot layout. */
private const val FoundationNextPage = 2

/** The pager's fixed slot count (previous/current/next); always 3, never a function of [pageCount][FoundationEffectPager]. */
private const val FoundationPagerPageCount = 3

/**
 * The tap zone boundary between the previous-page zone and the middle toggle-controls zone, as a
 * fraction of the pane's extent along the turn axis. Paired with [FoundationNextTapZoneRatio] to
 * form a symmetric 25/50/25 split — the two must sum to `1` so the previous- and next-page zones
 * are equal width with the toggle zone exactly between them; see `foundationPagerTapAction`.
 */
private const val FoundationPreviousTapZoneRatio = 0.25f

/**
 * The tap zone boundary between the middle toggle-controls zone and the next-page zone, as a
 * fraction of the pane's extent along the turn axis. Paired with [FoundationPreviousTapZoneRatio]
 * to form the same symmetric 25/50/25 split; see `foundationPagerTapAction`.
 */
private const val FoundationNextTapZoneRatio = 0.75f

/**
 * How long a settle animation (a programmatic move to a neighbour, or a released manual drag
 * committing to a turn) takes, in milliseconds. Unlike the constants above and below, this is a
 * genuine tuning choice — an animation duration picked for feel — not a derived or physics value.
 */
private const val FoundationPagerSettleMillis = 220

/**
 * The minimum movement along the turn axis, in pixels, before a manual drag commits to a
 * direction in [foundationGestureSide]. A tuning choice for how much slop to allow before
 * treating a drag as directional, not a derived value.
 */
private const val FoundationGestureDirectionThresholdPx = 1f

/**
 * How many points make up a [FoundationFluidEdge]'s chain by default. This and the next eight
 * constants are the fluid-edge spring physics: [FoundationFluidEdge.tick]'s KDoc documents them
 * together as one system — how they interact, their stability bound, and the two
 * similarly-named damping constants' different roles — since a change to any one of them shifts
 * what the others mean. The one-liners on each below only restate that constant's own role; see
 * [FoundationFluidEdge.tick] for the derivations and the numeric checks behind them. This one
 * interacts with [FoundationFluidTouchRadius]: point spacing is `1 / (pointCount - 1)`, which
 * determines how many points the touch radius actually covers.
 */
private const val FoundationFluidPointCount = 25

/** How far, as a fraction of the chain's cross-axis extent, the touch-tension term in [FoundationFluidEdge.tick] reaches before its influence falls to zero. */
private const val FoundationFluidTouchRadius = 0.24f

/** The duration of one frame at 60 fps in milliseconds, derived from the frame rate rather than chosen — used to normalise [FoundationFluidEdge.tick]'s `frameUnits` to actual elapsed time. */
private const val FoundationFrameMillis = 1000f / 60f

/** The weight of the restoring force pulling every [FoundationFluidEdge] point back toward `x = 0`; see [FoundationFluidEdge.tick]. */
private const val FoundationFluidEdgeTension = 0.01f

/** The weight of the restoring force pulling every [FoundationFluidEdge] point toward `x = 1` once past [FoundationFluidCompleteThreshold]; equal to [FoundationFluidEdgeTension] by design, pulling the opposite way. */
private const val FoundationFluidFarEdgeTension = 0.01f

/** The weight of the force pulling a [FoundationFluidEdge] point toward the touch's target progress, scaled by proximity to the touch within [FoundationFluidTouchRadius]; see [FoundationFluidEdge.tick]. */
private const val FoundationFluidTouchTension = 0.10f

/** The weight of the force pulling a [FoundationFluidEdge] point toward each neighbouring point; doubled for an interior point, which is central to [FoundationFluidEdge.tick]'s stability analysis. */
private const val FoundationFluidPointTension = 0.25f

/** The per-frame velocity decay multiplier applied to a [FoundationFluidEdge] point while a touch is active; see [FoundationFluidEdge.tick] for its e-folding time and how it differs in kind from [FoundationFluidReleaseDamping]. */
private const val FoundationFluidDamping = 0.90f

/** The per-frame fraction of remaining distance closed when a released [FoundationFluidEdge] interpolates back toward its target; numerically equal to [FoundationFluidCompleteThreshold] by coincidence only — see [FoundationFluidEdge.tick]. */
private const val FoundationFluidReleaseDamping = 0.82f

/** The turn progress past which [FoundationFluidEdge.tick] enables the far-edge tension; numerically equal to [FoundationFluidReleaseDamping] by coincidence only — the two gate unrelated behavior. */
private const val FoundationFluidCompleteThreshold = 0.82f

/**
 * The `graphicsLayer` camera distance used by every 3D tilt/rotation in this file
 * ([FoundationWholePageFlipBox], [FoundationPageFlipHalfBox], [Modifier.foundationMovieCarouselLayer]):
 * a tuning choice keeping the perspective foreshortening at full rotation subtle rather than
 * fisheye-distorting the page, not a derived value.
 */
private const val FoundationCameraDistance = 64f

/**
 * The maximum tilt, in degrees, [Modifier.foundationMovieCarouselLayer]'s (currently inert, see
 * [FoundationMovieTranslationRatio]) rotation branch would apply to a fully receded
 * [PageAnimation.MOVIE_CAROUSEL] page. A tuning choice for how far that tilt would lean, not a
 * derived value.
 */
private const val FoundationMovieRotationDegrees = 12f

/**
 * The fraction of a page's own size [Modifier.foundationMovieCarouselLayer] would translate a
 * receding [PageAnimation.MOVIE_CAROUSEL] page by. Currently `0f`, which makes
 * `FoundationMovieCarouselSpec.translationFraction` always zero and disables that translation and
 * its paired rotation entirely — only the scale/alpha shrink is presently visible for this style.
 */
private const val FoundationMovieTranslationRatio = 0f

/** The minimum scale a fully receded [PageAnimation.MOVIE_CAROUSEL] page shrinks to, per `foundationMovieCarouselSpec`. A tuning choice for how much the carousel shrinks, not a derived value. */
private const val FoundationMovieMinScale = 0.9f

/** The minimum alpha a fully receded [PageAnimation.MOVIE_CAROUSEL] page fades to, per `foundationMovieCarouselSpec`. A tuning choice for how much the carousel fades, not a derived value. */
private const val FoundationMovieMinAlpha = 0.55f

/**
 * A geometric half turn, in degrees: the rotation a [FoundationPageFlipLayout.SplitHalfFold] half
 * sweeps through end to end, per `foundationPageFlipHalfSpec`. Back with
 * [FoundationPreviousPage]'s group above — derived from the geometry of a flip, not tuned.
 */
private const val FoundationPageFlipRotationDegrees = 180f

/**
 * A geometric quarter turn, in degrees: the rotation a [FoundationPageFlipLayout.WholePage] sheet
 * sweeps through end to end, per `foundationWholePageFlipSpec`. Derived from the geometry of a
 * flip, not tuned.
 */
private const val FoundationWholePageFlipRotationDegrees = 90f

/**
 * The peak alpha of the fluid-reveal's cast shadow ([foundationFluidShadow]). This and the
 * remaining constants in this file are plain alphas or pixel widths for one of this file's
 * cast/contact/hinge shadows, chosen by eye for how the shadow reads and nothing more — there is
 * no formula, ratio, or physical derivation behind any of them the way there is for the
 * fluid-edge constants above.
 */
private const val FoundationRevealShadowAlpha = 0.28f

/** The width, in pixels, of the fluid-reveal's cast shadow ([foundationFluidShadow]); chosen by eye, no formula. */
private const val FoundationRevealShadowWidth = 58f

/** The width, in pixels, of the fluid-reveal's narrower contact shadow ([foundationFluidShadow]); chosen by eye, no formula. */
private const val FoundationRevealContactShadowWidth = 3f

/** The peak alpha of the circle-reveal's shadow ring (`foundationCircleRevealShadowSpec`); chosen by eye, no formula. */
private const val FoundationCircleRevealShadowAlpha = 0.22f

/** The width, in pixels, of the circle-reveal's shadow ring (`foundationCircleRevealShadowSpec`); chosen by eye, no formula. */
private const val FoundationCircleRevealShadowWidth = 30f

/** The peak alpha of the movie-carousel's darkening overlay (`foundationMovieCarouselDimAlpha`); chosen by eye, no formula. */
private const val FoundationMovieShadowAlpha = 0.16f

/** The peak alpha of the movie-carousel's incoming-edge shadow ([Modifier.foundationMovieCarouselShadow]); chosen by eye, no formula. */
private const val FoundationMovieEdgeShadowAlpha = 0.28f

/** The width, in pixels, of the movie-carousel's incoming-edge shadow ([Modifier.foundationMovieCarouselShadow]); chosen by eye, no formula. */
private const val FoundationMovieShadowWidth = 54f

/** The PAGE_FLIP cast shadow's peak opacity, shared by its pure lighting spec and width normalisation. */
private const val FoundationPageFlipMaxCastAlpha = 0.22f

/** The minimum PAGE_FLIP cast-shadow width in pixels before lift-dependent growth. */
private const val FoundationPageFlipPageShadowWidth = 44f

/** The additional fraction of the page extent covered by the cast shadow while the leaf is edge-on. */
private const val FoundationPageFlipCastGrowthRatio = 0.18f

/** The narrow PAGE_FLIP contact-shadow width in pixels at the outer edge or spread spine. */
private const val FoundationPageFlipContactWidth = 3f

/** The fraction of rim intensity reused as soft paper-colored bounce light on the back face. */
private const val FoundationPageFlipBackLightRatio = 0.35f

/** The PAGE_FLIP turning-edge highlight width in pixels. */
private const val FoundationPageFlipRimWidth = 2f

/** The surface-gradient width as a fraction of the leaf extent. */
private const val FoundationPageFlipHingeWidthRatio = 0.22f

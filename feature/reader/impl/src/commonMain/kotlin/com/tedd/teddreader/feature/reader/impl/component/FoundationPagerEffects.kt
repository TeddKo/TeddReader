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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FoundationEffectPager(
    pageKey: Int,
    pageCount: Int,
    pageStep: Int,
    pageTurnMode: PageTurnMode,
    pageAnimation: PageAnimation,
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
    val previousPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, -1)
    val nextPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, 1)
    val pageFlipLayout = foundationPageFlipLayout(pageStep = pageStep, paneCount = paneCount)
    var isManualDragInProgress by remember { mutableStateOf(false) }
    val manualDragDistancePx = remember { floatArrayOf(0f) }
    LaunchedEffect(pageMoveRequest?.id) {
        val request = pageMoveRequest ?: return@LaunchedEffect
        try {
            val targetPagerPage = when (request.movement) {
                ReaderPageMovement.Previous -> FoundationPreviousPage.takeIf { previousPage != null }
                ReaderPageMovement.Next -> FoundationNextPage.takeIf { nextPage != null }
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

    LaunchedEffect(pageKey, pageCount, pageStep) {
        fluidEdge.reset()
        if (pagerState.currentPage != FoundationCenterPage) {
            pagerState.scrollToPage(FoundationCenterPage)
        }
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
    LaunchedEffect(pagerState, pageKey, pageCount, pageStep, isManualDragInProgress) {
        snapshotFlow { Triple(pagerState.currentPage, pagerState.isScrollInProgress, isManualDragInProgress) }
            .filter { (_, isScrollInProgress, manualInProgress) -> !isScrollInProgress && !manualInProgress }
            .map { (page, _, _) -> page }
            .distinctUntilChanged()
            .collect { page ->
                when (page) {
                    FoundationPreviousPage -> {
                        pagerState.scrollToPage(FoundationCenterPage)
                        if (previousPage != null) latestOnPreviousPage()
                    }
                    FoundationNextPage -> {
                        pagerState.scrollToPage(FoundationCenterPage)
                        if (nextPage != null) latestOnNextPage() else onAutoScrollStop()
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
        Modifier.pointerInput(axis, previousPage != null, nextPage != null) {
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
                            hasNextPage = nextPage != null,
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
                    hasNextPage = nextPage != null,
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
                    hasNextPage = nextPage != null,
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

    val tapModifier = Modifier.pointerInput(axis, pagerState, isAutoScrollEnabled, onToggleControls, previousPage, nextPage) {
        detectTapGestures { position ->
            if (isAutoScrollEnabled) {
                onToggleControls()
                return@detectTapGestures
            }
            val primary = if (axis == FoundationPagerAxis.Horizontal) position.x else position.y
            val extent = if (axis == FoundationPagerAxis.Horizontal) size.width else size.height
            when {
                primary < extent * FoundationPreviousTapZoneRatio -> {
                    if (previousPage != null) {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(
                                page = FoundationPreviousPage,
                                animationSpec = settleAnimationSpec,
                            )
                        }
                    }
                }
                primary > extent * FoundationNextTapZoneRatio -> {
                    if (nextPage != null) {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(
                                page = FoundationNextPage,
                                animationSpec = settleAnimationSpec,
                            )
                        }
                    }
                }
                else -> onToggleControls()
            }
        }
    }

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
                pageFlipLayout = pageFlipLayout,
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
            FoundationPageFlipAwareBox(
                pageAnimation = pageAnimation,
                axis = axis,
                pageOffset = pageOffset,
                pageFlipLayout = pageFlipLayout,
                isCurrentPage = pagerPage == FoundationCenterPage,
                modifier = pageModifier(pagerPage),
            ) {
                val documentPage = readerPagerAdjacentPage(
                    pageKey,
                    pageCount,
                    pageStep,
                    pagerPage - FoundationCenterPage,
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
            FoundationPageFlipAwareBox(
                pageAnimation = pageAnimation,
                axis = axis,
                pageOffset = pageOffset,
                pageFlipLayout = pageFlipLayout,
                isCurrentPage = pagerPage == FoundationCenterPage,
                modifier = pageModifier(pagerPage),
            ) {
                val documentPage = readerPagerAdjacentPage(
                    pageKey,
                    pageCount,
                    pageStep,
                    pagerPage - FoundationCenterPage,
                )
                if (documentPage != null) content(documentPage)
            }
        }
    }
}

@Composable
internal fun FoundationCurlPager(
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
        pageMoveRequest = pageMoveRequest,
        onPageMoveRequestConsumed = onPageMoveRequestConsumed,
        onPreviousPage = onPreviousPage,
        onNextPage = onNextPage,
        onToggleControls = onToggleControls,
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

@Composable
private fun FoundationPageFlipAwareBox(
    pageAnimation: PageAnimation,
    axis: FoundationPagerAxis,
    pageOffset: Float,
    pageFlipLayout: FoundationPageFlipLayout,
    isCurrentPage: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (pageAnimation == PageAnimation.PAGE_FLIP && isCurrentPage) {
        when (pageFlipLayout) {
            FoundationPageFlipLayout.WholePage -> {
                FoundationWholePageFlipBox(
                    axis = axis,
                    pageOffset = pageOffset,
                    modifier = modifier,
                    content = content,
                )
            }
            FoundationPageFlipLayout.SplitHalfFold -> {
                Box(modifier = modifier) {
                    when (axis) {
                        FoundationPagerAxis.Horizontal -> {
                            FoundationPageFlipHalfBox(FoundationPageFlipHalf.Left, pageOffset, content = content)
                            FoundationPageFlipHalfBox(FoundationPageFlipHalf.Right, pageOffset, content = content)
                        }
                        FoundationPagerAxis.Vertical -> {
                            FoundationPageFlipHalfBox(FoundationPageFlipHalf.Top, pageOffset, content = content)
                            FoundationPageFlipHalfBox(FoundationPageFlipHalf.Bottom, pageOffset, content = content)
                        }
                    }
                }
            }
        }
    } else {
        Box(modifier = modifier) {
            content()
        }
    }
}

@Composable
private fun FoundationWholePageFlipBox(
    axis: FoundationPagerAxis,
    pageOffset: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val spec = foundationWholePageFlipSpec(axis = axis, pageOffset = pageOffset)
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                cameraDistance = FoundationCameraDistance
                transformOrigin = TransformOrigin(spec.transformOriginX, spec.transformOriginY)
                rotationX = spec.rotationX
                rotationY = spec.rotationY
            },
    ) {
        content()
    }
}

@Composable
private fun FoundationPageFlipHalfBox(
    half: FoundationPageFlipHalf,
    pageOffset: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val spec = foundationPageFlipHalfSpec(half, pageOffset)
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
            .foundationPageFlipHalfShadow(half, pageOffset),
    ) {
        content()
    }
}

private fun Modifier.foundationPageFlipHalfShadow(
    half: FoundationPageFlipHalf,
    pageOffset: Float,
): Modifier = drawWithCache {
    val spec = foundationPageFlipHalfSpec(half, pageOffset)
    val rotationProgress = (max(abs(spec.rotationX), abs(spec.rotationY)) / 90f).coerceIn(0f, 1f)
    if (rotationProgress <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }

    val ambientAlpha = FoundationPageFlipHalfAmbientAlpha * rotationProgress
    val hingeAlpha = FoundationPageFlipHalfHingeAlpha * rotationProgress
    val hingeWidth = if (half == FoundationPageFlipHalf.Left || half == FoundationPageFlipHalf.Right) {
        size.width * FoundationPageFlipHingeWidthRatio
    } else {
        size.height * FoundationPageFlipHingeWidthRatio
    }

    onDrawWithContent {
        drawContent()
        drawRect(Color.Black.copy(alpha = ambientAlpha))

        when (half) {
            FoundationPageFlipHalf.Left -> {
                val hinge = size.width / 2f
                val left = (hinge - hingeWidth).coerceAtLeast(0f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = hingeAlpha)),
                        startX = left,
                        endX = hinge,
                    ),
                    topLeft = Offset(left, 0f),
                    size = Size((hinge - left).coerceAtLeast(0f), size.height),
                )
            }
            FoundationPageFlipHalf.Right -> {
                val hinge = size.width / 2f
                val right = (hinge + hingeWidth).coerceAtMost(size.width)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Black.copy(alpha = hingeAlpha), Color.Transparent),
                        startX = hinge,
                        endX = right,
                    ),
                    topLeft = Offset(hinge, 0f),
                    size = Size((right - hinge).coerceAtLeast(0f), size.height),
                )
            }
            FoundationPageFlipHalf.Top -> {
                val hinge = size.height / 2f
                val top = (hinge - hingeWidth).coerceAtLeast(0f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = hingeAlpha)),
                        startY = top,
                        endY = hinge,
                    ),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, (hinge - top).coerceAtLeast(0f)),
                )
            }
            FoundationPageFlipHalf.Bottom -> {
                val hinge = size.height / 2f
                val bottom = (hinge + hingeWidth).coerceAtMost(size.height)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = hingeAlpha), Color.Transparent),
                        startY = hinge,
                        endY = bottom,
                    ),
                    topLeft = Offset(0f, hinge),
                    size = Size(size.width, (bottom - hinge).coerceAtLeast(0f)),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.foundationEffectPageModifier(
    pagerState: PagerState,
    pagerPage: Int,
    axis: FoundationPagerAxis,
    pageAnimation: PageAnimation,
    pageOffset: Float,
    pageFlipLayout: FoundationPageFlipLayout,
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
            .zIndex(foundationPageFlipZIndex(pageOffset))
            .run {
                if (page == FoundationPagerPage.Current) {
                    foundationPageFlipPageShadow(
                        axis = axis,
                        pageOffset = pageOffset,
                        layout = pageFlipLayout,
                    )
                } else {
                    this
                }
            }

        else -> Modifier
    }
}

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

internal fun foundationMovieCarouselShadowSide(page: FoundationPagerPage): FoundationFluidSide? = when (page) {
    FoundationPagerPage.Previous -> FoundationFluidSide.End
    FoundationPagerPage.Next -> FoundationFluidSide.Start
    FoundationPagerPage.Current -> null
}

internal fun foundationMovieCarouselDimAlpha(progress: Float): Float =
    (FoundationMovieShadowAlpha * sin(progress.coerceIn(0f, 1f) * PI.toFloat())).coerceAtLeast(0f)

private fun Modifier.foundationPageFlipPageShadow(
    axis: FoundationPagerAxis,
    pageOffset: Float,
    layout: FoundationPageFlipLayout,
): Modifier = drawWithCache {
    val shadowSide = foundationPageFlipShadowSide(pageOffset)
    val progress = abs(pageOffset).coerceIn(0f, 1f)
    val alpha = FoundationPageFlipPageShadowAlpha * sin(progress * PI.toFloat())
    if (shadowSide == null || alpha <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }

    onDrawWithContent {
        drawContent()
        if (axis == FoundationPagerAxis.Horizontal) {
            val centerX = size.width / 2f
            if (shadowSide == FoundationFluidSide.Start) {
                val left = when (layout) {
                    FoundationPageFlipLayout.WholePage -> 0f
                    FoundationPageFlipLayout.SplitHalfFold -> (centerX - FoundationPageFlipPageShadowWidth).coerceAtLeast(0f)
                }
                val right = when (layout) {
                    FoundationPageFlipLayout.WholePage -> FoundationPageFlipPageShadowWidth.coerceAtMost(size.width)
                    FoundationPageFlipLayout.SplitHalfFold -> centerX.coerceAtMost(size.width)
                }
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
                val left = when (layout) {
                    FoundationPageFlipLayout.WholePage -> (size.width - FoundationPageFlipPageShadowWidth).coerceAtLeast(0f)
                    FoundationPageFlipLayout.SplitHalfFold -> centerX
                }
                val right = when (layout) {
                    FoundationPageFlipLayout.WholePage -> size.width
                    FoundationPageFlipLayout.SplitHalfFold -> (centerX + FoundationPageFlipPageShadowWidth).coerceAtMost(size.width)
                }
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
                val top = when (layout) {
                    FoundationPageFlipLayout.WholePage -> 0f
                    FoundationPageFlipLayout.SplitHalfFold -> (centerY - FoundationPageFlipPageShadowWidth).coerceAtLeast(0f)
                }
                val bottom = when (layout) {
                    FoundationPageFlipLayout.WholePage -> FoundationPageFlipPageShadowWidth.coerceAtMost(size.height)
                    FoundationPageFlipLayout.SplitHalfFold -> centerY.coerceAtMost(size.height)
                }
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
                val top = when (layout) {
                    FoundationPageFlipLayout.WholePage -> (size.height - FoundationPageFlipPageShadowWidth).coerceAtLeast(0f)
                    FoundationPageFlipLayout.SplitHalfFold -> centerY
                }
                val bottom = when (layout) {
                    FoundationPageFlipLayout.WholePage -> size.height
                    FoundationPageFlipLayout.SplitHalfFold -> (centerY + FoundationPageFlipPageShadowWidth).coerceAtMost(size.height)
                }
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
}

internal fun foundationPageFlipShadowSide(pageOffset: Float): FoundationFluidSide? = when {
    pageOffset < 0f -> FoundationFluidSide.Start
    pageOffset > 0f -> FoundationFluidSide.End
    else -> null
}

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

private fun foundationGestureSide(
    axis: FoundationPagerAxis,
    gestureState: FoundationPagerGestureState,
): FoundationFluidSide? {
    if (!gestureState.active) return null
    val delta = axis.primary(gestureState.current) - axis.primary(gestureState.start)
    if (abs(delta) < FoundationGestureDirectionThresholdPx) return null
    return if (delta < 0f) FoundationFluidSide.End else FoundationFluidSide.Start
}

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

private fun Modifier.foundationHiddenWhenInactive(hidden: Boolean): Modifier = if (hidden) {
    graphicsLayer { alpha = 0f }
} else {
    this
}

private enum class FoundationRevealStyle {
    Fluid,
    Circle,
}

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

private fun foundationPageFlipZIndex(pageOffset: Float): Float {
    val distance = abs(pageOffset)
    return when {
        distance <= 0.5f -> 3f
        distance <= 1f -> 2f
        else -> 1f
    }
}

internal enum class FoundationPageFlipHalf {
    Top,
    Bottom,
    Left,
    Right,
}

internal enum class FoundationPageFlipLayout {
    WholePage,
    SplitHalfFold,
}

internal data class FoundationWholePageFlipSpec(
    val rotationX: Float,
    val rotationY: Float,
    val transformOriginX: Float,
    val transformOriginY: Float,
)

internal data class FoundationPageFlipHalfSpec(
    val rotationX: Float,
    val rotationY: Float,
)

internal fun foundationPageFlipLayout(pageStep: Int, paneCount: Int): FoundationPageFlipLayout =
    if (pageStep.coerceAtLeast(1) == 1 && paneCount.coerceAtLeast(1) == 1) {
        FoundationPageFlipLayout.WholePage
    } else {
        FoundationPageFlipLayout.SplitHalfFold
    }

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
                transformOriginX = 0f,
                transformOriginY = 0.5f,
            )
        } else {
            FoundationWholePageFlipSpec(
                rotationX = 0f,
                rotationY = -offset * FoundationWholePageFlipRotationDegrees,
                transformOriginX = 1f,
                transformOriginY = 0.5f,
            )
        }

        FoundationPagerAxis.Vertical -> if (offset <= 0f) {
            FoundationWholePageFlipSpec(
                rotationX = offset * FoundationWholePageFlipRotationDegrees,
                rotationY = 0f,
                transformOriginX = 0.5f,
                transformOriginY = 0f,
            )
        } else {
            FoundationWholePageFlipSpec(
                rotationX = offset * FoundationWholePageFlipRotationDegrees,
                rotationY = 0f,
                transformOriginX = 0.5f,
                transformOriginY = 1f,
            )
        }
    }
}

internal fun foundationPageFlipHalfSpec(
    half: FoundationPageFlipHalf,
    pageOffset: Float,
): FoundationPageFlipHalfSpec {
    val offset = pageOffset.coerceIn(-1f, 1f)
    val startOffset = max(offset, 0f)
    val endOffset = min(offset, 0f)
    return when (half) {
        FoundationPageFlipHalf.Top -> FoundationPageFlipHalfSpec(
            rotationX = (endOffset * FoundationPageFlipRotationDegrees).coerceIn(-90f, 0f),
            rotationY = 0f,
        )
        FoundationPageFlipHalf.Bottom -> FoundationPageFlipHalfSpec(
            rotationX = (startOffset * FoundationPageFlipRotationDegrees).coerceIn(0f, 90f),
            rotationY = 0f,
        )
        FoundationPageFlipHalf.Left -> FoundationPageFlipHalfSpec(
            rotationX = 0f,
            rotationY = -(endOffset * FoundationPageFlipRotationDegrees).coerceIn(-90f, 0f),
        )
        FoundationPageFlipHalf.Right -> FoundationPageFlipHalfSpec(
            rotationX = 0f,
            rotationY = -(startOffset * FoundationPageFlipRotationDegrees).coerceIn(0f, 90f),
        )
    }
}

private fun foundationPageFlipShape(half: FoundationPageFlipHalf): Shape = when (half) {
    FoundationPageFlipHalf.Top -> FoundationPageFlipTopShape
    FoundationPageFlipHalf.Bottom -> FoundationPageFlipBottomShape
    FoundationPageFlipHalf.Left -> FoundationPageFlipLeftShape
    FoundationPageFlipHalf.Right -> FoundationPageFlipRightShape
}

private val FoundationPageFlipTopShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, 0f, size.width, size.height / 2f))
}

private val FoundationPageFlipBottomShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, size.height / 2f, size.width, size.height))
}

private val FoundationPageFlipLeftShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, 0f, size.width / 2f, size.height))
}

private val FoundationPageFlipRightShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(size.width / 2f, 0f, size.width, size.height))
}

internal data class FoundationMovieCarouselSpec(
    val translationFraction: Float,
    val scale: Float,
    val alpha: Float,
)

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

internal fun foundationPagerShouldBlockDrag(
    primaryDelta: Float,
    hasPreviousPage: Boolean,
    hasNextPage: Boolean,
): Boolean = when {
    primaryDelta > 0f -> !hasPreviousPage
    primaryDelta < 0f -> !hasNextPage
    else -> false
}

private const val FoundationManualFlingVelocityThresholdPxPerSecond = 1000f
private const val FoundationManualDragDistanceThresholdRatio = 0.25f

private data class FoundationPagerGestureState(
    val start: Offset = Offset.Zero,
    val current: Offset = Offset.Zero,
    val last: Offset = Offset.Zero,
    val active: Boolean = false,
    val touched: Boolean = false,
) {
    fun touchPoint(): FoundationPagerPoint? {
        if (!touched) return null
        val touch = if (active) current else last
        return FoundationPagerPoint(touch.x, touch.y)
    }

    fun startPoint(): FoundationPagerPoint? {
        if (!touched) return null
        return FoundationPagerPoint(start.x, start.y)
    }
}

internal class FoundationFluidEdge(pointCount: Int = FoundationFluidPointCount) {
    val points: List<FoundationFluidPoint> = List(pointCount) { index ->
        FoundationFluidPoint(y = index.toFloat() / (pointCount - 1).toFloat())
    }
    var version by mutableStateOf(0)
        private set

    private var activeSide = FoundationFluidSide.Start
    private var progress = 0f
    private var touchCrossAxis = 0.5f
    private var touchActive = false

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

internal data class FoundationFluidPoint(
    var x: Float = 0f,
    val y: Float = 0f,
    var velocityX: Float = 0f,
)

internal data class FoundationActivePageTurn(
    val side: FoundationFluidSide,
    val progress: Float,
)

internal data class FoundationPagerPoint(
    val x: Float,
    val y: Float,
) {
    operator fun plus(other: FoundationPagerPoint): FoundationPagerPoint = FoundationPagerPoint(x + other.x, y + other.y)
    operator fun minus(other: FoundationPagerPoint): FoundationPagerPoint = FoundationPagerPoint(x - other.x, y - other.y)
    operator fun times(value: Float): FoundationPagerPoint = FoundationPagerPoint(x * value, y * value)
}

internal data class FoundationPagerSize(
    val width: Float,
    val height: Float,
)

internal enum class FoundationPagerAxis {
    Horizontal,
    Vertical,
    ;

    fun toCanonical(point: FoundationPagerPoint): FoundationPagerPoint = when (this) {
        Horizontal -> point
        Vertical -> FoundationPagerPoint(point.y, point.x)
    }

    fun fromCanonical(point: FoundationPagerPoint): FoundationPagerPoint = toCanonical(point)

    fun toCanonicalSize(size: FoundationPagerSize): FoundationPagerSize = when (this) {
        Horizontal -> size
        Vertical -> FoundationPagerSize(width = size.height, height = size.width)
    }

    fun toCanonicalSize(size: Size): Size = when (this) {
        Horizontal -> size
        Vertical -> Size(size.height, size.width)
    }

    fun fromCanonical(point: Offset): Offset = when (this) {
        Horizontal -> point
        Vertical -> Offset(point.y, point.x)
    }

    fun primary(point: Offset): Float = when (this) {
        Horizontal -> point.x
        Vertical -> point.y
    }
}

internal enum class FoundationFluidSide {
    Start,
    End,
}

internal enum class FoundationPagerPage(
    val pagerPage: Int,
    val side: FoundationFluidSide,
) {
    Previous(FoundationPreviousPage, FoundationFluidSide.Start),
    Current(FoundationCenterPage, FoundationFluidSide.End),
    Next(FoundationNextPage, FoundationFluidSide.End),
    ;

    companion object {
        fun fromPagerPage(page: Int): FoundationPagerPage = when (page) {
            FoundationPreviousPage -> Previous
            FoundationNextPage -> Next
            else -> Current
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun PagerState.foundationOffsetForPage(page: Int): Float =
    (currentPage - page) + currentPageOffsetFraction

@OptIn(ExperimentalFoundationApi::class)
fun PagerState.foundationAdjacentProgress(page: Int): Float =
    (1f - abs(foundationOffsetForPage(page))).coerceIn(0f, 1f)

internal fun foundationPagerLerp(start: Float, stop: Float, progress: Float): Float {
    val fraction = progress.coerceIn(0f, 1f)
    return start + (stop - start) * fraction
}

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

internal data class FoundationCircleRevealSpec(
    val origin: FoundationPagerPoint,
    val center: FoundationPagerPoint,
    val radius: Float,
)

internal data class FoundationCircleRevealShadowSpec(
    val center: FoundationPagerPoint,
    val radius: Float,
    val innerRadius: Float,
    val alpha: Float,
)

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

private fun List<FoundationPagerPoint>.toPath(axis: FoundationPagerAxis): Path =
    map(axis::fromCanonical).toPath()

private const val FoundationPreviousPage = 0
private const val FoundationCenterPage = 1
private const val FoundationNextPage = 2
private const val FoundationPagerPageCount = 3
private const val FoundationPreviousTapZoneRatio = 0.25f
private const val FoundationNextTapZoneRatio = 0.75f
private const val FoundationPagerSettleMillis = 220
private const val FoundationGestureDirectionThresholdPx = 1f
private const val FoundationFluidPointCount = 25
private const val FoundationFluidTouchRadius = 0.24f
private const val FoundationFrameMillis = 1000f / 60f
private const val FoundationFluidEdgeTension = 0.01f
private const val FoundationFluidFarEdgeTension = 0.01f
private const val FoundationFluidTouchTension = 0.10f
private const val FoundationFluidPointTension = 0.25f
private const val FoundationFluidDamping = 0.90f
private const val FoundationFluidReleaseDamping = 0.82f
private const val FoundationFluidCompleteThreshold = 0.82f
private const val FoundationCameraDistance = 64f
private const val FoundationMovieRotationDegrees = 12f
private const val FoundationMovieTranslationRatio = 0f
private const val FoundationMovieMinScale = 0.9f
private const val FoundationMovieMinAlpha = 0.55f
private const val FoundationPageFlipRotationDegrees = 180f
private const val FoundationWholePageFlipRotationDegrees = 90f
private const val FoundationRevealShadowAlpha = 0.28f
private const val FoundationRevealShadowWidth = 58f
private const val FoundationRevealContactShadowWidth = 3f
private const val FoundationCircleRevealShadowAlpha = 0.22f
private const val FoundationCircleRevealShadowWidth = 30f
private const val FoundationMovieShadowAlpha = 0.16f
private const val FoundationMovieEdgeShadowAlpha = 0.28f
private const val FoundationMovieShadowWidth = 54f
private const val FoundationPageFlipPageShadowAlpha = 0.18f
private const val FoundationPageFlipPageShadowWidth = 44f
private const val FoundationPageFlipHalfAmbientAlpha = 0.14f
private const val FoundationPageFlipHalfHingeAlpha = 0.36f
private const val FoundationPageFlipHingeWidthRatio = 0.22f

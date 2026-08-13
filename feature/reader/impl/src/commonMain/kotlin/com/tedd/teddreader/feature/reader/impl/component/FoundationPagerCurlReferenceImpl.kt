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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.clipPath
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Compose Foundation pager host with the pagecurl 1.5.1 interaction and rendering state machine.
 *
 * On a two-pane spread the curl runs on the outer leaf only: the leaf folds about the spine and
 * lands on the facing page, the way a real book turns. Single pane keeps the reference behavior.
 */
@Composable
internal fun FoundationPagerCurlReferenceImpl(
    pageKey: Int,
    pageCount: Int,
    pageStep: Int,
    pageTurnMode: PageTurnMode,
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
    val axis = if (pageTurnMode == PageTurnMode.HORIZONTAL) {
        FoundationReferenceCurlAxis.Horizontal
    } else {
        FoundationReferenceCurlAxis.Vertical
    }
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
        val leafOriginX = (canonicalSize.width - leafSize.width).toFloat()
        val backwardLeafScale = if (isSpread) {
            leafSize.width / ((canonicalSize.width - gutterPx) * spreadLeftWeight).coerceAtLeast(1f)
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
        var animationJob by remember(axis, leafSize) { mutableStateOf<Job?>(null) }

        suspend fun reset() {
            forwardEdge.snapTo(rightEdge)
            backwardEdge.snapTo(backwardRestEdge)
            if (pagerState.currentPage != FoundationReferenceCenterPage) {
                pagerState.scrollToPage(FoundationReferenceCenterPage)
            }
        }

        fun complete(direction: FoundationReferenceCurlDirection) {
            when (direction) {
                FoundationReferenceCurlDirection.Forward -> latestOnNextPage()
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
                val edge = if (direction == FoundationReferenceCurlDirection.Forward) forwardEdge else backwardEdge
                val start = if (direction == FoundationReferenceCurlDirection.Forward) rightEdge else backwardRestEdge
                val end = if (direction == FoundationReferenceCurlDirection.Forward) {
                    leftEdge
                } else {
                    backwardEndEdge
                }
                var completed = false
                try {
                    reset()
                    edge.animateTo(
                        targetValue = end,
                        animationSpec = foundationReferenceTapSpec(
                            direction = foundationReferenceCurlGeometryDirection(direction, isSpread),
                            size = leafSize,
                            durationMillisOverride = animationDurationMillis,
                        ),
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

        LaunchedEffect(pageKey, pageCount, pageStep, axis, leafSize) {
            reset()
            renderedPageKey = pageKey
        }

        val previousPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, -1)
        val nextPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, 1)
        val canGoBackward = previousPage != null
        val canGoForward = nextPage != null
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
                    pointerInput(forwardEdge, backwardEdge, canGoForward, canGoBackward) {
                        detectFoundationReferenceCurlGestures(
                            axis = axis,
                            canonicalSize = leafSize,
                            isSpread = isSpread,
                            leafOriginX = leafOriginX,
                            backwardLeafScale = backwardLeafScale,
                            leafWidth = leafSize.width.toFloat(),
                            scope = scope,
                            forwardEdge = forwardEdge,
                            backwardEdge = backwardEdge,
                            canGoForward = canGoForward,
                            canGoBackward = canGoBackward,
                            onDragStart = {
                                animationJob?.cancel()
                                scope.launch { reset() }
                            },
                            onComplete = { direction ->
                                complete(direction)
                            },
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
            val pageOffset = pagerPage - FoundationReferenceCenterPage
            val documentPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, pageOffset)
            val leafEdge = when (pageOffset) {
                -1 -> foundationReferenceVisibleCurlEdge(
                    pageKey,
                    renderedPageKey,
                    backwardEdge.value,
                    backwardRestEdge,
                )
                0 -> foundationReferenceVisibleCurlEdge(
                    pageKey,
                    renderedPageKey,
                    forwardEdge.value,
                    rightEdge,
                )
                else -> null
            }
            // In a spread the previous leaf paints its back face over the facing page, so it may
            // only be composed while it is actually being turned back.
            val skipSpreadPage = isSpread &&
                pageOffset == -1 &&
                (leafEdge == backwardRestEdge ||
                    foundationReferenceVisibleCurlEdge(
                        pageKey,
                        renderedPageKey,
                        forwardEdge.value,
                        rightEdge,
                    ) != rightEdge)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .foundationCancelPagerPlacement(axis, pageOffset)
                    .zIndex(foundationReferenceCurlZIndex(pageOffset))
                    .run {
                        if (isSpread || leafEdge == null) this else foundationReferenceDrawCurl(axis, leafEdge)
                    },
            ) {
                if (documentPage != null && !skipSpreadPage) {
                    if (isSpread) {
                        if (pageOffset == -1) {
                            FoundationReferenceBackwardSpread(
                                previousLeftPage = documentPage,
                                currentLeftPage = pageKey,
                                axis = axis,
                                leafEdge = requireNotNull(leafEdge),
                                gutter = spreadGutter,
                                leftWeight = spreadLeftWeight,
                                spreadModifier = spreadModifier,
                                paneContent = requireNotNull(paneContent),
                            )
                        } else {
                            FoundationReferenceSpread(
                                leftPage = documentPage,
                                axis = axis,
                                leafEdge = leafEdge,
                                gutter = spreadGutter,
                                leftWeight = spreadLeftWeight,
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
 * Two-pane spread: the static facing page plus the outer leaf that folds about the spine.
 *
 * The leaf carries both of its faces. [leftPage] + 1 is printed on the front, [leftPage] + 2 on the
 * back, so a forward fold lands the next page on the facing side exactly where the pager will place
 * it once the turn completes.
 */
@Composable
private fun FoundationReferenceSpread(
    leftPage: Int,
    axis: FoundationReferenceCurlAxis,
    leafEdge: FoundationReferenceCurlEdge?,
    gutter: Dp,
    leftWeight: Float,
    spreadModifier: Modifier,
    paneContent: @Composable (page: Int, modifier: Modifier) -> Unit,
) {
    Row(
        modifier = spreadModifier,
        horizontalArrangement = Arrangement.spacedBy(gutter),
    ) {
        paneContent(leftPage, Modifier.weight(leftWeight).fillMaxHeight())
        Box(modifier = Modifier.weight(1f - leftWeight).fillMaxHeight()) {
            paneContent(
                leftPage + 1,
                Modifier.fillMaxSize().run {
                    if (leafEdge == null) this else foundationReferenceDrawLeafFront(axis, leafEdge)
                },
            )
            if (leafEdge != null) {
                paneContent(
                    leftPage + 2,
                    Modifier
                        .fillMaxSize()
                        .foundationReferenceDrawLeafBack(axis, leafEdge)
                        // Pre-mirrored so the fold reflection lands the back face right-reading.
                        .graphicsLayer { scaleX = -1f },
                )
            }
        }
    }
}

@Composable
private fun FoundationReferenceBackwardSpread(
    previousLeftPage: Int,
    currentLeftPage: Int,
    axis: FoundationReferenceCurlAxis,
    leafEdge: FoundationReferenceCurlEdge,
    gutter: Dp,
    leftWeight: Float,
    spreadModifier: Modifier,
    paneContent: @Composable (page: Int, modifier: Modifier) -> Unit,
) {
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
                    .foundationReferenceDrawLeafFront(axis, leafEdge, mirrorHorizontally = true),
            )
            paneContent(
                previousLeftPage + 1,
                Modifier
                    .fillMaxSize()
                    .foundationReferenceDrawLeafBack(axis, leafEdge, mirrorHorizontally = true),
            )
        }
        Box(modifier = Modifier.weight(1f - leftWeight).fillMaxHeight())
    }
}

internal fun foundationReferenceLeafSize(
    canonicalSize: IntSize,
    isSpread: Boolean,
    gutterPx: Float,
    leftWeight: Float,
): IntSize {
    if (!isSpread) return canonicalSize
    val pagesWidth = (canonicalSize.width - gutterPx).coerceAtLeast(0f)
    val leafWidth = (pagesWidth * (1f - leftWeight.coerceIn(0f, 1f))).toInt().coerceAtLeast(1)
    return IntSize(leafWidth, canonicalSize.height)
}

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

internal fun foundationReferenceCurlZIndex(pageOffset: Int): Float =
    (1 - pageOffset).toFloat()

private data class FoundationReferenceDragConfig(
    val direction: FoundationReferenceCurlDirection,
    val edge: Animatable<FoundationReferenceCurlEdge, AnimationVector4D>,
    val start: FoundationReferenceCurlEdge,
    val end: FoundationReferenceCurlEdge,
)

private suspend fun PointerInputScope.detectFoundationReferenceCurlGestures(
    axis: FoundationReferenceCurlAxis,
    canonicalSize: IntSize,
    isSpread: Boolean,
    leafOriginX: Float,
    backwardLeafScale: Float,
    leafWidth: Float,
    scope: CoroutineScope,
    forwardEdge: Animatable<FoundationReferenceCurlEdge, AnimationVector4D>,
    backwardEdge: Animatable<FoundationReferenceCurlEdge, AnimationVector4D>,
    canGoForward: Boolean,
    canGoBackward: Boolean,
    onDragStart: () -> Unit,
    onComplete: (FoundationReferenceCurlDirection) -> Unit,
) {
    val velocityTracker = VelocityTracker()
    var config: FoundationReferenceDragConfig? = null
    var startOffset = Offset.Zero

    detectFoundationReferenceCustomDragGestures(
        onDragStart = { start, current ->
            val direction = foundationReferenceCurlDirection(
                start = axis.toCanonical(start),
                current = axis.toCanonical(current),
                canGoBackward = canGoBackward,
                canGoForward = canGoForward,
            )
            startOffset = direction?.let {
                foundationReferenceCurlLeafOffset(start, axis, it, isSpread, leafOriginX, backwardLeafScale, leafWidth)
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
            if (config != null) onDragStart()
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
                    leafOriginX,
                    backwardLeafScale,
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
                scope.launch {
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
                leafOriginX,
                backwardLeafScale,
                leafWidth,
            )
            velocityTracker.addPosition(change.uptimeMillis, current)
            scope.launch {
                dragConfig.edge.animateTo(
                    foundationReferenceCurlEdge(canonicalSize, startOffset, current),
                )
            }
        },
    )
}

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
                null -> Unit
            }
        },
    )
}

internal fun foundationReferenceCurlTapAction(
    position: Offset,
    size: IntSize,
    axis: FoundationReferenceCurlAxis,
    canGoBackward: Boolean,
    canGoForward: Boolean,
    isAutoScrollEnabled: Boolean = false,
): FoundationReferenceCurlTapAction? {
    val primary = axis.toCanonical(position).x
    val extent = axis.canonicalSize(size).width
    if (isAutoScrollEnabled) return FoundationReferenceCurlTapAction.ToggleControls
    return when {
        primary < extent * FoundationReferencePreviousTapZoneRatio ->
            FoundationReferenceCurlTapAction.Backward.takeIf { canGoBackward }
        primary > extent * FoundationReferenceNextTapZoneRatio ->
            FoundationReferenceCurlTapAction.Forward.takeIf { canGoForward }
        else -> FoundationReferenceCurlTapAction.ToggleControls
    }
}

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

internal fun foundationReferenceCurlLeafOffset(
    offset: Offset,
    axis: FoundationReferenceCurlAxis,
    direction: FoundationReferenceCurlDirection,
    isSpread: Boolean,
    forwardLeafOriginX: Float,
    backwardLeafScale: Float,
    leafWidth: Float,
): Offset {
    val canonical = axis.toCanonical(offset)
    val x = when {
        !isSpread -> canonical.x
        direction == FoundationReferenceCurlDirection.Forward -> canonical.x - forwardLeafOriginX
        else -> leafWidth - canonical.x * backwardLeafScale
    }.let { if (isSpread) it.coerceIn(0f, leafWidth) else it }
    return Offset(x, canonical.y)
}

internal fun foundationReferenceCurlGeometryDirection(
    direction: FoundationReferenceCurlDirection,
    isSpread: Boolean,
): FoundationReferenceCurlDirection =
    if (isSpread && direction == FoundationReferenceCurlDirection.Backward) {
        FoundationReferenceCurlDirection.Forward
    } else {
        direction
    }

internal fun foundationReferenceVisibleCurlEdge(
    pageKey: Int,
    renderedPageKey: Int,
    animatedEdge: FoundationReferenceCurlEdge,
    restingEdge: FoundationReferenceCurlEdge,
): FoundationReferenceCurlEdge =
    if (pageKey == renderedPageKey) animatedEdge else restingEdge

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

private fun Modifier.foundationReferenceDrawCurl(
    axis: FoundationReferenceCurlAxis,
    edge: FoundationReferenceCurlEdge,
): Modifier = drawWithCache {
    val canonicalSize = axis.canonicalSize(IntSize(size.width.toInt(), size.height.toInt()))
    if (edge == FoundationReferenceCurlEdge.left(canonicalSize)) {
        return@drawWithCache onDrawWithContent { }
    }
    if (edge == FoundationReferenceCurlEdge.right(canonicalSize)) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    val fold = foundationReferenceCurlFold(axis, edge, canonicalSize)
        ?: return@drawWithCache onDrawWithContent { drawContent() }

    onDrawWithContent {
        clipPath(fold.clippedPath) {
            this@onDrawWithContent.drawContent()
        }
        withTransform({ fold.applyTo(this, axis) }) {
            fold.drawShadow(this, axis)
            clipPath(fold.polygon.toPath(axis)) {
                this@onDrawWithContent.drawContent()
                drawRect(Color.White.copy(alpha = FoundationReferenceBackOverlayAlpha))
            }
        }
    }
}

/** Leaf front face: everything on the spine side of the fold line stays flat. */
private fun Modifier.foundationReferenceDrawLeafFront(
    axis: FoundationReferenceCurlAxis,
    edge: FoundationReferenceCurlEdge,
    mirrorHorizontally: Boolean = false,
): Modifier = drawWithCache {
    val canonicalSize = axis.canonicalSize(IntSize(size.width.toInt(), size.height.toInt()))
    if (edge == FoundationReferenceCurlEdge.left(canonicalSize)) {
        return@drawWithCache onDrawWithContent { }
    }
    if (edge == FoundationReferenceCurlEdge.right(canonicalSize)) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    val fold = foundationReferenceCurlFold(axis, edge, canonicalSize)
        ?: return@drawWithCache onDrawWithContent { drawContent() }

    onDrawWithContent {
        withTransform({ if (mirrorHorizontally) scale(-1f, 1f) }) {
            clipPath(fold.clippedPath) {
                withTransform({ if (mirrorHorizontally) scale(-1f, 1f) }) {
                    this@onDrawWithContent.drawContent()
                }
            }
        }
    }
}

/** Leaf back face: the folded-over part, reflected across the fold line onto the facing page. */
private fun Modifier.foundationReferenceDrawLeafBack(
    axis: FoundationReferenceCurlAxis,
    edge: FoundationReferenceCurlEdge,
    mirrorHorizontally: Boolean = false,
): Modifier = drawWithCache {
    val canonicalSize = axis.canonicalSize(IntSize(size.width.toInt(), size.height.toInt()))
    if (edge == FoundationReferenceCurlEdge.right(canonicalSize)) {
        return@drawWithCache onDrawWithContent { }
    }
    val fold = foundationReferenceCurlFold(axis, edge, canonicalSize)
        ?: return@drawWithCache onDrawWithContent { }

    onDrawWithContent {
        withTransform({ if (mirrorHorizontally) scale(-1f, 1f) }) {
            withTransform({ fold.applyTo(this, axis) }) {
                fold.drawShadow(this, axis)
                clipPath(fold.polygon.toPath(axis)) {
                    this@onDrawWithContent.drawContent()
                }
            }
        }
    }
}

private class FoundationReferenceCurlFold(
    val clippedPath: Path,
    val polygon: FoundationPagerCurlPolygon,
    val angle: Float,
    val pivot: Offset,
    val shadowOffset: Offset,
    val shadowRadius: Float,
) {
    fun applyTo(scope: DrawTransform, axis: FoundationReferenceCurlAxis) {
        if (axis == FoundationReferenceCurlAxis.Horizontal) {
            scope.scale(-1f, 1f, pivot = pivot)
            scope.rotateRad(angle, pivot = pivot)
        } else {
            scope.scale(1f, -1f, pivot = pivot)
            scope.rotateRad(-angle, pivot = pivot)
        }
    }

    fun drawShadow(scope: DrawScope, axis: FoundationReferenceCurlAxis) {
        scope.drawFoundationPagerCurlShadow(
            polygon = polygon,
            axis = axis,
            radius = shadowRadius,
            shadowOffset = shadowOffset,
            color = Color.Black.copy(alpha = FoundationReferenceShadowAlpha),
        )
    }
}

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

private fun foundationReferenceCurlPolygon(
    width: Float,
    height: Float,
    topCurlOffset: Offset,
    bottomCurlOffset: Offset,
): FoundationPagerCurlPolygon {
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

internal data class FoundationReferenceCurlEdge(
    val top: Offset,
    val bottom: Offset,
) {
    companion object {
        val VectorConverter: TwoWayConverter<FoundationReferenceCurlEdge, AnimationVector4D> = TwoWayConverter(
            convertToVector = { AnimationVector4D(it.top.x, it.top.y, it.bottom.x, it.bottom.y) },
            convertFromVector = { FoundationReferenceCurlEdge(Offset(it.v1, it.v2), Offset(it.v3, it.v4)) },
        )
        val VisibilityThreshold = FoundationReferenceCurlEdge(
            Offset.VisibilityThreshold,
            Offset.VisibilityThreshold,
        )

        fun left(size: IntSize): FoundationReferenceCurlEdge = FoundationReferenceCurlEdge(
            Offset.Zero,
            Offset(0f, size.height.toFloat()),
        )

        fun right(size: IntSize): FoundationReferenceCurlEdge = FoundationReferenceCurlEdge(
            Offset(size.width.toFloat(), 0f),
            Offset(size.width.toFloat(), size.height.toFloat()),
        )

        fun end(size: IntSize): FoundationReferenceCurlEdge = FoundationReferenceCurlEdge(
            Offset(size.width.toFloat(), size.height.toFloat()),
            Offset(size.width.toFloat(), size.height.toFloat()),
        )
    }
}

internal enum class FoundationReferenceCurlDirection { Forward, Backward }

internal enum class FoundationReferenceCurlTapAction { Backward, ToggleControls, Forward }

internal enum class FoundationReferenceCurlAxis {
    Horizontal,
    Vertical,
    ;

    fun canonicalSize(size: IntSize): IntSize = when (this) {
        Horizontal -> size
        Vertical -> IntSize(size.height, size.width)
    }

    fun toCanonical(offset: Offset): Offset = when (this) {
        Horizontal -> offset
        Vertical -> Offset(offset.y, offset.x)
    }

    fun fromCanonical(offset: Offset): Offset = when (this) {
        Horizontal -> offset
        Vertical -> Offset(offset.y, offset.x)
    }
}

internal data class FoundationPagerCurlPolygon(val vertices: List<Offset>) {
    fun translate(offset: Offset): FoundationPagerCurlPolygon =
        FoundationPagerCurlPolygon(vertices.map { it + offset })

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

    fun toPath(axis: FoundationReferenceCurlAxis): Path = vertices.foundationReferencePath(axis)

    private fun wrap(index: Int): Int = ((index % vertices.size) + vertices.size) % vertices.size
}

internal expect fun DrawScope.drawFoundationPagerCurlShadow(
    polygon: FoundationPagerCurlPolygon,
    axis: FoundationReferenceCurlAxis,
    radius: Float,
    shadowOffset: Offset,
    color: Color,
)

private fun List<Offset>.foundationReferencePath(axis: FoundationReferenceCurlAxis): Path = Path().apply {
    this@foundationReferencePath.forEachIndexed { index, point ->
        val actual = axis.fromCanonical(point)
        if (index == 0) moveTo(actual.x, actual.y) else lineTo(actual.x, actual.y)
    }
}

private fun Offset.foundationReferenceRotate(angle: Float): Offset {
    val sin = sin(angle)
    val cos = cos(angle)
    return Offset(x * cos - y * sin, x * sin + y * cos)
}

private fun Offset.foundationReferenceNormalized(): Offset {
    val distance = getDistance()
    return if (distance != 0f) this / distance else this
}

private const val FoundationReferencePagerPageCount = 3
private const val FoundationReferenceCenterPage = 1
private const val FoundationReferenceTapDurationMillis = 450
private const val FoundationReferencePreviousTapZoneRatio = 0.25f
private const val FoundationReferenceNextTapZoneRatio = 0.75f
private const val FoundationReferenceDragThresholdRatio = 0.2f
private const val FoundationReferenceBackOverlayAlpha = 0.9f
private const val FoundationReferenceShadowAlpha = 0.2f
private val FoundationReferenceShadowRadius = 15.dp
private val FoundationReferenceShadowOffsetX = (-5).dp

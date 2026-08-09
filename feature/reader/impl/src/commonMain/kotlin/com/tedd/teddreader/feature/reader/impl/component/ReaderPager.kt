package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.splineBasedDecay
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
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
                onMovieTransitionProgressChanged = onMovieTransitionProgressChanged,
                modifier = modifier,
                content = content,
            )
            return
        }

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
                val forward = targetState >= initialState
                when (pageAnimation) {
                    PageAnimation.NONE -> fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    PageAnimation.FADE -> fadeIn(tween(140)) togetherWith fadeOut(tween(140))
                    PageAnimation.BOOK_CURL -> softSlideTransition(pageTurnMode, forward)
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
    modifier: Modifier = Modifier,
    content: @Composable (page: Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = CenterPageIndex)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pageKey, pageCount, pageStep) {
        listState.scrollToItem(CenterPageIndex)
    }
    val previousPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, -1)
    val nextPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, 1)
    LaunchedEffect(pageMoveRequest?.id) {
        val request = pageMoveRequest ?: return@LaunchedEffect
        try {
            val targetIndex = when (request.movement) {
                ReaderPageMovement.Previous -> 0.takeIf { previousPage != null }
                ReaderPageMovement.Next -> 2.takeIf { nextPage != null }
            }
            if (targetIndex != null) listState.animateScrollToItem(targetIndex)
        } finally {
            onPageMoveRequestConsumed(request.id)
        }
    }
    LaunchedEffect(listState, pageKey, pageCount, pageStep) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
            .filter { (_, isScrollInProgress) -> !isScrollInProgress }
            .map { (index, _) -> index }
            .distinctUntilChanged()
            .collect { index ->
                when (index) {
                    0 -> if (previousPage != null) onPreviousPage()
                    2 -> if (nextPage != null) onNextPage()
                }
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
                            listState.animateScrollToItem(0)
                        }
                    }
                }

                primary > extent * NextTapZoneRatio -> {
                    if (nextPage != null) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(2)
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
        ) {
            items(ScrollPageOffsets) { pageOffset ->
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
        ) {
            items(ScrollPageOffsets) { pageOffset ->
                Box(modifier = Modifier.fillParentMaxSize()) {
                    val documentPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, pageOffset)
                    if (documentPage != null) content(documentPage)
                }
            }
        }
    }
}

@Composable
private fun GoogleCurlPager(
    pageKey: Int,
    pageTurnMode: PageTurnMode,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (page: Int) -> Unit,
) {
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var dragState by remember(pageKey) { mutableStateOf<GoogleDragState?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val verticalMode = isVerticalMode(pageTurnMode)
    val axisSize = if (verticalMode) viewportSize.height else viewportSize.width
    val primaryDrag = dragState?.primaryDelta(verticalMode) ?: 0f
    val targetPage = when {
        primaryDrag < -TouchSlopPx -> pageKey + 1
        primaryDrag > TouchSlopPx -> pageKey - 1
        else -> pageKey
    }

    fun settleCurl(targetPrimary: Float, after: () -> Unit = {}) {
        val state = dragState ?: return
        val startPrimary = state.primaryDelta(verticalMode)
        val startCross = state.crossDelta(verticalMode)
        coroutineScope.launch {
            Animatable(0f).animateTo(
                targetValue = 1f,
                animationSpec = tween(ReaderCurlSettleMillis, easing = FastOutSlowInEasing),
            ) {
                val primary = lerp(startPrimary, targetPrimary, value)
                val cross = lerp(startCross, 0f, value)
                dragState =
                    state.withDelta(primary = primary, cross = cross, vertical = verticalMode)
            }
            dragState = null
            after()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            .pointerInput(pageTurnMode, axisSize, onPreviousPage, onNextPage, onToggleControls) {
                detectGoogleCurlGesture(
                    pageTurnMode = pageTurnMode,
                    axisSize = axisSize.toFloat().coerceAtLeast(1f),
                    onDrag = { start, current -> dragState = GoogleDragState(start, current) },
                    onCancel = { settleCurl(0f) },
                    onPreviousPage = {
                        settleCurl(
                            axisSize.toFloat().coerceAtLeast(1f),
                            onPreviousPage
                        )
                    },
                    onNextPage = { settleCurl(-axisSize.toFloat().coerceAtLeast(1f), onNextPage) },
                    onToggleControls = onToggleControls,
                )
            },
    ) {
        GoogleCurlLayer(
            dragState = dragState,
            pageTurnMode = pageTurnMode,
            currentPage = pageKey,
            targetPage = targetPage,
            content = content,
        )
    }
}

@Composable
private fun GoogleCurlLayer(
    dragState: GoogleDragState?,
    pageTurnMode: PageTurnMode,
    currentPage: Int,
    targetPage: Int,
    modifier: Modifier = Modifier,
    content: @Composable (page: Int) -> Unit,
) {
    val axis = curlAxisFrom(pageTurnMode)
    val geometryModifier = Modifier.googleEdgePathCurl(axis, dragState)
    val targetModifier = Modifier.googleEdgePathTarget(axis, dragState)

    Box(modifier.fillMaxSize()) {
        if (dragState != null && targetPage != currentPage) {
            Box(Modifier.fillMaxSize().then(targetModifier)) {
                content(targetPage)
            }
        }
        Box(Modifier.fillMaxSize().then(geometryModifier)) {
            content(currentPage)
        }
    }
}

private fun Modifier.googleEdgePathTarget(
    axis: CurlAxis,
    dragState: GoogleDragState?,
): Modifier = drawWithCache {
    val geometry = buildGoogleEdgeGeometry(
        size = axis.canonicalSize(IntSize(size.width.toInt(), size.height.toInt())),
        axis = axis,
        state = dragState,
    )
    if (geometry == null) {
        return@drawWithCache onDrawWithContent { }
    }
    onDrawWithContent {
        clipPath(geometry.targetPath) {
            this@onDrawWithContent.drawContent()
        }
    }
}

private fun Modifier.googleEdgePathCurl(
    axis: CurlAxis,
    dragState: GoogleDragState?,
): Modifier = drawWithCache {
    val geometry = buildGoogleEdgeGeometry(
        size = axis.canonicalSize(IntSize(size.width.toInt(), size.height.toInt())),
        axis = axis,
        state = dragState,
    )
    if (geometry == null) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    onDrawWithContent {
        clipPath(geometry.currentPath) {
            this@onDrawWithContent.drawContent()
        }
        drawLine(
            color = Color.Black.copy(alpha = GoogleShadowAlpha * geometry.progress),
            start = geometry.lineStart,
            end = geometry.lineEnd,
            strokeWidth = GoogleShadowWidthPx,
        )
        drawLine(
            color = Color.White.copy(alpha = GoogleEdgeHighlightAlpha * geometry.progress),
            start = geometry.lineStart,
            end = geometry.lineEnd,
            strokeWidth = GoogleEdgeWidthPx,
        )
    }
}

private data class GoogleEdgeGeometry(
    val currentPath: Path,
    val targetPath: Path,
    val lineStart: Offset,
    val lineEnd: Offset,
    val progress: Float,
)

private fun buildGoogleEdgeGeometry(
    size: Size,
    axis: CurlAxis,
    state: GoogleDragState?,
): GoogleEdgeGeometry? {
    if (state == null || size.width <= 1f || size.height <= 1f) return null
    val start = axis.toCanonical(state.start)
    val current = axis.toCanonical(state.current)
    val primary = current.x - start.x
    if (abs(primary) <= TouchSlopPx) return null

    val forward = primary < 0f
    fun mirror(point: Offset): Offset = Offset(size.width - point.x, point.y)
    val edge = if (forward) {
        createGooglePageEdge(size, start, current)
    } else {
        val mirrored = createGooglePageEdge(size, mirror(start), mirror(current))
        Edge(top = mirror(mirrored.top), bottom = mirror(mirrored.bottom))
    }
    val topIntersection = lineLineIntersection(
        Offset(0f, 0f),
        Offset(size.width, 0f),
        edge.top,
        edge.bottom,
    ) ?: return null
    val bottomIntersection = lineLineIntersection(
        Offset(0f, size.height),
        Offset(size.width, size.height),
        edge.top,
        edge.bottom,
    ) ?: return null
    val topX = topIntersection.x.coerceIn(0f, size.width)
    val bottomX = bottomIntersection.x.coerceIn(0f, size.width)
    val leftPath = listOf(
        Offset(0f, 0f),
        Offset(topX, 0f),
        Offset(bottomX, size.height),
        Offset(0f, size.height),
    ).toPath(axis)
    val rightPath = listOf(
        Offset(topX, 0f),
        Offset(size.width, 0f),
        Offset(size.width, size.height),
        Offset(bottomX, size.height),
    ).toPath(axis)
    return GoogleEdgeGeometry(
        currentPath = if (forward) leftPath else rightPath,
        targetPath = if (forward) rightPath else leftPath,
        lineStart = axis.fromCanonical(Offset(topX, 0f)),
        lineEnd = axis.fromCanonical(Offset(bottomX, size.height)),
        progress = (abs(primary) / size.width).coerceIn(0f, 1f),
    )
}

private fun createGooglePageEdge(size: Size, start: Offset, current: Offset): Edge {
    val vector = Offset(size.width, start.y) - current
    val rotatedVector = vector.rotate90()
    return Edge(
        top = current - rotatedVector + vector / 2f,
        bottom = current + rotatedVector + vector / 2f,
    )
}

@Composable
private fun AppleReferenceCurlPager(
    pageKey: Int,
    pageTurnMode: PageTurnMode,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (page: Int) -> Unit,
) {
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var curlState by remember(pageKey) { mutableStateOf<ReferenceCurlState?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val axis = curlAxisFrom(pageTurnMode)
    val canonicalSize = axis.canonicalSize(viewportSize)
    val leftEdge = Edge.left(canonicalSize)
    val rightEdge = Edge.right(canonicalSize)

    fun settleCurl(target: Edge, after: () -> Unit = {}) {
        val state = curlState ?: return
        coroutineScope.launch {
            Animatable(state.edge, Edge.VectorConverter, Edge.VisibilityThreshold).animateTo(
                targetValue = target,
                animationSpec = tween(ReferenceCurlSettleMillis, easing = FastOutSlowInEasing),
            ) {
                curlState = state.copy(edge = value)
            }
            curlState = null
            after()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            .pointerInput(
                pageTurnMode,
                viewportSize,
                onPreviousPage,
                onNextPage,
                onToggleControls
            ) {
                detectReferenceCurlGestures(
                    axis = axis,
                    onDrag = { state -> curlState = state },
                    onCancel = { direction ->
                        settleCurl(if (direction == CurlDirection.Forward) rightEdge else leftEdge)
                    },
                    onForward = { settleCurl(leftEdge, onNextPage) },
                    onBackward = { settleCurl(rightEdge, onPreviousPage) },
                    onToggleControls = onToggleControls,
                )
            },
    ) {
        content(pageKey + 1)
        Box(
            Modifier.referenceDrawCurl(
                axis = axis,
                edge = if (curlState?.direction == CurlDirection.Forward) curlState?.edge
                    ?: rightEdge else rightEdge,
            ),
        ) {
            content(pageKey)
        }
        if (curlState?.direction == CurlDirection.Backward) {
            Box(
                Modifier.referenceDrawCurl(
                    axis = axis,
                    edge = curlState?.edge ?: leftEdge,
                ),
            ) {
                content(pageKey - 1)
            }
        }
    }
}

private data class GoogleDragState(
    val start: Offset,
    val current: Offset,
) {
    private val delta: Offset = current - start

    fun primaryDelta(vertical: Boolean): Float = if (vertical) delta.y else delta.x

    fun crossDelta(vertical: Boolean): Float = if (vertical) delta.x else delta.y

    fun withDelta(primary: Float, cross: Float, vertical: Boolean): GoogleDragState {
        val nextCurrent = if (vertical) {
            Offset(start.x + cross, start.y + primary)
        } else {
            Offset(start.x + primary, start.y + cross)
        }
        return copy(current = nextCurrent)
    }
}

private data class ReferenceCurlState(
    val direction: CurlDirection,
    val edge: Edge,
)

private enum class CurlDirection {
    Forward,
    Backward,
}

private enum class CurlAxis {
    Horizontal,
    Vertical,
    ;

    fun canonicalSize(size: IntSize): Size = when (this) {
        Horizontal -> Size(size.width.toFloat(), size.height.toFloat())
        Vertical -> Size(size.height.toFloat(), size.width.toFloat())
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

private fun curlAxisFrom(pageTurnMode: PageTurnMode): CurlAxis =
    if (isVerticalMode(pageTurnMode)) CurlAxis.Vertical else CurlAxis.Horizontal

private data class Edge(
    val top: Offset,
    val bottom: Offset,
) {
    val centerX: Float = (top.x + bottom.x) * 0.5f

    companion object {
        val VectorConverter: TwoWayConverter<Edge, AnimationVector4D> = TwoWayConverter(
            convertToVector = { AnimationVector4D(it.top.x, it.top.y, it.bottom.x, it.bottom.y) },
            convertFromVector = { Edge(Offset(it.v1, it.v2), Offset(it.v3, it.v4)) },
        )
        val VisibilityThreshold: Edge = Edge(Offset(0.1f, 0.1f), Offset(0.1f, 0.1f))

        fun left(size: Size): Edge = Edge(Offset(0f, 0f), Offset(0f, size.height))

        fun right(size: Size): Edge = Edge(Offset(size.width, 0f), Offset(size.width, size.height))
    }
}

// Ported from Apache-2.0 https://github.com/oleksandrbalan/pagecurl
// Files referenced: CurlDraw.kt, DragCommonGesture.kt, DragGesture.kt.
private fun Modifier.referenceDrawCurl(
    axis: CurlAxis,
    edge: Edge,
): Modifier = drawWithCache {
    val canonicalSize = axis.canonicalSize(IntSize(size.width.toInt(), size.height.toInt()))
    val leftEdge = Edge.left(canonicalSize)
    val rightEdge = Edge.right(canonicalSize)

    if (edge == leftEdge) {
        return@drawWithCache onDrawWithContent { }
    }
    if (edge == rightEdge) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }

    val topIntersection = lineLineIntersection(
        Offset(0f, 0f),
        Offset(canonicalSize.width, 0f),
        edge.top,
        edge.bottom,
    )
    val bottomIntersection = lineLineIntersection(
        Offset(0f, canonicalSize.height),
        Offset(canonicalSize.width, canonicalSize.height),
        edge.top,
        edge.bottom,
    )

    if (topIntersection == null || bottomIntersection == null) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }

    val topCurlOffset = Offset(max(0f, topIntersection.x), topIntersection.y)
    val bottomCurlOffset = Offset(max(0f, bottomIntersection.x), bottomIntersection.y)
    val clippedPath = listOf(
        Offset(0f, 0f),
        topCurlOffset,
        bottomCurlOffset,
        Offset(0f, canonicalSize.height),
    ).toPath(axis)
    val polygon = buildReferenceCurlPolygon(canonicalSize, topCurlOffset, bottomCurlOffset)
    val polygonPath = polygon.toPath(axis)
    val actualTop = axis.fromCanonical(topCurlOffset)
    val actualBottom = axis.fromCanonical(bottomCurlOffset)
    val lineVector = actualTop - actualBottom
    val angleDegrees = (PI.toFloat() - atan2(lineVector.y, lineVector.x) * 2f) * DegreesPerRadian

    onDrawWithContent {
        clipPath(clippedPath) {
            this@onDrawWithContent.drawContent()
        }
        drawLine(
            color = Color.Black.copy(alpha = ReferenceShadowAlpha),
            start = actualTop,
            end = actualBottom,
            strokeWidth = ReferenceShadowWidthPx,
        )
        withTransform({
            scale(scaleX = -1f, scaleY = 1f, pivot = actualBottom)
            rotate(degrees = angleDegrees, pivot = actualBottom)
        }) {
            clipPath(polygonPath) {
                this@onDrawWithContent.drawContent()
                drawRect(Color.Black.copy(alpha = ReferenceBackOverlayAlpha))
            }
        }
    }
}

private fun buildReferenceCurlPolygon(
    size: Size,
    topCurlOffset: Offset,
    bottomCurlOffset: Offset,
): List<Offset> {
    fun endSideInterception(): List<Offset> {
        val offset = lineLineIntersection(
            topCurlOffset,
            bottomCurlOffset,
            Offset(size.width, 0f),
            Offset(size.width, size.height),
        ) ?: return emptyList()
        return listOf(offset, offset)
    }

    return buildList {
        if (topCurlOffset.x < size.width) {
            add(topCurlOffset)
            add(Offset(size.width, topCurlOffset.y))
        } else {
            addAll(endSideInterception())
        }
        if (bottomCurlOffset.x < size.width) {
            add(Offset(size.width, size.height))
            add(bottomCurlOffset)
        } else {
            addAll(endSideInterception())
        }
    }
}

private fun createReferencePageEdge(
    size: IntSize,
    axis: CurlAxis,
    start: Offset,
    current: Offset
): Edge {
    val canonicalSize = axis.canonicalSize(size)
    val startOffset = axis.toCanonical(start)
    val currentOffset = axis.toCanonical(current)
    val vector = Offset(canonicalSize.width, startOffset.y) - currentOffset
    val rotatedVector = vector.rotate90()
    return Edge(
        top = currentOffset - rotatedVector + vector / 2f,
        bottom = currentOffset + rotatedVector + vector / 2f,
    )
}

private suspend fun PointerInputScope.detectReferenceCurlGestures(
    axis: CurlAxis,
    onDrag: (ReferenceCurlState) -> Unit,
    onCancel: (CurlDirection) -> Unit,
    onForward: () -> Unit,
    onBackward: () -> Unit,
    onToggleControls: () -> Unit,
) {
    val velocityTracker = VelocityTracker()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var drag: PointerInputChange?
        var direction: CurlDirection? = null
        do {
            drag = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                change.consume()
            }
        } while (drag != null && !drag.isConsumed)

        if (drag == null) {
            handleTap(down.position, onBackward, onForward, onToggleControls)
            return@awaitEachGesture
        }

        val start = axis.toCanonical(down.position)
        val end = axis.toCanonical(drag.position)
        direction = when {
            end.x < start.x -> CurlDirection.Forward
            end.x > start.x -> CurlDirection.Backward
            else -> null
        }
        if (direction == null) {
            onToggleControls()
            return@awaitEachGesture
        }

        velocityTracker.addPosition(drag.uptimeMillis, drag.position)
        onDrag(
            ReferenceCurlState(
                direction,
                createReferencePageEdge(size, axis, down.position, drag.position)
            )
        )
        val completed = drag(drag.id) { change ->
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            onDrag(
                ReferenceCurlState(
                    direction,
                    createReferencePageEdge(size, axis, down.position, change.position)
                )
            )
            change.consume()
        }
        val velocity = velocityTracker.calculateVelocity()
        val decay = splineBasedDecay<Offset>(this)
        val endOffset = decay.calculateTargetValue(
            Offset.VectorConverter,
            drag.position,
            Offset(velocity.x, velocity.y),
        ).let { Offset(it.x.coerceIn(0f, size.width - 1f), it.y.coerceIn(0f, size.height - 1f)) }
        val canonicalEnd = axis.toCanonical(endOffset)
        val succeed = completed && when (direction) {
            CurlDirection.Forward -> canonicalEnd.x < start.x
            CurlDirection.Backward -> canonicalEnd.x > start.x
        }
        if (succeed) {
            if (direction == CurlDirection.Forward) onForward() else onBackward()
        } else {
            onCancel(direction)
        }
    }
}

private suspend fun PointerInputScope.detectGoogleCurlGesture(
    pageTurnMode: PageTurnMode,
    axisSize: Float,
    onDrag: (start: Offset, current: Offset) -> Unit,
    onCancel: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleControls: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var current = down.position
        var moved = false

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { change -> change.id == down.id } ?: break
            if (!change.pressed) break
            current = change.position
            val drag = current - down.position
            if (abs(drag.x) > TouchSlopPx || abs(drag.y) > TouchSlopPx) moved = true
            if (moved) {
                change.consume()
                onDrag(down.position, current)
            }
        }

        if (!moved) {
            handleTap(down.position, onPreviousPage, onNextPage, onToggleControls)
            return@awaitEachGesture
        }

        val drag = current - down.position
        val primary = if (isVerticalMode(pageTurnMode)) drag.y else drag.x
        val cross = if (isVerticalMode(pageTurnMode)) drag.x else drag.y
        if (abs(primary) > min(SwipeThresholdPx, axisSize * CurlCommitRatio) && abs(primary) > abs(
                cross
            ) * 0.35f
        ) {
            if (primary < 0f) onNextPage() else onPreviousPage()
        } else {
            onCancel()
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

private fun lineLineIntersection(
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

private fun List<Offset>.toPath(axis: CurlAxis): Path = map(axis::fromCanonical).toPath()

private fun List<Offset>.toPath(): Path = Path().apply {
    forEachIndexed { index, point ->
        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
    close()
}


private fun Offset.rotate90(): Offset = Offset(-y, x)

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + ((end - start) * fraction)

@OptIn(ExperimentalAnimationApi::class)
private fun softSlideTransition(pageTurnMode: PageTurnMode, forward: Boolean) =
    if (isVerticalMode(pageTurnMode)) {
        fadeIn(tween(160)) + slideInVertically(tween(220)) { fullHeight -> if (forward) fullHeight / 2 else -fullHeight / 2 } togetherWith
                fadeOut(tween(160)) + slideOutVertically(tween(220)) { fullHeight -> if (forward) -fullHeight / 4 else fullHeight / 4 }
    } else {
        fadeIn(tween(160)) + slideInHorizontally(tween(220)) { fullWidth -> if (forward) fullWidth / 2 else -fullWidth / 2 } togetherWith
                fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { fullWidth -> if (forward) -fullWidth / 4 else fullWidth / 4 }
    }

internal data class CurlPreset(

    val maxRotation: Float = 0f,
    val translationRatio: Float = 0f,
    val shadowAlpha: Float = 0f,
    val shadowSizeRatio: Float = 0f,
    val highlightAlpha: Float = 0f,
    val highlightSize: Float = 0f,
    val cameraDistance: Float = 0f,
    val backsideAlpha: Float = 0f,
    val creaseAlpha: Float = 0f,
    val diagonalRatio: Float = 0f,
    val cornerLiftRatio: Float = 0f,
    val depthAlpha: Float = 0f,
    val appleStyle: Float = 0f,
)

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

private val ScrollPageOffsets = listOf(-1, 0, 1)
private const val CenterPageIndex = 1
private const val TouchSlopPx = 8f
private const val SwipeThresholdPx = 72f
private const val CurlCommitRatio = 0.18f
private const val ReaderCurlSettleMillis = 180
private const val ReferenceCurlSettleMillis = 220
private const val DegreesPerRadian = 57.29578f
private const val GoogleShadowAlpha = 0.18f
private const val GoogleShadowWidthPx = 12f
private const val GoogleEdgeWidthPx = 2f
private const val GoogleEdgeHighlightAlpha = 0.10f
private const val ReferenceShadowAlpha = 0.18f
private const val ReferenceShadowWidthPx = 16f
private const val ReferenceBackOverlayAlpha = 0.08f
private const val PreviousTapZoneRatio = 0.28f
private const val NextTapZoneRatio = 0.72f

package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.tedd.teddreader.core.common.model.PageTurnMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FoundationPagerFluidReferenceImpl(
    pageKey: Int,
    pageCount: Int,
    pageStep: Int,
    pageTurnMode: PageTurnMode,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleControls: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (page: Int) -> Unit,
) {
    if (pageCount <= 0 || pageKey !in 0 until pageCount) return

    val vertical = pageTurnMode != PageTurnMode.HORIZONTAL
    val pagerState = rememberPagerState(
        initialPage = ReferenceFluidCenterPage,
        pageCount = { ReferenceFluidPagerPageCount },
    )
    var currentPage by remember { mutableIntStateOf(pageKey) }
    var dragPage by remember { mutableStateOf<Int?>(null) }
    var dragStartPosition by remember { mutableStateOf(Offset.Zero) }
    var dragDirection by remember { mutableFloatStateOf(0f) }
    var dragCompleted by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val fluidEdge = remember { FoundationReferenceFluidEdge(count = ReferenceFluidPointCount) }
    val latestOnPreviousPage by rememberUpdatedState(onPreviousPage)
    val latestOnNextPage by rememberUpdatedState(onNextPage)
    val latestOnToggleControls by rememberUpdatedState(onToggleControls)

    LaunchedEffect(pageKey, pageCount, pageStep) {
        if (currentPage != pageKey) {
            currentPage = pageKey
            dragPage = null
            dragCompleted = false
            dragDirection = 0f
            fluidEdge.applyTouchOffset(null)
            fluidEdge.reset()
        }
        if (pagerState.currentPage != ReferenceFluidCenterPage) {
            pagerState.scrollToPage(ReferenceFluidCenterPage)
        }
    }
    LaunchedEffect(fluidEdge) {
        while (true) {
            withFrameMillis(fluidEdge::tick)
        }
    }

    val dragModifier = Modifier.pointerInput(vertical, pageCount, pageStep) {
        detectDragGestures(
            onDragStart = { offset ->
                val completedPage = dragPage
                if (completedPage != null && dragCompleted) {
                    val previousPage = currentPage
                    currentPage = completedPage
                    if (completedPage < previousPage) {
                        latestOnPreviousPage()
                    } else {
                        latestOnNextPage()
                    }
                }

                dragPage = null
                dragStartPosition = offset
                dragCompleted = false
                dragDirection = 0f

                fluidEdge.farEdgeTension = 0.0
                fluidEdge.edgeTension = 0.01
                fluidEdge.reset()
            },
            onDrag = { change, _ ->
                val currentPosition = change.position
                val primaryDelta = if (vertical) {
                    currentPosition.y - dragStartPosition.y
                } else {
                    currentPosition.x - dragStartPosition.x
                }

                if (dragDirection == 0f) {
                    val direction = foundationReferenceFluidDragDirection(primaryDelta)
                    val targetPage = foundationReferenceFluidTargetPage(
                        currentPage = currentPage,
                        dragDirection = direction,
                        pageCount = pageCount,
                        pageStep = pageStep,
                    )
                    if (direction != 0f && targetPage != null) {
                        dragDirection = direction
                        dragPage = targetPage
                        fluidEdge.side = when {
                            vertical && direction == 1f -> FoundationReferenceFluidSide.TOP
                            vertical -> FoundationReferenceFluidSide.BOTTOM
                            direction == 1f -> FoundationReferenceFluidSide.LEFT
                            else -> FoundationReferenceFluidSide.RIGHT
                        }
                    }
                }

                if (dragDirection != 0f) {
                    val primaryExtent = if (vertical) containerSize.height else containerSize.width
                    val adjustedPrimary = if (dragDirection == -1f) {
                        primaryExtent + primaryDelta
                    } else {
                        primaryDelta
                    }
                    fluidEdge.applyTouchOffset(
                        offset = if (vertical) {
                            Offset(currentPosition.x, adjustedPrimary)
                        } else {
                            Offset(adjustedPrimary, currentPosition.y)
                        },
                        size = containerSize.toSize(),
                    )

                    if (!dragCompleted) {
                        if (foundationReferenceFluidShouldComplete(
                                primaryDelta = primaryDelta,
                                primaryExtent = primaryExtent.toFloat(),
                            )
                        ) {
                            dragCompleted = true
                            fluidEdge.farEdgeTension = 0.01
                            fluidEdge.edgeTension = 0.0
                            fluidEdge.applyTouchOffset(null)
                        }
                    }
                }
            },
            onDragEnd = {
                fluidEdge.applyTouchOffset(null)
                if (dragCompleted) {
                    fluidEdge.edgeTension = 0.0
                } else {
                    fluidEdge.edgeTension = 0.01
                    fluidEdge.farEdgeTension = 0.0
                }
            },
        )
    }
    val tapModifier = Modifier.pointerInput(vertical, pageCount, pageStep) {
        detectTapGestures { position ->
            val primary = if (vertical) position.y else position.x
            val extent = if (vertical) size.height else size.width
            when (foundationReferenceFluidTapAction(primary, extent, currentPage, pageCount, pageStep)) {
                FoundationReferenceFluidTapAction.Previous -> latestOnPreviousPage()
                FoundationReferenceFluidTapAction.Next -> latestOnNextPage()
                FoundationReferenceFluidTapAction.ToggleControls -> latestOnToggleControls()
                null -> Unit
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            .then(dragModifier)
            .then(tapModifier),
    ) {
        if (vertical) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                userScrollEnabled = false,
            ) { pagerPage ->
                FoundationReferenceFluidPagerPage(
                    pagerPage = pagerPage,
                    currentPage = currentPage,
                    pageCount = pageCount,
                    pageStep = pageStep,
                    dragPage = dragPage,
                    fluidEdge = fluidEdge,
                    vertical = true,
                    content = content,
                )
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                userScrollEnabled = false,
            ) { pagerPage ->
                FoundationReferenceFluidPagerPage(
                    pagerPage = pagerPage,
                    currentPage = currentPage,
                    pageCount = pageCount,
                    pageStep = pageStep,
                    dragPage = dragPage,
                    fluidEdge = fluidEdge,
                    vertical = false,
                    content = content,
                )
            }
        }
    }
}

internal fun foundationReferenceFluidTapAction(
    primary: Float,
    extent: Int,
    currentPage: Int,
    pageCount: Int,
    pageStep: Int = 1,
): FoundationReferenceFluidTapAction? = when {
    primary < extent * ReferenceFluidPreviousTapZoneRatio -> {
        FoundationReferenceFluidTapAction.Previous.takeIf {
            readerPagerAdjacentPage(currentPage, pageCount, pageStep, -1) != null
        }
    }
    primary > extent * ReferenceFluidNextTapZoneRatio -> {
        FoundationReferenceFluidTapAction.Next.takeIf {
            readerPagerAdjacentPage(currentPage, pageCount, pageStep, 1) != null
        }
    }
    else -> FoundationReferenceFluidTapAction.ToggleControls
}

internal enum class FoundationReferenceFluidTapAction {
    Previous,
    ToggleControls,
    Next,
}

@Composable
private fun FoundationReferenceFluidPagerPage(
    pagerPage: Int,
    currentPage: Int,
    pageCount: Int,
    pageStep: Int,
    dragPage: Int?,
    fluidEdge: FoundationReferenceFluidEdge,
    vertical: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (page: Int) -> Unit,
) {
    val documentPage = readerPagerAdjacentPage(
        currentPage,
        pageCount,
        pageStep,
        pagerPage - ReferenceFluidCenterPage,
    ) ?: return

    val isCurrentPage = pagerPage == ReferenceFluidCenterPage
    val isDragPage = !isCurrentPage && documentPage == dragPage
    if (!isCurrentPage && !isDragPage) return

    val overlayModifier = modifier
        .fillMaxSize()
        .graphicsLayer {
            val pageDelta = (ReferenceFluidCenterPage - pagerPage).toFloat()
            if (vertical) {
                translationY = pageDelta * size.height
            } else {
                translationX = pageDelta * size.width
            }
        }
        .zIndex(if (isDragPage) 1f else 0f)

    if (isDragPage) {
        FoundationReferenceFluidClipBox(
            fluidEdge = fluidEdge,
            modifier = overlayModifier,
        ) {
            Box(Modifier.fillMaxSize()) { content(documentPage) }
        }
    } else {
        Box(overlayModifier) { content(documentPage) }
    }
}

@Composable
private fun FoundationReferenceFluidClipBox(
    fluidEdge: FoundationReferenceFluidEdge,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val fluidVersion = fluidEdge.version
    val fluidShape = remember(fluidVersion) {
        object : Shape {
            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Outline = Outline.Generic(fluidEdge.buildPath(size, margin = ReferenceFluidPathMargin))
        }
    }
    Box(modifier.clip(fluidShape)) { content() }
}

internal fun foundationReferenceFluidTargetPage(
    currentPage: Int,
    dragDirection: Float,
    pageCount: Int,
    pageStep: Int = 1,
): Int? = readerPagerAdjacentPage(
    currentPage = currentPage,
    pageCount = pageCount,
    pageStep = pageStep,
    pageOffset = -dragDirection.toInt(),
)

internal fun foundationReferenceFluidDragDirection(primaryDelta: Float): Float =
    if (abs(primaryDelta) > ReferenceFluidDragGatePx) sign(primaryDelta) else 0f

internal fun foundationReferenceFluidShouldComplete(
    primaryDelta: Float,
    primaryExtent: Float,
): Boolean = primaryExtent > 0f &&
    abs(primaryDelta) >= primaryExtent * ReferenceFluidCompletionRatio

internal enum class FoundationReferenceFluidSide {
    LEFT,
    TOP,
    RIGHT,
    BOTTOM,
}

internal class FoundationReferenceFluidEdge(
    count: Int = 10,
    var side: FoundationReferenceFluidSide = FoundationReferenceFluidSide.LEFT,
) {
    internal val points = MutableList(count) { index ->
        FoundationReferenceFluidPoint(0.0, index.toDouble() / (count - 1))
    }
    var edgeTension = 0.01
    var farEdgeTension = 0.0
    var touchTension = 0.1
    var pointTension = 0.25
    var damping = 0.9
    var maxTouchDistance = 0.15
    private var lastT = 0L
    private var touchOffset: Offset? = null
    var version by mutableIntStateOf(0)
        private set

    fun reset() {
        points.forEach { point ->
            point.x = 0.0
            point.velX = 0.0
            point.velY = 0.0
        }
        version++
    }

    fun applyTouchOffset(offset: Offset? = null, size: Size = Size.Zero) {
        if (offset == null) {
            touchOffset = null
            return
        }

        val fraction = Offset(offset.x / size.width, offset.y / size.height)
        touchOffset = when (side) {
            FoundationReferenceFluidSide.LEFT -> fraction
            FoundationReferenceFluidSide.RIGHT -> Offset(1.0f - fraction.x, 1.0f - fraction.y)
            FoundationReferenceFluidSide.TOP -> Offset(fraction.y, fraction.x)
            FoundationReferenceFluidSide.BOTTOM -> Offset(1.0f - fraction.y, fraction.x)
        }
    }

    fun buildPath(size: Size, margin: Float = 0f): Path {
        if (points.isEmpty()) return Path()

        if (side == FoundationReferenceFluidSide.TOP || side == FoundationReferenceFluidSide.BOTTOM) {
            return buildVerticalPath(size, margin)
        }

        val transform = getTransform(size, margin)
        val path = Path()
        val pointCount = points.size

        var point = FoundationReferenceFluidPoint(-margin.toDouble(), 1.0).toOffset(transform)
        path.moveTo(point.x, point.y)
        point = FoundationReferenceFluidPoint(-margin.toDouble(), 0.0).toOffset(transform)
        path.lineTo(point.x, point.y)
        point = points[0].toOffset(transform)
        path.lineTo(point.x, point.y)

        if (pointCount > 1) {
            var nextPoint = points[1].toOffset(transform)
            path.lineTo(
                point.x + (nextPoint.x - point.x) / 2,
                point.y + (nextPoint.y - point.y) / 2,
            )
            for (index in 2 until pointCount) {
                point = nextPoint
                nextPoint = points[index].toOffset(transform)
                path.quadraticTo(
                    point.x,
                    point.y,
                    point.x + (nextPoint.x - point.x) / 2,
                    point.y + (nextPoint.y - point.y) / 2,
                )
            }
            path.lineTo(nextPoint.x, nextPoint.y)
        }
        path.close()
        return path
    }

    private fun buildVerticalPath(size: Size, margin: Float): Path {
        val farEdgeY = when (side) {
            FoundationReferenceFluidSide.TOP -> -margin
            FoundationReferenceFluidSide.BOTTOM -> size.height + margin
            else -> error("Vertical path requires TOP or BOTTOM")
        }
        val path = Path().apply {
            moveTo(size.width + margin, farEdgeY)
            lineTo(-margin, farEdgeY)
        }
        var point = foundationReferenceFluidVerticalOffset(points[0], size, side)
        path.lineTo(point.x, point.y)

        if (points.size > 1) {
            var nextPoint = foundationReferenceFluidVerticalOffset(points[1], size, side)
            path.lineTo(
                point.x + (nextPoint.x - point.x) / 2,
                point.y + (nextPoint.y - point.y) / 2,
            )
            for (index in 2 until points.size) {
                point = nextPoint
                nextPoint = foundationReferenceFluidVerticalOffset(points[index], size, side)
                path.quadraticTo(
                    point.x,
                    point.y,
                    point.x + (nextPoint.x - point.x) / 2,
                    point.y + (nextPoint.y - point.y) / 2,
                )
            }
            path.lineTo(nextPoint.x, nextPoint.y)
        }
        path.close()
        return path
    }

    fun tick(frameTimeMillis: Long) {
        if (points.isEmpty()) return

        val deltaMs = if (lastT == 0L) 16 else (frameTimeMillis - lastT).toInt()
        lastT = frameTimeMillis
        val time = min(1.5, deltaMs / 1000.0 * 60.0)
        val timeDamping = damping.pow(time)

        points.forEachIndexed { index, point ->
            point.velX -= point.x * edgeTension * time
            point.velX += (1.0 - point.x) * farEdgeTension * time

            touchOffset?.let { touch ->
                val ratio = max(0.0, 1.0 - abs(point.y - touch.y) / maxTouchDistance)
                point.velX += (touch.x - point.x) * touchTension * ratio * time
            }
            if (index > 0) {
                addPointTension(point, points[index - 1].x, time)
            }
            if (index < points.lastIndex) {
                addPointTension(point, points[index + 1].x, time)
            }
            point.velX *= timeDamping
        }

        points.forEach { point -> point.x += point.velX * time }
        version++
    }

    private fun addPointTension(point: FoundationReferenceFluidPoint, x: Double, time: Double) {
        point.velX += (x - point.x) * pointTension * time
    }

    private fun getTransform(size: Size, margin: Float): Matrix {
        val vertical = side == FoundationReferenceFluidSide.TOP ||
            side == FoundationReferenceFluidSide.BOTTOM
        val width = (if (vertical) size.height else size.width) + margin * 2
        val height = (if (vertical) size.width else size.height) + margin * 2
        return Matrix().apply {
            translate(-margin, 0f)
            scale(width, height)
            when (side) {
                FoundationReferenceFluidSide.TOP -> {
                    rotateZ(90f)
                    translate(0f, -1f)
                }
                FoundationReferenceFluidSide.RIGHT -> {
                    rotateZ(180f)
                    translate(-1f, -1f)
                }
                FoundationReferenceFluidSide.BOTTOM -> {
                    rotateZ(270f)
                    translate(-1f, 0f)
                }
                FoundationReferenceFluidSide.LEFT -> Unit
            }
        }
    }
}

internal fun foundationReferenceFluidVerticalOffset(
    point: FoundationReferenceFluidPoint,
    size: Size,
    side: FoundationReferenceFluidSide,
): Offset = when (side) {
    FoundationReferenceFluidSide.TOP -> Offset(
        x = point.y.toFloat() * size.width,
        y = point.x.toFloat() * size.height,
    )
    FoundationReferenceFluidSide.BOTTOM -> Offset(
        x = point.y.toFloat() * size.width,
        y = (1f - point.x.toFloat()) * size.height,
    )
    else -> error("Vertical offset requires TOP or BOTTOM")
}

internal data class FoundationReferenceFluidPoint(
    var x: Double = 0.0,
    var y: Double = 0.0,
    var velX: Double = 0.0,
    var velY: Double = 0.0,
) {
    fun toOffset(transform: Matrix? = null): Offset {
        val offset = Offset(x.toFloat(), y.toFloat())
        return transform?.map(offset) ?: offset
    }
}

private const val ReferenceFluidCenterPage = 1
private const val ReferenceFluidPagerPageCount = 3
private const val ReferenceFluidPointCount = 25
private const val ReferenceFluidDragGatePx = 20f
private const val ReferenceFluidCompletionRatio = 0.2f
private const val ReferenceFluidPathMargin = 10f
private const val ReferenceFluidPreviousTapZoneRatio = 0.25f
private const val ReferenceFluidNextTapZoneRatio = 0.75f

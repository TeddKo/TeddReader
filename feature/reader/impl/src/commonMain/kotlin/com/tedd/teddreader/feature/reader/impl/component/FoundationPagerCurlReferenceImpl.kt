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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
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
import kotlin.math.abs
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
 *
 * In a two-page spread the leaf is narrower than the viewport, so pointer travel is scaled into leaf space
 * rather than translated — that keeps the fold progress per unit of swipe identical to the single-pane curl
 * at every pointer position and in both directions.
 *
 * Also in a spread, the previous leaf paints its back face over the facing page, so it may only be composed
 * while it is actually being turned back; otherwise it would cover the page the reader is looking at.
 *
 * @param pageKey The current page index.
 * @param pageCount The total number of pages known so far.
 * @param pageStep How many pages one turn advances.
 * @param pageTurnMode Whether pages turn along the horizontal or vertical axis.
 * @param style Whether to use the original pointer-tracked curl or the horizontal-only 3D rolling
 *   profile.
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
internal fun FoundationPagerCurlReferenceImpl(
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
    val axis = foundationReferenceCurlAxis(pageTurnMode, style)
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
        val leafScale = if (isSpread) {
            leafSize.width / canonicalSize.width.toFloat().coerceAtLeast(1f)
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
        var animationJob by remember(axis, leafSize, style) { mutableStateOf<Job?>(null) }
        val previousPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, -1)
        val nextPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, 1)
        val canGoBackward = previousPage != null
        val canGoForward = readerPagerCanAdvanceForward(nextPage != null, canRequestNextPage)

        suspend fun reset() {
            forwardEdge.snapTo(rightEdge)
            backwardEdge.snapTo(backwardRestEdge)
            if (pagerState.currentPage != FoundationReferenceCenterPage) {
                pagerState.scrollToPage(FoundationReferenceCenterPage)
            }
        }

        fun complete(direction: FoundationReferenceCurlDirection) {
            when (direction) {
                FoundationReferenceCurlDirection.Forward -> {
                    latestOnNextPage()
                    if (nextPage == null && canRequestNextPage) {
                        scope.launch { reset() }
                    }
                }
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
                val isThreeD = style == FoundationReferenceCurlStyle.ThreeDimensional
                val geometryDirection = foundationReferenceCurlGeometryDirection(direction, isSpread)
                val edge = if (direction == FoundationReferenceCurlDirection.Forward) forwardEdge else backwardEdge
                val start = if (direction == FoundationReferenceCurlDirection.Forward) rightEdge else backwardRestEdge
                val end = when {
                    geometryDirection == FoundationReferenceCurlDirection.Forward -> leftEdge
                    isThreeD -> rightEdge
                    else -> backwardEndEdge
                }
                var completed = false
                try {
                    reset()
                    edge.animateTo(
                        targetValue = end,
                        animationSpec = if (isThreeD) {
                            foundationReferenceThreeDCurlTapSpec(
                                direction = geometryDirection,
                                size = leafSize,
                                durationMillisOverride = animationDurationMillis,
                            )
                        } else {
                            foundationReferenceTapSpec(
                                direction = geometryDirection,
                                size = leafSize,
                                durationMillisOverride = animationDurationMillis,
                            )
                        },
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

        LaunchedEffect(pageKey, pageStep, axis, leafSize, style) {
            reset()
            renderedPageKey = pageKey
        }

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
                    pointerInput(forwardEdge, backwardEdge, canGoForward, canGoBackward, style) {
                        detectFoundationReferenceCurlGestures(
                            axis = axis,
                            canonicalSize = leafSize,
                            isSpread = isSpread,
                            leafScale = leafScale,
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
                            style = style,
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
            val documentPage = readerPagerDisplayedPage(
                currentPage = pageKey,
                adjacentPage = readerPagerAdjacentPage(pageKey, pageCount, pageStep, pageOffset),
                pageOffset = pageOffset,
                canRequestNextPage = canRequestNextPage,
            )
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
                        if (isSpread || leafEdge == null) {
                            this
                        } else {
                            foundationReferenceDrawCurl(axis, leafEdge, style)
                        }
                    },
            ) {
                if (documentPage != null && !skipSpreadPage) {
                    if (isSpread) {
                        if (pageOffset == -1) {
                            FoundationReferenceBackwardSpread(
                                previousLeftPage = documentPage,
                                currentLeftPage = pageKey,
                                axis = axis,
                                style = style,
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
                                style = style,
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
 *
 * The back face is pre-mirrored (`scaleX = -1f`) so that the fold's own reflection lands it right-reading;
 * without the pre-mirror the text on a turning leaf's back reads backwards.
 *
 * @param style Whether the leaf uses standard painting or the 3D lighting profile.
 */
@Composable
private fun FoundationReferenceSpread(
    leftPage: Int,
    axis: FoundationReferenceCurlAxis,
    style: FoundationReferenceCurlStyle,
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
                    if (leafEdge == null) {
                        this
                    } else {
                        foundationReferenceDrawLeafFront(axis, leafEdge, style)
                    }
                },
            )
            if (leafEdge != null) {
                paneContent(
                    leftPage + 2,
                    Modifier
                        .fillMaxSize()
                        .foundationReferenceDrawLeafBack(axis, leafEdge, style)
                        .graphicsLayer { scaleX = -1f },
                )
            }
        }
    }
}

/**
 * Two-pane spread while a backward turn folds the current leaf away, back toward the previous page it
 * covers.
 *
 * Both pages this composable draws sit in the left pane, stacked in the order needed to reveal the one
 * underneath as the fold opens: [previousLeftPage] flat on the bottom (what the turn is uncovering),
 * then [currentLeftPage] drawn with [foundationReferenceDrawLeafFront] (the leaf's still-flat part),
 * then [previousLeftPage] + 1 drawn with [foundationReferenceDrawLeafBack] (the part folding open). That
 * is the same leaf a forward turn starting at [previousLeftPage] would draw — see
 * [FoundationReferenceSpread] — played in reverse, with front and back swapped because this animation
 * approaches the flat state instead of leaving it. Both leaf-face calls pass `mirrorHorizontally = true`
 * because this fold hinges on the left edge, the mirror image of the forward fold's right-edge hinge, so
 * the same front/back pre-mirror trick still lands the text right-reading. The right pane is left empty
 * here; the pager's own offset-0 slot renders it separately.
 *
 * @param previousLeftPage the page this backward turn is revealing.
 * @param currentLeftPage the page currently showing, whose leaf is folding away from it.
 * @param axis whether the fold runs horizontally or vertically.
 * @param style Whether the leaf uses standard painting or the 3D lighting profile.
 * @param leafEdge the leaf's current fold edge, in canonical coordinates.
 * @param gutter the gap between the two panes.
 * @param leftWeight the fraction of the spread's width given to the left pane.
 * @param spreadModifier the modifier applied to the row.
 * @param paneContent renders one page into a pane with the given modifier.
 */
@Composable
private fun FoundationReferenceBackwardSpread(
    previousLeftPage: Int,
    currentLeftPage: Int,
    axis: FoundationReferenceCurlAxis,
    style: FoundationReferenceCurlStyle,
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
                    .foundationReferenceDrawLeafFront(
                        axis = axis,
                        edge = leafEdge,
                        style = style,
                        mirrorHorizontally = true,
                    ),
            )
            paneContent(
                previousLeftPage + 1,
                Modifier
                    .fillMaxSize()
                    .foundationReferenceDrawLeafBack(
                        axis = axis,
                        edge = leafEdge,
                        style = style,
                        mirrorHorizontally = true,
                    ),
            )
        }
        Box(modifier = Modifier.weight(1f - leftWeight).fillMaxHeight())
    }
}

/**
 * The size the curl geometry treats as one page, given the viewport already reduced to one axis'
 * canonical orientation.
 *
 * Outside a spread the leaf is the whole pane, so [canonicalSize] passes through unchanged. Inside a
 * spread only the non-left pane actually turns, so the leaf is narrower: it gets whatever width is left
 * after the gutter and the left pane's share ([leftWeight]) are taken out, floored at 1px so a zero or
 * negative split never produces a degenerate size the fold math cannot invert.
 *
 * @param canonicalSize the viewport size in the axis' canonical (horizontal-first) orientation.
 * @param isSpread whether the pager is showing two panes side by side.
 * @param gutterPx the gap between panes, in pixels.
 * @param leftWeight the fraction of the spread's width given to the left pane; clamped to 0..1 before
 *   the leaf gets the remainder.
 * @return the size the curl edge, fold, and hit-testing math should use as one page.
 */
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

/**
 * Undoes the pager's own per-page placement so every page in the three-page window lands on the exact
 * same screen rect instead of side by side.
 *
 * [HorizontalPager]/[VerticalPager] place page N at N page-sizes along the scroll axis even though
 * this pager's current page never actually moves — the curl needs all three pages (previous, current,
 * next) stacked at the same position so [foundationReferenceCurlZIndex] can composite them by depth
 * instead of by scroll offset.
 *
 * @receiver the page's own modifier chain, before [foundationReferenceCurlZIndex] and any curl drawing.
 * @param axis which screen axis the pager scrolls along, so the right translation gets cancelled.
 * @param pageOffset this page's offset from the pager's fixed center page (-1, 0, or +1).
 */
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

/**
 * Stacking order for the pager's fixed three-slot window: previous page highest, current page in the
 * middle, next page lowest.
 *
 * Only the previous and current slots ever draw a curl fold — the next slot's `leafEdge` is always null
 * in [FoundationPagerCurlReferenceImpl]'s page content — so whichever of the other two is actively
 * folding needs to sit above the page it is revealing, in both directions; that only holds if this
 * ordering is fixed regardless of which turn is in progress.
 *
 * @param pageOffset the slot's offset from the pager's fixed center page (-1, 0, or +1).
 * @return a z-index where -1 sorts highest and +1 sorts lowest.
 */
internal fun foundationReferenceCurlZIndex(pageOffset: Int): Float =
    (1 - pageOffset).toFloat()

/**
 * What one in-progress drag gesture is doing, decided once at drag start and read for the rest of it.
 *
 * [detectFoundationReferenceCurlGestures] resolves [direction] and, from it, which of the pager's two
 * curl animatables applies ([edge]) before the first pointer move; bundling that choice here means the
 * rest of the gesture — drag, fling, cancel — never has to re-derive it or risk disagreeing about which
 * animatable is live mid-gesture.
 *
 * @property direction which way this drag is turning the page.
 * @property edge the animatable this gesture drives — [FoundationPagerCurlReferenceImpl]'s forward or
 *   backward edge, depending on [direction].
 * @property start the edge a cancelled drag animates back to.
 * @property end the edge a successful drag animates to, completing the turn.
 */
private data class FoundationReferenceDragConfig(
    val direction: FoundationReferenceCurlDirection,
    val edge: Animatable<FoundationReferenceCurlEdge, AnimationVector4D>,
    val start: FoundationReferenceCurlEdge,
    val end: FoundationReferenceCurlEdge,
)

/**
 * Drives one page-turn drag from first touch to its resolved outcome: fling-completed, dragged past the
 * threshold, or snapped back.
 *
 * Direction and the edge to animate are fixed in a [FoundationReferenceDragConfig] the moment a drag
 * starts, from the touch-slop displacement alone; nothing about the gesture after that can change which
 * animatable is driven. Every pointer position seen after that first decision is converted through
 * [foundationReferenceCurlLeafOffset] into the leaf's own coordinate space — the only space the fold
 * geometry understands — so a spread's narrower leaf still tracks the same normalized travel a single
 * pane would for the same finger movement.
 *
 * On lift, the recorded velocity is projected forward with a spline decay to find where the finger's
 * fling would have landed, and [foundationReferenceCurlDragSucceeds] judges that projected point against
 * the page-turn threshold — so a fast short flick can complete a turn a slow drag of the same distance
 * would not, matching how a real page flip responds to a flick versus a slow push.
 *
 * @receiver the pointer input scope providing gesture detection and the coroutine context
 *   [splineBasedDecay] needs.
 * @param axis whether the pager turns horizontally or vertically.
 * @param canonicalSize the leaf's size in the axis' canonical orientation.
 * @param isSpread whether the pager is showing two panes side by side.
 * @param leafScale the fraction of full-viewport pointer travel that maps to one leaf-width of fold
 *   progress in a spread; unused outside a spread.
 * @param leafWidth the leaf's width, used to mirror travel for a backward drag in a spread.
 * @param scope the coroutine scope the fold animations are launched on.
 * @param forwardEdge the animatable driving a forward turn.
 * @param backwardEdge the animatable driving a backward turn.
 * @param canGoForward whether a next page exists to turn to.
 * @param canGoBackward whether a previous page exists to turn to.
 * @param onDragStart called once a drag is recognized as a valid turn gesture, before the first frame
 *   of fold animation.
 * @param onComplete called with the resolved direction once the fold animation finishes a completed
 *   turn.
 * @param style the curl profile in effect; the 3D style locks the drag to horizontal-dominant motion
 *   and drives its crease from pointer x alone, while the standard style keeps the corner-peel curl.
 */
private suspend fun PointerInputScope.detectFoundationReferenceCurlGestures(
    axis: FoundationReferenceCurlAxis,
    canonicalSize: IntSize,
    isSpread: Boolean,
    leafScale: Float,
    leafWidth: Float,
    scope: CoroutineScope,
    forwardEdge: Animatable<FoundationReferenceCurlEdge, AnimationVector4D>,
    backwardEdge: Animatable<FoundationReferenceCurlEdge, AnimationVector4D>,
    canGoForward: Boolean,
    canGoBackward: Boolean,
    onDragStart: () -> Unit,
    onComplete: (FoundationReferenceCurlDirection) -> Unit,
    style: FoundationReferenceCurlStyle,
) {
    val velocityTracker = VelocityTracker()
    var config: FoundationReferenceDragConfig? = null
    var startOffset = Offset.Zero

    detectFoundationReferenceCustomDragGestures(
        onDragStart = { start, current ->
            val direction = if (style == FoundationReferenceCurlStyle.ThreeDimensional) {
                foundationReferenceThreeDCurlDirection(
                    start = axis.toCanonical(start),
                    current = axis.toCanonical(current),
                    canGoBackward = canGoBackward,
                    canGoForward = canGoForward,
                )
            } else {
                foundationReferenceCurlDirection(
                    start = axis.toCanonical(start),
                    current = axis.toCanonical(current),
                    canGoBackward = canGoBackward,
                    canGoForward = canGoForward,
                )
            }
            startOffset = direction?.let {
                foundationReferenceCurlLeafOffset(start, axis, it, isSpread, leafScale, leafWidth)
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
                    leafScale,
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
                leafScale,
                leafWidth,
            )
            velocityTracker.addPosition(change.uptimeMillis, current)
            scope.launch {
                dragConfig.edge.animateTo(
                    if (style == FoundationReferenceCurlStyle.ThreeDimensional) {
                        foundationReferenceThreeDCurlDragEdge(
                            size = canonicalSize,
                            start = startOffset,
                            current = current,
                            direction = foundationReferenceCurlGeometryDirection(
                                dragConfig.direction,
                                isSpread,
                            ),
                        )
                    } else {
                        foundationReferenceCurlEdge(canonicalSize, startOffset, current)
                    },
                )
            }
        },
    )
}

/**
 * A press-drag-release loop shaped like Compose Foundation's own drag-gesture detector, except
 * [onDragStart] can veto the gesture.
 *
 * The stock detector always accepts a drag once touch slop is passed and returns nothing from its start
 * callback; this one needs [onDragStart] to look at the touch-slop displacement and answer whether it is
 * even a valid page-turn attempt (there may be no page to turn to in that direction) before committing
 * to drive an animatable or fire its side effects. A rejected start exits without calling [onDrag] or
 * [onDragEnd] at all.
 *
 * @receiver the pointer input scope this gesture loop is detected within.
 * @param onDragStart given the down position and the position once touch slop is passed; returns
 *   whether the drag should proceed.
 * @param onDragEnd called once per accepted gesture with the last known position and whether it ended
 *   in a normal pointer-up (`true`) versus a cancellation (`false`).
 * @param onDrag called for every drag position after the start, including the touch-slop offset itself.
 */
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

/**
 * Wires Compose's tap/double-tap detection to [foundationReferenceCurlTapAction]'s zone decision.
 *
 * Kept separate from [detectFoundationReferenceCurlGestures] because taps and drags are recognized by
 * two independent `pointerInput` blocks on the pager modifier (see [FoundationPagerCurlReferenceImpl]),
 * so a tap that never moves past touch slop still reaches this detector.
 *
 * @receiver the pointer input scope this gesture is detected within.
 * @param axis whether the pager turns horizontally or vertically.
 * @param canGoForward whether a next page exists to turn to.
 * @param canGoBackward whether a previous page exists to turn to.
 * @param isAutoScrollEnabled whether auto-scroll is running, in which case any tap toggles the controls.
 * @param onPageTap called with the direction to animate a tap-triggered page turn.
 * @param onToggleControls called when the tap should show or hide the reader's controls instead of
 *   turning a page.
 * @param onDoubleTap forwarded to Compose's tap detector; null disables double-tap handling.
 */
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
            }
        },
    )
}

/**
 * What a tap on the curl pager should do, decided from the tap's position alone.
 *
 * A tap in an edge zone with no page to go to — the first or last page of the book — falls through to
 * toggling the controls, exactly like a tap in the middle zone. That rule is here because its absence was a
 * shipped bug (F16): a tap on the last page used to do nothing at all, so a reader could not tell the end of
 * the book from a swallowed tap. Auto-scroll short-circuits to the same toggle, since a page turn during
 * auto-scroll would fight the scroll.
 *
 * @param position the tap position in the pane's own coordinates.
 * @param size the pane's size, used with [axis] to reduce both to one canonical axis.
 * @param axis which way this pager turns.
 * @param canGoBackward whether a page exists behind the current one.
 * @param canGoForward whether a page exists ahead of it.
 * @param isAutoScrollEnabled whether auto-scroll is running, in which case any tap toggles the controls.
 * @return the action to take; never "nothing".
 */
internal fun foundationReferenceCurlTapAction(
    position: Offset,
    size: IntSize,
    axis: FoundationReferenceCurlAxis,
    canGoBackward: Boolean,
    canGoForward: Boolean,
    isAutoScrollEnabled: Boolean = false,
): FoundationReferenceCurlTapAction {
    val primary = axis.toCanonical(position).x
    val extent = axis.canonicalSize(size).width
    if (isAutoScrollEnabled) return FoundationReferenceCurlTapAction.ToggleControls
    return when {
        primary < extent * FoundationReferencePreviousTapZoneRatio ->
            if (canGoBackward) FoundationReferenceCurlTapAction.Backward else FoundationReferenceCurlTapAction.ToggleControls
        primary > extent * FoundationReferenceNextTapZoneRatio ->
            if (canGoForward) FoundationReferenceCurlTapAction.Forward else FoundationReferenceCurlTapAction.ToggleControls
        else -> FoundationReferenceCurlTapAction.ToggleControls
    }
}

/**
 * Which way a drag's initial displacement means to turn the page, or null when that direction has
 * nowhere to go.
 *
 * Comparing [current] against [start] in canonical (horizontal-first) coordinates lets one comparison
 * serve both axes: dragging toward the start of the axis is a forward turn, dragging toward its end is
 * backward, regardless of whether the pager is laid out horizontally or vertically. Returning null when
 * [canGoForward]/[canGoBackward] rules the direction out is what lets
 * [detectFoundationReferenceCustomDragGestures]'s `onDragStart` veto a drag at the first or last page
 * instead of animating a turn to nowhere.
 *
 * @param start the drag's starting position, in canonical coordinates.
 * @param current the drag's position once touch slop is passed, in canonical coordinates.
 * @param canGoBackward whether a previous page exists.
 * @param canGoForward whether a next page exists.
 * @return [FoundationReferenceCurlDirection.Forward] or [FoundationReferenceCurlDirection.Backward], or
 *   null if the indicated direction has no page to turn to.
 */
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

/**
 * The swipe axis the curl actually turns along, given the reader's configured [pageTurnMode] and the
 * selected [style].
 *
 * The Play Books-style [FoundationReferenceCurlStyle.ThreeDimensional] rolls a single leaf about a
 * near-vertical spine, a motion that only reads correctly as a left/right page turn; forcing it onto a
 * vertical swipe would fold the leaf against its own rolling direction, so this style is pinned to
 * [FoundationReferenceCurlAxis.Horizontal] regardless of [pageTurnMode]. The
 * [FoundationReferenceCurlStyle.Standard] curl has no such constraint and keeps honoring the reader's
 * chosen direction.
 *
 * @param pageTurnMode the reader's configured turn direction.
 * @param style the curl painting/interaction profile in effect.
 * @return [FoundationReferenceCurlAxis.Horizontal] for the 3D style or a horizontal [pageTurnMode],
 *   [FoundationReferenceCurlAxis.Vertical] only for the standard style on a vertical [pageTurnMode].
 */
internal fun foundationReferenceCurlAxis(
    pageTurnMode: PageTurnMode,
    style: FoundationReferenceCurlStyle,
): FoundationReferenceCurlAxis = when {
    style == FoundationReferenceCurlStyle.ThreeDimensional -> FoundationReferenceCurlAxis.Horizontal
    pageTurnMode == PageTurnMode.HORIZONTAL -> FoundationReferenceCurlAxis.Horizontal
    else -> FoundationReferenceCurlAxis.Vertical
}

/**
 * Which way a 3D-curl drag means to turn the page, accepting the gesture only when it is clearly a
 * left/right swipe rather than an incidental vertical drift.
 *
 * The Play Books-style roll is horizontal by construction (see [foundationReferenceCurlAxis]), so a
 * mostly-vertical drag is not a page turn at all and must not start one — this rejects any gesture whose
 * vertical travel dominates its horizontal travel (`abs(dy) >= abs(dx)`, which also rejects a pure
 * vertical or a no-movement drag). Once the drag is horizontal-dominant it defers to the same
 * availability semantics as [foundationReferenceCurlDirection]: leftward is a forward turn, rightward a
 * backward one, and either resolves to null when that direction has no page to turn to.
 *
 * @param start the drag's starting position, in canonical coordinates.
 * @param current the drag's position once touch slop is passed, in canonical coordinates.
 * @param canGoBackward whether a previous page exists.
 * @param canGoForward whether a next page exists.
 * @return the resolved turn direction, or null when the drag is not horizontal-dominant or the implied
 *   direction has nowhere to go.
 */
internal fun foundationReferenceThreeDCurlDirection(
    start: Offset,
    current: Offset,
    canGoBackward: Boolean,
    canGoForward: Boolean,
): FoundationReferenceCurlDirection? {
    val dx = current.x - start.x
    val dy = current.y - start.y
    if (abs(dx) <= abs(dy)) return null
    return foundationReferenceCurlDirection(start, current, canGoBackward, canGoForward)
}

/**
 * The 3D curl's rolling-crease edge for a pointer at [current], driven entirely by its x position.
 *
 * The Play Books-style roll keeps the crease near-vertical and sweeping across the page as the finger
 * moves horizontally, so this ignores [current]'s y completely — the same pointer x produces the same
 * crease at the top of the page as at the bottom, unlike [foundationReferenceCurlEdge]'s corner-peel
 * construction which pivots about the drag's starting height. The two exact endpoints coincide with the
 * renderer's flat rest edges so a fully-swept crease hands the draw path the very
 * [FoundationReferenceCurlEdge.left]/[FoundationReferenceCurlEdge.right] values its early returns already
 * short-circuit on: x at or past the left edge is [FoundationReferenceCurlEdge.left] (fully rolled), x at
 * or past the right edge is [FoundationReferenceCurlEdge.right] (at rest).
 *
 * Between those extremes the crease is a single near-vertical line centered on [current]'s x, tilted by
 * [FoundationReferenceThreeDCurlTiltRatio] of the shorter leaf side, scaled by a sine that peaks
 * mid-sweep and returns to zero at both edges. The tilt keeps the interior crease non-degenerate
 * (its top and bottom x differ), giving
 * the roll a visible lean instead of a flat vertical band, while its vanishing at the endpoints keeps
 * them exactly equal to the flat rest edges.
 *
 * @param size the leaf's size, in canonical coordinates; its width bounds the sweep and its height spans
 *   the crease.
 * @param current the pointer position; only its x is read.
 * @return the crease edge for this pointer x, or the exact flat rest edge at either endpoint.
 */
internal fun foundationReferenceThreeDCurlEdge(
    size: IntSize,
    current: Offset,
): FoundationReferenceCurlEdge {
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    val x = current.x
    if (x <= 0f) return FoundationReferenceCurlEdge.left(size)
    if (x >= width) return FoundationReferenceCurlEdge.right(size)
    val tilt = min(width, height) * FoundationReferenceThreeDCurlTiltRatio *
        sin(PI.toFloat() * x / width)
    return FoundationReferenceCurlEdge(
        top = Offset(x - tilt, 0f),
        bottom = Offset(x + tilt, height),
    )
}

/**
 * Converts horizontal drag travel into the 3D roll edge without letting the pointer's absolute touch
 * position choose the initial deformation.
 *
 * A Play Books-style swipe begins with a flat leaf wherever the finger touched. Forward travel moves
 * the crease left from the right rest edge; backward travel moves it right from the left rest edge.
 * Reversing past the touch point stays at that direction's rest edge through
 * [foundationReferenceThreeDCurlEdge]'s endpoint clamping. Spread callers pass their resolved geometry
 * direction, so a backward spread still folds the outer leaf with forward geometry.
 *
 * @param size the leaf's size in canonical coordinates.
 * @param start the pointer position mapped into leaf coordinates when the drag began.
 * @param current the current pointer position in the same leaf coordinates; its y is ignored.
 * @param direction the fold geometry direction to render.
 * @return the rolling crease reached by the drag's horizontal displacement.
 */
internal fun foundationReferenceThreeDCurlDragEdge(
    size: IntSize,
    start: Offset,
    current: Offset,
    direction: FoundationReferenceCurlDirection,
): FoundationReferenceCurlEdge {
    val x = when (direction) {
        FoundationReferenceCurlDirection.Forward -> size.width.toFloat() - (start.x - current.x)
        FoundationReferenceCurlDirection.Backward -> current.x - start.x
    }
    return foundationReferenceThreeDCurlEdge(size, Offset(x, 0f))
}

/**
 * Maps a pointer position into the coordinate space of the leaf that actually folds.
 *
 * A spread scales the full viewport travel onto the narrower leaf rather than translating it, so a
 * pointer at the viewport start edge always means "fully folded" and the viewport end edge always
 * means "at rest" — the same normalized progress the single pane curl produces. Translating instead
 * would pin the whole facing pane to the folded extreme and make the fold jump on drag start.
 */
internal fun foundationReferenceCurlLeafOffset(
    offset: Offset,
    axis: FoundationReferenceCurlAxis,
    direction: FoundationReferenceCurlDirection,
    isSpread: Boolean,
    leafScale: Float,
    leafWidth: Float,
): Offset {
    val canonical = axis.toCanonical(offset)
    val x = when {
        !isSpread -> canonical.x
        direction == FoundationReferenceCurlDirection.Forward -> canonical.x * leafScale
        else -> leafWidth - canonical.x * leafScale
    }
    return Offset(x, canonical.y)
}

/**
 * Maps a page-turn direction to the fold shape that should render it, collapsing backward into forward
 * whenever the pager is a spread.
 *
 * Outside a spread the two directions fold from opposite edges — a forward turn peels from the right,
 * a backward one from the left — so the geometry direction matches [direction] one-to-one. Inside a
 * spread only the outer (right-hand) leaf ever folds; a backward turn there is rendered as that same
 * leaf folding forward (see [FoundationPagerCurlReferenceImpl]'s rest and end edges, both pinned to the
 * leaf's right/left sides in that case), so its fold shape, shadow, and tap-animation spec must all use
 * the forward geometry even though the page navigation itself is backward.
 *
 * @param direction the page-turn direction as the reader experiences it.
 * @param isSpread whether the pager is showing two panes side by side.
 * @return the direction whose fold geometry should actually be drawn for this turn.
 */
internal fun foundationReferenceCurlGeometryDirection(
    direction: FoundationReferenceCurlDirection,
    isSpread: Boolean,
): FoundationReferenceCurlDirection =
    if (isSpread && direction == FoundationReferenceCurlDirection.Backward) {
        FoundationReferenceCurlDirection.Forward
    } else {
        direction
    }

/**
 * The fold edge to actually draw for this page slot, discarding a stale animation instead of letting it
 * bleed onto content it was never turning.
 *
 * [pageKey] can change before [FoundationPagerCurlReferenceImpl]'s page-key `LaunchedEffect` has had a
 * chance to reset the animatables and catch [renderedPageKey] up — a programmatic jump is the clearest
 * case. In that gap [animatedEdge] still holds whatever fold state the previous page's turn left it in;
 * drawing that here would flash a leftover fold across the new page's content instead of the flat rest
 * state it should start from.
 *
 * @param pageKey the page currently requested.
 * @param renderedPageKey the page the fold animatables were last reset for.
 * @param animatedEdge the animatable's live value.
 * @param restingEdge the flat edge to fall back to when the animation cannot be trusted.
 * @return [animatedEdge] while it is known to belong to the current page, [restingEdge] otherwise.
 */
internal fun foundationReferenceVisibleCurlEdge(
    pageKey: Int,
    renderedPageKey: Int,
    animatedEdge: FoundationReferenceCurlEdge,
    restingEdge: FoundationReferenceCurlEdge,
): FoundationReferenceCurlEdge =
    if (pageKey == renderedPageKey) animatedEdge else restingEdge

/**
 * Whether a completed or flung drag travelled far enough, in [direction], to count as a finished page
 * turn rather than a cancelled one.
 *
 * The travel and the threshold are both computed from [start]/[end]/[size] in canonical coordinates, so
 * the same ratio ([FoundationReferenceDragThresholdRatio]) applies to horizontal and vertical pagers
 * alike. For [FoundationReferenceCurlAxis.Vertical] the threshold is measured against the smaller of
 * [size]'s two dimensions rather than its canonical width — the canonical width for a vertical pager is
 * the screen's height, and requiring that much travel on a tall portrait screen would make a vertical
 * turn far harder to complete than a horizontal one.
 *
 * @param direction which way the drag was turning; determines which sign of travel counts as forward
 *   progress.
 * @param start the drag's starting position, in canonical coordinates.
 * @param end the drag's final (or projected fling) position, in canonical coordinates.
 * @param size the leaf size the drag happened over, in canonical coordinates.
 * @param axis whether the pager turns horizontally or vertically.
 * @return true once the directional travel reaches [FoundationReferenceDragThresholdRatio] of the
 *   required distance.
 */
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

/**
 * The fold's crease line for a finger at [currentOffset], built as the classic paper-fold construction:
 * the perpendicular bisector between the page corner being pulled and the finger's current position.
 *
 * The pulled corner is fixed at `(size.width, startOffset.y)` — the top/right edge at the height the
 * drag began — for the whole gesture; only [currentOffset] moves. Reflecting that corner across the
 * returned edge always lands it exactly on [currentOffset], which is what makes the fold track the
 * finger the way a real sheet of paper being peeled back would. [foundationReferenceCurlFold] extends
 * this line out to the page's own top and bottom edges to get the crease's actual endpoints.
 *
 * @param size the leaf's size, in canonical coordinates; only its width (the pulled corner's x) is used.
 * @param startOffset the drag's starting position, in canonical coordinates; only its y (the pulled
 *   corner's height) is used.
 * @param currentOffset the finger's current position, in canonical coordinates.
 * @return an edge whose `top`/`bottom` lie on the crease line through [currentOffset], perpendicular to
 *   the segment from the pulled corner to [currentOffset].
 */
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

/**
 * The keyframe animation spec for a tap- or auto-scroll-triggered page turn, shaped to pass through a
 * believable curl instead of the linear edge morph a two-point animateTo would produce.
 *
 * Interpolating the leaf's right/left edge straight to its opposite end would move the crease in a
 * straight line and look like the page sliding rather than curling. Routing through `middle` — a
 * diagonal crease from the mid-right/mid-bottom points — at a third of the way through a forward turn
 * (or, symmetrically, a third before the finish of a backward one) gives the fold an actual arc,
 * matching what a real drag-driven curl looks like partway through.
 *
 * @param direction which way the tap-triggered turn is animating; determines which end state the crease
 *   moves to and which side of the timeline the `middle` keyframe sits on.
 * @param size the leaf size the crease keyframes are computed against.
 * @param durationMillisOverride the total animation duration; also reused for auto-scroll's per-page
 *   delay, so it is coerced to at least 1ms rather than assumed positive.
 * @return a keyframes animation spec driving [FoundationReferenceCurlEdge]'s `top`/`bottom` through the
 *   shapes described above.
 */
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

/**
 * The keyframe spec for a tap- or auto-scroll-triggered 3D curl turn, routing the edge through the same
 * rolling crease [foundationReferenceThreeDCurlEdge] produces mid-drag so a tapped turn reads as the
 * Play Books-style roll rather than the standard curl's diagonal peel.
 *
 * A forward turn sweeps the crease from the right rest edge to the left, and a backward turn sweeps it
 * back the other way, ending at the right edge — the 3D roll has no distinct collapsed corner state, so
 * both directions settle on one of the two flat rest edges the renderer already short-circuits on. The
 * mid-sweep keyframe is sampled from [foundationReferenceThreeDCurlEdge] at the page's horizontal center,
 * placed a third into a forward turn and a third before the finish of a backward one so the crease passes
 * through its most-tilted state at the same point in the timeline the standard spec arcs through its
 * diagonal middle.
 *
 * @param direction which way the tap-triggered turn is animating; forward settles at the left rest edge,
 *   backward at the right.
 * @param size the leaf size the crease keyframes are computed against.
 * @param durationMillisOverride the total animation duration, reused for auto-scroll's per-page delay and
 *   so coerced to at least 1ms.
 * @return a keyframes spec driving [FoundationReferenceCurlEdge] through the 3D rolling crease.
 */
private fun foundationReferenceThreeDCurlTapSpec(
    direction: FoundationReferenceCurlDirection,
    size: IntSize,
    durationMillisOverride: Int = FoundationReferenceTapDurationMillis,
) = keyframes {
    val totalDurationMillis = durationMillisOverride.coerceAtLeast(1)
    val middleDurationMillis = max(1, totalDurationMillis / 3)
    durationMillis = totalDurationMillis
    val right = FoundationReferenceCurlEdge.right(size)
    val left = FoundationReferenceCurlEdge.left(size)
    val middle = foundationReferenceThreeDCurlEdge(size, Offset(size.width / 2f, size.height / 2f))
    if (direction == FoundationReferenceCurlDirection.Forward) {
        right at 0
        middle at middleDurationMillis
    } else {
        left at 0
        middle at totalDurationMillis - middleDurationMillis
    }
}

/**
 * Draws one non-spread page with a page-curl fold applied at [edge].
 *
 * At [edge]'s two rest positions (`left`/`right`) nothing is computed at all — the page is either fully
 * hidden or drawn exactly as-is — so the fold math in [foundationReferenceCurlFold] only ever runs while
 * a turn is actually mid-flight. Otherwise the flat remaining part of the page is clipped to
 * [FoundationReferenceCurlFold.clippedPath] and drawn normally, then the folded-over part is drawn a
 * second time inside the fold's own rotated, mirrored transform, clipped to its polygon and dimmed with
 * a white overlay. There is no separate back-face artwork in single-pane mode, so redrawing the same
 * content and fogging it is what stands in for "the back of the sheet" — [FoundationReferenceSpread]'s
 * two-pane curl instead has real back-face content and uses [foundationReferenceDrawLeafFront]/
 * [foundationReferenceDrawLeafBack] for the same split.
 *
 * The PlayLikeCurl-style [FoundationReferenceCurlStyle.ThreeDimensional] roll takes a different mid-turn
 * path entirely: instead of one planar reflected flap it renders the leaf through
 * [foundationReferenceDrawThreeDCurlMesh]'s [FoundationReferenceThreeDCurlGrid]-column cylindrical mesh,
 * so the leading edge bows toward the viewer while the trailing part stays flat. Its two rest positions
 * still short-circuit on the same `left`/`right` early returns.
 *
 * @receiver the page composable's own modifier chain.
 * @param axis whether the fold runs horizontally or vertically.
 * @param edge the leaf's current fold edge; `left`/`right` are the two rest positions, anything else is
 *   mid-turn.
 * @param style Whether to preserve standard curl painting or render the 3D cylindrical mesh.
 * @return The modifier drawing the selected curl appearance.
 */
private fun Modifier.foundationReferenceDrawCurl(
    axis: FoundationReferenceCurlAxis,
    edge: FoundationReferenceCurlEdge,
    style: FoundationReferenceCurlStyle,
): Modifier = drawWithCache {
    val canonicalSize = axis.canonicalSize(IntSize(size.width.toInt(), size.height.toInt()))
    if (edge == FoundationReferenceCurlEdge.left(canonicalSize)) {
        return@drawWithCache onDrawWithContent { }
    }
    if (edge == FoundationReferenceCurlEdge.right(canonicalSize)) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    if (style == FoundationReferenceCurlStyle.ThreeDimensional &&
        axis == FoundationReferenceCurlAxis.Horizontal
    ) {
        val progress = foundationReferenceThreeDCurlProgress(edge, canonicalSize.width.toFloat())
        val strips = foundationReferenceThreeDCurlStripSpecs(progress)
        val meshLighting = foundationReferenceThreeDCurlLightingSpec(progress * PI.toFloat())
        return@drawWithCache onDrawWithContent {
            foundationReferenceDrawThreeDCurlMesh(
                strips = strips,
                lighting = meshLighting,
                progress = progress,
                width = size.width,
                height = size.height,
                mirrorHorizontally = false,
            )
        }
    }
    val fold = foundationReferenceCurlFold(axis, edge, canonicalSize)
        ?: return@drawWithCache onDrawWithContent { drawContent() }
    val lighting = if (style == FoundationReferenceCurlStyle.ThreeDimensional) {
        foundationReferenceThreeDCurlLightingSpec(fold.angle)
    } else {
        null
    }
    val crease = ((edge.top.x + edge.bottom.x) / 2f)
        .coerceIn(0f, canonicalSize.width.toFloat())
    val frontShade = lighting?.let {
        if (axis == FoundationReferenceCurlAxis.Horizontal) {
            Brush.horizontalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = it.frontShadeAlpha)),
                startX = 0f,
                endX = crease.coerceAtLeast(1f),
            )
        } else {
            Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = it.frontShadeAlpha)),
                startY = 0f,
                endY = crease.coerceAtLeast(1f),
            )
        }
    }
    val backShade = lighting?.let {
        if (axis == FoundationReferenceCurlAxis.Horizontal) {
            Brush.horizontalGradient(
                listOf(
                    Color.White.copy(alpha = it.backLightAlpha),
                    Color.Transparent,
                    Color.Black.copy(alpha = it.backShadeAlpha),
                ),
                startX = crease,
                endX = canonicalSize.width.toFloat().coerceAtLeast(crease + 1f),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = it.backLightAlpha),
                    Color.Transparent,
                    Color.Black.copy(alpha = it.backShadeAlpha),
                ),
                startY = crease,
                endY = canonicalSize.width.toFloat().coerceAtLeast(crease + 1f),
            )
        }
    }

    onDrawWithContent {
        clipPath(fold.clippedPath) {
            this@onDrawWithContent.drawContent()
            if (frontShade != null) drawRect(frontShade)
        }
        withTransform({ fold.applyTo(this, axis) }) {
            fold.drawShadow(
                scope = this,
                axis = axis,
                alpha = lighting?.shadowAlpha ?: FoundationReferenceShadowAlpha,
            )
            clipPath(fold.polygon.toPath(axis)) {
                this@onDrawWithContent.drawContent()
                if (backShade == null) {
                    drawRect(Color.White.copy(alpha = FoundationReferenceBackOverlayAlpha))
                } else {
                    drawRect(backShade)
                    drawLine(
                        color = Color.White.copy(alpha = lighting.rimAlpha),
                        start = axis.fromCanonical(fold.polygon.vertices.first()),
                        end = axis.fromCanonical(fold.polygon.vertices.last()),
                        strokeWidth = FoundationReferenceThreeDRimWidthPx,
                    )
                }
            }
        }
    }
}

/**
 * Draws the leaf's flat front face, adding only the 3D profile's crease-directed diffuse shade.
 *
 * For the PlayLikeCurl-style [FoundationReferenceCurlStyle.ThreeDimensional] roll mid-turn this instead
 * renders the leaf through [foundationReferenceDrawThreeDCurlMesh]'s cylindrical strip mesh, honoring
 * [mirrorHorizontally] by mirroring the whole projection so a backward spread's left-hinged fold matches
 * its forward counterpart.
 *
 * @receiver The page composable's modifier chain.
 * @param axis Whether the fold runs horizontally or vertically.
 * @param edge The leaf's current fold edge.
 * @param style Whether to preserve standard painting or render the 3D cylindrical mesh.
 * @param mirrorHorizontally Whether a backward spread mirrors this leaf about its spine.
 * @return The modifier drawing the clipped and optionally lit front face.
 */
private fun Modifier.foundationReferenceDrawLeafFront(
    axis: FoundationReferenceCurlAxis,
    edge: FoundationReferenceCurlEdge,
    style: FoundationReferenceCurlStyle,
    mirrorHorizontally: Boolean = false,
): Modifier = drawWithCache {
    val canonicalSize = axis.canonicalSize(IntSize(size.width.toInt(), size.height.toInt()))
    if (edge == FoundationReferenceCurlEdge.left(canonicalSize)) {
        return@drawWithCache onDrawWithContent { }
    }
    if (edge == FoundationReferenceCurlEdge.right(canonicalSize)) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    if (style == FoundationReferenceCurlStyle.ThreeDimensional &&
        axis == FoundationReferenceCurlAxis.Horizontal
    ) {
        val progress = foundationReferenceThreeDCurlProgress(edge, canonicalSize.width.toFloat())
        val strips = foundationReferenceThreeDCurlStripSpecs(progress)
        val meshLighting = foundationReferenceThreeDCurlLightingSpec(progress * PI.toFloat())
        return@drawWithCache onDrawWithContent {
            foundationReferenceDrawThreeDCurlMesh(
                strips = strips,
                lighting = meshLighting,
                progress = progress,
                width = size.width,
                height = size.height,
                mirrorHorizontally = mirrorHorizontally,
            )
        }
    }
    val fold = foundationReferenceCurlFold(axis, edge, canonicalSize)
        ?: return@drawWithCache onDrawWithContent { drawContent() }
    val lighting = if (style == FoundationReferenceCurlStyle.ThreeDimensional) {
        foundationReferenceThreeDCurlLightingSpec(fold.angle)
    } else {
        null
    }
    val crease = ((edge.top.x + edge.bottom.x) / 2f)
        .coerceIn(0f, canonicalSize.width.toFloat())
    val frontShade = lighting?.let {
        if (axis == FoundationReferenceCurlAxis.Horizontal) {
            Brush.horizontalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = it.frontShadeAlpha)),
                startX = 0f,
                endX = crease.coerceAtLeast(1f),
            )
        } else {
            Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = it.frontShadeAlpha)),
                startY = 0f,
                endY = crease.coerceAtLeast(1f),
            )
        }
    }

    onDrawWithContent {
        withTransform({ if (mirrorHorizontally) scale(-1f, 1f) }) {
            clipPath(fold.clippedPath) {
                withTransform({ if (mirrorHorizontally) scale(-1f, 1f) }) {
                    this@onDrawWithContent.drawContent()
                }
                if (frontShade != null) drawRect(frontShade)
            }
        }
    }
}

/**
 * Draws the standard curl's folded back face reflected across the crease, including its paper light,
 * independent back shade, rim highlight, and angle-driven cast shadow.
 *
 * The PlayLikeCurl-style [FoundationReferenceCurlStyle.ThreeDimensional] roll has no folded back
 * texture to draw: the reference renders Left/Center/Right pages and reveals the underlying next or
 * previous pager page through the turning leaf's projected mesh
 * ([foundationReferenceDrawThreeDCurlMesh], drawn by [foundationReferenceDrawLeafFront]) rather than
 * folding a back face over it. A back face here would cover that mesh, so this modifier draws nothing at
 * all while a 3D leaf is mid-turn; the standard back path is unchanged.
 *
 * @receiver The page composable's modifier chain.
 * @param axis Whether the fold runs horizontally or vertically.
 * @param edge The leaf's current fold edge.
 * @param style Whether to preserve standard painting or, for the 3D roll, draw nothing so the mesh front
 *   reveals the underlying page.
 * @param mirrorHorizontally Whether a backward spread mirrors this leaf about its spine.
 * @return The modifier drawing the transformed and optionally lit back face.
 */
private fun Modifier.foundationReferenceDrawLeafBack(
    axis: FoundationReferenceCurlAxis,
    edge: FoundationReferenceCurlEdge,
    style: FoundationReferenceCurlStyle,
    mirrorHorizontally: Boolean = false,
): Modifier = drawWithCache {
    val canonicalSize = axis.canonicalSize(IntSize(size.width.toInt(), size.height.toInt()))
    if (edge == FoundationReferenceCurlEdge.right(canonicalSize)) {
        return@drawWithCache onDrawWithContent { }
    }
    if (style == FoundationReferenceCurlStyle.ThreeDimensional &&
        axis == FoundationReferenceCurlAxis.Horizontal
    ) {
        return@drawWithCache onDrawWithContent { }
    }
    val fold = foundationReferenceCurlFold(axis, edge, canonicalSize)
        ?: return@drawWithCache onDrawWithContent { }
    val lighting = if (style == FoundationReferenceCurlStyle.ThreeDimensional) {
        foundationReferenceThreeDCurlLightingSpec(fold.angle)
    } else {
        null
    }
    val crease = ((edge.top.x + edge.bottom.x) / 2f)
        .coerceIn(0f, canonicalSize.width.toFloat())
    val backShade = lighting?.let {
        if (axis == FoundationReferenceCurlAxis.Horizontal) {
            Brush.horizontalGradient(
                listOf(
                    Color.White.copy(alpha = it.backLightAlpha),
                    Color.Transparent,
                    Color.Black.copy(alpha = it.backShadeAlpha),
                ),
                startX = crease,
                endX = canonicalSize.width.toFloat().coerceAtLeast(crease + 1f),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = it.backLightAlpha),
                    Color.Transparent,
                    Color.Black.copy(alpha = it.backShadeAlpha),
                ),
                startY = crease,
                endY = canonicalSize.width.toFloat().coerceAtLeast(crease + 1f),
            )
        }
    }

    onDrawWithContent {
        withTransform({ if (mirrorHorizontally) scale(-1f, 1f) }) {
            withTransform({ fold.applyTo(this, axis) }) {
                fold.drawShadow(
                    scope = this,
                    axis = axis,
                    alpha = lighting?.shadowAlpha ?: FoundationReferenceShadowAlpha,
                )
                clipPath(fold.polygon.toPath(axis)) {
                    this@onDrawWithContent.drawContent()
                    if (backShade != null) {
                        drawRect(backShade)
                        drawLine(
                            color = Color.White.copy(alpha = lighting.rimAlpha),
                            start = axis.fromCanonical(fold.polygon.vertices.first()),
                            end = axis.fromCanonical(fold.polygon.vertices.last()),
                            strokeWidth = FoundationReferenceThreeDRimWidthPx,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The fold geometry computed for one crease position: everything [foundationReferenceDrawCurl] and the
 * leaf-face modifiers need to draw the flat remainder, the folded-over part, and its shadow.
 *
 * [polygon]/[angle]/[pivot] describe the folded-over part in its own drawing frame, not the page's:
 * [applyTo] must run first, inside the enclosing `withTransform` block, mirroring and rotating the draw
 * scope about [pivot] so that [polygon], used as a clip right after, and the page's own upright content
 * drawn inside that clip, both land where the folded-over paper actually sits while the transform is
 * active; `withTransform` then restores the untransformed scope once the block ends.
 *
 * @property clippedPath the page's flat, not-yet-folded region, ready to use as a clip path directly.
 * @property polygon the folded-over region's outline, in the fold's own (pre-[applyTo]) frame.
 * @property angle how far the fold has rotated open, in radians.
 * @property pivot the point the fold hinges about, in canonical coordinates.
 * @property shadowOffset the shadow's offset from the fold, already rotated to match [angle].
 * @property shadowRadius the shadow's blur radius, in pixels.
 */
private class FoundationReferenceCurlFold(
    val clippedPath: Path,
    val polygon: FoundationPagerCurlPolygon,
    val angle: Float,
    val pivot: Offset,
    val shadowOffset: Offset,
    val shadowRadius: Float,
) {
    /**
     * Puts [scope] into the folded-over part's own drawing frame: mirrored and rotated by [angle]
     * about [pivot], so [polygon] and the page's own content, drawn after this call, land where the
     * folded-over paper actually sits instead of where the flat page would put them.
     *
     * The mirror axis and rotation sign flip between axes because [FoundationReferenceCurlAxis]'s
     * vertical case reuses the same horizontal-axis fold math by swapping x/y rather than deriving a
     * second set of formulas — mirroring the other axis and negating the angle is what keeps a vertical
     * fold turning the same visual direction a horizontal one does for the same edge motion.
     *
     * @param scope the draw transform to apply the fold's mirror and rotation to.
     * @param axis which screen axis the fold actually renders on.
     */
    fun applyTo(scope: DrawTransform, axis: FoundationReferenceCurlAxis) {
        if (axis == FoundationReferenceCurlAxis.Horizontal) {
            scope.scale(-1f, 1f, pivot = pivot)
            scope.rotateRad(angle, pivot = pivot)
        } else {
            scope.scale(1f, -1f, pivot = pivot)
            scope.rotateRad(-angle, pivot = pivot)
        }
    }

    /**
     * Draws the folded-over part's drop shadow, delegating to the platform-specific
     * [drawFoundationPagerCurlShadow] since only the platform canvas can blur a shadow layer.
     *
     * @param scope the draw scope to render the shadow into.
     * @param axis which screen axis the fold renders on, forwarded so the platform implementation can
     *   convert [polygon] back to screen coordinates.
     * @param alpha The cast shadow's opacity for the selected visual style and fold angle.
     */
    fun drawShadow(
        scope: DrawScope,
        axis: FoundationReferenceCurlAxis,
        alpha: Float,
    ) {
        scope.drawFoundationPagerCurlShadow(
            polygon = polygon,
            axis = axis,
            radius = shadowRadius,
            shadowOffset = shadowOffset,
            color = Color.Black.copy(alpha = alpha.coerceIn(0f, 1f)),
        )
    }
}

/**
 * Turns the raw, unbounded fold line in [edge] into the actual fold geometry for one page: where the
 * crease crosses this page's own bounds, how far the folded-over part has rotated open, and where its
 * shadow falls. This is the single computation every curl draw path — [foundationReferenceDrawCurl],
 * [foundationReferenceDrawLeafFront], [foundationReferenceDrawLeafBack] — builds its rendering from.
 *
 * [edge] only carries a direction and a point the crease passes through (see
 * [foundationReferenceCurlEdge]), not where it meets the page — that has to be solved for by
 * intersecting it against the page's top and bottom edges. A null result there means the fold line is
 * exactly horizontal, parallel to both edges and therefore has no single crossing point; every caller
 * treats that as "no fold" and draws the page flat instead of clipping to a degenerate path.
 *
 * The intersections' x is clamped to at least 0 (`topCurlOffset`/`bottomCurlOffset`) because a drag that
 * has travelled past the page's own left edge — an overscrolled or fast-flung gesture — would otherwise
 * project the crease off the left side of the page; clamping pins it to the edge instead of handing
 * [foundationReferenceCurlPolygon] and the clip path a crease outside the region they clip.
 *
 * `angle` is twice the crease line's own tilt, because reflecting the pulled corner across a line at
 * angle θ rotates the folded-over paper by 2θ — the same relationship that makes
 * [foundationReferenceCurlEdge]'s perpendicular-bisector construction work in the first place. `pivot`
 * anchors that rotation ([FoundationReferenceCurlFold.applyTo]) at the crease's bottom endpoint.
 * `shadowOffset` is rotated by the same angle so the shadow keeps falling in a consistent direction
 * relative to the fold as it opens, rather than a fixed screen-space offset that would look wrong once
 * the page has rotated.
 *
 * @receiver the modifier's draw-cache scope, needed to resolve [FoundationReferenceShadowOffsetX] and
 *   [FoundationReferenceShadowRadius] from dp to pixels.
 * @param axis whether the fold runs horizontally or vertically; used to convert the pivot and shadow
 *   offset back from canonical coordinates to screen coordinates.
 * @param edge the (unbounded) fold line to solve against this page, in canonical coordinates.
 * @param canonicalSize the page's size in the axis' canonical orientation.
 * @return the fold's full geometry, or null if [edge] is exactly horizontal and has no defined crossing
 *   with the page's top and bottom edges.
 */
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

/**
 * The outline of the page's folded-over region: the part between the crease
 * ([topCurlOffset]-[bottomCurlOffset]) and the page's own right edge, as a closed polygon in canonical
 * coordinates. [foundationReferenceCurlFold] uses this both as the clip shape for the folded-over
 * drawing pass and as the shape [drawFoundationPagerCurlShadow] casts a shadow from.
 *
 * The ordinary case treats the crease's top and bottom points as already inside the page
 * ([topCurlOffset]/[bottomCurlOffset].x < [width]) and closes the polygon by projecting each one
 * straight across to the right edge at the same height, giving a simple quadrilateral: crease-top,
 * right-edge-at-crease-top-height, page's bottom-right corner, crease-bottom (or the top/bottom
 * equivalent, built symmetrically).
 *
 * Once a crease point has been driven past the right edge — which happens as the fold approaches
 * completion, since [foundationReferenceCurlFold] only clamps the crease's x to a minimum of 0, not a
 * maximum of [width] — that corner no longer has a meaningful position inside the page to add directly.
 * `endSideIntersection` instead finds where the crease line (extended, not the possibly out-of-bounds
 * point) actually crosses the right edge, and contributes that same point twice so the branch still adds
 * its usual two vertices; the result is a degenerate, zero-length edge at that corner rather than a
 * malformed polygon, which is a small enough drawing artifact right at the fold's most extreme state to
 * leave uncorrected.
 *
 * @param width the page's width in canonical coordinates; also the x of its right edge.
 * @param height the page's height in canonical coordinates.
 * @param topCurlOffset where the crease crosses the page's top edge (or beyond it, past the right edge).
 * @param bottomCurlOffset where the crease crosses the page's bottom edge (or beyond it, past the right
 *   edge).
 * @return the folded-over region's outline, always as a closed 4-point polygon.
 */
private fun foundationReferenceCurlPolygon(
    width: Float,
    height: Float,
    topCurlOffset: Offset,
    bottomCurlOffset: Offset,
): FoundationPagerCurlPolygon {
    /**
     * Where the crease line actually crosses the page's right edge, doubled so the calling branch
     * still contributes its usual two vertices; empty if the crease is exactly parallel to that edge.
     */
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

/**
 * Where two infinite lines cross, treating each pair of points as defining a line rather than a bounded
 * segment — the fold crease this file works with is conceptually infinite until intersected against the
 * page's own edges, so every caller here needs the line-line form rather than a segment-clipped one.
 *
 * @param line1a a point on the first line.
 * @param line1b a second, distinct point on the first line.
 * @param line2a a point on the second line.
 * @param line2b a second, distinct point on the second line.
 * @return the crossing point, or null when the two lines are parallel (or identical) and have no single
 *   crossing point.
 */
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

/**
 * The fold's crease line, as the two points where it crosses a page's top and bottom edges — the value
 * the animatables in [FoundationPagerCurlReferenceImpl] drive to animate a page turn, and what
 * [foundationReferenceCurlFold] solves the rest of the fold geometry from.
 *
 * @property top where the crease meets the page's top edge, in canonical coordinates.
 * @property bottom where the crease meets the page's bottom edge, in canonical coordinates.
 */
internal data class FoundationReferenceCurlEdge(
    val top: Offset,
    val bottom: Offset,
) {
    /**
     * [Animatable]'s conversion for this type, the fixed edge positions a page turn animates
     * between, and the [VisibilityThreshold] that tells [Animatable] when it has arrived.
     */
    companion object {
        /**
         * Lets [Animatable] interpolate a [FoundationReferenceCurlEdge] by treating its two offsets as
         * one four-component vector, so [top] and [bottom] each move independently and linearly
         * between animated values.
         */
        val VectorConverter: TwoWayConverter<FoundationReferenceCurlEdge, AnimationVector4D> = TwoWayConverter(
            convertToVector = { AnimationVector4D(it.top.x, it.top.y, it.bottom.x, it.bottom.y) },
            convertFromVector = { FoundationReferenceCurlEdge(Offset(it.v1, it.v2), Offset(it.v3, it.v4)) },
        )
        /**
         * The smallest per-component change [Animatable] treats as visible motion for a curl edge;
         * reused from [Offset]'s own default rather than picked separately for this type.
         */
        val VisibilityThreshold = FoundationReferenceCurlEdge(
            Offset.VisibilityThreshold,
            Offset.VisibilityThreshold,
        )

        /**
         * The edge at the page's left side (`top`/`bottom` both at x = 0) — a forward turn's completed
         * position, and, outside a spread, a backward turn's rest position.
         */
        fun left(size: IntSize): FoundationReferenceCurlEdge = FoundationReferenceCurlEdge(
            Offset.Zero,
            Offset(0f, size.height.toFloat()),
        )

        /**
         * The edge at the page's right side (`top`/`bottom` both at x = [size]'s width) — a forward
         * turn's rest position, and, in a spread, a backward turn's rest position too (see
         * [foundationReferenceCurlGeometryDirection]).
         */
        fun right(size: IntSize): FoundationReferenceCurlEdge = FoundationReferenceCurlEdge(
            Offset(size.width.toFloat(), 0f),
            Offset(size.width.toFloat(), size.height.toFloat()),
        )

        /**
         * The edge collapsed to a single point at the page's bottom-right corner — the target a
         * tap-triggered backward turn animates to outside a spread, distinct from the plain [left]/
         * [right] rest positions a drag-driven turn uses.
         */
        fun end(size: IntSize): FoundationReferenceCurlEdge = FoundationReferenceCurlEdge(
            Offset(size.width.toFloat(), size.height.toFloat()),
            Offset(size.width.toFloat(), size.height.toFloat()),
        )
    }
}

/**
 * Selects the curl interaction and rendering profile.
 *
 * [Standard] preserves the existing pointer-tracked corner peel. [ThreeDimensional] fixes the turn
 * to horizontal swipes, drives a near-vertical rolling crease from pointer x alone, and adds
 * front/back shading, paper bounce light, a crease highlight, and dynamic cast shadow.
 */
internal enum class FoundationReferenceCurlStyle {
    Standard,
    ThreeDimensional,
}

/**
 * Additional light intensities used by the 3D curl renderer for one fold angle.
 *
 * @property frontShadeAlpha Diffuse shade laid over the flat front face near the crease.
 * @property backShadeAlpha Dark component applied to the folded back face.
 * @property backLightAlpha Paper-colored bounce light applied to the folded back face.
 * @property rimAlpha Narrow highlight drawn directly on the fold line.
 * @property shadowAlpha Cast-shadow opacity underneath the raised leaf.
 */
internal data class FoundationReferenceThreeDCurlLightingSpec(
    val frontShadeAlpha: Float,
    val backShadeAlpha: Float,
    val backLightAlpha: Float,
    val rimAlpha: Float,
    val shadowAlpha: Float,
)

/**
 * Resolves the 3D curl's extra lighting from the reflected leaf angle. A sine makes every effect
 * vanish when the sheet is flat at either orientation and peak while it is most visibly bent,
 * avoiding stale dimming at the start or end of a turn.
 *
 * @param foldAngleRadians The fold reflection angle produced by the curl geometry, in radians.
 * @return The front, back, rim, and cast-shadow intensities for this frame.
 */
internal fun foundationReferenceThreeDCurlLightingSpec(
    foldAngleRadians: Float,
): FoundationReferenceThreeDCurlLightingSpec {
    val intensity = abs(sin(foldAngleRadians)).coerceIn(0f, 1f)
    return FoundationReferenceThreeDCurlLightingSpec(
        frontShadeAlpha = 0.16f * intensity,
        backShadeAlpha = 0.12f * intensity,
        backLightAlpha = 0.24f * intensity,
        rimAlpha = 0.32f * intensity,
        shadowAlpha = 0.34f * intensity,
    )
}

/**
 * One vertical column of the PlayLikeCurl-style cylindrical mesh: the fraction of the source page it
 * samples and where that column lands on screen once the leaf has rolled part-way around its curl
 * cylinder.
 *
 * The renderer draws the turning page as [FoundationReferenceThreeDCurlGrid] of these columns rather
 * than one planar reflected flap, so the leading edge can bow toward the reader while the trailing part
 * stays flat, the way Google Play Books rolls a page. Adjacent columns share a boundary
 * ([foundationReferenceThreeDCurlStripSpecs] computes each boundary once), so a strip's
 * [destinationEndFraction] is exactly the next strip's [destinationStartFraction] and the mesh has no
 * seam between columns.
 *
 * All fractions are expressed as a share of the leaf's width (0 at the leaf's start edge, 1 at its end
 * edge); a negative destination fraction means that column has rolled past the start edge and off the
 * leaf. [depthFraction] and [verticalScale] carry the projected column toward the camera so the rolled
 * region reads as raised paper: a column nearer the viewer is both drawn taller and lit more strongly.
 *
 * @property sourceStartFraction the leaf-width fraction where this column begins sampling the flat page.
 * @property sourceEndFraction the leaf-width fraction where this column stops sampling the flat page;
 *   always greater than [sourceStartFraction] by exactly one grid step.
 * @property destinationStartFraction where [sourceStartFraction] lands after the cylindrical roll and
 *   perspective, as a leaf-width fraction; may be negative once the column has rolled off the leaf.
 * @property destinationEndFraction where [sourceEndFraction] lands after the roll, as a leaf-width
 *   fraction; equals the next column's [destinationStartFraction] so the mesh stays continuous.
 * @property verticalScale how much taller than flat this column is drawn, from the perspective foreshadow
 *   of its raised depth; 1 while the column is still flat, greater than 1 once it lifts toward the camera.
 * @property depthFraction how far this column has lifted off the flat page toward the camera, in
 *   leaf-width units; 0 while flat, growing as the column rolls up the cylinder.
 */
internal data class FoundationReferenceThreeDCurlStripSpec(
    val sourceStartFraction: Float,
    val sourceEndFraction: Float,
    val destinationStartFraction: Float,
    val destinationEndFraction: Float,
    val verticalScale: Float,
    val depthFraction: Float,
)

/**
 * Projects the flat leaf into the PlayLikeCurl-style cylindrical mesh for a turn that is [progress] of
 * the way from rest to complete, returning one [FoundationReferenceThreeDCurlStripSpec] per grid column.
 *
 * The model rolls the page around a cylinder of radius [FoundationReferenceThreeDCurlRadius] whose fold
 * line sweeps from the leaf's end edge toward — and past — its start edge as [progress] rises. A source
 * point still ahead of the fold line stays flat; past it, the paper wraps up the cylinder, so its
 * projected position bows back toward the fold (`radius * sin(theta)`) while lifting toward the camera
 * (`radius * (1 - cos(theta))`), and a distance-[FoundationReferenceThreeDCurlCameraDistance] perspective
 * divide both foreshortens its horizontal span and, reused as [FoundationReferenceThreeDCurlStripSpec.verticalScale],
 * makes the raised column draw taller. Three ramps shape the sweep to match the reference feel: the curl
 * [FoundationReferenceThreeDCurlRadius] eases in over the first [FoundationReferenceThreeDCurlRadiusRampEnd]
 * of the turn so the page does not snap to a hard cylinder on the first frame; horizontal travel of the
 * fold line only begins after [FoundationReferenceThreeDCurlMoveStart] so an initial touch lifts the
 * corner before the page slides; and the arc's angular rate is scaled by
 * [FoundationReferenceThreeDCurlWavelengthRatio] and capped at a half turn so the rolled paper forms a
 * believable half-cylinder rather than spiralling onto itself.
 *
 * The [FoundationReferenceThreeDCurlGrid] + 1 column boundaries are each computed exactly once and shared
 * by the two columns that meet at them, so [FoundationReferenceThreeDCurlStripSpec.destinationEndFraction]
 * of one column is bit-for-bit the [FoundationReferenceThreeDCurlStripSpec.destinationStartFraction] of the
 * next and the mesh never tears. At [progress] 0 the radius ramp yields a zero radius, so every boundary
 * projects to itself and every column is the identity map with unit [verticalScale]; at [progress] 1 the
 * fold line has swept a full leaf-width plus the cylinder's own clearance past the start edge, so every
 * column's destination has rolled off to a negative fraction.
 *
 * @param progress how far the turn has advanced, from 0 (flat at rest) to 1 (fully turned); values
 *   outside that range are clamped.
 * @return the [FoundationReferenceThreeDCurlGrid] columns of the projected mesh, left to right, with
 *   shared, contiguous destination boundaries.
 */
internal fun foundationReferenceThreeDCurlStripSpecs(
    progress: Float,
): List<FoundationReferenceThreeDCurlStripSpec> {
    val clamped = progress.coerceIn(0f, 1f)
    val radius = FoundationReferenceThreeDCurlRadius *
        min(1f, clamped / FoundationReferenceThreeDCurlRadiusRampEnd)
    val move = if (clamped < FoundationReferenceThreeDCurlMoveStart) {
        0f
    } else {
        (clamped - FoundationReferenceThreeDCurlMoveStart) /
            (1f - FoundationReferenceThreeDCurlMoveStart)
    }
    val clearance = PI.toFloat() * FoundationReferenceThreeDCurlRadius + 0.1f
    val foldLine = 1f - move * (1f + clearance)
    val boundaries = FloatArray(FoundationReferenceThreeDCurlGrid + 1)
    val depths = FloatArray(FoundationReferenceThreeDCurlGrid + 1)
    val scales = FloatArray(FoundationReferenceThreeDCurlGrid + 1)
    for (index in boundaries.indices) {
        val source = index.toFloat() / FoundationReferenceThreeDCurlGrid
        val distancePastFold = source - foldLine
        val projected: Float
        val depth: Float
        if (radius <= FoundationReferenceThreeDCurlFlatEpsilon || distancePastFold <= 0f) {
            projected = source
            depth = 0f
        } else {
            val theta = min(
                PI.toFloat(),
                distancePastFold / radius * FoundationReferenceThreeDCurlWavelengthRatio,
            )
            val armLength = radius / FoundationReferenceThreeDCurlWavelengthRatio
            projected = foldLine + armLength * sin(theta)
            depth = armLength * (1f - cos(theta))
        }
        val scale = FoundationReferenceThreeDCurlCameraDistance /
            (FoundationReferenceThreeDCurlCameraDistance - depth)
        boundaries[index] = 0.5f + (projected - 0.5f) * scale
        depths[index] = depth
        scales[index] = scale
    }
    return List(FoundationReferenceThreeDCurlGrid) { index ->
        FoundationReferenceThreeDCurlStripSpec(
            sourceStartFraction = index.toFloat() / FoundationReferenceThreeDCurlGrid,
            sourceEndFraction = (index + 1).toFloat() / FoundationReferenceThreeDCurlGrid,
            destinationStartFraction = boundaries[index],
            destinationEndFraction = boundaries[index + 1],
            verticalScale = max(scales[index], scales[index + 1]),
            depthFraction = max(depths[index], depths[index + 1]),
        )
    }
}

/**
 * How far a rolling 3D crease has advanced, from its [FoundationReferenceCurlEdge] value, as the
 * [foundationReferenceThreeDCurlStripSpecs] progress input.
 *
 * The 3D crease sweeps its average x from the leaf's right edge (rest) to its left edge (complete) — the
 * mirror of the strip mesh's fold line, which sweeps its source fraction from 1 toward 0. Converting the
 * crease x to `1 - x / width` therefore hands the mesh the same 0-at-rest, 1-at-complete progress the
 * gesture and tap specs already drive the crease through, so the projected mesh and the crease that
 * produced it always agree.
 *
 * @param edge the leaf's current crease, in canonical coordinates.
 * @param width the leaf's width, in canonical pixels; a non-positive width yields 0 progress rather than
 *   dividing by zero.
 * @return the roll progress in 0..1 for [edge].
 */
private fun foundationReferenceThreeDCurlProgress(
    edge: FoundationReferenceCurlEdge,
    width: Float,
): Float {
    if (width <= 0f) return 0f
    val crease = (edge.top.x + edge.bottom.x) / 2f
    return (1f - crease / width).coerceIn(0f, 1f)
}

/**
 * Draws the turning leaf as the projected PlayLikeCurl cylindrical mesh over the already-drawn
 * underlying page, one clipped, transformed [drawContent] pass per column plus depth-driven shading.
 *
 * Each [FoundationReferenceThreeDCurlStripSpec] is painted back-to-front by depth so a column nearer the
 * camera overlays the one behind it: the destination x-span is clipped, the flat source column is scaled
 * into that span and stretched vertically about the leaf's center by the column's foreshortening, then
 * [drawContent] paints the page into it. Over each raised column a black-to-transparent front shade and a
 * white rim — both from [foundationReferenceThreeDCurlLightingSpec] at `progress * PI`, scaled by the
 * column's own depth — model the diffuse fall-off and the lit crease. Finally one cast gradient is laid
 * along the mesh's projected leading edge over the underlying page, standing in for the shadow the raised
 * paper throws. A [mirrorHorizontally] leaf mirrors the whole draw about the leaf's vertical center so a
 * backward spread's left-hinged fold projects the same mesh its right-hinged forward counterpart would.
 *
 * @receiver the content draw scope whose [ContentDrawScope.drawContent] paints the leaf's page.
 * @param strips the projected mesh columns for this frame, from [foundationReferenceThreeDCurlStripSpecs].
 * @param lighting the shade/rim/shadow intensities for this frame's roll angle.
 * @param progress the roll progress, reused to fade the cast shadow in with the turn.
 * @param width the leaf's width, in pixels.
 * @param height the leaf's height, in pixels.
 * @param mirrorHorizontally whether to mirror the whole mesh about the leaf's vertical center.
 */
private fun ContentDrawScope.foundationReferenceDrawThreeDCurlMesh(
    strips: List<FoundationReferenceThreeDCurlStripSpec>,
    lighting: FoundationReferenceThreeDCurlLightingSpec,
    progress: Float,
    width: Float,
    height: Float,
    mirrorHorizontally: Boolean,
) {
    val maxDepth = strips.maxOfOrNull { it.depthFraction }
        ?.coerceAtLeast(FoundationReferenceThreeDCurlFlatEpsilon)
        ?: return
    val trailingEdge = strips.maxOf {
        max(it.destinationStartFraction, it.destinationEndFraction)
    } * width
    val shadowStart = trailingEdge.coerceIn(0f, width)
    val shadowEnd = (shadowStart + width * FoundationReferenceThreeDCurlShadowSpread)
        .coerceAtMost(width)
    val ridge = strips.maxBy { it.depthFraction }
    val ridgeX = ((ridge.destinationStartFraction + ridge.destinationEndFraction) / 2f * width)
        .coerceIn(0f, width)
    withTransform({ if (mirrorHorizontally) scale(-1f, 1f) }) {
        if (lighting.shadowAlpha > 0f && shadowEnd > shadowStart) {
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = lighting.shadowAlpha), Color.Transparent),
                    startX = shadowStart,
                    endX = shadowEnd,
                ),
                topLeft = Offset(shadowStart, 0f),
                size = Size(shadowEnd - shadowStart, height),
            )
        }
        strips
            .sortedBy { it.depthFraction }
            .forEach { strip ->
                val destStart = strip.destinationStartFraction * width
                val destEnd = strip.destinationEndFraction * width
                val left = min(destStart, destEnd).coerceIn(0f, width)
                val right = max(destStart, destEnd).coerceIn(0f, width)
                if (right - left < FoundationReferenceThreeDCurlFlatEpsilon) return@forEach
                val sourceStart = strip.sourceStartFraction * width
                val sourceEnd = strip.sourceEndFraction * width
                val sourceSpan = sourceEnd - sourceStart
                if (abs(sourceSpan) < FoundationReferenceThreeDCurlFlatEpsilon) return@forEach
                val scaleX = (destEnd - destStart) / sourceSpan
                clipRect(left = left, top = 0f, right = right, bottom = height) {
                    withTransform({ scale(1f, strip.verticalScale, pivot = Offset(width / 2f, height / 2f)) }) {
                        withTransform({ translate(destStart - sourceStart, 0f) }) {
                            withTransform({ scale(scaleX, 1f, pivot = Offset(sourceStart, 0f)) }) {
                                this@foundationReferenceDrawThreeDCurlMesh.drawContent()
                            }
                        }
                    }
                    val depthShare = (strip.depthFraction / maxDepth).coerceIn(0f, 1f)
                    if (scaleX < 0f) {
                        if (lighting.backLightAlpha > 0f) {
                            drawRect(Color.White.copy(alpha = lighting.backLightAlpha * depthShare))
                        }
                        if (lighting.backShadeAlpha > 0f) {
                            drawRect(Color.Black.copy(alpha = lighting.backShadeAlpha * depthShare))
                        }
                    } else if (lighting.frontShadeAlpha > 0f) {
                        drawRect(Color.Black.copy(alpha = lighting.frontShadeAlpha * depthShare))
                    }
                }
            }
        if (lighting.rimAlpha > 0f && ridgeX > 0f && ridgeX < width) {
            drawLine(
                color = Color.White.copy(alpha = lighting.rimAlpha),
                start = Offset(ridgeX, 0f),
                end = Offset(ridgeX, height),
                strokeWidth = FoundationReferenceThreeDRimWidthPx,
            )
        }
    }
}

/**
 * Which way a page turn moves through the document: [Forward] toward the next page, [Backward] toward
 * the previous one. This is the reader-facing direction; [foundationReferenceCurlGeometryDirection]
 * maps it to the direction the fold itself actually renders, which can differ in a spread.
 */
internal enum class FoundationReferenceCurlDirection { Forward, Backward }

/**
 * What a single tap on the curl pager should do, as decided by [foundationReferenceCurlTapAction]: turn
 * to the previous page ([Backward]), turn to the next one ([Forward]), or show/hide the reader's
 * controls ([ToggleControls]) when the tap lands in neither turn zone, or has nowhere to turn to.
 */
internal enum class FoundationReferenceCurlTapAction { Backward, ToggleControls, Forward }

/**
 * Which screen axis a page turn runs along, and the conversion between real screen coordinates and this
 * file's fold math, which is written once for a horizontal turn and reused for [Vertical] by swapping
 * width/height and x/y rather than duplicating every formula.
 *
 * [canonicalSize]/[toCanonical] convert into that shared frame; [fromCanonical] converts back. For
 * [Horizontal] both directions are the identity; for [Vertical] each swaps its two components, so a
 * vertical turn's "width" is the screen's height and its "x" is the screen's y.
 */
internal enum class FoundationReferenceCurlAxis {
    Horizontal,
    Vertical,
    ;

    /**
     * [size] as the fold math sees it: width/height swapped for [Vertical] so the turn axis is always
     * "width" regardless of screen orientation.
     */
    fun canonicalSize(size: IntSize): IntSize = when (this) {
        Horizontal -> size
        Vertical -> IntSize(size.height, size.width)
    }

    /** [offset] as the fold math sees it: x/y swapped for [Vertical], for the same reason as [canonicalSize]. */
    fun toCanonical(offset: Offset): Offset = when (this) {
        Horizontal -> offset
        Vertical -> Offset(offset.y, offset.x)
    }

    /**
     * The inverse of [toCanonical]: a canonical-space [offset] converted back to real screen
     * coordinates. Happens to be the same swap as [toCanonical] because swapping x/y twice is the
     * identity.
     */
    fun fromCanonical(offset: Offset): Offset = when (this) {
        Horizontal -> offset
        Vertical -> Offset(offset.y, offset.x)
    }
}

/**
 * The folded-over region's outline as a closed loop of points, in canonical coordinates — what
 * [foundationReferenceCurlPolygon] builds and what [FoundationReferenceCurlFold.polygon] clips and
 * shadows the fold with.
 *
 * @property vertices the polygon's corners, in order around the loop.
 */
internal data class FoundationPagerCurlPolygon(val vertices: List<Offset>) {
    /**
     * [vertices] shifted by [offset], used by the pre-API-28 Android shadow path to draw into an inset
     * bitmap large enough to hold the blur without clipping it.
     */
    fun translate(offset: Offset): FoundationPagerCurlPolygon =
        FoundationPagerCurlPolygon(vertices.map { it + offset })

    /**
     * A copy of this polygon expanded outward by [value] along each vertex's own normal, used to grow
     * the silhouette a shadow is drawn from so its blur has room to bleed past the fold's own edge
     * instead of being clipped at it.
     *
     * Each vertex normal is the average of its two adjacent edge normals (computed via [wrap] so the
     * loop's first and last vertices are treated as neighbors), which is what keeps a beveled corner's
     * expansion pointing outward correctly instead of just offsetting each edge independently and
     * leaving gaps or overlaps at the corners.
     *
     * @param value how far to expand outward, in pixels; [drawFoundationPagerCurlShadow] passes the
     *   shadow's blur radius.
     * @return the expanded polygon, with the same vertex count and order as the original.
     */
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

    /** This polygon as a drawable [Path], converted from canonical back to screen coordinates via [axis]. */
    fun toPath(axis: FoundationReferenceCurlAxis): Path = vertices.foundationReferencePath(axis)

    /** Wraps [index] into `0 until vertices.size`, so the first and last vertices count as neighbors. */
    private fun wrap(index: Int): Int = ((index % vertices.size) + vertices.size) % vertices.size
}

/**
 * Draws the folded-over part's drop shadow, expected once per platform because Compose Multiplatform's
 * common [DrawScope] has no shared way to blur a shape into a shadow — each platform's actual reaches
 * for its own native canvas API (the Android actual, for example, sets a shadow layer on an
 * `android.graphics.Paint`).
 *
 * @receiver the draw scope to render the shadow into, in screen coordinates.
 * @param polygon the folded-over region's outline, in canonical coordinates; an actual implementation is
 *   expected to expand it by [radius] itself (see [FoundationPagerCurlPolygon.offset]) so the blur has
 *   room to bleed past the fold's edge.
 * @param axis needed to convert [polygon] from canonical to screen coordinates.
 * @param radius the shadow's blur radius, in pixels.
 * @param shadowOffset how far the shadow is displaced from the fold, in pixels.
 * @param color the shadow's color, including its alpha.
 */
internal expect fun DrawScope.drawFoundationPagerCurlShadow(
    polygon: FoundationPagerCurlPolygon,
    axis: FoundationReferenceCurlAxis,
    radius: Float,
    shadowOffset: Offset,
    color: Color,
)

/**
 * Connects these points into a [Path] in order, converting each one from canonical to screen coordinates
 * via [axis] first. Does not explicitly close the path back to the first point — every caller here only
 * ever uses the result as a clip shape, which treats an open contour the same as a closed one.
 *
 * @receiver the polygon's points, in canonical coordinates, in the order they should connect.
 * @param axis needed to convert each point back to screen coordinates.
 */
private fun List<Offset>.foundationReferencePath(axis: FoundationReferenceCurlAxis): Path = Path().apply {
    this@foundationReferencePath.forEachIndexed { index, point ->
        val actual = axis.fromCanonical(point)
        if (index == 0) moveTo(actual.x, actual.y) else lineTo(actual.x, actual.y)
    }
}

/**
 * This vector rotated by [angle] radians about the origin, using the standard 2D rotation matrix.
 *
 * @receiver the vector to rotate, treated as relative to the origin rather than a screen position.
 * @param angle the rotation, in radians.
 */
private fun Offset.foundationReferenceRotate(angle: Float): Offset {
    val sin = sin(angle)
    val cos = cos(angle)
    return Offset(x * cos - y * sin, x * sin + y * cos)
}

/**
 * This vector scaled to unit length, or left as-is — rather than dividing by zero — when it already has
 * zero length.
 */
private fun Offset.foundationReferenceNormalized(): Offset {
    val distance = getDistance()
    return if (distance != 0f) this / distance else this
}

/**
 * The pager always has exactly this many virtual pages — previous, current, next — since
 * [FoundationPagerCurlReferenceImpl] never scrolls the underlying pager and instead stacks and folds
 * those three slots itself.
 */
private const val FoundationReferencePagerPageCount = 3

/**
 * The pager's index is pinned here for the whole gesture lifecycle; this file drives page turns through
 * the fold animatables instead of through pager scroll position.
 */
private const val FoundationReferenceCenterPage = 1

/** How long a tap-triggered page turn animates by default, in milliseconds. */
private const val FoundationReferenceTapDurationMillis = 450

/**
 * A tap in the left/top quarter of the pane ([foundationReferenceCurlTapAction]) turns to the previous
 * page.
 */
private const val FoundationReferencePreviousTapZoneRatio = 0.25f

/**
 * A tap in the right/bottom quarter of the pane ([foundationReferenceCurlTapAction]) turns to the next
 * page; the middle half between this and [FoundationReferencePreviousTapZoneRatio] toggles controls
 * instead.
 */
private const val FoundationReferenceNextTapZoneRatio = 0.75f

/**
 * A drag or fling must cover this fraction of the required distance
 * ([foundationReferenceCurlDragSucceeds]) before it counts as a completed turn rather than a cancelled
 * one.
 */
private const val FoundationReferenceDragThresholdRatio = 0.2f

/**
 * How opaque the white overlay drawn over a single-pane fold's redrawn content is
 * ([foundationReferenceDrawCurl]) — high enough to read as the back of a sheet of paper rather than the
 * front page showing through unchanged.
 */
private const val FoundationReferenceBackOverlayAlpha = 0.9f

/**
 * The fold's drop shadow color's alpha ([FoundationReferenceCurlFold.drawShadow]) — low enough to read
 * as a soft cast shadow rather than a hard silhouette.
 */
private const val FoundationReferenceShadowAlpha = 0.2f

/** The 3D Curl crease highlight's screen-space width in pixels. */
private const val FoundationReferenceThreeDRimWidthPx = 2f

/**
 * Fraction of the shorter leaf side used as the maximum 3D rolling-crease lean. The reference
 * PlayLikeCurl mesh uses a `0.18` curl radius; applying the same normalized amount keeps the
 * approximation consistent across phones, tablets, and spread leaves instead of over-tilting a
 * narrow page with a fixed pixel distance.
 */
private const val FoundationReferenceThreeDCurlTiltRatio = 0.18f

/**
 * Number of vertical columns the PlayLikeCurl-style turning leaf is sliced into for
 * [foundationReferenceThreeDCurlStripSpecs]. The reference mesh uses a 25-column grid; more columns
 * would smooth the curved silhouette further at the cost of one extra clipped `drawContent` pass each,
 * and 25 is already enough for the bow to read as a continuous roll rather than faceted strips.
 */
private const val FoundationReferenceThreeDCurlGrid = 25

/**
 * The curl cylinder's radius as a fraction of the leaf's width, matching the reference PlayLikeCurl mesh.
 * A larger radius makes a lazier, gentler roll; this normalized value keeps the same curl feel on a phone,
 * a tablet, and a narrow spread leaf alike.
 */
private const val FoundationReferenceThreeDCurlRadius = 0.18f

/**
 * The fraction of the turn over which the curl radius eases from flat to its full
 * [FoundationReferenceThreeDCurlRadius]. Ramping the radius in over the first fifth of the turn keeps the
 * page from snapping onto a hard cylinder on the very first frame of a drag or tap.
 */
private const val FoundationReferenceThreeDCurlRadiusRampEnd = 0.20f

/**
 * The fraction of the turn before the curl fold line starts travelling horizontally. Below this the touch
 * only lifts the leading corner into its curl; past it the fold line begins sweeping across the leaf, so a
 * page does not slide sideways the instant it is touched.
 */
private const val FoundationReferenceThreeDCurlMoveStart = 0.05f

/**
 * Scales the angular rate at which the paper wraps up the curl cylinder in
 * [foundationReferenceThreeDCurlStripSpecs]. The reference mesh's `0.60` sine wavelength ratio stretches
 * the wrap so the rolled paper forms a believable half-cylinder rather than spiralling tightly onto itself.
 */
private const val FoundationReferenceThreeDCurlWavelengthRatio = 0.60f

/**
 * The perspective camera's distance from the page plane, in leaf-width units, used to foreshorten the
 * rolled mesh in [foundationReferenceThreeDCurlStripSpecs]. A raised column this far from the camera is
 * both narrowed horizontally and stretched vertically toward it, which is what lifts the roll off the flat
 * page instead of leaving it a flat reflected flap.
 */
private const val FoundationReferenceThreeDCurlCameraDistance = 2.0f

/**
 * The smallest curl radius, column width, or source span [foundationReferenceThreeDCurlStripSpecs] and
 * [foundationReferenceDrawThreeDCurlMesh] treat as non-zero, below which a column is drawn flat or skipped
 * rather than dividing by a vanishing span.
 */
private const val FoundationReferenceThreeDCurlFlatEpsilon = 1e-4f

/**
 * How far past the mesh's projected leading edge, as a fraction of leaf width, the cast-shadow gradient in
 * [foundationReferenceDrawThreeDCurlMesh] fades out — the soft penumbra the raised paper throws onto the
 * page it is uncovering.
 */
private const val FoundationReferenceThreeDCurlShadowSpread = 0.12f

/** The fold's shadow blur radius ([FoundationReferenceCurlFold.shadowRadius]). */
private val FoundationReferenceShadowRadius = 15.dp

/**
 * The fold's shadow displacement, in dp, before [foundationReferenceCurlFold] negates and rotates it to
 * match the fold's own angle; only its magnitude matters, since the call site flips its sign.
 */
private val FoundationReferenceShadowOffsetX = (-5).dp

package com.tedd.teddreader.feature.reader.impl

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * The allowed range for the reader's text font size, in sp, that a pinch gesture on a text page can
 * reach. [readerPinchFontSize] clamps every pinch result into this range, and it doubles as the
 * slider bounds the font-size option sheet offers, so a pinch and the settings sheet can never
 * disagree about how large or small text is allowed to get.
 */
internal val ReaderPinchFontSizeRange = 8f..80f

/**
 * The allowed zoom-factor range for a visual page (PDF, image, CBZ): `1f` is the page shown at its
 * natural fit-to-viewport size, and every zoom this file computes — pinch, double-tap, or a directly
 * set slider value — is clamped into this range before it reaches the screen.
 */
internal val ReaderPdfZoomRange = 1f..4f

/** The fixed zoom factor a double-tap jumps a visual page to, when it is not already zoomed past [ReaderPdfZoomRange]'s minimum. */
private const val ReaderDoubleTapZoom = 2.5f

/**
 * The current zoom and pan applied to a visual page (PDF, image, CBZ), held as one value so the two
 * always change together and a caller can never apply a new zoom against a stale pan or vice versa.
 *
 * @property zoom The zoom factor, where `1f` is the page at its natural fit-to-viewport size. Always
 *   within [ReaderPdfZoomRange].
 * @property pan The page's translation, in px, in the viewport's own coordinate space — how far the
 *   zoomed content has been dragged away from being centered.
 */
internal data class ReaderPdfTransform(
    val zoom: Float,
    val pan: Offset,
)

/**
 * The committed font size, in sp, once a pinch gesture on a text page ends. [gestureScale] is the
 * unitless multiplier the gesture accumulated since it started (not since the previous frame),
 * applied to the font size the gesture started from and then clamped into
 * [ReaderPinchFontSizeRange] and rounded — the same clamp-and-round a caller would otherwise have to
 * remember to apply before persisting a font size.
 *
 * @param startFontSizeSp The font size, in sp, in effect when the pinch gesture began.
 * @param gestureScale The cumulative pinch scale factor since the gesture started; `1f` means no
 *   change.
 * @return The new font size, in sp, as an integer within [ReaderPinchFontSizeRange].
 */
internal fun readerPinchFontSize(startFontSizeSp: Int, gestureScale: Float): Int {
    val scaled = startFontSizeSp * gestureScale
    return scaled
        .coerceIn(ReaderPinchFontSizeRange.start, ReaderPinchFontSizeRange.endInclusive)
        .roundToInt()
}

/**
 * Applies one increment of zoom and pan to a visual page's transform, keeping the point under the
 * gesture's focal point fixed on screen as the content scales — the "zoom toward the fingers"
 * behavior a pinch gesture is expected to have, rather than always zooming from the viewport's
 * center. [zoomChange] and [panChange] are deltas since the previous call, not absolute values,
 * matching what a pointer gesture's `calculateZoom()`/`calculatePan()` report per event.
 *
 * Snaps back to an untranslated [ReaderPdfTransform] the moment the resulting zoom would land at
 * exactly `1f`, so zooming all the way back out always leaves the page centered rather than at
 * whatever pan offset the gesture happened to leave behind. Otherwise the resulting pan is clamped
 * so the page can never be dragged far enough to show blank space past its own edge — the maximum
 * pan in each axis is half the viewport times how far past `1f` the new zoom is.
 *
 * A non-finite [centroid] or [panChange] — which a gesture detector can report transiently, for
 * example between the last two-finger frame and the first one-finger frame of a pinch — falls back
 * to the viewport's center and to no pan change respectively, rather than letting `NaN` propagate
 * into the transform.
 *
 * @param current The transform before this increment.
 * @param zoomChange The zoom multiplier since the previous call; `1f` for a pan-only update.
 * @param panChange The pan delta, in px, since the previous call.
 * @param centroid The gesture's focal point, in px, in the viewport's own coordinate space — the
 *   point the zoom is applied around.
 * @param viewportSize The size of the area the page renders into, in px, used both to find the
 *   viewport's center and to compute the pan-clamping bounds.
 * @return The updated, bounds-clamped transform.
 */
internal fun readerPdfTransform(
    current: ReaderPdfTransform,
    zoomChange: Float,
    panChange: Offset,
    centroid: Offset,
    viewportSize: IntSize,
): ReaderPdfTransform {
    val newZoom = (current.zoom * zoomChange)
        .coerceIn(ReaderPdfZoomRange.start, ReaderPdfZoomRange.endInclusive)

    if (newZoom == 1f) {
        return ReaderPdfTransform(zoom = 1f, pan = Offset.Zero)
    }

    val ratio = newZoom / current.zoom
    val center = Offset(
        x = viewportSize.width / 2f,
        y = viewportSize.height / 2f,
    )
    val safeCentroid = if (centroid.x.isFinite() && centroid.y.isFinite()) centroid else center
    val safePanChange = if (panChange.x.isFinite() && panChange.y.isFinite()) panChange else Offset.Zero
    val focalOffset = safeCentroid - center
    val unclampedPan = current.pan * ratio + focalOffset * (1f - ratio) + safePanChange
    val maxPanX = viewportSize.width / 2f * (newZoom - 1f)
    val maxPanY = viewportSize.height / 2f * (newZoom - 1f)

    return ReaderPdfTransform(
        zoom = newZoom,
        pan = Offset(
            x = unclampedPan.x.coerceIn(-maxPanX, maxPanX),
            y = unclampedPan.y.coerceIn(-maxPanY, maxPanY),
        ),
    )
}

/**
 * Re-derives a valid, in-bounds [ReaderPdfTransform] for a [zoom]/[pan] pair that was not produced by
 * a live gesture — for example, a value the visual-zoom slider in the view options sheet set
 * directly. Delegates to [readerPdfTransform] with no zoom or pan delta and the viewport's own
 * center as the focal point, so a directly set zoom gets exactly the same edge-clamping a pinch
 * gesture would have applied to it.
 *
 * @param zoom The zoom factor to apply, clamped into [ReaderPdfZoomRange] before use.
 * @param pan The pan to reconcile against [zoom], in px, in the viewport's own coordinate space.
 * @param viewportSize The size of the area the page renders into, in px.
 * @return A transform whose pan is guaranteed in-bounds for [zoom].
 */
internal fun readerClampedPdfTransform(
    zoom: Float,
    pan: Offset,
    viewportSize: IntSize,
): ReaderPdfTransform = readerPdfTransform(
    current = ReaderPdfTransform(
        zoom = zoom.coerceIn(ReaderPdfZoomRange.start, ReaderPdfZoomRange.endInclusive),
        pan = pan,
    ),
    zoomChange = 1f,
    panChange = Offset.Zero,
    centroid = Offset(
        x = viewportSize.width / 2f,
        y = viewportSize.height / 2f,
    ),
    viewportSize = viewportSize,
)

/**
 * The transform a double-tap on a visual page should jump to. Acts as a toggle: if the page is
 * already zoomed in past [ReaderPdfZoomRange]'s minimum, resets straight back to the untransformed
 * `1f`/no-pan state; otherwise zooms in to [ReaderDoubleTapZoom] centered on the tapped point, via
 * the same [readerPdfTransform] path a pinch gesture uses, so the tapped point stays fixed under the
 * finger as the page scales up.
 *
 * @param current The transform in effect when the double-tap happened.
 * @param tapPosition The tap location, in px, in the viewport's own coordinate space — the point the
 *   zoom-in case zooms around.
 * @param viewportSize The size of the area the page renders into, in px.
 * @return The reset or zoomed-in transform, depending on whether [current] was already zoomed in.
 */
internal fun readerDoubleTapVisualTransform(
    current: ReaderPdfTransform,
    tapPosition: Offset,
    viewportSize: IntSize,
): ReaderPdfTransform = if (current.zoom > ReaderPdfZoomRange.start) {
    ReaderPdfTransform(zoom = ReaderPdfZoomRange.start, pan = Offset.Zero)
} else {
    readerPdfTransform(
        current = current,
        zoomChange = ReaderDoubleTapZoom / current.zoom,
        panChange = Offset.Zero,
        centroid = tapPosition,
        viewportSize = viewportSize,
    )
}

/**
 * Installs the reader's combined pinch/pan gesture on the page content: a two-finger pinch resizes
 * text font size on a text page or zooms a visual page, and, once a visual page is zoomed in, a
 * single finger pans it. The two modes ([isVisualMode] selects which) share one gesture loop rather
 * than two separate `pointerInput` blocks because they are mutually exclusive on any given page and
 * a caller only ever wants one of them live at a time.
 *
 * Every parameter is captured through `rememberUpdatedState` inside `composed { }` rather than read
 * directly, because the `pointerInput(Unit)` gesture-detection coroutine below is keyed on the
 * constant `Unit` and therefore never restarts across recomposition — without that indirection it
 * would keep observing the values captured on its first launch for as long as the composable stays
 * on screen.
 *
 * A pinch starting always turns auto-scroll off first ([onAutoScrollEnabledChange]), since scrolling
 * out from under a page the reader is actively resizing or zooming is not useful, and a text pinch's
 * font-size change is only ever committed ([onTextFontSizeCommit]) once the gesture ends — while it
 * is in progress the caller only sees a live preview scale ([onTextGestureScaleChange]) so the text
 * layout is not re-measured on every frame.
 *
 * @receiver The page-content modifier this gesture attaches to.
 * @param enabled Whether the gesture participates at all; the reader turns it off while no page has
 *   been paginated yet, since there is nothing on screen to zoom or pan.
 * @param viewportSize The size of the page-content area, in px, used to compute pan bounds and zoom
 *   focal points exactly like [readerPdfTransform] does.
 * @param isVisualMode True to zoom/pan a visual page (PDF, image, CBZ); false to resize text font
 *   size instead.
 * @param textStartFontSizeSp The font size, in sp, in effect when a text pinch gesture starts — the
 *   base [readerPinchFontSize] scales from.
 * @param pdfTransform The visual page's current zoom/pan, read at the start of each gesture as the
 *   base a pinch or pan increment is applied on top of.
 * @param isAutoScrollEnabled Whether auto-scroll is currently on, checked once a pinch starts so it
 *   is only turned off ([onAutoScrollEnabledChange]) when it actually needs to be.
 * @param onAutoScrollEnabledChange Turns auto-scroll off; called with `false` the moment a
 *   two-finger pinch begins while [isAutoScrollEnabled] is true.
 * @param onGestureActiveChange Reports whether this gesture currently owns the pointer input, so the
 *   caller can suppress other gestures (like a page-turn tap) for the duration.
 * @param onTextGestureScaleChange The live, uncommitted text scale factor during a text pinch, for a
 *   caller to preview without re-measuring the page.
 * @param onTextFontSizeCommit The final font size, in sp, once a text pinch ends with a size
 *   different from [textStartFontSizeSp].
 * @param onPdfTransformChange The updated zoom/pan for a visual page, called on every frame the
 *   gesture changes it.
 * @return This modifier with the gesture attached, or itself unchanged when [enabled] is false.
 */
internal fun Modifier.readerPinchZoomGesture(
    enabled: Boolean,
    viewportSize: IntSize,
    isVisualMode: Boolean,
    textStartFontSizeSp: Int,
    pdfTransform: ReaderPdfTransform,
    isAutoScrollEnabled: Boolean,
    onAutoScrollEnabledChange: (Boolean) -> Unit,
    onGestureActiveChange: (Boolean) -> Unit,
    onTextGestureScaleChange: (Float) -> Unit,
    onTextFontSizeCommit: (Int) -> Unit,
    onPdfTransformChange: (ReaderPdfTransform) -> Unit,
): Modifier = composed {
    if (!enabled) return@composed this

    val latestViewportSize by rememberUpdatedState(viewportSize)
    val latestIsVisualMode by rememberUpdatedState(isVisualMode)
    val latestTextStartFontSizeSp by rememberUpdatedState(textStartFontSizeSp)
    val latestPdfTransform by rememberUpdatedState(pdfTransform)
    val latestIsAutoScrollEnabled by rememberUpdatedState(isAutoScrollEnabled)
    val latestOnAutoScrollEnabledChange by rememberUpdatedState(onAutoScrollEnabledChange)
    val latestOnGestureActiveChange by rememberUpdatedState(onGestureActiveChange)
    val latestOnTextGestureScaleChange by rememberUpdatedState(onTextGestureScaleChange)
    val latestOnTextFontSizeCommit by rememberUpdatedState(onTextFontSizeCommit)
    val latestOnPdfTransformChange by rememberUpdatedState(onPdfTransformChange)

    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var gestureOwned = false
            var gestureActive = false
            var pinchStarted = false
            var textGestureScale = 1f
            var pdfGestureTransform = latestPdfTransform
            val textFontSizeAtGestureStart = latestTextStartFontSizeSp

            fun startGesture() {
                if (!gestureActive) {
                    latestOnGestureActiveChange(true)
                    gestureActive = true
                }
            }

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressedChanges = event.changes.filter { it.pressed }
                val pressedCount = pressedChanges.size
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                val centroid = event.calculateCentroid(useCurrent = true)

                if (pressedCount >= 2 && !pinchStarted) {
                    pinchStarted = true
                    gestureOwned = true
                    startGesture()
                    if (latestIsAutoScrollEnabled) {
                        latestOnAutoScrollEnabledChange(false)
                    }
                }

                if (!gestureOwned && latestIsVisualMode && latestPdfTransform.zoom > 1f && pressedCount == 1 && panChange != Offset.Zero) {
                    gestureOwned = true
                    startGesture()
                }

                if (gestureOwned) {
                    if (latestIsVisualMode) {
                        val nextTransform = if (pressedCount >= 2 || pdfGestureTransform.zoom > 1f) {
                            readerPdfTransform(
                                current = pdfGestureTransform,
                                zoomChange = if (pressedCount >= 2) zoomChange else 1f,
                                panChange = panChange,
                                centroid = centroid,
                                viewportSize = latestViewportSize,
                            )
                        } else {
                            pdfGestureTransform
                        }
                        if (nextTransform != pdfGestureTransform) {
                            pdfGestureTransform = nextTransform
                            latestOnPdfTransformChange(nextTransform)
                        }
                    } else if (pressedCount >= 2) {
                        val minScale = ReaderPinchFontSizeRange.start / textFontSizeAtGestureStart
                        val maxScale = ReaderPinchFontSizeRange.endInclusive / textFontSizeAtGestureStart
                        textGestureScale = (textGestureScale * zoomChange).coerceIn(minScale, maxScale)
                        latestOnTextGestureScaleChange(textGestureScale)
                    }

                    event.changes.forEach { change ->
                        if (!change.isConsumed) {
                            change.consume()
                        }
                    }
                }

                if (pressedCount == 0) break
            }

            if (!latestIsVisualMode) {
                latestOnTextGestureScaleChange(1f)
                if (pinchStarted) {
                    val committedFontSize = readerPinchFontSize(
                        startFontSizeSp = textFontSizeAtGestureStart,
                        gestureScale = textGestureScale,
                    )
                    if (committedFontSize != textFontSizeAtGestureStart) {
                        latestOnTextFontSizeCommit(committedFontSize)
                    }
                }
            }

            if (gestureActive) {
                latestOnGestureActiveChange(false)
            }
        }
    }
}

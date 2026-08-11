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

internal val ReaderPinchFontSizeRange = 8f..80f
internal val ReaderPdfZoomRange = 1f..4f

internal data class ReaderPdfTransform(
    val zoom: Float,
    val pan: Offset,
)

internal fun readerPinchFontSize(startFontSizeSp: Int, gestureScale: Float): Int {
    val scaled = startFontSizeSp * gestureScale
    return scaled
        .coerceIn(ReaderPinchFontSizeRange.start, ReaderPinchFontSizeRange.endInclusive)
        .roundToInt()
}

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

internal fun Modifier.readerPinchZoomGesture(
    enabled: Boolean,
    viewportSize: IntSize,
    isPdfMode: Boolean,
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
    val latestIsPdfMode by rememberUpdatedState(isPdfMode)
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

                if (!gestureOwned && latestIsPdfMode && latestPdfTransform.zoom > 1f && pressedCount == 1 && panChange != Offset.Zero) {
                    gestureOwned = true
                    startGesture()
                }

                if (gestureOwned) {
                    if (latestIsPdfMode) {
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

            if (!latestIsPdfMode) {
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

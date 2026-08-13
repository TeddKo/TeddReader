package com.tedd.teddreader.feature.reader.impl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderPinchZoomTest {
    @Test
    fun `reader pinch font size scales and clamps with rounded integer sp`() {
        assertEquals(36, readerPinchFontSize(startFontSizeSp = 18, gestureScale = 2f))
        assertEquals(8, readerPinchFontSize(startFontSizeSp = 6, gestureScale = 1f))
        assertEquals(80, readerPinchFontSize(startFontSizeSp = 50, gestureScale = 2f))
        assertEquals(19, readerPinchFontSize(startFontSizeSp = 18, gestureScale = 1.03f))
    }

    @Test
    fun `reader pdf transform applies centered zoom pan and clamps viewport bounds`() {
        val viewportSize = IntSize(width = 1000, height = 800)
        val centeredZoom = readerPdfTransform(
            current = ReaderPdfTransform(zoom = 1f, pan = Offset.Zero),
            zoomChange = 2f,
            panChange = Offset(120f, 80f),
            centroid = Offset(x = 500f, y = 400f),
            viewportSize = viewportSize,
        )
        assertEquals(2f, centeredZoom.zoom)
        assertEquals(Offset(120f, 80f), centeredZoom.pan)

        val clampedPan = readerPdfTransform(
            current = centeredZoom,
            zoomChange = 1f,
            panChange = Offset(1000f, 1000f),
            centroid = Offset(x = 500f, y = 400f),
            viewportSize = viewportSize,
        )
        assertEquals(Offset(500f, 400f), clampedPan.pan)

        val clampedZoomOut = readerPdfTransform(
            current = centeredZoom,
            zoomChange = 0.1f,
            panChange = Offset.Zero,
            centroid = Offset(x = 500f, y = 400f),
            viewportSize = viewportSize,
        )
        assertEquals(1f, clampedZoomOut.zoom)
        assertEquals(Offset.Zero, clampedZoomOut.pan)

        val clampedZoomIn = readerPdfTransform(
            current = centeredZoom,
            zoomChange = 3f,
            panChange = Offset.Zero,
            centroid = Offset(x = 500f, y = 400f),
            viewportSize = viewportSize,
        )
        assertEquals(4f, clampedZoomIn.zoom)
    }

    @Test
    fun `reader pdf transform keeps existing transform for unspecified centroid`() {
        val transform = readerPdfTransform(
            current = ReaderPdfTransform(zoom = 2f, pan = Offset(120f, 80f)),
            zoomChange = 1f,
            panChange = Offset.Zero,
            centroid = Offset.Unspecified,
            viewportSize = IntSize(width = 1000, height = 800),
        )

        assertEquals(2f, transform.zoom)
        assertEquals(Offset(120f, 80f), transform.pan)
        assertTrue(transform.pan.x.isFinite())
        assertTrue(transform.pan.y.isFinite())
    }

    @Test
    fun `visual page double tap zooms around tap then resets`() {
        val viewport = IntSize(width = 1000, height = 800)
        val zoomed = readerDoubleTapVisualTransform(
            current = ReaderPdfTransform(zoom = 1f, pan = Offset.Zero),
            tapPosition = Offset(x = 750f, y = 600f),
            viewportSize = viewport,
        )

        assertEquals(2.5f, zoomed.zoom)
        assertEquals(Offset(x = -375f, y = -300f), zoomed.pan)
        assertEquals(
            ReaderPdfTransform(zoom = 1f, pan = Offset.Zero),
            readerDoubleTapVisualTransform(
                current = zoomed,
                tapPosition = Offset(x = 750f, y = 600f),
                viewportSize = viewport,
            ),
        )
    }
}

package com.tedd.teddreader.core.designsystem

import androidx.compose.ui.graphics.toArgb
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderLightBackgroundArgb
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the one conversion between the model's stored colours and Compose's: a stored `0xAARRGGBB` has to
 * come back byte for byte, alpha included.
 *
 * Worth pinning because the conversion goes through `Int`, where a sign-extension mistake silently turns a
 * translucent overlay opaque — which reads as "the scrim is too dark" rather than as a bug in a converter.
 */
class ReaderColorsTest {
    /** An opaque page colour survives the round trip unchanged. */
    @Test
    fun readerColorToColorPreservesOpaqueArgb() {
        val color = ReaderColor(ReaderLightBackgroundArgb).toColor()

        assertEquals(ReaderLightBackgroundArgb, color.toArgb().toLong() and 0xFFFFFFFFL)
    }

    /** A translucent colour keeps its alpha, which is what the control surfaces and scrims depend on. */
    @Test
    fun readerColorToColorPreservesAlpha() {
        val argb = 0x80112233L
        val color = ReaderColor(argb).toColor()

        assertEquals(argb, color.toArgb().toLong() and 0xFFFFFFFFL)
    }
}

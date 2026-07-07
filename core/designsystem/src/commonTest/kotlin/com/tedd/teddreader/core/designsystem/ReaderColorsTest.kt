package com.tedd.teddreader.core.designsystem

import androidx.compose.ui.graphics.toArgb
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderLightBackgroundArgb
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderColorsTest {
    @Test
    fun readerColorToColorPreservesOpaqueArgb() {
        val color = ReaderColor(ReaderLightBackgroundArgb).toColor()

        assertEquals(ReaderLightBackgroundArgb, color.toArgb().toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun readerColorToColorPreservesAlpha() {
        val argb = 0x80112233L
        val color = ReaderColor(argb).toColor()

        assertEquals(argb, color.toArgb().toLong() and 0xFFFFFFFFL)
    }
}

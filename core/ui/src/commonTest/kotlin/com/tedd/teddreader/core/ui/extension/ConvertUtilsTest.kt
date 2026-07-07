package com.tedd.teddreader.core.ui.extension

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ConvertUtilsTest {
    @Test
    fun densityConvertsPixelsToSpWithDensityAndFontScale() {
        val density = Density(density = 2f, fontScale = 1.5f)

        assertEquals(10f, density.pxToSp(30f).value)
    }

    @Test
    fun densityConvertsDpToSpWithFontScaleOnly() {
        val density = Density(density = 3f, fontScale = 1.5f)

        assertEquals(8f, density.dpToSp(12.dp).value)
    }
}

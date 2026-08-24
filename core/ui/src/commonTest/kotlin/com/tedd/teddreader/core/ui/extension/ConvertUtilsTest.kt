package com.tedd.teddreader.core.ui.extension

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies `Density.pxToSp`/`Density.dpToSp` (defined in this module's `ConvertUtils.kt`, not owned
 * by this documentation pass) convert correctly given both a pixel density and a font scale, since
 * these two units are easy to get backwards: sp already factors out density but not the user's font
 * scale, so a conversion helper that gets the order of operations wrong would look correct at
 * `fontScale = 1` and only misbehave once a user changes their system font size.
 */
class ConvertUtilsTest {
    /**
     * At `density = 2`, 30px is 15dp; at `fontScale = 1.5`, 15dp of text becomes 10sp — confirming the
     * density division happens before the font-scale division, not after.
     */
    @Test
    fun densityConvertsPixelsToSpWithDensityAndFontScale() {
        val density = Density(density = 2f, fontScale = 1.5f)

        assertEquals(10f, density.pxToSp(30f).value)
    }

    /**
     * A dp value is already density-independent, so converting it to sp should divide only by
     * `fontScale` and ignore `density` entirely — confirmed here by picking a `density` value that
     * would change the result if it were (incorrectly) applied.
     */
    @Test
    fun densityConvertsDpToSpWithFontScaleOnly() {
        val density = Density(density = 3f, fontScale = 1.5f)

        assertEquals(8f, density.dpToSp(12.dp).value)
    }
}

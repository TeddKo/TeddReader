package com.tedd.teddreader.app.reader

import androidx.compose.ui.graphics.toArgb
import com.tedd.teddreader.core.common.model.ReaderDarkBackgroundArgb
import com.tedd.teddreader.core.common.model.ReaderLightBackgroundArgb
import com.tedd.teddreader.core.common.model.ReaderSepiaBackgroundArgb
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.withThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeddReaderAppThemeTest {
    @Test
    fun systemLikeThemeModesFollowSystemDarkTheme() {
        assertTrue(appUsesDarkTheme(ReaderThemeMode.PUBLISHER, systemInDarkTheme = true))
        assertFalse(appUsesDarkTheme(ReaderThemeMode.PUBLISHER, systemInDarkTheme = false))
        assertTrue(appUsesDarkTheme(ReaderThemeMode.SYSTEM, systemInDarkTheme = true))
        assertFalse(appUsesDarkTheme(ReaderThemeMode.SYSTEM, systemInDarkTheme = false))
    }

    @Test
    fun explicitLightLikeThemeModesDisableDarkTheme() {
        assertFalse(appUsesDarkTheme(ReaderThemeMode.LIGHT, systemInDarkTheme = true))
        assertFalse(appUsesDarkTheme(ReaderThemeMode.SEPIA, systemInDarkTheme = true))
        assertFalse(appUsesDarkTheme(ReaderThemeMode.CUSTOM, systemInDarkTheme = true))
    }

    @Test
    fun explicitDarkThemeModeEnablesDarkTheme() {
        assertTrue(appUsesDarkTheme(ReaderThemeMode.DARK, systemInDarkTheme = false))
    }

    @Test
    fun systemBarBackgroundFollowsTheGlobalThemeOnEveryScreen() {
        listOf(
            Triple(ReaderThemeMode.LIGHT, true, ReaderLightBackgroundArgb),
            Triple(ReaderThemeMode.DARK, false, ReaderDarkBackgroundArgb),
            Triple(ReaderThemeMode.SEPIA, true, ReaderSepiaBackgroundArgb),
            Triple(ReaderThemeMode.SYSTEM, true, ReaderDarkBackgroundArgb),
            Triple(ReaderThemeMode.PUBLISHER, false, ReaderLightBackgroundArgb),
        ).forEach { (mode, systemInDarkTheme, expectedArgb) ->
            val actualArgb = appSystemBarBackground(
                style = ReaderStyle().withThemeMode(mode),
                systemInDarkTheme = systemInDarkTheme,
            ).toArgb().toLong() and 0xFFFFFFFFL

            assertEquals(expectedArgb, actualArgb)
        }
    }
}

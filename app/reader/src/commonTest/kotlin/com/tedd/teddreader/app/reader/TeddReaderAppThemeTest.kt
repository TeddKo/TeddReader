package com.tedd.teddreader.app.reader

import com.tedd.teddreader.core.common.model.ReaderThemeMode
import kotlin.test.Test
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
}

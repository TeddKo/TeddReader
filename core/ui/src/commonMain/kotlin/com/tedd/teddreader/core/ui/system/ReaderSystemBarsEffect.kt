package com.tedd.teddreader.core.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Applies the app-wide theme colour and matching icon contrast to the platform system bars.
 * This is composed once at the app root so home, search, settings, document info, and reader all
 * receive the same persisted reader-theme update.
 *
 * @param backgroundColor The current global theme's opaque page colour.
 */
@Composable
expect fun SystemBarsThemeEffect(backgroundColor: Color)

/**
 * Owns only the reader-specific window behavior: immersive visibility and keeping the screen awake.
 * System bar colour and icon contrast remain the app root's responsibility.
 *
 * @param visible Whether the system status/navigation bars should be shown.
 * @param keepScreenOn Whether the device's screen should stay awake while reading.
 */
@Composable
expect fun ReaderSystemBarsEffect(
    visible: Boolean,
    keepScreenOn: Boolean,
)

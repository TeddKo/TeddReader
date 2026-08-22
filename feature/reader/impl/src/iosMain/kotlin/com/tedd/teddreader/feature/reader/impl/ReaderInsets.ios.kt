package com.tedd.teddreader.feature.reader.impl

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable

/**
 * iOS's answer: the plain, visibility-aware `WindowInsets.systemBars` — it tracks the status bar
 * and home indicator's currently visible extent, without the "report the bar's size even while
 * hidden" behavior the Android actual (see its own doc) asks `systemBarsIgnoringVisibility` for.
 */
@Composable
internal actual fun readerSystemBarsInsets(): WindowInsets = WindowInsets.systemBars

package com.tedd.teddreader.core.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** iOS system chrome theming is not wired yet; keeps common app-root code platform-neutral. */
@Composable
actual fun SystemBarsThemeEffect(backgroundColor: Color) = Unit

/** iOS reader immersive/keep-awake behavior is not wired yet. */
@Composable
actual fun ReaderSystemBarsEffect(
    visible: Boolean,
    keepScreenOn: Boolean,
) = Unit

package com.tedd.teddreader.core.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
expect fun ReaderSystemBarsEffect(
    visible: Boolean,
    backgroundColor: Color,
    keepScreenOn: Boolean,
)

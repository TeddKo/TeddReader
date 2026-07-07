package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class TeddReaderElevation(
    val none: Dp = 0.dp,
    val xSmall: Dp = 1.dp,
    val small: Dp = 2.dp,
    val medium: Dp = 4.dp,
    val large: Dp = 8.dp,
    val xLarge: Dp = 12.dp,
)

val DefaultTeddReaderElevation = TeddReaderElevation()

package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class TeddReaderSpacing(
    val none: Dp = 0.dp,
    val xxSmall: Dp = 4.dp,
    val xSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val xLarge: Dp = 32.dp,
    val xxLarge: Dp = 40.dp,
    val xxxLarge: Dp = 48.dp,
    val screenPadding: Dp = 20.dp,
    val cardPadding: Dp = 16.dp,
    val sheetPadding: Dp = 24.dp,
    val readerMargin: Dp = 20.dp,
)

val DefaultTeddReaderSpacing = TeddReaderSpacing()

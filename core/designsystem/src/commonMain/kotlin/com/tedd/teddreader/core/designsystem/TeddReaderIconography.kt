package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class TeddReaderIconography(
    val small: Dp = 18.dp,
    val medium: Dp = 24.dp,
    val large: Dp = 32.dp,
)

val DefaultTeddReaderIconography = TeddReaderIconography()

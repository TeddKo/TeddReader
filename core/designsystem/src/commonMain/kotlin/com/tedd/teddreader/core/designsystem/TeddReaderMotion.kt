package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable

@Immutable
data class TeddReaderMotion(
    val shortDurationMs: Int = 120,
    val mediumDurationMs: Int = 200,
    val longDurationMs: Int = 300,
)

val DefaultTeddReaderMotion = TeddReaderMotion()

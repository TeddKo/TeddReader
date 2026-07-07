package com.tedd.teddreader.feature.reader.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.ReaderLocation

@Immutable
data class ReaderOutlineItem(
    val title: String,
    val location: ReaderLocation,
)

package com.tedd.teddreader.core.designsystem

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

@Immutable
data class TeddReaderShapes(
    val extraSmall: CornerBasedShape = RoundedCornerShape(4.dp),
    val small: CornerBasedShape = RoundedCornerShape(8.dp),
    val medium: CornerBasedShape = RoundedCornerShape(12.dp),
    val large: CornerBasedShape = RoundedCornerShape(16.dp),
    val extraLarge: CornerBasedShape = RoundedCornerShape(24.dp),
)

val DefaultTeddReaderShapes = TeddReaderShapes()

fun TeddReaderShapes.toMaterialShapes(): Shapes = Shapes(
    extraSmall = extraSmall,
    small = small,
    medium = medium,
    large = large,
    extraLarge = extraLarge,
)

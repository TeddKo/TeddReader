package com.tedd.teddreader.core.designsystem

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/**
 * The corner radii the app's surfaces use.
 *
 * Kept as the app's own scale rather than read straight from Material so the reader's look can move
 * independently of the library's defaults; [toMaterialShapes] is the one place the two are joined.
 *
 * @property extraSmall a chip or a badge.
 * @property small a button or an input.
 * @property medium a card.
 * @property large a sheet or dialog.
 * @property extraLarge the same radius as [large] today, kept separate so the largest surfaces can grow
 * their own corner without touching cards.
 */
@Immutable
data class TeddReaderShapes(
    val extraSmall: CornerBasedShape = RoundedCornerShape(4.dp),
    val small: CornerBasedShape = RoundedCornerShape(8.dp),
    val medium: CornerBasedShape = RoundedCornerShape(12.dp),
    val large: CornerBasedShape = RoundedCornerShape(16.dp),
    val extraLarge: CornerBasedShape = RoundedCornerShape(16.dp),
)

/** The shape scale the theme installs unless a caller overrides it. */
val DefaultTeddReaderShapes = TeddReaderShapes()

/**
 * Hands this scale to Material, so a Material component that draws its own surface picks up the app's
 * corners instead of the library defaults.
 *
 * @receiver the app's shape scale.
 * @return the same radii in Material's own type, for `MaterialTheme(shapes = …)`.
 */
fun TeddReaderShapes.toMaterialShapes(): Shapes = Shapes(
    extraSmall = extraSmall,
    small = small,
    medium = medium,
    large = large,
    extraLarge = extraLarge,
)

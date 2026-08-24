package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far each kind of surface sits above the page, in one place so two surfaces of the same importance
 * cannot drift apart.
 *
 * @property none flat against the background.
 * @property xSmall a hairline lift, for a divider-like surface.
 * @property small a resting card.
 * @property medium a raised card or a bar.
 * @property large a sheet or menu.
 * @property xLarge a dialog, the highest surface the app draws.
 */
@Immutable
data class TeddReaderElevation(
    val none: Dp = 0.dp,
    val xSmall: Dp = 1.dp,
    val small: Dp = 2.dp,
    val medium: Dp = 4.dp,
    val large: Dp = 8.dp,
    val xLarge: Dp = 12.dp,
)

/** The elevation scale the theme installs unless a caller overrides it. */
val DefaultTeddReaderElevation = TeddReaderElevation()

package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icon sizes, so an icon's box matches the text or control beside it rather than whatever the asset
 * happens to be.
 *
 * @property small an icon inside a dense row or a chip.
 * @property medium the default icon size, matching a Material icon button's content.
 * @property large an icon that stands on its own, as in an empty state.
 */
@Immutable
data class TeddReaderIconography(
    val small: Dp = 18.dp,
    val medium: Dp = 24.dp,
    val large: Dp = 32.dp,
)

/** The icon scale the theme installs unless a caller overrides it. */
val DefaultTeddReaderIconography = TeddReaderIconography()

package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icon sizes, so an icon's box matches the text or control beside it rather than whatever the asset
 * happens to be.
 *
 * [small] and [medium] are the two optical sizes the design language commits to for anything the user
 * acts on; an action icon drawn at another size reads as a mistake next to them. The other two are not
 * more body sizes: [extraSmall] belongs to glyphs that are read rather than pressed, and [large] is a
 * display size for an icon carrying a whole state on its own.
 *
 * @property extraSmall a glyph that sits inline with caption text and is never a touch target — the
 * battery indicator in the reader's status footer is the case this exists for. It is deliberately
 * below the 20/24 action sizes because it has to match the cap height of the caption beside it, not the
 * tap area of a control; scaled up to [small] it would dominate a row meant to be read at a glance.
 * @property small the supporting icon size, for a trailing affordance in a row or a glyph inside a
 * chip — anywhere the icon accompanies text rather than labelling an action by itself.
 * @property medium the default, for an icon that is the sole label of a control.
 * @property large an icon that stands on its own, as in an empty state.
 */
@Immutable
data class TeddReaderIconography(
    val extraSmall: Dp = 16.dp,
    val small: Dp = 20.dp,
    val medium: Dp = 24.dp,
    val large: Dp = 32.dp,
)

/** The icon scale the theme installs unless a caller overrides it. */
val DefaultTeddReaderIconography = TeddReaderIconography()

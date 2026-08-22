package com.tedd.teddreader.core.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * Physical display fold of the current window, measured in dp along the window.
 *
 * A phone or tablet reports none. A book-posture foldable reports a vertical fold whose bounds are
 * the hinge occlusion, so a two-page layout can put its gutter exactly on the spine — see
 * `ReaderSpreadLayout`, the only consumer of [startDp]/[endDp]/[isVertical] beyond [thicknessDp].
 *
 * @property startDp The fold's near edge, in dp from the window's start (left, for a vertical fold)
 * or top (for a horizontal fold).
 * @property endDp The fold's far edge, in the same axis as [startDp]; always >= [startDp].
 * @property isVertical Whether the fold runs top-to-bottom (a book-posture split, gutter on the
 * vertical axis) rather than left-to-right.
 * @property isSeparating Whether the platform treats the two sides of the fold as physically
 * separate display areas (e.g. an unfolded book-style device), as opposed to a fold that is merely
 * present but not currently separating the screen into two areas.
 */
@Immutable
data class DisplayFold(
    val startDp: Float,
    val endDp: Float,
    val isVertical: Boolean,
    val isSeparating: Boolean,
) {
    /** The fold's occlusion width in dp ([endDp] minus [startDp]), never negative. */
    val thicknessDp: Float get() = (endDp - startDp).coerceAtLeast(0f)
}

/**
 * Reads the current window's [DisplayFold] from the platform, recomposing as the device's fold state
 * changes (e.g. the hinge angle crossing into or out of book posture).
 *
 * @return The platform-reported fold, or null on a device with no fold, on Android when no folding
 * feature is currently reported, and unconditionally on iOS (see the iOS `actual`).
 */
@Composable
expect fun rememberDisplayFold(): DisplayFold?

package com.tedd.teddreader.core.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * Physical display fold of the current window, measured in dp along the window.
 *
 * A phone or tablet reports none. A book-posture foldable reports a vertical fold whose bounds are
 * the hinge occlusion, so a two-page layout can put its gutter exactly on the spine.
 */
@Immutable
data class DisplayFold(
    val startDp: Float,
    val endDp: Float,
    val isVertical: Boolean,
    val isSeparating: Boolean,
) {
    val thicknessDp: Float get() = (endDp - startDp).coerceAtLeast(0f)
}

/** Display fold reported by the platform, or null when there is none. */
@Composable
expect fun rememberDisplayFold(): DisplayFold?

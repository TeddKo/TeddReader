package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.ui.system.DisplayFold
import kotlin.math.max
import kotlin.math.min

/**
 * The number of side-by-side page panes `ReaderScreen` should lay out for the current window: one
 * for an ordinary phone-width read, two for a tablet-width window or a book-posture foldable opened
 * flat. A vertical, separating fold forces two panes even below the width threshold, because such a
 * device is already split by its own hinge — laying out a single continuous page across it would
 * run text straight through the occlusion instead of respecting the seam the hardware imposes.
 *
 * @param widthDp The current window width, in dp.
 * @param heightDp The current window height, in dp. The threshold check compares against
 *   `min(widthDp, heightDp)` rather than [widthDp] alone, so the pane count does not flip merely
 *   because the device rotated.
 * @param fold The device's physical display fold, or null when the platform reports none.
 * @return `2` to lay out a two-page spread, `1` for a single page.
 */
internal fun readerPaneCount(widthDp: Float, heightDp: Float, fold: DisplayFold? = null): Int = when {
    fold != null && fold.isBookSpine() -> 2
    min(widthDp, heightDp) >= TwoPaneMinShortestSideDp -> 2
    else -> 1
}

/**
 * The fraction of a two-pane spread's width given to the left page, chosen so the gutter between
 * panes lands on the fold's hinge occlusion instead of splitting the window straight down the
 * middle. Falls back to an even [BalancedSpreadWeight] split whenever there is no vertical fold to
 * measure against, or the reported window width is non-positive.
 *
 * @param widthDp The current window width, in dp.
 * @param fold The device's physical display fold. Only a vertical fold moves the weight away from
 *   [BalancedSpreadWeight]; a horizontal fold or no fold at all keeps the spread evenly split.
 * @return A value in [MinSpreadWeight]..[MaxSpreadWeight], the left page's share of [widthDp].
 */
internal fun readerSpreadLeftWeight(widthDp: Float, fold: DisplayFold?): Float {
    if (fold == null || !fold.isVertical || widthDp <= 0f) return BalancedSpreadWeight
    val leftDp = fold.startDp
    val rightDp = widthDp - fold.endDp
    if (leftDp <= 0f || rightDp <= 0f) return BalancedSpreadWeight
    return (leftDp / (leftDp + rightDp)).coerceIn(MinSpreadWeight, MaxSpreadWeight)
}

/**
 * The gutter width, in dp, to render between the two panes of a spread. Widened to clear the fold's
 * own hinge occlusion on a book-posture foldable, since a gutter narrower than that occlusion would
 * let page content sit underneath the hinge; passed through unchanged for every other window shape,
 * so an ordinary tablet spread keeps its normal reading gutter.
 *
 * @param fold The device's physical display fold, or null when the platform reports none.
 * @param defaultGutterDp The ordinary reading gutter, in dp, used whenever [fold] is not a
 *   book-posture fold to widen past.
 * @return The larger of [defaultGutterDp] and the fold's own occlusion thickness.
 */
internal fun readerSpreadGutterDp(fold: DisplayFold?, defaultGutterDp: Float): Float =
    if (fold != null && fold.isBookSpine()) max(defaultGutterDp, fold.thicknessDp) else defaultGutterDp

/**
 * True when this fold behaves like a book's spine: vertical, and actually separating the window into
 * two panes a foldable device physically hinges apart, rather than a fold the platform merely
 * reports while the device still lies flat. [readerPaneCount] and [readerSpreadGutterDp] both gate
 * their foldable-specific behavior on this instead of on [DisplayFold.isVertical] alone, so a flat
 * or horizontal fold is never mistaken for a book spine.
 *
 * @receiver The physical display fold being tested.
 * @return True only for a vertical, separating fold.
 */
private fun DisplayFold.isBookSpine(): Boolean = isVertical && isSeparating

/** The even 50/50 split [readerSpreadLeftWeight] falls back to when there is no hinge to weight the spread toward. */
private const val BalancedSpreadWeight = 0.5f

/** The narrowest share [readerSpreadLeftWeight] will give the left page, so an extreme off-centre hinge can never squeeze one pane down to nothing. */
private const val MinSpreadWeight = 0.2f

/** The widest share [readerSpreadLeftWeight] will give the left page — the mirror bound of [MinSpreadWeight]. */
private const val MaxSpreadWeight = 0.8f

/** The ordinary reading gutter, in dp, between two panes of a spread on a device with no hinge to clear — the default input `ReaderScreen` passes to [readerSpreadGutterDp]. */
internal const val ReaderPaneGutterDp = 16f

/** The shortest-side width, in dp, at or above which [readerPaneCount] treats the window as tablet-sized and lays out two panes even without a book-posture fold forcing it. */
private const val TwoPaneMinShortestSideDp = 600f

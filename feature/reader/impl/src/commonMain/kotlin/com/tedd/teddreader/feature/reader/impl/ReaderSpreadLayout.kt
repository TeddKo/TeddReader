package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.ui.system.DisplayFold
import kotlin.math.max

internal fun readerPaneCount(widthDp: Float, fold: DisplayFold? = null): Int = when {
    fold != null && fold.isBookSpine() -> 2
    widthDp >= TwoPaneMinWidthDp -> 2
    else -> 1
}

/** Fraction of the spread taken by the left page, so the gutter sits on the hinge. */
internal fun readerSpreadLeftWeight(widthDp: Float, fold: DisplayFold?): Float {
    if (fold == null || !fold.isVertical || widthDp <= 0f) return BalancedSpreadWeight
    val leftDp = fold.startDp
    val rightDp = widthDp - fold.endDp
    if (leftDp <= 0f || rightDp <= 0f) return BalancedSpreadWeight
    return (leftDp / (leftDp + rightDp)).coerceIn(MinSpreadWeight, MaxSpreadWeight)
}

/** Gutter wide enough to clear the hinge occlusion, never narrower than the reading gutter. */
internal fun readerSpreadGutterDp(fold: DisplayFold?, defaultGutterDp: Float): Float =
    if (fold != null && fold.isBookSpine()) max(defaultGutterDp, fold.thicknessDp) else defaultGutterDp

private fun DisplayFold.isBookSpine(): Boolean = isVertical && isSeparating

private const val BalancedSpreadWeight = 0.5f
private const val MinSpreadWeight = 0.2f
private const val MaxSpreadWeight = 0.8f
private const val ReaderPaneMinWidthDp = 280f
internal const val ReaderPaneGutterDp = 16f
private const val TwoPaneMinWidthDp = ReaderPaneMinWidthDp * 2f + ReaderPaneGutterDp

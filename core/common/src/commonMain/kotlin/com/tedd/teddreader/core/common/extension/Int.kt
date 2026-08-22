package com.tedd.teddreader.core.common.extension

/** Default for [toDisplayCount]'s [Int.toDisplayCount] cap — the widest a three-digit badge fits. */
private const val DefaultMaxDisplayCount = 999

/**
 * A count as a badge can show it: never negative, and never wider than [maxDisplayCount].
 *
 * The cap exists so a number cannot grow a chip beyond the layout that holds it; the default of 999 is
 * the widest a three-digit badge fits.
 *
 * @receiver the raw count.
 * @param maxDisplayCount the widest number the badge can hold; defaults to 999, the widest a three-digit
 * badge fits.
 * @return the count clamped to `0..maxDisplayCount`.
 */
fun Int.toDisplayCount(maxDisplayCount: Int = DefaultMaxDisplayCount): Int =
    coerceAtLeast(0).coerceAtMost(maxDisplayCount)

/**
 * A zero-based page index as the reader shows it, since a person's first page is 1.
 *
 * Named rather than written inline so the boundary between the model's indexing and the display's
 * numbering is visible at every site that crosses it — an off-by-one here is a page counter that lies.
 *
 * @receiver a zero-based page index.
 * @return the same page as a person counts it.
 */
fun Int.toOneBasedPageNumber(): Int = this + 1


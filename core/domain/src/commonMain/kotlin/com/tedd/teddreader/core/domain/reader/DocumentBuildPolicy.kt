package com.tedd.teddreader.core.domain.reader

/**
 * Whether the reader may tell the person reading that pagination is finished.
 *
 * Two facts are needed and forgetting either one is the failure mode this function exists to make impossible.
 * A document still being parsed will have sections it does not have yet, so a measurement covering only what
 * is parsed so far is not a measurement of the book; and a fully parsed book can still have no measurement for
 * the type currently on screen. The rule was written out by hand in three places, in three different shapes,
 * and one of those places carried a fourteen-line comment explaining why it was dangerous — a named function
 * that demands both arguments turns that omission into a compile error instead.
 *
 * @param isImportComplete whether every section of the document has been parsed.
 * @param isPaginationMeasured whether the current type and viewport have a real measurement covering it.
 * @return true only when both hold, which is the only state in which a page total may be presented as final.
 */
fun canReportPaginationComplete(isImportComplete: Boolean, isPaginationMeasured: Boolean): Boolean =
    isImportComplete && isPaginationMeasured

/**
 * Whether the background pagination walk still has work for this style.
 *
 * Deliberately answers only the pagination facts. The caller owns import gating because an active import
 * invalidates pagination sessions, while a completed import can safely continue the current measurement.
 *
 * @param isPaginationMeasured whether the current type and viewport are already measured through.
 * @param hasMeasurementForStyle whether a real page breaker exists for the style being continued; without one
 * there is nothing to measure with and asking again cannot help.
 * @return true when the walk should be started, or restarted, for this style.
 */
fun needsPaginationContinuation(isPaginationMeasured: Boolean, hasMeasurementForStyle: Boolean): Boolean =
    !isPaginationMeasured && hasMeasurementForStyle

/**
 * How many spine items one import step parses.
 *
 * A batch bounds how long the reader waits before the page count grows again, and how much work is thrown away
 * when the reader leaves mid-import. Sixteen is small enough that a batch lands quickly on a slow device and
 * large enough that a 500-chapter book does not spend its whole import in per-batch overhead.
 */
const val ImportBatchSectionCount: Int = 16

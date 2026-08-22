package com.tedd.teddreader.core.domain.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the two truth tables that decide how a progressively built document reports itself to the reader.
 *
 * These rules used to live inside the reader's ViewModel, where observing one meant standing up four fake
 * repositories and driving a coroutine dispatcher; each was also written out more than once, in different
 * shapes. The failure mode was never a wrong `&&` — it was forgetting to ask one of the two questions at all,
 * which is exactly what a named function taking both arguments prevents.
 */
class DocumentBuildPolicyTest {
    /** Both facts hold: the page total the reader sees is final, so saying so is honest. */
    @Test
    fun paginationIsCompleteWhenTheImportAndTheMeasurementBothAre() {
        assertTrue(canReportPaginationComplete(isImportComplete = true, isPaginationMeasured = true))
    }

    /**
     * A fully parsed book whose current type has never been laid out is not finished — the reader chose a font
     * nobody has read this book at, and only the resumed section has been measured so far.
     */
    @Test
    fun paginationIsNotCompleteWhileTheStyleIsUnmeasured() {
        assertFalse(canReportPaginationComplete(isImportComplete = true, isPaginationMeasured = false))
    }

    /**
     * The dangerous case: a measurement can report itself finished mid-import simply because the import wiped
     * the walk it was on, and believing it would freeze the page total at whatever was parsed then.
     */
    @Test
    fun paginationIsNotCompleteWhileTheImportIsStillRunning() {
        assertFalse(canReportPaginationComplete(isImportComplete = false, isPaginationMeasured = true))
    }

    /** Neither fact holds, which is where a freshly imported book starts. */
    @Test
    fun paginationIsNotCompleteWhenNeitherHolds() {
        assertFalse(canReportPaginationComplete(isImportComplete = false, isPaginationMeasured = false))
    }

    /** Unmeasured with a breaker in hand is exactly the state the walk exists for. */
    @Test
    fun continuationIsNeededWhenAMeasurementExistsAndPagesAreNot() {
        assertTrue(needsPaginationContinuation(isPaginationMeasured = false, hasMeasurementForStyle = true))
    }

    /** Nothing to measure with: asking again cannot make progress, so the walk must not spin. */
    @Test
    fun continuationIsNotNeededWithoutAMeasurementForTheStyle() {
        assertFalse(needsPaginationContinuation(isPaginationMeasured = false, hasMeasurementForStyle = false))
    }

    /** Already measured through: there is no work left for this style. */
    @Test
    fun continuationIsNotNeededOnceTheStyleIsMeasured() {
        assertFalse(needsPaginationContinuation(isPaginationMeasured = true, hasMeasurementForStyle = true))
    }

    /** Measured through and no breaker either — still nothing to do. */
    @Test
    fun continuationIsNotNeededWhenMeasuredWithoutABreaker() {
        assertFalse(needsPaginationContinuation(isPaginationMeasured = true, hasMeasurementForStyle = false))
    }

    /** The import batch size is the value the reader actually runs with. */
    @Test
    fun importBatchSizeIsTheOneTheReaderRunsWith() {
        assertEquals(16, ImportBatchSectionCount)
    }
}

package com.tedd.teddreader.core.domain.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 점진적으로 구성되는 문서가 리더에 자체 상태를 알리는 방식을 결정하는 두 진리표를 고정한다.
 *
 * 이 규칙은 이전에 리더의 ViewModel 내부에 있었고, 하나를 관찰하려면 가짜 구현 저장소 4개를 준비하고 코루틴
 * 디스패처를 구동해야 했다. 각 규칙은 서로 다른 형태로 두 번 이상 작성되기도 했다. 실패 원인은 잘못된
 * `&&`가 아니라 두 질문 중 하나를 아예 확인하지 않는 것이었으며, 두 인자를 받는 이름 있는 함수가 정확히 이를
 * 방지한다.
 */
class DocumentBuildPolicyTest {
    /** 두 조건이 모두 충족된다. 리더가 보는 페이지 합계는 최종값이므로 완료됐다고 알려도 정확하다. */
    @Test
    fun paginationIsCompleteWhenTheImportAndTheMeasurementBothAre() {
        assertTrue(canReportPaginationComplete(isImportComplete = true, isPaginationMeasured = true))
    }

    /**
     * 파싱이 끝난 책이라도 현재 글꼴 설정으로 배치한 적이 없으면 완료된 것이 아니다. 아무도 이 책에 사용하지 않은
     * 글꼴을 독자가 선택했으며, 지금까지는 재개한 섹션만 측정됐다.
     */
    @Test
    fun paginationIsNotCompleteWhileTheStyleIsUnmeasured() {
        assertFalse(canReportPaginationComplete(isImportComplete = true, isPaginationMeasured = false))
    }

    /**
     * 위험한 경우다. 가져오기가 진행 중이던 순회를 지웠다는 이유만으로 측정 자체는 완료됐다고 보고할 수 있으며,
     * 이를 믿으면 당시 파싱된 내용의 페이지 합계로 고정된다.
     */
    @Test
    fun paginationIsNotCompleteWhileTheImportIsStillRunning() {
        assertFalse(canReportPaginationComplete(isImportComplete = false, isPaginationMeasured = true))
    }

    /** 두 조건 모두 충족되지 않으며, 새로 가져온 책은 이 상태에서 시작한다. */
    @Test
    fun paginationIsNotCompleteWhenNeitherHolds() {
        assertFalse(canReportPaginationComplete(isImportComplete = false, isPaginationMeasured = false))
    }

    /** 측정 수단이 있고 아직 측정되지 않은 상태가 바로 이 순회가 존재하는 이유다. */
    @Test
    fun continuationIsNeededWhenAMeasurementExistsAndPagesAreNot() {
        assertTrue(needsPaginationContinuation(isPaginationMeasured = false, hasMeasurementForStyle = true))
    }

    /** 측정 수단이 없다. 다시 요청해도 진행되지 않으므로 순회를 반복하면 안 된다. */
    @Test
    fun continuationIsNotNeededWithoutAMeasurementForTheStyle() {
        assertFalse(needsPaginationContinuation(isPaginationMeasured = false, hasMeasurementForStyle = false))
    }

    /** 이미 끝까지 측정됐으므로 이 스타일에 남은 작업이 없다. */
    @Test
    fun continuationIsNotNeededOnceTheStyleIsMeasured() {
        assertFalse(needsPaginationContinuation(isPaginationMeasured = true, hasMeasurementForStyle = true))
    }

    /** 끝까지 측정됐고 페이지 구분기도 없으므로 여전히 할 일이 없다. */
    @Test
    fun continuationIsNotNeededWhenMeasuredWithoutABreaker() {
        assertFalse(needsPaginationContinuation(isPaginationMeasured = true, hasMeasurementForStyle = false))
    }

    /** 가져오기 배치 크기가 리더가 실제로 사용하는 값임을 확인한다. */
    @Test
    fun importBatchSizeIsTheOneTheReaderRunsWith() {
        assertEquals(16, ImportBatchSectionCount)
    }
}

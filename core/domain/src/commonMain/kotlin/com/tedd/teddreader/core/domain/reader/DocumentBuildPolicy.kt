package com.tedd.teddreader.core.domain.reader

/**
 * 리더가 읽는 사람에게 페이지 나누기가 끝났다고 알려도 되는지를 나타낸다.
 *
 * 두 조건이 모두 필요하며, 이 함수는 둘 중 하나를 빠뜨리는 실패를 불가능하게 만들기 위해 존재한다.
 * 아직 파싱 중인 문서에는 확보하지 못한 섹션이 있으므로 지금까지 파싱한 내용만 포함하는 측정은 책 전체의
 * 측정이 아니다. 또한 파싱이 끝난 책에도 현재 화면에 표시된 글꼴 설정의 측정이 없을 수 있다. 이 규칙은
 * 세 곳에서 서로 다른 형태로 직접 작성되어 있었고, 그중 한 곳에는 왜 위험한지를 설명하는 14줄짜리 주석도
 * 있었다. 두 인자를 모두 요구하는 이름 있는 함수로 만들면 이런 누락이 대신 컴파일 오류가 된다.
 *
 * @param isImportComplete 문서의 모든 섹션이 파싱되었는지 여부.
 * @param isPaginationMeasured 현재 글꼴 설정과 뷰포트를 포괄하는 실제 측정이 있는지 여부.
 * @return 두 조건이 모두 충족될 때만 true. 페이지 합계를 최종값으로 표시할 수 있는 유일한 상태다.
 */
fun canReportPaginationComplete(isImportComplete: Boolean, isPaginationMeasured: Boolean): Boolean =
    isImportComplete && isPaginationMeasured

/**
 * 이 스타일의 백그라운드 페이지 나누기 순회에 남은 작업이 있는지를 나타낸다.
 *
 * 의도적으로 페이지 나누기 관련 조건만 판정한다. 진행 중인 가져오기는 페이지 나누기 세션을 무효화하지만,
 * 완료된 가져오기는 현재 측정을 안전하게 계속할 수 있으므로 가져오기 조건 판단은 호출자가 담당한다.
 *
 * @param isPaginationMeasured 현재 글꼴 설정과 뷰포트의 측정이 이미 끝까지 완료되었는지 여부.
 * @param hasMeasurementForStyle 계속할 스타일에 실제 페이지 구분기가 있는지 여부. 없으면 측정할 수단이 없으며
 * 다시 요청해도 도움이 되지 않는다.
 * @return 이 스타일의 순회를 시작하거나 다시 시작해야 하면 true.
 */
fun needsPaginationContinuation(isPaginationMeasured: Boolean, hasMeasurementForStyle: Boolean): Boolean =
    !isPaginationMeasured && hasMeasurementForStyle

/**
 * 한 번의 가져오기 단계에서 파싱하는 스파인 항목 수다.
 *
 * 배치는 페이지 수가 다시 늘어날 때까지 리더가 기다리는 시간과, 가져오기 도중 리더를 나갈 때 버려지는 작업량을
 * 제한한다. 16은 느린 기기에서도 한 배치가 빠르게 끝날 만큼 작고, 500개 챕터로 된 책이 배치별 부가 작업에
 * 가져오기 시간 대부분을 쓰지 않을 만큼 크다.
 */
const val ImportBatchSectionCount: Int = 16

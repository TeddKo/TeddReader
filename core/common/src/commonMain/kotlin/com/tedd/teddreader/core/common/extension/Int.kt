package com.tedd.teddreader.core.common.extension

/**
 * [toDisplayCount]의 [Int.toDisplayCount] 상한 기본값으로, 세 자리 배지에 들어가는 가장 큰 값이다.
 */
private const val DefaultMaxDisplayCount = 999

/**
 * 배지에 표시할 수 있는 개수로, 음수가 아니며 [maxDisplayCount]보다 크지 않다.
 *
 * 숫자가 커져 칩을 담는 레이아웃보다 칩이 커지는 일을 막기 위한 상한이다. 기본값 999는 세 자리 배지에 들어가는 가장 큰 값이다.
 *
 * @receiver 원본 개수.
 * @param maxDisplayCount 배지가 담을 수 있는 가장 큰 숫자. 기본값은 세 자리 배지에 들어가는 가장 큰 값인 999이다.
 * @return `0..maxDisplayCount`로 제한한 개수.
 */
fun Int.toDisplayCount(maxDisplayCount: Int = DefaultMaxDisplayCount): Int =
    coerceAtLeast(0).coerceAtMost(maxDisplayCount)

/**
 * 독자가 보는 0부터 시작하는 페이지 인덱스이다. 사람이 세는 첫 페이지는 1이기 때문이다.
 *
 * 인라인으로 쓰지 않고 이름을 붙여 모델의 인덱스와 화면의 번호 체계 사이 경계를 모든 변환 지점에서 드러낸다. 여기서 하나가 어긋나면 페이지 카운터가 잘못된 값을 표시한다.
 *
 * @receiver 0부터 시작하는 페이지 인덱스.
 * @return 사람이 세는 방식으로 나타낸 같은 페이지.
 */
fun Int.toOneBasedPageNumber(): Int = this + 1

/**
 * 화면에 보여줄 숫자를 `#,##0` 패턴, 즉 세 자리마다 쉼표를 넣은 정수 문자열로 만든다.
 *
 * 페이지 슬라이더 라벨·페이지 분수·페이지 수처럼 수천 단위까지 커지는 숫자는 구분 기호 없이 쓰면 한눈에
 * 자릿수를 읽기 어렵다. 로캘별 구분 기호 대신 항상 쉼표를 쓰는 것은 앱이 지원하는 영어·한국어가 모두
 * 쉼표를 쓰고, `commonMain`에서 플랫폼 포매터 없이 Android와 iOS가 같은 결과를 내야 하기 때문이다.
 * 표시 전용이며, 저장하거나 다시 파싱하는 값에는 쓰지 않는다.
 *
 * @receiver 표시할 정수. 음수는 앞에 `-`를 붙여 같은 규칙으로 묶는다.
 * @return 예를 들어 `1234567`은 `"1,234,567"`, `999`는 `"999"`.
 */
fun Long.toGroupedString(): String {
    val digits = toString().removePrefix("-")
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (this < 0) "-$grouped" else grouped
}

/**
 * [Long.toGroupedString]과 같은 `#,##0` 표시 문자열로, 페이지 번호처럼 `Int`로 다루는 값을 위한 진입점이다.
 *
 * @receiver 표시할 정수.
 * @return 세 자리마다 쉼표를 넣은 문자열.
 */
fun Int.toGroupedString(): String = toLong().toGroupedString()

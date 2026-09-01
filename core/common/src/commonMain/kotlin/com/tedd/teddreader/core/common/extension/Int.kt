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


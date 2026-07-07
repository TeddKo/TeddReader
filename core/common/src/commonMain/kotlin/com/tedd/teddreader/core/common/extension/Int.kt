package com.tedd.teddreader.core.common.extension

private const val DefaultMaxDisplayCount = 999

fun Int.toDisplayCount(maxDisplayCount: Int = DefaultMaxDisplayCount): Int =
    coerceAtLeast(0).coerceAtMost(maxDisplayCount)

fun Int.toOneBasedPageNumber(): Int = this + 1


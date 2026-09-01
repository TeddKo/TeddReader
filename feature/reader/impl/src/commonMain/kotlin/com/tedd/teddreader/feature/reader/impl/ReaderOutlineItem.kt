package com.tedd.teddreader.feature.reader.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.ReaderLocation

/**
 * 리더 목차의 항목 하나 — 문서 자체의 내비게이션(EPUB의 nav 문서)에서 가져오거나, 그런 것이 없으면
 * 섹션/페이지당 하나씩 합성한 것이다.
 *
 * @property title 이 항목에 표시되는 텍스트.
 * @property location 이 항목을 선택하면 리더가 이동할 위치.
 * @property level 아웃라인 목록에서 들여쓰기에 쓰이는 중첩 깊이. 1이 최상위이고 숫자가 커질수록 더 들여쓴다.
 *   보고할 실제 계층이 없는 문서라면 기본값 1을 쓴다.
 */
@Immutable
data class ReaderOutlineItem(
    val title: String,
    val location: ReaderLocation,
    val level: Int = 1,
)

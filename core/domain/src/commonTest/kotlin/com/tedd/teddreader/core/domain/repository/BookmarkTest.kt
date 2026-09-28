package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * 저장 위치의 기본 키와 정렬 시각이 Room 행으로 전달되기 전에 지켜야 하는 최소 도메인 불변식을 고정한다.
 */
class BookmarkTest {
    /**
     * 빈 기본 키는 서로 다른 저장 위치를 같은 행으로 덮어쓸 수 있으므로 생성 단계에서 거부한다.
     */
    @Test
    fun rejectsBlankId() {
        assertFailsWith<IllegalArgumentException> {
            Bookmark(
                id = " ",
                documentId = DocumentId("doc-1"),
                location = ReaderLocation.TextOffset(0L),
                createdAtEpochMillis = 1L,
            )
        }
    }

    /**
     * epoch 이전 시각은 최신순 정렬과 저장 시각 의미를 깨뜨리므로 생성 단계에서 거부한다.
     */
    @Test
    fun rejectsNegativeCreationTime() {
        assertFailsWith<IllegalArgumentException> {
            Bookmark(
                id = "doc-1:text:0",
                documentId = DocumentId("doc-1"),
                location = ReaderLocation.TextOffset(0L),
                createdAtEpochMillis = -1L,
            )
        }
    }
}

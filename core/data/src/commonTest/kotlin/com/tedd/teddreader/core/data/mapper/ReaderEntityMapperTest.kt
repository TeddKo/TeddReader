package com.tedd.teddreader.core.data.mapper

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import com.tedd.teddreader.core.domain.repository.ReadingSession
import com.tedd.teddreader.core.room.entity.ReadingProgressEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderEntityMapperTest {
    @Test
    fun readingProgressRoundTripsThroughEntity() {
        val progress = ReadingProgress(
            documentId = DocumentId("doc-1"),
            location = ReaderLocation.TextOffset(42),
            pageIndex = PageIndex(current = 2, total = 10),
            updatedAtEpochMillis = 1_000,
        )

        assertEquals(progress, progress.toReadingProgressEntity().toReadingProgress())
    }

    /**
     * 이전 행의 전체 페이지 수가 없거나 현재 인덱스가 전체 수와 같아도 영속 위치를 읽는 과정은 실패하지 않고 유효한 표시 인덱스로 제한해야 한다.
     */
    @Test
    fun storedReadingProgressClampsPageIndexToKnownPages() {
        val withoutTotal = ReadingProgressEntity(
            documentId = "doc-1",
            readerLocation = "txt:42",
            currentPageIndex = 7,
            totalPageCount = null,
            updatedAtEpochMillis = 1_000L,
        ).toReadingProgress()
        val pastLastPage = ReadingProgressEntity(
            documentId = "doc-1",
            readerLocation = "txt:42",
            currentPageIndex = 10,
            totalPageCount = 10,
            updatedAtEpochMillis = 1_000L,
        ).toReadingProgress()

        assertEquals(PageIndex(current = 0, total = 0), withoutTotal.pageIndex)
        assertEquals(PageIndex(current = 9, total = 10), pastLastPage.pageIndex)
    }

    @Test
    fun readingSessionRoundTripsThroughEntity() {
        val session = ReadingSession(
            id = "session-1",
            documentId = DocumentId("doc-1"),
            startedAtEpochMillis = 1_000,
            endedAtEpochMillis = 2_000,
            activeMillis = 700,
            startLocation = ReaderLocation.TextOffset(10),
            endLocation = ReaderLocation.TextOffset(20),
        )

        assertEquals(session, session.toReadingSessionEntity().toReadingSession())
    }

    @Test
    fun searchIndexRowsUseParserVersion9() {
        val entity = ReaderSection(
            index = 0,
            title = "Chapter",
            text = "Body",
            range = TextRange(0, 4),
        ).toSearchIndexEntity(
            documentId = DocumentId("doc"),
            blocks = listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 4))),
        )

        assertEquals(9, entity.parserVersion)
    }
}

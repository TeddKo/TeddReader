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
    fun searchIndexRowsUseParserVersion7() {
        val entity = ReaderSection(
            index = 0,
            title = "Chapter",
            text = "Body",
            range = TextRange(0, 4),
        ).toSearchIndexEntity(
            documentId = DocumentId("doc"),
            blocks = listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 4))),
        )

        assertEquals(7, entity.parserVersion)
    }
}

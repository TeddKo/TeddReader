package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReadingStats
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import com.tedd.teddreader.core.domain.repository.ReadingSession
import com.tedd.teddreader.core.domain.repository.ReadingStatsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Pins the composed read behind the document-info screen: three sources, one answer, and never a partial one.
 *
 * The all-or-nothing property is why this deserves a test of its own. The screen shows metadata, a page number
 * and reading totals as a single panel, so applying two of the three and leaving the third blank would read as
 * a book with no reading history rather than as a failure.
 */
class GetDocumentInfoUseCaseTest {
    private val documentId = DocumentId("file:///book.epub")

    private val metadata = DocumentMetadata(
        id = documentId,
        location = DocumentLocation(sourceUri = documentId.value, displayName = "book.epub"),
        format = DocumentFormat.EPUB,
        addedAtEpochMillis = 1_000,
    )

    private val stats = ReadingStats(documentId = documentId, activeMillis = 0, charactersRead = 12, wordsRead = 3)

    /**
     * Answers with fixed metadata, or throws from the one read this use case makes.
     *
     * @property metadata what [getDocument] answers.
     * @property failure thrown instead, for the partial-answer case.
     */
    private class FakeDocuments(
        private val metadata: DocumentMetadata?,
        private val failure: Throwable? = null,
    ) : DocumentRepository {
        override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? {
            failure?.let { throw it }
            return metadata
        }

        override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = flowOf(emptyList())
        override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = null
        override suspend fun getPageWindows(
            documentId: DocumentId,
            style: ReaderStyle,
            viewportSize: ViewportSize?,
            pageBreaker: ReaderPageBreaker?,
            anchorOffset: Long?,
        ): List<PageWindow> = emptyList()

        override suspend fun importDocument(
            source: DocumentImportSource,
            importedAtEpochMillis: Long,
        ): ReaderDocument = error("not used by this use case")

        override suspend fun upsertDocument(document: DocumentMetadata) = Unit
        override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit
        override suspend fun deleteDocument(documentId: DocumentId) = Unit
    }

    /** Holds one stored position, or none at all for a book that has never been opened. */
    private class FakeReader(private val progress: ReadingProgress?) : ReaderRepository {
        override fun observeProgress(documentId: DocumentId): Flow<ReadingProgress?> = flowOf(progress)
        override suspend fun getProgress(documentId: DocumentId): ReadingProgress? = progress
        override suspend fun saveProgress(progress: ReadingProgress) = Unit
        override suspend fun deleteProgress(documentId: DocumentId) = Unit
    }

    /** Answers fixed totals; the write half of the interface is never reached by this read. */
    private class FakeStats(private val stats: ReadingStats) : ReadingStatsRepository {
        override fun observeSessions(documentId: DocumentId): Flow<List<ReadingSession>> = flowOf(emptyList())
        override suspend fun recordSession(session: ReadingSession) = Unit
        override suspend fun getStats(documentId: DocumentId): ReadingStats = stats
    }

    /** All three reads land in one answer, which is the whole reason the composition exists. */
    @Test
    fun composesMetadataProgressAndStats() = runTest {
        val progress = ReadingProgress(
            documentId = documentId,
            location = ReaderLocation.TextOffset(40),
            pageIndex = PageIndex(current = 3, total = 9),
            updatedAtEpochMillis = 0,
        )
        val useCase = GetDocumentInfoUseCase(FakeDocuments(metadata), FakeReader(progress), FakeStats(stats))

        val info = useCase(documentId)

        assertEquals(metadata, info.metadata)
        assertEquals(PageIndex(current = 3, total = 9), info.pageIndex)
        assertEquals(stats, info.stats)
    }

    /** A book never opened has no page to show, which is a different fact from being on page one. */
    @Test
    fun pageIndexIsAbsentForABookThatWasNeverOpened() = runTest {
        val useCase = GetDocumentInfoUseCase(FakeDocuments(metadata), FakeReader(null), FakeStats(stats))

        assertNull(useCase(documentId).pageIndex)
    }

    /** A document the library does not hold still answers, with the absence made explicit. */
    @Test
    fun metadataIsAbsentForAnUnknownDocument() = runTest {
        val useCase = GetDocumentInfoUseCase(FakeDocuments(null), FakeReader(null), FakeStats(stats))

        assertNull(useCase(documentId).metadata)
    }

    /** One failed read fails the whole answer, so the screen cannot show a half-filled panel. */
    @Test
    fun aFailedReadPropagatesInsteadOfYieldingAPartialAnswer() = runTest {
        val useCase = GetDocumentInfoUseCase(
            FakeDocuments(metadata, failure = IllegalStateException("storage unavailable")),
            FakeReader(null),
            FakeStats(stats),
        )

        val error = assertFailsWith<IllegalStateException> { useCase(documentId) }

        assertEquals("storage unavailable", error.message)
    }
}

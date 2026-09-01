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
 * 문서 정보 화면 뒤의 조합된 읽기, 즉 세 공급원과 하나의 결과, 부분 결과 없음이라는 계약을 고정한다.
 *
 * 전부 아니면 전무라는 속성 때문에 별도 테스트가 필요하다. 화면은 메타데이터, 페이지 번호, 읽기 합계를 하나의
 * 패널로 표시한다. 셋 중 둘만 적용하고 나머지를 비워 두면 실패가 아니라 읽기 이력이 없는 책처럼 보인다.
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
     * 고정된 메타데이터를 반환하거나 이 유스 케이스가 수행하는 유일한 읽기에서 예외를 던진다.
     *
     * @property metadata [getDocument]가 반환할 값.
     * @property failure 부분 결과 사례에서 대신 던질 예외.
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

    /** 한 번도 열지 않은 책에는 저장된 위치가 없으며, 그 외에는 위치 하나를 보관한다. */
    private class FakeReader(private val progress: ReadingProgress?) : ReaderRepository {
        override fun observeProgress(documentId: DocumentId): Flow<ReadingProgress?> = flowOf(progress)
        override suspend fun getProgress(documentId: DocumentId): ReadingProgress? = progress
        override suspend fun saveProgress(progress: ReadingProgress) = Unit
        override suspend fun deleteProgress(documentId: DocumentId) = Unit
    }

    /** 고정된 합계를 반환한다. 이 읽기에서는 인터페이스의 쓰기 쪽에 도달하지 않는다. */
    private class FakeStats(private val stats: ReadingStats) : ReadingStatsRepository {
        override fun observeSessions(documentId: DocumentId): Flow<List<ReadingSession>> = flowOf(emptyList())
        override suspend fun recordSession(session: ReadingSession) = Unit
        override suspend fun getStats(documentId: DocumentId): ReadingStats = stats
    }

    /** 세 읽기가 하나의 결과에 담긴다. 이 조합이 존재하는 이유 전체를 검증한다. */
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

    /** 한 번도 열지 않은 책에는 표시할 페이지가 없으며, 이는 1페이지에 있다는 것과 다른 상태다. */
    @Test
    fun pageIndexIsAbsentForABookThatWasNeverOpened() = runTest {
        val useCase = GetDocumentInfoUseCase(FakeDocuments(metadata), FakeReader(null), FakeStats(stats))

        assertNull(useCase(documentId).pageIndex)
    }

    /** 라이브러리에 없는 문서도 결과를 반환하되, 없다는 사실을 명시한다. */
    @Test
    fun metadataIsAbsentForAnUnknownDocument() = runTest {
        val useCase = GetDocumentInfoUseCase(FakeDocuments(null), FakeReader(null), FakeStats(stats))

        assertNull(useCase(documentId).metadata)
    }

    /** 읽기 하나가 실패하면 전체 결과가 실패하므로 화면은 절반만 채운 패널을 표시할 수 없다. */
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

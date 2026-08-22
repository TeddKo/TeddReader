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
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReaderSettings
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpenReaderDocumentUseCaseTest {
    private val documentId = DocumentId("file:///book.epub")
    private val style = ReaderStyle(fontSizeSp = 20f)
    private val viewport = ViewportSize(320, 560)
    private val metadata = DocumentMetadata(
        id = documentId,
        location = DocumentLocation(sourceUri = documentId.value, displayName = "book.epub"),
        format = DocumentFormat.EPUB,
        addedAtEpochMillis = 1,
        pageCount = 8,
    )
    private val document = ReaderDocument(
        id = documentId,
        title = "Book",
        format = DocumentFormat.EPUB,
        sections = listOf(
            ReaderSection(0, "hello world", TextRange(0, 11), title = "One"),
            ReaderSection(1, "next page", TextRange(11, 20), title = "Two"),
        ),
        pageCount = 8,
    )

    @Test
    fun computesResumeOffsetCurrentPageAndRememberedViewport() = runTest {
        val repository = FakeDocuments(
            metadata = metadata,
            readerDocument = document,
            pageWindows = listOf(
                pageWindow(0, 0, 11),
                pageWindow(1, 11, 20),
            ),
            rememberedViewport = viewport,
            isImportComplete = false,
            isPaginationComplete = false,
        )
        val progress = ReadingProgress(
            documentId = documentId,
            location = ReaderLocation.EpubOffset(spineIndex = 1, offset = 3),
            pageIndex = PageIndex(current = 0, total = 99),
            updatedAtEpochMillis = 0,
        )
        val useCase = OpenReaderDocumentUseCase(
            documentRepository = repository,
            readerRepository = FakeReader(progress),
            readerSettingsRepository = FakeSettings(style),
        )

        val open = useCase(
            documentId = documentId,
            hasReportedPaneSize = false,
            viewportSize = viewport,
            pageBreaker = null,
            pageBreakerStyle = null,
        )

        assertEquals(14L, open.anchorOffset)
        assertEquals(1, open.currentPage)
        assertEquals(viewport, open.rememberedViewportSize)
        assertFalse(open.isImportComplete)
        assertFalse(open.isPaginationMeasured)
        assertSame(open.paginated.pageWindows, open.pageWindows)
        assertEquals(null, repository.lastViewportPassed)
    }

    @Test
    fun ignoresMismatchedPageBreakerStyle() = runTest {
        val repository = FakeDocuments(metadata, document, pageWindows = emptyList())
        val useCase = OpenReaderDocumentUseCase(repository, FakeReader(null), FakeSettings(style))
        val breaker = ReaderPageBreaker { _, _ -> intArrayOf(0) }

        useCase(
            documentId = documentId,
            hasReportedPaneSize = true,
            viewportSize = viewport,
            pageBreaker = breaker,
            pageBreakerStyle = ReaderStyle(fontSizeSp = 22f),
        )

        assertNull(repository.lastPageBreakerPassed)
        assertEquals(viewport, repository.lastViewportPassed)
    }

    @Test
    fun visualDocumentsSkipPaginationAndHaveNoRememberedViewport() = runTest {
        val repository = FakeDocuments(
            metadata = metadata.copy(format = DocumentFormat.PDF),
            readerDocument = document.copy(format = DocumentFormat.PDF),
            pageWindows = listOf(pageWindow(0, 0, 11)),
            rememberedViewport = viewport,
        )
        val useCase = OpenReaderDocumentUseCase(repository, FakeReader(null), FakeSettings(style))

        val open = useCase(documentId, false, viewport, null, null)

        assertTrue(open.isVisualMode)
        assertTrue(open.isPaginationMeasured)
        assertTrue(open.pageWindows.isEmpty())
        assertNull(open.rememberedViewportSize)
    }

    @Test
    fun refreshesMetadataAndImportStatusAfterReaderDocumentRepair() = runTest {
        val staleMetadata = metadata.copy(pageCount = 8)
        val freshMetadata = metadata.copy(pageCount = 0)
        val repository = FakeDocuments(
            metadata = staleMetadata,
            readerDocument = document,
            pageWindows = emptyList(),
            isImportComplete = true,
            metadataAfterReaderDocument = freshMetadata,
            isImportCompleteAfterReaderDocument = false,
        )
        val useCase = OpenReaderDocumentUseCase(
            documentRepository = repository,
            readerRepository = FakeReader(null),
            readerSettingsRepository = FakeSettings(style),
        )

        val open = useCase(
            documentId = documentId,
            hasReportedPaneSize = true,
            viewportSize = viewport,
            pageBreaker = null,
            pageBreakerStyle = null,
        )

        assertEquals(freshMetadata, open.metadata)
        assertFalse(open.isImportComplete)
    }

    @Test
    fun openReadsIndependentSourcesConcurrently() = runTest {
        val tracker = ConcurrencyTracker()
        val repository = FakeDocuments(
            metadata = metadata,
            readerDocument = document,
            pageWindows = listOf(pageWindow(0, 0, 11)),
            tracker = tracker,
        )
        val useCase = OpenReaderDocumentUseCase(
            documentRepository = repository,
            readerRepository = FakeReader(null, tracker),
            readerSettingsRepository = FakeSettings(style, tracker),
        )

        useCase(
            documentId = documentId,
            hasReportedPaneSize = true,
            viewportSize = viewport,
            pageBreaker = null,
            pageBreakerStyle = null,
        )

        assertTrue(tracker.maxConcurrent >= 3)
    }

    private class FakeDocuments(
        private val metadata: DocumentMetadata?,
        private val readerDocument: ReaderDocument?,
        private val pageWindows: List<PageWindow>,
        private val rememberedViewport: ViewportSize? = null,
        private val isImportComplete: Boolean = true,
        private val isPaginationComplete: Boolean = true,
        private val tracker: ConcurrencyTracker? = null,
        private val metadataAfterReaderDocument: DocumentMetadata? = metadata,
        private val isImportCompleteAfterReaderDocument: Boolean = isImportComplete,
    ) : DocumentRepository {
        var lastViewportPassed: ViewportSize? = null
        var lastPageBreakerPassed: ReaderPageBreaker? = null
        private var didReadReaderDocument = false

        override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = flowOf(listOfNotNull(currentMetadata()))
        override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? = tracker?.wrap { currentMetadata() } ?: currentMetadata()
        override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = tracker?.wrap {
            didReadReaderDocument = true
            readerDocument
        } ?: readerDocument.also { didReadReaderDocument = true }
        override suspend fun getPageWindows(documentId: DocumentId, style: ReaderStyle, viewportSize: ViewportSize?, pageBreaker: ReaderPageBreaker?, anchorOffset: Long?): List<PageWindow> {
            lastViewportPassed = viewportSize
            lastPageBreakerPassed = pageBreaker
            return pageWindows
        }
        override suspend fun resolveViewportSizeForStyle(documentId: DocumentId, style: ReaderStyle): ViewportSize? = rememberedViewport
        override suspend fun isImportComplete(documentId: DocumentId): Boolean = tracker?.wrap { currentImportComplete() } ?: currentImportComplete()
        override suspend fun isPaginationComplete(documentId: DocumentId): Boolean = isPaginationComplete
        override suspend fun importDocument(source: DocumentImportSource, importedAtEpochMillis: Long): ReaderDocument = error("unused")
        override suspend fun upsertDocument(document: DocumentMetadata) = Unit
        override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit
        override suspend fun deleteDocument(documentId: DocumentId) = Unit

        private fun currentMetadata(): DocumentMetadata? = if (didReadReaderDocument) metadataAfterReaderDocument else metadata
        private fun currentImportComplete(): Boolean = if (didReadReaderDocument) isImportCompleteAfterReaderDocument else isImportComplete
    }

    private class FakeReader(
        private val progress: ReadingProgress?,
        private val tracker: ConcurrencyTracker? = null,
    ) : ReaderRepository {
        override fun observeProgress(documentId: DocumentId) = flowOf(progress)
        override suspend fun getProgress(documentId: DocumentId): ReadingProgress? = tracker?.wrap { progress } ?: progress
        override suspend fun saveProgress(progress: ReadingProgress) = Unit
        override suspend fun deleteProgress(documentId: DocumentId) = Unit
    }

    private class FakeSettings(
        style: ReaderStyle,
        private val tracker: ConcurrencyTracker? = null,
    ) : ReaderSettingsRepository {
        override val settings = if (tracker == null) {
            flowOf(ReaderSettings(style = style))
        } else {
            flow {
                tracker.enter()
                try {
                    delay(1)
                    emit(ReaderSettings(style = style))
                } finally {
                    tracker.exit()
                }
            }
        }
        override suspend fun updateStyle(style: ReaderStyle) = Unit
        override suspend fun updatePageTurnMode(pageTurnMode: com.tedd.teddreader.core.common.model.PageTurnMode) = Unit
        override suspend fun updatePageAnimation(pageAnimation: com.tedd.teddreader.core.common.model.PageAnimation) = Unit
        override suspend fun updateAutoScrollConfig(autoScrollConfig: com.tedd.teddreader.core.common.model.AutoScrollConfig) = Unit
        override suspend fun updateAppLanguage(appLanguage: com.tedd.teddreader.core.common.model.AppLanguage) = Unit
    }

    private fun pageWindow(page: Int, start: Long, end: Long) = PageWindow(
        pageIndex = PageIndex(current = page, total = page + 1),
        location = ReaderLocation.TextOffset(start),
        text = "page-$page",
        textRange = TextRange(start, end),
    )

    private class ConcurrencyTracker {
        private var current = 0
        var maxConcurrent = 0
            private set

        suspend fun <T> wrap(block: () -> T): T {
            enter()
            return try {
                delay(1)
                block()
            } finally {
                exit()
            }
        }

        fun enter() {
            current += 1
            if (current > maxConcurrent) maxConcurrent = current
        }

        fun exit() {
            current -= 1
        }
    }
}

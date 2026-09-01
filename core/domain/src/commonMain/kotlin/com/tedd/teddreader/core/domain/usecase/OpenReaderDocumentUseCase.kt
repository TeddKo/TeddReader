package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.PaginatedDocument
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.absoluteOffsetOf
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import com.tedd.teddreader.core.common.model.layoutKey
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReaderSettings
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/** 리더가 처음 열릴 때 게시하는 데 필요한 모든 값으로, domain/common 타입만 사용한다. */
data class OpenReaderDocument(
    val metadata: DocumentMetadata?,
    val readerDocument: ReaderDocument?,
    val progress: ReadingProgress?,
    val settings: ReaderSettings,
    val documentFormat: DocumentFormat,
    val documentUri: String?,
    val paginated: PaginatedDocument,
    val rememberedViewportSize: ViewportSize?,
    val anchorOffset: Long?,
    val isImportComplete: Boolean,
    val isPaginationMeasured: Boolean,
    val totalPages: Int,
    val currentPage: Int,
) {
    val pageWindows: List<PageWindow> get() = paginated.pageWindows
    val isPdfMode: Boolean get() = documentFormat == DocumentFormat.PDF
    val isVisualMode: Boolean get() = documentFormat.isVisualPageFormat()
}

class OpenReaderDocumentUseCase(
    private val documentRepository: DocumentRepository,
    private val readerRepository: ReaderRepository,
    private val readerSettingsRepository: ReaderSettingsRepository,
) {
    suspend operator fun invoke(
        documentId: DocumentId,
        hasReportedPaneSize: Boolean,
        viewportSize: ViewportSize,
        viewportDensity: Float,
        pageBreaker: ReaderPageBreaker?,
        pageBreakerStyle: ReaderStyle?,
    ): OpenReaderDocument {
        data class OpenInputs(
            val metadata: DocumentMetadata?,
            val readerDocument: ReaderDocument?,
            val progress: ReadingProgress?,
            val settings: ReaderSettings,
            val isImportComplete: Boolean,
        )

        val inputs = coroutineScope {
            val readerDocumentDeferred = async { documentRepository.getReaderDocument(documentId) }
            val progressDeferred = async { readerRepository.getProgress(documentId) }
            val settingsDeferred = async { readerSettingsRepository.settings.first() }
            val readerDocument = readerDocumentDeferred.await()
            val metadataDeferred = async { documentRepository.getDocument(documentId) }
            val importCompleteDeferred = async { documentRepository.isImportComplete(documentId) }
            OpenInputs(
                metadata = metadataDeferred.await(),
                readerDocument = readerDocument,
                progress = progressDeferred.await(),
                settings = settingsDeferred.await(),
                isImportComplete = importCompleteDeferred.await(),
            )
        }
        val metadata = inputs.metadata
        val readerDocument = inputs.readerDocument
        val progress = inputs.progress
        val settings = inputs.settings
        val isImportComplete = inputs.isImportComplete
        val documentFormat = metadata?.format ?: DocumentFormat.UNKNOWN
        val isVisualMode = documentFormat.isVisualPageFormat()
        val anchorOffset = if (isVisualMode) {
            null
        } else {
            progress?.location?.let { absoluteOffsetOf(it, readerDocument?.sections.orEmpty()) }
        }
        val matchingPageBreaker = pageBreaker?.takeIf { pageBreakerStyle?.layoutKey() == settings.style.layoutKey() }
        val pageWindows = if (isVisualMode) {
            emptyList()
        } else {
            documentRepository.getPageWindows(
                documentId = documentId,
                style = settings.style,
                viewportSize = if (hasReportedPaneSize) viewportSize else null,
                viewportDensity = viewportDensity,
                pageBreaker = matchingPageBreaker,
                anchorOffset = anchorOffset,
            )
        }
        val rememberedViewportSize = if (!isVisualMode && !hasReportedPaneSize) {
            documentRepository.resolveViewportSizeForStyle(documentId, settings.style)
        } else {
            null
        }
        val paginated = PaginatedDocument(pageWindows, readerDocument?.sections.orEmpty())
        val isPaginationMeasured = isVisualMode || documentRepository.isPaginationComplete(documentId)
        val totalPages = when {
            pageWindows.isNotEmpty() -> pageWindows.size
            metadata?.pageCount != null -> metadata.pageCount ?: 0
            progress?.pageIndex?.total != null -> progress.pageIndex.total
            else -> 0
        }
        val currentPage = (anchorOffset?.let { paginated.pageOf(it) } ?: progress?.pageIndex?.current)
            ?.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
            ?: 0
        return OpenReaderDocument(
            metadata = metadata,
            readerDocument = readerDocument,
            progress = progress,
            settings = settings,
            documentFormat = documentFormat,
            documentUri = metadata?.location?.sourceUri,
            paginated = paginated,
            rememberedViewportSize = rememberedViewportSize,
            anchorOffset = anchorOffset,
            isImportComplete = isImportComplete,
            isPaginationMeasured = isPaginationMeasured,
            totalPages = totalPages,
            currentPage = currentPage,
        )
    }
}

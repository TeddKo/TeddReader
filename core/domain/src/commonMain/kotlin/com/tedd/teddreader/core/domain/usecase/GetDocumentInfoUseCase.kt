package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.ReadingStats
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReadingStatsRepository

/**
 * Everything the document-info screen shows about one document, gathered in one answer.
 *
 * @property metadata the library's own record of the document, or null when nothing is stored under that id.
 * @property pageIndex the page the reader last saw, or null for a book that has never been opened. Only ever
 * displayed — a resume uses the stored location instead (see ReadingProgress).
 * @property stats reading totals; never null, and its active time is zero for a document with no recorded
 * sessions, which today is every document.
 */
data class DocumentInfo(
    val metadata: DocumentMetadata?,
    val pageIndex: PageIndex?,
    val stats: ReadingStats,
)

/**
 * Answers "what is this document" by composing the three sources that each hold one third of the answer.
 *
 * This exists because its caller would otherwise inject three repositories to make three reads that only mean
 * something together — and it used two of those three at exactly one call site each. Collapsing them here
 * leaves the screen with one collaborator for the composed read, which is a genuine narrowing rather than a
 * layer added for its own sake: a use case whose signature merely repeated a repository method would be the
 * pass-through this project already deleted six of.
 *
 * The reads run in sequence and exceptions propagate, so a failure in any one of them means the caller applies
 * none of them — the screen shows an error rather than a half-filled panel.
 *
 * A class rather than a top-level function because it has to be injectable: as a function taking three
 * repositories, all three would still sit in the caller's constructor and nothing would be narrowed at all.
 *
 * @property documentRepository where the library record comes from.
 * @property readerRepository where the last displayed page comes from.
 * @property readingStatsRepository where the reading totals come from.
 */
class GetDocumentInfoUseCase(
    private val documentRepository: DocumentRepository,
    private val readerRepository: ReaderRepository,
    private val readingStatsRepository: ReadingStatsRepository,
) {
    /**
     * @param documentId the document to describe.
     * @return its metadata, last displayed page and reading totals.
     * @throws Throwable whatever any of the three reads throws; nothing is applied partially.
     */
    suspend operator fun invoke(documentId: DocumentId): DocumentInfo = DocumentInfo(
        metadata = documentRepository.getDocument(documentId),
        pageIndex = readerRepository.getProgress(documentId)?.pageIndex,
        stats = readingStatsRepository.getStats(documentId),
    )
}

package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ViewportSize
import kotlinx.coroutines.flow.Flow

class DocumentImportSource(
    val location: DocumentLocation,
    val bytes: ByteArray?,
) {
    init {
        require(bytes == null || bytes.isNotEmpty()) { "Document bytes must not be empty." }
    }
}

/**
 * How far [DocumentRepository.importNextSections] got: [isComplete] mirrors
 * `documents.importCompletedAtEpochMillis` becoming non-null, [sectionsImported] is how many new
 * sections this one call actually parsed and stored — 0 with isComplete=true either means the import
 * had already finished, or the document never split its import into phases at all.
 */
data class ImportProgress(
    val isComplete: Boolean,
    val sectionsImported: Int,
)

interface DocumentRepository {
    fun observeRecentDocuments(): Flow<List<DocumentMetadata>>
    suspend fun getDocument(documentId: DocumentId): DocumentMetadata?
    suspend fun getDocumentCover(documentId: DocumentId): ByteArray? = null
    suspend fun getVisualPageImages(
        documentId: DocumentId,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> = emptyMap()
    suspend fun getEmbeddedImages(
        documentId: DocumentId,
        hrefs: Set<String>,
    ): Map<String, ByteArray> = emptyMap()
    suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument?
    // Null means the caller has no real pane measurement yet — getPageWindows resolves the newest
    // layout ever stored for this exact style itself in that case (see DocumentRepositoryImpl), rather
    // than pagination running against a guessed viewport that almost never matches a stored one.
    suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: ReaderPageBreaker? = null,
        // The absolute offset the reader is resting on right now. Consulted only when no page layout
        // at all is stored yet for this style: pagination then measures just the section holding this
        // offset before returning anything, instead of the whole book (see DocumentRepositoryImpl and
        // continuePagination, which extends the rest afterwards). Null anchors to the first content
        // section, the same place a freshly imported book with nowhere to resume to starts from.
        anchorOffset: Long? = null,
    ): List<PageWindow>

    /**
     * The viewport the newest page layout stored for [documentId] at [style]'s layout-affecting
     * fields (font size, line height, font family) was measured at — the same resolution
     * [getPageWindows] performs internally when given a null viewportSize, exposed here so a caller
     * that asked for that resolution can learn what it was and adopt it. Null when nothing is stored.
     */
    suspend fun resolveViewportSizeForStyle(documentId: DocumentId, style: ReaderStyle): ViewportSize? = null

    /**
     * Ensures [sectionIndexes] have their blocks decoded for whichever document [getPageWindows]/
     * [getReaderDocument] most recently loaded, fetching only whichever of them are not decoded yet.
     * A no-op if [documentId] is not that document, or if it was not loaded from storage at all (see
     * DocumentRepositoryImpl.SectionBlocksCache) — there is nothing left to fetch either way. Default
     * no-op keeps every other DocumentRepository implementation (tests included) unaffected.
     */
    suspend fun warmSectionBlocks(documentId: DocumentId, sectionIndexes: Set<Int>): Unit = Unit
    suspend fun importDocument(source: DocumentImportSource, importedAtEpochMillis: Long): ReaderDocument
    suspend fun upsertDocument(document: DocumentMetadata)
    suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long)
    suspend fun deleteDocument(documentId: DocumentId)

    /**
     * Advances a progressively-imported document (currently: EPUB only, see DocumentRepositoryImpl)
     * by parsing and persisting up to [count] more of its remaining spine items, in spine order —
     * appending only ever adds to what is already stored, never moving an offset already published.
     * When [pageBreaker] is real and a layout for [style]/[viewportSize] is already stored, the new
     * sections are also measured and their page starts appended to it, so a page already shown keeps
     * its exact boundaries. A no-op returning isComplete=true for a document whose import has already
     * finished, or — the default here — for any repository/format that never splits import into
     * phases at all.
     */
    suspend fun importNextSections(
        documentId: DocumentId,
        count: Int,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
    ): ImportProgress = ImportProgress(isComplete = true, sectionsImported = 0)

    /** Whether [documentId]'s import has finished — true for a document that isn't mid-import at all,
     * and for any repository/format that never splits import into phases. */
    suspend fun isImportComplete(documentId: DocumentId): Boolean = true

    /**
     * Continues a progressive pagination [getPageWindows] started but could not finish measuring in
     * one call — see that function's [anchorOffset] doc. Measures one more content section for real,
     * extending outward from the section the reader resumed into: backward first, until section 0 (so
     * the resumed page's own page number stops moving), then forward until the last section (so the
     * total does). A no-op returning isComplete=true with nothing to measure — no real [pageBreaker],
     * [documentId] is not the document the last [getPageWindows] call measured this way, or that
     * measurement already finished.
     */
    suspend fun continuePagination(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
    ): PaginationProgress = PaginationProgress(isComplete = true, sectionsMeasured = 0)

    /** Whether every content section of [documentId] has a real page-layout measurement for whichever
     * (style, viewport) [getPageWindows] most recently measured progressively (see [continuePagination]).
     * True once that measurement is done or was never needed — restored from storage, measured whole in
     * one call, or for any repository that never splits pagination into phases at all. */
    suspend fun isPaginationComplete(documentId: DocumentId): Boolean = true
}

/**
 * How far [DocumentRepository.continuePagination] got: [isComplete] mirrors
 * [DocumentRepository.isPaginationComplete] becoming true, [sectionsMeasured] is how many sections
 * this one call actually measured — 0 with isComplete=true either means nothing was left to measure,
 * or there was no real [ReaderPageBreaker] to measure with yet.
 */
data class PaginationProgress(
    val isComplete: Boolean,
    val sectionsMeasured: Int,
)

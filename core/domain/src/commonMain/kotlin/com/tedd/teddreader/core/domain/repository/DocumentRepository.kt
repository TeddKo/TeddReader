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
import kotlinx.coroutines.flow.first

/**
 * A document the app has been handed and is about to import: where it came from, and its bytes when they
 * are already in memory.
 *
 * Not a data class, because [bytes] is a whole book in memory and value equality over it would compare
 * megabytes.
 *
 * @property location where the document came from, and the only thing format detection needs — it
 * resolves from the name and MIME type, never from content.
 * @property bytes the document's content when the caller already holds it, or null to mean "read it from
 * [location] yourself". Null is the normal case for EPUB and CBZ: both are zips a parser opens and seeks
 * around in, so reading one fully into memory here just to write it back out to a temp file is wasted
 * work on the largest books.
 * @throws IllegalArgumentException if [bytes] is present but empty, which would be an unreadable
 * document masquerading as a readable one.
 */
class DocumentImportSource(
    val location: DocumentLocation,
    val bytes: ByteArray?,
) {
    init {
        require(bytes == null || bytes.isNotEmpty()) { "Document bytes must not be empty." }
    }
}

/**
 * How far [DocumentRepository.importNextSections] got, so its caller knows whether to step again.
 *
 * @property isComplete whether the document's import has finished — the same fact as
 * `documents.importCompletedAtEpochMillis` becoming non-null.
 * @property sectionsImported how many new sections this one call actually parsed and stored. Zero with
 * [isComplete] true means either that the import had already finished, or that this document's format
 * never splits its import into phases.
 */
data class ImportProgress(
    val isComplete: Boolean,
    val sectionsImported: Int,
)

/**
 * Everything the app knows about documents: the library's list, one book's parsed text, and the page
 * layout a reader draws from.
 *
 * The read paths are deliberately separate because they cost wildly different amounts.
 * [observeRecentDocuments] and [getDocument] touch only metadata, so listing a shelf of books never
 * loads one. [getReaderDocument] loads a book's text. [getPageWindows] is the expensive one — it
 * measures — and everything about its shape exists to avoid measuring twice.
 *
 * Two long-running jobs are exposed as "advance by one step" calls rather than as background work this
 * interface owns: [importNextSections] for a book still being parsed, and [continuePagination] for a
 * style still being measured. A screen drives them on its own scope, so leaving the reader stops them and
 * the next open resumes from whatever is stored — no separate subsystem, and no work outliving the screen
 * that wanted it. [isImportComplete] and [isPaginationComplete] are how a caller knows whether to keep
 * stepping.
 *
 * The defaults here answer for a repository that has none of this — a fake in a test, or a format whose
 * import and pagination happen in one call — which is why "nothing to do" and "not this document" both
 * come back as `isComplete = true` rather than as an error.
 */
interface DocumentRepository {
    /**
     * The library feed.
     *
     * @return a flow of every imported document, most recently opened first (falling back to when it was
     * added, so a freshly imported book appears at the top before it has ever been read), re-emitted on
     * every change.
     */
    fun observeRecentDocuments(): Flow<List<DocumentMetadata>>

    /**
     * Reads one document's metadata without touching its text.
     *
     * @param documentId the document to read.
     * @return its metadata, or null when nothing has been imported under that id.
     */
    suspend fun getDocument(documentId: DocumentId): DocumentMetadata?

    /**
     * Reads a document's cover art, kept out of [getDocument] so listing a library never loads images.
     *
     * @param documentId the document whose cover to read.
     * @return the encoded image bytes, or null when this document has no cover — which includes every
     * format that cannot carry one.
     */
    suspend fun getDocumentCover(documentId: DocumentId): ByteArray? = null

    /**
     * Reads page images for a format that has no reflowable text at all — PDF, CBZ, a single image.
     *
     * Asked for by page rather than by book because these images are orders of magnitude larger than
     * text: the reader requests the window it is about to draw.
     *
     * @param documentId the document to read from.
     * @param pageIndexes the pages wanted, zero-based.
     * @return the encoded image bytes per page index, omitting any page that has none.
     */
    suspend fun getVisualPageImages(
        documentId: DocumentId,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> = emptyMap()

    /**
     * Reads images referenced from inside reflowable text, by the href the document itself used.
     *
     * Same reason as [getVisualPageImages]: an illustrated book's images dwarf its text, so they are
     * fetched for the pages on screen instead of with the document.
     *
     * @param documentId the document to read from.
     * @param hrefs the image paths as they appear in the document's own blocks.
     * @return the encoded image bytes per href, omitting any that the container does not hold.
     */
    suspend fun getEmbeddedImages(
        documentId: DocumentId,
        hrefs: Set<String>,
    ): Map<String, ByteArray> = emptyMap()

    /**
     * Resolves embedded EPUB font files to reusable local file paths, keyed by the href the document
     * itself used.
     *
     * Unlike [getEmbeddedImages], this returns file paths instead of bytes because the renderer and page
     * breaker only need something the platform text stack can open later; keeping whole font byte arrays
     * in UI state would pin large binary blobs in memory for no gain.
     *
     * @param documentId the document to read from.
     * @param hrefs the font paths as they appear in the document's own block/span styling.
     * @return reusable local file paths per href, omitting any href the container does not hold.
     */
    suspend fun getEmbeddedFontFiles(
        documentId: DocumentId,
        hrefs: Set<String>,
    ): Map<String, String> = emptyMap()

    /**
     * Loads the parsed document a reader reads: its sections, their text, and the block structure that
     * styles them.
     *
     * @param documentId the document to load.
     * @return the document as parsed *so far* — for a progressively imported book this grows between
     * calls, which is why the reader re-reads it after every import batch — or null when nothing is
     * stored under that id.
     */
    suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument?

    /**
     * Lays out [documentId] into pages for [style] and [viewportSize] — the call a reader builds a screen
     * from, and the only one that ever measures text.
     *
     * A stored measurement always beats measuring again, so this resolves in order: a layout already on
     * disk for exactly this type and viewport, else a fresh measurement. That is also why a caller
     * without a real [pageBreaker] still gets the stored answer rather than an estimate.
     *
     * A fresh measurement of a whole book is expensive enough to be visible — 6.4s and 13.0s measured on a
     * real device for 204- and 528-section books — so a first pass measures only the section the reader is
     * resting on and returns just those pages, honestly labelled as the pages known so far.
     * [continuePagination] extends it outward from there, and nothing is ever built from an estimate
     * standing in for a section that was not reached.
     *
     * @param documentId the document to lay out.
     * @param style the reading style; only its layout-affecting fields change the outcome (see
     * `ReaderStyle.layoutKey`).
     * @param viewportSize the box a page is laid out into, or null when the caller has no pane measurement
     * yet — in which case this resolves the newest layout ever stored for this type at *any* viewport,
     * because that beats paginating against a guessed viewport that almost never matches a stored one.
     * @param pageBreaker the real text layout to measure with, or null for a caller that only wants
     * whatever is already stored.
     * @param anchorOffset the absolute offset the reader is resting on, which decides *which* section a
     * first pass measures. Null anchors to the first content section — where a freshly imported book with
     * nowhere to resume to starts.
     * @return the pages known for this document at this type and viewport, in reading order; empty for a
     * document that has no reflowable text or nothing stored at all.
     */
    suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: ReaderPageBreaker? = null,
        anchorOffset: Long? = null,
        viewportDensity: Float = 1f,
    ): List<PageWindow>

    /**
     * Reports the viewport the newest stored layout for [style] was measured at — the same resolution
     * [getPageWindows] performs internally for a null viewport, exposed so a caller that asked for it can
     * learn the answer and adopt it.
     *
     * @param documentId the document to look up.
     * @param style the style whose layout-affecting fields (font size, line height, family) to match.
     * @return that viewport, or null when no layout is stored for this type.
     */
    suspend fun resolveViewportSizeForStyle(documentId: DocumentId, style: ReaderStyle): ViewportSize? = null

    /**
     * Decodes the block structure of [sectionIndexes] for whichever document [getPageWindows] or
     * [getReaderDocument] most recently loaded, fetching only the sections not already decoded.
     *
     * A page built from an un-decoded section renders as plain text — no indents, no headings — so a
     * caller that is about to publish a page warms that page's sections first.
     *
     * @param documentId the document the sections belong to; a different document is a no-op.
     * @param sectionIndexes the sections to decode.
     * @return how many sections this call actually decoded — 0 when [documentId] is not the loaded
     * document, when it was not loaded from storage at all, or when every section asked for was already
     * decoded, so a caller can skip re-publishing when nothing changed.
     */
    suspend fun warmSectionBlocks(documentId: DocumentId, sectionIndexes: Set<Int>): Int = 0

    /**
     * Every embedded-font href any block or span of [documentId] references, across the whole document.
     *
     * This is what lets the reader resolve a book's fonts once, up front, instead of discovering them
     * window by window as pages are mounted: every discovery changed the font-dependent layout key and
     * re-measured the whole book, which is exactly the flicker-and-clip cascade a reader sees when a
     * page's type changes under it.
     *
     * @param documentId the document to scan; a document that is not the loaded one yields an empty set.
     * @return the distinct embedded-font hrefs the document references anywhere.
     */
    suspend fun getReferencedEmbeddedFontHrefs(documentId: DocumentId): Set<String> = emptySet()

    /**
     * Imports what [source] points at and returns the document a reader can open.
     *
     * Opening a book the library already holds must not re-import it: doing so threw away stored text and
     * page layouts and paid the whole import again, which on a 528-chapter book is the difference between
     * opening at once and waiting. An *unfinished* import is different — it is picked up where it stopped
     * by [importNextSections], not restarted.
     *
     * @param source where the document is and, optionally, its bytes.
     * @param importedAtEpochMillis when this import happened, which orders the library until the book is
     * first opened.
     * @return the parsed document, which for a progressively imported format is only its first phase.
     */
    suspend fun importDocument(source: DocumentImportSource, importedAtEpochMillis: Long): ReaderDocument

    /**
     * Writes back edited metadata — the library's rename, favourite and folder actions all land here.
     *
     * @param document the full metadata row to store; fields absent from it are overwritten, so a caller
     * edits a copy of what it read.
     */
    suspend fun upsertDocument(document: DocumentMetadata)

    /**
     * Rewrites the bookmarked flag for [documentIds] in one batch.
     *
     * The default walks the ids and rewrites only rows that actually need changing, which keeps old fakes
     * working. Real storage overrides this with one SQL update.
     *
     * @param documentIds the documents to rewrite.
     * @param isBookmarked the bookmarked state to apply.
     */
    suspend fun setDocumentsBookmarked(documentIds: Collection<DocumentId>, isBookmarked: Boolean) {
        documentIds.forEach { documentId ->
            val document = getDocument(documentId) ?: return@forEach
            if (document.isBookmarked != isBookmarked) {
                upsertDocument(document.copy(isBookmarked = isBookmarked))
            }
        }
    }

    /**
     * Rewrites the folder membership for [documentIds] in one batch.
     *
     * The folder pair is validated here so every caller gets the same invariant the stored model enforces:
     * either both values are present, or neither is. The default keeps older fakes working by reading each
     * document and writing back a copy; real storage overrides this with one SQL update.
     *
     * @param documentIds the documents to move.
     * @param folderId the folder to move them into, or null to unfile them.
     * @param folderName that folder's display name, present exactly when [folderId] is.
     */
    suspend fun setDocumentsFolder(
        documentIds: Collection<DocumentId>,
        folderId: String?,
        folderName: String?,
    ) {
        require((folderId == null) == (folderName == null)) {
            "folderId and folderName must both be null or both be non-null."
        }
        require(folderId == null || folderId.isNotBlank()) { "folderId must not be blank." }
        require(folderName == null || folderName.isNotBlank()) { "folderName must not be blank." }
        documentIds.forEach { documentId ->
            val document = getDocument(documentId) ?: return@forEach
            if (document.folderId != folderId || document.folderName != folderName) {
                upsertDocument(document.copy(folderId = folderId, folderName = folderName))
            }
        }
    }

    /**
     * Renames every document currently in [folderId].
     *
     * The default falls back to the live library flow so old in-memory fakes still behave correctly;
     * concrete storage can override this with one SQL update.
     *
     * @param folderId the folder to rename.
     * @param folderName the new display name.
     */
    suspend fun renameFolder(folderId: String, folderName: String) {
        require(folderId.isNotBlank()) { "folderId must not be blank." }
        require(folderName.isNotBlank()) { "folderName must not be blank." }
        val members = observeRecentDocuments().first().filter { it.folderId == folderId }.map(DocumentMetadata::id)
        setDocumentsFolder(members, folderId, folderName)
    }

    /**
     * Removes [folderId] by clearing that membership from every current member.
     *
     * The default uses the same observe-once fallback as [renameFolder] so test doubles that only expose
     * the flow still work.
     *
     * @param folderId the folder to clear.
     */
    suspend fun clearFolder(folderId: String) {
        require(folderId.isNotBlank()) { "folderId must not be blank." }
        val members = observeRecentDocuments().first().filter { it.folderId == folderId }.map(DocumentMetadata::id)
        setDocumentsFolder(members, null, null)
    }

    /**
     * Records that a document was opened, which is what reorders the library.
     *
     * @param documentId the document that was opened.
     * @param openedAtEpochMillis when it was opened.
     */
    suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long)

    /**
     * Removes a document and everything derived from it — text, search index, saved places, progress and
     * measured page layouts all go with it.
     *
     * @param documentId the document to remove.
     */
    suspend fun deleteDocument(documentId: DocumentId)

    /**
     * Removes [documentIds], using [deleteDocument] one by one unless a concrete repository can do better.
     *
     * @param documentIds the documents to remove.
     */
    suspend fun deleteDocuments(documentIds: Collection<DocumentId>) {
        for (documentId in documentIds) deleteDocument(documentId)
    }

    /**
     * Advances a progressively imported document (currently EPUB only) by parsing and persisting up to
     * [count] more of its remaining spine items, in spine order.
     *
     * Appending only ever adds to what is stored and never moves an offset already published, so a page
     * the reader is looking at keeps its text. When [pageBreaker] is real and a layout for this type and
     * viewport is already stored, the new sections are measured too and their page starts appended, so a
     * page already shown keeps its exact boundaries.
     *
     * @param documentId the document to advance.
     * @param count the maximum number of sections to parse in this step.
     * @param style the current reading style, used only if the new sections are also measured.
     * @param viewportSize the current viewport, used only if the new sections are also measured.
     * @param pageBreaker the real text layout, or null to parse without measuring.
     * @return how far the import got — see [ImportProgress]. A no-op with `isComplete = true` for a
     * document whose import has already finished, or for any format that never splits import into phases.
     */
    suspend fun importNextSections(
        documentId: DocumentId,
        count: Int,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float = 1f,
    ): ImportProgress = ImportProgress(isComplete = true, sectionsImported = 0)

    /**
     * Whether a document is fully parsed.
     *
     * @param documentId the document to ask about.
     * @return true when its import has finished — including for a document that was never split into
     * phases at all — and false only while sections it will have are still unparsed.
     */
    suspend fun isImportComplete(documentId: DocumentId): Boolean = true

    /**
     * Continues the progressive pagination [getPageWindows] started but could not finish in one call,
     * measuring another bounded batch for real.
     *
     * The walk extends outward from the section the reader resumed into: backward first, to section 0, so
     * the resumed page's own number stops moving, then forward to the last section, so the total does.
     *
     * @param documentId the document being measured.
     * @param style the style the in-flight measurement belongs to; a different one has nothing to
     * continue.
     * @param viewportSize the viewport that measurement belongs to.
     * @param pageBreaker the real text layout to measure with; null makes this a no-op.
     * @return how far the measurement got — see [PaginationProgress]. A no-op with `isComplete = true`
     * when there is nothing to measure: no real breaker, a different document, or a walk already finished.
     */
    suspend fun continuePagination(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float = 1f,
    ): PaginationProgress = PaginationProgress(isComplete = true, sectionsMeasured = 0)

    /**
     * Whether every content section has a real measurement for whichever type and viewport
     * [getPageWindows] most recently measured progressively.
     *
     * @param documentId the document to ask about.
     * @return true once that measurement is done or was never needed — restored from storage, measured
     * whole in one call, or a repository that never splits pagination into phases. **Never true while
     * [isImportComplete] is false**: sections the book will have are still being parsed, so a measurement
     * of what is parsed so far is not a measurement of the book, and a caller asking this in order to
     * decide whether to keep [continuePagination] running must keep it running while the import moves.
     */
    suspend fun isPaginationComplete(documentId: DocumentId): Boolean = true
}

/**
 * How far [DocumentRepository.continuePagination] got, so its caller knows whether to step again.
 *
 * @property isComplete whether the measurement has finished — the same fact as
 * [DocumentRepository.isPaginationComplete] becoming true.
 * @property sectionsMeasured how many sections this one call actually measured. Zero with [isComplete]
 * true means either that nothing was left to measure, or that there was no real [ReaderPageBreaker] to
 * measure with.
 */
data class PaginationProgress(
    val isComplete: Boolean,
    val sectionsMeasured: Int,
)

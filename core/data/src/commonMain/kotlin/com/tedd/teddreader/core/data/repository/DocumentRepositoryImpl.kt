package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.suspendRunCatching
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderObjectReplacementChar
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderNavigation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderLayoutKey
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.layoutKey
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import com.tedd.teddreader.core.common.model.rebasedBy
import com.tedd.teddreader.core.common.model.wordCount
import com.tedd.teddreader.core.data.mapper.CurrentReaderParserVersion
import com.tedd.teddreader.core.data.mapper.toDocumentEntity
import com.tedd.teddreader.core.data.mapper.toDocumentMetadata
import com.tedd.teddreader.core.data.mapper.toSearchIndexEntity
import com.tedd.teddreader.core.data.pagination.RestoredPageWindows
import com.tedd.teddreader.core.data.pagination.SectionPageStarts
import com.tedd.teddreader.core.data.pagination.ReaderPageMeasureDispatcher
import com.tedd.teddreader.core.data.pagination.TextPageLayoutEngine
import com.tedd.teddreader.core.data.parser.ComicBookDocumentParser
import com.tedd.teddreader.core.data.parser.ComicArchive
import com.tedd.teddreader.core.data.parser.DocumentFormatDetector
import com.tedd.teddreader.core.data.parser.EpubDocumentParser
import com.tedd.teddreader.core.data.parser.EpubImportContainer
import com.tedd.teddreader.core.data.parser.EpubParsedSection
import com.tedd.teddreader.core.data.parser.ImageDocumentParser
import com.tedd.teddreader.core.data.parser.PdfDocumentParser
import com.tedd.teddreader.core.data.parser.SectionSeparatorLength
import com.tedd.teddreader.core.data.parser.TxtDocumentParser
import com.tedd.teddreader.core.data.parser.TxtTextDecoder
import com.tedd.teddreader.core.data.parser.buildEpubCoverSection
import com.tedd.teddreader.core.data.parser.fillIntrinsicImageSizes
import com.tedd.teddreader.core.data.parser.openEpubImportContainer
import com.tedd.teddreader.core.data.parser.parseEpubSpineItem
import com.tedd.teddreader.core.data.parser.resolveEpubNavigationAtCompletion
import com.tedd.teddreader.core.data.parser.systemFileSystem
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ImportProgress
import com.tedd.teddreader.core.domain.repository.PaginationProgress
import com.tedd.teddreader.core.room.dao.DocumentDao
import com.tedd.teddreader.core.room.dao.PageLayoutDao
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import com.tedd.teddreader.core.room.dao.SectionSourcePathEntry
import com.tedd.teddreader.core.room.dao.SectionTitleUpdate
import com.tedd.teddreader.core.room.entity.DocumentEntity
import com.tedd.teddreader.core.room.entity.PageLayoutEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.TimeSource
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip
import okio.buffer
import org.koin.core.annotation.Single

/**
 * The sole [DocumentRepository] implementation: turns an imported file into a [ReaderDocument], persists
 * it across [documentDao] (shelf metadata), [searchIndexDao] (per-section text/blocks/search index) and
 * [pageLayoutDao] (measured page-start layouts), and serves it back out through the small set of
 * in-memory caches documented alongside their own fields below. Format detection and per-format parsing
 * are delegated to [formatDetector] and the `*DocumentParser`s; text is laid out into pages by
 * [textPageLayoutEngine]. EPUB import is progressive (see [importEpubPhase0] and [importNextSections]),
 * so this class also tracks an in-flight pagination session and a scratch copy of the EPUB currently
 * being read from disk.
 *
 * @property documentDao Shelf-level metadata: title, format, timestamps, favourite/folder state, and the
 *   `importCompletedAtEpochMillis` stamp a progressive EPUB import leaves unset until it actually
 *   finishes (see [isImportComplete]).
 * @property searchIndexDao Per-section storage: text, character range, decoded-block JSON, and the
 *   document-level navigation/title carried on section 0 (see [getStoredSections]).
 * @property pageLayoutDao Measured page-start layouts keyed by style and viewport (see
 *   [restorePageWindows]/[storePageWindows]).
 * @property formatDetector Resolves a [DocumentFormat] from a [DocumentImportSource]'s location/bytes.
 * @property txtDocumentParser Parses plain-text imports and TXT repairs.
 * @property epubDocumentParser Parses EPUB imports and repairs, including the non-progressive
 *   fallback-chapters path used when a book has no OPF.
 * @property pdfDocumentParser Parses PDF imports and extracts PDF covers.
 * @property comicBookDocumentParser Parses CBZ imports, covers, and per-page images.
 * @property imageDocumentParser Parses single-image imports.
 * @property textPageLayoutEngine Lays sections out into [PageWindow]s and reconstructs a stored layout
 *   back into windows without re-measuring.
 * @property documentFileSource Reads/copies the original file bytes for a [DocumentMetadata.location].
 *   Null in a context with no file access (some tests); every path that needs it degrades to returning
 *   nothing rather than throwing when it is null, except progressive EPUB import, which requires it.
 */
@Single([DocumentRepository::class])
class DocumentRepositoryImpl(
    private val documentDao: DocumentDao,
    private val searchIndexDao: SearchIndexDao,
    private val pageLayoutDao: PageLayoutDao,
    private val formatDetector: DocumentFormatDetector,
    private val txtDocumentParser: TxtDocumentParser,
    private val epubDocumentParser: EpubDocumentParser,
    private val pdfDocumentParser: PdfDocumentParser,
    private val comicBookDocumentParser: ComicBookDocumentParser,
    private val imageDocumentParser: ImageDocumentParser,
    private val textPageLayoutEngine: TextPageLayoutEngine,
    private val documentFileSource: DocumentFileSource? = null,
) : DocumentRepository {
    /** JSON codec used to encode/decode the block lists and navigation stored as text columns. */
    private val json = Json

    /**
     * Structured logger, tagged `"Pagination"`, for this class's cache-hit/measurement/import
     * diagnostics.
     */
    private val logger = Logger.withTag("Pagination")

    /**
     * Owns everything about a document's cover picture — its cache file, and extracting it from an EPUB
     * or PDF that has none cached yet. See [DocumentCoverStore] for why this is a collaborator rather
     * than four methods scattered through this class.
     *
     * CBZ covers are the exception and stay here, in [getDocumentCover]: a comic's cover comes out of
     * [cbzArchive], the same mutex-guarded slot page requests read, so it cannot move without moving
     * that lock with it.
     */
    private val coverStore = DocumentCoverStore(
        epubDocumentParser = epubDocumentParser,
        pdfDocumentParser = pdfDocumentParser,
        documentFileSource = documentFileSource,
    )

    /**
     * Guards [cachedDocumentId], [cachedReaderDocument], [cachedSectionBlocks], the page-window
     * cache fields below them ([cachedPageWindowKey], [cachedPageWindows], [cachedPageWindowsAreMeasured],
     * [paginationSession]), and [documentCacheGeneration] — every read or write of any of those fields
     * happens inside a `documentCacheLock.withLock` block.
     */
    private val documentCacheLock = Mutex()

    /**
     * The id of the one document currently held by [cachedReaderDocument]/[cachedSectionBlocks], or null
     * when nothing is cached. Reading a document back means loading every section's text out of the
     * database and decoding a block list per section. Opening a book asked for exactly that twice — once
     * directly and once more from inside [getPageWindows] — and every repagination asked again. One book
     * is cached, which is all the reader ever has open, and it is dropped (see [invalidateDocumentCache])
     * the moment that book is rewritten or deleted.
     */
    private var cachedDocumentId: DocumentId? = null

    /** The [ReaderDocument] cached for [cachedDocumentId] — see that property's doc for why one is kept. */
    private var cachedReaderDocument: ReaderDocument? = null

    /**
     * The same book's per-section block decoder, kept alongside [cachedReaderDocument] so a restored page
     * layout can ask for one section's blocks instead of forcing [cachedReaderDocument]'s block list to
     * decode the whole book. Null when the cached document came from a repair pass instead of storage —
     * that document already holds every block in memory, so there is nothing to look up on demand.
     */
    private var cachedSectionBlocks: SectionBlocksCache? = null

    /**
     * The style/viewport [cachedPageWindows] answers for. Laying the book out is the most expensive
     * thing the reader does, and the same question gets asked repeatedly: the pane reports its size
     * again after a rotation and back, a settings sheet opens and closes without touching the type, the
     * reader returns to the book it just left. One answer is kept, because one book is laid out at one
     * size at a time.
     */
    private var cachedPageWindowKey: PageWindowKey? = null

    /** The page windows cached for [cachedPageWindowKey] — see that property's doc. */
    private var cachedPageWindows: List<PageWindow> = emptyList()

    /**
     * Whether [cachedPageWindows] came from an actual measurement (a restore, or a session that measured
     * with a real [ReaderPageBreaker]) rather than the estimate a caller with no breaker gets. Gates
     * whether a caller that specifically wants a measured answer may reuse the cache — see
     * [getPageWindows].
     */
    private var cachedPageWindowsAreMeasured: Boolean = false

    /**
     * Progressive pagination in flight for [cachedPageWindowKey] — see [PaginationSession]'s own doc.
     * Null once nothing is mid-measurement: either every content section is already covered by
     * [cachedPageWindows] (and, once a real breaker measured it, already written to `page_layouts`), or
     * [getPageWindows] has not had to measure this document at all yet.
     */
    private var paginationSession: PaginationSession? = null

    /**
     * Bumped by every [invalidateDocumentCache] call, regardless of whether the document it named was
     * actually the one cached at the time — the signal [getReaderDocument] uses to decide whether the
     * [loadReaderDocument] result it just finished computing outside the lock is still safe to publish.
     * A load that started before some invalidation, but that only reaches its own publishing step after
     * that invalidation already ran, would otherwise write a pre-invalidation snapshot back into the
     * cache right after the invalidation cleared it, silently undoing it. [getReaderDocument] instead
     * captures this value in its first locked section, then only writes to the cache in its second
     * locked section when the value is still the same one; a mismatch means some writer invalidated the
     * cache while the load was in flight, so the freshly loaded document is handed back to the caller
     * without being cached, and the next call reloads instead of trusting a snapshot that arrived too
     * late.
     */
    private var documentCacheGeneration: Long = 0L

    /**
     * Serialises [continuePagination] against itself — see that function's own doc for the duplicate
     * measurement and double-append it prevents. Deliberately not [documentCacheLock]: this one is held
     * across a whole section's measurement, and that lock guards the page list the reader reads on its
     * way to a frame.
     */
    private val paginationContinuationLock = Mutex()

    /**
     * Guards [epubScratchDocumentId], [epubScratchPath], [epubScratchContainer], and
     * [epubEmbeddedFontFilesByHref]. Every I/O operation on the scratch copy file — reading
     * embedded images or fonts, opening the ZIP for the progressive import container — must hold
     * this lock for the duration of the read, or at minimum re-verify [epubScratchDocumentId]
     * inside a `withLock` block before touching the path. [invalidateCaches] acquires this lock
     * to delete the scratch file, so holding it prevents the file from vanishing mid-read.
     *
     * This is a non-reentrant [Mutex]: callers that already invoke [epubScratchCopy] (which
     * acquires the lock internally) must not wrap that call in their own `withLock` — instead
     * they call [epubScratchCopy] first, then re-acquire the lock to use the path.
     */
    private val epubScratchLock = Mutex()
    /** Guards [epubNextSpineCursorByDocumentId] below. */
    private val epubImportCursorLock = Mutex()

    /**
     * Serialises creation, use, replacement, and deletion of the one CBZ scratch copy and its open
     * archive ([cbzScratchDocumentId]/[cbzScratchPath]/[cbzArchive]). Every read of the archive, every
     * swap to a different document's archive, and every invalidation happens inside a
     * `cbzScratchLock.withLock` block, so a page-window request can never be reading a scratch file that
     * another request is deleting out from under it.
     */
    private val cbzScratchLock = Mutex()

    /**
     * The id of the document [cbzScratchPath] is a scratch copy of, or null when no copy is held. A CBZ
     * page-window request used to copy the whole archive to a fresh temporary file, open it as a ZIP,
     * and list plus natural-sort its entries every single time — so every page turn re-paid the cost of
     * opening the book. One copy and one opened [cbzArchive] are kept and reused for every later
     * page/cover request of the same document; a request for a different document replaces both.
     */
    private var cbzScratchDocumentId: DocumentId? = null

    /** Filesystem path of the CBZ scratch copy for [cbzScratchDocumentId] — see that property's doc. */
    private var cbzScratchPath: Path? = null

    /** The open archive over [cbzScratchPath], its ZIP index built once and reused — see [cbzScratchDocumentId]. */
    private var cbzArchive: ComicArchive? = null

    /**
     * The id of the document [epubScratchPath] is a scratch copy of, or null when no copy is held. The
     * EPUB an image is pulled out of is unpacked once and kept: each call used to read the whole file
     * into memory and write a fresh scratch copy of it just to reach one picture, so turning to an
     * illustrated page cost as much as opening the book.
     */
    private var epubScratchDocumentId: DocumentId? = null

    /** Filesystem path of the scratch copy for [epubScratchDocumentId] — see that property's doc. */
    private var epubScratchPath: Path? = null

    /**
     * Counts how many times [invalidateCaches] has torn down EPUB scratch state, so [epubScratchCopy]
     * can tell whether a deletion landed while it was copying a book outside [epubScratchLock].
     *
     * A counter is needed because the state alone cannot answer that question: a document deleted while
     * its scratch slot was already empty leaves [epubScratchDocumentId] null both before and after the
     * deletion, so a copy finishing afterwards would happily install a scratch copy for a document that
     * no longer exists. Installing it is exactly the resurrection this counter prevents.
     *
     * The count is bumped for every invalidation rather than only the ones that match the document being
     * copied. That makes an unrelated deletion during a copy abort the install too, which costs one
     * empty result the caller retries on its next request — cheap, and far preferable to tracking
     * per-document invalidation state that would grow with the shelf.
     */
    private var epubScratchInvalidationCount = 0L
    /** Open import container for the currently-held scratch copy, reused across progressive batches. */
    private var epubScratchContainer: EpubImportContainer? = null
    /** Reusable temp font files keyed by the href they were extracted from for the current EPUB. */
    private val epubEmbeddedFontFilesByHref = linkedMapOf<String, Path>()
    /** Next unread linear spine position per progressively imported EPUB, cached to avoid replaying prior items. */
    private val epubNextSpineCursorByDocumentId = mutableMapOf<DocumentId, Int>()
    /** Section source path by stored section index for same-process progressive imports, cleared on invalidation/completion. */
    private val epubSectionPathByIndexByDocumentId = mutableMapOf<DocumentId, MutableMap<Int, String>>()

    /** The shelf, live: every document [documentDao] knows about, re-emitted as that table changes. */
    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> =
        documentDao.observeRecentDocuments().map { documents -> documents.map { it.toDocumentMetadata() } }

    /**
     * Shelf metadata for [documentId].
     *
     * @param documentId The document to look up.
     * @return The stored [DocumentMetadata], or null when no document with that id is on the shelf.
     */
    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        documentDao.getDocument(documentId.value)?.toDocumentMetadata()

    /**
     * The cover image bytes for [documentId], preferring the file [coverFilePath] already wrote (at
     * import time, see [importDocument]/[persistParsedDocument]) over paying to extract one again.
     *
     * Nothing cached yet means either this book was imported before covers were written at import time,
     * or it's a PDF/CBZ, whose parsers don't already have cover bytes in hand the way
     * [EpubDocumentParser] does. In that case this falls back to today's whole-file extraction, once, and
     * writes the result so no later open pays this again.
     *
     * @param documentId The document to fetch a cover for.
     * @return The cover bytes, or null when the format has no cover concept (TXT/IMAGE/UNKNOWN), when
     *   [documentFileSource] is unavailable, or when reading/extraction fails.
     */
    override suspend fun getDocumentCover(documentId: DocumentId): ByteArray? = withContext(Dispatchers.Default) {
        val metadata = getDocument(documentId) ?: return@withContext null
        when (metadata.format) {
            DocumentFormat.TXT,
            DocumentFormat.IMAGE,
            DocumentFormat.UNKNOWN -> null
            DocumentFormat.EPUB,
            DocumentFormat.PDF,
            DocumentFormat.CBZ,
                -> {
                val fileSource = documentFileSource ?: return@withContext null
                coverStore.cached(documentId)?.let {
                    logger.d { "cover: served ${it.size} B from file for ${metadata.location.displayName}" }
                    return@withContext it
                }
                logger.d {
                    "cover: no cached file at ${coverStore.pathFor(documentId)} for ${metadata.location.displayName}, extracting"
                }
                val extracted = when (metadata.format) {
                    DocumentFormat.CBZ -> cbzScratchLock.withLock {
                        cbzArchiveLocked(metadata, fileSource).coverImageBytes()
                    }
                    else -> coverStore.extract(metadata)
                }
                logger.d { "cover: extraction gave ${extracted?.size ?: -1} B for ${metadata.location.displayName}" }
                extracted?.also { coverStore.store(documentId, it) }
            }
        }
    }

    /**
     * Page images for a CBZ, decoded straight from the archive on demand rather than kept in memory or
     * in [searchIndexDao] — a comic's pages are raster images, not text to index.
     *
     * @param documentId The document to fetch pages from; a no-op returning an empty map for any format
     *   other than CBZ.
     * @param pageIndexes Which pages to decode.
     * @return Decoded bytes keyed by the page indexes that were actually found, or an empty map when
     *   [pageIndexes] is empty, the format isn't CBZ, or [documentFileSource] is unavailable.
     */
    override suspend fun getVisualPageImages(
        documentId: DocumentId,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> = withContext(Dispatchers.Default) {
        if (pageIndexes.isEmpty()) return@withContext emptyMap()
        val metadata = getDocument(documentId) ?: return@withContext emptyMap()
        if (metadata.format != DocumentFormat.CBZ) return@withContext emptyMap()
        val fileSource = documentFileSource ?: return@withContext emptyMap()
        cbzScratchLock.withLock {
            cbzArchiveLocked(metadata, fileSource).pageImageBytes(pageIndexes)
        }
    }

    /**
     * Inline image bytes for an EPUB, extracted through the shared [epubScratchCopy] rather than a fresh
     * whole-file read per image (see [epubScratchLock]'s own doc).
     *
     * Extraction happens entirely within [epubScratchLock] so that a concurrent document
     * deletion or replacement cannot remove the scratch file while this call is reading from it.
     * The trade-off is that a large batch of images serialises against any other scratch-copy
     * consumer (progressive import, font extraction, or another image request) for the duration
     * of the ZIP reads. In practice the images requested per page are small and few, so the hold
     * time stays in the low tens of milliseconds; a pathological case (many large images in one
     * call) would delay the next scratch-copy consumer by that same time.
     *
     * The document ID is re-verified inside the lock after the scratch copy is established: if
     * another coroutine replaced the scratch between [epubScratchCopy] releasing its internal
     * lock and this call's own re-acquisition, the stale path is not used and an empty map is
     * returned instead.
     *
     * @param documentId The EPUB to extract images from; a no-op returning an empty map for any other
     *   format.
     * @param hrefs Archive-relative paths of the images to extract, trimmed and de-duplicated before use.
     * @return Extracted bytes keyed by the hrefs that were actually found, or an empty map when [hrefs]
     *   is empty (after trimming), the format isn't EPUB, [documentFileSource] is unavailable, or the
     *   scratch copy was invalidated by a concurrent deletion before extraction could begin.
     */
    override suspend fun getEmbeddedImages(
        documentId: DocumentId,
        hrefs: Set<String>,
    ): Map<String, ByteArray> = withContext(Dispatchers.Default) {
        if (hrefs.isEmpty()) return@withContext emptyMap()
        val metadata = getDocument(documentId) ?: return@withContext emptyMap()
        if (metadata.format != DocumentFormat.EPUB) return@withContext emptyMap()
        val fileSource = documentFileSource ?: return@withContext emptyMap()
        val normalizedHrefs = hrefs.map(String::trim).filterTo(mutableSetOf(), String::isNotEmpty)
        if (normalizedHrefs.isEmpty()) return@withContext emptyMap()
        epubScratchCopy(metadata, fileSource)
        epubScratchLock.withLock {
            val path = epubScratchPath
            if (epubScratchDocumentId != documentId || path == null) return@withLock emptyMap()
            epubDocumentParser.extractEmbeddedImageBytes(path = path, hrefs = normalizedHrefs)
        }
    }

    /**
     * Embedded EPUB font files, extracted once per href into reusable temp files and reused while this
     * document stays current.
     *
     * This streams each requested ZIP entry straight to its own temp file, one href at a time, so both
     * the long-lived cache and the extraction peak stay at file paths plus a small copy buffer rather than
     * whole font byte arrays. Only the requested hrefs are touched.
     *
     * Extraction runs entirely within [epubScratchLock] so that a concurrent document deletion or
     * replacement cannot remove the scratch file while this call is streaming from it. The
     * document ID is re-verified inside the lock: if the scratch was invalidated between
     * [epubScratchCopy] releasing its internal lock and the re-acquisition here, the stale path
     * is not used and an empty map is returned. The locked [epubScratchPath] is used directly
     * rather than the path variable captured before the lock, closing the window where a
     * concurrent [invalidateCaches] could delete the file the captured variable still names.
     *
     * The trade-off is that font streaming serialises against other scratch-copy consumers for
     * its duration; in practice only a handful of font files are requested per document, each a
     * few hundred kilobytes, so the hold time is small.
     *
     * @param documentId The EPUB to extract fonts from; a no-op returning an empty map for any other
     *   format.
     * @param hrefs Archive-relative paths of the font files to extract, trimmed and de-duplicated before
     *   use.
     * @return File paths of the extracted temp fonts keyed by the hrefs that were found, or an empty map
     *   when nothing matches or the scratch copy was invalidated concurrently.
     */
    override suspend fun getEmbeddedFontFiles(
        documentId: DocumentId,
        hrefs: Set<String>,
    ): Map<String, String> = withContext(Dispatchers.Default) {
        if (hrefs.isEmpty()) return@withContext emptyMap()
        val metadata = getDocument(documentId) ?: return@withContext emptyMap()
        if (metadata.format != DocumentFormat.EPUB) return@withContext emptyMap()
        val fileSource = documentFileSource ?: return@withContext emptyMap()
        val normalizedHrefs = hrefs.map(String::trim).filterTo(linkedSetOf(), String::isNotEmpty)
        if (normalizedHrefs.isEmpty()) return@withContext emptyMap()
        epubScratchCopy(metadata, fileSource)
        epubScratchLock.withLock {
            val path = epubScratchPath
            if (epubScratchDocumentId != documentId || path == null) return@withLock emptyMap()
            val missingHrefs = normalizedHrefs.filter { href ->
                epubEmbeddedFontFilesByHref[href]?.let(systemFileSystem()::exists) != true
            }.toSet()
            if (missingHrefs.isNotEmpty()) {
                val zip = systemFileSystem().openZip(path)
                missingHrefs.forEach { href ->
                    streamEmbeddedFontScratchFile(zip = zip, href = href)?.let { fontPath ->
                        epubEmbeddedFontFilesByHref[href] = fontPath
                    }
                }
                deleteAbandonedEmbeddedFontScratchFiles(keep = epubEmbeddedFontFilesByHref.values.toSet())
            }
            normalizedHrefs.mapNotNull { href ->
                epubEmbeddedFontFilesByHref[href]?.takeIf(systemFileSystem()::exists)?.let { href to it.toString() }
            }.toMap()
        }
    }

    /**
     * The full [ReaderDocument] for [documentId] — sections, blocks, navigation — serving
     * [cachedReaderDocument] when it already names this id, and otherwise loading it via
     * [loadReaderDocument] and replacing the
     * cache with the result (even when that result is null, so a document that fails to load is not
     * retried on every call until something else invalidates the cache).
     *
     * The load itself runs outside [documentCacheLock] on purpose (see that property's own doc for why
     * every document load would otherwise serialise behind one mutex), which leaves a window for
     * [invalidateDocumentCache] to run while this call's own [loadReaderDocument] is still in flight.
     * [documentCacheGeneration], captured in the first locked section below, is what closes that window:
     * the second locked section only publishes the freshly loaded result into the cache when that
     * generation is still current. A load that started before an invalidation but only finishes after it
     * therefore never overwrites the invalidation with a stale snapshot — it is simply handed back to
     * this call's own caller uncached, and the next call reloads.
     *
     * @param documentId The document to load.
     * @return The document, or null when it is not on the shelf or fails to load.
     */
    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? {
        val generation = documentCacheLock.withLock {
            if (cachedDocumentId == documentId) return cachedReaderDocument
            documentCacheGeneration
        }
        val loaded = loadReaderDocument(documentId)
        documentCacheLock.withLock {
            if (documentCacheGeneration == generation) {
                cachedDocumentId = documentId
                cachedReaderDocument = loaded?.document
                cachedSectionBlocks = loaded?.sectionBlocks
            }
        }
        return loaded?.document
    }

    /**
     * Loads [documentId] from storage, repairing it first when the stored rows are missing something the
     * current parser would have captured.
     *
     * A TXT document with no sections at all, or sections whose text decoded badly (see
     * [hasBrokenText]), is re-read from the original file via [repairTxtDocument]. An EPUB whose sections
     * were written by an older [EpubDocumentParser] version, or whose navigation was never resolved, is
     * re-read via [repairEpubDocument]: stored text written by an older parser is missing things the
     * reader now needs — image proportions, block styles, pictures kept inside their sentence — and the
     * only repair is to read the file again. Asking the rows which parser wrote them costs one integer;
     * the previous way, looking through the blocks for traces of the old code, decoded 293 of one book's
     * 528 chapters on every open before it could answer.
     *
     * @param documentId The document to load.
     * @return The loaded document plus its on-demand block cache, or null when it is not on the shelf.
     */
    private suspend fun loadReaderDocument(documentId: DocumentId): LoadedReaderDocument? {
        val metadata = getDocument(documentId) ?: return null
        val storedSections = getStoredSections(documentId)
        if (metadata.format == DocumentFormat.TXT && (storedSections.sections.isEmpty() || storedSections.sections.hasBrokenText())) {
            repairTxtDocument(metadata)?.let { return LoadedReaderDocument(it, sectionBlocks = null) }
        }
        if (
            metadata.format == DocumentFormat.EPUB &&
            (storedSections.parserVersion < CurrentReaderParserVersion || storedSections.navigationJson.isBlank())
        ) {
            repairEpubDocument(metadata)?.let { return LoadedReaderDocument(it, sectionBlocks = null) }
        }
        return LoadedReaderDocument(metadata.toReaderDocument(storedSections), storedSections.sectionBlocks)
    }

    /**
     * Page windows for [documentId] at [style] and [viewportSize] — the reader's primary way to ask "what
     * do the pages of this book look like right now." Runs off the main dispatcher because laying the
     * document out is the expensive half of pagination; off the main dispatcher it no longer stalls the
     * frame the reader is drawing, so a page turn made right after a font or line-height change still
     * reaches the pager instead of being dropped.
     *
     * A null [viewportSize] means the caller has no real pane measurement yet. The newest layout ever
     * stored for this exact style — whatever viewport it was measured at — is a far better first answer
     * than the hardcoded [DefaultViewportSize] guess callers used to pass directly; only when nothing is
     * stored does this fall back to that same guess, so a freshly imported book with no stored layout at
     * all still gets a first pagination pass instead of waiting on a pane that would never measure
     * without one (see commit f33313b).
     *
     * A cached answer at [cachedPageWindowKey] (the `cachedPageWindowKey == key` check below) is reused
     * when the key matches and either it is already measured or this call brought no [pageBreaker] of its
     * own: a measured answer is the better answer, so it also serves a caller that arrived without a
     * breaker; only the reverse is refused. Keying on the request instead meant the same restored layout
     * was fetched and rebuilt twice on every open, once for each kind of caller.
     *
     * Failing that, [restorePageWindows] is tried next: a layout on disk is only ever a real measurement
     * (see [storePageWindows]), so it beats measuring again whether this call brought its own breaker or
     * not — an estimate call gets the exact result a measurement would have given it, for free. When it
     * succeeds, the two debug logs below the `restored != null` check report first how many pages came
     * back and how long the restore took, then what made that restore cheap: only the sections actually
     * decoded (by [SectionBlocksCache]) and only the pages actually built (by [RestoredPageWindows]),
     * instead of every block and every page in the book.
     *
     * When nothing is stored at all yet for this style, laying every section out before the reader sees
     * anything cost 6.4s/13.0s measured on a real device (204/528-section books) — so this measures the
     * section the reader is resting on first (via [anchorPositionFor]) and then keeps measuring forward,
     * bounded to [InitialForwardPaginationSections] sections, until at least
     * [InitialForwardPaginationPages] pages after the anchor page are already known. It still measures the
     * immediate previous section first when the resumed page is the first page of its section, so a single
     * backward turn is ready too. [continuePagination] extends the rest afterwards: backward toward
     * position 0 first (so the resumed page's number stops moving), then forward in spine order (so the
     * total does). A page is only ever built from a section that was actually measured — never an
     * estimate standing in for a section not yet reached — so the total this returns is honest:
     * "pages measured so far," not a guess dressed up as an answer. Cover detection needs section 0
     * eagerly, the same as a restore (see [restorePageWindows]), so the `prewarm(setOf(0))` call below
     * runs before [TextPageLayoutEngine.resolveSections].
     *
     * A fully measured session is written to [pageLayoutDao] whenever [pageBreaker] is real and at
     * least one page exists. Completed imports use [storePageWindows]; incomplete imports use
     * [storePartialPageWindows], whose character-count version identifies the exact stored prefix.
     * [importNextSections] then appends only the new sections when that version matches, deletes the row
     * when a breaker-less batch makes it stale, and promotes a final matching row when import completes.
     * [continuePagination] applies the same complete-versus-partial choice at its matching write site.
     *
     * @param documentId The document to lay out.
     * @param style The font/line-height/family the pages must be measured for.
     * @param viewportSize The pane size to lay out for, or null to let this resolve one itself (see
     *   above).
     * @param pageBreaker The real page-breaking measurement to use, or null for an estimate-only call
     *   that accepts whatever cached or restored answer is available without forcing a fresh measurement.
     * @param anchorOffset The character offset to resume into when a fresh measurement is needed, or null
     *   to start from the first content section.
     * @return The known page windows for this book/style/viewport — restored, cached, or freshly measured
     *   for the anchor section plus any bounded neighbours needed to cover the immediate previous page and
     *   at least [InitialForwardPaginationPages] following pages — or an empty list when the document
     *   can't be loaded or is a visual-page format (CBZ/IMAGE/PDF), which this function never paginates.
     */
    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: ReaderPageBreaker?,
        anchorOffset: Long?,
        viewportDensity: Float,
    ): List<PageWindow> = withContext(Dispatchers.Default) {
        val layoutKey = style.layoutKey()
        val resolvedViewportSize = viewportSize
            ?: newestStoredViewportSize(documentId, layoutKey)
            ?: DefaultViewportSize
        val key = PageWindowKey(
            documentId = documentId,
            layoutKey = layoutKey,
            viewportSize = resolvedViewportSize,
        )
        val wantsMeasured = pageBreaker != null
        documentCacheLock.withLock {
            val activeSession = paginationSession?.takeIf { it.key == key }
            if (activeSession == null && cachedPageWindowKey == key && (cachedPageWindowsAreMeasured || !wantsMeasured)) {
                return@withContext cachedPageWindows
            }
        }
        paginationContinuationLock.withLock {
            val session = documentCacheLock.withLock {
                paginationSession?.takeIf { it.key == key && (it.hasMeasuredPages || !wantsMeasured) }
            }
            if (session != null) {
                val windows = session.snapshotWindows(textPageLayoutEngine)
                documentCacheLock.withLock {
                    cachedPageWindowKey = key
                    cachedPageWindows = windows
                    cachedPageWindowsAreMeasured = session.hasMeasuredPages
                }
                return@withContext windows
            }
        }
        val document = getReaderDocument(documentId) ?: return@withContext emptyList()
        if (document.format.isVisualPageFormat()) return@withContext emptyList()

        val restoreStarted = TimeSource.Monotonic.markNow()
        val restored = suspendRunCatching { restorePageWindows(documentId, document, key) }
            .onFailure { error -> logger.w(error) { "Failed to restore stored page layout for $documentId" } }
            .getOrNull()
        if (restored != null) {
            val windows = restored.windows
            logger.d {
                "${document.title.orEmpty().take(12)}: ${windows.size} pages from ${document.sections.size} sections " +
                    "restored from storage in ${restoreStarted.elapsedNow().inWholeMilliseconds} ms"
            }
            logger.d {
                val decodedSections = restored.sectionBlocksCache?.decodedSectionCount ?: document.sections.size
                val builtWindows = (windows as? RestoredPageWindows)?.builtCount ?: windows.size
                "${document.title.orEmpty().take(12)}: on-demand pagination decoded $decodedSections/${document.sections.size} " +
                    "sections and built $builtWindows/${windows.size} windows to open"
            }
            documentCacheLock.withLock {
                cachedPageWindowKey = key
                cachedPageWindows = windows
                cachedPageWindowsAreMeasured = true
            }
            return@withContext windows
        }

        val started = TimeSource.Monotonic.markNow()
        val sectionBlocksCache = documentCacheLock.withLock { cachedSectionBlocks.takeIf { cachedDocumentId == documentId } }
        val fallbackSectionBlocks: (ReaderSection) -> List<ReaderBlock> = if (sectionBlocksCache != null) {
            { section -> sectionBlocksCache.blocksFor(section.index) }
        } else {
            textPageLayoutEngine.defaultSectionBlocks(document)
        }
        sectionBlocksCache?.prewarm(setOf(0))
        val resolved = textPageLayoutEngine.resolveSections(document, fallbackSectionBlocks)
        val anchorPosition = anchorPositionFor(resolved.contentSections, anchorOffset)
        val session = PaginationSession(
            key = key,
            format = document.format,
            coverPage = resolved.coverPage,
            contentSections = resolved.contentSections,
            sectionBlocksCache = sectionBlocksCache,
            fallbackSectionBlocks = fallbackSectionBlocks,
            lowPosition = anchorPosition,
            highPosition = anchorPosition,
            hasMeasuredPages = wantsMeasured,
        )
        if (resolved.contentSections.isNotEmpty()) {
            val anchorSection = resolved.contentSections[anchorPosition]
            val anchorStarts = measuredPageStartsForSection(
                section = anchorSection,
                sectionBlocks = session.blocksFor(anchorSection),
                style = style,
                viewportSize = resolvedViewportSize,
                viewportDensity = viewportDensity,
                pageBreaker = pageBreaker,
            )
            session.putMeasured(anchorPosition, anchorStarts)

            val anchorPageIndex = pageIndexContaining(anchorStarts.offsets, anchorSection.range, anchorOffset)
            if (anchorPageIndex == 0 && anchorPosition > 0) {
                val previousSection = resolved.contentSections[anchorPosition - 1]
                session.putMeasured(
                    anchorPosition - 1,
                    measuredPageStartsForSection(
                        section = previousSection,
                        sectionBlocks = session.blocksFor(previousSection),
                        style = style,
                        viewportSize = resolvedViewportSize,
                        viewportDensity = viewportDensity,
                        pageBreaker = pageBreaker,
                    ),
                )
            }
            var nextPosition = anchorPosition + 1
            var forwardSectionsMeasured = 0
            while (
                nextPosition <= resolved.contentSections.lastIndex &&
                forwardSectionsMeasured < InitialForwardPaginationSections &&
                session.pagesAfter(anchorPosition, anchorPageIndex) < InitialForwardPaginationPages
            ) {
                val nextSection = resolved.contentSections[nextPosition]
                session.putMeasured(
                    nextPosition,
                    measuredPageStartsForSection(
                        section = nextSection,
                        sectionBlocks = session.blocksFor(nextSection),
                        style = style,
                        viewportSize = resolvedViewportSize,
                        viewportDensity = viewportDensity,
                        pageBreaker = pageBreaker,
                    ),
                )
                nextPosition += 1
                forwardSectionsMeasured += 1
            }
        }
        val pageWindows = session.snapshotWindows(textPageLayoutEngine)
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        logger.d {
            "${document.title.orEmpty().take(12)}: measured section ${anchorPosition + 1}/" +
                "${resolved.contentSections.size.coerceAtLeast(1)} (${pageWindows.size} pages so far) " +
                "in $elapsedMs ms, measured=$wantsMeasured, complete=${session.isComplete}"
        }
        documentCacheLock.withLock {
            cachedPageWindowKey = key
            cachedPageWindows = pageWindows
            cachedPageWindowsAreMeasured = wantsMeasured
            paginationSession = session.takeUnless { it.isComplete }
        }
        if (session.isComplete && pageBreaker != null && pageWindows.isNotEmpty()) {
            val importComplete = isImportComplete(documentId)
            if (importComplete) {
                storePageWindows(documentId, document, key, session)
            } else {
                storePartialPageWindows(documentId, document, key, session)
            }
        }
        pageWindows
    }

    /**
     * Extends the in-flight [paginationSession] for [documentId]/[style]/[viewportSize] by a bounded batch
     * of more content sections, claiming and committing them atomically under
     * [paginationContinuationLock].
     *
     * One section is claimed and committed at a time. Two continuation passes overlap in the ordinary
     * course of a style change — `updateStyle` starts one, and the pane's first breaker report for the
     * new style starts another — and reading [PaginationSession.lowPosition]/
     * [PaginationSession.highPosition] outside a lock let both claim the same position, measure it, and
     * append it twice. A pass that walked the whole book that way finished holding, and stored, exactly
     * twice the book's pages.
     * [paginationContinuationLock] is held across the measurement itself, not just the commit, because
     * the claim is only safe if nothing else can read the positions it is about to move. Nothing on the
     * reader's own path takes this lock — continuation is background work being serialised against
     * itself, never against a page the reader is waiting for (which is served from [cachedPageWindows]
     * under [documentCacheLock]).
     *
     * The extension direction alternates on [PaginationSession.lowPosition]: while it is still above 0
     * the next section claimed is the one just before it (so the resumed section's own pages settle
     * first); once it reaches 0 every further call extends forward from
     * [PaginationSession.highPosition] instead. This path now only appends measured section starts to the
     * live session; it does not rebuild the whole page-window snapshot after every batch. Snapshot/cache
     * materialisation is deferred until a caller asks [getPageWindows] for it or the session completes.
     * Once the session is complete, its windows are written to [pageLayoutDao]. A finished import gets
     * a complete row; an in-progress import gets a character-count-versioned partial row that
     * [appendMeasuredPageStarts] can extend with later sections without remeasuring this prefix.
     *
     * @param documentId The document whose in-flight session to extend.
     * @param style The style the in-flight session must match to be extended.
     * @param viewportSize The viewport the in-flight session must match to be extended.
     * @param pageBreaker The real page-breaking measurement for the newly claimed section, or null to
     *   report immediate completion without measuring anything.
     * @return [PaginationProgress.isComplete] true and `sectionsMeasured = 0` when there is no matching
     *   in-flight session, the session is already complete, or [pageBreaker] is null; otherwise progress
     *   for the sections this call measured.
     */
    override suspend fun continuePagination(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float,
    ): PaginationProgress = withContext(Dispatchers.Default) {
        if (pageBreaker == null) return@withContext PaginationProgress(isComplete = true, sectionsMeasured = 0)
        paginationContinuationLock.withLock {
            val key = PageWindowKey(documentId = documentId, layoutKey = style.layoutKey(), viewportSize = viewportSize)
            val session = documentCacheLock.withLock { paginationSession?.takeIf { it.key == key } }
                ?: return@withContext PaginationProgress(isComplete = true, sectionsMeasured = 0)
            if (session.isComplete) return@withContext PaginationProgress(isComplete = true, sectionsMeasured = 0)
            session.hasMeasuredPages = true

            var sectionsMeasured = 0
            while (sectionsMeasured < PaginationContinuationBatchSize && !session.isComplete) {
                val extendingBackward = session.lowPosition > 0
                val nextPosition = if (extendingBackward) session.lowPosition - 1 else session.highPosition + 1
                val nextSection = session.contentSections[nextPosition]
                session.putMeasured(
                    nextPosition,
                    measuredPageStartsForSection(
                        section = nextSection,
                        sectionBlocks = session.blocksFor(nextSection),
                        style = style,
                        viewportSize = viewportSize,
                        viewportDensity = viewportDensity,
                        pageBreaker = pageBreaker,
                    ),
                )
                sectionsMeasured += 1
            }
            val isComplete = session.isComplete
            if (isComplete) {
                val windows = session.snapshotWindows(textPageLayoutEngine)
                documentCacheLock.withLock {
                    cachedPageWindowKey = key
                    cachedPageWindows = windows
                    cachedPageWindowsAreMeasured = true
                    paginationSession = null
                }
                if (windows.isNotEmpty()) {
                    val importComplete = isImportComplete(documentId)
                    if (importComplete) {
                        getReaderDocument(documentId)?.let { document -> storePageWindows(documentId, document, key, session) }
                    } else {
                        getReaderDocument(documentId)?.let { document -> storePartialPageWindows(documentId, document, key, session) }
                    }
                }
            } else {
                documentCacheLock.withLock {
                    paginationSession = session
                }
            }
            PaginationProgress(isComplete = isComplete, sectionsMeasured = sectionsMeasured)
        }
    }

    /**
     * Whether pagination for [documentId] has measured every content section the book currently has.
     *
     * This can never be true while the import is still running: while it runs there are sections the
     * book will have that are not even parsed yet, so no measurement of it can be complete however far
     * the current session walked. [isImportComplete] is therefore checked first and short-circuits to
     * false. Answering from [paginationSession] alone said "complete" for every moment a batch had just
     * nulled it (see [invalidateDocumentCache]), and a caller asks this to decide whether to keep the
     * continuation running (see `ReaderViewModel.refreshPaginationCompleteness`) — so that answer retired
     * the only thing that grows the page count, leaving the total pinned to the one section the last
     * reload measured.
     *
     * The [isImportComplete] check is deliberately made outside [documentCacheLock]: it reads storage,
     * and holding the cache lock across it would block the page the reader is waiting for.
     *
     * @param documentId The document to check.
     * @return True once the import has finished and either the active session for this document is
     *   complete, or there is no active session and the cached windows for this document came from a
     *   real measurement rather than an estimate-only open.
     */
    override suspend fun isPaginationComplete(documentId: DocumentId): Boolean {
        if (!isImportComplete(documentId)) return false
        return documentCacheLock.withLock {
            paginationSession?.let { it.key.documentId != documentId || it.isComplete }
                ?: if (cachedPageWindowKey?.documentId == documentId) cachedPageWindowsAreMeasured else true
        }
    }

    /** The position in [contentSections] of the section containing [anchorOffset] — the last section
     * starting at or before it, since sections are ascending and non-overlapping. Defaults to the
     * first content section when [anchorOffset] is null or before every section's own start, the same
     * place a freshly imported book with nowhere to resume to starts from. */
    private fun anchorPositionFor(contentSections: List<ReaderSection>, anchorOffset: Long?): Int {
        if (contentSections.isEmpty() || anchorOffset == null) return 0
        var low = 0
        var high = contentSections.lastIndex
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (contentSections[mid].range.start <= anchorOffset) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result.coerceIn(0, contentSections.lastIndex)
    }

    /** Which page in [sectionStarts] contains [anchorOffset], defaulting to the first page when null/outside. */
    private fun pageIndexContaining(sectionStarts: LongArray, sectionRange: TextRange, anchorOffset: Long?): Int {
        if (sectionStarts.isEmpty() || anchorOffset == null) return 0
        var lo = 0
        var hi = sectionStarts.lastIndex
        var result = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sectionStarts[mid] <= anchorOffset) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val start = sectionStarts[result]
        val end = sectionStarts.getOrNull(result + 1) ?: sectionRange.end
        return if (anchorOffset in start until end) result else 0
    }

    /**
     * The viewport a caller should measure at for [documentId]/[style] before calling [getPageWindows]
     * with a real [ReaderPageBreaker] — the same lookup [getPageWindows] itself falls back to when no
     * viewport is supplied.
     *
     * @param documentId The document to look up a stored viewport for.
     * @param style The style whose stored layout's viewport should be reused.
     * @return The viewport of the newest stored layout for this style, or null when none is stored yet.
     */
    override suspend fun resolveViewportSizeForStyle(documentId: DocumentId, style: ReaderStyle): ViewportSize? =
        withContext(Dispatchers.Default) { newestStoredViewportSize(documentId, style.layoutKey()) }

    /**
     * Eagerly decodes [sectionIndexes]' blocks into the cached [SectionBlocksCache] for [documentId], so
     * a caller that knows which sections it is about to show can pay that cost ahead of the page that
     * needs it instead of leaving it to lazily catch up.
     *
     * @param documentId The document whose section-blocks cache to warm; a no-op returning 0 when this
     *   is not the currently cached document.
     * @param sectionIndexes Which sections to decode.
     * @return How many sections were actually newly decoded by this call.
     */
    override suspend fun warmSectionBlocks(documentId: DocumentId, sectionIndexes: Set<Int>): Int {
        if (sectionIndexes.isEmpty()) return 0
        val cache = documentCacheLock.withLock {
            cachedSectionBlocks.takeIf { cachedDocumentId == documentId }
        } ?: return 0
        return withContext(Dispatchers.Default) { cache.prewarm(sectionIndexes) }
    }

    /**
     * Resolves every publisher-font href referenced by [documentId]. New imports answer from the exact
     * persisted href index without decoding block JSON; a legacy null index scans the cached section
     * blocks once, persists the result, and makes every later call use the indexed path.
     *
     * @param documentId The EPUB whose referenced publisher fonts are needed.
     * @return The distinct referenced hrefs, or an empty set when the document or its loaded block cache
     *   is unavailable.
     */
    override suspend fun getReferencedEmbeddedFontHrefs(documentId: DocumentId): Set<String> {
        val entity = documentDao.getDocument(documentId.value) ?: return emptySet()
        val indexed = entity.embeddedFontHrefsJson
        if (indexed != null) {
            val started = TimeSource.Monotonic.markNow()
            val result = runCatching { json.decodeFromString<List<String>>(indexed).toSet() }.getOrDefault(emptySet())
            logger.d {
                "${entity.name.take(12)}: font index served ${result.size} hrefs from stored JSON " +
                    "in ${started.elapsedNow().inWholeMilliseconds} ms (O(F), no blocks DAO read)"
            }
            return result
        }
        val started = TimeSource.Monotonic.markNow()
        val cache = documentCacheLock.withLock {
            cachedSectionBlocks.takeIf { cachedDocumentId == documentId }
        } ?: return emptySet()
        return withContext(Dispatchers.Default) {
            val snapshot = cache.snapshotAllBlocks()
            val hrefs = snapshot.values
                .asSequence()
                .flatMap { blocks -> blocks.asSequence() }
                .flatMap { block ->
                    sequenceOf(block.style?.fontHref)
                        .plus(block.spans.asSequence().map { span -> span.styleDelta?.fontHref })
                }
                .filterNotNull()
                .toMutableSet()
            val sortedHrefs = hrefs.sorted()
            val hrefsJson = json.encodeToString(sortedHrefs)
            documentDao.updateEmbeddedFontHrefsJson(documentId.value, hrefsJson)
            logger.d {
                "${entity.name.take(12)}: legacy font scan found ${hrefs.size} hrefs, backfilled index " +
                    "in ${started.elapsedNow().inWholeMilliseconds} ms"
            }
            hrefs
        }
    }

    /**
     * The viewport [PageLayoutDao.getNewestPageLayoutForStyle] resolves for [layoutKey], if any row exists.
     *
     * @param documentId The document to look up a stored layout for.
     * @param layoutKey The style (font size/line height/family) to match.
     * @return The viewport of the newest matching stored layout, or null when none exists.
     */
    private suspend fun newestStoredViewportSize(documentId: DocumentId, layoutKey: ReaderLayoutKey): ViewportSize? {
        val stored = pageLayoutDao.getNewestPageLayoutForStyle(
            documentId = documentId.value,
            fontSizeSp = layoutKey.fontSizeSp,
            lineHeightMultiplier = layoutKey.lineHeightMultiplier,
            fontFamilyName = layoutKey.fontFamilyName.orEmpty(),
        ) ?: return null
        return ViewportSize(widthPx = stored.viewportWidthPx, heightPx = stored.viewportHeightPx)
    }

    /**
     * The persisted counterpart of [cachedPageWindows]: page starts a real measurement produced on an
     * earlier open, kept past the process's lifetime so the next open of the same book at the same type
     * and viewport never measures a single line of it again.
     *
     * Refuses (and deletes) a stored row whose `characterCount` no longer matches [document]'s: re-parsing
     * a document can move every character offset in it, and refusing a row whose character count no
     * longer matches is what keeps a bookmark or a reading position from silently landing on the wrong
     * text after a repair pass rewrites the book it pointed into. Only the blob (`pageStartsBlob`) is ever
     * decoded — `pageStartsJson` is legacy storage kept for schema reasons (see [PageLayoutEntity]). A row
     * written before `TeddReaderMigration7To8` has no blob and is treated the same as no stored row at
     * all; that migration deletes every such row for exactly this reason, so this should only be null in
     * a database that predates the migration entirely.
     *
     * Also refuses (and deletes) a row whose decoded page starts do not strictly ascend: pages are
     * written in reading order, so their starts can only ascend. A row that breaks that was not written
     * by a sound measurement of the book as it now stands, and rebuilding pages from it would put the
     * reader on text that is not where the row says it is — so it is thrown away and measured again
     * rather than trusted. The check is one pass over a few thousand longs, next to a decode that already
     * walked the same array, and it is what lets a device carrying a row some writer bug corrupted heal
     * itself on the next open instead of reading the wrong page forever.
     *
     * A section-blocks cache exists only for a document actually loaded from storage; a document that
     * just came out of a repair pass already holds every block in memory, so
     * [TextPageLayoutEngine.reconstruct] falls back to its own default there instead of decoding anything
     * twice. When a cache is available,
     * section 0 is prewarmed before reconstructing because cover detection looks at section 0 eagerly, not
     * lazily (see `TextPageLayoutEngine.findCoverSection`, called from within `reconstruct` itself before
     * it ever returns) — so section 0 has to already be decoded before `reconstruct` runs, not just before
     * some later page happens to be built.
     *
     * @param documentId The document whose stored layout to restore.
     * @param document The freshly loaded document the stored layout must still agree with.
     * @param key The style/viewport the stored layout must have been measured at.
     * @return The reconstructed windows plus the section-blocks cache that answered them, or null when
     *   nothing is stored, the stored row fails a consistency check above, or there is no page-starts
     *   blob to decode.
     */
    private suspend fun restorePageWindows(
        documentId: DocumentId,
        document: ReaderDocument,
        key: PageWindowKey,
    ): RestoredPageWindowsResult? {
        val stored = pageLayoutDao.getPageLayout(
            documentId = documentId.value,
            fontSizeSp = key.layoutKey.fontSizeSp,
            lineHeightMultiplier = key.layoutKey.lineHeightMultiplier,
            fontFamilyName = key.layoutKey.fontFamilyName.orEmpty(),
            viewportWidthPx = key.viewportSize.widthPx,
            viewportHeightPx = key.viewportSize.heightPx,
        ) ?: return null
        if (stored.characterCount != document.characterCount ||
            document.sections.any { section -> !textPageLayoutEngine.canMeasureSection(section) }
        ) {
            pageLayoutDao.deletePageLayouts(documentId.value)
            return null
        }
        val pageStartsBlob = stored.pageStartsBlob ?: return null
        val pageStarts = decodePageStartsBlob(pageStartsBlob)
        if (!pageStarts.isStrictlyAscending()) {
            logger.w { "Discarding a stored page layout for $documentId whose page starts do not ascend" }
            pageLayoutDao.deletePageLayouts(documentId.value)
            return null
        }
        val sectionBlocksCache = documentCacheLock.withLock {
            cachedSectionBlocks.takeIf { cachedDocumentId == documentId }
        }
        val windows = if (sectionBlocksCache != null) {
            sectionBlocksCache.prewarm(setOf(0))
            textPageLayoutEngine.reconstruct(
                document = document,
                contentPageStarts = pageStarts,
                sectionBlocks = { section -> sectionBlocksCache.blocksFor(section.index) },
                isSectionReady = sectionBlocksCache::isReady,
            )
        } else {
            textPageLayoutEngine.reconstruct(document, pageStarts)
        }
        return RestoredPageWindowsResult(windows, sectionBlocksCache)
    }

    /**
     * Persists every measured start from a completed pagination session as a final reusable layout.
     *
     * @param documentId The document whose completed layout is being stored.
     * @param document The complete document whose character count versions the row.
     * @param key The exact style and viewport measurement key.
     * @param session The completed session supplying all content-page starts.
     */
    private suspend fun storePageWindows(
        documentId: DocumentId,
        document: ReaderDocument,
        key: PageWindowKey,
        session: PaginationSession,
    ) {
        if (!session.isFullyMeasured) return
        storePageStarts(documentId, document, key, session.allMeasuredStarts())
    }

    /**
     * Stores a partial page layout — one measured against the currently-known prefix of a document
     * whose import has not yet completed. Partial rows are distinguished from complete ones by
     * [PageLayoutEntity.isPartial] = true, and are promoted once the import finishes and the final
     * characterCount matches.
     *
     * @param documentId The document whose partial layout to store.
     * @param document The document in its current prefix state.
     * @param key The style/viewport the layout was measured at.
     * @param session The completed pagination session for the current prefix.
     */
    private suspend fun storePartialPageWindows(
        documentId: DocumentId,
        document: ReaderDocument,
        key: PageWindowKey,
        session: PaginationSession,
    ) {
        if (!session.isFullyMeasured) return
        pageLayoutDao.upsertPageLayout(
            PageLayoutEntity(
                documentId = documentId.value,
                fontSizeSp = key.layoutKey.fontSizeSp,
                lineHeightMultiplier = key.layoutKey.lineHeightMultiplier,
                fontFamilyName = key.layoutKey.fontFamilyName.orEmpty(),
                viewportWidthPx = key.viewportSize.widthPx,
                viewportHeightPx = key.viewportSize.heightPx,
                characterCount = document.characterCount,
                pageStartsBlob = encodePageStartsBlob(session.allMeasuredStarts()),
                writtenAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                isPartial = true,
            ),
        )
    }

    /**
     * Writes final page starts and trims older layout variants so style changes cannot grow storage
     * without bound.
     *
     * @param documentId The document whose final page starts are being stored.
     * @param document The complete document whose character count versions the row.
     * @param key The exact style and viewport measurement key.
     * @param contentPageStarts The measured starts in strictly increasing reading order.
     */
    private suspend fun storePageStarts(
        documentId: DocumentId,
        document: ReaderDocument,
        key: PageWindowKey,
        contentPageStarts: LongArray,
    ) {
        pageLayoutDao.upsertPageLayout(
            PageLayoutEntity(
                documentId = documentId.value,
                fontSizeSp = key.layoutKey.fontSizeSp,
                lineHeightMultiplier = key.layoutKey.lineHeightMultiplier,
                fontFamilyName = key.layoutKey.fontFamilyName.orEmpty(),
                viewportWidthPx = key.viewportSize.widthPx,
                viewportHeightPx = key.viewportSize.heightPx,
                characterCount = document.characterCount,
                pageStartsBlob = encodePageStartsBlob(contentPageStarts),
                writtenAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        pageLayoutDao.trimPageLayouts(documentId.value, keep = MaxStoredPageLayoutsPerDocument)
    }

    /**
     * Imports [source] as a new document, or hands back the existing one when it is already on the
     * shelf and fully imported.
     *
     * A book already on the shelf, imported all the way through, is opened rather than imported again:
     * another app handing this one over — "open with", a share — arrives here every time, and
     * re-importing threw away the stored text and page layouts of a book the reader was already reading,
     * so opening a 528-chapter book from a file manager paid the whole import over again. An unfinished
     * import is not skipped this way — [importNextSections] picks that one up where it stopped, so the
     * `existingDocument != null && isImportComplete(id)` check below only short-circuits a book that
     * genuinely finished.
     *
     * EPUB is special-cased below: progressive import ([importEpubPhase0]) only pays for itself when the
     * caller deliberately withheld the bytes to avoid reading the whole file into memory (see
     * `DocumentImporter.android/ios.kt`, which now passes `bytes=null` for a picked EPUB). A caller that
     * already has the bytes — an existing test, or a Google Drive download that already paid the network
     * cost — gets nothing from deferring the rest of the spine, so it gets the same synchronous full
     * parse ([importEpubFullyFromBytes]) EPUB import has always done. Only the `bytes=null` path takes
     * [importEpubPhase0]'s phased route, which needs a real file source to stream from since it has no
     * bytes to fall back on.
     *
     * @param source The picked file's location and, optionally, its already-read bytes.
     * @param importedAtEpochMillis When this import happened, used to stamp `addedAtEpochMillis` (for a
     *   genuinely new document) and `lastOpenedAtEpochMillis`.
     * @return The imported (or already-shelved) document.
     * @throws IllegalStateException When an EPUB is imported with no bytes and no [documentFileSource]
     *   is configured, or when a CBZ is imported with no bytes and no [documentFileSource].
     * @throws IllegalArgumentException When [formatDetector] cannot recognise the format.
     */
    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument {
        val id = DocumentId(source.location.sourceUri)
        val existingDocument = getDocument(id)
        if (existingDocument != null && isImportComplete(id)) {
            getReaderDocument(id)?.let { return it }
        }
        val format = formatDetector.detect(source.location, source.bytes)
        val document = when (format) {
            DocumentFormat.TXT -> txtDocumentParser.parse(
                id = id,
                title = source.location.displayName,
                text = TxtTextDecoder.decode(requireDocumentBytes(source)),
            )

            DocumentFormat.EPUB -> return source.bytes?.let { bytes ->
                importEpubFullyFromBytes(id, source, existingDocument, importedAtEpochMillis, bytes)
            } ?: importEpubPhase0(
                id = id,
                source = source,
                existingDocument = existingDocument,
                importedAtEpochMillis = importedAtEpochMillis,
                fileSource = documentFileSource ?: error("Cannot import EPUB without a file source when no bytes are provided."),
            )

            DocumentFormat.PDF -> pdfDocumentParser.parse(
                id = id,
                title = source.location.displayName,
                location = source.location,
                bytes = source.bytes,
            )

            DocumentFormat.CBZ -> source.bytes?.let { bytes ->
                comicBookDocumentParser.parse(
                    id = id,
                    title = source.location.displayName,
                    bytes = bytes,
                )
            } ?: run {
                val fileSource = documentFileSource ?: error("Cannot import CBZ without file source.")
                withTemporarySourceCopy(fileSource, source.location) { path ->
                    comicBookDocumentParser.parse(
                        id = id,
                        title = source.location.displayName,
                        path = path,
                    )
                }
            }

            DocumentFormat.IMAGE -> imageDocumentParser.parse(
                id = id,
                title = source.location.displayName,
            )

            DocumentFormat.UNKNOWN -> throw IllegalArgumentException(
                "Unsupported document format: ${source.location.displayName}",
            )
        }

        persistParsedDocument(
            metadata = DocumentMetadata(
                id = id,
                location = source.location,
                format = document.format,
                addedAtEpochMillis = existingDocument?.addedAtEpochMillis ?: importedAtEpochMillis,
                lastOpenedAtEpochMillis = importedAtEpochMillis,
                pageCount = document.pageCount,
                characterCount = document.characterCount,
                wordCount = document.wordCount,
                isBookmarked = existingDocument?.isBookmarked ?: false,
                folderId = existingDocument?.folderId,
                folderName = existingDocument?.folderName,
            ),
            document = document,
        )
        return document
    }

    /**
     * Writes an ordinary metadata edit — a favourite toggle, a folder move — for a document already on
     * the shelf, preserving its `importCompletedAtEpochMillis` stamp across the write.
     *
     * [DocumentMetadata] carries no field for that column (see [DocumentEntity]), and Room's upsert
     * replaces the whole row — an ordinary edit like a favourite toggle would otherwise write back null
     * and erase the timestamp a later progressive-import step needs to trust. Reading the stored value
     * forward is the smaller fix; threading the column through the domain model would touch every one of
     * its call sites for a value nothing reads yet.
     *
     * @param document The metadata to write; every field on it overwrites the stored row except
     *   `importCompletedAtEpochMillis`, which is carried forward from storage instead.
     */
    override suspend fun upsertDocument(document: DocumentMetadata) {
        val importCompletedAtEpochMillis = documentDao.getDocument(document.id.value)?.importCompletedAtEpochMillis
        documentDao.upsertDocument(
            document.toDocumentEntity().copy(importCompletedAtEpochMillis = importCompletedAtEpochMillis),
        )
    }

    override suspend fun setDocumentsBookmarked(documentIds: Collection<DocumentId>, isBookmarked: Boolean) {
        if (documentIds.isEmpty()) return
        documentDao.updateBookmarked(documentIds.map(DocumentId::value), isBookmarked)
    }

    override suspend fun setDocumentsFolder(
        documentIds: Collection<DocumentId>,
        folderId: String?,
        folderName: String?,
    ) {
        require((folderId == null) == (folderName == null)) {
            "folderId and folderName must both be null or both be non-null."
        }
        require(folderId == null || folderId.isNotBlank()) { "folderId must not be blank." }
        require(folderName == null || folderName.isNotBlank()) { "folderName must not be blank." }
        if (documentIds.isEmpty()) return
        documentDao.updateFolder(documentIds.map(DocumentId::value), folderId, folderName)
    }

    override suspend fun renameFolder(folderId: String, folderName: String) {
        require(folderId.isNotBlank()) { "folderId must not be blank." }
        require(folderName.isNotBlank()) { "folderName must not be blank." }
        documentDao.renameFolder(folderId, folderName)
    }

    override suspend fun clearFolder(folderId: String) {
        require(folderId.isNotBlank()) { "folderId must not be blank." }
        documentDao.clearFolder(folderId)
    }

    /**
     * Stamps [documentId] as opened at [openedAtEpochMillis], the anchor the shelf's "recent" ordering
     * and the reading-position invariant (see AGENTS.md's Reader Invariants) both read.
     *
     * @param documentId The document that was opened.
     * @param openedAtEpochMillis When it was opened.
     */
    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) {
        documentDao.updateLastOpenedAt(documentId.value, openedAtEpochMillis)
    }

    /**
     * Removes [documentId] from the shelf entirely: [documentDao]'s row for it, every in-memory cache
     * that might still name it and its stored page layouts (both via [invalidateCaches]), its EPUB
     * scratch copy if it is the one currently held (also via [invalidateCaches]), and its cached cover
     * file.
     *
     * @param documentId The document to delete.
     */
    override suspend fun deleteDocument(documentId: DocumentId) {
        documentDao.deleteDocument(documentId.value)
        invalidateCaches(documentId)
        coverStore.delete(documentId)
    }

    override suspend fun deleteDocuments(documentIds: Collection<DocumentId>) {
        if (documentIds.isEmpty()) return
        documentDao.deleteDocuments(documentIds.map(DocumentId::value))
        documentIds.forEach { documentId ->
            invalidateCaches(documentId)
            coverStore.delete(documentId)
        }
    }

    /**
     * The full cache teardown for [documentId] used whenever a document is rewritten (a repair, a
     * repeat import) or deleted outright: the in-memory caches via [invalidateDocumentCache], the stored
     * page layouts, and the EPUB scratch copy if it is the one currently held.
     *
     * A stored layout addresses text by absolute offset, and re-parsing the document is exactly what
     * moves those offsets. Every path that rewrites a document's sections calls through here first, so
     * this is the one place that needs to know a stored layout has gone stale.
     *
     * @param documentId The document whose caches and stored layout to drop.
     * @param keepScratchCopy True to keep the currently-held EPUB scratch copy for this document even
     *   while the rest of the caches are dropped — used only by phase-0 progressive import, whose next
     *   background batch still needs that same copy.
     */
    private suspend fun invalidateCaches(
        documentId: DocumentId,
        keepScratchCopy: Boolean = false,
    ) {
        invalidateDocumentCache(documentId)
        pageLayoutDao.deletePageLayouts(documentId.value)
        epubImportCursorLock.withLock {
            epubNextSpineCursorByDocumentId.remove(documentId)
            epubSectionPathByIndexByDocumentId.remove(documentId)
        }
        epubScratchLock.withLock {
            if (!keepScratchCopy) {
                epubScratchInvalidationCount += 1
                if (epubScratchDocumentId == documentId) {
                    epubScratchPath?.let { path -> runCatching { systemFileSystem().delete(path) } }
                    clearEmbeddedFontScratchFilesLocked()
                    epubScratchDocumentId = null
                    epubScratchPath = null
                    epubScratchContainer = null
                }
            }
        }
        cbzScratchLock.withLock {
            if (cbzScratchDocumentId == documentId) {
                cbzScratchPath?.let { path -> runCatching { systemFileSystem().delete(path) } }
                cbzArchive = null
                cbzScratchDocumentId = null
                cbzScratchPath = null
            }
        }
    }

    /**
     * Just the in-memory half of [invalidateCaches] — dropping the cached document, its section-blocks
     * cache and the cached page-window answer, without touching the stored page layouts or the EPUB
     * scratch copy. A progressive import's completion paths ([finishEpubImport],
     * [finishNonProgressiveEpubImport]) call this instead of [invalidateCaches]: the document really did
     * grow and the next read must see that, but the stored page layout is exactly what
     * [importNextSections] is extending in place, and the scratch copy is exactly what it is still
     * reading from — deleting either mid-import would throw away real progress, not stale data.
     *
     * Always bumps [documentCacheGeneration], even when [documentId] does not match whatever is
     * currently cached: a [getReaderDocument] load already in flight for this same document has no way
     * to know that from inside its own call, and the bump is exactly what tells it not to publish a
     * snapshot that started before this invalidation into the cache after it.
     *
     * @param documentId The document whose in-memory caches to drop, if it is the one currently cached.
     */
    private suspend fun invalidateDocumentCache(documentId: DocumentId) {
        documentCacheLock.withLock {
            documentCacheGeneration += 1
            if (cachedDocumentId == documentId) {
                cachedDocumentId = null
                cachedReaderDocument = null
                cachedSectionBlocks = null
            }
            if (cachedPageWindowKey?.documentId == documentId) {
                cachedPageWindowKey = null
                cachedPageWindows = emptyList()
                cachedPageWindowsAreMeasured = false
            }
            if (paginationSession?.key?.documentId == documentId) {
                paginationSession = null
            }
        }
    }

    /**
     * A scratch copy of the EPUB behind [metadata]'s document, made once and reused for every later
     * embedded image/font extraction and progressive import batch.
     *
     * Only one is kept: the reader has one book open, and holding a second copy of a previous one on
     * disk buys nothing. A copy this long-lived cannot be removed in a `finally`, so the process holds
     * its path in [epubScratchPath] and deletes it when the next book replaces it. That path is lost when
     * the process dies, and the copy it named is not — one abandoned copy per run, the size of the whole
     * book. [deleteAbandonedScratchCopies] sweeping the ones no longer named here is what keeps a shelf
     * of large books from filling the cache.
     *
     * **Lifetime contract.** The returned path is valid only as long as [epubScratchLock] is not
     * re-acquired by a concurrent coroutine that replaces or deletes the scratch (see
     * [invalidateCaches]). Callers that need to *use* the path for I/O must re-acquire
     * [epubScratchLock] immediately after this call returns and re-verify [epubScratchDocumentId]
     * before touching the file — or accept that the path may refer to a file that no longer exists.
     * [getEmbeddedImages] and [getEmbeddedFontFiles] follow this pattern; [openEpubScratchContainer]
     * adds a `runCatching` around the ZIP open for the window it cannot lock around.
     *
     * **Why the copy runs outside the lock.** Copying a book is the one genuinely slow step here — a
     * large EPUB arriving through Android's SAF takes seconds — and holding [epubScratchLock] across it
     * stalled every other scratch consumer for that whole time, so turning to an illustrated page during
     * a first open blocked until the copy finished. The copy is therefore performed unlocked and the
     * result *installed* under the lock, in three cases the install has to distinguish:
     *
     * - Another coroutine already established a usable scratch for this same document while this copy
     *   was running: the freshly copied file is deleted and that established path is returned, so both
     *   callers converge on one copy instead of fighting over the slot.
     * - [invalidateCaches] ran during the copy, which [epubScratchInvalidationCount] is what detects:
     *   nothing is installed and the copied file is deleted. The path is still returned, and the
     *   caller's own re-verification inside [epubScratchLock] then sees the slot does not name this
     *   document and gives up — which is how a deleted document yields an empty result instead of a
     *   resurrected scratch copy.
     * - Otherwise the copy is installed, replacing whatever the slot held, which is the same
     *   last-writer-wins behavior the fully locked version had.
     *
     * A caller that only *reuses* an already-established copy never leaves the lock at all: that check
     * is the first thing this function does, and it returns without ever reaching the copy.
     *
     * @param metadata The document the EPUB belongs to; a scratch copy already held for the same id is
     *   reused as-is when it still exists on disk.
     * @param fileSource Where to copy the original file bytes from when a fresh copy is needed.
     * @return The scratch copy's path. When an invalidation aborted the install, this names a file that
     *   has already been deleted, and the caller's re-verification under [epubScratchLock] is what
     *   turns that into an empty result.
     */
    private suspend fun epubScratchCopy(
        metadata: DocumentMetadata,
        fileSource: DocumentFileSource,
    ): Path {
        epubScratchLock.withLock {
            epubScratchPath?.takeIf { epubScratchDocumentId == metadata.id && systemFileSystem().exists(it) }
                ?.let { return it }
        }

        val invalidationsBeforeCopy = epubScratchLock.withLock { epubScratchInvalidationCount }
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "tedd-reader-epub-open-${Random.nextLong().toString(16)}.epub"
        fileSource.copyTo(metadata.location, path)

        return epubScratchLock.withLock {
            epubScratchPath?.takeIf { epubScratchDocumentId == metadata.id && systemFileSystem().exists(it) }
                ?.let { established ->
                    runCatching { systemFileSystem().delete(path) }
                    return@withLock established
                }

            if (epubScratchInvalidationCount != invalidationsBeforeCopy) {
                runCatching { systemFileSystem().delete(path) }
                return@withLock path
            }

            epubScratchPath?.let { previous -> runCatching { systemFileSystem().delete(previous) } }
            clearEmbeddedFontScratchFilesLocked(keepDocumentId = metadata.id)
            epubScratchContainer = null
            deleteAbandonedScratchCopies(keep = path)
            deleteAbandonedEmbeddedFontScratchFiles(keep = epubEmbeddedFontFilesByHref.values.toSet())
            epubScratchDocumentId = metadata.id
            epubScratchPath = path
            path
        }
    }

    /**
     * Returns (or creates and caches) the [EpubImportContainer] for [documentId]'s scratch copy at
     * [path], used by progressive import to iterate spine items without re-parsing the OPF each batch.
     *
     * The cached container is returned immediately when it matches. On a cache miss, the container is
     * built outside [epubScratchLock] to avoid holding the mutex during OPF/manifest parsing, which
     * can be significant for EPUBs with thousands of spine items. A document-ID and path re-verification
     * guards both sides:
     *
     * - Before opening the ZIP: [epubScratchDocumentId] and [epubScratchPath] must still match
     *   [documentId] and [path], confirming the scratch file has not been deleted or replaced by a
     *   concurrent [invalidateCaches].
     * - After building the container: the same check decides whether the result is worth caching
     *   (a concurrent invalidation that ran during parsing makes the container stale).
     *
     * Returns null both when no OPF is found (non-progressive fallback) and when the scratch copy
     * was invalidated mid-flight — the caller ([importNextSections]) treats both as "nothing more to
     * import progressively" and completes the document via [finishNonProgressiveEpubImport].
     *
     * @param documentId The document whose container to open or reuse.
     * @param path The scratch copy path that [epubScratchCopy] returned for this document.
     * @param title Fallback title for the container when the OPF has none.
     * @return The container, or null when the OPF is missing or the scratch was invalidated.
     */
    private suspend fun openEpubScratchContainer(
        documentId: DocumentId,
        path: Path,
        title: String,
    ): EpubImportContainer? {
        epubScratchLock.withLock {
            if (epubScratchDocumentId == documentId && epubScratchPath == path) {
                epubScratchContainer?.let { return it }
            }
            if (epubScratchDocumentId != documentId || epubScratchPath != path) return null
        }
        val zip = runCatching { systemFileSystem().openZip(path) }.getOrNull() ?: return null
        val container = openEpubImportContainer(zip, title)
        epubScratchLock.withLock {
            if (epubScratchDocumentId == documentId && epubScratchPath == path) {
                epubScratchContainer = container
            }
        }
        return container
    }

    private suspend fun clearEpubScratchContainer(documentId: DocumentId) {
        epubScratchLock.withLock {
            if (epubScratchDocumentId == documentId) epubScratchContainer = null
        }
    }

    /**
     * The [ComicArchive] for [metadata]'s CBZ, made once and reused for every later page/cover request
     * of the same document. Must be called while [cbzScratchLock] is held so creation, use, and
     * replacement stay serialised — the reason a page-window request can never read a scratch file that
     * a document switch or an invalidation is deleting.
     *
     * A different document (or a scratch copy that has vanished from disk) replaces both the copy and
     * the open archive: the previous scratch file is deleted, a fresh copy is streamed via
     * [DocumentFileSource.copyTo], [deleteAbandonedComicScratchCopies] sweeps any copy an earlier
     * process left behind (its path is lost when the process dies, but the file is not), and a new
     * archive is opened over the fresh copy. The same document reuses the held copy and archive as-is.
     *
     * @param metadata The CBZ whose archive to open; a copy already held for the same id is reused when
     *   it still exists on disk.
     * @param fileSource Where to copy the original file bytes from when a fresh copy is needed.
     * @return The reusable [ComicArchive] for [metadata].
     */
    private suspend fun cbzArchiveLocked(
        metadata: DocumentMetadata,
        fileSource: DocumentFileSource,
    ): ComicArchive {
        cbzArchive?.takeIf { cbzScratchDocumentId == metadata.id && cbzScratchPath?.let(systemFileSystem()::exists) == true }
            ?.let { return it }

        cbzScratchPath?.let { previous -> runCatching { systemFileSystem().delete(previous) } }
        cbzArchive = null
        cbzScratchDocumentId = null
        cbzScratchPath = null
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "$ComicScratchCopyPrefix${Random.nextLong().toString(16)}.cbz"
        deleteAbandonedComicScratchCopies(keep = path)
        return try {
            fileSource.copyTo(metadata.location, path)
            val archive = comicBookDocumentParser.openArchive(path)
            cbzScratchDocumentId = metadata.id
            cbzScratchPath = path
            cbzArchive = archive
            archive
        } catch (throwable: Throwable) {
            runCatching { systemFileSystem().delete(path) }
            throw throwable
        }
    }

    private fun clearEmbeddedFontScratchFilesLocked(keepDocumentId: DocumentId? = null) {
        if (keepDocumentId != null && epubScratchDocumentId == keepDocumentId) return
        epubEmbeddedFontFilesByHref.values.forEach { path -> runCatching { systemFileSystem().delete(path) } }
        epubEmbeddedFontFilesByHref.clear()
    }

    private fun streamEmbeddedFontScratchFile(
        zip: FileSystem,
        href: String,
    ): Path? {
        val suffix = href.substringAfterLast('/', missingDelimiterValue = href)
            .takeIf(String::isNotBlank)
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?: "font.bin"
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "$EmbeddedFontScratchPrefix${Random.nextLong().toString(16)}-$suffix"
        val written = runCatching {
            val source = zip.source(href.toPath()).buffer()
            try {
                val sink = systemFileSystem().sink(path).buffer()
                try {
                    var totalBytes = 0L
                    while (true) {
                        val read = source.read(sink.buffer, 8_192)
                        if (read == -1L) break
                        totalBytes += read
                        if (totalBytes > MAX_EPUB_FONT_BYTES) throw IllegalStateException("Embedded font too large: $href")
                        sink.emitCompleteSegments()
                    }
                    sink.flush()
                } finally {
                    sink.close()
                }
            } finally {
                source.close()
            }
        }.isSuccess
        if (!written) {
            runCatching { systemFileSystem().delete(path) }
            return null
        }
        return path
    }

    /**
     * Loads every stored section of [documentId], minus each section's block JSON — [SectionBlocksCache]
     * fetches that back, only for the sections something actually asks for. `blocksJson` is deliberately
     * excluded from this row (see [SearchIndexDao.getDocumentSectionsWithoutBlocks]): on a big book it
     * dwarfs every other column combined, and opening used to pull all of it into memory as strings
     * before a single page was built.
     *
     * @param documentId The document to load stored sections for.
     * @return The stored sections, their on-demand block cache, and the document-level title/navigation/
     *   parser-version carried on section 0.
     */
    private suspend fun getStoredSections(documentId: DocumentId): StoredReaderDocument {
        val readStarted = TimeSource.Monotonic.markNow()
        val entries = searchIndexDao.getDocumentSectionsWithoutBlocks(documentId.value)
        val readMs = readStarted.elapsedNow().inWholeMilliseconds
        logger.d {
            "stored sections: ${entries.size} rows read in $readMs ms, ${entries.sumOf { it.text.length }} chars"
        }
        return StoredReaderDocument(
            sections = entries.map { entry ->
                ReaderSection(
                    index = entry.sectionIndex,
                    text = entry.text,
                    range = TextRange(entry.startOffset, entry.endOffset),
                    title = entry.sectionTitle,
                )
            },
            sectionBlocks = SectionBlocksCache(documentId, entries.map { it.sectionIndex }, searchIndexDao, ::decodeBlocks),
            title = entries.firstNotNullOfOrNull { it.documentTitle },
            navigationJson = entries.firstOrNull()?.navigationJson.orEmpty(),
            parserVersion = entries.firstOrNull()?.parserVersion ?: CurrentReaderParserVersion,
        )
    }

    /**
     * Re-reads [metadata]'s file from scratch and re-persists it as a TXT document — the repair
     * [loadReaderDocument] falls back to when the stored sections are empty or contain broken text (see
     * [hasBrokenText]).
     *
     * @param metadata The shelf entry to repair; its location is where the file is re-read from.
     * @return The freshly parsed document, or null when [documentFileSource] is unavailable or the
     *   re-read/parse fails.
     */
    private suspend fun repairTxtDocument(metadata: DocumentMetadata): ReaderDocument? {
        val fileSource = documentFileSource ?: return null
        return suspendRunCatching {
            val document = txtDocumentParser.parse(
                id = metadata.id,
                title = metadata.location.displayName,
                text = TxtTextDecoder.decode(fileSource.readBytes(metadata.location)),
            )
            persistParsedDocument(
                metadata = metadata.copy(
                    format = document.format,
                    pageCount = document.pageCount,
                    characterCount = document.characterCount,
                    wordCount = document.wordCount,
                ),
                document = document,
            )
            document
        }.getOrNull()
    }

    /**
     * Re-reads a book whose stored text an older parser wrote (see CurrentReaderParserVersion) by
     * handing it to the very same phased import a newly picked EPUB gets: the cover and first chapter
     * are parsed and committed, that is what the caller is given, and the rest of the spine is left for
     * [importNextSections] to append in the background exactly as it does after a fresh import.
     *
     * This used to read the whole file into memory and parse every chapter before the reader was
     * allowed to draw anything — 20-40s of nothing, on the next open of a book the reader had already
     * been reading, which is what kept the parser version pinned at 1 and every improvement in the
     * parsers out of the hands of books already on the shelf.
     *
     * [DocumentImportSource] with no bytes is what selects the phased route (see [importDocument]), and
     * carrying the existing [metadata] through as the "existing document" is what keeps the shelf entry
     * the reader recognises: when it was added, whether it is a favourite, which folder it sits in. A
     * repair is not a fresh import, so the `importedAtEpochMillis` passed down is the reader's own
     * `lastOpenedAtEpochMillis` history and not this moment; only a document that somehow never recorded
     * one falls back to now.
     *
     * @param metadata The shelf entry to repair; its location is where the file is re-read from.
     * @return The freshly imported (phase-0) document, or null when [documentFileSource] is unavailable
     *   or the re-read/parse fails.
     */
    private suspend fun repairEpubDocument(metadata: DocumentMetadata): ReaderDocument? {
        val fileSource = documentFileSource ?: return null
        return suspendRunCatching {
            importEpubPhase0(
                id = metadata.id,
                source = DocumentImportSource(location = metadata.location, bytes = null),
                existingDocument = metadata,
                importedAtEpochMillis = metadata.lastOpenedAtEpochMillis
                    ?: Clock.System.now().toEpochMilliseconds(),
                fileSource = fileSource,
            )
        }.getOrNull()
    }

    /**
     * The EPUB import path when the caller already has the whole file in memory — a synchronous full
     * parse, exactly what EPUB import did before progressive import existed. There is no streamed-
     * import cost left to avoid once the bytes are already in hand, so nothing is gained by phasing it;
     * [persistParsedDocument]'s default `importCompletedAtEpochMillis` (now) is correct as-is because
     * this always finishes the whole book in one call.
     *
     * @param id The id to import as.
     * @param source The import source; its location is used for the display title and the persisted
     *   [DocumentMetadata.location].
     * @param existingDocument The shelf entry already recorded for [id], if any — its `addedAtEpochMillis`,
     *   favourite state, and folder are carried forward.
     * @param importedAtEpochMillis When this import happened.
     * @param bytes The whole EPUB file, already in memory.
     * @return The fully parsed document.
     */
    private suspend fun importEpubFullyFromBytes(
        id: DocumentId,
        source: DocumentImportSource,
        existingDocument: DocumentMetadata?,
        importedAtEpochMillis: Long,
        bytes: ByteArray,
    ): ReaderDocument {
        val parsed = epubDocumentParser.parseWithCover(id = id, title = source.location.displayName, bytes = bytes)
        persistParsedDocument(
            metadata = DocumentMetadata(
                id = id,
                location = source.location,
                format = parsed.document.format,
                addedAtEpochMillis = existingDocument?.addedAtEpochMillis ?: importedAtEpochMillis,
                lastOpenedAtEpochMillis = importedAtEpochMillis,
                pageCount = parsed.document.pageCount,
                characterCount = parsed.document.characterCount,
                wordCount = parsed.document.wordCount,
                isBookmarked = existingDocument?.isBookmarked ?: false,
                folderId = existingDocument?.folderId,
                folderName = existingDocument?.folderName,
            ),
            document = parsed.document,
            coverBytes = parsed.coverBytes,
        )
        return parsed.document
    }

    /**
     * Phase 0/1 of a progressive EPUB import (see [importNextSections] for the batches that follow, and
     * [finishEpubImport] for the final one): stream the picked file into app-private storage once (via
     * [epubScratchCopy]), parse just the container/OPF and settle the cover decision — deciding it any
     * later shifts every offset after it — parse until at least [InitialReadAheadMinimumContentChars]
     * readable non-whitespace/non-object characters are buffered, while skipping null spine items and
     * capping the read-ahead at [InitialReadAheadMaxSpineItems] spine slots, and commit the document row
     * plus those initial sections. [DocumentMetadata.characterCount]
     * stays null and `documents.importCompletedAtEpochMillis` stays unset unless the whole spine turns out
     * to fit in this bounded read-ahead, so a book that never
     * finishes importing reads as unfinished rather than wrong. Only reached with `bytes=null` (see
     * [importDocument]) — there is always a real [fileSource] to stream from.
     *
     * When the EPUB has no OPF at all (`container == null`), the existing fallback-chapters parse
     * ([EpubDocumentParser.parseWithCover]) already reads and lays out every chapter it can find directly
     * from this same scratch copy, so there is no spine left to stream and nothing progressive about this
     * branch — it is treated as fully imported in one call, same as any other format.
     *
     * Otherwise this parses only the cover section (if any) and enough readable spine sections to reach
     * [InitialReadAheadMinimumContentChars] real text (bounded by
     * [InitialReadAheadMaxSpineItems] spine slots), which is exactly what a batch from
     * [importNextSections] does for its own slice of the spine later — except this first call also
     * settles the cover decision and the document's initial title/navigation stand-in. A spine fully
     * consumed by that bounded read-ahead
     * already covered the whole book — no different, for what gets stored, than any other format that
     * always imports in one shot; `isFullyImported` captures exactly that.
     *
     * @param id The id to import as.
     * @param source The import source; its location is used for the display title and the persisted
     *   [DocumentMetadata.location].
     * @param existingDocument The shelf entry already recorded for [id], if any — its `addedAtEpochMillis`,
     *   favourite state, and folder are carried forward.
     * @param importedAtEpochMillis When this import (or repair) happened.
     * @param fileSource Where to stream the original EPUB bytes from.
     * @return The document as known after this first phase — just the cover and/or first bounded readable
     *   spine sections unless the whole book fit in them, in which case it is the complete document.
     */
    private suspend fun importEpubPhase0(
        id: DocumentId,
        source: DocumentImportSource,
        existingDocument: DocumentMetadata?,
        importedAtEpochMillis: Long,
        fileSource: DocumentFileSource,
    ): ReaderDocument {
        val title = source.location.displayName
        val scratchMetadata = DocumentMetadata(
            id = id,
            location = source.location,
            format = DocumentFormat.EPUB,
            addedAtEpochMillis = existingDocument?.addedAtEpochMillis ?: importedAtEpochMillis,
        )
        val path = epubScratchCopy(scratchMetadata, fileSource)
        val container = openEpubScratchContainer(id, path, title)

        val isFullyImported: Boolean
        val document: ReaderDocument
        val coverBytes: ByteArray?
        var phase0NextSpinePosition = 0
        var phase0SectionPaths: Map<Int, String> = emptyMap()
        if (container == null) {
            val parsed = epubDocumentParser.parseWithCover(id = id, title = title, path = path, fileSystem = systemFileSystem())
            document = parsed.document
            coverBytes = parsed.coverBytes
            isFullyImported = true
        } else {
            val sections = mutableListOf<ReaderSection>()
            val blocks = mutableListOf<ReaderBlock>()
            val sectionPathByIndex = mutableMapOf<Int, String>()
            val coverSectionIndex = 0.takeIf { container.coverDecision.hasCoverSection }
            buildEpubCoverSection(container.coverDecision, container.documentTitle)?.let { cover ->
                sections += cover.section
                blocks += cover.blocks
                container.coverDecision.coverHref?.let { sectionPathByIndex[cover.section.index] = it }
            }
            var spinePosition = 0
            var bufferedContentChars = 0
            var spineItemsReadAhead = 0
            while (
                spinePosition < container.linearSpineItems.size &&
                spineItemsReadAhead < InitialReadAheadMaxSpineItems &&
                bufferedContentChars < InitialReadAheadMinimumContentChars
            ) {
                parseEpubSpineItem(
                    container = container,
                    spinePosition = spinePosition,
                    sectionIndex = sections.size,
                    baseOffset = sections.lastOrNull()?.let { it.range.end + SectionSeparatorLength } ?: 0L,
                )?.let { parsed ->
                    sections += parsed.section
                    blocks += parsed.blocks
                    sectionPathByIndex[parsed.section.index] = container.linearSpineItems[spinePosition].path
                    bufferedContentChars += parsed.section.text.count { char ->
                        !char.isWhitespace() && char != ReaderObjectReplacementChar
                    }
                }
                spinePosition += 1
                spineItemsReadAhead += 1
            }
            phase0NextSpinePosition = spinePosition
            fillIntrinsicImageSizes(blocks, container.zip, container.coverDecision.coverHref, container.coverDecision.coverBytes)
            isFullyImported = spinePosition >= container.linearSpineItems.size
            val navigation = if (isFullyImported) {
                resolveEpubNavigationAtCompletion(
                    container = container,
                    sectionPathByIndex = sectionPathByIndex,
                    coverSectionIndex = coverSectionIndex,
                    firstReadableContentSectionIndex = sections.firstOrNull {
                        it.index != coverSectionIndex && it.text.isNotBlank()
                    }?.index,
                )
            } else {
                ReaderNavigation()
            }
            val titledSections = if (navigation.items.isEmpty()) {
                sections
            } else {
                val titlesByIndex = navigation.items
                    .asSequence()
                    .filter { it.offset == 0L }
                    .associate { it.spineIndex to it.title }
                sections.map { section ->
                    titlesByIndex[section.index]?.let { section.copy(title = it) } ?: section
                }
            }
            document = ReaderDocument(
                id = id,
                format = DocumentFormat.EPUB,
                title = container.documentTitle,
                sections = titledSections,
                blocks = blocks,
                navigation = navigation,
            )
            phase0SectionPaths = sectionPathByIndex
            coverBytes = container.coverDecision.coverBytes
        }

        persistParsedDocument(
            metadata = DocumentMetadata(
                id = id,
                location = source.location,
                format = DocumentFormat.EPUB,
                addedAtEpochMillis = existingDocument?.addedAtEpochMillis ?: importedAtEpochMillis,
                lastOpenedAtEpochMillis = importedAtEpochMillis,
                pageCount = document.pageCount.takeIf { isFullyImported },
                characterCount = document.characterCount,
                wordCount = document.wordCount,
                isBookmarked = existingDocument?.isBookmarked ?: false,
                folderId = existingDocument?.folderId,
                folderName = existingDocument?.folderName,
            ),
            document = document,
            coverBytes = coverBytes,
            importCompletedAtEpochMillis = if (isFullyImported) Clock.System.now().toEpochMilliseconds() else null,
            keepScratchCopy = true,
            embeddedFontHrefsJson = json.encodeToString(extractFontHrefs(document.blocks)),
            sectionSourcePaths = phase0SectionPaths,
        )
        if (isFullyImported) {
            clearEpubScratchContainer(id)
        } else {
            rememberNextSpineCursor(id, phase0NextSpinePosition)
            rememberSectionPaths(id, phase0SectionPaths)
        }
        return document
    }

    /**
     * Whether [documentId]'s import has fully finished — every EPUB spine item parsed and stored, or any
     * other format's single-shot import already completed.
     *
     * @param documentId The document to check.
     * @return True once `documents.importCompletedAtEpochMillis` is set for this document.
     */
    override suspend fun isImportComplete(documentId: DocumentId): Boolean =
        documentDao.getDocument(documentId.value)?.importCompletedAtEpochMillis != null

    /**
     * The persisted build facts a progressive import extends without rereading completed prefix text.
     *
     * @property characterCount characters in every section stored before the next batch.
     * @property wordCount words in every section stored before the next batch.
     * @property embeddedFontHrefs exact font hrefs referenced by every block stored before the next batch.
     */
    private data class ImportBuildState(
        val characterCount: Long,
        val wordCount: Long,
        val embeddedFontHrefs: Set<String>,
    )

    /**
     * Resolves the accumulators a new import batch starts from. Version-9 imports read them directly from
     * the document row. A document interrupted on an older schema has null accumulators, so that one
     * migration-boundary resume reconstructs them from stored sections and blocks before any new section
     * is added; every later batch returns to the indexed path.
     *
     * @param documentId the progressive EPUB being resumed.
     * @param entity its row before the new batch is stored.
     * @return complete prefix counts and font references for safe append arithmetic.
     */
    private suspend fun resolveImportBuildState(
        documentId: DocumentId,
        entity: DocumentEntity,
    ): ImportBuildState {
        val indexedFonts = entity.embeddedFontHrefsJson?.let { encoded ->
            runCatching { json.decodeFromString<List<String>>(encoded).toSet() }.getOrNull()
        }
        val indexedCharacterCount = entity.characterCount
        val indexedWordCount = entity.wordCount
        if (indexedCharacterCount != null && indexedWordCount != null && indexedFonts != null) {
            return ImportBuildState(indexedCharacterCount, indexedWordCount, indexedFonts)
        }
        val document = getReaderDocument(documentId)
        val fontHrefs = indexedFonts ?: getReferencedEmbeddedFontHrefs(documentId)
        return ImportBuildState(
            characterCount = indexedCharacterCount ?: document?.characterCount ?: 0L,
            wordCount = indexedWordCount ?: document?.wordCount ?: 0L,
            embeddedFontHrefs = fontHrefs,
        )
    }

    /**
     * Completes and stores a measured layout for the document prefix that exists before a new import
     * batch lands. Reusing the live pagination session preserves sections the pane already measured; once
     * this returns, the new batch can append only its own page starts without remeasuring that prefix.
     *
     * @param documentId the document whose current prefix must have a stored layout.
     * @param style the layout style shared with the incoming batch.
     * @param viewportSize the measured viewport shared with the incoming batch.
     * @param viewportDensity density used by estimate-only helpers inside pagination.
     * @param pageBreaker the real text measurer for this style and viewport.
     * @param expectedCharacterCount the exact character count of the current stored prefix.
     */
    private suspend fun ensurePartialLayoutForCurrentPrefix(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        viewportDensity: Float,
        pageBreaker: ReaderPageBreaker,
        expectedCharacterCount: Long,
    ) {
        val layoutKey = style.layoutKey()
        val stored = pageLayoutDao.getPageLayout(
            documentId = documentId.value,
            fontSizeSp = layoutKey.fontSizeSp,
            lineHeightMultiplier = layoutKey.lineHeightMultiplier,
            fontFamilyName = layoutKey.fontFamilyName.orEmpty(),
            viewportWidthPx = viewportSize.widthPx,
            viewportHeightPx = viewportSize.heightPx,
        )
        if (stored?.isPartial == true && stored.characterCount == expectedCharacterCount) return
        if (stored != null) pageLayoutDao.deletePageLayouts(documentId.value)

        getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
            viewportDensity = viewportDensity,
            pageBreaker = pageBreaker,
            anchorOffset = null,
        )
        while (true) {
            val progress = continuePagination(
                documentId = documentId,
                style = style,
                viewportSize = viewportSize,
                viewportDensity = viewportDensity,
                pageBreaker = pageBreaker,
            )
            if (progress.isComplete) return
        }
    }

    /**
     * One batch of a progressive EPUB import: parses up to [count] more spine items starting from where
     * the last batch (or [importEpubPhase0]) left off, stores them, extends the stored page layout in
     * place (via [appendMeasuredPageStarts]) instead of re-measuring the whole book, and — only once the
     * whole spine is finally exhausted — runs [finishEpubImport] to resolve navigation and stamp the
     * document complete. Each batch incrementally persists character/word counts and exact font hrefs;
     * only navigation and section-title resolution wait for completion because a table-of-contents entry
     * can name any spine item.
     *
     * A no-op — reporting already complete — when [documentId] is not on the shelf, its import is already
     * complete, it is not an EPUB, or [documentFileSource] is unavailable. When the EPUB has no OPF at
     * all, [importEpubPhase0]'s fallback-chapters branch already imported everything there was to import
     * in one shot, so the only thing left here is the completion stamp that branch skipped — handled by
     * [finishNonProgressiveEpubImport].
     *
     * In the parsing loop below: a null `parsed` result (a pure-cover skip, or an unreadable item)
     * consumes a spine slot without becoming a section, same as the one-shot loop in [importEpubPhase0].
     * `relativeBlocks` shifts the parsed blocks to be stored section-relative from here on, same as
     * [persistParsedDocument]'s own sections (see `TextPageLayoutEngine.sectionPageRanges`) —
     * [appendMeasuredPageStarts] below now expects that same relative shape, not the absolute one
     * [parseEpubSpineItem] hands back.
     *
     * @param documentId The document to continue importing.
     * @param count How many more spine items to parse in this call.
     * @param style The style to measure any newly imported sections' pages at.
     * @param viewportSize The viewport to measure any newly imported sections' pages at.
     * @param pageBreaker The real page-breaking measurement to extend the stored layout with, or null to
     *   import text without extending any stored layout.
     * @return Whether the import is now complete, and how many sections this call actually imported.
     */
    override suspend fun importNextSections(
        documentId: DocumentId,
        count: Int,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float,
    ): ImportProgress = withContext(Dispatchers.Default) {
        val entity = documentDao.getDocument(documentId.value)
            ?: return@withContext ImportProgress(isComplete = true, sectionsImported = 0)
        val fileSource = documentFileSource
        if (entity.importCompletedAtEpochMillis != null || entity.format != DocumentFormat.EPUB.name || fileSource == null) {
            return@withContext ImportProgress(isComplete = true, sectionsImported = 0)
        }

        val path = epubScratchCopy(entity.toDocumentMetadata(), fileSource)
        val container = openEpubScratchContainer(documentId, path, entity.name)
            ?: return@withContext finishNonProgressiveEpubImport(documentId, entity)
        var buildState = resolveImportBuildState(documentId, entity)
        if (pageBreaker != null) {
            ensurePartialLayoutForCurrentPrefix(
                documentId = documentId,
                style = style,
                viewportSize = viewportSize,
                viewportDensity = viewportDensity,
                pageBreaker = pageBreaker,
                expectedCharacterCount = buildState.characterCount,
            )
        }

        val lastSection = searchIndexDao.getLastSection(documentId.value)
        var sectionIndex = (lastSection?.sectionIndex?.plus(1)) ?: 0
        var offset = lastSection?.endOffset?.plus(SectionSeparatorLength) ?: 0L
        var spinePosition = resolveNextSpineCursor(documentId, container, sectionIndex)

        val newEntries = mutableListOf<SearchIndexEntity>()
        val newSections = mutableListOf<Pair<ReaderSection, List<ReaderBlock>>>()
        val sectionPathByIndex = mutableMapOf<Int, String>()
        var sectionsImported = 0
        while (sectionsImported < count && spinePosition < container.linearSpineItems.size) {
            val parsed = parseEpubSpineItem(container, spinePosition, sectionIndex, offset)
            spinePosition += 1
            if (parsed == null) continue
            val blocks = parsed.blocks.toMutableList()
            fillIntrinsicImageSizes(blocks, container.zip, container.coverDecision.coverHref, container.coverDecision.coverBytes)
            val relativeBlocks = blocks.rebasedBy(parsed.section.range.start)
            val spinePath = container.linearSpineItems[spinePosition - 1].path
            sectionPathByIndex[parsed.section.index] = spinePath
            newEntries += parsed.section.toSearchIndexEntity(
                documentId = documentId,
                blocks = relativeBlocks,
                json = json,
                sourcePath = spinePath,
            )
            newSections += parsed.section to relativeBlocks
            offset = parsed.section.range.end + SectionSeparatorLength
            sectionIndex += 1
            sectionsImported += 1
        }

        if (newEntries.isNotEmpty()) {
            val batchStarted = TimeSource.Monotonic.markNow()
            val expectedExistingCharacterCount = buildState.characterCount
            val batchCharCount = newSections.sumOf { (section, _) -> section.text.length.toLong() }
            val batchWordCount = newSections.sumOf { (section, _) -> section.text.wordCount().toLong() }
            val batchFontHrefs = extractFontHrefs(newSections.flatMap { (_, blocks) -> blocks })
            val mergedFontHrefs = buildState.embeddedFontHrefs + batchFontHrefs
            buildState = ImportBuildState(
                characterCount = expectedExistingCharacterCount + batchCharCount,
                wordCount = buildState.wordCount + batchWordCount,
                embeddedFontHrefs = mergedFontHrefs,
            )
            searchIndexDao.upsertImportBatch(
                documentDao = documentDao,
                entries = newEntries,
                documentId = documentId.value,
                characterCount = buildState.characterCount,
                wordCount = buildState.wordCount,
                embeddedFontHrefsJson = json.encodeToString(mergedFontHrefs.sorted()),
            )
            rememberSectionPaths(documentId, sectionPathByIndex)
            appendMeasuredPageStarts(
                documentId = documentId,
                style = style,
                viewportSize = viewportSize,
                viewportDensity = viewportDensity,
                pageBreaker = pageBreaker,
                newSections = newSections,
                expectedExistingCharacterCount = expectedExistingCharacterCount,
            )
            logger.d {
                "${entity.name.take(12)}: import batch $sectionsImported sections, " +
                    "+$batchCharCount chars, ${buildState.embeddedFontHrefs.size} fonts indexed " +
                    "in ${batchStarted.elapsedNow().inWholeMilliseconds} ms"
            }
        }
        rememberNextSpineCursor(documentId, spinePosition)

        val isComplete = spinePosition >= container.linearSpineItems.size
        if (!isComplete) return@withContext ImportProgress(isComplete = false, sectionsImported = sectionsImported)

        finishEpubImport(documentId, entity, container, buildState)
        ImportProgress(isComplete = true, sectionsImported = sectionsImported)
    }

    /**
     * [TextPageLayoutEngine.pageStartsForSection], run on the one dispatcher a real measurement is
     * allowed on ([ReaderPageMeasureDispatcher]) — the single funnel every measuring call site goes
     * through, so no platform's text-stack threading rule depends on which caller measured. An
     * estimate-only call (null [pageBreaker]) lays out no text and stays on the caller's dispatcher.
     *
     * @param section The content section whose page starts are needed.
     * @param sectionBlocks The section-relative blocks carrying its measured styling.
     * @param style The reader typography used for measurement.
     * @param viewportSize The pane dimensions used for line breaking.
     * @param pageBreaker The real text measurer, or null for estimate-only starts.
     * @param viewportDensity The pane density used by the text layout engine.
     * @return Absolute page starts plus whether a real breaker produced them, so persistence can
     * reject bounded estimates without discarding the pages used for the current open.
     */
    private suspend fun measuredPageStartsForSection(
        section: ReaderSection,
        sectionBlocks: List<ReaderBlock>,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float = 1f,
    ): SectionPageStarts = if (pageBreaker == null) {
        textPageLayoutEngine.pageStartsForSection(section, sectionBlocks, style, viewportSize, null, viewportDensity)
    } else {
        withContext(ReaderPageMeasureDispatcher) {
            textPageLayoutEngine.pageStartsForSection(section, sectionBlocks, style, viewportSize, pageBreaker, viewportDensity)
        }
    }

    /**
     * Extends the version-matched partial layout with only [newSections]. A missing, complete, or
     * mismatched row is deleted and replaced by measuring the now-current prefix once; a null
     * [pageBreaker] deletes partial rows because the newly stored text cannot be appended accurately.
     * This keeps every persisted row aligned with the exact prefix its `characterCount` names.
     *
     * @param documentId The progressive EPUB whose partial row is being extended.
     * @param style The style the stored row and new measurements share.
     * @param viewportSize The viewport the stored row and new measurements share.
     * @param viewportDensity The density used by the page breaker.
     * @param pageBreaker The real text measurer, or null when no accurate append is possible.
     * @param newSections The newly persisted sections and their section-relative blocks.
     * @param expectedExistingCharacterCount The prefix version that must already be in the row before
     *   these sections can be appended.
     */
    private suspend fun appendMeasuredPageStarts(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        viewportDensity: Float,
        pageBreaker: ReaderPageBreaker?,
        newSections: List<Pair<ReaderSection, List<ReaderBlock>>>,
        expectedExistingCharacterCount: Long,
    ) {
        if (newSections.isEmpty()) {
            if (pageBreaker == null) {
                pageLayoutDao.deletePartialPageLayouts(documentId.value)
            }
            return
        }
        if (pageBreaker == null) {
            pageLayoutDao.deletePartialPageLayouts(documentId.value)
            return
        }
        val layoutKey = style.layoutKey()
        val stored = pageLayoutDao.getPageLayout(
            documentId = documentId.value,
            fontSizeSp = layoutKey.fontSizeSp,
            lineHeightMultiplier = layoutKey.lineHeightMultiplier,
            fontFamilyName = layoutKey.fontFamilyName.orEmpty(),
            viewportWidthPx = viewportSize.widthPx,
            viewportHeightPx = viewportSize.heightPx,
        )
        if (stored != null && stored.isPartial && stored.characterCount == expectedExistingCharacterCount) {
            val existingStarts = stored.pageStartsBlob?.let(::decodePageStartsBlob) ?: run {
                pageLayoutDao.deletePageLayouts(documentId.value)
                return
            }
            val addedCharacterCount = newSections.sumOf { (section, _) -> section.text.length.toLong() }
            val appendedResults = newSections.map { (section, blocks) ->
                measuredPageStartsForSection(section, blocks, style, viewportSize, pageBreaker, viewportDensity)
            }
            if (appendedResults.any { result -> !result.isMeasured } ||
                appendedResults.all { result -> result.offsets.isEmpty() }
            ) {
                pageLayoutDao.deletePageLayouts(documentId.value)
                return
            }
            pageLayoutDao.upsertPageLayout(
                stored.copy(
                    characterCount = stored.characterCount + addedCharacterCount,
                    pageStartsBlob = encodePageStartsBlob(
                        concatPageStarts(existingStarts, appendedResults.map { result -> result.offsets }),
                    ),
                    writtenAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                    isPartial = true,
                ),
            )
        } else {
            pageLayoutDao.deletePageLayouts(documentId.value)
            invalidateDocumentCache(documentId)
            ensurePartialLayoutForCurrentPrefix(
                documentId = documentId,
                style = style,
                viewportSize = viewportSize,
                viewportDensity = viewportDensity,
                pageBreaker = pageBreaker,
                expectedCharacterCount = expectedExistingCharacterCount +
                    newSections.sumOf { (section, _) -> section.text.length.toLong() },
            )
        }
    }

    /**
     * Which spine path each already-stored section index came from, replaying spine parsing so pure-cover,
     * missing, and unreadable items are skipped exactly as they were during import.
     *
     * @param container The EPUB's parsed container, for its linear spine item paths.
     * @param coverSectionIndex The section index of the synthetic cover section, or null when this book
     *   has none.
     * @param storedSectionCount How many sections are stored for this document.
     * @return Every stored section index mapped to its source path: the cover section (if present) maps
     *   to the cover's own href rather than a spine item, and every other section maps to the
     *   archive-relative path of the linear spine item it came from.
     */
    private fun buildSectionPathByIndex(
        container: EpubImportContainer,
        coverSectionIndex: Int?,
        storedSectionCount: Int,
    ): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        val coverHref = container.coverDecision.coverHref
        if (coverSectionIndex != null && coverHref != null) map[coverSectionIndex] = coverHref
        var sectionIndex = if (coverSectionIndex != null) 1 else 0
        var offset = buildEpubCoverSection(container.coverDecision, container.documentTitle)
            ?.section
            ?.range
            ?.end
            ?.plus(SectionSeparatorLength)
            ?: 0L
        var spinePosition = 0
        while (sectionIndex < storedSectionCount && spinePosition < container.linearSpineItems.size) {
            val parsed = parseEpubSpineItem(container, spinePosition, sectionIndex, offset)
            spinePosition += 1
            if (parsed == null) continue
            map[sectionIndex] = container.linearSpineItems[spinePosition - 1].path
            sectionIndex += 1
            offset = parsed.section.range.end + SectionSeparatorLength
        }
        return map
    }

    private fun consumedSpinePositionForStoredSections(
        container: EpubImportContainer,
        storedSectionCount: Int,
    ): Int {
        var sectionIndex = if (container.coverDecision.hasCoverSection) 1 else 0
        var offset = buildEpubCoverSection(container.coverDecision, container.documentTitle)
            ?.section
            ?.range
            ?.end
            ?.plus(SectionSeparatorLength)
            ?: 0L
        var spinePosition = 0
        while (sectionIndex < storedSectionCount && spinePosition < container.linearSpineItems.size) {
            val parsed = parseEpubSpineItem(container, spinePosition, sectionIndex, offset)
            spinePosition += 1
            if (parsed == null) continue
            sectionIndex += 1
            offset = parsed.section.range.end + SectionSeparatorLength
        }
        return spinePosition
    }

    private suspend fun resolveNextSpineCursor(
        documentId: DocumentId,
        container: EpubImportContainer,
        storedSectionCount: Int,
    ): Int {
        epubImportCursorLock.withLock {
            epubNextSpineCursorByDocumentId[documentId]?.let { return it }
        }
        val replayed = consumedSpinePositionForStoredSections(container, storedSectionCount)
        return epubImportCursorLock.withLock {
            epubNextSpineCursorByDocumentId.getOrPut(documentId) { replayed }
        }
    }

    private suspend fun rememberNextSpineCursor(documentId: DocumentId, spinePosition: Int) {
        epubImportCursorLock.withLock {
            epubNextSpineCursorByDocumentId[documentId] = spinePosition
        }
    }

    private suspend fun rememberSectionPaths(documentId: DocumentId, sectionPathByIndex: Map<Int, String>) {
        if (sectionPathByIndex.isEmpty()) return
        epubImportCursorLock.withLock {
            val existing = epubSectionPathByIndexByDocumentId.getOrPut(documentId) { mutableMapOf() }
            existing.putAll(sectionPathByIndex)
        }
    }

    /**
     * The last step of a progressive EPUB import, run by [importNextSections] only once the final batch
     * exhausts the spine: resolve navigation now that every section is known, retitle whichever sections
     * the table of contents names, stamp the accumulated counts, promote a matching partial layout, and
     * mark the document complete. Counts and font hrefs were already accumulated per batch; completion
     * therefore avoids the former whole-section text query while navigation still waits for the full
     * spine because an entry can point at any section.
     *
     * [invalidateDocumentCache] runs before the completion stamp is written, not after: writing the
     * stamp first left a window where `documents.importCompletedAtEpochMillis` was already visible to
     * [isImportComplete] while [getReaderDocument] still served the pre-completion cached document — an
     * empty table of contents a reader caught in that window would see stick until the next app
     * relaunch, since nothing would invalidate the cache again afterwards. Invalidating first closes that
     * window: by the time the stamp is visible, [getReaderDocument] can no longer answer from a cache
     * entry that predates the navigation resolved just above.
     *
     * @param documentId The document being finished.
     * @param entity The document's current stored row, copied forward with the rolled-up counts and the
     *   completion stamp.
     * @param container The EPUB's parsed container, for resolving navigation against.
     * @param buildState The final batch-inclusive counts and exact embedded-font href set.
     */
    private suspend fun finishEpubImport(
        documentId: DocumentId,
        entity: DocumentEntity,
        container: EpubImportContainer,
        buildState: ImportBuildState,
    ) {
        val finishStarted = TimeSource.Monotonic.markNow()
        val coverSectionIndex = 0.takeIf { container.coverDecision.hasCoverSection }
        val sectionCount = searchIndexDao.getSectionCount(documentId.value)
        val firstReadableContentSectionIndex = searchIndexDao.getFirstReadableContentSectionIndex(
            documentId = documentId.value,
            excludeSectionIndex = coverSectionIndex ?: -1,
        )
        val cachedSectionPaths = epubImportCursorLock.withLock {
            epubSectionPathByIndexByDocumentId.remove(documentId)?.toMap()
        }
        val sectionPathByIndex: Map<Int, String> = if (cachedSectionPaths != null && cachedSectionPaths.size >= sectionCount) {
            cachedSectionPaths
        } else {
            val storedPaths = searchIndexDao.getSectionSourcePaths(documentId.value)
            val hasAllPaths = storedPaths.all { it.sourcePath != null }
            if (hasAllPaths) {
                storedPaths.associate { it.sectionIndex to it.sourcePath.orEmpty() }
            } else {
                buildSectionPathByIndex(container, coverSectionIndex, sectionCount)
            }
        }
        val navigation = resolveEpubNavigationAtCompletion(
            container = container,
            sectionPathByIndex = sectionPathByIndex,
            coverSectionIndex = coverSectionIndex,
            firstReadableContentSectionIndex = firstReadableContentSectionIndex,
        )
        val titleUpdates = navigation.items.filter { it.offset == 0L }
        searchIndexDao.updateCompletedNavigation(
            documentId = documentId.value,
            sectionIndex = 0,
            documentTitle = container.documentTitle,
            navigationJson = json.encodeToString(navigation),
            titleUpdates = titleUpdates.map { item -> SectionTitleUpdate(item.spineIndex, item.title) },
        )
        invalidateDocumentCache(documentId)
        val finalCharCount = buildState.characterCount
        val finalWordCount = buildState.wordCount
        documentDao.updateCountsAndMarkComplete(
            documentId = documentId.value,
            characterCount = finalCharCount,
            wordCount = finalWordCount,
            importCompletedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
        )
        pageLayoutDao.promotePartialLayouts(documentId.value, finalCharCount)
        logger.d {
            "${entity.name.take(12)}: finishEpubImport completed in " +
                "${finishStarted.elapsedNow().inWholeMilliseconds} ms, " +
                "$sectionCount sections, ${titleUpdates.size} title updates, " +
                "no full section text query"
        }
        epubImportCursorLock.withLock {
            epubNextSpineCursorByDocumentId.remove(documentId)
            epubSectionPathByIndexByDocumentId.remove(documentId)
        }
        clearEpubScratchContainer(documentId)
    }

    /**
     * Defensive fallback for [importNextSections]: reached only if a document's import somehow never got
     * stamped complete even though its EPUB has no OPF at all, a case [importEpubPhase0] already
     * finishes in one shot. Resolves or legacy-backfills its persisted counts and stamps completion,
     * the same as [finishEpubImport]'s final step, but with no navigation to resolve since there was never
     * a spine to walk.
     *
     * Same as [finishEpubImport], [invalidateDocumentCache] runs before the completion stamp is written,
     * not after — otherwise a reader landing between the two statements would see [isImportComplete]
     * answer true while [getReaderDocument] still served the pre-completion cached document.
     *
     * @param documentId The document being finished.
     * @param entity The document's current stored row, copied forward with the rolled-up counts and the
     *   completion stamp.
     * @return Completion progress with `sectionsImported = 0`, since this call imports nothing new — it
     *   only stamps a book that was already fully imported.
     */
    private suspend fun finishNonProgressiveEpubImport(documentId: DocumentId, entity: DocumentEntity): ImportProgress {
        val buildState = resolveImportBuildState(documentId, entity)
        invalidateDocumentCache(documentId)
        val finalCharCount = buildState.characterCount
        val finalWordCount = buildState.wordCount
        documentDao.updateCountsAndMarkComplete(
            documentId = documentId.value,
            characterCount = finalCharCount,
            wordCount = finalWordCount,
            importCompletedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
        )
        epubImportCursorLock.withLock {
            epubNextSpineCursorByDocumentId.remove(documentId)
            epubSectionPathByIndexByDocumentId.remove(documentId)
        }
        clearEpubScratchContainer(documentId)
        return ImportProgress(isComplete = true, sectionsImported = 0)
    }

    /**
     * Replaces every stored trace of [metadata]'s document with [document]'s sections, invalidating all
     * caches both before and after the rewrite so nothing ever reads the old *or* the torn content out
     * of them.
     *
     * Sections are stored with their blocks shifted relative to each section's own start, not as the
     * absolute offsets pagination addresses a page with — see `TextPageLayoutEngine.sectionPageRanges`,
     * which used to redo this exact shift, for every block and every span, on every pagination pass
     * instead of once here.
     *
     * [importCompletedAtEpochMillis] defaults to now because every existing caller parses and stores the
     * whole document in one shot, so "complete right now" is the correct default for all of them —
     * TXT/PDF/CBZ/IMAGE import, and an EPUB repair, which re-parses the entire book synchronously even for
     * a document whose *original* import never finished. Only [importEpubPhase0] overrides this with
     * null: it persists just the first section(s) and leaves the rest to [importNextSections]. Bypassing
     * the public [upsertDocument] (which otherwise preserves whatever was already stored, for an ordinary
     * metadata edit like toggling a favourite) is what lets this decide the value outright instead of
     * inheriting it.
     *
     * The leading [invalidateCaches] call only protects against a load that started before this function
     * runs and is still in flight while it runs — the same [documentCacheGeneration] hole [getReaderDocument]
     * closes for itself. It does nothing for a load that starts *after* that call: `documentDao.upsertDocument`
     * makes the row (and, with it, [isImportComplete]) visible immediately, but `searchIndexDao.deleteSearchIndex`
     * then empties every section row, and they are not written back until `searchIndexDao.upsertSearchIndex`
     * finishes below. A [getReaderDocument] load that starts anywhere in that window reads zero sections —
     * for an EPUB repair racing this same function, a blank navigation too — and, unlike [finishEpubImport],
     * that torn read is not merely uncached: nothing invalidates the cache again afterward, so if that load's
     * own generation check happens to still match (no other invalidation landed in between), it publishes the
     * empty snapshot and nothing would ever clear it — the exact bug this whole cache-generation mechanism
     * exists to close, reopened through the writer instead of the reader.
     *
     * The trailing [invalidateDocumentCache] call closes that second hole. It runs unconditionally, after
     * every row this function touches has been written, so it is never skipped by an early return the way a
     * `finally` could be forgotten to be. Any load whose own publish raced ahead of it — landing between the
     * empty read above and this call — named [metadata]'s id when it published, so this call's own
     * `cachedDocumentId == documentId` check (see [invalidateDocumentCache]) still matches and clears it out
     * one statement later; the earlier read is not corrected, but it is guaranteed to never survive as the
     * cached answer. A load that starts after this call simply sees the freshly written rows and needs no
     * guard at all. This is the mirror image of [finishEpubImport]'s ordering, not the same fix repeated:
     * there, invalidating first is what is safe, because every field [invalidateDocumentCache] is protecting
     * is written before that invalidation runs (see that function's own doc); here, this function *is* the
     * rewrite, so nothing about it is safe until the invalidation also runs after it.
     *
     * When [coverBytes] is supplied, it is written to the cover file now, while the caller already has
     * it decoded — sparing every later open the whole-file read [getDocumentCover] would otherwise repeat
     * (see this class's own doc). The cover file sits outside [ReaderDocument] entirely, so it does not
     * change which side of the trailing invalidation it happens to land on.
     *
     * @param metadata The metadata row to write.
     * @param document The parsed document whose sections/blocks/navigation to store.
     * @param coverBytes The cover image bytes to write alongside, if the caller already has them decoded.
     * @param importCompletedAtEpochMillis The completion stamp to write, or null to leave the import
     *   marked unfinished (see above).
     * @param keepScratchCopy True to preserve the in-memory EPUB scratch binding for this document while
     *   rewriting storage; phase-0 uses this so continuation can keep reading the same copied file.
     * @param embeddedFontHrefsJson The exact referenced-font index encoded for direct lookup, or null for
     *   formats and legacy writes that do not supply one.
     * @param sectionSourcePaths The archive-relative source path for each EPUB section index, used to
     *   resolve navigation without replaying every spine item at completion.
     */
    private suspend fun persistParsedDocument(
        metadata: DocumentMetadata,
        document: ReaderDocument,
        coverBytes: ByteArray? = null,
        importCompletedAtEpochMillis: Long? = Clock.System.now().toEpochMilliseconds(),
        keepScratchCopy: Boolean = false,
        embeddedFontHrefsJson: String? = null,
        sectionSourcePaths: Map<Int, String> = emptyMap(),
    ) {
        invalidateCaches(metadata.id, keepScratchCopy = keepScratchCopy)
        documentDao.upsertDocument(
            metadata.toDocumentEntity().copy(
                importCompletedAtEpochMillis = importCompletedAtEpochMillis,
                embeddedFontHrefsJson = embeddedFontHrefsJson,
            ),
        )
        searchIndexDao.deleteSearchIndex(metadata.id.value)
        if (document.sections.isNotEmpty()) {
            val blocksPerSection = distributeBlocksIntoSections(
                sections = document.sections.map { it.range },
                blocks = document.blocks,
            )
            val firstSectionIndex = document.sections.first().index
            searchIndexDao.upsertSearchIndex(
                document.sections.mapIndexed { position, section ->
                    section.toSearchIndexEntity(
                        documentId = metadata.id,
                        blocks = blocksPerSection[position],
                        documentTitle = document.title.takeIf { section.index == firstSectionIndex },
                        navigation = document.navigation.takeIf { section.index == firstSectionIndex },
                        json = json,
                        sourcePath = sectionSourcePaths[section.index],
                    )
                },
            )
        }
        if (coverBytes != null) {
            coverStore.store(metadata.id, coverBytes)
        }
        invalidateDocumentCache(metadata.id)
    }

    /**
     * Turns this shelf metadata plus [document]'s freshly loaded sections into the [ReaderDocument] the
     * rest of the app reads. `blocks` is [LazyFlattenedBlocks], not a plain list: every block in the book
     * is decoded the first time something actually reads that list rather than as the price of building
     * it — pagination itself never does, see [SectionBlocksCache].
     *
     * @receiver The shelf metadata to combine with [document]'s content.
     * @param document The stored sections, on-demand block cache, and navigation JSON just loaded.
     * @return The combined [ReaderDocument].
     */
    private fun DocumentMetadata.toReaderDocument(document: StoredReaderDocument): ReaderDocument = ReaderDocument(
        id = id,
        format = format,
        title = document.title ?: location.displayName,
        sections = document.sections,
        pageCount = pageCount,
        blocks = LazyFlattenedBlocks(document.sections, document.sectionBlocks),
        navigation = decodeNavigation(document.navigationJson),
    )

    /**
     * Decodes a section's stored block JSON, tolerating a decode failure by answering an empty list
     * rather than propagating the exception — the same "missing images/formatting only" degradation
     * [SectionBlocksCache] documents for a section it hasn't fetched yet.
     *
     * @param blocksJson The stored JSON to decode.
     * @return The decoded blocks, or an empty list if decoding fails.
     */
    private fun decodeBlocks(blocksJson: String): List<ReaderBlock> =
        runCatching { json.decodeFromString<List<ReaderBlock>>(blocksJson) }.getOrDefault(emptyList())

    /**
     * Decodes a document's stored navigation JSON.
     *
     * @param navigationJson The stored JSON to decode, or blank when no navigation was ever resolved.
     * @return The decoded navigation, or null when [navigationJson] is blank or fails to decode.
     */
    private fun decodeNavigation(navigationJson: String): ReaderNavigation? =
        navigationJson.takeIf(String::isNotBlank)
            ?.let { runCatching { json.decodeFromString<ReaderNavigation>(it) }.getOrNull() }
}

/**
 * Whether every offset is larger than the one before it — the invariant a page list written in reading
 * order always holds, and the one [DocumentRepositoryImpl.restorePageWindows] checks a stored row
 * against before it will build pages from it. An empty or single-page layout ascends vacuously.
 */
private fun LongArray.isStrictlyAscending(): Boolean {
    for (index in 1 until size) if (this[index] <= this[index - 1]) return false
    return true
}

/**
 * [PageLayoutEntity.pageStartsBlob] as a little-endian Int32 per offset. Offsets fit comfortably
 * inside `Int` — the largest real book this reader opens is 3.5M characters — so this is exactly the
 * `LongArray` [DocumentRepositoryImpl.storePageWindows] already builds, four bytes apiece instead of
 * JSON digits. Internal rather than private so [DocumentRepositoryImpl.restorePageWindows]/
 * [DocumentRepositoryImpl.storePageWindows]'s round trip can be tested directly (see
 * PageStartsBlobCodecTest) without going through Room.
 *
 * @param pageStarts The page starts to encode; each must fit in an `Int`.
 * @return The encoded blob, [Int.SIZE_BYTES] bytes per entry.
 */
internal fun encodePageStartsBlob(pageStarts: LongArray): ByteArray {
    val blob = ByteArray(pageStarts.size * Int.SIZE_BYTES)
    for (index in pageStarts.indices) {
        val value = pageStarts[index].toInt()
        val offset = index * Int.SIZE_BYTES
        blob[offset] = value.toByte()
        blob[offset + 1] = (value ushr 8).toByte()
        blob[offset + 2] = (value ushr 16).toByte()
        blob[offset + 3] = (value ushr 24).toByte()
    }
    return blob
}

/**
 * The inverse of [encodePageStartsBlob].
 *
 * @param blob The encoded blob to decode.
 * @return The decoded page starts.
 */
internal fun decodePageStartsBlob(blob: ByteArray): LongArray {
    val count = blob.size / Int.SIZE_BYTES
    return LongArray(count) { index ->
        val offset = index * Int.SIZE_BYTES
        val value = (blob[offset].toInt() and 0xFF) or
            ((blob[offset + 1].toInt() and 0xFF) shl 8) or
            ((blob[offset + 2].toInt() and 0xFF) shl 16) or
            ((blob[offset + 3].toInt() and 0xFF) shl 24)
        value.toLong()
    }
}

/**
 * Whether any section's text decoded badly — a Unicode replacement character, or the double-encoded
 * mojibake string that shows up when the same broken decode step ran on already-broken bytes. Sections
 * this shape trigger [DocumentRepositoryImpl.loadReaderDocument]'s TXT repair path rather than being
 * shown to the reader as-is.
 *
 * @receiver The stored sections to check.
 * @return True when at least one section's text contains broken-decode evidence.
 */
private fun List<ReaderSection>.hasBrokenText(): Boolean = any { section ->
    section.text.contains('\uFFFD') || section.text.contains("ï¿½")
}

/**
 * The bytes a non-progressive import (TXT, PDF) needs to already have in hand — those formats have no
 * phased/streamed path, so they cannot proceed without them.
 *
 * @param source The import source to require bytes from.
 * @return The source's bytes.
 * @throws IllegalStateException When [source] carries no bytes.
 */
private fun requireDocumentBytes(source: DocumentImportSource): ByteArray =
    source.bytes ?: error("Document bytes required for ${source.location.displayName}")

/**
 * Extracts the distinct set of embedded-font hrefs referenced anywhere in [blocks], by unioning
 * `block.style.fontHref` and each `span.styleDelta.fontHref`. This is the precise calculation —
 * no OPF superset estimation — as specified by the optimization contract.
 *
 * @param blocks The block list to scan.
 * @return A set of every distinct font href found, sorted for deterministic JSON encoding.
 */
private fun extractFontHrefs(blocks: List<ReaderBlock>): List<String> =
    blocks.asSequence()
        .flatMap { block ->
            sequenceOf(block.style?.fontHref)
                .plus(block.spans.asSequence().map { span -> span.styleDelta?.fontHref })
        }
        .filterNotNull()
        .toMutableSet()
        .sorted()

/**
 * Where [documentId]'s cover is cached. Named by a hash of the id rather than the id itself — a
 * document id is the book's full source URI, which can be arbitrarily long or contain characters a
 * file system rejects as a path component — and the hash is what guarantees two different ids never
 * write the same file. The file existing at this path *is* the cache (see [DocumentRepositoryImpl]'s
 * own doc): there is no database column recording it. Internal rather than private so a test can
 * assert the file is actually written and actually removed (see DocumentRepositoryImplTest), the same
 * reason [encodePageStartsBlob]/[decodePageStartsBlob] above are internal.
 *
 * @param fileSource Where the app-private directory the cover lives under is resolved from.
 * @param documentId The document whose cover path to compute.
 * @return The path the cover for [documentId] is, or would be, cached at.
 */
internal fun coverFilePath(fileSource: DocumentFileSource, documentId: DocumentId): Path =
    fileSource.appPrivateDirectory() / "covers" / "${documentId.value.encodeUtf8().sha1().hex()}.img"

/**
 * The identity of a [DocumentRepositoryImpl.cachedPageWindows] answer: which document, at which style,
 * laid out for which pane size. Two calls with an equal key can share one cached or stored layout;
 * anything that differs — even a resized pane at the same font — cannot.
 *
 * @property documentId The document the layout is for.
 * @property layoutKey The font/line-height/family the layout was (or would be) measured at.
 * @property viewportSize The pane size the layout was (or would be) measured at.
 */
private data class PageWindowKey(
    val documentId: DocumentId,
    val layoutKey: ReaderLayoutKey,
    val viewportSize: ViewportSize,
)

/**
 * Progressive pagination in flight for one (document, style, viewport) [key] that had no stored layout
 * at all when [DocumentRepositoryImpl.getPageWindows] first measured it. Grows one content section at
 * a time — backward from the section the reader resumed into down to position 0, then forward up to
 * the last content section — via [DocumentRepositoryImpl.continuePagination], so the resumed section's
 * own pages are always the first ones measured, and never move again once built: one section's pages
 * depend on nothing but that section (see TextPageLayoutEngine.paginateSection).
 *
 * [lowPosition]/[highPosition] are positions in [contentSections], not [ReaderSection.index] — the two
 * only differ when the book has a cover section, which [contentSections] already excludes.
 *
 * Owns [sectionBlocksCache] itself, rather than a bare closure over whatever [DocumentRepositoryImpl]
 * happened to have cached at the moment this session was built — that field can be replaced by a later,
 * unrelated cache invalidation while this session is still mid-measurement, and a closure captured
 * before the swap would then prewarm an orphaned cache while reading a different, never-warmed one.
 *
 * @property key Which document/style/viewport this session is measuring.
 * @property format The document's format, threaded through to [TextPageLayoutEngine.paginateSection].
 * @property coverPage The document's cover page, if it has one — never re-measured, only carried along.
 * @property contentSections The document's non-cover sections, in spine order, that this session walks.
 * @property sectionBlocksCache The document's on-demand block cache, when it was loaded from storage —
 *   see [blocksFor].
 * @property lowPosition The lowest position in [contentSections] measured so far.
 * @property highPosition The highest position in [contentSections] measured so far.
 */
private class PaginationSession(
    val key: PageWindowKey,
    val format: DocumentFormat,
    val coverPage: PageWindow?,
    val contentSections: List<ReaderSection>,
    private val sectionBlocksCache: SectionBlocksCache?,
    private val fallbackSectionBlocks: (ReaderSection) -> List<ReaderBlock>,
    var lowPosition: Int,
    var highPosition: Int,
    var hasMeasuredPages: Boolean,
) {
    /** Every visited section's page starts, keyed by its position in [contentSections]. */
    private val measuredPageStarts = mutableMapOf<Int, LongArray>()
    private var cachedSnapshot: List<PageWindow>? = null
    private var snapshotDirty = true

    /**
     * Whether every page start in this session came from a real breaker rather than bounded estimated
     * geometry. Estimated windows remain usable for the current open but must never become a stored
     * measured layout.
     */
    var isFullyMeasured: Boolean = true
        private set

    /** Whether every content section has now been visited — see class doc for the growth order. */
    val isComplete: Boolean
        get() = contentSections.isEmpty() || (lowPosition == 0 && highPosition == contentSections.lastIndex)

    /**
     * Adds one section's page starts and folds its measurement provenance into [isFullyMeasured].
     *
     * @param position the section's position in [contentSections].
     * @param result page starts and whether a real breaker produced them.
     */
    fun putMeasured(position: Int, result: SectionPageStarts) {
        measuredPageStarts[position] = result.offsets
        isFullyMeasured = isFullyMeasured && result.isMeasured
        if (position < lowPosition) lowPosition = position
        if (position > highPosition) highPosition = position
        snapshotDirty = true
    }

    fun measuredSections(): List<ReaderSection> = (lowPosition..highPosition).mapNotNull { position ->
        measuredPageStarts[position]?.let { contentSections[position] }
    }

    fun measuredStarts(): List<LongArray> = (lowPosition..highPosition).mapNotNull(measuredPageStarts::get)

    fun pagesAfter(anchorPosition: Int, anchorPageIndex: Int): Int =
        (measuredPageStarts[anchorPosition]?.size ?: 0) - anchorPageIndex - 1 +
            ((anchorPosition + 1)..highPosition).sumOf { position -> measuredPageStarts[position]?.size ?: 0 }

    fun allMeasuredStarts(): LongArray {
        val ordered = measuredStarts()
        val total = ordered.sumOf { it.size }
        var offset = 0
        return LongArray(total).also { flattened ->
            ordered.forEach { starts ->
                starts.copyInto(flattened, destinationOffset = offset)
                offset += starts.size
            }
        }
    }

    /**
     * [section]'s blocks, from [sectionBlocksCache] when one exists, or from [fallbackSectionBlocks]
     * otherwise. [fallbackSectionBlocks] is only ever consulted when there is no cache at all — a
     * document already fully in memory from a repair pass (see [LoadedReaderDocument]) — so a whole-book
     * grouping pass there is a one-time cost on top of work the repair already paid, not a repeat of it.
     *
     * @param section The section to fetch blocks for.
     * @return That section's blocks.
     */
    suspend fun blocksFor(section: ReaderSection): List<ReaderBlock> {
        val cache = sectionBlocksCache ?: return fallbackSectionBlocks(section)
        cache.prewarm(setOf(section.index))
        return cache.blocksFor(section.index)
    }

    fun blocksForSync(section: ReaderSection): List<ReaderBlock> =
        sectionBlocksCache?.blocksFor(section.index) ?: fallbackSectionBlocks(section)

    fun isSectionReady(sectionIndex: Int): Boolean = sectionBlocksCache?.isReady(sectionIndex) ?: true

    fun snapshotWindows(textPageLayoutEngine: TextPageLayoutEngine): List<PageWindow> {
        val existing = cachedSnapshot
        if (existing != null && !snapshotDirty) return existing
        return textPageLayoutEngine.reconstructMeasuredSections(
            format = format,
            coverPage = coverPage,
            contentSections = measuredSections(),
            sectionPageStarts = measuredStarts(),
            sectionBlocks = ::blocksForSync,
            isSectionReady = ::isSectionReady,
        ).also {
            cachedSnapshot = it
            snapshotDirty = false
        }
    }
}

/**
 * How many measured layouts [storePageWindows] keeps per document before [PageLayoutDao.trimPageLayouts]
 * discards the oldest. A reader who is not yet settled on a size tries a handful of them in one sitting —
 * the font a step up, a step down, maybe a line-height or typeface change too — before landing on one. A
 * stored row is now a page-starts blob rather than a JSON array (see [PageLayoutEntity]), cheap enough —
 * a few dozen KB even for a 16,000-page book — that keeping a couple more of them costs nothing worth
 * trading against re-measuring one the reader lands back on.
 */
private const val MaxStoredPageLayoutsPerDocument = 5
private const val PaginationContinuationBatchSize = 8
private const val InitialReadAheadMinimumContentChars = 8_192
private const val InitialReadAheadMaxSpineItems = 16
private const val InitialForwardPaginationPages = 4
private const val InitialForwardPaginationSections = 8

/**
 * The viewport a null caller gets from [DocumentRepositoryImpl.getPageWindows] when nothing is stored
 * for its style yet — the same guess `ReaderViewModel` used to pass directly before `getPageWindows`
 * could resolve one itself.
 */
private val DefaultViewportSize = ViewportSize(widthPx = 320, heightPx = 560)

/**
 * Removes scratch copies left by earlier runs, keeping [keep] and anything still being written.
 *
 * A copy this long-lived cannot be removed in a `finally`, so [DocumentRepositoryImpl.epubScratchCopy]
 * holds its path and deletes it when the next book replaces it. That path is lost when the process dies,
 * and the copy it named is not — one abandoned copy per run, the size of the whole book. This sweep of
 * the ones no longer named by [keep] is what keeps a shelf of large books from filling the cache.
 *
 * @param keep The scratch copy currently in use, which must survive this sweep.
 */
private fun deleteAbandonedScratchCopies(keep: Path) {
    val fileSystem = systemFileSystem()
    val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
    runCatching { fileSystem.list(directory) }.getOrNull()?.forEach { candidate ->
        if (candidate == keep) return@forEach
        if (!candidate.name.startsWith(ScratchCopyPrefix)) return@forEach
        runCatching { fileSystem.delete(candidate) }
    }
}

/** The filename prefix every EPUB scratch copy is written with, so [deleteAbandonedScratchCopies] can
 * recognise one among whatever else is in the temporary directory. */
private const val ScratchCopyPrefix = "tedd-reader-epub-open-"

/**
 * Removes CBZ scratch copies left by earlier runs, keeping [keep] and anything still being written.
 *
 * The one CBZ scratch copy [DocumentRepositoryImpl.cbzArchiveLocked] holds cannot be removed in a
 * `finally` — it stays open across many page-window requests — so the process holds its path and
 * deletes it when the next document replaces it. That path is lost when the process dies, and the copy
 * it named is not — one abandoned copy per run, the size of the whole archive. This sweep of the ones
 * no longer named by [keep] is what keeps a shelf of large comics from filling the cache.
 *
 * @param keep The scratch copy currently in use, which must survive this sweep.
 */
private fun deleteAbandonedComicScratchCopies(keep: Path) {
    val fileSystem = systemFileSystem()
    val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
    runCatching { fileSystem.list(directory) }.getOrNull()?.forEach { candidate ->
        if (candidate == keep) return@forEach
        if (!candidate.name.startsWith(ComicScratchCopyPrefix)) return@forEach
        runCatching { fileSystem.delete(candidate) }
    }
}

/** The filename prefix every CBZ scratch copy is written with, so [deleteAbandonedComicScratchCopies]
 * can recognise one among whatever else is in the temporary directory. */
private const val ComicScratchCopyPrefix = "tedd-reader-comic-open-"

/** Removes orphaned embedded-font scratch files, keeping only the still-live set in [keep]. */
private fun deleteAbandonedEmbeddedFontScratchFiles(keep: Set<Path>) {
    val fileSystem = systemFileSystem()
    val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
    runCatching { fileSystem.list(directory) }.getOrNull()?.forEach { candidate ->
        if (candidate in keep) return@forEach
        if (!candidate.name.startsWith(EmbeddedFontScratchPrefix)) return@forEach
        runCatching { fileSystem.delete(candidate) }
    }
}

/** The filename prefix every embedded-font scratch file is written with. */
private const val EmbeddedFontScratchPrefix = "tedd-reader-epub-font-"
/** Upper bound on one embedded font's extracted size, enforced while streaming to scratch. */
private const val MAX_EPUB_FONT_BYTES = 64L * 1024 * 1024

/**
 * Copies [location] to a fresh temporary file for the duration of [block], deleting it afterwards
 * whether [block] succeeds or throws — for a parser that needs a real [Path] to read from (rather than
 * bytes in memory) but must not leave anything behind once it is done.
 *
 * @param fileSource Where to copy the original file bytes from.
 * @param location The original file's location.
 * @param block The work to do with the temporary copy's path.
 * @return Whatever [block] returns.
 */
private suspend fun <T> withTemporarySourceCopy(
    fileSource: DocumentFileSource,
    location: com.tedd.teddreader.core.common.model.DocumentLocation,
    block: suspend (Path) -> T,
): T {
    val fileSystem = systemFileSystem()
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "tedd-reader-document-${Random.nextLong().toString(16)}-${location.displayName.substringAfterLast('/').ifBlank { "document" }}"
    return try {
        fileSource.copyTo(location, path)
        block(path)
    } finally {
        runCatching { fileSystem.delete(path) }
    }
}

/**
 * What [DocumentRepositoryImpl.getStoredSections] loaded for one document: its sections, their
 * on-demand block cache, and the document-level facts ([title], [navigationJson], [parserVersion])
 * carried on section 0.
 *
 * @property sections The stored sections, in spine order.
 * @property sectionBlocks The on-demand block cache built for [sections].
 * @property title The document's title, if any section recorded one.
 * @property navigationJson The document's stored navigation, still JSON-encoded, or blank if none was
 *   ever resolved.
 * @property parserVersion The parser version the sections were written by — see
 *   [com.tedd.teddreader.core.data.mapper.CurrentReaderParserVersion].
 */
private class StoredReaderDocument(
    val sections: List<ReaderSection>,
    val sectionBlocks: SectionBlocksCache,
    val title: String?,
    val navigationJson: String,
    val parserVersion: Int,
)

/**
 * What [DocumentRepositoryImpl.loadReaderDocument] found: a document plus its on-demand block cache.
 *
 * @property document The loaded document.
 * @property sectionBlocks The document's on-demand block cache, or null when [document] came from a
 *   repair pass and already holds every block in memory.
 */
private class LoadedReaderDocument(
    val document: ReaderDocument,
    val sectionBlocks: SectionBlocksCache?,
)

/**
 * What a restore produced, alongside the cache that answered it — see
 * [DocumentRepositoryImpl.getPageWindows].
 *
 * @property windows The reconstructed page windows.
 * @property sectionBlocksCache The on-demand block cache the reconstruction was built against, or null
 *   when the document came from a repair pass instead of storage.
 */
private class RestoredPageWindowsResult(
    val windows: List<PageWindow>,
    val sectionBlocksCache: SectionBlocksCache?,
)

/**
 * A section's blocks, fetched from [searchIndexDao] and decoded the first time something actually
 * asks for that section, and remembered after that. This is the guarantee a page already shown to the
 * reader relies on: once a section is decoded it stays decoded in [decoded] for the lifetime of this
 * cache, so a page built from it never has its images or block styles disappear back into "not decoded
 * yet" underneath the reader.
 *
 * [blocksFor] is called synchronously — from inside `RestoredPageWindows.get` while a page is being
 * built, sometimes from the main thread turning a page — so it can never suspend and must never touch
 * the database itself. It only ever answers from [decoded]; the fetching happens in [prewarm], called
 * ahead of time for the sections a caller knows it is about to need. A section nothing has fetched yet
 * answers empty, the same as a genuinely empty section would, until [prewarm] (or the background fill
 * that follows the first page publish) catches it up — see `ReaderViewModel.openDocument` for why an
 * empty answer here is safe: it can only ever leave a page's images/formatting momentarily missing,
 * never its text, which never depended on blocks in the first place. [isSectionReady] is the answer this
 * safety argument depends on for a *restored* page list specifically: it tells
 * [TextPageLayoutEngine.reconstruct] whether a section that page actually needs has already been
 * decoded, so reconstruct can distinguish "genuinely no blocks" from "not fetched yet" instead of
 * silently treating every not-yet-fetched section as the former.
 *
 * The published cache ([decoded]) is bounded to [MaxWarmSectionsRetained] entries so pagination's
 * working-set never grows unbounded. [prewarm] always trims after fetching. When a full-document scan
 * needs every section — such as the legacy embedded-font-href extraction in
 * [DocumentRepositoryImpl.getReferencedEmbeddedFontHrefs] — [snapshotAllBlocks] returns a complete,
 * independent copy of all decoded blocks under the same [lock], then trims the published cache back to
 * [MaxWarmSectionsRetained]. The snapshot outlives any trim, so the caller scans accurately without
 * permanently inflating the cache.
 *
 * [blocksFor] answers relative to the section's own start, not as an absolute document offset — that
 * is how [DocumentRepositoryImpl.persistParsedDocument] now writes `blocksJson` (see there for why).
 * [TextPageLayoutEngine] wants exactly that shape. A caller that wants the document's usual absolute
 * addressing instead, like [LazyFlattenedBlocks], has to shift it back itself.
 *
 * @property documentId The document these sections belong to.
 * @param sectionIndexes Every section index this document actually has, so [prewarm] can filter out a
 *   request for a section that will never exist instead of asking the database for it.
 * @property searchIndexDao Where a section's block JSON is fetched from.
 * @property decode How to turn a section's stored block JSON into [ReaderBlock]s.
 */
private class SectionBlocksCache(
    private val documentId: DocumentId,
    sectionIndexes: List<Int>,
    private val searchIndexDao: SearchIndexDao,
    private val decode: (String) -> List<ReaderBlock>,
) {
    /** Every section index this document actually has, per the [sectionIndexes] constructor parameter. */
    private val knownSections: Set<Int> = sectionIndexes.toSet()

    /** [knownSections], exposed so a whole-document scan can name every section it has to prewarm. */
    val knownSectionIndexes: Set<Int> get() = knownSections

    /**
     * Every section decoded so far. Read from [blocksFor]'s synchronous, possibly-main-thread call and
     * written from [prewarm]'s suspend call, on whatever background dispatcher fetched a batch — two
     * different threads, neither ever locking the other. Replacing the whole map on every fetch (rather
     * than mutating one already published) is what makes a concurrent read of this field always see a
     * complete map or the one before it, never a half-filled one.
     */
    @Volatile
    private var decoded: Map<Int, List<ReaderBlock>> = emptyMap()
    private val lock = Mutex()

    /**
     * @param sectionIndex The section to fetch blocks for.
     * @return That section's decoded blocks, relative to the section's own start, or an empty list when
     *   it hasn't been decoded yet (see class doc for why that is safe).
     */
    fun blocksFor(sectionIndex: Int): List<ReaderBlock> = decoded[sectionIndex].orEmpty()

    /**
     * Whether [sectionIndex]'s blocks are the section's real, decoded answer right now — true both for a
     * section already in [decoded] and for one this document doesn't even have, since there is nothing
     * to wait for in that case.
     *
     * @param sectionIndex The section to check.
     * @return Whether [blocksFor] would answer this section's real content if called right now.
     */
    fun isReady(sectionIndex: Int): Boolean = sectionIndex !in knownSections || sectionIndex in decoded

    /**
     * Fetches and decodes whichever of [sectionIndexes] are not decoded yet, in one query. A section
     * this document doesn't have is filtered out instead of asking the database for a row that will
     * never exist. After merging, the published cache is trimmed to the most recent
     * [MaxWarmSectionsRetained] entries so the pagination working set never grows unbounded — a
     * full-document scan that needs every section without disturbing the published cache should use
     * [snapshotAllBlocks] instead.
     *
     * @param sectionIndexes The sections to ensure are decoded.
     * @return How many sections this call actually decoded, so [DocumentRepositoryImpl.warmSectionBlocks]
     *   can tell a caller whether re-publishing is worth doing at all.
     */
    suspend fun prewarm(sectionIndexes: Collection<Int>): Int {
        return lock.withLock {
            val current = decoded
            val requestedKnown = sectionIndexes.filterTo(linkedSetOf()) { it in knownSections }
            val missing = requestedKnown.filterTo(linkedSetOf()) { it !in current }
            val merged = LinkedHashMap<Int, List<ReaderBlock>>(current.size + missing.size)
            current.forEach { (index, blocks) ->
                if (index !in requestedKnown) merged[index] = blocks
            }
            requestedKnown.forEach { index ->
                current[index]?.let { merged[index] = it }
            }
            if (missing.isEmpty()) {
                decoded = merged
                return@withLock 0
            }
            val rows = searchIndexDao.getSectionBlocksJson(documentId.value, missing.toList())
            if (rows.isEmpty()) {
                decoded = merged
                return@withLock 0
            }

            rows.forEach { row ->
                merged.remove(row.sectionIndex)
                merged[row.sectionIndex] = decode(row.blocksJson)
            }
            while (merged.size > MaxWarmSectionsRetained) {
                val oldest = merged.entries.firstOrNull()?.key ?: break
                merged.remove(oldest)
            }
            decoded = merged
            rows.size
        }
    }

    /**
     * Fetches every known section's blocks under [lock], takes a complete snapshot for a full-document
     * scan such as legacy embedded-font-href extraction, then trims the published [decoded] map back to
     * [MaxWarmSectionsRetained] so pagination's working-set invariant is restored. The returned map is
     * an independent copy that outlives any subsequent [prewarm] or trim — callers may iterate it freely
     * without racing the cache's own eviction.
     *
     * Atomicity: a concurrent [prewarm] cannot interleave between the fetch and the snapshot because
     * both happen inside the same [lock] acquisition. The snapshot therefore always reflects a complete,
     * consistent view of every section this document has.
     *
     * @return A map from every known section index to its decoded blocks, relative to each section's own
     *   start (same coordinate space as [blocksFor]).
     */
    suspend fun snapshotAllBlocks(): Map<Int, List<ReaderBlock>> {
        return lock.withLock {
            val current = decoded
            val missing = knownSections.filterTo(linkedSetOf()) { it !in current }
            val full = LinkedHashMap<Int, List<ReaderBlock>>(knownSections.size)
            knownSections.forEach { index ->
                current[index]?.let { full[index] = it }
            }
            if (missing.isNotEmpty()) {
                val rows = searchIndexDao.getSectionBlocksJson(documentId.value, missing.toList())
                rows.forEach { row ->
                    full[row.sectionIndex] = decode(row.blocksJson)
                }
            }
            val trimmed = LinkedHashMap<Int, List<ReaderBlock>>(minOf(full.size, MaxWarmSectionsRetained))
            val entries = full.entries.toList()
            val start = maxOf(0, entries.size - MaxWarmSectionsRetained)
            for (i in start until entries.size) {
                trimmed[entries[i].key] = entries[i].value
            }
            decoded = trimmed
            full
        }
    }

    /** How many distinct sections have actually been decoded — what an open logs to show the saving. */
    val decodedSectionCount: Int get() = decoded.size

    private companion object {
        const val MaxWarmSectionsRetained = 24
    }
}

/**
 * [ReaderDocument.blocks] for a document loaded from storage: every block in the book, flattened only
 * once something actually reads this list — a repair check or a caller that wants the whole document —
 * rather than as the price of opening it. Pagination itself never touches this; it asks
 * [SectionBlocksCache] for one section at a time instead.
 *
 * @property sections The document's sections, in spine order, defining how [sectionBlocks]' per-section
 *   answers are ordered and offset when flattened.
 * @property sectionBlocks The on-demand block cache to flatten.
 */
private class LazyFlattenedBlocks(
    private val sections: List<ReaderSection>,
    private val sectionBlocks: SectionBlocksCache,
) : AbstractList<ReaderBlock>() {
    /**
     * Every section's blocks, concatenated in spine order and shifted back to the document's absolute
     * offsets. [sectionBlocks]' `blocksFor` answers relative to each section's own start, while
     * [ReaderDocument.blocks] is documented as addressing the same absolute offsets as the rest of the
     * document, so each section's answer is shifted back before joining — otherwise two sections' blocks
     * would land on the same small numbers once concatenated, instead of the book's real, ascending
     * offsets.
     */
    private val flattened: List<ReaderBlock> by lazy {
        sections.flatMap { section -> sectionBlocks.blocksFor(section.index).rebasedBy(-section.range.start) }
    }

    /** The book's total block count once [flattened]. */
    override val size: Int get() = flattened.size

    /** The [index]th block across the whole book, from [flattened]. */
    override fun get(index: Int): ReaderBlock = flattened[index]
}

package com.tedd.teddreader.core.data.repository

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
import com.tedd.teddreader.core.common.model.blocksIn
import com.tedd.teddreader.core.common.model.layoutKey
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import com.tedd.teddreader.core.common.model.rebasedBy
import com.tedd.teddreader.core.common.model.wordCount
import com.tedd.teddreader.core.data.mapper.CurrentReaderParserVersion
import com.tedd.teddreader.core.data.mapper.toDocumentEntity
import com.tedd.teddreader.core.data.mapper.toDocumentMetadata
import com.tedd.teddreader.core.data.mapper.toSearchIndexEntity
import com.tedd.teddreader.core.data.pagination.RestoredPageWindows
import com.tedd.teddreader.core.data.pagination.TextPageLayoutEngine
import com.tedd.teddreader.core.data.parser.ComicBookDocumentParser
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
import okio.openZip
import okio.buffer
import org.koin.core.annotation.Single

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
    private val json = Json
    private val logger = Logger.withTag("Pagination")

    // Reading a document back means loading every section's text out of the database and decoding a
    // block list per section. Opening a book asked for exactly that twice — once directly and once
    // more from inside getPageWindows — and every repagination asked again. One book is cached, which
    // is all the reader ever has open, and it is dropped the moment that book is rewritten or deleted.
    private val documentCacheLock = Mutex()
    private var cachedDocumentId: DocumentId? = null
    private var cachedReaderDocument: ReaderDocument? = null

    // The same book's per-section block decoder, kept alongside [cachedReaderDocument] so a restored
    // page layout can ask for one section's blocks instead of forcing [cachedReaderDocument.blocks] to
    // decode the whole book. Null when the cached document came from a repair pass instead of storage —
    // that document already holds every block in memory, so there is nothing to look up on demand.
    private var cachedSectionBlocks: SectionBlocksCache? = null

    // Laying the book out is the most expensive thing the reader does, and the same question gets
    // asked repeatedly: the pane reports its size again after a rotation and back, a settings sheet
    // opens and closes without touching the type, the reader returns to the book it just left. One
    // answer is kept, because one book is laid out at one size at a time.
    private var cachedPageWindowKey: PageWindowKey? = null
    private var cachedPageWindows: List<PageWindow> = emptyList()
    private var cachedPageWindowsAreMeasured: Boolean = false

    // Progressive pagination in flight for [cachedPageWindowKey] — see PaginationSession's own doc.
    // Null once nothing is mid-measurement: either every content section is already covered by
    // [cachedPageWindows] (and, once a real breaker measured it, already written to page_layouts), or
    // getPageWindows has not had to measure this document at all yet.
    private var paginationSession: PaginationSession? = null

    // Serialises [continuePagination] against itself — see its own doc for the duplicate it prevents.
    // Deliberately not [documentCacheLock]: this one is held across a whole section's measurement, and
    // that lock guards the page list the reader reads on its way to a frame.
    private val paginationContinuationLock = Mutex()

    // The EPUB an image is pulled out of, unpacked once and kept. Each call used to read the whole
    // file into memory and write a fresh scratch copy of it just to reach one picture, so turning to
    // an illustrated page cost as much as opening the book.
    private val epubScratchLock = Mutex()
    private var epubScratchDocumentId: DocumentId? = null
    private var epubScratchPath: Path? = null

    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> =
        documentDao.observeRecentDocuments().map { documents -> documents.map { it.toDocumentMetadata() } }

    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        documentDao.getDocument(documentId.value)?.toDocumentMetadata()

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
                val coverPath = coverFilePath(fileSource, documentId)
                readCoverFile(coverPath)?.let {
                    logger.d { "cover: served ${it.size} B from file for ${metadata.location.displayName}" }
                    return@withContext it
                }
                logger.d { "cover: no cached file at $coverPath for ${metadata.location.displayName}, extracting" }
                // Nothing cached yet — either this book was imported before covers were written at
                // import time (see importDocument), or it's a PDF/CBZ, whose parsers don't already have
                // cover bytes in hand the way EpubDocumentParser does. Fall back to today's whole-file
                // extraction, once, and write the result so no later open pays this again.
                val extracted = when (metadata.format) {
                    DocumentFormat.EPUB -> {
                        val bytes = runCatching { fileSource.readBytes(metadata.location) }.getOrNull() ?: return@withContext null
                        epubDocumentParser.coverImageBytes(bytes)
                    }
                    DocumentFormat.PDF -> {
                        val bytes = runCatching { fileSource.readBytes(metadata.location) }.getOrNull() ?: return@withContext null
                        pdfDocumentParser.coverImageBytes(metadata.location, bytes)
                    }
                    DocumentFormat.CBZ -> withTemporarySourceCopy(fileSource, metadata.location) { path ->
                        comicBookDocumentParser.coverImageBytes(path)
                    }
                    else -> null
                }
                logger.d { "cover: extraction gave ${extracted?.size ?: -1} B for ${metadata.location.displayName}" }
                extracted?.also { writeCoverFile(coverPath, it) }
            }
        }
    }

    override suspend fun getVisualPageImages(
        documentId: DocumentId,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> = withContext(Dispatchers.Default) {
        if (pageIndexes.isEmpty()) return@withContext emptyMap()
        val metadata = getDocument(documentId) ?: return@withContext emptyMap()
        if (metadata.format != DocumentFormat.CBZ) return@withContext emptyMap()
        val fileSource = documentFileSource ?: return@withContext emptyMap()
        withTemporarySourceCopy(fileSource, metadata.location) { path ->
            comicBookDocumentParser.pageImageBytes(
                path = path,
                pageIndexes = pageIndexes,
            )
        }
    }

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
        val path = runCatching { epubScratchCopy(metadata, fileSource) }.getOrNull()
            ?: return@withContext emptyMap()
        epubDocumentParser.extractEmbeddedImageBytes(path = path, hrefs = normalizedHrefs)
    }

    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? {
        documentCacheLock.withLock {
            if (cachedDocumentId == documentId) return cachedReaderDocument
        }
        val loaded = loadReaderDocument(documentId)
        documentCacheLock.withLock {
            cachedDocumentId = documentId
            cachedReaderDocument = loaded?.document
            cachedSectionBlocks = loaded?.sectionBlocks
        }
        return loaded?.document
    }

    private suspend fun loadReaderDocument(documentId: DocumentId): LoadedReaderDocument? {
        val metadata = getDocument(documentId) ?: return null
        val storedSections = getStoredSections(documentId)
        if (metadata.format == DocumentFormat.TXT && (storedSections.sections.isEmpty() || storedSections.sections.hasBrokenText())) {
            repairTxtDocument(metadata)?.let { return LoadedReaderDocument(it, sectionBlocks = null) }
        }
        // Stored text written by an older parser is missing things the reader now needs — image
        // proportions, block styles, pictures kept inside their sentence — and the only repair is to
        // read the file again. Asking the rows which parser wrote them costs one integer; the previous
        // way, looking through the blocks for traces of the old code, decoded 293 of one book's 528
        // chapters on every open before it could answer.
        if (
            metadata.format == DocumentFormat.EPUB &&
            (storedSections.parserVersion < CurrentReaderParserVersion || storedSections.navigationJson.isBlank())
        ) {
            repairEpubDocument(metadata)?.let { return LoadedReaderDocument(it, sectionBlocks = null) }
        }
        return LoadedReaderDocument(metadata.toReaderDocument(storedSections), storedSections.sectionBlocks)
    }

    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: ReaderPageBreaker?,
        anchorOffset: Long?,
        // Laying the document out is the expensive half of pagination. Off the main dispatcher it no
        // longer stalls the frame the reader is drawing, so a page turn made right after a font or
        // line-height change still reaches the pager instead of being dropped.
    ): List<PageWindow> = withContext(Dispatchers.Default) {
        val layoutKey = style.layoutKey()
        // A null viewportSize means the caller has no real pane measurement yet. The newest layout
        // ever stored for this exact style — whatever viewport it was measured at — is a far better
        // first answer than the hardcoded guess callers used to pass directly; only when nothing is
        // stored does this fall back to that same guess, so a freshly imported book with no stored
        // layout at all still gets a first pagination pass instead of waiting on a pane that would
        // never measure without one (see f33313b).
        val resolvedViewportSize = viewportSize
            ?: newestStoredViewportSize(documentId, layoutKey)
            ?: DefaultViewportSize
        val key = PageWindowKey(
            documentId = documentId,
            layoutKey = layoutKey,
            viewportSize = resolvedViewportSize,
        )
        // A measured answer is the better answer, so it also serves a caller that arrived without a
        // breaker; only the reverse is refused. Keying on the request instead meant the same restored
        // layout was fetched and rebuilt twice on every open, once for each kind of caller.
        val wantsMeasured = pageBreaker != null
        documentCacheLock.withLock {
            if (cachedPageWindowKey == key && (cachedPageWindowsAreMeasured || !wantsMeasured)) {
                return@withContext cachedPageWindows
            }
        }
        val document = getReaderDocument(documentId) ?: return@withContext emptyList()
        if (document.format.isVisualPageFormat()) return@withContext emptyList()

        // A layout on disk is only ever a real measurement (see storePageWindows), so it beats
        // measuring again whether this particular call brought its own breaker or not — an estimate
        // call gets the exact result a measurement would have given it, for free.
        val restoreStarted = TimeSource.Monotonic.markNow()
        val restored = runCatching { restorePageWindows(documentId, document, key) }
            .onFailure { error -> logger.w(error) { "Failed to restore stored page layout for $documentId" } }
            .getOrNull()
        if (restored != null) {
            val windows = restored.windows
            logger.d {
                "${document.title.orEmpty().take(12)}: ${windows.size} pages from ${document.sections.size} sections " +
                    "restored from storage in ${restoreStarted.elapsedNow().inWholeMilliseconds} ms"
            }
            // What made the restore above cheap: only these sections were actually decoded and only
            // these pages actually built, instead of every block and every page in the book.
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
        // No layout at all is stored yet for this style. Laying every section out before the reader
        // sees anything cost 6.4s/13.0s measured on a real device (204/528-section books) — so this
        // measures only the section the reader is resting on and returns just that, the same shape a
        // freshly imported EPUB already shows its first chapter in before the rest is parsed (see
        // ReaderViewModel.continueImportIfIncomplete). continuePagination extends the rest afterwards:
        // this section's own neighbours first (so the resumed page's number stops moving), then the
        // remainder in spine order (so the total does). A page is only ever built from a section that
        // was actually measured — never an estimate standing in for a section not yet reached — so the
        // total this returns is honest: "pages measured so far," not a guess dressed up as an answer.
        val sectionBlocksCache = documentCacheLock.withLock { cachedSectionBlocks }
        val fallbackSectionBlocks: (ReaderSection) -> List<ReaderBlock> = if (sectionBlocksCache != null) {
            { section -> sectionBlocksCache.blocksFor(section.index) }
        } else {
            textPageLayoutEngine.defaultSectionBlocks(document)
        }
        // Cover detection needs section 0 eagerly, the same as a restore (see restorePageWindows).
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
        )
        if (resolved.contentSections.isNotEmpty()) {
            val anchorSection = resolved.contentSections[anchorPosition]
            session.sectionPages.addLast(
                textPageLayoutEngine.paginateSection(
                    format = document.format,
                    section = anchorSection,
                    sectionBlocks = session.blocksFor(anchorSection),
                    style = style,
                    viewportSize = resolvedViewportSize,
                    pageBreaker = pageBreaker,
                ),
            )
        }
        val pageWindows = windowsFor(session)
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
            storePageWindows(documentId, document, key, pageWindows)
        }
        pageWindows
    }

    override suspend fun continuePagination(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
    ): PaginationProgress = withContext(Dispatchers.Default) {
        if (pageBreaker == null) return@withContext PaginationProgress(isComplete = true, sectionsMeasured = 0)
        // One section claimed and committed at a time. Two continuation passes overlap in the ordinary
        // course of a type change — updateStyle starts one, and the pane's first breaker report for the
        // new type starts another — and reading lowPosition/highPosition outside a lock let both claim
        // the same position, measure it, and append it twice. A pass that walked the whole book that way
        // finished holding, and stored, exactly twice the book's pages. Held across the measurement
        // itself, not just the commit, because the claim is only safe if nothing else can read the
        // positions it is about to move. Nothing on the reader's own path takes this lock — continuation
        // is background work being serialised against itself, never against a page the reader is waiting
        // for (which is served from cachedPageWindows under documentCacheLock).
        paginationContinuationLock.withLock {
            val key = PageWindowKey(documentId = documentId, layoutKey = style.layoutKey(), viewportSize = viewportSize)
            val session = documentCacheLock.withLock { paginationSession?.takeIf { it.key == key } }
                ?: return@withContext PaginationProgress(isComplete = true, sectionsMeasured = 0)
            if (session.isComplete) return@withContext PaginationProgress(isComplete = true, sectionsMeasured = 0)

            val extendingBackward = session.lowPosition > 0
            val nextPosition = if (extendingBackward) session.lowPosition - 1 else session.highPosition + 1
            val nextSection = session.contentSections[nextPosition]
            val newPages = textPageLayoutEngine.paginateSection(
                format = session.format,
                section = nextSection,
                sectionBlocks = session.blocksFor(nextSection),
                style = style,
                viewportSize = viewportSize,
                pageBreaker = pageBreaker,
            )
            if (extendingBackward) {
                session.sectionPages.addFirst(newPages)
                session.lowPosition = nextPosition
            } else {
                session.sectionPages.addLast(newPages)
                session.highPosition = nextPosition
            }

            val windows = windowsFor(session)
            val isComplete = session.isComplete
            documentCacheLock.withLock {
                cachedPageWindowKey = key
                cachedPageWindows = windows
                cachedPageWindowsAreMeasured = true
                paginationSession = session.takeUnless { isComplete }
            }
            if (isComplete && windows.isNotEmpty()) {
                getReaderDocument(documentId)?.let { document -> storePageWindows(documentId, document, key, windows) }
            }
            PaginationProgress(isComplete = isComplete, sectionsMeasured = 1)
        }
    }

    override suspend fun isPaginationComplete(documentId: DocumentId): Boolean = documentCacheLock.withLock {
        paginationSession?.let { it.key.documentId != documentId || it.isComplete } ?: true
    }

    /** The position in [contentSections] of the section containing [anchorOffset] — the last section
     * starting at or before it, since sections are ascending and non-overlapping. Defaults to the
     * first content section when [anchorOffset] is null or before every section's own start, the same
     * place a freshly imported book with nowhere to resume to starts from. */
    private fun anchorPositionFor(contentSections: List<ReaderSection>, anchorOffset: Long?): Int {
        if (contentSections.isEmpty() || anchorOffset == null) return 0
        val position = contentSections.indexOfLast { section -> section.range.start <= anchorOffset }
        return position.coerceIn(0, contentSections.lastIndex)
    }

    private fun windowsFor(session: PaginationSession): List<PageWindow> =
        textPageLayoutEngine.assemblePages(session.coverPage, session.sectionPages.flatten())

    override suspend fun resolveViewportSizeForStyle(documentId: DocumentId, style: ReaderStyle): ViewportSize? =
        withContext(Dispatchers.Default) { newestStoredViewportSize(documentId, style.layoutKey()) }

    override suspend fun warmSectionBlocks(documentId: DocumentId, sectionIndexes: Set<Int>) {
        if (sectionIndexes.isEmpty()) return
        val cache = documentCacheLock.withLock {
            cachedSectionBlocks.takeIf { cachedDocumentId == documentId }
        } ?: return
        withContext(Dispatchers.Default) { cache.prewarm(sectionIndexes) }
    }

    /** The viewport [PageLayoutDao.getNewestPageLayoutForStyle] resolves for [layoutKey], if any row exists. */
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
        // Re-parsing a document can move every character offset in it. Refusing a row whose character
        // count no longer matches is what keeps a bookmark or a reading position from silently landing
        // on the wrong text after a repair pass rewrites the book it pointed into.
        if (stored.characterCount != document.characterCount) {
            pageLayoutDao.deletePageLayouts(documentId.value)
            return null
        }
        // Only the blob is ever decoded — pageStartsJson is legacy storage kept for schema reasons
        // (see PageLayoutEntity). A row written before TeddReaderMigration7To8 has no blob and is
        // treated the same as no stored row at all; that migration deletes every such row for exactly
        // this reason, so this should only be null in a database that predates the migration entirely.
        val pageStartsBlob = stored.pageStartsBlob ?: return null
        val pageStarts = decodePageStartsBlob(pageStartsBlob)
        // Pages are written in reading order, so their starts can only ascend. A row that breaks that
        // was not written by a sound measurement of the book as it now stands, and rebuilding pages from
        // it would put the reader on text that is not where the row says it is — so it is thrown away and
        // measured again rather than trusted. One pass over a few thousand longs, next to a decode that
        // already walked the same array, and it is what lets a device carrying a row some writer bug
        // corrupted heal itself on the next open instead of reading the wrong page forever.
        if (!pageStarts.isStrictlyAscending()) {
            logger.w { "Discarding a stored page layout for $documentId whose page starts do not ascend" }
            pageLayoutDao.deletePageLayouts(documentId.value)
            return null
        }
        // A section cache exists only for a document actually loaded from storage; a document that just
        // came out of a repair pass already holds every block in memory, so reconstruct falls back to
        // its own default there instead of decoding anything twice.
        val sectionBlocksCache = documentCacheLock.withLock {
            cachedSectionBlocks.takeIf { cachedDocumentId == documentId }
        }
        val windows = if (sectionBlocksCache != null) {
            // Cover detection looks at section 0 eagerly, not lazily (see
            // TextPageLayoutEngine.findCoverSection, called from within reconstruct itself before it
            // ever returns) — so section 0 has to already be decoded before reconstruct runs, not just
            // before some later page happens to be built.
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

    /** Only ever called for a real measurement — see the `pageBreaker != null` guard at the call site. */
    private suspend fun storePageWindows(
        documentId: DocumentId,
        document: ReaderDocument,
        key: PageWindowKey,
        pageWindows: List<PageWindow>,
    ) {
        val coverPageCount = if (textPageLayoutEngine.hasCoverPage(document)) 1 else 0
        val contentPageStarts = LongArray(pageWindows.size - coverPageCount) { index ->
            pageWindows[index + coverPageCount].textRange?.start ?: 0L
        }
        pageLayoutDao.upsertPageLayout(
            PageLayoutEntity(
                documentId = documentId.value,
                fontSizeSp = key.layoutKey.fontSizeSp,
                lineHeightMultiplier = key.layoutKey.lineHeightMultiplier,
                fontFamilyName = key.layoutKey.fontFamilyName.orEmpty(),
                viewportWidthPx = key.viewportSize.widthPx,
                viewportHeightPx = key.viewportSize.heightPx,
                characterCount = document.characterCount,
                // pageStartsJson is left at its default — see PageLayoutEntity; only the blob is written.
                pageStartsBlob = encodePageStartsBlob(contentPageStarts),
                writtenAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        pageLayoutDao.trimPageLayouts(documentId.value, keep = MaxStoredPageLayoutsPerDocument)
    }

    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument {
        val id = DocumentId(source.location.sourceUri)
        val existingDocument = getDocument(id)
        // A book already on the shelf, imported all the way through, is opened rather than imported
        // again. Another app handing this one over — "open with", a share — arrives here every time, and
        // re-importing threw away the stored text and page layouts of a book the reader was already
        // reading, so opening a 528-chapter book from a file manager paid the whole import over again.
        // An unfinished import is not skipped: importNextSections picks that one up where it stopped.
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

            // Progressive import only pays for itself when the caller deliberately withheld the
            // bytes to avoid reading the whole file into memory (see DocumentImporter.android/ios.kt,
            // which now passes bytes=null for a picked EPUB). A caller that already has the bytes —
            // an existing test, or a Google Drive download that already paid the network cost — gets
            // nothing from deferring the rest of the spine, so it gets the same synchronous full parse
            // EPUB import has always done. Only the bytes=null path takes importEpubPhase0's phased
            // route, which needs a real file source to stream from since it has no bytes to fall back on.
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
                bytes = requireDocumentBytes(source),
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

    override suspend fun upsertDocument(document: DocumentMetadata) {
        // DocumentMetadata carries no field for this column (see DocumentEntity), and Room's upsert
        // replaces the whole row — an ordinary edit like a favourite toggle would otherwise write back
        // null and erase the timestamp a later progressive-import step needs to trust. Reading the
        // stored value forward is the smaller fix; threading the column through the domain model would
        // touch every one of its call sites for a value nothing reads yet.
        val importCompletedAtEpochMillis = documentDao.getDocument(document.id.value)?.importCompletedAtEpochMillis
        documentDao.upsertDocument(
            document.toDocumentEntity().copy(importCompletedAtEpochMillis = importCompletedAtEpochMillis),
        )
    }

    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) {
        documentDao.updateLastOpenedAt(documentId.value, openedAtEpochMillis)
    }

    override suspend fun deleteDocument(documentId: DocumentId) {
        documentDao.deleteDocument(documentId.value)
        invalidateCaches(documentId)
        documentFileSource?.let { fileSource ->
            runCatching { systemFileSystem().delete(coverFilePath(fileSource, documentId)) }
        }
    }

    private suspend fun invalidateCaches(documentId: DocumentId) {
        invalidateDocumentCache(documentId)
        // A stored layout addresses text by absolute offset, and re-parsing the document is exactly
        // what moves those offsets. Every path that rewrites a document's sections calls through here
        // first, so this is the one place that needs to know a stored layout has gone stale.
        pageLayoutDao.deletePageLayouts(documentId.value)
        epubScratchLock.withLock {
            if (epubScratchDocumentId == documentId) {
                epubScratchPath?.let { path -> runCatching { systemFileSystem().delete(path) } }
                epubScratchDocumentId = null
                epubScratchPath = null
            }
        }
    }

    /**
     * Just the in-memory half of [invalidateCaches] — dropping the cached document, its section-blocks
     * cache and the cached page-window answer, without touching [page_layouts] or the EPUB scratch
     * copy. A progressive import's own batches call this instead of [invalidateCaches]: the document
     * really did grow and the next read must see that, but the stored page layout is exactly what
     * [importNextSections] is extending in place, and the scratch copy is exactly what it is still
     * reading from — deleting either mid-import would throw away real progress, not stale data.
     */
    private suspend fun invalidateDocumentCache(documentId: DocumentId) {
        documentCacheLock.withLock {
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
     * A scratch copy of the EPUB behind [documentId], made once and reused for every later image.
     *
     * Only one is kept: the reader has one book open, and holding a second copy of a previous one on
     * disk buys nothing.
     */
    private suspend fun epubScratchCopy(
        metadata: DocumentMetadata,
        fileSource: DocumentFileSource,
    ): Path = epubScratchLock.withLock {
        epubScratchPath?.takeIf { epubScratchDocumentId == metadata.id && systemFileSystem().exists(it) }
            ?.let { return@withLock it }

        epubScratchPath?.let { previous -> runCatching { systemFileSystem().delete(previous) } }
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "tedd-reader-epub-open-${Random.nextLong().toString(16)}.epub"
        // A copy this long-lived cannot be removed in a `finally`, so the process holds its path and
        // deletes it when the next book replaces it. That path is lost when the process dies, and the
        // copy it named is not — one abandoned copy per run, the size of the whole book. Sweeping the
        // ones no longer named here is what keeps a shelf of large books from filling the cache.
        deleteAbandonedScratchCopies(keep = path)
        fileSource.copyTo(metadata.location, path)
        epubScratchDocumentId = metadata.id
        epubScratchPath = path
        path
    }

    private suspend fun getStoredSections(documentId: DocumentId): StoredReaderDocument {
        val readStarted = TimeSource.Monotonic.markNow()
        // blocksJson is deliberately not part of this row (see SearchIndexDao.getDocumentSectionsWithoutBlocks)
        // — on a big book it dwarfs every other column combined, and opening used to pull all of it
        // into memory as strings before a single page was built. SectionBlocksCache fetches it back,
        // only for the sections something actually asks for.
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

    private suspend fun repairTxtDocument(metadata: DocumentMetadata): ReaderDocument? {
        val fileSource = documentFileSource ?: return null
        return runCatching {
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
     * [DocumentImportSource] with no bytes is what selects the phased route (see importDocument), and
     * carrying the existing [metadata] through as the "existing document" is what keeps the shelf entry
     * the reader recognises: when it was added, whether it is a favourite, which folder it sits in.
     */
    private suspend fun repairEpubDocument(metadata: DocumentMetadata): ReaderDocument? {
        val fileSource = documentFileSource ?: return null
        return runCatching {
            importEpubPhase0(
                id = metadata.id,
                source = DocumentImportSource(location = metadata.location, bytes = null),
                existingDocument = metadata,
                // A repair is not a fresh import, so the shelf's "last opened" is the reader's own
                // history and not this moment; only a document that somehow never recorded one falls
                // back to now.
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
     * Phase 0/1 of a progressive EPUB import (see [importNextSections] for the rest): stream the
     * picked file into app-private storage once, parse just the container/OPF and settle the cover
     * decision — deciding it any later shifts every offset after it — parse spine item 0, and commit
     * the document row plus that first section. [DocumentMetadata.characterCount] stays null and
     * `documents.importCompletedAtEpochMillis` stays unset unless the whole spine turns out to fit in
     * this first item, so a book that never finishes importing reads as unfinished rather than wrong.
     * Only reached with bytes=null (see [importDocument]) — there is a real file source to stream from.
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
        val zip = systemFileSystem().openZip(path)
        val container = openEpubImportContainer(zip, title)

        val isFullyImported: Boolean
        val document: ReaderDocument
        val coverBytes: ByteArray?
        if (container == null) {
            // No OPF at all: the existing fallback-chapters parse already reads and lays out every
            // chapter it can find directly from this same scratch copy, so there is no spine left to
            // stream and nothing progressive about this branch.
            val parsed = epubDocumentParser.parseWithCover(id = id, title = title, path = path, fileSystem = systemFileSystem())
            document = parsed.document
            coverBytes = parsed.coverBytes
            isFullyImported = true
        } else {
            val sections = mutableListOf<ReaderSection>()
            val blocks = mutableListOf<ReaderBlock>()
            buildEpubCoverSection(container.coverDecision, container.documentTitle)?.let { cover ->
                sections += cover.section
                blocks += cover.blocks
            }
            val baseOffset = sections.lastOrNull()?.let { it.range.end + SectionSeparatorLength } ?: 0L
            parseEpubSpineItem(
                container = container,
                spinePosition = 0,
                sectionIndex = sections.size,
                baseOffset = baseOffset,
            )?.let { first ->
                sections += first.section
                blocks += first.blocks
            }
            fillIntrinsicImageSizes(blocks, zip, container.coverDecision.coverHref, container.coverDecision.coverBytes)
            document = ReaderDocument(
                id = id,
                format = DocumentFormat.EPUB,
                title = container.documentTitle,
                sections = sections,
                blocks = blocks,
                navigation = ReaderNavigation(),
            )
            coverBytes = container.coverDecision.coverBytes
            // A spine of exactly one linear item (or a synthetic cover consuming position 0) means
            // this very first batch already covered the whole book — no different, for what gets
            // stored, than any other format that always imports in one shot.
            isFullyImported = container.linearSpineItems.size <= 1
        }

        persistParsedDocument(
            metadata = DocumentMetadata(
                id = id,
                location = source.location,
                format = DocumentFormat.EPUB,
                addedAtEpochMillis = existingDocument?.addedAtEpochMillis ?: importedAtEpochMillis,
                lastOpenedAtEpochMillis = importedAtEpochMillis,
                pageCount = document.pageCount.takeIf { isFullyImported },
                characterCount = document.characterCount.takeIf { isFullyImported },
                wordCount = document.wordCount.takeIf { isFullyImported },
                isBookmarked = existingDocument?.isBookmarked ?: false,
                folderId = existingDocument?.folderId,
                folderName = existingDocument?.folderName,
            ),
            document = document,
            coverBytes = coverBytes,
            importCompletedAtEpochMillis = if (isFullyImported) Clock.System.now().toEpochMilliseconds() else null,
        )
        return document
    }

    override suspend fun isImportComplete(documentId: DocumentId): Boolean =
        documentDao.getDocument(documentId.value)?.importCompletedAtEpochMillis != null

    override suspend fun importNextSections(
        documentId: DocumentId,
        count: Int,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
    ): ImportProgress = withContext(Dispatchers.Default) {
        val entity = documentDao.getDocument(documentId.value)
            ?: return@withContext ImportProgress(isComplete = true, sectionsImported = 0)
        val fileSource = documentFileSource
        if (entity.importCompletedAtEpochMillis != null || entity.format != DocumentFormat.EPUB.name || fileSource == null) {
            return@withContext ImportProgress(isComplete = true, sectionsImported = 0)
        }

        val path = epubScratchCopy(entity.toDocumentMetadata(), fileSource)
        val zip = systemFileSystem().openZip(path)
        val container = openEpubImportContainer(zip, entity.name)
            // No OPF: importEpubPhase0's fallback-chapters branch already imported everything there was
            // to import, so the only thing left here is the completion stamp that branch skipped.
            ?: return@withContext finishNonProgressiveEpubImport(documentId, entity)

        val lastSection = searchIndexDao.getLastSection(documentId.value)
        val hasCoverSection = container.coverDecision.hasCoverSection
        var sectionIndex = (lastSection?.sectionIndex?.plus(1)) ?: 0
        var offset = lastSection?.endOffset?.plus(SectionSeparatorLength) ?: 0L
        var spinePosition = sectionIndex -
            (if (hasCoverSection) 1 else 0) +
            (if (container.coverDecision.spineOrder0Skipped) 1 else 0)

        val newEntries = mutableListOf<SearchIndexEntity>()
        val newSections = mutableListOf<Pair<ReaderSection, List<ReaderBlock>>>()
        var sectionsImported = 0
        while (sectionsImported < count && spinePosition < container.linearSpineItems.size) {
            val parsed = parseEpubSpineItem(container, spinePosition, sectionIndex, offset)
            spinePosition += 1
            if (parsed == null) continue // pure-cover skip, or an unreadable item — consumes a spine
                                          // slot without becoming a section, same as the one-shot loop.
            val blocks = parsed.blocks.toMutableList()
            fillIntrinsicImageSizes(blocks, zip, container.coverDecision.coverHref, container.coverDecision.coverBytes)
            // Stored section-relative from here on, same as persistParsedDocument's own sections (see
            // TextPageLayoutEngine.sectionPageRanges) — and pageStartsForSection below now expects that
            // same relative shape, not the absolute one parseEpubSpineItem hands back.
            val relativeBlocks = blocks.rebasedBy(parsed.section.range.start)
            newEntries += parsed.section.toSearchIndexEntity(documentId = documentId, blocks = relativeBlocks, json = json)
            newSections += parsed.section to relativeBlocks
            offset = parsed.section.range.end + SectionSeparatorLength
            sectionIndex += 1
            sectionsImported += 1
        }

        if (newEntries.isNotEmpty()) {
            searchIndexDao.upsertSearchIndex(newEntries)
            invalidateDocumentCache(documentId)
            appendMeasuredPageStarts(documentId, style, viewportSize, pageBreaker, newSections)
        }

        val isComplete = spinePosition >= container.linearSpineItems.size
        if (!isComplete) return@withContext ImportProgress(isComplete = false, sectionsImported = sectionsImported)

        finishEpubImport(documentId, entity, container)
        ImportProgress(isComplete = true, sectionsImported = sectionsImported)
    }

    /**
     * Extends an already-stored page layout with the pages [newSections] measure on their own, instead
     * of re-measuring the whole book from scratch on every batch. A no-op when there is nothing stored
     * yet for [style]/[viewportSize] (the reader has not measured this document at all yet) or no real
     * [pageBreaker] to measure with — the next real [getPageWindows] call falls back to measuring the
     * whole currently-known book once, exactly as it already does for any document with no stored
     * layout (see that function's own fallback).
     */
    private suspend fun appendMeasuredPageStarts(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        newSections: List<Pair<ReaderSection, List<ReaderBlock>>>,
    ) {
        if (pageBreaker == null || newSections.isEmpty()) return
        val layoutKey = style.layoutKey()
        val stored = pageLayoutDao.getPageLayout(
            documentId = documentId.value,
            fontSizeSp = layoutKey.fontSizeSp,
            lineHeightMultiplier = layoutKey.lineHeightMultiplier,
            fontFamilyName = layoutKey.fontFamilyName.orEmpty(),
            viewportWidthPx = viewportSize.widthPx,
            viewportHeightPx = viewportSize.heightPx,
        ) ?: return
        val existingStarts = stored.pageStartsBlob?.let(::decodePageStartsBlob) ?: return
        val appendedStarts = newSections.flatMap { (section, blocks) ->
            textPageLayoutEngine.pageStartsForSection(section, blocks, style, viewportSize, pageBreaker).toList()
        }
        if (appendedStarts.isEmpty()) return
        val addedCharacterCount = newSections.sumOf { (section, _) -> section.text.length.toLong() }
        pageLayoutDao.upsertPageLayout(
            stored.copy(
                characterCount = stored.characterCount + addedCharacterCount,
                pageStartsBlob = encodePageStartsBlob((existingStarts.toList() + appendedStarts).toLongArray()),
                writtenAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    /** Which spine path each already-stored section index came from — a pure function of position
     * (position 0 possibly skipped as a pure-cover page, every other linear item giving exactly one
     * section, see [EpubCoverDecision]), not anything a batch needs to remember along the way. */
    private fun buildSectionPathByIndex(
        container: EpubImportContainer,
        coverSectionIndex: Int?,
        storedSectionCount: Int,
    ): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        val coverHref = container.coverDecision.coverHref
        if (coverSectionIndex != null && coverHref != null) map[coverSectionIndex] = coverHref
        var spinePosition = if (container.coverDecision.spineOrder0Skipped) 1 else 0
        var index = if (coverSectionIndex != null) 1 else 0
        while (index < storedSectionCount && spinePosition < container.linearSpineItems.size) {
            map[index] = container.linearSpineItems[spinePosition].path
            index += 1
            spinePosition += 1
        }
        return map
    }

    /** The last step of a progressive EPUB import: resolve navigation now that every section is known,
     * retitle whichever sections the table of contents names, and stamp the document complete. */
    private suspend fun finishEpubImport(
        documentId: DocumentId,
        entity: DocumentEntity,
        container: EpubImportContainer,
    ) {
        val entries = searchIndexDao.getDocumentSectionsWithoutBlocks(documentId.value)
        val coverSectionIndex = 0.takeIf { container.coverDecision.hasCoverSection }
        val firstReadableContentSectionIndex = entries
            .firstOrNull { it.sectionIndex != coverSectionIndex && it.text.isNotBlank() }
            ?.sectionIndex
        val navigation = resolveEpubNavigationAtCompletion(
            container = container,
            sectionPathByIndex = buildSectionPathByIndex(container, coverSectionIndex, entries.size),
            coverSectionIndex = coverSectionIndex,
            firstReadableContentSectionIndex = firstReadableContentSectionIndex,
        )
        navigation.items.filter { it.offset == 0L }.forEach { item ->
            searchIndexDao.updateSectionTitle(documentId.value, item.spineIndex, item.title)
        }
        searchIndexDao.updateDocumentTitleAndNavigation(
            documentId = documentId.value,
            sectionIndex = 0,
            documentTitle = container.documentTitle,
            navigationJson = json.encodeToString(navigation),
        )
        documentDao.upsertDocument(
            entity.copy(
                characterCount = entries.sumOf { it.text.length.toLong() },
                wordCount = entries.sumOf { it.text.wordCount().toLong() },
                importCompletedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        invalidateDocumentCache(documentId)
    }

    /** Defensive fallback for [importNextSections]: reached only if a document's import somehow never
     * got stamped complete even though its EPUB has no OPF at all, a case importEpubPhase0 already
     * finishes in one shot. */
    private suspend fun finishNonProgressiveEpubImport(documentId: DocumentId, entity: DocumentEntity): ImportProgress {
        val entries = searchIndexDao.getDocumentSectionsWithoutBlocks(documentId.value)
        documentDao.upsertDocument(
            entity.copy(
                characterCount = entries.sumOf { it.text.length.toLong() },
                wordCount = entries.sumOf { it.text.wordCount().toLong() },
                importCompletedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        invalidateDocumentCache(documentId)
        return ImportProgress(isComplete = true, sectionsImported = 0)
    }

    private suspend fun persistParsedDocument(
        metadata: DocumentMetadata,
        document: ReaderDocument,
        coverBytes: ByteArray? = null,
        // Every existing caller parses and stores the whole document in one shot, so "complete right
        // now" is the correct default for all of them — TXT/PDF/CBZ/IMAGE import, and an EPUB repair,
        // which re-parses the entire book synchronously even for a document whose *original* import
        // never finished. Only importEpubPhase0 overrides this with null: it persists just the first
        // section(s) and leaves the rest to importNextSections. Bypassing the public upsertDocument
        // (which otherwise preserves whatever was already stored, for an ordinary metadata edit like
        // toggling a favourite) is what lets this decide the value outright instead of inheriting it.
        importCompletedAtEpochMillis: Long? = Clock.System.now().toEpochMilliseconds(),
    ) {
        invalidateCaches(metadata.id)
        documentDao.upsertDocument(metadata.toDocumentEntity().copy(importCompletedAtEpochMillis = importCompletedAtEpochMillis))
        searchIndexDao.deleteSearchIndex(metadata.id.value)
        if (document.sections.isNotEmpty()) {
            searchIndexDao.upsertSearchIndex(
                document.sections.map { section ->
                    section.toSearchIndexEntity(
                        documentId = metadata.id,
                        // Stored relative to this section's own start, not as the absolute offsets
                        // pagination addresses a page with — see TextPageLayoutEngine.sectionPageRanges,
                        // which used to redo this exact shift, for every block and every span, on every
                        // pagination pass instead of once here.
                        blocks = document.blocks.blocksIn(section.range.start, section.range.end).rebasedBy(section.range.start),
                        documentTitle = document.title.takeIf { section.index == document.sections.first().index },
                        navigation = document.navigation.takeIf { section.index == document.sections.first().index },
                        json = json,
                    )
                },
            )
        }
        // Writing the cover now, while the caller already has its bytes decoded, is what spares every
        // later open the whole-file read getDocumentCover would otherwise repeat (see class doc).
        if (coverBytes != null) {
            documentFileSource?.let { fileSource -> writeCoverFile(coverFilePath(fileSource, metadata.id), coverBytes) }
        }
    }

    private fun DocumentMetadata.toReaderDocument(document: StoredReaderDocument): ReaderDocument = ReaderDocument(
        id = id,
        format = format,
        title = document.title ?: location.displayName,
        sections = document.sections,
        pageCount = pageCount,
        // Every block in the book, decoded the first time something actually reads this list rather
        // than as the price of building it — pagination itself never does, see [SectionBlocksCache].
        blocks = LazyFlattenedBlocks(document.sections, document.sectionBlocks),
        navigation = decodeNavigation(document.navigationJson),
    )

    private fun decodeBlocks(blocksJson: String): List<ReaderBlock> =
        runCatching { json.decodeFromString<List<ReaderBlock>>(blocksJson) }.getOrDefault(emptyList())

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
 * `LongArray` [storePageWindows] already builds, four bytes apiece instead of JSON digits. Internal
 * rather than private so [restorePageWindows]/[storePageWindows]'s round trip can be tested directly
 * (see PageStartsBlobCodecTest) without going through Room.
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

/** The inverse of [encodePageStartsBlob]. */
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

private fun List<ReaderSection>.hasBrokenText(): Boolean = any { section ->
    section.text.contains('\uFFFD') || section.text.contains("ï¿½")
}

/** True when every section decodes to zero blocks — a book stored before blocks were captured at all. */
private fun requireDocumentBytes(source: DocumentImportSource): ByteArray =
    source.bytes ?: error("Document bytes required for ${source.location.displayName}")

/**
 * Where [documentId]'s cover is cached. Named by a hash of the id rather than the id itself — a
 * document id is the book's full source URI, which can be arbitrarily long or contain characters a
 * file system rejects as a path component — and the hash is what guarantees two different ids never
 * write the same file. The file existing at this path *is* the cache (see class doc): there is no
 * database column recording it. Internal rather than private so a test can assert the file is
 * actually written and actually removed (see DocumentRepositoryImplTest), the same reason
 * [encodePageStartsBlob]/[decodePageStartsBlob] above are internal.
 */
internal fun coverFilePath(fileSource: DocumentFileSource, documentId: DocumentId): Path =
    fileSource.appPrivateDirectory() / "covers" / "${documentId.value.encodeUtf8().sha1().hex()}.img"

// Okio's own read/write helpers rather than use {}: okio.Closeable is not kotlin.AutoCloseable on
// Kotlin/Native, so `use` compiles on Android and fails the iOS targets.
private fun readCoverFile(path: Path): ByteArray? =
    runCatching {
        systemFileSystem().read(path) { readByteArray() }
    }.getOrNull()

private fun writeCoverFile(path: Path, bytes: ByteArray) {
    runCatching {
        path.parent?.let { parent -> systemFileSystem().createDirectories(parent) }
        systemFileSystem().write(path) { write(bytes) }
    }
}

/** Removes scratch copies left by earlier runs, keeping [keep] and anything still being written. */
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
 */
private class PaginationSession(
    val key: PageWindowKey,
    val format: DocumentFormat,
    val coverPage: PageWindow?,
    val contentSections: List<ReaderSection>,
    private val sectionBlocksCache: SectionBlocksCache?,
    // Only consulted when there is no cache at all — a document already fully in memory from a repair
    // pass (see LoadedReaderDocument) — so a whole-book grouping pass here is a one-time cost on top of
    // work the repair already paid, not a repeat of it.
    private val fallbackSectionBlocks: (ReaderSection) -> List<ReaderBlock>,
    var lowPosition: Int,
    var highPosition: Int,
) {
    val sectionPages: ArrayDeque<List<PageWindow>> = ArrayDeque()
    val isComplete: Boolean
        get() = contentSections.isEmpty() || (lowPosition == 0 && highPosition == contentSections.lastIndex)

    suspend fun blocksFor(section: ReaderSection): List<ReaderBlock> {
        val cache = sectionBlocksCache ?: return fallbackSectionBlocks(section)
        cache.prewarm(setOf(section.index))
        return cache.blocksFor(section.index)
    }
}

// A reader who is not yet settled on a size tries a handful of them in one sitting — the font a step
// up, a step down, maybe a line-height or typeface change too — before landing on one. A stored row is
// now a page-starts blob rather than a JSON array (see PageLayoutEntity), cheap enough — a few dozen KB
// even for a 16,000-page book — that keeping a couple more of them costs nothing worth trading against
// re-measuring one the reader lands back on.
private const val MaxStoredPageLayoutsPerDocument = 5

// The viewport a null caller gets when nothing is stored for its style yet — the same guess
// ReaderViewModel used to pass directly before getPageWindows could resolve one itself.
private val DefaultViewportSize = ViewportSize(widthPx = 320, heightPx = 560)

private fun deleteAbandonedScratchCopies(keep: Path) {
    val fileSystem = systemFileSystem()
    val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
    runCatching { fileSystem.list(directory) }.getOrNull()?.forEach { candidate ->
        if (candidate == keep) return@forEach
        if (!candidate.name.startsWith(ScratchCopyPrefix)) return@forEach
        runCatching { fileSystem.delete(candidate) }
    }
}

private const val ScratchCopyPrefix = "tedd-reader-epub-open-"

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

private class StoredReaderDocument(
    val sections: List<ReaderSection>,
    val sectionBlocks: SectionBlocksCache,
    val title: String?,
    val navigationJson: String,
    val parserVersion: Int,
)

/** What [DocumentRepositoryImpl.loadReaderDocument] found: a document plus its on-demand block cache. */
private class LoadedReaderDocument(
    val document: ReaderDocument,
    val sectionBlocks: SectionBlocksCache?,
)

/** What a restore produced, alongside the cache that answered it — see [DocumentRepositoryImpl.getPageWindows]. */
private class RestoredPageWindowsResult(
    val windows: List<PageWindow>,
    val sectionBlocksCache: SectionBlocksCache?,
)

/**
 * A section's blocks, fetched from [searchIndexDao] and decoded the first time something actually
 * asks for that section, and remembered after that.
 *
 * [blocksFor] is called synchronously — from inside [RestoredPageWindows.get] while a page is being
 * built, sometimes from the main thread turning a page — so it can never suspend and must never touch
 * the database itself. It only ever answers from [decoded]; the fetching happens in [prewarm], called
 * ahead of time for the sections a caller knows it is about to need. A section nothing has fetched yet
 * answers empty, the same as a genuinely empty section would, until [prewarm] (or the background fill
 * that follows the first page publish) catches it up — see ReaderViewModel.openDocument for why an
 * empty answer here is safe: it can only ever leave a page's images/formatting momentarily missing,
 * never its text, which never depended on blocks in the first place.
 *
 * [blocksFor] answers relative to the section's own start, not as an absolute document offset — that
 * is how [persistParsedDocument] now writes `blocksJson` (see there for why). [TextPageLayoutEngine]
 * wants exactly that shape. A caller that wants the document's usual absolute addressing instead, like
 * [LazyFlattenedBlocks], has to shift it back itself.
 */
private class SectionBlocksCache(
    private val documentId: DocumentId,
    sectionIndexes: List<Int>,
    private val searchIndexDao: SearchIndexDao,
    private val decode: (String) -> List<ReaderBlock>,
) {
    private val knownSections: Set<Int> = sectionIndexes.toSet()

    // Read from blocksFor's synchronous, possibly-main-thread call and written from prewarm's suspend
    // call, on whatever background dispatcher fetched a batch — two different threads, neither ever
    // locking the other. Replacing the whole map on every fetch (rather than mutating one already
    // published) is what makes a concurrent read of this field always see a complete map or the one
    // before it, never a half-filled one.
    @Volatile
    private var decoded: Map<Int, List<ReaderBlock>> = emptyMap()

    fun blocksFor(sectionIndex: Int): List<ReaderBlock> = decoded[sectionIndex].orEmpty()

    /** Whether [sectionIndex]'s blocks are the section's real, decoded answer right now. */
    fun isReady(sectionIndex: Int): Boolean = sectionIndex !in knownSections || sectionIndex in decoded

    /**
     * Fetches and decodes whichever of [sectionIndexes] are not decoded yet, in one query. A section
     * this document doesn't have is filtered out instead of asking the database for a row that will
     * never exist.
     */
    suspend fun prewarm(sectionIndexes: Collection<Int>) {
        val missing = sectionIndexes.filterTo(linkedSetOf()) { it in knownSections && it !in decoded }
        if (missing.isEmpty()) return
        val rows = searchIndexDao.getSectionBlocksJson(documentId.value, missing.toList())
        if (rows.isEmpty()) return
        decoded = decoded + rows.associate { row -> row.sectionIndex to decode(row.blocksJson) }
    }

    /** How many distinct sections have actually been decoded — what an open logs to show the saving. */
    val decodedSectionCount: Int get() = decoded.size
}

/**
 * [ReaderDocument.blocks] for a document loaded from storage: every block in the book, flattened only
 * once something actually reads this list — a repair check or a caller that wants the whole document —
 * rather than as the price of opening it. Pagination itself never touches this; it asks [SectionBlocksCache]
 * for one section at a time instead.
 */
private class LazyFlattenedBlocks(
    private val sections: List<ReaderSection>,
    private val sectionBlocks: SectionBlocksCache,
) : AbstractList<ReaderBlock>() {
    // sectionBlocks.blocksFor answers relative to each section's own start; ReaderDocument.blocks is
    // documented as addressing the same absolute offsets as the rest of the document, so each
    // section's answer is shifted back before joining — otherwise two sections' blocks would land on
    // the same small numbers once concatenated, instead of the book's real, ascending offsets.
    private val flattened: List<ReaderBlock> by lazy {
        sections.flatMap { section -> sectionBlocks.blocksFor(section.index).rebasedBy(-section.range.start) }
    }
    override val size: Int get() = flattened.size
    override fun get(index: Int): ReaderBlock = flattened[index]
}

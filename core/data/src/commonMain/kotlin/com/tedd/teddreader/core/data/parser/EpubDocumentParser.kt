package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderNavigation
import com.tedd.teddreader.core.common.model.ReaderNavigationItem
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.isBlankIgnoringObjects
import kotlin.random.Random
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import org.koin.core.annotation.Single

/**
 * One chapter's raw XHTML, either read from a real EPUB's spine or supplied directly by a caller of
 * [EpubDocumentParser.parseChapters] that has no real EPUB container to parse from.
 */
data class EpubChapter(
    /** Chapter title; null falls back to `"Chapter N"` (1-based) in [EpubDocumentParser.parseChapters]. */
    val title: String?,
    /** The chapter's raw XHTML markup, parsed by [parseXhtmlContent]. */
    val xhtml: String,
    /** Where the chapter sits in the container, so its relative image references can be resolved. */
    val path: String? = null,
)

/**
 * What [EpubDocumentParser.parseWithCover] found: the document, plus the cover image bytes the parser
 * already decodes to size the cover on the page (see [fillIntrinsicImageSizes]) and would otherwise
 * discard. Kept so a caller that is about to persist the document can cache the cover on disk without
 * opening the whole book a second time later (see DocumentRepositoryImpl.importDocument).
 */
data class EpubParseResult(
    /** The parsed document. */
    val document: ReaderDocument,
    /**
     * The book's cover image bytes, if it declared and could decode one; see
     * [EpubDocumentParser.parseWithCover].
     */
    val coverBytes: ByteArray?,
)

/**
 * Parses an EPUB (a ZIP container following the OCF/OPF packaging spec) into a [ReaderDocument]: one
 * [ReaderSection] of flattened, paginatable text per readable chapter, plus the [ReaderBlock]s (images,
 * headings, tables, …) addressing ranges inside it. Cover extraction, embedded-image extraction, and a
 * caller-supplied-chapters entry point ([parseChapters]) that bypasses container parsing entirely all
 * live here alongside the main [parseWithCover]/[parse] pair.
 *
 * A cover, when the book declares one, is always synthesized as section 0 before any real chapter is
 * parsed, because settling it first — rather than discovering it while walking the spine — is what
 * keeps every other section's offset stable; doing it any later would have to shift every offset
 * already handed out to make room for it. See [parseWithCover] for the full parsing walk, and
 * [EpubXhtmlParser]/[EpubCssEngine] for how one chapter's own markup and stylesheets
 * become text.
 */
@Single
open class EpubDocumentParser {
    /**
     * Parses the EPUB in [bytes] into a [ReaderDocument], discarding the cover bytes [parseWithCover]
     * also decodes along the way. Prefer [parseWithCover] instead when the caller is about to persist
     * the document, to avoid reopening the file later just to get its cover.
     *
     * @param id identity of the source file, carried through unchanged.
     * @param title fallback title, used only when the EPUB's own `dc:title` metadata is absent.
     * @param bytes the EPUB's raw contents (a ZIP archive).
     * @return the parsed [ReaderDocument].
     */
    fun parse(
        id: DocumentId,
        title: String,
        bytes: ByteArray,
    ): ReaderDocument = parseWithCover(id = id, title = title, bytes = bytes).document

    /**
     * Parses the EPUB already on disk at [path] into a [ReaderDocument], discarding the cover bytes
     * [parseWithCover] also decodes. See the `bytes` overload of [parse] for when to prefer
     * [parseWithCover] instead.
     *
     * @param id identity of the source file, carried through unchanged.
     * @param title fallback title, used only when the EPUB's own `dc:title` metadata is absent.
     * @param path location of the EPUB file, opened directly as a ZIP with no temporary copy.
     * @param fileSystem file system [path] is read through; defaults to the real platform file system,
     *   overridable so a caller (or test) can hand in a substituted one.
     * @return the parsed [ReaderDocument].
     */
    fun parse(
        id: DocumentId,
        title: String,
        path: Path,
        fileSystem: FileSystem = systemFileSystem(),
    ): ReaderDocument = parseWithCover(id = id, title = title, path = path, fileSystem = fileSystem).document

    /**
     * Same as [parse], but also returns the cover bytes the parser decoded along the way — see
     * [EpubParseResult].
     *
     * [bytes] is spilled to a uniquely-named temporary file so the rest of parsing can work through
     * [okio.FileSystem.openZip] the same way the [path] overload does; the scratch file is always
     * deleted afterward, whether parsing succeeds or throws. The file's name is a random token rather
     * than [id]: naming it after the document's source URI once pushed the name past the 255-byte limit
     * a file system allows for one path component, so importing a folder of EPUBs failed on every file
     * with `ENAMETOOLONG`. The name only has to stay unique for as long as the file exists, which a
     * random token already guarantees without that risk.
     *
     * @param id identity of the source file, carried through unchanged.
     * @param title fallback title, used only when the EPUB's own `dc:title` metadata is absent.
     * @param bytes the EPUB's raw contents.
     * @return the parsed document plus its cover bytes, if any.
     */
    fun parseWithCover(
        id: DocumentId,
        title: String,
        bytes: ByteArray,
    ): EpubParseResult {
        val fileSystem = systemFileSystem()
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "tedd-reader-epub-${Random.nextLong().toString(16)}.epub"
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
        return try {
            parseWithCover(id = id, title = title, path = path, fileSystem = fileSystem)
        } finally {
            fileSystem.delete(path)
        }
    }

    /**
     * Same as [parse], but also returns the cover bytes the parser decoded along the way — see
     * [EpubParseResult]. This is where an EPUB is actually parsed; the `bytes` overload of
     * [parseWithCover] only spills its input to a temporary file first and delegates here.
     *
     * The container's `META-INF/container.xml` is read first to find the OPF (package document) path.
     * When an EPUB has no OPF at all — malformed, or not really an EPUB — this falls back to treating
     * every `.xhtml`/`.html` entry in the archive as its own chapter via [parseChapters], with no cover,
     * no navigation, and each chapter named after its own file, because there is no package metadata to
     * do anything better with. When an OPF is found, its package data (spine, manifest, cover, title,
     * navigation document or NCX path) is parsed once via [parsePackageData] and drives everything below.
     *
     * If the OPF names a cover image and its bytes can be read, a synthetic one-character
     * [ReaderBlockKind.COVER_IMAGE] section is emitted first, before any chapter — see this class's own
     * doc for why that has to happen before any other offset is assigned. Only *linear* spine items
     * become chapters; a spine item's `linear="no"` is the book's own instruction that it is not part of
     * the normal reading order (an ad page, an alternate-format duplicate, …), and it is skipped
     * outright, never becoming a section. When a cover section was emitted and the very first linear
     * spine item turns out to contain nothing but that same cover picture ([isPureCoverXhtml]), that
     * item is skipped too — its content is already shown by the synthetic section — but its own path
     * is still recorded against the cover section, so a navigation entry that targets that chapter file
     * resolves to the cover rather than to nothing. Each remaining spine item is parsed with
     * [parseXhtmlContent], resolving its image references against its own place in the container and
     * passing in the chapter's CSS cascade ([linkedCss]) —
     * cached by stylesheet set, since a book typically reuses the same few sheets across hundreds
     * of chapters. A section's title is resolved title-attribute-of-heading-image first
     * ([XhtmlContent.headingTitle]), then the chapter's own first heading text ([firstHeadingTitle]),
     * then the manifest item's `title`, and finally its raw `id` as a last resort when nothing else
     * names the chapter at all. A spine item whose XHTML cannot be read (a missing or unreadable entry)
     * is skipped without failing the whole parse.
     *
     * Once every section is known, navigation ([resolveNavigation]) is resolved against the section
     * paths and anchor offsets recorded while parsing, and any section a nav entry targets at offset
     * zero is retitled to that entry's own label — letting the book's own table of contents override a
     * heading- or manifest-derived title where the two disagree. Finally [fillIntrinsicImageSizes]
     * patches every image block with the aspect ratio (and, where nothing else declared one, the
     * natural pixel width) read from the picture's own bytes: the cover's bytes are reused from what was
     * already decoded above rather than read a second time, and every other image's header is read
     * fresh out of the archive.
     *
     * @param id identity of the source file, carried through unchanged.
     * @param title fallback title, used only when the EPUB's own `dc:title` metadata is absent.
     * @param path location of the EPUB file.
     * @param fileSystem file system [path] is read through; defaults to the real platform file system.
     * @return the parsed document plus its cover bytes, if any.
     */
    fun parseWithCover(
        id: DocumentId,
        title: String,
        path: Path,
        fileSystem: FileSystem = systemFileSystem(),
    ): EpubParseResult {
        val zip = fileSystem.openZip(path)
        val opfPath = zip.readUtf8OrNull(ContainerPath.toPath())?.let(::findRootFilePath)
        if (opfPath == null) {
            val fallbackChapters = zip.listRecursively("/".toPath())
                .filter { it.name.endsWith(".xhtml") || it.name.endsWith(".html") }
                .mapNotNull { candidate ->
                    zip.readUtf8OrNull(candidate)?.let { xhtml ->
                        EpubChapter(title = candidate.name, xhtml = xhtml, path = candidate.toString())
                    }
                }
                .toList()
            return EpubParseResult(document = parseChapters(id, title, fallbackChapters), coverBytes = null)
        }

        val opf = zip.readUtf8OrNull(opfPath).orEmpty()
        val packageData = parsePackageData(opf = opf, opfPath = opfPath)
        val documentTitle = packageData.documentTitle ?: title
        val coverHref = packageData.coverHref
        val coverBytes = coverHref?.let { zip.readBytesOrNull(it.toPath()) }
        val parsedNavigation = packageData.navigationItemPath?.let { navPath ->
            zip.readUtf8OrNull(navPath.toPath())?.let(::parseEpubNavDocument)
        } ?: packageData.ncxPath?.let { ncxPath ->
            zip.readUtf8OrNull(ncxPath.toPath())?.let(::parseNcxDocument)
        } ?: ParsedNavigation()

        val sections = mutableListOf<ReaderSection>()
        val blocks = mutableListOf<ReaderBlock>()
        val sectionStartOffsets = linkedMapOf<Int, Long>()
        val sectionAnchorOffsets = linkedMapOf<Int, Map<String, Long>>()
        val sectionPathByIndex = linkedMapOf<Int, String>()
        var nextOffset = 0L
        var nextIndex = 0
        var coverSectionIndex: Int? = null

        if (coverHref != null && coverBytes != null) {
            val coverRange = TextRange(nextOffset, nextOffset + 1)
            sections += ReaderSection(
                index = nextIndex,
                text = " ",
                range = coverRange,
                title = documentTitle,
            )
            blocks += ReaderBlock(
                kind = ReaderBlockKind.COVER_IMAGE,
                range = coverRange,
                imageHref = coverHref,
                label = documentTitle,
            )
            sectionStartOffsets[nextIndex] = coverRange.start
            sectionPathByIndex[nextIndex] = coverHref
            coverSectionIndex = nextIndex
            nextIndex += 1
            nextOffset = coverRange.end + SectionSeparatorLength
        }

        val cssCache = mutableMapOf<String, EpubCss>()
        packageData.spineItems.filter { it.linear }.forEachIndexed { spineOrder, spineItem ->
            val xhtml = zip.readUtf8OrNull(spineItem.path.toPath()) ?: return@forEachIndexed
            val content = parseXhtmlContent(
                xhtml = xhtml,
                baseOffset = nextOffset,
                resolveImageHref = { source -> resolveContainerHref(spineItem.path, source) },
                css = linkedCss(xhtml, spineItem.path, zip, cssCache),
            )
            if (coverHref != null && spineOrder == 0 && isPureCoverXhtml(content, coverHref)) {
                if (coverSectionIndex != null) sectionPathByIndex[coverSectionIndex] = spineItem.path
                return@forEachIndexed
            }
            val sectionTitle = content.headingTitle
                ?: firstHeadingTitle(content, nextOffset)
                ?: spineItem.item.title
                ?: spineItem.item.id
            val range = TextRange(nextOffset, nextOffset + content.text.length)
            val sectionIndex = nextIndex
            sections += ReaderSection(
                index = sectionIndex,
                text = content.text,
                range = range,
                title = sectionTitle,
            )
            blocks += content.blocks
            sectionStartOffsets[sectionIndex] = range.start
            sectionAnchorOffsets[sectionIndex] = content.anchors
            sectionPathByIndex[sectionIndex] = spineItem.path
            nextIndex += 1
            nextOffset = range.end + SectionSeparatorLength
        }

        val firstReadableContentSectionIndex = sections.firstOrNull { section ->
            section.index != coverSectionIndex && section.text.isNotBlank()
        }?.index
        val navigation = resolveNavigation(
            navigation = parsedNavigation,
            sectionPathByIndex = sectionPathByIndex,
            sectionStartOffsets = sectionStartOffsets,
            sectionAnchorOffsets = sectionAnchorOffsets,
            coverSpineIndex = coverSectionIndex,
            firstReadableContentSectionIndex = firstReadableContentSectionIndex,
            navigationBasePath = packageData.navigationItemPath ?: packageData.ncxPath ?: opfPath.toString(),
        )
        val navigationTitlesBySection = navigation.items
            .filter { it.offset == 0L }
            .associateBy({ it.spineIndex }, { it.title })
        val retitledSections = sections.map { section ->
            section.copy(title = navigationTitlesBySection[section.index] ?: section.title)
        }

        fillIntrinsicImageSizes(blocks, zip, coverHref, coverBytes)

        return EpubParseResult(
            document = ReaderDocument(
                id = id,
                format = DocumentFormat.EPUB,
                title = documentTitle,
                sections = retitledSections,
                blocks = blocks,
                navigation = navigation,
            ),
            coverBytes = coverBytes,
        )
    }

    /**
     * The book's cover image bytes, decoded by re-reading just enough of [bytes] to find and extract
     * the cover — the OPF and the cover entry itself, not the whole book.
     *
     * @param bytes the EPUB's raw contents, spilled to a temporary file (same reasoning as
     *   [parseWithCover]'s `bytes` overload) so it can be opened as a ZIP.
     * @return the cover image's raw bytes, or null if the EPUB declares no cover, has no OPF, or the
     *   declared cover entry could not be read.
     */
    fun coverImageBytes(bytes: ByteArray): ByteArray? {
        val fileSystem = systemFileSystem()
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "tedd-reader-epub-cover-${Random.nextLong().toString(16)}.epub"
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
        return try {
            coverImageBytes(path, fileSystem)
        } finally {
            fileSystem.delete(path)
        }
    }

    /**
     * Reads the raw bytes of whichever entries in [hrefs] exist inside the EPUB held in [bytes],
     * without parsing the rest of the book at all.
     *
     * @param bytes the EPUB's raw contents, spilled to a temporary file so it can be opened as a ZIP.
     * @param hrefs container-relative paths to read; blank entries are dropped, and a set left empty
     *   after that trimming short-circuits without opening the archive at all.
     * @return bytes keyed by the (trimmed) href that produced them; an href with no matching entry, or
     *   whose entry could not be read, is simply absent from the result.
     */
    fun extractEmbeddedImageBytes(
        bytes: ByteArray,
        hrefs: Set<String>,
    ): Map<String, ByteArray> {
        if (hrefs.isEmpty()) return emptyMap()
        val normalizedHrefs = hrefs.map(String::trim).filter(String::isNotEmpty).toSet()
        if (normalizedHrefs.isEmpty()) return emptyMap()
        val fileSystem = systemFileSystem()
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "tedd-reader-epub-embedded-${Random.nextLong().toString(16)}.epub"
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
        return try {
            extractEmbeddedImageBytes(
                path = path,
                hrefs = normalizedHrefs,
                fileSystem = fileSystem,
            )
        } finally {
            fileSystem.delete(path)
        }
    }

    /**
     * Builds a [ReaderDocument] directly from a caller-supplied list of [chapters], bypassing the
     * container/OPF machinery entirely — used both by [parseWithCover]'s no-OPF fallback and by any
     * caller that already has chapter XHTML from somewhere other than a real EPUB container.
     *
     * There is no stylesheet or CSS cascade here — each chapter is parsed with [parseXhtmlContent]'s
     * own defaults — and no navigation beyond an empty [ReaderNavigation], since a caller assembling
     * chapters by hand has no OPF-derived table of contents to resolve. A chapter with no title falls
     * back to `"Chapter N"` (1-based). Sections are read back as one document joined by a single
     * newline, so that separator has to be counted as part of each chapter's own offset; leaving it out
     * drifted every later chapter's range by one character per chapter already seen, which put reading
     * position and search hits in the wrong chapter deep into a book.
     *
     * @param id identity of the source file, carried through unchanged.
     * @param title label for the whole document.
     * @param chapters the chapters to parse, in reading order.
     * @return a [ReaderDocument] of [DocumentFormat.EPUB] with one section per chapter and no navigation.
     */
    fun parseChapters(
        id: DocumentId,
        title: String,
        chapters: List<EpubChapter>,
    ): ReaderDocument {
        var offset = 0L
        val sections = mutableListOf<ReaderSection>()
        val blocks = mutableListOf<ReaderBlock>()

        chapters.forEachIndexed { index, chapter ->
            val content = parseXhtmlContent(
                xhtml = chapter.xhtml,
                baseOffset = offset,
                resolveImageHref = { source -> resolveContainerHref(chapter.path, source) },
            )
            sections += ReaderSection(
                index = index,
                title = chapter.title ?: "Chapter ${index + 1}",
                text = content.text,
                range = TextRange(offset, offset + content.text.length),
            )
            blocks += content.blocks
            offset += content.text.length + SectionSeparatorLength
        }
        return ReaderDocument(
            id = id,
            format = DocumentFormat.EPUB,
            title = title,
            sections = sections.toList(),
            blocks = blocks.toList(),
            navigation = ReaderNavigation(),
        )
    }

    /**
     * The book's cover image bytes, read directly from the EPUB already on disk at [path].
     *
     * Preferred over the `bytes` overload whenever the caller can stream the file to disk itself: that
     * overload has to hold the entire book in memory before it can even open it as a ZIP, so an
     * illustrated book of a few hundred megabytes paid its whole size in heap just to reach one
     * picture. Only the cover entry is read out of the archive here.
     *
     * @param path location of the EPUB file.
     * @param fileSystem file system [path] is read through; defaults to the real platform file system.
     * @return the cover image's raw bytes, or null if the EPUB declares no cover, has no OPF, or the
     *   declared cover entry could not be read.
     */
    fun coverImageBytes(
        path: Path,
        fileSystem: FileSystem = systemFileSystem(),
    ): ByteArray? {
        val zip = fileSystem.openZip(path)
        val opfPath = zip.readUtf8OrNull(ContainerPath.toPath())?.let(::findRootFilePath) ?: return null
        val opf = zip.readUtf8OrNull(opfPath) ?: return null
        val coverPath = findEpubCoverHref(opf, opfPath) ?: return null
        return zip.readBytesOrNull(coverPath.toPath())
    }

    /**
     * Pull images straight out of an already-unpacked copy, without re-reading the whole book.
     *
     * @param path location of the EPUB file.
     * @param hrefs container-relative paths to read.
     * @param fileSystem file system [path] is read through; defaults to the real platform file system.
     * @return bytes keyed by the href that produced them; an href with no matching entry, or whose
     *   entry could not be read, is simply absent from the result.
     */
    internal open fun extractEmbeddedImageBytes(
        path: Path,
        hrefs: Set<String>,
        fileSystem: FileSystem = systemFileSystem(),
    ): Map<String, ByteArray> {
        val zip = fileSystem.openZip(path)
        return hrefs.mapNotNull { href -> zip.readBytesOrNull(href.toPath())?.let { href to it } }.toMap()
    }
}

/**
 * Everything a progressive EPUB import needs before it can parse a single spine item on its own — the
 * OPF's own decisions, settled once (see class doc: settling the cover any later shifts every offset
 * after it) and never revisited by a later batch. Null from [openEpubImportContainer] only when the
 * EPUB has no OPF at all — the one case [EpubDocumentParser.parseWithCover]'s existing fallback-chapters
 * path already handles as a single, non-progressive parse, because there is no spine to stream.
 */
internal class EpubImportContainer(
    /**
     * The open archive; every spine item and stylesheet is read back out of this across the whole import.
     */
    val zip: FileSystem,
    /** Path of the OPF package document inside the container. */
    val opfPath: Path,
    /** The book's title: its own `dc:title`, or the caller-supplied fallback when it has none. */
    val documentTitle: String,
    /** The OPF's parsed manifest, spine, navigation/NCX paths, and cover href. */
    val packageData: PackageData,
    /**
     * What was settled about the cover before any spine item was parsed; see [resolveEpubCoverDecision].
     */
    val coverDecision: EpubCoverDecision,
) {
    /**
     * [PackageData.spineItems] narrowed to the linear ones — the only spine items that ever become a
     * section.
     */
    val linearSpineItems: List<SpineItem> = packageData.spineItems.filter { it.linear }
    /** Shared across progressive spine parsing so the same linked CSS cascade is parsed once per import. */
    val linkedCssCache = mutableMapOf<String, EpubCss>()
}

/** What [openEpubImportContainer] settles about the cover before any spine item is parsed for real. */
internal data class EpubCoverDecision(
    /** The cover's container-relative path, or null if the OPF declares no cover. */
    val coverHref: String?,
    /** The cover image's raw bytes, or null if there is no cover or its entry could not be read. */
    val coverBytes: ByteArray?,
    /** True when both [coverHref] and [coverBytes] are non-null — a real cover section will be shown. */
    val hasCoverSection: Boolean,
    /** True when the first linear spine item is nothing but the cover picture its own synthetic
     * section already shows — that item is then skipped by the spine loop, never becoming a section
     * of its own (mirrors the `isPureCoverXhtml` check inside [EpubDocumentParser.parseWithCover]). */
    val spineOrder0Skipped: Boolean,
)

/** One newly parsed section, plus its blocks — the unit [DocumentRepositoryImpl.importNextSections]
 * (core:data repository) appends per step of a progressive import. */
internal data class EpubParsedSection(
    /** The section itself. */
    val section: ReaderSection,
    /** Blocks whose ranges fall inside [section]. */
    val blocks: List<ReaderBlock>,
)

/**
 * Opens [zip] as a progressive-import container: reads its OPF once and settles everything about its
 * cover up front, so each spine item can later be parsed on its own via [parseEpubSpineItem] without
 * re-deriving any of this.
 *
 * @param zip the already-opened EPUB archive.
 * @param title fallback document title, used only when the OPF has no `dc:title`.
 * @return the opened container, or null if [zip] has no `META-INF/container.xml` pointing at an OPF —
 *   the one case a progressive import cannot proceed at all, because there is no spine to stream; the
 *   caller falls back to [EpubDocumentParser.parseChapters]'s single-shot, non-progressive path instead.
 */
internal fun openEpubImportContainer(zip: FileSystem, title: String): EpubImportContainer? {
    val opfPath = zip.readUtf8OrNull(ContainerPath.toPath())?.let(::findRootFilePath) ?: return null
    val opf = zip.readUtf8OrNull(opfPath).orEmpty()
    val packageData = parsePackageData(opf = opf, opfPath = opfPath)
    return EpubImportContainer(
        zip = zip,
        opfPath = opfPath,
        documentTitle = packageData.documentTitle ?: title,
        packageData = packageData,
        coverDecision = resolveEpubCoverDecision(zip, packageData),
    )
}

/**
 * Settles the [EpubCoverDecision] for [packageData]'s book: whether it has a cover, what its bytes are,
 * and whether the first linear spine item is nothing but that same cover picture (in which case the
 * spine loop must skip it rather than showing the cover twice).
 *
 * @param zip the already-opened EPUB archive, used to read the cover entry and, if needed, the first
 *   spine item's XHTML.
 * @param packageData the OPF's parsed manifest, spine, and cover href.
 */
private fun resolveEpubCoverDecision(zip: FileSystem, packageData: PackageData): EpubCoverDecision {
    val coverHref = packageData.coverHref
    val coverBytes = coverHref?.let { zip.readBytesOrNull(it.toPath()) }
    val firstLinearItem = packageData.spineItems.firstOrNull { it.linear }
    val spineOrder0Skipped = if (coverHref != null && firstLinearItem != null) {
        val itemPath = firstLinearItem.path
        val xhtml = zip.readUtf8OrNull(itemPath.toPath())
        xhtml != null && isPureCoverXhtml(
            parseXhtmlContent(
                xhtml = xhtml,
                baseOffset = 0L,
                resolveImageHref = { source -> resolveContainerHref(itemPath, source) },
                css = linkedCss(xhtml, itemPath, zip, mutableMapOf()),
            ),
            coverHref,
        )
    } else {
        false
    }
    return EpubCoverDecision(
        coverHref = coverHref,
        coverBytes = coverBytes,
        hasCoverSection = coverHref != null && coverBytes != null,
        spineOrder0Skipped = spineOrder0Skipped,
    )
}

/**
 * The synthetic section [EpubDocumentParser.parseWithCover] gives a book's own cover picture — always
 * section 0, since a cover (when [EpubCoverDecision.hasCoverSection]) is always the very first thing a
 * book parses, progressively or not.
 *
 * @param coverDecision the book's settled cover state; see [resolveEpubCoverDecision].
 * @param documentTitle title to label the synthetic section with.
 * @return the one-character cover section and its single [ReaderBlockKind.COVER_IMAGE] block, or null
 *   when [coverDecision] has no cover section to build.
 */
internal fun buildEpubCoverSection(coverDecision: EpubCoverDecision, documentTitle: String): EpubParsedSection? {
    val coverHref = coverDecision.coverHref?.takeIf { coverDecision.hasCoverSection } ?: return null
    val range = TextRange(0L, 1L)
    return EpubParsedSection(
        section = ReaderSection(index = 0, text = " ", range = range, title = documentTitle),
        blocks = listOf(ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = range, imageHref = coverHref, label = documentTitle)),
    )
}

/**
 * Parses spine item [spinePosition] of [EpubImportContainer.linearSpineItems] exactly as
 * [EpubDocumentParser.parseWithCover]'s own loop would at that same position, laying its text at
 * [baseOffset] and numbering it [sectionIndex]. Null for the one case that loop itself can skip
 * outright — [spinePosition] 0 being nothing but [EpubImportContainer.coverDecision]'s own cover
 * picture — or for a spine item whose xhtml cannot be read at all.
 *
 * @param container the opened import container; see [openEpubImportContainer].
 * @param spinePosition index into [EpubImportContainer.linearSpineItems] of the item to parse.
 * @param sectionIndex section index to record on the result.
 * @param baseOffset absolute offset this section's text should start at.
 * @return the parsed section and its blocks, or null for the skip cases described above.
 */
internal fun parseEpubSpineItem(
    container: EpubImportContainer,
    spinePosition: Int,
    sectionIndex: Int,
    baseOffset: Long,
): EpubParsedSection? {
    val spineItem = container.linearSpineItems.getOrNull(spinePosition) ?: return null
    val xhtml = container.zip.readUtf8OrNull(spineItem.path.toPath()) ?: return null
    val content = parseXhtmlContent(
        xhtml = xhtml,
        baseOffset = baseOffset,
        resolveImageHref = { source -> resolveContainerHref(spineItem.path, source) },
        css = linkedCss(xhtml, spineItem.path, container.zip, container.linkedCssCache),
    )
    val coverHref = container.coverDecision.coverHref
    if (spinePosition == 0 && coverHref != null && isPureCoverXhtml(content, coverHref)) return null
    val sectionTitle = content.headingTitle
        ?: firstHeadingTitle(content, baseOffset)
        ?: spineItem.item.title
        ?: spineItem.item.id
    val range = TextRange(baseOffset, baseOffset + content.text.length)
    return EpubParsedSection(
        section = ReaderSection(index = sectionIndex, text = content.text, range = range, title = sectionTitle),
        blocks = content.blocks,
    )
}

/**
 * Resolves [container]'s navigation once every section is known — called only once, when a progressive
 * import's last batch exhausts the spine, instead of after every batch. [sectionPathByIndex] is a pure
 * function of spine position (see DocumentRepositoryImpl.buildSectionPathByIndex), not anything a batch
 * needs to remember along the way. A nav entry that targets a fragment inside a section resolves to
 * that section's own start rather than the fragment's exact offset — the same graceful fallback
 * [resolveNavigation] already gives a fragment it cannot place.
 *
 * @param container the opened import container the navigation document/NCX is read from.
 * @param sectionPathByIndex every section built so far, keyed by index, mapped to its container path.
 * @param coverSectionIndex index of the synthetic cover section, or null if the book has none.
 * @param firstReadableContentSectionIndex index of the first section with non-blank text, or null if
 *   none was found — used the same way [resolveNavigation] uses it, as where a nav entry that targets
 *   the cover should redirect a reader to instead.
 * @return the resolved [ReaderNavigation].
 */
internal fun resolveEpubNavigationAtCompletion(
    container: EpubImportContainer,
    sectionPathByIndex: Map<Int, String>,
    coverSectionIndex: Int?,
    firstReadableContentSectionIndex: Int?,
): ReaderNavigation {
    val packageData = container.packageData
    val parsedNavigation = packageData.navigationItemPath?.let { navPath ->
        container.zip.readUtf8OrNull(navPath.toPath())?.let(::parseEpubNavDocument)
    } ?: packageData.ncxPath?.let { ncxPath ->
        container.zip.readUtf8OrNull(ncxPath.toPath())?.let(::parseNcxDocument)
    } ?: ParsedNavigation()
    return resolveNavigation(
        navigation = parsedNavigation,
        sectionPathByIndex = sectionPathByIndex,
        sectionStartOffsets = emptyMap(),
        sectionAnchorOffsets = emptyMap(),
        coverSpineIndex = coverSectionIndex,
        firstReadableContentSectionIndex = firstReadableContentSectionIndex,
        navigationBasePath = packageData.navigationItemPath ?: packageData.ncxPath ?: container.opfPath.toString(),
    )
}

/**
 * One `<item>` from the OPF manifest — id, target, title, media type, and any properties (like
 * `cover-image`) it carries. Internal rather than private: [EpubImportContainer] exposes these to
 * `DocumentRepositoryImpl`'s progressive import, which needs the spine list and manifest items a batch
 * at a time rather than all at once.
 */
internal data class ManifestItem(
    /** The manifest item's own `id` attribute, referenced by a spine `itemref`'s `idref`. */
    val id: String,
    /**
     * The item's `href`, relative to the OPF's own location; resolved to a container path via
     * [resolveContainerHref].
     */
    val href: String,
    /** The item's `title` attribute, if any. */
    val title: String?,
    /** The item's `media-type` attribute, if any. */
    val mediaType: String?,
    /**
     * The item's `properties` attribute (space-separated tokens such as `cover-image` or `nav`), if any.
     */
    val properties: String?,
)

/** One spine `<itemref>`, resolved to the manifest item it references and that item's container path. */
internal data class SpineItem(
    /** The manifest item this spine entry references. */
    val item: ManifestItem,
    /** [ManifestItem.href] resolved to a path inside the container. */
    val path: String,
    /**
     * False when the `<itemref>` declares `linear="no"` — the book's own instruction that this item is
     * not part of the normal reading order, so it never becomes a section.
     */
    val linear: Boolean,
)

/** One spine `<itemref>` before it has been matched to its manifest item. */
private data class SpineItemRef(
    /** The `idref` attribute, matched against a [ManifestItem.id]. */
    val idref: String,
    /** False when `linear="no"`; see [SpineItem.linear]. */
    val linear: Boolean,
)

/** The whole OPF's parsed contents, ready to drive either a one-shot or progressive EPUB parse. */
internal data class PackageData(
    /** The book's `dc:title`, or null if the OPF declares none. */
    val documentTitle: String?,
    /** Every spine `<itemref>`, resolved to its manifest item and container path, in spine order. */
    val spineItems: List<SpineItem>,
    /**
     * Container path of the EPUB 3 navigation document (the manifest item with `properties="nav"`), or
     * null if the book has none.
     */
    val navigationItemPath: String?,
    /** Container path of the EPUB 2 NCX document, or null if the book has none. */
    val ncxPath: String?,
    /**
     * Container path of the cover image, or null if the OPF declares no raster cover; see
     * [findEpubCoverHref].
     */
    val coverHref: String?,
)

/** A navigation entry's `href`, split into its container path and (if any) fragment. */
private data class ResolvedReference(
    /** Container-relative path, with any `#fragment` removed. */
    val path: String,
    /** The `#fragment` part, if the href had one. */
    val fragment: String?,
)

/** Reads [path]'s full contents as UTF-8, or null if it does not exist or could not be read. */
private fun FileSystem.readUtf8OrNull(path: Path): String? =
    runCatching {
        val source = source(path).buffer()
        try {
            source.readUtf8()
        } finally {
            source.close()
        }
    }.getOrNull()

/**
 * The cascade of every stylesheet [chapterPath] links, in `<link>` order so a later sheet wins a tie —
 * this is where these books keep most of their CSS besides picture width. Cached by the set of linked
 * stylesheets (their joined hrefs), because a book typically reuses the same few sheets across hundreds
 * of chapters.
 *
 * @param xhtml the chapter's raw markup, scanned for `<link rel="stylesheet">` tags.
 * @param chapterPath the chapter's own container path, used to resolve each linked sheet's `href`.
 * @param zip the archive the linked stylesheet(s) are read from.
 * @param cache stylesheet-set to parsed [EpubCss], shared across chapters by the caller so a repeated
 *   set of sheets is parsed only once.
 * @return [EpubCss.Empty] if the chapter links no stylesheet, otherwise the merged cascade.
 */
private fun linkedCss(
    xhtml: String,
    chapterPath: String,
    zip: FileSystem,
    cache: MutableMap<String, EpubCss>,
): EpubCss {
    val hrefs = linkedStyleSheetHrefs(xhtml, chapterPath)
    if (hrefs.isEmpty()) return EpubCss.Empty
    val key = hrefs.joinToString("|")
    cache[key]?.let { return it }
    val parsed = EpubCss.parseSources(
        hrefs.mapNotNull { href ->
            zip.readUtf8OrNull(href.toPath())?.let { css -> CssStyleSheetSource(path = href, css = css) }
        },
    )
    cache[key] = parsed
    return parsed
}

/**
 * The container paths of every stylesheet [xhtml] links via `<link rel="stylesheet">`, in document order.
 *
 * @param xhtml the chapter's raw markup.
 * @param chapterPath the chapter's own container path, used to resolve each `href`.
 */
private fun linkedStyleSheetHrefs(xhtml: String, chapterPath: String): List<String> =
    StyleSheetLinkRegex.findAll(xhtml)
        .map { match -> parseAttributes(match.value) }
        .filter { attributes -> attributes["rel"]?.contains("stylesheet", ignoreCase = true) == true }
        .mapNotNull { attributes -> attributes["href"]?.let { resolveContainerHref(chapterPath, it) } }
        .toList()

/**
 * Patches every image block with the size read from the picture's own bytes: the aspect ratio, and the
 * intrinsic width that sizes it when neither the markup nor the stylesheet declares one. Mutates
 * [blocks] in place.
 *
 * Internal rather than private: `DocumentRepositoryImpl.importNextSections` calls this once per batch
 * of newly parsed blocks during a progressive import — the same sizing pass a one-shot parse runs once
 * over the whole book.
 *
 * @param blocks blocks to patch in place; only [ReaderBlockKind.IMAGE] and [ReaderBlockKind.COVER_IMAGE]
 *   blocks are touched.
 * @param zip the archive each non-cover image's header is read from.
 * @param coverHref the book's cover path, so the already-decoded [coverBytes] can be reused for it
 *   instead of read a second time; null if the book has no cover.
 * @param coverBytes the cover's already-decoded bytes; null if there is no cover or its bytes were
 *   unavailable.
 */
internal fun fillIntrinsicImageSizes(
    blocks: MutableList<ReaderBlock>,
    zip: FileSystem,
    coverHref: String?,
    coverBytes: ByteArray?,
) {
    val hrefs = blocks.asSequence()
        .filter { it.kind == ReaderBlockKind.IMAGE || it.kind == ReaderBlockKind.COVER_IMAGE }
        .mapNotNull { it.imageHref }
        .toSet()
    if (hrefs.isEmpty()) return

    val sizes = mutableMapOf<String, Pair<Int, Int>>()
    if (coverHref != null && coverHref in hrefs && coverBytes != null) {
        sniffImageDimensions(coverBytes)?.let { sizes[coverHref] = it }
    }
    (hrefs - sizes.keys).forEach { href ->
        val header = zip.readHeaderBytesOrNull(href.toPath(), ImageHeaderSniffBytes) ?: return@forEach
        sniffImageDimensions(header)?.let { sizes[href] = it }
    }
    if (sizes.isEmpty()) return

    for (index in blocks.indices) {
        val block = blocks[index]
        val (width, height) = block.imageHref?.let(sizes::get) ?: continue
        blocks[index] = block.copy(
            imageAspectRatio = block.imageAspectRatio ?: (width.toFloat() / height),
            imageNaturalWidthPx = block.imageNaturalWidthPx ?: width,
        )
    }
}

/**
 * Reads up to [maxBytes] from the start of [path] — just enough to sniff an image's dimensions from its
 * header without reading the whole, possibly much larger, file.
 *
 * A single `read()` call is not guaranteed to fill the request even when more bytes remain, so this
 * loops up to the cap (or EOF) the same way [readBytesOrNull] does for a full file.
 *
 * @receiver the archive [path] is read from.
 * @param path entry to read.
 * @param maxBytes upper bound on how many bytes to read.
 * @return up to [maxBytes] bytes from the start of the entry, or null if it could not be opened or
 *   reading it threw.
 */
private fun FileSystem.readHeaderBytesOrNull(path: Path, maxBytes: Int): ByteArray? {
    val source = runCatching { source(path).buffer() }.getOrNull() ?: return null
    return try {
        val buffer = Buffer()
        while (buffer.size < maxBytes) {
            if (source.read(buffer, maxBytes - buffer.size) == -1L) break
        }
        buffer.readByteArray()
    } catch (_: Throwable) {
        null
    } finally {
        source.close()
    }
}

/**
 * Reads [path]'s full contents, capped at [MAX_EPUB_IMAGE_BYTES] so a single malformed or
 * unexpectedly huge entry cannot blow past the memory a cover or embedded image should ever need.
 *
 * @receiver the archive [path] is read from.
 * @param path entry to read.
 * @return the entry's bytes, or null if it could not be opened, reading it threw, or it exceeded
 *   [MAX_EPUB_IMAGE_BYTES].
 */
private fun FileSystem.readBytesOrNull(path: Path): ByteArray? {
    val source = runCatching { source(path).buffer() }.getOrNull() ?: return null
    return try {
        val buffer = Buffer()
        var totalBytes = 0L
        while (true) {
            val read = source.read(buffer, 8_192)
            if (read == -1L) break
            totalBytes += read
            if (totalBytes > MAX_EPUB_IMAGE_BYTES) return null
        }
        buffer.readByteArray()
    } catch (_: Throwable) {
        null
    } finally {
        source.close()
    }
}

/**
 * The OPF's path, from `META-INF/container.xml`'s `<rootfile full-path="...">`, or null if it is missing or
 * malformed.
 */
private fun findRootFilePath(containerXml: String): Path? =
    Regex("""full-path\s*=\s*["']([^"']+)["']""")
        .find(containerXml)
        ?.groupValues
        ?.get(1)
        ?.toPath()

/**
 * Parses [opf] into [PackageData]: its title, spine (resolved to manifest items and container paths),
 * navigation document / NCX path, and cover href.
 *
 * @param opf the OPF package document's raw XML.
 * @param opfPath the OPF's own container path, used to resolve every referenced item's `href` relative
 *   to where the OPF itself sits.
 */
private fun parsePackageData(opf: String, opfPath: Path): PackageData {
    val manifest = parseManifest(opf)
    val spineRefs = parseSpine(opf)
    val spineItems = spineRefs.mapNotNull { ref ->
        val item = manifest[ref.idref]?.takeIf { it.isChapterItem() } ?: return@mapNotNull null
        val path = resolveContainerHref(opfPath.toString(), item.href) ?: return@mapNotNull null
        SpineItem(item = item, path = path, linear = ref.linear)
    }
    val spineTocId = Regex("""(?is)<spine\b[^>]*toc\s*=\s*["']([^"']+)["']""")
        .find(opf)
        ?.groupValues
        ?.get(1)
    val navPath = manifest.values
        .firstOrNull { navTypeTokens(it.properties).contains("nav") }
        ?.let { resolveContainerHref(opfPath.toString(), it.href) }
    val ncxPath = spineTocId?.let { manifest[it] }
        ?.takeIf { it.mediaType.equals(NcxMediaType, ignoreCase = true) }
        ?.let { resolveContainerHref(opfPath.toString(), it.href) }
        ?: manifest.values.firstOrNull { it.mediaType.equals(NcxMediaType, ignoreCase = true) }
            ?.let { resolveContainerHref(opfPath.toString(), it.href) }
    return PackageData(
        documentTitle = parseDcTitle(opf),
        spineItems = spineItems,
        navigationItemPath = navPath,
        ncxPath = ncxPath,
        coverHref = findEpubCoverItem(opf, manifest, opfPath),
    )
}

/**
 * The OPF's `<dc:title>`, with its own inner markup stripped and entities decoded, or null if absent or
 * blank.
 */
private fun parseDcTitle(opf: String): String? =
    Regex("""(?is)<dc:title\b[^>]*>(.*?)</dc:title>""")
        .find(opf)
        ?.groupValues
        ?.get(1)
        ?.let(::stripMarkup)
        ?.takeIf(String::isNotBlank)

/**
 * Every `<item>` in the OPF's `<manifest>`, keyed by its `id`; an item missing `id` or `href` is dropped.
 */
private fun parseManifest(opf: String): Map<String, ManifestItem> =
    Regex("""(?is)<item\b[^>]*>""")
        .findAll(opf)
        .mapNotNull { match ->
            val attrs = parseAttributes(match.value)
            val id = attrs["id"] ?: return@mapNotNull null
            val href = attrs["href"] ?: return@mapNotNull null
            id to ManifestItem(
                id = id,
                href = decodeXmlEntities(href),
                title = attrs["title"]?.let(::decodeXmlEntities),
                mediaType = attrs["media-type"],
                properties = attrs["properties"],
            )
        }
        .toMap()

/** Every `<itemref>` in the OPF's `<spine>`, in document order; an entry missing `idref` is dropped. */
private fun parseSpine(opf: String): List<SpineItemRef> =
    Regex("""(?is)<itemref\b[^>]*>""")
        .findAll(opf)
        .mapNotNull { match ->
            val attrs = parseAttributes(match.value)
            val idref = attrs["idref"] ?: return@mapNotNull null
            SpineItemRef(idref = idref, linear = !attrs["linear"].equals("no", ignoreCase = true))
        }
        .toList()

/**
 * The book's cover image path, tried in the order real EPUBs actually declare one; see
 * [findEpubCoverItem] for the exact fallback chain.
 *
 * Parses the manifest itself, so it stays a self-contained entry point for a caller that has only the
 * OPF text in hand — [parsePackageData], which has already parsed the manifest for the spine, calls the
 * manifest-taking [findEpubCoverItem] overload instead to avoid parsing it a second time.
 *
 * @param opf the OPF package document's raw XML.
 * @param opfPath when non-null, resolves the found item's `href` to a container path; when null, the
 *   item's raw `href` is returned unresolved.
 * @return the cover's path, or null if the OPF declares no raster cover image at all.
 */
internal fun findEpubCoverHref(opf: String, opfPath: Path? = null): String? =
    findEpubCoverItem(opf, parseManifest(opf), opfPath)

/**
 * Finds the manifest item that is the book's cover, trying the ways real EPUBs actually declare one,
 * from most to least explicit: the EPUB 3 `properties="cover-image"` item; failing that, the EPUB 2
 * `<meta name="cover" content="...">` pointer to a manifest id; failing that, any raster image item
 * whose own id or href hints at being a cover. Every candidate must also be a raster image
 * ([ManifestItem.isRasterImage]) — an SVG cover is not resolved here, since this reader decodes covers
 * as raster bytes.
 *
 * Takes the already-parsed [manifest] rather than re-parsing [opf]'s `<item>`s, so a caller that has one
 * (its spine build needs it) pays for the manifest scan once; [opf] is still needed for the EPUB 2
 * `<meta name="cover">` pointer, which lives outside the manifest.
 *
 * @param opf the OPF package document's raw XML, read only for the EPUB 2 cover `<meta>` pointer.
 * @param manifest the OPF's manifest, id-keyed, already parsed by the caller.
 * @param opfPath when non-null, resolves the found item's `href` to a container path; when null, the
 *   item's raw `href` is returned unresolved.
 * @return the cover item's path, or null if none of the three ways finds a raster cover.
 */
private fun findEpubCoverItem(opf: String, manifest: Map<String, ManifestItem>, opfPath: Path? = null): String? {
    val raw = manifest.values.firstOrNull { it.isCoverImageProperty() && it.isRasterImage() }
        ?: findCoverMetaId(opf)?.let { manifest[it] }?.takeIf { it.isRasterImage() }
        ?: manifest.values.firstOrNull { it.isRasterImage() && it.hasCoverHint() }
        ?: return null
    return opfPath?.let { resolveContainerHref(it.toString(), raw.href) } ?: raw.href
}

/**
 * The manifest id an EPUB 2 `<meta name="cover" content="...">` points at, or null if the OPF has no such
 * tag.
 */
private fun findCoverMetaId(opf: String): String? =
    Regex("""(?is)<meta\b[^>]*>""")
        .findAll(opf)
        .mapNotNull { match ->
            val attrs = parseAttributes(match.value)
            attrs["content"]?.takeIf { attrs["name"]?.equals("cover", ignoreCase = true) == true }
        }
        .firstOrNull()

/**
 * Whether this item's media type is an `image/` type that is not SVG — the raster formats this reader can
 * decode as a cover.
 */
private fun ManifestItem.isRasterImage(): Boolean {
    val mediaType = mediaType ?: return false
    return mediaType.startsWith("image/", ignoreCase = true) && !mediaType.contains("svg", ignoreCase = true)
}

/**
 * Whether this manifest item is readable chapter content: an XHTML media type, or an `.html`/`.xhtml` href
 * when the media type itself does not say so.
 */
private fun ManifestItem.isChapterItem(): Boolean {
    val mediaType = mediaType.orEmpty()
    return mediaType.contains("xhtml", ignoreCase = true) ||
        href.endsWith(".html", ignoreCase = true) ||
        href.endsWith(".xhtml", ignoreCase = true)
}

/**
 * Whether this item's own id or href contains `"cover"` — the weakest of [findEpubCoverItem]'s three
 * fallbacks.
 */
private fun ManifestItem.hasCoverHint(): Boolean =
    id.contains("cover", ignoreCase = true) || href.contains("cover", ignoreCase = true)

/** Whether this item declares the EPUB 3 `properties="cover-image"` token. */
private fun ManifestItem.isCoverImageProperty(): Boolean = navTypeTokens(properties).contains("cover-image")

/**
 * Parses a tag's `name="value"`/`name='value'` attribute pairs into a lowercase-keyed map, for OPF/NCX/
 * navigation-document markup — a separate, simpler pass than [EpubXhtmlParser]'s own tag parsing, since
 * these documents need no block/inline interpretation.
 *
 * @param tag raw text of one tag, e.g. the `<item id="..." href="..."/>` matched by the caller's regex.
 */
internal fun parseAttributes(tag: String): Map<String, String> =
    Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        .findAll(tag)
        .associate { match ->
            match.groupValues[1].lowercase() to (match.groupValues[2].ifEmpty { match.groupValues[3] })
        }

/**
 * Resolves [source] — a raw `src`/`href` value straight out of markup — against [chapterPath]'s own
 * place in the container, into a path every other function here can look up directly against the ZIP.
 *
 * @param chapterPath container path of the document [source] was written in; null resolves relative to
 *   the container root instead.
 * @param source the raw reference, still percent- and entity-encoded and possibly carrying a `#fragment`.
 * @return the resolved container path (fragment stripped, `..`/`.` segments normalized away), or null
 *   if [source] is empty, is a `data:` URI, or is a remote `http(s)://` URL this reader cannot fetch.
 */
internal fun resolveContainerHref(chapterPath: String?, source: String): String? {
    val decodedSource = decodePercentEncoding(decodeXmlEntities(source.trim()))
    if (decodedSource.isEmpty() || decodedSource.startsWith("data:")) return null
    if (decodedSource.startsWith("http://") || decodedSource.startsWith("https://")) return null
    val cleaned = decodedSource.substringBefore('#')
    if (cleaned.isEmpty()) return null
    if (cleaned.startsWith("/")) return cleaned.removePrefix("/")
    val parent = chapterPath?.toPath()?.parent ?: return cleaned
    return parent.resolve(cleaned).normalized().toString().removePrefix("/")
}

/**
 * Like [resolveContainerHref], but for a navigation entry's `href`: keeps the `#fragment` instead of
 * discarding it, and treats a bare `#fragment` (no path at all) as pointing at [basePath] itself.
 *
 * @param basePath container path of the navigation document [source] was written in.
 * @param source the raw `href`, still percent- and entity-encoded.
 * @return the resolved path and optional fragment, or null if [source] decodes to nothing usable.
 */
private fun resolveContainerReference(basePath: String?, source: String): ResolvedReference? {
    val decodedSource = decodePercentEncoding(decodeXmlEntities(source.trim()))
    if (decodedSource.isEmpty()) return null
    val fragment = decodedSource.substringAfter('#', missingDelimiterValue = "").takeIf(String::isNotEmpty)
    val pathSource = decodedSource.substringBefore('#')
    val resolvedPath = when {
        pathSource.isEmpty() -> basePath?.removePrefix("/")
        else -> resolveContainerHref(basePath, decodedSource)
    } ?: return null
    return ResolvedReference(path = resolvedPath, fragment = fragment)
}

/**
 * Decodes `%XX` percent-encoding in [value] byte by byte, then re-decodes the result as UTF-8 — a raw
 * `href` from markup can legally carry a multi-byte UTF-8 character encoded this way one byte at a time.
 *
 * @param value the raw, possibly percent-encoded reference.
 * @return [value] with every well-formed `%XX` triplet decoded; a malformed one (bad hex, or too close
 *   to the end of the string) is left as literal text.
 */
private fun decodePercentEncoding(value: String): String {
    if ('%' !in value) return value
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val code = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (code != null) {
                bytes += code.toByte()
                index += 3
                continue
            }
        }
        char.toString().encodeToByteArray().forEach { bytes += it }
        index += 1
    }
    return bytes.toByteArray().decodeToString()
}

/**
 * Whether a parsed chapter is nothing but the book's own cover picture, repeated — the case
 * [EpubDocumentParser.parseWithCover] skips as a chapter because the synthetic cover section already
 * shows it.
 *
 * A picture occupies one [com.tedd.teddreader.core.common.model.ReaderObjectReplacementChar] placeholder
 * character of the flattened text, so "no readable text" has to mean no text beyond those placeholders
 * themselves, which is what [isBlankIgnoringObjects] checks rather than plain blankness.
 *
 * @param content the chapter's already-parsed content.
 * @param coverHref the book's cover image path, to confirm every image in the chapter is that same
 *   picture and not some other image that merely happens to be alone on the page.
 */
private fun isPureCoverXhtml(
    content: XhtmlContent,
    coverHref: String,
): Boolean {
    if (!content.text.isBlankIgnoringObjects()) return false
    val imageBlocks = content.blocks.filter { it.kind == ReaderBlockKind.IMAGE }
    return imageBlocks.isNotEmpty() && content.blocks.all { it.kind == ReaderBlockKind.IMAGE } && imageBlocks.all { it.imageHref == coverHref }
}

/**
 * The chapter's first heading block's own text, trimmed — the second-choice section title behind
 * [XhtmlContent.headingTitle] (an image-only heading's `title` attribute).
 *
 * @param content the chapter's already-parsed content.
 * @param baseOffset the same base offset [content] was parsed with, needed to turn its blocks' absolute
 *   ranges back into indexes into [content]'s own text.
 * @return the first heading's trimmed text, or null if the chapter has no heading block, or that
 *   heading's text is blank.
 */
private fun firstHeadingTitle(
    content: XhtmlContent,
    baseOffset: Long,
): String? =
    content.blocks.firstOrNull { it.kind == ReaderBlockKind.HEADING }?.let { block ->
        val start = (block.range.start - baseOffset).toInt().coerceAtLeast(0)
        val end = (block.range.end - baseOffset).toInt().coerceAtMost(content.text.length)
        if (end <= start) null else content.text.substring(start, end).trim().takeIf(String::isNotBlank)
    }

/**
 * Turns [navigation]'s raw entries (from either the EPUB 3 nav document or an EPUB 2 NCX) into
 * [ReaderNavigationItem]s addressed at an absolute section and offset the reader can jump to.
 *
 * An entry's `href` is matched against [sectionPathByIndex] first exactly, then by suffix (a navigation
 * document living one directory up from its targets commonly links them with a shorter relative path
 * than the one this parser resolved them to) — an entry matching neither is dropped, since there is
 * nowhere for it to jump to. When the matched section is the synthetic cover section
 * ([coverSpineIndex]) but the entry's own title is not itself a recognizable cover label (see
 * [String.isVisibleCoverLabel]), the jump is redirected to [firstReadableContentSectionIndex] instead:
 * this covers a book whose table of contents lists a real first chapter under its real title, but whose
 * chapter file happens to be the same cover-only XHTML the synthetic section already shows — in that
 * case the reader almost certainly meant the first real content, not to be sent back to the cover. A
 * fragment on the entry resolves to that fragment's own anchor offset inside its section when one was
 * recorded, or otherwise falls back to the section's own start (offset 0) rather than failing the whole
 * entry.
 *
 * @param navigation the raw parsed navigation (nav document or NCX), before it is addressed to any section.
 * @param sectionPathByIndex every section, keyed by index, mapped to its own container path.
 * @param sectionStartOffsets each section's own absolute start offset, keyed by index — used to turn an
 *   anchor's absolute offset back into one relative to its section.
 * @param sectionAnchorOffsets each section's own `id`/`name`/`xml:id` anchors and their absolute
 *   offsets, keyed by section index.
 * @param coverSpineIndex index of the synthetic cover section, or null if the book has none.
 * @param firstReadableContentSectionIndex index of the first section with non-blank text, used as the
 *   redirect target described above; a null value falls back to the first non-cover section, then to 0.
 * @param navigationBasePath container path the navigation document itself sits at, used to resolve each
 *   entry's relative `href`.
 * @return the resolved [ReaderNavigation], carrying [navigation]'s own heading label unchanged.
 */
private fun resolveNavigation(
    navigation: ParsedNavigation,
    sectionPathByIndex: Map<Int, String>,
    sectionStartOffsets: Map<Int, Long>,
    sectionAnchorOffsets: Map<Int, Map<String, Long>>,
    coverSpineIndex: Int?,
    firstReadableContentSectionIndex: Int?,
    navigationBasePath: String,
): ReaderNavigation {
    val sectionByPath = sectionPathByIndex.entries.associateBy({ it.value }, { it.key })
    val firstContentSection = firstReadableContentSectionIndex
        ?: sectionPathByIndex.keys.firstOrNull { it != coverSpineIndex }
        ?: 0
    val items = navigation.entries.mapNotNull { entry ->
        val resolved = resolveContainerReference(navigationBasePath, entry.href) ?: return@mapNotNull null
        val matchingSection = sectionByPath[resolved.path]
            ?: sectionByPath.entries.firstOrNull { (path, _) -> path.endsWith(resolved.path) }?.value
            ?: return@mapNotNull null
        val spineIndex = if (coverSpineIndex != null && matchingSection == coverSpineIndex && !entry.title.isVisibleCoverLabel()) {
            firstContentSection
        } else {
            matchingSection
        }
        val offset = resolved.fragment?.let { fragment ->
            sectionAnchorOffsets[spineIndex]?.get(fragment)?.let { anchorAbsolute ->
                (anchorAbsolute - (sectionStartOffsets[spineIndex] ?: 0L)).coerceAtLeast(0L)
            }
        } ?: 0L
        ReaderNavigationItem(
            title = entry.title.trim(),
            level = entry.level.coerceAtLeast(1),
            spineIndex = spineIndex,
            offset = offset,
        )
    }
    return ReaderNavigation(heading = navigation.heading, items = items)
}

/**
 * Whether this navigation entry's title, whitespace aside, literally reads as "cover" in English or Korean
 * — the two labels these books actually use for a cover entry.
 */
private fun String.isVisibleCoverLabel(): Boolean {
    val normalized = lowercase().replace(Regex("""\s+"""), "")
    return normalized == "cover" || normalized == "표지"
}

/**
 * Splits a space-separated, entity-encoded attribute value (`epub:type`, manifest `properties`) into its
 * lowercase tokens.
 */
private fun navTypeTokens(value: String?): Set<String> =
    value.orEmpty()
        .let(::decodeXmlEntities)
        .split(Regex("""\s+"""))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(String::lowercase)
        .toSet()

/** Sections are joined by a single newline when the document is read as one text. Internal rather than
 * private: DocumentRepositoryImpl.importNextSections advances the same offset a batch at a time. */
internal const val SectionSeparatorLength = 1L

/** Upper bound on one embedded image's decoded size, enforced by [readBytesOrNull]. */
private const val MAX_EPUB_IMAGE_BYTES = 8L * 1024 * 1024

/**
 * How many bytes [readHeaderBytesOrNull] reads to sniff a non-cover image's dimensions.
 *
 * PNG, GIF and WebP declare their size in their first 32 bytes; a JPEG's SOF marker sits further in,
 * after its `APP` segments, and an embedded EXIF thumbnail is the only thing that pushes it out far
 * enough to matter. 64 KiB clears that case with room to spare and no more: this many bytes are read
 * once per distinct image in the book, so this window is the entire cost of learning how big the
 * pictures are.
 */
private const val ImageHeaderSniffBytes = 64 * 1024

/** Matches a `<link>` tag, for finding a chapter's linked stylesheets. */
private val StyleSheetLinkRegex = Regex("""(?is)<link\b[^>]*>""")

/** Fixed, spec-mandated location of an EPUB's container descriptor, which names the OPF's own path. */
private const val ContainerPath = "META-INF/container.xml"

/**
 * The EPUB 2 NCX's manifest `media-type`, used to find it among the manifest items when the spine's own
 * `toc` attribute does not name it directly.
 */
private const val NcxMediaType = "application/x-dtbncx+xml"

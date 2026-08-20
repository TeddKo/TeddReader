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

data class EpubChapter(
    val title: String?,
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
    val document: ReaderDocument,
    val coverBytes: ByteArray?,
)

@Single
class EpubDocumentParser {
    fun parse(
        id: DocumentId,
        title: String,
        bytes: ByteArray,
    ): ReaderDocument = parseWithCover(id = id, title = title, bytes = bytes).document

    fun parse(
        id: DocumentId,
        title: String,
        path: Path,
        fileSystem: FileSystem = systemFileSystem(),
    ): ReaderDocument = parseWithCover(id = id, title = title, path = path, fileSystem = fileSystem).document

    /** Same as [parse], but also returns the cover bytes the parser decoded along the way — see [EpubParseResult]. */
    fun parseWithCover(
        id: DocumentId,
        title: String,
        bytes: ByteArray,
    ): EpubParseResult {
        val fileSystem = systemFileSystem()
        // The id is the document's source URI. Naming the scratch file after it pushed the name past
        // the 255-byte limit a file system allows for one path component, so importing a folder of
        // EPUBs failed on every file with ENAMETOOLONG. Keep the id out of the name; the file is
        // deleted below, so it only has to be unique.
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

    /** Same as [parse], but also returns the cover bytes the parser decoded along the way — see [EpubParseResult]. */
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

        val styleSheetCache = mutableMapOf<String, EpubStyleSheet>()
        val cssCache = mutableMapOf<String, EpubCss>()
        packageData.spineItems.filter { it.linear }.forEachIndexed { spineOrder, spineItem ->
            val xhtml = zip.readUtf8OrNull(spineItem.path.toPath()) ?: return@forEachIndexed
            val content = parseXhtmlContent(
                xhtml = xhtml,
                baseOffset = nextOffset,
                resolveImageHref = { source -> resolveContainerHref(spineItem.path, source) },
                styleSheet = linkedStyleSheet(xhtml, spineItem.path, zip, styleSheetCache),
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
            // Sections are read as one document joined by a single newline, so that separator has to
            // be part of the offsets. Leaving it out drifted every range by one per chapter, which put
            // reading position and search hits in the wrong chapter deep into a book.
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

    private fun coverImageBytes(
        path: Path,
        fileSystem: FileSystem,
    ): ByteArray? {
        val zip = fileSystem.openZip(path)
        val opfPath = zip.readUtf8OrNull(ContainerPath.toPath())?.let(::findRootFilePath) ?: return null
        val opf = zip.readUtf8OrNull(opfPath) ?: return null
        val coverPath = findEpubCoverHref(opf, opfPath) ?: return null
        return zip.readBytesOrNull(coverPath.toPath())
    }

    /** Pull images straight out of an already-unpacked copy, without re-reading the whole book. */
    fun extractEmbeddedImageBytes(
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
    val zip: FileSystem,
    val opfPath: Path,
    val documentTitle: String,
    val packageData: PackageData,
    val coverDecision: EpubCoverDecision,
) {
    val linearSpineItems: List<SpineItem> = packageData.spineItems.filter { it.linear }
}

/** What [openEpubImportContainer] settles about the cover before any spine item is parsed for real. */
internal data class EpubCoverDecision(
    val coverHref: String?,
    val coverBytes: ByteArray?,
    val hasCoverSection: Boolean,
    /** True when the first linear spine item is nothing but the cover picture its own synthetic
     * section already shows — that item is then skipped by the spine loop, never becoming a section
     * of its own (mirrors the `isPureCoverXhtml` check inside [EpubDocumentParser.parseWithCover]). */
    val spineOrder0Skipped: Boolean,
)

/** One newly parsed section, plus its blocks — the unit [DocumentRepositoryImpl.importNextSections]
 * (core:data repository) appends per step of a progressive import. */
internal data class EpubParsedSection(
    val section: ReaderSection,
    val blocks: List<ReaderBlock>,
)

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
                styleSheet = linkedStyleSheet(xhtml, itemPath, zip, mutableMapOf()),
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

/** The synthetic section [EpubDocumentParser.parseWithCover] gives a book's own cover picture — always
 * section 0, since a cover (when [EpubCoverDecision.hasCoverSection]) is always the very first thing a
 * book parses, progressively or not. */
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
        styleSheet = linkedStyleSheet(xhtml, spineItem.path, container.zip, mutableMapOf()),
        css = linkedCss(xhtml, spineItem.path, container.zip, mutableMapOf()),
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

// internal rather than private: EpubImportContainer (below) exposes these to
// DocumentRepositoryImpl's progressive import, which needs the spine list and manifest items a batch
// at a time instead of all at once.
internal data class ManifestItem(
    val id: String,
    val href: String,
    val title: String?,
    val mediaType: String?,
    val properties: String?,
)

internal data class SpineItem(val item: ManifestItem, val path: String, val linear: Boolean)
private data class SpineItemRef(val idref: String, val linear: Boolean)
internal data class PackageData(
    val documentTitle: String?,
    val spineItems: List<SpineItem>,
    val navigationItemPath: String?,
    val ncxPath: String?,
    val coverHref: String?,
)
private data class ResolvedReference(val path: String, val fragment: String?)

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
 * The stylesheets a chapter links, merged in `<link>` order so the last one wins the cascade, which is
 * where these books keep their picture sizes. Cached by stylesheet path: a book reuses the same few
 * sheets across hundreds of chapters.
 */
/**
 * The cascade of every stylesheet this chapter links, in link order so a later sheet wins a tie.
 * Cached by the set of sheets, because a book reuses the same few across hundreds of chapters.
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
    val parsed = EpubCss.parse(hrefs.mapNotNull { href -> zip.readUtf8OrNull(href.toPath()) })
    cache[key] = parsed
    return parsed
}

private fun linkedStyleSheetHrefs(xhtml: String, chapterPath: String): List<String> =
    StyleSheetLinkRegex.findAll(xhtml)
        .map { match -> parseAttributes(match.value) }
        .filter { attributes -> attributes["rel"]?.contains("stylesheet", ignoreCase = true) == true }
        .mapNotNull { attributes -> attributes["href"]?.let { resolveContainerHref(chapterPath, it) } }
        .toList()

private fun linkedStyleSheet(
    xhtml: String,
    chapterPath: String,
    zip: FileSystem,
    cache: MutableMap<String, EpubStyleSheet>,
): EpubStyleSheet {
    val hrefs = linkedStyleSheetHrefs(xhtml, chapterPath)
    if (hrefs.isEmpty()) return EpubStyleSheet()
    val key = hrefs.joinToString("|")
    cache[key]?.let { return it }
    val merged = hrefs.fold(EpubStyleSheet()) { sheet, href ->
        val css = zip.readUtf8OrNull(href.toPath()) ?: return@fold sheet
        parseEpubStyleSheet(css, sheet)
    }
    cache[key] = merged
    return merged
}

/**
 * Patches every image block with the size read from the picture's own bytes: the aspect ratio, and the
 * intrinsic width that sizes it when neither the markup nor the stylesheet declares one. Mutates
 * [blocks] in place.
 */
// internal rather than private: DocumentRepositoryImpl.importNextSections calls this once per batch
// of newly parsed blocks — the same sizing pass the one-shot parse runs once over the whole book.
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

private fun FileSystem.readHeaderBytesOrNull(path: Path, maxBytes: Int): ByteArray? {
    val source = runCatching { source(path).buffer() }.getOrNull() ?: return null
    return try {
        val buffer = Buffer()
        // A single read() call is not guaranteed to fill the request even when more bytes remain, so
        // this loops to the cap (or EOF) the same way readBytesOrNull below does for the full file.
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

private fun findRootFilePath(containerXml: String): Path? =
    Regex("""full-path\s*=\s*["']([^"']+)["']""")
        .find(containerXml)
        ?.groupValues
        ?.get(1)
        ?.toPath()

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
        coverHref = findEpubCoverHref(opf, opfPath),
    )
}

private fun parseDcTitle(opf: String): String? =
    Regex("""(?is)<dc:title\b[^>]*>(.*?)</dc:title>""")
        .find(opf)
        ?.groupValues
        ?.get(1)
        ?.let(::stripMarkup)
        ?.takeIf(String::isNotBlank)

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

private fun parseSpine(opf: String): List<SpineItemRef> =
    Regex("""(?is)<itemref\b[^>]*>""")
        .findAll(opf)
        .mapNotNull { match ->
            val attrs = parseAttributes(match.value)
            val idref = attrs["idref"] ?: return@mapNotNull null
            SpineItemRef(idref = idref, linear = !attrs["linear"].equals("no", ignoreCase = true))
        }
        .toList()

internal fun findEpubCoverHref(opf: String, opfPath: Path? = null): String? =
    findEpubCoverItem(opf, opfPath)

private fun findEpubCoverItem(opf: String, opfPath: Path? = null): String? {
    val manifest = parseManifest(opf)
    val raw = manifest.values.firstOrNull { it.isCoverImageProperty() && it.isRasterImage() }
        ?: findCoverMetaId(opf)?.let { manifest[it] }?.takeIf { it.isRasterImage() }
        ?: manifest.values.firstOrNull { it.isRasterImage() && it.hasCoverHint() }
        ?: return null
    return opfPath?.let { resolveContainerHref(it.toString(), raw.href) } ?: raw.href
}

private fun findCoverMetaId(opf: String): String? =
    Regex("""(?is)<meta\b[^>]*>""")
        .findAll(opf)
        .mapNotNull { match ->
            val attrs = parseAttributes(match.value)
            attrs["content"]?.takeIf { attrs["name"]?.equals("cover", ignoreCase = true) == true }
        }
        .firstOrNull()

private fun ManifestItem.isRasterImage(): Boolean {
    val mediaType = mediaType ?: return false
    return mediaType.startsWith("image/", ignoreCase = true) && !mediaType.contains("svg", ignoreCase = true)
}

private fun ManifestItem.isChapterItem(): Boolean {
    val mediaType = mediaType.orEmpty()
    return mediaType.contains("xhtml", ignoreCase = true) ||
        href.endsWith(".html", ignoreCase = true) ||
        href.endsWith(".xhtml", ignoreCase = true)
}

private fun ManifestItem.hasCoverHint(): Boolean =
    id.contains("cover", ignoreCase = true) || href.contains("cover", ignoreCase = true)

private fun ManifestItem.isCoverImageProperty(): Boolean = navTypeTokens(properties).contains("cover-image")

internal fun parseAttributes(tag: String): Map<String, String> =
    Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        .findAll(tag)
        .associate { match ->
            match.groupValues[1].lowercase() to (match.groupValues[2].ifEmpty { match.groupValues[3] })
        }

/** Resolve a chapter-relative reference against the chapter's own place in the container. */
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

private fun isPureCoverXhtml(
    content: XhtmlContent,
    coverHref: String,
): Boolean {
    // The picture itself now occupies a character of the text, so "no readable text" means no text
    // beyond the pictures.
    if (!content.text.isBlankIgnoringObjects()) return false
    val imageBlocks = content.blocks.filter { it.kind == ReaderBlockKind.IMAGE }
    return imageBlocks.isNotEmpty() && content.blocks.all { it.kind == ReaderBlockKind.IMAGE } && imageBlocks.all { it.imageHref == coverHref }
}

private fun firstHeadingTitle(
    content: XhtmlContent,
    baseOffset: Long,
): String? =
    content.blocks.firstOrNull { it.kind == ReaderBlockKind.HEADING }?.let { block ->
        val start = (block.range.start - baseOffset).toInt().coerceAtLeast(0)
        val end = (block.range.end - baseOffset).toInt().coerceAtMost(content.text.length)
        if (end <= start) null else content.text.substring(start, end).trim().takeIf(String::isNotBlank)
    }

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

private fun String.isVisibleCoverLabel(): Boolean {
    val normalized = lowercase().replace(Regex("""\s+"""), "")
    return normalized == "cover" || normalized == "표지"
}

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
private const val MAX_EPUB_IMAGE_BYTES = 8L * 1024 * 1024
// PNG, GIF and WebP declare their size in the first 32 bytes; a JPEG's SOF marker sits after its
// APP segments, and an embedded EXIF thumbnail is the only thing that pushes it far in. 64 KiB clears
// that and no more: this is inflated once per distinct image in the book, so the window is the whole
// cost of learning how big the pictures are.
private const val ImageHeaderSniffBytes = 64 * 1024
private val StyleSheetLinkRegex = Regex("""(?is)<link\b[^>]*>""")
private const val ContainerPath = "META-INF/container.xml"
private const val NcxMediaType = "application/x-dtbncx+xml"

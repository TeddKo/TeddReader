package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.TextRange
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

@Single
class EpubDocumentParser {
    fun parse(
        id: DocumentId,
        title: String,
        bytes: ByteArray,
    ): ReaderDocument {
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
            parse(id = id, title = title, path = path, fileSystem = fileSystem)
        } finally {
            fileSystem.delete(path)
        }
    }

    fun parse(
        id: DocumentId,
        title: String,
        path: Path,
        fileSystem: FileSystem = systemFileSystem(),
    ): ReaderDocument {
        val zip = fileSystem.openZip(path)
        val opfPath = zip.readUtf8OrNull("META-INF/container.xml".toPath())
            ?.let(::findRootFilePath)

        val chapters = if (opfPath != null) {
            val opf = zip.readUtf8OrNull(opfPath) ?: ""
            val manifest = parseManifest(opf)
            parseSpine(opf).mapNotNull { idRef ->
                val item = manifest[idRef]?.takeIf { it.isChapterItem() } ?: return@mapNotNull null
                val chapterPath = opfPath.parent?.resolve(item.href) ?: item.href.toPath()
                zip.readUtf8OrNull(chapterPath)?.let { xhtml ->
                    EpubChapter(title = item.title ?: idRef, xhtml = xhtml, path = chapterPath.toString())
                }
            }
        } else {
            zip.listRecursively("/".toPath())
                .filter { path -> path.name.endsWith(".xhtml") || path.name.endsWith(".html") }
                .mapNotNull { path ->
                    zip.readUtf8OrNull(path)?.let { EpubChapter(path.name, it, path.toString()) }
                }
                .toList()
        }

        return parseChapters(id, title, chapters)
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
        )
    }

    private fun coverImageBytes(
        path: Path,
        fileSystem: FileSystem = systemFileSystem(),
    ): ByteArray? {
        val zip = fileSystem.openZip(path)
        val opfPath = zip.readUtf8OrNull("META-INF/container.xml".toPath())
            ?.let(::findRootFilePath)
            ?: return null
        val opf = zip.readUtf8OrNull(opfPath) ?: return null
        val coverItem = findEpubCoverItem(opf) ?: return null
        val coverPath = opfPath.parent?.resolve(coverItem.href) ?: coverItem.href.toPath()
        return zip.readBytesOrNull(coverPath)
    }
}

private data class ManifestItem(
    val id: String,
    val href: String,
    val title: String?,
    val mediaType: String?,
    val properties: String?,
)

private fun FileSystem.readUtf8OrNull(path: Path): String? =
    runCatching {
        val source = source(path).buffer()
        try {
            source.readUtf8()
        } finally {
            source.close()
        }
    }.getOrNull()

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
    Regex("""full-path=["']([^"']+)["']""")
        .find(containerXml)
        ?.groupValues
        ?.get(1)
        ?.toPath()

private fun parseManifest(opf: String): Map<String, ManifestItem> =
    Regex("""<item\b[^>]*>""")
        .findAll(opf)
        .mapNotNull { match ->
            val attrs = parseAttributes(match.value)
            val id = attrs["id"] ?: return@mapNotNull null
            val href = attrs["href"] ?: return@mapNotNull null
            id to ManifestItem(
                id = id,
                href = href,
                title = attrs["title"],
                mediaType = attrs["media-type"],
                properties = attrs["properties"],
            )
        }
        .toMap()

private fun parseSpine(opf: String): List<String> =
    Regex("""<itemref\b[^>]*>""")
        .findAll(opf)
        .mapNotNull { match -> parseAttributes(match.value)["idref"] }
        .toList()

internal fun findEpubCoverHref(opf: String): String? = findEpubCoverItem(opf)?.href

private fun findEpubCoverItem(opf: String): ManifestItem? {
    val manifest = parseManifest(opf)
    manifest.values.firstOrNull { it.isCoverImageProperty() && it.isRasterImage() }?.let { return it }
    findCoverMetaId(opf)?.let { coverId ->
        manifest[coverId]?.takeIf { it.isRasterImage() }?.let { return it }
    }
    return manifest.values.firstOrNull { it.isRasterImage() && it.hasCoverHint() }
}

private fun findCoverMetaId(opf: String): String? =
    Regex("""<meta\b[^>]*>""")
        .findAll(opf)
        .mapNotNull { match ->
            val attrs = parseAttributes(match.value)
            if (attrs["name"] == "cover") attrs["content"] else null
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

private fun ManifestItem.isCoverImageProperty(): Boolean =
    properties?.split(Regex("""\s+"""))?.contains("cover-image") == true

private fun parseAttributes(tag: String): Map<String, String> =
    Regex("""([\w:-]+)=["']([^"']*)["']""")
        .findAll(tag)
        .associate { match -> match.groupValues[1] to match.groupValues[2] }

/** Resolve a chapter-relative reference against the chapter's own place in the container. */
internal fun resolveContainerHref(chapterPath: String?, source: String): String? {
    val cleaned = source.substringBefore('#').trim()
    if (cleaned.isEmpty() || cleaned.startsWith("data:")) return null
    if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) return null
    val decoded = decodePercentEncoding(cleaned)
    if (decoded.startsWith("/")) return decoded.removePrefix("/")
    val parent = chapterPath?.toPath()?.parent ?: return decoded
    return parent.resolve(decoded).normalized().toString().removePrefix("/")
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

/** Sections are joined by a single newline when the document is read as one text. */
private const val SectionSeparatorLength = 1L

private const val MAX_EPUB_IMAGE_BYTES = 8L * 1024 * 1024

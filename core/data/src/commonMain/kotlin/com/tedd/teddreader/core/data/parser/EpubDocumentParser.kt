package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
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
)

@Single
class EpubDocumentParser {
    fun parse(
        id: DocumentId,
        title: String,
        bytes: ByteArray,
    ): ReaderDocument {
        val fileSystem = systemFileSystem()
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "${id.value.replace('/', '_')}.epub"
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
                    EpubChapter(title = item.title ?: idRef, xhtml = xhtml)
                }
            }
        } else {
            zip.listRecursively("/".toPath())
                .filter { path -> path.name.endsWith(".xhtml") || path.name.endsWith(".html") }
                .mapNotNull { path -> zip.readUtf8OrNull(path)?.let { EpubChapter(path.name, it) } }
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
        val sections = chapters.mapIndexed { index, chapter ->
            val text = chapter.xhtml.toReadableText()
            val range = TextRange(offset, offset + text.length)
            offset += text.length
            ReaderSection(
                index = index,
                title = chapter.title ?: "Chapter ${index + 1}",
                text = text,
                range = range,
            )
        }

        return ReaderDocument(
            id = id,
            format = DocumentFormat.EPUB,
            title = title,
            sections = sections,
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

private fun String.toReadableText(): String = this
    .replace(Regex("""(?s)<script\b[^>]*>.*?</script>"""), " ")
    .replace(Regex("""(?s)<style\b[^>]*>.*?</style>"""), " ")
    .replace(Regex("""<[^>]+>"""), " ")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace(Regex("""\s+"""), " ")
    .trim()

private const val MAX_EPUB_IMAGE_BYTES = 8L * 1024 * 1024

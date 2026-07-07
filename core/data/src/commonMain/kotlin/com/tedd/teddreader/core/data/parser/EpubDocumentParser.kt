package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.TextRange
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
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "${id.value.replace('/', '_')}.epub"
        val sink = FileSystem.SYSTEM.sink(path).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
        return try {
            parse(id = id, title = title, path = path)
        } finally {
            FileSystem.SYSTEM.delete(path)
        }
    }

    fun parse(
        id: DocumentId,
        title: String,
        path: Path,
        fileSystem: FileSystem = FileSystem.SYSTEM,
    ): ReaderDocument {
        val zip = fileSystem.openZip(path)
        val opfPath = zip.readUtf8OrNull("META-INF/container.xml".toPath())
            ?.let(::findRootFilePath)

        val chapters = if (opfPath != null) {
            val opf = zip.readUtf8OrNull(opfPath) ?: ""
            val manifest = parseManifest(opf)
            parseSpine(opf).mapNotNull { idRef ->
                val item = manifest[idRef] ?: return@mapNotNull null
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
}

private data class ManifestItem(
    val href: String,
    val title: String?,
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
            if (attrs["media-type"]?.contains("xhtml") != true && !href.endsWith(".html") && !href.endsWith(".xhtml")) {
                return@mapNotNull null
            }
            id to ManifestItem(href = href, title = attrs["title"])
        }
        .toMap()

private fun parseSpine(opf: String): List<String> =
    Regex("""<itemref\b[^>]*>""")
        .findAll(opf)
        .mapNotNull { match -> parseAttributes(match.value)["idref"] }
        .toList()

private fun parseAttributes(tag: String): Map<String, String> =
    Regex("""([\w:-]+)=["']([^"']*)["']""")
        .findAll(tag)
        .associate { match -> match.groupValues[1] to match.groupValues[2] }

private fun String.toReadableText(): String = this
    .replace(Regex("""<script\b[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL), " ")
    .replace(Regex("""<style\b[^>]*>.*?</style>""", RegexOption.DOT_MATCHES_ALL), " ")
    .replace(Regex("""<[^>]+>"""), " ")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace(Regex("""\s+"""), " ")
    .trim()

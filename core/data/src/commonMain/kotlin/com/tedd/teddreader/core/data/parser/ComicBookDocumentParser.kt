package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import kotlin.random.Random
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import org.koin.core.annotation.Single

@Single
class ComicBookDocumentParser {
    fun parse(
        id: DocumentId,
        title: String,
        bytes: ByteArray,
    ): ReaderDocument = withComicZip(bytes) { zip ->
        val pageCount = comicPagePaths(zip).size
        require(pageCount > 0) { "CBZ contains no supported image pages." }
        comicReaderDocument(id = id, title = title, pageCount = pageCount)
    }

    fun coverImageBytes(bytes: ByteArray): ByteArray? =
        pageImageBytes(bytes, setOf(0))[0]

    fun pageImageBytes(
        bytes: ByteArray,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> {
        if (pageIndexes.isEmpty()) return emptyMap()
        return withComicZip(bytes) { zip ->
            val pages = comicPagePaths(zip)
            pageIndexes.sorted().mapNotNull { pageIndex ->
                pages.getOrNull(pageIndex)
                    ?.let { pagePath -> zip.readComicPageOrNull(pagePath) }
                    ?.let { pageBytes -> pageIndex to pageBytes }
            }.toMap()
        }
    }

    private fun <T> withComicZip(bytes: ByteArray, block: (FileSystem) -> T): T {
        val fileSystem = systemFileSystem()
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "tedd-reader-comic-${Random.nextLong().toString(16)}.cbz"
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
        return try {
            block(fileSystem.openZip(path))
        } finally {
            fileSystem.delete(path)
        }
    }
}

internal fun comicReaderDocument(
    id: DocumentId,
    title: String,
    pageCount: Int,
): ReaderDocument {
    require(pageCount > 0) { "Comic pageCount must be positive." }
    return ReaderDocument(
        id = id,
        format = DocumentFormat.CBZ,
        title = title,
        sections = emptyList(),
        pageCount = pageCount,
    )
}

internal fun sortedComicPageNames(names: List<String>): List<String> = names
    .asSequence()
    .map { it.replace('\\', '/').removePrefix("/") }
    .filter(::isComicPageName)
    .sortedWith(Comparator { left, right ->
        val coverOrder = isCoverPageName(right).compareTo(isCoverPageName(left))
        if (coverOrder != 0) coverOrder else compareNaturalPageNames(left, right)
    })
    .toList()

private fun comicPagePaths(zip: FileSystem): List<Path> {
    val pathsByName = zip.listRecursively("/".toPath())
        .associateBy { path -> path.toString().removePrefix("/") }
    return sortedComicPageNames(pathsByName.keys.toList()).mapNotNull(pathsByName::get)
}

private fun isComicPageName(name: String): Boolean {
    val normalized = name.lowercase()
    val fileName = normalized.substringAfterLast('/')
    if (normalized.startsWith("__macosx/") || "/__macosx/" in normalized || fileName.startsWith("._")) return false
    return normalized.substringAfterLast('.', missingDelimiterValue = "") in ComicPageExtensions
}

private fun isCoverPageName(name: String): Boolean =
    name.substringAfterLast('/').substringBeforeLast('.').equals("cover", ignoreCase = true)

private fun compareNaturalPageNames(left: String, right: String): Int {
    val a = left.lowercase()
    val b = right.lowercase()
    var aIndex = 0
    var bIndex = 0
    while (aIndex < a.length && bIndex < b.length) {
        val aIsDigit = a[aIndex].isDigit()
        val bIsDigit = b[bIndex].isDigit()
        if (aIsDigit && bIsDigit) {
            val aEnd = a.indexAfterRun(aIndex, Char::isDigit)
            val bEnd = b.indexAfterRun(bIndex, Char::isDigit)
            val aNumber = a.substring(aIndex, aEnd).trimStart('0').ifEmpty { "0" }
            val bNumber = b.substring(bIndex, bEnd).trimStart('0').ifEmpty { "0" }
            val lengthOrder = aNumber.length.compareTo(bNumber.length)
            if (lengthOrder != 0) return lengthOrder
            val numberOrder = aNumber.compareTo(bNumber)
            if (numberOrder != 0) return numberOrder
            aIndex = aEnd
            bIndex = bEnd
        } else {
            val characterOrder = a[aIndex].compareTo(b[bIndex])
            if (characterOrder != 0) return characterOrder
            aIndex += 1
            bIndex += 1
        }
    }
    return a.length.compareTo(b.length).takeIf { it != 0 } ?: left.compareTo(right)
}

private fun String.indexAfterRun(start: Int, predicate: (Char) -> Boolean): Int {
    var index = start
    while (index < length && predicate(this[index])) index += 1
    return index
}

private fun FileSystem.readComicPageOrNull(path: Path): ByteArray? {
    val source = runCatching { source(path).buffer() }.getOrNull() ?: return null
    return try {
        val buffer = Buffer()
        var totalBytes = 0L
        while (true) {
            val read = source.read(buffer, 8_192)
            if (read == -1L) break
            totalBytes += read
            if (totalBytes > MaxComicPageBytes) return null
        }
        buffer.readByteArray()
    } catch (_: Throwable) {
        null
    } finally {
        source.close()
    }
}

private val ComicPageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
private const val MaxComicPageBytes = 16L * 1024 * 1024

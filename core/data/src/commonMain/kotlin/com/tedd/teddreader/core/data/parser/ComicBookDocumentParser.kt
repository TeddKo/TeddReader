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

/**
 * Reads a CBZ (a ZIP of page images) as a page-counted [ReaderDocument] and, on demand, decodes the
 * bytes of whichever pages a caller actually needs — the cover to show in a library, or a page to
 * display while reading. It never decodes every page up front: [parse] only counts how many entries
 * qualify as pages, and the actual image bytes for a page are read lazily by [pageImageBytes] and
 * [coverImageBytes]. There is no text here at all — [ReaderDocument.sections] is always empty — because
 * a comic is read as a sequence of page images, not as flowed prose.
 */
@Single
open class ComicBookDocumentParser {
    /**
     * Counts the pages in the CBZ held in [bytes] and builds the document for it.
     *
     * @param id identity of the source file, carried through unchanged.
     * @param title label shown for the document; not derived from the archive.
     * @param bytes the CBZ's raw contents, spilled to a temporary file so it can be opened as a ZIP
     *   (see [withComicZip]).
     * @return a [ReaderDocument] of [DocumentFormat.CBZ] with no sections and a page count equal to the
     *   number of entries [sortedComicPageNames] recognizes as pages.
     * @throws IllegalArgumentException if the archive holds no entry [sortedComicPageNames] recognizes
     *   as a page — an empty or non-comic ZIP has nothing this reader could show.
     */
    fun parse(
        id: DocumentId,
        title: String,
        bytes: ByteArray,
    ): ReaderDocument = withComicZip(bytes) { archive ->
        val pageCount = archive.pageCount
        require(pageCount > 0) { "CBZ contains no supported image pages." }
        comicReaderDocument(id = id, title = title, pageCount = pageCount)
    }

    /**
     * Counts the pages in the CBZ at [path] and builds the document for it.
     *
     * @param id identity of the source file, carried through unchanged.
     * @param title label shown for the document; not derived from the archive.
     * @param path location of the CBZ file, opened directly as a ZIP with no temporary copy.
     * @return a [ReaderDocument] of [DocumentFormat.CBZ] with no sections and a page count equal to the
     *   number of entries [sortedComicPageNames] recognizes as pages.
     * @throws IllegalArgumentException if the archive holds no entry [sortedComicPageNames] recognizes
     *   as a page.
     */
    fun parse(
        id: DocumentId,
        title: String,
        path: Path,
    ): ReaderDocument = withComicZip(path) { archive ->
        val pageCount = archive.pageCount
        require(pageCount > 0) { "CBZ contains no supported image pages." }
        comicReaderDocument(id = id, title = title, pageCount = pageCount)
    }

    /**
     * The first page's image bytes — this format's stand-in for a cover, since a CBZ carries no cover
     * metadata the way an EPUB or PDF can.
     *
     * @param bytes the CBZ's raw contents.
     * @return the first page's bytes, or null if the archive has no recognizable page at index 0, or
     *   that page's bytes could not be read (see [pageImageBytes]).
     */
    fun coverImageBytes(bytes: ByteArray): ByteArray? =
        pageImageBytes(bytes, setOf(0))[0]

    /**
     * The first page's image bytes, read from the CBZ at [path]. See [coverImageBytes] (the bytes
     * overload) for what null means.
     *
     * @param path location of the CBZ file.
     */
    fun coverImageBytes(path: Path): ByteArray? =
        pageImageBytes(path, setOf(0))[0]

    /**
     * Reads the image bytes of the pages at [pageIndexes] out of the CBZ held in [bytes].
     *
     * @param bytes the CBZ's raw contents.
     * @param pageIndexes zero-based page numbers to read, in [comicPagePaths] reading order; an empty
     *   set short-circuits without opening the archive at all.
     * @return bytes keyed by page index, containing only the indexes that both existed and whose entry
     *   could actually be read — an index past the last page, or a page entry that is corrupt,
     *   oversized (see [MaxComicPageBytes]), or otherwise unreadable, is simply absent from the result
     *   rather than causing the whole call to fail.
     */
    fun pageImageBytes(
        bytes: ByteArray,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> {
        if (pageIndexes.isEmpty()) return emptyMap()
        return withComicZip(bytes) { archive ->
            archive.pageImageBytes(pageIndexes)
        }
    }

    /**
     * Reads the image bytes of the pages at [pageIndexes] out of the CBZ at [path]. See the [bytes]
     * overload of [pageImageBytes] for what is included in the result.
     *
     * @param path location of the CBZ file.
     * @param pageIndexes zero-based page numbers to read; an empty set short-circuits without opening
     *   the archive.
     */
    fun pageImageBytes(
        path: Path,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> {
        if (pageIndexes.isEmpty()) return emptyMap()
        return withComicZip(path) { archive ->
            archive.pageImageBytes(pageIndexes)
        }
    }

    /**
     * Opens the CBZ already on disk at [path] as a reusable [ComicArchive] whose ZIP [FileSystem] and
     * naturally-ordered page paths are built once and can then answer any number of
     * [ComicArchive.pageImageBytes]/[ComicArchive.coverImageBytes] requests without re-listing or
     * re-sorting the entries.
     *
     * The public [parse]/[pageImageBytes]/[coverImageBytes] overloads all funnel through this so a
     * caller that only needs a single answer keeps its old one-shot behaviour, while a caller that
     * turns page after page of the same book — [com.tedd.teddreader.core.data.repository.DocumentRepositoryImpl]'s
     * page-window path — can hold one archive open across every window request and pay the ZIP-index
     * cost a single time.
     *
     * @param path location of the CBZ file, opened directly with no temporary copy; the caller owns
     *   that file's lifetime and must keep it on disk for as long as it uses the returned archive.
     * @return a [ComicArchive] over [path], reusable for repeated page/cover reads.
     */
    internal open fun openArchive(path: Path): ComicArchive {
        val fileSystem = systemFileSystem().openZip(path)
        return ComicArchive(fileSystem = fileSystem, pagePaths = comicPagePaths(fileSystem))
    }

    /**
     * Runs [block] against [bytes] opened as a ZIP, by first spilling it to a uniquely-named temporary
     * file — comic bytes arrive in memory as a whole file, and Okio's ZIP file system needs a [Path] to
     * open one — and always deleting that scratch file afterward, whether [block] succeeds or throws.
     *
     * @param bytes the CBZ's raw contents.
     * @param block reads whatever it needs from the opened archive before this returns.
     * @return whatever [block] returns.
     */
    private fun <T> withComicZip(bytes: ByteArray, block: (ComicArchive) -> T): T {
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
            block(openArchive(path))
        } finally {
            fileSystem.delete(path)
        }
    }

    /**
     * Runs [block] against the CBZ already on disk at [path], opened directly with no temporary copy.
     *
     * @param path location of the CBZ file.
     * @param block reads whatever it needs from the opened archive before this returns.
     * @return whatever [block] returns.
     */
    private fun <T> withComicZip(path: Path, block: (ComicArchive) -> T): T = block(openArchive(path))
}

/**
 * A CBZ opened once for repeated reading: its Okio ZIP [FileSystem] and the naturally-ordered list of
 * page entry [Path]s ([ComicBookDocumentParser.sortedComicPageNames]' order) are computed a single time,
 * so every later [pageImageBytes]/[coverImageBytes]/[pageCount] call reuses them instead of re-listing
 * and re-sorting the archive. It is read-only and holds no scratch file of its own — the caller that
 * built it (via [ComicBookDocumentParser.openArchive]) owns the underlying file's lifetime and must
 * keep it on disk for as long as this archive is used.
 *
 * @property fileSystem the opened ZIP file system every page read draws from.
 * @property pagePaths the archive's page entries, already in reading order.
 */
internal class ComicArchive(
    private val fileSystem: FileSystem,
    private val pagePaths: List<Path>,
) {
    /** How many pages this archive holds — the size of its reading-ordered [pagePaths]. */
    val pageCount: Int get() = pagePaths.size

    /**
     * Reads the image bytes of the pages at [pageIndexes] out of this already-open archive, reusing the
     * page index this archive was built with rather than rebuilding it.
     *
     * @param pageIndexes zero-based page numbers to read, in reading order; an empty set short-circuits
     *   to an empty map.
     * @return bytes keyed by page index, containing only the indexes that both existed and whose entry
     *   could actually be read — an index past the last page, or a page entry that is corrupt, oversized
     *   (see [MaxComicPageBytes]), or otherwise unreadable, is simply absent from the result rather than
     *   causing the whole call to fail.
     */
    fun pageImageBytes(pageIndexes: Set<Int>): Map<Int, ByteArray> {
        if (pageIndexes.isEmpty()) return emptyMap()
        return pageIndexes.sorted().mapNotNull { pageIndex ->
            pagePaths.getOrNull(pageIndex)
                ?.let { pagePath -> fileSystem.readComicPageOrNull(pagePath) }
                ?.let { pageBytes -> pageIndex to pageBytes }
        }.toMap()
    }

    /**
     * The first page's image bytes — this format's stand-in for a cover.
     *
     * @return the first page's bytes, or null if the archive has no page at index 0 or that page could
     *   not be read (see [pageImageBytes]).
     */
    fun coverImageBytes(): ByteArray? = pageImageBytes(setOf(0))[0]
}

/**
 * Builds the CBZ [ReaderDocument] both [ComicBookDocumentParser.parse] overloads share once they know
 * [pageCount] — a comic has no readable text, so its sections list is always empty.
 *
 * @param id identity of the source file, carried through unchanged.
 * @param title label shown for the document.
 * @param pageCount number of pages the archive was found to contain; must be positive.
 * @return a [ReaderDocument] of [DocumentFormat.CBZ] with no sections.
 * @throws IllegalArgumentException if [pageCount] is not positive.
 */
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

/**
 * The subset of [names] that are actual comic pages, in the order a reader should show them: an entry
 * literally named `cover` (any extension) always comes first regardless of where it would sort
 * naturally, then every other page in natural numeric order — so `page2` comes before `page10`, the way
 * a person reading the filenames would expect, rather than the way a plain string sort would place them.
 *
 * Backslash separators are normalized to `/` and a leading `/` is stripped before filtering, so a name
 * pulled straight from a Windows-authored archive's entry list still matches [isComicPageName].
 *
 * @param names raw entry names (paths) as stored in the archive.
 * @return the page entries among [names], reordered as described above; anything [isComicPageName]
 *   rejects — metadata folders, resource-fork files, unsupported extensions — is dropped entirely.
 */
internal fun sortedComicPageNames(names: List<String>): List<String> = names
    .asSequence()
    .map { it.replace('\\', '/').removePrefix("/") }
    .filter(::isComicPageName)
    .sortedWith(Comparator { left, right ->
        val coverOrder = isCoverPageName(right).compareTo(isCoverPageName(left))
        if (coverOrder != 0) coverOrder else compareNaturalPageNames(left, right)
    })
    .toList()

/**
 * Every entry in [zip] that [isComicPageName] accepts, resolved to its [Path] and put in reading order
 * by [sortedComicPageNames].
 */
private fun comicPagePaths(zip: FileSystem): List<Path> {
    val pathsByName = zip.listRecursively("/".toPath())
        .associateBy { path -> path.toString().removePrefix("/") }
    return sortedComicPageNames(pathsByName.keys.toList()).mapNotNull(pathsByName::get)
}

/**
 * Whether [name] is an entry [sortedComicPageNames] should treat as a page: its extension must be one
 * of [ComicPageExtensions], and it must not be one of the two kinds of non-page clutter a Mac-created
 * ZIP routinely adds — an entry under a `__MACOSX/` metadata folder, or an AppleDouble resource-fork
 * file whose own name starts with `._`.
 *
 * @param name a raw entry name, already normalized to forward slashes by [sortedComicPageNames].
 */
private fun isComicPageName(name: String): Boolean {
    val normalized = name.lowercase()
    val fileName = normalized.substringAfterLast('/')
    if (normalized.startsWith("__macosx/") || "/__macosx/" in normalized || fileName.startsWith("._")) return false
    return normalized.substringAfterLast('.', missingDelimiterValue = "") in ComicPageExtensions
}

/**
 * Whether [name]'s file name, extension aside, is literally `cover` — the one name [sortedComicPageNames]
 * always places first, ahead of natural order.
 *
 * @param name a raw entry name.
 */
private fun isCoverPageName(name: String): Boolean =
    name.substringAfterLast('/').substringBeforeLast('.').equals("cover", ignoreCase = true)

/**
 * Natural-order comparison of [left] and [right]: a run of digits is compared as a number (leading
 * zeros stripped, and a longer run of digits always outranking a shorter one at the same position) so
 * `page2` sorts before `page10`, while a run of non-digit characters compares as ordinary lowercase
 * text. Falls back to comparing overall length, then the original strings, only when every character
 * position ties.
 *
 * @param left one entry name being compared; case is ignored (lowercased before comparing).
 * @param right the other entry name being compared; case is ignored the same way.
 * @return negative if [left] sorts first, positive if [right] does, zero if they are equivalent.
 */
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

/**
 * The index of the first position at or after [start] whose character fails [predicate] — used by
 * [compareNaturalPageNames] to find where a run of digits ends.
 *
 * @receiver the string being scanned.
 * @param start index to begin scanning from; must itself satisfy [predicate].
 * @param predicate the test a run's characters must keep satisfying.
 * @return the index one past the end of the run, or [String.length] if the run reaches the end of the
 *   receiver.
 */
private fun String.indexAfterRun(start: Int, predicate: (Char) -> Boolean): Int {
    var index = start
    while (index < length && predicate(this[index])) index += 1
    return index
}

/**
 * Reads one page entry's full bytes, capped at [MaxComicPageBytes] so a single malformed or
 * unexpectedly huge entry cannot blow past the memory a page image should ever need.
 *
 * @receiver the archive to read from.
 * @param path entry path of the page, as resolved by [comicPagePaths].
 * @return the page's bytes, or null if the entry could not be opened, reading it threw, or its size
 *   exceeded [MaxComicPageBytes].
 */
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

/** File extensions [isComicPageName] accepts as a page image. */
private val ComicPageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/** Upper bound on one page's decoded size, enforced by [readComicPageOrNull]. */
private const val MaxComicPageBytes = 16L * 1024 * 1024

package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.suspendRunCatching
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.data.parser.EpubDocumentParser
import com.tedd.teddreader.core.data.parser.PdfDocumentParser
import com.tedd.teddreader.core.data.parser.systemFileSystem
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import kotlin.random.Random
import okio.FileSystem
import okio.Path

/**
 * Owns a document's cover picture: where it is cached on disk, how it is read back, and how it is
 * extracted from the book when nothing is cached yet.
 *
 * These four concerns used to sit in four different places in [DocumentRepositoryImpl] — the read and
 * the write inside `getDocumentCover`, the extraction in a private method beside it, the delete next to
 * the document-deletion path, and the write again inside `persistParsedDocument` on the import path.
 * Nothing about a cover is shared with the repository's caches or its scratch-copy locks, so keeping it
 * spread across the class bought nothing and made the lifetime — extract once, serve from file forever,
 * delete with the document — impossible to read in one place.
 *
 * Cover *extraction* here covers EPUB and PDF only. CBZ stays with [DocumentRepositoryImpl] because its
 * cover comes out of the shared, mutex-guarded comic archive slot, and moving that out from under its
 * lock is a separate concern from where cover files live.
 *
 * @property epubDocumentParser Reads the declared cover entry out of an EPUB container.
 * @property pdfDocumentParser Renders a PDF's first page as its cover.
 * @property documentFileSource Resolves the app-private directory covers are cached under, and streams
 *   the original file when an EPUB cover has to be extracted. Null in a context with no file access,
 *   which turns every operation here into a no-op rather than an error.
 */
internal class DocumentCoverStore(
    private val epubDocumentParser: EpubDocumentParser,
    private val pdfDocumentParser: PdfDocumentParser,
    private val documentFileSource: DocumentFileSource?,
) {
    /**
     * Where [documentId]'s cover is, or would be, cached.
     *
     * Exposed so a caller can name the path in a diagnostic without recomputing the hash [coverFilePath]
     * derives it from.
     *
     * @param documentId The document whose cover path to resolve.
     * @return The path, or null when there is no file access to resolve it against.
     */
    fun pathFor(documentId: DocumentId): Path? =
        documentFileSource?.let { fileSource -> coverFilePath(fileSource, documentId) }

    /**
     * The already-extracted cover for [documentId], if one was cached by an earlier request or by the
     * import that first parsed the book.
     *
     * Uses Okio's own `read { }` scoping rather than `use { }`: `okio.Closeable` is not
     * `kotlin.AutoCloseable` on Kotlin/Native, so `use` compiles on Android and fails the iOS targets.
     * [store] follows the same precedent with `write { }`.
     *
     * @param documentId The document whose cached cover to read.
     * @return The cover bytes, or null when nothing is cached, there is no file access, or the read
     *   failed — all of which the caller treats the same way, by extracting again.
     */
    fun cached(documentId: DocumentId): ByteArray? {
        val path = pathFor(documentId) ?: return null
        return runCatching { systemFileSystem().read(path) { readByteArray() } }.getOrNull()
    }

    /**
     * Caches [bytes] as [documentId]'s cover so no later request has to open the book again.
     *
     * Failures are swallowed: a cover that fails to cache is simply extracted again on the next request,
     * which is strictly better than failing the request that produced it.
     *
     * @param documentId The document the cover belongs to.
     * @param bytes The cover image bytes to write.
     */
    fun store(documentId: DocumentId, bytes: ByteArray) {
        val path = pathFor(documentId) ?: return
        runCatching {
            path.parent?.let { parent -> systemFileSystem().createDirectories(parent) }
            systemFileSystem().write(path) { write(bytes) }
        }
    }

    /**
     * Removes [documentId]'s cached cover, which a document deletion must do on top of dropping the
     * shelf row: the cover file is named by a hash of the document id, so a book re-imported from the
     * same location would otherwise be served the previous import's picture.
     *
     * @param documentId The document whose cached cover to delete.
     */
    fun delete(documentId: DocumentId) {
        val path = pathFor(documentId) ?: return
        runCatching { systemFileSystem().delete(path) }
    }

    /**
     * Extracts [metadata]'s cover straight from the book, for the formats whose cover this store knows
     * how to reach.
     *
     * @param metadata The document to extract from.
     * @return The cover image bytes, or null when the format's cover is not this store's to extract
     *   (CBZ, or a format with no cover at all), there is no file access, or the book declares no
     *   readable cover.
     */
    suspend fun extract(metadata: DocumentMetadata): ByteArray? = when (metadata.format) {
        DocumentFormat.EPUB -> documentFileSource?.let { fileSource ->
            extractEpubCoverWithoutBuffering(metadata, fileSource)
        }
        DocumentFormat.PDF -> pdfDocumentParser.coverImageBytes(metadata.location, bytes = null)
        DocumentFormat.CBZ,
        DocumentFormat.TXT,
        DocumentFormat.IMAGE,
        DocumentFormat.UNKNOWN,
            -> null
    }

    /**
     * Extracts an EPUB's cover by streaming the book to its own short-lived temporary file and reading
     * only the cover entry back out of it.
     *
     * The obvious alternatives are both wrong here. Reading the file into a [ByteArray] first — what
     * this path used to do — charged the whole book's size to the heap to reach one picture, and an
     * illustrated book of a few hundred megabytes could exhaust the process on a low-memory device.
     * Reusing the repository's long-lived EPUB scratch slot would be worse in a different way: the home
     * screen asks for many documents' covers, and each request would evict the scratch copy of whatever
     * book the reader currently has open, forcing that book to be copied again on the next page turn.
     *
     * The temporary file is deleted in a `finally`, so a failed extraction does not leave the book's
     * full size behind on disk. The repository's abandoned-scratch sweep does not cover this prefix
     * because nothing outlives this function that would need sweeping.
     *
     * @param metadata The EPUB whose cover to extract; its location is what gets streamed.
     * @param fileSource Where the original file is streamed from.
     * @return The cover image's bytes, or null when the copy failed or the book declares no readable
     *   cover.
     */
    private suspend fun extractEpubCoverWithoutBuffering(
        metadata: DocumentMetadata,
        fileSource: DocumentFileSource,
    ): ByteArray? {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "tedd-reader-epub-cover-source-${Random.nextLong().toString(16)}.epub"
        return try {
            suspendRunCatching { fileSource.copyTo(metadata.location, path) }.getOrNull()
                ?: return null
            epubDocumentParser.coverImageBytes(path)
        } finally {
            runCatching { systemFileSystem().delete(path) }
        }
    }
}

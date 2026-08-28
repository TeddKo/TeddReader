package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentLocation
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import okio.FileSystem
import okio.buffer
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy
import kotlin.random.Random

/** iOS's implementation of the [defaultPdfMetadataReader] contract. */
internal actual fun defaultPdfMetadataReader(): PdfMetadataReader = IosPdfMetadataReader()

/**
 * iOS's [PdfMetadataReader], built on PDFKit. Both [pageCount] and [coverImageBytes] resolve the
 * document **location-first**: they open a `PDFDocument` directly from the file path encoded in
 * [DocumentLocation.sourceUri], avoiding any temporary-file write when the path is reachable. Only
 * when the path cannot be opened and [bytes] is non-null does this implementation fall back to
 * writing [bytes] to a temporary file — the legacy path for callers that have not yet materialized
 * the document into the sandbox.
 *
 * Before this change, [pageCount] already used the location directly while [coverImageBytes]
 * unconditionally wrote bytes to a temp file — a redundant copy for every cover extraction of a
 * PDF already sitting in the app's sandbox. Both methods now share the same location-first
 * resolution strategy.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPdfMetadataReader : PdfMetadataReader {
    /**
     * @param location The document's location; the page count is read from the file at
     *   [DocumentLocation.sourceUri] directly.
     * @param bytes Fallback bytes used only when [location]'s path cannot be opened as a
     *   `PDFDocument`. Null when the caller guarantees [location] is a reachable local file.
     * @return The page count, or `1` if no file exists at `location`'s path and no bytes fallback
     *   is available, or the file cannot be opened as a PDF — this never throws.
     */
    override fun pageCount(location: DocumentLocation, bytes: ByteArray?): Int =
        withPdfDocument(location, bytes) { document ->
            document.pageCount.toInt().coerceAtLeast(1)
        } ?: 1

    /**
     * @param location The document's location; the cover is rendered from the file at
     *   [DocumentLocation.sourceUri] directly when reachable.
     * @param bytes Fallback bytes used only when [location]'s path cannot be opened as a
     *   `PDFDocument`. Null when the caller guarantees [location] is a reachable local file.
     * @return A PNG-encoded thumbnail of the first page, sized to fit a 360×480 box by PDFKit's own
     *   `thumbnailOfSize`, or `null` if the document has no first page or rendering fails for any
     *   reason.
     */
    override fun coverImageBytes(location: DocumentLocation, bytes: ByteArray?): ByteArray? =
        withPdfDocument(location, bytes) { document ->
            val page = document.pageAtIndex(0UL) ?: return@withPdfDocument null
            val thumbnail = page.thumbnailOfSize(
                size = CGSizeMake(360.0, 480.0),
                forBox = kPDFDisplayBoxMediaBox,
            )
            UIImagePNGRepresentation(thumbnail)?.toByteArray()
        }

    /**
     * Opens a [PDFDocument] using the location-first strategy: tries the local file path from
     * [location] first, then falls back to writing [bytes] to a temporary file. Executes [block]
     * against whichever document was successfully opened, cleaning up any temporary file afterward.
     *
     * @param location The document's location to try opening first.
     * @param bytes Fallback bytes to materialize into a temp file when [location] cannot be opened.
     * @param block The work to do with the opened [PDFDocument].
     * @return The result of [block], or null if no document could be opened.
     */
    private fun <T> withPdfDocument(
        location: DocumentLocation,
        bytes: ByteArray?,
        block: (PDFDocument) -> T,
    ): T? {
        val documentFromLocation = openFromLocation(location)
        if (documentFromLocation != null) {
            return runCatching { block(documentFromLocation) }.getOrNull()
        }
        if (bytes == null) return null
        val fileSystem = systemFileSystem()
        val tempPath = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "tedd-reader-pdf-cover-${Random.nextLong().toString(16)}.pdf"
        val sink = fileSystem.sink(tempPath).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
        return try {
            val url = NSURL.fileURLWithPath(tempPath.toString())
            val document = PDFDocument(url)
            block(document)
        } catch (_: Throwable) {
            null
        } finally {
            fileSystem.delete(tempPath)
        }
    }

    /**
     * Attempts to open a [PDFDocument] from [location]'s file path. Returns null when the URI is
     * not a `file://` path or the file at that path cannot be opened as a valid PDF.
     *
     * @param location The document location to resolve.
     * @return An opened [PDFDocument], or null when direct access is not possible.
     */
    private fun openFromLocation(location: DocumentLocation): PDFDocument? = runCatching {
        val path = location.sourceUri.removePrefix("file://")
        val url = NSURL.fileURLWithPath(path)
        PDFDocument(url)
    }.getOrNull()
}

/**
 * Copies this `NSData`'s bytes into a Kotlin [ByteArray].
 *
 * @receiver The data to copy.
 * @return An equal-length [ByteArray]. Empty input is special-cased to an empty array without
 *   touching native memory, since pinning a zero-length [ByteArray] and taking its address is
 *   undefined behavior on Kotlin/Native.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val result = ByteArray(size)
    if (size == 0) return result

    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, size.convert())
    }
    return result
}

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
 * iOS's [PdfMetadataReader], built on PDFKit. Unusually, [pageCount] and [coverImageBytes] resolve
 * the document differently from each other here: [pageCount] opens `PDFDocument` directly from
 * [DocumentLocation.sourceUri]'s file path and never touches `bytes` at all, while [coverImageBytes]
 * instead writes `bytes` out to a fresh temporary file and opens that. Both must therefore describe
 * the same document for the two methods to agree.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPdfMetadataReader : PdfMetadataReader {
    /**
     * @param location The document's location; the page count is read from the file at
     *   [DocumentLocation.sourceUri] directly.
     * @param bytes Unused by this implementation.
     * @return The page count, or `1` if no file exists at `location`'s path or it cannot be opened as
     *   a PDF — this never throws.
     */
    override fun pageCount(location: DocumentLocation, bytes: ByteArray): Int = runCatching {
        val path = location.sourceUri.removePrefix("file://")
        val url = NSURL.fileURLWithPath(path)
        PDFDocument(url).pageCount.toInt().coerceAtLeast(1)
    }.getOrDefault(1)

    /**
     * @param location Unused by this implementation.
     * @param bytes The document's raw bytes, written to a temporary file for PDFKit to open.
     * @return A PNG-encoded thumbnail of the first page, sized to fit a 360×480 box by PDFKit's own
     *   `thumbnailOfSize`, or `null` if the document has no first page or rendering fails for any
     *   reason. The temporary file is always deleted before returning.
     */
    override fun coverImageBytes(location: DocumentLocation, bytes: ByteArray): ByteArray? {
        val fileSystem = systemFileSystem()
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "tedd-reader-pdf-cover-${Random.nextLong().toString(16)}.pdf"
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
        return try {
            val url = NSURL.fileURLWithPath(path.toString())
            val document = PDFDocument(url)
            val page = document.pageAtIndex(0UL) ?: return null
            val thumbnail = page.thumbnailOfSize(
                size = CGSizeMake(360.0, 480.0),
                forBox = kPDFDisplayBoxMediaBox,
            )
            UIImagePNGRepresentation(thumbnail)?.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            fileSystem.delete(path)
        }
    }
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

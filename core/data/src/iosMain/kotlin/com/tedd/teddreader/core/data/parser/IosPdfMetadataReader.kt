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

internal actual fun defaultPdfMetadataReader(): PdfMetadataReader = IosPdfMetadataReader()

@OptIn(ExperimentalForeignApi::class)
class IosPdfMetadataReader : PdfMetadataReader {
    override fun pageCount(location: DocumentLocation, bytes: ByteArray): Int = runCatching {
        val path = location.sourceUri.removePrefix("file://")
        val url = NSURL.fileURLWithPath(path)
        PDFDocument(url).pageCount.toInt().coerceAtLeast(1)
    }.getOrDefault(1)

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

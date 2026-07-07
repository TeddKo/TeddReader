package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentLocation
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument

internal actual fun defaultPdfMetadataReader(): PdfMetadataReader = IosPdfMetadataReader()

class IosPdfMetadataReader : PdfMetadataReader {
    override fun pageCount(location: DocumentLocation, bytes: ByteArray): Int = runCatching {
        val path = location.sourceUri.removePrefix("file://")
        val url = NSURL.fileURLWithPath(path)
        PDFDocument(url).pageCount.toInt().coerceAtLeast(1)
    }.getOrDefault(1)
}

package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.SupportedDocumentExtensions
import com.tedd.teddreader.core.common.model.SupportedDocumentMimeTypes
import org.koin.core.annotation.Single

@Single
class DocumentFormatDetector {
    fun detect(location: DocumentLocation, bytes: ByteArray): DocumentFormat {
        val name = location.displayName.lowercase()
        val mimeType = location.mimeType?.lowercase()
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        return when {
            mimeType == "text/plain" || name.endsWith(".txt") -> DocumentFormat.TXT
            mimeType == "application/pdf" || name.endsWith(".pdf") || bytes.startsWithAscii("%PDF") -> DocumentFormat.PDF
            mimeType == "application/epub" || mimeType == "application/epub+zip" || name.endsWith(".epub") -> DocumentFormat.EPUB
            mimeType == "application/vnd.comicbook+zip" || mimeType == "application/x-cbz" || extension == "cbz" ->
                DocumentFormat.CBZ
            mimeType in SupportedImageMimeTypes || extension in SupportedImageExtensions || bytes.hasRasterImageSignature() ->
                DocumentFormat.IMAGE
            else -> DocumentFormat.UNKNOWN
        }
    }
}

private val SupportedImageMimeTypes = SupportedDocumentMimeTypes.filterTo(hashSetOf()) { it.startsWith("image/") }
private val SupportedImageExtensions = SupportedDocumentExtensions.filterTo(hashSetOf()) { extension ->
    extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
}

private fun ByteArray.startsWithAscii(value: String): Boolean =
    size >= value.length && value.indices.all { index -> this[index].toInt() and 0xFF == value[index].code }

private fun ByteArray.hasRasterImageSignature(): Boolean =
    startsWithBytes(0xFF, 0xD8, 0xFF) ||
        startsWithBytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ||
        startsWithAscii("GIF87a") ||
        startsWithAscii("GIF89a") ||
        (startsWithAscii("RIFF") && size >= 12 && copyOfRange(8, 12).startsWithAscii("WEBP")) ||
        startsWithAscii("BM")

private fun ByteArray.startsWithBytes(vararg values: Int): Boolean =
    size >= values.size && values.indices.all { index -> this[index].toInt() and 0xFF == values[index] }

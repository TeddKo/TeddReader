package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentLocation
import org.koin.core.annotation.Single

@Single
class DocumentFormatDetector {
    fun detect(location: DocumentLocation, bytes: ByteArray): DocumentFormat {
        val name = location.displayName.lowercase()
        val mimeType = location.mimeType?.lowercase()
        val header = bytes.take(8).map { it.toInt().toChar() }.joinToString("")
        return when {
            mimeType == "text/plain" || name.endsWith(".txt") -> DocumentFormat.TXT
            mimeType == "application/pdf" || name.endsWith(".pdf") || header.startsWith("%PDF") -> DocumentFormat.PDF
            mimeType == "application/epub" || mimeType == "application/epub+zip" || name.endsWith(".epub") -> DocumentFormat.EPUB
            else -> DocumentFormat.UNKNOWN
        }
    }
}

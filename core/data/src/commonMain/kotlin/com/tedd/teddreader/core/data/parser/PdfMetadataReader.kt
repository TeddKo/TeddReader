package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentLocation

fun interface PdfMetadataReader {
    fun pageCount(location: DocumentLocation, bytes: ByteArray): Int
}

internal expect fun defaultPdfMetadataReader(): PdfMetadataReader

package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentLocation

fun interface PdfMetadataReader {
    fun pageCount(location: DocumentLocation, bytes: ByteArray): Int
    fun coverImageBytes(location: DocumentLocation, bytes: ByteArray): ByteArray? = null
}

internal expect fun defaultPdfMetadataReader(): PdfMetadataReader

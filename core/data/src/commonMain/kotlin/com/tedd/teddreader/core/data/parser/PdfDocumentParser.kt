package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.ReaderDocument
import org.koin.core.annotation.Single

@Single
class PdfDocumentParser(
    private val metadataReader: PdfMetadataReader = defaultPdfMetadataReader(),
) {
    fun parse(
        id: DocumentId,
        title: String,
        location: DocumentLocation,
        bytes: ByteArray,
    ): ReaderDocument = ReaderDocument(
        id = id,
        format = DocumentFormat.PDF,
        title = title,
        sections = emptyList(),
        pageCount = metadataReader.pageCount(location, bytes).coerceAtLeast(1),
    )

    fun coverImageBytes(
        location: DocumentLocation,
        bytes: ByteArray,
    ): ByteArray? = metadataReader.coverImageBytes(location, bytes)
}

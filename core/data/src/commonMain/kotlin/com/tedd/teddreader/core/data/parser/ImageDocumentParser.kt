package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import org.koin.core.annotation.Single

@Single
class ImageDocumentParser {
    fun parse(
        id: DocumentId,
        title: String,
    ): ReaderDocument = ReaderDocument(
        id = id,
        format = DocumentFormat.IMAGE,
        title = title,
        sections = emptyList(),
        pageCount = 1,
    )
}

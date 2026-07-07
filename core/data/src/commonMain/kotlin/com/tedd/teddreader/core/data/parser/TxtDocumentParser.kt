package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.TextRange
import org.koin.core.annotation.Single

@Single
class TxtDocumentParser {
    fun parse(
        id: DocumentId,
        title: String,
        text: String,
    ): ReaderDocument {
        val normalizedText = text.replace("\r\n", "\n").replace('\r', '\n')
        return ReaderDocument(
            id = id,
            format = DocumentFormat.TXT,
            title = title,
            sections = listOf(
                ReaderSection(
                    index = 0,
                    title = title,
                    text = normalizedText,
                    range = TextRange(0L, normalizedText.length.toLong()),
                ),
            ),
        )
    }
}

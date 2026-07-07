package com.tedd.teddreader.core.data.mapper

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentEntityMapperTest {
    @Test
    fun documentMetadataRoundTripsThroughEntity() {
        val metadata = DocumentMetadata(
            id = DocumentId("doc-1"),
            location = DocumentLocation(
                sourceUri = "file:///book.txt",
                displayName = "book.txt",
                mimeType = "text/plain",
                sizeBytes = 42L,
            ),
            format = DocumentFormat.TXT,
            addedAtEpochMillis = 1_000L,
            pageCount = 3,
            characterCount = 100L,
            wordCount = 20L,
        )

        assertEquals(metadata, metadata.toDocumentEntity().toDocumentMetadata())
    }
}

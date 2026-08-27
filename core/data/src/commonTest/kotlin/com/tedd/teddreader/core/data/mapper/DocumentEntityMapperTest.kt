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

        val entity = metadata.toDocumentEntity().copy(importCompletedAtEpochMillis = 1_000L)
        assertEquals(metadata, entity.toDocumentMetadata())
    }

    @Test
    fun documentMetadataRoundTripsFolderMembershipThroughEntity() {
        val metadata = DocumentMetadata(
            id = DocumentId("doc-folder"),
            location = DocumentLocation(
                sourceUri = "file:///folder-book.epub",
                displayName = "folder-book.epub",
                mimeType = "application/epub+zip",
                sizeBytes = 99L,
            ),
            format = DocumentFormat.EPUB,
            addedAtEpochMillis = 2_000L,
            folderId = "folder-42",
            folderName = "Weekend Reads",
        )

        val entity = metadata.toDocumentEntity().copy(importCompletedAtEpochMillis = 2_000L)
        assertEquals(metadata, entity.toDocumentMetadata())
    }

    @Test
    fun incompleteImportMasksCountsInDomainMetadata() {
        val entity = DocumentMetadata(
            id = DocumentId("doc-partial"),
            location = DocumentLocation(
                sourceUri = "file:///partial.epub",
                displayName = "partial.epub",
                mimeType = "application/epub+zip",
            ),
            format = DocumentFormat.EPUB,
            addedAtEpochMillis = 3_000L,
            characterCount = 500L,
            wordCount = 80L,
        ).toDocumentEntity()

        val metadata = entity.toDocumentMetadata()
        assertEquals(null, metadata.characterCount, "domain metadata masks characterCount when import is incomplete")
        assertEquals(null, metadata.wordCount, "domain metadata masks wordCount when import is incomplete")
    }
}

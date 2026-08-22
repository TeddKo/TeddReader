package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the invariant that keeps a library row from claiming a folder it cannot name: folder id and folder
 * name are either both present or both absent, and a half-filled pair is rejected at construction rather
 * than rendered as a blank folder chip.
 */
class DocumentModelsTest {
    @Test
    fun documentMetadataAcceptsFolderMembershipPair() {
        val metadata = DocumentMetadata(
            id = DocumentId("doc-1"),
            location = DocumentLocation(
                sourceUri = "file:///book.epub",
                displayName = "book.epub",
            ),
            format = DocumentFormat.EPUB,
            addedAtEpochMillis = 1_000L,
            folderId = "folder-1",
            folderName = "Favorites",
        )

        assertEquals("folder-1", metadata.folderId)
        assertEquals("Favorites", metadata.folderName)
    }

    @Test
    fun documentMetadataRejectsPartialFolderMembershipPair() {
        assertFailsWith<IllegalArgumentException> {
            DocumentMetadata(
                id = DocumentId("doc-1"),
                location = DocumentLocation(
                    sourceUri = "file:///book.epub",
                    displayName = "book.epub",
                ),
                format = DocumentFormat.EPUB,
                addedAtEpochMillis = 1_000L,
                folderId = "folder-1",
                folderName = null,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            DocumentMetadata(
                id = DocumentId("doc-1"),
                location = DocumentLocation(
                    sourceUri = "file:///book.epub",
                    displayName = "book.epub",
                ),
                format = DocumentFormat.EPUB,
                addedAtEpochMillis = 1_000L,
                folderId = null,
                folderName = "Favorites",
            )
        }
    }
}

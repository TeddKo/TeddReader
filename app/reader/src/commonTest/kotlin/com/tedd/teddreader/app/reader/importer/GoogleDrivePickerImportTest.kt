package com.tedd.teddreader.app.reader.importer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleDrivePickerImportTest {
    @Test
    fun parsePickedFileIdsTrimsFiltersBlanksPreservesOrderAndDeduplicates() {
        assertEquals(
            listOf("file-1", "file-2", "file-3"),
            parsePickedFileIds(" file-1, file-2 ,, file-1, ,file-3 , file-2 "),
        )
    }

    @Test
    fun parseDriveFileMetadataReadsCoreFields() {
        val metadata = parseDriveFileMetadata(
            """
                {
                  "id": "drive-file-1",
                  "name": "Book Title.epub",
                  "mimeType": "application/epub+zip",
                  "size": "321",
                  "capabilities": {
                    "canDownload": true
                  }
                }
            """.trimIndent(),
        )

        assertEquals("drive-file-1", metadata.id)
        assertEquals("Book Title.epub", metadata.name)
        assertEquals("application/epub+zip", metadata.mimeType)
        assertEquals(321L, metadata.sizeBytes)
        assertTrue(metadata.canDownload)
    }

    @Test
    fun driveFileMetadataSupportRequiresDownloadableTxtPdfOrEpub() {
        assertTrue(
            GoogleDriveFileMetadata(
                id = "txt-by-mime",
                name = "ignored.bin",
                mimeType = "text/plain",
                sizeBytes = 1L,
                canDownload = true,
            ).isImportSupported(),
        )
        assertTrue(
            GoogleDriveFileMetadata(
                id = "pdf-by-extension",
                name = "chapter.PDF",
                mimeType = "application/octet-stream",
                sizeBytes = 2L,
                canDownload = true,
            ).isImportSupported(),
        )
        assertTrue(
            GoogleDriveFileMetadata(
                id = "epub-by-extension",
                name = "novel.epub",
                mimeType = null,
                sizeBytes = 3L,
                canDownload = true,
            ).isImportSupported(),
        )
        assertFalse(
            GoogleDriveFileMetadata(
                id = "blocked-download",
                name = "book.pdf",
                mimeType = "application/pdf",
                sizeBytes = 4L,
                canDownload = false,
            ).isImportSupported(),
        )
        assertFalse(
            GoogleDriveFileMetadata(
                id = "unsupported",
                name = "notes.docx",
                mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                sizeBytes = 5L,
                canDownload = true,
            ).isImportSupported(),
        )
    }

    @Test
    fun driveFileMetadataAndBytesConvertToDocumentImportSource() {
        val bytes = "hello drive".encodeToByteArray()
        val metadata = GoogleDriveFileMetadata(
            id = "drive-123",
            name = "hello.txt",
            mimeType = "text/plain",
            sizeBytes = 11L,
            canDownload = true,
        )

        val source = metadata.toDocumentImportSource(bytes)

        assertEquals("gdrive://drive-123", source.location.sourceUri)
        assertEquals("hello.txt", source.location.displayName)
        assertEquals("text/plain", source.location.mimeType)
        assertEquals(11L, source.location.sizeBytes)
        assertContentEquals(bytes, source.bytes)
    }

    @Test
    fun googleDrivePickerResultRejectsBlankAccessToken() {
        assertFailsWith<IllegalArgumentException> {
            GoogleDrivePickerResult(
                accessToken = " ",
                fileIds = listOf("file-1"),
            )
        }
    }

    @Test
    fun googleDrivePickerResultRejectsEmptyFileIds() {
        assertFailsWith<IllegalArgumentException> {
            GoogleDrivePickerResult(
                accessToken = "token-1",
                fileIds = emptyList(),
            )
        }
    }

    @Test
    fun googleDrivePickerResultKeepsTokenAndIds() {
        val result = GoogleDrivePickerResult(
            accessToken = "token-1",
            fileIds = listOf("file-1", "file-2"),
        )

        assertEquals("token-1", result.accessToken)
        assertEquals(listOf("file-1", "file-2"), result.fileIds)
    }
}

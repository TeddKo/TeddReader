package com.tedd.teddreader.app.reader.importer

import com.tedd.teddreader.core.common.model.DocumentLocation
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
    fun driveFileMetadataSupportRequiresCanonicalSupportedFormats() {
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
        assertTrue(
            GoogleDriveFileMetadata(
                id = "cbz-by-mime",
                name = "comic.cbz",
                mimeType = "application/vnd.comicbook+zip",
                sizeBytes = 3L,
                canDownload = true,
            ).isImportSupported(),
        )
        assertTrue(
            GoogleDriveFileMetadata(
                id = "image-by-mime",
                name = "cover.webp",
                mimeType = "image/webp",
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

    /** 앱 파일 위치에서 직접 처리하는 PDF는 전체 ByteArray를 요구하지 않는지 검증한다. */
    @Test
    fun materializedPdfDoesNotRequireImportBytes() {
        val location = DocumentLocation(
            sourceUri = "file:///documents/book.pdf",
            displayName = "book.pdf",
            mimeType = "application/pdf",
            sizeBytes = 100L,
        )

        assertFalse(location.requiresImportBytes())
    }

    /** 지속 권한으로 원본 content URI를 보관한 PDF는 메타데이터 리더가 직접 열 수 없어 바이트를 요구하는지 검증한다. */
    @Test
    fun contentUriPdfRequiresImportBytes() {
        val location = DocumentLocation(
            sourceUri = "content://com.android.providers.media.documents/document/book.pdf",
            displayName = "book.pdf",
            mimeType = "application/pdf",
            sizeBytes = 100L,
        )

        assertTrue(location.requiresImportBytes())
    }

    /** 텍스트 디코더 입력인 TXT는 앱 파일 구체화 뒤에도 전체 ByteArray를 요구하는지 검증한다. */
    @Test
    fun materializedTextRequiresImportBytes() {
        val location = DocumentLocation(
            sourceUri = "file:///documents/book.txt",
            displayName = "book.txt",
            mimeType = "text/plain",
            sizeBytes = 100L,
        )

        assertTrue(location.requiresImportBytes())
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

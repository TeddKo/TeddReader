package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PdfDocumentParserTest {
    private val location = DocumentLocation(
        sourceUri = "file:///book.pdf",
        displayName = "Book.pdf",
        mimeType = "application/pdf",
        sizeBytes = 12L,
    )

    @Test
    fun usesPlatformMetadataReaderPageCount() {
        val bytes = byteArrayOf(1, 2, 3)
        val parser = PdfDocumentParser { passedLocation, passedBytes ->
            assertEquals(location, passedLocation)
            assertContentEquals(bytes, passedBytes)
            7
        }

        val document = parser.parse(
            id = DocumentId("pdf-1"),
            title = "Book",
            location = location,
            bytes = bytes,
        )

        assertEquals(DocumentFormat.PDF, document.format)
        assertEquals(7, document.pageCount)
        assertEquals(0, document.sections.size)
    }

    @Test
    fun coercesInvalidPlatformPageCountToOne() {
        val parser = PdfDocumentParser { _, _ -> 0 }

        val document = parser.parse(
            id = DocumentId("pdf-1"),
            title = "Book",
            location = location,
            bytes = byteArrayOf(),
        )

        assertEquals(1, document.pageCount)
    }
}

package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Pins [PdfDocumentParser]'s delegation contract to its injected [PdfMetadataReader]: the reader's page
 * count and cover bytes pass through unchanged, and an invalid (zero or negative) page count is
 * floored to 1 rather than producing a page-less document.
 */
class PdfDocumentParserTest {
    /**
     * A fixed [DocumentLocation] fixture shared by every test in this file; its field values are not
     * exercised by the parser itself, only passed through to the fake [PdfMetadataReader].
     */
    private val location = DocumentLocation(
        sourceUri = "file:///book.pdf",
        displayName = "Book.pdf",
        mimeType = "application/pdf",
        sizeBytes = 12L,
    )

    /**
     * The parser's page count and format come straight from the injected [PdfMetadataReader], and no
     * sections are produced.
     */
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

    /**
     * Regression guard: a platform reader reporting 0 pages (a malformed or unreadable PDF) must not
     * produce a page-less document — the count is floored to 1.
     */
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

    /** The platform reader's cover bytes pass through [PdfDocumentParser.coverImageBytes] unchanged. */
    @Test
    fun passesThroughPlatformCoverBytes() {
        val bytes = byteArrayOf(9, 8, 7)
        val parser = PdfDocumentParser(
            object : PdfMetadataReader {
                override fun pageCount(location: DocumentLocation, bytes: ByteArray): Int = 1
                override fun coverImageBytes(location: DocumentLocation, bytes: ByteArray): ByteArray? = bytes
            },
        )

        assertContentEquals(
            bytes,
            parser.coverImageBytes(location, bytes),
        )
    }
}

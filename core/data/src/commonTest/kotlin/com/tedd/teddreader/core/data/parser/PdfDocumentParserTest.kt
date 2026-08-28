package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [PdfDocumentParser]'s delegation contract to its injected [PdfMetadataReader]: the reader's page
 * count and cover bytes pass through unchanged, and an invalid (zero or negative) page count is
 * floored to 1 rather than producing a page-less document. Also verifies the location-first contract:
 * callers may pass `bytes = null` when [DocumentLocation.sourceUri] points at a reachable local file.
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
     * sections are produced. Bytes are passed through to the reader unchanged.
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
                override fun pageCount(location: DocumentLocation, bytes: ByteArray?): Int = 1
                override fun coverImageBytes(location: DocumentLocation, bytes: ByteArray?): ByteArray? = bytes
            },
        )

        assertContentEquals(
            bytes,
            parser.coverImageBytes(location, bytes),
        )
    }

    /**
     * When bytes are null (location-first path), the parser passes null through to the metadata
     * reader, which resolves the document from [DocumentLocation.sourceUri] instead.
     */
    @Test
    fun passesNullBytesToMetadataReaderWhenLocationFirst() {
        var receivedBytes: ByteArray? = byteArrayOf(99)
        val parser = PdfDocumentParser { passedLocation, passedBytes ->
            assertEquals(location, passedLocation)
            receivedBytes = passedBytes
            5
        }

        val document = parser.parse(
            id = DocumentId("pdf-2"),
            title = "Book",
            location = location,
            bytes = null,
        )

        assertEquals(5, document.pageCount)
        assertNull(receivedBytes)
    }

    /**
     * Cover extraction with null bytes delegates to [PdfMetadataReader.coverImageBytes] with null,
     * so the platform reader resolves from location. A fake that returns null when bytes are null
     * confirms the null is forwarded.
     */
    @Test
    fun coverImageBytesWithNullBytesDelegatesToLocation() {
        val parser = PdfDocumentParser(
            object : PdfMetadataReader {
                override fun pageCount(location: DocumentLocation, bytes: ByteArray?): Int = 1
                override fun coverImageBytes(location: DocumentLocation, bytes: ByteArray?): ByteArray? {
                    return if (bytes == null) byteArrayOf(1, 2, 3) else null
                }
            },
        )

        val result = parser.coverImageBytes(location, null)
        assertContentEquals(byteArrayOf(1, 2, 3), result)
    }

    /**
     * Mutation guard: verifies that a metadata reader that distinguishes between a location-only
     * call (bytes = null) and a bytes-provided call yields different results through the parser,
     * proving the parser does not silently supply a default or ignore the null.
     */
    @Test
    fun metadataReaderReceivesDistinctBytesPresence() {
        var pageCountCallBytes: ByteArray? = byteArrayOf(99)
        var coverCallBytes: ByteArray? = byteArrayOf(99)
        val parser = PdfDocumentParser(
            object : PdfMetadataReader {
                override fun pageCount(location: DocumentLocation, bytes: ByteArray?): Int {
                    pageCountCallBytes = bytes
                    return if (bytes == null) 10 else 20
                }

                override fun coverImageBytes(location: DocumentLocation, bytes: ByteArray?): ByteArray? {
                    coverCallBytes = bytes
                    return if (bytes == null) byteArrayOf(0xA) else byteArrayOf(0xB)
                }
            },
        )

        val documentLocationOnly = parser.parse(
            id = DocumentId("pdf-loc"),
            title = "Book",
            location = location,
            bytes = null,
        )
        assertEquals(10, documentLocationOnly.pageCount)
        assertNull(pageCountCallBytes)

        val coverLocationOnly = parser.coverImageBytes(location, null)
        assertContentEquals(byteArrayOf(0xA), coverLocationOnly)
        assertNull(coverCallBytes)

        val documentWithBytes = parser.parse(
            id = DocumentId("pdf-bytes"),
            title = "Book",
            location = location,
            bytes = byteArrayOf(1),
        )
        assertEquals(20, documentWithBytes.pageCount)
        assertContentEquals(byteArrayOf(1), pageCountCallBytes)

        val coverWithBytes = parser.coverImageBytes(location, byteArrayOf(2))
        assertContentEquals(byteArrayOf(0xB), coverWithBytes)
        assertContentEquals(byteArrayOf(2), coverCallBytes)
    }
}

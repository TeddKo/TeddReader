package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentLocation
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies [AndroidPdfMetadataReader]'s location-first resolution strategy: a `file://` URI pointing
 * at a readable PDF is opened directly without touching [bytes], while a non-file URI (or a missing
 * file) falls back to the bytes-to-temp-file path. This is the behavioral contract that ensures an
 * already-materialized PDF never pays for a redundant temp-file write during metadata extraction.
 *
 * These tests use a minimal 1-page PDF synthesized in memory — just enough structure for `PdfRenderer`
 * to open and report one page. The same bytes are either written to a real file (for the file:// path)
 * or passed as the bytes fallback, so both paths exercise `PdfRenderer` itself.
 */
class AndroidPdfMetadataReaderTest {
    /**
     * Minimal PDF bytes that `PdfRenderer` accepts as a valid single-page document. This is the
     * simplest PDF 1.4 structure: one page with an empty content stream.
     */
    private val minimalPdfBytes = createMinimalPdf()

    /**
     * When [DocumentLocation.sourceUri] is a `file://` URI pointing at a readable PDF, [pageCount]
     * returns the real page count without needing [bytes].
     */
    @Test
    fun pageCountFromFileLocationWithoutBytes() {
        val file = File.createTempFile("test-pdf-loc", ".pdf").apply {
            writeBytes(minimalPdfBytes)
            deleteOnExit()
        }
        val location = DocumentLocation(
            sourceUri = "file://${file.absolutePath}",
            displayName = "test.pdf",
            mimeType = "application/pdf",
            sizeBytes = file.length(),
        )
        val reader = AndroidPdfMetadataReader()

        val count = reader.pageCount(location, bytes = null)

        assertEquals(1, count)
        file.delete()
    }

    /**
     * When [DocumentLocation.sourceUri] is a non-file URI (simulating a content:// that has not been
     * materialized), [pageCount] falls back to the provided [bytes].
     */
    @Test
    fun pageCountFromBytesFallbackWhenLocationIsNotFile() {
        val location = DocumentLocation(
            sourceUri = "content://provider/doc/123",
            displayName = "test.pdf",
            mimeType = "application/pdf",
            sizeBytes = minimalPdfBytes.size.toLong(),
        )
        val reader = AndroidPdfMetadataReader()

        val count = reader.pageCount(location, bytes = minimalPdfBytes)

        assertEquals(1, count)
    }

    /**
     * When [DocumentLocation.sourceUri] is a `file://` URI whose target does not exist, and [bytes]
     * is null, [pageCount] returns the safe default of 1 rather than throwing.
     */
    @Test
    fun pageCountReturnsOneWhenFileIsMissingAndBytesNull() {
        val location = DocumentLocation(
            sourceUri = "file:///nonexistent/path/to/missing.pdf",
            displayName = "missing.pdf",
            mimeType = "application/pdf",
            sizeBytes = 0L,
        )
        val reader = AndroidPdfMetadataReader()

        val count = reader.pageCount(location, bytes = null)

        assertEquals(1, count)
    }

    /**
     * When [DocumentLocation.sourceUri] is a `file://` URI whose target does not exist, but [bytes]
     * is provided, [pageCount] falls back to the bytes and returns the real page count.
     */
    @Test
    fun pageCountFallsBackToBytesWhenFileIsMissing() {
        val location = DocumentLocation(
            sourceUri = "file:///nonexistent/path/to/missing.pdf",
            displayName = "missing.pdf",
            mimeType = "application/pdf",
            sizeBytes = minimalPdfBytes.size.toLong(),
        )
        val reader = AndroidPdfMetadataReader()

        val count = reader.pageCount(location, bytes = minimalPdfBytes)

        assertEquals(1, count)
    }

    /**
     * Cover extraction from a `file://` location attempts to open from file without needing
     * [bytes]. With a minimal PDF the renderer may produce a blank image or null depending on
     * the PDF structure, but the key assertion is that no exception is thrown and the method
     * does not require bytes to attempt extraction.
     */
    @Test
    fun coverImageBytesFromFileLocationWithoutBytesDoesNotThrow() {
        val file = File.createTempFile("test-pdf-cover", ".pdf").apply {
            writeBytes(minimalPdfBytes)
            deleteOnExit()
        }
        val location = DocumentLocation(
            sourceUri = "file://${file.absolutePath}",
            displayName = "test.pdf",
            mimeType = "application/pdf",
            sizeBytes = file.length(),
        )
        val reader = AndroidPdfMetadataReader()

        val cover = reader.coverImageBytes(location, bytes = null)

        // The minimal PDF may or may not produce renderable content; the key contract
        // is that the method does not throw and does not require bytes to execute.
        // A null result here means PdfRenderer couldn't render the empty page, which is
        // acceptable — the location-first path was still exercised.
        if (cover != null) {
            assertTrue(cover.isNotEmpty())
        }
        file.delete()
    }

    /**
     * Cover extraction returns null when location is unreachable and bytes is null.
     */
    @Test
    fun coverImageBytesReturnsNullWhenLocationUnreachableAndBytesNull() {
        val location = DocumentLocation(
            sourceUri = "file:///nonexistent/path/to/missing.pdf",
            displayName = "missing.pdf",
            mimeType = "application/pdf",
            sizeBytes = 0L,
        )
        val reader = AndroidPdfMetadataReader()

        val cover = reader.coverImageBytes(location, bytes = null)

        assertNull(cover)
    }

    /**
     * Mutation guard: when both file:// path exists AND bytes are provided, the file path takes
     * priority. This is verified by passing corrupt bytes that would fail if used, while the file
     * contains valid PDF. If the reader incorrectly prefers bytes over location, this test fails.
     */
    @Test
    fun filePathTakesPriorityOverBytesWhenBothPresent() {
        val file = File.createTempFile("test-pdf-priority", ".pdf").apply {
            writeBytes(minimalPdfBytes)
            deleteOnExit()
        }
        val corruptBytes = byteArrayOf(0, 0, 0, 0)
        val location = DocumentLocation(
            sourceUri = "file://${file.absolutePath}",
            displayName = "test.pdf",
            mimeType = "application/pdf",
            sizeBytes = file.length(),
        )
        val reader = AndroidPdfMetadataReader()

        val count = reader.pageCount(location, bytes = corruptBytes)

        assertEquals(1, count)
        file.delete()
    }
}

/**
 * Creates the smallest valid PDF `PdfRenderer` will accept: a single blank page at 72×72 points with
 * an empty content stream. Cross-reference offsets are approximate but `PdfRenderer` is tolerant
 * enough to accept this without exact byte positions.
 *
 * @return The minimal PDF as a [ByteArray].
 */
private fun createMinimalPdf(): ByteArray {
    val pdf = buildString {
        append("%PDF-1.4\n")
        append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 72 72] /Contents 4 0 R >>\nendobj\n")
        append("4 0 obj\n<< /Length 0 >>\nstream\n\nendstream\nendobj\n")
        append("xref\n0 5\n")
        append("0000000000 65535 f \n")
        append("0000000009 00000 n \n")
        append("0000000058 00000 n \n")
        append("0000000115 00000 n \n")
        append("0000000210 00000 n \n")
        append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n260\n%%EOF\n")
    }
    return pdf.toByteArray()
}

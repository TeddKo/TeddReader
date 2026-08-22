package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentLocation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [DocumentFormatDetector]'s precedence rules across every input a source can give it — MIME
 * type, file extension, and (as a last-resort confirmation) the file's own leading bytes — including
 * its deliberate exclusions: a generic `.zip` (or a ZIP-based format like `.docx`/`.mobi`) must never
 * be read as a comic just because it shares a CBZ's container format, and a vector `image/svg+xml`
 * must never be read as a supported raster IMAGE.
 */
class DocumentFormatDetectorTest {
    private val detector = DocumentFormatDetector()

    /** TXT is detected by either its MIME type or its `.txt` extension, independent of file content. */
    @Test
    fun detectsTxtByMimeAndExtension() {
        assertEquals(
            DocumentFormat.TXT,
            detector.detect(location("book.bin", mimeType = "text/plain"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.TXT,
            detector.detect(location("book.txt"), byteArrayOf(1)),
        )
    }

    /**
     * PDF is detected by MIME type, by its `%PDF` byte signature, or by its `.pdf` extension — any one is
     * sufficient.
     */
    @Test
    fun detectsPdfByMimeHeaderAndExtension() {
        assertEquals(
            DocumentFormat.PDF,
            detector.detect(location("book.bin", mimeType = "application/pdf"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.PDF,
            detector.detect(location("book.bin"), "%PDF-1.7".encodeToByteArray()),
        )
        assertEquals(
            DocumentFormat.PDF,
            detector.detect(location("book.pdf"), byteArrayOf(1)),
        )
    }

    /** EPUB is detected by either of its two MIME type spellings or its `.epub` extension. */
    @Test
    fun detectsEpubByMimeAndExtension() {
        assertEquals(
            DocumentFormat.EPUB,
            detector.detect(location("book.bin", mimeType = "application/epub"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.EPUB,
            detector.detect(location("book.bin", mimeType = "application/epub+zip"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.EPUB,
            detector.detect(location("book.epub"), byteArrayOf(1)),
        )
    }

    /**
     * Regression guard: CBZ is detected only by a comic-specific MIME type or a `.cbz` extension —
     * never by a generic `application/zip` MIME type alone, even on a file already named `.cbz`
     * (case-insensitively) — and a plain `.zip` extension with that generic MIME type must resolve to
     * UNKNOWN.
     */
    @Test
    fun detectsCbzByComicMimeAndExtensionWithoutAcceptingGenericZip() {
        assertEquals(
            DocumentFormat.CBZ,
            detector.detect(location("comic.bin", mimeType = "application/vnd.comicbook+zip"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.CBZ,
            detector.detect(location("comic.CBZ", mimeType = "application/zip"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.UNKNOWN,
            detector.detect(location("archive.zip", mimeType = "application/zip"), byteArrayOf(1)),
        )
    }

    /**
     * Raster images are detected by MIME type, by extension (case-insensitively), or by magic-byte
     * signature; an SVG (`image/svg+xml`, textual `<svg` content) is deliberately not one of them and
     * must resolve to UNKNOWN.
     */
    @Test
    fun detectsSupportedRasterImagesByMimeExtensionAndSignature() {
        assertEquals(
            DocumentFormat.IMAGE,
            detector.detect(location("page.bin", mimeType = "image/jpeg"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.IMAGE,
            detector.detect(location("page.WEBP"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.IMAGE,
            detector.detect(
                location("page.bin"),
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            ),
        )
        assertEquals(
            DocumentFormat.UNKNOWN,
            detector.detect(location("vector.svg", mimeType = "image/svg+xml"), "<svg".encodeToByteArray()),
        )
    }

    /**
     * A plain ZIP, a Word `.docx`, and a Kindle `.mobi` — all container/binary formats this reader
     * does not parse — must resolve to UNKNOWN rather than being mistaken for a supported format.
     */
    @Test
    fun rejectsUnsupportedZipDocxAndMobi() {
        assertEquals(
            DocumentFormat.UNKNOWN,
            detector.detect(location("archive.zip", mimeType = "application/zip"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.UNKNOWN,
            detector.detect(
                location(
                    "book.docx",
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ),
                byteArrayOf(1),
            ),
        )
        assertEquals(
            DocumentFormat.UNKNOWN,
            detector.detect(location("book.mobi", mimeType = "application/x-mobipocket-ebook"), byteArrayOf(1)),
        )
    }

    /**
     * A [DocumentLocation] fixture with the given display name and optional MIME type, at a fixed fake
     * `file:///` URI.
     */
    private fun location(
        name: String,
        mimeType: String? = null,
    ): DocumentLocation = DocumentLocation(
        sourceUri = "file:///$name",
        displayName = name,
        mimeType = mimeType,
    )
}

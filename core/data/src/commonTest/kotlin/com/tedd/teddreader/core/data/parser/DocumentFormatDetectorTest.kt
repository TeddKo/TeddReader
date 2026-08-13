package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentFormatDetectorTest {
    private val detector = DocumentFormatDetector()

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

    private fun location(
        name: String,
        mimeType: String? = null,
    ): DocumentLocation = DocumentLocation(
        sourceUri = "file:///$name",
        displayName = name,
        mimeType = mimeType,
    )
}

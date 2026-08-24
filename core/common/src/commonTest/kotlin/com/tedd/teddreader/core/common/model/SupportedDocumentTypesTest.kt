package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins that the picker filters and the format list still cover all four kinds of document this reader
 * opens — reflowable text, fixed pages, comics, raster images — so adding a format cannot silently leave
 * the file pickers unable to select it.
 */
class SupportedDocumentTypesTest {
    @Test
    fun supportedDocumentTypesExposeTextFixedPageComicAndRasterImages() {
        assertEquals(
            setOf(
                DocumentFormat.TXT,
                DocumentFormat.PDF,
                DocumentFormat.EPUB,
                DocumentFormat.CBZ,
                DocumentFormat.IMAGE,
            ),
            SupportedDocumentFormats,
        )
        assertTrue(SupportedDocumentMimeTypes.contains("text/plain"))
        assertTrue(SupportedDocumentMimeTypes.contains("application/pdf"))
        assertTrue(SupportedDocumentMimeTypes.contains("application/epub"))
        assertTrue(SupportedDocumentMimeTypes.contains("application/epub+zip"))
        assertTrue(SupportedDocumentMimeTypes.contains("application/vnd.comicbook+zip"))
        assertTrue(SupportedDocumentMimeTypes.contains("image/jpeg"))
        assertTrue(SupportedDocumentMimeTypes.contains("image/png"))
        assertEquals(
            setOf(
                "text/plain",
                "application/pdf",
                "application/epub",
                "application/epub+zip",
                "application/vnd.comicbook+zip",
                "application/x-cbz",
                "application/zip",
                "image/jpeg",
                "image/png",
                "image/webp",
                "image/gif",
                "image/bmp",
            ),
            GoogleDriveSupportedDocumentMimeTypes,
        )
        assertEquals(
            setOf("txt", "pdf", "epub", "cbz", "jpg", "jpeg", "png", "webp", "gif", "bmp"),
            SupportedDocumentExtensions,
        )
        assertFalse(SupportedDocumentFormats.contains(DocumentFormat.UNKNOWN))
    }
}

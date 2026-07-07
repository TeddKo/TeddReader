package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportedDocumentTypesTest {
    @Test
    fun supportedDocumentTypesExposeOnlyTxtPdfAndEpub() {
        assertEquals(
            setOf(DocumentFormat.TXT, DocumentFormat.PDF, DocumentFormat.EPUB),
            SupportedDocumentFormats,
        )
        assertTrue(SupportedDocumentMimeTypes.contains("text/plain"))
        assertTrue(SupportedDocumentMimeTypes.contains("application/pdf"))
        assertTrue(SupportedDocumentMimeTypes.contains("application/epub"))
        assertTrue(SupportedDocumentMimeTypes.contains("application/epub+zip"))
        assertEquals(setOf("txt", "pdf", "epub"), SupportedDocumentExtensions)
        assertFalse(SupportedDocumentFormats.contains(DocumentFormat.UNKNOWN))
    }
}

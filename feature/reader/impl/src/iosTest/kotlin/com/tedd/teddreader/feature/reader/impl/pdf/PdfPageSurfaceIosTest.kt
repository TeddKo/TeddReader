package com.tedd.teddreader.feature.reader.impl.pdf

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfPageSurfaceIosTest {
    @Test
    fun nativePdfNavigatesOnlyWhenTheTargetPageChanges() {
        val page = Any()
        assertFalse(readerPdfShouldNavigate(currentPage = page, targetPage = page))
        assertTrue(readerPdfShouldNavigate(currentPage = Any(), targetPage = page))
        assertFalse(readerPdfShouldNavigate(currentPage = null, targetPage = null))
    }
}

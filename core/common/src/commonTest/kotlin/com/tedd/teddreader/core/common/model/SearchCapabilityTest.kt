package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins when the reader offers to search a document.
 *
 * Both cases are about not offering a search that can only answer "no results": a PDF with no extracted
 * text has nothing to match, and a book whose sections exist but are still blank is mid-import rather than
 * empty.
 */
class SearchCapabilityTest {
    @Test
    fun pdfSearchIsNotReportedAsSupportedWithoutExtractedText() {
        val pdf = ReaderDocument(
            id = DocumentId("pdf-1"),
            format = DocumentFormat.PDF,
            title = "PDF",
            sections = emptyList(),
            pageCount = 1,
        )

        assertFalse(pdf.isTextSearchSupported())
    }

    @Test
    fun textDocumentSearchIsSupportedWhenSectionsHaveText() {
        val txt = ReaderDocument(
            id = DocumentId("txt-1"),
            format = DocumentFormat.TXT,
            title = "TXT",
            sections = listOf(
                ReaderSection(index = 0, text = "hello", range = TextRange(0, 5)),
            ),
        )

        assertTrue(txt.isTextSearchSupported())
    }
}

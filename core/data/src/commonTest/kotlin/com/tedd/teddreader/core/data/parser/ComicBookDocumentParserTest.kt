package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals

class ComicBookDocumentParserTest {
    @Test
    fun comicPageNamesFilterMetadataAndUseNaturalReadingOrder() {
        assertEquals(
            listOf("cover.jpg", "chapter/page1.png", "chapter/page2.png", "chapter/page10.png"),
            sortedComicPageNames(
                listOf(
                    "chapter/page10.png",
                    "__MACOSX/._page1.jpg",
                    "notes.txt",
                    "chapter/page2.png",
                    "cover.jpg",
                    "chapter/page1.png",
                ),
            ),
        )
    }

    @Test
    fun comicMetadataUsesImageCountAsPageCount() {
        val document = comicReaderDocument(
            id = DocumentId("comic-1"),
            title = "Comic.cbz",
            pageCount = 12,
        )

        assertEquals(DocumentFormat.CBZ, document.format)
        assertEquals(12, document.pageCount)
        assertEquals(emptyList(), document.sections)
    }
}

package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins how a CBZ's page list is built without ever opening a real ZIP archive: metadata and
 * resource-fork entries are filtered out, pages sort in natural reading order with an entry named
 * `cover` forced first, and the page count alone is enough to build a valid comic
 * [com.tedd.teddreader.core.common.model.ReaderDocument].
 * Actually opening an archive is covered separately by an instrumented test.
 */
class ComicBookDocumentParserTest {
    /**
     * Regression guard: `__MACOSX/` metadata and AppleDouble `._`-prefixed entries, and non-image
     * files, must be dropped; an entry literally named `cover` must always sort first; and the
     * remaining pages must sort in natural numeric order (`page2` before `page10`) rather than plain
     * string order.
     */
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

    /**
     * The synthetic CBZ document carries the given page count and CBZ format, and no sections at all — a
     * comic has no text.
     */
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

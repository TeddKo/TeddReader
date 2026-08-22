package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.PaginatedDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderSpan
import com.tedd.teddreader.core.common.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderPageUiMapperTest {
    @Test
    fun facingUsesMountWindow() {
        val windows = listOf(
            PageWindow(PageIndex(0, 4), ReaderLocation.TextOffset(0), "a", TextRange(0, 1)),
            PageWindow(PageIndex(1, 4), ReaderLocation.TextOffset(1), "b", TextRange(1, 2)),
            PageWindow(PageIndex(2, 4), ReaderLocation.TextOffset(2), "c", TextRange(2, 3)),
            PageWindow(PageIndex(3, 4), ReaderLocation.TextOffset(3), "d", TextRange(3, 4)),
        )
        val facing = readerPageFacingUi(
            ReaderPageUiContext(
                pageIndex = PageIndex(1, 4),
                documentUri = null,
                isPdfMode = false,
                paginated = PaginatedDocument(windows),
                embeddedImages = emptyMap(),
                embeddedFontFiles = emptyMap(),
                failedEmbeddedImageHrefs = emptySet(),
                failedEmbeddedFontHrefs = emptySet(),
            ),
        )
        assertEquals("b", facing.current.text)
        assertEquals(listOf(0, 1, 2, 3), facing.slots.map { it.page })
    }

    @Test
    fun pageUiFiltersEmbeddedFontsToTheCurrentPage() {
        val block = ReaderBlock(
            kind = ReaderBlockKind.PARAGRAPH,
            range = TextRange(0, 4),
            style = ReaderBlockStyle(fontHref = "fonts/body.otf"),
            spans = listOf(
                ReaderSpan(range = TextRange(1, 3), cssStyle = ReaderBlockStyle(fontHref = "fonts/inline.otf")),
            ),
        )
        val page = readerPageUi(
            page = 0,
            context = ReaderPageUiContext(
                pageIndex = PageIndex(0, 1),
                documentUri = null,
                isPdfMode = false,
                paginated = PaginatedDocument(
                    pageWindows = listOf(
                        PageWindow(PageIndex(0, 1), ReaderLocation.TextOffset(0), "test", TextRange(0, 4), blocks = listOf(block)),
                    ),
                ),
                embeddedImages = emptyMap(),
                embeddedFontFiles = mapOf(
                    "fonts/body.otf" to "/tmp/body.otf",
                    "fonts/inline.otf" to "/tmp/inline.otf",
                    "fonts/other.otf" to "/tmp/other.otf",
                ),
                failedEmbeddedImageHrefs = emptySet(),
                failedEmbeddedFontHrefs = setOf("fonts/inline.otf"),
            ),
        )

        assertEquals(
            mapOf("fonts/body.otf" to "/tmp/body.otf", "fonts/inline.otf" to "/tmp/inline.otf"),
            page?.embeddedFontFiles?.toMap(),
        )
        assertEquals(setOf("fonts/inline.otf"), page?.failedEmbeddedFontHrefs?.toSet())
    }

}

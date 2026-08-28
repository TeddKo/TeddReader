package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.PaginatedDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderSpan
import com.tedd.teddreader.core.common.model.ReaderSpanStyle
import com.tedd.teddreader.core.common.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
                ReaderSpan(range = TextRange(1, 3), styleDelta = ReaderSpanStyle(fontHref = "fonts/inline.otf")),
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

    /**
     * A block whose image href, block-style font href, and span font hrefs repeat within the page
     * must collapse to one entry each — the resource maps and sets are keyed by href, so a
     * duplicate reference never produces a second entry and never disturbs first-reference order.
     */
    @Test
    fun pageUiDeduplicatesRepeatedHrefs() {
        val blocks = listOf(
            ReaderBlock(
                kind = ReaderBlockKind.IMAGE,
                range = TextRange(0, 1),
                imageHref = "img/a.png",
                style = ReaderBlockStyle(fontHref = "fonts/body.otf"),
                spans = listOf(
                    ReaderSpan(range = TextRange(0, 1), styleDelta = ReaderSpanStyle(fontHref = "fonts/inline.otf")),
                ),
            ),
            ReaderBlock(
                kind = ReaderBlockKind.IMAGE,
                range = TextRange(1, 2),
                imageHref = "img/a.png",
                style = ReaderBlockStyle(fontHref = "fonts/body.otf"),
                spans = listOf(
                    ReaderSpan(range = TextRange(1, 2), styleDelta = ReaderSpanStyle(fontHref = "fonts/inline.otf")),
                ),
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
                        PageWindow(PageIndex(0, 1), ReaderLocation.TextOffset(0), "ab", TextRange(0, 2), blocks = blocks),
                    ),
                ),
                embeddedImages = mapOf("img/a.png" to byteArrayOf(1)),
                embeddedFontFiles = mapOf("fonts/body.otf" to "/tmp/body.otf", "fonts/inline.otf" to "/tmp/inline.otf"),
                failedEmbeddedImageHrefs = emptySet(),
                failedEmbeddedFontHrefs = emptySet(),
            ),
        )

        assertEquals(listOf("img/a.png"), page?.embeddedImages?.keys?.toList())
        assertEquals(listOf("fonts/body.otf", "fonts/inline.otf"), page?.embeddedFontFiles?.keys?.toList())
    }

    /**
     * The loaded-font map and the failed-font set are resolved against independent inputs, so an
     * href that both has a loaded file and is marked failed must appear in both — one must never
     * suppress the other.
     */
    @Test
    fun pageUiKeepsHrefInBothLoadedAndFailed() {
        val block = ReaderBlock(
            kind = ReaderBlockKind.IMAGE,
            range = TextRange(0, 1),
            imageHref = "img/a.png",
            style = ReaderBlockStyle(fontHref = "fonts/body.otf"),
        )
        val page = readerPageUi(
            page = 0,
            context = ReaderPageUiContext(
                pageIndex = PageIndex(0, 1),
                documentUri = null,
                isPdfMode = false,
                paginated = PaginatedDocument(
                    pageWindows = listOf(
                        PageWindow(PageIndex(0, 1), ReaderLocation.TextOffset(0), "a", TextRange(0, 1), blocks = listOf(block)),
                    ),
                ),
                embeddedImages = mapOf("img/a.png" to byteArrayOf(1)),
                embeddedFontFiles = mapOf("fonts/body.otf" to "/tmp/body.otf"),
                failedEmbeddedImageHrefs = setOf("img/a.png"),
                failedEmbeddedFontHrefs = setOf("fonts/body.otf"),
            ),
        )

        assertEquals(listOf("img/a.png"), page?.embeddedImages?.keys?.toList())
        assertEquals(setOf("img/a.png"), page?.failedEmbeddedImageHrefs?.toSet())
        assertEquals(listOf("fonts/body.otf"), page?.embeddedFontFiles?.keys?.toList())
        assertEquals(setOf("fonts/body.otf"), page?.failedEmbeddedFontHrefs?.toSet())
    }

    /**
     * A block's own style font is offered before that block's span fonts, and blocks keep list
     * order, so the resolved font map iterates style-font-then-span-font per block.
     */
    @Test
    fun pageUiOrdersStyleFontBeforeSpanFont() {
        val block = ReaderBlock(
            kind = ReaderBlockKind.PARAGRAPH,
            range = TextRange(0, 4),
            style = ReaderBlockStyle(fontHref = "fonts/style.otf"),
            spans = listOf(
                ReaderSpan(range = TextRange(0, 2), styleDelta = ReaderSpanStyle(fontHref = "fonts/span-a.otf")),
                ReaderSpan(range = TextRange(2, 4), styleDelta = ReaderSpanStyle(fontHref = "fonts/span-b.otf")),
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
                        PageWindow(PageIndex(0, 1), ReaderLocation.TextOffset(0), "text", TextRange(0, 4), blocks = listOf(block)),
                    ),
                ),
                embeddedImages = emptyMap(),
                embeddedFontFiles = mapOf(
                    "fonts/style.otf" to "/tmp/style.otf",
                    "fonts/span-a.otf" to "/tmp/span-a.otf",
                    "fonts/span-b.otf" to "/tmp/span-b.otf",
                ),
                failedEmbeddedImageHrefs = emptySet(),
                failedEmbeddedFontHrefs = emptySet(),
            ),
        )

        assertEquals(
            listOf("fonts/style.otf", "fonts/span-a.otf", "fonts/span-b.otf"),
            page?.embeddedFontFiles?.keys?.toList(),
        )
    }

    /**
     * A page with no blocks resolves to empty resource collections and touches nothing in the
     * context maps or sets.
     */
    @Test
    fun pageUiWithNoBlocksHasEmptyResources() {
        val page = readerPageUi(
            page = 0,
            context = ReaderPageUiContext(
                pageIndex = PageIndex(0, 1),
                documentUri = null,
                isPdfMode = false,
                paginated = PaginatedDocument(
                    pageWindows = listOf(
                        PageWindow(PageIndex(0, 1), ReaderLocation.TextOffset(0), "text", TextRange(0, 4)),
                    ),
                ),
                embeddedImages = mapOf("img/a.png" to byteArrayOf(1)),
                embeddedFontFiles = mapOf("fonts/body.otf" to "/tmp/body.otf"),
                failedEmbeddedImageHrefs = setOf("img/a.png"),
                failedEmbeddedFontHrefs = setOf("fonts/body.otf"),
            ),
        )

        assertEquals(emptyMap(), page?.embeddedImages?.toMap())
        assertEquals(emptyMap(), page?.embeddedFontFiles?.toMap())
        assertEquals(emptySet(), page?.failedEmbeddedImageHrefs?.toSet())
        assertEquals(emptySet(), page?.failedEmbeddedFontHrefs?.toSet())
    }

    @Test
    fun pageUiCarriesItsChapterLocalPageIndex() {
        val pages = (0 until 5).map { page ->
            PageWindow(
                pageIndex = PageIndex(page, 5),
                location = ReaderLocation.TextOffset(page * 10L),
                text = "page $page",
                textRange = TextRange(page * 10L, (page + 1) * 10L),
            )
        }
        val page = readerPageUi(
            page = 3,
            context = ReaderPageUiContext(
                pageIndex = PageIndex(3, 5),
                documentUri = null,
                isPdfMode = false,
                paginated = PaginatedDocument(
                    pageWindows = pages,
                    sections = listOf(
                        ReaderSection(0, "", TextRange(0, 20), "One"),
                        ReaderSection(1, "", TextRange(20, 50), "Two"),
                    ),
                ),
                embeddedImages = emptyMap(),
                embeddedFontFiles = emptyMap(),
                failedEmbeddedImageHrefs = emptySet(),
                failedEmbeddedFontHrefs = emptySet(),
            ),
        )

        assertEquals("Two", page?.chapterTitle)
        assertEquals(PageIndex(current = 1, total = 3), page?.chapterPageIndex)
    }

    /**
     * A list that counts every element read, so a mapper walking its blocks N times is caught as N
     * reads. The single-pass mapper reads each block index exactly once regardless of how many
     * resource kinds a block contributes.
     *
     * @property delegate the backing block list every read is forwarded to.
     * @property readCount how many element reads have been served, one per [get] call.
     */
    private class CountingBlockList(private val delegate: List<ReaderBlock>) : AbstractList<ReaderBlock>() {
        var readCount = 0
            private set

        override val size: Int get() = delegate.size

        override fun get(index: Int): ReaderBlock {
            readCount++
            return delegate[index]
        }
    }

    /**
     * Guards the near-single-pass invariant on resource resolution. Mapping one page reads its block
     * list four times by design: the immutable copy, the chapter-title and chapter-page cover scans,
     * and one resource walk that folds images, fonts, and failures together. The old mapper's four
     * separate resource walks would still cost seven passes with both chapter queries present.
     */
    @Test
    fun pageUiWalksBlocksInOneResourcePassBeyondTheCopy() {
        val blocks = CountingBlockList(
            listOf(
                ReaderBlock(
                    kind = ReaderBlockKind.IMAGE,
                    range = TextRange(0, 1),
                    imageHref = "img/a.png",
                    style = ReaderBlockStyle(fontHref = "fonts/body.otf"),
                    spans = listOf(
                        ReaderSpan(range = TextRange(0, 1), styleDelta = ReaderSpanStyle(fontHref = "fonts/inline.otf")),
                    ),
                ),
                ReaderBlock(
                    kind = ReaderBlockKind.PARAGRAPH,
                    range = TextRange(1, 4),
                    style = ReaderBlockStyle(fontHref = "fonts/head.otf"),
                ),
            ),
        )
        readerPageUi(
            page = 0,
            context = ReaderPageUiContext(
                pageIndex = PageIndex(0, 1),
                documentUri = null,
                isPdfMode = false,
                paginated = PaginatedDocument(
                    pageWindows = listOf(
                        PageWindow(PageIndex(0, 1), ReaderLocation.TextOffset(0), "abcd", TextRange(0, 4), blocks = blocks),
                    ),
                ),
                embeddedImages = mapOf("img/a.png" to byteArrayOf(1)),
                embeddedFontFiles = mapOf(
                    "fonts/body.otf" to "/tmp/body.otf",
                    "fonts/inline.otf" to "/tmp/inline.otf",
                    "fonts/head.otf" to "/tmp/head.otf",
                ),
                failedEmbeddedImageHrefs = setOf("img/a.png"),
                failedEmbeddedFontHrefs = setOf("fonts/inline.otf"),
            ),
        )

        val copyPass = blocks.size
        val chapterTitlePass = blocks.size
        val chapterPagePass = blocks.size
        val singleResourcePass = blocks.size
        assertEquals(
            copyPass + chapterTitlePass + chapterPagePass + singleResourcePass,
            blocks.readCount,
            "mapping a page must read its blocks exactly four times (copy, two chapter scans, one " +
                "resource pass), but it read ${blocks.readCount} for ${blocks.size} blocks",
        )
        val oldFourResourcePassReads = copyPass + chapterTitlePass + chapterPagePass + 4 * blocks.size
        assertTrue(
            blocks.readCount < oldFourResourcePassReads,
            "the old four-resource-pass mapper would read blocks $oldFourResourcePassReads times; " +
                "the current one must fold them into a single pass",
        )
    }
}

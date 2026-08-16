package com.tedd.teddreader.core.data.pagination

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextPageLayoutEngineTest {
    private val engine = TextPageLayoutEngine()

    @Test
    fun pageBreakerBlockShiftAlsoShiftsInlineSpanRangesAfterCover() {
        val document = ReaderDocument(
            id = DocumentId("epub-span-shift"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                ReaderSection(1, text = "plain bold text", range = TextRange(2, 17), title = "Body"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg"),
                ReaderBlock(
                    kind = ReaderBlockKind.PARAGRAPH,
                    range = TextRange(2, 17),
                    spans = listOf(
                        com.tedd.teddreader.core.common.model.ReaderSpan(
                            range = TextRange(8, 12),
                            style = com.tedd.teddreader.core.common.model.ReaderInlineStyle.BOLD,
                        ),
                    ),
                ),
            ),
        )
        var measuredBlocks: List<ReaderBlock> = emptyList()
        val breaker = ReaderPageBreaker { _, blocks ->
            measuredBlocks = blocks
            intArrayOf(0)
        }

        engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = breaker,
        )

        assertEquals(TextRange(0, 15), measuredBlocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.range)
        assertEquals(
            TextRange(6, 10),
            measuredBlocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.spans.single().range,
        )
    }

    @Test
    fun estimatedPaginationReservesRoomForATallInlineImage() {
        // A document past the measurement cap falls back to estimated pagination. A tall image there
        // used to count as the single newline it carries, so a whole page of text was packed around it
        // and the image was clipped by the pane it overflowed.
        val paragraph = "가".repeat(400)
        val text = "$paragraph\n \n$paragraph"
        val imageOffset = paragraph.length + 1
        val document = ReaderDocument(
            id = DocumentId("epub-tall-image"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(ReaderSection(0, text = text, range = TextRange(0, text.length.toLong()))),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, paragraph.length.toLong())),
                ReaderBlock(
                    kind = ReaderBlockKind.IMAGE,
                    range = TextRange(imageOffset.toLong(), imageOffset + 1L),
                    imageHref = "Images/plate.jpg",
                    // Portrait plate: half as wide as it is tall, so it cannot share a page with text.
                    imageAspectRatio = 0.5f,
                ),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1.5f),
            viewportSize = ViewportSize(widthPx = 400, heightPx = 600),
            pageBreaker = null,
        )

        val imagePage = pages.single { page ->
            val range = page.textRange ?: return@single false
            imageOffset >= range.start && imageOffset < range.end
        }
        val imagePageLength = (imagePage.textRange!!.end - imagePage.textRange!!.start).toInt()
        val textOnlyPageLength = pages
            .filter { it !== imagePage }
            .maxOf { (it.textRange!!.end - it.textRange!!.start).toInt() }

        // The image claims real height, so its page carries far less text than a text-only page does.
        assertTrue(
            imagePageLength < textOnlyPageLength,
            "image page held $imagePageLength chars, text page held $textOnlyPageLength",
        )
    }

    @Test
    fun coverSectionGetsItsOwnFirstPageWithoutPageBreaker() {
        val document = ReaderDocument(
            id = DocumentId("epub-cover"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                ReaderSection(1, text = "Body text", range = TextRange(2, 11), title = "Body"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg"),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(2, 11)),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
        )

        assertEquals(ReaderBlockKind.COVER_IMAGE, pages.first().blocks.single().kind)
        assertEquals(ReaderLocation.EpubOffset(0, 0), pages.first().location)
        assertTrue(pages[1].text.startsWith("Body"))
    }

    @Test
    fun coverSectionGetsItsOwnFirstPageWithPageBreaker() {
        val document = ReaderDocument(
            id = DocumentId("epub-cover-breaker"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                ReaderSection(1, text = "abcdef", range = TextRange(2, 8), title = "Body"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg"),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(2, 8)),
            ),
            navigation = null,
        )
        val breaker = ReaderPageBreaker { measured, _ ->
            intArrayOf(0, 3)
        }

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = breaker,
        )

        assertEquals(listOf(" ", "abc", "def"), pages.map { it.text })
        assertEquals(ReaderLocation.EpubOffset(1, 0), pages[1].location)
    }

    @Test
    fun paginatesTextByViewportAndStyle() {
        val document = ReaderDocument(
            id = DocumentId("txt-1"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = "a".repeat(200), range = TextRange(0, 200)),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
        )

        assertTrue(pages.size > 1)
        assertEquals(0, pages.first().pageIndex.current)
        assertEquals(ReaderLocation.TextOffset(0), pages.first().location)
    }

    @Test
    fun paginatedPagesKeepTextContinuous() {
        val text = "abcdefghijklmnopqrstuvwxyz".repeat(20)
        val document = ReaderDocument(
            id = DocumentId("txt-continuous"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 18f),
            viewportSize = ViewportSize(widthPx = 80, heightPx = 100),
        )

        pages.zipWithNext().forEach { (current, next) ->
            assertEquals(current.textRange?.end, next.textRange?.start)
        }
        assertEquals(text, pages.joinToString(separator = "") { page -> page.text })
    }
    @Test
    fun usesConservativePageSizeForWideGlyphs() {
        val text = "가".repeat(100)
        val document = ReaderDocument(
            id = DocumentId("txt-wide"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
        )

        assertTrue(pages.first().text.length <= 25)
    }

    @Test
    fun narrowGlyphsUseTheProportionalAdvanceInsteadOfHalfAnEm() {
        val english = "a".repeat(400)
        val korean = "가".repeat(400)
        val style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f)
        val viewportSize = ViewportSize(widthPx = 480, heightPx = 100)

        fun paginate(text: String) = engine.paginate(
            document = ReaderDocument(
                id = DocumentId(text.first().toString()),
                format = DocumentFormat.TXT,
                title = "Book",
                sections = listOf(
                    ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
                ),
            ),
            style = style,
            viewportSize = viewportSize,
        )

        val englishPages = paginate(english)
        val koreanPages = paginate(korean)

        // 24 em per line over 5 lines: a wide glyph takes one em, a narrow glyph 0.45 em. The old
        // half-em budget would have stopped at 240 narrow glyphs.
        assertEquals(265, englishPages.first().text.length)
        assertEquals(120, koreanPages.first().text.length)
        assertEquals(english, englishPages.joinToString(separator = "") { page -> page.text })
        assertEquals(korean, koreanPages.joinToString(separator = "") { page -> page.text })
    }

    @Test
    fun estimatedLinesWrapAtSpacesLikeTheRendererDoes() {
        // "aaaa bbbb cccc ..." at 10 narrow glyphs per line: a renderer breaks after "aaaa bbbb",
        // never mid-word, so the estimate has to leave the split word for the next line.
        val text = List(20) { index -> ('a' + index % 26).toString().repeat(4) }.joinToString(" ")
        val document = ReaderDocument(
            id = DocumentId("txt-wrap"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 90, heightPx = 40),
        )

        assertEquals("aaaa bbbb ", pages.first().text.take(10))
        assertTrue(pages.all { page -> page.text.isEmpty() || !page.text.startsWith(" ") })
        assertEquals(text, pages.joinToString(separator = "") { page -> page.text })
    }

    @Test
    fun measuredPageBreaksAreUsedVerbatim() {
        val text = "abcdefghij".repeat(60)
        val document = ReaderDocument(
            id = DocumentId("txt-measured"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )
        // Stands in for the reader's own text layout: it reports a page break every 150 characters.
        val renderedPageLength = 150
        val pageBreaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + renderedPageLength - 1) / renderedPageLength) { page ->
                page * renderedPageLength
            }
        }

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = pageBreaker,
        )

        assertTrue(pages.dropLast(1).all { it.text.length == renderedPageLength })
        assertEquals(text, pages.joinToString(separator = "") { page -> page.text })
    }

    @Test
    fun measuredPagesIgnoreTheEstimatedLineCountAcrossFontSizes() {
        val text = "abcdefghij".repeat(60)
        val document = ReaderDocument(
            id = DocumentId("txt-measured-ignores-style"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )
        // A real measurement's page starts do not have to line up with any arithmetic line count;
        // this one grows farther apart than a real layout would, to prove the estimate is ignored.
        val renderedPageLength = 137
        val pageBreaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + renderedPageLength - 1) / renderedPageLength) { page ->
                page * renderedPageLength
            }
        }
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)

        fun paginate(style: ReaderStyle) = engine.paginate(
            document = document,
            style = style,
            viewportSize = viewportSize,
            pageBreaker = pageBreaker,
        )

        val smallFontPages = paginate(ReaderStyle(fontSizeSp = 8f, lineHeightMultiplier = 1f))
        val largeFontPages = paginate(ReaderStyle(fontSizeSp = 40f, lineHeightMultiplier = 3f))

        assertEquals(
            smallFontPages.map { it.textRange },
            largeFontPages.map { it.textRange },
        )
    }

    @Test
    fun oversizedContentSkipsPageBreakerAndFallsBackToEstimatedRanges() {
        val text = "a".repeat(200_001)
        val document = ReaderDocument(
            id = DocumentId("txt-oversized-measured"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )
        var breakerCalled = false
        val pageBreaker = ReaderPageBreaker { _, _ ->
            breakerCalled = true
            intArrayOf(0)
        }

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 2_000, heightPx = 2_000),
            pageBreaker = pageBreaker,
        )

        assertFalse(breakerCalled)
        assertTrue(pages.isNotEmpty())
        assertEquals(text, pages.joinToString(separator = "") { page -> page.text })
    }

    @Test
    fun explicitLineBreaksDoNotOverflowPageLineCapacity() {
        val text = (1..20).joinToString(separator = "\n") { "x" }
        val document = ReaderDocument(
            id = DocumentId("txt-lines"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
        )

        assertTrue(pages.first().text.lines().count { line -> line.isNotEmpty() } <= 5)
        assertEquals(text, pages.joinToString(separator = "") { page -> page.text })
    }

    @Test
    fun everyChapterStartsItsOwnPageSoItsHeadingSitsAtTheTop() {
        // One EPUB spine item is a document of its own, and no reading system runs two of them
        // together on a screen. Paginating the book as one long string put a chapter's title halfway
        // down the previous chapter's last page, which is exactly what the table of contents then
        // jumped to.
        val first = "먼저 읽는 장의 본문"
        val second = "2화 기회\n뒤에 오는 장의 본문"
        val document = ReaderDocument(
            id = DocumentId("epub-chapter-breaks"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = first, range = TextRange(0, first.length.toLong()), title = "1화"),
                ReaderSection(
                    1,
                    text = second,
                    range = TextRange(first.length + 1L, first.length + 1L + second.length),
                    title = "2화 기회",
                ),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, first.length.toLong())),
                ReaderBlock(
                    kind = ReaderBlockKind.HEADING,
                    level = 1,
                    range = TextRange(first.length + 1L, first.length + 1L + 5),
                ),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 4_000, heightPx = 4_000),
        )

        // The whole book fits one screen by measurement, yet the chapters never share a page.
        assertEquals(listOf(first, second), pages.map { it.text })
        val chapterPage = pages[1]
        assertEquals(ReaderLocation.EpubOffset(1, 0), chapterPage.location)
        assertTrue(chapterPage.text.startsWith("2화 기회"), "chapter page began with '${chapterPage.text.take(12)}'")
        assertEquals(ReaderBlockKind.HEADING, chapterPage.blocks.first().kind)
    }

    @Test
    fun pageWindowsKeepOnlyIntersectingBlocks() {
        val text = "abcdefghij"
        val blocks = listOf(
            ReaderBlock(
                kind = ReaderBlockKind.HEADING,
                range = TextRange(0, 4),
            ),
            ReaderBlock(
                kind = ReaderBlockKind.QUOTE,
                range = TextRange(4, 8),
            ),
            ReaderBlock(
                kind = ReaderBlockKind.SEPARATOR,
                range = TextRange(8, 9),
            ),
        )
        val document = ReaderDocument(
            id = DocumentId("epub-blocks"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(ReaderSection(0, text = text, range = TextRange(0, text.length.toLong()))),
            blocks = blocks,
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = ReaderPageBreaker { _, _ -> intArrayOf(0, 5) },
        )

        assertEquals(listOf(blocks[0], blocks[1]), pages[0].blocks)
        assertEquals(listOf(blocks[1], blocks[2]), pages[1].blocks)
    }

}

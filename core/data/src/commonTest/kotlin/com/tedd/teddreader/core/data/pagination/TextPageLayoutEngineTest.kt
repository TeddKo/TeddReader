package com.tedd.teddreader.core.data.pagination

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderSpan
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.blocksIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [TextPageLayoutEngine]'s pagination contract: a page never spans two sections, a cover image
 * gets a page of its own, an estimate reserves real room for wide glyphs, wrapped words and inline
 * images the same way the renderer does, a real measurement is used verbatim over the estimate, and
 * [TextPageLayoutEngine.reconstruct] rebuilds from stored page starts the exact page list
 * [TextPageLayoutEngine.paginate] produced — whether the caller's block lookup answers with absolute
 * or section-relative ranges. Several of these tests exist because one of those guarantees broke in
 * production: a clipped inline image, a chapter heading stranded mid-page, or blocks corrupted by a
 * double rebase once storage started handing sections' blocks over section-relative.
 */
class TextPageLayoutEngineTest {
    /** The pagination engine under test. */
    private val engine = TextPageLayoutEngine()

    /**
     * [TextPageLayoutEngine.defaultSectionBlocks] rebases a section's blocks to that section's own start
     * before handing them to the [ReaderPageBreaker] — this pins that the rebase reaches into a block's
     * own [ReaderSpan]s too, not just the block's outer range, once the section is not section 0 (a cover
     * image occupies section 0 here, so the body section's absolute start is 2, not 0).
     */
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

    /**
     * When pagination has no real measurement to use — here because no [ReaderPageBreaker] was
     * supplied, the same estimate a section past [TextPageLayoutEngine]'s measurement cap falls back
     * to — a tall inline image has to reserve real vertical room. An image used to count as only the
     * single newline character it carries in the text, so the estimate packed a whole page of text
     * around it and the renderer clipped the image by the pane it overflowed; this pins that the
     * image's own page now holds far less text than a text-only page does, because the image claims
     * real height on its page rather than the single line a newline would. The fixture's image is a
     * portrait plate, half as wide as it is tall (`imageAspectRatio = 0.5f`), deliberately too tall to
     * share a page with text.
     */
    @Test
    fun estimatedPaginationReservesRoomForATallInlineImage() {
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

        assertTrue(
            imagePageLength < textOnlyPageLength,
            "image page held $imagePageLength chars, text page held $textOnlyPageLength",
        )
    }

    /**
     * A cover image section becomes page 0 on its own, at [ReaderLocation.EpubOffset] `(0, 0)`, even when
     * pagination falls back to the estimate because no [ReaderPageBreaker] was supplied.
     */
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

    /**
     * The same cover-gets-its-own-page split as
     * [coverSectionGetsItsOwnFirstPageWithoutPageBreaker], now with a real [ReaderPageBreaker]: the
     * cover still becomes page 0 by itself, and the first measured content page starts the body section
     * at its own relative offset 0 ([ReaderLocation.EpubOffset] `(1, 0)`), not the document's absolute
     * offset.
     */
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

    /**
     * Baseline: a plain TXT document too long for one page splits into more than one, the first page is
     * numbered 0, and its location is [ReaderLocation.TextOffset] `0`.
     */
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

    /**
     * Adjoining pages must not gap or overlap: each page's [PageWindow.textRange] ends exactly where
     * the next one starts, and concatenating every page's text in order reproduces the section's
     * original text verbatim.
     */
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
    /**
     * A page of wide (CJK) glyphs holds far fewer characters than the same viewport would hold of
     * narrow Latin ones once line height is 1x: the estimate must charge a full-width glyph its whole
     * line-width budget instead of the fractional advance a Latin letter gets, so this bounds the
     * wide-glyph page at 25 characters for a 100x100 viewport at 20sp.
     */
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

    /**
     * A narrow glyph's estimated advance is a measured proportional fraction of an em (~0.45em), not
     * half an em. At 480px/20sp/line-height 1 the line holds 24 em: a wide glyph takes a whole em and a
     * narrow one 0.45em, so the first page holds 265 narrow-glyph (English) characters and 120
     * wide-glyph (Korean) ones over 5 lines — the old half-em budget would have stopped English at only
     * 240 characters.
     */
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

        assertEquals(265, englishPages.first().text.length)
        assertEquals(120, koreanPages.first().text.length)
        assertEquals(english, englishPages.joinToString(separator = "") { page -> page.text })
        assertEquals(korean, koreanPages.joinToString(separator = "") { page -> page.text })
    }

    /**
     * The estimate wraps at spaces the same way the renderer does: at 10 narrow-glyph units per line,
     * "aaaa bbbb cccc ..." breaks the line after "aaaa bbbb" rather than mid-word, so the estimate has
     * to hold back the word that would have split and start the next line with it instead — and no page
     * may start with the leading space a wrap left behind.
     */
    @Test
    fun estimatedLinesWrapAtSpacesLikeTheRendererDoes() {
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

    /**
     * A real [ReaderPageBreaker] is used exactly as it reports its breaks — modeled here with a fake
     * standing in for the reader's own text layout that reports a page break every 150 characters — so
     * every page but the last comes out exactly that long, and joining every page back together
     * reproduces the original text untouched.
     */
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

    /**
     * A real measurement's page starts do not have to line up with any arithmetic line count — the
     * fake [ReaderPageBreaker] here reports breaks every 137 characters, deliberately farther apart than
     * any real layout would produce, specifically to prove the estimate plays no part once a
     * measurement exists: pagination gives the exact same page ranges whether it is asked for at an
     * 8sp/1x style or a 40sp/3x one.
     */
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

    /**
     * A section longer than [TextPageLayoutEngine]'s measurement cap (200,000 characters) never reaches
     * the supplied [ReaderPageBreaker] at all — pagination falls straight back to the estimate, which
     * still covers the whole text without dropping any of it.
     */
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

        val starts = engine.pageStartsForSection(
            section = document.sections.single(),
            sectionBlocks = emptyList(),
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 2_000, heightPx = 2_000),
            pageBreaker = pageBreaker,
        )
        assertFalse(starts.isMeasured)
        assertTrue(starts.offsets.isNotEmpty())
    }

    /**
     * An explicit `\n` counts as a line the same way a wrapped line does: a page never holds more
     * non-empty lines than its estimated line capacity, however many of them come from real newlines
     * in the source text rather than from wrapping.
     */
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

    /**
     * A chapter never shares a page with the one before it, however generous the viewport — the rule
     * [TextPageLayoutEngine] rests every entry point on: one EPUB spine item is a document of its own,
     * and no reading system runs two of them together on a screen. Paginating the whole book as one
     * long string used to put a chapter's title halfway down the previous chapter's last page, which is
     * exactly where the table of contents then jumped a reader to; here the viewport is deliberately
     * large enough to fit the whole book on one screen by measurement alone, and the chapters must
     * still land on separate pages.
     */
    @Test
    fun everyChapterStartsItsOwnPageSoItsHeadingSitsAtTheTop() {
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

        assertEquals(listOf(first, second), pages.map { it.text })
        val chapterPage = pages[1]
        assertEquals(ReaderLocation.EpubOffset(1, 0), chapterPage.location)
        assertTrue(chapterPage.text.startsWith("2화 기회"), "chapter page began with '${chapterPage.text.take(12)}'")
        assertEquals(ReaderBlockKind.HEADING, chapterPage.blocks.first().kind)
    }

    /**
     * A page's [PageWindow.blocks] holds exactly the blocks whose range intersects that page's
     * [PageWindow.textRange] — including a block that straddles the boundary between two pages, which
     * therefore appears in both pages' lists rather than being dropped from either.
     */
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

    /**
     * [TextPageLayoutEngine.reconstruct] rebuilds, from nothing but the absolute page starts a real
     * measurement produced, the exact same page list [TextPageLayoutEngine.paginate] gave it — for a
     * book shaped like a stored layout actually has to survive: a cover section that is a single image,
     * followed by two ordinary chapters. The fake [ReaderPageBreaker] used to measure (a page break
     * every 3 characters of whichever section is being measured, standing in for the reader's own text
     * layout) only ever produces the content pages' starts; the cover page is never stored at all — it
     * is always rebuilt the same way, with no measurement involved — yet [TextPageLayoutEngine.reconstruct]
     * still has to reproduce it identically.
     */
    @Test
    fun reconstructFromStoredStartsMatchesMeasuredPaginateExactly() {
        val document = ReaderDocument(
            id = DocumentId("epub-reconstruct"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                ReaderSection(1, text = "abcdef", range = TextRange(2, 8), title = "Chapter 1"),
                ReaderSection(2, text = "ghijklmno", range = TextRange(9, 18), title = "Chapter 2"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg"),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(2, 8)),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(9, 18)),
            ),
        )
        val breaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + 2) / 3) { page -> page * 3 }
        }

        val measuredPages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = breaker,
        )
        val contentPageStarts = measuredPages.drop(1).map { it.textRange!!.start }.toLongArray()

        val reconstructedPages = engine.reconstruct(document, contentPageStarts)

        assertEquals(measuredPages, reconstructedPages)
    }

    /**
     * [TextPageLayoutEngine.reconstruct] decodes a section's blocks only once a page belonging to it is
     * actually read. Three ordinary chapters, no cover, one page per chapter is enough sections to
     * prove that reading the third page (which belongs to "Chapter 3", `contentPageStarts[2]`) must not
     * also decode "Chapter 2" in between — a bug that decoded every section eagerly would be
     * indistinguishable from correct behaviour on a page that only ever needs its own section.
     * `sectionBlocks` is passed by name because [TextPageLayoutEngine.reconstruct] takes two trailing
     * functional parameters; an unnamed lambda would bind to `isSectionReady` instead of the block
     * lookup this test is watching. Cover detection always checks section 0 up front — nothing else has
     * been asked for yet at that point — so constructing the list alone already decodes `{0}` before
     * any page is read.
     */
    @Test
    fun reconstructOnlyDecodesSectionsItsRequestedPagesTouch() {
        val document = ReaderDocument(
            id = DocumentId("epub-lazy-sections"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = "aaa", range = TextRange(0, 3), title = "Chapter 1"),
                ReaderSection(1, text = "bbb", range = TextRange(4, 7), title = "Chapter 2"),
                ReaderSection(2, text = "ccc", range = TextRange(8, 11), title = "Chapter 3"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 3)),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(4, 7)),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(8, 11)),
            ),
        )
        val contentPageStarts = longArrayOf(0L, 4L, 8L)
        val decodedSections = mutableSetOf<Int>()
        val windows = engine.reconstruct(
            document = document,
            contentPageStarts = contentPageStarts,
            sectionBlocks = { section ->
                decodedSections += section.index
                document.blocks.blocksIn(section.range.start, section.range.end)
            },
        )

        assertEquals(setOf(0), decodedSections, "constructing the list must not decode beyond cover detection")

        windows[2]
        assertEquals(setOf(0, 2), decodedSections, "chapter 2 was never asked for and must stay undecoded")
    }

    /**
     * Section-relative block storage (see [DocumentRepositoryImpl.persistParsedDocument]) shifts each
     * section's blocks to read relative to that section's own start before they are ever handed to
     * [TextPageLayoutEngine.paginate]/[TextPageLayoutEngine.reconstruct] — this and the following three
     * tests are exactly the tests that had to fail against the pre-change code: `sectionPageRanges` used
     * to rebase its `sectionBlocks` argument itself, on the assumption it was always absolute, so a
     * block that arrived already section-relative got rebased a second time and corrupted.
     *
     * [TextPageLayoutEngine.paginate] must always hand back blocks in absolute document offsets
     * regardless of which shape its `sectionBlocks` lookup answers with. Section 1's absolute start
     * here is 6, not 0: a cover section always starts at 0, the one place a forgotten un-rebase would
     * still look correct by accident, so this deliberately exercises a section that would expose the
     * bug. A page's blocks have to stay absolute because `ReaderSemanticText` locates a block within
     * `page.text` by subtracting the page's own absolute `textRange.start` from the block's
     * `range.start`.
     */
    @Test
    fun paginateReturnsAbsoluteBlockRangesEvenWhenSectionBlocksArriveSectionRelative() {
        val document = ReaderDocument(
            id = DocumentId("relative-input-absolute-output"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = "intro", range = TextRange(0, 5), title = "Intro"),
                ReaderSection(1, text = "plain bold text", range = TextRange(6, 21), title = "Body"),
            ),
        )
        val sectionRelativeBlocks = mapOf(
            0 to listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 5))),
            1 to listOf(
                ReaderBlock(
                    kind = ReaderBlockKind.PARAGRAPH,
                    range = TextRange(0, 15),
                    spans = listOf(ReaderSpan(range = TextRange(6, 10), style = ReaderInlineStyle.BOLD)),
                ),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = ReaderPageBreaker { _, _ -> intArrayOf(0) },
        ) { section -> sectionRelativeBlocks.getValue(section.index) }

        val bodyPage = pages.single { it.text == "plain bold text" }
        val bodyBlock = bodyPage.blocks.single()
        assertEquals(TextRange(6, 21), bodyBlock.range, "a page's blocks must stay absolute even when fed section-relative input")
        assertEquals(TextRange(12, 16), bodyBlock.spans.single().range, "a span has to shift with its block, not stay behind")
    }

    /**
     * [TextPageLayoutEngine.paginate]'s default path — no explicit `sectionBlocks` lambda, so it groups
     * [ReaderDocument.blocks] itself, which a fresh parse still hands over as absolute ranges — and the
     * cache-backed path a stored book takes, which hands over blocks already section-relative, must
     * produce exactly the same pages either way. The cover section is included deliberately: it always
     * starts at absolute offset 0, which is exactly the one case where a forgotten rebase would still
     * look correct by accident, so proving equality there is the point, not an afterthought.
     */
    @Test
    fun paginateProducesIdenticalPageWindowsWhetherSectionBlocksAreSectionRelativeOrTheDefaultGroupingPath() {
        val document = ReaderDocument(
            id = DocumentId("relative-vs-default"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                ReaderSection(1, text = "chapter one text", range = TextRange(2, 18), title = "Chapter 1"),
                ReaderSection(2, text = "chapter two text", range = TextRange(19, 35), title = "Chapter 2"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg"),
                ReaderBlock(kind = ReaderBlockKind.HEADING, level = 1, range = TextRange(2, 11)),
                ReaderBlock(kind = ReaderBlockKind.HEADING, level = 1, range = TextRange(19, 28)),
            ),
        )
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 400, heightPx = 400)

        val defaultPages = engine.paginate(document = document, style = style, viewportSize = viewportSize)

        val relativeBySection = mapOf(
            0 to listOf(ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg")),
            1 to listOf(ReaderBlock(kind = ReaderBlockKind.HEADING, level = 1, range = TextRange(0, 9))),
            2 to listOf(ReaderBlock(kind = ReaderBlockKind.HEADING, level = 1, range = TextRange(0, 9))),
        )
        val relativePages = engine.paginate(
            document = document,
            style = style,
            viewportSize = viewportSize,
        ) { section -> relativeBySection.getValue(section.index) }

        assertEquals(defaultPages, relativePages)
    }

    /**
     * The same guarantee as
     * [paginateProducesIdenticalPageWindowsWhetherSectionBlocksAreSectionRelativeOrTheDefaultGroupingPath],
     * for [TextPageLayoutEngine.reconstruct]: the default grouping path (absolute blocks) and a lookup
     * that already answers section-relative must reconstruct exactly the same pages, cover section
     * included.
     */
    @Test
    fun reconstructProducesIdenticalPageWindowsWhetherSectionBlocksAreSectionRelativeOrTheDefaultGroupingPath() {
        val document = ReaderDocument(
            id = DocumentId("reconstruct-relative-vs-default"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                ReaderSection(1, text = "abcdef", range = TextRange(2, 8), title = "Chapter 1"),
                ReaderSection(2, text = "ghijklmno", range = TextRange(9, 18), title = "Chapter 2"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg"),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(2, 8)),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(9, 18)),
            ),
        )
        val contentPageStarts = longArrayOf(2L, 5L, 9L, 14L)

        val defaultReconstructed = engine.reconstruct(document, contentPageStarts)

        val relativeBySection = mapOf(
            0 to listOf(ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg")),
            1 to listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 6))),
            2 to listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 9))),
        )
        val relativeReconstructed = engine.reconstruct(
            document = document,
            contentPageStarts = contentPageStarts,
            sectionBlocks = { section -> relativeBySection.getValue(section.index) },
        )

        assertEquals(defaultReconstructed, relativeReconstructed)
    }

    /**
     * Restoring a document from stored page-start boundaries must not change either the total page
     * count or which page any character offset lands on: for every offset across the document,
     * rebuilding pages from [TextPageLayoutEngine.reconstruct] must land it on the same page index a
     * linear scan of the freshly measured pages would, checked here via [pageOfOffset] — the same
     * binary search `ReaderViewModel.pageOfOffset` runs against a book's page windows.
     */
    @Test
    fun reconstructTotalPageCountAndOffsetLookupMatchMeasuredPagination() {
        val document = ReaderDocument(
            id = DocumentId("epub-reconstruct-lookup"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                ReaderSection(1, text = "abcdef", range = TextRange(2, 8), title = "Chapter 1"),
                ReaderSection(2, text = "ghijklmno", range = TextRange(9, 18), title = "Chapter 2"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg"),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(2, 8)),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(9, 18)),
            ),
        )
        val breaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + 2) / 3) { page -> page * 3 }
        }
        val measuredPages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = breaker,
        )
        val contentPageStarts = measuredPages.drop(1).map { it.textRange!!.start }.toLongArray()

        val windows = engine.reconstruct(document, contentPageStarts)

        assertEquals(measuredPages.size, windows.size, "restoring from stored boundaries must not change the page count")
        for (offset in 0L until 18L) {
            val expected = measuredPages.indexOfFirst { page ->
                val range = page.textRange!!
                offset >= range.start && offset < range.end
            }.takeIf { it >= 0 }
            assertEquals(expected, windows.pageOfOffset(offset), "offset $offset landed on a different page after reconstruct")
        }
    }

}

/**
 * The index of the page whose [PageWindow.textRange] contains [offset], found by binary search since
 * the receiver's ranges are ascending and non-overlapping — the same lookup `ReaderViewModel` runs
 * against a book's page windows to answer "which page is this offset on."
 *
 * @receiver the page windows to search, in ascending, non-overlapping [PageWindow.textRange] order.
 * @param offset an absolute document offset to locate.
 * @return the index of the page containing [offset], or null when no page's range covers it (a page
 * with no [PageWindow.textRange] at all, or an offset outside every page).
 */
private fun List<PageWindow>.pageOfOffset(offset: Long): Int? {
    var low = 0
    var high = lastIndex
    while (low <= high) {
        val mid = (low + high) / 2
        val range = this[mid].textRange ?: return null
        when {
            offset < range.start -> high = mid - 1
            offset >= range.end -> low = mid + 1
            else -> return mid
        }
    }
    return null
}

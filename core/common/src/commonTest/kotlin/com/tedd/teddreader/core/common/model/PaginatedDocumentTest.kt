package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins every domain query [PaginatedDocument] answers, reproducing the exact semantics
 * `ReaderViewModel`'s own `pageOfOffset`, `absoluteOffset`, `sectionContaining`,
 * `sectionIndexContaining`, and the chapter-title/`isSectionTail` derivation inside `pageUi` had before
 * this type existed — including their edge cases, such as a binary search that gives up the moment it
 * lands on a page whose blocks are not decoded yet, and a chapter title that is inherited from the last
 * titled section rather than the section a page's start literally falls in.
 *
 * It also pins the B4 decision behind this type's declaration: [PaginatedDocument] is a plain `class`,
 * not a `data class`, precisely so nothing here ever walks its lazily built [PaginatedDocument.pageWindows]
 * list — [equalContentInstancesAreNotEqual] fails the moment someone "helpfully" adds `data` back.
 */
class PaginatedDocumentTest {

    /**
     * Builds a page window carrying only what these queries actually read, so each case states its own
     * premise instead of hiding it in one fixture shared by twenty tests.
     *
     * @param range the page's text range; null stands for a page whose blocks are not decoded yet, which
     * is the state the offset search has to stop on rather than skip past.
     * @param blocks the page's blocks; a cover block is what makes [PaginatedDocument.chapterTitleAt]
     * answer null, and an image block is what [PaginatedDocument.imageHrefsIn] collects.
     * @return a window whose page index, location and text are placeholders no query here depends on.
     */
    private fun page(
        range: TextRange? = null,
        blocks: List<ReaderBlock> = emptyList(),
    ): PageWindow = PageWindow(
        pageIndex = PageIndex(current = 0, total = 1),
        location = ReaderLocation.TextOffset(0L),
        text = "",
        textRange = range,
        blocks = blocks,
    )

    /**
     * Builds a section spanning `start until end`, which is the only part of a section these queries read.
     *
     * @param index the section's own index, which the section lookups answer with.
     * @param start the section's first absolute offset.
     * @param end the section's exclusive end offset; a page ending exactly here is a section tail.
     * @param title the section's title; null is a real state in EPUBs, and the one a chapter title has to
     * be inherited for.
     * @return a section with empty text, since no query here reads it.
     */
    private fun section(
        index: Int,
        start: Long,
        end: Long,
        title: String? = null,
    ): ReaderSection = ReaderSection(index = index, text = "", range = TextRange(start, end), title = title)

    /** A document with no pages has no page for any offset, rather than answering page zero. */
    @Test
    fun pageOfOffsetOnAnEmptyPageListIsNull() {
        val document = PaginatedDocument()

        assertNull(document.pageOf(0L))
    }

    /** A page owns its range from its first offset up to, but not including, the next page's first. */
    @Test
    fun pageOfOffsetFindsAPageAtItsFirstAndLastOffset() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = TextRange(10, 20)),
                page(range = TextRange(20, 30)),
            ),
        )

        assertEquals(0, document.pageOf(0L))
        assertEquals(0, document.pageOf(9L))
        assertEquals(2, document.pageOf(20L))
        assertEquals(2, document.pageOf(29L))
    }

    /**
     * An offset past everything measured so far has no page yet — a different answer from the last page,
     * which is what keeps a resume from landing at the end of a partially measured book.
     */
    @Test
    fun pageOfOffsetPastTheEndIsNull() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = TextRange(10, 20)),
            ),
        )

        assertNull(document.pageOf(20L))
    }

    /**
     * The search gives up on an undecoded page instead of stepping over it.
     *
     * Binary search on three pages visits index 1 first. Offset 25 truly belongs to page 2, so a search
     * treating a null range as "keep going" would find it — and would be answering from a page list it
     * cannot actually trust. Reproducing `ReaderViewModel.pageOfOffset` exactly, this edge case included,
     * is the whole reason this type took the old implementation over rather than replacing it.
     */
    @Test
    fun pageOfOffsetStopsAtAPageWithNoTextRangeInsteadOfSkippingIt() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = null),
                page(range = TextRange(20, 30)),
            ),
        )

        assertNull(document.pageOf(25L))
    }

    /** A plain text offset needs no section context: it is already absolute. */
    @Test
    fun pageOfLocationResolvesATextOffsetThroughAbsoluteOffsetOf() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = TextRange(10, 20)),
                page(range = TextRange(20, 30)),
            ),
        )

        assertEquals(1, document.pageOf(ReaderLocation.TextOffset(15L)))
    }

    /**
     * An EPUB offset is relative to its spine item, so it only means something once the section list says
     * where that item starts — which is why pages and sections travel together in this one type.
     */
    @Test
    fun pageOfLocationResolvesAnEpubOffsetAgainstItsSpineItemsSectionStart() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = TextRange(10, 20)),
                page(range = TextRange(20, 30)),
            ),
            sections = listOf(section(index = 0, start = 0, end = 30, title = "Spine 0")),
        )

        assertEquals(1, document.pageOf(ReaderLocation.EpubOffset(spineIndex = 0, offset = 15L)))
    }

    /**
     * A PDF page number is not a text offset, so this query has no answer for it. The caller has to branch
     * on the location type — the reason `ReaderViewModel.moveToLocation` keeps its own explicit `PdfPage`
     * branch instead of routing every jump through here.
     */
    @Test
    fun pageOfLocationOnAPdfPageIsAlwaysNull() {
        val document = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(0, 10))),
            sections = listOf(section(index = 0, start = 0, end = 10)),
        )

        assertNull(document.pageOf(ReaderLocation.PdfPage(0)))
    }

    /** The stored position for a page is the window's own location, not one derived from its range. */
    @Test
    fun locationAtReturnsTheWindowsOwnLocationWhenThePageExists() {
        val location = ReaderLocation.TextOffset(42L)
        val document = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(0, 10)).copy(location = location)),
        )

        assertEquals(location, document.locationAt(0))
    }

    /** Asking about a page the measurement has not reached answers absence, not a fallback location. */
    @Test
    fun locationAtIsNullWhenThePageHasNoWindow() {
        val document = PaginatedDocument(pageWindows = listOf(page(range = TextRange(0, 10))))

        assertNull(document.locationAt(5))
    }

    /**
     * A section owns every offset from its own start until the next section's, so the lookup takes the last
     * section starting at or before the offset rather than the first one whose range contains it.
     */
    @Test
    fun sectionContainingFindsTheLastSectionStartingAtOrBeforeTheOffset() {
        val sectionA = section(index = 0, start = 10, end = 20, title = "Intro")
        val sectionB = section(index = 1, start = 20, end = 40, title = "Chapter 1")
        val document = PaginatedDocument(sections = listOf(sectionA, sectionB))

        assertEquals(sectionA, document.sectionContaining(15L))
        assertEquals(sectionB, document.sectionContaining(20L))
        assertEquals(sectionB, document.sectionContaining(35L))
    }

    /** Gaps still belong to the last section start at or before the offset, matching the old lookup. */
    @Test
    fun sectionContainingUsesTheLastSectionStartEvenAcrossAGap() {
        val intro = section(index = 0, start = 10, end = 20, title = "Intro")
        val chapter = section(index = 1, start = 30, end = 40, title = "Chapter 1")
        val document = PaginatedDocument(sections = listOf(intro, chapter))

        assertEquals(intro, document.sectionContaining(25L))
        assertEquals(0, document.sectionIndexContaining(25L))
    }

    /** Front matter can start after offset zero, and an offset before it belongs to no section at all. */
    @Test
    fun sectionContainingIsNullForAnOffsetBeforeTheFirstSection() {
        val document = PaginatedDocument(
            sections = listOf(section(index = 0, start = 10, end = 20, title = "Intro")),
        )

        assertNull(document.sectionContaining(5L))
        assertNull(document.sectionIndexContaining(5L))
    }

    /** The index form is the same lookup, so the two can never disagree about which section wins. */
    @Test
    fun sectionIndexContainingMatchesSectionContainingsIndex() {
        val document = PaginatedDocument(
            sections = listOf(
                section(index = 0, start = 10, end = 20, title = "Intro"),
                section(index = 1, start = 20, end = 40, title = "Chapter 1"),
            ),
        )

        assertEquals(1, document.sectionIndexContaining(35L))
    }

    /**
     * A page range is clamped to what has been measured, so asking for a fixed-size window around the
     * reading position cannot fail mid-import — which is what lets block warming ask by page range.
     */
    @Test
    fun sectionIndexesForIgnoresPagesPastTheEndOfTheKnownList() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = TextRange(10, 20)),
            ),
            sections = listOf(
                section(index = 0, start = 0, end = 10),
                section(index = 1, start = 10, end = 20),
            ),
        )

        assertEquals(setOf(0, 1), document.sectionIndexesFor(0..5))
    }

    @Test
    fun fontHrefsInCollectsBlockAndInlineFontReferencesOnce() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(
                    range = TextRange(0, 10),
                    blocks = listOf(
                        ReaderBlock(
                            kind = ReaderBlockKind.PARAGRAPH,
                            range = TextRange(0, 10),
                            style = ReaderBlockStyle(fontHref = "fonts/body.otf"),
                            spans = listOf(
                                ReaderSpan(
                                    range = TextRange(0, 4),
                                    styleDelta = ReaderSpanStyle(fontHref = "fonts/inline.otf"),
                                ),
                                ReaderSpan(
                                    range = TextRange(5, 9),
                                    styleDelta = ReaderSpanStyle(fontHref = "fonts/body.otf"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(setOf("fonts/body.otf", "fonts/inline.otf"), document.fontHrefsIn(0..0))
    }

    /** Nothing measured in the asked-for range means nothing to warm, not a request for an empty set. */
    @Test
    fun sectionIndexesForIsEmptyWhenTheWholeRangeIsPastTheEnd() {
        val document = PaginatedDocument(pageWindows = listOf(page(range = TextRange(0, 10))))

        assertEquals(emptySet(), document.sectionIndexesFor(5..10))
    }

    /**
     * A cover belongs to no chapter, so the reader's top bar stays empty on it instead of showing the
     * title of whichever section the cover's offsets happen to fall in.
     */
    @Test
    fun chapterTitleAtIsNullForACoverPage() {
        val coverBlock = ReaderBlock(
            kind = ReaderBlockKind.COVER_IMAGE,
            range = TextRange(0, 1),
            imageHref = "cover.jpg",
        )
        val document = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(0, 10), blocks = listOf(coverBlock))),
            sections = listOf(section(index = 0, start = 0, end = 100, title = "Book")),
        )

        assertNull(document.chapterTitleAt(0))
    }

    /**
     * An untitled section inherits the last title before it, which is what keeps a chapter's name in the
     * top bar for the whole chapter rather than only for its first spine item.
     *
     * The first assertion is the trap this pins: the section the page's start literally falls in has no
     * title of its own, so a naive `sectionContaining(start)?.title` answers null here.
     */
    @Test
    fun chapterTitleAtIsInheritedByAnUntitledSectionFromTheLastTitledSectionBeforeIt() {
        val preface = section(index = 0, start = 0, end = 50, title = "Preface")
        val untitledChapter = section(index = 1, start = 50, end = 100, title = null)
        val document = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(60, 70))),
            sections = listOf(preface, untitledChapter),
        )

        assertNull(document.sectionContaining(60L)?.title)
        assertEquals("Preface", document.chapterTitleAt(0))
    }
    /** Title inheritance walks backward from the positioned section until it finds a titled one. */
    @Test
    fun chapterTitleAtKeepsTheLastTitleAcrossUntitledSectionsAndGaps() {
        val preface = section(index = 0, start = 0, end = 50, title = "Preface")
        val untitledBridge = section(index = 1, start = 80, end = 100, title = null)
        val document = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(90, 95))),
            sections = listOf(preface, untitledBridge),
        )

        assertEquals("Preface", document.chapterTitleAt(0))
    }

    /** Inheritance never invents a title: a book whose sections are all untitled shows none. */
    @Test
    fun chapterTitleAtIsNullWhenNoSectionAtOrBeforeThePageHasEverCarriedATitle() {
        val document = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(0, 10))),
            sections = listOf(section(index = 0, start = 0, end = 10, title = null)),
        )

        assertNull(document.chapterTitleAt(0))
    }

    /**
     * Only the page whose end meets its section's end is a tail, which is how the reader tells a page that
     * ends a chapter from one merely sitting inside it.
     */
    @Test
    fun isSectionTailIsTrueExactlyWhenThePagesEndMatchesItsSectionsEnd() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = TextRange(10, 20)),
            ),
            sections = listOf(section(index = 0, start = 0, end = 20)),
        )

        assertFalse(document.isSectionTail(0))
        assertTrue(document.isSectionTail(1))
    }

    /** Without a measured range, or without a window at all, the answer is no rather than a guess. */
    @Test
    fun isSectionTailIsFalseWithoutATextRangeOrAWindow() {
        val document = PaginatedDocument(pageWindows = listOf(page(range = null)))

        assertFalse(document.isSectionTail(0))
        assertFalse(document.isSectionTail(5))
    }

    /**
     * Images are collected per page range and de-duplicated, so prefetching around the reading position
     * asks for each file once and never for a page the reader is nowhere near.
     */
    @Test
    fun imageHrefsInCollectsDistinctHrefsFromTheGivenPagesOnly() {
        val firstPageImage = ReaderBlock(kind = ReaderBlockKind.IMAGE, range = TextRange(0, 1), imageHref = "a.png")
        val textBlock = ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(1, 5))
        val secondPageImage = ReaderBlock(kind = ReaderBlockKind.IMAGE, range = TextRange(0, 1), imageHref = "b.png")
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10), blocks = listOf(firstPageImage, textBlock)),
                page(range = TextRange(10, 20), blocks = listOf(secondPageImage)),
            ),
        )

        assertEquals(setOf("a.png", "b.png"), document.imageHrefsIn(0..1))
        assertEquals(setOf("a.png"), document.imageHrefsIn(0..0))
        assertEquals(emptySet(), document.imageHrefsIn(5..10))
    }

    /**
     * Re-measuring pages leaves the section list alone, because a repagination changes where the page
     * boundaries fall but not how the book is divided.
     */
    @Test
    fun withPagesReplacesOnlyThePageList() {
        val originalSections = listOf(section(index = 0, start = 0, end = 10, title = "Only"))
        val original = PaginatedDocument(pageWindows = listOf(page(range = TextRange(0, 10))), sections = originalSections)
        val freshPages = listOf(page(range = TextRange(0, 20)))

        val updated = original.withPages(freshPages)

        assertEquals(freshPages, updated.pageWindows)
        assertEquals(originalSections, updated.sections)
    }

    /**
     * The mirror image: an import parsing further into the book replaces the sections while the pages
     * measured so far stay as they are. The two updates stay separate because they arrive separately.
     */
    @Test
    fun withSectionsReplacesOnlyTheSectionList() {
        val originalPages = listOf(page(range = TextRange(0, 10)))
        val original = PaginatedDocument(
            pageWindows = originalPages,
            sections = listOf(section(index = 0, start = 0, end = 10, title = "Old")),
        )
        val freshSections = listOf(section(index = 0, start = 0, end = 20, title = "New"))

        val updated = original.withSections(freshSections)

        assertEquals(originalPages, updated.pageWindows)
        assertEquals(freshSections, updated.sections)
    }

    /**
     * Content-equal instances are deliberately not equal, which is the guard on this type staying a plain
     * `class`.
     *
     * The first two assertions establish that the two really do hold content-equal lists; the last shows
     * that this still does not make the values equal, because [PaginatedDocument] has neither a generated
     * nor a hand-written `equals`. Adding `data` back would satisfy the first two and break the third —
     * and would make every `==` walk a page list that builds and caches pages as it is indexed, so the
     * comparison would be neither cheap nor free of side effects.
     */
    @Test
    fun equalContentInstancesAreNotEqual() {
        val first = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(0, 10))),
            sections = listOf(section(index = 0, start = 0, end = 10, title = "Same")),
        )
        val second = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(0, 10))),
            sections = listOf(section(index = 0, start = 0, end = 10, title = "Same")),
        )

        assertEquals(first.pageWindows, second.pageWindows)
        assertEquals(first.sections, second.sections)
        assertNotEquals(first, second)
    }
}

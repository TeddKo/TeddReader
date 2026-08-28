package com.tedd.teddreader.core.common.model

/**
 * Everything one pagination pass found out about a document — its page windows and the sections those
 * pages are laid out over — held together as the one value the reader runs its domain queries against.
 *
 * The two lists have to be kept in step: a page's chapter title and its [isSectionTail] flag both come
 * from matching [pageWindows] against [sections], and reading pages against a section list from a
 * different pagination pass puts a chapter title from one measurement onto a page from another. Naming
 * that pair as a value is all this type does — it does not enforce the pairing. [withPages] and
 * [withSections] exist separately and on purpose, because a reload publishes its fresh page list before
 * its fresh section list arrives (see the two-assignment ordering this mirrors in `ReaderViewModel.reloadPages`),
 * so the type has to allow the torn intermediate state its own primary caller relies on.
 *
 * @property pageWindows the pages this pagination pass produced. **This can be a lazily built list
 *   whose indexing side-effects a cache** (`DocumentRepositoryImpl`'s `RestoredPageWindows`: laying every
 *   section out up front measured 6.4s/13.0s on real devices for 204/528-section books). Never iterate,
 *   compare, or print this list — every query on this type touches only the O(log n) or constant-count
 *   indices it actually needs. This is exactly why this type does not carry a `data` modifier: a
 *   generated `equals`/`hashCode`/`toString` would walk the whole list, mutating the cache as it goes,
 *   which is both slow and impure for an `equals`.
 * @property sections the sections these pages are laid out over, ascending and non-overlapping.
 */
class PaginatedDocument(
    val pageWindows: List<PageWindow> = emptyList(),
    val sections: List<ReaderSection> = emptyList(),
) {
    /** How many pages this pagination pass has published so far. */
    val pageCount: Int get() = pageWindows.size

    /**
     * This document with a fresh page list, [sections] unchanged.
     *
     * @param pageWindows the new page list to hold.
     * @return a new instance carrying [pageWindows] and this instance's [sections].
     */
    fun withPages(pageWindows: List<PageWindow>): PaginatedDocument = PaginatedDocument(pageWindows, sections)

    /**
     * This document with a fresh section list, [pageWindows] unchanged.
     *
     * @param sections the new section list to hold.
     * @return a new instance carrying this instance's [pageWindows] and [sections].
     */
    fun withSections(sections: List<ReaderSection>): PaginatedDocument = PaginatedDocument(pageWindows, sections)

    /**
     * The index of the page whose [PageWindow.textRange] contains [offset].
     *
     * A page window is built — and its section's blocks decoded — only the first time something reads
     * it by index (see [pageWindows]'s own documentation), so scanning [pageWindows] in order would force
     * every page up to the match to build just to answer where one offset lands. Binary search over the
     * pages' own start offsets instead touches only the O(log n) pages the search actually visits, and
     * this is the one invariant this type exists to protect: never add a caller that walks [pageWindows]
     * end to end to answer this question.
     *
     * @param offset an absolute document offset.
     * @return the page containing [offset], or null when no page's range contains it (including when
     *   the page the search lands on has no [PageWindow.textRange] yet, which ends the search rather than
     *   continuing it).
     */
    fun pageOf(offset: Long): Int? {
        var low = 0
        var high = pageWindows.lastIndex
        while (low <= high) {
            val mid = (low + high) / 2
            val range = pageWindows[mid].textRange ?: return null
            when {
                offset < range.start -> high = mid - 1
                offset >= range.end -> low = mid + 1
                else -> return mid
            }
        }
        return null
    }

    /**
     * The index of the page showing [location], resolved through [absoluteOffsetOf].
     *
     * @param location a reading position. A [ReaderLocation.PdfPage] always resolves to null here, since
     *   [absoluteOffsetOf] has no absolute offset to give it — a caller reading a visual document keeps
     *   its own branch for that case rather than relying on this method.
     * @return the page showing [location], or null when it cannot be resolved against [sections] and
     *   [pageWindows].
     */
    fun pageOf(location: ReaderLocation): Int? =
        absoluteOffsetOf(location, sections)?.let { offset -> pageOf(offset) }

    /**
     * Where [page] starts, as a reading position that survives repagination.
     *
     * @param page a page index.
     * @return the page's own [PageWindow.location], or null when [page] has no window yet.
     */
    fun locationAt(page: Int): ReaderLocation? = pageWindows.getOrNull(page)?.location

    /**
     * The section whose absolute range contains [offset] — the last one starting at or before it, since
     * [sections] is ascending and non-overlapping.
     *
     * @param offset an absolute document offset.
     * @return the containing section, or null only when [sections] is empty or none starts at or before
     *   [offset].
     */
    fun sectionContaining(offset: Long): ReaderSection? =
        sectionPositionAtOrBefore(offset)?.let(sections::get)

    /**
     * The index of the section whose absolute range contains [offset], the same containment
     * [sectionContaining] resolves.
     *
     * @param offset an absolute document offset.
     * @return the containing section's index, or null only when [sectionContaining] returns null.
     */
    fun sectionIndexContaining(offset: Long): Int? = sectionContaining(offset)?.index

    /**
     * Every section index touched by a page in [pages] — one lookup per page that already has a window,
     * used to decide which sections' blocks a background warm should decode next.
     *
     * @param pages the pages to check; an index past [pageWindows]'s end is skipped rather than treated
     *   as an error, since a caller asking about a mount window can legitimately reach past the last
     *   known page.
     * @return the distinct section indices those pages start in.
     */
    fun sectionIndexesFor(pages: IntRange): Set<Int> =
        pages.mapNotNull { page -> pageWindows.getOrNull(page)?.textRange?.start }
            .mapNotNull(::sectionIndexContaining)
            .toSet()

    /**
     * The chapter title to show while [page] is on screen.
     *
     * A cover page never carries a title, and a section with no title of its own is not "untitled" — it
     * inherits the title of the last titled section at or before it, which is what keeps a chapter title
     * pinned across every page of a chapter instead of only its first. That inheritance is why this is
     * not simply `sectionContaining(start)?.title`: this filters to titled sections *before* taking the
     * one with the largest start, so an untitled section sitting between two titled ones still shows the
     * earlier title rather than null.
     *
     * @param page a page index.
     * @return the inherited chapter title, or null when [page] has no window, starts with a cover image,
     *   has no [PageWindow.textRange] yet, or no section at or before it has ever carried a title.
     */
    fun chapterTitleAt(page: Int): String? {
        val pageWindow = pageWindows.getOrNull(page) ?: return null
        if (pageWindow.blocks.any { block -> block.kind == ReaderBlockKind.COVER_IMAGE }) return null
        val start = pageWindow.textRange?.start ?: return null
        var sectionIndex = sectionPositionAtOrBefore(start) ?: return null
        while (sectionIndex >= 0) {
            sections[sectionIndex].title?.let { return it }
            sectionIndex--
        }
        return null
    }

    /**
     * The zero-based page position and page count inside the chapter containing [page].
     *
     * Chapter boundaries follow [chapterTitleAt]: an untitled section belongs to the previous titled
     * section, and the next titled section starts the next chapter. Page lookups stay logarithmic so
     * this never walks the lazily built [pageWindows] list.
     */
    fun chapterPageIndexAt(page: Int): PageIndex? {
        val pageWindow = pageWindows.getOrNull(page) ?: return null
        if (pageWindow.blocks.any { block -> block.kind == ReaderBlockKind.COVER_IMAGE }) return null
        val start = pageWindow.textRange?.start ?: return null
        val chapterSection = titledSectionPositionAtOrBefore(sectionPositionAtOrBefore(start) ?: return null)
            ?: return null
        val chapterStartPage = pageOf(sections[chapterSection].range.start) ?: return null
        val nextChapterSection = (chapterSection + 1..sections.lastIndex)
            .firstOrNull { index -> sections[index].title != null }
        val chapterEndPage = nextChapterSection
            ?.let { index -> pageOf(sections[index].range.start) }
            ?: pageCount
        return PageIndex(current = page - chapterStartPage, total = chapterEndPage - chapterStartPage)
    }

    private fun titledSectionPositionAtOrBefore(sectionPosition: Int): Int? {
        var position = sectionPosition
        while (position >= 0) {
            if (sections[position].title != null) return position
            position--
        }
        return null
    }

    private fun sectionPositionAtOrBefore(offset: Long): Int? {
        var low = 0
        var high = sections.lastIndex
        var result: Int? = null
        while (low <= high) {
            val mid = (low + high) / 2
            when {
                sections[mid].range.start <= offset -> {
                    result = mid
                    low = mid + 1
                }
                else -> high = mid - 1
            }
        }
        return result
    }

    /**
     * Whether [page] is the last page of its section.
     *
     * True by construction from where pagination put this page's own boundary, not from how much of the
     * sheet the rendered text happened to fill — an estimated pagination under-fills every page it has
     * not measured for real, which would otherwise make every page on a fresh install look short before
     * the real measurement replaces it.
     *
     * @param page a page index.
     * @return true exactly when the page's own [PageWindow.textRange] ends where its containing section
     *   ends; false when [page] has no window, no [PageWindow.textRange] yet, or does not reach its
     *   section's end.
     */
    fun isSectionTail(page: Int): Boolean {
        val range = pageWindows.getOrNull(page)?.textRange ?: return false
        return sectionContaining(range.start)?.range?.end == range.end
    }

    /**
     * The embedded-image hrefs referenced by any block on a page in [pages] — what a preloader asks the
     * repository to fetch for that window.
     *
     * @param pages the pages to check; an index outside [pageWindows] is skipped rather than treated as
     *   an error.
     * @return the distinct image hrefs those pages' blocks reference.
     */
    fun imageHrefsIn(pages: IntRange): Set<String> =
        pages.filter { page -> page in pageWindows.indices }
            .flatMap { page -> pageWindows[page].blocks.mapNotNull { block -> block.imageHref } }
            .toSet()

    /**
     * The embedded-font hrefs referenced by a page in [pages], from both block-level and inline CSS.
     *
     * @param pages the pages to check; an index outside [pageWindows] is skipped rather than treated as
     *   an error.
     * @return the distinct font hrefs those pages reference.
     */
    fun fontHrefsIn(pages: IntRange): Set<String> =
        pages.asSequence()
            .filter { page -> page in pageWindows.indices }
            .flatMap { page ->
                pageWindows[page].blocks.asSequence().flatMap { block ->
                    sequenceOf(block.style?.fontHref)
                        .plus(block.spans.asSequence().map { span -> span.styleDelta?.fontHref })
                }
            }
            .filterNotNull()
            .toSet()
}

/**
 * The absolute document offset [location] names, resolved against [sections].
 *
 * A free function rather than a member because a caller can need to resolve a location against a
 * section list that is not yet a [PaginatedDocument]'s own — `ReaderViewModel` opening a document
 * resolves the resumed offset against the freshly loaded document's sections before that pagination
 * pass exists.
 *
 * @param location a reading position.
 * @param sections the section list to resolve [ReaderLocation.EpubOffset] against.
 * @return the offset [location] names: itself for [ReaderLocation.TextOffset], the sum of its spine
 *   item's start and its own offset for [ReaderLocation.EpubOffset] (the spine item's start defaults to
 *   0 when [sections] does not contain it yet, which lets an EPUB position resolve early during a
 *   progressive import), or null for [ReaderLocation.PdfPage], which names a page number rather than a
 *   character offset.
 */
fun absoluteOffsetOf(location: ReaderLocation, sections: List<ReaderSection>): Long? =
    when (location) {
        is ReaderLocation.TextOffset -> location.offset
        is ReaderLocation.EpubOffset -> {
            val sectionStart = sections
                .firstOrNull { section -> section.index == location.spineIndex }
                ?.range
                ?.start
                ?: 0L
            sectionStart + location.offset
        }
        is ReaderLocation.PdfPage -> null
    }

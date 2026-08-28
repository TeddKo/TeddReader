package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PaginatedDocument
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderSpanStyle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet

internal data class ReaderPageUiContext(
    val pageIndex: PageIndex,
    val documentUri: String?,
    val isPdfMode: Boolean,
    val paginated: PaginatedDocument,
    val embeddedImages: Map<String, ByteArray>,
    val embeddedFontFiles: Map<String, String>,
    val failedEmbeddedImageHrefs: Set<String>,
    val failedEmbeddedFontHrefs: Set<String>,
)

internal data class ReaderPageFacingUi(
    val previous: ReaderPageUi?,
    val current: ReaderPageUi,
    val next: ReaderPageUi?,
    val slots: ImmutableList<ReaderPageUi>,
)

internal fun currentReaderPageUi(context: ReaderPageUiContext): ReaderPageUi =
    readerPageUi(context.pageIndex.current, context) ?: ReaderPageUi(
        page = context.pageIndex.current,
        isPdf = context.isPdfMode,
        documentUri = context.documentUri,
    )

internal fun readerPageFacingUi(context: ReaderPageUiContext): ReaderPageFacingUi {
    val currentPage = context.pageIndex.current
    return ReaderPageFacingUi(
        previous = readerPageUi(currentPage - 1, context),
        current = currentReaderPageUi(context),
        next = readerPageUi(currentPage + 1, context),
        slots = pagerMountWindow(currentPage).mapNotNull { page -> readerPageUi(page, context) }.toImmutableList(),
    )
}

internal fun readerPageUi(page: Int, context: ReaderPageUiContext): ReaderPageUi? {
    if (context.pageIndex.total <= 0 || page !in 0 until context.pageIndex.total) return null
    val pageWindow = context.paginated.pageWindows.getOrNull(page)
    val blocks = pageWindow?.blocks.orEmpty()
    val resources = pageResourceSnapshot(blocks, context)
    return ReaderPageUi(
        page = page,
        text = if (context.isPdfMode) "" else pageWindow?.text.orEmpty(),
        isPdf = context.isPdfMode,
        documentUri = context.documentUri,
        textRange = pageWindow?.textRange,
        blocks = blocks.toImmutableList(),
        embeddedImages = resources.embeddedImages,
        embeddedFontFiles = resources.embeddedFontFiles,
        failedEmbeddedImageHrefs = resources.failedEmbeddedImageHrefs,
        failedEmbeddedFontHrefs = resources.failedEmbeddedFontHrefs,
        chapterTitle = context.paginated.chapterTitleAt(page),
        isSectionTail = context.paginated.isSectionTail(page),
    )
}

/**
 * The embedded-image bytes, embedded-font file paths, and the failed-to-load hrefs of both kinds
 * that a single page's blocks reference — everything [readerPageUi] needs to hand a [ReaderPageUi]
 * about resources that live inside the document container.
 *
 * This exists so the mapper resolves a page's image and font references in one linear walk of the
 * page's blocks (and, for fonts, each block's spans) instead of the five independent passes the
 * mapper used to make over the same list. On a page carrying real blocks and inline spans those
 * repeated passes were the mapper's hot cost; folding them into one walk keeps the visible contract
 * identical while touching each block and span once.
 *
 * The four members keep the exact meaning [ReaderPageUi]'s own fields carry, including two subtle
 * ones the single pass must preserve:
 * - [embeddedFontFiles] and [failedEmbeddedFontHrefs] are resolved against two independent inputs —
 *   [ReaderPageUiContext.embeddedFontFiles] and [ReaderPageUiContext.failedEmbeddedFontHrefs] — so
 *   the same href can appear in both when a font both has a loaded file and is also marked failed;
 *   this snapshot never lets one of the two suppress the other.
 * - within [embeddedFontFiles] and [failedEmbeddedFontHrefs] the block's own style font is offered
 *   before that block's span fonts, and blocks are visited in list order, so the first-seen order
 *   matches what the old `listOfNotNull(block.style?.fontHref) + block.spans…` flat-map produced.
 *
 * Insertion order is preserved throughout: the backing maps and sets are [LinkedHashMap] and
 * [LinkedHashSet], so iterating a result reproduces first-reference order. When a page has no
 * matching resource the corresponding member is the shared empty persistent collection, so an
 * image-free, font-free page allocates no intermediate builder at all.
 *
 * @param blocks the blocks of one page, already resolved to a possibly-empty list by the caller.
 * @param context the mapping context whose [ReaderPageUiContext.embeddedImages],
 *   [ReaderPageUiContext.embeddedFontFiles], [ReaderPageUiContext.failedEmbeddedImageHrefs], and
 *   [ReaderPageUiContext.failedEmbeddedFontHrefs] a reference is looked up against.
 * @return the resolved per-page resource snapshot; every member is empty when [blocks] references
 *   nothing the context knows.
 */
private fun pageResourceSnapshot(
    blocks: List<ReaderBlock>,
    context: ReaderPageUiContext,
): PageResourceSnapshot {
    if (blocks.isEmpty()) return PageResourceSnapshot.Empty

    val builder = PageResourceSnapshotBuilder(context)
    for (block in blocks) {
        block.imageHref?.let(builder::offerImage)
        block.style?.fontHref?.let(builder::offerFont)
        for (span in block.spans) {
            span.styleDelta?.fontHref?.let(builder::offerFont)
        }
    }
    return builder.build()
}

/**
 * A single-page resource collector filled by [pageResourceSnapshot] while it walks a page's blocks,
 * turning each offered href into the right one of the four [PageResourceSnapshot] collections.
 *
 * Its lazy [LinkedHashMap]/[LinkedHashSet] backing means a page that references no known image or
 * font never allocates a builder collection, and a first reference decides an href's position, so
 * iterating a built result reproduces the order references arrived in.
 *
 * @property context the lookup source an offered href is resolved against; see the members of
 *   [ReaderPageUiContext] for what each map and set means.
 */
private class PageResourceSnapshotBuilder(private val context: ReaderPageUiContext) {
    /** Loaded image bytes in first-reference order, allocated only after the first matching href. */
    private var embeddedImages: LinkedHashMap<String, ByteArray>? = null

    /** Loaded font files in first-reference order, allocated only after the first matching href. */
    private var embeddedFonts: LinkedHashMap<String, String>? = null

    /** Failed image hrefs in first-reference order, allocated only after the first failed href. */
    private var failedImages: LinkedHashSet<String>? = null

    /** Failed font hrefs in first-reference order, allocated only after the first failed href. */
    private var failedFonts: LinkedHashSet<String>? = null

    /**
     * Resolves one block image [href] against the context: records its bytes when the document
     * carries them, and marks it failed when the importer flagged it — independently, so an href
     * both carried and flagged lands in both.
     *
     * @param href a block's [ReaderBlock.imageHref].
     */
    fun offerImage(href: String) {
        context.embeddedImages[href]?.let { bytes ->
            val images = embeddedImages ?: LinkedHashMap<String, ByteArray>().also { embeddedImages = it }
            images[href] = bytes
        }
        if (href in context.failedEmbeddedImageHrefs) {
            val failed = failedImages ?: LinkedHashSet<String>().also { failedImages = it }
            failed.add(href)
        }
    }

    /**
     * Resolves one block-style or span font [href] against the context: records its on-disk file
     * when the document carries it, and marks it failed when the importer flagged it —
     * independently, so an href both carried and flagged lands in both.
     *
     * @param href a [ReaderBlockStyle.fontHref] or a [ReaderSpanStyle.fontHref].
     */
    fun offerFont(href: String) {
        context.embeddedFontFiles[href]?.let { file ->
            val fonts = embeddedFonts ?: LinkedHashMap<String, String>().also { embeddedFonts = it }
            fonts[href] = file
        }
        if (href in context.failedEmbeddedFontHrefs) {
            val failed = failedFonts ?: LinkedHashSet<String>().also { failedFonts = it }
            failed.add(href)
        }
    }

    /**
     * The snapshot of everything offered so far, each member the shared empty persistent collection
     * when nothing of that kind was recorded.
     *
     * @return the immutable per-page resource snapshot.
     */
    fun build(): PageResourceSnapshot = PageResourceSnapshot(
        embeddedImages = embeddedImages?.toImmutableMap() ?: persistentMapOf(),
        embeddedFontFiles = embeddedFonts?.toImmutableMap() ?: persistentMapOf(),
        failedEmbeddedImageHrefs = failedImages?.toImmutableSet() ?: persistentSetOf(),
        failedEmbeddedFontHrefs = failedFonts?.toImmutableSet() ?: persistentSetOf(),
    )
}

/**
 * The four resource collections a single page contributes to its [ReaderPageUi], produced together
 * by [pageResourceSnapshot] so the mapper resolves them in one walk of the page's blocks.
 *
 * @property embeddedImages the image bytes of every block image href the document actually carries,
 *   in block order.
 * @property embeddedFontFiles the on-disk font file of every block-style or span font href the
 *   document actually carries, in first-reference order with each block's style font ahead of its
 *   span fonts.
 * @property failedEmbeddedImageHrefs the block image hrefs the importer marked as failed to load.
 * @property failedEmbeddedFontHrefs the block-style and span font hrefs the importer marked as
 *   failed to load; independent of [embeddedFontFiles], so an href can appear in both.
 */
private data class PageResourceSnapshot(
    val embeddedImages: ImmutableMap<String, ByteArray>,
    val embeddedFontFiles: ImmutableMap<String, String>,
    val failedEmbeddedImageHrefs: ImmutableSet<String>,
    val failedEmbeddedFontHrefs: ImmutableSet<String>,
) {
    /** Holds [Empty], the shared snapshot a page with no known image or font reference reuses. */
    companion object {
        /** The allocation-free result reused when a page has no blocks to inspect. */
        val Empty = PageResourceSnapshot(
            embeddedImages = persistentMapOf(),
            embeddedFontFiles = persistentMapOf(),
            failedEmbeddedImageHrefs = persistentSetOf(),
            failedEmbeddedFontHrefs = persistentSetOf(),
        )
    }
}

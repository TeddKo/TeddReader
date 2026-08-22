package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PaginatedDocument
import kotlinx.collections.immutable.ImmutableList
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
    return ReaderPageUi(
        page = page,
        text = if (context.isPdfMode) "" else pageWindow?.text.orEmpty(),
        isPdf = context.isPdfMode,
        documentUri = context.documentUri,
        textRange = pageWindow?.textRange,
        blocks = pageWindow?.blocks.orEmpty().toImmutableList(),
        embeddedImages = pageWindow
            ?.blocks
            .orEmpty()
            .mapNotNull { block -> block.imageHref?.takeIf(context.embeddedImages::containsKey) }
            .associateWith(context.embeddedImages::getValue)
            .toImmutableMap(),
        embeddedFontFiles = pageWindow
            ?.blocks
            .orEmpty()
            .flatMap { block ->
                listOfNotNull(block.style?.fontHref) + block.spans.mapNotNull { span -> span.cssStyle?.fontHref }
            }
            .distinct()
            .mapNotNull { href -> context.embeddedFontFiles[href]?.let { href to it } }
            .toMap()
            .toImmutableMap(),
        failedEmbeddedImageHrefs = pageWindow
            ?.blocks
            .orEmpty()
            .mapNotNull { it.imageHref }
            .filter(context.failedEmbeddedImageHrefs::contains)
            .toImmutableSet(),
        failedEmbeddedFontHrefs = pageWindow
            ?.blocks
            .orEmpty()
            .flatMap { block ->
                listOfNotNull(block.style?.fontHref) + block.spans.mapNotNull { span -> span.cssStyle?.fontHref }
            }
            .filter(context.failedEmbeddedFontHrefs::contains)
            .toImmutableSet(),
        chapterTitle = context.paginated.chapterTitleAt(page),
        isSectionTail = context.paginated.isSectionTail(page),
    )
}

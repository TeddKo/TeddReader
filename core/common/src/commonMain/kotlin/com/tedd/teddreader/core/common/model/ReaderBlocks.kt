package com.tedd.teddreader.core.common.model

import kotlinx.serialization.Serializable

/**
 * Structure a document's text carries, laid over the same character offsets the reader already uses
 * for search, bookmarks and reading position. A block names what a stretch of text is; it never owns
 * the text itself, so pagination and progress keep working on one flat string.
 */
@Serializable
enum class ReaderBlockKind {
    PARAGRAPH,
    HEADING,
    QUOTE,
    LIST_ITEM,
    PREFORMATTED,
    IMAGE,
    COVER_IMAGE,
    TABLE_CELL,
    TABLE_HEADER_CELL,
    SEPARATOR,
}

@Serializable
enum class ReaderInlineStyle {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKETHROUGH,
    MONOSPACE,
    SUPERSCRIPT,
    SUBSCRIPT,
    LINK,
}

@Serializable
enum class ReaderTextAlign {
    START,
    CENTER,
    END,
    JUSTIFY,
}

/** One inline run inside a block, addressed in the document's flat text. */
@Serializable
data class ReaderSpan(
    val range: TextRange,
    val style: ReaderInlineStyle,
    val href: String? = null,
) {
    init {
        require(style != ReaderInlineStyle.LINK || href != null) { "A link span must carry an href." }
    }
}

@Serializable
data class ReaderBlock(
    val kind: ReaderBlockKind,
    val range: TextRange,
    /** Heading level 1..6, or list nesting depth starting at 1. Zero when the kind has no level. */
    val level: Int = 0,
    val spans: List<ReaderSpan> = emptyList(),
    val align: ReaderTextAlign? = null,
    /** Resolved path of the image inside the container, for [ReaderBlockKind.IMAGE]. */
    val imageHref: String? = null,
    /** Alt text of an image, or the marker of an ordered list item. */
    val label: String? = null,
    val tableRow: Int? = null,
    val tableColumn: Int? = null,
    /** Width divided by height of the source image, for [ReaderBlockKind.IMAGE] and [ReaderBlockKind.COVER_IMAGE]. */
    val imageAspectRatio: Float? = null,
) {
    init {
        require(level >= 0) { "Block level must be positive." }
        require(
            (kind != ReaderBlockKind.IMAGE && kind != ReaderBlockKind.COVER_IMAGE) || imageHref != null,
        ) { "An image block must carry an href." }
        require(tableRow == null || tableRow >= 0) { "Table row must be positive." }
        require(tableColumn == null || tableColumn >= 0) { "Table column must be positive." }
        require(imageAspectRatio == null || imageAspectRatio > 0f) { "Image aspect ratio must be positive." }
    }
}

fun ReaderBlockKind.isTableCell(): Boolean =
    this == ReaderBlockKind.TABLE_CELL || this == ReaderBlockKind.TABLE_HEADER_CELL

/** True when the block draws something other than text and so carries no readable characters. */
fun ReaderBlockKind.isStandalone(): Boolean =
    this == ReaderBlockKind.IMAGE || this == ReaderBlockKind.COVER_IMAGE || this == ReaderBlockKind.SEPARATOR

/** Blocks that overlap [start, end), so a page can render only the structure it actually shows. */
fun List<ReaderBlock>.blocksIn(start: Long, end: Long): List<ReaderBlock> = filter { block ->
    if (block.range.start == block.range.end) {
        block.range.start in start until end || (block.range.start == end && end == start)
    } else {
        block.range.start < end && block.range.end > start
    }
}

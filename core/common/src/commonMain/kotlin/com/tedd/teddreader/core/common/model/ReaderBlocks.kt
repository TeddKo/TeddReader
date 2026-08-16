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
    /** Intrinsic width of the source image in CSS pixels, used when nothing declares a width. */
    val imageNaturalWidthPx: Int? = null,
    /** Width the document's own stylesheet gives the image, as a fraction of the text column. */
    val imageWidthPercent: Float? = null,
    /** Width the document's own stylesheet gives the image, in em. */
    val imageWidthEm: Float? = null,
) {
    init {
        require(level >= 0) { "Block level must be positive." }
        require(
            (kind != ReaderBlockKind.IMAGE && kind != ReaderBlockKind.COVER_IMAGE) || imageHref != null,
        ) { "An image block must carry an href." }
        require(tableRow == null || tableRow >= 0) { "Table row must be positive." }
        require(tableColumn == null || tableColumn >= 0) { "Table column must be positive." }
        require(imageAspectRatio == null || imageAspectRatio > 0f) { "Image aspect ratio must be positive." }
        require(imageNaturalWidthPx == null || imageNaturalWidthPx > 0) { "Image natural width must be positive." }
        require(imageWidthPercent == null || imageWidthPercent > 0f) { "Image width percent must be positive." }
        require(imageWidthEm == null || imageWidthEm > 0f) { "Image width em must be positive." }
    }
}

/** Size an image is drawn at, in em, so measurement and rendering can never disagree about it. */
data class ReaderImageSize(val widthEm: Float, val heightEm: Float)

/**
 * Lay out one image the way a reading system does.
 *
 * This follows the rule every reflowable reading system settles on. Readium's own stylesheet states it
 * as `img, svg, video { object-fit: contain; width: auto; height: auto; max-width: 100%;
 * max-height: 95vh !important; break-inside: avoid }`, readium-shared-js as
 * `max-width: 98%; max-height: 98%; height: auto; width: auto`, and foliate-js sets the same pair of
 * maxima plus `object-fit: contain` on every `img, svg, video` it finds. The shared meaning is: the
 * picture is drawn at the size it actually is — the width the book's stylesheet gives it, or failing
 * that its own intrinsic width — and is only ever shrunk to fit the column and the page, never
 * stretched up to them. Forcing every picture to the full column instead turns a hairline rule into a
 * thick band and a small logo into a poster.
 *
 * [columnWidthEm] is the text column, [maxHeightEm] the page, and [emInPx] how many CSS pixels one em
 * is, which converts an intrinsic pixel width into the em units everything else is measured in.
 */
fun ReaderBlock.readerImageSize(
    columnWidthEm: Float,
    maxHeightEm: Float,
    emInPx: Float,
): ReaderImageSize {
    val column = columnWidthEm.coerceAtLeast(MinReaderImageEm)
    if (kind == ReaderBlockKind.SEPARATOR) return ReaderImageSize(column, SeparatorHeightEm)

    // `max-height: 95vh`, not the whole page: an image allowed the last hairline of the page leaves no
    // room for the line box holding it and is pushed to a page of its own or clipped at the edge.
    val page = (maxHeightEm * MaxImagePageHeightFraction).coerceAtLeast(MinReaderImageEm)
    val declaredEm = imageWidthEm
        ?: imageWidthPercent?.let { column * it }
        ?: imageNaturalWidthPx?.takeIf { emInPx > 0f }?.let { it / emInPx }
    // max-width: 100%; the picture is scaled down to the column but never up past what is declared.
    var width = (declaredEm ?: column).coerceIn(MinReaderImageEm, column)
    val ratio = imageAspectRatio?.takeIf { it > 0f }
    var height = if (ratio != null) width / ratio else page
    if (height > page) {
        // max-height: keep the proportions and give back the width the shorter box no longer needs.
        if (ratio != null) width = (page * ratio).coerceAtMost(column)
        height = page
    }
    return ReaderImageSize(width.coerceAtLeast(MinReaderImageEm), height.coerceAtLeast(MinReaderImageEm))
}

private const val MinReaderImageEm = 0.05f

/** `max-height: 95vh`, the cap Readium's stylesheet puts on any image in reflowable text. */
private const val MaxImagePageHeightFraction = 0.95f

/** Height a horizontal rule draws at, matching the divider the renderer puts in its place. */
private const val SeparatorHeightEm = 1.25f

fun ReaderBlockKind.isTableCell(): Boolean =
    this == ReaderBlockKind.TABLE_CELL || this == ReaderBlockKind.TABLE_HEADER_CELL

/** True when the block draws something other than text and so carries no readable characters. */
fun ReaderBlockKind.isStandalone(): Boolean =
    this == ReaderBlockKind.IMAGE || this == ReaderBlockKind.COVER_IMAGE || this == ReaderBlockKind.SEPARATOR

/**
 * The one character a picture occupies in a document's flat text: U+FFFC OBJECT REPLACEMENT CHARACTER,
 * which is what it means. Keeping the picture in the text rather than between two blocks is what lets
 * an `<img>` stay inside the sentence it was written in, which is where HTML puts it.
 */
const val ReaderObjectReplacementChar: Char = '￼'

/** True when [this] reads as empty once the pictures in it are discounted. */
fun String.isBlankIgnoringObjects(): Boolean =
    all { char -> char == ReaderObjectReplacementChar || char.isWhitespace() }

/**
 * The pictures and rules that stand on a line of their own, as opposed to those set inside a sentence.
 *
 * An `<img>` is inline content in HTML — a gaiji glyph or an icon belongs on the line it was written
 * on — and no reading system moves it out of its paragraph. A picture that is the only thing in its
 * block has no paragraph enclosing it, and that is exactly what makes it a plate.
 */
fun List<ReaderBlock>.standaloneBlocks(): List<ReaderBlock> {
    val textRanges = filter { !it.kind.isStandalone() }.map { it.range }
    if (textRanges.isEmpty()) return filter { it.kind.isStandalone() }
    return filter { block ->
        block.kind.isStandalone() &&
            textRanges.none { range -> range.start <= block.range.start && range.end >= block.range.end }
    }
}

/** Blocks that overlap [start, end), so a page can render only the structure it actually shows. */
fun List<ReaderBlock>.blocksIn(start: Long, end: Long): List<ReaderBlock> = filter { block ->
    if (block.range.start == block.range.end) {
        block.range.start in start until end || (block.range.start == end && end == start)
    } else {
        block.range.start < end && block.range.end > start
    }
}

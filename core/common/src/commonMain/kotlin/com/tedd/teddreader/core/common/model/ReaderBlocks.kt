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
    CONTAINER,
    IMAGE,
    COVER_IMAGE,
    TABLE_CELL,
    TABLE_HEADER_CELL,
    SEPARATOR,
}

/**
 * The inline emphasis a book can ask for inside a block, as [ReaderSpan] applies it.
 *
 * A closed set for the semantic inline shapes the reader already knows how to draw. Extra inline CSS
 * that is still representable in reader-owned types rides alongside it through [ReaderSpan.styleDelta]
 * rather than expanding this enum into raw stylesheet vocabulary.
 */
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

/** Generic families a book can ask for that this reader can actually supply. */
@Serializable
enum class ReaderFontFamily {
    SERIF,
    SANS_SERIF,
    MONOSPACE,
}

/** Which inline edge a floated image clings to in publisher styling. */
@Serializable
enum class ReaderFloat {
    START,
    END,
}

/** One side of a box border the publisher asked for. */
@Serializable
data class ReaderBorder(
    val widthPx: Float? = null,
    val color: ReaderColor? = null,
) {
    init {
        require(widthPx == null || widthPx >= 0f) { "Border width must be non-negative." }
    }
}

/** Compact box styling the renderer can still honor later. */
@Serializable
data class ReaderBoxStyle(
    val backgroundColor: ReaderColor? = null,
    val borderTop: ReaderBorder? = null,
    val borderRight: ReaderBorder? = null,
    val borderBottom: ReaderBorder? = null,
    val borderLeft: ReaderBorder? = null,
    /** Corner roundness as a CSS/Compose percent in the closed 0..100 range. */
    val borderRadiusPercent: Float? = null,
) {
    init {
        require(borderRadiusPercent == null || borderRadiusPercent in 0f..100f) {
            "Border radius percent must be in 0..100."
        }
    }

    fun isEmpty(): Boolean = this == Empty

    companion object {
        val Empty = ReaderBoxStyle()
    }
}

/**
 * What a book's own stylesheet says a block — or an inline run narrow enough to reuse this type — looks
 * like, in units relative to the reader's own type.
 *
 * Everything here is relative on purpose: the reader's font size and theme stay in charge, and the
 * book adjusts around them. An absolute size or colour from a stylesheet would fight the size the
 * reader chose and the theme they are reading in.
 *
 * @property fontScale multiple of the reader's font size, e.g. `font-size: 1.4em`; null when unstated.
 * @property bold whether the book asks for bold, or null when it says nothing.
 * @property italic whether the book asks for italic, or null when it says nothing.
 * @property fontFamily a generic family the book asks for, or null when it says nothing.
 * @property lineHeightScale multiple of the font size, e.g. `line-height: 1.7em`; null when unstated.
 * @property textIndentEm first-line indent in em, e.g. `text-indent: 1em`; null when unstated.
 * @property marginTopEm space above the block in em, e.g. `margin-top: 0.5em`; null when unstated.
 * @property marginBottomEm space below the block in em; null when unstated. This is what decides the gap
 * between two paragraphs, so a book that states `margin-bottom: 10px` gets that gap and not a whole line.
 * @property marginStartEm inline-start space in em; null when unstated.
 * @property marginEndEm inline-end space in em; null when unstated.
 * @property paddingTopEm space inside the block above its text, in em; null when unstated.
 * @property paddingBottomEm space inside the block below its text, in em; null when unstated.
 * @property paddingStartEm space inside the block before its text, in em; null when unstated. This is what
 * a book indents a quotation with, and a reader that drops it sets the quotation flush with the prose.
 * @property paddingEndEm space inside the block after its text, in em; null when unstated.
 * @property underline whether the book asks for an underline; false is a real answer — it is how a book
 * says its links carry none — and null means it said nothing.
 * @property lineThrough whether the book asks for a strikethrough, or null when it said nothing.
 * @throws IllegalArgumentException if [fontScale] or [lineHeightScale] is not positive, or a vertical
 * margin is negative.
 */
@Serializable
data class ReaderBlockStyle(
    /** Multiple of the reader's font size, e.g. `font-size: 1.4em`. */
    val fontScale: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val fontFamily: ReaderFontFamily? = null,
    val fontFamilyName: String? = null,
    val fontHref: String? = null,
    /** Multiple of the font size, e.g. `line-height: 1.7em`. */
    val lineHeightScale: Float? = null,
    /** First-line indent in em, e.g. `text-indent: 1em`. */
    val textIndentEm: Float? = null,
    /** Space the book asks for above this block, in em; null when it says nothing. */
    val marginTopEm: Float? = null,
    /** Space the book asks for below this block, in em; null when it says nothing. */
    val marginBottomEm: Float? = null,
    /** Space the book asks for before this block inline-wise, in em; null when it says nothing. */
    val marginStartEm: Float? = null,
    /** Space the book asks for after this block inline-wise, in em; null when it says nothing. */
    val marginEndEm: Float? = null,
    /** Space inside the block above its own text, in em; null when the book says nothing. */
    val paddingTopEm: Float? = null,
    /** Space inside the block below its own text, in em; null when the book says nothing. */
    val paddingBottomEm: Float? = null,
    /** Space inside the block before its own text, in em — what indents a quotation; null when unstated. */
    val paddingStartEm: Float? = null,
    /** Space inside the block after its own text, in em; null when the book says nothing. */
    val paddingEndEm: Float? = null,
    /**
     * The whole inline-start space this block's text is laid out behind, in em: its own start margin and
     * padding plus every block-level ancestor's, accumulated by the parser. Null when nothing states one.
     * This is the resolved value a renderer indents with; the per-side margin/padding fields above stay
     * the block's own, for box painting.
     */
    val insetStartEm: Float? = null,
    /** Inline-end counterpart of [insetStartEm]; null when nothing states one. */
    val insetEndEm: Float? = null,
    /** Whether the book asks for an underline, or null when it says nothing about decoration. */
    val underline: Boolean? = null,
    /** Whether the book asks for a strikethrough, or null when it says nothing about decoration. */
    val lineThrough: Boolean? = null,
    val foregroundColor: ReaderColor? = null,
    val boxStyle: ReaderBoxStyle? = null,
) {
    init {
        require(fontScale == null || fontScale > 0f) { "fontScale must be positive." }
        require(lineHeightScale == null || lineHeightScale > 0f) { "lineHeightScale must be positive." }
        require(marginTopEm == null || marginTopEm >= 0f) { "marginTopEm must be non-negative." }
        require(marginBottomEm == null || marginBottomEm >= 0f) { "marginBottomEm must be non-negative." }
    }

    /** Whether the book's stylesheet stated nothing at all here, making this indistinguishable from [Empty]. */
    fun isEmpty(): Boolean = this == Empty

    /** Holds [Empty], the shared instance a block with no stated style is given instead of a fresh one. */
    companion object {
        val Empty = ReaderBlockStyle()
    }
}

/**
 * Inline styling a span carries as the *difference* from whatever encloses it — never as absolutes.
 *
 * A span nests inside a block (and inside other spans), and the renderer applies its values on top of
 * whatever is already in force at that position: an em font size multiplies the enclosing size. A span
 * that re-stated its full inherited style applied everything its block already applied — a `0.9em`
 * wrapper's text came out at `0.81`. This type makes that contract structural rather than a comment:
 * only delta-safe properties exist here, and absolute lengths (margins, insets, line height, indent)
 * are unrepresentable on a span by construction.
 *
 * @property fontScale multiple of the *enclosing* font size at the span's position, not of the reader's
 * base; null when the span does not change the size.
 * @property bold/italic/fontFamily/fontFamilyName/fontHref/foregroundColor overrides of the enclosing
 * value; null means the enclosing value stands.
 * @property underline whether decoration is painted across this span; false is a real answer (how a book
 * turns a link's underline off) and null means the enclosing decoration stands.
 * @property lineThrough strikethrough on the same terms as [underline].
 */
@Serializable
data class ReaderSpanStyle(
    val fontScale: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val fontFamily: ReaderFontFamily? = null,
    val fontFamilyName: String? = null,
    val fontHref: String? = null,
    val underline: Boolean? = null,
    val lineThrough: Boolean? = null,
    val foregroundColor: ReaderColor? = null,
) {
    init {
        require(fontScale == null || fontScale > 0f) { "fontScale must be positive." }
    }

    /** Whether this delta changes nothing at all, making it indistinguishable from [Empty]. */
    fun isEmpty(): Boolean = this == Empty

    /** Holds [Empty], the shared no-op instance. */
    companion object {
        val Empty = ReaderSpanStyle()
    }
}

/** How a block's lines are aligned when the book's stylesheet says so; null on a block means the reader's
 *  own default stands. */
@Serializable
enum class ReaderTextAlign {
    START,
    CENTER,
    END,
    JUSTIFY,
}

/** One inline run inside a block, addressed in the document's flat text. *
 * @property range the run's span in absolute document offsets.
 * @property style the emphasis to apply over that run, or null for a pure CSS span with no semantic tag.
 * @property href the link target, required for [ReaderInlineStyle.LINK] and null otherwise.
 * @property styleDelta extra CSS-derived styling that rides with the span when [style] alone is not
 * enough, always as a [ReaderSpanStyle] delta against the enclosing context.
 * @throws IllegalArgumentException if a link span carries no [href].
 */
@Serializable
data class ReaderSpan(
    val range: TextRange,
    val style: ReaderInlineStyle? = null,
    val href: String? = null,
    val styleDelta: ReaderSpanStyle? = null,
) {
    init {
        require(style != ReaderInlineStyle.LINK || href != null) { "A link span must carry an href." }
        require(
            style != null || styleDelta?.isEmpty() == false,
        ) { "A span must carry a semantic style or non-empty styleDelta." }
    }
}

/**
 * One structural piece of a document — a paragraph, a heading, a picture, a table cell — named by its
 * [kind] and addressed by the span of flat text it covers.
 *
 * A block never owns text. It points at the same absolute offsets search, bookmarks and reading position
 * use, which is what lets styling be added to a document without pagination or progress having to know
 * anything about it: the text stays one flat string, and blocks are a layer over it.
 *
 * Most fields are nullable because a block only carries what its own kind and the book's stylesheet
 * actually stated; `init` enforces the pairs that must hold together, above all that a picture always
 * knows where its file is.
 *
 * The image fields are separate rather than pre-resolved into one size because the answer depends on the
 * column and page a page is being laid out into — see [readerImageSize], which is the only place they
 * are combined.
 */
@Serializable
data class ReaderBlock(
    /** What this block is — a paragraph, heading, picture, table cell — which decides which of the
     *  fields below apply. */
    val kind: ReaderBlockKind,
    /** The span of flat text this block covers, in the same absolute document offsets search,
     *  bookmarks and reading position already use. */
    val range: TextRange,
    /** Heading level 1..6, list nesting depth starting at 1, or CONTAINER nesting depth starting at 1.
     *  Zero when the kind has no level. */
    val level: Int = 0,
    /** Inline emphasis runs inside this block's own text; empty when the book asked for none. */
    val spans: List<ReaderSpan> = emptyList(),
    /** How this block's lines are aligned when the book's stylesheet said so; null means the reader's
     *  own default stands. */
    val align: ReaderTextAlign? = null,
    /** Resolved path of the image inside the container, for [ReaderBlockKind.IMAGE]. */
    val imageHref: String? = null,
    /** Alt text of an image, or the marker of an ordered list item. */
    val label: String? = null,
    /** Zero-based row this cell sits in within its table, for [ReaderBlockKind.TABLE_CELL] and
     *  [ReaderBlockKind.TABLE_HEADER_CELL]; null for every other kind. */
    val tableRow: Int? = null,
    /** Zero-based column within [tableRow]; null for every kind but a table cell. */
    val tableColumn: Int? = null,
    /** Width divided by height of the source image, for [ReaderBlockKind.IMAGE] and [ReaderBlockKind.COVER_IMAGE]. */
    val imageAspectRatio: Float? = null,
    /** Intrinsic width of the source image in CSS pixels, used when nothing declares a width. */
    val imageNaturalWidthPx: Int? = null,
    /** Width the document's own stylesheet gives the image, as a fraction of the text column. */
    val imageWidthPercent: Float? = null,
    /** Width the document's own stylesheet gives the image, in em. */
    val imageWidthEm: Float? = null,
    /** Float placement the document gives an image, or null when it does not float. */
    val float: ReaderFloat? = null,
    /** What the book's stylesheet says this block looks like, or null when it says nothing. */
    val style: ReaderBlockStyle? = null,
    /** True only for html/body container blocks that should paint the whole page surface. */
    val isPageContainer: Boolean = false,
) {
    init {
        require(level >= 0) { "Block level must be non-negative." }
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

/** Size an image is drawn at, in em, so measurement and rendering can never disagree about it. *
 * @property widthEm the box's width in em.
 * @property heightEm the box's height in em.
 */
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
 * The page cap is [MaxImagePageHeightFraction], not the whole page, because an image allowed the last
 * hairline of the page leaves no room for the line box holding it and is then pushed to a page of its own
 * or clipped at the edge. When that cap bites, the proportions are kept and the width the shorter box no
 * longer needs is given back to the text.
 *
 * An image whose proportions could not be read is squared off rather than handed the page. The box is
 * only what the text lays out around — the picture keeps its real shape when it is drawn — so claiming a
 * whole page for an unmeasurable image strands a small illustration in empty space and pushes the text
 * around it off the page.
 *
 * @receiver the image or separator block to size.
 * @param columnWidthEm the text column the box has to fit inside.
 * @param maxHeightEm the page height, before the 95% cap is applied to it.
 * @param emInPx how many CSS pixels one em is, which converts an intrinsic pixel width into em.
 * @return the box the text lays out around, never smaller than [MinReaderImageEm] in either direction.
 */
fun ReaderBlock.readerImageSize(
    columnWidthEm: Float,
    maxHeightEm: Float,
    emInPx: Float,
): ReaderImageSize {
    val column = columnWidthEm.coerceAtLeast(MinReaderImageEm)
    if (kind == ReaderBlockKind.SEPARATOR) return ReaderImageSize(column, SeparatorHeightEm)

    val page = (maxHeightEm * MaxImagePageHeightFraction).coerceAtLeast(MinReaderImageEm)
    val declaredEm = imageWidthEm
        ?: imageWidthPercent?.let { column * it }
        ?: imageNaturalWidthPx?.takeIf { emInPx > 0f }?.let { it / emInPx }
    var width = (declaredEm ?: column).coerceIn(MinReaderImageEm, column)
    val ratio = imageAspectRatio?.takeIf { it > 0f }
    var height = if (ratio != null) width / ratio else width
    if (height > page) {
        if (ratio != null) width = (page * ratio).coerceAtMost(column)
        height = page
    }
    return ReaderImageSize(width.coerceAtLeast(MinReaderImageEm), height.coerceAtLeast(MinReaderImageEm))
}

/** Floor on any image box, so a picture the book declared at zero still occupies something drawable. */
private const val MinReaderImageEm = 0.05f

/** `max-height: 95vh`, the cap Readium's stylesheet puts on any image in reflowable text. */
private const val MaxImagePageHeightFraction = 0.95f

/** Height a horizontal rule draws at, matching the divider the renderer puts in its place. */
private const val SeparatorHeightEm = 1.25f

/**
 * Whether this kind is part of a table, so a renderer can group cells into rows without listing kinds.
 *
 * @receiver the kind to test.
 * @return true for [ReaderBlockKind.TABLE_CELL] and [ReaderBlockKind.TABLE_HEADER_CELL].
 */
fun ReaderBlockKind.isTableCell(): Boolean =
    this == ReaderBlockKind.TABLE_CELL || this == ReaderBlockKind.TABLE_HEADER_CELL

/** True when the block draws something other than text and so carries no readable characters. *
 * @receiver the kind in question.
 * @return true for images, cover images and rules — the kinds that draw something instead of text.
 */
fun ReaderBlockKind.isStandalone(): Boolean =
    this == ReaderBlockKind.IMAGE || this == ReaderBlockKind.COVER_IMAGE || this == ReaderBlockKind.SEPARATOR

/**
 * The one character a picture occupies in a document's flat text: U+FFFC OBJECT REPLACEMENT CHARACTER,
 * which is what it means. Keeping the picture in the text rather than between two blocks is what lets
 * an `<img>` stay inside the sentence it was written in, which is where HTML puts it.
 */
const val ReaderObjectReplacementChar: Char = '￼'

/** True when [this] reads as empty once the pictures in it are discounted. *
 * @receiver the text to test.
 * @return true when it holds nothing but whitespace and picture placeholders.
 */
fun String.isBlankIgnoringObjects(): Boolean =
    all { char -> char == ReaderObjectReplacementChar || char.isWhitespace() }

/**
 * The pictures and rules that stand on a line of their own, as opposed to those set inside a sentence.
 *
 * An `<img>` is inline content in HTML — a gaiji glyph or an icon belongs on the line it was written
 * on — and no reading system moves it out of its paragraph. A picture that is the only thing in its
 * block has no paragraph enclosing it, and that is exactly what makes it a plate.
 *
 * Only *text-carrying* blocks count as enclosure. A [ReaderBlockKind.CONTAINER] is a wrapper — it owns
 * decoration and spacing, never a line of prose — and books routinely box a plate in one
 * (`<div class="frame"><img/></div>`); counting the wrapper as text demoted every such plate to an
 * inline glyph, which lost it its own centred line. A styled `body` recorded as a page container
 * likewise encloses everything on the page and proved nothing about any picture inside it.
 *
 * @receiver every block of the stretch of text being considered.
 * @return the standalone blocks that no text block encloses, i.e. the plates rather than the pictures set
 * inside a sentence.
 */
fun List<ReaderBlock>.standaloneBlocks(): List<ReaderBlock> {
    val textRanges = filter { !it.kind.isStandalone() && it.kind != ReaderBlockKind.CONTAINER }.map { it.range }
    if (textRanges.isEmpty()) return filter { it.kind.isStandalone() }
    return filter { block ->
        block.kind.isStandalone() &&
            textRanges.none { range -> range.start <= block.range.start && range.end >= block.range.end }
    }
}

/** Blocks that overlap [start, end), so a page can render only the structure it actually shows. *
 * @receiver the blocks to filter.
 * @param start first offset of the range, inclusive.
 * @param end one past its last offset.
 * @return the blocks overlapping that range, including a zero-width block sitting inside it.
 */
fun List<ReaderBlock>.blocksIn(start: Long, end: Long): List<ReaderBlock> = filter { block ->
    if (block.range.start == block.range.end) {
        block.range.start in start until end || (block.range.start == end && end == start)
    } else {
        block.range.start < end && block.range.end > start
    }
}

/**
 * [block] with its own range, and every span's range, shifted by [base]. Storing a book's blocks
 * relative to their own section instead of as absolute document offsets is what this buys: the shift
 * happens once, when a section is written (see DocumentRepositoryImpl.persistParsedDocument /
 * importNextSections), instead of once per pagination pass — a full remeasurement used to redo this
 * same allocation for every block and every span in the book just to lay it out again.
 *
 * Passing a negative [base] shifts the other way, which is how a caller reads a section-relative block
 * back out as absolute (see TextPageLayoutEngine.buildPageWindow) — the two are the same operation.
 *
 * @receiver the block to shift.
 * @param base offset to subtract from the block and each of its spans; pass a negative value to shift the
 * other way, which is how a section-relative block is read back as absolute.
 * @return the shifted copy, with offsets floored at zero.
 */
fun ReaderBlock.rebasedBy(base: Long): ReaderBlock = copy(
    range = TextRange((range.start - base).coerceAtLeast(0L), (range.end - base).coerceAtLeast(0L)),
    spans = spans.map { span ->
        span.copy(
            range = TextRange(
                (span.range.start - base).coerceAtLeast(0L),
                (span.range.end - base).coerceAtLeast(0L),
            ),
        )
    },
)

/** [ReaderBlock.rebasedBy] applied to every block in the list, in order. *
 * @receiver the blocks to shift.
 * @param base offset to subtract, as in [ReaderBlock.rebasedBy].
 * @return the shifted copies, in the same order.
 */
fun List<ReaderBlock>.rebasedBy(base: Long): List<ReaderBlock> = map { it.rebasedBy(base) }

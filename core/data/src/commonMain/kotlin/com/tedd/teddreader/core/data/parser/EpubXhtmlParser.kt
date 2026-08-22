package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderBorder
import com.tedd.teddreader.core.common.model.ReaderBoxStyle
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderFontFamily
import com.tedd.teddreader.core.common.model.ReaderFloat
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import com.tedd.teddreader.core.common.model.ReaderObjectReplacementChar
import com.tedd.teddreader.core.common.model.ReaderSpan
import com.tedd.teddreader.core.common.model.ReaderSpanStyle
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.isBlankIgnoringObjects
import kotlin.math.abs

/**
 * Flattened text of one chapter together with the structure that text carries.
 *
 * The text is what the reader paginates, searches and stores; the blocks and spans address ranges
 * inside it. Replacing every tag with a space threw all of that away and collapsed the newlines with
 * it, which is why an EPUB used to read as one run-on line.
 */
internal data class XhtmlContent(
    /** The chapter's readable text, with every tag stripped but its structure preserved as newlines. */
    val text: String,
    /**
     * Structural spans over [text] — headings, paragraphs, images, table cells, and the rest — in
     * reading order.
     */
    val blocks: List<ReaderBlock>,
    /**
     * Every `id`/`name`/`xml:id` this chapter declares, mapped to its absolute offset in [text], for
     * resolving an internal link's fragment to a position.
     */
    val anchors: Map<String, Long> = emptyMap(),
    /**
     * Chapter name taken from the `title` attribute of the first heading. These books set their part
     * and chapter headings as a picture and put the readable name only in that attribute, so without
     * it the chapter has no title text at all.
     */
    val headingTitle: String? = null,
)

/**
 * Turn chapter XHTML into text plus blocks, with every offset expressed relative to [baseOffset] so
 * chapter content can be concatenated into one document without recomputing anything.
 *
 * The parser is a single forward scan: it is not a full XML/HTML parser and builds no DOM, only a
 * running block/span builder ([XhtmlContentBuilder]) fed one tag or text run at a time. `<script>`,
 * `<style>`, `<head>` and `<title>` bodies are skipped outright (see [SkippedBodyElements]) — none of
 * them hold readable text, and skipping keeps CSS source and script code out of the page — but `<svg>`
 * is deliberately not treated this way even though it is not a recognized block or inline tag either:
 * EPUBs very commonly wrap a full-page illustration as `<svg><image xlink:href="..."/></svg>`
 * (Sigil/Calibre's standard cover/illustration pattern) so it scales to the viewport, and skipping that
 * whole subtree the way script/style genuinely warrant silently dropped every one of those pictures.
 * Descending into `svg` is harmless: it matches no known block or inline tag so it is otherwise
 * ignored, and its inner `image` element is handled the same as any other. Malformed markup fails soft
 * rather than throwing: an unterminated comment, CDATA section, or tag simply consumes the rest of the
 * input as its own body or text, and an inline or block element left unclosed at the end is closed
 * implicitly by [XhtmlContentBuilder.build].
 *
 * @param xhtml the chapter's raw markup.
 * @param baseOffset absolute offset [XhtmlContent.text]'s start represents, so a caller concatenating
 *   many chapters can pass the previous chapters' combined length and get ranges that already line up
 *   with the whole book rather than just this one file.
 * @param resolveImageHref maps a raw `src`/`xlink:href`/`href` to a path inside the container, or null
 *   to drop the image — e.g. a remote `http(s)://` URL this reader cannot fetch.
 * @param css the full CSS cascade for this chapter's element ancestry — alignment, weight, style,
 *   family, line height, indent, paragraph spacing, and picture width alike. Defaults to
 *   [EpubCss.Empty].
 * @return the flattened [XhtmlContent] for this chapter.
 */
internal fun parseXhtmlContent(
    xhtml: String,
    baseOffset: Long = 0L,
    resolveImageHref: (String) -> String? = { it },
    css: EpubCss = EpubCss.Empty,
): XhtmlContent {
    val builder = XhtmlContentBuilder(
        baseOffset = baseOffset,
        resolveImageHref = resolveImageHref,
        css = css,
    )
    var index = 0
    while (index < xhtml.length) {
        val tagStart = xhtml.indexOf('<', index)
        if (tagStart < 0) {
            builder.appendText(xhtml.substring(index))
            break
        }
        if (tagStart > index) builder.appendText(xhtml.substring(index, tagStart))

        if (xhtml.startsWith("<!--", tagStart)) {
            index = xhtml.skipPast(tagStart, "-->")
            continue
        }
        if (xhtml.startsWith("<![CDATA[", tagStart)) {
            val end = xhtml.indexOf("]]>", tagStart)
            if (end < 0) {
                builder.appendText(xhtml.substring(tagStart + 9))
                break
            }
            builder.appendText(xhtml.substring(tagStart + 9, end))
            index = end + 3
            continue
        }

        val tagEnd = xhtml.indexOf('>', tagStart)
        if (tagEnd < 0) break
        val raw = xhtml.substring(tagStart + 1, tagEnd)
        index = tagEnd + 1

        if (raw.startsWith("!") || raw.startsWith("?")) continue
        val tag = parseTag(raw)

        if (!tag.isClosing && tag.name in SkippedBodyElements) {
            if (tag.isSelfClosing) continue
            index = xhtml.skipPast(index, "</${tag.name}")
            index = xhtml.indexOf('>', index).let { if (it < 0) xhtml.length else it + 1 }
            continue
        }

        if (tag.isClosing) builder.closeElement(tag.name) else builder.openElement(tag)
    }
    return builder.build()
}

/**
 * Index just past the first occurrence of [marker] at or after [from], or [String.length] if [marker]
 * never appears — used to jump over a comment, CDATA section, or skipped-element body whose closing
 * marker may be missing from malformed markup, rather than looping forever looking for it.
 *
 * @receiver the markup being scanned.
 * @param from index to start searching from.
 * @param marker the closing text being sought, e.g. `"-->"` or `"</script"`.
 */
private fun String.skipPast(from: Int, marker: String): Int {
    val found = indexOf(marker, from)
    return if (found < 0) length else found + marker.length
}

/**
 * One start or end tag as [parseTag] reads it, before it is interpreted as a block, inline style, or
 * something ignored entirely.
 */
private class XhtmlTag(
    /** Lowercased tag name, e.g. `"p"` or `"img"`. */
    val name: String,
    /** Attribute name to value, lowercase names; empty for a closing tag. */
    val attributes: Map<String, String>,
    /** True for `</name>`. */
    val isClosing: Boolean,
    /** True for `<name .../>`. */
    val isSelfClosing: Boolean,
)

/** Parses one tag's raw interior — between its `<`/`</` and its closing `>` — into an [XhtmlTag]. */
private fun parseTag(raw: String): XhtmlTag {
    val isClosing = raw.startsWith("/")
    val body = raw.removePrefix("/").removeSuffix("/")
    val name = body.takeWhile { !it.isWhitespace() }.lowercase()
    return XhtmlTag(
        name = name,
        attributes = if (isClosing) emptyMap() else parseTagAttributes(body),
        isClosing = isClosing,
        isSelfClosing = raw.endsWith("/"),
    )
}

/** Parses a tag body's `name="value"`/`name='value'` pairs into a lowercase-keyed map. */
private fun parseTagAttributes(body: String): Map<String, String> =
    TagAttributeRegex.findAll(body).associate { match ->
        match.groupValues[1].lowercase() to (match.groupValues[2].ifEmpty { match.groupValues[3] })
    }

/**
 * An element currently open on [XhtmlContentBuilder]'s stack, kept for CSS ancestry matching and for
 * closing it back off by name.
 */
private class OpenElement(
    /** Lowercased tag name. */
    val name: String,
    /** The element's own classes, in markup order. */
    val classNames: List<String>,
    /** The element's `id` attribute, or null. */
    val id: String?,
    /** Inline `style` declarations on this element, which outrank linked CSS on the same element. */
    val inlineDeclarations: CssDeclarations = CssDeclarations.Empty,
    /** This element's fully resolved style; see [ComputedStyle]. */
    val computed: ComputedStyle = ComputedStyle.Root,
) {
    /**
     * This element as [EpubCss.declarationsFor] needs it, built once and reused for every child's cascade
     * lookup.
     */
    val cssElement: CssElement = CssElement(tag = name, classes = classNames.toSet(), id = id)
}

/**
 * A `line-height` as the resolver hands it on: either still a factor of whichever font it lands on, or a
 * size already fixed in units of the reader's base em.
 */
private sealed interface ResolvedLineHeight {
    /** A unitless factor, multiplied by each element's own [ComputedStyle.fontScale] where consumed. */
    data class Factor(val value: Float) : ResolvedLineHeight
    /** A length already computed at its declaring element, in base-em units. */
    data class BaseEm(val value: Float) : ResolvedLineHeight
}

/**
 * One element's style with the whole cascade already resolved — the single place CSS stops being raw
 * declarations and becomes numbers a renderer can consume without re-interpreting anything.
 *
 * Every length here is in one coordinate system: **units of the reader's base em**. That is the contract
 * that keeps the four consumers (block styling, span styling, gap sizing, box painting) agreeing with each
 * other — an `em` in a book compounds through its ancestors *here*, once, instead of each consumer guessing
 * what it was relative to.
 */
private class ComputedStyle(
    /**
     * Effective declarations for this element: inherited raw-text properties (weight, style, family,
     * color, alignment) with the element's own declarations layered on top. Non-inherited properties
     * (margins, padding, borders, display, width, float) are the element's own only.
     */
    val declarations: CssDeclarations,
    /** Font size as a multiple of the reader's base, compounded through the ancestor chain. */
    val fontScale: Float,
    /** Line height as declared or inherited, or null when nothing in the chain stated one. */
    val lineHeight: ResolvedLineHeight?,
    /** `text-indent` in base-em units, inherited as its computed value; null when unstated. */
    val textIndentEm: Float?,
    /** Whether an underline is painted across this element, by itself or an ancestor; null = nothing said. */
    val underline: Boolean?,
    /** Whether a strikethrough is painted across this element, on the same terms. */
    val lineThrough: Boolean?,
    /**
     * Inline-start space accumulated from every block-level ancestor's margin+padding plus this element's
     * own, in base-em units. What indents a paragraph nested in an indented wrapper. Page containers
     * (`html`/`body`) are excluded — their spacing becomes the page margin instead.
     */
    val insetStartEm: Float,
    /** Inline-end counterpart of [insetStartEm]. */
    val insetEndEm: Float,
) {
    /** The line height this element's own text is set at, in base-em units, or null when unstated. */
    fun lineHeightBaseEm(): Float? = when (val height = lineHeight) {
        is ResolvedLineHeight.Factor -> height.value * fontScale
        is ResolvedLineHeight.BaseEm -> height.value
        null -> null
    }

    /**
     * Whether a blank line belongs after a block of this style. `margin-bottom: 0` is the classic
     * indented-prose setting where paragraphs run on with no gap; anything else keeps the gap.
     */
    fun separatesParagraphs(): Boolean {
        val bottom = declarations.marginBottom?.toResolvedMarginEm(fontScale) ?: return true
        return bottom > 0f
    }

    companion object {
        /** What the document root inherits from: nothing stated, base type, no insets. */
        val Root = ComputedStyle(
            declarations = CssDeclarations.Empty,
            fontScale = 1f,
            lineHeight = null,
            textIndentEm = null,
            underline = null,
            lineThrough = null,
            insetStartEm = 0f,
            insetEndEm = 0f,
        )
    }
}

/**
 * Resolves one element's [ComputedStyle] from its parent's — one incremental step instead of a re-fold of
 * the whole ancestry, which is also what makes the cascade O(depth) per element instead of O(depth²).
 *
 * @param parent the enclosing element's computed style, or [ComputedStyle.Root] at the top.
 * @param css the chapter's stylesheets.
 * @param ancestry CSS ancestry including the element itself, outermost first.
 * @param inline the element's own `style=""` declarations, which outrank the sheet.
 * @param accumulatesInset whether this element's own start/end margin+padding joins the inset its
 * descendants are laid out with — true for block-level wrappers, false for inline elements and for the
 * page containers whose spacing becomes the page margin instead.
 */
private fun resolveComputedStyle(
    parent: ComputedStyle,
    css: EpubCss,
    ancestry: List<CssElement>,
    inline: CssDeclarations,
    accumulatesInset: Boolean,
): ComputedStyle {
    val own = css.declarationsFor(ancestry).mergedWith(inline)
    val inheritedBase = parent.declarations.inheritable()
    val effective = inheritedBase.mergedWith(own).resolvedInheritedKeywords(inheritedBase)
    val fontScale = own.fontSize.resolveFontScale(parent.fontScale)
    val lineHeight = when (val declared = own.lineHeight) {
        is CssLineHeight.Factor -> declared.value.takeIf { it > 0f }?.let(ResolvedLineHeight::Factor) ?: parent.lineHeight
        is CssLineHeight.Length -> declared.length.toResolvedLineHeightEm(fontScale)?.let(ResolvedLineHeight::BaseEm) ?: parent.lineHeight
        null -> parent.lineHeight
    }
    val textIndentEm = own.textIndent?.toResolvedIndentEm(fontScale) ?: parent.textIndentEm
    val underline = own.textDecoration?.toDecorationFlag("underline") ?: parent.underline
    val lineThrough = own.textDecoration?.toDecorationFlag("line-through") ?: parent.lineThrough
    val insetStart = if (accumulatesInset) {
        (own.marginLeft?.toResolvedMarginEm(fontScale) ?: 0f) + (own.paddingLeft?.toResolvedMarginEm(fontScale) ?: 0f)
    } else {
        0f
    }
    val insetEnd = if (accumulatesInset) {
        (own.marginRight?.toResolvedMarginEm(fontScale) ?: 0f) + (own.paddingRight?.toResolvedMarginEm(fontScale) ?: 0f)
    } else {
        0f
    }
    return ComputedStyle(
        declarations = effective,
        fontScale = fontScale,
        lineHeight = lineHeight,
        textIndentEm = textIndentEm,
        underline = underline,
        lineThrough = lineThrough,
        insetStartEm = parent.insetStartEm + insetStart,
        insetEndEm = parent.insetEndEm + insetEnd,
    )
}

/**
 * This element's own declared font size resolved against its parent's, or the parent's when it declares
 * none — the compounding CSS defines: `0.8em` inside `0.8em` is `0.64`, not `0.8`.
 */
private fun CssLength?.resolveFontScale(parentScale: Float): Float = when (this) {
    is CssLength.Em -> (value * parentScale).takeIf { it > 0f } ?: parentScale
    is CssLength.Percent -> (fraction * parentScale).takeIf { it > 0f } ?: parentScale
    is CssLength.Px -> (value / CssDefaultFontPx).takeIf { it > 0f } ?: parentScale
    null -> parentScale
}

/** A `line-height` length in base-em units: `em`/`%` against the element's own size, `px` against 16. */
private fun CssLength.toResolvedLineHeightEm(fontScale: Float): Float? = when (this) {
    is CssLength.Em -> (value * fontScale).takeIf { it > 0f }
    is CssLength.Percent -> (fraction * fontScale).takeIf { it > 0f }
    is CssLength.Px -> (value / CssDefaultFontPx).takeIf { it > 0f }
}

/** A `text-indent` in base-em units; a percent needs a width this parser does not have and is dropped. */
private fun CssLength.toResolvedIndentEm(fontScale: Float): Float? = when (this) {
    is CssLength.Em -> value * fontScale
    is CssLength.Px -> value / CssDefaultFontPx
    is CssLength.Percent -> null
}

/**
 * A margin or padding side in base-em units, never negative.
 *
 * A negative margin pulls content the other way, which this renderer has no way to draw, so it reads as no
 * margin at all. A percentage resolves against the containing block's *width* in CSS — a number this parser
 * does not have — so only an exact zero survives from that form.
 */
private fun CssLength.toResolvedMarginEm(fontScale: Float): Float? = when (this) {
    is CssLength.Em -> (value * fontScale).coerceAtLeast(0f)
    is CssLength.Px -> (value / CssDefaultFontPx).coerceAtLeast(0f)
    is CssLength.Percent -> 0f.takeIf { fraction == 0f }
}

/**
 * Accumulates [parseXhtmlContent]'s output one tag or text run at a time: the flattened text, the
 * [ReaderBlock]s addressing it, and the anchors found along the way. State that spans multiple calls —
 * which block is currently open, which inline spans and container elements are still open, list and
 * table position — lives here rather than in [parseXhtmlContent] itself, so that function's single
 * forward scan can stay a flat loop over tag boundaries instead of a recursive descent.
 */
private class XhtmlContentBuilder(
    /**
     * Absolute offset the builder's text starts at; see [parseXhtmlContent]'s own `baseOffset` parameter.
     */
    private val baseOffset: Long,
    /**
     * Maps a raw `src`/`href` to a container path, or null to drop the image; see [parseXhtmlContent]'s own
     * parameter of the same name.
     */
    private val resolveImageHref: (String) -> String?,
    /** The chapter's full CSS cascade; see [parseXhtmlContent]'s own parameter of the same name. */
    private val css: EpubCss,
) {
    private val text = StringBuilder()

    /**
     * Every [ReaderBlock] recorded so far, appended to by [flushBlock], [appendImage], and
     * [emitStandaloneBlock] as each block or standalone element closes. Left in recording order —
     * an inline picture is recorded mid-paragraph, before the paragraph enclosing it closes — and
     * only sorted into reading order once, by [build].
     */
    private val blocks = mutableListOf<ReaderBlock>()

    /**
     * Every `id`/`name`/`xml:id` seen so far, mapped to its absolute offset in [text]; recorded by
     * [rememberAnchors] as markup is scanned and handed out as-is, unsorted, by [build].
     */
    private val anchors = linkedMapOf<String, Long>()

    /** Offset into [text] where the block currently being built starts, or -1 when no block is open. */
    private var blockStart = -1

    /** [ReaderBlockKind] the block currently being built will be recorded as. */
    private var blockKind = ReaderBlockKind.PARAGRAPH

    /** Heading level, or list nesting depth for a list item; 0 for anything else. */
    private var blockLevel = 0

    /** Alignment for the block currently being built, from markup or the stylesheet cascade. */
    private var blockAlign: ReaderTextAlign? = null

    /** Ordinal marker text (e.g. `"3."`) for the current block when it is an ordered list item. */
    private var blockLabel: String? = null

    /**
     * Style (font scale, weight, family, line height, indent) the stylesheet cascade gives the current
     * block.
     */
    private var blockStyle: ReaderBlockStyle? = null

    /** The current block's resolved style, kept so inline spans can be emitted as deltas against it. */
    private var blockComputed: ComputedStyle? = null

    /**
     * Whether the current block gets a full blank line after it, or just a single line break; see
     * [flushBlock].
     */
    private var blockSeparatesWithBlankLine = true

    /** Table row index for the current block, when it is a table cell. */
    private var blockTableRow: Int? = null

    /** Table column index for the current block, when it is a table cell. */
    private var blockTableColumn: Int? = null

    /** Inline spans (bold, italic, links, …) collected so far for the block currently being built. */
    private val blockSpans = mutableListOf<ReaderSpan>()

    /** Inline styling elements (`<b>`, `<a>`, …) still open, innermost last. */
    private val openInline = mutableListOf<OpenSpan>()

    /**
     * Block and container elements still open, outermost first — the ancestry both a CSS lookup and an
     * image's width lookup walk.
     */
    private val openBlocks = mutableListOf<OpenElement>()
    private val openContainers = mutableListOf<OpenContainer>()
    private val hiddenElements = mutableListOf<String>()

    /** Open `<ol>`/`<ul>` contexts, innermost last, tracking ordinal position. */
    private val lists = mutableListOf<ListContext>()

    /** Open `<table>` contexts, innermost last, tracking row/column position. */
    private val tables = mutableListOf<TableContext>()

    /** Depth of nested `<pre>` elements; whitespace is written verbatim while this is above zero. */
    private var preformattedDepth = 0

    /**
     * A run of whitespace has been seen but not yet written, since markup padding must not open a block or
     * start a line by itself.
     */
    private var pendingSpace = false

    /**
     * Chapter name taken from the first heading's `title` attribute, if any; becomes
     * [XhtmlContent.headingTitle].
     */
    private var headingTitle: String? = null

    /** Pictures written into the block being built, so a wrapper holding only pictures is recognised. */
    private var blockImageCount = 0

    /** Explicit `<br>`s written into the block being built, so a blank-line paragraph is recognised. */
    private var blockLineBreakCount = 0

    private val suppressingHiddenContent: Boolean
        get() = hiddenElements.isNotEmpty()

    /**
     * Appends one text run between tags to the output, decoding its entities first.
     *
     * Outside a `<pre>`, whitespace is collapsed the way HTML collapses it: a run of whitespace is
     * held in [pendingSpace] rather than written immediately, because markup padding around a tag must
     * not open a block or start a line by itself, while a real space between two inline elements
     * arrives as its own text run and still has to survive the gap between them. Inside a `<pre>`
     * (tracked by [preformattedDepth]), the text is written exactly as given, with no collapsing at all.
     *
     * @param rawText the text run as it appeared in the markup, not yet entity-decoded.
     */
    fun appendText(rawText: String) {
        if (suppressingHiddenContent) return
        if (rawText.isEmpty()) return
        val decoded = decodeXmlEntities(rawText)
        if (preformattedDepth > 0) {
            ensureBlockOpen()
            pendingSpace = false
            text.append(decoded)
            return
        }
        decoded.forEach { char ->
            if (char.isWhitespace()) {
                pendingSpace = true
                return@forEach
            }
            if (blockStart < 0) ensureBlockOpen()
            if (pendingSpace && text.length > blockStart) text.append(' ')
            pendingSpace = false
            text.append(char)
        }
    }

    /**
     * Handles one opening (or self-closing) tag: some tags act immediately (`<br>`, `<img>`/`<image>`,
     * `<hr>`), some only push list/table tracking state, and the rest — recognized block elements or
     * inline styles — either open a span or close the previous block and open a new one shaped by
     * whatever the stylesheet cascade and markup attributes say about it.
     *
     * For an image, width and float are resolved from the same cascade the rest of the element sees:
     * linked CSS through [css], inline `style` layered on top of it, and [styleSheet] kept as the
     * legacy width fallback for older class-keyed image rules. For a recognized block element, [css] is
     * asked for the declarations that apply to the whole open-element ancestry, not just the element
     * itself, so an inherited `text-align` set on a wrapper still reaches a paragraph nested inside it;
     * markup written directly on the element (an `align` attribute, an inline `style`) still wins over
     * that cascade, the same way it would in a browser. Before an inline styling element records where
     * its span starts, any pending space is flushed first — recording the start before that flush made
     * every span begin one character early, swallowing the leading space of whatever it wrapped.
     *
     * @param tag the opening tag, already parsed by [parseTag].
     */
    fun openElement(tag: XhtmlTag) {
        if (suppressingHiddenContent) {
            if (!tag.isSelfClosing) hiddenElements += tag.name
            return
        }
        rememberAnchors(tag.attributes)
        val currentElement = openElementFor(tag)
        if (currentElement.computed.declarations.display == "none") {
            if (!tag.isSelfClosing) hiddenElements += tag.name
            return
        }
        when (tag.name) {
            "br" -> {
                ensureBlockOpen()
                pendingSpace = false
                text.append('\n')
                blockLineBreakCount += 1
                return
            }

            "img", "image" -> {
                val source = tag.attributes["src"] ?: tag.attributes["xlink:href"] ?: tag.attributes["href"]
                val href = source?.let(resolveImageHref)
                if (href != null) {
                    val imageLayout = resolveImageLayout(currentElement, openBlocks)
                    appendImage(
                        imageHref = href,
                        label = tag.attributes["alt"]?.takeIf { it.isNotBlank() },
                        aspectRatio = tag.attributes.declaredImageAspectRatio(),
                        widthPercent = imageLayout.widthPercent,
                        widthEm = imageLayout.widthEm,
                        align = imageLayout.align,
                        float = imageLayout.float,
                        style = imageLayout.style,
                    )
                }
                return
            }

            "hr" -> {
                emitStandaloneBlock(kind = ReaderBlockKind.SEPARATOR)
                return
            }

            "ol", "ul" -> {
                lists += ListContext(isOrdered = tag.name == "ol", nextOrdinal = tag.attributes.startOrdinal())
                openBlocks += currentElement
                return
            }

            "table" -> {
                tables += TableContext()
                openBlocks += currentElement
                return
            }

            "tr" -> {
                tables.lastOrNull()?.let { table ->
                    table.rowIndex += 1
                    table.columnIndex = -1
                }
                openBlocks += currentElement
                return
            }
        }

        InlineStyles[tag.name]?.let { style ->
            val href = tag.attributes["href"]
            if (style == ReaderInlineStyle.LINK && href.isNullOrBlank()) return
            ensureBlockOpen()
            flushPendingSpace()
            val inlineCssStyle = currentElement.computed.toSpanDelta(spanDeltaBase(), css)
            openInline += OpenSpan(
                name = tag.name,
                style = style,
                href = href,
                start = text.length,
                styleDelta = inlineCssStyle,
                computed = currentElement.computed,
            )
            return
        }

        if (BlockKinds[tag.name] == ReaderBlockKind.HEADING && headingTitle == null) {
            headingTitle = tag.attributes["title"]?.trim()?.takeIf(String::isNotEmpty)
        }

        val kind = BlockKinds[tag.name] ?: run {
            if (tag.name in NeutralContainers) {
                // A pure inline container's styling reaches its text as a span; a block-level wrapper's
                // reaches it baked into every block resolved inside it, so only the former needs one.
                if (tag.name in PureInlineContainers) {
                    ensureBlockOpen()
                    val delta = currentElement.computed.toSpanDelta(spanDeltaBase(), css)
                    openBlocks += currentElement
                    if (delta != null) {
                        flushPendingSpace()
                        openInline += OpenSpan(
                            name = tag.name,
                            style = null,
                            href = null,
                            start = text.length,
                            styleDelta = delta,
                            computed = currentElement.computed,
                        )
                    }
                } else {
                    openBlocks += currentElement
                    maybeOpenContainer(tag.name, currentElement.computed.toReaderBlockStyle(css))
                }
            }
            return
        }

        flushBlock()
        openBlocks += currentElement
        maybeOpenContainer(tag.name, currentElement.computed.toReaderBlockStyle(css))
        blockComputed = currentElement.computed
        blockStyle = currentElement.computed.toReaderBlockStyle(css)
        blockSeparatesWithBlankLine = currentElement.computed.separatesParagraphs()
        blockKind = kind
        blockLevel = when {
            kind == ReaderBlockKind.HEADING -> tag.name.removePrefix("h").toIntOrNull() ?: 1
            kind == ReaderBlockKind.LIST_ITEM -> lists.size.coerceAtLeast(1)
            else -> 0
        }
        blockAlign = tag.attributes.textAlign() ?: currentElement.computed.declarations.textAlign?.toReaderTextAlign()
        blockLabel = null
        if (kind == ReaderBlockKind.LIST_ITEM) {
            lists.lastOrNull()?.let { list ->
                if (list.isOrdered) {
                    blockLabel = "${list.nextOrdinal}."
                    list.nextOrdinal += 1
                }
            }
        }
        if (kind.isTableCellKind()) {
            tables.lastOrNull()?.let { table ->
                table.columnIndex += 1
                blockTableRow = table.rowIndex.coerceAtLeast(0)
                blockTableColumn = table.columnIndex
            }
        }
        if (kind == ReaderBlockKind.PREFORMATTED) preformattedDepth += 1
        ensureBlockOpen()
    }

    /**
     * Handles one closing tag: closes the innermost still-open inline span of this name if there is
     * one, pops list/table tracking, and closes (flushing) the innermost still-open block element of
     * this name if there is one. A closing tag with no matching open element — already consumed by an
     * ancestor's own close, or malformed markup with no matching open tag at all — is simply ignored.
     *
     * @param name the closing tag's element name (already lowercased by the caller).
     */
    fun closeElement(name: String) {
        val lowered = name.lowercase()
        if (hiddenElements.isNotEmpty()) {
            hiddenElements.indexOfLast { it == lowered }.takeIf { it >= 0 }?.let { hiddenElements.removeAt(it) }
            return
        }
        openInline.indexOfLast { it.name == lowered }.takeIf { it >= 0 }?.let { spanIndex ->
            val span = openInline.removeAt(spanIndex)
            if (text.length > span.start) {
                blockSpans += ReaderSpan(
                    range = TextRange(baseOffset + span.start, baseOffset + text.length),
                    style = span.style,
                    href = span.href,
                    styleDelta = span.styleDelta?.takeIf { !it.isEmpty() },
                )
            }
        }

        when (lowered) {
            "ol", "ul" -> lists.removeLastOrNull()
            "table" -> tables.removeLastOrNull()
        }
        if (lowered == "pre" && preformattedDepth > 0) preformattedDepth -= 1

        val blockIndex = openBlocks.indexOfLast { it.name == lowered }
        if (blockIndex >= 0) {
            openBlocks.removeAt(blockIndex)
            if (lowered in BlockKinds) flushBlock()
        }
        closeContainer(lowered)
    }

    /**
     * Finishes the chapter: flushes whatever block is still open, trims the trailing blank line(s) a
     * closing block would otherwise leave dangling past the end of the readable content, and returns
     * the accumulated [XhtmlContent].
     *
     * Trimming stops at the end of the last recorded block's range rather than at the very end of the
     * text, because the separator written after that block is exactly the blank line that would
     * otherwise show up as trailing whitespace at the end of a chapter. The returned blocks are sorted
     * by start offset rather than left in the order they were recorded, because an inline picture is
     * recorded the moment it is written — mid-paragraph — while the paragraph enclosing it is only
     * recorded once that paragraph's own block closes; putting the list back into reading order here is
     * what lets every downstream consumer assume blocks already come in that order.
     *
     * @return the accumulated text, blocks (in reading order), anchors, and heading title for this chapter.
     */
    fun build(): XhtmlContent {
        flushBlock()
        while (openContainers.isNotEmpty()) closeContainer(openContainers.last().name)
        val minimumLength = blocks.maxOfOrNull { block -> (block.range.end - baseOffset).toInt() } ?: 0
        while (text.length > minimumLength && text.isNotEmpty() && text.last() == '\n') {
            text.deleteAt(text.length - 1)
        }
        return XhtmlContent(
            text = text.toString(),
            blocks = blocks.sortedBy { block -> block.range.start },
            anchors = anchors.toMap(),
            headingTitle = headingTitle,
        )
    }

    /**
     * Records [attributes]' `id`/`name`/`xml:id`, if any, as pointing at the current write position.
     * The first anchor recorded for a given name wins over a later duplicate of the same name.
     */
    private fun rememberAnchors(attributes: Map<String, String>) {
        val absoluteOffset = baseOffset + text.length
        listOfNotNull(attributes["id"], attributes["name"], attributes["xml:id"])
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { anchor -> if (anchor !in anchors) anchors[anchor] = absoluteOffset }
    }

    /**
     * Marks a block as started at the current write position, if one is not already open.
     *
     * A block opened here rather than by a block tag — text set directly inside `<body>` or a wrapper —
     * still sits inside whatever styling its ancestors resolved, so it takes the inherited-visual slice
     * of the nearest block-level ancestor's computed style as its own. Pure inline containers are skipped
     * as that ancestor: their styling reaches the text as a span, and taking it here too would apply it
     * twice.
     */
    private fun ensureBlockOpen() {
        if (blockStart >= 0) return
        blockStart = text.length
        if (blockStyle == null) {
            openBlocks.lastOrNull { it.name !in PureInlineContainers }?.computed?.let { context ->
                blockComputed = context
                blockStyle = context.toInheritedReaderBlockStyle(css)
            }
        }
    }

    /** Builds one [OpenElement] with its style resolved incrementally from the innermost open ancestor. */
    private fun openElementFor(tag: XhtmlTag): OpenElement {
        val classNames = tag.classNames()
        val id = tag.attributes["id"]
        val inline = tag.attributes.inlineCssDeclarations()
        val ancestry = openBlocks.map(OpenElement::cssElement) + CssElement(tag = tag.name, classes = classNames.toSet(), id = id)
        val computed = resolveComputedStyle(
            parent = openBlocks.lastOrNull()?.computed ?: ComputedStyle.Root,
            css = css,
            ancestry = ancestry,
            inline = inline,
            accumulatesInset = tag.name.accumulatesInset(),
        )
        return OpenElement(tag.name, classNames, id, inline, computed)
    }

    /** The style the next inline span should be a delta against: the innermost span, else the block. */
    private fun spanDeltaBase(): ComputedStyle =
        openInline.lastOrNull()?.computed
            ?: blockComputed
            ?: openBlocks.lastOrNull()?.computed
            ?: ComputedStyle.Root

    /**
     * Writes the held space from [pendingSpace], if any, unless it would be the very first character of the
     * open block.
     */
    private fun flushPendingSpace() {
        if (!pendingSpace) return
        if (blockStart >= 0 && text.length > blockStart) text.append(' ')
        pendingSpace = false
    }

    /**
     * Write a picture where it was written: ordinarily into the line being built, not between two
     * blocks.
     *
     * `<img>` is inline content in HTML, so an ordinary inline glyph or icon stays in its paragraph.
     * A floated image is likewise preserved inline here; the publisher float itself is carried on the
     * image block for a later renderer decision. A picture that turns out to be the only thing in its
     * block is still recognised in [flushBlock], and stands on a line of its own there, the way a plate
     * does.
     */
    private fun appendImage(
        imageHref: String,
        label: String?,
        aspectRatio: Float?,
        widthPercent: Float?,
        widthEm: Float?,
        align: ReaderTextAlign = ReaderTextAlign.CENTER,
        float: ReaderFloat? = null,
        style: ReaderBlockStyle? = null,
    ) {
        ensureBlockOpen()
        flushPendingSpace()
        val start = text.length
        text.append(ReaderObjectReplacementChar)
        blockImageCount += 1
        blocks += ReaderBlock(
            kind = ReaderBlockKind.IMAGE,
            range = TextRange(baseOffset + start, baseOffset + text.length),
            imageHref = imageHref,
            label = label,
            align = align,
            imageAspectRatio = aspectRatio,
            imageWidthPercent = widthPercent,
            imageWidthEm = widthEm,
            float = float,
            style = style?.takeIf { !it.isEmpty() },
        )
    }

    /**
     * Writes a self-contained block that is not built up from surrounding text — currently just a rule
     * (`<hr>`) — as one character plus its own [ReaderBlock], flushing whatever paragraph was already
     * open first.
     *
     * The block is given a real, one-character range rather than a zero-width one because a zero-width
     * range would fall through a page-range filter exactly at a page boundary. The single newline
     * written after it ends the block's own line; no blank line is added before it, because the
     * preceding paragraph's own flush already wrote one — the same spacing a browser gives `<hr>`,
     * rather than the roughly doubled gap two newlines from both sides would have produced.
     *
     * @param kind block kind to record.
     */
    private fun emitStandaloneBlock(kind: ReaderBlockKind) {
        flushBlock()
        pendingSpace = false
        val start = text.length
        text.append(ReaderObjectReplacementChar)
        blocks += ReaderBlock(
            kind = kind,
            range = TextRange(baseOffset + start, baseOffset + text.length),
        )
        text.append('\n')
    }

    /**
     * Closes out whatever block is currently open (if any) and writes the separator that follows it.
     *
     * Trailing block padding (spaces, tabs, line breaks) is trimmed off the end first; a block that
     * turns out to be completely empty after trimming — its start and end offset now equal — records
     * no [ReaderBlock] at all, and its open spans are discarded rather than kept for an empty range. A
     * block that held nothing but pictures (an image-only wrapper `<div>`, for instance) is treated the
     * same way: there is no prose to record, so no paragraph block is written, and each picture already
     * written keeps its own line rather than being folded into an empty paragraph drawn around it.
     * Otherwise the accumulated [ReaderBlock] is recorded with whatever kind, level, spans, alignment,
     * label, table position, and style were set while it was open. The separator written afterward is a
     * blank line (`"\n\n"`) or a single line break (`"\n"`) depending on [blockSeparatesWithBlankLine] —
     * how one paragraph is told apart from the next on the page. A stylesheet rule of `margin: 0` on a
     * paragraph is this reader's signal that paragraphs should run on with no gap between them (their
     * own first-line indent is what separates them instead); giving those a blank line anyway would
     * have spread the page out to roughly twice the length the book intended.
     */
    private fun flushBlock() {
        val start = blockStart
        val separatesWithBlankLine = blockSeparatesWithBlankLine
        pendingSpace = false
        resetOpenSpans()
        val imageCount = blockImageCount
        blockImageCount = 0
        val lineBreakCount = blockLineBreakCount
        blockLineBreakCount = 0
        if (start < 0) {
            resetBlockAttributes()
            return
        }
        blockStart = -1
        while (text.length > start && text.last().isBlockPadding()) text.deleteAt(text.length - 1)
        if (text.length == start) {
            // A paragraph holding nothing but explicit line breaks is a *blank-line* paragraph, not an
            // empty one: `<p><br/></p>` draws as one empty line in a browser, and it is how these books
            // put the space between a chapter-title box and its prose. Dropping it glued the two
            // together. The breaks are kept as the paragraph's own content so each draws as one line of
            // the paragraph's line height, exactly the height the book set the space in.
            if (lineBreakCount > 0) {
                repeat(lineBreakCount) { text.append('\n') }
            } else {
                blockSpans.clear()
                resetBlockAttributes()
                return
            }
        }

        if (imageCount > 0 && text.substring(start, text.length).isBlankIgnoringObjects()) {
            blockSpans.clear()
            resetBlockAttributes()
            text.append('\n')
            return
        }

        blocks += ReaderBlock(
            kind = blockKind,
            range = TextRange(baseOffset + start, baseOffset + text.length),
            level = blockLevel,
            spans = blockSpans.filter { span -> span.range.start < baseOffset + text.length }.toList(),
            align = blockAlign,
            label = blockLabel,
            tableRow = blockTableRow,
            tableColumn = blockTableColumn,
            style = blockStyle?.takeIf { !it.isEmpty() },
        )
        blockSpans.clear()
        resetBlockAttributes()
        text.append(if (separatesWithBlankLine) "\n\n" else "\n")
    }

    /**
     * Closes every still-open inline span at the block's own end, so unclosed inline markup (an
     * unterminated `<b>`, say) ends with the block instead of leaking into whatever follows it.
     */
    private fun resetOpenSpans() {
        if (openInline.isEmpty()) return
        openInline.asReversed().forEach { span ->
            if (text.length > span.start) {
                blockSpans += ReaderSpan(
                    range = TextRange(baseOffset + span.start, baseOffset + text.length),
                    style = span.style,
                    href = span.href,
                    styleDelta = span.styleDelta?.takeIf { !it.isEmpty() },
                )
            }
        }
        openInline.clear()
    }

    /** Resets all per-block state to its default, ready for the next block to be built. */
    private fun resetBlockAttributes() {
        blockKind = ReaderBlockKind.PARAGRAPH
        blockLevel = 0
        blockAlign = null
        blockLabel = null
        blockTableRow = null
        blockTableColumn = null
        blockStyle = null
        blockComputed = null
        blockSeparatesWithBlankLine = true
    }

    private fun maybeOpenContainer(name: String, style: ReaderBlockStyle?) {
        if (style == null || style.isEmpty() || !style.hasVisualContainerData() || name in PureInlineContainers) return
        openContainers += OpenContainer(
            name = name,
            start = text.length,
            style = style,
            depth = openContainers.size + 1,
            isPageContainer = name == "html" || name == "body",
        )
    }

    private fun closeContainer(name: String) {
        val index = openContainers.indexOfLast { it.name == name }
        if (index < 0) return
        val container = openContainers.removeAt(index)
        val start = baseOffset + container.start
        var endIndex = text.length
        while (endIndex > container.start && text[endIndex - 1].isBlockPadding()) endIndex -= 1
        val end = baseOffset + endIndex
        if (end <= start) return
        // A styled block element that wrapped exactly one text run already carries this whole style on
        // that leaf block — its box is the leaf's box. Recording a CONTAINER twin over the same range
        // with the same style forced every renderer to re-discover the duplication (by comparing ranges
        // and styles) just to avoid double-counting its spacing; suppressing the twin at the single
        // point it would be created keeps the invariant structural: a CONTAINER is always a genuine
        // wrapper. Page containers are exempt — html/body must always be recorded, since page margins
        // and the page background are read off them.
        val range = TextRange(start, end)
        if (!container.isPageContainer &&
            blocks.any { block ->
                block.kind != ReaderBlockKind.CONTAINER && block.range == range && block.style == container.style
            }
        ) {
            return
        }
        val block = ReaderBlock(
            kind = ReaderBlockKind.CONTAINER,
            range = range,
            level = container.depth,
            style = container.style.takeIf { !it.isEmpty() },
            isPageContainer = container.isPageContainer,
        )
        if (blocks.lastOrNull() != block) blocks += block
    }

    private data class OpenContainer(
        val name: String,
        val start: Int,
        val style: ReaderBlockStyle,
        val depth: Int,
        val isPageContainer: Boolean,
    )
}

/**
 * An inline styling element (`<b>`, `<a>`, …) currently open, remembered until its closing tag turns it
 * into a [ReaderSpan].
 */
private class OpenSpan(
    /** Lowercased tag name, matched against the closing tag that ends this span. */
    val name: String,
    /** Inline style this element applies once closed. */
    val style: ReaderInlineStyle?,
    /** Link target, for [ReaderInlineStyle.LINK]; null otherwise. */
    val href: String?,
    /** Offset into the builder's text where this span starts. */
    val start: Int,
    /** Extra CSS-derived styling carried by this span, as a delta against its enclosing context. */
    val styleDelta: ReaderSpanStyle? = null,
    /** The element's resolved style, the delta base for any span nested inside this one. */
    val computed: ComputedStyle = ComputedStyle.Root,
)

/** Tracks position inside one open `<ol>`/`<ul>`. */
private class ListContext(
    /** True for `<ol>`, false for `<ul>` — only an ordered list gives its items a numeric label. */
    val isOrdered: Boolean,
    /** The ordinal the next `<li>` should be labeled with, incremented after each one. */
    var nextOrdinal: Int,
)

/** Tracks position inside one open `<table>`. */
private class TableContext {
    /** Index of the row currently open, incremented on each `<tr>`; -1 before the first row. */
    var rowIndex: Int = -1

    /**
     * Index of the cell currently open within the row, incremented on each `<td>`/`<th>`; reset to -1 by
     * each `<tr>`.
     */
    var columnIndex: Int = -1
}

/** Whether this character is whitespace [XhtmlContentBuilder] trims from a block's trailing edge. */
private fun Char.isBlockPadding(): Boolean = this == ' ' || this == '\n' || this == '\t' || this == '\r'

/**
 * Whether this kind is one of the two table-cell kinds, which carry a row/column position the others do
 * not.
 */
private fun ReaderBlockKind.isTableCellKind(): Boolean =
    this == ReaderBlockKind.TABLE_CELL || this == ReaderBlockKind.TABLE_HEADER_CELL

/** This tag's `class` attribute, split on whitespace into its individual class names. */
private fun XhtmlTag.classNames(): List<String> =
    attributes["class"].orEmpty().split(Regex("""\s+""")).map(String::trim).filter(String::isNotEmpty)

/** An `<ol>`'s `start` attribute as the first ordinal its items should be labeled with, defaulting to 1. */
private fun Map<String, String>.startOrdinal(): Int = this["start"]?.toIntOrNull() ?: 1

/** Inline `style` declarations on this tag, parsed with the same declaration reader as linked CSS. */
private fun Map<String, String>.inlineCssDeclarations(): CssDeclarations =
    this["style"]?.let(::parseCssDeclarations) ?: CssDeclarations.Empty

/**
 * Whether this element's own inline-start/end margin and padding join the inset its descendants are laid
 * out with. Block-level wrappers and blocks do; inline elements do not; `html`/`body` do not either, since
 * their spacing becomes the reader's page margin instead of a per-paragraph inset.
 */
private fun String.accumulatesInset(): Boolean = when {
    this == "html" || this == "body" -> false
    this in PureInlineContainers -> false
    this in BlockKinds -> true
    this in NeutralContainers -> true
    this == "ol" || this == "ul" || this == "table" || this == "tr" -> true
    else -> false
}

private fun CssLength.toCssWidthOrNull(): CssWidth? = when (this) {
    is CssLength.Percent -> fraction.takeIf { it > 0f }?.let(CssWidth::Percent)
    is CssLength.Em -> value.takeIf { it > 0f }?.let(CssWidth::Em)
    else -> null
}

private fun resolveDeclaredImageWidth(ownWidth: CssWidth?, ancestorWidth: CssWidth?): CssWidth? = when (ownWidth) {
    is CssWidth.Em -> ownWidth
    is CssWidth.Percent -> when (ancestorWidth) {
        is CssWidth.Em -> CssWidth.Em(ancestorWidth.value * ownWidth.fraction)
        is CssWidth.Percent -> CssWidth.Percent(ancestorWidth.fraction * ownWidth.fraction)
        null -> ownWidth
    }
    null -> ancestorWidth
}

private fun CssDeclarations.floatOrNull(): ReaderFloat? = when (float) {
    "left" -> ReaderFloat.START
    "right" -> ReaderFloat.END
    else -> null
}

private data class ResolvedImageLayout(
    val widthPercent: Float? = null,
    val widthEm: Float? = null,
    val align: ReaderTextAlign = ReaderTextAlign.CENTER,
    val float: ReaderFloat? = null,
    val style: ReaderBlockStyle? = null,
)

private fun resolveImageLayout(
    current: OpenElement,
    openBlocks: List<OpenElement>,
): ResolvedImageLayout {
    val ownDeclarations = current.computed.declarations
    val ancestorDeclarations = openBlocks.asReversed().map { element -> element.computed.declarations }
    val ancestorWidth = ancestorDeclarations.firstNotNullOfOrNull { it.width?.toCssWidthOrNull() }
    val width = resolveDeclaredImageWidth(
        ownWidth = ownDeclarations.width?.toCssWidthOrNull(),
        ancestorWidth = ancestorWidth,
    )
    val float = (sequenceOf(ownDeclarations) + ancestorDeclarations.asSequence())
        .mapNotNull(CssDeclarations::floatOrNull)
        .firstOrNull()
    // A float claims an edge and wins outright. Otherwise a deliberate placement — the inherited
    // `text-align` reaching the image being `center` or `right` — is honored; `left`/`justify` are how
    // a book styles its *prose* (body/p defaults the image merely inherits), and reading systems still
    // center a plate under those, so they fall through to the CENTER default rather than dragging the
    // picture to the margin.
    val inheritedAlign = ownDeclarations.textAlign?.toReaderTextAlign()
    val align = float?.toTextAlign()
        ?: inheritedAlign?.takeIf { it == ReaderTextAlign.CENTER || it == ReaderTextAlign.END }
        ?: ReaderTextAlign.CENTER
    return ResolvedImageLayout(
        widthPercent = (width as? CssWidth.Percent)?.fraction,
        widthEm = (width as? CssWidth.Em)?.value,
        align = align,
        float = float,
        style = ownDeclarations.toReaderImageStyle(),
    )
}

/**
 * A span's styling as the *difference* between its own resolved style and [base], the style of whatever
 * encloses it — the innermost open span, or the block.
 *
 * A delta is the only shape a span can safely carry here. The renderer nests span styles the way Compose
 * nests them: an `em` font size multiplies whatever size is already in force at that position. A span
 * carrying its full inherited style re-applied everything its block already applied — a `0.9em` wrapper's
 * text came out at `0.81`. [ReaderSpanStyle] makes that structural: absolute lengths cannot even be
 * stated on a span.
 *
 * @receiver the span element's resolved style.
 * @param base the enclosing context's resolved style.
 * @return the properties that actually differ, or null when nothing does.
 */
private fun ComputedStyle.toSpanDelta(base: ComputedStyle, css: EpubCss): ReaderSpanStyle? {
    fun <T> changed(value: T?, baseValue: T?): T? = value?.takeIf { it != baseValue }
    val own = declarations
    val baseDeclarations = base.declarations
    return ReaderSpanStyle(
        fontScale = (fontScale / base.fontScale).takeIf { ratio -> abs(ratio - 1f) > FontScaleRatioEpsilon },
        bold = changed(own.fontWeight?.toBoldOrNull(), baseDeclarations.fontWeight?.toBoldOrNull()),
        italic = changed(own.fontStyle?.toItalicFlag(), baseDeclarations.fontStyle?.toItalicFlag()),
        fontFamily = changed(own.fontFamily?.toReaderFontFamily(), baseDeclarations.fontFamily?.toReaderFontFamily()),
        fontFamilyName = changed(own.fontFamily?.toPublisherFontFamilyName(), baseDeclarations.fontFamily?.toPublisherFontFamilyName()),
        fontHref = changed(css.resolvedFontHref(own.fontFamily), css.resolvedFontHref(baseDeclarations.fontFamily)),
        foregroundColor = changed(own.color?.toReaderColorOrNull(), baseDeclarations.color?.toReaderColorOrNull()),
        underline = changed(underline, base.underline),
        lineThrough = changed(lineThrough, base.lineThrough),
    ).takeIf { !it.isEmpty() }
}

/** How far from exactly 1 a span's font-scale ratio must be before it is worth emitting at all. */
private const val FontScaleRatioEpsilon = 0.001f

private fun CssDeclarations.toReaderImageStyle(): ReaderBlockStyle? = ReaderBlockStyle(
    foregroundColor = color?.toReaderColorOrNull(),
    boxStyle = toReaderBoxStyle(),
).takeIf { !it.isEmpty() }

/**
 * Width divided by height, from the `width`/`height` attributes or an inline `style`, when the markup
 * declares both as plain pixel numbers. A `%` or missing dimension carries no real aspect ratio, so it
 * is left null rather than guessed; the real pixels are sniffed from the image bytes instead.
 */
private fun Map<String, String>.declaredImageAspectRatio(): Float? {
    val declaredWidth = this["width"]?.toPixelValue() ?: this["style"]?.let { cssPixelDimension(it, "width") }
    val declaredHeight = this["height"]?.toPixelValue() ?: this["style"]?.let { cssPixelDimension(it, "height") }
    if (declaredWidth == null || declaredHeight == null || declaredWidth <= 0f || declaredHeight <= 0f) return null
    return declaredWidth / declaredHeight
}

/**
 * This attribute value as a plain pixel number, or null if it is empty or carries a unit letter (e.g.
 * `"100%"`, `"2em"`) rather than a bare number.
 */
private fun String.toPixelValue(): Float? = trim().takeIf { it.isNotEmpty() && it.none(Char::isLetter) }?.toFloatOrNull()

/**
 * [property]'s pixel value out of an inline `style` attribute, or null if [property] is absent or not
 * declared in `px`.
 *
 * @param style raw inline `style` attribute text.
 * @param property CSS property name to look for, e.g. `"width"`.
 */
private fun cssPixelDimension(style: String, property: String): Float? =
    Regex("""$property\s*:\s*([0-9.]+)px""").find(style)?.groupValues?.get(1)?.toFloatOrNull()

/**
 * This tag's alignment from its `align` attribute or an inline `style`'s `text-align`, or null if neither
 * declares a recognized value.
 */
private fun Map<String, String>.textAlign(): ReaderTextAlign? {
    val declared = this["align"] ?: this["style"]?.let { style ->
        TextAlignRegex.find(style)?.groupValues?.get(1)
    } ?: return null
    return when (declared.trim().lowercase()) {
        "center" -> ReaderTextAlign.CENTER
        "right", "end" -> ReaderTextAlign.END
        "justify" -> ReaderTextAlign.JUSTIFY
        "left", "start" -> ReaderTextAlign.START
        else -> null
    }
}

/**
 * Decodes XML/HTML character references in [value]: numeric (`&#160;`, `&#x1F600;`) and the named
 * entities in [NamedEntities]. An entity reference longer than [MaxEntityLength], with no closing `;`,
 * or naming an entity not in [NamedEntities] is left in the output exactly as written rather than
 * dropped or guessed at — malformed or unrecognized markup should not silently eat the text around it.
 *
 * @param value raw text that may contain entity references.
 * @return [value] with every recognized entity replaced by the character(s) it represents.
 */
internal fun decodeXmlEntities(value: String): String {
    if ('&' !in value) return value
    val out = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char != '&') {
            out.append(char)
            index += 1
            continue
        }
        val end = value.indexOf(';', index + 1)
        if (end < 0 || end - index > MaxEntityLength) {
            out.append(char)
            index += 1
            continue
        }
        val body = value.substring(index + 1, end)
        val replacement = when {
            body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(16)?.toCharsOrNull()
            body.startsWith("#") -> body.drop(1).toIntOrNull()?.toCharsOrNull()
            else -> NamedEntities[body]
        }
        if (replacement == null) {
            out.append(char)
            index += 1
            continue
        }
        out.append(replacement)
        index = end + 1
    }
    return out.toString()
}

/**
 * This Unicode code point as a `String`, using a UTF-16 surrogate pair above the BMP, or null if it is out
 * of range or itself an unpaired surrogate value.
 */
private fun Int.toCharsOrNull(): String? = when {
    this <= 0 || this > 0x10FFFF -> null
    this in 0xD800..0xDFFF -> null
    this <= 0xFFFF -> toChar().toString()
    else -> {
        val value = this - 0x10000
        charArrayOf(
            (0xD800 + (value shr 10)).toChar(),
            (0xDC00 + (value and 0x3FF)).toChar(),
        ).concatToString()
    }
}

/** Matches one `name="value"` or `name='value'` attribute pair inside a tag body. */
private val TagAttributeRegex = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

/** Captures an inline `style` attribute's `text-align` value. */
private val TextAlignRegex = Regex("""text-align\s*:\s*([a-zA-Z]+)""")

/**
 * Longest entity reference (name or numeric, between `&` and `;`) [decodeXmlEntities] will try to resolve,
 * before giving up and leaving the `&` as literal text.
 */
private const val MaxEntityLength = 12

/**
 * Element names whose entire body is skipped as unreadable — script code, CSS source text, and page
 * metadata. `svg` is deliberately not included, even though it is not a recognized block or inline tag
 * either; see [parseXhtmlContent] for why skipping it the same way would be wrong.
 */
private val SkippedBodyElements = setOf("script", "style", "head", "title")

/**
 * Elements that are neither a recognized block ([BlockKinds]) nor an inline style ([InlineStyles]) but
 * are still pushed onto the open-element ancestry, so their own class or id can still be matched by a
 * CSS rule targeting a descendant (`.quotebox p`), even though opening one of these does not, by
 * itself, start a new block.
 */
private val NeutralContainers = setOf(
    "html", "body", "span", "font", "small", "big", "label", "tbody", "thead", "tfoot",
    "colgroup", "col", "nav", "header", "footer", "main", "aside", "figure", "dl",
)

private val PureInlineContainers = setOf("span", "font", "small", "big", "label")

/**
 * Recognized block-level elements, mapped to the [ReaderBlockKind] each becomes; anything else falls
 * through to [NeutralContainers] or is ignored outright.
 */
private val BlockKinds: Map<String, ReaderBlockKind> = mapOf(
    "p" to ReaderBlockKind.PARAGRAPH,
    "div" to ReaderBlockKind.PARAGRAPH,
    "section" to ReaderBlockKind.PARAGRAPH,
    "article" to ReaderBlockKind.PARAGRAPH,
    "center" to ReaderBlockKind.PARAGRAPH,
    "figcaption" to ReaderBlockKind.PARAGRAPH,
    "dd" to ReaderBlockKind.PARAGRAPH,
    "dt" to ReaderBlockKind.PARAGRAPH,
    "h1" to ReaderBlockKind.HEADING,
    "h2" to ReaderBlockKind.HEADING,
    "h3" to ReaderBlockKind.HEADING,
    "h4" to ReaderBlockKind.HEADING,
    "h5" to ReaderBlockKind.HEADING,
    "h6" to ReaderBlockKind.HEADING,
    "blockquote" to ReaderBlockKind.QUOTE,
    "li" to ReaderBlockKind.LIST_ITEM,
    "pre" to ReaderBlockKind.PREFORMATTED,
    "td" to ReaderBlockKind.TABLE_CELL,
    "th" to ReaderBlockKind.TABLE_HEADER_CELL,
)

/** Recognized inline styling elements, mapped to the [ReaderInlineStyle] span each becomes when closed. */
private val InlineStyles: Map<String, ReaderInlineStyle> = mapOf(
    "a" to ReaderInlineStyle.LINK,
    "b" to ReaderInlineStyle.BOLD,
    "strong" to ReaderInlineStyle.BOLD,
    "i" to ReaderInlineStyle.ITALIC,
    "em" to ReaderInlineStyle.ITALIC,
    "cite" to ReaderInlineStyle.ITALIC,
    "dfn" to ReaderInlineStyle.ITALIC,
    "var" to ReaderInlineStyle.ITALIC,
    "u" to ReaderInlineStyle.UNDERLINE,
    "ins" to ReaderInlineStyle.UNDERLINE,
    "s" to ReaderInlineStyle.STRIKETHROUGH,
    "strike" to ReaderInlineStyle.STRIKETHROUGH,
    "del" to ReaderInlineStyle.STRIKETHROUGH,
    "code" to ReaderInlineStyle.MONOSPACE,
    "kbd" to ReaderInlineStyle.MONOSPACE,
    "samp" to ReaderInlineStyle.MONOSPACE,
    "tt" to ReaderInlineStyle.MONOSPACE,
    "sup" to ReaderInlineStyle.SUPERSCRIPT,
    "sub" to ReaderInlineStyle.SUBSCRIPT,
)

/**
 * Named XML/HTML character references [decodeXmlEntities] resolves, beyond the numeric `&#…;`/`&#x…;`
 * forms it handles directly.
 */
private val NamedEntities: Map<String, String> = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
    "shy" to "­", "ndash" to "–", "mdash" to "—", "horbar" to "―",
    "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
    "lsaquo" to "‹", "rsaquo" to "›", "laquo" to "«", "raquo" to "»",
    "hellip" to "…", "middot" to "·", "bull" to "•", "dagger" to "†", "Dagger" to "‡",
    "prime" to "′", "Prime" to "″", "permil" to "‰", "para" to "¶", "sect" to "§",
    "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "plusmn" to "±",
    "times" to "×", "divide" to "÷", "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
    "sup1" to "¹", "sup2" to "²", "sup3" to "³", "micro" to "µ",
    "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "curren" to "¤",
    "larr" to "←", "uarr" to "↑", "rarr" to "→", "darr" to "↓", "harr" to "↔",
    "hearts" to "♥", "diams" to "♦", "clubs" to "♣", "spades" to "♠",
    "iexcl" to "¡", "iquest" to "¿", "ordf" to "ª", "ordm" to "º", "not" to "¬",
    "brvbar" to "¦", "uml" to "¨", "macr" to "¯", "acute" to "´", "cedil" to "¸",
)

/**
 * A block's whole resolved style as the renderer's model carries it — every length already in base-em
 * units, so no consumer re-interprets a raw declaration.
 */
private fun ComputedStyle.toReaderBlockStyle(css: EpubCss): ReaderBlockStyle = ReaderBlockStyle(
    fontScale = fontScale.takeIf { it != 1f },
    bold = declarations.fontWeight?.toBoldOrNull(),
    italic = declarations.fontStyle?.toItalicFlag(),
    fontFamily = declarations.fontFamily?.toReaderFontFamily(),
    fontFamilyName = declarations.fontFamily?.toPublisherFontFamilyName(),
    fontHref = css.resolvedFontHref(declarations.fontFamily),
    lineHeightScale = lineHeightBaseEm(),
    textIndentEm = textIndentEm,
    marginTopEm = declarations.marginTop?.toResolvedMarginEm(fontScale),
    marginBottomEm = declarations.marginBottom?.toResolvedMarginEm(fontScale),
    marginStartEm = declarations.marginLeft?.toResolvedMarginEm(fontScale),
    marginEndEm = declarations.marginRight?.toResolvedMarginEm(fontScale),
    paddingTopEm = declarations.paddingTop?.toResolvedMarginEm(fontScale),
    paddingBottomEm = declarations.paddingBottom?.toResolvedMarginEm(fontScale),
    paddingStartEm = declarations.paddingLeft?.toResolvedMarginEm(fontScale),
    paddingEndEm = declarations.paddingRight?.toResolvedMarginEm(fontScale),
    insetStartEm = insetStartEm.takeIf { it > 0f },
    insetEndEm = insetEndEm.takeIf { it > 0f },
    underline = underline,
    lineThrough = lineThrough,
    foregroundColor = declarations.color?.toReaderColorOrNull(),
    boxStyle = declarations.toReaderBoxStyle(),
)

/**
 * Only the inherited-visual slice of this style — what a block opened implicitly (text set directly
 * inside a wrapper, with no block tag of its own) takes from its surroundings. The wrapper's own box
 * (margins, padding, borders) stays with the wrapper; carrying it here would give every implicit
 * paragraph the wrapper's margins.
 */
private fun ComputedStyle.toInheritedReaderBlockStyle(css: EpubCss): ReaderBlockStyle? = ReaderBlockStyle(
    fontScale = fontScale.takeIf { it != 1f },
    bold = declarations.fontWeight?.toBoldOrNull(),
    italic = declarations.fontStyle?.toItalicFlag(),
    fontFamily = declarations.fontFamily?.toReaderFontFamily(),
    fontFamilyName = declarations.fontFamily?.toPublisherFontFamilyName(),
    fontHref = css.resolvedFontHref(declarations.fontFamily),
    lineHeightScale = lineHeightBaseEm(),
    textIndentEm = textIndentEm,
    insetStartEm = insetStartEm.takeIf { it > 0f },
    insetEndEm = insetEndEm.takeIf { it > 0f },
    underline = underline,
    lineThrough = lineThrough,
    foregroundColor = declarations.color?.toReaderColorOrNull(),
).takeIf { !it.isEmpty() }

/** Whether this `font-style` value asks for italic type. */
private fun String.toItalicFlag(): Boolean = this == "italic" || this == "oblique"

/**
 * Whether this `text-decoration` value asks for [decoration], or null when the value says nothing about
 * decoration at all.
 *
 * `none` is an answer, not silence: a book writing `text-decoration: none` on its links is saying they
 * carry no underline, and reading that as "unstated" leaves the reader's own underline on. A value naming
 * some other decoration (`overline`, say) leaves [decoration] unstated rather than switching it off, since
 * the book was talking about something else.
 *
 * @receiver the raw declaration value, already lowercased by the declaration parser.
 * @param decoration the decoration being asked about, e.g. `"underline"`.
 * @return true when the value asks for it, false when the value is `none`, null otherwise.
 */
private fun String.toDecorationFlag(decoration: String): Boolean? = when {
    contains(decoration) -> true
    trim() == "none" -> false
    else -> null
}

/**
 * This `font-weight` value as a bold flag, or null if it is not a recognized keyword or number.
 *
 * A numeric weight reads as bold from [BoldWeightThreshold] (600) up, which is where the CSS weight
 * scale places semi-bold.
 */
private fun String.toBoldOrNull(): Boolean? = when {
    this == "bold" || this == "bolder" -> true
    this == "normal" || this == "lighter" -> false
    toIntOrNull() != null -> toInt() >= BoldWeightThreshold
    else -> null
}

/**
 * The generic family a declaration asks for. A book naming its own bundled face gets the reader's
 * font instead: that face is not installed here, and guessing a substitute would change the page for
 * no reason the reader asked for.
 */
private fun String.toReaderFontFamily(): ReaderFontFamily? = when {
    contains("monospace") || contains("courier") -> ReaderFontFamily.MONOSPACE
    contains("sans-serif") -> ReaderFontFamily.SANS_SERIF
    contains("serif") -> ReaderFontFamily.SERIF
    else -> null
}

private fun String.toPublisherFontFamilyName(): String? =
    split(',').map(String::trim).map { it.removeSurrounding("\"").removeSurrounding("'") }
        .firstOrNull { family ->
            family.isNotEmpty() &&
                !family.equals("serif", ignoreCase = true) &&
                !family.equals("sans-serif", ignoreCase = true) &&
                !family.equals("monospace", ignoreCase = true) &&
                !family.equals("cursive", ignoreCase = true) &&
                !family.equals("fantasy", ignoreCase = true) &&
                !family.equals("system-ui", ignoreCase = true)
        }

private fun ReaderFloat.toTextAlign(): ReaderTextAlign = when (this) {
    ReaderFloat.START -> ReaderTextAlign.START
    ReaderFloat.END -> ReaderTextAlign.END
}

/**
 * This CSS `text-align` keyword as a [ReaderTextAlign], or null if it is not one of the recognized values.
 */
private fun String.toReaderTextAlign(): ReaderTextAlign? = when (this) {
    "center" -> ReaderTextAlign.CENTER
    "right", "end" -> ReaderTextAlign.END
    "justify" -> ReaderTextAlign.JUSTIFY
    "left", "start" -> ReaderTextAlign.START
    else -> null
}

private fun CssDeclarations.toReaderBoxStyle(): ReaderBoxStyle? = ReaderBoxStyle(
    backgroundColor = backgroundColor?.toReaderColorOrNull(),
    borderTop = borderTop.toReaderBorderOrNull(),
    borderRight = borderRight.toReaderBorderOrNull(),
    borderBottom = borderBottom.toReaderBorderOrNull(),
    borderLeft = borderLeft.toReaderBorderOrNull(),
    borderRadiusPercent = borderRadius.toBorderRadiusPercentOrNull(),
).takeUnless(ReaderBoxStyle::isEmpty)

private fun CssBorder?.toReaderBorderOrNull(): ReaderBorder? = this?.let {
    ReaderBorder(
        widthPx = it.width.toPxOrNull(),
        color = it.color?.toReaderColorOrNull(),
    ).takeIf { border -> border.widthPx != null || border.color != null }
}

private fun CssLength?.toPxOrNull(): Float? = when (this) {
    is CssLength.Em -> value * CssDefaultFontPx
    is CssLength.Percent -> null
    is CssLength.Px -> value
    null -> null
}

private fun CssLength?.toBorderRadiusPercentOrNull(): Float? = when (this) {
    is CssLength.Percent -> fraction * 100f
    else -> null
}

/**
 * Whether a container carries anything a page can be drawn from: a background or border to paint, or the
 * spacing that holds its own content off those edges.
 *
 * Spacing counts because `body { margin: 2em }` is how a reflowable book states its page margins, and a
 * container recorded only when it has a background would throw that away — the text is then set edge to edge
 * in a column far wider than the book was typeset for.
 */
private fun ReaderBlockStyle.hasVisualContainerData(): Boolean =
    boxStyle?.isEmpty() == false || hasBoxSpacing()

/** Whether this style states any margin or padding at all, on any side. */
private fun ReaderBlockStyle.hasBoxSpacing(): Boolean = listOf(
    marginTopEm, marginBottomEm, marginStartEm, marginEndEm,
    paddingTopEm, paddingBottomEm, paddingStartEm, paddingEndEm,
).any { side -> side != null && side > 0f }

private fun String.toReaderColorOrNull(): ReaderColor? {
    val value = trim().lowercase()
    return when {
        value == "transparent" -> ReaderColor(0x00000000)
        value == "black" -> ReaderColor(0xFF000000)
        value == "white" -> ReaderColor(0xFFFFFFFF)
        value == "red" -> ReaderColor(0xFFFF0000)
        value == "blue" -> ReaderColor(0xFF0000FF)
        value == "green" -> ReaderColor(0xFF008000)
        value == "gray" || value == "grey" -> ReaderColor(0xFF808080)
        value.startsWith("#") -> parseHexColor(value.removePrefix("#"))
        value.startsWith("rgb(") -> parseRgbColor(value, hasAlpha = false)
        value.startsWith("rgba(") -> parseRgbColor(value, hasAlpha = true)
        else -> null
    }
}

private fun parseHexColor(hex: String): ReaderColor? {
    val expanded = when (hex.length) {
        3 -> hex.flatMap { listOf(it, it) }.joinToString("")
        4 -> hex.flatMap { listOf(it, it) }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    val argb = when (expanded.length) {
        6 -> "FF$expanded"
        8 -> expanded.takeLast(2) + expanded.dropLast(2)
        else -> return null
    }
    return argb.toLongOrNull(16)?.let(::ReaderColor)
}

private fun parseRgbColor(value: String, hasAlpha: Boolean): ReaderColor? {
    val inner = value.substringAfter('(').substringBeforeLast(')')
    val parts = inner.split(',').map(String::trim)
    if (parts.size != if (hasAlpha) 4 else 3) return null
    val channels = parts.take(3).map { it.toFloatOrNull()?.coerceIn(0f, 255f)?.toInt() ?: return null }
    val alpha = if (!hasAlpha) 255 else {
        val raw = parts[3].toFloatOrNull() ?: return null
        (raw.coerceIn(0f, 1f) * 255f).toInt()
    }
    return ReaderColor(
        ((alpha.toLong() and 0xFF) shl 24) or
            ((channels[0].toLong() and 0xFF) shl 16) or
            ((channels[1].toLong() and 0xFF) shl 8) or
            (channels[2].toLong() and 0xFF),
    )
}

/**
 * Default font size in pixels a `px` length is read against when converting it to a relative scale,
 * matching a typical browser default.
 */
private const val CssDefaultFontPx = 16f

/** Numeric `font-weight` at and above which [String.toBoldOrNull] reads a value as bold. */
private const val BoldWeightThreshold = 600

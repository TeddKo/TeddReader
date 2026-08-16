package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import com.tedd.teddreader.core.common.model.ReaderObjectReplacementChar
import com.tedd.teddreader.core.common.model.ReaderSpan
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.isBlankIgnoringObjects

/**
 * Flattened text of one chapter together with the structure that text carries.
 *
 * The text is what the reader paginates, searches and stores; the blocks and spans address ranges
 * inside it. Replacing every tag with a space threw all of that away and collapsed the newlines with
 * it, which is why an EPUB used to read as one run-on line.
 */
internal data class XhtmlContent(
    val text: String,
    val blocks: List<ReaderBlock>,
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
 * [resolveImageHref] maps a raw `src` to a path inside the container, or null to drop the image.
 */
internal fun parseXhtmlContent(
    xhtml: String,
    baseOffset: Long = 0L,
    resolveImageHref: (String) -> String? = { it },
    styleSheet: EpubStyleSheet = EpubStyleSheet(),
): XhtmlContent {
    val builder = XhtmlContentBuilder(
        baseOffset = baseOffset,
        resolveImageHref = resolveImageHref,
        styleSheet = styleSheet,
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

        // Script, style and head hold no readable text; skipping their bodies keeps CSS and code out.
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

private fun String.skipPast(from: Int, marker: String): Int {
    val found = indexOf(marker, from)
    return if (found < 0) length else found + marker.length
}

private class XhtmlTag(
    val name: String,
    val attributes: Map<String, String>,
    val isClosing: Boolean,
    val isSelfClosing: Boolean,
)

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

private fun parseTagAttributes(body: String): Map<String, String> =
    TagAttributeRegex.findAll(body).associate { match ->
        match.groupValues[1].lowercase() to (match.groupValues[2].ifEmpty { match.groupValues[3] })
    }

private class OpenElement(val name: String, val classNames: List<String>)

private class XhtmlContentBuilder(
    private val baseOffset: Long,
    private val resolveImageHref: (String) -> String?,
    private val styleSheet: EpubStyleSheet,
) {
    private val text = StringBuilder()
    private val blocks = mutableListOf<ReaderBlock>()
    private val anchors = linkedMapOf<String, Long>()

    private var blockStart = -1
    private var blockKind = ReaderBlockKind.PARAGRAPH
    private var blockLevel = 0
    private var blockAlign: ReaderTextAlign? = null
    private var blockLabel: String? = null
    private var blockTableRow: Int? = null
    private var blockTableColumn: Int? = null
    private val blockSpans = mutableListOf<ReaderSpan>()

    private val openInline = mutableListOf<OpenSpan>()
    private val openBlocks = mutableListOf<OpenElement>()
    private val lists = mutableListOf<ListContext>()
    private val tables = mutableListOf<TableContext>()
    private var preformattedDepth = 0
    private var pendingSpace = false
    private var headingTitle: String? = null

    /** Pictures written into the block being built, so a wrapper holding only pictures is recognised. */
    private var blockImageCount = 0

    fun appendText(rawText: String) {
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
                // Held rather than written: markup padding must not open a block or start a line, but
                // a space between two inline elements arrives in a separate text run and has to
                // survive that gap.
                pendingSpace = true
                return@forEach
            }
            if (blockStart < 0) ensureBlockOpen()
            if (pendingSpace && text.length > blockStart) text.append(' ')
            pendingSpace = false
            text.append(char)
        }
    }

    fun openElement(tag: XhtmlTag) {
        rememberAnchors(tag.attributes)
        when (tag.name) {
            "br" -> {
                ensureBlockOpen()
                pendingSpace = false
                text.append('\n')
                return
            }

            "img", "image" -> {
                val source = tag.attributes["src"] ?: tag.attributes["xlink:href"] ?: tag.attributes["href"]
                val href = source?.let(resolveImageHref)
                if (href != null) {
                    // These books declare picture size only in their stylesheet, keyed by the class on
                    // the image or on the block wrapping it, so the width is looked up outward from the
                    // image through its ancestors — nearest rule wins, as the cascade would have it.
                    val ancestorClasses = tag.classNames() + openBlocks.asReversed().flatMap(OpenElement::classNames)
                    val cssWidth = styleSheet.widthFor(ancestorClasses)
                    appendImage(
                        imageHref = href,
                        label = tag.attributes["alt"]?.takeIf { it.isNotBlank() },
                        aspectRatio = tag.attributes.declaredImageAspectRatio(),
                        widthPercent = (cssWidth as? CssWidth.Percent)?.fraction,
                        widthEm = (cssWidth as? CssWidth.Em)?.value,
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
                openBlocks += OpenElement(tag.name, tag.classNames())
                return
            }

            "table" -> {
                tables += TableContext()
                openBlocks += OpenElement(tag.name, tag.classNames())
                return
            }

            "tr" -> {
                tables.lastOrNull()?.let { table ->
                    table.rowIndex += 1
                    table.columnIndex = -1
                }
                openBlocks += OpenElement(tag.name, tag.classNames())
                return
            }
        }

        InlineStyles[tag.name]?.let { style ->
            val href = tag.attributes["href"]
            if (style == ReaderInlineStyle.LINK && href.isNullOrBlank()) return
            ensureBlockOpen()
            // The space before the element belongs to the text, not to the span; writing it after the
            // start was recorded made every span begin one character early.
            flushPendingSpace()
            openInline += OpenSpan(name = tag.name, style = style, href = href, start = text.length)
            return
        }

        if (BlockKinds[tag.name] == ReaderBlockKind.HEADING && headingTitle == null) {
            headingTitle = tag.attributes["title"]?.trim()?.takeIf(String::isNotEmpty)
        }

        val kind = BlockKinds[tag.name] ?: run {
            if (tag.name in NeutralContainers) openBlocks += OpenElement(tag.name, tag.classNames())
            return
        }

        flushBlock()
        openBlocks += OpenElement(tag.name, tag.classNames())
        blockKind = kind
        blockLevel = when {
            kind == ReaderBlockKind.HEADING -> tag.name.removePrefix("h").toIntOrNull() ?: 1
            kind == ReaderBlockKind.LIST_ITEM -> lists.size.coerceAtLeast(1)
            else -> 0
        }
        blockAlign = tag.attributes.textAlign()
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

    fun closeElement(name: String) {
        val lowered = name.lowercase()
        openInline.indexOfLast { it.name == lowered }.takeIf { it >= 0 }?.let { spanIndex ->
            val span = openInline.removeAt(spanIndex)
            if (text.length > span.start) {
                blockSpans += ReaderSpan(
                    range = TextRange(baseOffset + span.start, baseOffset + text.length),
                    style = span.style,
                    href = span.href,
                )
            }
            return
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
    }

    fun build(): XhtmlContent {
        flushBlock()
        // The separator after the final block would show as a blank line at the end of a chapter.
        val minimumLength = blocks.maxOfOrNull { block -> (block.range.end - baseOffset).toInt() } ?: 0
        while (text.length > minimumLength && text.isNotEmpty() && text.last() == '\n') {
            text.deleteAt(text.length - 1)
        }
        return XhtmlContent(
            text = text.toString(),
            // A picture is recorded the moment it is written, its paragraph only once that paragraph
            // ends, so an inline picture is added before the block enclosing it. Reading order is what
            // everything downstream assumes, so the list is put back into it here.
            blocks = blocks.sortedBy { block -> block.range.start },
            anchors = anchors.toMap(),
            headingTitle = headingTitle,
        )
    }

    private fun rememberAnchors(attributes: Map<String, String>) {
        val absoluteOffset = baseOffset + text.length
        listOfNotNull(attributes["id"], attributes["name"], attributes["xml:id"])
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { anchor -> if (anchor !in anchors) anchors[anchor] = absoluteOffset }
    }

    private fun ensureBlockOpen() {
        if (blockStart >= 0) return
        blockStart = text.length
    }

    private fun flushPendingSpace() {
        if (!pendingSpace) return
        if (blockStart >= 0 && text.length > blockStart) text.append(' ')
        pendingSpace = false
    }

    /**
     * Write a picture where it was written: into the line being built, not between two blocks.
     *
     * `<img>` is inline content in HTML, and no reading system takes it out of its paragraph — doing so
     * tore a gaiji glyph out of the middle of its sentence and left a blank line in its place. A
     * picture that turns out to be the only thing in its block is recognised in [flushBlock], and
     * stands on a line of its own there, the way a plate does.
     */
    private fun appendImage(
        imageHref: String,
        label: String?,
        aspectRatio: Float?,
        widthPercent: Float?,
        widthEm: Float?,
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
            align = ReaderTextAlign.CENTER,
            imageAspectRatio = aspectRatio,
            imageWidthPercent = widthPercent,
            imageWidthEm = widthEm,
        )
    }

    private fun emitStandaloneBlock(
        kind: ReaderBlockKind,
        imageHref: String? = null,
        label: String? = null,
        aspectRatio: Float? = null,
        widthPercent: Float? = null,
        widthEm: Float? = null,
    ) {
        flushBlock()
        // The block owns one character, so it holds a real range: a zero-width block would fall
        // through the page-range filter at a boundary. The single newline that follows ends the rule's
        // line; the blank line before it is the one the preceding paragraph already wrote, which is the
        // spacing `<hr>` carries in a browser rather than the four blank lines two newlines gave it.
        pendingSpace = false
        val start = text.length
        text.append(ReaderObjectReplacementChar)
        blocks += ReaderBlock(
            kind = kind,
            range = TextRange(baseOffset + start, baseOffset + text.length),
            imageHref = imageHref,
            label = label,
            align = ReaderTextAlign.CENTER.takeIf { kind == ReaderBlockKind.IMAGE || kind == ReaderBlockKind.COVER_IMAGE },
            imageAspectRatio = aspectRatio,
            imageWidthPercent = widthPercent,
            imageWidthEm = widthEm,
        )
        text.append('\n')
    }

    private fun flushBlock() {
        val start = blockStart
        pendingSpace = false
        resetOpenSpans()
        val imageCount = blockImageCount
        blockImageCount = 0
        if (start < 0) {
            resetBlockAttributes()
            return
        }
        blockStart = -1
        while (text.length > start && text.last().isBlockPadding()) text.deleteAt(text.length - 1)
        if (text.length == start) {
            blockSpans.clear()
            resetBlockAttributes()
            return
        }

        if (imageCount > 0 && text.substring(start, text.length).isBlankIgnoringObjects()) {
            // Nothing here but pictures, so there is no prose to record: the block was only ever a
            // wrapper. Each picture keeps its own line, and no empty paragraph is left behind it.
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
        )
        blockSpans.clear()
        resetBlockAttributes()
        text.append("\n\n")
    }

    private fun resetOpenSpans() {
        if (openInline.isEmpty()) return
        // Unclosed inline markup ends with the block instead of leaking into the next one.
        openInline.asReversed().forEach { span ->
            if (text.length > span.start) {
                blockSpans += ReaderSpan(
                    range = TextRange(baseOffset + span.start, baseOffset + text.length),
                    style = span.style,
                    href = span.href,
                )
            }
        }
        openInline.clear()
    }

    private fun resetBlockAttributes() {
        blockKind = ReaderBlockKind.PARAGRAPH
        blockLevel = 0
        blockAlign = null
        blockLabel = null
        blockTableRow = null
        blockTableColumn = null
    }
}

private class OpenSpan(
    val name: String,
    val style: ReaderInlineStyle,
    val href: String?,
    val start: Int,
)

private class ListContext(
    val isOrdered: Boolean,
    var nextOrdinal: Int,
)

private class TableContext {
    var rowIndex: Int = -1
    var columnIndex: Int = -1
}

private fun Char.isBlockPadding(): Boolean = this == ' ' || this == '\n' || this == '\t' || this == '\r'

private fun ReaderBlockKind.isTableCellKind(): Boolean =
    this == ReaderBlockKind.TABLE_CELL || this == ReaderBlockKind.TABLE_HEADER_CELL

private fun XhtmlTag.classNames(): List<String> =
    attributes["class"].orEmpty().split(Regex("""\s+""")).map(String::trim).filter(String::isNotEmpty)

private fun Map<String, String>.startOrdinal(): Int = this["start"]?.toIntOrNull() ?: 1

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

private fun String.toPixelValue(): Float? = trim().takeIf { it.isNotEmpty() && it.none(Char::isLetter) }?.toFloatOrNull()

private fun cssPixelDimension(style: String, property: String): Float? =
    Regex("""$property\s*:\s*([0-9.]+)px""").find(style)?.groupValues?.get(1)?.toFloatOrNull()

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

private val TagAttributeRegex = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
private val TextAlignRegex = Regex("""text-align\s*:\s*([a-zA-Z]+)""")

private const val MaxEntityLength = 12

// "svg" is deliberately not skipped: EPUBs very commonly wrap a full-page illustration as
// `<svg><image xlink:href="..."/></svg>` (Sigil/Calibre's standard cover/illustration pattern) to make
// it scale to the viewport. Skipping the whole subtree, as script/style/head genuinely warrant,
// silently dropped every one of those images. Descending into svg is harmless: it isn't a known block
// or inline tag, so it is otherwise ignored, and its inner "image" element is handled like any other.
private val SkippedBodyElements = setOf("script", "style", "head", "title")

private val NeutralContainers = setOf(
    "html", "body", "span", "font", "small", "big", "label", "tbody", "thead", "tfoot",
    "colgroup", "col", "nav", "header", "footer", "main", "aside", "figure", "dl",
)

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

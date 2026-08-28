package com.tedd.teddreader.core.data.parser

/**
 * An EPUB's table of contents, read from either its EPUB3 nav document or its EPUB2 NCX, before it is
 * resolved against the book's actual spine and section offsets. Only carrying what the two very
 * different source formats agree on — an optional heading and a flat, indent-tagged list of entries —
 * keeps [parseEpubNavDocument] and [parseNcxDocument] returning the same shape regardless of which
 * table-of-contents format the book actually shipped with.
 *
 * @property heading The TOC's own heading text (e.g. "Contents"), if the source markup had one.
 * @property entries The TOC's entries, in document order.
 */
internal data class ParsedNavigation(
    val heading: String? = null,
    val entries: List<ParsedNavigationEntry> = emptyList(),
)

/**
 * One entry in an EPUB's table of contents, before its `href` has been resolved to a section index
 * and character offset in the parsed book.
 *
 * @property title The entry's display text.
 * @property level Nesting depth, starting at 1 for a top-level entry; deeper for an entry nested
 *   inside an EPUB3 `<ol>` or an NCX `navPoint`.
 * @property href The link target this entry points at, as written in the source markup — a path to a
 *   spine document, optionally with a `#fragment` anchor into it.
 */
internal data class ParsedNavigationEntry(
    val title: String,
    val level: Int,
    val href: String,
)

/**
 * Reads an EPUB3 navigation document's table of contents: the `<nav>` element whose `epub:type` (or
 * `type`/`role`, both seen in books that predate the `epub:type` attribute) contains `toc`, among
 * possibly several `<nav>` elements for landmarks or a page list.
 *
 * The body is walked as a flat token stream rather than parsed as a DOM, tracking `<ol>` nesting depth
 * for [ParsedNavigationEntry.level] and, inside each `<a>`, collecting its text content as the title —
 * falling back to an inner `<img>`'s `alt` or `title` attribute for a link that carries a cover
 * thumbnail instead of text, which some books use as their only chapter label. An entry with no `href`
 * or an empty title is dropped rather than added with a placeholder.
 *
 * @param xhtml The nav document's raw markup.
 * @return The heading and entries found, or an empty [ParsedNavigation] if no `toc`-typed `<nav>`
 *   exists, or its opening tag has no matching close (malformed markup never throws here — it just
 *   yields nothing to show).
 */
internal fun parseEpubNavDocument(xhtml: String): ParsedNavigation {
    val navMatch = NavOpenTagRegex
        .findAll(xhtml)
        .firstOrNull { match ->
            navTypeTokens(parseAttributes(match.value)).contains("toc")
        } ?: return ParsedNavigation()
    val navEnd = findMatchingEndTag(xhtml, navMatch.range.first, NavTagPairRegex) ?: return ParsedNavigation()
    val navBody = xhtml.substring(navMatch.range.last + 1, navEnd.start)
    val heading = NavHeadingRegex
        .find(navBody)
        ?.groupValues
        ?.get(2)
        ?.let(::stripMarkup)
        ?.takeIf(String::isNotBlank)

    val entries = mutableListOf<ParsedNavigationEntry>()
    val tokens = MarkupTokenRegex.findAll(navBody)
    var listDepth = 0
    var captureDepth = 0
    var pendingHref: String? = null
    val label = StringBuilder()
    for (token in tokens) {
        val value = token.value
        if (!value.startsWith('<')) {
            if (captureDepth > 0) label.append(decodeXmlEntities(value))
            continue
        }
        val tag = parseNavToken(value)
        when {
            !tag.isClosing && tag.name == "ol" -> listDepth += 1
            tag.isClosing && tag.name == "ol" && listDepth > 0 -> listDepth -= 1
            !tag.isClosing && tag.name == "a" -> {
                pendingHref = tag.attributes["href"]?.let(::decodeXmlEntities)
                label.clear()
                captureDepth = if (pendingHref != null) 1 else 0
            }
            !tag.isClosing && captureDepth > 0 && tag.name == "img" -> {
                val altOrTitle = tag.attributes["alt"]?.takeIf(String::isNotBlank)
                    ?: tag.attributes["title"]?.takeIf(String::isNotBlank)
                if (altOrTitle != null) label.append(altOrTitle)
            }
            !tag.isClosing && captureDepth > 0 -> captureDepth += 1
            tag.isClosing && captureDepth > 0 && tag.name == "a" -> {
                val href = pendingHref
                val title = label.toString().replace(WhitespaceRunRegex, " ").trim()
                if (href != null && title.isNotEmpty()) {
                    entries += ParsedNavigationEntry(
                        title = title,
                        level = listDepth.coerceAtLeast(1),
                        href = href,
                    )
                }
                pendingHref = null
                label.clear()
                captureDepth = 0
            }
            tag.isClosing && captureDepth > 0 -> captureDepth = (captureDepth - 1).coerceAtLeast(0)
        }
    }
    return ParsedNavigation(heading = heading, entries = entries)
}

/**
 * Reads an EPUB2 NCX document's table of contents — the legacy format still shipped alongside, or
 * instead of, an EPUB3 nav document by books produced with older tooling. `docTitle`/`text` supplies
 * the heading, and nested `navPoint` elements ([parseNcxNavPoints]) supply the entries, with an
 * entry's nesting depth becoming its [ParsedNavigationEntry.level].
 *
 * @param xml The NCX document's raw markup.
 * @return The heading and entries found; entries is empty if the document has no `navPoint`s or they
 *   are malformed enough that none can be matched.
 */
internal fun parseNcxDocument(xml: String): ParsedNavigation {
    val heading = NcxDocTitleRegex
        .find(xml)
        ?.groupValues
        ?.get(1)
        ?.let(::stripMarkup)
        ?.takeIf(String::isNotBlank)
    val entries = mutableListOf<ParsedNavigationEntry>()
    parseNcxNavPoints(xml, 1, entries)
    return ParsedNavigation(heading = heading, entries = entries)
}

/**
 * Recursively collects [ParsedNavigationEntry] from every `navPoint` in [xml] into [entries], in
 * document order, descending into each `navPoint`'s own body to find the ones nested inside it at
 * [level] + 1. A `navPoint` missing either a `navLabel`/`text` title or a `content` `src` is skipped —
 * its children are still visited — rather than added with a blank title or href.
 *
 * @param xml The markup to scan for `navPoint` elements at this level; either the whole NCX document
 *   or the body of one `navPoint`, when called recursively.
 * @param level Nesting depth to assign to entries found directly in [xml].
 * @param entries The list entries are appended to, shared across the whole recursive walk.
 */
private fun parseNcxNavPoints(xml: String, level: Int, entries: MutableList<ParsedNavigationEntry>) {
    var index = 0
    while (true) {
        val start = NcxNavPointOpenRegex.find(xml, index) ?: break
        val end = findMatchingEndTag(xml, start.range.first, NcxNavPointTagPairRegex) ?: break
        val body = xml.substring(start.range.last + 1, end.start)
        val title = NcxNavLabelTextRegex
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.let(::stripMarkup)
            ?.trim()
            .orEmpty()
        val href = NcxContentSrcRegex
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.let(::decodeXmlEntities)
            .orEmpty()
        if (title.isNotEmpty() && href.isNotEmpty()) {
            entries += ParsedNavigationEntry(title = title, level = level, href = href)
        }
        parseNcxNavPoints(body, level + 1, entries)
        index = end.end
    }
}

/** Span of a closing tag [findMatchingEndTag] found, from its `<` through its `>` inclusive. */
private data class EndTagRange(val start: Int, val end: Int)

/**
 * Finds the closing tag that matches the element opened at [startIndex], accounting for other
 * same-named elements nested inside it (an `<ol>` inside an `<ol>`, say) and for self-closing tags,
 * which never increase nesting depth in the first place.
 *
 * Takes the tag pattern already compiled rather than an element name, because
 * [parseNcxNavPoints] calls this once per `navPoint` while walking a book's whole table of contents
 * and recursing into each entry's children — building the pattern from a name here charged a regex
 * compilation to every entry at every nesting level.
 *
 * @param text The markup to scan.
 * @param startIndex Position of (at or before) the opening tag's own `<`.
 * @param tagPair Pattern matching both the opening and closing form of the element, capturing `/` in
 *   group 1 for the closing form, i.e. [NavTagPairRegex] or [NcxNavPointTagPairRegex].
 * @return The matching close tag's range, or `null` if [text] has no balanced close for it — markup
 *   this parser treats as malformed and gives up on for that element, rather than guessing.
 */
private fun findMatchingEndTag(text: String, startIndex: Int, tagPair: Regex): EndTagRange? {
    var depth = 0
    generateSequence(tagPair.find(text, startIndex)) { previous -> tagPair.find(text, previous.range.last + 1) }.forEach { match ->
        val raw = match.value
        val selfClosing = raw.endsWith("/>")
        val isClosing = match.groupValues[1] == "/"
        if (!isClosing) {
            depth += 1
            if (selfClosing) depth -= 1
        } else {
            depth -= 1
            if (depth == 0) return EndTagRange(match.range.first, match.range.last + 1)
        }
    }
    return null
}

/**
 * Parses one already-isolated tag string (e.g. `<a href="ch1.xhtml">` or `</ol>`) from
 * [parseEpubNavDocument]'s token stream into its name, attributes, and open/close state.
 *
 * @param raw The raw tag text, including its enclosing `<`/`>` and, for a self-closing tag, its `/`.
 * @return The parsed [NavToken]; a closing tag always has an empty attribute map, since a close tag
 *   carries none.
 */
private fun parseNavToken(raw: String): NavToken {
    val body = raw.removePrefix("<").removeSuffix(">").removeSuffix("/")
    val isClosing = body.startsWith("/")
    val normalized = body.removePrefix("/")
    val name = normalized.takeWhile { !it.isWhitespace() }.lowercase()
    return NavToken(
        name = name,
        attributes = if (isClosing) emptyMap() else parseAttributes(normalized),
        isClosing = isClosing,
    )
}

/**
 * One tag from [parseEpubNavDocument]'s token stream, decomposed enough to drive its state machine.
 *
 * @property name The tag's element name, lowercased.
 * @property attributes The tag's attributes; always empty for a closing tag.
 * @property isClosing Whether this is a `</name>` closing tag rather than an opening or self-closing one.
 */
private data class NavToken(
    val name: String,
    val attributes: Map<String, String>,
    val isClosing: Boolean,
)

/**
 * Removes every tag from an HTML/XML fragment, decodes the entities in what remains, and collapses
 * whitespace — turning a heading's or label's markup into the plain text it displays as.
 *
 * @param value The markup fragment to strip.
 * @return The fragment's text content with all runs of whitespace collapsed to a single space and
 *   leading/trailing whitespace removed.
 */
internal fun stripMarkup(value: String): String = buildString {
    MarkupTokenRegex.findAll(value).forEach { token ->
        if (!token.value.startsWith('<')) append(decodeXmlEntities(token.value))
    }
}.replace(WhitespaceRunRegex, " ").trim()

/**
 * The lowercase, whitespace-split token set carried by a `<nav>` tag's `epub:type` attribute — or,
 * for books written before that attribute existed, its `type` or `role` attribute instead — which
 * [parseEpubNavDocument] checks for the `toc` token to find the right `<nav>` among possibly several.
 *
 * @param attributes The tag's parsed attributes.
 * @return The tokens found across whichever of `epub:type`, `type`, and `role` are present; empty if
 *   none of them are.
 */
private fun navTypeTokens(attributes: Map<String, String>): Set<String> =
    sequenceOf(attributes["epub:type"], attributes["type"], attributes["role"])
        .filterNotNull()
        .flatMap { value -> decodeXmlEntities(value).split(WhitespaceRunRegex).asSequence() }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(String::lowercase)
        .toSet()

/**
 * Matches a `<nav>` opening tag, whose attributes [parseEpubNavDocument] then checks for the `toc`
 * type token.
 */
private val NavOpenTagRegex = Regex("""(?is)<nav\b[^>]*>""")

/** Matches both `<nav …>` and `</nav>` so [findMatchingEndTag] can balance nesting depth. */
private val NavTagPairRegex = Regex("""(?is)<(/?)nav\b[^>]*>""")

/** Matches the first heading element inside a nav document's body, whose group 2 is its markup. */
private val NavHeadingRegex = Regex("""(?is)<(h[1-6]|p)\b[^>]*>(.*?)</\1>""")

/**
 * Splits markup into a flat stream of tags and the text between them, the tokenization both
 * [parseEpubNavDocument]'s state machine and [stripMarkup] walk.
 */
private val MarkupTokenRegex = Regex("""(?s)<[^>]+>|[^<]+""")

/**
 * Matches one run of whitespace, for collapsing a label's internal whitespace and for splitting a
 * `nav` type attribute into tokens.
 *
 * Hoisted because [stripMarkup] runs once per table-of-contents entry and per heading, so a book with
 * a large outline compiled this pattern hundreds of times per import.
 */
private val WhitespaceRunRegex = Regex("""\s+""")

/** Captures an NCX `docTitle`'s text content in group 1, the heading [parseNcxDocument] reports. */
private val NcxDocTitleRegex = Regex("""(?is)<docTitle>.*?<text>(.*?)</text>.*?</docTitle>""")

/** Matches a `navPoint` opening tag, the element [parseNcxNavPoints] walks. */
private val NcxNavPointOpenRegex = Regex("""(?is)<navPoint\b[^>]*>""")

/** Matches both `<navPoint …>` and `</navPoint>` so [findMatchingEndTag] can balance nesting depth. */
private val NcxNavPointTagPairRegex = Regex("""(?is)<(/?)navPoint\b[^>]*>""")

/** Captures a `navPoint`'s `navLabel`/`text` title in group 1. */
private val NcxNavLabelTextRegex = Regex("""(?is)<navLabel>.*?<text>(.*?)</text>.*?</navLabel>""")

/** Captures a `navPoint`'s `content` `src` link target in group 1. */
private val NcxContentSrcRegex = Regex("""(?is)<content\b[^>]*src\s*=\s*["']([^"']+)["'][^>]*/?>""")

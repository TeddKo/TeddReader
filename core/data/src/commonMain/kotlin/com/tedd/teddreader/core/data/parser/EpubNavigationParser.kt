package com.tedd.teddreader.core.data.parser

internal data class ParsedNavigation(
    val heading: String? = null,
    val entries: List<ParsedNavigationEntry> = emptyList(),
)

internal data class ParsedNavigationEntry(
    val title: String,
    val level: Int,
    val href: String,
)

internal fun parseEpubNavDocument(xhtml: String): ParsedNavigation {
    val navMatch = Regex("""(?is)<nav\b[^>]*>""")
        .findAll(xhtml)
        .firstOrNull { match ->
            navTypeTokens(parseAttributes(match.value)).contains("toc")
        } ?: return ParsedNavigation()
    val navEnd = findMatchingEndTag(xhtml, navMatch.range.first, "nav") ?: return ParsedNavigation()
    val navBody = xhtml.substring(navMatch.range.last + 1, navEnd.start)
    val heading = Regex("""(?is)<(h[1-6]|p)\b[^>]*>(.*?)</\1>""")
        .find(navBody)
        ?.groupValues
        ?.get(2)
        ?.let(::stripMarkup)
        ?.takeIf(String::isNotBlank)

    val entries = mutableListOf<ParsedNavigationEntry>()
    val tokens = Regex("""(?s)<[^>]+>|[^<]+""").findAll(navBody)
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
                val title = label.toString().replace(Regex("""\s+"""), " ").trim()
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

internal fun parseNcxDocument(xml: String): ParsedNavigation {
    val heading = Regex("""(?is)<docTitle>.*?<text>(.*?)</text>.*?</docTitle>""")
        .find(xml)
        ?.groupValues
        ?.get(1)
        ?.let(::stripMarkup)
        ?.takeIf(String::isNotBlank)
    val entries = mutableListOf<ParsedNavigationEntry>()
    parseNcxNavPoints(xml, 1, entries)
    return ParsedNavigation(heading = heading, entries = entries)
}

private fun parseNcxNavPoints(xml: String, level: Int, entries: MutableList<ParsedNavigationEntry>) {
    var index = 0
    while (true) {
        val start = Regex("""(?is)<navPoint\b[^>]*>""").find(xml, index) ?: break
        val end = findMatchingEndTag(xml, start.range.first, "navPoint") ?: break
        val body = xml.substring(start.range.last + 1, end.start)
        val title = Regex("""(?is)<navLabel>.*?<text>(.*?)</text>.*?</navLabel>""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.let(::stripMarkup)
            ?.trim()
            .orEmpty()
        val href = Regex("""(?is)<content\b[^>]*src\s*=\s*["']([^"']+)["'][^>]*/?>""")
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

private data class EndTagRange(val start: Int, val end: Int)

private fun findMatchingEndTag(text: String, startIndex: Int, tagName: String): EndTagRange? {
    val tokenRegex = Regex("""(?is)<(/?)$tagName\b[^>]*>""")
    var depth = 0
    generateSequence(tokenRegex.find(text, startIndex)) { previous -> tokenRegex.find(text, previous.range.last + 1) }.forEach { match ->
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

private data class NavToken(
    val name: String,
    val attributes: Map<String, String>,
    val isClosing: Boolean,
)

internal fun stripMarkup(value: String): String = buildString {
    Regex("""(?s)<[^>]+>|[^<]+""").findAll(value).forEach { token ->
        if (!token.value.startsWith('<')) append(decodeXmlEntities(token.value))
    }
}.replace(Regex("""\s+"""), " ").trim()

private fun navTypeTokens(attributes: Map<String, String>): Set<String> =
    sequenceOf(attributes["epub:type"], attributes["type"], attributes["role"])
        .filterNotNull()
        .flatMap { value -> decodeXmlEntities(value).split(Regex("""\s+""")).asSequence() }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(String::lowercase)
        .toSet()

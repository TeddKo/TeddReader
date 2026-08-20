package com.tedd.teddreader.core.data.parser

/**
 * The slice of CSS an EPUB can actually be drawn with here.
 *
 * The reader lays a book out as one styled string, so a declaration only means something if a span or
 * a paragraph can carry it. `float`, `display`, `position` and the rest describe a box model this
 * renderer does not have, and parsing them would buy nothing. What is here is everything that does
 * reach the page — and because the cascade below is general, adding another property is one entry in
 * this list plus one line where it is read.
 */
internal data class CssDeclarations(
    val textAlign: String? = null,
    val fontSize: CssLength? = null,
    val fontWeight: String? = null,
    val fontStyle: String? = null,
    val fontFamily: String? = null,
    val lineHeight: CssLength? = null,
    val color: String? = null,
    val textIndent: CssLength? = null,
    val marginTop: CssLength? = null,
    val marginBottom: CssLength? = null,
    val width: CssLength? = null,
) {
    /** [other] layered on top of this, as a later or more specific rule would be. */
    fun mergedWith(other: CssDeclarations): CssDeclarations = CssDeclarations(
        textAlign = other.textAlign ?: textAlign,
        fontSize = other.fontSize ?: fontSize,
        fontWeight = other.fontWeight ?: fontWeight,
        fontStyle = other.fontStyle ?: fontStyle,
        fontFamily = other.fontFamily ?: fontFamily,
        lineHeight = other.lineHeight ?: lineHeight,
        color = other.color ?: color,
        textIndent = other.textIndent ?: textIndent,
        marginTop = other.marginTop ?: marginTop,
        marginBottom = other.marginBottom ?: marginBottom,
        width = other.width ?: width,
    )

    /** What a child starts from: the properties CSS defines as inherited, and only those. */
    fun inheritable(): CssDeclarations = CssDeclarations(
        textAlign = textAlign,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        fontFamily = fontFamily,
        lineHeight = lineHeight,
        color = color,
        textIndent = textIndent,
    )

    fun isEmpty(): Boolean = this == Empty

    companion object {
        val Empty = CssDeclarations()
    }
}

internal sealed interface CssLength {
    data class Percent(val fraction: Float) : CssLength
    data class Em(val value: Float) : CssLength
    data class Px(val value: Float) : CssLength
}

/** One element as the matcher sees it: its tag, its classes and its id. */
internal data class CssElement(
    val tag: String,
    val classes: Set<String> = emptySet(),
    val id: String? = null,
)

/**
 * One compound selector — `h1`, `.note`, `h1.note#id` — as the pieces it has to match.
 */
private data class CompoundSelector(
    val tag: String?,
    val classes: Set<String>,
    val id: String?,
) {
    fun matches(element: CssElement): Boolean {
        if (tag != null && !tag.equals(element.tag, ignoreCase = true)) return false
        if (id != null && id != element.id) return false
        return element.classes.containsAll(classes)
    }

    /** CSS specificity, ordered id > class > tag, as the spec defines it. */
    val specificity: Int get() = (if (id != null) 10_000 else 0) + classes.size * 100 + (if (tag != null) 1 else 0)
}

/** A selector as its compound parts, innermost last; only the descendant combinator is honoured. */
private data class CssSelector(val compounds: List<CompoundSelector>) {
    val specificity: Int get() = compounds.sumOf(CompoundSelector::specificity)

    /**
     * True when [ancestors] (outermost first, the element itself last) satisfies this selector.
     *
     * `>`, `+` and `~` are read as plain descendants: treating them as stricter would drop styling a
     * book meant to apply, and treating a descendant as a child never changes which of two rules for
     * the same element wins here.
     */
    fun matches(ancestors: List<CssElement>): Boolean {
        if (compounds.isEmpty() || ancestors.isEmpty()) return false
        if (!compounds.last().matches(ancestors.last())) return false
        var compoundIndex = compounds.size - 2
        var ancestorIndex = ancestors.size - 2
        while (compoundIndex >= 0) {
            if (ancestorIndex < 0) return false
            if (compounds[compoundIndex].matches(ancestors[ancestorIndex])) compoundIndex -= 1
            ancestorIndex -= 1
        }
        return true
    }
}

private data class CssRule(
    val selector: CssSelector,
    val declarations: CssDeclarations,
    val order: Int,
)

/** A book's stylesheets, ready to answer what an element in it looks like. */
internal class EpubCss private constructor(private val rules: List<CssRule>) {
    /**
     * Declarations that apply to the last element of [ancestors], weakest first so a caller can layer
     * them. Ties on specificity fall back to declaration order, which is what a browser does.
     */
    fun declarationsFor(ancestors: List<CssElement>): CssDeclarations {
        if (rules.isEmpty()) return CssDeclarations.Empty
        return rules.asSequence()
            .filter { rule -> rule.selector.matches(ancestors) }
            .sortedWith(compareBy({ it.selector.specificity }, { it.order }))
            .fold(CssDeclarations.Empty) { acc, rule -> acc.mergedWith(rule.declarations) }
    }

    fun isEmpty(): Boolean = rules.isEmpty()

    companion object {
        val Empty = EpubCss(emptyList())

        /** Parse [sheets] in the order the document links them, so a later sheet wins a tie. */
        fun parse(sheets: List<String>): EpubCss {
            val rules = mutableListOf<CssRule>()
            var order = 0
            sheets.forEach { css ->
                CssRuleRegex.findAll(stripCssComments(css)).forEach { match ->
                    val declarations = parseDeclarations(match.groupValues[2])
                    if (declarations.isEmpty()) return@forEach
                    match.groupValues[1].split(',').forEach { rawSelector ->
                        val selector = parseSelector(rawSelector) ?: return@forEach
                        rules += CssRule(selector, declarations, order)
                        order += 1
                    }
                }
            }
            return if (rules.isEmpty()) Empty else EpubCss(rules)
        }
    }
}

private fun parseSelector(raw: String): CssSelector? {
    val cleaned = raw.trim()
    // An at-rule body, a pseudo-element or an attribute selector is not something this can judge, and
    // guessing would apply a book's print-only or state-only styling to every page.
    if (cleaned.isEmpty() || '@' in cleaned || '[' in cleaned || "::" in cleaned) return null
    val compounds = cleaned.split(CssCombinatorRegex)
        .filter(String::isNotEmpty)
        .map(::parseCompound)
    if (compounds.isEmpty() || compounds.any { it == null }) return null
    return CssSelector(compounds.filterNotNull())
}

private fun parseCompound(raw: String): CompoundSelector? {
    // A pseudo-class narrows a rule by state this cannot observe — `a:hover` is not `a`. Dropping the
    // rule keeps hover and print styling off a page that is neither.
    if (raw.isEmpty() || ':' in raw || '*' in raw) return null
    val tag = raw.takeWhile { it != '.' && it != '#' }.takeIf(String::isNotEmpty)
    val classes = CssClassNameRegex.findAll(raw).map { it.groupValues[1] }.toSet()
    val id = CssIdRegex.find(raw)?.groupValues?.get(1)
    if (tag == null && classes.isEmpty() && id == null) return null
    return CompoundSelector(tag = tag, classes = classes, id = id)
}

private fun parseDeclarations(body: String): CssDeclarations {
    var result = CssDeclarations.Empty
    body.split(';').forEach { declaration ->
        val name = declaration.substringBefore(':', "").trim().lowercase()
        val value = declaration.substringAfter(':', "").trim()
        if (name.isEmpty() || value.isEmpty()) return@forEach
        result = when (name) {
            "text-align" -> result.copy(textAlign = value.lowercase())
            "font-size" -> result.copy(fontSize = parseLength(value))
            "font-weight" -> result.copy(fontWeight = value.lowercase())
            "font-style" -> result.copy(fontStyle = value.lowercase())
            "font-family" -> result.copy(fontFamily = value.lowercase())
            "line-height" -> result.copy(lineHeight = parseLineHeight(value))
            "color" -> result.copy(color = value.lowercase())
            "text-indent" -> result.copy(textIndent = parseLength(value))
            "width" -> result.copy(width = parseLength(value))
            "margin-top" -> result.copy(marginTop = parseLength(value))
            "margin-bottom" -> result.copy(marginBottom = parseLength(value))
            "margin" -> parseMarginShorthand(value)?.let { (top, bottom) ->
                result.copy(marginTop = top, marginBottom = bottom)
            } ?: result
            else -> result
        }
    }
    return result
}

private fun parseLength(value: String): CssLength? {
    val match = CssLengthRegex.find(value.trim()) ?: return null
    val number = match.groupValues[1].toFloatOrNull() ?: return null
    return when (match.groupValues[2].lowercase()) {
        "%" -> CssLength.Percent(number / 100f)
        "em", "rem" -> CssLength.Em(number)
        "px", "pt" -> CssLength.Px(number)
        else -> null
    }
}

/** `line-height: 1.6` has no unit and means a multiple of the font size. */
private fun parseLineHeight(value: String): CssLength? =
    value.trim().toFloatOrNull()?.let { CssLength.Em(it) } ?: parseLength(value)

/** Vertical margins of the `margin` shorthand, in its 1..4 value forms. */
private fun parseMarginShorthand(value: String): Pair<CssLength?, CssLength?>? {
    val parts = value.trim().split(CssWhitespaceRegex).filter(String::isNotEmpty)
    return when (parts.size) {
        1 -> parseLength(parts[0]).let { it to it }
        2, 3 -> parseLength(parts[0]) to parseLength(if (parts.size == 2) parts[0] else parts[2])
        4 -> parseLength(parts[0]) to parseLength(parts[2])
        else -> null
    }
}

private fun stripCssComments(css: String): String = css.replace(CssCommentRegex, " ")

private val CssCommentRegex = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val CssRuleRegex = Regex("""([^{}]+)\{([^{}]*)\}""")
private val CssCombinatorRegex = Regex("""[\s>+~]+""")
private val CssClassNameRegex = Regex("""\.([\w-]+)""")
private val CssIdRegex = Regex("""#([\w-]+)""")
private val CssLengthRegex = Regex("""(-?[0-9.]+)\s*(%|em|rem|px|pt)""", RegexOption.IGNORE_CASE)
private val CssWhitespaceRegex = Regex("""\s+""")

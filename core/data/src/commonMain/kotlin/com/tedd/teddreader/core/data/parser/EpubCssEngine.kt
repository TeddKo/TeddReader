package com.tedd.teddreader.core.data.parser

/**
 * The slice of CSS an EPUB can actually be drawn with here.
 *
 * The reader lays a book out as one styled string, so a declaration only means something if a span or
 * a paragraph can carry it. Most box-model properties (`display`, `position`, true float layout, …)
 * still do not reach the page here; what is here is the subset the parser can preserve in reader-owned
 * model types, including publisher colors, simple borders, hidden-subtree suppression, and embedded
 * font-face references. Because the cascade below is general, adding another supported property is still
 * one entry in this list plus one line where it is read.
 */
internal data class CssDeclarations(
    /** Raw `text-align` value (`"center"`, `"right"`, …), resolved to a reader alignment later. */
    val textAlign: String? = null,
    /** `font-size`, relative to whatever the surrounding text's own size already is. */
    val fontSize: CssLength? = null,
    /**
     * Raw `font-weight` value — a keyword like `"bold"` or a numeric weight as text — resolved to a
     * bold flag later.
     */
    val fontWeight: String? = null,
    /** Raw `font-style` value (`"italic"`, `"oblique"`, `"normal"`); anything else reads as not italic. */
    val fontStyle: String? = null,
    /**
     * Raw `font-family` value; matched against the reader's own generic families where possible, and
     * also used to look up a linked `@font-face` href when the book bundled one.
     */
    val fontFamily: String? = null,
    /** `line-height`; a unitless value stays a factor of the element's own font size, see [parseLineHeight]. */
    val lineHeight: CssLineHeight? = null,
    /** Raw `color` value, carried through as text for later CSS color resolution. */
    val color: String? = null,
    /** Raw `background-color` value, likewise carried through for later resolution. */
    val backgroundColor: String? = null,
    /** `text-indent` of the first line. */
    val textIndent: CssLength? = null,
    /** `margin-top`; not inherited — see [inheritable]. */
    val marginTop: CssLength? = null,
    /** `margin-bottom`; also what decides the gap between two paragraphs. */
    val marginBottom: CssLength? = null,
    /**
     * Raw `text-decoration` value (`"none"`, `"underline"`, `"line-through"`, …). A book that turns its
     * link underlines off says so here, and a reader that ignores it draws underlines the book removed.
     */
    val textDecoration: String? = null,
    /** `padding-top`, which spaces a box's content off its own top edge. */
    val paddingTop: CssLength? = null,
    /** `padding-right`, the inline-end counterpart of [paddingLeft]. */
    val paddingRight: CssLength? = null,
    /** `padding-bottom`, the vertical counterpart of [paddingTop]. */
    val paddingBottom: CssLength? = null,
    /** `padding-left`, which is what indents a quotation away from the text around it. */
    val paddingLeft: CssLength? = null,
    /** `margin-left`; not inherited, and read as the inline-start space of a block. */
    val marginLeft: CssLength? = null,
    /** `margin-right`; the inline-end counterpart of [marginLeft]. */
    val marginRight: CssLength? = null,
    /** Raw `float` value (`"left"`, `"right"`, `"none"`); image styling reads it as a publisher hint. */
    val float: String? = null,
    /** `width`, for an image whose ancestor this rule matches. */
    val width: CssLength? = null,
    /** Raw `display` value; `display:none` is what the XHTML parser actually consumes. */
    val display: String? = null,
    /** Top border declaration, whether from the shorthand or the explicit side property. */
    val borderTop: CssBorder? = null,
    /** Right border declaration, whether from the shorthand or the explicit side property. */
    val borderRight: CssBorder? = null,
    /** Bottom border declaration, whether from the shorthand or the explicit side property. */
    val borderBottom: CssBorder? = null,
    /** Left border declaration, whether from the shorthand or the explicit side property. */
    val borderLeft: CssBorder? = null,
    /** `border-radius`; only the percent form is preserved all the way to the reader model. */
    val borderRadius: CssLength? = null,
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
        backgroundColor = other.backgroundColor ?: backgroundColor,
        textIndent = other.textIndent ?: textIndent,
        marginTop = other.marginTop ?: marginTop,
        marginBottom = other.marginBottom ?: marginBottom,
        marginLeft = other.marginLeft ?: marginLeft,
        marginRight = other.marginRight ?: marginRight,
        textDecoration = other.textDecoration ?: textDecoration,
        paddingTop = other.paddingTop ?: paddingTop,
        paddingRight = other.paddingRight ?: paddingRight,
        paddingBottom = other.paddingBottom ?: paddingBottom,
        paddingLeft = other.paddingLeft ?: paddingLeft,
        float = other.float ?: float,
        width = other.width ?: width,
        display = other.display ?: display,
        borderTop = other.borderTop ?: borderTop,
        borderRight = other.borderRight ?: borderRight,
        borderBottom = other.borderBottom ?: borderBottom,
        borderLeft = other.borderLeft ?: borderLeft,
        borderRadius = other.borderRadius ?: borderRadius,
    )

    /**
     * What a child starts from: the raw-text properties CSS defines as inherited, and only those.
     *
     * `font-size`, `line-height` and `text-indent` are inherited too, but not here — they inherit as
     * *numbers* the style resolver computes (an `em` compounds through its ancestors, a unitless
     * `line-height` re-multiplies each element's own size), and carrying the raw declaration down
     * instead made every descendant re-resolve it against the wrong base. `text-decoration` is not
     * inherited at all: CSS paints an ancestor's decoration across its descendants, which the resolver
     * models separately — inheriting it re-drew each child's underline at the child's own thickness.
     */
    fun inheritable(): CssDeclarations = CssDeclarations(
        textAlign = textAlign,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        fontFamily = fontFamily,
        color = color,
    )

    /** Any raw `inherit` keyword here resolved back to the already-inherited [parent] value. */
    fun resolvedInheritedKeywords(parent: CssDeclarations): CssDeclarations = copy(
        textAlign = textAlign.resolveInheritedKeyword(parent.textAlign),
        fontWeight = fontWeight.resolveInheritedKeyword(parent.fontWeight),
        fontStyle = fontStyle.resolveInheritedKeyword(parent.fontStyle),
        fontFamily = fontFamily.resolveInheritedKeyword(parent.fontFamily),
        color = color.resolveInheritedKeyword(parent.color),
    )

    /**
     * True when every property here is unset — a rule (or lack of one) that changes nothing about how an
     * element looks.
     */
    fun isEmpty(): Boolean = this == Empty

    /** Holds [Empty], the shared no-op instance returned wherever nothing in the cascade applies. */
    companion object {
        /**
         * The no-op set of declarations: every property unset, returned wherever nothing in the cascade
         * applies.
         */
        val Empty = CssDeclarations()
    }
}

private fun String?.resolveInheritedKeyword(parent: String?): String? =
    if (this?.equals("inherit", ignoreCase = true) == true) parent else this

/** A CSS width, in the units EPUB stylesheets actually use to size a picture. */
internal sealed interface CssWidth {
    /** `width: 75%`, as a fraction of the containing block. */
    data class Percent(val fraction: Float) : CssWidth

    /** `width: 6.5em`. */
    data class Em(val value: Float) : CssWidth
}

/**
 * A CSS length as this engine can resolve it — relative to a container, to the current font size, or
 * absolute.
 */
internal sealed interface CssLength {
    /** `n%`, as a fraction of whatever base the property being sized defines. */
    data class Percent(val fraction: Float) : CssLength
    /** `n em`/`n rem`, or an already-unitless `line-height` normalized to the same multiple. */
    data class Em(val value: Float) : CssLength
    /** `n px` or `n pt`, read against the reader's own default font size wherever needed. */
    data class Px(val value: Float) : CssLength
}

/**
 * A `line-height` value with the distinction CSS inheritance depends on kept intact.
 *
 * A unitless `line-height: 1.6` is a *factor* of whichever element it ends up applying to — a heading
 * inheriting it from `body` gets `1.6 × its own type size`, not `1.6 × the body text`. A length
 * (`1.6em`, `24px`) computes once against the declaring element and inherits as that fixed size.
 * Collapsing the two into one length is what set every large-type block's lines too tight.
 */
internal sealed interface CssLineHeight {
    /** Unitless factor, re-multiplied against each inheriting element's own font size. */
    data class Factor(val value: Float) : CssLineHeight
    /** An explicit length, computed once at the declaring element. */
    data class Length(val length: CssLength) : CssLineHeight
}

/** One border side the parser can still preserve: width plus color, with style reduced to “present”. */
internal data class CssBorder(
    val width: CssLength? = null,
    val color: String? = null,
)

/** One parsed `@font-face`: the family name it defines and the resolved embedded-font href, if any. */
internal data class CssFontFace(
    val familyName: String,
    val srcHref: String?,
)

/** One linked stylesheet together with the container path it was loaded from, for relative `url(...)`. */
internal data class CssStyleSheetSource(
    val path: String? = null,
    val css: String,
)

/** One element as the matcher sees it: its tag, its classes and its id. */
internal data class CssElement(
    /** HTML tag name, e.g. `"p"` or `"h1"`. */
    val tag: String,
    /**
     * Every class the element carries; a selector matches when its own required classes are a subset of
     * these.
     */
    val classes: Set<String> = emptySet(),
    /** The element's `id` attribute, or null if it has none. */
    val id: String? = null,
)

/** One compound selector — `h1`, `.note`, `h1.note#id` — as the pieces it has to match. */
private data class CompoundSelector(
    /** Required tag name, or null if the compound has no tag part (e.g. `.note` alone). */
    val tag: String?,
    /** Every class the compound requires; a matching element must carry all of these. */
    val classes: Set<String>,
    /** Required id, or null if the compound has no `#id` part. */
    val id: String?,
) {
    /**
     * Whether [element] satisfies this compound: its tag if any, its id if any, and every one of its
     * classes.
     */
    fun matches(element: CssElement): Boolean {
        if (tag != null && !tag.equals(element.tag, ignoreCase = true)) return false
        if (id != null && id != element.id) return false
        return element.classes.containsAll(classes)
    }

    /** CSS specificity, ordered id > class > tag, as the spec defines it. */
    val specificity: Int get() = (if (id != null) 10_000 else 0) + classes.size * 100 + (if (tag != null) 1 else 0)
}

/** A selector as its compound parts, innermost last; only the descendant combinator is honoured. */
private data class CssSelector(
    /** The selector's compounds, outermost ancestor first and the selected element itself last. */
    val compounds: List<CompoundSelector>,
) {
    /** Sum of every compound's own specificity — how CSS ranks one whole selector against another. */
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

/** One parsed `selector { declarations }` rule, plus its position in the sheet for specificity ties. */
private data class CssRule(
    /** What an element must match for [declarations] to apply. */
    val selector: CssSelector,
    /** What the rule declares, already narrowed to the properties [CssDeclarations] can represent. */
    val declarations: CssDeclarations,
    /**
     * Index in the order rules were parsed, across every sheet in [EpubCss.parse]'s list — a later rule
     * wins a specificity tie.
     */
    val order: Int,
)

/** A book's stylesheets, ready to answer what an element in it looks like. */
internal class EpubCss private constructor(
    private val rules: List<CssRule>,
    private val fontFaces: Map<String, String>,
) {
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

    /** The embedded-font href for the first named family in [fontFamily] this sheet actually defines. */
    fun resolvedFontHref(fontFamily: String?): String? {
        if (fontFamily.isNullOrBlank()) return null
        return splitFontFamilies(fontFamily).firstNotNullOfOrNull { family ->
            fontFaces[family.normalizeFontFamilyKey()]
        }
    }

    /** True when this book declared no usable rule or `@font-face` at all. */
    fun isEmpty(): Boolean = rules.isEmpty() && fontFaces.isEmpty()

    companion object {
        /** The no-op stylesheet: no rules and no font faces. */
        val Empty = EpubCss(emptyList(), emptyMap())

        /**
         * Parse raw stylesheet texts with no base paths, so relative `url(...)` cannot be resolved.
         *
         * The list order is still the linked order, so a later sheet wins a tie.
         */
        fun parse(sheets: List<String>): EpubCss = parseSources(sheets.map { CssStyleSheetSource(css = it) })

        /**
         * Parse [sheets] in linked order so a later sheet wins ties and keeps its own relative base path.
         */
        fun parseSources(sheets: List<CssStyleSheetSource>): EpubCss {
            val rules = mutableListOf<CssRule>()
            val fontFaces = linkedMapOf<String, String>()
            var order = 0
            sheets.forEach { source ->
                scanCssRules(
                    css = stripCssComments(source.css),
                    onFontFace = { body ->
                        parseFontFace(body, source.path)?.let { fontFace ->
                            fontFace.srcHref?.let { fontFaces[fontFace.familyName.normalizeFontFamilyKey()] = it }
                        }
                    },
                    onRule = { selectorText, body ->
                        val declarations = parseCssDeclarations(body)
                        if (declarations.isEmpty()) return@scanCssRules
                        selectorText.split(',').forEach { rawSelector ->
                            val selector = parseSelector(rawSelector) ?: return@forEach
                            rules += CssRule(selector, declarations, order)
                            order += 1
                        }
                    },
                )
            }
            return if (rules.isEmpty() && fontFaces.isEmpty()) Empty else EpubCss(rules, fontFaces)
        }
    }
}

/**
 * Walks one stylesheet's rules with real brace matching, so an at-rule's body is a *block* rather than
 * text a flat regex tears rules out of.
 *
 * This is the boundary that keeps conditional styling conditional. The previous regex extraction had no
 * notion of nesting, so `@media print { p { display:none } }` matched the inner `p { … }` as an ordinary
 * rule and hid those paragraphs on screen — styling the book stated for a medium this reader is not.
 * Here every `{` finds its matching `}` (quote-aware, so a brace inside a string never miscounts), and
 * what happens to the block depends on its prelude:
 *
 * - an ordinary selector: handed to [onRule] with its own body;
 * - `@media`: descended into only when [mediaQueryApplies] says the query names this medium, and the
 *   body is then scanned recursively, so rules and `@font-face`s nested in an applying query still count;
 * - `@font-face`: handed to [onFontFace];
 * - any other at-rule block (`@supports`, `@keyframes`, `@page`, vendor rules): skipped whole, body and
 *   all — the same "cannot judge → drop" policy the selector matcher applies to pseudo-classes;
 * - a statement at-rule (`@import`, `@charset`, `@namespace`): skipped to its `;`.
 *
 * Malformed input fails soft: an unclosed block consumes the rest of the sheet as its own body, and a
 * stray `}` is ignored, so one broken rule cannot shift every rule after it.
 *
 * @param css the stylesheet text, comments already stripped.
 * @param onFontFace called with each `@font-face` body found in an applying context.
 * @param onRule called with each ordinary rule's selector list text and declaration body.
 */
private fun scanCssRules(
    css: String,
    onFontFace: (body: String) -> Unit,
    onRule: (selectorText: String, body: String) -> Unit,
) {
    var index = 0
    var preludeStart = 0
    while (index < css.length) {
        when (css[index]) {
            '"', '\'' -> index = css.skipQuoted(index)
            ';' -> {
                // Ends a statement at-rule (`@import …;`) or stray junk between rules.
                preludeStart = index + 1
                index += 1
            }
            '}' -> {
                // A stray closer with no open block of its own; drop it and whatever led up to it.
                preludeStart = index + 1
                index += 1
            }
            '{' -> {
                val prelude = css.substring(preludeStart, index).trim()
                val bodyStart = index + 1
                val bodyEnd = css.matchingBraceEnd(index)
                val body = css.substring(bodyStart, bodyEnd)
                when {
                    prelude.startsWith("@media", ignoreCase = true) -> {
                        if (mediaQueryApplies(prelude.drop("@media".length))) {
                            scanCssRules(body, onFontFace, onRule)
                        }
                    }
                    prelude.startsWith("@font-face", ignoreCase = true) -> onFontFace(body)
                    prelude.startsWith("@") -> Unit
                    prelude.isNotEmpty() -> onRule(prelude, body)
                }
                index = if (bodyEnd < css.length) bodyEnd + 1 else css.length
                preludeStart = index
            }
            else -> index += 1
        }
    }
}

/**
 * Index of the `}` closing the block opened at [openIndex], or [String.length] when the sheet ends with
 * the block still open — the unclosed block then swallows the rest of the sheet rather than looping.
 * Quoted strings are skipped so a brace inside one never changes the depth.
 */
private fun String.matchingBraceEnd(openIndex: Int): Int {
    var depth = 1
    var index = openIndex + 1
    while (index < length) {
        when (this[index]) {
            '"', '\'' -> {
                index = skipQuoted(index)
                continue
            }
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) return index
            }
        }
        index += 1
    }
    return length
}

/** Index just past the quoted string starting at [quoteIndex]; an unterminated one runs to the end. */
private fun String.skipQuoted(quoteIndex: Int): Int {
    val quote = this[quoteIndex]
    var index = quoteIndex + 1
    while (index < length) {
        when (this[index]) {
            '\\' -> index += 1
            quote -> return index + 1
        }
        index += 1
    }
    return length
}

/**
 * Whether a `@media` query names a medium this reader is: `all`, `screen`, or nothing (which CSS reads
 * as `all`). Any branch of the comma-separated list that does — optionally `only`-prefixed — applies the
 * whole block.
 *
 * A branch carrying a feature condition (`(min-width: 60em)`, `(orientation: …)`) is *skipped*, not
 * guessed at: this engine resolves styles once at parse time and has no viewport to evaluate a feature
 * against, and applying a wide-screen override to every phone is exactly the kind of styling leak the
 * scan exists to stop. A `print`/`speech`/other-medium branch never applies. This is the same
 * cannot-judge → drop policy [parseCompound] applies to pseudo-classes.
 *
 * @param query the raw text between `@media` and the block's `{`.
 */
private fun mediaQueryApplies(query: String): Boolean = query.split(',').any { branch ->
    val cleaned = branch.trim().lowercase().removePrefix("only").trim()
    cleaned.isEmpty() || cleaned == "all" || cleaned == "screen"
}

/**
 * Parses [raw] (one comma-separated branch of a rule's selector list) into a [CssSelector], or null
 * for anything this matcher cannot safely judge: an at-rule body, a pseudo-element, or an attribute
 * selector. Guessing at any of those would risk applying a book's print-only or state-only styling to
 * every page, so the whole selector — and the rule it belongs to — is dropped instead.
 *
 * @param raw one selector, e.g. `"h1.title"` or `".quote p"`.
 * @return the parsed selector, or null if [raw] is empty or judged unsafe to match.
 */
private fun parseSelector(raw: String): CssSelector? {
    val cleaned = raw.trim()
    if (cleaned.isEmpty() || '@' in cleaned || '[' in cleaned || "::" in cleaned) return null
    val compounds = cleaned.split(CssCombinatorRegex).filter(String::isNotEmpty).map(::parseCompound)
    if (compounds.isEmpty() || compounds.any { it == null }) return null
    return CssSelector(compounds.filterNotNull())
}

/**
 * Parses one compound (`h1`, `.note`, `h1.note#id`) into a [CompoundSelector], or null when it cannot
 * be judged: a pseudo-class narrows a rule by a state this matcher cannot observe (`a:hover` is not the
 * same thing as `a`), and a universal selector (`*`) is not one this parser resolves either. Dropping
 * the compound — and so the whole selector — keeps hover- and print-only styling off a page that is
 * neither being hovered nor printed.
 *
 * @param raw one compound out of a selector, with no combinator.
 * @return the parsed compound, or null if [raw] is empty, or carries a pseudo-class or `*`.
 */
private fun parseCompound(rawCompound: String): CompoundSelector? {
    val raw = StatelessPseudoClassRegex.replace(rawCompound, "")
    if (raw.isEmpty() || ':' in raw || '*' in raw) return null
    val tag = raw.takeWhile { it != '.' && it != '#' }.takeIf(String::isNotEmpty)
    val classes = CssClassNameRegex.findAll(raw).map { it.groupValues[1] }.toSet()
    val id = CssIdRegex.find(raw)?.groupValues?.get(1)
    if (tag == null && classes.isEmpty() && id == null) return null
    return CompoundSelector(tag = tag, classes = classes, id = id)
}

/**
 * Parses a rule's declaration block into the subset of properties [CssDeclarations] can represent,
 * silently dropping anything else — the same policy [CssDeclarations]'s own class doc describes, applied
 * property by property as the block is walked.
 *
 * @param body raw text between a rule's braces, e.g. `"text-align:center;float:left"`.
 * @return the recognized declarations found in [body]; a property this cannot draw, or a malformed
 *   `name:value` pair, contributes nothing rather than failing the whole rule.
 */
internal fun parseCssDeclarations(body: String): CssDeclarations {
    var result = CssDeclarations.Empty
    body.split(';').forEach { declaration ->
        val name = declaration.substringBefore(':', "").trim().lowercase()
        val value = declaration.substringAfter(':', "").trim().stripImportant()
        if (name.isEmpty() || value.isEmpty()) return@forEach
        result = when (name) {
            "text-align" -> result.copy(textAlign = value.lowercase())
            "text-decoration", "text-decoration-line" -> result.copy(textDecoration = value.lowercase())
            "padding-top" -> result.copy(paddingTop = parseLength(value))
            "padding-right" -> result.copy(paddingRight = parseLength(value))
            "padding-bottom" -> result.copy(paddingBottom = parseLength(value))
            "padding-left" -> result.copy(paddingLeft = parseLength(value))
            "padding" -> parseMarginShorthand(value)?.let { sides ->
                result.copy(
                    paddingTop = sides.top,
                    paddingRight = sides.right,
                    paddingBottom = sides.bottom,
                    paddingLeft = sides.left,
                )
            } ?: result
            "font-size" -> result.copy(fontSize = parseLength(value))
            "font-weight" -> result.copy(fontWeight = value.lowercase())
            "font-style" -> result.copy(fontStyle = value.lowercase())
            "font-family" -> result.copy(fontFamily = value)
            "line-height" -> result.copy(lineHeight = parseLineHeight(value))
            "color" -> result.copy(color = value)
            "background-color" -> result.copy(backgroundColor = value)
            "text-indent" -> result.copy(textIndent = parseLength(value))
            "float" -> result.copy(float = value.lowercase())
            "width" -> result.copy(width = parseLength(value))
            "display" -> result.copy(display = value.lowercase())
            "margin-top" -> result.copy(marginTop = parseLength(value))
            "margin-bottom" -> result.copy(marginBottom = parseLength(value))
            "margin-left" -> result.copy(marginLeft = parseLength(value))
            "margin-right" -> result.copy(marginRight = parseLength(value))
            "margin" -> parseMarginShorthand(value)?.let { sides ->
                result.copy(
                    marginTop = sides.top,
                    marginRight = sides.right,
                    marginBottom = sides.bottom,
                    marginLeft = sides.left,
                )
            } ?: result
            "border" -> result.withBorder(parseBorderShorthand(value))
            "border-top" -> result.copy(borderTop = parseBorderShorthand(value))
            "border-right" -> result.copy(borderRight = parseBorderShorthand(value))
            "border-bottom" -> result.copy(borderBottom = parseBorderShorthand(value))
            "border-left" -> result.copy(borderLeft = parseBorderShorthand(value))
            "border-top-width" -> result.copy(borderTop = result.borderTop.mergeWidth(parseBorderWidthValue(value)))
            "border-right-width" -> result.copy(borderRight = result.borderRight.mergeWidth(parseBorderWidthValue(value)))
            "border-bottom-width" -> result.copy(borderBottom = result.borderBottom.mergeWidth(parseBorderWidthValue(value)))
            "border-left-width" -> result.copy(borderLeft = result.borderLeft.mergeWidth(parseBorderWidthValue(value)))
            "border-top-color" -> result.copy(borderTop = result.borderTop.mergeColor(value))
            "border-right-color" -> result.copy(borderRight = result.borderRight.mergeColor(value))
            "border-bottom-color" -> result.copy(borderBottom = result.borderBottom.mergeColor(value))
            "border-left-color" -> result.copy(borderLeft = result.borderLeft.mergeColor(value))
            "border-radius" -> result.copy(borderRadius = parseBorderRadius(value))
            else -> result
        }
    }
    return result
}

/** The `border` shorthand copied onto every side this engine preserves. */
private fun CssDeclarations.withBorder(border: CssBorder?): CssDeclarations = copy(
    borderTop = border ?: borderTop,
    borderRight = border ?: borderRight,
    borderBottom = border ?: borderBottom,
    borderLeft = border ?: borderLeft,
)

/** This border with [width] layered over its current width, if either exists. */
private fun CssBorder?.mergeWidth(width: CssLength?): CssBorder? = if (width == null && this == null) null else CssBorder(
    width = width ?: this?.width,
    color = this?.color,
)

/** This border with [color] layered over its current color, if either exists. */
private fun CssBorder?.mergeColor(color: String?): CssBorder? = if (color == null && this == null) null else CssBorder(
    width = this?.width,
    color = color ?: this?.color,
)

/**
 * Parses a single CSS length value into a [CssLength], or null if [value] has no number or carries an
 * unrecognized unit.
 *
 * A bare `0` is a length in CSS whatever the property — it is how nearly every EPUB writes its reset
 * (`margin: 0`) — so it resolves to zero rather than to "unstated". Any other unitless number is not a
 * length this engine can size anything from and is dropped.
 *
 * @param value raw declaration value, e.g. `"90%"`, `"1.5em"` or `"0"`.
 */
private fun parseLength(value: String): CssLength? {
    val trimmed = value.trim()
    trimmed.toFloatOrNull()?.let { bare -> return if (bare == 0f) CssLength.Px(0f) else null }
    val match = CssLengthRegex.find(trimmed) ?: return null
    val number = match.groupValues[1].toFloatOrNull() ?: return null
    return when (match.groupValues[2].lowercase()) {
        "%" -> CssLength.Percent(number / 100f)
        "em", "rem" -> CssLength.Em(number)
        "px" -> CssLength.Px(number)
        "pt" -> CssLength.Px(number * PxPerPoint)
        else -> null
    }
}

/** CSS pixels one point is worth: `1pt = 1/72in` against the reference `96dpi` pixel. */
private const val PxPerPoint = 96f / 72f

/** `line-height: 1.6` has no unit and means a factor of the element's own font size. */
private fun parseLineHeight(value: String): CssLineHeight? =
    value.trim().toFloatOrNull()?.let { CssLineHeight.Factor(it) }
        ?: parseLength(value)?.let { CssLineHeight.Length(it) }

/** The four sides the `margin` shorthand resolves to, in its 1..4 value forms. */
internal data class CssMarginSides(
    val top: CssLength? = null,
    val right: CssLength? = null,
    val bottom: CssLength? = null,
    val left: CssLength? = null,
)

/** Every side of the `margin` shorthand, expanded the way CSS defines its 1..4 value forms. */
private fun parseMarginShorthand(value: String): CssMarginSides? {
    val parts = value.trim().split(CssWhitespaceRegex).filter(String::isNotEmpty).map(::parseLength)
    return when (parts.size) {
        1 -> CssMarginSides(parts[0], parts[0], parts[0], parts[0])
        2 -> CssMarginSides(parts[0], parts[1], parts[0], parts[1])
        3 -> CssMarginSides(parts[0], parts[1], parts[2], parts[1])
        4 -> CssMarginSides(parts[0], parts[1], parts[2], parts[3])
        else -> null
    }
}

/** Width/color preserved from the `border` shorthand, ignoring style except for its presence. */
private fun parseBorderShorthand(value: String): CssBorder? {
    val parts = value.trim().split(CssWhitespaceRegex).filter(String::isNotEmpty)
    if (parts.isEmpty()) return null
    var width: CssLength? = null
    var color: String? = null
    var style: String? = null
    parts.forEach { part ->
        if (width == null) width = parseBorderWidthValue(part)
        if (style == null) style = parseBorderStyleKeyword(part)
        if (color == null && style != part.lowercase()) {
            if (parseBorderWidthValue(part) == null) color = part
        }
    }
    if (style == "none" || style == "hidden") return CssBorder(width = CssLength.Px(0f), color = color)
    if (style != null && width == null) width = CssLength.Px(3f)
    return if (width == null && color == null) null else CssBorder(width = width, color = color)
}

/** One border width value, including the border-only special case of a unitless zero. */
private fun parseBorderWidthValue(value: String): CssLength? =
    parseBorderWidthKeyword(value)
        ?: value.trim().takeIf { it == "0" || it == "+0" || it == "-0" || it == "0.0" }?.let { CssLength.Px(0f) }
        ?: parseLength(value)

/** Keyword border widths as the pixel values reading systems traditionally map them to. */
private fun parseBorderWidthKeyword(value: String): CssLength? = when (value.lowercase()) {
    "thin" -> CssLength.Px(1f)
    "medium" -> CssLength.Px(3f)
    "thick" -> CssLength.Px(5f)
    else -> null
}

/** Border styles this parser recognizes only so they are not misread as colors. */
private fun parseBorderStyleKeyword(value: String): String? = when (value.lowercase()) {
    "none", "hidden", "dotted", "dashed", "solid", "double", "groove", "ridge", "inset", "outset" -> value.lowercase()
    else -> null
}

/** The first radius component of `border-radius`; slash-separated elliptical radii are collapsed to one. */
private fun parseBorderRadius(value: String): CssLength? =
    value.substringBefore('/').trim().split(CssWhitespaceRegex).firstOrNull()?.let(::parseLength)

/**
 * Parses one `@font-face` body into the family it defines and the first relative/embedded `url(...)`
 * source this reader could later open.
 */
private fun parseFontFace(body: String, cssPath: String?): CssFontFace? {
    val declarations = parseCssDeclarations(body)
    val family = declarations.fontFamily?.let(::splitFontFamilies)?.firstOrNull()?.trimQuotes()?.takeIf(String::isNotEmpty) ?: return null
    val srcHref = FontFaceUrlRegex.find(body)?.groupValues?.get(1)?.trimQuotes()?.let { resolveContainerHref(cssPath, it) }
    return CssFontFace(familyName = family, srcHref = srcHref)
}

/** One `font-family` value split on commas and trimmed down to individual family names. */
private fun splitFontFamilies(value: String): List<String> =
    value.split(',').map(String::trim).map(String::trimQuotes).filter(String::isNotEmpty)

/** This possibly quoted CSS string token with one matching pair of wrapping quotes removed. */
private fun String.trimQuotes(): String = trim().removeSurrounding("\"").removeSurrounding("'")

/** Lowercased key form [EpubCss.resolvedFontHref] uses to match a family name against `@font-face`. */
private fun String.normalizeFontFamilyKey(): String = trimQuotes().lowercase()

/** A declaration value with one trailing `!important` stripped off, preserving the rest verbatim. */
private fun String.stripImportant(): String = replace(ImportantSuffixRegex, "").trim()

/** [css] with every CSS block comment blanked out, so a commented-out rule is never parsed as real. */
private fun stripCssComments(css: String): String = css.replace(CssCommentRegex, " ")

/** Matches a CSS block comment, spanning newlines, for [stripCssComments] to blank out. */
private val CssCommentRegex = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
/**
 * Splits a selector on whichever combinator separates its compounds; every combinator is read as a plain
 * descendant — see [CssSelector.matches].
 */
private val CssCombinatorRegex = Regex("""[\s>+~]+""")
/**
 * The pseudo-classes that describe how an element ordinarily looks rather than a state this matcher can
 * observe, and so are dropped from a compound instead of costing the whole rule.
 *
 * `a:link` and `a:visited` between them cover every link on a page, so a book writing
 * `a:link { text-decoration: none }` is saying its links carry no underline. Dropping that rule — which is
 * what happens to any selector this cannot judge — underlines every link the book has.
 */
private val StatelessPseudoClassRegex = Regex(":(link|visited)", RegexOption.IGNORE_CASE)

/** Captures one class name out of a compound, e.g. the `note` in `.note`. */
private val CssClassNameRegex = Regex("""\.([\w-]+)""")
/** Captures the id out of a compound, e.g. the `lead` in `#lead`. */
private val CssIdRegex = Regex("""#([\w-]+)""")
/**
 * Matches a length value and captures its number and unit; a leading `-` is allowed since a margin may
 * legitimately be negative.
 */
private val CssLengthRegex = Regex("""(-?[0-9.]+)\s*(%|em|rem|px|pt)""", RegexOption.IGNORE_CASE)
/** Splits shorthands like `margin`/`border-radius` on CSS whitespace. */
private val CssWhitespaceRegex = Regex("""\s+""")
/** A single trailing `!important` marker at the end of one declaration value. */
private val ImportantSuffixRegex = Regex("""\s*!important\s*$""", RegexOption.IGNORE_CASE)
/** Captures one `url(...)` payload out of an `@font-face src` declaration. */
private val FontFaceUrlRegex = Regex("""url\(([^)]+)\)""", RegexOption.IGNORE_CASE)

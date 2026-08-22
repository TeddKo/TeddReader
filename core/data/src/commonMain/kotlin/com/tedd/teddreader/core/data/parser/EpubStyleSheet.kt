package com.tedd.teddreader.core.data.parser

/** A CSS width, in the units EPUB stylesheets actually use to size a picture. */
internal sealed interface CssWidth {
    /** `width: 75%`, as a fraction of the containing block. */
    data class Percent(val fraction: Float) : CssWidth

    /** `width: 6.5em`. */
    data class Em(val value: Float) : CssWidth
}

/**
 * The image widths an EPUB's own stylesheets declare.
 *
 * This is how these books state how big a picture is. The markup carries no width at all — only a
 * class, such as `img_full` or `img_blockh` — and the stylesheet turns that into `width: 90%` or
 * `width: 75%`. Reading it is the difference between a decorative rule drawn at its true hairline
 * height and the same rule blown up to fill the column, and between a full-page plate and a thumbnail.
 */
internal data class EpubStyleSheet(
    /** Width set on the element that carries the class, e.g. `.img_full{width:90%}`. */
    val containerWidths: Map<String, CssWidth> = emptyMap(),
    /** Width set on an image inside it, e.g. `.img_britg img{width:100%}`. */
    val imageWidths: Map<String, CssWidth> = emptyMap(),
    /** Width a bare `img{width:…}` rule gives every picture the classes do not size themselves. */
    val defaultImageWidth: CssWidth? = null,
) {
    /**
     * Width for an image whose ancestors carry [classNames], nearest first.
     *
     * A rule on the image itself is relative to its container, so `.img_britg{width:6.5em}` together
     * with `.img_britg img{width:100%}` resolves to 6.5em rather than the full column.
     */
    fun widthFor(classNames: List<String>): CssWidth? {
        classNames.forEach { name ->
            val onImage = imageWidths[name]
            val onContainer = containerWidths[name]
            if (onImage != null) {
                return when (onImage) {
                    is CssWidth.Em -> onImage
                    is CssWidth.Percent -> when (onContainer) {
                        is CssWidth.Em -> CssWidth.Em(onContainer.value * onImage.fraction)
                        is CssWidth.Percent -> CssWidth.Percent(onContainer.fraction * onImage.fraction)
                        null -> CssWidth.Percent(onImage.fraction)
                    }
                }
            }
            if (onContainer != null) return onContainer
        }
        return defaultImageWidth
    }

    /** True when this sheet declares no width at all — a book that never sizes a picture through CSS. */
    fun isEmpty(): Boolean = containerWidths.isEmpty() && imageWidths.isEmpty() && defaultImageWidth == null
}

/**
 * Collect the width declarations from one stylesheet, layered over [base] so a later sheet in the
 * document's `<link>` order wins the cascade the same way a browser would resolve it.
 *
 * A selector's key compounds are chosen so a width resolves against whichever element actually carries
 * the class: when the selector targets `img` directly (e.g. `.wrapper img`), the image is sized
 * relative to its wrapper rather than itself, so every compound but the last — the last being `img` —
 * becomes the key; otherwise the selector's own last compound is the key. A bare `img{width:…}` rule
 * with no class at all is treated specially too, as [EpubStyleSheet.defaultImageWidth]: plenty of books
 * state their picture sizes exactly this way, and keying every rule by a class name alone read those
 * books as declaring nothing, leaving every one of their images to fall back to filling the full column.
 */
internal fun parseEpubStyleSheet(css: String, base: EpubStyleSheet = EpubStyleSheet()): EpubStyleSheet {
    val containerWidths = base.containerWidths.toMutableMap()
    val imageWidths = base.imageWidths.toMutableMap()
    var defaultImageWidth = base.defaultImageWidth

    CssRuleRegex.findAll(stripCssComments(css)).forEach { rule ->
        val width = declaredWidth(rule.groupValues[2]) ?: return@forEach
        rule.groupValues[1].split(',').forEach { selector ->
            val compounds = selector.trim().split(Regex("""[\s>+~]+""")).filter(String::isNotEmpty)
            if (compounds.isEmpty()) return@forEach
            val targetsImage = compounds.last().substringBefore('.').substringBefore(':').equals("img", ignoreCase = true)
            val keyCompounds = if (targetsImage) compounds.dropLast(1) else listOf(compounds.last())
            val classes = keyCompounds.flatMap { compound ->
                CssClassRegex.findAll(compound).map { it.groupValues[1] }.toList()
            }
            if (targetsImage && classes.isEmpty()) {
                defaultImageWidth = width
                return@forEach
            }
            val target = if (targetsImage) imageWidths else containerWidths
            classes.forEach { className -> target[className] = width }
        }
    }
    return EpubStyleSheet(
        containerWidths = containerWidths,
        imageWidths = imageWidths,
        defaultImageWidth = defaultImageWidth,
    )
}

/**
 * The `width` this rule's declaration block states, in a unit that actually sizes a picture.
 *
 * `px` is deliberately not among the recognized units here: a pixel width does not scale with the
 * reader's own font size the way `%` and `em` do, so it is not useful as a picture size in this reader
 * and is read as no declared width at all. A zero or negative value is likewise treated as undeclared.
 *
 * @param declarations the raw text between a rule's braces, e.g. `"margin:0 auto;width:90%;"`.
 * @return the declared width, or null if the block has no `width` in a recognized unit.
 */
private fun declaredWidth(declarations: String): CssWidth? {
    return when (val width = parseCssDeclarations(declarations).width) {
        is CssLength.Percent -> width.fraction.takeIf { it > 0f }?.let(CssWidth::Percent)
        is CssLength.Em -> width.value.takeIf { it > 0f }?.let(CssWidth::Em)
        else -> null
    }
}

/**
 * [css] with every CSS block comment blanked out, so a commented-out rule is never parsed as a real one.
 */
private fun stripCssComments(css: String): String = css.replace(CssCommentRegex, " ")

/** Matches a CSS block comment, spanning newlines, for [stripCssComments] to blank out. */
private val CssCommentRegex = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

/**
 * Splits a stylesheet into `selector { declarations }` pairs; the two capture groups are the selector list
 * and the declaration body.
 */
private val CssRuleRegex = Regex("""([^{}]+)\{([^{}]*)\}""")

/** Captures one class name out of a compound selector, e.g. the `dedi` in `.dedi`. */
private val CssClassRegex = Regex("""\.([\w-]+)""")

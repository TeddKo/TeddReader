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

    fun isEmpty(): Boolean = containerWidths.isEmpty() && imageWidths.isEmpty() && defaultImageWidth == null
}

/**
 * Collect the width declarations from one stylesheet, layered over [base] so a later sheet in the
 * document's `<link>` order wins the cascade the same way a browser would resolve it.
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
            // `.wrapper img` sizes the image relative to `.wrapper`, so it is keyed by the wrapper.
            val keyCompounds = if (targetsImage) compounds.dropLast(1) else listOf(compounds.last())
            val classes = keyCompounds.flatMap { compound ->
                CssClassRegex.findAll(compound).map { it.groupValues[1] }.toList()
            }
            if (targetsImage && classes.isEmpty()) {
                // A bare `img{width:…}` sizes every picture in the book. Books that state their
                // picture sizes this way rather than through a class were being read as stating
                // nothing at all, and every one of their images fell back to the full column.
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

private fun declaredWidth(declarations: String): CssWidth? {
    val match = CssWidthRegex.find(declarations) ?: return null
    val value = match.groupValues[1].toFloatOrNull() ?: return null
    if (value <= 0f) return null
    return when (match.groupValues[2].lowercase()) {
        "%" -> CssWidth.Percent(value / 100f)
        "em", "rem" -> CssWidth.Em(value)
        else -> null
    }
}

private fun stripCssComments(css: String): String = css.replace(CssCommentRegex, " ")

private val CssCommentRegex = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val CssRuleRegex = Regex("""([^{}]+)\{([^{}]*)\}""")
private val CssClassRegex = Regex("""\.([\w-]+)""")

// Anchored at a declaration boundary so `max-width` or `min-width` never reads as `width`.
private val CssWidthRegex = Regex("""(?:^|[;{\s])width\s*:\s*([0-9.]+)\s*(%|em|rem|px)""", RegexOption.IGNORE_CASE)

package com.tedd.teddreader.core.ui.reader

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.designsystem.readerTextStyle

/**
 * Every parameter a text layout depends on, derived once and shared verbatim by the page breaker, the
 * float fitter, and the page surface.
 *
 * A page break is a pure function of (text, styles, fontset, pane px). The breaker and the surface used
 * to each derive these numbers from the same style and pane — the same arithmetic, written twice — and
 * any drift between the two meant a page measured with one set of values and drawn with another, which
 * is how a page clips its last line. Building the tuple in one place makes that drift unrepresentable.
 *
 * @property textStyle the base text style the layout measures and draws with.
 * @property widthPx the drawn text area's width in pixels — the pane minus its margins, not the pane.
 * @property heightPx the drawn text area's height in pixels, on the same terms.
 * @property fontPx the reader's font size in device pixels, which converts line geometry to em.
 * @property lineWidthEm the text column width in em, bounding images like `max-width: 100%`.
 * @property maxHeightEm the page height in em, bounding images like `max-height`.
 * @property emInPx CSS pixels per em (accessibility scale only, not density) — an image's intrinsic
 * size is in CSS pixels, which are density-independent.
 * @property embeddedFontFamiliesByHref resolved embedded font families by EPUB href; empty when a
 * user-selected reader font suppresses publisher fonts.
 * @property publisherFontsEnabled whether publisher-requested font families apply at all.
 * @property lineHeightMultiplier the reader's line-height slider value, anchored by consumers at the
 * slider default so a book's own line height survives it.
 * @property fontWeight the reader's chosen base body weight (see [ReaderStyle.fontWeight]), which
 * [buildReaderSemanticText] needs on both the measuring and the drawing side so a page's emphasis —
 * headings, bold runs, table header cells — is scaled up from the same base the breaker measured it at
 * and the surface draws it at; reading this back out of [textStyle] instead is not an option, since
 * [TextStyle.fontWeight] is nullable and does not carry the guarantee this field does.
 */
data class ReaderLayoutInputs(
    val textStyle: TextStyle,
    val widthPx: Int,
    val heightPx: Int,
    val fontPx: Float,
    val lineWidthEm: Float,
    val maxHeightEm: Float,
    val emInPx: Float,
    val embeddedFontFamiliesByHref: Map<String, FontFamily>,
    val publisherFontsEnabled: Boolean,
    val lineHeightMultiplier: Float,
    val fontWeight: Int,
)

/**
 * Derives the one [ReaderLayoutInputs] both measurement and drawing must share for a pane of
 * [widthPx] × [heightPx].
 *
 * The two em conversions inside are deliberately different: text geometry goes through [density] so a
 * page is measured in the pixels it is drawn in, while [ReaderLayoutInputs.emInPx] uses the font size
 * scaled only by the accessibility font scale, because an image's intrinsic size is in CSS pixels.
 *
 * @param style the reading style; font size, family choice, line height slider, and font weight all feed
 * the inputs.
 * @param widthPx the drawn text area's width in pixels.
 * @param heightPx the drawn text area's height in pixels.
 * @param density the composition's density, so measurement uses drawing pixels.
 * @param embeddedFontFamiliesByHref resolved embedded fonts; suppressed here when the reader has its
 * own font selected, so no consumer needs to re-apply that rule.
 */
fun readerLayoutInputs(
    style: ReaderStyle,
    widthPx: Int,
    heightPx: Int,
    density: Density,
    embeddedFontFamiliesByHref: Map<String, FontFamily> = emptyMap(),
): ReaderLayoutInputs {
    val fontPx = with(density) { style.fontSizeSp.sp.toPx() }
    return ReaderLayoutInputs(
        textStyle = style.readerTextStyle(),
        widthPx = widthPx,
        heightPx = heightPx,
        fontPx = fontPx,
        lineWidthEm = if (fontPx > 0f) widthPx / fontPx else 0f,
        maxHeightEm = if (fontPx > 0f) heightPx / fontPx else 0f,
        emInPx = style.fontSizeSp * density.fontScale,
        embeddedFontFamiliesByHref = if (style.fontFamilyName == null) embeddedFontFamiliesByHref else emptyMap(),
        publisherFontsEnabled = style.fontFamilyName == null,
        lineHeightMultiplier = style.lineHeightMultiplier,
        fontWeight = style.fontWeight,
    )
}

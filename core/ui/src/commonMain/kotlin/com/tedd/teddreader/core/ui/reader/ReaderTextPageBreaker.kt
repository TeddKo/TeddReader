package com.tedd.teddreader.core.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.layoutKey
import kotlin.math.roundToInt

/**
 * Page breaker backed by the same text layout the reader page draws with.
 *
 * [widthPx] and [heightPx] must be the drawn text area, so a page holds exactly the lines that fit
 * in it: the break follows measured line boxes rather than a `height / lineHeight` count, which is
 * what keeps the last line whole when the reader's font size or line height changes.
 *
 * ponytail: lays the whole document out once per style/size change. Fine for the documents this
 * reader opens; switch to chunked measurement if a book ever makes that layout too slow.
 *
 * The `remember` key is the style's layout key, not the whole style. Colour rides along in `TextStyle`, so
 * keying on the style handed back a different breaker on every theme switch and measured every page in the
 * book again for a change that cannot move a line. The captured style keeps whatever colour it was built
 * with, which is harmless because nothing here draws.
 *
 * A pane of zero width or height yields null rather than a breaker. A breaker built before the pane is
 * measured can only answer "I measured nothing", and handing that to the reader silently disables measured
 * pagination for the whole book — every page then comes from the estimate, which cannot know the line
 * height the book's stylesheet asks for. EPUB also waits until every referenced embedded font has either
 * resolved or failed, so the first measurement uses the same font families the page surface will draw.
 *
 * Two em conversions appear inside, deliberately different. The text one goes through [LocalDensity] so a
 * page is measured in the same pixels it will be drawn in (see EpubPageSurface). The image one uses the
 * font size scaled only by the accessibility font scale, because an image's intrinsic size is in CSS
 * pixels, which are density-independent.
 *
 * A page holds a line back from its usable height. The chapter is laid out once, in full, and split by line
 * position; each page is then drawn on its own, and the two layouts never agree to the pixel — justified
 * text, and an opening line that is a middle line here but a first line there, shift things a little — so a
 * page filled to the last hairline loses the bottom of its final line. One line is the smallest slack that
 * absorbs that rather than most of it: it costs a page a line where the fit was that tight, and a clipped
 * line costs the reader a line of the book. The first line's height is only a sample, since a chapter mixes
 * heading, quote and picture lines and the line landing on the boundary may be taller, so [PageSlack]
 * floors the slack at a share of the page instead of trusting whichever line happened to be measured.
 *
 * A page then breaks at the first line whose *measured box bottom* passes that usable height, which stays
 * correct when line boxes are not uniform.
 *
 * @param style the reading style; only its layout key affects where pages break.
 * @param widthPx the drawn text area's width in pixels — the pane minus its margins, not the pane.
 * @param heightPx the drawn text area's height in pixels, on the same terms.
 * @param embeddedFontFamiliesByHref resolved embedded font families keyed by href, shared with the page surface.
 * @param canMeasure whether the caller has enough viewport/font state to trust a first measurement yet.
 * @return a breaker that measures with the reader's own text layout, or null while the pane has no real
 * size or the caller is still waiting on required font resolution — in which case the caller must not treat
 * the absence as "no pages".
 */
@Composable
fun rememberReaderPageBreaker(
    style: ReaderStyle,
    widthPx: Int,
    heightPx: Int,
    embeddedFontFamiliesByHref: Map<String, androidx.compose.ui.text.font.FontFamily> = emptyMap(),
    canMeasure: Boolean = true,
): ReaderPageBreaker? {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    return remember(measurer, style.layoutKey(), widthPx, heightPx, density, embeddedFontFamiliesByHref, canMeasure) {
        if (!canMeasure || widthPx <= 0 || heightPx <= 0) return@remember null
        val inputs = readerLayoutInputs(
            style = style,
            widthPx = widthPx,
            heightPx = heightPx,
            density = density,
            embeddedFontFamiliesByHref = embeddedFontFamiliesByHref,
        )
        val floatFitter = readerFloatTextFitter(
            measurer = measurer,
            inputs = inputs,
            publisherColorsEnabled = false,
        )
        ReaderPageBreaker { text, blocks ->
            if (text.isEmpty()) {
                IntArray(0)
            } else {
                val semanticText = buildReaderSemanticText(
                    text = text,
                    blocks = blocks,
                    lineWidthEm = inputs.lineWidthEm,
                    maxHeightEm = inputs.maxHeightEm,
                    emInPx = inputs.emInPx,
                    embeddedFontFamiliesByHref = inputs.embeddedFontFamiliesByHref,
                    publisherColorsEnabled = false,
                    publisherFontsEnabled = inputs.publisherFontsEnabled,
                    floatTextFitter = floatFitter,
                    lineHeightMultiplier = inputs.lineHeightMultiplier,
                    baseFontWeight = inputs.fontWeight,
                )
                val layout = measurer.measure(
                    text = semanticText.annotatedString,
                    style = inputs.textStyle,
                    constraints = Constraints(maxWidth = widthPx),
                    placeholders = semanticText.placeholders.map { placeholder ->
                        AnnotatedString.Range(
                            item = placeholder.placeholder,
                            start = placeholder.start,
                            end = placeholder.end,
                        )
                    },
                )
                val starts = mutableListOf(0)
                var pageTop = layout.getLineTop(0)
                val firstLineHeight = layout.getLineBottom(0) - layout.getLineTop(0)
                val usableHeight = heightPx - maxOf(firstLineHeight * LineSlack, heightPx * PageSlack)
                for (line in 1 until layout.lineCount) {
                    if (layout.getLineBottom(line) - pageTop > usableHeight) {
                        starts += semanticText.sourceOffsetFor(layout.getLineStart(line))
                        pageTop = layout.getLineTop(line)
                    }
                }
                starts.toIntArray()
            }
        }
    }
}

/**
 * Builds the shared float fitter used by both pagination and rendering.
 *
 * The fitter measures the remaining paragraph beside a floated image once, finds the last full line that fits
 * under the image height, then rebuilds only that consumed prefix as semantic text. Sharing the callback is
 * what keeps the placeholder's consumed source range identical in the breaker and the page surface.
 */
fun readerFloatTextFitter(
    measurer: TextMeasurer,
    inputs: ReaderLayoutInputs,
    publisherColorsEnabled: Boolean,
): ReaderFloatTextFitter = { request ->
    val paragraphStart = maxOf(request.paragraphRange.start, request.imageBlock.range.end)
    val paragraphEnd = request.paragraphRange.end
    if (paragraphEnd <= paragraphStart) {
        emptyFloatPlacement(paragraphStart)
    } else {
        val availableWidthPx = (inputs.widthPx - request.imageSize.widthEm * inputs.fontPx).roundToInt().coerceAtLeast(0)
        if (availableWidthPx <= 0) {
            emptyFloatPlacement(paragraphStart)
        } else {
            val paragraphStartLocal = (paragraphStart - request.range.start).toInt()
            val paragraphEndLocal = (paragraphEnd - request.range.start).toInt()
            val paragraphText = request.text.substring(paragraphStartLocal, paragraphEndLocal)
            val paragraphRange = TextRange(paragraphStart, paragraphEnd)
            val paragraphSemantic = buildReaderSemanticText(
                text = paragraphText,
                blocks = request.blocks,
                range = paragraphRange,
                lineWidthEm = inputs.lineWidthEm,
                maxHeightEm = inputs.maxHeightEm,
                emInPx = inputs.emInPx,
                embeddedFontFamiliesByHref = inputs.embeddedFontFamiliesByHref,
                publisherColorsEnabled = publisherColorsEnabled,
                publisherFontsEnabled = inputs.publisherFontsEnabled,
                floatTextFitter = null,
                lineHeightMultiplier = inputs.lineHeightMultiplier,
                baseFontWeight = inputs.fontWeight,
            )
            if (paragraphSemantic.annotatedString.text.isEmpty()) {
                emptyFloatPlacement(paragraphStart)
            } else {
                val paragraphLayout = measurer.measure(
                    text = paragraphSemantic.annotatedString,
                    style = inputs.textStyle,
                    constraints = Constraints(maxWidth = availableWidthPx),
                    placeholders = paragraphSemantic.placeholders.map { placeholder ->
                        AnnotatedString.Range(placeholder.placeholder, placeholder.start, placeholder.end)
                    },
                )
                var lastLine = -1
                for (line in 0 until paragraphLayout.lineCount) {
                    if (paragraphLayout.getLineBottom(line) <= request.imageSize.heightEm * inputs.fontPx) lastLine = line else break
                }
                if (lastLine < 0) {
                    emptyFloatPlacement(paragraphStart)
                } else {
                    val displayEnd = paragraphLayout.getLineEnd(lastLine, visibleEnd = false)
                    val sourceEnd = paragraphSemantic.sourceOffsetFor(displayEnd)
                    val fittedRange = TextRange(paragraphStart, sourceEnd.toLong())
                    val fittedLength = (sourceEnd - paragraphStart.toInt()).coerceAtLeast(0)
                    val fittedText = buildReaderSemanticText(
                        text = paragraphText.substring(0, fittedLength.coerceAtMost(paragraphText.length)),
                        blocks = request.blocks,
                        range = fittedRange,
                        lineWidthEm = inputs.lineWidthEm,
                        maxHeightEm = inputs.maxHeightEm,
                        emInPx = inputs.emInPx,
                        embeddedFontFamiliesByHref = inputs.embeddedFontFamiliesByHref,
                        publisherColorsEnabled = publisherColorsEnabled,
                        publisherFontsEnabled = inputs.publisherFontsEnabled,
                        floatTextFitter = null,
                        lineHeightMultiplier = inputs.lineHeightMultiplier,
                        baseFontWeight = inputs.fontWeight,
                    )
                    ReaderFloatPlacement(fittedRange, fittedText)
                }
            }
        }
    }
}

/** Lines held back from each page, so the drawn page may differ from the measured one by that much. */
private const val LineSlack = 1.0f

/** Floor on that slack as a share of the page, for pages whose lines vary in height. */
private const val PageSlack = 0.04f

private fun emptyFloatPlacement(sourceStart: Long): ReaderFloatPlacement =
    ReaderFloatPlacement(
        nestedRange = TextRange(sourceStart, sourceStart),
        nestedText = ReaderSemanticText(AnnotatedString(""), intArrayOf(sourceStart.toInt()), emptyList()),
    )

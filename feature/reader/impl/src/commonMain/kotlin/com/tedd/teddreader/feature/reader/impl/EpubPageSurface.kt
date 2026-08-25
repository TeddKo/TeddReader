package com.tedd.teddreader.feature.reader.impl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderBorder
import com.tedd.teddreader.core.common.model.ReaderBoxStyle
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderFloat
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.layoutKey
import com.tedd.teddreader.core.designsystem.readerTextStyle
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.designsystem.toColor
import com.tedd.teddreader.core.ui.component.TeddDivider
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.visual_page_unavailable
import com.tedd.teddreader.core.ui.reader.ReaderContainerDecoration
import com.tedd.teddreader.core.ui.reader.ReaderFloatContent
import com.tedd.teddreader.core.ui.reader.ReaderPlaceholder
import com.tedd.teddreader.core.ui.reader.buildReaderSemanticText
import com.tedd.teddreader.core.ui.reader.readerFloatTextFitter
import com.tedd.teddreader.core.ui.reader.readerLayoutInputs
import com.tedd.teddreader.core.ui.reader.readerReferencedFontHrefs
import org.jetbrains.compose.resources.stringResource

/**
 * Draws one EPUB text page with the same semantic text the page breaker measured.
 *
 * The page surface rebuilds semantic text with the same text style, font families, widths, and float fitting
 * callback the breaker used, then lets Compose lay that result out once for actual drawing. Container
 * backgrounds and borders are painted from the final text layout geometry so page decorations track the same
 * lines the glyphs occupy.
 *
 * @param page the already-sliced page content to draw, including embedded image bytes and failure state.
 * @param style the style this page's slices were measured under, including theme mode and any user
 * font override — colour rides in from the live style regardless; see `ReaderPagePane` in
 * `ReaderScreen.kt` for what actually chooses which style reaches this parameter.
 * @param embeddedFontFamiliesByHref resolved embedded font families keyed by EPUB href, shared with the page
 * breaker so measurement and rendering stay in sync.
 * @param modifier applied to the page root.
 */
@Composable
internal fun EpubPageSurface(
    page: ReaderPageUi,
    style: ReaderStyle,
    embeddedFontFamiliesByHref: Map<String, FontFamily> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val plateBlock = epubFullPagePlate(text = page.text, blocks = page.blocks)
    val readerTextStyle = style.readerTextStyle()
    val baseTextColor = style.textColor.toColor()
    val publisherColorsEnabled = style.themeMode == ReaderThemeMode.PUBLISHER
    if (plateBlock != null) {
        EpubImageBox(
            imageBytes = plateBlock.imageHref?.let(page.embeddedImages::get),
            label = plateBlock.label,
            isFailed = plateBlock.imageHref != null && plateBlock.imageHref in page.failedEmbeddedImageHrefs,
            boxStyle = plateBlock.style?.boxStyle,
            currentColor = plateBlock.style?.foregroundColor.takeIf { publisherColorsEnabled }?.toColor() ?: baseTextColor,
            usePublisherColors = publisherColorsEnabled,
            modifier = modifier,
        )
        return
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // The one derivation the breaker also uses — same struct, same numbers, so a page is drawn with
        // exactly the values it was measured with.
        val inputs = remember(style.layoutKey(), widthPx, heightPx, density, embeddedFontFamiliesByHref) {
            readerLayoutInputs(
                style = style,
                widthPx = widthPx.toInt(),
                heightPx = heightPx.toInt(),
                density = density,
                embeddedFontFamiliesByHref = embeddedFontFamiliesByHref,
            )
        }
        val fontPx = inputs.fontPx
        val floatFitter = remember(measurer, inputs, publisherColorsEnabled) {
            readerFloatTextFitter(
                measurer = measurer,
                inputs = inputs,
                publisherColorsEnabled = publisherColorsEnabled,
            )
        }
        val semanticText = remember(page.text, page.blocks, page.textRange, inputs, publisherColorsEnabled, floatFitter, style.layoutKey()) {
            buildReaderSemanticText(
                text = page.text,
                blocks = page.blocks,
                range = page.textRange ?: com.tedd.teddreader.core.common.model.TextRange(0, page.text.length.toLong()),
                lineWidthEm = inputs.lineWidthEm,
                maxHeightEm = inputs.maxHeightEm,
                emInPx = inputs.emInPx,
                embeddedFontFamiliesByHref = inputs.embeddedFontFamiliesByHref,
                publisherColorsEnabled = publisherColorsEnabled,
                publisherFontsEnabled = inputs.publisherFontsEnabled,
                floatTextFitter = floatFitter,
                lineHeightMultiplier = inputs.lineHeightMultiplier,
            )
        }
        val inlineContent = remember(semanticText.placeholders, page.embeddedImages, page.failedEmbeddedImageHrefs, readerTextStyle, baseTextColor, publisherColorsEnabled) {
            semanticText.placeholders.associate { placeholder ->
                placeholder.id to InlineTextContent(placeholder.placeholder) {
                    EpubInlinePlaceholder(
                        placeholder = placeholder,
                        imageBytes = placeholder.href?.let(page.embeddedImages::get),
                        isFailed = placeholder.href != null && placeholder.href in page.failedEmbeddedImageHrefs,
                        textStyle = readerTextStyle,
                        baseTextColor = baseTextColor,
                        publisherColorsEnabled = publisherColorsEnabled,
                    )
                }
            }
        }
        var textLayout by remember(semanticText.annotatedString) { mutableStateOf<TextLayoutResult?>(null) }

        Layout(
            content = {
                BasicText(
                    text = semanticText.annotatedString,
                    style = readerTextStyle,
                    inlineContent = inlineContent,
                    onTextLayout = { textLayout = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            textLayout?.let { layout ->
                                drawContainerDecorations(
                                    layout = layout,
                                    decorations = semanticText.containerDecorations,
                                    baseTextColor = baseTextColor,
                                    publisherColorsEnabled = publisherColorsEnabled,
                                    emPx = fontPx,
                                )
                            }
                        },
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    semanticText.containerDecorations
                        .filter(ReaderContainerDecoration::isPageContainer)
                        .forEach { decoration ->
                            drawReaderBoxBackground(
                                boxStyle = decoration.boxStyle,
                                rect = Rect(Offset.Zero, size),
                                usePublisherColors = publisherColorsEnabled,
                            )
                            drawReaderBoxBorders(
                                boxStyle = decoration.boxStyle,
                                rect = Rect(Offset.Zero, size),
                                currentColor = decoration.foregroundColor?.toColor() ?: baseTextColor,
                                usePublisherColors = publisherColorsEnabled,
                                drawTop = decoration.startsHere,
                                drawBottom = decoration.endsHere,
                            )
                        }
                },
        ) { measurables, constraints ->
            val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val pageHeight = constraints.maxHeight
            val top = if (page.isSectionTail) ((pageHeight - placeable.height) / 2).coerceAtLeast(0) else 0
            layout(constraints.maxWidth, pageHeight) { placeable.place(0, top) }
        }
    }
}

/**
 * Whether an EPUB page has enough embedded-font state to allow its first measured pagination.
 *
 * A user-selected reader font bypasses publisher fonts entirely, so no wait is needed. Otherwise every font
 * href referenced by the page must have either resolved successfully or reached a known failed state before
 * the breaker is allowed to measure.
 */
internal fun canMeasureEpubPage(
    page: ReaderPageUi,
    style: ReaderStyle,
    resolvedFontFamiliesByHref: Map<String, FontFamily> = emptyMap(),
    failedResolvedFontHrefs: Set<String> = emptySet(),
): Boolean {
    if (style.fontFamilyName != null) return true
    val referenced = readerReferencedFontHrefs(page.blocks)
    return referenced.all { href ->
        href in resolvedFontFamiliesByHref ||
            href in page.failedEmbeddedFontHrefs ||
            href in failedResolvedFontHrefs
    }
}

/**
 * The composable an EPUB page's inline placeholder resolves to at the spot [buildReaderSemanticText]
 * reserved for it — an image, a floated image plus nested text, a separator rule, or nothing for placeholder
 * kinds that carry no visual content of their own.
 *
 * @param placeholder the placeholder's kind, label, float metadata, and inherited colors, as produced by
 * [buildReaderSemanticText].
 * @param imageBytes the decoded image bytes for this placeholder, when its kind is an image and the bytes are available.
 * @param isFailed whether this placeholder's image previously failed to decode.
 * @param textStyle the resolved text style the nested float text must draw with.
 * @param baseTextColor the reader theme's fallback text color when publisher colors are disabled.
 * @param publisherColorsEnabled whether publisher colors should be honored for this page.
 */
@Composable
private fun EpubInlinePlaceholder(
    placeholder: ReaderPlaceholder,
    imageBytes: ByteArray?,
    isFailed: Boolean,
    textStyle: TextStyle,
    baseTextColor: Color,
    publisherColorsEnabled: Boolean,
) {
    val floatContent = placeholder.floatContent
    when {
        floatContent != null -> EpubFloatPlaceholder(
            floatContent = floatContent,
            imageBytes = imageBytes,
            label = placeholder.label,
            isFailed = isFailed,
            imageBoxStyle = placeholder.boxStyle,
            textStyle = textStyle,
            currentColor = placeholder.foregroundColor.takeIf { publisherColorsEnabled }?.toColor() ?: baseTextColor,
            usePublisherColors = publisherColorsEnabled,
        )
        placeholder.kind == ReaderBlockKind.SEPARATOR -> TeddDivider(modifier = Modifier.fillMaxSize())
        placeholder.kind == ReaderBlockKind.IMAGE || placeholder.kind == ReaderBlockKind.COVER_IMAGE -> EpubImageBox(
            imageBytes = imageBytes,
            label = placeholder.label,
            isFailed = isFailed,
            boxStyle = placeholder.boxStyle,
            currentColor = placeholder.foregroundColor.takeIf { publisherColorsEnabled }?.toColor() ?: baseTextColor,
            usePublisherColors = publisherColorsEnabled,
        )
        else -> Box(modifier = Modifier.fillMaxSize())
    }
}

/**
 * Lays out a floated image placeholder as an inline full-column box whose content is a row-like split:
 * image on the start/end edge and the fitted leading paragraph slice beside it.
 */
@Composable
private fun EpubFloatPlaceholder(
    floatContent: ReaderFloatContent,
    imageBytes: ByteArray?,
    label: String?,
    isFailed: Boolean,
    imageBoxStyle: ReaderBoxStyle?,
    textStyle: TextStyle,
    currentColor: Color,
    usePublisherColors: Boolean,
) {
    Layout(
        content = {
            EpubImageBox(
                imageBytes = imageBytes,
                label = label,
                isFailed = isFailed,
                boxStyle = imageBoxStyle,
                currentColor = currentColor,
                usePublisherColors = usePublisherColors,
            )
            BasicText(
                text = floatContent.text.annotatedString,
                style = textStyle,
                inlineContent = emptyMap(),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val imageFraction = (floatContent.imageWidthEm / floatContent.columnWidthEm).coerceIn(0f, 1f)
        val imageWidthPx = (constraints.maxWidth * imageFraction).toInt().coerceIn(0, constraints.maxWidth)
        val imagePlaceable = measurables[0].measure(Constraints.fixed(width = imageWidthPx, height = constraints.maxHeight))
        val textPlaceable = measurables[1].measure(
            constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth - imagePlaceable.width).coerceAtLeast(0)),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (floatContent.side == ReaderFloat.START) {
                imagePlaceable.place(0, 0)
                textPlaceable.place(imagePlaceable.width, 0)
            } else {
                textPlaceable.place(0, 0)
                imagePlaceable.place(constraints.maxWidth - imagePlaceable.width, 0)
            }
        }
    }
}

/**
 * Draws one EPUB image — a full-page plate or an inline picture — as either the decoded artwork or,
 * once it is certain the image will never arrive, a text label in its place.
 *
 * The publisher background for the image box is drawn before the content, then the image itself is drawn,
 * then borders are painted above it. That keeps explicit image frames visible while preserving the exact box
 * pagination measured.
 *
 * A picture arrives in two steps — its bytes are read out of the book, then decoded — and showing something
 * during each step made the page change three times before settling: alt text or a spinner, then a second
 * spinner, then the picture. Since the space is already reserved at the right size, `SubcomposeAsyncImage`'s
 * `loading` slot is left blank and only the decoded image (crossfaded in over [ImageFadeMillis] so the page
 * settles once instead of snapping as each picture finishes) or, once decoding has actually failed, the
 * [label] text is ever shown — nothing flickers while an image that will still arrive is only slow.
 *
 * @param imageBytes the image's decoded bytes, when available; null renders nothing unless [isFailed] is
 * also true, in which case [label] is shown.
 * @param label alt text shown when the image is missing or failed to decode, and used as the content
 * description while it renders successfully.
 * @param isFailed whether this image previously failed to decode, so a missing [imageBytes] here is shown as
 * [label] instead of silently rendering nothing.
 * @param boxStyle publisher box styling attached to the image itself, including background, borders and radius.
 * @param currentColor fallback color for `currentColor`-style borders.
 * @param usePublisherColors whether explicit publisher colors should be honored instead of theme fallbacks.
 * @param modifier the modifier applied to the image's root; expected to already carry the exact size the
 * image should occupy.
 */
@Composable
private fun EpubImageBox(
    imageBytes: ByteArray?,
    label: String?,
    isFailed: Boolean,
    boxStyle: ReaderBoxStyle?,
    currentColor: Color,
    usePublisherColors: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = teddReaderColors()
    val typography = teddReaderTypography()
    val platformContext = LocalPlatformContext.current
    val request = remember(imageBytes, platformContext) {
        imageBytes?.let { bytes ->
            ImageRequest.Builder(platformContext)
                .data(bytes)
                .size(MaxInlineImageDimensionPx, MaxInlineImageDimensionPx)
                .crossfade(ImageFadeMillis)
                .build()
        }
    }
    val radiusPercent = boxStyle?.borderRadiusPercent?.toInt()?.coerceIn(0, 100)
    val decoratedModifier = modifier
        .fillMaxSize()
        .run { if (radiusPercent != null && radiusPercent > 0) clip(RoundedCornerShape(percent = radiusPercent)) else this }
        .drawWithContent {
            val rect = Rect(Offset.Zero, size)
            drawReaderBoxBackground(boxStyle, rect, usePublisherColors)
            drawContent()
            drawReaderBoxBorders(boxStyle, rect, currentColor, usePublisherColors)
        }

    Box(modifier = decoratedModifier, contentAlignment = Alignment.Center) {
        when {
            request != null -> SubcomposeAsyncImage(
                model = request,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                loading = {},
                error = {
                    TeddText(
                        text = label ?: stringResource(Res.string.visual_page_unavailable),
                        style = typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                },
            )
            isFailed -> TeddText(
                text = label ?: stringResource(Res.string.visual_page_unavailable),
                style = typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

private fun DrawScope.drawContainerDecorations(
    layout: TextLayoutResult,
    decorations: List<ReaderContainerDecoration>,
    baseTextColor: Color,
    publisherColorsEnabled: Boolean,
    emPx: Float,
) {
    decorations
        .filterNot(ReaderContainerDecoration::isPageContainer)
        .forEach { decoration ->
            val rect = fullWidthRangeRect(layout, decoration.start, decoration.end)
                ?.grownByPadding(decoration, emPx)
                ?: return@forEach
            val currentColor = decoration.foregroundColor.takeIf { publisherColorsEnabled }?.toColor() ?: baseTextColor
            drawReaderBoxBackground(
                boxStyle = decoration.boxStyle,
                rect = rect,
                usePublisherColors = publisherColorsEnabled,
            )
            drawReaderBoxBorders(
                boxStyle = decoration.boxStyle,
                rect = rect,
                currentColor = currentColor,
                usePublisherColors = publisherColorsEnabled,
                drawTop = decoration.startsHere,
                drawBottom = decoration.endsHere,
            )
        }
}

private fun fullWidthRangeRect(layout: TextLayoutResult, start: Int, end: Int): Rect? {
    if (end <= start || layout.layoutInput.text.length == 0) return null
    val safeStart = start.coerceIn(0, layout.layoutInput.text.length - 1)
    val safeEnd = (end - 1).coerceIn(safeStart, layout.layoutInput.text.length - 1)
    val firstLine = layout.getLineForOffset(safeStart)
    val lastLine = layout.getLineForOffset(safeEnd)
    return Rect(
        left = 0f,
        top = layout.getLineTop(firstLine),
        right = layout.size.width.toFloat(),
        bottom = layout.getLineBottom(lastLine),
    )
}

private fun DrawScope.drawReaderBoxBackground(
    boxStyle: ReaderBoxStyle?,
    rect: Rect,
    usePublisherColors: Boolean,
) {
    boxStyle ?: return
    if (!usePublisherColors) return
    val radius = ((boxStyle.borderRadiusPercent ?: 0f).coerceIn(0f, 100f) / 100f) * minOf(rect.width, rect.height)
    boxStyle.backgroundColor?.let { color ->
        if (radius > 0f) {
            drawRoundRect(
                color = color.toColor(),
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(radius, radius),
            )
        } else {
            drawRect(color.toColor(), rect.topLeft, rect.size)
        }
    }
}

private fun DrawScope.drawReaderBoxBorders(
    boxStyle: ReaderBoxStyle?,
    rect: Rect,
    currentColor: Color,
    usePublisherColors: Boolean,
    drawTop: Boolean = true,
    drawBottom: Boolean = true,
) {
    boxStyle ?: return
    val insetRect = rect.insetForBorders(boxStyle, density)
    if (drawTop) drawBorderSide(boxStyle.borderTop, insetRect.left, insetRect.top, insetRect.right, insetRect.top, currentColor, usePublisherColors)
    drawBorderSide(boxStyle.borderRight, insetRect.right, insetRect.top, insetRect.right, insetRect.bottom, currentColor, usePublisherColors)
    if (drawBottom) drawBorderSide(boxStyle.borderBottom, insetRect.left, insetRect.bottom, insetRect.right, insetRect.bottom, currentColor, usePublisherColors)
    drawBorderSide(boxStyle.borderLeft, insetRect.left, insetRect.top, insetRect.left, insetRect.bottom, currentColor, usePublisherColors)
}

private fun Rect.insetForBorders(boxStyle: ReaderBoxStyle, density: Float): Rect {
    val halfStroke = maxBorderHalfStrokePx(boxStyle, density)
    return if (halfStroke == 0f) this else Rect(
        left = left + halfStroke,
        top = top + halfStroke,
        right = right - halfStroke,
        bottom = bottom - halfStroke,
    )
}

internal fun maxBorderHalfStrokePx(boxStyle: ReaderBoxStyle, density: Float): Float =
    listOf(
        boxStyle.borderTop?.widthPx,
        boxStyle.borderRight?.widthPx,
        boxStyle.borderBottom?.widthPx,
        boxStyle.borderLeft?.widthPx,
    ).mapNotNull { it?.takeIf { width -> width > 0f } }
        .maxOrNull()
        ?.times(density)
        ?.times(0.5f)
        ?: 0f

/**
 * Deepest page-container publisher background, if any, to paint across the whole text pane.
 *
 * html/body-like containers cover the reader's whole text page rather than only the laid-out glyph bounds, so
 * the pane background is chosen separately from the per-layout decoration pass.
 */
/**
 * The page margins a book asks for on its own `html`/`body`, in em, or [ReaderPageMarginsEm.Zero] when it
 * asks for none.
 *
 * A reflowable book states its page margins on `body` — `body { margin: 2em }` is the single most common
 * line in an EPUB's stylesheet — and a reader that ignores it sets the text edge to edge in a column far
 * wider than the book was typeset for. The margins are read off the page-container blocks (`html` and
 * `body`), the widest of which wins, and applied outside the text area so pagination measures the same
 * column the text is drawn into.
 *
 * Each side is capped at [MaxPageContainerMarginEm]: a book asking for a margin wider than the page it is
 * read on would otherwise leave a column too narrow to set a word in.
 *
 * @param page the page whose blocks carry the container styling.
 * @return the margins to inset the text area by.
 */
internal fun epubPageContainerMarginsEm(page: ReaderPageUi): ReaderPageMarginsEm {
    val containers = page.blocks.filter(ReaderBlock::isPageContainer)
    if (containers.isEmpty()) return ReaderPageMarginsEm.Zero
    // Margin and padding are summed per side: on `html`/`body` both simply stand between the page edge
    // and the text, and the parser deliberately keeps page-container spacing out of the per-paragraph
    // insets so it lands here exactly once.
    fun widest(margin: (ReaderBlockStyle) -> Float?, padding: (ReaderBlockStyle) -> Float?): Float =
        containers.mapNotNull { block ->
            block.style?.let { style -> (margin(style) ?: 0f) + (padding(style) ?: 0f) }
        }
            .maxOrNull()
            ?.coerceIn(0f, MaxPageContainerMarginEm)
            ?: 0f
    return ReaderPageMarginsEm(
        start = widest(ReaderBlockStyle::marginStartEm, ReaderBlockStyle::paddingStartEm),
        top = widest(ReaderBlockStyle::marginTopEm, ReaderBlockStyle::paddingTopEm),
        end = widest(ReaderBlockStyle::marginEndEm, ReaderBlockStyle::paddingEndEm),
        bottom = widest(ReaderBlockStyle::marginBottomEm, ReaderBlockStyle::paddingBottomEm),
    )
}

/**
 * Page margins in em, as a book's own `body` rule states them.
 *
 * @property start inline-start margin in em.
 * @property top margin above the text area in em.
 * @property end inline-end margin in em.
 * @property bottom margin below the text area in em.
 */
data class ReaderPageMarginsEm(
    val start: Float,
    val top: Float,
    val end: Float,
    val bottom: Float,
) {
    /** True when the book asks for no page margin at all, so the reader's own padding stands alone. */
    fun isZero(): Boolean = this == Zero

    /** Holds [Zero], the margins a book that states none is given. */
    companion object {
        /** No page margin on any side. */
        val Zero = ReaderPageMarginsEm(0f, 0f, 0f, 0f)
    }
}

/** Widest page margin, per side, this reader will honor before the text column stops being readable. */
private const val MaxPageContainerMarginEm = 4f

internal fun epubPageContainerBackgroundColor(
    page: ReaderPageUi,
    style: ReaderStyle,
): ReaderColor? {
    if (style.themeMode != ReaderThemeMode.PUBLISHER) return null
    return page.blocks
        .asSequence()
        .filter(ReaderBlock::isPageContainer)
        .mapNotNull { block ->
            block.style?.boxStyle?.backgroundColor
                ?.takeUnless { (it.argb ushr 24) == 0L }
                ?.let { color -> block.level to color }
        }
        .maxByOrNull { (level, _) -> level }
        ?.second
}

/**
 * This rect grown to include the box's own padding, so its border is drawn off the text rather than through
 * it.
 *
 * A text layout only knows where the lines are, so a box measured from it ends exactly at the glyphs it
 * encloses — and a book framing a section with `border-top` plus `padding: 1em 0` then had its rule drawn
 * across the first and last lines of that section. The space is reserved in the gap either side of the box
 * (see the renderer's container-edge accounting), and this is where it is claimed.
 *
 * @receiver the rect the box's lines occupy.
 * @param decoration the box being painted, which states its padding in em.
 * @param emPx how many pixels one em is.
 * @return the rect to paint the background and borders in.
 */
private fun Rect.grownByPadding(decoration: ReaderContainerDecoration, emPx: Float): Rect = Rect(
    left = left,
    top = top - if (decoration.startsHere) decoration.paddingTopEm * emPx else 0f,
    right = right,
    bottom = bottom + if (decoration.endsHere) decoration.paddingBottomEm * emPx else 0f,
)

private fun DrawScope.drawBorderSide(
    border: ReaderBorder?,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    fallbackColor: Color,
    usePublisherColors: Boolean,
) {
    val width = border?.widthPx?.takeIf { it > 0f }?.times(density) ?: return
    drawLine(
        color = if (usePublisherColors) border.color?.toColor() ?: fallbackColor else fallbackColor,
        start = Offset(startX, startY),
        end = Offset(endX, endY),
        strokeWidth = width,
    )
}

/** The decode size, in pixels, requested for any EPUB image drawn by [EpubImageBox]. */
private const val MaxInlineImageDimensionPx = 2_048

/** How long, in milliseconds, an EPUB image crossfades in once decoded; see [EpubImageBox]. */
private const val ImageFadeMillis = 120

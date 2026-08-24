package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import com.tedd.teddreader.core.designsystem.toColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderBoxStyle
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderFloat
import com.tedd.teddreader.core.common.model.ReaderFontFamily
import com.tedd.teddreader.core.common.model.ReaderDefaultLineHeightMultiplier
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import com.tedd.teddreader.core.common.model.ReaderSpanStyle
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import com.tedd.teddreader.core.common.model.isStandalone
import com.tedd.teddreader.core.common.model.readerImageSize
import com.tedd.teddreader.core.common.model.standaloneBlocks
import com.tedd.teddreader.core.common.model.TextRange

/** U+FFFC OBJECT REPLACEMENT CHARACTER: the one character a picture occupies in the rendered text. */
private const val ObjectReplacementChar = '\uFFFC'
/** Annotation tag a link carries, so a tap on the rendered text can find the href behind it. */
private const val ReaderLinkTag = "link"

/**
 * A page's text as Compose draws it, plus the map back to the document it came from.
 *
 * Rendering adds characters the document does not have — a quote bar, a list bullet, a heading rule, the
 * placeholder standing in for a picture — so display offsets and document offsets stop agreeing at the first
 * such prefix. Everything the reader does with a position (saving progress, jumping to a search hit,
 * reporting where a page ends) speaks in document offsets, so the map travels with the string rather than
 * being recomputed by whoever needs it.
 *
 * @property annotatedString the text to draw, prefixes and placeholders included.
 * @property offsetMap document offset per rendered character, one entry longer than the string so the
 * position just past the end is addressable; read through [sourceOffsetFor], which clamps.
 * @property placeholders the boxes reserved for pictures and rules, in rendering order.
 * @property containerDecorations full-width block decorations resolved from container blocks, in paint order.
 */
data class ReaderSemanticText(
    val annotatedString: AnnotatedString,
    val offsetMap: IntArray,
    val placeholders: List<ReaderPlaceholder>,
    val containerDecorations: List<ReaderContainerDecoration> = emptyList(),
)

/**
 * One full-width block decoration segment in the rendered text, and everything the renderer needs to paint it.
 *
 * Container backgrounds and borders are measured from the same text layout as the glyphs they surround, so
 * the geometry needed for page painting travels beside the text rather than being rediscovered later.
 */
data class ReaderContainerDecoration(
    val start: Int,
    val end: Int,
    val boxStyle: ReaderBoxStyle,
    val foregroundColor: ReaderColor? = null,
    val startsHere: Boolean = true,
    val endsHere: Boolean = true,
    val isPageContainer: Boolean = false,
    /** Space the box keeps between its own top edge and the first line inside it, in em. */
    val paddingTopEm: Float = 0f,
    /** Space the box keeps between the last line inside it and its own bottom edge, in em. */
    val paddingBottomEm: Float = 0f,
)

/**
 * The leading slice of paragraph content measured to sit beside a floated image inside one placeholder.
 *
 * Pagination and drawing both consume the same fitted slice so a float claims the same source offsets in
 * measurement and rendering.
 */
data class ReaderFloatContent(
    val side: ReaderFloat,
    val text: ReaderSemanticText,
    val imageWidthEm: Float,
    val columnWidthEm: Float,
    val sourceEnd: Int,
)

/**
 * One reserved box in the rendered text, and everything the renderer needs to fill it.
 *
 * The box is reserved during layout and filled during drawing, which is why the size lives here rather than
 * being decided at draw time: pagination has to know how much of the page a picture takes before anyone
 * loads it, and the drawn picture then has to match what was measured.
 *
 * @property id the inline-content key Compose matches the box to its content by; unique per page.
 * @property kind what stands in the box — a picture, a cover, a rule, or a floated image plus its nested text.
 * @property href the image's path inside the container, for a caller that has to fetch the bytes.
 * @property label the image's alt text, for accessibility and for a failed load.
 * @property placeholder the box itself: the size reserved and how it aligns with the line.
 * @property start where the box sits in [ReaderSemanticText.annotatedString].
 * @property end one past [start] — a box always occupies exactly one character.
 * @property floatContent nested text content measured to live beside a floated image, when this placeholder is one.
 * @property boxStyle publisher box styling that belongs to the image box itself, when present.
 * @property foregroundColor the color the placeholder should inherit for currentColor-style borders.
 */
data class ReaderPlaceholder(
    val id: String,
    val kind: ReaderBlockKind,
    val href: String? = null,
    val label: String? = null,
    val placeholder: Placeholder,
    val start: Int,
    val end: Int,
    val floatContent: ReaderFloatContent? = null,
    val boxStyle: ReaderBoxStyle? = null,
    val foregroundColor: ReaderColor? = null,
)

/**
 * Input for the shared float fitter used by pagination and page rendering.
 *
 * The request carries the paragraph containing the float in absolute document offsets so the fitter can
 * measure only the remaining post-image slice yet still return source offsets the caller can trust.
 */
data class ReaderFloatPlacementRequest(
    val text: String,
    val blocks: List<ReaderBlock>,
    val range: TextRange,
    val paragraphRange: TextRange,
    val imageBlock: ReaderBlock,
    val imageSize: com.tedd.teddreader.core.common.model.ReaderImageSize,
)

/**
 * The fitted leading paragraph slice that can live beside a float.
 *
 * [nestedRange] stays in source offsets while [nestedText] carries the rendered substring that fits in the
 * floated column beside the image.
 */
data class ReaderFloatPlacement(
    val nestedRange: TextRange,
    val nestedText: ReaderSemanticText,
)

/**
 * Shared callback that returns the largest leading paragraph slice that fits beside a float.
 *
 * It is injected so pagination and rendering can share the exact same fitting logic and measurement inputs.
 */
typealias ReaderFloatTextFitter = (ReaderFloatPlacementRequest) -> ReaderFloatPlacement?

/**
 * Collects embedded font hrefs referenced by block and span styles.
 *
 * EPUB pagination waits until every referenced font has either resolved or failed, so this scan is the cheap
 * contract that tells the caller which hrefs matter for first measurement.
 */
fun readerReferencedFontHrefs(blocks: List<ReaderBlock>): Set<String> = buildSet {
    blocks.forEach { block ->
        block.style?.fontHref?.let(::add)
        block.spans.forEach { span -> span.styleDelta?.fontHref?.let(::add) }
    }
}

/**
 * Turns stored text and its block structure into the string a page draws, and the offset map back.
 *
 * This is the single place the document's flat text becomes something visual, and it runs on both sides of
 * the reader: the page breaker measures with it, then the page surface draws with it. Both must see the same
 * string, or a page will be measured with one set of characters and drawn with another — which is how a page
 * ends up clipping its last line.
 *
 * A picture is kept *in* the text as one placeholder character rather than being lifted out between blocks,
 * because that is where HTML puts it: a gaiji glyph or an icon belongs in the sentence it was written in. A
 * picture that is the only thing in its block gets a paragraph of its own — that is what centres it as the
 * book asks and stops prose being set beside it — while one written inside a sentence gets none of that,
 * since a paragraph there would break the sentence and fight the enclosing style. A floated image still keeps
 * that single placeholder position, but may also consume the largest leading slice of the containing
 * paragraph that fits beside it; the consumed source range is mapped to the placeholder so measurement and
 * drawing agree on where the remaining text resumes.
 *
 * Two allocations here were the bulk of the work when opening a book, and both are deliberate now: the
 * offset map is a growable [IntBuffer] instead of an `ArrayList<Int>`, which boxed an `Integer` per rendered
 * character, and each block's display start is recorded by its *position* in a map instead of by searching a
 * list for an equal block, which compared whole blocks — spans and all — once per block, quadratically.
 *
 * @param text the stretch of document text to render.
 * @param blocks the blocks covering it, in absolute document offsets; blocks are clamped to [range], so
 * passing a whole section's blocks for one page's text is expected.
 * @param range where [text] sits in the document, which is what makes the returned offsets absolute.
 * Defaults to treating [text] as the whole document.
 * @param lineWidthEm width of the text column in em, which bounds an image like `max-width: 100%`; 0 means
 * unknown and falls back to a default column.
 * @param maxHeightEm height available for one page in em, which bounds an image like `max-height`; 0 falls
 * back to a default page.
 * @param emInPx CSS pixels per em, which turns an image's intrinsic pixel width into em; 0 means an
 * intrinsic width cannot be used.
 * @param embeddedFontFamiliesByHref resolved embedded font families keyed by EPUB href, reused by pagination
 * and drawing so both measure with the same fonts.
 * @param publisherColorsEnabled whether publisher foreground and background colors should be applied.
 * @param publisherFontsEnabled whether publisher-requested generic/custom font families should be applied.
 * @param floatTextFitter shared float fitting callback; null disables float nesting and leaves images as plain
 * placeholders.
 * @return the drawable string, its offset map, and the boxes reserved for pictures. Empty [text] yields an
 * empty string with a one-entry map, never an invalid one.
 */
fun buildReaderSemanticText(
    text: String,
    blocks: List<ReaderBlock>,
    range: TextRange = TextRange(0, text.length.toLong()),
    lineWidthEm: Float = 0f,
    maxHeightEm: Float = 0f,
    emInPx: Float = 0f,
    embeddedFontFamiliesByHref: Map<String, FontFamily> = emptyMap(),
    publisherColorsEnabled: Boolean = false,
    publisherFontsEnabled: Boolean = true,
    floatTextFitter: ReaderFloatTextFitter? = null,
    lineHeightMultiplier: Float = 1f,
): ReaderSemanticText {
    if (text.isEmpty()) {
        return ReaderSemanticText(AnnotatedString(""), intArrayOf(0), emptyList())
    }

    val absoluteStart = range.start.toInt()
    val absoluteEnd = range.end.toInt().coerceAtMost(absoluteStart + text.length)
    val localLength = (absoluteEnd - absoluteStart).coerceAtLeast(0)
    val sourceToDisplay = IntArray(localLength + 1)
    val displayToSource = IntBuffer(text.length + blocks.size * 4 + 1)
    val display = StringBuilder(text.length + blocks.size * 4)
    val placeholderSpecs = mutableListOf<PlaceholderSpec>()

    val clampedBlocks = blocks.mapIndexedNotNull { index, block ->
        val start = block.range.start.coerceAtLeast(range.start)
        val end = block.range.end.coerceAtMost(range.end)
        if (start >= end && block.kind != ReaderBlockKind.IMAGE && block.kind != ReaderBlockKind.COVER_IMAGE && block.kind != ReaderBlockKind.SEPARATOR) {
            null
        } else {
            val localStart = (start - range.start).toInt().coerceAtLeast(0)
            val localEnd = (end - range.start).toInt().coerceAtLeast(localStart)
            ClampedBlock(index, block, localStart, localEnd, includesStart = block.range.start in range.start until range.end)
        }
    }
    val blockDisplayStart = HashMap<Int, Int>(clampedBlocks.size)
    val blocksByStart = clampedBlocks.groupBy { it.localStart }
    val placeholderBlocksByStart = clampedBlocks
        .filter { it.block.kind == ReaderBlockKind.IMAGE || it.block.kind == ReaderBlockKind.COVER_IMAGE || it.block.kind == ReaderBlockKind.SEPARATOR }
        .associateBy { it.localStart }

    val leafBlocks = clampedBlocks.filter { it.block.kind != ReaderBlockKind.CONTAINER }
    val coverage = IntArray(localLength + 2)
    leafBlocks.forEach { block ->
        val start = block.localStart.coerceIn(0, localLength)
        val end = block.localEnd.coerceIn(start, localLength)
        if (end > start) {
            coverage[start] += 1
            coverage[end] -= 1
        }
    }
    var coverageRunning = 0
    val covered = BooleanArray(localLength + 1)
    for (index in 0..localLength) {
        coverageRunning += coverage[index]
        covered[index] = coverageRunning > 0
    }
    val leafStarts = leafBlocks.sortedBy { it.localStart }
    val leafEnds = leafBlocks.sortedBy { it.localEnd }
    val gapRanges = mutableListOf<Pair<Int, Float>>()

    var localIndex = 0
    while (localIndex < localLength) {
        val sourceAbsolute = absoluteStart + localIndex
        val sourceChar = text[localIndex]
        if (sourceChar == '\n' && !covered[localIndex]) {
            var runEnd = localIndex
            while (runEnd < localLength && text[runEnd] == '\n' && !covered[runEnd]) runEnd += 1
            val gapEm = blockGapEm(
                before = leafEnds.lastOrNull { it.localEnd <= localIndex }?.block,
                after = leafStarts.firstOrNull { it.localStart >= runEnd }?.block,
            ) + containerEdgeEm(clampedBlocks, localIndex, runEnd, emInPx)
            for (skippedIndex in localIndex until runEnd) sourceToDisplay[skippedIndex] = display.length
            if (gapEm > 0f) {
                gapRanges += display.length to gapEm
                display.append(BlockGapChar)
                displayToSource += sourceAbsolute
            }
            localIndex = runEnd
            continue
        }
        blocksByStart[localIndex].orEmpty()
            .filterNot { it.block.kind == ReaderBlockKind.IMAGE || it.block.kind == ReaderBlockKind.COVER_IMAGE || it.block.kind == ReaderBlockKind.SEPARATOR }
            .forEach { block ->
                val prefix = blockPrefix(block.block).takeIf { block.includesStart }.orEmpty()
                val blockDisplayStartIndex = display.length
                prefix.forEach { char ->
                    display.append(char)
                    displayToSource += sourceAbsolute
                }
                blockDisplayStart[block.index] = blockDisplayStartIndex
            }

        sourceToDisplay[localIndex] = display.length
        val placeholderBlock = placeholderBlocksByStart[localIndex]
        if (placeholderBlock != null && placeholderBlock.includesStart) {
            val placeholderId = "${placeholderBlock.block.kind.name.lowercase()}-${absoluteStart + localIndex}-${placeholderSpecs.size}"
            val floatContent = if (placeholderBlock.block.kind == ReaderBlockKind.IMAGE && placeholderBlock.block.float != null && floatTextFitter != null) {
                buildFloatContent(
                    imageBlock = placeholderBlock,
                    clampedBlocks = clampedBlocks,
                    text = text,
                    range = range,
                    lineWidthEm = lineWidthEm,
                    maxHeightEm = maxHeightEm,
                    emInPx = emInPx,
                    floatTextFitter = floatTextFitter,
                )
            } else {
                null
            }
            display.append(ObjectReplacementChar)
            displayToSource += sourceAbsolute
            placeholderSpecs += PlaceholderSpec(
                id = placeholderId,
                kind = placeholderBlock.block.kind,
                href = placeholderBlock.block.imageHref,
                label = placeholderBlock.block.label,
                block = placeholderBlock.block,
                start = display.length - 1,
                floatContent = floatContent,
            )
            val nextLocalIndex = maxOf(
                placeholderBlock.localEnd.coerceAtLeast(localIndex + 1),
                floatContent?.sourceEnd?.minus(absoluteStart) ?: Int.MIN_VALUE,
            ).coerceAtMost(localLength)
            for (skippedIndex in localIndex + 1..nextLocalIndex.coerceAtMost(localLength)) {
                sourceToDisplay[skippedIndex] = display.length
            }
            localIndex = nextLocalIndex
            continue
        }

        display.append(sourceChar)
        displayToSource += sourceAbsolute
        localIndex += 1
    }
    sourceToDisplay[localLength] = display.length
    displayToSource += absoluteEnd

    val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
    val annotations = mutableListOf<Triple<String, String, IntRange>>()
    val paragraphs = mutableListOf<Pair<IntRange, ParagraphStyle>>()

    clampedBlocks.forEach { block ->
        if (block.block.kind == ReaderBlockKind.IMAGE || block.block.kind == ReaderBlockKind.COVER_IMAGE || block.block.kind == ReaderBlockKind.SEPARATOR) return@forEach
        // A container contributes decorations and gap spacing only. Its inherited styling is already
        // baked into every leaf block the parser resolved inside it, and a span of its own would also
        // cover the zero-width gap characters between those leaves — which is how underlines and
        // background fragments used to appear in the blank space between paragraphs, and how a
        // container font size compounded into the gap lines and broke their measured height.
        if (block.block.kind == ReaderBlockKind.CONTAINER) return@forEach
        val blockStart = blockDisplayStart[block.index] ?: sourceToDisplay[block.localStart]
        val blockEnd = sourceToDisplay[block.localEnd]
        if (blockEnd <= blockStart) return@forEach

        blockSpanStyle(block.block, embeddedFontFamiliesByHref, publisherColorsEnabled, publisherFontsEnabled)?.let { spans += (blockStart until blockEnd) to it }
        run {
            val paragraphStyle = blockParagraphStyle(
                block = block.block,
                indentsFirstLine = block.includesStart,
                justifies = text.justifiesWell(block.localStart, block.localEnd),
                lineHeightMultiplier = lineHeightMultiplier,
            ) ?: EmptyParagraphStyle
            paragraphs += (blockStart until blockEnd) to paragraphStyle
        }

        block.block.spans.forEach { span ->
            val start = (span.range.start - range.start).toInt().coerceIn(0, localLength)
            val end = (span.range.end - range.start).toInt().coerceIn(start, localLength)
            if (end <= start) return@forEach
            val displayStart = sourceToDisplay[start]
            val displayEnd = sourceToDisplay[end]
            if (displayEnd <= displayStart) return@forEach
            inlineSpanStyle(span, embeddedFontFamiliesByHref, publisherColorsEnabled, publisherFontsEnabled)?.let { spans += (displayStart until displayEnd) to it }
            if (span.style == ReaderInlineStyle.LINK) {
                span.href?.let { href -> annotations += Triple(ReaderLinkTag, href, displayStart until displayEnd) }
            }
        }
    }

    gapRanges.forEach { (start, gapEm) ->
        paragraphs += (start until start + 1) to ParagraphStyle(lineHeight = gapEm.em)
        spans += (start until start + 1) to SpanStyle(fontSize = (gapEm / GapLineNaturalHeightRatio).em)
    }

    val standaloneBlocks = clampedBlocks.map { it.block }.standaloneBlocks().toSet()
    placeholderSpecs.forEach { spec ->
        if (spec.block !in standaloneBlocks || spec.floatContent != null) return@forEach
        blockParagraphStyle(spec.block)?.let { style ->
            paragraphs += (spec.start until spec.start + 1) to style
        }
    }

    // A float placeholder is a full-column box ten-odd lines tall. Left inside the paragraph it was
    // written in, its line box contaminates the line-height resolution of every following line of that
    // paragraph — the remaining prose came out with line boxes the height of the picture. Splitting the
    // paragraph around the placeholder confines that height to the placeholder's own line while the
    // surrounding text keeps the paragraph's stated style.
    placeholderSpecs.filter { it.floatContent != null }.map(PlaceholderSpec::start).sorted().forEach { floatStart ->
        val containing = paragraphs.indexOfFirst { (rangeValue, _) -> floatStart in rangeValue && rangeValue.count() > 1 }
        val floatParagraph = (floatStart until floatStart + 1) to EmptyParagraphStyle
        if (containing < 0) {
            paragraphs += floatParagraph
            return@forEach
        }
        val (rangeValue, style) = paragraphs.removeAt(containing)
        if (rangeValue.first < floatStart) paragraphs += (rangeValue.first until floatStart) to style
        paragraphs += floatParagraph
        if (floatStart + 1 <= rangeValue.last) paragraphs += (floatStart + 1..rangeValue.last) to style
    }

    val placeholders = placeholderSpecs.map { spec ->
        val inheritedForeground = clampedBlocks
            .filter { it.block.kind != ReaderBlockKind.IMAGE && it.block.kind != ReaderBlockKind.COVER_IMAGE && it.block.kind != ReaderBlockKind.SEPARATOR }
            .filter { it.block.range.start <= spec.block.range.start && it.block.range.end >= spec.block.range.end }
            .minByOrNull { it.block.range.end - it.block.range.start }
            ?.block
            ?.style
            ?.foregroundColor
        ReaderPlaceholder(
            id = spec.id,
            kind = spec.kind,
            href = spec.href,
            label = spec.label,
            placeholder = placeholderFor(
                block = spec.block,
                isStandalone = spec.block in standaloneBlocks,
                lineWidthEm = lineWidthEm,
                maxHeightEm = maxHeightEm,
                emInPx = emInPx,
                isFloat = spec.floatContent != null,
            ),
            start = spec.start,
            end = spec.start + 1,
            floatContent = spec.floatContent,
            boxStyle = spec.block.style?.boxStyle,
            foregroundColor = spec.block.style?.foregroundColor ?: inheritedForeground,
        )
    }

    val annotatedString = buildAnnotatedString {
        val placeholdersByStart = placeholderSpecs.associateBy(PlaceholderSpec::start)
        var displayIndex = 0
        while (displayIndex < display.length) {
            val placeholder = placeholdersByStart[displayIndex]
            if (placeholder != null) {
                appendInlineContent(placeholder.id, ObjectReplacementChar.toString())
            } else {
                append(display[displayIndex])
            }
            displayIndex += 1
        }
        // A placeholder's reserved box is stated in em, and Compose resolves that em against the font
        // size in force at the placeholder's position — so a placeholder inside a 0.85em block span was
        // reserved 15% smaller than every consumer (image sizing, float fitting, pagination arithmetic)
        // computed in base em. Splitting the spans around each placeholder keeps its em the base em.
        val placeholderStarts = placeholderSpecs.map(PlaceholderSpec::start).toSet()
        spans.flatMap { (rangeValue, style) -> rangeValue.splitAround(placeholderStarts).map { it to style } }
            .forEach { (rangeValue, style) -> addStyle(style, rangeValue.first, rangeValue.last + 1) }
        paragraphs.withoutOverlaps().forEach { (rangeValue, style) -> addStyle(style, rangeValue.first, rangeValue.last + 1) }
        annotations.forEach { (tag, value, rangeValue) -> addStringAnnotation(tag, value, rangeValue.first, rangeValue.last + 1) }
    }

    // Wrappers paint first (outermost lowest), then styled leaf blocks paint their own boxes on top —
    // the box a leaf used to get from its parse-time container twin now comes straight from the leaf.
    // Standalone image kinds are excluded: the image box paints its own background and borders.
    val containerDecorations = clampedBlocks
        .asSequence()
        .filter { block ->
            block.block.kind == ReaderBlockKind.CONTAINER || !block.block.kind.isStandalone()
        }
        .mapNotNull { block ->
            block.block.style?.boxStyle
                ?.takeUnless(ReaderBoxStyle::isEmpty)
                ?.let { boxStyle -> block to boxStyle }
        }
        .sortedWith(
            compareBy<Pair<ClampedBlock, ReaderBoxStyle>> { if (it.first.block.kind == ReaderBlockKind.CONTAINER) 0 else 1 }
                .thenBy { it.first.block.level }
                .thenBy { it.first.index },
        )
        .mapNotNull { (block, boxStyle) ->
            val start = blockDisplayStart[block.index] ?: sourceToDisplay[block.localStart]
            val end = sourceToDisplay[block.localEnd]
            if (end > start) ReaderContainerDecoration(
                start = start,
                end = end,
                boxStyle = boxStyle,
                foregroundColor = block.block.style?.foregroundColor,
                startsHere = block.block.range.start >= range.start,
                endsHere = block.block.range.end <= range.end,
                isPageContainer = block.block.isPageContainer,
                paddingTopEm = block.block.style?.paddingTopEm ?: 0f,
                paddingBottomEm = block.block.style?.paddingBottomEm ?: 0f,
            ) else null
        }
        .toList()

    return ReaderSemanticText(
        annotatedString = annotatedString,
        offsetMap = displayToSource.toIntArray(),
        placeholders = placeholders,
        containerDecorations = containerDecorations,
    )
}

private fun buildFloatContent(
    imageBlock: ClampedBlock,
    clampedBlocks: List<ClampedBlock>,
    text: String,
    range: TextRange,
    lineWidthEm: Float,
    maxHeightEm: Float,
    emInPx: Float,
    floatTextFitter: ReaderFloatTextFitter,
): ReaderFloatContent? {
    val paragraph = clampedBlocks
        .filter { it.block.kind != ReaderBlockKind.IMAGE && it.block.kind != ReaderBlockKind.COVER_IMAGE && it.block.kind != ReaderBlockKind.SEPARATOR }
        .filter { it.localStart <= imageBlock.localStart && it.localEnd >= imageBlock.localEnd }
        .minByOrNull { it.localEnd - it.localStart }
        ?: return null
    val imageSize = imageBlock.block.readerImageSize(
        columnWidthEm = lineWidthEm.takeIf { it > 0f } ?: DefaultImageWidthEm,
        maxHeightEm = maxHeightEm.takeIf { it > 0f } ?: DefaultImageMaxHeightEm,
        emInPx = emInPx,
    )
    val placement = floatTextFitter(
        ReaderFloatPlacementRequest(
            text = text,
            blocks = clampedBlocks.map(ClampedBlock::block),
            range = range,
            paragraphRange = TextRange(
                range.start + paragraph.localStart,
                range.start + paragraph.localEnd,
            ),
            imageBlock = imageBlock.block,
            imageSize = imageSize,
        ),
    ) ?: return null
    return ReaderFloatContent(
        side = imageBlock.block.float ?: return null,
        text = placement.nestedText,
        imageWidthEm = imageSize.widthEm,
        columnWidthEm = lineWidthEm.takeIf { it > 0f } ?: DefaultImageWidthEm,
        sourceEnd = placement.nestedRange.end.toInt(),
    )
}

/** This range cut into the sub-ranges that exclude every position in [holes]; empty pieces are dropped. */
private fun IntRange.splitAround(holes: Set<Int>): List<IntRange> {
    if (holes.none { it in this }) return listOf(this)
    val pieces = mutableListOf<IntRange>()
    var pieceStart = first
    for (position in this) {
        if (position in holes) {
            if (position > pieceStart) pieces += pieceStart until position
            pieceStart = position + 1
        }
    }
    if (pieceStart <= last) pieces += pieceStart..last
    return pieces
}

/** A growable primitive int buffer, used so building the offset map does not box one Integer per character. */
private class IntBuffer(initialCapacity: Int) {
    private var values = IntArray(initialCapacity.coerceAtLeast(16))
    private var size = 0

    operator fun plusAssign(value: Int) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size] = value
        size += 1
    }

    fun toIntArray(): IntArray = values.copyOf(size)
}

/**
 * The space between two blocks, in em, resolved the way CSS resolves it: each side states its own margin,
 * adjacent vertical margins collapse to the larger of the two, and a side that states nothing falls back to
 * the default a browser gives that element.
 *
 * This is the whole reason a book's paragraph rhythm survives. The stored text separates two blocks with
 * newlines, and drawing those newlines as text costs a whole line of the reader's own line height —
 * a book asking for `margin-bottom: 10px` between paragraphs got a gap six times what it wrote, and one
 * asking for none still got a full blank line. The gap is drawn as a single line whose height is exactly
 * the collapsed margin instead, so `margin: 0` prose runs on the way its indents assume and a stated
 * margin is the size it says.
 *
 * A gap with nothing before it, or nothing after it, is dropped: it would be blank space at the top or
 * bottom of a page, which no reading system leaves there.
 *
 * @param before the block the gap follows, or null when the gap opens the rendered stretch.
 * @param after the block the gap precedes, or null when the gap closes it.
 * @return the gap in em, never negative.
 */
private fun blockGapEm(before: ReaderBlock?, after: ReaderBlock?): Float {
    if (before == null || after == null) return 0f
    val below = before.style?.marginBottomEm ?: defaultBlockMarginEm(before.kind)
    val above = after.style?.marginTopEm ?: defaultBlockMarginEm(after.kind)
    val padding = (before.style?.paddingBottomEm ?: 0f) + (after.style?.paddingTopEm ?: 0f)
    val stated = (maxOf(below, above) + padding).coerceAtLeast(0f)
    return if (stated > 0f || !runsOnUnreadably(before, after)) stated else MobileParagraphFloorEm
}

/**
 * Whether these two blocks would run into each other with nothing at all to tell them apart.
 *
 * A book can state no gap and no first-line indent on its paragraphs — plenty do, because on the wide page
 * they were typeset for the line length alone makes the breaks legible. On a phone the same setting is a
 * solid wall of type where one paragraph ends and the next begins mid-line. A gap the reader supplies here
 * is the smallest thing that keeps them apart, and it is only ever supplied when the book left both means
 * of separation out.
 *
 * @param before the block the gap follows.
 * @param after the block the gap precedes.
 * @return true when neither a gap nor an indent would separate them.
 */
private fun runsOnUnreadably(before: ReaderBlock, after: ReaderBlock): Boolean {
    if (before.kind != after.kind) return false
    if (before.kind != ReaderBlockKind.PARAGRAPH && before.kind != ReaderBlockKind.QUOTE) return false
    return (after.style?.textIndentEm ?: 0f) <= 0f
}

/**
 * Smallest gap the reader puts between two paragraphs a book separates by nothing at all.
 *
 * Small enough to read as the same setting the book asked for rather than as spacing of the reader's own —
 * a quarter of a line, against the whole line a blank line would have cost.
 */
private const val MobileParagraphFloorEm = 0.35f

/**
 * These paragraph ranges with any that overlaps an earlier one dropped, innermost-first.
 *
 * A paragraph is a hard division of the text, and Compose refuses two that overlap — it throws rather than
 * drawing the page. The blocks a well-formed document produces never overlap, but a malformed one can (a
 * cell inside a cell, a heading a document never closed), and a book that reaches that state must still be
 * readable. The narrower range wins, since it is the one closest to the text it describes.
 *
 * @receiver the paragraph ranges collected while rendering, in block order.
 * @return the subset that can be applied together.
 */
private fun List<Pair<IntRange, ParagraphStyle>>.withoutOverlaps(): List<Pair<IntRange, ParagraphStyle>> {
    if (size <= 1) return this
    val ordered = sortedWith(compareBy({ it.first.first }, { it.first.last - it.first.first }))
    val kept = mutableListOf<Pair<IntRange, ParagraphStyle>>()
    var lastEnd = Int.MIN_VALUE
    ordered.forEach { candidate ->
        if (candidate.first.first >= lastEnd) {
            kept += candidate
            lastEnd = candidate.first.last + 1
        }
    }
    return kept
}

/**
 * The space a box's own edges need where they fall inside this gap, in em.
 *
 * A `<div>` with a border and `padding: 1em 0` — the shape a book frames its table of contents or its
 * author note with — draws its rule above the first line inside it and below the last. Without room
 * reserved for that rule and the padding it stands off by, the rule is drawn straight through the words:
 * the box has no space of its own, so it borrows the text's.
 *
 * @param blocks every block of the stretch being rendered, containers included.
 * @param gapStart first offset of the gap, relative to the rendered stretch.
 * @param gapEnd one past its last offset.
 * @param emInPx CSS pixels per em, which turns a border width into em; 0 leaves borders out.
 * @return the space the boxes opening and closing here need, in em.
 */
private fun containerEdgeEm(
    blocks: List<ClampedBlock>,
    gapStart: Int,
    gapEnd: Int,
    emInPx: Float,
): Float {
    // A CONTAINER is always a genuine wrapper (the parser suppresses same-range-same-style twins at the
    // source), so its margins, padding and borders all need room of their own here — no leaf accounts
    // for any of them. A leaf block's own padding and margins already reach the gap through blockGapEm,
    // so a styled leaf only reserves the one thing blockGapEm cannot know about: its border strokes.
    var extra = 0f
    blocks.forEach { block ->
        val style = block.block.style ?: return@forEach
        val isWrapper = block.block.kind == ReaderBlockKind.CONTAINER
        if (isWrapper && block.block.isPageContainer) return@forEach
        if (!isWrapper && block.block.kind.isStandalone()) return@forEach
        if (block.localStart in gapStart..gapEnd) {
            extra += style.boxStyle?.borderTop.widthEm(emInPx) +
                (if (isWrapper) (style.paddingTopEm ?: 0f) + (style.marginTopEm ?: 0f) else 0f)
        }
        if (block.localEnd in gapStart..gapEnd) {
            extra += style.boxStyle?.borderBottom.widthEm(emInPx) +
                (if (isWrapper) (style.paddingBottomEm ?: 0f) + (style.marginBottomEm ?: 0f) else 0f)
        }
    }
    return extra
}

/** This border's width in em, or zero when it has none or one em's width in pixels is unknown. */
private fun com.tedd.teddreader.core.common.model.ReaderBorder?.widthEm(emInPx: Float): Float {
    val widthPx = this?.widthPx ?: return 0f
    return if (emInPx > 0f) widthPx / emInPx else 0f
}

/**
 * The margin a browser's own stylesheet gives this kind, used only when the book states none.
 *
 * These are the CSS2.1 sample-stylesheet values every engine ships: `1em` above and below a paragraph,
 * a blockquote and a `pre`, `0.67em` around an `h1`, and nothing around a list item or a table cell,
 * which are spaced by their list or their row instead.
 *
 * @receiver the kind whose default margin is wanted.
 * @return that margin in em.
 */
private fun defaultBlockMarginEm(kind: ReaderBlockKind): Float = when (kind) {
    ReaderBlockKind.PARAGRAPH,
    ReaderBlockKind.QUOTE,
    ReaderBlockKind.PREFORMATTED,
    -> 1f
    ReaderBlockKind.HEADING -> 0.67f
    ReaderBlockKind.IMAGE,
    ReaderBlockKind.COVER_IMAGE,
    ReaderBlockKind.SEPARATOR,
    -> 0.5f
    ReaderBlockKind.LIST_ITEM,
    ReaderBlockKind.TABLE_CELL,
    ReaderBlockKind.TABLE_HEADER_CELL,
    ReaderBlockKind.CONTAINER,
    -> 0f
}

/** The one character a between-block gap occupies: a zero-width space, so the gap line draws nothing. */
private const val BlockGapChar = '\u200B'

/**
 * How much taller than its font size a line is by nature, used to pick the type size a gap line is set in
 * so its natural height never exceeds the gap it has to be.
 *
 * A line box is at least as tall as the font asks for, so a gap line set in the reader's own size could
 * not be shorter than a whole line however small the margin. Setting it in type scaled down by this ratio
 * makes the stated line height the binding constraint again.
 */
private const val GapLineNaturalHeightRatio = 1.3f

/** A paragraph that asks for nothing, attached to a block that needs no styling but must still be its own
 *  paragraph — without one it would run on into the block after it once the gap between them is closed. */
private val EmptyParagraphStyle = ParagraphStyle()

/** Maps a rendered character position back to the document offset it came from, clamping out-of-range asks. */
fun ReaderSemanticText.sourceOffsetFor(displayIndex: Int): Int =
    offsetMap[displayIndex.coerceIn(0, offsetMap.lastIndex)]

/**
 * The visible prefix a block contributes at its own start: a list item's marker, and nothing else.
 *
 * A marker belongs to the document — a browser draws one for every `<li>` too — so it is written into the
 * rendered text. A heading or a quotation gets nothing. The bar this used to draw beside those was
 * typography the book never asked for: it fought the book's own centring, indents and margins, and no
 * reading system puts one there. What sets a heading apart is the size, weight and spacing the book states,
 * or failing that the browser default the reader falls back to.
 */
private fun blockPrefix(block: ReaderBlock): String = when (block.kind) {
    ReaderBlockKind.LIST_ITEM -> "${"  ".repeat((block.level - 1).coerceAtLeast(0))}${block.label ?: "•"} "
    else -> ""
}

/**
 * The composed span style a block contributes before inline spans narrow it further.
 *
 * Publisher colors and publisher-requested font families are gated separately so a reader-selected font can
 * suppress all EPUB font-family styling while still keeping structural emphasis like heading weight.
 */
private fun blockSpanStyle(
    block: ReaderBlock,
    embeddedFontFamiliesByHref: Map<String, FontFamily>,
    publisherColorsEnabled: Boolean,
    publisherFontsEnabled: Boolean,
): SpanStyle? {
    val kindStyle = when (block.kind) {
        ReaderBlockKind.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingScale(block.level).em)
        ReaderBlockKind.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic)
        ReaderBlockKind.PREFORMATTED -> SpanStyle(fontFamily = FontFamily.Monospace)
        ReaderBlockKind.TABLE_HEADER_CELL -> SpanStyle(fontWeight = FontWeight.SemiBold)
        else -> null
    }
    val bookStyle = block.style ?: return kindStyle
    val merged = SpanStyle(
        fontWeight = bookStyle.bold?.let { if (it) FontWeight.Bold else FontWeight.Normal } ?: kindStyle?.fontWeight,
        fontStyle = bookStyle.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal } ?: kindStyle?.fontStyle,
        fontSize = bookStyle.fontScale?.em ?: kindStyle?.fontSize ?: TextUnit.Unspecified,
        fontFamily = bookStyle.toComposeFontFamily(embeddedFontFamiliesByHref).takeIf { publisherFontsEnabled } ?: kindStyle?.fontFamily,
        color = bookStyle.foregroundColor.takeIf { publisherColorsEnabled }?.toColor() ?: Color.Unspecified,
        textDecoration = bookStyle.toTextDecoration(),
    )
    return merged.takeIf { it != EmptySpanStyle }
}

/** A style that asks for nothing, compared against so an all-null merge is reported as no style at all. */
private val EmptySpanStyle = SpanStyle()

/**
 * @receiver the generic family the book asked for.
 * @return the same family as Compose names it.
 */
private fun ReaderFontFamily.toComposeFontFamily(): FontFamily = when (this) {
    ReaderFontFamily.SERIF -> FontFamily.Serif
    ReaderFontFamily.SANS_SERIF -> FontFamily.SansSerif
    ReaderFontFamily.MONOSPACE -> FontFamily.Monospace
}

/**
 * Picks the font family a block or span's CSS requests, preferring an embedded face by href when one was loaded.
 *
 * Callers gate whether publisher fonts are honored at all; this helper only resolves the best available family.
 */
private fun ReaderBlockStyle.toComposeFontFamily(embeddedFontFamiliesByHref: Map<String, FontFamily>): FontFamily? =
    fontHref?.let(embeddedFontFamiliesByHref::get)
        ?: fontFamily?.toComposeFontFamily()
        ?: fontFamilyName.toComposeFontFamilyOrNull()

private fun String?.toComposeFontFamilyOrNull(): FontFamily? = when (this?.lowercase()) {
    null -> null
    "serif" -> FontFamily.Serif
    "sans", "sans-serif", "system-ui" -> FontFamily.SansSerif
    "mono", "monospace" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> null
}

/**
 * The paragraph-level style a block is set in: its indents, its alignment, and the line height the book asks
 * for.
 *
 * A container never gets one: it spans the blocks inside it, and two paragraphs that overlap are something
 * Compose refuses outright. Everything a container states that a paragraph can carry — its line height, its
 * alignment — is an inherited property, so the blocks inside it already carry it themselves.
 *
 * The inline space before the text is the book's own: `margin-left` plus `padding-left`, which is how a
 * stylesheet indents a quotation, a table of contents or a nested note. Only when the book states neither
 * does the reader supply one, and then only for the kinds that would otherwise be unreadable — a list item
 * needs room for its marker, a table cell room off its neighbour. `text-indent` is added on top of that
 * inset for the first line, exactly as CSS composes the two.
 *
 * @param block the block to style.
 * @param indentsFirstLine false for a paragraph that began on an earlier page. Its opening line here is the
 * middle of a paragraph, so it takes no first-line indent: pagination measured it as a middle line, and
 * indenting it on the page costs a line's worth of room the page does not have, which pushed the last line
 * off the bottom.
 * @return the paragraph style, or null when nothing about this block departs from the page's own defaults.
 */
private fun blockParagraphStyle(
    block: ReaderBlock,
    indentsFirstLine: Boolean = true,
    justifies: Boolean = true,
    lineHeightMultiplier: Float = 1f,
): ParagraphStyle? {
    val readerInset = when (block.kind) {
        ReaderBlockKind.LIST_ITEM -> block.level.coerceAtLeast(1) * 1.25f
        ReaderBlockKind.TABLE_HEADER_CELL,
        ReaderBlockKind.TABLE_CELL,
        -> 0.75f
        else -> 0f
    }
    val bookInset = block.style?.insetStartEm
        ?: ((block.style?.marginStartEm ?: 0f) + (block.style?.paddingStartEm ?: 0f))
    val inset = if (bookInset > 0f) bookInset else readerInset
    val align = when (block.align) {
        ReaderTextAlign.CENTER -> TextAlign.Center
        ReaderTextAlign.END -> TextAlign.End
        ReaderTextAlign.JUSTIFY -> if (justifies) TextAlign.Justify else TextAlign.Start
        ReaderTextAlign.START -> TextAlign.Start
        null -> null
    }
    val firstLineIndent = block.style?.textIndentEm?.takeIf { indentsFirstLine } ?: 0f
    // The book's line height rides the reader's slider rather than replacing it, anchored at the slider's
    // neutral point: at the default the block draws exactly what the book stated, and moving the slider
    // scales it proportionally. Multiplying by the raw slider value instead compounded the default 145%
    // into every styled book — lines half again looser than the book asked for out of the box.
    val lineHeight = block.style?.lineHeightScale
        ?.times(lineHeightMultiplier / ReaderDefaultLineHeightMultiplier)?.em
        ?: TextUnit.Unspecified
    if (inset == 0f && align == null && firstLineIndent == 0f && lineHeight == TextUnit.Unspecified) return null
    return ParagraphStyle(
        textAlign = align ?: TextAlign.Unspecified,
        textIndent = TextIndent(firstLine = (inset + firstLineIndent).em, restLine = inset.em),
        lineHeight = lineHeight,
    )
}

/**
 * Whether justifying this stretch would set evenly, or tear holes in it.
 *
 * Justification here can only stretch the spaces between words, which is what a Latin column is built to
 * absorb. A CJK column is not: its lines break between characters, its spaces are few and far apart, and
 * pushing a line out to the margin by widening three of them leaves gaps wide enough to read as separate
 * columns. The book still gets its alignment wherever the stretch can carry it, and falls back to a ragged
 * edge — which is what a phone-width CJK column wants anyway — where it cannot.
 *
 * @receiver the rendered stretch of text.
 * @param start first offset of the block, relative to that stretch.
 * @param end one past its last offset.
 * @return true when the block is not dominated by characters that carry no inter-word space.
 */
private fun String.justifiesWell(start: Int, end: Int): Boolean {
    val from = start.coerceIn(0, length)
    val until = end.coerceIn(from, length)
    if (until <= from) return true
    var wide = 0
    var letters = 0
    for (index in from until until) {
        val char = this[index]
        if (char.isWhitespace()) continue
        letters += 1
        if (char.isWideScript()) wide += 1
    }
    if (letters == 0) return true
    return wide * 2 < letters
}

/** Whether this character belongs to a script whose lines break between characters rather than at spaces. */
private fun Char.isWideScript(): Boolean = code in 0x1100..0x11FF ||
    code in 0x2E80..0xA4CF ||
    code in 0xAC00..0xD7AF ||
    code in 0xF900..0xFAFF ||
    code in 0xFF00..0xFFEF

/**
 * @param span the inline run whose semantic and CSS emphasis should be rendered.
 * @return the Compose style that renders it; a link is underlined here and carries its href as a separate
 * annotation, since a colour alone would not survive a theme change.
 */
private fun inlineSpanStyle(
    span: com.tedd.teddreader.core.common.model.ReaderSpan,
    embeddedFontFamiliesByHref: Map<String, FontFamily>,
    publisherColorsEnabled: Boolean,
    publisherFontsEnabled: Boolean,
): SpanStyle? {
    val semanticStyle = when (span.style) {
        ReaderInlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        ReaderInlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        ReaderInlineStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
        ReaderInlineStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        ReaderInlineStyle.MONOSPACE -> SpanStyle(fontFamily = FontFamily.Monospace)
        ReaderInlineStyle.SUPERSCRIPT -> SpanStyle(baselineShift = BaselineShift.Superscript)
        ReaderInlineStyle.SUBSCRIPT -> SpanStyle(baselineShift = BaselineShift.Subscript)
        ReaderInlineStyle.LINK -> SpanStyle(textDecoration = TextDecoration.Underline)
        null -> null
    }
    val deltaStyle = span.styleDelta?.toComposeSpanStyle(embeddedFontFamiliesByHref, publisherColorsEnabled, publisherFontsEnabled)
    return when {
        semanticStyle == null -> deltaStyle
        deltaStyle == null -> semanticStyle
        else -> semanticStyle.merge(deltaStyle)
    }
}

private fun ReaderSpanStyle.toComposeSpanStyle(
    embeddedFontFamiliesByHref: Map<String, FontFamily>,
    publisherColorsEnabled: Boolean,
    publisherFontsEnabled: Boolean,
): SpanStyle = SpanStyle(
    fontWeight = bold?.let { if (it) FontWeight.Bold else FontWeight.Normal },
    fontStyle = italic?.let { if (it) FontStyle.Italic else FontStyle.Normal },
    // A span's em is resolved by Compose against the size already in force at its position, which is
    // exactly what a delta ratio means — no re-anchoring to the reader's base here.
    fontSize = fontScale?.em ?: TextUnit.Unspecified,
    fontFamily = toComposeFontFamily(embeddedFontFamiliesByHref).takeIf { publisherFontsEnabled },
    color = foregroundColor.takeIf { publisherColorsEnabled }?.toColor() ?: Color.Unspecified,
    textDecoration = toTextDecoration(),
)

/** The embedded or generic family this span delta asks for, on the same terms as the block resolver. */
private fun ReaderSpanStyle.toComposeFontFamily(embeddedFontFamiliesByHref: Map<String, FontFamily>): FontFamily? =
    fontHref?.let(embeddedFontFamiliesByHref::get)
        ?: fontFamily?.toComposeFontFamily()
        ?: fontFamilyName.toComposeFontFamilyOrNull()

/** The decoration this span delta asks for, on the same terms as [ReaderBlockStyle.toTextDecoration]. */
private fun ReaderSpanStyle.toTextDecoration(): TextDecoration? = when {
    underline == true && lineThrough == true ->
        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    underline == true -> TextDecoration.Underline
    lineThrough == true -> TextDecoration.LineThrough
    underline == false || lineThrough == false -> TextDecoration.None
    else -> null
}

/**
 * The decoration this style asks for, or null when the book said nothing about it.
 *
 * [TextDecoration.None] is what a book that turned decoration off gets, and it is what makes
 * `a { text-decoration: none }` win over the underline a link is otherwise drawn with.
 */
private fun ReaderBlockStyle.toTextDecoration(): TextDecoration? = when {
    underline == true && lineThrough == true ->
        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
    underline == true -> TextDecoration.Underline
    lineThrough == true -> TextDecoration.LineThrough
    underline == false || lineThrough == false -> TextDecoration.None
    else -> null
}

/**
 * Reserves exactly the box [readerImageSize] says the picture occupies, so the line the text lays out around
 * is the line the image is actually drawn into.
 *
 * @param block the picture, cover or rule to reserve room for.
 * @param isStandalone whether it has its line to itself. A picture set inside a sentence sits on the text's
 * own centre, the way a glyph does; a plate is centred in its own line.
 * @param lineWidthEm the text column in em, or 0 to fall back to [DefaultImageWidthEm].
 * @param maxHeightEm the page height in em, or 0 to fall back to [DefaultImageMaxHeightEm].
 * @param emInPx CSS pixels per em, for turning an intrinsic pixel width into em.
 * @param isFloat whether this image is rendered as a floated full-column placeholder with nested text beside it.
 * @return the box to reserve; a one-em square for any other kind, which no caller asks for.
 */
private fun placeholderFor(
    block: ReaderBlock,
    isStandalone: Boolean,
    lineWidthEm: Float,
    maxHeightEm: Float,
    emInPx: Float,
    isFloat: Boolean,
): Placeholder = when (block.kind) {
    ReaderBlockKind.IMAGE,
    ReaderBlockKind.COVER_IMAGE,
    ReaderBlockKind.SEPARATOR,
    -> {
        val size = block.readerImageSize(
            columnWidthEm = lineWidthEm.takeIf { it > 0f } ?: DefaultImageWidthEm,
            maxHeightEm = maxHeightEm.takeIf { it > 0f } ?: DefaultImageMaxHeightEm,
            emInPx = emInPx,
        )
        Placeholder(
            width = (if (isFloat) lineWidthEm.takeIf { it > 0f } ?: DefaultImageWidthEm else size.widthEm).em,
            height = size.heightEm.em,
            placeholderVerticalAlign = if (isStandalone || isFloat) PlaceholderVerticalAlign.Center else PlaceholderVerticalAlign.TextCenter,
        )
    }
    else -> Placeholder(1.em, 1.em, PlaceholderVerticalAlign.Center)
}

/** Column used when the caller has no measured one — wide enough that a plate is not shrunk to a thumbnail. */
private const val DefaultImageWidthEm = 20f
/** Page height used when the caller has no measured one, on the same terms. */
private const val DefaultImageMaxHeightEm = 26f

/**
 * @param level the heading's level, clamped to 1..6 so a malformed document cannot ask for type of any size.
 * @return the multiple of the reader's font size that heading is set at, tightening as the level deepens.
 */
private fun headingScale(level: Int): Float = when (level.coerceIn(1, 6)) {
    1 -> 1.55f
    2 -> 1.4f
    3 -> 1.3f
    4 -> 1.2f
    5 -> 1.1f
    else -> 1.05f
}

/**
 * One block cut down to the stretch of text being rendered.
 *
 * Deliberately not a data class: it is looked up by [index], never compared field by field — comparing whole
 * blocks, spans and all, is what made building a page quadratic.
 *
 * @property index the block's position in the caller's own list, which is its identity here.
 * @property block the block itself.
 * @property localStart where it starts, relative to the rendered stretch.
 * @property localEnd where it ends, on the same terms.
 * @property includesStart whether the block's own beginning falls inside this stretch — false for a
 * paragraph continued from an earlier page, which is what suppresses its prefix and its first-line indent.
 */
private class ClampedBlock(
    val index: Int,
    val block: ReaderBlock,
    val localStart: Int,
    val localEnd: Int,
    val includesStart: Boolean,
)

/**
 * A reserved box while the string is still being built, before its final size is known.
 *
 * Float fitting may attach nested content and consume extra source offsets before the final
 * [ReaderPlaceholder] is built from this spec.
 *
 * @property id the inline-content key this box will be matched by.
 * @property kind what stands in it.
 * @property href the image's path inside the container.
 * @property label the image's alt text.
 * @property block the block it came from, kept so the size can be computed once the column is known.
 * @property start where the placeholder character landed in the rendered text.
 */
private data class PlaceholderSpec(
    val id: String,
    val kind: ReaderBlockKind,
    val href: String?,
    val label: String?,
    val block: ReaderBlock,
    val start: Int,
    val floatContent: ReaderFloatContent? = null,
)

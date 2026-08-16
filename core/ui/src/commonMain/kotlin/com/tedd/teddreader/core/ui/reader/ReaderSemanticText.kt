package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.text.appendInlineContent
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
import androidx.compose.ui.unit.em
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import com.tedd.teddreader.core.common.model.readerImageSize
import com.tedd.teddreader.core.common.model.standaloneBlocks
import com.tedd.teddreader.core.common.model.TextRange

private const val ObjectReplacementChar = '\uFFFC'
private const val ReaderLinkTag = "link"

data class ReaderSemanticText(
    val annotatedString: AnnotatedString,
    val offsetMap: IntArray,
    val placeholders: List<ReaderPlaceholder>,
)

data class ReaderPlaceholder(
    val id: String,
    val kind: ReaderBlockKind,
    val href: String? = null,
    val label: String? = null,
    val placeholder: Placeholder,
    val start: Int,
    val end: Int,
)

fun buildReaderSemanticText(
    text: String,
    blocks: List<ReaderBlock>,
    range: TextRange = TextRange(0, text.length.toLong()),
    /** Width of the text column, in em, which bounds an image like `max-width: 100%`. */
    lineWidthEm: Float = 0f,
    /** Height available for one page, in em, which bounds an image like `max-height`. */
    maxHeightEm: Float = 0f,
    /** CSS pixels per em, which turns an image's intrinsic pixel width into em. */
    emInPx: Float = 0f,
): ReaderSemanticText {
    if (text.isEmpty()) {
        return ReaderSemanticText(AnnotatedString(""), intArrayOf(0), emptyList())
    }

    val absoluteStart = range.start.toInt()
    val absoluteEnd = range.end.toInt().coerceAtMost(absoluteStart + text.length)
    val localLength = (absoluteEnd - absoluteStart).coerceAtLeast(0)
    val sourceToDisplay = IntArray(localLength + 1)
    // A growable IntArray rather than an ArrayList<Int>: this holds one entry per rendered character,
    // and the boxed list allocated an Integer for every one of them. On a chapter of any size that
    // allocation, not the layout, was the bulk of the work.
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
    // Where each block's own text begins once prefixes are written, addressed by the block's position
    // rather than by its value. Searching this list for an equal block instead compared whole blocks —
    // spans and all — once per block, which is quadratic and was the slowest step in opening a book.
    val blockDisplayStart = HashMap<Int, Int>(clampedBlocks.size)
    val blocksByStart = clampedBlocks.groupBy { it.localStart }
    val standaloneByStart = clampedBlocks
        .filter { it.block.kind == ReaderBlockKind.IMAGE || it.block.kind == ReaderBlockKind.COVER_IMAGE || it.block.kind == ReaderBlockKind.SEPARATOR }
        .associateBy { it.localStart }

    var localIndex = 0
    while (localIndex < localLength) {
        val sourceAbsolute = absoluteStart + localIndex
        val sourceChar = text[localIndex]
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
        val standalone = standaloneByStart[localIndex]
        if (standalone != null && standalone.includesStart) {
            val placeholderId = "${standalone.block.kind.name.lowercase()}-${absoluteStart + localIndex}-${placeholderSpecs.size}"
            display.append(ObjectReplacementChar)
            displayToSource += sourceAbsolute
            placeholderSpecs += PlaceholderSpec(
                id = placeholderId,
                kind = standalone.block.kind,
                href = standalone.block.imageHref,
                label = standalone.block.label,
                block = standalone.block,
                start = display.length - 1,
            )
            val nextLocalIndex = standalone.localEnd.coerceAtLeast(localIndex + 1)
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
        val blockStart = blockDisplayStart[block.index] ?: sourceToDisplay[block.localStart]
        val blockEnd = sourceToDisplay[block.localEnd]
        if (blockEnd <= blockStart) return@forEach

        blockSpanStyle(block.block)?.let { spans += (blockStart until blockEnd) to it }
        blockParagraphStyle(block.block)?.let { paragraphs += (blockStart until blockEnd) to it }

        block.block.spans.forEach { span ->
            val start = (span.range.start - range.start).toInt().coerceIn(0, localLength)
            val end = (span.range.end - range.start).toInt().coerceIn(start, localLength)
            if (end <= start) return@forEach
            val displayStart = sourceToDisplay[start]
            val displayEnd = sourceToDisplay[end]
            if (displayEnd <= displayStart) return@forEach
            inlineSpanStyle(span.style)?.let { spans += (displayStart until displayEnd) to it }
            if (span.style == ReaderInlineStyle.LINK) {
                span.href?.let { href ->
                    annotations += Triple(ReaderLinkTag, href, displayStart until displayEnd)
                }
            }
        }
    }

    // A picture on a line of its own is its own paragraph: that is what centres it the way the book
    // asks and what stops a line of prose from being set beside it. A picture written inside a
    // sentence gets none of this — it is part of that paragraph, and giving it a paragraph of its own
    // would both break the sentence and overlap the enclosing style.
    val standaloneBlocks = clampedBlocks.map { it.block }.standaloneBlocks().toSet()
    placeholderSpecs.forEach { spec ->
        if (spec.block !in standaloneBlocks) return@forEach
        blockParagraphStyle(spec.block)?.let { style ->
            paragraphs += (spec.start until spec.start + 1) to style
        }
    }

    val placeholders = placeholderSpecs.map { spec ->
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
            ),
            start = spec.start,
            end = spec.start + 1,
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
        spans.forEach { (rangeValue, style) -> addStyle(style, rangeValue.first, rangeValue.last + 1) }
        paragraphs.forEach { (rangeValue, style) -> addStyle(style, rangeValue.first, rangeValue.last + 1) }
        annotations.forEach { (tag, value, rangeValue) -> addStringAnnotation(tag, value, rangeValue.first, rangeValue.last + 1) }
    }

    return ReaderSemanticText(
        annotatedString = annotatedString,
        offsetMap = displayToSource.toIntArray(),
        placeholders = placeholders,
    )
}

/** Append-only IntArray, so mapping every rendered character back to its source costs no boxing. */
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

fun ReaderSemanticText.sourceOffsetFor(displayIndex: Int): Int =
    offsetMap[displayIndex.coerceIn(0, offsetMap.lastIndex)]

private fun blockPrefix(block: ReaderBlock): String = when (block.kind) {
    ReaderBlockKind.QUOTE -> "│ "
    ReaderBlockKind.LIST_ITEM -> "${"  ".repeat((block.level - 1).coerceAtLeast(0))}${block.label ?: "•"} "
    else -> ""
}

private fun blockSpanStyle(block: ReaderBlock): SpanStyle? = when (block.kind) {
    ReaderBlockKind.HEADING -> SpanStyle(
        fontWeight = FontWeight.Bold,
        fontSize = headingScale(block.level).em,
    )
    ReaderBlockKind.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic)
    ReaderBlockKind.PREFORMATTED -> SpanStyle(fontFamily = FontFamily.Monospace)
    ReaderBlockKind.TABLE_HEADER_CELL -> SpanStyle(fontWeight = FontWeight.SemiBold)
    else -> null
}

private fun blockParagraphStyle(block: ReaderBlock): ParagraphStyle? {
    val indent = when (block.kind) {
        ReaderBlockKind.QUOTE -> 1.25.em
        ReaderBlockKind.LIST_ITEM -> (block.level.coerceAtLeast(1) * 1.25).em
        ReaderBlockKind.TABLE_HEADER_CELL,
        ReaderBlockKind.TABLE_CELL,
            -> 0.75.em
        else -> null
    }
    val align = when (block.align) {
        ReaderTextAlign.CENTER -> TextAlign.Center
        ReaderTextAlign.END -> TextAlign.End
        ReaderTextAlign.JUSTIFY -> TextAlign.Justify
        ReaderTextAlign.START -> TextAlign.Start
        // A heading the book does not align itself is centred: it is the chapter title, and
        // pagination starts each chapter on a fresh page, so this is the line at the top of it.
        null -> TextAlign.Center.takeIf { block.kind == ReaderBlockKind.HEADING }
    }
    if (indent == null && align == null) return null
    return ParagraphStyle(
        textAlign = align ?: TextAlign.Unspecified,
        textIndent = indent?.let { TextIndent(firstLine = it, restLine = it) } ?: TextIndent(),
    )
}

private fun inlineSpanStyle(style: ReaderInlineStyle): SpanStyle? = when (style) {
    ReaderInlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    ReaderInlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    ReaderInlineStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
    ReaderInlineStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    ReaderInlineStyle.MONOSPACE -> SpanStyle(fontFamily = FontFamily.Monospace)
    ReaderInlineStyle.SUPERSCRIPT -> SpanStyle(baselineShift = BaselineShift.Superscript)
    ReaderInlineStyle.SUBSCRIPT -> SpanStyle(baselineShift = BaselineShift.Subscript)
    ReaderInlineStyle.LINK -> SpanStyle(textDecoration = TextDecoration.Underline)
}

/**
 * Reserves exactly the box [readerImageSize] says the picture occupies, so the line the text lays out
 * around is the line the image is actually drawn into.
 */
private fun placeholderFor(
    block: ReaderBlock,
    isStandalone: Boolean,
    lineWidthEm: Float,
    maxHeightEm: Float,
    emInPx: Float,
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
            width = size.widthEm.em,
            height = size.heightEm.em,
            // A picture set inside a sentence sits on the text's own centre, the way a glyph does; a
            // plate has its line to itself and is centred in it.
            placeholderVerticalAlign = if (isStandalone) {
                PlaceholderVerticalAlign.Center
            } else {
                PlaceholderVerticalAlign.TextCenter
            },
        )
    }
    else -> Placeholder(1.em, 1.em, PlaceholderVerticalAlign.Center)
}

private const val DefaultImageWidthEm = 20f
private const val DefaultImageMaxHeightEm = 26f

private fun headingScale(level: Int): Float = when (level.coerceIn(1, 6)) {
    1 -> 1.55f
    2 -> 1.4f
    3 -> 1.3f
    4 -> 1.2f
    5 -> 1.1f
    else -> 1.05f
}

/** Deliberately not a data class: it is looked up by [index], never compared field by field. */
private class ClampedBlock(
    val index: Int,
    val block: ReaderBlock,
    val localStart: Int,
    val localEnd: Int,
    val includesStart: Boolean,
)

private data class PlaceholderSpec(
    val id: String,
    val kind: ReaderBlockKind,
    val href: String?,
    val label: String?,
    val block: ReaderBlock,
    val start: Int,
)

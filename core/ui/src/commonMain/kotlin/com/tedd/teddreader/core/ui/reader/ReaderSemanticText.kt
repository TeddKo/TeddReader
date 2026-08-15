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
): ReaderSemanticText {
    if (text.isEmpty()) {
        return ReaderSemanticText(AnnotatedString(""), intArrayOf(0), emptyList())
    }

    val absoluteStart = range.start.toInt()
    val absoluteEnd = range.end.toInt().coerceAtMost(absoluteStart + text.length)
    val localLength = (absoluteEnd - absoluteStart).coerceAtLeast(0)
    val sourceToDisplay = IntArray(localLength + 1)
    val displayToSource = ArrayList<Int>(text.length + blocks.size * 4 + 1)
    val display = StringBuilder(text.length + blocks.size * 4)
    val placeholderSpecs = mutableListOf<PlaceholderSpec>()
    val blockRanges = mutableListOf<BlockDisplayRange>()

    val clampedBlocks = blocks.mapNotNull { block ->
        val start = block.range.start.coerceAtLeast(range.start)
        val end = block.range.end.coerceAtMost(range.end)
        if (start >= end && block.kind != ReaderBlockKind.IMAGE && block.kind != ReaderBlockKind.SEPARATOR) {
            null
        } else {
            val localStart = (start - range.start).toInt().coerceAtLeast(0)
            val localEnd = (end - range.start).toInt().coerceAtLeast(localStart)
            ClampedBlock(block, localStart, localEnd, includesStart = block.range.start in range.start until range.end)
        }
    }
    val blocksByStart = clampedBlocks.groupBy { it.localStart }
    val standaloneByStart = clampedBlocks
        .filter { it.block.kind == ReaderBlockKind.IMAGE || it.block.kind == ReaderBlockKind.SEPARATOR }
        .associateBy { it.localStart }

    var localIndex = 0
    while (localIndex < localLength) {
        val sourceAbsolute = absoluteStart + localIndex
        val sourceChar = text[localIndex]
        blocksByStart[localIndex].orEmpty()
            .filterNot { it.block.kind == ReaderBlockKind.IMAGE || it.block.kind == ReaderBlockKind.SEPARATOR }
            .forEach { block ->
                val prefix = blockPrefix(block.block).takeIf { block.includesStart }.orEmpty()
                if (prefix.isNotEmpty()) {
                    val prefixStart = display.length
                    prefix.forEach { char ->
                        display.append(char)
                        displayToSource += sourceAbsolute
                    }
                    blockRanges += BlockDisplayRange(block, prefixStart)
                } else {
                    blockRanges += BlockDisplayRange(block, display.length)
                }
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
        if (block.block.kind == ReaderBlockKind.IMAGE || block.block.kind == ReaderBlockKind.SEPARATOR) return@forEach
        val blockStart = blockRanges.firstOrNull { it.block == block }?.displayStart ?: sourceToDisplay[block.localStart]
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

    val placeholders = placeholderSpecs.map { spec ->
        ReaderPlaceholder(
            id = spec.id,
            kind = spec.kind,
            href = spec.href,
            label = spec.label,
            placeholder = placeholderFor(spec.kind),
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
        null -> null
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

private fun placeholderFor(kind: ReaderBlockKind): Placeholder = when (kind) {
    ReaderBlockKind.IMAGE -> Placeholder(8.em, 6.em, PlaceholderVerticalAlign.Center)
    ReaderBlockKind.SEPARATOR -> Placeholder(8.em, 1.25.em, PlaceholderVerticalAlign.Center)
    else -> Placeholder(1.em, 1.em, PlaceholderVerticalAlign.Center)
}

private fun headingScale(level: Int): Float = when (level.coerceIn(1, 6)) {
    1 -> 1.55f
    2 -> 1.4f
    3 -> 1.3f
    4 -> 1.2f
    5 -> 1.1f
    else -> 1.05f
}

private data class ClampedBlock(
    val block: ReaderBlock,
    val localStart: Int,
    val localEnd: Int,
    val includesStart: Boolean,
)

private data class BlockDisplayRange(
    val block: ClampedBlock,
    val displayStart: Int,
)

private data class PlaceholderSpec(
    val id: String,
    val kind: ReaderBlockKind,
    val href: String?,
    val label: String?,
    val start: Int,
)

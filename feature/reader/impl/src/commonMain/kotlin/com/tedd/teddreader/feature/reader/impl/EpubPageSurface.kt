package com.tedd.teddreader.feature.reader.impl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.designsystem.readerTextStyle
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.visual_page_unavailable
import com.tedd.teddreader.core.ui.reader.ReaderPlaceholder
import com.tedd.teddreader.core.ui.reader.buildReaderSemanticText
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun EpubPageSurface(
    page: ReaderPageUi,
    style: com.tedd.teddreader.core.common.model.ReaderStyle,
    modifier: Modifier = Modifier,
) {
    val plateBlock = epubFullPagePlate(text = page.text, blocks = page.blocks)
    if (plateBlock != null) {
        EpubImageBox(
            imageBytes = plateBlock.imageHref?.let(page.embeddedImages::get),
            label = plateBlock.label,
            isFailed = plateBlock.imageHref != null && plateBlock.imageHref in page.failedEmbeddedImageHrefs,
            modifier = modifier,
        )
        return
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // Same em conversion the pagination breaker uses (see rememberReaderPageBreaker), so a
        // standalone image lays out here exactly as wide/tall as it was paginated to be.
        val fontPx = with(density) { style.fontSizeSp.sp.toPx() }
        val lineWidthEm = if (fontPx > 0f) widthPx / fontPx else 0f
        val maxHeightEm = if (fontPx > 0f) heightPx / fontPx else 0f
        val emInPx = style.fontSizeSp * density.fontScale

        val semanticText = remember(page.text, page.blocks, page.textRange, lineWidthEm, maxHeightEm, emInPx) {
            buildReaderSemanticText(
                text = page.text,
                blocks = page.blocks,
                range = page.textRange ?: com.tedd.teddreader.core.common.model.TextRange(0, page.text.length.toLong()),
                lineWidthEm = lineWidthEm,
                maxHeightEm = maxHeightEm,
                emInPx = emInPx,
            )
        }
        val inlineContent = remember(semanticText.placeholders, page.embeddedImages, page.failedEmbeddedImageHrefs) {
            semanticText.placeholders.associate { placeholder ->
                placeholder.id to androidx.compose.foundation.text.InlineTextContent(
                    placeholder = placeholder.placeholder,
                ) {
                    EpubInlinePlaceholder(
                        placeholder = placeholder,
                        imageBytes = placeholder.href?.let(page.embeddedImages::get),
                        isFailed = placeholder.href != null && placeholder.href in page.failedEmbeddedImageHrefs,
                    )
                }
            }
        }

        // A page that ends its section is a page the book gave over to something short — a chapter
        // epigraph, a part title, the tail of a chapter — and centring it reads as the plate it is,
        // rather than as a page that failed to load with the rest of the sheet blank underneath. A
        // mid-section page of running prose is never this, whatever pagination produced it, which is
        // what keeps the first line of ordinary pages at the same height as you turn through them.
        //
        // This used to be decided from how much of the sheet the measured text filled — under some
        // fraction, centred — but an estimated pagination (the only kind available before the type has
        // ever been measured for real, see TextPageLayoutEngine) cannot know the line height the book's
        // own stylesheet sets and under-fills badly, so on a fresh install every page looked short and
        // was centred until the real measurement replaced it. Whether a page ends its section is true
        // by construction from where pagination put its boundaries, estimated or not, so it does not
        // share that failure.
        val readerTextStyle = style.readerTextStyle()
        Layout(
            content = {
                BasicText(
                    text = semanticText.annotatedString,
                    style = readerTextStyle,
                    inlineContent = inlineContent,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            // Measured against an unbounded height, so a short page reports the height it really is
            // rather than the height of the sheet it was given.
            val placeable = measurables.first().measure(
                constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
            )
            val pageHeight = constraints.maxHeight
            val top = if (page.isSectionTail) {
                ((pageHeight - placeable.height) / 2).coerceAtLeast(0)
            } else {
                0
            }
            layout(constraints.maxWidth, pageHeight) { placeable.place(0, top) }
        }
    }
}

@Composable
private fun EpubInlinePlaceholder(
    placeholder: ReaderPlaceholder,
    imageBytes: ByteArray?,
    isFailed: Boolean,
) {

    when (placeholder.kind) {
        ReaderBlockKind.SEPARATOR -> HorizontalDivider(modifier = Modifier.fillMaxSize())
        ReaderBlockKind.IMAGE,
        ReaderBlockKind.COVER_IMAGE,
            -> EpubImageBox(
                imageBytes = imageBytes,
                label = placeholder.label,
                isFailed = isFailed,
            )
        else -> Box(modifier = Modifier.fillMaxSize())
    }
}


@Composable
private fun EpubImageBox(
    imageBytes: ByteArray?,
    label: String?,
    isFailed: Boolean,
    modifier: Modifier = Modifier,
) {
    val platformContext = LocalPlatformContext.current
    val request = remember(imageBytes, platformContext) {
        imageBytes?.let { bytes ->
            ImageRequest.Builder(platformContext)
                .data(bytes)
                .size(MaxInlineImageDimensionPx, MaxInlineImageDimensionPx)
                // Fading in rather than appearing means the page settles once, instead of snapping as
                // each picture finishes decoding.
                .crossfade(ImageFadeMillis)
                .build()
        }
    }

    // No frame and no plate behind the picture: the placeholder is already the exact box the image
    // occupies, so a background would show as a slab around every illustration and a rounded corner
    // would clip the artwork the book actually drew.
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // A picture arrives in two steps — the bytes are read out of the book, then decoded — and
        // showing something during each step made the page change three times before settling: alt
        // text or a spinner, then a second spinner, then the picture. The space is already reserved
        // at the right size, so waiting quietly and fading the picture in leaves nothing to flicker.
        // Only a picture that will never arrive says so.
        when {
            request != null -> SubcomposeAsyncImage(
                model = request,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                loading = {},
                error = {
                    Text(
                        text = label ?: stringResource(Res.string.visual_page_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            isFailed -> Text(
                text = label ?: stringResource(Res.string.visual_page_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val MaxInlineImageDimensionPx = 2_048
private const val ImageFadeMillis = 120

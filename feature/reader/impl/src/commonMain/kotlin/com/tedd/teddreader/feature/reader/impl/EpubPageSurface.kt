package com.tedd.teddreader.feature.reader.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
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
    val coverBlock = page.blocks.firstOrNull { it.kind == ReaderBlockKind.COVER_IMAGE }
    if (coverBlock != null) {
        EpubImageBox(
            imageBytes = coverBlock.imageHref?.let(page.embeddedImages::get),
            label = coverBlock.label,
            isFailed = coverBlock.imageHref != null && coverBlock.imageHref in page.failedEmbeddedImageHrefs,
            modifier = modifier,
        )
        return
    }

    val semanticText = remember(page.text, page.blocks, page.textRange) {
        buildReaderSemanticText(
            text = page.text,
            blocks = page.blocks,
            range = page.textRange ?: com.tedd.teddreader.core.common.model.TextRange(0, page.text.length.toLong()),
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

    BasicText(
        text = semanticText.annotatedString,
        style = style.readerTextStyle(),
        inlineContent = inlineContent,
        modifier = modifier,
    )
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
                .build()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            request != null -> SubcomposeAsyncImage(
                model = request,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                loading = {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                },
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
            label != null -> Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    }
}

private const val MaxInlineImageDimensionPx = 2_048

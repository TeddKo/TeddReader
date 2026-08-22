package com.tedd.teddreader.feature.reader.impl.image

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.visual_page_content_description
import com.tedd.teddreader.core.ui.generated.resources.visual_page_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * Renders one CBZ/image-format page: decodes [imageBytes] or [sourceUri] through Coil and fits it
 * to the available space, showing a spinner while decoding and a fixed message if the page is
 * already known to have failed.
 *
 * @param page zero-based page index, used only to number the accessibility content description.
 * @param imageBytes the page's already-loaded bytes, or null to load from [sourceUri] instead.
 * @param sourceUri a URI Coil can load the page from, used only when [imageBytes] is null.
 * @param isFailed whether this page already failed to decode; shown instead of the loading spinner
 *   when both [imageBytes] and [sourceUri] are null and this is true.
 * @param modifier applied to the outer [Box].
 */
@Composable
internal fun ImagePageSurface(
    page: Int,
    imageBytes: ByteArray?,
    sourceUri: String?,
    isFailed: Boolean,
    modifier: Modifier = Modifier,
) {
    val platformContext = LocalPlatformContext.current
    val request = remember(imageBytes, sourceUri, platformContext) {
        (imageBytes ?: sourceUri)?.let { data ->
            ImageRequest.Builder(platformContext)
                .data(data)
                .size(MaxReaderImageDimensionPx, MaxReaderImageDimensionPx)
                .build()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            request != null -> SubcomposeAsyncImage(
                model = request,
                contentDescription = stringResource(Res.string.visual_page_content_description, page + 1),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                loading = {
                    CircularProgressIndicator()
                },
                error = {
                    Text(
                        text = stringResource(Res.string.visual_page_unavailable),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            isFailed -> Text(
                text = stringResource(Res.string.visual_page_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> CircularProgressIndicator()
        }
    }
}

/**
 * Longest side, in pixels, Coil is asked to decode a page image down to. A raw CBZ page can carry
 * far more pixels than any device screen can show; capping the decode target trades a small amount
 * of headroom on an unusually large source image for a decode that does not hold several times more
 * pixels in memory than the screen will ever display.
 */
private const val MaxReaderImageDimensionPx = 2_048

package com.tedd.teddreader.feature.reader.impl.image

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Size
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.visual_page_content_description
import com.tedd.teddreader.core.ui.generated.resources.visual_page_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * Renders one CBZ/image-format page: decodes [imageBytes] or [sourceUri] through Coil and fits it
 * to the available space, showing a spinner while decoding and a fixed message after failure.
 *
 * CBZ bytes have no default Coil keyer, so [documentUri] and [page] provide a stable key that lets
 * duplicate page-effect compositions reuse one decoded bitmap. URI-backed image documents keep
 * Coil's own URI identity. Both paths resolve decode size from the actual layout constraints while
 * [MaxReaderImageDimensionPx] prevents unusually large sources from exceeding the reader cap.
 *
 * @param page zero-based page index used for accessibility text and CBZ bitmap cache identity.
 * @param documentUri stable URI of the document that owns this page.
 * @param imageBytes the page's already-loaded bytes, or null to load from [sourceUri] instead.
 * @param sourceUri a URI Coil can load the page from, used only when [imageBytes] is null.
 * @param isFailed whether loading the page bytes already failed.
 * @param modifier applied to the outer [Box].
 */
@Composable
internal fun ImagePageSurface(
    page: Int,
    documentUri: String?,
    imageBytes: ByteArray?,
    sourceUri: String?,
    isFailed: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = teddReaderColors()
    val platformContext = LocalPlatformContext.current
    val imageCacheKey = visualPageMemoryCacheKey(documentUri, page).takeIf { imageBytes != null }
    val request = remember(imageBytes, sourceUri, imageCacheKey, platformContext) {
        (imageBytes ?: sourceUri)?.let { data ->
            ImageRequest.Builder(platformContext)
                .data(data)
                .apply {
                    if (imageCacheKey != null) memoryCacheKey(imageCacheKey)
                }
                .maxBitmapSize(Size(MaxReaderImageDimensionPx, MaxReaderImageDimensionPx))
                .build()
        }
    }
    var isLoading by remember(request) { mutableStateOf(request != null) }
    var decodeFailed by remember(request) { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = stringResource(Res.string.visual_page_content_description, page + 1),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onLoading = {
                    isLoading = true
                    decodeFailed = false
                },
                onSuccess = {
                    isLoading = false
                    decodeFailed = false
                },
                onError = {
                    isLoading = false
                    decodeFailed = true
                },
            )
        }
        when {
            decodeFailed || request == null && isFailed -> TeddText(
                text = stringResource(Res.string.visual_page_unavailable),
                color = colors.onSurfaceVariant,
            )
            isLoading || request == null -> TeddLoadingIndicator()
        }
    }
}

/**
 * Builds one document-and-page scoped memory-cache key for CBZ page bytes.
 *
 * Coil 3.5.0 can fetch [ByteArray] data but has no default keyer for it. Length-prefixing the URI
 * keeps document identity unambiguous, and the page suffix prevents adjacent archive entries from
 * sharing a decoded bitmap. A missing URI returns null so no partial identity enters the cache.
 *
 * @param documentUri stable URI of the CBZ document that owns the page.
 * @param page zero-based archive page index.
 * @return a stable Coil memory-cache key, or null when the document identity is unavailable.
 */
internal fun visualPageMemoryCacheKey(documentUri: String?, page: Int): String? {
    val document = documentUri?.takeIf(String::isNotBlank) ?: return null
    return "visual:${document.length}:$document:$page"
}

/**
 * Longest side, in pixels, Coil is asked to decode a page image down to. A raw CBZ page can carry
 * far more pixels than any device screen can show; capping the decode target trades a small amount
 * of headroom on an unusually large source image for a decode that does not hold several times more
 * pixels in memory than the screen will ever display.
 */
private const val MaxReaderImageDimensionPx = 2_048

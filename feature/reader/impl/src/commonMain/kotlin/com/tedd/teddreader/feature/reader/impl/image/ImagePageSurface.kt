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

private const val MaxReaderImageDimensionPx = 2_048

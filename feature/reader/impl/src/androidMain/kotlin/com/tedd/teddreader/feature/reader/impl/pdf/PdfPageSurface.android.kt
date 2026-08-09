package com.tedd.teddreader.feature.reader.impl.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun PlatformPdfPageSurface(
    documentUri: String?,
    pageIndex: PageIndex,
    modifier: Modifier,
    message: String,
    contentPadding: PaddingValues,
    placeholderContentPadding: PaddingValues,
) {
    val context = LocalContext.current
    var state by remember(documentUri, pageIndex.current) {
        mutableStateOf<PdfRenderState>(PdfRenderState.Loading)
    }

    LaunchedEffect(documentUri, pageIndex.current) {
        state = renderPdfPage(
            context = context,
            documentUri = documentUri,
            pageIndex = pageIndex.current,
        )
    }

    when (val currentState = state) {
        PdfRenderState.Loading -> Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        is PdfRenderState.Rendered -> Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = currentState.image,
                contentDescription = stringResource(Res.string.pdf_page_content_description, pageIndex.current + 1),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        is PdfRenderState.Unavailable -> PdfPlaceholderSurface(
            pageIndex = pageIndex,
            modifier = modifier,
            message = currentState.message.ifBlank { message },
            contentPadding = placeholderContentPadding,
        )
    }
}

private sealed interface PdfRenderState {
    data object Loading : PdfRenderState
    data class Rendered(val image: ImageBitmap) : PdfRenderState
    data class Unavailable(val message: String) : PdfRenderState
}

private suspend fun renderPdfPage(
    context: Context,
    documentUri: String?,
    pageIndex: Int,
): PdfRenderState = withContext(Dispatchers.IO) {
    if (documentUri.isNullOrBlank()) {
        return@withContext PdfRenderState.Unavailable("PDF file URI is missing.")
    }

    runCatching {
        openPdfDescriptor(context, documentUri)?.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val safePageIndex = pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                renderer.openPage(safePageIndex).use { page ->
                    val scale = 2
                    val bitmap = Bitmap.createBitmap(
                        page.width * scale,
                        page.height * scale,
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    PdfRenderState.Rendered(bitmap.asImageBitmap())
                }
            }
        } ?: PdfRenderState.Unavailable("Unable to open PDF file.")
    }.getOrElse { throwable ->
        PdfRenderState.Unavailable(throwable.message ?: "Unable to render PDF page.")
    }
}

private fun openPdfDescriptor(
    context: Context,
    documentUri: String,
): ParcelFileDescriptor? {
    val uri = Uri.parse(documentUri)
    return if (uri.scheme == "file") {
        ParcelFileDescriptor.open(File(requireNotNull(uri.path)), ParcelFileDescriptor.MODE_READ_ONLY)
    } else {
        context.contentResolver.openFileDescriptor(uri, "r")
    }
}

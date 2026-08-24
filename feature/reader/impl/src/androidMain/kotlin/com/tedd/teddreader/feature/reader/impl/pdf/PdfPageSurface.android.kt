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

/**
 * The Android actual of `PlatformPdfPageSurface`: renders one PDF page as a bitmap through Android's
 * platform [PdfRenderer] and shows it, a loading spinner while the render is in flight, or a
 * placeholder if rendering failed. Re-renders whenever [documentUri] or the current page changes,
 * since [PdfRenderer] has no notion of "just update the page" — every page is its own decode.
 *
 * @param documentUri The source URI of the PDF to render, or null/blank to show the unavailable
 *   placeholder without attempting a render.
 * @param pageIndex The page to render (`pageIndex.current`) and the total page count shown alongside
 *   it on the placeholder/loading state.
 * @param modifier Applied to the surface's outer container in every state (loading, rendered,
 *   unavailable).
 * @param message The fallback text shown on the unavailable placeholder when the render failure did
 *   not produce its own message.
 * @param contentPadding Padding around the rendered page image.
 * @param placeholderContentPadding Padding around the unavailable-state placeholder content, kept
 *   separate from [contentPadding] since the placeholder's layout differs from the page image's.
 */
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

/**
 * The three states a PDF page render can be in, driven entirely by [renderPdfPage]'s result — kept
 * as a private sealed hierarchy rather than a nullable bitmap plus a separate error string, so
 * `PlatformPdfPageSurface`'s `when` over it is exhaustive and can never show a bitmap and an error
 * message at the same time.
 */
private sealed interface PdfRenderState {
    /** The initial state while [renderPdfPage] is still decoding the page; shown as a centered spinner. */
    data object Loading : PdfRenderState

    /**
     * A successfully decoded page, ready to draw.
     *
     * @property image The rendered page as a bitmap.
     */
    data class Rendered(val image: ImageBitmap) : PdfRenderState

    /**
     * The page could not be rendered — a missing URI, a PDF the platform renderer could not open, or
     * an exception during rendering.
     *
     * @property message A human-readable description of why rendering failed, shown in place of the
     *   page.
     */
    data class Unavailable(val message: String) : PdfRenderState
}

/**
 * Decodes one PDF page into a bitmap off the main thread, since [PdfRenderer] does file I/O and
 * native rendering work that would otherwise block composition. Opens and closes the file
 * descriptor, the [PdfRenderer], and the individual [PdfRenderer.Page] through nested `use { }`
 * blocks — all three are platform closeable types the renderer requires callers to release
 * explicitly, and leaving any of them open leaks a native resource. Renders at twice the page's
 * reported point size, so text and vector content stay sharp on a high-density display rather than
 * being upscaled from a 1:1 bitmap.
 *
 * Every failure path — a missing/blank URI, a descriptor Android refuses to open, or an exception
 * during rendering — is caught and turned into [PdfRenderState.Unavailable] with a description of
 * what went wrong, rather than letting the exception propagate into the composable.
 *
 * @param context Used to resolve [documentUri] into a file descriptor via the content resolver.
 * @param documentUri The source URI of the PDF, or null/blank for the missing-URI failure case.
 * @param pageIndex The zero-based page to render, clamped into the document's actual page range so
 *   a stale or out-of-sync index cannot crash the renderer.
 * @return [PdfRenderState.Rendered] on success, or [PdfRenderState.Unavailable] describing the
 *   failure.
 */
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

/**
 * Opens a read-only file descriptor for [documentUri], the raw handle [PdfRenderer] requires. A
 * `file://` URI is opened directly from disk; anything else goes through the content resolver,
 * since a `content://` URI (a document picked from Google Drive or another provider) has no path
 * [ParcelFileDescriptor.open] can use directly.
 *
 * @param context Supplies the content resolver used for a non-`file://` [documentUri].
 * @param documentUri The source URI to open; must not be blank.
 * @return An open, read-only descriptor, or null if the content resolver could not open one.
 */
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

package com.tedd.teddreader.feature.reader.impl.pdf

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.tedd.teddreader.core.common.model.PageIndex
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFView

/**
 * The iOS actual of `PlatformPdfPageSurface`: a `UIKitView` hosting PDFKit's own `PDFView`, which
 * loads [documentUri] as a `PDFDocument` and jumps to [pageIndex] itself. Unlike the Android
 * actual's [android.graphics.pdf.PdfRenderer] page, there is no separate loading state to render
 * here, since `PDFView` owns its own document decode and paging and this composable never sees an
 * intermediate "not yet rendered" state to show a spinner for.
 *
 * @param documentUri the source `file://` URI of the PDF to render, or null/blank to show the
 *   unavailable placeholder without creating a `PDFView` at all.
 * @param pageIndex the page to jump `PDFView` to (`pageIndex.current`).
 * @param modifier applied to the `UIKitView`/placeholder container.
 * @param message the fallback text shown on the unavailable placeholder.
 * @param contentPadding padding applied around the `UIKitView` hosting `PDFView`.
 * @param placeholderContentPadding padding around the unavailable-state placeholder, forwarded to
 *   `PdfPlaceholderSurface` unchanged.
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
    if (documentUri.isNullOrBlank()) {
        PdfPlaceholderSurface(
            pageIndex = pageIndex,
            modifier = modifier,
            message = message,
            contentPadding = placeholderContentPadding,
        )
        return
    }

    val path = documentUri.removePrefix("file://")
    UIKitView(
        modifier = modifier.padding(contentPadding),
        factory = {
            PDFView().apply {
                autoScales = true
            }
        },
        update = { view ->
            val document = PDFDocument(NSURL.fileURLWithPath(path))
            view.document = document
            document.pageAtIndex(pageIndex.current.coerceAtLeast(0).toULong())?.let(view::goToPage)
        },
    )
}

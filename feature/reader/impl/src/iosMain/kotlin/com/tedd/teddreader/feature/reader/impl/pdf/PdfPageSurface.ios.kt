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

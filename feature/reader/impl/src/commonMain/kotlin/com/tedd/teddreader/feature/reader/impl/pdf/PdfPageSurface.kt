package com.tedd.teddreader.feature.reader.impl.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Renders one PDF page for the current platform, applying pinch-zoom and rotation as a
 * `graphicsLayer` transform on top of whatever [PlatformPdfPageSurface] draws — the platform
 * renderer itself never needs to know about zoom or rotation.
 *
 * @param pageIndex The page to render, and the total page count it is drawn against.
 * @param modifier Applied to the platform surface, before the zoom/rotation transform.
 * @param documentUri The document to render a page from; null falls through to the placeholder.
 * @param zoom Uniform scale factor layered on top of the rendered page.
 * @param rotationDegrees Clockwise rotation, in degrees, layered on top of the rendered page.
 * @param message Placeholder text shown when no real page can be rendered; defaults to a
 * "renderer not connected" message.
 * @param contentPadding Padding passed through to the platform surface around a rendered page.
 * @param placeholderContentPadding Padding passed through to the platform surface around
 * placeholder content.
 */
@Composable
fun PdfPageSurface(
    pageIndex: PageIndex,
    modifier: Modifier = Modifier,
    documentUri: String? = null,
    style: ReaderStyle = ReaderStyle(),
    zoom: Float = 1f,
    rotationDegrees: Float = 0f,
    message: String? = null,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    placeholderContentPadding: PaddingValues = PaddingValues(24.dp),
) {
    PlatformPdfPageSurface(
        documentUri = documentUri,
        pageIndex = pageIndex,
        modifier = modifier.graphicsLayer(
            scaleX = zoom,
            scaleY = zoom,
            rotationZ = rotationDegrees,
            colorFilter = style.pdfColorFilter(),
        ),
        message = message ?: stringResource(Res.string.pdf_renderer_not_connected),
        contentPadding = contentPadding,
        placeholderContentPadding = placeholderContentPadding,
    )
}


internal fun ReaderStyle.pdfColorFilter(): ColorFilter? =
    pdfThemeLuminanceMatrix()?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }

internal fun ReaderStyle.pdfThemeLuminanceMatrix(): FloatArray? {
    if (themeMode == ReaderThemeMode.PUBLISHER) return null

    val textRed = ((textColor.argb shr 16) and 0xFF).toFloat()
    val textGreen = ((textColor.argb shr 8) and 0xFF).toFloat()
    val textBlue = (textColor.argb and 0xFF).toFloat()
    val backgroundRed = ((backgroundColor.argb shr 16) and 0xFF).toFloat()
    val backgroundGreen = ((backgroundColor.argb shr 8) and 0xFF).toFloat()
    val backgroundBlue = (backgroundColor.argb and 0xFF).toFloat()

    return luminanceRemapMatrix(
        textRed = textRed,
        textGreen = textGreen,
        textBlue = textBlue,
        backgroundRed = backgroundRed,
        backgroundGreen = backgroundGreen,
        backgroundBlue = backgroundBlue,
    )
}

internal fun luminanceRemapMatrix(
    textRed: Float,
    textGreen: Float,
    textBlue: Float,
    backgroundRed: Float,
    backgroundGreen: Float,
    backgroundBlue: Float,
): FloatArray {
    val redDelta = backgroundRed - textRed
    val greenDelta = backgroundGreen - textGreen
    val blueDelta = backgroundBlue - textBlue
    val redScale = redDelta / 255f
    val greenScale = greenDelta / 255f
    val blueScale = blueDelta / 255f

    return floatArrayOf(
        PdfLumaRed * redScale, PdfLumaGreen * redScale, PdfLumaBlue * redScale, 0f, textRed,
        PdfLumaRed * greenScale, PdfLumaGreen * greenScale, PdfLumaBlue * greenScale, 0f, textGreen,
        PdfLumaRed * blueScale, PdfLumaGreen * blueScale, PdfLumaBlue * blueScale, 0f, textBlue,
        0f, 0f, 0f, 1f, 0f,
    )
}

private const val PdfLumaRed = 0.2126f
private const val PdfLumaGreen = 0.7152f
private const val PdfLumaBlue = 0.0722f

/**
 * Platform hook that actually renders a PDF page, or falls back to [PdfPlaceholderSurface] with
 * [message] when it cannot. The Android actual loads the page through
 * `android.graphics.pdf.PdfRenderer`, which must be closed after use, and decodes it into a
 * bitmap on a background dispatcher before showing it; the iOS actual instead hosts a PDFKit
 * `PDFView` through `UIKitView` and lets PDFKit itself page to [pageIndex].
 *
 * @param documentUri The document to render a page from; null renders the placeholder.
 * @param pageIndex The page to render, and the total page count it is drawn against.
 * @param modifier Applied to the rendered surface or placeholder.
 * @param message Shown by the placeholder when no real page can be rendered.
 * @param contentPadding Padding around a real rendered page.
 * @param placeholderContentPadding Padding around placeholder content.
 */
@Composable
internal expect fun PlatformPdfPageSurface(
    documentUri: String?,
    pageIndex: PageIndex,
    modifier: Modifier,
    message: String,
    contentPadding: PaddingValues,
    placeholderContentPadding: PaddingValues,
)

/**
 * Fallback content shown in place of a real PDF page: a page-number readout and an explanatory
 * [message], used both while [PlatformPdfPageSurface] has nothing to render yet and when it
 * cannot render at all (a missing or unreadable document).
 *
 * @param pageIndex The page this placeholder stands in for, and the total page count it is shown
 * against.
 * @param modifier Applied to this placeholder's root.
 * @param message Explains to the user why no real page is shown.
 * @param contentPadding Padding around the placeholder's content.
 */
@Composable
internal fun PdfPlaceholderSurface(
    pageIndex: PageIndex,
    modifier: Modifier = Modifier,
    message: String,
    contentPadding: PaddingValues = PaddingValues(24.dp),
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("PDF", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = stringResource(Res.string.pdf_page_fraction, pageIndex.current + 1, pageIndex.total),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Compose preview of [PdfPageSurface] with a sample page index, for the IDE preview pane. */
@Preview
@Composable
private fun PdfPageSurfacePreview() {
    TeddReaderTheme {
        PdfPageSurface(pageIndex = PageIndex(current = 0, total = 10))
    }
}

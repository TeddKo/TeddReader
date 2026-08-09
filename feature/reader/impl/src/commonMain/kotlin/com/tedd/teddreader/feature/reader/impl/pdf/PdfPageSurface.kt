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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.ui.teddString

@Composable
fun PdfPageSurface(
    pageIndex: PageIndex,
    modifier: Modifier = Modifier,
    documentUri: String? = null,
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
        ),
        message = message ?: teddString("PDF page renderer is not connected yet.", "PDF 페이지 렌더러가 아직 연결되지 않았습니다."),
        contentPadding = contentPadding,
        placeholderContentPadding = placeholderContentPadding,
    )
}

@Composable
internal expect fun PlatformPdfPageSurface(
    documentUri: String?,
    pageIndex: PageIndex,
    modifier: Modifier,
    message: String,
    contentPadding: PaddingValues,
    placeholderContentPadding: PaddingValues,
)

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
                text = teddString(
                    "Page ${pageIndex.current + 1} / ${pageIndex.total}",
                    "페이지 ${pageIndex.current + 1} / ${pageIndex.total}",
                ),
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

@Preview
@Composable
private fun PdfPageSurfacePreview() {
    TeddReaderTheme {
        PdfPageSurface(pageIndex = PageIndex(current = 0, total = 10))
    }
}

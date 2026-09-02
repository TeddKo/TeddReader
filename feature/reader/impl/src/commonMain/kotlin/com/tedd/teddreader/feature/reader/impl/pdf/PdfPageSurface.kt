package com.tedd.teddreader.feature.reader.impl.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * 현재 플랫폼을 위해 PDF 페이지 한 장을 그리며, [PlatformPdfPageSurface]가 무엇을 그려내든 그 위에 핀치/확대와
 * 회전을 `graphicsLayer` transform으로 얹는다 — 플랫폼 렌더러 자신은 확대나 회전에 대해 전혀 알 필요가 없다.
 *
 * @param pageIndex 그릴 페이지와, 그 페이지가 매겨지는 전체 페이지 수.
 * @param modifier zoom/회전 transform이 적용되기 전에 플랫폼 서피스에 적용된다.
 * @param documentUri 페이지를 그려낼 문서. null이면 플레이스홀더로 대체된다.
 * @param zoom 렌더링된 페이지 위에 얹히는 균일한 배율.
 * @param rotationDegrees 렌더링된 페이지 위에 얹히는 시계 방향 회전(도).
 * @param message 실제 페이지를 그릴 수 없을 때 표시되는 플레이스홀더 텍스트. 기본값은 "렌더러가 연결되지
 * 않음" 메시지다.
 * @param contentPadding 렌더링된 페이지 주변에 플랫폼 서피스로 그대로 전달되는 패딩.
 * @param placeholderContentPadding 플레이스홀더 콘텐츠 주변에 플랫폼 서피스로 그대로 전달되는 패딩. null이면
 * 테마의 `large` spacing이 사방에 적용된다.
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
    contentPadding: PaddingValues = PaddingValues(PdfPageDefaultContentPadding),
    placeholderContentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedPlaceholderContentPadding = placeholderContentPadding ?: PaddingValues(spacing.large)

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
        placeholderContentPadding = resolvedPlaceholderContentPadding,
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

/** [PdfPageSurface]의 `contentPadding` 기본값으로 쓰이는, 렌더링된 페이지 주변 여백. */
private val PdfPageDefaultContentPadding = 12.dp

/** [PdfPlaceholderSurface]의 페이지 번호·메시지 사이에 두는 세로 간격. */
private val PdfPlaceholderContentSpacing = 8.dp

/**
 * 실제로 PDF 페이지를 렌더링하거나, 렌더링할 수 없을 때는 [message]와 함께 [PdfPlaceholderSurface]로
 * 대체되는 플랫폼 훅이다. Android actual은 `android.graphics.pdf.PdfRenderer`를 통해 페이지를 로드하는데,
 * 이는 사용 후 반드시 닫아야 하며, 보여주기 전에 백그라운드 디스패처에서 비트맵으로 디코드한다. iOS actual은
 * 대신 `UIKitView`를 통해 PDFKit의 `PDFView`를 호스팅하고, PDFKit 스스로 [pageIndex]로 페이지를 넘기게 둔다.
 *
 * @param documentUri 페이지를 그려낼 문서. null이면 플레이스홀더를 그린다.
 * @param pageIndex 그릴 페이지와, 그 페이지가 매겨지는 전체 페이지 수.
 * @param modifier 렌더링된 서피스나 플레이스홀더에 적용된다.
 * @param message 실제 페이지를 그릴 수 없을 때 플레이스홀더에 표시된다.
 * @param contentPadding 실제로 렌더링된 페이지 주변 패딩.
 * @param placeholderContentPadding 플레이스홀더 콘텐츠 주변 패딩.
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
 * 실제 PDF 페이지 대신 표시되는 대체 콘텐츠 — 페이지 번호 표시와 설명용 [message]로 이루어지며,
 * [PlatformPdfPageSurface]가 아직 그릴 것이 없을 때와 아예 그릴 수 없을 때(문서가 없거나 읽을 수 없을 때)
 * 모두에 쓰인다.
 *
 * @param pageIndex 이 플레이스홀더가 대신하는 페이지와, 함께 표시되는 전체 페이지 수.
 * @param modifier 이 플레이스홀더의 루트에 적용된다.
 * @param message 실제 페이지가 표시되지 않는 이유를 사용자에게 설명한다.
 * @param contentPadding 플레이스홀더 콘텐츠 주변 패딩. null이면 테마의 `large` spacing이 사방에 적용된다.
 */
@Composable
internal fun PdfPlaceholderSurface(
    pageIndex: PageIndex,
    modifier: Modifier = Modifier,
    message: String,
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(spacing.large)
    val colors = teddReaderColors()
    val typography = teddReaderTypography()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceContainerLow)
            .padding(resolvedContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PdfPlaceholderContentSpacing),
        ) {
            TeddText(text = "PDF", style = typography.headlineMedium)
            TeddText(
                text = stringResource(Res.string.pdf_page_fraction, pageIndex.current + 1, pageIndex.total),
                style = typography.bodyMedium,
            )
            TeddText(
                text = message,
                style = typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** 표본 페이지 인덱스를 쓰는 [PdfPageSurface]의 Compose 미리보기로, IDE 미리보기 패널에 쓰인다. */
@Preview
@Composable
private fun PdfPageSurfacePreview() {
    TeddReaderTheme {
        PdfPageSurface(pageIndex = PageIndex(current = 0, total = 10))
    }
}

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
import kotlinx.coroutines.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
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

/** Android PDF Bitmap 한 픽셀이 사용하는 ARGB_8888 바이트 수다. */
internal const val PdfArgbBytesPerPixel = 4L

/** viewport가 비정상적으로 커도 PDF Bitmap 한 변이 넘지 않는 상한이다. */
private const val PdfMaxRenderDimension = 4_096

/** 한 PDF 페이지 Bitmap이 사용할 수 있는 최대 ARGB 메모리다. */
private const val PdfMaxRenderBytes = 64L * 1_024L * 1_024L

/**
 * Android PDF 페이지가 viewport 안에서 원본 비율을 유지하며 사용할 Bitmap 크기다.
 *
 * @property width 렌더링할 Bitmap의 가로 픽셀 수.
 * @property height 렌더링할 Bitmap의 세로 픽셀 수.
 */
internal data class PdfRenderSize(
    val width: Int,
    val height: Int,
)

/**
 * PDF 원본 비율과 viewport를 기준으로 렌더 크기를 정하고 최대 변과 ARGB 메모리 한도 안으로 축소한다.
 * 픽셀 수는 `Long`으로 계산해 큰 입력의 `Int` 곱셈 overflow가 할당 제한을 우회하지 못하게 한다.
 *
 * @param pageWidth PDF 페이지가 보고한 원본 가로 크기.
 * @param pageHeight PDF 페이지가 보고한 원본 세로 크기.
 * @param viewportWidth 페이지를 표시할 Compose viewport의 가로 픽셀 수.
 * @param viewportHeight 페이지를 표시할 Compose viewport의 세로 픽셀 수.
 * @param maxDimension Bitmap 한 변에 허용할 최대 픽셀 수.
 * @param maxBytes Bitmap의 ARGB 픽셀에 허용할 최대 바이트 수.
 * @return 유효한 입력이면 제한 안의 렌더 크기, 할당할 수 없는 입력이면 null.
 */
internal fun pdfRenderSize(
    pageWidth: Int,
    pageHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    maxDimension: Int = PdfMaxRenderDimension,
    maxBytes: Long = PdfMaxRenderBytes,
): PdfRenderSize? {
    if (
        pageWidth <= 0 || pageHeight <= 0 ||
        viewportWidth <= 0 || viewportHeight <= 0 ||
        maxDimension <= 0 || maxBytes < PdfArgbBytesPerPixel
    ) {
        return null
    }

    val viewportScale = min(
        viewportWidth.toDouble() / pageWidth.toDouble(),
        viewportHeight.toDouble() / pageHeight.toDouble(),
    )
    var width = (pageWidth * viewportScale).roundToInt().coerceAtLeast(1)
    var height = (pageHeight * viewportScale).roundToInt().coerceAtLeast(1)
    val dimensionScale = min(1.0, maxDimension.toDouble() / maxOf(width, height).toDouble())
    width = (width * dimensionScale).toInt().coerceAtLeast(1)
    height = (height * dimensionScale).toInt().coerceAtLeast(1)

    val maxPixels = maxBytes / PdfArgbBytesPerPixel
    val pixelCount = width.toLong() * height.toLong()
    if (pixelCount > maxPixels) {
        val byteScale = sqrt(maxPixels.toDouble() / pixelCount.toDouble())
        width = (width * byteScale).toInt().coerceAtLeast(1)
        height = (height * byteScale).toInt().coerceAtLeast(1)
        if (width.toLong() * height.toLong() > maxPixels) {
            if (width >= height) {
                width = (maxPixels / height.toLong()).toInt().coerceAtLeast(1)
            } else {
                height = (maxPixels / width.toLong()).toInt().coerceAtLeast(1)
            }
        }
    }
    return PdfRenderSize(width = width, height = height)
}

/**
 * PDF 렌더 예외를 플랫폼 실패 상태로 바꾸되 coroutine 취소는 호출자에게 그대로 전파한다.
 *
 * @param render 실제 PDF 렌더 작업.
 * @param onFailure 일반 렌더 예외를 고정 실패 상태로 바꾸고 상세를 기록하는 처리.
 * @return 렌더 성공값 또는 [onFailure]가 만든 실패값.
 * @throws CancellationException 렌더 작업이 취소된 경우.
 */
internal inline fun <T> pdfRenderOrElse(
    render: () -> T,
    onFailure: (Throwable) -> T,
): T = try {
    render()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    onFailure(throwable)
}

/**
 * 생성된 PDF 렌더 리소스를 UI 결과로 전환하며, 전환 전 실패한 경우에만 즉시 해제한다.
 *
 * @param resource 렌더 단계가 임시로 소유하는 리소스.
 * @param release 렌더가 결과를 반환하지 못했을 때 리소스를 해제하는 동작.
 * @param render 리소스를 UI가 소유할 성공 결과로 전환하는 렌더 작업.
 * @return [render]가 만든 성공 결과.
 */
internal inline fun <T, R> pdfResourceOrRelease(
    resource: T,
    release: (T) -> Unit,
    render: (T) -> R,
): R {
    var transferred = false
    return try {
        render(resource).also { transferred = true }
    } finally {
        if (!transferred) release(resource)
    }
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
 * 모두에 쓰인다. 읽기 화면은 `Surface`나 `Scaffold` 안에 있지 않아 `LocalContentColor`가 Material 기본값으로
 * 남으므로, 각 텍스트는 플레이스홀더 표면의 semantic 색을 직접 지정한다.
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
            TeddText(text = "PDF", style = typography.headlineMedium, color = colors.onSurface)
            TeddText(
                text = stringResource(Res.string.pdf_page_fraction, pageIndex.current + 1, pageIndex.total),
                style = typography.bodyMedium,
                color = colors.onSurface,
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

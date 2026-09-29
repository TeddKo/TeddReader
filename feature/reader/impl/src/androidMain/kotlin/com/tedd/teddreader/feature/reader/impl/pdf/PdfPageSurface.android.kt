package com.tedd.teddreader.feature.reader.impl.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import co.touchlab.kermit.Logger
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.generated.resources.*
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * `PlatformPdfPageSurface`의 Android actual이다. Android 플랫폼의 [PdfRenderer]를 통해 PDF 페이지 한 장을
 * 비트맵으로 렌더링하여 보여주고, 렌더링이 진행 중일 때는 로딩 스피너를, 실패했을 때는 플레이스홀더를 표시한다.
 * [documentUri]나 현재 페이지가 바뀔 때마다 다시 렌더링한다. [PdfRenderer]에는 "페이지만 갱신"이라는 개념이 없어
 * 페이지마다 독립적으로 디코드해야 하기 때문이다.
 *
 * @param documentUri 렌더링할 PDF의 소스 URI. null/공백이면 렌더링을 시도하지 않고 사용 불가 플레이스홀더를 보여준다.
 * @param pageIndex 렌더링할 페이지(`pageIndex.current`)와, 플레이스홀더/로딩 상태에 함께 표시되는 전체 페이지 수.
 * @param modifier 로딩·렌더링 완료·사용 불가의 모든 상태에서 서피스의 바깥 컨테이너에 적용된다.
 * @param message URI 누락이나 렌더 실패 시 표시하며 예외 상세를 포함하지 않는 고정 플레이스홀더 텍스트.
 * @param contentPadding 렌더링된 페이지 이미지 주변 패딩.
 * @param placeholderContentPadding 사용 불가 상태 플레이스홀더 콘텐츠 주변 패딩. 플레이스홀더의 레이아웃이 페이지
 *   이미지와 다르므로 [contentPadding]과 별도로 둔다.
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
    val colors = teddReaderColors()
    val context = LocalContext.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var state by remember(documentUri, pageIndex.current) {
        mutableStateOf<PdfRenderState>(
            if (documentUri.isNullOrBlank()) PdfRenderState.Unavailable else PdfRenderState.Loading,
        )
    }
    val surfaceModifier = modifier.onSizeChanged { measuredSize ->
        if (viewportSize != measuredSize) viewportSize = measuredSize
    }

    LaunchedEffect(documentUri, pageIndex.current, viewportSize) {
        if (documentUri.isNullOrBlank() || viewportSize.width > 0 && viewportSize.height > 0) {
            state = renderPdfPage(
                context = context,
                documentUri = documentUri,
                pageIndex = pageIndex.current,
                viewportSize = viewportSize,
            )
        }
    }

    when (val currentState = state) {
        PdfRenderState.Loading -> Box(
            modifier = surfaceModifier
                .fillMaxSize()
                .background(colors.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            TeddLoadingIndicator()
        }
        is PdfRenderState.Rendered -> {
            val bitmap = currentState.bitmap
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            DisposableEffect(bitmap) {
                onDispose {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
            Box(
                modifier = surfaceModifier
                    .fillMaxSize()
                    .background(colors.surfaceContainerLow)
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(
                        Res.string.pdf_page_content_description,
                        pageIndex.current + 1,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        PdfRenderState.Unavailable -> PdfPlaceholderSurface(
            pageIndex = pageIndex,
            modifier = surfaceModifier,
            message = message,
            contentPadding = placeholderContentPadding,
        )
    }
}

/**
 * PDF 페이지 렌더링이 가질 수 있는 세 가지 상태로, 전적으로 [renderPdfPage]의 결과에 의해 결정된다. nullable
 * 비트맵과 별도의 에러 문자열을 두는 대신 private sealed 계층으로 두어, `PlatformPdfPageSurface`의 `when`이
 * 이를 모두 포괄하며 비트맵과 에러 메시지를 동시에 보여주는 일이 절대 없도록 한다.
 */
private sealed interface PdfRenderState {
    /** [renderPdfPage]가 아직 페이지를 디코드 중일 때의 초기 상태이며, 화면 중앙에 스피너로 표시된다. */
    data object Loading : PdfRenderState

    /**
     * 성공적으로 디코드되어 그릴 준비가 된 페이지로, 화면에서 교체되거나 제거될 때 Bitmap을 해제한다.
     *
     * @property bitmap 렌더링된 페이지를 담고 이 상태가 수명 주기를 소유하는 Bitmap.
     */
    data class Rendered(val bitmap: Bitmap) : PdfRenderState

    /** URI 누락이나 플랫폼 렌더 실패로 페이지를 표시할 수 없어 고정 플레이스홀더를 보여주는 상태다. */
    data object Unavailable : PdfRenderState
}

/** 확대(최대 4배)에서도 흐려지지 않도록 viewport 크기에 곱하는 렌더 배율이며, 최대 변·메모리 상한이 계속 메모리를 제한한다. */
private const val PdfRenderOversample = 2f

/** PDF 렌더 실패 상세를 사용자 메시지와 분리해 기록하는 logger다. */
private val pdfRendererLogger = Logger.withTag("PdfRenderer")

/**
 * PDF 페이지 한 장을 I/O dispatcher에서 viewport에 맞춘 Bitmap으로 디코드한다. 파일 디스크립터,
 * [PdfRenderer], 개별 [PdfRenderer.Page]는 바깥 리소스부터 열고 페이지부터 역순으로 닫는다. Bitmap은 렌더가
 * 완료되어 UI 상태로 전달된 경우에만 composable이 소유하며, 예외나 coroutine 취소로 전달되지 못하면 이 함수가
 * 즉시 해제한다. 이를 위해 withContext 밖에 대기 Bitmap 보관 변수를 두고, 결과가 호출자에게 반환되기 전에
 * 취소가 도착하면 finally에서 해제한다.
 *
 * viewport가 바뀌면 새 Bitmap이 옛 Bitmap을 대체할 때까지 옛 것을 유지하므로 페이지당 최대 두 Bitmap이 동시에
 * 존재한다. 목표 크기는 [viewportSize]에 [PdfRenderOversample]을 곱해 확대 시에도 선명하게 하되 메모리 상한으로
 * [pdfRenderSize]가 계속 제한한다.
 * [Matrix]는 PDF 좌표를 제한된 Bitmap 전체에 맞춰 고밀도·대형 문서가 무제한 할당으로 이어지지 않게 한다.
 * 일반 오류는 상세를 로그에 남긴 뒤 고정 [PdfRenderState.Unavailable]로 바꾸지만 coroutine 취소는 재던진다.
 *
 * @param context content resolver를 통해 [documentUri]를 파일 디스크립터로 해석하는 데 사용한다.
 * @param documentUri PDF의 소스 URI. null이거나 공백이면 렌더링하지 않는다.
 * @param pageIndex 렌더링할 0-기반 페이지로, 문서의 실제 페이지 범위 안으로 제한된다.
 * @param viewportSize 페이지가 표시될 Compose viewport의 실제 픽셀 크기.
 * @return 성공하면 Bitmap 소유권을 가진 [PdfRenderState.Rendered], 실패하면 고정
 *   [PdfRenderState.Unavailable].
 * @throws kotlinx.coroutines.CancellationException 렌더 coroutine이 취소된 경우.
 */
private suspend fun renderPdfPage(
    context: Context,
    documentUri: String?,
    pageIndex: Int,
    viewportSize: IntSize,
): PdfRenderState {
    if (documentUri.isNullOrBlank()) return PdfRenderState.Unavailable

    var pendingBitmap: Bitmap? = null
    try {
        return pdfRenderOrElse(
            render = {
                withContext(Dispatchers.IO) {
                    openPdfDescriptor(context, documentUri)?.use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            val safePageIndex = pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                            renderer.openPage(safePageIndex).use { page ->
                                val renderSize = pdfRenderSize(
                                    pageWidth = page.width,
                                    pageHeight = page.height,
                                    viewportWidth = (viewportSize.width * PdfRenderOversample).roundToInt(),
                                    viewportHeight = (viewportSize.height * PdfRenderOversample).roundToInt(),
                                ) ?: return@use PdfRenderState.Unavailable.also {
                                    pdfRendererLogger.w { "PDF render size unavailable for page ${page.width}x${page.height}" }
                                }
                                pdfResourceOrRelease(
                                    resource = Bitmap.createBitmap(
                                        renderSize.width,
                                        renderSize.height,
                                        Bitmap.Config.ARGB_8888,
                                    ),
                                    release = { bitmap -> bitmap.recycle() },
                                    render = { bitmap ->
                                        bitmap.eraseColor(Color.WHITE)
                                        val matrix = Matrix().apply {
                                            setScale(
                                                renderSize.width.toFloat() / page.width.toFloat(),
                                                renderSize.height.toFloat() / page.height.toFloat(),
                                            )
                                        }
                                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        pendingBitmap = bitmap
                                        PdfRenderState.Rendered(bitmap)
                                    },
                                )
                            }
                        }
                    } ?: PdfRenderState.Unavailable.also {
                        pdfRendererLogger.w { "PDF descriptor could not be opened" }
                    }
                }
            },
            onFailure = { throwable ->
                pdfRendererLogger.w(throwable) { "PDF page render failed" }
                PdfRenderState.Unavailable
            },
        ).also { pendingBitmap = null }
    } finally {
        pendingBitmap?.recycle()
    }
}

/**
 * [documentUri]에 대한 읽기 전용 파일 디스크립터를 연다. 이는 [PdfRenderer]가 요구하는 원시 핸들이다.
 * `file://` URI는 디스크에서 직접 열고, 그 외에는 content resolver를 거친다. `content://` URI(Google Drive나
 * 다른 provider에서 선택된 문서)에는 [ParcelFileDescriptor.open]이 직접 사용할 수 있는 경로가 없기 때문이다.
 *
 * @param context `file://`가 아닌 [documentUri]에 사용할 content resolver를 제공한다.
 * @param documentUri 열려는 소스 URI. 공백이면 안 된다.
 * @return 열린 읽기 전용 디스크립터, content resolver가 열지 못했으면 null.
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

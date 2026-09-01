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
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.generated.resources.*
import java.io.File
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
 * @param message 렌더링 실패가 자체 메시지를 만들어내지 못했을 때 사용 불가 플레이스홀더에 표시할 대체 텍스트.
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
                .background(colors.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            TeddLoadingIndicator()
        }
        is PdfRenderState.Rendered -> Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.surfaceContainerLow)
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
 * PDF 페이지 렌더링이 가질 수 있는 세 가지 상태로, 전적으로 [renderPdfPage]의 결과에 의해 결정된다. nullable
 * 비트맵과 별도의 에러 문자열을 두는 대신 private sealed 계층으로 두어, `PlatformPdfPageSurface`의 `when`이
 * 이를 모두 포괄하며 비트맵과 에러 메시지를 동시에 보여주는 일이 절대 없도록 한다.
 */
private sealed interface PdfRenderState {
    /** [renderPdfPage]가 아직 페이지를 디코드 중일 때의 초기 상태이며, 화면 중앙에 스피너로 표시된다. */
    data object Loading : PdfRenderState

    /**
     * 성공적으로 디코드되어 그릴 준비가 된 페이지.
     *
     * @property image 렌더링된 페이지를 담은 비트맵.
     */
    data class Rendered(val image: ImageBitmap) : PdfRenderState

    /**
     * 페이지를 렌더링할 수 없는 경우다 — URI가 없거나, 플랫폼 렌더러가 열 수 없는 PDF이거나, 렌더링 중 예외가
     * 발생한 경우다.
     *
     * @property message 렌더링이 왜 실패했는지 사람이 읽을 수 있게 설명하며, 페이지 대신 표시된다.
     */
    data class Unavailable(val message: String) : PdfRenderState
}

/**
 * PDF 페이지 한 장을 메인 스레드가 아닌 곳에서 비트맵으로 디코드한다. [PdfRenderer]는 파일 I/O와 네이티브
 * 렌더링 작업을 수행하는데, 메인 스레드에서 하면 composition을 막게 되기 때문이다. 파일 디스크립터, [PdfRenderer],
 * 개별 [PdfRenderer.Page]를 중첩된 `use { }` 블록으로 열고 닫는다 — 셋 모두 렌더러가 호출자에게 명시적으로 해제할
 * 것을 요구하는 플랫폼 closeable 타입이며, 하나라도 열어둔 채로 두면 네이티브 리소스가 새어 나간다. 페이지가
 * 보고하는 포인트 크기의 두 배로 렌더링하여, 1:1 비트맵을 업스케일하는 대신 고밀도 디스플레이에서도 텍스트와
 * 벡터 콘텐츠가 선명하게 유지되도록 한다.
 *
 * URI 누락/공백, Android가 열기를 거부한 디스크립터, 렌더링 중 예외 등 모든 실패 경로는 예외가 composable로
 * 전파되도록 두는 대신 포착하여 무엇이 잘못됐는지를 담은 [PdfRenderState.Unavailable]로 바꾼다.
 *
 * @param context content resolver를 통해 [documentUri]를 파일 디스크립터로 해석하는 데 사용한다.
 * @param documentUri PDF의 소스 URI. URI 누락 실패 케이스에서는 null이거나 공백이다.
 * @param pageIndex 렌더링할 0-기반 페이지. 오래되었거나 동기화가 어긋난 인덱스가 렌더러를 비정상 종료시키지
 *   않도록 문서의 실제 페이지 범위 안으로 clamp된다.
 * @return 성공하면 [PdfRenderState.Rendered], 실패하면 그 사유를 설명하는 [PdfRenderState.Unavailable].
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

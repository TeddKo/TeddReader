package com.tedd.teddreader.feature.reader.impl.pdf

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.tedd.teddreader.core.common.model.PageIndex
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFView

/**
 * `PlatformPdfPageSurface`의 iOS actual이다 — PDFKit 자체의 `PDFView`를 호스팅하는 `UIKitView`로,
 * [documentUri]를 `PDFDocument`로 로드하고 스스로 [pageIndex]로 이동한다. Android actual의
 * [android.graphics.pdf.PdfRenderer] 페이지와 달리 여기서는 따로 그려야 할 로딩 상태가 없다. `PDFView`가
 * 문서 디코드와 페이지 넘김을 스스로 소유하고 있어서, 이 composable은 스피너를 보여줘야 할 "아직 렌더링되지
 * 않음" 중간 상태를 전혀 보지 못하기 때문이다.
 *
 * 네이티브 문서는 해석된 파일 경로를 기준으로 remember된다: 그래서 controls나 페이지 인덱스로 인한
 * recomposition은 PDFKit이 파싱해 둔 문서를 그대로 유지하고, 다른 URI를 열 때만 그 문서를 교체한다.
 * `UIKitView.update`는 그 remember된 identity가 바뀔 때만 view를 다시 연결하며, 그 외에는 페이지 이동만
 * 수행한다.
 *
 * @param documentUri 렌더링할 PDF의 소스 `file://` URI. null/공백이면 `PDFView`를 아예 만들지 않고 사용 불가
 *   플레이스홀더를 보여준다.
 * @param pageIndex `PDFView`가 이동할 페이지(`pageIndex.current`).
 * @param modifier `UIKitView`/플레이스홀더 컨테이너에 적용된다.
 * @param message 사용 불가 플레이스홀더에 표시되는 대체 텍스트.
 * @param contentPadding `PDFView`를 호스팅하는 `UIKitView` 주변에 적용되는 패딩.
 * @param placeholderContentPadding 사용 불가 상태 플레이스홀더 주변 패딩으로, 그대로 `PdfPlaceholderSurface`로
 *   전달된다.
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
    val document = remember(path) { PDFDocument(NSURL.fileURLWithPath(path)) }
    UIKitView(
        modifier = modifier.padding(contentPadding),
        factory = {
            PDFView().apply {
                autoScales = true
                this.document = document
            }
        },
        update = { view ->
            if (view.document !== document) view.document = document
            val targetPage = document.pageAtIndex(pageIndex.current.coerceAtLeast(0).toULong())
            if (targetPage != null && readerPdfShouldNavigate(view.currentPage, targetPage)) {
                view.goToPage(targetPage)
            }
        },
    )
}

internal fun readerPdfShouldNavigate(currentPage: Any?, targetPage: Any?): Boolean =
    targetPage != null && currentPage !== targetPage

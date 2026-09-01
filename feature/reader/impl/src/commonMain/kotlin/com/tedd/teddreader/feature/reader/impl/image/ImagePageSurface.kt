package com.tedd.teddreader.feature.reader.impl.image

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Size
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.visual_page_content_description
import com.tedd.teddreader.core.ui.generated.resources.visual_page_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * CBZ/이미지 형식 페이지 한 장을 그린다: [imageBytes] 또는 [sourceUri]를 Coil로 디코딩해 가용 공간에
 * 맞추고, 디코딩 중에는 스피너를, 실패 후에는 고정된 메시지를 보여준다.
 *
 * CBZ 바이트에는 기본 Coil keyer가 없으므로, [documentUri]와 [page]가 안정적인 키를 제공하여 중복된
 * page-effect composition들이 디코딩된 비트맵 하나를 재사용할 수 있게 한다. URI 기반 이미지 문서는
 * Coil 자체의 URI identity를 그대로 쓴다. 두 경로 모두 실제 레이아웃 제약으로부터 디코딩 크기를
 * 결정하며, [MaxReaderImageDimensionPx]는 지나치게 큰 원본이 리더의 상한을 넘지 않도록 막는다.
 *
 * @param page 접근성 텍스트와 CBZ 비트맵 캐시 identity에 쓰이는, 0부터 시작하는 페이지 인덱스.
 * @param documentUri 이 페이지를 소유한 문서의 안정적인 URI.
 * @param imageBytes 이미 로드된 페이지 바이트. null이면 대신 [sourceUri]로부터 로드한다.
 * @param sourceUri Coil이 페이지를 로드할 수 있는 URI로, [imageBytes]가 null일 때만 쓰인다.
 * @param isFailed 페이지 바이트 로드가 이미 실패했는지 여부.
 * @param modifier 바깥쪽 [Box]에 적용된다.
 */
@Composable
internal fun ImagePageSurface(
    page: Int,
    documentUri: String?,
    imageBytes: ByteArray?,
    sourceUri: String?,
    isFailed: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = teddReaderColors()
    val platformContext = LocalPlatformContext.current
    val imageCacheKey = visualPageMemoryCacheKey(documentUri, page).takeIf { imageBytes != null }
    val request = remember(imageBytes, sourceUri, imageCacheKey, platformContext) {
        (imageBytes ?: sourceUri)?.let { data ->
            ImageRequest.Builder(platformContext)
                .data(data)
                .apply {
                    if (imageCacheKey != null) memoryCacheKey(imageCacheKey)
                }
                .maxBitmapSize(Size(MaxReaderImageDimensionPx, MaxReaderImageDimensionPx))
                .build()
        }
    }
    var isLoading by remember(request) { mutableStateOf(request != null) }
    var decodeFailed by remember(request) { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = stringResource(Res.string.visual_page_content_description, page + 1),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onLoading = {
                    isLoading = true
                    decodeFailed = false
                },
                onSuccess = {
                    isLoading = false
                    decodeFailed = false
                },
                onError = {
                    isLoading = false
                    decodeFailed = true
                },
            )
        }
        when {
            decodeFailed || request == null && isFailed -> TeddText(
                text = stringResource(Res.string.visual_page_unavailable),
                color = colors.onSurfaceVariant,
            )
            isLoading || request == null -> TeddLoadingIndicator()
        }
    }
}

/**
 * CBZ 페이지 바이트를 위한, 문서와 페이지 범위로 한정된 메모리 캐시 키 하나를 만든다.
 *
 * Coil 3.5.0은 [ByteArray] 데이터를 가져올 수는 있지만 기본 keyer가 없다. URI 앞에 길이를 붙여 문서
 * identity를 모호하지 않게 유지하고, 페이지 접미사는 인접한 아카이브 항목들이 디코딩된 비트맵을
 * 공유하지 못하도록 막는다. URI가 없으면 null을 반환하여 캐시에 부분적인 identity가 들어가지 않도록
 * 한다.
 *
 * @param documentUri 이 페이지를 소유한 CBZ 문서의 안정적인 URI.
 * @param page 0부터 시작하는 아카이브 페이지 인덱스.
 * @return 안정적인 Coil 메모리 캐시 키. 문서 identity를 알 수 없으면 null.
 */
internal fun visualPageMemoryCacheKey(documentUri: String?, page: Int): String? {
    val document = documentUri?.takeIf(String::isNotBlank) ?: return null
    return "visual:${document.length}:$document:$page"
}

/**
 * Coil에게 페이지 이미지를 이 크기까지 디코딩하도록 요청하는, 픽셀 단위 긴 변 길이. 원본 CBZ 페이지는
 * 어떤 기기 화면이 보여줄 수 있는 것보다 훨씬 많은 픽셀을 담고 있을 수 있다. 디코딩 목표를 상한으로
 * 제한하면 지나치게 큰 원본 이미지에서 약간의 여유를 포기하는 대신, 화면이 실제로 표시할 수 있는 것보다
 * 몇 배나 많은 픽셀을 메모리에 담지 않는 디코딩을 얻는다.
 */
private const val MaxReaderImageDimensionPx = 2_048

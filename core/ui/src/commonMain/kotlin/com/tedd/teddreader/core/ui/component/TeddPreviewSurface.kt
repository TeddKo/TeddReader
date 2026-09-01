package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderColors

/**
 * `@Preview`를 앱의 테마와 서피스 색상으로 감싸서, 프리뷰가 앱이 실제로 그리는 대로 컴포넌트를
 * 보여주게 한다. 이 스캐폴딩은 채우기 색상과 콘텐츠 색상 기본값만 필요할 뿐 모양, 테두리, elevation은
 * 필요 없으므로 Material의 `Surface` 대신 단순한 [background]를 사용한다. 여기의
 * [CompositionLocalProvider]는 background만으로는 빠뜨렸을 `Surface`의 한 부분을 재현한다: 프리뷰
 * 대상 컴포넌트 다수(tint/색상이 지정되지 않은 아이콘과 텍스트)는 자신의 색상을 [LocalContentColor]
 * 에서 읽으며, 그렇지 않으면 이 프리뷰 바깥의 주변 기본값이 무엇이든 그것으로 대체되어 버린다.
 *
 * 의도적으로 internal이다: 이것은 이 모듈 안 프리뷰를 위한 스캐폴딩이지, 화면이 컴포즈해야 할 대상이
 * 아니다.
 *
 * @param modifier 서피스에 적용된다.
 * @param contentPadding 프리뷰 대상 콘텐츠 주위의 인셋으로, 컴포넌트의 가장자리가 보이게 한다.
 * @param content 프리뷰되는 컴포넌트.
 */
@Composable
internal fun TeddPreviewSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    TeddReaderTheme {
        val colors = teddReaderColors()
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            Box(modifier = modifier.background(colors.surface).padding(contentPadding)) {
                content()
            }
        }
    }
}

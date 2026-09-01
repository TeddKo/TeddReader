package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tedd.teddreader.core.designsystem.teddReaderColors

/**
 * 앱의 화면 프레임: 앱 자체 배경이 이미 적용된 Material `Scaffold`.
 *
 * 어떤 화면도 `containerColor`를 설정해야 한다는 것을 기억할 필요가 없도록 존재한다 — 이를 잊은
 * 화면은 Material의 기본 서피스로 렌더링되는데, 이는 리뷰를 통과할 만큼 앱 배경과 비슷하면서도 다른
 * 화면 옆에 놓이면 눈에 띄게 틀린 색이다.
 *
 * @param modifier scaffold에 적용된다.
 * @param topBar 화면의 상단 바로, 기본값은 비어 있다.
 * @param bottomBar 화면의 하단 바로, 기본값은 비어 있다.
 * @param content 화면 본문. 바들이 남긴 인셋을 전달받으며 반드시 이를 적용해야 한다.
 */
@Composable
fun TeddScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = teddReaderColors()
    Scaffold(
        modifier = modifier,
        containerColor = colors.background,
        contentColor = colors.onBackground,
        topBar = topBar,
        bottomBar = bottomBar,
        contentWindowInsets = contentWindowInsets,
        content = content,
    )
}

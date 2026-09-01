package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.darkReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.readerColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.ui.component.TeddText

/**
 * 리더 자체의 바와 시트가 페이지 위에 놓이는 서피스.
 *
 * 이 색상들은 앱 테마가 아니라 리더의 스타일에서 온다. 이 컨트롤들은 *책의* 종이 위에 떠 있기
 * 때문이다: 세피아 페이지는 세피아 색조의 크롬이 필요하고, 그 위에 앱 테마의 크롬이 있으면 다른
 * 앱의 창처럼 보인다. 서피스 색상과 콘텐츠 색상 둘 다 여기서 설정되므로, 호출자가 리더 자체의 종이
 * 위에서 읽을 수 없는 아이콘을 남길 수 없다.
 *
 * 구분선은 `Divider` 자식이 아니라 `drawBehind`로 그려져, 바가 하나의 레이아웃 노드로 남고 레이아웃을
 * 바꾸지 않고도 선이 어느 가장자리에든 놓일 수 있다.
 *
 * @param style 컨트롤 색상을 공급하는 리더의 스타일.
 * @param modifier 서피스에 적용된다; 부모의 너비를 채운다.
 * @param contentPadding 윈도우 인셋 안쪽, [content] 주위의 인셋.
 * @param windowInsets 이 바가 비워 두어야 하는 시스템 인셋 — 상단 바에는 상태 표시줄, 하단 바에는
 * 내비게이션 바. 프리뷰가 필요 없도록 기본값은 0이다.
 * @param dividerAtTop 하단 바에는 true. 그 머리카락 굵기 선은 위쪽 가장자리에 속한다; 상단 바에는
 * false.
 * @param content 바 자체의 컨트롤로, 바의 box scope 안에 있다.
 */
@Composable
fun ReaderChromeSurface(
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    dividerAtTop: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = style.readerColors()

    CompositionLocalProvider(LocalContentColor provides colors.controlsContent) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.controls)
                .drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    val y = if (dividerAtTop) {
                        strokeWidth / 2f
                    } else {
                        size.height - strokeWidth / 2f
                    }
                    drawLine(
                        color = colors.controlsContent.copy(alpha = 0.12f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokeWidth,
                    )
                }
                .windowInsetsPadding(windowInsets)
                .padding(contentPadding),
        ) {
            content()
        }
    }
}

/** 하단 가장자리를 따라 머리카락 굵기 선이 있는, 낮 종이 위의 상단 바. */
@Preview(widthDp = 360)
@Composable
private fun ReaderChromeSurfacePreview() {
    val spacing = teddReaderSpacing()

    TeddReaderTheme {
        ReaderChromeSurface(
            style = ReaderStyle(),
            contentPadding = PaddingValues(
                horizontal = spacing.small,
                vertical = spacing.xxSmall,
            ),
        ) {
            TeddText(text = "Transient chrome")
        }
    }
}

/** 밤 종이 위의 같은 바로, 머리카락 굵기 선의 알파가 계속 보여야 한다. */
@Preview(widthDp = 360)
@Composable
private fun ReaderChromeSurfaceDarkPreview() {
    val spacing = teddReaderSpacing()

    TeddReaderTheme(darkTheme = true) {
        ReaderChromeSurface(
            style = darkReaderStyle(),
            contentPadding = PaddingValues(
                horizontal = spacing.small,
                vertical = spacing.xxSmall,
            ),
        ) {
            TeddText(text = "Transient chrome")
        }
    }
}

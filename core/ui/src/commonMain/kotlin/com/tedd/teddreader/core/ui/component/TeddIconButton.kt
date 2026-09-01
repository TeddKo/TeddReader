package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.ui.extension.teddClickable
import com.tedd.teddreader.core.ui.icon.TeddIcons

/**
 * Material의 `IconButton` 대신 [teddClickable] 위에 만든, 아이콘만 있는 탭 타깃. `IconButton`의
 * 리플은 이 앱의 리플 정책이 금지하는 `contentColor`에서 색상을 가져오기 때문이다([teddClickable]
 * 참고). 리플/클립 모양으로 [CircleShape]가 전달되는데, 이는 `IconButton` 자체가 기본으로 귀결되는
 * 모양이기 때문이며, 그래서 눌림 피드백이 그 둥근 형태를 유지한다. [sizeIn] 경계는 앱이 어디서나
 * 요구하는 48dp 최소 터치 크기를 고정하는데, 그렇지 않으면 작은 아이콘 자체의 경계가 탭 가능 영역을
 * 접근성 최소값 아래로 줄여 버리기 때문이다. 또한 [content]가 어떤 아이콘을 렌더링하든 그것에
 * 맡기는 대신 [contentDescription]을 버튼 자체의 접근성 라벨로 연결해, 호출자가 아이콘 자체에 라벨을
 * 달아야 한다는 것을 기억할 필요가 없게 한다.
 *
 * @param onClick 버튼이 탭될 때 호출된다. [enabled]가 false인 동안에는 호출되지 않는다.
 * @param modifier 강제된 48dp 최소 크기가 적용된 뒤, 버튼 루트에 적용되는 modifier.
 * @param enabled 버튼이 탭에 반응할지 여부. false이면 클릭 semantics도 제거된다.
 * @param contentDescription 이 버튼에 대해 읽히는 접근성 라벨. null이거나 공백이면
 * `contentDescription` semantics가 붙지 않는다(아이콘이 자체 라벨을 제공하거나, 라벨 없이 남는다).
 * @param content 버튼 안에 표시되는 아이콘(또는 다른 작은 콘텐츠).
 */
@Composable
fun TeddIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val spacing = teddReaderSpacing()
    val semanticsModifier = if (!contentDescription.isNullOrBlank()) {
        Modifier.semantics {
            this.contentDescription = contentDescription
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .sizeIn(minWidth = spacing.touchTarget, minHeight = spacing.touchTarget)
            .teddClickable(onClick = onClick, shape = CircleShape, enabled = enabled, role = Role.Button)
            .then(semanticsModifier),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 북마크 아이콘과 접근성 라벨을 갖춘 [TeddIconButton]을 렌더링하는 Compose 프리뷰. */
@Preview
@Composable
private fun TeddIconButtonPreview() {
    TeddPreviewSurface {
        TeddIconButton(
            onClick = {},
            contentDescription = "Favorite",
        ) {
            TeddIcon(imageVector = TeddIcons.BookmarkFilled, contentDescription = null)
        }
    }
}

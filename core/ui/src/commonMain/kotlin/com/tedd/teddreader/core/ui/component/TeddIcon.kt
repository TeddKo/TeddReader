package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.tedd.teddreader.core.designsystem.teddReaderIconography
import com.tedd.teddreader.core.ui.icon.TeddIcons

/**
 * 아이콘을 그리는 앱의 유일한 방법으로, 모든 화면이 그로부터 만들어지는 두 개의 리프 컴포저블 중
 * 하나로 [TeddText]와 짝을 이룬다.
 *
 * [TeddText]가 Material의 `Text`를 피하는 것과 같은 이유로 Material의 `Icon` 대신 `foundation`의
 * `Image`를 사용한다: `material3`을 feature 모듈 밖에 두는 것은 그 안 어디서도 필요로 하지 않을 때만
 * 강제할 수 있다. 또한 Material의 `Icon`은 하지 않는, 아이콘의 박스를 앱 자체 아이콘 스케일에
 * 고정하는 일도 한다 — Material `Icon`은 에셋이 선언한 크기를 그대로 기본값으로 쓰므로, 24dp로 그려진
 * 아이콘과 에셋 자체 크기로 그려진 아이콘이 나란히 있으면 아무도 의도하지 않은 불일치가 생긴다.
 *
 * [TeddIcons]의 벡터들은 모두 "형태"를 나타내기 위한 대용으로 불투명한 검은색으로 그려져 있다. 화면에
 * 검은색으로 나오는 것은 아니다: 여기의 tint 색상 필터가 불투명한 모든 픽셀의 색을 대체하고 알파만
 * 남기므로, 사용자가 실제로 보는 것은 tint다. 이 필터 없이 렌더링된 아이콘은 어두운 서피스 위에서
 * 검은색으로 나올 것이다.
 *
 * @param imageVector 그릴 벡터로, 보통 [TeddIcons]의 멤버다.
 * @param contentDescription 스크린 리더가 알리는 내용. 시각 사용자가 인접한 텍스트에서 이미 얻는
 * 것 이상의 정보를 더하지 않는, 순전히 장식용 글리프에 대해서만 null을 전달한다. 자신의 액션에 대한
 * 유일한 라벨인 프로덕션 아이콘에는 반드시 값을 지정해야 한다.
 * @param modifier 크기가 적용되기 전, 아이콘의 레이아웃 노드에 적용되는 modifier.
 * @param size 아이콘 박스의 한 변. 아이콘들이 서로, 그리고 옆에 있는 텍스트와 나란히 맞춰지도록
 * 기본값은 앱의 medium 아이콘 크기다.
 * @param tint 벡터의 불투명한 모든 픽셀이 바뀌는 색. 기본값인 [Color.Unspecified]는 주변 콘텐츠
 * 색상을 상속한다는 뜻으로, 아이콘이 함께 놓인 라벨과 맞아떨어지게 한다.
 */
@Composable
fun TeddIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = teddReaderIconography().medium,
    tint: Color = Color.Unspecified,
) {
    val resolvedTint = if (tint == Color.Unspecified) LocalContentColor.current else tint

    Image(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(resolvedTint),
    )
}

/** [TeddIcon]을 기본 크기와 large 아이콘 크기로 렌더링하는 Compose 프리뷰. */
@Preview
@Composable
private fun TeddIconPreview() {
    TeddPreviewSurface {
        TeddIcon(imageVector = TeddIcons.Back, contentDescription = "Back")
        TeddIcon(
            imageVector = TeddIcons.BookmarkFilled,
            contentDescription = "Bookmarked",
            size = teddReaderIconography().large,
        )
    }
}

package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.teddReaderColors

/**
 * 두 행 또는 두 그룹을 구분하는 머리카락 굵기 선.
 *
 * [TeddText], [TeddIcon]과 마찬가지로 `core/ui` 밖에서 `material3` import가 필요 없도록 Material의
 * `HorizontalDivider`를 대체한다. 또한 Material의 기본값 대신 앱의 divider 역할을 기본으로 사용하는데,
 * 이 앱은 Material이 하나로 취급하는 두 가지 조용한 선 색상을 구분하기 때문에 이것이 중요하다: 동등한
 * 대상 사이의 구분선에는 `outlineVariant`를, 컨테이너 둘레의 테두리에는 `outlineSubtle`을 쓴다.
 * 컨테이너 색상으로 그려진 구분선은 컨테이너 가장자리가 겉도는 것처럼 보인다.
 *
 * 구분선보다 여백을 우선한다. 앱의 시각 언어는 간격과 활자 굵기로 위계를 만들므로, 구분선은 목록의
 * 모든 항목 사이를 장식하는 용도가 아니라 인접한 두 행이 그렇지 않으면 모호해질 때만 자리를 차지할
 * 자격이 있다.
 *
 * @param modifier 구분선에 적용되는 modifier. 구분선은 주어진 너비를 채우므로, 인셋이 있는 선을
 * 원하는 호출자는 패딩 파라미터를 기대하는 대신 여기에 그 인셋을 직접 제공한다.
 * @param thickness 선의 높이. 기기 픽셀 하나는 간격 스케일의 한 단계가 아니므로 간격 토큰이 아닌
 * 원시 머리카락 굵기 값으로 남겨 둔다.
 * @param color 선의 색상. 팔레트의 separator 역할을 기본값으로 사용한다.
 */
@Composable
fun TeddDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    color: Color = teddReaderColors().outlineVariant,
) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color),
    )
}

/** 두 라벨 사이에 [TeddDivider]를 렌더링하는 Compose 프리뷰. */
@Preview
@Composable
private fun TeddDividerPreview() {
    TeddPreviewSurface {
        TeddText(text = "Above the line")
        TeddDivider()
        TeddText(text = "Below the line")
    }
}

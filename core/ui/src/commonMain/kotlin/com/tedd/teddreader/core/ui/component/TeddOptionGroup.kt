package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * 관련된 설정 행들(보통 [TeddRadioRow]/[TeddSwitchRow]/[TeddCheckboxRow]/[TeddSliderRow])을 선택적
 * 헤더 아래로 묶는, 제목이 있는 섹션이다. 모든 설정 화면이 그 레이아웃을 직접 다시 만들지 않고도 같은
 * 헤더 활자 설정, 헤더-콘텐츠 간격, 서피스 배경을 갖게 해 준다.
 *
 * @param title [teddReaderTypography]의 `titleMedium` 스타일로 표시되는 섹션의 헤더 텍스트. null이고
 * [description]도 null이면 헤더가 전혀 렌더링되지 않는다.
 * @param modifier 그룹 루트에 적용되는 modifier.
 * @param description [title] 아래 흐린 색상으로 표시되는 두 번째 헤더 줄. non-null이면 [title]이
 * null이어도 렌더링된다.
 * @param isSelectableGroup 콘텐츠 column이 `Modifier.selectableGroup()`을 가질지 여부. 이것이 있으면
 * 스크린 리더가 각 자식 자체의 상태만이 아니라 집합 내에서의 위치("3개 중 2번째" 같은)까지 알릴 수
 * 있다. [content]가 연속된 [TeddRadioRow]들일 때는 반드시 true여야 한다. 라디오 그룹이야말로 그
 * 안내가 설명하는 상호 배타적 집합 그 자체이기 때문이다. [content]가 [TeddSwitchRow]나
 * [TeddCheckboxRow]처럼 서로 독립적인 토글들을 담고 있을 때는 반드시 false로 남아 있어야 한다. 그런
 * 행들은 서로를 배제하지 않으며, 이들을 묶으면 사실이 아닌 집합 관계를 알리게 되기 때문이다. 이 앱의
 * 대부분의 옵션 그룹이 독립적인 토글을 담고 있으므로 기본값은 false이며, 라디오 그룹을 opt-in하는
 * 것을 잊은 호출자는 빌드 실패가 아니라 조용한 접근성 회귀를 만들어낸다 — 결코 거짓 관계를 알리지
 * 않는 기본값이 더 안전한 쪽이다.
 * @param headerPadding 헤더 블록 주위의 패딩([title]이나 [description]이 non-null일 때만 의미가
 * 있다). null이면 테마의 screenPadding/medium/screenPadding(start/top/end) 조합을 사용하여, 헤더의
 * 가로 인셋이 그 안쪽 4dp에 놓이는 대신 [TeddSection]의 화면 패딩과 맞춰진다.
 * @param contentPadding [content] 주위의 패딩. null이면 테마의 small/medium(top/bottom) 조합을
 * 사용한다.
 * @param content [Column]으로 배치되는 섹션의 행들.
 */
@Composable
fun TeddOptionGroup(
    title: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    isSelectableGroup: Boolean = false,
    headerPadding: PaddingValues? = null,
    contentPadding: PaddingValues? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val resolvedHeaderPadding = headerPadding ?: PaddingValues(
        start = spacing.screenPadding,
        top = spacing.medium,
        end = spacing.screenPadding,
    )
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        top = spacing.small,
        bottom = spacing.medium,
    )
    val contentModifier = if (isSelectableGroup) {
        Modifier
            .fillMaxWidth()
            .selectableGroup()
            .padding(resolvedContentPadding)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(resolvedContentPadding)
    }
    val colors = teddReaderColors()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        CompositionLocalProvider(LocalContentColor provides teddReaderColors().onSurface) {
            if (title != null || description != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(resolvedHeaderPadding),
                ) {
                    title?.let {
                        TeddText(
                            text = it,
                            style = typography.titleMedium,
                        )
                    }
                    if (description != null) {
                        TeddText(
                            text = description,
                            modifier = Modifier.padding(top = if (title != null) spacing.xxSmall else 0.dp),
                            style = typography.settingDescription,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            Column(
                modifier = contentModifier,
                content = content,
            )
        }
    }
}

/** 제목, 설명, 두 개의 [TeddRadioRow]를 갖춘 [TeddOptionGroup]을 렌더링하는 Compose 프리뷰. */
@Preview
@Composable
private fun TeddOptionGroupPreview() {
    TeddPreviewSurface {
        TeddOptionGroup(
            title = "Reading direction",
            description = "Choose how pages flow through the reader.",
        ) {
            TeddRadioRow(title = "Horizontal", selected = true, onClick = {})
            TeddRadioRow(title = "Vertical", selected = false, onClick = {})
        }
    }
}

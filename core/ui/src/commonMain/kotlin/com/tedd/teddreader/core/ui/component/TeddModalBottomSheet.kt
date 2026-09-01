package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.extension.consumeUnconsumedVerticalScroll

/**
 * 앱의 모달 바텀 시트 크롬: [content] 위에 제목/설명 헤더를 두고, Material의 [ModalBottomSheet]
 * 위에 앱의 간격과 활자 설정으로 배치한다. 헤더와 콘텐츠를 함께 담는 [Column]에도
 * [consumeUnconsumedVerticalScroll]이 지정되어 있는데, 이것이 없으면 [content] 자체가 소비하지 않는
 * 수직 드래그 델타(예: 이미 위나 아래 끝에 도달한 스크롤 가능 목록, 또는 아예 스크롤되지 않는
 * 콘텐츠)가 이 column을 지나 [ModalBottomSheet] 자체의 nested-scroll 처리로 새어 들어가 시트를
 * 펼치거나 닫는 드래그로 해석되어 버리기 때문이다 — 시트 콘텐츠 끝까지 스크롤하면 시트 자체가 갑자기
 * 닫혀 버리는, 이 앱이 실제로 겪었던 버그다. 여기서 남은 델타를 소비함으로써 시트 드래그와 콘텐츠
 * 스크롤 제스처를 서로 독립적으로 유지한다.
 *
 * 펼침/접힘 상태는 파라미터로 받는 대신 여기서 remember된다. 예전에는 호출자가 이를 만들어 모든
 * 중간 컴포저블을 통해 아래로 넘겼지만, 그중 어느 것도 그 위에서 메서드를 호출한 적이 없었다 — 그
 * 상태는 이 컴포저블로 그냥 통과되는 것뿐이었다. 여기서 소유하면 그 파라미터 체인이 사라지고,
 * Material의 실험적인 sheet-state API가 opt-in 요구사항을 시트를 보여주는 모든 화면으로 새어 나가지
 * 않게 막는다. Kotlin이 타입 별칭을 주석이 달린 원래 타입으로 다시 펼쳐 버리기 때문에 type alias로는
 * 이를 할 수 없었다.
 *
 * 이 앱의 시트는 한 번에 하나만 열리므로, 시트마다 있는 상태는 그것이 대체한 공유 상태와 동일하게
 * 동작한다: 현재 컴포지션에 있는 시트만 상태를 가진다. Material의 드래그 핸들 슬롯은 의도적으로
 * 비활성화하고 같은 시각적 핸들을 일반 콘텐츠로 대신 렌더링한다: 이렇게 하면 핸들은 Material의
 * 추가적인 탭-토글 액션을 노출하지 않으면서도 스와이프 어포던스로 남는다.
 *
 * @param title [teddReaderTypography]의 `titleLarge` 스타일로 표시되는 시트의 헤더 텍스트.
 * @param onDismissRequest 사용자가 시트를 닫을 때(바깥 탭, 아래로 스와이프, 또는 뒤로 가기 제스처)
 * 호출된다. 시트가 닫히는 유일한 방법이므로, 애초에 시트를 컴포즈할지 결정하는 상태를 반드시
 * 초기화해야 한다.
 * @param modifier 내부 [ModalBottomSheet]에 적용되는 modifier.
 * @param description [title] 아래 흐린 색상으로 표시되는 두 번째 헤더 줄. null이면 생략된다.
 * @param contentPadding 헤더 아래, [content] 주위의 패딩. null이면 테마의
 * sheetPadding/sheetPadding/large(start/end/bottom) 조합을 사용한다.
 * @param content 시트의 본문.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeddModalBottomSheet(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    contentPadding: PaddingValues? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        start = spacing.sheetPadding,
        end = spacing.sheetPadding,
        bottom = spacing.large,
    )
    val typography = teddReaderTypography()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        BottomSheetDefaults.DragHandle(
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .consumeUnconsumedVerticalScroll(),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Column(
                modifier = Modifier.padding(
                    start = spacing.sheetPadding,
                    top = spacing.large,
                    end = spacing.sheetPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
            ) {
                TeddText(
                    text = title,
                    style = typography.titleLarge,
                )
                if (description != null) {
                    TeddText(
                        text = description,
                        style = typography.settingDescription,
                        color = teddReaderColors().onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(resolvedContentPadding),
                content = content,
            )
        }
    }
}

/** [TeddButton] 하나를 콘텐츠로 갖고 펼쳐진 [TeddModalBottomSheet]를 렌더링하는 Compose 프리뷰. */
@Preview
@Composable
private fun TeddModalBottomSheetContentPreview() {
    TeddPreviewSurface {
        TeddModalBottomSheet(
            title = "Sort by",
            onDismissRequest = {},
            content = {
                TeddButton(text = "Recent", onClick = {})
            },
        )
    }
}

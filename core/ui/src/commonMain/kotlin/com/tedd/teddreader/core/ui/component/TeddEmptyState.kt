package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * 화면이나 목록에 아무것도 없을 때(빈 서재, 검색 결과 없음)를 위한 앱의 플레이스홀더로, 제목과
 * 선택적 설명, 선택적 액션 버튼을 가운데 정렬한다. 이렇게 해서 이런 모든 화면이 각자 [Column]을 직접
 * 가운데 정렬하는 대신 같은 레이아웃을 공유한다.
 *
 * @param title [teddReaderTypography]의 `documentTitle` 스타일로 표시되는 주요 메시지.
 * @param modifier 상태 루트에 적용되는 modifier.
 * @param description [title] 아래 흐린 색상으로 표시되는 보조 텍스트. null이면 생략된다.
 * @param contentPadding 전체 상태 주위의 패딩. null이면 테마의 large(모든 방향) 값을 사용한다.
 * @param action 설명 아래에 표시되는 콘텐츠로, 보통은 다음 단계를 안내하는 [TeddButton]이다. null이면
 * 생략된다.
 */
@Composable
fun TeddEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    contentPadding: PaddingValues? = null,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(spacing.large)
    val typography = teddReaderTypography()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(resolvedContentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        TeddText(
            text = title,
            style = typography.documentTitle,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            TeddText(
                text = description,
                style = typography.settingDescription,
                color = teddReaderColors().onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        action?.invoke(this)
    }
}

/** 제목, 설명, 액션 버튼을 갖춘 [TeddEmptyState]를 렌더링하는 Compose 프리뷰. */
@Preview
@Composable
private fun TeddEmptyStatePreview() {
    TeddPreviewSurface {
        TeddEmptyState(
            title = "No books yet",
            description = "Add TXT, PDF, or EPUB files to start reading.",
            action = {
                TeddButton(text = "Open file", onClick = {})
            },
        )
    }
}

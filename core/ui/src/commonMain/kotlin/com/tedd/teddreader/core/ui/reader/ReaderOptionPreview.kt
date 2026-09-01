package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddText

/**
 * 읽기 페이지의 실시간 샘플로, 활자나 테마 컨트롤 옆에 표시되어 사용자가 시트를 떠나기 전에 설정의
 * 효과를 볼 수 있게 한다.
 *
 * 목업이 아니라 실제 [ReaderPageSurface]를 그리므로 샘플이 페이지에서 어긋날 수 없다: 종이 색상,
 * 여백, 활자는 실제 페이지가 이 스타일에 대해 사용할 것과 정확히 같다.
 *
 * @param style 프리뷰되는 스타일 — 보통 사용자가 현재 슬라이더를 드래그해서 만드는 초안이다.
 * @param modifier 블록 전체에 적용된다; 부모의 너비를 채운다.
 * @param title 샘플 위에 표시되는 컨트롤의 이름.
 * @param description 제목 아래 표시되는, 컨트롤을 설명하는 선택적 줄.
 * @param previewText 샘플 텍스트. 기본값은 줄 높이가 양쪽 모두에서 보이도록 한글과 라틴 문자를
 * 섞는다.
 * @param contentPadding 샘플 페이지의 여백으로, 샘플이 시트에 맞도록 실제 페이지보다 작다. null이면
 * 테마의 readerMargin/large 조합을 사용한다.
 */
@Composable
fun ReaderOptionPreview(
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    title: String = "Reader preview",
    description: String? = null,
    previewText: String = "Preview text\n가나다 ABC 123",
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.readerMargin,
        vertical = spacing.large,
    )
    val typography = teddReaderTypography()
    Column(modifier = modifier.fillMaxWidth()) {
        TeddText(
            text = title,
            style = typography.settingTitle,
        )
        if (description != null) {
            TeddText(
                text = description,
                modifier = Modifier.padding(top = spacing.xxSmall),
                style = typography.settingDescription,
            )
        }
        ReaderPageSurface(
            text = previewText,
            style = style,
            modifier = Modifier.padding(top = spacing.small),
            contentPadding = resolvedContentPadding,
        )
    }
}

/** 설정 시트가 보여주는 그대로의 블록으로, 제목과 설명을 포함한다. */
@Preview
@Composable
private fun ReaderOptionPreviewPreview() {
    TeddReaderTheme {
        ReaderOptionPreview(
            style = ReaderStyle(),
            description = "A live sample of the current reader typography and palette.",
        )
    }
}

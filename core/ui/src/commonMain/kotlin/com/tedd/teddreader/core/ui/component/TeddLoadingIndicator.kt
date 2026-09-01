package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * 아래에 선택적 캡션(예: "문서 불러오는 중")이 붙는 [LoadingIndicator]로, 무엇을 기다리는지 설명해야
 * 하는 화면이 스피너와 [TeddText]를 짝짓는 [Column]을 직접 조립할 필요가 없게 한다.
 *
 * @param modifier 인디케이터 루트 [Column]에 적용되는 modifier.
 * @param message 스피너 아래 흐린 색상으로 표시되는 캡션. null이면 생략된다.
 */
@Composable
fun TeddLoadingIndicator(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        LoadingIndicator()
        if (message != null) {
            TeddText(
                text = message,
                style = typography.settingDescription,
                color = teddReaderColors().onSurfaceVariant,
            )
        }
    }
}

/**
 * 가용 공간 전체 가운데에 [TeddLoadingIndicator]를 놓는다. 다른 콘텐츠 사이에 스피너를 인라인으로
 * 보여주는 대신, 로드(예: 문서 열기)에 화면 전체가 막혀 있는 경우를 위한 것이다.
 *
 * @param modifier `fillMaxSize()`가 더해지기 전에 적용되는 modifier.
 * @param message 스피너 아래 흐린 색상으로 표시되는 캡션. null이면 생략된다.
 */
@Composable
fun TeddFullScreenLoadingIndicator(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        TeddLoadingIndicator(message = message)
    }
}

/** 메시지와 추가 패딩을 갖춘 [TeddLoadingIndicator]를 렌더링하는 Compose 프리뷰. */
@Preview
@Composable
private fun TeddLoadingIndicatorPreview() {
    TeddPreviewSurface {
        TeddLoadingIndicator(
            modifier = Modifier.padding(24.dp),
            message = "Loading document",
        )
    }
}

package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderStroke
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * 앱의 카드 서피스: 모든 리스트 행과 패널이 놓이는, 클립되고 테두리가 있는 하나의 컨테이너.
 *
 * `Surface`로 감싸는 대신 이미 콘텐츠를 배치하는 `Column`에 modifier를 그려서, 카드는 두 개가 아니라
 * 하나의 레이아웃 노드가 된다. 콘텐츠 색상도 고정해 두므로, 호출자가 카드 자체 배경에 어떤 `on…`
 * 역할이 맞는지 기억할 필요가 없다.
 *
 * @param modifier 카드 자체에 적용된다. 호출자가 바깥에서 카드의 크기와 위치를 정한다.
 * @param content 카드의 자식들로, column으로 배치되고 카드의 콘텐츠 색상으로 그려진다.
 */
@Composable
fun TeddCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = teddReaderShapes().medium
    val colors = teddReaderColors()
    val strokeWidth = teddReaderStroke().hairline

    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceContainerLow)
            .border(BorderStroke(strokeWidth, colors.outlineVariant), shape),
    ) {
        CompositionLocalProvider(LocalContentColor provides teddReaderColors().onSurface) {
            content()
        }
    }
}

/**
 * 실패를 인라인으로 보여 주는 단 하나의 방법으로, 모든 화면이 오류를 같은 방식으로 알린다.
 *
 * 단순한 빨간색이 아니라 자체 색상 역할을 사용한다: 컨테이너는 머리카락 굵기 테두리와 함께 오류
 * 색조를 띠고, 콘텐츠 색상은 메시지와 액션 양쪽에 한 번만 지정되므로 호출자가 색이 입혀진 배경 위에
 * 읽을 수 없는 버튼을 남길 수 없다.
 *
 * @param message 리더의 언어로 표현된, 무엇이 잘못되었는지에 대한 설명.
 * @param modifier 배너에 적용된다. 기본적으로 부모의 너비를 채운다.
 * @param contentPadding 메시지와 액션 주위의 인셋. null이면 테마의 medium(모든 방향) 값을 사용한다.
 * @param action 배너 자체의 column scope 안 메시지 아래에 컴포즈되는, 선택적인 재시도 또는 닫기
 * 컨트롤.
 */
@Composable
fun TeddErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(spacing.medium)
    val typography = teddReaderTypography()
    val shape = teddReaderShapes().small
    val colors = teddReaderColors()
    val strokeWidth = teddReaderStroke().hairline

    CompositionLocalProvider(LocalContentColor provides teddReaderColors().onErrorContainer) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.errorContainer)
                .border(BorderStroke(strokeWidth, colors.error.copy(alpha = 0.15f)), shape)
                .padding(resolvedContentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            TeddText(text = message, style = typography.settingTitle)
            action?.invoke(this)
        }
    }
}

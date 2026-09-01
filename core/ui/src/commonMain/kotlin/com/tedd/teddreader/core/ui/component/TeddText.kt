package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * 화면에 텍스트를 놓는 앱의 유일한 방법이며, 화면이 Material 자체의 `Text`에 결코 손대지 않는
 * 이유다.
 *
 * Material 컴포저블을 감싸는 대신 `foundation`의 `BasicText` 위에 만들어져 있어, 어떤 화면도 라벨을
 * 렌더링하기 위해 `material3` import가 필요 없다. 이것이 바로 "`core/ui` 밖에서는 `material3` 금지"
 * 규칙을 리뷰어가 잘못 들어간 import를 알아채는 방식이 아니라 `checkMaterial3Imports` 검증 태스크가
 * 강제할 수 있게 하는 이유다: Material의 `Text`가 손닿는 곳에 있다면 모든 화면이 그 게이트가 금지하는
 * import를 필요로 했을 것이고, 그 게이트는 존재할 수 없었을 것이다. 여기서 [teddReaderTypography]를
 * 통해 활자 스케일에 접근하는 것 역시, 기본값이 항상 상속된 우연이 아니라 진짜 앱 스타일이라는 것을
 * 뜻한다.
 *
 * 기본 스타일은 상속된 주변 스타일이 아니라 본문 스타일이다. Material의 `Text`는 `LocalTextStyle`을
 * 병합하는데, 이는 부모가 여러 계층 아래의 텍스트 스타일을 조용히 바꿔 버릴 수 있게 한다. 스타일을
 * 이름 붙은 기본값을 가진 명시적 파라미터로 만든다는 것은, 라벨이 어떻게 보이는지가 그 라벨이 쓰인
 * 곳에서 결정된다는 뜻이다.
 *
 * 색상은 여전히 상속되는 유일한 것으로, Material의 `LocalContentColor`를 통해서다. 이는 의도적이다:
 * 이 앱이 Material에서 그대로 유지하는 시트, 메뉴, 슬라이더, 텍스트 필드는 모두 그 local을 통해
 * 자신의 콘텐츠 색상을 발행하므로, 그중 하나 안의 라벨은 그것을 읽어야 하며 그렇지 않으면 자신이 속한
 * 컨테이너와 어긋나게 된다.
 *
 * @param text 렌더링할 문자열.
 * @param modifier 텍스트의 레이아웃 노드에 적용되는 modifier.
 * @param style 그릴 활자 스타일. 기본값은 앱의 본문 스타일이다. 스케일이 단일 진실 공급원으로
 * 남도록 직접 만드는 대신 [teddReaderTypography]의 스타일을 전달한다.
 * @param color 텍스트 색상. 기본값인 [Color.Unspecified]는 주변 콘텐츠 색상을 상속한다는 뜻으로,
 * 테마가 적용된 컨테이너 안 라벨이 원하는 동작이다.
 * @param maxLines 텍스트가 멈추는 줄 수. [overflow]와 결합되어 긴 제목이 잘리는지 아니면 행을 더 높게
 * 미는지를 결정한다.
 * @param overflow [maxLines]를 초과하는 텍스트가 어떻게 처리되는지. 호출자가 의도적으로 말줄임표를
 * opt-in하도록 기본값은 클리핑이다.
 * @param textAlign 텍스트 자신의 너비 안에서의 가로 정렬. null이면 기본값으로 덮어쓰지 않고 [style]이
 * 이미 지정한 값을 그대로 유지한다.
 */
@Composable
fun TeddText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = teddReaderTypography().bodyLarge,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    val resolvedColor = if (color == Color.Unspecified) LocalContentColor.current else color
    val coloredStyle = style.copy(color = resolvedColor)
    val resolvedStyle = if (textAlign == null) coloredStyle else coloredStyle.copy(textAlign = textAlign)

    BasicText(
        text = text,
        modifier = modifier,
        style = resolvedStyle,
        overflow = overflow,
        maxLines = maxLines,
    )
}

/** [TeddText]를 기본 본문 스타일과 제목 스타일로 렌더링하는 Compose 프리뷰. */
@Preview
@Composable
private fun TeddTextPreview() {
    TeddPreviewSurface {
        TeddText(text = "Recent documents", style = teddReaderTypography().titleMedium)
        TeddText(text = "Twelve files, last opened yesterday.")
    }
}

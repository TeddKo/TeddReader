package com.tedd.teddreader.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.tedd.teddreader.core.common.model.ReaderStyle

/**
 * 리더의 본문 텍스트 스타일입니다.
 *
 * [LineHeightStyle.Mode.Minimum]을 사용해야 텍스트 안에 그림을 배치할 수 있습니다. 리더는 독립된 이미지를
 * 페이지 텍스트 안의 인라인 콘텐츠로 그립니다. 기본 모드인 `Fixed`는 모든 줄 상자의 높이를 정확히
 * [lineHeight]로 고정합니다. Compose 문서에 따르면 "중간 줄은 항상 지정된 줄 높이를 따르며, 큰 글리프는
 * 위나 아래 줄로 넘칠 수 있습니다." 이는 삽화가 위아래 산문을 가로질러 그려지는 현상입니다. 반면
 * `Minimum`은 줄 높이를 하한으로 취급하여 이미지가 있는 줄을 이미지 높이만큼 늘리고 텍스트를 밀어냅니다.
 * 같은 측정값을 통해 페이지네이션도 이미지가 페이지에서 실제로 차지하는 크기를 알 수 있습니다.
 *
 * `Trim.Both`는 이 계약의 나머지 절반입니다. 트리밍하지 않으면 첫 줄 위와 마지막 줄 아래의 절반 행간은
 * 줄을 렌더링하는 쪽에 속합니다. 페이지네이션은 문서 전체를 한 번에 배치하므로 페이지 첫 줄이 그
 * 레이아웃에서는 중간 줄이어서 행간이 없습니다. 이후 페이지를 따로 그리면 같은 줄이 첫 줄이 되어 한 줄
 * 높이의 행간이 나타났고, 이 때문에 페이지 마지막 줄이 아래로 밀려 잘렸습니다. 글자가 클수록 더 많이
 * 손실되었습니다. 트리밍하면 첫 줄이 어느 방식에서도 동일하게 측정됩니다.
 *
 * [FontWeight]는 [ReaderStyle.fontWeight]의 값인 300, 400, 500, 600 중 하나로 직접 만들어 일반 본문의
 * 기본 굵기로 사용합니다. 출판사가 지정한 강조 표현(굵은 텍스트 구간, 제목)은 렌더러가 적용하는 곳마다
 * 자체 굵은 범위 스타일로 이 값을 재정의합니다. 따라서 이 값은 페이지의 일반 텍스트가 시작하는 굵기의
 * 하한만 정하며, 강조 표현이 얼마나 굵게 보일 수 있는지의 상한은 정하지 않습니다.
 *
 * @receiver 독자가 선택한 크기, 줄 높이, 글꼴군, 굵기, 글자색을 담은 스타일입니다.
 * @return 페이지에 측정한 줄이 정확히 들어가도록 페이지 렌더러와 페이지 분할기가 함께 사용하는 텍스트
 * 스타일입니다.
 */
fun ReaderStyle.readerTextStyle(): TextStyle = TextStyle(
    color = textColor.toColor(),
    fontSize = fontSizeSp.sp,
    lineHeight = (fontSizeSp * lineHeightMultiplier).sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Proportional,
        trim = LineHeightStyle.Trim.Both,
        mode = LineHeightStyle.Mode.Minimum,
    ),
    fontFamily = fontFamilyName.toFontFamily(),
    fontWeight = FontWeight(fontWeight),
)

/**
 * 저장된 글꼴군 이름을 현재 플랫폼에 실제로 있는 글꼴군으로 대응시킵니다.
 *
 * 책과 독자는 글꼴군 이름을 느슨하게 지정하며(`mono`, `monospace`), 알 수 없는 이름도 아무것도 렌더링하지
 * 않는 대신 무언가로 표시해야 합니다. 따라서 "선택하지 않음"을 뜻하는 null을 포함해 인식하지 못한 값은
 * 모두 플랫폼 sans-serif로 대체합니다.
 *
 * @receiver 저장된 글꼴군 이름이며, 독자가 선택하지 않았으면 null입니다.
 * @return 일치하는 일반 글꼴군이며, 일치하지 않으면 sans-serif입니다.
 */
private fun String?.toFontFamily(): FontFamily = when (this?.lowercase()) {
    "serif" -> FontFamily.Serif
    "mono", "monospace" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}

package com.tedd.teddreader.core.ui.reader

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.designsystem.readerTextStyle

/**
 * 텍스트 레이아웃이 의존하는 모든 파라미터로, 한 번 계산되어 페이지 breaker, float fitter, 페이지
 * 서피스가 그대로 공유한다.
 *
 * 페이지 나눔은 (텍스트, 스타일, 폰트셋, pane px)의 순수 함수다. breaker와 서피스는 예전에는 각자
 * 같은 스타일과 pane으로부터 이 숫자들을 도출했다 — 같은 산술이 두 번 작성된 것이다 — 둘 사이에
 * 어긋남이 생기면 한 값 집합으로 측정되고 다른 값 집합으로 그려진 페이지가 되었고, 이것이 페이지의
 * 마지막 줄이 잘리는 원인이었다. 이 튜플을 한 곳에서 만들면 그 어긋남이 아예 표현될 수 없다.
 *
 * @property textStyle 레이아웃이 측정하고 그리는 기본 텍스트 스타일.
 * @property widthPx 픽셀 단위의 그려지는 텍스트 영역 너비 — pane이 아니라 그 여백을 뺀 pane.
 * @property heightPx 같은 기준의, 픽셀 단위 그려지는 텍스트 영역 높이.
 * @property fontPx 기기 픽셀 단위의 리더 글자 크기로, 줄 기하를 em으로 변환한다.
 * @property lineWidthEm em 단위의 텍스트 컬럼 너비로, `max-width: 100%`처럼 이미지를 제한한다.
 * @property maxHeightEm em 단위의 페이지 높이로, `max-height`처럼 이미지를 제한한다.
 * @property emInPx em당 CSS 픽셀(접근성 배율만 반영, density는 제외) — 이미지의 고유 크기는
 * density와 무관한 CSS 픽셀 단위다.
 * @property embeddedFontFamiliesByHref EPUB href별로 해석된 내장 글꼴 패밀리. 사용자가 선택한 리더
 * 글꼴이 출판사 글꼴을 억제하면 비어 있다.
 * @property publisherFontsEnabled 출판사가 요청한 글꼴 패밀리가 적용될지 여부.
 * @property lineHeightMultiplier 리더의 줄 높이 슬라이더 값으로, 소비자들이 슬라이더 기본값에
 * 고정해 두어 책 자체의 줄 높이가 살아남게 한다.
 * @property fontWeight 리더가 선택한 기본 본문 굵기([ReaderStyle.fontWeight] 참고). 페이지의 강조 —
 * 제목, 굵은 텍스트, 표 헤더 셀 — 가 breaker가 측정한 것과 서피스가 그리는 것이 같은 기준에서
 * 스케일업되도록 [buildReaderSemanticText]가 측정 쪽과 그리기 쪽 양쪽에서 필요로 한다; 대신 이를
 * [textStyle]에서 다시 읽어내는 것은 선택지가 아니다. [TextStyle.fontWeight]는 nullable이며 이
 * 필드가 갖는 보장을 갖지 않기 때문이다.
 */
data class ReaderLayoutInputs(
    val textStyle: TextStyle,
    val widthPx: Int,
    val heightPx: Int,
    val fontPx: Float,
    val lineWidthEm: Float,
    val maxHeightEm: Float,
    val emInPx: Float,
    val embeddedFontFamiliesByHref: Map<String, FontFamily>,
    val publisherFontsEnabled: Boolean,
    val lineHeightMultiplier: Float,
    val fontWeight: Int,
)

/**
 * [widthPx] × [heightPx] 크기의 pane에 대해 측정과 그리기가 반드시 공유해야 하는 단일
 * [ReaderLayoutInputs]를 도출한다.
 *
 * 내부의 두 em 변환은 의도적으로 다르다: 텍스트 기하는 [density]를 거쳐, 페이지가 그려지는 그
 * 픽셀로 측정되는 반면, [ReaderLayoutInputs.emInPx]는 이미지의 고유 크기가 CSS 픽셀 단위이기 때문에
 * 접근성 글꼴 배율만으로 스케일된 글자 크기를 사용한다.
 *
 * @param style 읽기 스타일. 글자 크기, 패밀리 선택, 줄 높이 슬라이더, 글꼴 굵기가 모두 입력값에
 * 반영된다.
 * @param widthPx 픽셀 단위의 그려지는 텍스트 영역 너비.
 * @param heightPx 픽셀 단위의 그려지는 텍스트 영역 높이.
 * @param density 컴포지션의 density로, 측정이 그리기 픽셀을 사용하게 한다.
 * @param embeddedFontFamiliesByHref 해석된 내장 글꼴. 리더가 자체 글꼴을 선택한 경우 여기서
 * 억제되어, 어떤 소비자도 그 규칙을 다시 적용할 필요가 없다.
 */
fun readerLayoutInputs(
    style: ReaderStyle,
    widthPx: Int,
    heightPx: Int,
    density: Density,
    embeddedFontFamiliesByHref: Map<String, FontFamily> = emptyMap(),
): ReaderLayoutInputs {
    val fontPx = with(density) { style.fontSizeSp.sp.toPx() }
    return ReaderLayoutInputs(
        textStyle = style.readerTextStyle(),
        widthPx = widthPx,
        heightPx = heightPx,
        fontPx = fontPx,
        lineWidthEm = if (fontPx > 0f) widthPx / fontPx else 0f,
        maxHeightEm = if (fontPx > 0f) heightPx / fontPx else 0f,
        emInPx = style.fontSizeSp * density.fontScale,
        embeddedFontFamiliesByHref = if (style.fontFamilyName == null) embeddedFontFamiliesByHref else emptyMap(),
        publisherFontsEnabled = style.fontFamilyName == null,
        lineHeightMultiplier = style.lineHeightMultiplier,
        fontWeight = style.fontWeight,
    )
}

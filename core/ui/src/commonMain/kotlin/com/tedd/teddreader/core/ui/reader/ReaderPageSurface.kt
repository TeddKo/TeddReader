package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.darkReaderStyle
import com.tedd.teddreader.core.common.model.sepiaReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.readerColors
import com.tedd.teddreader.core.designsystem.readerTextStyle
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.ui.component.TeddText

/**
 * 사용자가 선택할 수 있는 세 가지 페이지 여백으로, 화면과 프리뷰가 "comfortable"의 의미를 두고
 * 어긋나지 않도록 숫자 대신 이름으로 전달된다.
 *
 * 여백은 단순한 장식이 아니다: 페이지 분할이 측정하는 텍스트 컬럼을 제한하므로, 프리셋을 바꾸면
 * 페이지가 나뉘는 위치도 바뀐다.
 */
enum class ReaderContentPaddingPreset {
    Compact,
    Comfortable,
    Wide,
}

/**
 * 읽기 페이지: 리더 자체의 종이 색상, 여백, 그리고 리더 자체 활자로 설정된 일반 텍스트.
 *
 * 블록 구조가 필요 없는 텍스트 — 프리뷰, 일반 텍스트 파일 — 에 쓰인다. 문자열을 하나의 `Text`로
 * 설정하기 때문이다. 책 자체의 스타일링을 담은 페이지는 대신 리더 기능 자체의 EPUB 서피스가 만든다;
 * 이것은 종이, 여백, 활자가 둘 모두를 위해 한 곳에서 정의된 채로 남도록 존재한다.
 *
 * @param text 호출자가 이미 페이지로 나눈, 페이지의 텍스트.
 * @param style 페이지 색상과 활자를 모두 공급하는 리더의 스타일.
 * @param modifier 페이지에 적용된다; 페이지는 주어진 만큼 채운다.
 * @param contentPadding 페이지 여백으로, 기본값은 리더 자체 여백과 넉넉한 세로 인셋이다. 전달되는
 * 값은 반드시 페이지 breaker가 측정에 사용한 것과 일치해야 하며, 그렇지 않으면 그려진 페이지가
 * 측정된 페이지와 다른 줄 수를 갖게 된다. null은 테마의 readerMargin/xLarge 조합으로 해석된다.
 */
@Composable
fun ReaderPageSurface(
    text: String,
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
) {
    ReaderPageSurface(
        style = style,
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        TeddText(
            text = text,
            style = style.readerTextStyle(),
        )
    }
}

/**
 * 여백을 이름으로 선택하는, 같은 페이지.
 *
 * @param text 페이지의 텍스트.
 * @param style 리더의 스타일.
 * @param modifier 페이지에 적용된다.
 * @param contentPaddingPreset 세 가지 이름 있는 여백 중 어느 것을 사용할지.
 */
@Composable
fun ReaderPageSurface(
    text: String,
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    contentPaddingPreset: ReaderContentPaddingPreset,
) {
    ReaderPageSurface(
        text = text,
        style = style,
        modifier = modifier,
        contentPadding = contentPaddingPreset.toPaddingValues(),
    )
}

/**
 * 콘텐츠는 호출자에게 맡기고 종이 색상과 여백만 제공하는, 페이지 그 자체.
 *
 * 이것은 리더 자체 서피스들이 기반으로 삼는 오버로드로, 스타일이 적용된 EPUB 텍스트 페이지, 만화
 * 페이지, 일반 텍스트 페이지가 모두 같은 종이 위에 같은 여백으로 놓이게 한다.
 *
 * @param style 종이 색상을 공급하는 리더의 스타일.
 * @param modifier 페이지에 적용된다.
 * @param contentPadding 페이지 여백. null은 테마의 readerMargin/xLarge 조합으로 해석된다.
 * @param content 페이지에 그릴 내용으로, 스스로 정렬할 수 있도록 페이지 자체의 box scope 안에 있다.
 */
@Composable
fun ReaderPageSurface(
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.readerMargin,
        vertical = spacing.xLarge,
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(style.readerColors().background)
            .padding(resolvedContentPadding),
    ) {
        content()
    }
}

/**
 * @receiver 이름 있는 여백.
 * @param spacing 이를 해석할 간격 스케일. null이면 앱 자체 테마 스케일로 해석되어 프리뷰가 명시적인
 * 재정의를 필요로 하지 않게 한다.
 * @return 리터럴이 아니라 디자인 시스템에서 가져온 구체적인 인셋.
 */
@Composable
private fun ReaderContentPaddingPreset.toPaddingValues(
    spacing: TeddReaderSpacing? = null,
): PaddingValues {
    val resolvedSpacing = spacing ?: teddReaderSpacing()
    return when (this) {
        ReaderContentPaddingPreset.Compact -> PaddingValues(
            horizontal = resolvedSpacing.medium,
            vertical = resolvedSpacing.large,
        )

        ReaderContentPaddingPreset.Comfortable -> PaddingValues(
            horizontal = resolvedSpacing.readerMargin,
            vertical = resolvedSpacing.xLarge,
        )

        ReaderContentPaddingPreset.Wide -> PaddingValues(
            horizontal = resolvedSpacing.sheetPadding,
            vertical = resolvedSpacing.xxLarge,
        )
    }
}

/** 한글과 라틴 문자가 섞인 텍스트로, 프리뷰가 두 문자 체계의 줄 높이와 간격을 한 번에 보여준다. */
private val PreviewPageText = """
가나다 ABC 123
문장 간격과 줄 높이 확인용 텍스트입니다.
Reader preview keeps Korean and Latin mixed.
""".trimIndent()

/** 기본 활자로 표시된 낮 종이. */
@Preview(widthDp = 360, heightDp = 240)
@Composable
private fun ReaderPageSurfaceLightPreview() {
    TeddReaderTheme {
        ReaderPageSurface(
            text = PreviewPageText,
            style = ReaderStyle(),
            contentPaddingPreset = ReaderContentPaddingPreset.Comfortable,
        )
    }
}

/** 가장 좁은 여백의 세피아 종이. */
@Preview(widthDp = 360, heightDp = 240)
@Composable
private fun ReaderPageSurfaceSepiaPreview() {
    TeddReaderTheme {
        ReaderPageSurface(
            text = PreviewPageText,
            style = sepiaReaderStyle(),
            contentPaddingPreset = ReaderContentPaddingPreset.Compact,
        )
    }
}

/** 가장 넓은 여백의 밤 종이. */
@Preview(widthDp = 360, heightDp = 240)
@Composable
private fun ReaderPageSurfaceDarkPreview() {
    TeddReaderTheme(darkTheme = true) {
        ReaderPageSurface(
            text = PreviewPageText,
            style = darkReaderStyle(),
            contentPaddingPreset = ReaderContentPaddingPreset.Wide,
        )
    }
}

/** 이 프리뷰가 다루는 가장 큰 활자 크기로, 줄 높이가 잘못되면 가장 먼저 드러난다. */
@Preview(widthDp = 360, heightDp = 240)
@Composable
private fun ReaderPageSurfaceLargeFontPreview() {
    TeddReaderTheme {
        ReaderPageSurface(
            text = PreviewPageText,
            style = ReaderStyle(fontSizeSp = 24f),
            contentPaddingPreset = ReaderContentPaddingPreset.Wide,
        )
    }
}

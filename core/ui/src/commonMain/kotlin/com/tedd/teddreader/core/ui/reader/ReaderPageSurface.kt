package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.darkReaderStyle
import com.tedd.teddreader.core.common.model.sepiaReaderStyle
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.readerColors
import com.tedd.teddreader.core.designsystem.readerTextStyle

enum class ReaderContentPaddingPreset {
    Compact,
    Comfortable,
    Wide,
}

@Composable
fun ReaderPageSurface(
    text: String,
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.readerMargin,
        vertical = DefaultTeddReaderSpacing.xLarge,
    ),
) {
    ReaderPageSurface(
        style = style,
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = style.readerTextStyle(),
        )
    }
}

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

@Composable
fun ReaderPageSurface(
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.readerMargin,
        vertical = DefaultTeddReaderSpacing.xLarge,
    ),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(style.readerColors().background)
            .padding(contentPadding),
    ) {
        content()
    }
}

private fun ReaderContentPaddingPreset.toPaddingValues(
    spacing: TeddReaderSpacing = DefaultTeddReaderSpacing,
): PaddingValues = when (this) {
    ReaderContentPaddingPreset.Compact -> PaddingValues(
        horizontal = spacing.medium,
        vertical = spacing.large,
    )

    ReaderContentPaddingPreset.Comfortable -> PaddingValues(
        horizontal = spacing.readerMargin,
        vertical = spacing.xLarge,
    )

    ReaderContentPaddingPreset.Wide -> PaddingValues(
        horizontal = spacing.sheetPadding,
        vertical = spacing.xxLarge,
    )
}

private val PreviewPageText = """
가나다 ABC 123
문장 간격과 줄 높이 확인용 텍스트입니다.
Reader preview keeps Korean and Latin mixed.
""".trimIndent()

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

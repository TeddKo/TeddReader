package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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

/**
 * The three page margins a reader can choose between, named rather than passed as numbers so a screen and a
 * preview cannot drift apart on what "comfortable" means.
 *
 * The margin is not only decoration: it bounds the text column pagination measures, so changing a preset
 * changes where pages break.
 */
enum class ReaderContentPaddingPreset {
    Compact,
    Comfortable,
    Wide,
}

/**
 * A reading page: the reader's own paper colour, its margins, and plain text set in the reader's own type.
 *
 * Used for text that needs no block structure — a preview, a plain text file — since it sets the string in
 * one `Text`. A page that carries a book's own styling is built by the reader feature's own EPUB surface
 * instead; this one exists so the paper, margins and type stay defined in a single place for both.
 *
 * @param text the page's text, already paginated by the caller.
 * @param style the reader's style, which supplies both the page colours and the type.
 * @param modifier applied to the page; the page fills whatever it is given.
 * @param contentPadding the page margins, defaulting to the reader's own margin and a generous vertical
 * inset. Whatever is passed must match what the page breaker measured with, or the drawn page holds a
 * different number of lines than the measured one.
 */
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

/**
 * The same page, with its margins chosen by name.
 *
 * @param text the page's text.
 * @param style the reader's style.
 * @param modifier applied to the page.
 * @param contentPaddingPreset which of the three named margins to use.
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
 * The page itself, with its content left to the caller — paper colour and margins only.
 *
 * This is the overload the reader's own surfaces build on, so a page of styled EPUB text, a comic page and a
 * plain text page all sit on the same paper with the same margins.
 *
 * @param style the reader's style, which supplies the paper colour.
 * @param modifier applied to the page.
 * @param contentPadding the page margins.
 * @param content what to draw on the page, in the page's own box scope so it can align itself.
 */
@Composable
fun ReaderPageSurface(
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.readerMargin,
        vertical = DefaultTeddReaderSpacing.xLarge,
    ),
    content: @Composable BoxScope.() -> Unit,
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

/**
 * @receiver the named margin.
 * @param spacing the spacing scale to resolve it against; defaults to the app's own so a preview needs no
 * theme.
 * @return the concrete insets, drawn from the design system rather than from literals.
 */
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

/** Mixed Korean and Latin text, so a preview shows line height and spacing for both scripts at once. */
private val PreviewPageText = """
가나다 ABC 123
문장 간격과 줄 높이 확인용 텍스트입니다.
Reader preview keeps Korean and Latin mixed.
""".trimIndent()

/** Day paper at the default type. */
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

/** Sepia paper with the tightest margins. */
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

/** Night paper with the widest margins. */
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

/** The largest type this preview covers, where a wrong line height shows first. */
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

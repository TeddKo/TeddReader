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
 * A live sample of the reading page, shown beside a type or theme control so a reader sees the effect of a
 * setting before leaving the sheet.
 *
 * It draws the real [ReaderPageSurface] rather than a mock, so the sample cannot drift from the page: the
 * paper colour, margins and type are whatever the actual page would use for this style.
 *
 * @param style the style being previewed — normally the draft the reader is currently dragging a slider to.
 * @param modifier applied to the whole block; it fills its parent's width.
 * @param title the control's name, shown above the sample.
 * @param description an optional line explaining the control, shown under the title.
 * @param previewText the sample text; mixes Korean and Latin by default so line height is visible for both.
 * @param contentPadding margins for the sample page, smaller than a real page's so the sample fits a sheet;
 * null means the theme's readerMargin/large combination is used.
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

/** The block as a settings sheet shows it, title and description included. */
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

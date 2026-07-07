package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

@Composable
fun ReaderOptionPreview(
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    title: String = "Reader preview",
    description: String? = null,
    previewText: String = "Preview text\n가나다 ABC 123",
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.readerMargin,
        vertical = DefaultTeddReaderSpacing.large,
    ),
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = typography.settingTitle,
        )
        if (description != null) {
            Text(
                text = description,
                modifier = Modifier.padding(top = spacing.xxSmall),
                style = typography.settingDescription,
            )
        }
        ReaderPageSurface(
            text = previewText,
            style = style,
            modifier = Modifier.padding(top = spacing.small),
            contentPadding = contentPadding,
        )
    }
}

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

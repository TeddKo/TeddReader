package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.darkReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.readerColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing

@Composable
fun ReaderChromeSurface(
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    val colors = style.readerColors()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.controls,
        contentColor = colors.controlsContent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
        ) {
            content()
        }
    }
}

@Preview(widthDp = 360)
@Composable
private fun ReaderChromeSurfacePreview() {
    val spacing = teddReaderSpacing()

    TeddReaderTheme {
        ReaderChromeSurface(
            style = ReaderStyle(),
            contentPadding = PaddingValues(
                horizontal = spacing.small,
                vertical = spacing.xxSmall,
            ),
        ) {
            androidx.compose.material3.Text("Transient chrome")
        }
    }
}

@Preview(widthDp = 360)
@Composable
private fun ReaderChromeSurfaceDarkPreview() {
    val spacing = teddReaderSpacing()

    TeddReaderTheme(darkTheme = true) {
        ReaderChromeSurface(
            style = darkReaderStyle(),
            contentPadding = PaddingValues(
                horizontal = spacing.small,
                vertical = spacing.xxSmall,
            ),
        ) {
            androidx.compose.material3.Text("Transient chrome")
        }
    }
}

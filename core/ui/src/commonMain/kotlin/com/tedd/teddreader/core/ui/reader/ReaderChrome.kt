package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
    dividerAtTop: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
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
                .drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    val y = if (dividerAtTop) {
                        strokeWidth / 2f
                    } else {
                        size.height - strokeWidth / 2f
                    }
                    drawLine(
                        color = colors.controlsContent.copy(alpha = 0.12f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokeWidth,
                    )
                }
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

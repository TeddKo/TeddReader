package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
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

/**
 * The surface the reader's own bars and sheets sit on, over the page.
 *
 * Its colours come from the reader's style rather than from the app theme, because these controls float over
 * the *book's* paper: a sepia page needs sepia-tinted chrome, and app-themed chrome over it reads as another
 * app's window. Both the surface colour and the content colour are set here, so a caller cannot leave an icon
 * unreadable against the reader's own paper.
 *
 * The divider is drawn with `drawBehind` rather than as a `Divider` child, so the bar stays one layout node
 * and the line can sit on either edge without changing the layout.
 *
 * @param style the reader's style, which supplies the control colours.
 * @param modifier applied to the surface; it fills its parent's width.
 * @param contentPadding inset around [content], inside the window insets.
 * @param windowInsets the system insets this bar has to keep clear — the status bar for a top bar, the
 * navigation bar for a bottom one. Zero by default so a preview needs none.
 * @param dividerAtTop true for a bottom bar, whose hairline belongs on its upper edge; false for a top bar.
 * @param content the bar's own controls, in the bar's box scope.
 */
@Composable
fun ReaderChromeSurface(
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
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
                .windowInsetsPadding(windowInsets)
                .padding(contentPadding),
        ) {
            content()
        }
    }
}

/** A top bar on day paper, with its hairline along the bottom edge. */
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

/** The same bar on night paper, where the hairline's alpha has to stay visible. */
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

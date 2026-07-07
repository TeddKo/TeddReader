package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.reader.ReaderBottomControls
import com.tedd.teddreader.core.ui.reader.ReaderProgressBar

@Composable
fun ReaderBottomActionBar(
    pageIndex: PageIndex,
    style: ReaderStyle,
    isAutoScrollEnabled: Boolean,
    showProgress: Boolean,
    onAutoScrollToggle: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ReaderBottomControls(
        style = style,
        modifier = modifier,
        progress = if (showProgress) {
            {
            ReaderProgressBar(
                pageIndex = pageIndex,
                showPageLabel = true,
                showPercentLabel = false,
                compact = true,
            )
            }
        } else {
            null
        },
        actions = {
            TeddIconButton(onClick = onPreviousPage, contentDescription = "Previous page") { Text("‹", maxLines = 1) }
            TeddIconButton(onClick = onNextPage, contentDescription = "Next page") { Text("›", maxLines = 1) }
            TeddIconButton(
                onClick = onAutoScrollToggle,
                contentDescription = if (isAutoScrollEnabled) "Disable auto-scroll" else "Enable auto-scroll",
            ) {
                Text(if (isAutoScrollEnabled) "Ⅱ" else "▶", maxLines = 1)
            }
        },
    )
}

@Preview(widthDp = 360)
@Composable
private fun ReaderBottomActionBarPreview() {
    TeddReaderTheme {
        ReaderBottomActionBar(
            pageIndex = PageIndex(current = 4, total = 20),
            style = ReaderStyle(),
            isAutoScrollEnabled = true,
            showProgress = true,
            onAutoScrollToggle = {},
            onPreviousPage = {},
            onNextPage = {},
        )
    }
}

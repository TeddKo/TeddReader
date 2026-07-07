package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.extension.toOneBasedPageNumber
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.darkReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

@Composable
fun ReaderProgressBar(
    pageIndex: PageIndex,
    modifier: Modifier = Modifier,
    showPageLabel: Boolean = false,
    showPercentLabel: Boolean = false,
    compact: Boolean = false,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    if (compact) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xxSmall),
        ) {
            if (showPageLabel) {
                ReaderPageLabel(
                    pageIndex = pageIndex,
                    modifier = Modifier,
                )
            }

            LinearProgressIndicator(
                progress = { pageIndex.progress },
                modifier = modifier.weight(1f),
            )

            if (showPercentLabel) {
                Text(
                    text = "${(pageIndex.progress * 100).toInt()}%",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = typography.readerCaption,
                )
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.xxSmall)) {
        if (showPageLabel) {
            ReaderPageLabel(pageIndex = pageIndex)
        }

        LinearProgressIndicator(
            progress = { pageIndex.progress },
            modifier = modifier.fillMaxWidth(),
        )

        if (showPercentLabel) {
            Text(
                text = "${(pageIndex.progress * 100).toInt()}%",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = typography.readerCaption,
            )
        }
    }
}

@Composable
fun ReaderPageLabel(
    pageIndex: PageIndex,
    modifier: Modifier = Modifier,
) {
    val typography = teddReaderTypography()
    val current = if (pageIndex.total == 0) 0 else pageIndex.current.toOneBasedPageNumber()

    Text(
        text = "$current / ${pageIndex.total}",
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = typography.readerCaption,
    )
}

@Preview(widthDp = 360)
@Composable
private fun ReaderProgressPreview() {
    TeddReaderTheme {
        Column(verticalArrangement = Arrangement.spacedBy(teddReaderSpacing().small)) {
            val pageIndex = PageIndex(current = 4, total = 20)
            ReaderProgressBar(
                pageIndex = pageIndex,
                showPageLabel = true,
                showPercentLabel = true,
            )
            ReaderPageLabel(pageIndex = pageIndex)
        }
    }
}

@Preview(widthDp = 360)
@Composable
private fun ReaderProgressCompactDarkPreview() {
    TeddReaderTheme(darkTheme = true) {
        ReaderProgressBar(
            pageIndex = PageIndex(current = 88, total = 240),
            showPageLabel = true,
            showPercentLabel = false,
            compact = true,
        )
    }
}

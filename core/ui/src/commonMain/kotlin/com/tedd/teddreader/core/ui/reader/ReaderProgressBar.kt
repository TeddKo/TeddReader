package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
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
import com.tedd.teddreader.core.ui.component.TeddText

/**
 * How far through the book the reader is: a bar, optionally with the page count and the percentage.
 *
 * The figures come from [PageIndex], whose total is "pages known so far" rather than the whole book — so
 * while a progressive import is still running this bar honestly shows progress through what has been
 * measured, and both it and the label move as the total grows.
 *
 * @param pageIndex the current page and the total known so far.
 * @param modifier applied to the bar.
 * @param showPageLabel whether to show "current / total" beside the bar.
 * @param showPercentLabel whether to show the percentage.
 * @param compact true lays the label, bar and percentage out in one row for a bottom bar; false stacks them,
 * which is what a sheet or a wide layout uses.
 */
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
            modifier = modifier,
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
                modifier = Modifier.weight(1f),
            )

            if (showPercentLabel) {
                TeddText(
                    text = pageIndex.percentLabel(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = typography.readerCaption,
                )
            }
        }
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
    ) {
        if (showPageLabel) {
            ReaderPageLabel(pageIndex = pageIndex)
        }

        LinearProgressIndicator(
            progress = { pageIndex.progress },
            modifier = Modifier.fillMaxWidth(),
        )

        if (showPercentLabel) {
            TeddText(
                text = pageIndex.percentLabel(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = typography.readerCaption,
            )
        }
    }
}

/**
 * The page counter on its own, for a bar that shows the number without the progress track.
 *
 * @param pageIndex the current page and the total known so far.
 * @param modifier applied to the label.
 */
@Composable
fun ReaderPageLabel(
    pageIndex: PageIndex,
    modifier: Modifier = Modifier,
) {
    val typography = teddReaderTypography()
    val current = if (pageIndex.total == 0) 0 else pageIndex.current.toOneBasedPageNumber()

    TeddText(
        text = pageIndex.pageLabel(current),
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = typography.readerCaption,
    )
}


/**
 * @receiver the page index to render.
 * @param current the already one-based page number to show; a total of zero shows 0 rather than 1, so a book
 * with nothing measured yet does not claim to be on its first page.
 * @return the `"current / total"` text.
 */
private fun PageIndex.pageLabel(current: Int = if (total == 0) 0 else this.current.toOneBasedPageNumber()): String = "$current / $total"

/**
 * @receiver the page index to render.
 * @return the progress as a whole-percent string, truncated rather than rounded so it never shows 100%
 * before the last page.
 */
private fun PageIndex.percentLabel(): String = "${(progress * 100).toInt()}%"

/** The stacked layout, with both labels shown. */
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

/** The one-row layout on night paper, as the reader's bottom bar shows it. */
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

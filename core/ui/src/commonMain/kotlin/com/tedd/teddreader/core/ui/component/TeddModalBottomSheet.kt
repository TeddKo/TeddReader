package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.extension.consumeUnconsumedVerticalScroll

/**
 * The app's modal bottom sheet chrome: a title/description header above [content], laid out on top
 * of Material's [ModalBottomSheet] with the app's spacing and typography. The header-plus-content
 * [Column] is also given [consumeUnconsumedVerticalScroll], because without it, vertical drag deltas
 * that [content] itself does not consume (e.g. a scrollable list that has reached its top or bottom,
 * or non-scrolling content at all) leak past this column into [ModalBottomSheet]'s own nested-scroll
 * handling and get interpreted as a drag to expand or dismiss the sheet — a real bug this app hit
 * where scrolling to the end of a sheet's content caused the sheet itself to jump closed. Consuming
 * the leftover here keeps sheet-drag and content-scroll gestures independent.
 *
 * @param title The sheet's header text, shown in [teddReaderTypography]'s `titleLarge` style.
 * @param onDismissRequest Invoked when the user dismisses the sheet (tap outside, swipe down, or
 * back gesture).
 * @param sheetState The sheet's expand/collapse/hide state; owned and remembered by the caller so it
 * can also trigger dismissal programmatically.
 * @param modifier Modifier applied to the underlying [ModalBottomSheet].
 * @param description A second header line shown under [title] in a muted color; omitted when null.
 * @param contentPadding Padding around [content], below the header.
 * @param content The sheet's body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeddModalBottomSheet(
    title: String,
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    description: String? = null,
    contentPadding: PaddingValues = PaddingValues(
        start = DefaultTeddReaderSpacing.sheetPadding,
        end = DefaultTeddReaderSpacing.sheetPadding,
        bottom = DefaultTeddReaderSpacing.large,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .consumeUnconsumedVerticalScroll(),
        ) {
            Column(
                modifier = Modifier.padding(
                    start = DefaultTeddReaderSpacing.sheetPadding,
                    top = DefaultTeddReaderSpacing.large,
                    end = DefaultTeddReaderSpacing.sheetPadding,
                ),
            ) {
                Text(
                    text = title,
                    style = typography.titleLarge,
                )
                if (description != null) {
                    Text(
                        text = description,
                        modifier = Modifier.padding(top = spacing.xxSmall),
                        style = typography.settingDescription,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(spacing.medium))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content,
            )
        }
    }
}

/** Compose preview rendering [TeddModalBottomSheet] expanded, with one [TeddButton] as its content. */
@Preview
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeddModalBottomSheetContentPreview() {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    TeddPreviewSurface {
        TeddModalBottomSheet(
            title = "Sort by",
            onDismissRequest = {},
            sheetState = sheetState,
            content = {
                TeddButton(text = "Recent", onClick = {})
            },
        )
    }
}

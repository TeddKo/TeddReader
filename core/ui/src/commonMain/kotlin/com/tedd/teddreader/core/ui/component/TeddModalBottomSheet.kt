package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderColors
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
 * The expand/collapse state is remembered here rather than accepted as a parameter. Callers used to
 * create it and thread it down through every intermediate composable, but none of them ever called a
 * method on it — the state was pure pass-through to this composable. Owning it here removes that
 * parameter chain, and it keeps Material's experimental sheet-state API from leaking opt-in
 * requirements into every screen that shows a sheet, which a type alias could not do because Kotlin
 * expands an alias back to the annotated original.
 *
 * Sheets in this app open one at a time, so a per-sheet state behaves the same as the shared one it
 * replaced: only the sheet currently in composition has a state at all.
 *
 * @param title The sheet's header text, shown in [teddReaderTypography]'s `titleLarge` style.
 * @param onDismissRequest Invoked when the user dismisses the sheet (tap outside, swipe down, or
 * back gesture). This is the only way the sheet closes, so it must clear whatever state decides to
 * compose the sheet at all.
 * @param modifier Modifier applied to the underlying [ModalBottomSheet].
 * @param description A second header line shown under [title] in a muted color; omitted when null.
 * @param contentPadding Padding around [content], below the header; null means the theme's
 * sheetPadding/sheetPadding/large (start/end/bottom) combination is used.
 * @param content The sheet's body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeddModalBottomSheet(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    contentPadding: PaddingValues? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        start = spacing.sheetPadding,
        end = spacing.sheetPadding,
        bottom = spacing.large,
    )
    val typography = teddReaderTypography()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .consumeUnconsumedVerticalScroll(),
        ) {
            Column(
                modifier = Modifier.padding(
                    start = spacing.sheetPadding,
                    top = spacing.large,
                    end = spacing.sheetPadding,
                ),
            ) {
                TeddText(
                    text = title,
                    style = typography.titleLarge,
                )
                if (description != null) {
                    TeddText(
                        text = description,
                        modifier = Modifier.padding(top = spacing.xxSmall),
                        style = typography.settingDescription,
                        color = teddReaderColors().onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(spacing.medium))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(resolvedContentPadding),
                content = content,
            )
        }
    }
}

/** Compose preview rendering [TeddModalBottomSheet] expanded, with one [TeddButton] as its content. */
@Preview
@Composable
private fun TeddModalBottomSheetContentPreview() {
    TeddPreviewSurface {
        TeddModalBottomSheet(
            title = "Sort by",
            onDismissRequest = {},
            content = {
                TeddButton(text = "Recent", onClick = {})
            },
        )
    }
}

package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.ui.component.TeddDropdownMenuItem
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.feature.reader.impl.ReaderMenuAction

@Composable
fun ReaderActionMenu(
    expanded: Boolean,
    isCurrentPageSaved: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onActionSelected: (ReaderMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        TeddIconButton(
            onClick = { onExpandedChange(true) },
            contentDescription = "Reader actions",
        ) {
            Icon(imageVector = TeddIcons.MoreVert, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            ReaderMenuSection(
                title = "Navigation",
                actions = listOf(
                    ReaderMenuAction.TableOfContents,
                    ReaderMenuAction.GoToPage,
                    ReaderMenuAction.SavedPlaces,
                    ReaderMenuAction.Search,
                    ReaderMenuAction.DocumentInfo,
                ),
                isCurrentPageSaved = isCurrentPageSaved,
                onActionSelected = onActionSelected,
                onDismiss = { onExpandedChange(false) },
            )
            HorizontalDivider()
            ReaderMenuSection(
                title = "Appearance",
                actions = listOf(
                    ReaderMenuAction.ViewOptions,
                    ReaderMenuAction.FontOptions,
                    ReaderMenuAction.ThemeOptions,
                    ReaderMenuAction.BrightnessOptions,
                    ReaderMenuAction.ControlOptions,
                ),
                onActionSelected = onActionSelected,
                onDismiss = { onExpandedChange(false) },
            )
            HorizontalDivider()
            ReaderMenuSection(
                title = "Reading tools",
                actions = listOf(
                    ReaderMenuAction.ToggleSavedPlace,
                    ReaderMenuAction.PageTurnOptions,
                    ReaderMenuAction.AutoScrollOptions,
                ),
                isCurrentPageSaved = isCurrentPageSaved,
                onActionSelected = onActionSelected,
                onDismiss = { onExpandedChange(false) },
            )
        }
    }
}

@Composable
private fun ReaderMenuSection(
    title: String,
    actions: List<ReaderMenuAction>,
    isCurrentPageSaved: Boolean = false,
    onActionSelected: (ReaderMenuAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    headerPadding: PaddingValues = PaddingValues(
        start = DefaultTeddReaderSpacing.medium,
        top = DefaultTeddReaderSpacing.small,
        end = DefaultTeddReaderSpacing.medium,
        bottom = DefaultTeddReaderSpacing.xxSmall,
    ),
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            modifier = Modifier.padding(headerPadding),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        actions.forEach { action ->
            TeddDropdownMenuItem(
                text = action.label(isCurrentPageSaved),
                onClick = {
                    onDismiss()
                    onActionSelected(action)
                },
            )
        }
    }
}

private fun ReaderMenuAction.label(isCurrentPageSaved: Boolean): String = when (this) {
    ReaderMenuAction.Search -> "Search in document"
    ReaderMenuAction.ToggleSavedPlace -> if (isCurrentPageSaved) "Remove saved place" else "Save current page"
    ReaderMenuAction.SavedPlaces -> "Saved places"
    ReaderMenuAction.TableOfContents -> "Table of contents"
    ReaderMenuAction.GoToPage -> "Jump to page"
    ReaderMenuAction.ViewOptions -> "Display"
    ReaderMenuAction.FontOptions -> "Typography"
    ReaderMenuAction.ThemeOptions -> "Theme"
    ReaderMenuAction.PageTurnOptions -> "Page movement"
    ReaderMenuAction.AutoScrollOptions -> "Auto-scroll"
    ReaderMenuAction.BrightnessOptions -> "Brightness"
    ReaderMenuAction.ControlOptions -> "Bottom bar"
    ReaderMenuAction.DocumentInfo -> "Document details"
}

@Preview
@Composable
private fun ReaderActionMenuPreview() {
    TeddReaderTheme {
        ReaderActionMenu(
            expanded = false,
            isCurrentPageSaved = false,
            onExpandedChange = {},
            onActionSelected = {},
        )
    }
}

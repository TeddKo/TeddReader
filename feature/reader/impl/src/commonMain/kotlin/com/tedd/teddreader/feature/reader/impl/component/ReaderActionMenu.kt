package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.ui.component.TeddDropdownMenu
import com.tedd.teddreader.core.ui.component.TeddDropdownMenuItem
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.feature.reader.impl.ReaderMenuAction

@Composable
fun ReaderActionMenu(
    onActionSelected: (ReaderMenuAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    TeddIconButton(
        onClick = { expanded = true },
        contentDescription = "Reader actions",
    ) {
        Text("⋮")
    }
    TeddDropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        ReaderMenuSection(
            title = "Reading",
            actions = listOf(
                ReaderMenuAction.Search,
                ReaderMenuAction.Bookmark,
                ReaderMenuAction.TableOfContents,
                ReaderMenuAction.GoToPage,
            ),
            onActionSelected = onActionSelected,
            onDismiss = { expanded = false },
        )
        HorizontalDivider()
        ReaderMenuSection(
            title = "Appearance",
            actions = listOf(
                ReaderMenuAction.ViewOptions,
                ReaderMenuAction.FontOptions,
                ReaderMenuAction.ThemeOptions,
                ReaderMenuAction.BrightnessOptions,
            ),
            onActionSelected = onActionSelected,
            onDismiss = { expanded = false },
        )
        HorizontalDivider()
        ReaderMenuSection(
            title = "Motion",
            actions = listOf(
                ReaderMenuAction.PageTurnOptions,
                ReaderMenuAction.AutoScrollOptions,
            ),
            onActionSelected = onActionSelected,
            onDismiss = { expanded = false },
        )
        HorizontalDivider()
        ReaderMenuSection(
            title = "Info",
            actions = listOf(ReaderMenuAction.DocumentInfo, ReaderMenuAction.ControlOptions),
            onActionSelected = onActionSelected,
            onDismiss = { expanded = false },
        )
    }
}

@Composable
private fun ReaderMenuSection(
    title: String,
    actions: List<ReaderMenuAction>,
    onActionSelected: (ReaderMenuAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = teddReaderSpacing()
    Text(
        text = title,
        modifier = Modifier.padding(
            start = spacing.medium,
            top = spacing.small,
            bottom = spacing.xxSmall,
            end = spacing.medium,
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column {
        actions.forEach { action ->
            TeddDropdownMenuItem(
                text = action.label,
                onClick = {
                    onDismiss()
                    onActionSelected(action)
                },
                leadingIcon = {
                    Text(action.icon)
                },
            )
        }
    }
}

private val ReaderMenuAction.label: String
    get() = when (this) {
        ReaderMenuAction.Search -> "Search"
        ReaderMenuAction.Bookmark -> "Bookmarks"
        ReaderMenuAction.TableOfContents -> "Table of contents"
        ReaderMenuAction.GoToPage -> "Go to page"
        ReaderMenuAction.ViewOptions -> "View"
        ReaderMenuAction.FontOptions -> "Font"
        ReaderMenuAction.ThemeOptions -> "Theme"
        ReaderMenuAction.PageTurnOptions -> "Page turn"
        ReaderMenuAction.AutoScrollOptions -> "Auto-scroll"
        ReaderMenuAction.BrightnessOptions -> "Brightness"
        ReaderMenuAction.ControlOptions -> "Controls"
        ReaderMenuAction.DocumentInfo -> "Document info"
    }

private val ReaderMenuAction.icon: String
    get() = when (this) {
        ReaderMenuAction.Search -> "⌕"
        ReaderMenuAction.Bookmark -> "★"
        ReaderMenuAction.TableOfContents -> "≡"
        ReaderMenuAction.GoToPage -> "№"
        ReaderMenuAction.ViewOptions -> "◫"
        ReaderMenuAction.FontOptions -> "Aa"
        ReaderMenuAction.ThemeOptions -> "◐"
        ReaderMenuAction.PageTurnOptions -> "⇄"
        ReaderMenuAction.AutoScrollOptions -> "▶"
        ReaderMenuAction.BrightnessOptions -> "☼"
        ReaderMenuAction.ControlOptions -> "⌘"
        ReaderMenuAction.DocumentInfo -> "i"
    }

@Preview
@Composable
private fun ReaderActionMenuPreview() {
    TeddReaderTheme {
        ReaderActionMenu(onActionSelected = {})
    }
}

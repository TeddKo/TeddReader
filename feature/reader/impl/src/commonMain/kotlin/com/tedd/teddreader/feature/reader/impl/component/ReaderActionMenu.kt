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
import com.tedd.teddreader.core.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
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
            contentDescription = stringResource(Res.string.reader_actions),
        ) {
            Icon(imageVector = TeddIcons.MoreVert, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            ReaderMenuSection(
                title = stringResource(Res.string.navigation),
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
                title = stringResource(Res.string.appearance),
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
                title = stringResource(Res.string.reading_tools),
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

@Composable
private fun ReaderMenuAction.label(isCurrentPageSaved: Boolean): String = when (this) {
    ReaderMenuAction.Search -> stringResource(Res.string.search_in_document)
    ReaderMenuAction.ToggleSavedPlace -> if (isCurrentPageSaved) stringResource(Res.string.remove_saved_place) else stringResource(Res.string.save_current_page)
    ReaderMenuAction.SavedPlaces -> stringResource(Res.string.saved_places)
    ReaderMenuAction.TableOfContents -> stringResource(Res.string.table_of_contents)
    ReaderMenuAction.GoToPage -> stringResource(Res.string.jump_to_page)
    ReaderMenuAction.ViewOptions -> stringResource(Res.string.display)
    ReaderMenuAction.FontOptions -> stringResource(Res.string.typography)
    ReaderMenuAction.ThemeOptions -> stringResource(Res.string.theme)
    ReaderMenuAction.PageTurnOptions -> stringResource(Res.string.page_movement)
    ReaderMenuAction.AutoScrollOptions -> stringResource(Res.string.auto_scroll)
    ReaderMenuAction.BrightnessOptions -> stringResource(Res.string.reader_option_brightness)
    ReaderMenuAction.ControlOptions -> stringResource(Res.string.bottom_bar)
    ReaderMenuAction.DocumentInfo -> stringResource(Res.string.document_details)
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

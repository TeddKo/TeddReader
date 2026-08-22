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

/**
 * The reader's top-bar overflow menu: a "more" icon button that, when tapped, opens a dropdown
 * grouped into navigation, appearance, and reading-tools sections, each rendered by
 * [ReaderMenuSection]. Every item forwards the [ReaderMenuAction] it represents to
 * [onActionSelected] and closes itself; this composable only presents the choices, it does not
 * interpret what an action means.
 *
 * @param expanded Whether the dropdown is currently open.
 * @param isCurrentPageSaved Whether the current page already has a saved place, used to pick the
 * "save"/"remove saved place" wording for the toggle action.
 * @param onExpandedChange Invoked when the menu should open or close.
 * @param onActionSelected Invoked with the action the user picked, after the menu has closed.
 * @param modifier Applied to the menu's anchor [Box].
 */
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

/**
 * One titled group of menu items inside [ReaderActionMenu]'s dropdown: a section header followed
 * by one row per [ReaderMenuAction] in [actions], each labeled via [label].
 *
 * @param title The section's header text.
 * @param actions The actions to list, in display order.
 * @param isCurrentPageSaved Forwarded to [label] for whichever action's wording depends on it;
 * unused by actions that do not.
 * @param onActionSelected Invoked with the action the user tapped.
 * @param onDismiss Invoked alongside [onActionSelected] to close the parent dropdown.
 * @param modifier Applied to this section's column.
 * @param headerPadding Padding around the section header text.
 */
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

/**
 * The display label for this action in the reader's action menu.
 *
 * @receiver The action to label.
 * @param isCurrentPageSaved Whether the current page already has a saved place; only
 * [ReaderMenuAction.ToggleSavedPlace] uses this to pick "save" vs. "remove" wording.
 * @return The localized label shown for this action.
 */
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

/** Compose preview of [ReaderActionMenu] in its default, closed state, for the IDE preview pane. */
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

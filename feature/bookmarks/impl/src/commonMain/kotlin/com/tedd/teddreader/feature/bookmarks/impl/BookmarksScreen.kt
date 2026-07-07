package com.tedd.teddreader.feature.bookmarks.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddCard
import com.tedd.teddreader.core.ui.component.TeddChip
import com.tedd.teddreader.core.ui.component.TeddEmptyState
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddModalBottomSheet
import com.tedd.teddreader.core.ui.component.TeddSurface
import com.tedd.teddreader.core.ui.component.TeddTextField
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun BookmarksRouteScreen(
    documentId: String,
    onBack: () -> Unit,
    onBookmarkClick: (ReaderLocation) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookmarksViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(documentId) {
        viewModel.setDocument(documentId)
    }

    BookmarksScreen(
        uiState = uiState,
        onBack = onBack,
        onBookmarkClick = onBookmarkClick,
        onEditClick = viewModel::startEdit,
        onDeleteClick = viewModel::deleteBookmark,
        onDismissEdit = viewModel::dismissEdit,
        onSaveNote = viewModel::saveNote,
        modifier = modifier,
    )
}

@Composable
fun BookmarksScreen(
    uiState: BookmarksUiState,
    onBack: () -> Unit,
    onBookmarkClick: (ReaderLocation) -> Unit,
    onEditClick: (Bookmark) -> Unit,
    onDeleteClick: (Bookmark) -> Unit,
    onDismissEdit: () -> Unit,
    onSaveNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    if (uiState.isLoading) {
        TeddSurface(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                TeddLoadingIndicator(message = "Loading bookmarks")
            }
        }
        return
    }

    TeddSurface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(spacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                TeddButton(text = "Back", onClick = onBack)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xxSmall)) {
                    Text(
                        text = "Bookmarks",
                        style = typography.headlineSmall,
                    )
                    Text(
                        text = "${uiState.bookmarks.size} saved spots",
                        style = typography.settingDescription,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            uiState.errorMessage?.let { message ->
                TeddErrorBanner(message = message)
            }

            if (uiState.bookmarks.isEmpty()) {
                TeddEmptyState(
                    title = "No bookmarks yet",
                    description = "Save a spot from the reader and it will show up here.",
                    action = {
                        TeddButton(text = "Back to reader", onClick = onBack)
                    },
                )
            } else {
                uiState.bookmarks.forEach { bookmark ->
                    BookmarkCard(
                        bookmark = bookmark,
                        onBookmarkClick = { onBookmarkClick(bookmark.location) },
                        onEditClick = { onEditClick(bookmark) },
                        onDeleteClick = { onDeleteClick(bookmark) },
                    )
                }
            }
        }

        uiState.editingBookmark?.let { bookmark ->
            var note by remember(bookmark.id) { mutableStateOf(bookmark.note.orEmpty()) }

            TeddModalBottomSheet(
                title = "Bookmark note",
                onDismissRequest = onDismissEdit,
            ) {
                val sheetSpacing = teddReaderSpacing()
                val sheetTypography = teddReaderTypography()
                Column(verticalArrangement = Arrangement.spacedBy(sheetSpacing.medium)) {
                    TeddCard {
                        Column(
                            modifier = Modifier.padding(sheetSpacing.medium),
                            verticalArrangement = Arrangement.spacedBy(sheetSpacing.small),
                        ) {
                            Text(
                                text = bookmark.label ?: bookmark.location.asStorageString(),
                                style = sheetTypography.titleMedium,
                            )
                            TeddChip(text = bookmark.location.asStorageString())
                        }
                    }

                    TeddTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "Note",
                        minLines = 3,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(sheetSpacing.small)) {
                        TeddButton(text = "Save", onClick = { onSaveNote(note) })
                        TeddButton(text = "Delete", onClick = { onDeleteClick(bookmark) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkCard(
    bookmark: Bookmark,
    onBookmarkClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    TeddCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            TeddListItem(
                title = bookmark.label ?: bookmark.location.asStorageString(),
                supportingText = bookmark.note,
                onClick = onBookmarkClick,
                trailingContent = {
                    TeddChip(text = "Open")
                },
            )
            Text(
                text = bookmark.location.asStorageString(),
                style = typography.settingDescription,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                TeddButton(text = "Edit note", onClick = onEditClick)
                TeddButton(text = "Delete", onClick = onDeleteClick)
            }
        }
    }
}

@Preview
@Composable
private fun BookmarksScreenPreview() {
    TeddReaderTheme {
        BookmarksScreen(
            uiState = BookmarksUiState(
                isLoading = false,
                bookmarks = listOf(
                    Bookmark(
                        id = "preview",
                        documentId = DocumentId("preview"),
                        location = ReaderLocation.TextOffset(42L),
                        label = "Page 1",
                        note = "Interesting paragraph",
                        createdAtEpochMillis = 0L,
                    ),
                ),
            ),
            onBack = {},
            onBookmarkClick = {},
            onEditClick = {},
            onDeleteClick = {},
            onDismissEdit = {},
            onSaveNote = {},
        )
    }
}

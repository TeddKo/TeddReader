package com.tedd.teddreader.feature.bookmarks.impl

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddButtonEmphasis
import com.tedd.teddreader.core.ui.component.TeddCard
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddEmptyState
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddModalBottomSheet
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddTextField
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.icon.TeddIcons
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksRouteScreen(
    documentId: String,
    onBack: () -> Unit,
    onBookmarkClick: (ReaderLocation) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookmarksViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var note by rememberSaveable(documentId) { mutableStateOf("") }
    var pendingDeleteBookmarkId by rememberSaveable(documentId) { mutableStateOf<String?>(null) }
    var pendingDeleteFromEditSheet by rememberSaveable(documentId) { mutableStateOf(false) }

    LaunchedEffect(documentId) {
        viewModel.setDocument(documentId)
    }

    BookmarksScreen(
        uiState = uiState,
        onBack = onBack,
        onBookmarkClick = onBookmarkClick,
        note = note,
        onNoteChange = { note = it },
        onEditClick = { bookmark ->
            note = bookmark.note.orEmpty()
            viewModel.startEdit(bookmark)
        },
        pendingDeleteBookmarkId = pendingDeleteBookmarkId,
        onRequestDelete = { bookmark, fromEditSheet ->
            pendingDeleteBookmarkId = bookmark.id
            pendingDeleteFromEditSheet = fromEditSheet
        },
        onDismissDeleteConfirmation = {
            pendingDeleteBookmarkId = null
            pendingDeleteFromEditSheet = false
        },
        onConfirmDelete = { bookmark ->
            if (pendingDeleteFromEditSheet) viewModel.dismissEdit()
            viewModel.deleteBookmark(bookmark)
            pendingDeleteBookmarkId = null
            pendingDeleteFromEditSheet = false
        },
        onDismissEdit = viewModel::dismissEdit,
        onSaveNote = viewModel::saveNote,
        scrollState = scrollState,
        editSheetState = sheetState,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    uiState: BookmarksUiState,
    onBack: () -> Unit,
    onBookmarkClick: (ReaderLocation) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    onEditClick: (Bookmark) -> Unit,
    pendingDeleteBookmarkId: String?,
    onRequestDelete: (Bookmark, Boolean) -> Unit,
    onDismissDeleteConfirmation: () -> Unit,
    onConfirmDelete: (Bookmark) -> Unit,
    onDismissEdit: () -> Unit,
    onSaveNote: (String) -> Unit,
    scrollState: ScrollState,
    editSheetState: SheetState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(DefaultTeddReaderSpacing.screenPadding),
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    if (uiState.isLoading) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            TeddLoadingIndicator(message = "Loading saved places")
        }
        return
    }

    TeddScaffold(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
        topBar = {
            TeddTopBar(
                title = "Saved places",
                navigationIcon = {
                    TeddIconButton(
                        onClick = onBack,
                        contentDescription = "Back",
                    ) {
                        Icon(
                            imageVector = TeddIcons.Back,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(scaffoldPadding)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Text(
                text = if (uiState.bookmarks.isEmpty()) {
                    "Keep meaningful reading positions from this document."
                } else {
                    "${uiState.bookmarks.size} saved " +
                        if (uiState.bookmarks.size == 1) "place" else "places"
                },
                style = typography.settingDescription,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            uiState.errorMessage?.let { message ->
                TeddErrorBanner(message = message)
            }

            if (uiState.bookmarks.isEmpty()) {
                TeddEmptyState(
                    title = "No saved places yet",
                    description = "Use Save current page from the reader menu to keep a reading position.",
                )
            } else {
                uiState.bookmarks.forEach { bookmark ->
                    BookmarkRow(
                        bookmark = bookmark,
                        onBookmarkClick = { onBookmarkClick(bookmark.location) },
                        onEditClick = { onEditClick(bookmark) },
                    )
                }
            }
        }

        uiState.editingBookmark?.let { bookmark ->
            TeddModalBottomSheet(
                title = "Edit saved place",
                onDismissRequest = onDismissEdit,
                sheetState = editSheetState,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
                    Text(
                        text = bookmark.label ?: bookmark.location.asStorageString(),
                        style = typography.titleMedium,
                    )
                    Text(
                        text = bookmark.location.displayLabel(),
                        style = typography.settingDescription,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TeddTextField(
                        value = note,
                        onValueChange = onNoteChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = "Note",
                        minLines = 3,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                        TeddButton(
                            text = "Save",
                            onClick = { onSaveNote(note) },
                            enabled = note != bookmark.note.orEmpty(),
                        )
                        TeddButton(
                            text = "Delete",
                            onClick = { onRequestDelete(bookmark, true) },
                            emphasis = TeddButtonEmphasis.Destructive,
                        )
                    }
                }
            }
        }

        val pendingDeleteBookmark = pendingDeleteBookmarkId?.let { bookmarkId ->
            uiState.bookmarks.firstOrNull { it.id == bookmarkId }
                ?: uiState.editingBookmark?.takeIf { it.id == bookmarkId }
        }
        if (pendingDeleteBookmark != null) {
            AlertDialog(
                onDismissRequest = onDismissDeleteConfirmation,
                title = { Text("Delete saved place?") },
                text = {
                    Text(
                        "This removes ${pendingDeleteBookmark.label ?: pendingDeleteBookmark.location.displayLabel()}."
                    )
                },
                confirmButton = {
                    TeddButton(
                        text = "Delete",
                        onClick = { onConfirmDelete(pendingDeleteBookmark) },
                        emphasis = TeddButtonEmphasis.Destructive,
                    )
                },
                dismissButton = {
                    TeddButton(
                        text = "Cancel",
                        onClick = onDismissDeleteConfirmation,
                        emphasis = TeddButtonEmphasis.Secondary,
                    )
                },
            )
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    onBookmarkClick: () -> Unit,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()

    TeddCard(modifier = modifier.fillMaxWidth()) {
        TeddListItem(
            title = bookmark.label ?: bookmark.location.displayLabel(),
            supportingText = buildBookmarkSupportingText(bookmark),
            onClick = onBookmarkClick,
            showDivider = false,
        )
        Row(
            modifier = Modifier.padding(
                start = DefaultTeddReaderSpacing.medium,
                end = DefaultTeddReaderSpacing.medium,
                bottom = DefaultTeddReaderSpacing.medium,
            ),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            TeddButton(
                text = "Open",
                onClick = onBookmarkClick,
                emphasis = TeddButtonEmphasis.Text,
            )
            TeddButton(
                text = "Edit note",
                onClick = onEditClick,
                emphasis = TeddButtonEmphasis.Secondary,
            )
        }
    }
}

private fun buildBookmarkSupportingText(bookmark: Bookmark): String =
    bookmark.note?.takeIf { it.isNotBlank() } ?: bookmark.location.displayLabel()

private fun ReaderLocation.displayLabel(): String = when (this) {
    is ReaderLocation.PdfPage -> "PDF page ${pageIndex + 1}"
    is ReaderLocation.TextOffset -> "Text position ${offset + 1}"
    is ReaderLocation.EpubOffset -> "EPUB section ${spineIndex + 1} · position ${offset + 1}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(widthDp = 280)
@Preview(widthDp = 360)
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
            note = "Interesting paragraph",
            onNoteChange = {},
            onEditClick = {},
            pendingDeleteBookmarkId = null,
            onRequestDelete = { _, _ -> },
            onDismissDeleteConfirmation = {},
            onConfirmDelete = {},
            onDismissEdit = {},
            onSaveNote = {},
            scrollState = rememberScrollState(),
            editSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        )
    }
}

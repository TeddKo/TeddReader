package com.tedd.teddreader.feature.bookmarks.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddModalBottomSheet
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddTextField
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.generated.resources.*
import com.tedd.teddreader.core.ui.icon.TeddIcons
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private val ScreenMaxWidth = 720.dp
private val CompactContentWidth = 320.dp

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
    val listState = rememberLazyListState()
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
        listState = listState,
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
    listState: LazyListState,
    editSheetState: SheetState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(DefaultTeddReaderSpacing.screenPadding),
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    TeddScaffold(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
        topBar = {
            TeddTopBar(
                title = stringResource(Res.string.saved_places),
                navigationIcon = {
                    TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                        Icon(imageVector = TeddIcons.Back, contentDescription = null)
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .widthIn(max = ScreenMaxWidth)
                    .fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                if (uiState.bookmarks.isNotEmpty()) {
                    item {
                        Text(
                            text = if (uiState.bookmarks.size == 1) stringResource(Res.string.saved_places_single) else stringResource(Res.string.saved_places_count, uiState.bookmarks.size),
                            style = typography.settingDescription,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                uiState.errorMessage?.let { message ->
                    item { TeddErrorBanner(message = message) }
                }
                when {
                    uiState.isLoading -> item {
                        TeddLoadingIndicator(message = stringResource(Res.string.loading_saved_places))
                    }
                    uiState.bookmarks.isEmpty() -> item {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val compact = maxWidth < CompactContentWidth
                            val textAlign = if (compact) TextAlign.Start else TextAlign.Center
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
                                horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally,
                            ) {
                                Text(text = stringResource(Res.string.saved_places_empty_title), style = typography.titleMedium, textAlign = textAlign)
                                Text(
                                    text = stringResource(Res.string.saved_places_empty_description),
                                    style = typography.settingDescription,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = textAlign,
                                )
                            }
                        }
                    }
                    else -> items(uiState.bookmarks, key = { it.id }) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            onBookmarkClick = { onBookmarkClick(bookmark.location) },
                            onEditClick = { onEditClick(bookmark) },
                        )
                    }
                }
            }
        }

        uiState.editingBookmark?.let { bookmark ->
            TeddModalBottomSheet(
                title = stringResource(Res.string.edit_saved_place),
                onDismissRequest = onDismissEdit,
                sheetState = editSheetState,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
                    Text(text = bookmark.displayTitle(), style = typography.titleMedium)
                    if (bookmark.hasCustomLabel()) {
                        Text(
                            text = bookmark.location.displayLabel(),
                            style = typography.settingDescription,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TeddTextField(
                        value = note,
                        onValueChange = onNoteChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(Res.string.note),
                        minLines = 3,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TeddButton(
                            text = stringResource(Res.string.save),
                            onClick = { onSaveNote(note) },
                            enabled = note != bookmark.note.orEmpty(),
                            modifier = Modifier.weight(1f),
                        )
                        TeddButton(
                            text = stringResource(Res.string.delete),
                            onClick = { onRequestDelete(bookmark, true) },
                            emphasis = TeddButtonEmphasis.Destructive,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        val pendingDeleteBookmark = pendingDeleteBookmarkId?.let { bookmarkId ->
            uiState.bookmarks.firstOrNull { it.id == bookmarkId } ?: uiState.editingBookmark?.takeIf { it.id == bookmarkId }
        }
        if (pendingDeleteBookmark != null) {
            AlertDialog(
                onDismissRequest = onDismissDeleteConfirmation,
                title = { Text(stringResource(Res.string.delete_saved_place_title)) },
                text = { Text(stringResource(Res.string.delete_saved_place_message, pendingDeleteBookmark.displayTitle())) },
                confirmButton = {
                    TeddButton(
                        text = stringResource(Res.string.delete),
                        onClick = { onConfirmDelete(pendingDeleteBookmark) },
                        emphasis = TeddButtonEmphasis.Destructive,
                    )
                },
                dismissButton = {
                    TeddButton(
                        text = stringResource(Res.string.cancel),
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
    TeddListItem(
        title = bookmark.displayTitle(),
        supportingText = buildBookmarkSupportingText(bookmark),
        onClick = onBookmarkClick,
        modifier = modifier,
        trailingContent = {
            TeddIconButton(onClick = onEditClick, contentDescription = stringResource(Res.string.edit_saved_place)) {
                Icon(imageVector = TeddIcons.MoreVert, contentDescription = null)
            }
        },
    )
}

private val legacyPageLabelPattern = Regex("""^Page \d+$""")

private fun Bookmark.hasCustomLabel(): Boolean {
    val currentLabel = label
    return currentLabel != null && !legacyPageLabelPattern.matches(currentLabel)
}

@Composable
private fun Bookmark.displayTitle(): String = label?.takeIf { hasCustomLabel() } ?: location.displayLabel()

@Composable
private fun buildBookmarkSupportingText(bookmark: Bookmark): String = bookmark.note?.takeIf { it.isNotBlank() } ?: bookmark.location.displayLabel()

@Composable
private fun ReaderLocation.displayLabel(): String = when (this) {
    is ReaderLocation.PdfPage -> stringResource(Res.string.bookmark_location_pdf_page, pageIndex + 1)
    is ReaderLocation.TextOffset -> stringResource(Res.string.bookmark_location_text_position, offset + 1)
    is ReaderLocation.EpubOffset -> stringResource(Res.string.bookmark_location_epub_position, spineIndex + 1, offset + 1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(widthDp = 280)
@Preview(widthDp = 360)
@Preview(widthDp = 840)
@Composable
private fun BookmarksScreenPreview() {
    TeddReaderTheme {
        BookmarksScreen(
            uiState = BookmarksUiState(
                isLoading = false,
                bookmarks = persistentListOf(
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
            note = "",
            onNoteChange = {},
            onEditClick = {},
            pendingDeleteBookmarkId = null,
            onRequestDelete = { _, _ -> },
            onDismissDeleteConfirmation = {},
            onConfirmDelete = {},
            onDismissEdit = {},
            onSaveNote = {},
            listState = rememberLazyListState(),
            editSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        )
    }
}

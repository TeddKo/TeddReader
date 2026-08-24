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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderBreakpoints
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.ui.component.TeddAlertDialog
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddButtonEmphasis
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddModalBottomSheet
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddSection
import com.tedd.teddreader.core.ui.component.TeddSectionKind
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.component.TeddTextField
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.generated.resources.*
import com.tedd.teddreader.core.ui.icon.TeddIcons
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Entry point that wires [BookmarksViewModel] into [BookmarksScreen] for one document's saved
 * places. Like [BookmarksScreen] below it, this composable is a pure state-and-callback
 * pass-through to the view model: it collects [BookmarksUiState] and forwards every user action
 * back as a view model call, holding no bookmark data of its own.
 *
 * `note` is a draft: it holds the edit sheet's text field value while the user is typing, and is
 * only written back to storage when [onSaveNote]/[BookmarksViewModel.saveNote] is called. It is
 * seeded from the bookmark's own committed note when [onEditClick] opens the sheet, not kept in
 * sync with [BookmarksUiState] afterward, since the field the user is actively editing should not
 * be overwritten mid-edit by a state change from elsewhere. `pendingDeleteBookmarkId` and
 * `pendingDeleteFromEditSheet` are plain UI state with no committed counterpart at all: they only
 * track which bookmark has a delete confirmation open and whether that confirmation was raised
 * from the edit sheet (so confirming also dismisses the sheet) or from the list directly.
 *
 * @param documentId The document whose saved places this screen shows.
 * @param onBack Invoked when the user asks to leave the saved-places screen.
 * @param onBookmarkClick Invoked with a saved place's location when the user taps it to jump
 * there.
 * @param modifier Applied to the resulting [BookmarksScreen].
 * @param viewModel The bookmarks screen's view model; defaults to one resolved through Koin.
 */
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
        modifier = modifier,
    )
}

/**
 * The saved-places screen for one document: a scaffold with a list of saved places, an edit sheet
 * for the place currently being edited, and a delete-confirmation dialog. This composable is a
 * pure state-and-callback pass-through to the view model — every value it renders comes from
 * [uiState] or one of its other parameters, and every user action is reported back through a
 * callback; it holds no saved-place state of its own.
 *
 * The empty explanation and the bookmark list are each one [TeddSectionKind.Status] or
 * [TeddSectionKind.Collection] section, mutually exclusive by construction. The empty state names
 * no CTA of its own — the top bar's back action already provides the only navigation a reader
 * needs from an empty saved-places screen. Below
 * [TeddReaderBreakpoints.compactControlWidth][com.tedd.teddreader.core.designsystem.TeddReaderBreakpoints.compactControlWidth]
 * the empty state's message switches from centered to start-aligned text, since centering reads
 * cramped once the container itself is that narrow.
 *
 * @param uiState The saved-places screen's current state, as published by the view model.
 * @param onBack Invoked when the user asks to leave the screen.
 * @param onBookmarkClick Invoked with a saved place's location when the user taps its row.
 * @param note The edit sheet's current note draft, shown in its text field.
 * @param onNoteChange Invoked as the user types in the edit sheet's note field.
 * @param onEditClick Invoked with a saved place when the user opens it for editing.
 * @param pendingDeleteBookmarkId The id of the saved place awaiting delete confirmation, or null
 * if no confirmation dialog should be shown.
 * @param onRequestDelete Invoked with the saved place to confirm deleting and whether the request
 * came from the edit sheet's delete button (as opposed to a row in the list), so the caller knows
 * whether confirming should also dismiss the edit sheet.
 * @param onDismissDeleteConfirmation Invoked when the delete-confirmation dialog is dismissed
 * without confirming.
 * @param onConfirmDelete Invoked with the saved place to actually delete once the user confirms.
 * @param onDismissEdit Invoked when the edit sheet is dismissed without saving.
 * @param onSaveNote Invoked with the note text to commit when the user saves it from the edit
 * sheet.
 * @param listState Scroll state for the saved-places list.
 * @param modifier Applied to the scaffold.
 * @param contentPadding Vertical padding applied above and below the list's content; any
 * horizontal component is ignored because horizontal inset is owned by each [TeddSection]. Null
 * resolves to the theme's screenPadding for both edges.
 */
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
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val breakpoints = teddReaderBreakpoints()
    val resolvedContentPadding = contentPadding ?: PaddingValues(spacing.screenPadding)
    val typography = teddReaderTypography()
    val colors = teddReaderColors()

    val subtitle = if (uiState.bookmarks.isNotEmpty()) {
        if (uiState.bookmarks.size == 1) {
            stringResource(Res.string.saved_places_single)
        } else {
            stringResource(Res.string.saved_places_count, uiState.bookmarks.size)
        }
    } else {
        null
    }

    TeddScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TeddTopBar(
                title = stringResource(Res.string.saved_places),
                subtitle = subtitle,
                navigationIcon = {
                    TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                        TeddIcon(imageVector = TeddIcons.Back, contentDescription = null)
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
                    .widthIn(max = breakpoints.readableMaxWidth)
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    top = resolvedContentPadding.calculateTopPadding(),
                    bottom = resolvedContentPadding.calculateBottomPadding(),
                ),
            ) {
                uiState.errorMessage?.let { message ->
                    item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddErrorBanner(message = message)
                        }
                    }
                }
                when {
                    uiState.isLoading -> item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddLoadingIndicator(message = stringResource(Res.string.loading_saved_places))
                        }
                    }
                    uiState.bookmarks.isEmpty() -> item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                val compact = maxWidth < breakpoints.compactControlWidth
                                val textAlign = if (compact) TextAlign.Start else TextAlign.Center
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
                                    horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally,
                                ) {
                                    TeddText(text = stringResource(Res.string.saved_places_empty_title), style = typography.titleMedium, textAlign = textAlign)
                                    TeddText(
                                        text = stringResource(Res.string.saved_places_empty_description),
                                        style = typography.settingDescription,
                                        color = colors.onSurfaceVariant,
                                        textAlign = textAlign,
                                    )
                                }
                            }
                        }
                    }
                    else -> item {
                        TeddSection(kind = TeddSectionKind.Collection) {
                            uiState.bookmarks.forEach { bookmark ->
                                key(bookmark.id) {
                                    BookmarkRow(
                                        bookmark = bookmark,
                                        onBookmarkClick = { onBookmarkClick(bookmark.location) },
                                        onEditClick = { onEditClick(bookmark) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        uiState.editingBookmark?.let { bookmark ->
            TeddModalBottomSheet(
                title = stringResource(Res.string.edit_saved_place),
                onDismissRequest = onDismissEdit,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
                    TeddText(text = bookmark.displayTitle(), style = typography.titleMedium)
                    if (bookmark.hasCustomLabel()) {
                        TeddText(
                            text = bookmark.location.displayLabel(),
                            style = typography.settingDescription,
                            color = colors.onSurfaceVariant,
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
            TeddAlertDialog(
                onDismissRequest = onDismissDeleteConfirmation,
                title = stringResource(Res.string.delete_saved_place_title),
                text = { TeddText(text = stringResource(Res.string.delete_saved_place_message, pendingDeleteBookmark.displayTitle())) },
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
                        emphasis = TeddButtonEmphasis.Text,
                    )
                },
            )
        }
    }
}

/**
 * One saved place's row in [BookmarksScreen]'s list: its title, supporting text (see
 * [buildBookmarkSupportingText]), and an edit button.
 *
 * @param bookmark The saved place this row displays.
 * @param onBookmarkClick Invoked when the row itself is tapped.
 * @param onEditClick Invoked when the row's edit button is tapped.
 * @param modifier Applied to the row.
 */
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
        singleClick = true,
        modifier = modifier,
        trailingContent = {
            TeddIconButton(onClick = onEditClick, contentDescription = stringResource(Res.string.edit_saved_place)) {
                TeddIcon(imageVector = TeddIcons.MoreVert, contentDescription = null)
            }
        },
    )
}

/** Matches a generic, unlocalized label such as "Page 12" that an older build could have saved
 * directly onto [Bookmark.label] instead of leaving it null; [hasCustomLabel] treats a match as no
 * label at all so [displayTitle] falls back to today's localized, live location label instead of
 * showing a stale string in the wrong language. */
private val legacyPageLabelPattern = Regex("""^Page \d+$""")

/**
 * Whether this saved place carries a label the user (or an older build) actually wrote, as opposed
 * to no label or a legacy auto-generated one (see [legacyPageLabelPattern]).
 *
 * @receiver The saved place to check.
 */
private fun Bookmark.hasCustomLabel(): Boolean {
    val currentLabel = label
    return currentLabel != null && !legacyPageLabelPattern.matches(currentLabel)
}

/**
 * The title shown for this saved place: its own label when [hasCustomLabel] is true, otherwise
 * today's localized description of where it points (see [ReaderLocation.displayLabel]).
 *
 * @receiver The saved place to title.
 */
@Composable
private fun Bookmark.displayTitle(): String = label?.takeIf { hasCustomLabel() } ?: location.displayLabel()

/**
 * The secondary text shown under a saved place's title: its note when one has been written,
 * otherwise the same location description [displayTitle] falls back to, so a row without a note
 * still says where it points rather than repeating the title.
 *
 * @param bookmark The saved place to build supporting text for.
 * @return The note if non-blank, otherwise the location's display label.
 */
@Composable
private fun buildBookmarkSupportingText(bookmark: Bookmark): String = bookmark.note?.takeIf { it.isNotBlank() } ?: bookmark.location.displayLabel()

/**
 * A localized, human-readable description of where this location points, for a saved place that
 * has no title of its own to show. Each [ReaderLocation] variant is described in the terms that
 * make sense for it — a page number for a PDF, a raw text offset for plain text, and a
 * section-plus-offset pair for an EPUB spine position — rather than one generic label for all
 * three.
 *
 * @receiver The location to describe.
 */
@Composable
private fun ReaderLocation.displayLabel(): String = when (this) {
    is ReaderLocation.PdfPage -> stringResource(Res.string.bookmark_location_pdf_page, pageIndex + 1)
    is ReaderLocation.TextOffset -> stringResource(Res.string.bookmark_location_text_position, offset + 1)
    is ReaderLocation.EpubOffset -> stringResource(Res.string.bookmark_location_epub_position, spineIndex + 1, offset + 1)
}

/**
 * Preview of [BookmarksScreen] at three widths, with one sample saved place, exercising the
 * compact, default, and wide layouts the screen's content can render at.
 */
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
        )
    }
}

package com.tedd.teddreader.feature.home.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddButtonEmphasis
import com.tedd.teddreader.core.ui.component.TeddChip
import com.tedd.teddreader.core.ui.component.TeddDropdownMenuItem
import com.tedd.teddreader.core.ui.component.TeddEmptyState
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddTextField
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.all
import com.tedd.teddreader.core.ui.generated.resources.back
import com.tedd.teddreader.core.ui.generated.resources.cancel
import com.tedd.teddreader.core.ui.generated.resources.create_folder
import com.tedd.teddreader.core.ui.generated.resources.create_folder_dialog_title
import com.tedd.teddreader.core.ui.generated.resources.delete
import com.tedd.teddreader.core.ui.generated.resources.delete_folder_message
import com.tedd.teddreader.core.ui.generated.resources.delete_folder_title
import com.tedd.teddreader.core.ui.generated.resources.files_in_folder_count
import com.tedd.teddreader.core.ui.generated.resources.folder_name
import com.tedd.teddreader.core.ui.generated.resources.folder_row_description
import com.tedd.teddreader.core.ui.generated.resources.folders
import com.tedd.teddreader.core.ui.generated.resources.library
import com.tedd.teddreader.core.ui.generated.resources.library_empty_all_description
import com.tedd.teddreader.core.ui.generated.resources.library_empty_all_title
import com.tedd.teddreader.core.ui.generated.resources.library_empty_folder_description
import com.tedd.teddreader.core.ui.generated.resources.library_empty_folder_title
import com.tedd.teddreader.core.ui.generated.resources.move_to_folder
import com.tedd.teddreader.core.ui.generated.resources.no_folders_available
import com.tedd.teddreader.core.ui.generated.resources.remove_from_library_message
import com.tedd.teddreader.core.ui.generated.resources.remove_from_library_multiple_message
import com.tedd.teddreader.core.ui.generated.resources.remove_from_library_title
import com.tedd.teddreader.core.ui.generated.resources.rename_folder
import com.tedd.teddreader.core.ui.generated.resources.rename_folder_dialog_title
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.system.rememberDisplayFold
import com.tedd.teddreader.feature.home.impl.component.DocumentCard
import com.tedd.teddreader.feature.home.impl.component.FolderCoverCard
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Caps how wide the library's document/folder grid grows on a tablet or an unfolded foldable, so
 * cards stay a readable size instead of stretching edge-to-edge on a wide screen.
 */
private val ScreenMaxWidth = 720.dp

/**
 * Entry point that wires [HomeViewModel] into [LibraryScreen]. Like [ReaderRouteScreen] in the
 * reader feature, this composable is a pure state-and-callback pass-through to the view model: it
 * collects [HomeUiState] and hoists the grid's scroll state, and forwards every user action
 * straight to a view model call.
 *
 * @param folderId The folder to show documents for, or null to show the whole library.
 * @param onBack Invoked when the user asks to leave the library screen.
 * @param onDocumentClick Invoked when the user opens a document.
 * @param onFolderClick Invoked when the user opens a folder.
 * @param modifier Applied to the resulting [LibraryScreen].
 * @param viewModel The home feature's view model; defaults to one resolved through Koin.
 */
@Composable
fun LibraryRouteScreen(
    folderId: String?,
    onBack: () -> Unit,
    onDocumentClick: (DocumentId) -> Unit,
    onFolderClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    LibraryScreen(
        uiState = uiState,
        folderId = folderId,
        onBack = onBack,
        onDocumentClick = onDocumentClick,
        onFolderClick = onFolderClick,
        onCreateFolder = viewModel::createFolder,
        onMoveDocumentsToFolder = viewModel::moveDocumentsToFolder,
        onRenameFolder = viewModel::renameFolder,
        onDeleteFolder = viewModel::deleteFolder,
        onDocumentBookmarkChange = viewModel::setDocumentBookmarked,
        onDeleteDocuments = viewModel::deleteDocuments,
        onLoadCover = viewModel::loadCover,
        gridState = gridState,
        modifier = modifier,
    )
}

/**
 * Renders the library's document/folder grid: an "All"/"Folders" toggle for the whole-library
 * view, a folder's own document grid when [folderId] is set, multi-select with a dedicated
 * selection top bar, and the dialogs for creating, renaming, and deleting a folder or removing
 * documents. Like [LibraryRouteScreen], this composable is a pure state-and-callback pass-through
 * to the view model; the `remember`/`rememberSaveable` state declared here (selection, dialog
 * visibility, the folder-name draft) is local UI bookkeeping, not a second copy of library state.
 *
 * @param uiState The home feature's current state, as published by the view model.
 * @param folderId The folder whose documents are shown, or null for the whole library.
 * @param onBack Invoked when the user asks to leave the library screen.
 * @param onDocumentClick Invoked when the user opens a document outside of selection mode.
 * @param onFolderClick Invoked when the user opens a folder from the "Folders" tab.
 * @param onCreateFolder Invoked with a new folder's name and the documents to seed it with.
 * @param onMoveDocumentsToFolder Invoked with the documents to move and the target folder's id.
 * @param onRenameFolder Invoked with a folder's id and its new name.
 * @param onDeleteFolder Invoked with the id of the folder to delete.
 * @param onDocumentBookmarkChange Invoked with a document's id and its new favorite state.
 * @param onDeleteDocuments Invoked with the documents the user confirmed removing from the
 * library.
 * @param gridState Scroll state for the document/folder grid, hoisted so the caller can control or
 * observe scroll position.
 * @param modifier Applied to the screen's root.
 * @param contentPadding Padding applied inside the grid, around its items.
 */
@Composable
fun LibraryScreen(
    uiState: HomeUiState,
    folderId: String?,
    onBack: () -> Unit,
    onDocumentClick: (DocumentId) -> Unit,
    onFolderClick: (String) -> Unit,
    onCreateFolder: (String, Collection<DocumentId>) -> Unit,
    onMoveDocumentsToFolder: (Collection<DocumentId>, String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onDocumentBookmarkChange: (DocumentId, Boolean) -> Unit,
    onDeleteDocuments: (Collection<DocumentId>) -> Unit,
    onLoadCover: (DocumentId) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(DefaultTeddReaderSpacing.screenPadding),
) {
    val spacing = teddReaderSpacing()
    val displayFold = rememberDisplayFold()
    val currentFolder = remember(uiState.libraryFolders, folderId) {
        uiState.libraryFolders.firstOrNull { it.id == folderId }
    }
    val documents = remember(uiState.libraryDocuments, folderId) {
        if (folderId == null) uiState.libraryDocuments else uiState.libraryDocuments.filter { it.folderId == folderId }
    }
    var mode by rememberSaveable(folderId) { mutableStateOf(LibraryCollectionMode.All) }
    var selectedDocumentIds by rememberSaveable(folderId) { mutableStateOf<Set<String>>(emptySet()) }
    var actionDocumentId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteDocumentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuExpanded by remember { mutableStateOf(false) }
    var createDialogOpen by remember { mutableStateOf(false) }
    var moveDialogOpen by remember { mutableStateOf(false) }
    var folderNameDraft by rememberSaveable { mutableStateOf("") }
    var editingFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var deletingFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectedDocumentIds.isNotEmpty(),
        onBackCompleted = {
            selectedDocumentIds = emptySet()
            actionDocumentId = null
        },
    )

    LaunchedEffect(documents, uiState.libraryDocuments) {
        val currentIds = documents.mapTo(hashSetOf()) { it.id.value }
        selectedDocumentIds = selectedDocumentIds.filterTo(linkedSetOf()) { it in currentIds }
        pendingDeleteDocumentIds = pendingDeleteDocumentIds.filterTo(linkedSetOf()) { it in currentIds }
        if (actionDocumentId !in currentIds) {
            actionDocumentId = null
        }
    }

    val selectedDocuments = remember(documents, selectedDocumentIds) {
        documents.filter { it.id.value in selectedDocumentIds }
    }
    val pendingDeleteDocuments = remember(documents, pendingDeleteDocumentIds) {
        documents.filter { it.id.value in pendingDeleteDocumentIds }
    }

    TeddScaffold(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
        topBar = {
            if (selectedDocumentIds.isNotEmpty()) {
                LibrarySelectionTopBar(
                    selectedCount = selectedDocumentIds.size,
                    hasFolders = uiState.libraryFolders.isNotEmpty(),
                    onBack = {
                        selectedDocumentIds = emptySet()
                        actionDocumentId = null
                    },
                    onCreateFolder = {
                        folderNameDraft = ""
                        createDialogOpen = true
                        menuExpanded = false
                    },
                    onMoveToFolder = {
                        moveDialogOpen = true
                        menuExpanded = false
                    },
                    menuExpanded = menuExpanded,
                    onMenuExpandedChange = { menuExpanded = it },
                )
            } else {
                TeddTopBar(
                    title = currentFolder?.name ?: stringResource(Res.string.library),
                    navigationIcon = {
                        TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                            Icon(imageVector = TeddIcons.Back, contentDescription = null)
                        }
                    },
                )
            }
        },
    ) { scaffoldPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
        ) {
            val shortestSide = if (maxWidth <= maxHeight) maxWidth else maxHeight
            val previewLimit = libraryPreviewLimit(
                shortestSide = shortestSide,
                displayFold = displayFold,
            )
            val useAdaptiveGrid = shortestSide >= 600.dp || (displayFold?.isVertical == true && displayFold.isSeparating)

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = if (useAdaptiveGrid) GridCells.Adaptive(140.dp) else GridCells.Fixed(2),
                    modifier = Modifier
                        .widthIn(max = ScreenMaxWidth)
                        .fillMaxSize(),
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(spacing.medium),
                ) {
                    if (folderId == null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                            ) {
                                TeddChip(
                                    text = stringResource(Res.string.all),
                                    selected = mode == LibraryCollectionMode.All,
                                    onClick = { mode = LibraryCollectionMode.All },
                                )
                                TeddChip(
                                    text = stringResource(Res.string.folders),
                                    selected = mode == LibraryCollectionMode.Folders,
                                    onClick = { mode = LibraryCollectionMode.Folders },
                                )
                            }
                        }
                    }

                    when {
                        folderId == null && mode == LibraryCollectionMode.Folders -> {
                            if (uiState.libraryFolders.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    TeddEmptyState(
                                        title = stringResource(Res.string.library_empty_folder_title),
                                        description = stringResource(Res.string.library_empty_folder_description),
                                    )
                                }
                            } else {
                                items(uiState.libraryFolders, key = { it.id }) { folder ->
                                    val folderPreviewDocuments = libraryFolderPreviewDocuments(
                                        documents = uiState.libraryDocuments,
                                        folderId = folder.id,
                                        previewLimit = previewLimit,
                                    )
                                    FolderCoverCard(
                                        folder = folder,
                                        previewDocuments = folderPreviewDocuments,
                                        remainingDocumentCount = libraryFolderRemainingDocumentCount(
                                            totalCount = folder.documentCount,
                                            previewCount = folderPreviewDocuments.size,
                                        ),
                                        documentCoverImages = uiState.documentCoverImages,
                                        onClick = { onFolderClick(folder.id) },
                                        onRenameClick = {
                                            folderNameDraft = folder.name
                                            editingFolder = folder
                                        },
                                        onDeleteClick = { deletingFolder = folder },
                                        onLoadCover = { previewDocument -> onLoadCover(previewDocument.id) },
                                    )
                                }
                            }
                        }

                        documents.isEmpty() -> {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                TeddEmptyState(
                                    title = stringResource(
                                        if (folderId == null) Res.string.library_empty_all_title else Res.string.library_empty_folder_title,
                                    ),
                                    description = stringResource(
                                        if (folderId == null) Res.string.library_empty_all_description else Res.string.library_empty_folder_description,
                                    ),
                                )
                            }
                        }

                        else -> items(documents, key = { it.id.value }) { document ->
                            DocumentCard(
                                document = document,
                                coverImageBytes = uiState.documentCoverImages[document.id.value],
                                onLoadCover = { onLoadCover(document.id) },
                                selected = document.id.value in selectedDocumentIds,
                                onClick = {
                                    if (selectedDocumentIds.isNotEmpty()) {
                                        actionDocumentId = null
                                        selectedDocumentIds = selectedDocumentIds.toggle(document.id.value)
                                    } else {
                                        onDocumentClick(document.id)
                                    }
                                },
                                onLongClick = {
                                    actionDocumentId = null
                                    selectedDocumentIds = selectedDocumentIds.toggle(document.id.value)
                                },
                                actionsExpanded = actionDocumentId == document.id.value,
                                onShowActions = { actionDocumentId = document.id.value },
                                onDismissActions = { actionDocumentId = null },
                                onBookmarkClick = {
                                    actionDocumentId = null
                                    onDocumentBookmarkChange(document.id, !document.isBookmarked)
                                },
                                onDeleteClick = {
                                    actionDocumentId = null
                                    pendingDeleteDocumentIds = setOf(document.id.value)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(3f / 4f),
                            )
                        }
                    }
                }
            }
        }
    }

    if (createDialogOpen) {
        FolderNameDialog(
            title = stringResource(Res.string.create_folder_dialog_title),
            name = folderNameDraft,
            onNameChange = { folderNameDraft = it },
            confirmLabel = stringResource(Res.string.create_folder),
            confirmEnabled = folderNameDraft.trim().isNotBlank() && selectedDocuments.isNotEmpty(),
            onDismiss = { createDialogOpen = false },
            onConfirm = {
                onCreateFolder(folderNameDraft, selectedDocuments.map(DocumentMetadata::id))
                createDialogOpen = false
                selectedDocumentIds = emptySet()
                actionDocumentId = null
            },
        )
    }

    if (moveDialogOpen) {
        MoveToFolderDialog(
            folders = uiState.libraryFolders,
            onDismiss = { moveDialogOpen = false },
            onMove = { targetFolderId ->
                onMoveDocumentsToFolder(selectedDocuments.map(DocumentMetadata::id), targetFolderId)
                moveDialogOpen = false
                selectedDocumentIds = emptySet()
                actionDocumentId = null
            },
        )
    }

    editingFolder?.let { folder ->
        FolderNameDialog(
            title = stringResource(Res.string.rename_folder_dialog_title),
            name = folderNameDraft,
            onNameChange = { folderNameDraft = it },
            confirmLabel = stringResource(Res.string.rename_folder),
            confirmEnabled = folderNameDraft.trim().isNotBlank(),
            onDismiss = { editingFolder = null },
            onConfirm = {
                onRenameFolder(folder.id, folderNameDraft)
                editingFolder = null
            },
        )
    }

    deletingFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { deletingFolder = null },
            title = { Text(stringResource(Res.string.delete_folder_title)) },
            text = { Text(stringResource(Res.string.delete_folder_message, folder.name)) },
            confirmButton = {
                TeddButton(
                    text = stringResource(Res.string.delete),
                    onClick = {
                        onDeleteFolder(folder.id)
                        deletingFolder = null
                    },
                    emphasis = TeddButtonEmphasis.Destructive,
                )
            },
            dismissButton = {
                TeddButton(
                    text = stringResource(Res.string.cancel),
                    onClick = { deletingFolder = null },
                    emphasis = TeddButtonEmphasis.Secondary,
                )
            },
        )
    }

    if (pendingDeleteDocuments.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDeleteDocumentIds = emptySet() },
            title = { Text(stringResource(Res.string.remove_from_library_title)) },
            text = {
                Text(
                    if (pendingDeleteDocuments.size == 1) {
                        stringResource(
                            Res.string.remove_from_library_message,
                            pendingDeleteDocuments.single().location.displayName,
                        )
                    } else {
                        stringResource(
                            Res.string.remove_from_library_multiple_message,
                            pendingDeleteDocuments.size,
                        )
                    },
                )
            },
            confirmButton = {
                TeddButton(
                    text = stringResource(Res.string.delete),
                    onClick = {
                        val documentIds = pendingDeleteDocuments.map(DocumentMetadata::id)
                        pendingDeleteDocumentIds = emptySet()
                        selectedDocumentIds = selectedDocumentIds - documentIds.map(DocumentId::value).toSet()
                        onDeleteDocuments(documentIds)
                    },
                    emphasis = TeddButtonEmphasis.Destructive,
                )
            },
            dismissButton = {
                TeddButton(
                    text = stringResource(Res.string.cancel),
                    onClick = { pendingDeleteDocumentIds = emptySet() },
                    emphasis = TeddButtonEmphasis.Secondary,
                )
            },
        )
    }
}

/**
 * Flips whether [value] is a member of this set, used to add or remove a document id from the
 * current multi-selection with a single call at each tap site instead of a separate add/remove
 * branch.
 *
 * @receiver The selection set to toggle [value]'s membership in.
 * @param value The element to add if absent, or remove if present.
 * @return A new set with [value]'s membership flipped; this set is left unchanged.
 */
private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

/**
 * Top bar shown in place of the library's normal title bar once one or more documents are
 * selected: a count of the selection, a back action that clears it, and an overflow menu for
 * creating a new folder from the selection or moving it into an existing one.
 *
 * @param selectedCount The number of currently selected documents, shown in the title.
 * @param hasFolders Whether any folder exists yet; disables the "move to folder" menu item when
 * false, since there is nowhere to move the selection to.
 * @param onBack Invoked when the user taps back, to clear the selection.
 * @param onCreateFolder Invoked when the user picks "create folder" from the overflow menu.
 * @param onMoveToFolder Invoked when the user picks "move to folder" from the overflow menu.
 * @param menuExpanded Whether the overflow menu is currently open.
 * @param onMenuExpandedChange Invoked when the overflow menu is opened or dismissed.
 */
@Composable
private fun LibrarySelectionTopBar(
    selectedCount: Int,
    hasFolders: Boolean,
    onBack: () -> Unit,
    onCreateFolder: () -> Unit,
    onMoveToFolder: () -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
) {
    TeddTopBar(
        title = stringResource(Res.string.files_in_folder_count, selectedCount),
        navigationIcon = {
            TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                Icon(imageVector = TeddIcons.Back, contentDescription = null)
            }
        },
        actions = {
            Box {
                TeddIconButton(
                    onClick = { onMenuExpandedChange(true) },
                    contentDescription = stringResource(Res.string.library),
                ) {
                    Icon(imageVector = TeddIcons.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) },
                ) {
                    TeddDropdownMenuItem(
                        text = stringResource(Res.string.create_folder),
                        onClick = onCreateFolder,
                    )
                    TeddDropdownMenuItem(
                        text = stringResource(Res.string.move_to_folder),
                        onClick = onMoveToFolder,
                        enabled = hasFolders,
                    )
                }
            }
        },
    )
}

/**
 * A single text-field dialog for entering a folder name, shared by both the create-folder and
 * rename-folder flows in [LibraryScreen] — [title] and [confirmLabel] carry the wording that
 * distinguishes the two call sites; the dialog itself has no notion of which flow it is in.
 *
 * @param title The dialog's title, distinguishing create from rename.
 * @param name The text field's current value.
 * @param onNameChange Invoked as the user types.
 * @param confirmLabel The confirm button's label, distinguishing create from rename.
 * @param confirmEnabled Whether the confirm button is enabled; false while the name is blank, or
 * (for creation) while no document is selected to seed the new folder with.
 * @param onDismiss Invoked when the dialog is dismissed without confirming.
 * @param onConfirm Invoked when the user confirms the entered name.
 */
@Composable
private fun FolderNameDialog(
    title: String,
    name: String,
    onNameChange: (String) -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TeddTextField(
                value = name,
                onValueChange = onNameChange,
                label = stringResource(Res.string.folder_name),
            )
        },
        confirmButton = {
            TeddButton(
                text = confirmLabel,
                onClick = onConfirm,
                enabled = confirmEnabled,
            )
        },
        dismissButton = {
            TeddButton(
                text = stringResource(Res.string.cancel),
                onClick = onDismiss,
                emphasis = TeddButtonEmphasis.Secondary,
            )
        },
    )
}

/**
 * Dialog listing every existing folder so the user can pick one to move the current selection
 * into; shows a fallback message instead of the list when no folder exists yet.
 *
 * @param folders The folders available to move documents into.
 * @param onDismiss Invoked when the dialog is dismissed without picking a folder.
 * @param onMove Invoked with the id of the folder the user picked.
 */
@Composable
private fun MoveToFolderDialog(
    folders: ImmutableList<LibraryFolder>,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.move_to_folder)) },
        text = {
            if (folders.isEmpty()) {
                Text(stringResource(Res.string.no_folders_available))
            } else {
                androidx.compose.foundation.layout.Column {
                    folders.forEachIndexed { index, folder ->
                        TeddListItem(
                            title = folder.name,
                            supportingText = stringResource(Res.string.folder_row_description, folder.documentCount),
                            onClick = { onMove(folder.id) },
                            showDivider = index != folders.lastIndex,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TeddButton(
                text = stringResource(Res.string.cancel),
                onClick = onDismiss,
                emphasis = TeddButtonEmphasis.Secondary,
            )
        },
    )
}

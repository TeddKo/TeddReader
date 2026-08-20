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

private val ScreenMaxWidth = 720.dp

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
        gridState = gridState,
        modifier = modifier,
    )
}

@Composable
fun LibraryScreen(
    uiState: HomeUiState,
    folderId: String?,
    onBack: () -> Unit,
    onDocumentClick: (DocumentId) -> Unit,
    onFolderClick: (String) -> Unit,
    onCreateFolder: (String, Collection<DocumentId>) -> String,
    onMoveDocumentsToFolder: (Collection<DocumentId>, String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onDocumentBookmarkChange: (DocumentId, Boolean) -> Unit,
    onDeleteDocuments: (Collection<DocumentId>) -> Unit,
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
        modifier = modifier.fillMaxSize(),
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

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

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

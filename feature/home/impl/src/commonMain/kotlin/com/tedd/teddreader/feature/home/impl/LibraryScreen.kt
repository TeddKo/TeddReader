package com.tedd.teddreader.feature.home.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.tedd.teddreader.core.ui.generated.resources.document_pages
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
import com.tedd.teddreader.core.ui.generated.resources.rename_folder
import com.tedd.teddreader.core.ui.generated.resources.rename_folder_dialog_title
import com.tedd.teddreader.core.ui.generated.resources.select_document
import com.tedd.teddreader.core.ui.icon.TeddIcons
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
    val listState = rememberLazyListState()

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
        listState = listState,
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
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(DefaultTeddReaderSpacing.screenPadding),
) {
    val spacing = teddReaderSpacing()
    val currentFolder = remember(uiState.libraryFolders, folderId) {
        uiState.libraryFolders.firstOrNull { it.id == folderId }
    }
    val documents = remember(uiState.libraryDocuments, folderId) {
        if (folderId == null) uiState.libraryDocuments else uiState.libraryDocuments.filter { it.folderId == folderId }
    }
    var mode by rememberSaveable(folderId) {
        mutableStateOf(if (folderId == null) LibraryCollectionMode.All else LibraryCollectionMode.All)
    }
    var selectedDocumentIds by rememberSaveable(folderId) { mutableStateOf<Set<String>>(emptySet()) }
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
        onBackCompleted = { selectedDocumentIds = emptySet() },
    )

    LaunchedEffect(documents) {
        val currentIds = documents.mapTo(hashSetOf()) { it.id.value }
        selectedDocumentIds = selectedDocumentIds.filterTo(linkedSetOf()) { it in currentIds }
    }

    val selectedDocuments = remember(documents, selectedDocumentIds) {
        documents.filter { it.id.value in selectedDocumentIds }
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
                    onBack = { selectedDocumentIds = emptySet() },
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
                if (folderId == null) {
                    item {
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
                            item {
                                TeddEmptyState(
                                    title = stringResource(Res.string.library_empty_folder_title),
                                    description = stringResource(Res.string.library_empty_folder_description),
                                )
                            }
                        } else {
                            items(uiState.libraryFolders, key = { it.id }) { folder ->
                                FolderRow(
                                    folder = folder,
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
                        item {
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
                        TeddListItem(
                            title = document.location.displayName,
                            supportingText = document.supportingText(),
                            onClick = {
                                if (selectedDocumentIds.isNotEmpty()) {
                                    selectedDocumentIds = selectedDocumentIds.toggle(document.id.value)
                                } else {
                                    onDocumentClick(document.id)
                                }
                            },
                            onLongClick = {
                                selectedDocumentIds = selectedDocumentIds.toggle(document.id.value)
                            },
                            trailingContent = {
                                if (document.id.value in selectedDocumentIds) {
                                    Text(
                                        text = stringResource(Res.string.select_document),
                                        color = MaterialTheme.colorScheme.primary,
                                        style = com.tedd.teddreader.core.designsystem.teddReaderTypography().documentMeta,
                                    )
                                }
                            },
                        )
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
}

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
private fun FolderRow(
    folder: LibraryFolder,
    onClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    TeddListItem(
        title = folder.name,
        supportingText = stringResource(Res.string.folder_row_description, folder.documentCount),
        onClick = onClick,
        trailingContent = {
            Box {
                TeddIconButton(
                    onClick = { menuExpanded = true },
                    contentDescription = folder.name,
                ) {
                    Icon(imageVector = TeddIcons.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    TeddDropdownMenuItem(
                        text = stringResource(Res.string.rename_folder),
                        onClick = {
                            menuExpanded = false
                            onRenameClick()
                        },
                    )
                    TeddDropdownMenuItem(
                        text = stringResource(Res.string.delete),
                        onClick = {
                            menuExpanded = false
                            onDeleteClick()
                        },
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
    folders: List<LibraryFolder>,
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
                Column {
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


private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value

@Composable
private fun DocumentMetadata.supportingText(): String = pageCount?.let { stringResource(Res.string.document_pages, it) } ?: format.name

package com.tedd.teddreader.feature.home.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import com.tedd.teddreader.core.designsystem.teddReaderBreakpoints
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.ui.component.TeddAlertDialog
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddButtonEmphasis
import com.tedd.teddreader.core.ui.component.TeddChip
import com.tedd.teddreader.core.ui.component.TeddDropdownMenu
import com.tedd.teddreader.core.ui.component.TeddDropdownMenuItem
import com.tedd.teddreader.core.ui.component.TeddEmptyState
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddSection
import com.tedd.teddreader.core.ui.component.TeddSectionKind
import com.tedd.teddreader.core.ui.component.TeddText
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
 * [HomeViewModel]을 [LibraryScreen]에 연결하는 진입점이다. reader 기능의 [ReaderRouteScreen]처럼 이
 * composable은 상태와 콜백만 뷰 모델에 전달한다. [HomeUiState]를 수집하고 그리드의 스크롤 상태를
 * hoist하며 모든 사용자 동작을 곧바로 뷰 모델 호출로 전달한다.
 *
 * @param folderId 문서를 표시할 폴더. null이면 전체 라이브러리를 표시한다.
 * @param onBack 사용자가 라이브러리 화면을 나가려고 할 때 호출한다.
 * @param onDocumentClick 사용자가 문서를 열 때 호출한다.
 * @param onFolderClick 사용자가 폴더를 열 때 호출한다.
 * @param modifier 생성되는 [LibraryScreen]에 적용할 modifier.
 * @param viewModel 홈 기능의 뷰 모델. 기본값은 Koin으로 해결한다.
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
 * 라이브러리의 문서/폴더 그리드를 렌더링한다. 전체 라이브러리 보기의 "All"/"Folders" 전환, [folderId]가
 * 설정됐을 때 해당 폴더의 문서 그리드, 전용 선택 top bar가 있는 다중 선택, 폴더 생성/이름 변경/삭제와
 * 문서 제거 dialog를 제공한다. [LibraryRouteScreen]처럼 이 composable은 상태와 콜백만 뷰 모델에
 * 전달한다. 여기서 선언한 `remember`/`rememberSaveable` 상태(선택, dialog 표시 여부, 폴더 이름 초안)는
 * 로컬 UI 장부이며 라이브러리 상태의 두 번째 사본이 아니다. 문서/폴더 그리드 자체의 너비는
 * [TeddReaderBreakpoints.readableMaxWidth][com.tedd.teddreader.core.designsystem.TeddReaderBreakpoints.readableMaxWidth]로
 * 제한한다. 따라서 태블릿이나 펼친 폴더블에서 카드가 화면 양끝까지 늘어나지 않고 읽기 좋은 크기를 유지한다.
 *
 * @param uiState 뷰 모델이 발행한 홈 기능의 현재 상태.
 * @param folderId 문서를 표시할 폴더. null이면 전체 라이브러리.
 * @param onBack 사용자가 라이브러리 화면을 나가려고 할 때 호출한다.
 * @param onDocumentClick 선택 모드 밖에서 사용자가 문서를 열 때 호출한다.
 * @param onFolderClick 사용자가 "Folders" tab에서 폴더를 열 때 호출한다.
 * @param onCreateFolder 새 폴더 이름과 처음 넣을 문서를 전달해 호출한다.
 * @param onMoveDocumentsToFolder 이동할 문서와 대상 폴더 id를 전달해 호출한다.
 * @param onRenameFolder 폴더 id와 새 이름을 전달해 호출한다.
 * @param onDeleteFolder 삭제할 폴더 id를 전달해 호출한다.
 * @param onDocumentBookmarkChange 문서 id와 새 즐겨찾기 상태를 전달해 호출한다.
 * @param onDeleteDocuments 사용자가 라이브러리에서 제거하기로 확인한 문서를 전달해 호출한다.
 * @param gridState 호출자가 스크롤 위치를 제어하거나 관찰할 수 있도록 hoist한 문서/폴더 그리드 스크롤 상태.
 * @param modifier 화면 root에 적용할 modifier.
 * @param contentPadding 그리드 첫 행 위와 마지막 행 아래에 둘 세로 padding. null이면 양쪽 모두 테마의
 *   screenPadding을 사용한다. 가로 inset은 이 매개변수가 아니라 그리드 자체 modifier와 All/Folders
 *   selector의 [TeddSection]이 담당한다.
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
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val breakpoints = teddReaderBreakpoints()
    val resolvedContentPadding = contentPadding ?: PaddingValues(vertical = spacing.screenPadding)
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
                            TeddIcon(imageVector = TeddIcons.Back, contentDescription = null)
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
                tabletMinWidth = breakpoints.medium,
            )
            val useAdaptiveGrid = shortestSide >= breakpoints.medium || (displayFold?.isVertical == true && displayFold.isSeparating)

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = if (useAdaptiveGrid) GridCells.Adaptive(140.dp) else GridCells.Fixed(2),
                    modifier = Modifier
                        .widthIn(max = breakpoints.readableMaxWidth)
                        .fillMaxSize()
                        .padding(horizontal = spacing.screenPadding),
                    contentPadding = resolvedContentPadding,
                    horizontalArrangement = Arrangement.spacedBy(spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(spacing.medium),
                ) {
                    if (folderId == null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            TeddSection(kind = TeddSectionKind.Form, fullBleed = true) {
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
                                        singleClick = true,
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
                                singleClick = selectedDocumentIds.isEmpty(),
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
        TeddAlertDialog(
            onDismissRequest = { deletingFolder = null },
            title = stringResource(Res.string.delete_folder_title),
            text = { TeddText(text = stringResource(Res.string.delete_folder_message, folder.name)) },
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
        TeddAlertDialog(
            onDismissRequest = { pendingDeleteDocumentIds = emptySet() },
            title = stringResource(Res.string.remove_from_library_title),
            text = {
                TeddText(
                    text = if (pendingDeleteDocuments.size == 1) {
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
 * [value]가 이 집합의 원소인지 여부를 뒤집는다. 각 tap 지점에서 별도의 추가/제거 분기 없이 한 번의 호출로
 * 현재 다중 선택에 문서 id를 추가하거나 제거할 때 사용한다.
 *
 * @receiver [value]의 소속을 전환할 선택 집합.
 * @param value 없으면 추가하고 있으면 제거할 원소.
 * @return [value]의 소속을 뒤집은 새 집합. 현재 집합은 변경하지 않는다.
 */
private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

/**
 * 하나 이상의 문서를 선택하면 라이브러리의 일반 title bar 대신 표시하는 top bar다. 선택 개수, 선택을
 * 지우는 뒤로 가기 동작, 선택 항목으로 새 폴더를 만들거나 기존 폴더로 옮기는 더보기 메뉴를 제공한다.
 *
 * @param selectedCount 현재 선택한 문서 수. 제목에 표시한다.
 * @param hasFolders 폴더가 하나라도 존재하는지 여부. false이면 선택 항목을 옮길 곳이 없으므로
 *   "move to folder" 메뉴 항목을 비활성화한다.
 * @param onBack 사용자가 뒤로 가기를 눌러 선택을 지울 때 호출한다.
 * @param onCreateFolder 사용자가 더보기 메뉴에서 "create folder"를 선택할 때 호출한다.
 * @param onMoveToFolder 사용자가 더보기 메뉴에서 "move to folder"를 선택할 때 호출한다.
 * @param menuExpanded 더보기 메뉴가 현재 열려 있는지 여부.
 * @param onMenuExpandedChange 더보기 메뉴가 열리거나 닫힐 때 호출한다.
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
                TeddIcon(imageVector = TeddIcons.Back, contentDescription = null)
            }
        },
        actions = {
            Box {
                TeddIconButton(
                    onClick = { onMenuExpandedChange(true) },
                    contentDescription = stringResource(Res.string.library),
                ) {
                    TeddIcon(imageVector = TeddIcons.MoreVert, contentDescription = null)
                }
                TeddDropdownMenu(
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
 * 폴더 이름을 입력하는 단일 text field dialog로, [LibraryScreen]의 폴더 생성과 이름 변경 flow가 공유한다.
 * [title]과 [confirmLabel]이 두 호출 지점을 구분하는 문구를 전달하며 dialog 자체는 어느 flow인지 알지 못한다.
 *
 * @param title 생성과 이름 변경을 구분하는 dialog 제목.
 * @param name text field의 현재 값.
 * @param onNameChange 사용자가 입력할 때 호출한다.
 * @param confirmLabel 생성과 이름 변경을 구분하는 확인 button 레이블.
 * @param confirmEnabled 확인 button의 활성화 여부. 이름이 비어 있거나 생성 시 새 폴더에 넣을 문서를 선택하지
 *   않았으면 false.
 * @param onDismiss 확인하지 않고 dialog를 닫을 때 호출한다.
 * @param onConfirm 사용자가 입력한 이름을 확인할 때 호출한다.
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
    TeddAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
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
 * 사용자가 현재 선택 항목을 옮길 폴더를 고를 수 있도록 기존 폴더를 모두 나열하는 dialog다. 아직 폴더가
 * 없으면 목록 대신 대체 메시지를 표시한다.
 *
 * @param folders 문서를 옮길 수 있는 폴더.
 * @param onDismiss 폴더를 선택하지 않고 dialog를 닫을 때 호출한다.
 * @param onMove 사용자가 선택한 폴더 id를 전달해 호출한다.
 */
@Composable
private fun MoveToFolderDialog(
    folders: ImmutableList<LibraryFolder>,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
) {
    TeddAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.move_to_folder),
        text = {
            if (folders.isEmpty()) {
                TeddText(text = stringResource(Res.string.no_folders_available))
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

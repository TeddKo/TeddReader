package com.tedd.teddreader.feature.home.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderMotion
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddButtonEmphasis
import com.tedd.teddreader.core.ui.component.TeddCard
import com.tedd.teddreader.core.ui.component.TeddChip
import com.tedd.teddreader.core.ui.component.TeddEmptyState
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddFullScreenLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.add_documents
import com.tedd.teddreader.core.ui.generated.resources.add_to_favorites
import com.tedd.teddreader.core.ui.generated.resources.all
import com.tedd.teddreader.core.ui.generated.resources.back
import com.tedd.teddreader.core.ui.generated.resources.cancel
import com.tedd.teddreader.core.ui.generated.resources.cloud_documents
import com.tedd.teddreader.core.ui.generated.resources.delete
import com.tedd.teddreader.core.ui.generated.resources.document_pages
import com.tedd.teddreader.core.ui.generated.resources.favorite_documents_count
import com.tedd.teddreader.core.ui.generated.resources.favorite_documents_single
import com.tedd.teddreader.core.ui.generated.resources.favorites
import com.tedd.teddreader.core.ui.generated.resources.files_in_folder_count
import com.tedd.teddreader.core.ui.generated.resources.folder_row_description
import com.tedd.teddreader.core.ui.generated.resources.folders
import com.tedd.teddreader.core.ui.generated.resources.format
import com.tedd.teddreader.core.ui.generated.resources.google_drive
import com.tedd.teddreader.core.ui.generated.resources.google_drive_description
import com.tedd.teddreader.core.ui.generated.resources.home_add_documents_description
import com.tedd.teddreader.core.ui.generated.resources.home_loading_recent_documents
import com.tedd.teddreader.core.ui.generated.resources.home_no_documents_description
import com.tedd.teddreader.core.ui.generated.resources.home_no_documents_title
import com.tedd.teddreader.core.ui.generated.resources.home_no_matching_documents
import com.tedd.teddreader.core.ui.generated.resources.home_no_matching_documents_description
import com.tedd.teddreader.core.ui.generated.resources.home_selection_count
import com.tedd.teddreader.core.ui.generated.resources.library
import com.tedd.teddreader.core.ui.generated.resources.library_empty_folder_description
import com.tedd.teddreader.core.ui.generated.resources.library_empty_folder_title
import com.tedd.teddreader.core.ui.generated.resources.library_preview_description
import com.tedd.teddreader.core.ui.generated.resources.local_documents
import com.tedd.teddreader.core.ui.generated.resources.masthead_description
import com.tedd.teddreader.core.ui.generated.resources.recent
import com.tedd.teddreader.core.ui.generated.resources.recent_reading
import com.tedd.teddreader.core.ui.generated.resources.recent_reading_description
import com.tedd.teddreader.core.ui.generated.resources.remove_from_favorites
import com.tedd.teddreader.core.ui.generated.resources.remove_from_library_message
import com.tedd.teddreader.core.ui.generated.resources.remove_from_library_multiple_message
import com.tedd.teddreader.core.ui.generated.resources.remove_from_library_title
import com.tedd.teddreader.core.ui.generated.resources.select_document
import com.tedd.teddreader.core.ui.generated.resources.select_files
import com.tedd.teddreader.core.ui.generated.resources.select_files_description
import com.tedd.teddreader.core.ui.generated.resources.select_folder
import com.tedd.teddreader.core.ui.generated.resources.select_folder_description
import com.tedd.teddreader.core.ui.generated.resources.settings
import com.tedd.teddreader.core.ui.generated.resources.show_all
import com.tedd.teddreader.core.ui.generated.resources.sort
import com.tedd.teddreader.core.ui.generated.resources.title
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.system.rememberDisplayFold
import com.tedd.teddreader.feature.home.impl.component.DocumentCard
import com.tedd.teddreader.feature.home.impl.component.FolderCoverCard
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HomeRouteScreen(
    modifier: Modifier = Modifier,
    importMessage: String? = null,
    onOpenFilesClick: () -> Unit = {},
    onOpenFolderClick: () -> Unit = {},
    onOpenGoogleDriveClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit = {},
    onDocumentClick: (DocumentId) -> Unit = {},
    onOpenLibraryClick: () -> Unit = {},
    onOpenLibraryFolderClick: (String) -> Unit = {},
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    HomeScreen(
        uiState = uiState.copy(
            unsupportedFormatMessage = importMessage ?: uiState.unsupportedFormatMessage,
        ),
        onOpenFilesClick = onOpenFilesClick,
        onOpenFolderClick = onOpenFolderClick,
        onOpenGoogleDriveClick = onOpenGoogleDriveClick,
        onSettingsClick = onSettingsClick,
        onDocumentClick = onDocumentClick,
        onOpenLibraryClick = onOpenLibraryClick,
        onOpenLibraryFolderClick = onOpenLibraryFolderClick,
        onDocumentBookmarkChange = viewModel::setDocumentBookmarked,
        onSelectionBookmarkChange = { documentIds, target ->
            viewModel.setDocumentsBookmarked(documentIds, target)
        },
        onDeleteDocuments = viewModel::deleteDocuments,
        onSortChange = viewModel::updateSort,
        onFormatFilterChange = viewModel::updateFormatFilter,
        scrollState = scrollState,
        modifier = modifier,
    )
}

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onOpenFilesClick: () -> Unit,
    onOpenFolderClick: () -> Unit,
    onOpenGoogleDriveClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit,
    onDocumentClick: (DocumentId) -> Unit,
    scrollState: ScrollState,
    onOpenLibraryClick: () -> Unit = {},
    onOpenLibraryFolderClick: (String) -> Unit = {},
    onDocumentBookmarkChange: (DocumentId, Boolean) -> Unit = { _, _ -> },
    onSelectionBookmarkChange: (Collection<DocumentId>, Boolean) -> Unit = { _, _ -> },
    onDeleteDocuments: (Collection<DocumentId>) -> Unit = {},
    onSortChange: (HomeSort) -> Unit = {},
    onFormatFilterChange: (HomeFormatFilter) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(all = DefaultTeddReaderSpacing.screenPadding),
) {
    val spacing = teddReaderSpacing()
    val motion = teddReaderMotion()
    val displayFold = rememberDisplayFold()
    var actionDocumentTarget by remember { mutableStateOf<HomeDocumentActionTarget?>(null) }
    var pendingDeleteDocumentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedDocumentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var previewMode by rememberSaveable { mutableStateOf(LibraryCollectionMode.All) }
    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)
    val visibleDocumentIds = remember(
        uiState.favoriteDocuments,
        uiState.recentDocuments,
        uiState.libraryDocuments,
    ) {
        buildSet {
            uiState.favoriteDocuments.forEach { add(it.id.value) }
            uiState.recentDocuments.forEach { add(it.id.value) }
            uiState.libraryDocuments.forEach { add(it.id.value) }
        }
    }

    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectedDocumentIds.isNotEmpty(),
        onBackCompleted = {
            selectedDocumentIds = emptySet()
        },
    )

    LaunchedEffect(visibleDocumentIds) {
        selectedDocumentIds = selectedDocumentIds.filterTo(linkedSetOf()) { it in uiState.libraryDocuments.map(DocumentMetadata::id).map(DocumentId::value).toSet() }
        pendingDeleteDocumentIds = pendingDeleteDocumentIds.filterTo(linkedSetOf()) { it in uiState.libraryDocuments.map(DocumentMetadata::id).map(DocumentId::value).toSet() }
        if (actionDocumentTarget?.documentId !in uiState.libraryDocuments.map(DocumentMetadata::id).map(DocumentId::value).toSet()) {
            actionDocumentTarget = null
        }
    }

    if (uiState.isLoading) {
        TeddFullScreenLoadingIndicator(
            modifier = modifier,
            message = stringResource(Res.string.home_loading_recent_documents),
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        AnimatedVisibility(
            visible = selectedDocumentIds.isNotEmpty(),
            enter = fadeIn(tween(motion.mediumDurationMs)) +
                slideInVertically(tween(motion.mediumDurationMs)) { -it },
            exit = fadeOut(tween(motion.shortDurationMs)) +
                slideOutVertically(tween(motion.shortDurationMs)) { -it },
        ) {
            val selectedDocuments = uiState.libraryDocuments.filter { it.id.value in selectedDocumentIds }
            val bookmarkTarget = homeSelectionBookmarkTarget(selectedDocuments)
            SelectionTopBar(
                selectedCount = selectedDocumentIds.size,
                bookmarkTarget = bookmarkTarget,
                onCancelClick = {
                    selectedDocumentIds = emptySet()
                },
                onBookmarkClick = {
                    val documentIds = selectedDocumentIds.map(::DocumentId)
                    actionDocumentTarget = null
                    selectedDocumentIds = emptySet()
                    onSelectionBookmarkChange(documentIds, bookmarkTarget)
                },
                onDeleteClick = {
                    actionDocumentTarget = null
                    pendingDeleteDocumentIds = selectedDocumentIds
                },
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val shortestDp = if (maxWidth <= maxHeight) maxWidth else maxHeight
            val previewLimit = libraryPreviewLimit(
                shortestSide = shortestDp,
                displayFold = displayFold,
            )
            val previewDocuments = remember(uiState.libraryDocuments, previewLimit) {
                homeLibraryPreviewDocuments(uiState.libraryDocuments, previewLimit)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(spacing.large),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(contentPadding),
                    verticalArrangement = Arrangement.spacedBy(spacing.large),
                ) {
                    HomeMasthead(
                        showAddAction = uiState.hasDocuments,
                        onAddDocumentsClick = { showAddDialog = true },
                        onSettingsClick = onSettingsClick,
                    )

                    uiState.errorMessage?.let { TeddErrorBanner(message = it) }
                    uiState.unsupportedFormatMessage?.let { TeddErrorBanner(message = it) }

                    when {
                        !uiState.hasDocuments -> {
                            TeddEmptyState(
                                title = stringResource(Res.string.home_no_documents_title),
                                description = stringResource(Res.string.home_no_documents_description),
                                modifier = Modifier.fillMaxWidth(),
                                action = {
                                    TeddButton(
                                        text = stringResource(Res.string.add_documents),
                                        onClick = { showAddDialog = true },
                                    )
                                },
                            )
                        }

                        else -> {
                            HomeSortFilterControls(
                                sort = uiState.sort,
                                formatFilter = uiState.formatFilter,
                                onSortChange = onSortChange,
                                onFormatFilterChange = onFormatFilterChange,
                            )
                            if (uiState.libraryDocuments.isEmpty()) {
                                HomeFilteredEmptyState(
                                    onShowAllClick = { onFormatFilterChange(HomeFormatFilter.All) },
                                )
                            }
                        }
                    }
                }

                if (uiState.favoriteDocuments.isNotEmpty()) {
                    HomeDocumentSection(
                        section = HomeDocumentSection.Favorites,
                        title = stringResource(Res.string.favorites),
                        description = if (uiState.favoriteDocuments.size == 1) {
                            stringResource(Res.string.favorite_documents_single)
                        } else {
                            stringResource(Res.string.favorite_documents_count, uiState.favoriteDocuments.size)
                        },
                        documents = uiState.favoriteDocuments,
                        actionDocumentTarget = actionDocumentTarget,
                        showFavoriteIcon = true,
                        onDocumentClick = onDocumentClick,
                        onShowActions = {
                            actionDocumentTarget = HomeDocumentActionTarget(HomeDocumentSection.Favorites, it)
                        },
                        onDismissActions = { actionDocumentTarget = null },
                        onBookmarkClick = { document ->
                            actionDocumentTarget = null
                            onDocumentBookmarkChange(document.id, false)
                        },
                        onDeleteClick = { document ->
                            actionDocumentTarget = null
                            pendingDeleteDocumentIds = setOf(document.id.value)
                        },
                        documentCoverImages = uiState.documentCoverImages,
                    )
                }

                if (uiState.recentDocuments.isNotEmpty()) {
                    HomeDocumentSection(
                        section = HomeDocumentSection.Recent,
                        title = stringResource(Res.string.recent_reading),
                        description = stringResource(Res.string.recent_reading_description),
                        documents = uiState.recentDocuments,
                        actionDocumentTarget = actionDocumentTarget,
                        onDocumentClick = onDocumentClick,
                        onShowActions = {
                            actionDocumentTarget = HomeDocumentActionTarget(HomeDocumentSection.Recent, it)
                        },
                        onDismissActions = { actionDocumentTarget = null },
                        onBookmarkClick = { document ->
                            actionDocumentTarget = null
                            onDocumentBookmarkChange(document.id, true)
                        },
                        onDeleteClick = { document ->
                            actionDocumentTarget = null
                            pendingDeleteDocumentIds = setOf(document.id.value)
                        },
                        modifier = Modifier,
                        documentCoverImages = uiState.documentCoverImages,
                    )
                }

                if (uiState.libraryDocuments.isNotEmpty()) {
                    HomeLibraryPreviewSection(
                        previewMode = previewMode,
                        onPreviewModeChange = { previewMode = it },
                        previewDocuments = previewDocuments,
                        allDocuments = uiState.libraryDocuments,
                        folders = uiState.libraryFolders,
                        previewLimit = previewLimit,
                        selectedDocumentIds = selectedDocumentIds,
                        actionDocumentTarget = actionDocumentTarget,
                        documentCoverImages = uiState.documentCoverImages,
                        onDocumentClick = { documentId ->
                            if (selectedDocumentIds.isNotEmpty()) {
                                actionDocumentTarget = null
                                selectedDocumentIds = selectedDocumentIds.toggle(documentId.value)
                            } else {
                                onDocumentClick(documentId)
                            }
                        },
                        onStartSelection = { documentId ->
                            actionDocumentTarget = null
                            selectedDocumentIds = selectedDocumentIds + documentId.value
                        },
                        onShowActions = {
                            actionDocumentTarget = HomeDocumentActionTarget(HomeDocumentSection.Library, it)
                        },
                        onDismissActions = { actionDocumentTarget = null },
                        onBookmarkClick = { document ->
                            actionDocumentTarget = null
                            onDocumentBookmarkChange(document.id, !document.isBookmarked)
                        },
                        onDeleteClick = { document ->
                            actionDocumentTarget = null
                            pendingDeleteDocumentIds = setOf(document.id.value)
                        },
                        onFolderClick = onOpenLibraryFolderClick,
                        onViewAllClick = onOpenLibraryClick,
                        modifier = Modifier.padding(bottom = DefaultTeddReaderSpacing.large),
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        HomeAddDocumentsDialog(
            onDismissRequest = { showAddDialog = false },
            onSelectFilesClick = {
                showAddDialog = false
                onOpenFilesClick()
            },
            onSelectFolderClick = {
                showAddDialog = false
                onOpenFolderClick()
            },
            onSelectGoogleDriveClick = onOpenGoogleDriveClick?.let {
                {
                    showAddDialog = false
                    it()
                }
            },
        )
    }

    val pendingDeleteDocuments = remember(uiState.libraryDocuments, pendingDeleteDocumentIds) {
        uiState.libraryDocuments.filter { it.id.value in pendingDeleteDocumentIds }
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

internal fun homeSelectionBookmarkTarget(selectedDocuments: Collection<DocumentMetadata>): Boolean =
    selectedDocuments.any { !it.isBookmarked }

@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    bookmarkTarget: Boolean,
    onCancelClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bookmarkDescription = if (bookmarkTarget) {
        stringResource(Res.string.add_to_favorites)
    } else {
        stringResource(Res.string.remove_from_favorites)
    }
    TeddTopBar(
        title = stringResource(Res.string.home_selection_count, selectedCount),
        modifier = modifier,
        navigationIcon = {
            TeddIconButton(
                onClick = onCancelClick,
                contentDescription = stringResource(Res.string.cancel),
            ) {
                Icon(imageVector = TeddIcons.Back, contentDescription = null)
            }
        },
        actions = {
            TeddIconButton(
                onClick = onBookmarkClick,
                contentDescription = bookmarkDescription,
            ) {
                Icon(
                    imageVector = if (bookmarkTarget) TeddIcons.BookmarkFilled else TeddIcons.BookmarkOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            TeddIconButton(
                onClick = onDeleteClick,
                contentDescription = stringResource(Res.string.delete),
            ) {
                Icon(
                    imageVector = TeddIcons.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

@Composable
private fun HomeSectionHeader(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    showFavoriteIcon: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showFavoriteIcon) {
            Icon(
                imageVector = TeddIcons.BookmarkFilled,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
        ) {
            Text(text = title, style = typography.titleMedium)
            Text(
                text = description,
                style = typography.documentMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        actions()
    }
}

@Composable
private fun HomeLibraryPreviewSection(
    previewMode: LibraryCollectionMode,
    onPreviewModeChange: (LibraryCollectionMode) -> Unit,
    previewDocuments: ImmutableList<DocumentMetadata>,
    allDocuments: ImmutableList<DocumentMetadata>,
    folders: ImmutableList<LibraryFolder>,
    previewLimit: Int,
    selectedDocumentIds: Set<String>,
    actionDocumentTarget: HomeDocumentActionTarget?,
    documentCoverImages: Map<String, ByteArray>,
    onDocumentClick: (DocumentId) -> Unit,
    onStartSelection: (DocumentId) -> Unit,
    onShowActions: (String) -> Unit,
    onDismissActions: () -> Unit,
    onBookmarkClick: (DocumentMetadata) -> Unit,
    onDeleteClick: (DocumentMetadata) -> Unit,
    onFolderClick: (String) -> Unit,
    onViewAllClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val previewFolders = remember(folders, previewLimit) { folders.take(previewLimit) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        HomeSectionHeader(
            title = stringResource(Res.string.library),
            description = stringResource(Res.string.library_preview_description),
            modifier = Modifier.padding(horizontal = DefaultTeddReaderSpacing.screenPadding),
            actions = {
                TeddButton(
                    text = stringResource(Res.string.show_all),
                    onClick = onViewAllClick,
                    emphasis = TeddButtonEmphasis.Secondary,
                )
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DefaultTeddReaderSpacing.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            TeddChip(
                text = stringResource(Res.string.all),
                selected = previewMode == LibraryCollectionMode.All,
                onClick = { onPreviewModeChange(LibraryCollectionMode.All) },
            )
            TeddChip(
                text = stringResource(Res.string.folders),
                selected = previewMode == LibraryCollectionMode.Folders,
                onClick = { onPreviewModeChange(LibraryCollectionMode.Folders) },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DefaultTeddReaderSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            val columns = if (previewLimit > 4) 4 else 2

            when (previewMode) {
                LibraryCollectionMode.All -> homeLibraryGridRows(previewDocuments, columns).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    ) {
                        row.forEach { document ->
                            if (document == null) {
                                Spacer(modifier = Modifier.weight(1f))
                            } else {
                                DocumentCard(
                                    document = document,
                                    coverImageBytes = documentCoverImages[document.id.value],
                                    selected = document.id.value in selectedDocumentIds,
                                    actionsExpanded = actionDocumentTarget == HomeDocumentActionTarget(
                                        HomeDocumentSection.Library,
                                        document.id.value,
                                    ),
                                    onClick = { onDocumentClick(document.id) },
                                    onLongClick = { onStartSelection(document.id) },
                                    onShowActions = { onShowActions(document.id.value) },
                                    onDismissActions = onDismissActions,
                                    onBookmarkClick = { onBookmarkClick(document) },
                                    onDeleteClick = { onDeleteClick(document) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(3f / 4f),
                                )
                            }
                        }
                    }
                }

                LibraryCollectionMode.Folders -> if (previewFolders.isEmpty()) {
                    TeddEmptyState(
                        title = stringResource(Res.string.library_empty_folder_title),
                        description = stringResource(Res.string.library_empty_folder_description),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    homeLibraryGridRows(previewFolders, columns).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.small),
                        ) {
                            row.forEach { folder ->
                                if (folder == null) {
                                    Spacer(modifier = Modifier.weight(1f))
                                } else {
                                    val folderPreviewDocuments = libraryFolderPreviewDocuments(
                                        documents = allDocuments,
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
                                        documentCoverImages = documentCoverImages,
                                        onClick = { onFolderClick(folder.id) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeDocumentSection(
    section: HomeDocumentSection,
    title: String,
    description: String,
    documents: ImmutableList<DocumentMetadata>,
    actionDocumentTarget: HomeDocumentActionTarget?,
    onDocumentClick: (DocumentId) -> Unit,
    onShowActions: (String) -> Unit,
    onDismissActions: () -> Unit,
    onBookmarkClick: (DocumentMetadata) -> Unit,
    onDeleteClick: (DocumentMetadata) -> Unit,
    modifier: Modifier = Modifier,
    documentCoverImages: Map<String, ByteArray> = emptyMap(),
    showFavoriteIcon: Boolean = false,
) {
    val spacing = teddReaderSpacing()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        HomeSectionHeader(
            title = title,
            description = description,
            showFavoriteIcon = showFavoriteIcon,
            modifier = Modifier.padding(horizontal = DefaultTeddReaderSpacing.screenPadding),
        )
        HomeDocumentPager(
            section = section,
            documents = documents,
            actionDocumentTarget = actionDocumentTarget,
            onDocumentClick = onDocumentClick,
            onShowActions = onShowActions,
            onDismissActions = onDismissActions,
            onBookmarkClick = onBookmarkClick,
            onDeleteClick = onDeleteClick,
            documentCoverImages = documentCoverImages,
        )
    }
}

@Composable
private fun HomeDocumentPager(
    section: HomeDocumentSection,
    documents: ImmutableList<DocumentMetadata>,
    actionDocumentTarget: HomeDocumentActionTarget?,
    onDocumentClick: (DocumentId) -> Unit,
    onShowActions: (String) -> Unit,
    onDismissActions: () -> Unit,
    onBookmarkClick: (DocumentMetadata) -> Unit,
    onDeleteClick: (DocumentMetadata) -> Unit,
    documentCoverImages: Map<String, ByteArray>,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val pagerState = rememberPagerState(pageCount = { documents.size })

    HorizontalPager(
        state = pagerState,
        modifier = modifier
            .fillMaxWidth()
            .height(HomeDocumentCardWidth * 4f / 3f),
        pageSize = PageSize.Fixed(HomeDocumentCardWidth),
        pageSpacing = spacing.medium,
        userScrollEnabled = documents.size > 1,
        contentPadding = PaddingValues(horizontal = DefaultTeddReaderSpacing.screenPadding),
    ) { page ->
        val document = documents[page]
        DocumentCard(
            document = document,
            coverImageBytes = documentCoverImages[document.id.value],
            // Favourites and recent reading are shortcuts into a book, not a place to manage the
            // library. Selecting here also duplicated a book that appears in both places, and the
            // bulk actions it offered all belong to the library section.
            selected = false,
            actionsExpanded = actionDocumentTarget == HomeDocumentActionTarget(section, document.id.value),
            onClick = { onDocumentClick(document.id) },
            onShowActions = { onShowActions(document.id.value) },
            onDismissActions = onDismissActions,
            onBookmarkClick = { onBookmarkClick(document) },
            onDeleteClick = { onDeleteClick(document) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val HomeDocumentCardWidth = 180.dp

@Composable
private fun HomeMasthead(
    showAddAction: Boolean,
    onAddDocumentsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Text(
            text = stringResource(Res.string.library),
            style = typography.documentMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "TeddReader",
            style = typography.headlineSmall,
        )
        Text(
            text = stringResource(Res.string.masthead_description),
            style = typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            if (showAddAction) {
                TeddButton(
                    text = stringResource(Res.string.add_documents),
                    onClick = onAddDocumentsClick,
                )
            }
            TeddButton(
                text = stringResource(Res.string.settings),
                onClick = onSettingsClick,
                emphasis = TeddButtonEmphasis.Secondary,
            )
        }
    }
}

@Composable
private fun HomeAddDocumentsDialog(
    onDismissRequest: () -> Unit,
    onSelectFilesClick: () -> Unit,
    onSelectFolderClick: () -> Unit,
    onSelectGoogleDriveClick: (() -> Unit)? = null,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val trailingIcon: @Composable RowScope.() -> Unit = {
        Icon(
            imageVector = TeddIcons.Next,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Res.string.add_documents)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
                Text(stringResource(Res.string.home_add_documents_description))

                Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                    Text(
                        text = stringResource(Res.string.local_documents),
                        style = typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TeddCard(modifier = Modifier.fillMaxWidth()) {
                        TeddListItem(
                            title = stringResource(Res.string.select_files),
                            supportingText = stringResource(Res.string.select_files_description),
                            onClick = onSelectFilesClick,
                            trailingContent = trailingIcon,
                        )
                        TeddListItem(
                            title = stringResource(Res.string.select_folder),
                            supportingText = stringResource(Res.string.select_folder_description),
                            onClick = onSelectFolderClick,
                            showDivider = false,
                            trailingContent = trailingIcon,
                        )
                    }
                }

                onSelectGoogleDriveClick?.let { onClick ->
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                        Text(
                            text = stringResource(Res.string.cloud_documents),
                            style = typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TeddCard(modifier = Modifier.fillMaxWidth()) {
                            TeddListItem(
                                title = stringResource(Res.string.google_drive),
                                supportingText = stringResource(Res.string.google_drive_description),
                                onClick = onClick,
                                showDivider = false,
                                trailingContent = trailingIcon,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

@Composable
private fun HomeFilteredEmptyState(
    onShowAllClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Text(
            text = stringResource(Res.string.home_no_matching_documents),
            style = typography.titleMedium,
        )
        Text(
            text = stringResource(Res.string.home_no_matching_documents_description),
            style = typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TeddButton(
            text = stringResource(Res.string.show_all),
            onClick = onShowAllClick,
            emphasis = TeddButtonEmphasis.Secondary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeSortFilterControls(
    sort: HomeSort,
    formatFilter: HomeFormatFilter,
    onSortChange: (HomeSort) -> Unit,
    onFormatFilterChange: (HomeFormatFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        HomeChipGroup(
            label = stringResource(Res.string.sort),
            style = typography.documentMeta,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            HomeSort.entries.forEach { option ->
                TeddChip(
                    text = option.chipLabel(),
                    selected = sort == option,
                    onClick = { onSortChange(option) },
                )
            }
        }
        HomeChipGroup(
            label = stringResource(Res.string.format),
            style = typography.documentMeta,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            HomeFormatFilter.entries.forEach { option ->
                TeddChip(
                    text = option.chipLabel(),
                    selected = formatFilter == option,
                    onClick = { onFormatFilterChange(option) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeChipGroup(
    label: String,
    style: androidx.compose.ui.text.TextStyle,
    contentColor: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit,
) {
    val spacing = teddReaderSpacing()

    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Text(
            text = label,
            style = style,
            color = contentColor,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            content()
        }
    }
}

@Composable
private fun HomeSort.chipLabel(): String = when (this) {
    HomeSort.Recent -> stringResource(Res.string.recent)
    HomeSort.Title -> stringResource(Res.string.title)
    HomeSort.Format -> stringResource(Res.string.format)
}

@Composable
private fun HomeFormatFilter.chipLabel(): String = when (this) {
    HomeFormatFilter.All -> stringResource(Res.string.all)
    HomeFormatFilter.Txt -> "TXT"
    HomeFormatFilter.Pdf -> "PDF"
    HomeFormatFilter.Epub -> "EPUB"
    HomeFormatFilter.Comic -> "CBZ"
    HomeFormatFilter.Image -> "IMAGE"
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

@Composable
private fun DocumentMetadata.supportingText(): String =
    pageCount?.let { stringResource(Res.string.document_pages, it) } ?: format.name

@Preview(widthDp = 280)
@Composable
private fun HomeScreenEmptyPreview() {
    TeddReaderTheme {
        HomeScreen(
            uiState = HomeUiState(
                isLoading = false,
                hasDocuments = false,
            ),
            onOpenFilesClick = {},
            onOpenFolderClick = {},
            onSettingsClick = {},
            onDocumentClick = {},
            scrollState = rememberScrollState(),
        )
    }
}

@Preview(widthDp = 280)
@Preview(widthDp = 360)
@Preview(widthDp = 840)
@Composable
private fun HomeScreenRecentPreview() {
    TeddReaderTheme {
        HomeScreen(
            uiState = HomeUiState(
                isLoading = false,
                hasDocuments = true,
                favoriteDocuments = persistentListOf(
                    DocumentMetadata(
                        id = DocumentId("preview-1"),
                        location = DocumentLocation(
                            sourceUri = "file:///preview.epub",
                            displayName = "미드나잇 에디토리얼 샘플 원고.epub",
                            sizeBytes = 2_400_000L,
                        ),
                        format = DocumentFormat.EPUB,
                        addedAtEpochMillis = 0L,
                        pageCount = 320,
                        isBookmarked = true,
                    ),
                ),
                recentDocuments = persistentListOf(
                    DocumentMetadata(
                        id = DocumentId("preview-2"),
                        location = DocumentLocation(
                            sourceUri = "file:///preview.pdf",
                            displayName = "A Very Long Korean File Name That Should Still Stay Calm On Narrow Screens.pdf",
                            sizeBytes = 420_000L,
                        ),
                        format = DocumentFormat.PDF,
                        addedAtEpochMillis = 0L,
                        pageCount = 48,
                    ),
                ),
                libraryDocuments = persistentListOf(
                    DocumentMetadata(
                        id = DocumentId("preview-2"),
                        location = DocumentLocation(
                            sourceUri = "file:///preview.pdf",
                            displayName = "A Very Long Korean File Name That Should Still Stay Calm On Narrow Screens.pdf",
                            sizeBytes = 420_000L,
                        ),
                        format = DocumentFormat.PDF,
                        addedAtEpochMillis = 0L,
                        pageCount = 48,
                    ),
                    DocumentMetadata(
                        id = DocumentId("preview-3"),
                        location = DocumentLocation(
                            sourceUri = "file:///preview.txt",
                            displayName = "Pocket essay.txt",
                            sizeBytes = 32_000L,
                        ),
                        format = DocumentFormat.TXT,
                        addedAtEpochMillis = 3_000L,
                        folderId = "folder-1",
                        folderName = "Weekend Reads",
                    ),
                ),
                libraryFolders = persistentListOf(LibraryFolder("folder-1", "Weekend Reads", 1)),
            ),
            onOpenFilesClick = {},
            onOpenFolderClick = {},
            onSettingsClick = {},
            onDocumentClick = {},
            scrollState = rememberScrollState(),
        )
    }
}

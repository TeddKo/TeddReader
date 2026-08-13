package com.tedd.teddreader.feature.home.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderMotion
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
import com.tedd.teddreader.core.ui.generated.resources.*
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.feature.home.impl.component.DocumentCard
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
    val allDocuments = uiState.favoriteDocuments + uiState.recentDocuments
    val visibleDocumentIds = remember(allDocuments) { allDocuments.map { it.id.value }.toSet() }
    var actionDocumentId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteDocumentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedDocumentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAddDialog by remember { mutableStateOf(false) }
    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectedDocumentIds.isNotEmpty(),
        onBackCompleted = { selectedDocumentIds = emptySet() },
    )

    LaunchedEffect(visibleDocumentIds) {
        selectedDocumentIds = selectedDocumentIds.filterTo(linkedSetOf()) { it in visibleDocumentIds }
        pendingDeleteDocumentIds = pendingDeleteDocumentIds.filterTo(linkedSetOf()) { it in visibleDocumentIds }
        if (actionDocumentId !in visibleDocumentIds) {
            actionDocumentId = null
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
            val selectedDocuments = allDocuments.filter { it.id.value in selectedDocumentIds }
            val bookmarkTarget = homeSelectionBookmarkTarget(selectedDocuments)
            SelectionTopBar(
                selectedCount = selectedDocumentIds.size,
                bookmarkTarget = bookmarkTarget,
                onCancelClick = { selectedDocumentIds = emptySet() },
                onBookmarkClick = {
                    val documentIds = selectedDocumentIds.map(::DocumentId)
                    actionDocumentId = null
                    selectedDocumentIds = emptySet()
                    onSelectionBookmarkChange(documentIds, bookmarkTarget)
                },
                onDeleteClick = {
                    actionDocumentId = null
                    pendingDeleteDocumentIds = selectedDocumentIds
                },
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
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

                AnimatedVisibility(
                    visible = uiState.errorMessage != null,
                    enter = fadeIn(tween(motion.mediumDurationMs)),
                    exit = fadeOut(tween(motion.shortDurationMs)),
                ) {
                    uiState.errorMessage?.let { TeddErrorBanner(message = it) }
                }
                AnimatedVisibility(
                    visible = uiState.unsupportedFormatMessage != null,
                    enter = fadeIn(tween(motion.mediumDurationMs)),
                    exit = fadeOut(tween(motion.shortDurationMs)),
                ) {
                    uiState.unsupportedFormatMessage?.let { TeddErrorBanner(message = it) }
                }

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

                        AnimatedVisibility(
                            visible = uiState.favoriteDocuments.isEmpty() && uiState.recentDocuments.isEmpty(),
                            enter = fadeIn(tween(motion.mediumDurationMs)),
                            exit = fadeOut(tween(motion.shortDurationMs)),
                        ) {
                            HomeFilteredEmptyState(
                                onShowAllClick = { onFormatFilterChange(HomeFormatFilter.All) },
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = uiState.favoriteDocuments.isNotEmpty(),
                enter = fadeIn(tween(motion.mediumDurationMs)),
                exit = fadeOut(tween(motion.shortDurationMs)),
            ) {
                HomeDocumentSection(
                    title = stringResource(Res.string.favorites),
                    description = if (uiState.favoriteDocuments.size == 1) {
                        stringResource(Res.string.favorite_documents_single)
                    } else {
                        stringResource(Res.string.favorite_documents_count, uiState.favoriteDocuments.size)
                    },
                    documents = uiState.favoriteDocuments,
                    actionDocumentId = actionDocumentId,
                    selectedDocumentIds = selectedDocumentIds,
                    showFavoriteIcon = true,
                    onDocumentClick = onDocumentClick,
                    onToggleSelection = { documentId ->
                        actionDocumentId = null
                        selectedDocumentIds = selectedDocumentIds.toggle(documentId.value)
                    },
                    onStartSelection = { documentId ->
                        actionDocumentId = null
                        selectedDocumentIds = selectedDocumentIds + documentId.value
                    },
                    onShowActions = { actionDocumentId = it },
                    onDismissActions = { actionDocumentId = null },
                    onBookmarkClick = { document ->
                        actionDocumentId = null
                        onDocumentBookmarkChange(document.id, false)
                    },
                    onDeleteClick = { document ->
                        actionDocumentId = null
                        pendingDeleteDocumentIds = setOf(document.id.value)
                    },
                    modifier = Modifier,
                    documentCoverImages = uiState.documentCoverImages,
                )
            }

            AnimatedVisibility(
                visible = uiState.recentDocuments.isNotEmpty(),
                enter = fadeIn(tween(motion.mediumDurationMs)),
                exit = fadeOut(tween(motion.shortDurationMs)),
            ) {
                HomeDocumentSection(
                    title = stringResource(Res.string.recent_reading),
                    description = stringResource(Res.string.recent_reading_description),
                    documents = uiState.recentDocuments,
                    actionDocumentId = actionDocumentId,
                    selectedDocumentIds = selectedDocumentIds,
                    onDocumentClick = onDocumentClick,
                    onToggleSelection = { documentId ->
                        actionDocumentId = null
                        selectedDocumentIds = selectedDocumentIds.toggle(documentId.value)
                    },
                    onStartSelection = { documentId ->
                        actionDocumentId = null
                        selectedDocumentIds = selectedDocumentIds + documentId.value
                    },
                    onShowActions = { actionDocumentId = it },
                    onDismissActions = { actionDocumentId = null },
                    onBookmarkClick = { document ->
                        actionDocumentId = null
                        onDocumentBookmarkChange(document.id, true)
                    },
                    onDeleteClick = { document ->
                        actionDocumentId = null
                        pendingDeleteDocumentIds = setOf(document.id.value)
                    },
                    modifier = Modifier.padding(bottom = DefaultTeddReaderSpacing.large),
                    documentCoverImages = uiState.documentCoverImages,
                )
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

    val pendingDeleteDocuments = remember(allDocuments, pendingDeleteDocumentIds) {
        allDocuments.filter { it.id.value in pendingDeleteDocumentIds }
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

@Preview(widthDp = 280)
@Composable
private fun SelectionTopBarPreview() {
    TeddReaderTheme {
        SelectionTopBar(
            selectedCount = 3,
            bookmarkTarget = true,
            onCancelClick = {},
            onBookmarkClick = {},
            onDeleteClick = {},
        )
    }
}

@Composable
private fun HomeSectionHeader(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    showFavoriteIcon: Boolean = false,
) {
    val spacing = teddReaderSpacing()
    val motion = teddReaderMotion()
    val typography = teddReaderTypography()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = showFavoriteIcon,
            enter = fadeIn(tween(motion.mediumDurationMs)),
            exit = fadeOut(tween(motion.shortDurationMs)),
        ) {
            Icon(
                imageVector = TeddIcons.BookmarkFilled,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxSmall)) {
            Text(text = title, style = typography.titleMedium)
            Text(
                text = description,
                style = typography.documentMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeDocumentSection(
    title: String,
    description: String,
    documents: List<DocumentMetadata>,
    actionDocumentId: String?,
    selectedDocumentIds: Set<String>,
    onDocumentClick: (DocumentId) -> Unit,
    onToggleSelection: (DocumentId) -> Unit,
    onStartSelection: (DocumentId) -> Unit,
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
            documents = documents,
            actionDocumentId = actionDocumentId,
            selectedDocumentIds = selectedDocumentIds,
            onDocumentClick = onDocumentClick,
            onToggleSelection = onToggleSelection,
            onStartSelection = onStartSelection,
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
    documents: List<DocumentMetadata>,
    actionDocumentId: String?,
    selectedDocumentIds: Set<String>,
    onDocumentClick: (DocumentId) -> Unit,
    onToggleSelection: (DocumentId) -> Unit,
    onStartSelection: (DocumentId) -> Unit,
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
        val selectionMode = selectedDocumentIds.isNotEmpty()
        DocumentCard(
            document = document,
            coverImageBytes = documentCoverImages[document.id.value],
            selected = document.id.value in selectedDocumentIds,
            actionsExpanded = actionDocumentId == document.id.value,
            onClick = {
                if (selectionMode) {
                    onToggleSelection(document.id)
                } else {
                    onDocumentClick(document.id)
                }
            },
            onLongClick = { onStartSelection(document.id) },
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
    val motion = teddReaderMotion()
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
            AnimatedVisibility(
                visible = showAddAction,
                enter = fadeIn(tween(motion.mediumDurationMs)),
                exit = fadeOut(tween(motion.shortDurationMs)),
            ) {
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
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

@Preview(widthDp = 280)
@Preview(widthDp = 360)
@Composable
private fun HomeAddDocumentsDialogPreview() {
    TeddReaderTheme {
        HomeAddDocumentsDialog(
            onDismissRequest = {},
            onSelectFilesClick = {},
            onSelectFolderClick = {},
            onSelectGoogleDriveClick = {},
        )
    }
}

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
                favoriteDocuments = listOf(
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
                recentDocuments = listOf(
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
                    ),
                ),
            ),
            onOpenFilesClick = {},
            onOpenFolderClick = {},
            onSettingsClick = {},
            onDocumentClick = {},
            scrollState = rememberScrollState(),
        )
    }
}

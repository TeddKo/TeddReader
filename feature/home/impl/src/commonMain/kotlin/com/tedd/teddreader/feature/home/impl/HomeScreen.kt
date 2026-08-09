package com.tedd.teddreader.feature.home.impl

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddButtonEmphasis
import com.tedd.teddreader.core.ui.component.TeddCard
import com.tedd.teddreader.core.ui.component.TeddChip
import com.tedd.teddreader.core.ui.component.TeddEmptyState
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddFullScreenLoadingIndicator
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.tedd.teddreader.feature.home.impl.component.DocumentListItem
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HomeRouteScreen(
    modifier: Modifier = Modifier,
    importMessage: String? = null,
    onOpenFileClick: () -> Unit = {},
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
        onOpenFileClick = onOpenFileClick,
        onSettingsClick = onSettingsClick,
        onDocumentClick = onDocumentClick,
        onDocumentBookmarkChange = viewModel::setDocumentBookmarked,
        onDeleteDocument = viewModel::deleteDocument,
        onSortChange = viewModel::updateSort,
        onFormatFilterChange = viewModel::updateFormatFilter,
        scrollState = scrollState,
        modifier = modifier,
    )
}

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onOpenFileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDocumentClick: (DocumentId) -> Unit,
    scrollState: ScrollState,
    onDocumentBookmarkChange: (DocumentId, Boolean) -> Unit = { _, _ -> },
    onDeleteDocument: (DocumentId) -> Unit = {},
    onSortChange: (HomeSort) -> Unit = {},
    onFormatFilterChange: (HomeFormatFilter) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(all = DefaultTeddReaderSpacing.screenPadding),
    listItemPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.screenPadding,
        vertical = DefaultTeddReaderSpacing.small,
    ),
) {
    val spacing = teddReaderSpacing()
    var actionDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteDocumentId by rememberSaveable { mutableStateOf<String?>(null) }

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
            .systemBarsPadding()
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
                showOpenFileAction = uiState.hasDocuments,
                onOpenFileClick = onOpenFileClick,
                onSettingsClick = onSettingsClick,
            )

            uiState.errorMessage?.let { message ->
                TeddErrorBanner(message = message)
            }
            uiState.unsupportedFormatMessage?.let { message ->
                TeddErrorBanner(message = message)
            }

            when {
                !uiState.hasDocuments -> {
                    TeddEmptyState(
                        title = stringResource(Res.string.home_no_documents_title),
                        description = stringResource(Res.string.home_no_documents_description),
                        modifier = Modifier.fillMaxWidth(),
                        action = {
                            TeddButton(
                                text = stringResource(Res.string.open_file),
                                onClick = onOpenFileClick,
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

                    if (uiState.favoriteDocuments.isEmpty() && uiState.recentDocuments.isEmpty()) {
                        HomeFilteredEmptyState(
                            onShowAllClick = { onFormatFilterChange(HomeFormatFilter.All) },
                        )
                    }
                }
            }
        }

        if (uiState.favoriteDocuments.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DefaultTeddReaderSpacing.screenPadding),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                HomeSectionHeader(
                    title = stringResource(Res.string.favorites),
                    description = if (uiState.favoriteDocuments.size == 1) {
                        stringResource(Res.string.favorite_documents_single)
                    } else {
                        stringResource(Res.string.favorite_documents_count, uiState.favoriteDocuments.size)
                    },
                    showFavoriteIcon = true,
                )
                TeddCard(modifier = Modifier.fillMaxWidth()) {
                    HomeDocumentList(
                        documents = uiState.favoriteDocuments,
                        actionDocumentId = actionDocumentId,
                        contentPadding = PaddingValues(
                            horizontal = DefaultTeddReaderSpacing.medium,
                            vertical = DefaultTeddReaderSpacing.small,
                        ),
                        onDocumentClick = onDocumentClick,
                        onShowActions = { actionDocumentId = it },
                        onDismissActions = { actionDocumentId = null },
                        onBookmarkClick = { document ->
                            actionDocumentId = null
                            onDocumentBookmarkChange(document.id, false)
                        },
                        onDeleteClick = { document ->
                            actionDocumentId = null
                            pendingDeleteDocumentId = document.id.value
                        },
                    )
                }
            }
        }

        if (uiState.recentDocuments.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                HomeSectionHeader(
                    title = stringResource(Res.string.recent_reading),
                    description = stringResource(Res.string.recent_reading_description),
                    modifier = Modifier.padding(horizontal = DefaultTeddReaderSpacing.screenPadding),
                )
                HomeDocumentList(
                    documents = uiState.recentDocuments,
                    actionDocumentId = actionDocumentId,
                    contentPadding = listItemPadding,
                    onDocumentClick = onDocumentClick,
                    onShowActions = { actionDocumentId = it },
                    onDismissActions = { actionDocumentId = null },
                    onBookmarkClick = { document ->
                        actionDocumentId = null
                        onDocumentBookmarkChange(document.id, true)
                    },
                    onDeleteClick = { document ->
                        actionDocumentId = null
                        pendingDeleteDocumentId = document.id.value
                    },
                )
            }
        }
    }

    val pendingDeleteDocument = pendingDeleteDocumentId?.let { documentId ->
        (uiState.favoriteDocuments + uiState.recentDocuments).firstOrNull { it.id.value == documentId }
    }
    if (pendingDeleteDocument != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteDocumentId = null },
            title = { Text(stringResource(Res.string.remove_from_library_title)) },
            text = {
                Text(
                    stringResource(Res.string.remove_from_library_message, pendingDeleteDocument.location.displayName),
                )
            },
            confirmButton = {
                TeddButton(
                    text = stringResource(Res.string.delete),
                    onClick = {
                        pendingDeleteDocumentId = null
                        onDeleteDocument(pendingDeleteDocument.id)
                    },
                    emphasis = TeddButtonEmphasis.Destructive,
                )
            },
            dismissButton = {
                TeddButton(
                    text = stringResource(Res.string.cancel),
                    onClick = { pendingDeleteDocumentId = null },
                    emphasis = TeddButtonEmphasis.Secondary,
                )
            },
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
private fun HomeDocumentList(
    documents: List<DocumentMetadata>,
    actionDocumentId: String?,
    contentPadding: PaddingValues,
    onDocumentClick: (DocumentId) -> Unit,
    onShowActions: (String) -> Unit,
    onDismissActions: () -> Unit,
    onBookmarkClick: (DocumentMetadata) -> Unit,
    onDeleteClick: (DocumentMetadata) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        documents.forEachIndexed { index, document ->
            DocumentListItem(
                document = document,
                onClick = { onDocumentClick(document.id) },
                actionsExpanded = actionDocumentId == document.id.value,
                onShowActions = { onShowActions(document.id.value) },
                onDismissActions = onDismissActions,
                onBookmarkClick = { onBookmarkClick(document) },
                onDeleteClick = { onDeleteClick(document) },
                contentPadding = contentPadding,
                showDivider = index < documents.lastIndex,
            )
        }
    }
}

@Composable
private fun HomeMasthead(
    showOpenFileAction: Boolean,
    onOpenFileClick: () -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            if (showOpenFileAction) {
                TeddButton(
                    text = stringResource(Res.string.open_file),
                    onClick = onOpenFileClick,
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

@Preview(widthDp = 280)
@Composable
private fun HomeScreenEmptyPreview() {
    TeddReaderTheme {
        HomeScreen(
            uiState = HomeUiState(
                isLoading = false,
                hasDocuments = false,
            ),
            onOpenFileClick = {},
            onSettingsClick = {},
            onDocumentClick = {},
            scrollState = rememberScrollState(),
        )
    }
}

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
                ),
            ),
            onOpenFileClick = {},
            onSettingsClick = {},
            onDocumentClick = {},
            scrollState = rememberScrollState(),
        )
    }
}

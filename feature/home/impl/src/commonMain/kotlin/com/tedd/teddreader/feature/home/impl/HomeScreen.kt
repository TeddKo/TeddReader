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
import com.tedd.teddreader.core.ui.teddString
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
            message = teddString("Loading recent documents", "최근 문서를 불러오는 중"),
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
                        title = teddString("No documents yet", "아직 문서가 없습니다"),
                        description = teddString("Open a TXT, PDF, or EPUB file from device.", "기기에서 TXT, PDF, EPUB 파일을 열어보세요."),
                        modifier = Modifier.fillMaxWidth(),
                        action = {
                            TeddButton(
                                text = teddString("Open file", "파일 열기"),
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
                    title = teddString("Favorites", "즐겨찾기"),
                    description = if (uiState.favoriteDocuments.size == 1) {
                        teddString("1 hand-picked document", "엄선한 문서 1개")
                    } else {
                        teddString(
                            "${uiState.favoriteDocuments.size} hand-picked documents",
                            "엄선한 문서 ${uiState.favoriteDocuments.size}개",
                        )
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
                    title = teddString("Recent reading", "최근 읽은 문서"),
                    description = teddString("Continue where you left off", "중단한 지점부터 이어 읽기"),
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
            title = { Text(teddString("Remove from library?", "라이브러리에서 삭제할까요?")) },
            text = {
                Text(
                    teddString(
                        "\"${pendingDeleteDocument.location.displayName}\" and its reading data will be removed from TeddReader. The original file will stay on your device.",
                        "\"${pendingDeleteDocument.location.displayName}\"와 해당 읽기 데이터가 TeddReader에서 삭제됩니다. 원본 파일은 기기에 그대로 남습니다.",
                    ),
                )
            },
            confirmButton = {
                TeddButton(
                    text = teddString("Delete", "삭제"),
                    onClick = {
                        pendingDeleteDocumentId = null
                        onDeleteDocument(pendingDeleteDocument.id)
                    },
                    emphasis = TeddButtonEmphasis.Destructive,
                )
            },
            dismissButton = {
                TeddButton(
                    text = teddString("Cancel", "취소"),
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
            text = teddString("Library", "라이브러리"),
            style = typography.documentMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "TeddReader",
            style = typography.headlineSmall,
        )
        Text(
            text = teddString("Open local TXT, PDF, and EPUB documents.", "로컬 TXT, PDF, EPUB 문서를 열어보세요."),
            style = typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            if (showOpenFileAction) {
                TeddButton(
                    text = teddString("Open file", "파일 열기"),
                    onClick = onOpenFileClick,
                )
            }
            TeddButton(
                text = teddString("Settings", "설정"),
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
            text = teddString("No matching documents", "조건에 맞는 문서가 없습니다"),
            style = typography.titleMedium,
        )
        Text(
            text = teddString("Try another format filter or show all documents.", "다른 형식 필터를 선택하거나 전체 문서를 표시해 보세요."),
            style = typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TeddButton(
            text = teddString("Show all", "전체 보기"),
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
            label = teddString("Sort", "정렬"),
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
            label = teddString("Format", "형식"),
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
    HomeSort.Recent -> teddString("Recent", "최근")
    HomeSort.Title -> teddString("Title", "제목")
    HomeSort.Format -> teddString("Format", "형식")
}

@Composable
private fun HomeFormatFilter.chipLabel(): String = when (this) {
    HomeFormatFilter.All -> teddString("All", "전체")
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

package com.tedd.teddreader.feature.home.impl

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.tedd.teddreader.core.ui.component.TeddChip
import com.tedd.teddreader.core.ui.component.TeddEmptyState
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddFullScreenLoadingIndicator
import com.tedd.teddreader.feature.home.impl.component.DocumentListItem
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HomeRouteScreen(
    modifier: Modifier = Modifier,
    importMessage: String? = null,
    onOpenFileClick: () -> Unit = {},
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
        onDocumentClick = onDocumentClick,
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
    onDocumentClick: (DocumentId) -> Unit,
    scrollState: ScrollState,
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
    val typography = teddReaderTypography()

    if (uiState.isLoading) {
        TeddFullScreenLoadingIndicator(
            modifier = modifier,
            message = "Loading recent documents",
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
                title = uiState.title,
                description = uiState.description,
                showOpenFileAction = uiState.hasDocuments,
                onOpenFileClick = onOpenFileClick,
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
                        title = "No documents yet",
                        description = "Open a TXT, PDF, or EPUB file from device.",
                        modifier = Modifier.fillMaxWidth(),
                        action = {
                            TeddButton(
                                text = "Open file",
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

                    if (uiState.recentDocuments.isEmpty()) {
                        HomeFilteredEmptyState(
                            onShowAllClick = { onFormatFilterChange(HomeFormatFilter.All) },
                        )
                    } else {
                        Text(
                            text = "Recent reading",
                            style = typography.documentMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (uiState.hasDocuments && uiState.recentDocuments.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.none),
            ) {
                uiState.recentDocuments.forEach { document ->
                    DocumentListItem(
                        document = document,
                        onClick = { onDocumentClick(document.id) },
                        contentPadding = listItemPadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeMasthead(
    title: String,
    description: String,
    showOpenFileAction: Boolean,
    onOpenFileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Text(
            text = "Library",
            style = typography.documentMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = typography.headlineSmall,
        )
        Text(
            text = description,
            style = typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showOpenFileAction) {
            TeddButton(
                text = "Open file",
                onClick = onOpenFileClick,
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
            text = "No matching documents",
            style = typography.titleMedium,
        )
        Text(
            text = "Try another format filter or show all documents.",
            style = typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TeddButton(
            text = "Show all",
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
            label = "Sort",
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
            label = "Format",
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

private fun HomeSort.chipLabel(): String = when (this) {
    HomeSort.Recent -> "Recent"
    HomeSort.Title -> "Title"
    HomeSort.Format -> "Format"
}

private fun HomeFormatFilter.chipLabel(): String = when (this) {
    HomeFormatFilter.All -> "All"
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
                recentDocuments = listOf(
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
                    ),
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
            onDocumentClick = {},
            scrollState = rememberScrollState(),
        )
    }
}

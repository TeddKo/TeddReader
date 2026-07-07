package com.tedd.teddreader.feature.home.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
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
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddCard
import com.tedd.teddreader.core.ui.component.TeddChip
import com.tedd.teddreader.core.ui.component.TeddEmptyState
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddFullScreenLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddSurface
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

    HomeScreen(
        uiState = uiState.copy(
            unsupportedFormatMessage = importMessage ?: uiState.unsupportedFormatMessage,
        ),
        onOpenFileClick = onOpenFileClick,
        onDocumentClick = onDocumentClick,
        onSortChange = viewModel::updateSort,
        onFormatFilterChange = viewModel::updateFormatFilter,
        modifier = modifier,
    )
}

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onOpenFileClick: () -> Unit,
    onDocumentClick: (DocumentId) -> Unit,
    onSortChange: (HomeSort) -> Unit = {},
    onFormatFilterChange: (HomeFormatFilter) -> Unit = {},
    modifier: Modifier = Modifier,
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

    TeddSurface(
        modifier = modifier
            .fillMaxSize()
            .safeContentPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(spacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.large),
        ) {
            TeddCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(spacing.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(spacing.medium),
                ) {
                    Text(
                        text = uiState.title,
                        style = typography.documentTitle,
                    )
                    Text(
                        text = uiState.description,
                        style = typography.settingDescription,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TeddButton(
                        text = "Open file",
                        onClick = onOpenFileClick,
                    )
                }
            }

            uiState.errorMessage?.let { message ->
                TeddErrorBanner(message = message)
            }
            uiState.unsupportedFormatMessage?.let { message ->
                TeddErrorBanner(message = message)
            }

            if (uiState.recentDocuments.isEmpty()) {
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
            } else {
                HomeSortFilterControls(
                    sort = uiState.sort,
                    formatFilter = uiState.formatFilter,
                    onSortChange = onSortChange,
                    onFormatFilterChange = onFormatFilterChange,
                )

                Text(
                    text = "Recent documents",
                    style = typography.settingTitle,
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    uiState.recentDocuments.forEach { document ->
                        DocumentListItem(
                            document = document,
                            onClick = { onDocumentClick(document.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSortFilterControls(
    sort: HomeSort,
    formatFilter: HomeFormatFilter,
    onSortChange: (HomeSort) -> Unit,
    onFormatFilterChange: (HomeFormatFilter) -> Unit,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    TeddCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Text(
                text = "Sort & filter",
                style = typography.settingTitle,
            )
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                    HomeSort.entries.forEach { option ->
                        TeddChip(
                            text = option.chipLabel(sort == option),
                            onClick = { onSortChange(option) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                    HomeFormatFilter.entries.forEach { option ->
                        TeddChip(
                            text = option.chipLabel(formatFilter == option),
                            onClick = { onFormatFilterChange(option) },
                        )
                    }
                }
            }
        }
    }
}

private fun HomeSort.chipLabel(selected: Boolean): String = when (this) {
    HomeSort.Recent -> if (selected) "Recent •" else "Recent"
    HomeSort.Title -> if (selected) "Title •" else "Title"
    HomeSort.Format -> if (selected) "Format •" else "Format"
}

private fun HomeFormatFilter.chipLabel(selected: Boolean): String = when (this) {
    HomeFormatFilter.All -> if (selected) "All •" else "All"
    HomeFormatFilter.Txt -> if (selected) "TXT •" else "TXT"
    HomeFormatFilter.Pdf -> if (selected) "PDF •" else "PDF"
    HomeFormatFilter.Epub -> if (selected) "EPUB •" else "EPUB"
}

@Preview
@Composable
private fun HomeScreenEmptyPreview() {
    TeddReaderTheme {
        HomeScreen(
            uiState = HomeUiState(isLoading = false),
            onOpenFileClick = {},
            onDocumentClick = {},
        )
    }
}

@Preview
@Composable
private fun HomeScreenRecentPreview() {
    TeddReaderTheme {
        HomeScreen(
            uiState = HomeUiState(
                isLoading = false,
                recentDocuments = listOf(
                    DocumentMetadata(
                        id = DocumentId("preview"),
                        location = DocumentLocation(
                            sourceUri = "file:///preview.epub",
                            displayName = "Preview Novel.epub",
                            sizeBytes = 2_400_000L,
                        ),
                        format = DocumentFormat.EPUB,
                        addedAtEpochMillis = 0L,
                        pageCount = 320,
                    ),
                ),
            ),
            onOpenFileClick = {},
            onDocumentClick = {},
        )
    }
}

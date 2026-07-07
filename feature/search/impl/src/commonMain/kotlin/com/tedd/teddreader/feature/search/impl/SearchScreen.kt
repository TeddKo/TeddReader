package com.tedd.teddreader.feature.search.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddCard
import com.tedd.teddreader.core.ui.component.TeddEmptyState
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddSearchField
import com.tedd.teddreader.core.ui.component.TeddSurface
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SearchRouteScreen(
    documentId: String,
    onBack: () -> Unit,
    onResultClick: (ReaderLocation) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(documentId) {
        viewModel.setDocument(documentId)
    }

    SearchScreen(
        uiState = uiState,
        onQueryChange = viewModel::updateQuery,
        onSearchClick = viewModel::search,
        onBack = onBack,
        onResultClick = onResultClick,
        modifier = modifier,
    )
}

@Composable
fun SearchScreen(
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    onBack: () -> Unit,
    onResultClick: (ReaderLocation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

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
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xSmall)) {
                        Text(
                            text = "Find in document",
                            style = typography.documentTitle,
                        )
                        Text(
                            text = "Search the current document without leaving the reader flow.",
                            style = typography.settingDescription,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TeddButton(
                        text = "Back",
                        onClick = onBack,
                    )
                }
            }

            TeddCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(spacing.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(spacing.medium),
                ) {
                    TeddSearchField(
                        value = uiState.query,
                        onValueChange = onQueryChange,
                        placeholder = "Search text",
                    )
                    TeddButton(
                        text = "Search",
                        onClick = onSearchClick,
                    )
                }
            }

            uiState.unsupportedMessage?.let {
                TeddErrorBanner(message = it)
            }
            uiState.errorMessage?.let {
                TeddErrorBanner(message = it)
            }

            when {
                uiState.isLoading -> {
                    TeddLoadingIndicator(message = "Searching")
                }

                uiState.query.isBlank() -> {
                    TeddEmptyState(
                        title = "Search your document",
                        description = "Type a word or phrase to find passages.",
                    )
                }

                uiState.results.isEmpty() -> {
                    TeddEmptyState(
                        title = "No results",
                        description = "Try a different search term.",
                    )
                }

                else -> {
                    Text(
                        text = "${uiState.results.size} results",
                        style = typography.settingTitle,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                        uiState.results.forEach { result ->
                            TeddListItem(
                                title = result.snippet,
                                supportingText = result.location.asStorageString(),
                                onClick = { onResultClick(result.location) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun SearchScreenPreview() {
    TeddReaderTheme {
        SearchScreen(
            uiState = SearchUiState(
                documentId = "preview",
                query = "reader",
                results = listOf(
                    SearchResult(
                        documentId = DocumentId("preview"),
                        query = "reader",
                        location = ReaderLocation.TextOffset(10L),
                        snippet = "Reader search result preview",
                        range = TextRange(10L, 16L),
                    ),
                ),
            ),
            onQueryChange = {},
            onSearchClick = {},
            onBack = {},
            onResultClick = {},
        )
    }
}

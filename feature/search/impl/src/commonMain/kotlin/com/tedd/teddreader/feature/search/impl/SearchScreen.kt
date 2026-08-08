package com.tedd.teddreader.feature.search.impl

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddEmptyState
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.icon.TeddIcons
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
    val scrollState = rememberScrollState()

    LaunchedEffect(documentId) {
        viewModel.setDocument(documentId)
    }

    SearchScreen(
        uiState = uiState,
        onQueryChange = viewModel::updateQuery,
        onSearchClick = viewModel::search,
        onBack = onBack,
        onResultClick = onResultClick,
        scrollState = scrollState,
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
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(DefaultTeddReaderSpacing.screenPadding),
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val isFieldEnabled = uiState.unsupportedMessage == null && !uiState.isLoading
    val canSearch = isFieldEnabled && uiState.query.isNotBlank()

    TeddScaffold(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
        topBar = {
            TeddTopBar(
                title = "Search",
                navigationIcon = {
                    TeddIconButton(
                        onClick = onBack,
                        contentDescription = "Back",
                    ) {
                        Icon(
                            imageVector = TeddIcons.Back,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(scaffoldPadding)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.large),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                Text(
                    text = "Find a passage in the current document.",
                    style = typography.settingDescription,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search text") },
                    singleLine = true,
                    enabled = isFieldEnabled,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { if (canSearch) onSearchClick() },
                    ),
                )
                TeddButton(
                    text = "Search",
                    onClick = onSearchClick,
                    enabled = canSearch,
                )
            }

            when {
                uiState.unsupportedMessage != null -> {
                    TeddErrorBanner(message = uiState.unsupportedMessage)
                }

                uiState.errorMessage != null -> {
                    TeddErrorBanner(message = uiState.errorMessage)
                }

                uiState.isLoading -> {
                    TeddLoadingIndicator(message = "Searching")
                }

                uiState.query.isBlank() -> {
                    TeddEmptyState(
                        title = "Search this document",
                        description = "Type a word or phrase to jump to matching passages.",
                    )
                }

                uiState.results.isEmpty() -> {
                    TeddEmptyState(
                        title = "No results",
                        description = "Try a different word or a shorter phrase.",
                    )
                }

                else -> {
                    Text(
                        text = "${uiState.results.size} matches",
                        style = typography.settingTitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                        uiState.results.forEach { result ->
                            TeddListItem(
                                title = result.sectionTitle?.takeIf { it.isNotBlank() } ?: result.snippet,
                                supportingText = buildSearchSupportingText(result),
                                onClick = { onResultClick(result.location) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildSearchSupportingText(result: SearchResult): String = buildList {
    if (result.sectionTitle?.isNotBlank() == true) add(result.snippet)
    add(result.location.asStorageString())
}.joinToString("\n")

@Preview(widthDp = 280)
@Preview(widthDp = 360)
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
                        snippet = "Reader search result preview with a longer snippet that still wraps cleanly.",
                        sectionTitle = "Opening section",
                        range = TextRange(10L, 16L),
                    ),
                ),
            ),
            onQueryChange = {},
            onSearchClick = {},
            onBack = {},
            onResultClick = {},
            scrollState = rememberScrollState(),
        )
    }
}

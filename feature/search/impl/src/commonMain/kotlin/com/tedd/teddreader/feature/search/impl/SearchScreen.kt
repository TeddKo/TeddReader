package com.tedd.teddreader.feature.search.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddSearchField
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.generated.resources.*
import com.tedd.teddreader.core.ui.icon.TeddIcons
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private val ScreenMaxWidth = 720.dp
private val CompactContentWidth = 320.dp

@Composable
fun SearchRouteScreen(
    documentId: String,
    onBack: () -> Unit,
    onResultClick: (ReaderLocation) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(documentId) {
        viewModel.setDocument(documentId)
    }

    SearchScreen(
        uiState = uiState,
        onQueryChange = viewModel::updateQuery,
        onSearchClick = viewModel::search,
        onBack = onBack,
        onResultClick = onResultClick,
        listState = listState,
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
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(DefaultTeddReaderSpacing.screenPadding),
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val isFieldEnabled = !uiState.isSearchUnsupported
    val canSearch = isFieldEnabled && uiState.query.isNotBlank() && !uiState.isLoading

    TeddScaffold(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
        topBar = {
            TeddTopBar(
                title = stringResource(Res.string.search_in_document),
                navigationIcon = {
                    TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                        Icon(imageVector = TeddIcons.Back, contentDescription = null)
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .widthIn(max = ScreenMaxWidth)
                    .fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(spacing.large),
            ) {
                item {
                    SearchForm(
                        query = uiState.query,
                        isFieldEnabled = isFieldEnabled,
                        canSearch = canSearch,
                        onQueryChange = onQueryChange,
                        onSearchClick = onSearchClick,
                    )
                }

                when {
                    uiState.isSearchUnsupported -> item {
                        TeddErrorBanner(message = stringResource(Res.string.search_pdf_unsupported))
                    }
                    uiState.errorMessage != null -> item {
                        TeddErrorBanner(message = uiState.errorMessage)
                    }
                    uiState.isLoading -> item {
                        TeddLoadingIndicator(message = stringResource(Res.string.search_loading))
                    }
                    uiState.query.isBlank() -> Unit
                    uiState.results.isEmpty() -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxSmall)) {
                            Text(text = stringResource(Res.string.search_no_results_title), style = typography.titleMedium)
                            Text(
                                text = stringResource(Res.string.search_no_results_description),
                                style = typography.settingDescription,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        item {
                            Text(
                                text = stringResource(Res.string.search_matches_count, uiState.results.size),
                                style = typography.settingTitle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(uiState.results) { result ->
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

@Composable
private fun SearchForm(
    query: String,
    isFieldEnabled: Boolean,
    canSearch: Boolean,
    onQueryChange: (String) -> Unit,
    onSearchClick: () -> Unit,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Text(
            text = stringResource(Res.string.search_find_passage_description),
            style = typography.settingDescription,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth < CompactContentWidth) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                    SearchField(
                        query = query,
                        isFieldEnabled = isFieldEnabled,
                        canSearch = canSearch,
                        onQueryChange = onQueryChange,
                        onSearchClick = onSearchClick,
                    )
                    TeddButton(
                        text = stringResource(Res.string.search),
                        onClick = onSearchClick,
                        enabled = canSearch,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchField(
                        query = query,
                        isFieldEnabled = isFieldEnabled,
                        canSearch = canSearch,
                        onQueryChange = onQueryChange,
                        onSearchClick = onSearchClick,
                        modifier = Modifier.weight(1f),
                    )
                    TeddButton(
                        text = stringResource(Res.string.search),
                        onClick = onSearchClick,
                        enabled = canSearch,
                        modifier = Modifier.defaultMinSize(minHeight = 56.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    isFieldEnabled: Boolean,
    canSearch: Boolean,
    onQueryChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TeddSearchField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = stringResource(Res.string.search_text_placeholder),
        enabled = isFieldEnabled,
        onSearch = { if (canSearch) onSearchClick() },
        trailingContent = if (query.isNotEmpty()) {
            {
                TeddIconButton(
                    onClick = { onQueryChange("") },
                    enabled = isFieldEnabled,
                    contentDescription = stringResource(Res.string.clear_search_query),
                ) {
                    Icon(imageVector = TeddIcons.Close, contentDescription = null)
                }
            }
        } else {
            null
        },
    )
}

private fun buildSearchSupportingText(result: SearchResult): String = buildList {
    if (result.sectionTitle?.isNotBlank() == true) add(result.snippet)
    add(result.location.asStorageString())
}.joinToString(separator = "\n")

@Preview(widthDp = 280)
@Preview(widthDp = 360)
@Preview(widthDp = 720)
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
            listState = rememberLazyListState(),
        )
    }
}

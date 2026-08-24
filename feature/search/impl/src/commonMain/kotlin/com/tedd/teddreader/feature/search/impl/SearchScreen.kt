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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderBreakpoints
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddSearchField
import com.tedd.teddreader.core.ui.component.TeddSection
import com.tedd.teddreader.core.ui.component.TeddSectionKind
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.extension.clearFocusOnBackgroundTap
import com.tedd.teddreader.core.ui.generated.resources.*
import com.tedd.teddreader.core.ui.icon.TeddIcons
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Entry point that wires [SearchViewModel] into [SearchScreen] for one document's in-document
 * search. Like [SearchScreen] below it, this composable is a pure state-and-callback pass-through
 * to the view model: it collects [SearchUiState] and forwards every user action back as a view
 * model call, holding no search state of its own — not even a draft. Unlike the reader's or
 * saved-places' route screens, there is no in-progress value to hold here: every keystroke in the
 * search field commits straight through [SearchViewModel.updateQuery] to [SearchUiState.query],
 * since typing a query has no gesture-in-progress-versus-committed distinction the way a slider
 * drag or a note edit does.
 *
 * @param documentId The document to search within; changing it re-triggers
 * [SearchViewModel.setDocument].
 * @param onBack Invoked when the user asks to leave the search screen.
 * @param onResultClick Invoked with a search result's location when the user taps it to jump
 * there.
 * @param modifier Applied to the resulting [SearchScreen].
 * @param viewModel The search screen's view model; defaults to one resolved through Koin.
 */
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

/**
 * The in-document search screen: a scaffold with the search form at the top of a scrolling list,
 * followed by whichever of the unsupported/error/loading/empty-query/no-results/results states
 * [uiState] currently describes. This composable is a pure state-and-callback pass-through to the
 * view model — every value it renders comes from [uiState] or one of its other parameters, and it
 * holds no search state of its own; `isFieldEnabled`, `canSearch`, and `resultContentPadding` are
 * all derived fresh from the parameters on every call, not stored state.
 *
 * Every block is one [TeddSection], which is what makes the states mutually exclusive by
 * construction: exactly one of the unsupported/error/loading/blank/no-results branches supplies a
 * [TeddSectionKind.Status] section, and the results branch supplies a [TeddSectionKind.Collection]
 * section instead. The results heading is rendered through an otherwise-empty
 * [TeddSectionKind.Collection] section — `fullBleed` on it costs nothing since it has no body —
 * immediately followed by the result rows as their own flat [LazyColumn] items rather than as that
 * section's body, so the results keep true row-level laziness and each row supplies the screen's
 * horizontal inset itself through `resultContentPadding`. That is what lets the divider and ripple
 * each row draws run the full bounded column width with no section gap breaking them apart, per the
 * search screen's contiguous-results contract.
 *
 * The content [Box] carries `clearFocusOnBackgroundTap` so a tap outside every result row's own
 * clickable bounds — the margin beside a width-capped list on a wide window, or the empty space
 * below the last result — drops focus and dismisses the soft keyboard the search field raised. A
 * tap that lands on the field or on a result row is consumed by that row's own click handling
 * first, so this never intercepts the taps a caller relies on.
 *
 * @param uiState The search screen's current state, as published by the view model.
 * @param onQueryChange Invoked as the user types in the search field.
 * @param onSearchClick Invoked when the user asks to search, whether from the field's own action
 * or the search button.
 * @param onBack Invoked when the user asks to leave the screen.
 * @param onResultClick Invoked with a search result's location when the user taps it.
 * @param listState Scroll state for the search form and results list.
 * @param modifier Applied to the scaffold.
 * @param contentPadding Vertical padding applied above and below the list's content; any
 * horizontal component is ignored because horizontal inset is owned by each [TeddSection] and the
 * result rows themselves. Null resolves to the theme's screenPadding for both edges.
 */
@Composable
fun SearchScreen(
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    onBack: () -> Unit,
    onResultClick: (ReaderLocation) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val breakpoints = teddReaderBreakpoints()
    val resolvedContentPadding = contentPadding ?: PaddingValues(spacing.screenPadding)
    val typography = teddReaderTypography()
    val colors = teddReaderColors()
    val isFieldEnabled = !uiState.isSearchUnsupported
    val canSearch = isFieldEnabled && uiState.query.isNotBlank() && !uiState.isLoading
    val resultContentPadding = PaddingValues(
        horizontal = spacing.screenPadding,
        vertical = spacing.small,
    )

    TeddScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TeddTopBar(
                title = stringResource(Res.string.search_in_document),
                navigationIcon = {
                    TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                        TeddIcon(imageVector = TeddIcons.Back, contentDescription = null)
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .imePadding()
                .clearFocusOnBackgroundTap(focusManager = LocalFocusManager.current),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .widthIn(max = breakpoints.readableMaxWidth)
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    top = resolvedContentPadding.calculateTopPadding(),
                    bottom = resolvedContentPadding.calculateBottomPadding(),
                ),
            ) {
                item {
                    TeddSection(kind = TeddSectionKind.Form) {
                        SearchForm(
                            query = uiState.query,
                            isFieldEnabled = isFieldEnabled,
                            canSearch = canSearch,
                            onQueryChange = onQueryChange,
                            onSearchClick = onSearchClick,
                        )
                    }
                }

                when {
                    uiState.isSearchUnsupported -> item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddErrorBanner(message = stringResource(Res.string.search_pdf_unsupported))
                        }
                    }
                    uiState.errorMessage != null -> item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddErrorBanner(message = uiState.errorMessage)
                        }
                    }
                    uiState.isLoading -> item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddLoadingIndicator(message = stringResource(Res.string.search_loading))
                        }
                    }
                    uiState.query.isBlank() -> Unit
                    uiState.results.isEmpty() -> item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing.xxSmall)) {
                                TeddText(text = stringResource(Res.string.search_no_results_title), style = typography.titleMedium)
                                TeddText(
                                    text = stringResource(Res.string.search_no_results_description),
                                    style = typography.settingDescription,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    else -> {
                        item {
                            TeddSection(
                                kind = TeddSectionKind.Collection,
                                title = stringResource(Res.string.search_matches_count, uiState.results.size),
                                fullBleed = true,
                            ) {}
                        }
                        items(uiState.results) { result ->
                            TeddListItem(
                                title = result.snippet,
                                supportingText = buildSearchSupportingText(result),
                                onClick = { onResultClick(result.location) },
                                singleClick = true,
                                contentPadding = resultContentPadding,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The search input: an explanatory line above a responsive layout that either stacks the search
 * field over a full-width button (narrow container) or places them side by side (wide container),
 * switching at
 * [TeddReaderBreakpoints.compactControlWidth][com.tedd.teddreader.core.designsystem.TeddReaderBreakpoints.compactControlWidth].
 *
 * @param query The search field's current text.
 * @param isFieldEnabled Whether the field accepts input; false while search is unsupported for
 * the open document.
 * @param canSearch Whether a search can actually be run right now, which enables the search
 * button.
 * @param onQueryChange Invoked as the user types in the search field.
 * @param onSearchClick Invoked when the user taps the search button or the field's own search
 * action.
 * @param modifier Applied to the form's root column.
 */
@Composable
private fun SearchForm(
    query: String,
    isFieldEnabled: Boolean,
    canSearch: Boolean,
    onQueryChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val breakpoints = teddReaderBreakpoints()
    val typography = teddReaderTypography()
    val colors = teddReaderColors()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        TeddText(
            text = stringResource(Res.string.search_find_passage_description),
            style = typography.settingDescription,
            color = colors.onSurfaceVariant,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth < breakpoints.compactControlWidth) {
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
                        modifier = Modifier.defaultMinSize(minHeight = spacing.rowHeight),
                    )
                }
            }
        }
    }
}

/**
 * The search text field itself: a [TeddSearchField] with a clear button shown only once there is
 * text to clear.
 *
 * @param query The field's current text.
 * @param isFieldEnabled Whether the field (and its clear button) accepts input.
 * @param canSearch Whether pressing enter/search should actually trigger a search.
 * @param onQueryChange Invoked as the user types, and with an empty string when the clear button
 * is tapped.
 * @param onSearchClick Invoked when the field's own search action fires and [canSearch] is true.
 * @param modifier Applied to the field.
 */
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
        onClearClick = { onQueryChange("") },
        clearDescription = stringResource(Res.string.clear_search_query),
    )
}

/**
 * The secondary text shown under a search result's snippet: the section it was found in, followed
 * by a localized description of its exact location (see [ReaderLocation.displayLabel]), on
 * separate lines. The section line is omitted when the result carries no section title, e.g. a
 * document with no chapter structure.
 *
 * @param result The search result to build supporting text for.
 * @return One or two lines: the section title (if any) and the location description.
 */
@Composable
private fun buildSearchSupportingText(result: SearchResult): String = buildList {
    result.sectionTitle?.takeIf { it.isNotBlank() }?.let(::add)
    add(result.location.displayLabel())
}.joinToString(separator = "\n")

/**
 * A localized, human-readable description of where this location points, shown as a search
 * result's supporting text. Each [ReaderLocation] variant is described in the terms that make
 * sense for it — a page number for a PDF, a raw text offset for plain text, a spine section for an
 * EPUB — rather than one generic label for all three. This mirrors the saved-places screen's own
 * `displayLabel`, kept as a separate copy here rather than a shared one because no feature module
 * in this project may depend on another feature's api or impl.
 *
 * @receiver The location to describe.
 */
@Composable
private fun ReaderLocation.displayLabel(): String = when (this) {
    is ReaderLocation.PdfPage -> stringResource(Res.string.reader_location_page, pageIndex + 1)
    is ReaderLocation.TextOffset -> stringResource(Res.string.reader_location_text_position, offset + 1)
    is ReaderLocation.EpubOffset -> stringResource(Res.string.reader_location_epub_section, spineIndex + 1)
}

/**
 * Preview of [SearchScreen] at three widths, with one sample search result, exercising the
 * compact, default, and wide layouts the screen's content can render at.
 */
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
                results = persistentListOf(
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

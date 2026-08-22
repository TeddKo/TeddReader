package com.tedd.teddreader.feature.search.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.SearchResult
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * The search screen's snapshot, as [SearchViewModel] publishes it and [SearchScreen] renders it.
 *
 * @property documentId The document being searched, set by [SearchViewModel.setDocument].
 * @property query The text currently in the search field, kept here so it survives a
 * recomposition rather than living only in a local Compose text-field state.
 * @property results The matches found for the most recently completed search, in reading order;
 * empty before any search has run or when the last one found nothing.
 * @property isLoading True while a search is in flight; false at every other time, including
 * before the first search has ever been run.
 * @property errorMessage The most recent failure to report to the user, or null once nothing is
 * pending — cleared by [SearchViewModel.updateQuery] as soon as the user starts typing a new one.
 * @property isSearchUnsupported True when the current document's format has no stored text to
 * search (a visual page format such as PDF, CBZ, or an image), which tells the screen to disable
 * the search field entirely rather than let the user run a search that could only ever fail.
 */
@Immutable
data class SearchUiState(
    val documentId: String = "",
    val query: String = "",
    val results: ImmutableList<SearchResult> = persistentListOf(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSearchUnsupported: Boolean = false,
)

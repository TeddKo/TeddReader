package com.tedd.teddreader.feature.search.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.SearchResult
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class SearchUiState(
    val documentId: String = "",
    val query: String = "",
    val results: ImmutableList<SearchResult> = persistentListOf(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSearchUnsupported: Boolean = false,
)

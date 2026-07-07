package com.tedd.teddreader.feature.search.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.SearchResult

@Immutable
data class SearchUiState(
    val documentId: String = "",
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val unsupportedMessage: String? = null,
)

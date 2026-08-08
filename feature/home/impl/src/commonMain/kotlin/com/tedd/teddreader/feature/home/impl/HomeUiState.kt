package com.tedd.teddreader.feature.home.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.DocumentMetadata

@Immutable
data class HomeUiState(
    val title: String = "TeddReader",
    val description: String = "Open local TXT, PDF, and EPUB documents.",
    val recentDocuments: List<DocumentMetadata> = emptyList(),
    val hasDocuments: Boolean = false,
    val sort: HomeSort = HomeSort.Recent,
    val formatFilter: HomeFormatFilter = HomeFormatFilter.All,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val unsupportedFormatMessage: String? = null,
)

enum class HomeSort {
    Recent,
    Title,
    Format,
}

enum class HomeFormatFilter {
    All,
    Txt,
    Pdf,
    Epub,
}

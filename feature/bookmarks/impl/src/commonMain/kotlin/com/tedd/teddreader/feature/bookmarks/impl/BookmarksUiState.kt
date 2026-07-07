package com.tedd.teddreader.feature.bookmarks.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.domain.repository.Bookmark

@Immutable
data class BookmarksUiState(
    val documentId: String = "",
    val bookmarks: List<Bookmark> = emptyList(),
    val editingBookmark: Bookmark? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

package com.tedd.teddreader.feature.bookmarks.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.domain.repository.Bookmark
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class BookmarksUiState(
    val documentId: String = "",
    val bookmarks: ImmutableList<Bookmark> = persistentListOf(),
    val editingBookmark: Bookmark? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

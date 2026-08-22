package com.tedd.teddreader.feature.bookmarks.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.domain.repository.Bookmark
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * What the bookmarks screen renders at any moment.
 *
 * @property documentId the document these bookmarks belong to; empty before the screen's view model
 *   has resolved it from its route.
 * @property bookmarks the document's saved places, in the order the screen lists them.
 * @property editingBookmark the bookmark currently open in the rename/edit sheet, or null when no
 *   sheet is showing.
 * @property isLoading whether [bookmarks] still reflects its initial, not-yet-loaded default rather
 *   than a real read from storage.
 * @property errorMessage a user-facing message for the most recent failed action, or null when
 *   nothing needs reporting.
 */
@Immutable
data class BookmarksUiState(
    val documentId: String = "",
    val bookmarks: ImmutableList<Bookmark> = persistentListOf(),
    val editingBookmark: Bookmark? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

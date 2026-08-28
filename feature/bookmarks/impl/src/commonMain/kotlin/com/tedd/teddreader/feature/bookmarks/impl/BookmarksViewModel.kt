package com.tedd.teddreader.feature.bookmarks.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tedd.teddreader.core.common.suspendRunCatching
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.domain.repository.BookmarkRepository
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class BookmarksViewModel(
    private val bookmarkRepository: BookmarkRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BookmarksUiState())
    val uiState: StateFlow<BookmarksUiState> = _uiState

    private var observeJob: Job? = null

    fun setDocument(documentIdValue: String) {
        if (_uiState.value.documentId == documentIdValue) return
        observeJob?.cancel()
        _uiState.value = BookmarksUiState(documentId = documentIdValue)
        observeJob = viewModelScope.launch {
            bookmarkRepository.observeBookmarks(DocumentId(documentIdValue))
                .catch { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = throwable.message ?: "Failed to load bookmarks.",
                        )
                    }
                }
                .collect { bookmarks ->
                    _uiState.update { it.copy(bookmarks = bookmarks.toImmutableList(), isLoading = false) }
                }
        }
    }

    fun startEdit(bookmark: Bookmark) {
        _uiState.update { it.copy(editingBookmark = bookmark) }
    }

    fun dismissEdit() {
        _uiState.update { it.copy(editingBookmark = null) }
    }

    /**
     * Persists [note] onto the bookmark currently being edited and closes the editor.
     *
     * The write is guarded because a Room failure here — a full disk, a constraint violation — used to
     * escape the launched coroutine uncaught and take the process down while the user was only editing
     * a note. On failure the editor stays open, so the text the user typed is not thrown away along
     * with the failed save, and the reason is published to [BookmarksUiState.errorMessage].
     *
     * @param note The note text to store; blank is stored as no note at all rather than an empty string.
     */
    fun saveNote(note: String) {
        val bookmark = _uiState.value.editingBookmark ?: return
        viewModelScope.launch {
            suspendRunCatching {
                bookmarkRepository.saveBookmark(bookmark.copy(note = note.ifBlank { null }))
            }
                .onSuccess { dismissEdit() }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(errorMessage = throwable.message ?: "Failed to save the bookmark note.")
                    }
                }
        }
    }

    /**
     * Removes [bookmark] from storage.
     *
     * Guarded for the same reason as [saveNote]: an unguarded delete let a storage failure crash the
     * process. The list itself is not touched here — it refreshes from
     * [BookmarkRepository.observeBookmarks], so a failed delete simply leaves the bookmark visible,
     * which is the honest outcome.
     *
     * @param bookmark The bookmark to delete; only its id is used.
     */
    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            suspendRunCatching { bookmarkRepository.deleteBookmark(bookmark.id) }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(errorMessage = throwable.message ?: "Failed to delete the bookmark.")
                    }
                }
        }
    }
}

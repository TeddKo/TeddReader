package com.tedd.teddreader.feature.bookmarks.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.domain.repository.BookmarkRepository
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
                    _uiState.update { it.copy(bookmarks = bookmarks, isLoading = false) }
                }
        }
    }

    fun startEdit(bookmark: Bookmark) {
        _uiState.update { it.copy(editingBookmark = bookmark) }
    }

    fun dismissEdit() {
        _uiState.update { it.copy(editingBookmark = null) }
    }

    fun saveNote(note: String) {
        val bookmark = _uiState.value.editingBookmark ?: return
        viewModelScope.launch {
            bookmarkRepository.saveBookmark(bookmark.copy(note = note.ifBlank { null }))
            dismissEdit()
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch { bookmarkRepository.deleteBookmark(bookmark.id) }
    }
}

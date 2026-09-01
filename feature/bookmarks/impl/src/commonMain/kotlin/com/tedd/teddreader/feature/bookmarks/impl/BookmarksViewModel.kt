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
     * 현재 편집 중인 북마크에 [note]를 저장하고 편집기를 닫는다.
     *
     * 디스크 공간 부족이나 제약 조건 위반 같은 Room 쓰기 실패가 실행 중인 코루틴 밖으로 처리되지 않은 채
     * 빠져나가, 사용자가 메모만 편집하던 중에도 프로세스를 종료하던 문제를 막기 위해 쓰기를 보호한다.
     * 실패하면 편집기를 열린 상태로 유지하므로 저장 실패와 함께 사용자가 입력한 텍스트가 사라지지 않으며,
     * 실패 원인은 [BookmarksUiState.errorMessage]로 게시된다.
     *
     * @param note 저장할 메모 텍스트이다. 공백뿐인 값은 빈 문자열 대신 메모 없음으로 저장된다.
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
     * 저장소에서 [bookmark]를 삭제한다.
     *
     * 보호되지 않은 삭제 작업에서는 저장소 실패로 프로세스가 종료될 수 있으므로 [saveNote]와 같은 이유로
     * 보호한다. 여기서는 목록 자체를 변경하지 않고 [BookmarkRepository.observeBookmarks]에서 새로 고치므로,
     * 삭제가 실패하면 실제 결과를 그대로 반영해 북마크가 화면에 남는다.
     *
     * @param bookmark 삭제할 북마크이며, id만 사용한다.
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

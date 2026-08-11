package com.tedd.teddreader.feature.home.impl

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun bookmarkChangeMovesDocumentToFavorites() = runTest {
        val repository = FakeDocumentRepository()
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.setDocumentBookmarked(repository.documentId, true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.favoriteDocuments.single().isBookmarked)
        assertEquals(emptyList(), viewModel.uiState.value.recentDocuments)
    }

    @Test
    fun deleteRemovesRecentDocument() = runTest {
        val repository = FakeDocumentRepository()
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteDocument(repository.documentId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.hasDocuments)
    }

    @Test
    fun deleteDocumentsRemovesAllSelectedRecentDocuments() = runTest {
        val repository = FakeDocumentRepository(includeSecondDocument = true)
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteDocuments(listOf(repository.documentId, repository.secondDocumentId))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.hasDocuments)
    }
}

private class FakeDocumentRepository(
    includeSecondDocument: Boolean = false,
) : DocumentRepository {
    val documentId = DocumentId("document-1")
    val secondDocumentId = DocumentId("document-2")
    private val documents = MutableStateFlow(
        buildList {
            add(
                DocumentMetadata(
                    id = documentId,
                    location = DocumentLocation(
                        sourceUri = "file:///document.txt",
                        displayName = "document.txt",
                    ),
                    format = DocumentFormat.TXT,
                    addedAtEpochMillis = 1_000L,
                ),
            )
            if (includeSecondDocument) {
                add(
                    DocumentMetadata(
                        id = secondDocumentId,
                        location = DocumentLocation(
                            sourceUri = "file:///document-2.txt",
                            displayName = "document-2.txt",
                        ),
                        format = DocumentFormat.TXT,
                        addedAtEpochMillis = 2_000L,
                    ),
                )
            }
        },
    )

    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = documents
    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        documents.value.firstOrNull { it.id == documentId }

    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = null

    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
    ): List<PageWindow> = emptyList()

    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument = error("not used")

    override suspend fun upsertDocument(document: DocumentMetadata) {
        documents.value = documents.value.map { current ->
            if (current.id == document.id) document else current
        }
    }

    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit

    override suspend fun deleteDocument(documentId: DocumentId) {
        documents.value = documents.value.filterNot { it.id == documentId }
    }
}

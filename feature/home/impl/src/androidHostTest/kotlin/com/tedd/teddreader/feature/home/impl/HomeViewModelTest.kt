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
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Test
    fun documentActionTargetDistinguishesSameDocumentAcrossHomeSections() {
        val recent = HomeDocumentActionTarget(HomeDocumentSection.Recent, "document-1")
        val library = HomeDocumentActionTarget(HomeDocumentSection.Library, "document-1")

        assertNotEquals(recent, library)
    }

    @Test
    fun homeLibraryGridRowsKeepTwoColumnsAndPadOnlyTheLastRow() {
        assertEquals(
            listOf(listOf(1, 2), listOf(3, 4)),
            homeLibraryGridRows(listOf(1, 2, 3, 4), columns = 2),
        )
        assertEquals(
            listOf(listOf(1, 2), listOf(3, null)),
            homeLibraryGridRows(listOf(1, 2, 3), columns = 2),
        )
    }

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
    fun bookmarkDocumentsMovesAllSelectedDocumentsToFavorites() = runTest {
        val repository = FakeDocumentRepository(includeSecondDocument = true)
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.setDocumentsBookmarked(
            listOf(repository.documentId, repository.secondDocumentId),
            true,
        )
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.favoriteDocuments.size)
        assertTrue(viewModel.uiState.value.favoriteDocuments.all(DocumentMetadata::isBookmarked))
        assertEquals(emptyList(), viewModel.uiState.value.recentDocuments)
    }

    @Test
    fun bookmarkSelectionTargetReturnsFalseOnlyWhenEverySelectedDocumentIsAlreadyBookmarked() {
        assertFalse(
            homeSelectionBookmarkTarget(
                listOf(
                    bookmarkedDocument("bookmarked-1"),
                    bookmarkedDocument("bookmarked-2"),
                ),
            ),
        )
        assertTrue(
            homeSelectionBookmarkTarget(
                listOf(
                    bookmarkedDocument("bookmarked-1"),
                    recentDocument("recent-1"),
                ),
            ),
        )
    }

    @Test
    fun unbookmarkDocumentsMovesAllSelectedDocumentsToRecents() = runTest {
        val repository = FakeDocumentRepository(
            includeSecondDocument = true,
            secondDocumentFormat = DocumentFormat.PDF,
            initiallyBookmarkedIds = setOf("document-1", "document-2"),
        )
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.setDocumentsBookmarked(
            listOf(repository.documentId, repository.secondDocumentId),
            false,
        )
        advanceUntilIdle()

        assertEquals(emptyList(), viewModel.uiState.value.favoriteDocuments)
        assertEquals(2, viewModel.uiState.value.recentDocuments.size)
        assertTrue(viewModel.uiState.value.recentDocuments.none(DocumentMetadata::isBookmarked))
    }

    @Test
    fun bulkImportedPdfCoversRequestAtMostOneCoverAtATime() = runTest {
        val repository = SuspendingCoverDocumentRepository()
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        repository.emitBulkPdfDocuments(count = 3)
        advanceUntilIdle()

        assertEquals(1, repository.maxConcurrentCoverRequests)
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

    @Test
    fun loadsPdfCoverAndSkipsTxtCoverRequests() = runTest {
        val repository = FakeDocumentRepository(includeSecondDocument = true)
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertContentEquals(listOf(repository.documentId.value), repository.coverRequestIds)
        assertContentEquals(repository.pdfCoverBytes, viewModel.uiState.value.documentCoverImages[repository.documentId.value])
        assertFalse(viewModel.uiState.value.documentCoverImages.containsKey(repository.secondDocumentId.value))
    }

    @Test
    fun coverAppearsOnceTheImportFinishesWithoutRestartingTheApp() = runTest {
        // A progressively imported document shows up in the library before its cover has been written,
        // so the first request comes back empty. Remembering that answer left the card blank until the
        // process was restarted, which is exactly what a reader saw after adding a book.
        val repository = FakeDocumentRepository()
        repository.coverAvailable = false
        val importing = repository.documents.value.map { document ->
            if (document.id == repository.documentId) document.copy(characterCount = null) else document
        }
        repository.emitDocuments(importing)
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.documentCoverImages.containsKey(repository.documentId.value))

        repository.coverAvailable = true
        repository.emitDocuments(
            importing.map { document ->
                if (document.id == repository.documentId) document.copy(characterCount = 1_234L) else document
            },
        )
        advanceUntilIdle()

        assertContentEquals(
            repository.pdfCoverBytes,
            viewModel.uiState.value.documentCoverImages[repository.documentId.value],
        )
    }

    @Test
    fun deleteRemovesLoadedCoverBytes() = runTest {
        val repository = FakeDocumentRepository()
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteDocument(repository.documentId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.documentCoverImages.containsKey(repository.documentId.value))
        assertFalse(viewModel.uiState.value.hasDocuments)
    }

    @Test
    fun homeStateKeepsAllLibraryDocumentsWhileRecentShowsLatestTwentyNonFavorites() = runTest {
        val repository = FakeDocumentRepository(
            documents = List(25) { index ->
                testDocument(
                    id = "recent-$index",
                    isBookmarked = index < 3,
                    addedAtEpochMillis = index.toLong(),
                    lastOpenedAtEpochMillis = (1_000L + index),
                )
            },
        )
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(25, viewModel.uiState.value.libraryDocuments.size)
        assertEquals(20, viewModel.uiState.value.recentDocuments.size)
        assertTrue(viewModel.uiState.value.recentDocuments.none(DocumentMetadata::isBookmarked))
        assertEquals("recent-24", viewModel.uiState.value.recentDocuments.first().id.value)
        assertEquals("recent-5", viewModel.uiState.value.recentDocuments.last().id.value)
    }

    @Test
    fun libraryPreviewUsesFourOnPhoneAndEightOnExpandedLayoutsWithoutAutoFolderMode() {
        val documents = List(10) { index ->
            testDocument(id = "library-$index", isBookmarked = false, addedAtEpochMillis = index.toLong())
        }

        assertEquals(
            4,
            homeLibraryPreviewDocuments(
                documents = documents,
                previewLimit = homeLibraryPreviewLimit(
                    isExpanded = false,
                    isTablet = false,
                    hasSeparatingFold = false,
                ),
            ).size,
        )
        assertEquals(
            8,
            homeLibraryPreviewDocuments(
                documents = documents,
                previewLimit = homeLibraryPreviewLimit(
                    isExpanded = true,
                    isTablet = false,
                    hasSeparatingFold = false,
                ),
            ).size,
        )
        assertEquals(
            8,
            homeLibraryPreviewDocuments(
                documents = documents,
                previewLimit = homeLibraryPreviewLimit(
                    isExpanded = false,
                    isTablet = true,
                    hasSeparatingFold = false,
                ),
            ).size,
        )
        assertEquals(
            8,
            homeLibraryPreviewDocuments(
                documents = documents,
                previewLimit = homeLibraryPreviewLimit(
                    isExpanded = false,
                    isTablet = false,
                    hasSeparatingFold = true,
                ),
            ).size,
        )
    }

    @Test
    fun libraryFolderPreviewDocumentsReturnsOnlyRequestedFolderInSourceOrderAndLimit() {
        val documents = buildList {
            repeat(10) { index ->
                add(
                    testDocument(
                        id = "folder-doc-$index",
                        isBookmarked = false,
                        addedAtEpochMillis = index.toLong(),
                        folderId = "folder-1",
                        folderName = "Folder 1",
                    ),
                )
            }
            add(
                testDocument(
                    id = "other-folder-doc",
                    isBookmarked = false,
                    addedAtEpochMillis = 100L,
                    folderId = "folder-2",
                    folderName = "Folder 2",
                ),
            )
        }

        assertEquals(
            listOf("folder-doc-0", "folder-doc-1", "folder-doc-2", "folder-doc-3"),
            libraryFolderPreviewDocuments(
                documents = documents,
                folderId = "folder-1",
                previewLimit = 4,
            ).map { it.id.value },
        )
        assertEquals(
            listOf(
                "folder-doc-0",
                "folder-doc-1",
                "folder-doc-2",
                "folder-doc-3",
                "folder-doc-4",
                "folder-doc-5",
                "folder-doc-6",
                "folder-doc-7",
            ),
            libraryFolderPreviewDocuments(
                documents = documents,
                folderId = "folder-1",
                previewLimit = 8,
            ).map { it.id.value },
        )
    }

    @Test
    fun libraryFolderRemainingDocumentCountNeverDropsBelowZero() {
        assertEquals(6, libraryFolderRemainingDocumentCount(totalCount = 10, previewCount = 4))
        assertEquals(0, libraryFolderRemainingDocumentCount(totalCount = 4, previewCount = 4))
        assertEquals(0, libraryFolderRemainingDocumentCount(totalCount = 3, previewCount = 4))
    }

    @Test
    fun createMoveRenameAndDeleteFolderOnlyMutateMembership() = runTest {
        val repository = FakeDocumentRepository(
            documents = listOf(
                testDocument(id = "doc-1", isBookmarked = false, addedAtEpochMillis = 1L),
                testDocument(id = "doc-2", isBookmarked = false, addedAtEpochMillis = 2L),
                testDocument(
                    id = "doc-3",
                    isBookmarked = false,
                    addedAtEpochMillis = 3L,
                    folderId = "folder-old",
                    folderName = "Old Folder",
                ),
            ),
        )
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val createdFolderId = viewModel.createFolder(
            name = "Weekend Reads",
            documentIds = listOf(DocumentId("doc-1"), DocumentId("doc-2")),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("doc-1", "doc-2"),
            repository.documentsInFolder(createdFolderId).map { it.id.value },
        )
        assertEquals(
            listOf("Weekend Reads"),
            viewModel.uiState.value.libraryFolders.filter { it.id == createdFolderId }.map { it.name },
        )

        viewModel.moveDocumentsToFolder(
            documentIds = listOf(DocumentId("doc-3")),
            folderId = createdFolderId,
        )
        advanceUntilIdle()
        assertEquals(
            createdFolderId,
            repository.requireDocument("doc-3").folderId,
        )

        viewModel.renameFolder(folderId = createdFolderId, name = "Renamed Folder")
        advanceUntilIdle()
        assertEquals(
            setOf("Renamed Folder"),
            repository.documentsInFolder(createdFolderId).mapNotNull { it.folderName }.toSet(),
        )

        viewModel.deleteFolder(createdFolderId)
        advanceUntilIdle()
        assertEquals(
            listOf("doc-1", "doc-2", "doc-3"),
            repository.documentsWithoutFolder().map { it.id.value }.sorted(),
        )
        assertEquals(3, viewModel.uiState.value.libraryDocuments.size)
    }

    @Test
    fun formatFilterOnlyLimitsVisibleDocumentsWhileFolderRenameAndDeleteStillAffectWholeFolder() = runTest {
        val repository = FakeDocumentRepository(
            documents = listOf(
                testDocument(
                    id = "pdf-doc",
                    isBookmarked = false,
                    addedAtEpochMillis = 1L,
                    folderId = "folder-shared",
                    folderName = "Shared Folder",
                    format = DocumentFormat.PDF,
                ),
                testDocument(
                    id = "txt-doc",
                    isBookmarked = false,
                    addedAtEpochMillis = 2L,
                    folderId = "folder-shared",
                    folderName = "Shared Folder",
                    format = DocumentFormat.TXT,
                ),
            ),
        )
        val viewModel = HomeViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.updateFormatFilter(HomeFormatFilter.Pdf)
        advanceUntilIdle()
        assertEquals(listOf("pdf-doc"), viewModel.uiState.value.libraryDocuments.map { it.id.value })

        viewModel.renameFolder(folderId = "folder-shared", name = "Renamed Folder")
        advanceUntilIdle()
        assertEquals("Renamed Folder", repository.requireDocument("pdf-doc").folderName)
        assertEquals("Renamed Folder", repository.requireDocument("txt-doc").folderName)

        viewModel.deleteFolder("folder-shared")
        advanceUntilIdle()
        assertEquals(null, repository.requireDocument("pdf-doc").folderId)
        assertEquals(null, repository.requireDocument("pdf-doc").folderName)
        assertEquals(null, repository.requireDocument("txt-doc").folderId)
        assertEquals(null, repository.requireDocument("txt-doc").folderName)
        assertEquals(2, repository.documentsWithoutFolder().size)
        assertEquals(listOf("pdf-doc"), viewModel.uiState.value.libraryDocuments.map { it.id.value })
    }

}

private class FakeDocumentRepository(
    includeSecondDocument: Boolean = false,
    secondDocumentFormat: DocumentFormat = DocumentFormat.TXT,
    initiallyBookmarkedIds: Set<String> = emptySet(),
    documents: List<DocumentMetadata>? = null,
) : DocumentRepository {
    val documentId = DocumentId("document-1")
    val secondDocumentId = DocumentId("document-2")
    val pdfCoverBytes = byteArrayOf(1, 3, 3, 7)
    val coverRequestIds = mutableListOf<String>()
    val documents = MutableStateFlow(
        documents ?: buildList {
            add(
                DocumentMetadata(
                    id = documentId,
                    location = DocumentLocation(
                        sourceUri = "file:///document.pdf",
                        displayName = "document.pdf",
                    ),
                    format = DocumentFormat.PDF,
                    addedAtEpochMillis = 1_000L,
                    isBookmarked = documentId.value in initiallyBookmarkedIds,
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
                        format = secondDocumentFormat,
                        addedAtEpochMillis = 2_000L,
                        isBookmarked = secondDocumentId.value in initiallyBookmarkedIds,
                    ),
                )
            }
        },
    )

    fun requireDocument(id: String): DocumentMetadata =
        documents.value.first { it.id.value == id }

    fun documentsInFolder(folderId: String): List<DocumentMetadata> =
        documents.value.filter { it.folderId == folderId }

    fun documentsWithoutFolder(): List<DocumentMetadata> =
        documents.value.filter { it.folderId == null }

    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = documents
    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        documents.value.firstOrNull { it.id == documentId }

    var coverAvailable: Boolean = true

    fun emitDocuments(next: List<DocumentMetadata>) {
        documents.value = next
    }

    override suspend fun getDocumentCover(documentId: DocumentId): ByteArray? {
        coverRequestIds += documentId.value
        if (!coverAvailable) return null
        return if (documentId == this.documentId) pdfCoverBytes else null
    }

    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = null

    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: com.tedd.teddreader.core.common.model.ReaderPageBreaker?,
        anchorOffset: Long?,
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

private class SuspendingCoverDocumentRepository : DocumentRepository {
    private val coverGate = CompletableDeferred<Unit>()
    private val documents = MutableStateFlow<List<DocumentMetadata>>(emptyList())
    var activeCoverRequests = 0
        private set
    var maxConcurrentCoverRequests = 0
        private set

    fun emitBulkPdfDocuments(count: Int) {
        documents.value = List(count) { index ->
            DocumentMetadata(
                id = DocumentId("bulk-$index"),
                location = DocumentLocation(
                    sourceUri = "file:///bulk-$index.pdf",
                    displayName = "bulk-$index.pdf",
                ),
                format = DocumentFormat.PDF,
                addedAtEpochMillis = index.toLong(),
            )
        }
    }
    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = documents

    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        documents.value.firstOrNull { it.id == documentId }

    override suspend fun getDocumentCover(documentId: DocumentId): ByteArray? {
        activeCoverRequests += 1
        maxConcurrentCoverRequests = maxOf(maxConcurrentCoverRequests, activeCoverRequests)
        try {
            coverGate.await()
            return byteArrayOf(documentId.value.length.toByte())
        } finally {
            activeCoverRequests -= 1
        }
    }

    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = null

    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: com.tedd.teddreader.core.common.model.ReaderPageBreaker?,
        anchorOffset: Long?,
    ): List<PageWindow> = emptyList()

    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument = error("not used")

    override suspend fun upsertDocument(document: DocumentMetadata) = Unit

    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit

    override suspend fun deleteDocument(documentId: DocumentId) = Unit
}

private fun bookmarkedDocument(id: String): DocumentMetadata = testDocument(id = id, isBookmarked = true)

private fun recentDocument(id: String): DocumentMetadata = testDocument(id = id, isBookmarked = false)

private fun testDocument(
    id: String,
    isBookmarked: Boolean,
    addedAtEpochMillis: Long = 1_000L,
    lastOpenedAtEpochMillis: Long? = null,
    folderId: String? = null,
    folderName: String? = null,
    format: DocumentFormat = DocumentFormat.PDF,
): DocumentMetadata = DocumentMetadata(
    id = DocumentId(id),
    location = DocumentLocation(
        sourceUri = "file:///$id.${format.name.lowercase()}",
        displayName = "$id.${format.name.lowercase()}",
    ),
    format = format,
    addedAtEpochMillis = addedAtEpochMillis,
    lastOpenedAtEpochMillis = lastOpenedAtEpochMillis,
    isBookmarked = isBookmarked,
    folderId = folderId,
    folderName = folderName,
)

package com.tedd.teddreader.feature.home.impl

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.usecase.CreateLibraryFolderUseCase
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins [HomeViewModel]'s behavior end to end against fake repositories: how bookmarking moves a
 * document between favorites and recents, how folder membership is created, moved, renamed, and
 * deleted, how covers are loaded lazily and only as documents become visible, and how the pure
 * layout helpers (grid rows, library/folder preview limits) behave in isolation. A regression in
 * any of these should fail one of the tests below rather than only show up as a wrong screen at
 * runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    /** Guards that a recent-section and a library-section action target for the same document id
     * compare as different, so selecting a document's row in one section does not also select its
     * duplicate row in the other. */
    @Test
    fun documentActionTargetDistinguishesSameDocumentAcrossHomeSections() {
        val recent = HomeDocumentActionTarget(HomeDocumentSection.Recent, "document-1")
        val library = HomeDocumentActionTarget(HomeDocumentSection.Library, "document-1")

        assertNotEquals(recent, library)
    }

    /** Guards that `homeLibraryGridRows` groups items into fixed-width rows and pads only a short
     * final row with `null`, never a full one. */
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

    /** The coroutine dispatcher installed as the main dispatcher for every test, so `viewModelScope`
     * work in [HomeViewModel] runs deterministically under `advanceUntilIdle` instead of on a real
     * thread. */
    private val dispatcher = StandardTestDispatcher()

    /** Installs [dispatcher] as the main dispatcher before each test. */
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    /** Restores the real main dispatcher after each test, so dispatcher state does not leak between
     * tests. */
    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(repository: DocumentRepository): HomeViewModel =
        HomeViewModel(
            createLibraryFolder = CreateLibraryFolderUseCase(repository),
            documentRepository = repository,
        )

    /** Guards that bookmarking a document moves it out of `recentDocuments` and into
     * `favoriteDocuments` in the emitted `HomeUiState`. */
    @Test
    fun bookmarkChangeMovesDocumentToFavorites() = runTest {
        val repository = FakeDocumentRepository()
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.setDocumentBookmarked(repository.documentId, true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.favoriteDocuments.single().isBookmarked)
        assertEquals(emptyList(), viewModel.uiState.value.recentDocuments)
    }

    /** Guards that bookmarking a batch of documents moves every one of them to favorites, not just
     * the first. */
    @Test
    fun bookmarkDocumentsMovesAllSelectedDocumentsToFavorites() = runTest {
        val repository = FakeDocumentRepository(includeSecondDocument = true)
        val viewModel = createViewModel(repository)
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

    /** Guards that `homeSelectionBookmarkTarget` returns false (meaning "unbookmark next") only
     * when every selected document is already bookmarked, and true (meaning "bookmark next") as
     * soon as any one of them is not — the rule that decides what a bulk bookmark toggle does
     * next. */
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

    /** Guards that unbookmarking a batch of documents moves every one of them back into
     * `recentDocuments`. */
    @Test
    fun unbookmarkDocumentsMovesAllSelectedDocumentsToRecents() = runTest {
        val repository = FakeDocumentRepository(
            includeSecondDocument = true,
            secondDocumentFormat = DocumentFormat.PDF,
            initiallyBookmarkedIds = setOf("document-1", "document-2"),
        )
        val viewModel = createViewModel(repository)
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

    /** Guards that repeated visible-card cover callbacks for the same PDF only keep one's initializer processes one document's
     * cover at a time even when a bulk import emits several PDFs in a single list, using
     * [SuspendingCoverDocumentRepository] to observe the actual concurrency instead of only the
     * final result. */
    @Test
    fun bulkImportedPdfCoversRequestAtMostOneCoverAtATime() = runTest {
        val repository = SuspendingCoverDocumentRepository()
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        repository.emitBulkPdfDocuments(count = 3)
        advanceUntilIdle()

        repeat(3) {
            viewModel.loadCover(DocumentId("bulk-0"))
        }
        advanceUntilIdle()

        assertEquals(1, repository.maxConcurrentCoverRequests)
    }

    /** Guards that deleting a document removes it from the library entirely, not just from one
     * section. */
    @Test
    fun deleteRemovesRecentDocument() = runTest {
        val repository = FakeDocumentRepository()
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteDocument(repository.documentId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.hasDocuments)
    }

    /** Guards that deleting a batch of documents removes every one of them. */
    @Test
    fun deleteDocumentsRemovesAllSelectedRecentDocuments() = runTest {
        val repository = FakeDocumentRepository(includeSecondDocument = true)
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteDocuments(listOf(repository.documentId, repository.secondDocumentId))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.hasDocuments)
    }

    /** Guards that visible-card cover callbacks only request formats that actually support
     * repository covers (PDF here) and skip TXT entirely. */
    @Test
    fun loadsPdfCoverAndSkipsTxtCoverRequests() = runTest {
        val repository = FakeDocumentRepository(includeSecondDocument = true)
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.loadCover(repository.documentId)
        viewModel.loadCover(repository.secondDocumentId)
        advanceUntilIdle()

        assertContentEquals(listOf(repository.documentId.value), repository.coverRequestIds)
        assertContentEquals(repository.pdfCoverBytes, viewModel.uiState.value.documentCoverImages[repository.documentId.value])
        assertFalse(viewModel.uiState.value.documentCoverImages.containsKey(repository.secondDocumentId.value))
    }

    /**
     * Guards the bug this test is named for: a progressively imported document shows up in the
     * library before its cover has been written, so the first cover request comes back empty.
     * Remembering that empty answer used to leave the card blank until the process was restarted —
     * exactly what a reader saw right after adding a book — so this asserts that once the import
     * finishes and a later emission arrives, the cover is fetched again and shows up without a
     * restart.
     */
    @Test
    fun coverAppearsOnceTheImportFinishesWithoutRestartingTheApp() = runTest {
        val repository = FakeDocumentRepository()
        repository.coverAvailable = false
        val importing = repository.documents.value.map { document ->
            if (document.id == repository.documentId) document.copy(characterCount = null) else document
        }
        repository.emitDocuments(importing)
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.loadCover(repository.documentId)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.documentCoverImages.containsKey(repository.documentId.value))

        repository.coverAvailable = true
        repository.emitDocuments(
            importing.map { document ->
                if (document.id == repository.documentId) document.copy(characterCount = 1_234L) else document
            },
        )
        advanceUntilIdle()

        viewModel.loadCover(repository.documentId)
        advanceUntilIdle()

        assertContentEquals(
            listOf(repository.documentId.value, repository.documentId.value),
            repository.coverRequestIds,
        )
        assertContentEquals(
            repository.pdfCoverBytes,
            viewModel.uiState.value.documentCoverImages[repository.documentId.value],
        )
    }

    /** Guards that deleting a document also drops its cached cover bytes from the emitted state, so
     * a later document reusing the same id cannot show a stale cover. */
    @Test
    fun deleteRemovesLoadedCoverBytes() = runTest {
        val repository = FakeDocumentRepository()
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteDocument(repository.documentId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.documentCoverImages.containsKey(repository.documentId.value))
        assertFalse(viewModel.uiState.value.hasDocuments)
    }

    /** Guards that `libraryDocuments` keeps every non-filtered document while `recentDocuments` is
     * capped at the newest 20 non-favorites — the two lists are deliberately allowed to disagree on
     * how much of the library they show. */
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
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(25, viewModel.uiState.value.libraryDocuments.size)
        assertEquals(20, viewModel.uiState.value.recentDocuments.size)
        assertTrue(viewModel.uiState.value.recentDocuments.none(DocumentMetadata::isBookmarked))
        assertEquals("recent-24", viewModel.uiState.value.recentDocuments.first().id.value)
        assertEquals("recent-5", viewModel.uiState.value.recentDocuments.last().id.value)
    }

    /** Guards `homeLibraryPreviewLimit`'s rule for how many library items the home screen previews:
     * four on a compact phone layout, and eight once the layout is expanded, is a tablet, or has a
     * separating display fold. */
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

    /** Guards that `libraryFolderPreviewDocuments` returns only the requested folder's documents,
     * in their original order, truncated to the given preview limit. */
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

    /** Guards that `libraryFolderRemainingDocumentCount` floors at zero once the preview already
     * covers the whole folder, instead of going negative. */
    @Test
    fun libraryFolderRemainingDocumentCountNeverDropsBelowZero() {
        assertEquals(6, libraryFolderRemainingDocumentCount(totalCount = 10, previewCount = 4))
        assertEquals(0, libraryFolderRemainingDocumentCount(totalCount = 4, previewCount = 4))
        assertEquals(0, libraryFolderRemainingDocumentCount(totalCount = 3, previewCount = 4))
    }

    /**
     * Guards the full folder lifecycle end to end: creating a folder assigns exactly the selected
     * documents to it and nothing else changes; moving a document into it updates only that
     * document's folder fields; renaming rewrites the folder name on every member without touching
     * membership; and deleting the folder clears every member's folder fields while leaving the
     * documents themselves in place.
     */
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
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.createFolder(
            name = "Weekend Reads",
            documentIds = listOf(DocumentId("doc-1"), DocumentId("doc-2")),
        )
        advanceUntilIdle()

        val createdFolderId = repository.requireDocument("doc-1").folderId ?: error("folder not created")
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

    /**
     * Guards that a format filter only narrows what `libraryDocuments` shows and does not narrow
     * what a folder operation acts on — renaming or deleting a folder still touches every member
     * document, even the ones the current filter is hiding from view.
     */
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
        val viewModel = createViewModel(repository)
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

    /**
     * Guards that a cover-only emission merges into [HomeUiState] without rebuilding any of the
     * document-derived lists: after a cover is fetched, the new state's [HomeUiState.libraryDocuments],
     * [HomeUiState.favoriteDocuments], [HomeUiState.recentDocuments], and [HomeUiState.libraryFolders]
     * must be the *same instances* the pre-cover state already held, while only
     * [HomeUiState.documentCoverImages] changes to carry the new bytes.
     *
     * This is the whole point of deriving the document lists in a separate flow from the cover map:
     * collapsing them back into one `combine(recentDocuments, controls, documentCoverImages)` rebuilds
     * every list on each cover emission, and the `assertSame` checks below fail — verified by actually
     * running that regression, not by reading the code.
     */
    @Test
    fun coverEmissionKeepsPreviousDocumentListInstances() = runTest {
        val repository = FakeDocumentRepository(includeSecondDocument = true, secondDocumentFormat = DocumentFormat.PDF)
        val viewModel = createViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val beforeCover = viewModel.uiState.value
        assertTrue(beforeCover.documentCoverImages.isEmpty())

        viewModel.loadCover(repository.documentId)
        advanceUntilIdle()

        val afterCover = viewModel.uiState.value
        assertContentEquals(
            repository.pdfCoverBytes,
            afterCover.documentCoverImages[repository.documentId.value],
        )
        assertSame(beforeCover.libraryDocuments, afterCover.libraryDocuments)
        assertSame(beforeCover.favoriteDocuments, afterCover.favoriteDocuments)
        assertSame(beforeCover.recentDocuments, afterCover.recentDocuments)
        assertSame(beforeCover.libraryFolders, afterCover.libraryFolders)
    }

}

/**
 * An in-memory [DocumentRepository] backed by a [MutableStateFlow], so a test can push a new
 * document list mid-run (see [emitDocuments]) and observe how [HomeViewModel] reacts, without a
 * real database or file I/O. Models the two axes most tests vary: how many documents exist and
 * which ones start bookmarked.
 *
 * @param includeSecondDocument Whether a second seed document exists alongside [documentId]'s.
 * @param secondDocumentFormat The second document's format, used only when [includeSecondDocument]
 * is true.
 * @param initiallyBookmarkedIds Ids that should start out bookmarked.
 * @param documents A fully custom seed list, overriding the two-document default entirely.
 */
private class FakeDocumentRepository(
    includeSecondDocument: Boolean = false,
    secondDocumentFormat: DocumentFormat = DocumentFormat.TXT,
    initiallyBookmarkedIds: Set<String> = emptySet(),
    documents: List<DocumentMetadata>? = null,
) : DocumentRepository {
    /** The default seed document's id, referenced directly by most tests instead of looking it up
     * in [documents]. */
    val documentId = DocumentId("document-1")

    /** The optional second seed document's id; only present in [documents] when the constructor's
     * `includeSecondDocument` was true. */
    val secondDocumentId = DocumentId("document-2")

    /** The fixed bytes [getDocumentCover] returns for [documentId] when a cover is available, so a
     * test can assert the exact bytes made it into [HomeViewModel]'s state. */
    val pdfCoverBytes = byteArrayOf(1, 3, 3, 7)

    /** Every id [getDocumentCover] has been asked for, in call order, so a test can assert which
     * documents were fetched and which were skipped. */
    val coverRequestIds = mutableListOf<String>()

    /** The mutable backing list; seeded from the constructor and mutated by [emitDocuments],
     * [upsertDocument], and [deleteDocument] to stand in for the repository's live document flow. */
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

    /** Looks up a document by id for assertions, failing loudly if it is missing rather than
     * returning null. */
    fun requireDocument(id: String): DocumentMetadata =
        documents.value.first { it.id.value == id }

    /** Documents currently carrying the given folder id, for assertions after a folder
     * mutation. */
    fun documentsInFolder(folderId: String): List<DocumentMetadata> =
        documents.value.filter { it.folderId == folderId }

    /** Documents with no folder assigned, for assertions that a folder deletion actually cleared
     * membership rather than leaving it behind. */
    fun documentsWithoutFolder(): List<DocumentMetadata> =
        documents.value.filter { it.folderId == null }

    /** Exposes [documents] as the live document flow [HomeViewModel] observes. */
    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = documents

    /** Looks up a document by id the way the real repository would, returning null if it is not
     * present. */
    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        documents.value.firstOrNull { it.id == documentId }

    /** Whether [getDocumentCover] should return bytes at all; set to false to simulate a document
     * whose cover has not been written yet, e.g. one still importing. */
    var coverAvailable: Boolean = true

    /** Replaces the whole document list, simulating an import or edit landing in the underlying
     * store as a single new emission. */
    fun emitDocuments(next: List<DocumentMetadata>) {
        documents.value = next
    }

    /** Records the request in [coverRequestIds] and returns [pdfCoverBytes] for [documentId] when
     * [coverAvailable], mirroring the real repository's per-document cover lookup without touching a
     * file. */
    override suspend fun getDocumentCover(documentId: DocumentId): ByteArray? {
        coverRequestIds += documentId.value
        if (!coverAvailable) return null
        return if (documentId == this.documentId) pdfCoverBytes else null
    }

    /** Not exercised by these tests; returns null since no test in this file opens a document body
     * through this fake. */
    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = null

    /** Not exercised by these tests; returns an empty page list since no test paginates through
     * this fake. */
    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: ReaderPageBreaker?,
        anchorOffset: Long?,
        viewportDensity: Float,
    ): List<PageWindow> = emptyList()

    /** Not exercised by these tests; fails loudly if called, since importing is out of scope for
     * the home-screen behavior this fake supports. */
    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument = error("not used")

    /** Writes back a document's full record, replacing the previous entry with the same id — the
     * read-modify-write half of every folder and bookmark mutation under test. */
    override suspend fun upsertDocument(document: DocumentMetadata) {
        documents.value = documents.value.map { current ->
            if (current.id == document.id) document else current
        }
    }

    /** Not exercised by these tests; a no-op since nothing here reads "last opened." */
    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit

    /** Removes a document from [documents], the deletion counterpart [upsertDocument] tests rely on
     * to verify a document is really gone. */
    override suspend fun deleteDocument(documentId: DocumentId) {
        documents.value = documents.value.filterNot { it.id == documentId }
    }
}

/**
 * A [DocumentRepository] whose [getDocumentCover] never returns, so
 * [bulkImportedPdfCoversRequestAtMostOneCoverAtATime] can observe how many in-flight
 * requests repeated visible-card callbacks for the same document create, instead of only seeing
 * the eventual result.
 */
private class SuspendingCoverDocumentRepository : DocumentRepository {
    /** Never completed, so every [getDocumentCover] call suspends on it for the rest of the test —
     * that is what keeps [activeCoverRequests] elevated long enough to observe. */
    private val coverGate = CompletableDeferred<Unit>()

    /** The backing document flow, seeded by [emitBulkPdfDocuments]. */
    private val documents = MutableStateFlow<List<DocumentMetadata>>(emptyList())

    /** How many [getDocumentCover] calls are currently suspended on [coverGate]. */
    var activeCoverRequests = 0
        private set

    /** The high-water mark of [activeCoverRequests] — the largest number of in-flight cover
     * requests [HomeViewModel] ever had at once for the repeated-callback scenario the test
     * asserts on. */
    var maxConcurrentCoverRequests = 0
        private set

    /** Seeds [count] PDF documents in a single emission, simulating a bulk import landing all at
     * once so the test can check that the view model's cover-loading pass throttles itself rather
     * than firing every request in parallel.
     *
     * @param count How many documents to seed.
     */
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
    /** Exposes [documents] as the live document flow [HomeViewModel] observes. */
    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = documents

    /** Not exercised by these tests; returns null since nothing here looks a document up by id. */
    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        documents.value.firstOrNull { it.id == documentId }

    /** Tracks [activeCoverRequests] and [maxConcurrentCoverRequests] around a suspend that never
     * resolves within the test, so the caller's concurrency at this exact call site — not the
     * eventual return value — is what the test observes. */
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

    /** Not exercised by these tests; returns null since no test opens a document body through this
     * fake. */
    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = null

    /** Not exercised by these tests; returns an empty page list since no test paginates through
     * this fake. */
    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: ReaderPageBreaker?,
        anchorOffset: Long?,
        viewportDensity: Float,
    ): List<PageWindow> = emptyList()

    /** Not exercised by these tests; fails loudly if called, since importing is out of scope for
     * the cover-concurrency behavior this fake supports. */
    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument = error("not used")

    /** Not exercised by these tests; a no-op since no test in this fixture bookmarks or refiles a
     * document. */
    override suspend fun upsertDocument(document: DocumentMetadata) = Unit

    /** Not exercised by these tests; a no-op since nothing here reads "last opened." */
    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit

    /** Not exercised by these tests; a no-op since no test in this fixture deletes a document. */
    override suspend fun deleteDocument(documentId: DocumentId) = Unit
}

/** A minimal bookmarked document for tests that only care about bookmark state, not the rest of
 * [DocumentMetadata]. */
private fun bookmarkedDocument(id: String): DocumentMetadata = testDocument(id = id, isBookmarked = true)

/** A minimal non-bookmarked document; the counterpart to [bookmarkedDocument]. */
private fun recentDocument(id: String): DocumentMetadata = testDocument(id = id, isBookmarked = false)

/**
 * Builds a [DocumentMetadata] with sensible defaults for every field a given test does not care
 * about, so each test only has to name the handful of fields its assertions actually depend on.
 *
 * @param id The document's id, also used to derive its source URI and display name.
 * @param isBookmarked Whether the document starts bookmarked.
 * @param addedAtEpochMillis When the document was added; defaults to a fixed timestamp so ordering
 * tests can override it explicitly.
 * @param lastOpenedAtEpochMillis When the document was last opened, or null if never.
 * @param folderId The folder the document belongs to, or null for none.
 * @param folderName The folder's display name; must be non-null exactly when [folderId] is.
 * @param format The document's format, defaulting to PDF.
 */
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

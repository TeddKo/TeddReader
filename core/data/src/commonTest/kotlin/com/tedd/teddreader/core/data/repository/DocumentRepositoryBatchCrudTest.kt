package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.data.pagination.TextPageLayoutEngine
import com.tedd.teddreader.core.data.parser.ComicBookDocumentParser
import com.tedd.teddreader.core.data.parser.DocumentFormatDetector
import com.tedd.teddreader.core.data.parser.EpubDocumentParser
import com.tedd.teddreader.core.data.parser.ImageDocumentParser
import com.tedd.teddreader.core.data.parser.PdfDocumentParser
import com.tedd.teddreader.core.data.parser.TxtDocumentParser
import com.tedd.teddreader.core.data.parser.systemFileSystem
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.room.dao.DocumentDao
import com.tedd.teddreader.core.room.dao.PageLayoutDao
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import com.tedd.teddreader.core.room.entity.DocumentEntity
import com.tedd.teddreader.core.room.entity.PageLayoutEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentRepositoryBatchCrudTest {
    @Test
    fun bulkBookmarkFolderRenameAndClearUseSingleDaoCalls() = runTest {
        val dao = RecordingDocumentDao()
        val repository = newRepository(documentDao = dao)
        val documentIds = listOf(DocumentId("a"), DocumentId("b"))

        repository.setDocumentsBookmarked(documentIds, true)
        repository.setDocumentsFolder(documentIds, "folder-1", "Shelf")
        repository.renameFolder("folder-1", "Renamed")
        repository.clearFolder("folder-1")

        assertEquals(listOf("a", "b"), dao.lastBookmarkedIds)
        assertTrue(dao.lastBookmarkedValue)
        assertEquals(listOf("a", "b"), dao.lastFolderIds)
        assertEquals("folder-1", dao.lastFolderId)
        assertEquals("Shelf", dao.lastFolderName)
        assertEquals("folder-1", dao.renamedFolderId)
        assertEquals("Renamed", dao.renamedFolderName)
        assertEquals("folder-1", dao.clearedFolderId)
    }

    @Test
    fun emptyBulkWritesReturnBeforeTouchingDao() = runTest {
        val dao = RecordingDocumentDao()
        val repository = newRepository(documentDao = dao)

        repository.setDocumentsBookmarked(emptyList(), true)
        repository.setDocumentsFolder(emptyList(), "folder-1", "Shelf")
        repository.deleteDocuments(emptyList())

        assertFalse(dao.bookmarkedCalled)
        assertFalse(dao.folderCalled)
        assertFalse(dao.bulkDeleteCalled)
    }

    @Test
    fun bulkDeleteAlsoDropsLayoutsAndCachedCoverFilesPerDocument() = runTest {
        val documentIds = listOf(DocumentId("file:///one.epub"), DocumentId("file:///two.epub"))
        val documentDao = RecordingDocumentDao(
            documents = documentIds.associateWith { id -> documentEntity(id) }.toMutableMap(),
        )
        val pageLayoutDao = RecordingPageLayoutDao()
        val fileSource = BatchCrudDocumentFileSource()
        val repository = newRepository(documentDao = documentDao, pageLayoutDao = pageLayoutDao, documentFileSource = fileSource)

        documentIds.forEach { id ->
            val path = coverFilePath(fileSource, id)
            systemFileSystem().createDirectories(path.parent!!)
            systemFileSystem().write(path) { write(byteArrayOf(1, 2, 3)) }
            assertTrue(systemFileSystem().exists(path))
        }

        repository.deleteDocuments(documentIds)

        assertEquals(documentIds.map(DocumentId::value), documentDao.bulkDeletedIds)
        assertEquals(documentIds.map(DocumentId::value), pageLayoutDao.deletedDocumentIds)
        documentIds.forEach { id -> assertFalse(systemFileSystem().exists(coverFilePath(fileSource, id))) }
    }

    private fun newRepository(
        documentDao: DocumentDao,
        pageLayoutDao: PageLayoutDao = RecordingPageLayoutDao(),
        searchIndexDao: SearchIndexDao = RecordingSearchIndexDao(),
        documentFileSource: DocumentFileSource? = null,
    ) = DocumentRepositoryImpl(
        documentDao = documentDao,
        searchIndexDao = searchIndexDao,
        pageLayoutDao = pageLayoutDao,
        formatDetector = DocumentFormatDetector(),
        txtDocumentParser = TxtDocumentParser(),
        epubDocumentParser = EpubDocumentParser(),
        pdfDocumentParser = PdfDocumentParser(),
        comicBookDocumentParser = ComicBookDocumentParser(),
        imageDocumentParser = ImageDocumentParser(),
        textPageLayoutEngine = TextPageLayoutEngine(),
        documentFileSource = documentFileSource,
    )

    private fun documentEntity(id: DocumentId) = DocumentEntity(
        id = id.value,
        name = id.value.substringAfterLast('/'),
        sourceUri = id.value,
        format = DocumentFormat.EPUB.name,
        addedAtEpochMillis = 1,
    )
}

private class RecordingDocumentDao(
    val documents: MutableMap<DocumentId, DocumentEntity> = mutableMapOf(),
) : DocumentDao {
    var bookmarkedCalled = false
    var folderCalled = false
    var bulkDeleteCalled = false
    var lastBookmarkedIds: List<String>? = null
    var lastBookmarkedValue: Boolean = false
    var lastFolderIds: List<String>? = null
    var lastFolderId: String? = null
    var lastFolderName: String? = null
    var renamedFolderId: String? = null
    var renamedFolderName: String? = null
    var clearedFolderId: String? = null
    var bulkDeletedIds: List<String>? = null

    override suspend fun upsertDocument(document: DocumentEntity) {
        documents[DocumentId(document.id)] = document
    }

    override suspend fun getDocument(documentId: String): DocumentEntity? = documents[DocumentId(documentId)]
    override fun observeRecentDocuments(): Flow<List<DocumentEntity>> = flowOf(documents.values.toList())
    override suspend fun updateBookmarked(documentIds: List<String>, isBookmarked: Boolean) {
        bookmarkedCalled = true
        lastBookmarkedIds = documentIds
        lastBookmarkedValue = isBookmarked
    }
    override suspend fun updateFolder(documentIds: List<String>, folderId: String?, folderName: String?) {
        folderCalled = true
        lastFolderIds = documentIds
        lastFolderId = folderId
        lastFolderName = folderName
    }
    override suspend fun renameFolder(folderId: String, folderName: String) {
        renamedFolderId = folderId
        renamedFolderName = folderName
    }
    override suspend fun clearFolder(folderId: String) {
        clearedFolderId = folderId
    }
    override suspend fun updateLastOpenedAt(documentId: String, openedAtEpochMillis: Long) = Unit
    override suspend fun deleteDocument(documentId: String) { documents.remove(DocumentId(documentId)) }
    override suspend fun deleteDocuments(documentIds: List<String>) {
        bulkDeleteCalled = true
        bulkDeletedIds = documentIds
        documentIds.forEach { documents.remove(DocumentId(it)) }
    }
}

private class RecordingSearchIndexDao : SearchIndexDao {
    override suspend fun upsertSearchIndex(entries: List<com.tedd.teddreader.core.room.entity.SearchIndexEntity>) = Unit
    override suspend fun search(documentId: String, query: String, limit: Int) = emptyList<com.tedd.teddreader.core.room.entity.SearchIndexEntity>()
    override suspend fun getDocumentSectionsWithoutBlocks(documentId: String) = emptyList<com.tedd.teddreader.core.room.dao.SearchIndexSectionEntry>()
    override suspend fun getSectionBlocksJson(documentId: String, sectionIndexes: List<Int>) = emptyList<com.tedd.teddreader.core.room.dao.SectionBlocksJsonEntry>()
    override suspend fun getLastSection(documentId: String) = null
    override suspend fun updateSectionTitle(documentId: String, sectionIndex: Int, title: String) = Unit
    override suspend fun updateDocumentTitleAndNavigation(documentId: String, sectionIndex: Int, documentTitle: String, navigationJson: String) = Unit
    override suspend fun deleteSearchIndex(documentId: String) = Unit
}

private class RecordingPageLayoutDao : PageLayoutDao {
    val deletedDocumentIds = mutableListOf<String>()
    override suspend fun upsertPageLayout(layout: PageLayoutEntity) = Unit
    override suspend fun getPageLayout(documentId: String, fontSizeSp: Float, lineHeightMultiplier: Float, fontFamilyName: String, viewportWidthPx: Int, viewportHeightPx: Int): PageLayoutEntity? = null
    override suspend fun getNewestPageLayoutForStyle(documentId: String, fontSizeSp: Float, lineHeightMultiplier: Float, fontFamilyName: String): PageLayoutEntity? = null
    override suspend fun deletePageLayouts(documentId: String) { deletedDocumentIds += documentId }
    override suspend fun trimPageLayouts(documentId: String, keep: Int) = Unit
}

private class BatchCrudDocumentFileSource : DocumentFileSource {
    private val root = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "teddreader-batch-crud-test").also {
        systemFileSystem().createDirectories(it)
    }

    override suspend fun readBytes(location: DocumentLocation): ByteArray = byteArrayOf()
    override suspend fun copyTo(location: DocumentLocation, destination: okio.Path) = Unit
    override fun appPrivateDirectory() = root
}

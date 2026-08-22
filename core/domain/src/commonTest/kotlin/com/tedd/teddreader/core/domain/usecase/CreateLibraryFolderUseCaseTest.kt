package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CreateLibraryFolderUseCaseTest {
    @Test
    fun trimsNameGeneratesIdAndWritesMembership() = runTest {
        val documents = RecordingDocuments()
        val useCase = CreateLibraryFolderUseCase(documents) { "folder-123" }

        val folderId = useCase("  Weekend Reads  ", listOf(DocumentId("a"), DocumentId("b")))

        assertEquals("folder-123", folderId)
        assertEquals(listOf(DocumentId("a"), DocumentId("b")), documents.lastDocumentIds)
        assertEquals("folder-123", documents.lastFolderId)
        assertEquals("Weekend Reads", documents.lastFolderName)
    }

    @Test
    fun blankNameOrNoDocumentsDoesNothing() = runTest {
        val documents = RecordingDocuments()
        val useCase = CreateLibraryFolderUseCase(documents) { "folder-123" }

        assertEquals("", useCase("   ", listOf(DocumentId("a"))))
        assertEquals("", useCase("Name", emptyList()))
        assertNull(documents.lastFolderId)
    }

    private class RecordingDocuments : DocumentRepository {
        var lastDocumentIds: Collection<DocumentId>? = null
        var lastFolderId: String? = null
        var lastFolderName: String? = null

        override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> = flowOf(emptyList())
        override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? = null
        override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? = null
        override suspend fun getPageWindows(documentId: DocumentId, style: ReaderStyle, viewportSize: ViewportSize?, pageBreaker: ReaderPageBreaker?, anchorOffset: Long?): List<PageWindow> = emptyList()
        override suspend fun importDocument(source: DocumentImportSource, importedAtEpochMillis: Long): ReaderDocument = error("unused")
        override suspend fun upsertDocument(document: DocumentMetadata) = Unit
        override suspend fun setDocumentsFolder(documentIds: Collection<DocumentId>, folderId: String?, folderName: String?) {
            lastDocumentIds = documentIds
            lastFolderId = folderId
            lastFolderName = folderName
        }
        override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) = Unit
        override suspend fun deleteDocument(documentId: DocumentId) = Unit
    }
}

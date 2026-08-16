package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ViewportSize
import kotlinx.coroutines.flow.Flow

class DocumentImportSource(
    val location: DocumentLocation,
    val bytes: ByteArray?,
) {
    init {
        require(bytes == null || bytes.isNotEmpty()) { "Document bytes must not be empty." }
    }
}

interface DocumentRepository {
    fun observeRecentDocuments(): Flow<List<DocumentMetadata>>
    suspend fun getDocument(documentId: DocumentId): DocumentMetadata?
    suspend fun getDocumentCover(documentId: DocumentId): ByteArray? = null
    suspend fun getVisualPageImages(
        documentId: DocumentId,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> = emptyMap()
    suspend fun getEmbeddedImages(
        documentId: DocumentId,
        hrefs: Set<String>,
    ): Map<String, ByteArray> = emptyMap()
    suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument?
    suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker? = null,
    ): List<PageWindow>
    suspend fun importDocument(source: DocumentImportSource, importedAtEpochMillis: Long): ReaderDocument
    suspend fun upsertDocument(document: DocumentMetadata)
    suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long)
    suspend fun deleteDocument(documentId: DocumentId)
}

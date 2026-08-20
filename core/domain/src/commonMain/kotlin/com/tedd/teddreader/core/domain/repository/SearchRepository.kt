package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.SearchResult

/**
 * Searching a document that is already stored.
 *
 * Writing is deliberately absent. The table the search reads is the same one that holds every
 * document's section text and block structure, written once when the document is imported, so a
 * second writer here could only overwrite the stored document with less than it had.
 */
interface SearchRepository {
    suspend fun findInDocument(
        documentId: DocumentId,
        query: String,
        limit: Int = 50,
    ): List<SearchResult>
}

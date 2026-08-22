package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.SearchResult

/**
 * Searching a document that is already stored.
 *
 * Writing is deliberately absent. The table the search reads is the same one that holds every document's
 * section text and block structure, written once when the document is imported, so a second writer here
 * could only overwrite the stored document with less than it had.
 */
interface SearchRepository {
    /**
     * Finds occurrences of [query] in one document's stored text.
     *
     * The query is taken as typed and normalised here rather than by every caller: surrounding space is
     * trimmed, a query that is blank once trimmed matches nothing rather than everything, and a [limit]
     * below one is read as one — a caller asking for a search is asking for a result. Both guarantees used
     * to live in a use case that did nothing else, which meant the same three lines guarded the same call
     * twice; they belong to the search itself, so a caller reaching this interface directly gets them too.
     *
     * @param documentId the document to search; a document with no stored text yields nothing.
     * @param query what to look for, as the reader typed it.
     * @param limit the greatest number of occurrences to return, counted across the whole document rather
     * than per section.
     * @return the matches in reading order, each carrying the position to jump to and the exact span to
     * highlight; empty for a blank query or a document with nothing to match.
     */
    suspend fun findInDocument(
        documentId: DocumentId,
        query: String,
        limit: Int = 50,
    ): List<SearchResult>
}

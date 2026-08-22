package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.data.mapper.toSearchResults
import com.tedd.teddreader.core.domain.repository.SearchRepository
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import org.koin.core.annotation.Single

/**
 * [SearchRepository] backed by Room's full-text index: the implementation that actually honours the
 * trimming, blank-query, and limit-floor guarantees the [SearchRepository] contract promises callers,
 * since [searchIndexDao] itself only knows how to filter and sort stored section rows, not occurrences
 * within them.
 *
 * @property searchIndexDao Source of the per-section stored text a query is matched against.
 */
@Single(binds = [SearchRepository::class])
class SearchRepositoryImpl(
    private val searchIndexDao: SearchIndexDao,
) : SearchRepository {
    /**
     * Finds every occurrence of [query] in [documentId]'s stored text, in reading order.
     *
     * [query] is trimmed before anything else runs, and a query that is blank once trimmed matches
     * nothing — it never reaches [searchIndexDao] — rather than being treated as "no filter" and
     * matching everything. [searchIndexDao] is asked for at most `limit` matching *sections*, each of
     * which [toSearchResults] then expands into however many occurrences it actually contains; because
     * every section [searchIndexDao] returns is guaranteed to contain at least one match, that always
     * yields at least as many occurrences as sections requested, so trimming the flattened list down to
     * `limit` afterwards is what turns a section-count limit into the occurrence-count limit callers
     * actually asked for.
     *
     * @param documentId The document to search.
     * @param query The text to search for; leading/trailing whitespace is ignored.
     * @param limit The maximum number of occurrences to return; a value below one is read as one, since
     *   a caller asking for a search is asking for at least one result.
     * @return Matching [SearchResult]s in document order, or an empty list if [query] is blank once
     *   trimmed or nothing matches.
     */
    override suspend fun findInDocument(
        documentId: DocumentId,
        query: String,
        limit: Int,
    ): List<SearchResult> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return emptyList()

        return searchIndexDao
            .search(documentId.value, trimmedQuery, limit.coerceAtLeast(1))
            .flatMap { entry -> entry.toSearchResults(trimmedQuery) }
            .take(limit.coerceAtLeast(1))
    }
}

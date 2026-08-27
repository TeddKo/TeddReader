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
     * which can contain many occurrences. Each section receives only the document-wide result budget
     * still unfilled, and scanning stops the moment that budget reaches zero, so a dense first chapter
     * cannot allocate thousands of [SearchResult]s and snippets that a final `take(limit)` would discard.
     * Every returned section contains at least one SQL match, so asking for `limit` sections is still
     * sufficient to fill an occurrence limit of the same size whenever that many occurrences exist.
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

        val effectiveLimit = limit.coerceAtLeast(1)
        val entries = searchIndexDao.search(documentId.value, trimmedQuery, effectiveLimit)
        return buildList(capacity = minOf(effectiveLimit, 16)) {
            for (entry in entries) {
                addAll(entry.toSearchResults(trimmedQuery, limit = effectiveLimit - size))
                if (size >= effectiveLimit) break
            }
        }
    }
}

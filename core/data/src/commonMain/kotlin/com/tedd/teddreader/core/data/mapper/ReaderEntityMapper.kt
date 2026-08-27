package com.tedd.teddreader.core.data.mapper

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderNavigation
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.parseReaderLocation
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.domain.repository.ReadingSession
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import com.tedd.teddreader.core.room.dao.SearchIndexSearchEntry
import com.tedd.teddreader.core.room.entity.BookmarkEntity
import com.tedd.teddreader.core.room.entity.ReadingProgressEntity
import com.tedd.teddreader.core.room.entity.ReadingSessionEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity
import kotlinx.serialization.json.Json

/**
 * Rehydrates a stored reading position into the domain [ReadingProgress] the reader actually works
 * with, parsing the flat [ReaderLocation] string Room stores back into its typed form.
 *
 * @receiver The Room row for one document's saved reading position.
 * @return The equivalent [ReadingProgress].
 */
fun ReadingProgressEntity.toReadingProgress(): ReadingProgress = ReadingProgress(
    documentId = DocumentId(documentId),
    location = parseReaderLocation(readerLocation),
    pageIndex = PageIndex(currentPageIndex, totalPageCount ?: 0),
    updatedAtEpochMillis = updatedAtEpochMillis,
)

/**
 * Flattens a [ReadingProgress] into the row shape Room stores it as, the inverse of
 * [toReadingProgress]. [ReaderLocation] has no Room-friendly representation of its own, so it is
 * serialized to a plain string via [asStorageString] before it can become a column.
 *
 * @receiver The reading position to persist.
 * @return The equivalent [ReadingProgressEntity] row.
 */
fun ReadingProgress.toReadingProgressEntity(): ReadingProgressEntity = ReadingProgressEntity(
    documentId = documentId.value,
    readerLocation = location.asStorageString(),
    currentPageIndex = pageIndex.current,
    totalPageCount = pageIndex.total,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

/**
 * Rehydrates a stored bookmark row into the domain [Bookmark] the reader displays.
 *
 * @receiver The Room row for one saved bookmark.
 * @return The equivalent [Bookmark].
 */
fun BookmarkEntity.toBookmark(): Bookmark = Bookmark(
    id = id,
    documentId = DocumentId(documentId),
    location = parseReaderLocation(readerLocation),
    label = label,
    note = note,
    createdAtEpochMillis = createdAtEpochMillis,
)

/**
 * Flattens a [Bookmark] into the row shape Room stores it as, the inverse of [toBookmark].
 *
 * @receiver The bookmark to persist.
 * @return The equivalent [BookmarkEntity] row.
 */
fun Bookmark.toBookmarkEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    documentId = documentId.value,
    readerLocation = location.asStorageString(),
    label = label,
    note = note,
    createdAtEpochMillis = createdAtEpochMillis,
)

/**
 * Rehydrates one logged reading session into the domain [ReadingSession] reading stats are computed
 * from. `endLocation` stays `null` for a session that has no recorded end position rather than being
 * parsed into some placeholder value.
 *
 * @receiver The Room row for one reading session.
 * @return The equivalent [ReadingSession].
 */
fun ReadingSessionEntity.toReadingSession(): ReadingSession = ReadingSession(
    id = id,
    documentId = DocumentId(documentId),
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    activeMillis = activeMillis,
    startLocation = parseReaderLocation(startLocation),
    endLocation = endLocation?.let(::parseReaderLocation),
)

/**
 * Flattens a [ReadingSession] into the row shape Room stores it as, the inverse of
 * [toReadingSession].
 *
 * @receiver The reading session to persist.
 * @return The equivalent [ReadingSessionEntity] row.
 */
fun ReadingSession.toReadingSessionEntity(): ReadingSessionEntity = ReadingSessionEntity(
    id = id,
    documentId = documentId.value,
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    activeMillis = activeMillis,
    startLocation = startLocation.asStorageString(),
    endLocation = endLocation?.asStorageString(),
)

/**
 * Builds the search-index row a section is stored as, at the moment a document is imported or
 * repaired. The section's own text and offsets carry over as-is; [blocks] and [navigation] are
 * serialized to JSON because Room has no column type for them, and [documentTitle] is duplicated onto
 * every section's row (rather than looked up separately) so a search result can show which book it
 * came from without a join. The row is tagged with [CurrentReaderParserVersion] so a later parser
 * upgrade can tell which rows were written by an older parser and need re-importing.
 *
 * @receiver The section being indexed.
 * @param documentId The document this section belongs to.
 * @param blocks This section's block structure, serialized into the row as JSON.
 * @param documentTitle The owning document's title, denormalized onto this row for display; `null`
 *   when the caller does not have it at hand yet.
 * @param navigation The document's table of contents, serialized into the row as JSON; `null` when the
 *   caller does not have it at hand yet, which is stored as an empty string rather than as JSON `null`.
 * @param json The [Json] instance used to serialize [blocks] and [navigation].
 * @param sourcePath The archive-relative path of the spine item this section was parsed from, stored
 *   so `finishEpubImport` can resolve navigation without re-reading all section text; null for
 *   non-EPUB documents.
 * @return The [SearchIndexEntity] row to upsert for this section.
 */
fun ReaderSection.toSearchIndexEntity(
    documentId: DocumentId,
    blocks: List<ReaderBlock> = emptyList(),
    documentTitle: String? = null,
    navigation: ReaderNavigation? = null,
    json: Json = Json,
    sourcePath: String? = null,
): SearchIndexEntity = SearchIndexEntity(
    documentId = documentId.value,
    sectionIndex = index,
    sectionTitle = title,
    text = text,
    startOffset = range.start,
    endOffset = range.end,
    blocksJson = json.encodeToString(blocks),
    documentTitle = documentTitle,
    navigationJson = navigation?.let { json.encodeToString(it) }.orEmpty(),
    parserVersion = CurrentReaderParserVersion,
    sourcePath = sourcePath,
)

/**
 * Bumped whenever the parsers start producing something the reader needs but older stored text lacks —
 * image proportions, stylesheet-derived block styles, pictures kept inside their sentence. Stored rows
 * written by an earlier build are re-read from the file the next time the book is opened.
 *
 * Version 2 is section-relative block storage (DocumentRepositoryImpl.persistParsedDocument/
 * importNextSections). Bumping to it was held back while `repairEpubDocument` still read the whole file
 * and parsed every chapter before the reader could draw, because that would have handed every book
 * already on the shelf a 20-40s wall on its next open. That path now goes through the same phased import
 * a newly picked EPUB takes, so a book below this version shows its first chapter as fast as a fresh one
 * and finishes in the background — which is what makes a bump cost about as little as it ever will.
 *
 * Version 3 adds inline-CSS span preservation and float-image fallback/width repair, so stale stored
 * blocks must be reparsed before an already-imported EPUB can render those fixes.
 *
 * Version 4 adds publisher color/font/box styling, hidden-subtree stripping, decorated container ranges,
 * and inline floated-image preservation, so older stored sections must be reparsed to recover those
 * richer blocks and spans.
 *
 * Version 5 bumps that richer schema again now that body/html background containers and publisher float/
 * border/color decoding changed shape; stored rows below this version must be repaired before those
 * blocks can surface consistently.
 *
 * A repair does re-read the book's text, so character offsets can move; stored page layouts are dropped
 * on the character-count mismatch that follows (see DocumentRepositoryImpl.restorePageWindows) and the
 * reading position lands on the nearest page rather than the exact one. That is the price of a bump and
 * the reason not to make one for anything the reader does not actually need.
 */
const val CurrentReaderParserVersion: Int = 9

/**
 * Every non-overlapping occurrence of [query] in this section's stored text, in document order.
 *
 * A section's row can hold the same word many times over, and each occurrence is its own
 * [SearchResult] rather than one result per section, so a caller can jump straight to any of them.
 * Matching is case-insensitive and advances past each match before looking for the next one, so an
 * occurrence never overlaps the one before it (searching "aa" in "aaa" finds one match, not two). An
 * empty [query] would otherwise loop forever advancing by zero characters each time, so it is rejected
 * up front and yields no results instead.
 *
 * @receiver The stored section row to search within.
 * @param query The text to find; must be non-empty for any results to come back.
 * @return Matching [SearchResult]s in the order they occur in [text], or an empty list if [query] is
 *   empty or does not occur.
 */
fun SearchIndexSearchEntry.toSearchResults(query: String): List<SearchResult> {
    if (query.isEmpty()) return emptyList()
    return buildList {
        var searchStartIndex = 0
        while (searchStartIndex <= text.length - query.length) {
            val matchIndex = text.indexOf(query, startIndex = searchStartIndex, ignoreCase = true)
            if (matchIndex < 0) break

            val matchStart = startOffset + matchIndex
            val matchEnd = (matchStart + query.length).coerceAtMost(endOffset)
            add(
                SearchResult(
                    documentId = DocumentId(documentId),
                    snippet = text.snippetAround(matchIndex, query.length),
                    location = ReaderLocation.TextOffset(matchStart),
                    sectionTitle = sectionTitle,
                    range = TextRange(matchStart, matchEnd),
                    query = query,
                ),
            )
            searchStartIndex = matchIndex + query.length
        }
    }
}

/**
 * The window of surrounding text a search result shows around its match, so a result reads as a
 * snippet of context rather than either the bare matched word or the section's entire text.
 *
 * @receiver The section text the match was found in.
 * @param matchIndex Index into this string where the match starts.
 * @param matchLength Length of the matched text.
 * @return Up to [SNIPPET_RADIUS] characters on either side of the match, clipped to this string's
 *   bounds rather than padded when the match is near either end.
 */
private fun String.snippetAround(matchIndex: Int, matchLength: Int): String {
    val start = (matchIndex - SNIPPET_RADIUS).coerceAtLeast(0)
    val end = (matchIndex + matchLength + SNIPPET_RADIUS).coerceAtMost(length)
    return substring(start, end)
}

/** Characters of context [snippetAround] keeps on each side of a match. */
private const val SNIPPET_RADIUS = 40

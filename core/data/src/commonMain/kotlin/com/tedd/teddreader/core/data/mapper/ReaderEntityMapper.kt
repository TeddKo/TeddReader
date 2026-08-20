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
import com.tedd.teddreader.core.room.entity.BookmarkEntity
import com.tedd.teddreader.core.room.entity.ReadingProgressEntity
import com.tedd.teddreader.core.room.entity.ReadingSessionEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity
import kotlinx.serialization.json.Json

fun ReadingProgressEntity.toReadingProgress(): ReadingProgress = ReadingProgress(
    documentId = DocumentId(documentId),
    location = parseReaderLocation(readerLocation),
    pageIndex = PageIndex(currentPageIndex, totalPageCount ?: 0),
    updatedAtEpochMillis = updatedAtEpochMillis,
)

fun ReadingProgress.toReadingProgressEntity(): ReadingProgressEntity = ReadingProgressEntity(
    documentId = documentId.value,
    readerLocation = location.asStorageString(),
    currentPageIndex = pageIndex.current,
    totalPageCount = pageIndex.total,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

fun BookmarkEntity.toBookmark(): Bookmark = Bookmark(
    id = id,
    documentId = DocumentId(documentId),
    location = parseReaderLocation(readerLocation),
    label = label,
    note = note,
    createdAtEpochMillis = createdAtEpochMillis,
)

fun Bookmark.toBookmarkEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    documentId = documentId.value,
    readerLocation = location.asStorageString(),
    label = label,
    note = note,
    createdAtEpochMillis = createdAtEpochMillis,
)

fun ReadingSessionEntity.toReadingSession(): ReadingSession = ReadingSession(
    id = id,
    documentId = DocumentId(documentId),
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    activeMillis = activeMillis,
    startLocation = parseReaderLocation(startLocation),
    endLocation = endLocation?.let(::parseReaderLocation),
)

fun ReadingSession.toReadingSessionEntity(): ReadingSessionEntity = ReadingSessionEntity(
    id = id,
    documentId = documentId.value,
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    activeMillis = activeMillis,
    startLocation = startLocation.asStorageString(),
    endLocation = endLocation?.asStorageString(),
)

fun ReaderSection.toSearchIndexEntity(
    documentId: DocumentId,
    blocks: List<ReaderBlock> = emptyList(),
    documentTitle: String? = null,
    navigation: ReaderNavigation? = null,
    json: Json = Json,
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
 * A repair does re-read the book's text, so character offsets can move; stored page layouts are dropped
 * on the character-count mismatch that follows (see DocumentRepositoryImpl.restorePageWindows) and the
 * reading position lands on the nearest page rather than the exact one. That is the price of a bump and
 * the reason not to make one for anything the reader does not actually need.
 */
const val CurrentReaderParserVersion: Int = 2

fun SearchIndexEntity.toSearchResults(query: String): List<SearchResult> {
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

private fun String.snippetAround(matchIndex: Int, matchLength: Int): String {
    val start = (matchIndex - SNIPPET_RADIUS).coerceAtLeast(0)
    val end = (matchIndex + matchLength + SNIPPET_RADIUS).coerceAtMost(length)
    return substring(start, end)
}

private const val SNIPPET_RADIUS = 40

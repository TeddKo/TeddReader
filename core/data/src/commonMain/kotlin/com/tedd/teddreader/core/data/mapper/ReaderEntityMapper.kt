package com.tedd.teddreader.core.data.mapper

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderLocation
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

fun ReaderSection.toSearchIndexEntity(documentId: DocumentId): SearchIndexEntity = SearchIndexEntity(
    documentId = documentId.value,
    sectionIndex = index,
    sectionTitle = title,
    text = text,
    startOffset = range.start,
    endOffset = range.end,
)

fun SearchIndexEntity.toSearchResult(query: String): SearchResult = SearchResult(
    documentId = DocumentId(documentId),
    snippet = text.snippetAround(query),
    location = ReaderLocation.TextOffset(startOffset),
    sectionTitle = sectionTitle,
    range = TextRange(startOffset, endOffset),
)

private fun String.snippetAround(query: String): String {
    val index = indexOf(query, ignoreCase = true).takeIf { it >= 0 } ?: return take(SNIPPET_LENGTH)
    val start = (index - SNIPPET_RADIUS).coerceAtLeast(0)
    val end = (index + query.length + SNIPPET_RADIUS).coerceAtMost(length)
    return substring(start, end)
}

private const val SNIPPET_RADIUS = 40
private const val SNIPPET_LENGTH = 120

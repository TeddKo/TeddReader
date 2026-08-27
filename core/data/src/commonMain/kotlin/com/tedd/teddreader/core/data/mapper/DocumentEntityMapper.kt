package com.tedd.teddreader.core.data.mapper

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.room.entity.DocumentEntity

/**
 * Reads a library row into the domain's own metadata type.
 *
 * The format is matched by name and falls back to [DocumentFormat.UNKNOWN] for a value this build does not
 * know: a row written by a newer version must still list rather than crash the library. A null stored size
 * becomes 0 because [DocumentLocation] treats size as a number, not as an optional fact.
 *
 * When `importCompletedAtEpochMillis` is null — meaning the import has not finished — character and word
 * counts are masked to null in the domain model, even though the entity carries running accumulators.
 * This preserves the existing domain contract: null counts mean "not yet known," and callers that display
 * statistics (the document-info sheet) treat them accordingly.
 *
 * @receiver the stored row.
 * @return the same document as the domain sees it.
 */
fun DocumentEntity.toDocumentMetadata(): DocumentMetadata {
    val importComplete = importCompletedAtEpochMillis != null
    return DocumentMetadata(
        id = DocumentId(id),
        location = DocumentLocation(
            sourceUri = sourceUri,
            displayName = name,
            mimeType = mimeType,
            sizeBytes = sizeBytes ?: 0L,
        ),
        format = DocumentFormat.entries.firstOrNull { it.name == format } ?: DocumentFormat.UNKNOWN,
        addedAtEpochMillis = addedAtEpochMillis,
        lastOpenedAtEpochMillis = lastOpenedAtEpochMillis,
        pageCount = pageCount,
        characterCount = if (importComplete) characterCount else null,
        wordCount = if (importComplete) wordCount else null,
        isBookmarked = isBookmarked,
        folderId = folderId,
        folderName = folderName,
    )
}

/**
 * Writes domain metadata back into a library row.
 *
 * The inverse of [toDocumentMetadata] except for `importCompletedAtEpochMillis`, which no domain type
 * carries: the repository owns that column, so a write from here leaves it at its default and must not be
 * used to update a document mid-import.
 *
 * @receiver the metadata to store.
 * @return the row to upsert.
 */
fun DocumentMetadata.toDocumentEntity(): DocumentEntity = DocumentEntity(
    id = id.value,
    name = location.displayName,
    sourceUri = location.sourceUri,
    format = format.name,
    mimeType = location.mimeType,
    sizeBytes = location.sizeBytes,
    addedAtEpochMillis = addedAtEpochMillis,
    lastOpenedAtEpochMillis = lastOpenedAtEpochMillis,
    pageCount = pageCount,
    characterCount = characterCount,
    wordCount = wordCount,
    isBookmarked = isBookmarked,
    folderId = folderId,
    folderName = folderName,
)

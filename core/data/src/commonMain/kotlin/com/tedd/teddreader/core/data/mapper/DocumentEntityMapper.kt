package com.tedd.teddreader.core.data.mapper

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.room.entity.DocumentEntity

fun DocumentEntity.toDocumentMetadata(): DocumentMetadata = DocumentMetadata(
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
    characterCount = characterCount,
    wordCount = wordCount,
    isBookmarked = isBookmarked,
    folderId = folderId,
    folderName = folderName,
)

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

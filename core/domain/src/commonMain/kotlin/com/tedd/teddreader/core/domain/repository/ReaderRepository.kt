package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderLocation
import kotlinx.coroutines.flow.Flow

data class ReadingProgress(
    val documentId: DocumentId,
    val location: ReaderLocation,
    val pageIndex: PageIndex,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(updatedAtEpochMillis >= 0L) { "updatedAtEpochMillis must be positive." }
    }
}

interface ReaderRepository {
    fun observeProgress(documentId: DocumentId): Flow<ReadingProgress?>
    suspend fun getProgress(documentId: DocumentId): ReadingProgress?
    suspend fun saveProgress(progress: ReadingProgress)
    suspend fun deleteProgress(documentId: DocumentId)
}

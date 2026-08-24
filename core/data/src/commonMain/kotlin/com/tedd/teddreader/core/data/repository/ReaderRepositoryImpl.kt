package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.data.mapper.toReadingProgress
import com.tedd.teddreader.core.data.mapper.toReadingProgressEntity
import com.tedd.teddreader.core.room.dao.ReadingProgressDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

/**
 * [ReaderRepository] backed by Room: the only place a document's reading position is read from or
 * written to storage. Every method here does the same two things — translate [DocumentId] to the
 * plain string key Room stores, and translate between Room's progress entity and the domain
 * [ReadingProgress] via [toReadingProgress]/[toReadingProgressEntity] — so nothing above this layer
 * (view models, use cases) needs to know Room exists.
 *
 * @property progressDao The DAO this repository delegates every read and write to.
 */
@Single(binds = [ReaderRepository::class])
class ReaderRepositoryImpl(
    private val progressDao: ReadingProgressDao,
) : ReaderRepository {
    /**
     * Live view of [documentId]'s saved reading position, updating whenever it is saved again.
     *
     * @param documentId The document whose progress to observe.
     * @return A [Flow] of the current [ReadingProgress], or `null` while nothing has been saved yet.
     */
    override fun observeProgress(documentId: DocumentId): Flow<ReadingProgress?> =
        progressDao.observeProgress(documentId.value).map { it?.toReadingProgress() }

    /**
     * The last saved reading position for [documentId], read once rather than observed.
     *
     * @param documentId The document to look up.
     * @return The stored [ReadingProgress], or `null` if this document has never been saved.
     */
    override suspend fun getProgress(documentId: DocumentId): ReadingProgress? =
        progressDao.getProgress(documentId.value)?.toReadingProgress()

    /**
     * Persists [progress] as the current reading position for its document, replacing whatever was
     * saved before it.
     *
     * @param progress The position to save; its `documentId` selects which document's row is replaced.
     */
    override suspend fun saveProgress(progress: ReadingProgress) {
        progressDao.upsertProgress(progress.toReadingProgressEntity())
    }

    /**
     * Removes any saved reading position for [documentId], e.g. once the document itself is deleted.
     *
     * @param documentId The document whose saved progress should be discarded.
     */
    override suspend fun deleteProgress(documentId: DocumentId) {
        progressDao.deleteProgress(documentId.value)
    }
}

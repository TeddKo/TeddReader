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

@Single(binds = [ReaderRepository::class])
class ReaderRepositoryImpl(
    private val progressDao: ReadingProgressDao,
) : ReaderRepository {
    override fun observeProgress(documentId: DocumentId): Flow<ReadingProgress?> =
        progressDao.observeProgress(documentId.value).map { it?.toReadingProgress() }

    override suspend fun getProgress(documentId: DocumentId): ReadingProgress? =
        progressDao.getProgress(documentId.value)?.toReadingProgress()

    override suspend fun saveProgress(progress: ReadingProgress) {
        progressDao.upsertProgress(progress.toReadingProgressEntity())
    }

    override suspend fun deleteProgress(documentId: DocumentId) {
        progressDao.deleteProgress(documentId.value)
    }
}

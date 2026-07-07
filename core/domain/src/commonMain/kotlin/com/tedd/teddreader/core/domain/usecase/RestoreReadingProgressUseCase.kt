package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import org.koin.core.annotation.Single

@Single
class RestoreReadingProgressUseCase(
    private val readerRepository: ReaderRepository,
) {
    suspend operator fun invoke(documentId: DocumentId): ReadingProgress? =
        readerRepository.getProgress(documentId)
}

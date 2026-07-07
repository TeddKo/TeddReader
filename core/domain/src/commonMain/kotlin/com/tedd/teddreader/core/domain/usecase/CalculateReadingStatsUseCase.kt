package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReadingStats
import com.tedd.teddreader.core.domain.repository.ReadingStatsRepository
import org.koin.core.annotation.Single

@Single
class CalculateReadingStatsUseCase(
    private val readingStatsRepository: ReadingStatsRepository,
) {
    suspend operator fun invoke(documentId: DocumentId): ReadingStats =
        readingStatsRepository.getStats(documentId)
}

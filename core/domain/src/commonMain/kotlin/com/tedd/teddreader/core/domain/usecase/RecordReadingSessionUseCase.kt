package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.domain.repository.ReadingSession
import com.tedd.teddreader.core.domain.repository.ReadingStatsRepository
import org.koin.core.annotation.Single

@Single
class RecordReadingSessionUseCase(
    private val readingStatsRepository: ReadingStatsRepository,
) {
    suspend operator fun invoke(session: ReadingSession) {
        readingStatsRepository.recordSession(session)
    }
}

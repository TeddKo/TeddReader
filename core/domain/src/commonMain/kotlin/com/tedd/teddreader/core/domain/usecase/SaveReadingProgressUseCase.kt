package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import org.koin.core.annotation.Single

@Single
class SaveReadingProgressUseCase(
    private val readerRepository: ReaderRepository,
) {
    suspend operator fun invoke(progress: ReadingProgress) {
        readerRepository.saveProgress(progress)
    }
}

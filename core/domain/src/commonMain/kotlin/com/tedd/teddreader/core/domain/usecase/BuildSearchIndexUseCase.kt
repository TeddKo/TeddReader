package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.domain.repository.SearchRepository
import org.koin.core.annotation.Single

@Single
class BuildSearchIndexUseCase(
    private val searchRepository: SearchRepository,
) {
    suspend operator fun invoke(document: ReaderDocument) {
        searchRepository.indexDocument(document)
    }
}

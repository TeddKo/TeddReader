package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import org.koin.core.annotation.Single

@Single
class OpenDocumentUseCase(
    private val documentRepository: DocumentRepository,
) {
    suspend operator fun invoke(
        source: DocumentImportSource,
        openedAtEpochMillis: Long,
    ): ReaderDocument = documentRepository.importDocument(source, openedAtEpochMillis)
}

package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import kotlin.random.Random

/** Validates a new folder request, generates its id, then writes membership in one repository call. */
class CreateLibraryFolderUseCase(
    private val documentRepository: DocumentRepository,
    private val folderIdGenerator: () -> String = ::generatedFolderId,
) {
    suspend operator fun invoke(name: String, documentIds: Collection<DocumentId>): String {
        val trimmedName = name.trim()
        if (trimmedName.isBlank() || documentIds.isEmpty()) return ""
        val folderId = folderIdGenerator()
        documentRepository.setDocumentsFolder(documentIds, folderId, trimmedName)
        return folderId
    }
}

private fun generatedFolderId(): String =
    "folder-${Random.nextLong().toULong().toString(16)}-${Random.nextLong().toULong().toString(16)}"

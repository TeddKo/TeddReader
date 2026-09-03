package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import kotlin.random.Random
import org.koin.core.annotation.Single

/** 새 폴더 요청을 검증하고 식별자를 생성한 뒤 한 번의 저장소 호출로 소속을 기록한다. */
@Single
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

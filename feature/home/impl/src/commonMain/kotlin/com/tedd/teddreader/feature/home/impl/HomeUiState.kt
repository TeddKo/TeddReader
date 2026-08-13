package com.tedd.teddreader.feature.home.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.DocumentMetadata

@Immutable
data class HomeUiState(
    val favoriteDocuments: List<DocumentMetadata> = emptyList(),
    val recentDocuments: List<DocumentMetadata> = emptyList(),
    val libraryDocuments: List<DocumentMetadata> = emptyList(),
    val libraryFolders: List<LibraryFolder> = emptyList(),
    val documentCoverImages: Map<String, ByteArray> = emptyMap(),
    val hasDocuments: Boolean = false,
    val sort: HomeSort = HomeSort.Recent,
    val formatFilter: HomeFormatFilter = HomeFormatFilter.All,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val unsupportedFormatMessage: String? = null,
)

@Immutable
data class LibraryFolder(
    val id: String,
    val name: String,
    val documentCount: Int,
)

enum class HomeSort {
    Recent,
    Title,
    Format,
}

enum class HomeFormatFilter {
    All,
    Txt,
    Pdf,
    Epub,
}

enum class LibraryCollectionMode {
    All,
    Folders,
}

internal fun homeLibraryPreviewLimit(
    isExpanded: Boolean,
    isTablet: Boolean,
    hasSeparatingFold: Boolean,
): Int = if (isExpanded || isTablet || hasSeparatingFold) 8 else 4

internal fun homeLibraryPreviewDocuments(
    documents: List<DocumentMetadata>,
    previewLimit: Int,
): List<DocumentMetadata> = documents.take(previewLimit)

internal fun buildLibraryFolders(documents: List<DocumentMetadata>): List<LibraryFolder> =
    documents
        .filter { it.folderId != null && it.folderName != null }
        .groupBy { it.folderId!! }
        .mapNotNull { (folderId, folderDocuments) ->
            val folderName = folderDocuments.firstOrNull()?.folderName ?: return@mapNotNull null
            LibraryFolder(
                id = folderId,
                name = folderName,
                documentCount = folderDocuments.size,
            )
        }
        .sortedBy { it.name.lowercase() }

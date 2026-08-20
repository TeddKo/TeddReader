package com.tedd.teddreader.feature.home.impl

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.ui.system.DisplayFold
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList

@Immutable
data class HomeUiState(
    val favoriteDocuments: ImmutableList<DocumentMetadata> = persistentListOf(),
    val recentDocuments: ImmutableList<DocumentMetadata> = persistentListOf(),
    val libraryDocuments: ImmutableList<DocumentMetadata> = persistentListOf(),
    val libraryFolders: ImmutableList<LibraryFolder> = persistentListOf(),
    val documentCoverImages: ImmutableMap<String, ByteArray> = persistentMapOf(),
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
    Comic,
    Image,
}

internal enum class HomeDocumentSection {
    Favorites,
    Recent,
    Library,
}

internal data class HomeDocumentActionTarget(
    val section: HomeDocumentSection,
    val documentId: String,
)

enum class LibraryCollectionMode {
    All,
    Folders,
}

internal fun homeLibraryPreviewLimit(
    isExpanded: Boolean,
    isTablet: Boolean,
    hasSeparatingFold: Boolean,
): Int = if (isExpanded || isTablet || hasSeparatingFold) 8 else 4

internal fun libraryPreviewLimit(
    shortestSide: Dp,
    displayFold: DisplayFold?,
): Int = homeLibraryPreviewLimit(
    isExpanded = false,
    isTablet = shortestSide >= 600.dp,
    hasSeparatingFold = displayFold?.isVertical == true && displayFold.isSeparating,
)

internal fun homeLibraryPreviewDocuments(
    documents: List<DocumentMetadata>,
    previewLimit: Int,
): ImmutableList<DocumentMetadata> = documents.take(previewLimit).toImmutableList()

internal fun <T : Any> homeLibraryGridRows(
    items: List<T>,
    columns: Int,
): List<List<T?>> {
    require(columns > 0) { "columns must be positive." }
    return items.chunked(columns).map { row ->
        row.map<T, T?> { it } + List(columns - row.size) { null }
    }
}

internal fun libraryFolderPreviewDocuments(
    documents: List<DocumentMetadata>,
    folderId: String,
    previewLimit: Int,
): ImmutableList<DocumentMetadata> =
    documents.filter { it.folderId == folderId }.take(previewLimit).toImmutableList()

internal fun libraryFolderRemainingDocumentCount(
    totalCount: Int,
    previewCount: Int,
): Int = (totalCount - previewCount).coerceAtLeast(0)

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

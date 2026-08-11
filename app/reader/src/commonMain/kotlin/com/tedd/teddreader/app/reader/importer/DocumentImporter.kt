package com.tedd.teddreader.app.reader.importer

import androidx.compose.runtime.Composable
import com.tedd.teddreader.core.common.model.DocumentId
import kotlinx.coroutines.CancellationException

data class ExternalDocumentImportRequest(
    val sourceUri: String,
    val displayName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
    val grantFlags: Int = 0,
) {
    init {
        require(sourceUri.isNotBlank()) { "sourceUri must not be blank." }
        require(sizeBytes >= 0L) { "sizeBytes must be positive." }
    }
}

interface DocumentImporter {
    val supportsGoogleDrivePicker: Boolean

    fun openFiles(
        onImported: (List<DocumentId>) -> Unit,
        onError: (String) -> Unit,
    )

    fun openFolder(
        onImported: (List<DocumentId>) -> Unit,
        onError: (String) -> Unit,
    )

    fun openGoogleDrive(
        onImported: (List<DocumentId>) -> Unit,
        onError: (String) -> Unit,
    )

    fun importExternal(
        request: ExternalDocumentImportRequest,
        onImported: (DocumentId) -> Unit,
        onError: (String) -> Unit,
    )
}

@Composable
internal expect fun rememberDocumentImporter(
    googleDrivePickerBridge: GoogleDrivePickerBridge? = null,
): DocumentImporter

internal data class DocumentImportBatchResult(
    val importedDocumentIds: List<DocumentId>,
    val failedCount: Int,
)

internal suspend fun <T> importDocuments(
    items: Collection<T>,
    importItem: suspend (T) -> DocumentId,
): DocumentImportBatchResult {
    val importedDocumentIds = mutableListOf<DocumentId>()
    var failedCount = 0

    items.forEach { item ->
        try {
            importedDocumentIds += importItem(item)
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (_: Throwable) {
            failedCount += 1
        }
    }

    return DocumentImportBatchResult(
        importedDocumentIds = importedDocumentIds,
        failedCount = failedCount,
    )
}

internal fun DocumentImportBatchResult.toImportErrorMessage(): String? = when {
    importedDocumentIds.isEmpty() && failedCount == 0 -> "No supported documents found."
    failedCount == 0 -> null
    else -> "$failedCount documents failed to import."
}

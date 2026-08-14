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
    val firstFailureReason: String? = null,
)

internal suspend fun <T> importDocuments(
    items: Collection<T>,
    importItem: suspend (T) -> DocumentId,
): DocumentImportBatchResult {
    val importedDocumentIds = mutableListOf<DocumentId>()
    var failedCount = 0
    var firstFailureReason: String? = null

    items.forEach { item ->
        try {
            importedDocumentIds += importItem(item)
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (throwable: Throwable) {
            failedCount += 1
            // Dropping the cause left the reader reporting a bare count for an unreadable file, a
            // wrong format and an empty file alike, with nothing in the log either. Keep the first
            // reason so the message says what actually went wrong.
            if (firstFailureReason == null) firstFailureReason = throwable.importFailureReason()
        }
    }

    return DocumentImportBatchResult(
        importedDocumentIds = importedDocumentIds,
        failedCount = failedCount,
        firstFailureReason = firstFailureReason,
    )
}

internal fun Throwable.importFailureReason(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "unknown error"

internal fun DocumentImportBatchResult.toImportErrorMessage(): String? = when {
    importedDocumentIds.isEmpty() && failedCount == 0 -> "No supported documents found."
    failedCount == 0 -> null
    firstFailureReason == null -> "$failedCount documents failed to import."
    else -> "$failedCount documents failed to import. $firstFailureReason"
}

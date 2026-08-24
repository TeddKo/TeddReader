package com.tedd.teddreader.app.reader.importer

import androidx.compose.runtime.Composable
import com.tedd.teddreader.core.common.model.DocumentId
import kotlinx.coroutines.CancellationException

/**
 * A document handed to the app from outside its own pickers — an incoming Android `Intent`
 * (`VIEW`/`SEND`) or an OS-level "open with"/share target — carrying just enough information for
 * [DocumentImporter.importExternal] to resolve, materialize, and import it the same way a
 * user-driven pick would be.
 *
 * @property sourceUri an opaque, platform-specific locator for the document (for example, an
 *   Android `content://` URI rendered to a string). Must not be blank.
 * @property displayName the file name the source reports, used for user-facing display and as a
 *   fallback for extension-based format detection when [mimeType] is absent; null when the caller
 *   could not resolve one.
 * @property mimeType the MIME type the source reports, or null when the caller could not resolve
 *   one and format detection must fall back to [displayName]'s extension.
 * @property sizeBytes the document's size in bytes if known, defaulting to `0L`; must be zero or
 *   positive.
 * @property grantFlags the Android URI permission grant flags carried by the originating intent
 *   (e.g. `FLAG_GRANT_READ_URI_PERMISSION`), forwarded so the Android importer can persist read
 *   access to [sourceUri] beyond the lifetime of that intent. Always `0` on platforms with no such
 *   concept.
 * @throws IllegalArgumentException if [sourceUri] is blank or [sizeBytes] is negative.
 */
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

/**
 * The composition root's single entry point for bringing a document into the library, behind
 * which each platform hides its own picker UI, permission model, and file materialization
 * strategy. Screens call only these methods and never touch platform file APIs directly;
 * `rememberDocumentImporter` supplies the Android SAF/Intent-based or iOS
 * `UIDocumentPickerViewController`-backed implementation for whichever platform is running.
 *
 * Every entry point takes its result as `onImported`/`onError` callbacks rather than returning a
 * suspend value, because opening a picker is inherently a fire-and-forget UI interaction (an
 * Android `ActivityResultLauncher` callback, an iOS delegate callback) that outlives the call that
 * launched it; there is no value to hand back synchronously.
 */
interface DocumentImporter {
    /**
     * Whether [openGoogleDrive] can currently do anything useful on this platform/device — for
     * example, false when no activity is available to host the Android authorization flow, or when
     * the platform's [GoogleDrivePickerBridge] was never configured. Callers use this to decide
     * whether to show a Google Drive entry point at all, rather than showing one that always fails.
     */
    val supportsGoogleDrivePicker: Boolean

    /**
     * Opens the platform's picker for one or more individual document files and imports each one
     * selected.
     *
     * @param onImported called once with the [DocumentId] of every file that imported
     *   successfully; not called at all if the user picked nothing or every pick failed.
     * @param onError called with a user-facing message if the batch had any failures, or if the
     *   platform picker itself could not be opened.
     */
    fun openFiles(
        onImported: (List<DocumentId>) -> Unit,
        onError: (String) -> Unit,
    )

    /**
     * Opens the platform's picker for a whole folder and imports every supported document found
     * inside it, recursing into subfolders where the platform allows browsing them.
     *
     * @param onImported called once with the [DocumentId] of every document imported from the
     *   folder; not called if none imported.
     * @param onError called with a user-facing message if any document in the folder failed to
     *   import, or if the folder picker itself could not be opened.
     */
    fun openFolder(
        onImported: (List<DocumentId>) -> Unit,
        onError: (String) -> Unit,
    )

    /**
     * Starts the Google Drive picker flow — authorization first if needed, then file selection —
     * and imports whatever the user picks. Callers should gate showing this entry point on
     * [supportsGoogleDrivePicker] rather than calling it unconditionally.
     *
     * @param onImported called once with the [DocumentId] of every Drive file imported.
     * @param onError called with a user-facing message if authorization, download, or import
     *   failed, including when [supportsGoogleDrivePicker] is false and the platform refuses the
     *   request outright.
     */
    fun openGoogleDrive(
        onImported: (List<DocumentId>) -> Unit,
        onError: (String) -> Unit,
    )

    /**
     * Imports the single document described by an externally delivered
     * [ExternalDocumentImportRequest] — one the OS handed the app rather than one the user picked
     * from inside it.
     *
     * @param request the document to import, as resolved from the incoming intent or share target.
     * @param onImported called once with the imported document's [DocumentId] on success.
     * @param onError called with a user-facing message if the import failed, or if this platform
     *   has not wired external import up yet.
     */
    fun importExternal(
        request: ExternalDocumentImportRequest,
        onImported: (DocumentId) -> Unit,
        onError: (String) -> Unit,
    )
}

/**
 * Resolves this platform's [DocumentImporter] implementation, scoped to the current composition:
 * Android's SAF/Intent-based pickers plus Google Drive OAuth, or iOS's
 * `UIDocumentPickerViewController` plus security-scoped resource handling. Called once from
 * `TeddReaderApp` and the resulting importer threaded down to the navigation host and the screens
 * that trigger imports.
 *
 * @param googleDrivePickerBridge the platform bridge for the Google Drive picker/authorization
 *   flow, or null when Drive import is not configured for this build; forwarded to the platform
 *   implementation so it can decide [DocumentImporter.supportsGoogleDrivePicker].
 * @return the platform's [DocumentImporter], remembered for the composition's lifetime.
 */
@Composable
internal expect fun rememberDocumentImporter(
    googleDrivePickerBridge: GoogleDrivePickerBridge? = null,
): DocumentImporter

/**
 * The outcome of importing a batch of documents picked together — a multi-file selection, or every
 * supported file inside a picked folder: which ones made it into the library, how many did not,
 * and, for [toImportErrorMessage] to report, why the first one failed.
 *
 * @property importedDocumentIds the [DocumentId] of every document that imported successfully, in
 *   the order [importDocuments] processed them.
 * @property failedCount how many items in the batch failed to import.
 * @property firstFailureReason the reason the first failed item gave (see
 *   [Throwable.importFailureReason]), or null when nothing failed. Only the first reason is kept —
 *   see [importDocuments] for why a bare failure count on its own is not enough.
 */
internal data class DocumentImportBatchResult(
    val importedDocumentIds: List<DocumentId>,
    val failedCount: Int,
    val firstFailureReason: String? = null,
)

/**
 * Imports a batch of items one at a time, letting each failure fall through instead of aborting
 * the whole batch, so that one unreadable file in a ten-file folder import does not cost the other
 * nine.
 *
 * A [CancellationException] thrown by [importItem] is always rethrown rather than counted as a
 * failure, so cancelling the enclosing coroutine — for example, navigating away mid-import — still
 * cancels promptly instead of being absorbed as one failure among many.
 *
 * Only the first failure's reason is kept, in the returned result's `firstFailureReason`.
 * Reporting a bare failure count left the reader with no way to tell an unreadable file, a wrong
 * format, and an empty file apart — the count looked identical for all three, and nothing else was
 * logged either — so the first concrete reason is surfaced instead of the count alone; later
 * failures in the same batch may have different causes, but the first is enough to tell the user
 * something actionable happened.
 *
 * @param items the items to import, in iteration order.
 * @param importItem imports a single item and returns its resulting [DocumentId]; may throw on
 *   failure, including [CancellationException] to cancel the whole batch.
 * @return a [DocumentImportBatchResult] summarizing which items succeeded, how many failed, and
 *   why the first failure happened.
 */
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
            if (firstFailureReason == null) firstFailureReason = throwable.importFailureReason()
        }
    }

    return DocumentImportBatchResult(
        importedDocumentIds = importedDocumentIds,
        failedCount = failedCount,
        firstFailureReason = firstFailureReason,
    )
}

/**
 * The best available human-readable reason this throwable represents an import failure: its own
 * message when present and non-blank, falling back to the exception's simple class name, and
 * finally to a fixed "unknown error" string when even the class name is unavailable, as for an
 * anonymous or local exception type.
 *
 * @receiver the throwable caught while importing a single document.
 * @return a non-blank string safe to show to the user or fold into
 *   [DocumentImportBatchResult.firstFailureReason].
 */
internal fun Throwable.importFailureReason(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "unknown error"

/**
 * Decides the user-facing message, if any, for a completed import batch.
 *
 * @receiver the batch result to summarize.
 * @return `"No supported documents found."` when nothing was picked and nothing failed, as for an
 *   empty folder or a picker that returned zero usable files; null when every item imported
 *   successfully, since a fully successful batch needs no error message at all; otherwise a count
 *   of failures, with [DocumentImportBatchResult.firstFailureReason] appended when one is known so
 *   the message says what actually went wrong instead of just how many files failed.
 */
internal fun DocumentImportBatchResult.toImportErrorMessage(): String? = when {
    importedDocumentIds.isEmpty() && failedCount == 0 -> "No supported documents found."
    failedCount == 0 -> null
    firstFailureReason == null -> "$failedCount documents failed to import."
    else -> "$failedCount documents failed to import. $firstFailureReason"
}

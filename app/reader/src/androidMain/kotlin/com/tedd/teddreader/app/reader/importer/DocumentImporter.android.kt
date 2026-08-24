package com.tedd.teddreader.app.reader.importer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.Identity
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.GoogleDriveSupportedDocumentMimeTypes
import com.tedd.teddreader.core.common.model.SupportedDocumentExtensions
import com.tedd.teddreader.core.common.model.SupportedDocumentMimeTypes
import com.tedd.teddreader.core.data.storage.AndroidDocumentFileSource
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin

/**
 * The MIME type filter Android's `OpenMultipleDocuments` picker is opened with for the "open
 * files" entry point: every format [SupportedDocumentMimeTypes] recognizes, plus `application/zip`,
 * so an EPUB or CBZ whose content provider reports it only as a generic zip archive is still
 * selectable, the same reasoning [GoogleDriveSupportedDocumentMimeTypes] applies for Drive.
 */
private val AndroidPickerMimeTypes = (SupportedDocumentMimeTypes + "application/zip").toTypedArray()

/**
 * [GoogleDriveSupportedDocumentMimeTypes] rendered as a [List], because
 * `buildGoogleDriveAuthorizationRequest`'s `PICKER_MIMETYPES` resource parameter needs an ordered
 * collection to join into a comma-separated string, not a [Set].
 */
internal val AndroidGoogleDriveMimeTypes = GoogleDriveSupportedDocumentMimeTypes.toList()

/**
 * Android's [DocumentImporter]: SAF (`OpenMultipleDocuments`/`OpenDocumentTree`) for on-device
 * picks, and Google Play Services Identity's `AuthorizationClient` for Google Drive, since Android
 * has no bridge-based Drive integration the way iOS does — [googleDrivePickerBridge] is accepted
 * for shared-signature parity with the `expect` declaration but is never read here.
 *
 * Every picker is an [androidx.activity.result.ActivityResultLauncher] registered once via
 * `rememberLauncherForActivityResult`, whose result callback fires asynchronously and independently
 * of whichever [DocumentImporter] method call triggered it. Because a launcher's callback is fixed
 * at registration time, the `onImported`/`onError` callbacks a caller passes to [openFiles],
 * [openFolder], or [openGoogleDrive] cannot be captured directly in the launcher's closure — they
 * are instead stashed in `importedCallback`/`errorCallback` mutable state just before each
 * `launch()` call, so the launcher's callback always reads whichever pair the most recent call
 * installed, and the two are reset back to no-ops once that attempt finishes so a later spurious
 * callback invocation cannot resurface a finished attempt's callbacks.
 *
 * The Google Drive flow needs two launchers because Play Services Identity's `authorize()` call
 * itself only sometimes needs to show UI: when the account is already authorized it returns a
 * result with no `hasResolution()`, handled immediately; otherwise its `pendingIntent` is launched
 * through `googleDriveAuthorizationLauncher`, whose own result is converted back into the same
 * [GoogleDrivePickerResult] shape so both paths converge on the same download/import step.
 *
 * @param googleDrivePickerBridge accepted only to satisfy the `expect fun` signature; Android's
 *   Drive integration is self-contained and does not use a platform bridge.
 * @return an Android-backed [DocumentImporter] remembered for the composition's lifetime.
 */
@Composable
internal actual fun rememberDocumentImporter(
    googleDrivePickerBridge: GoogleDrivePickerBridge?,
): DocumentImporter {
    val context = LocalContext.current.applicationContext
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()
    val documentRepository = getKoin().get<DocumentRepository>()
    val documentFileSource = getKoin().get<AndroidDocumentFileSource>()
    val authorizationClient = remember(activity) { activity?.let(Identity::getAuthorizationClient) }
    var importedCallback by remember { mutableStateOf<(List<DocumentId>) -> Unit>({}) }
    var errorCallback by remember { mutableStateOf<(String) -> Unit>({}) }

    fun clearDriveCallbacks() {
        importedCallback = {}
        errorCallback = {}
    }

    fun handleGoogleDrivePickerResult(result: GoogleDrivePickerResult) {
        scope.launch {
            try {
                val client = authorizationClient ?: error("Google Drive is unavailable on this device.")
                val sources = fetchGoogleDriveImportSources(
                    authorizationClient = client,
                    pickerResult = result,
                ).map { source ->
                    source.copyMaterialized(documentFileSource)
                }
                val importResult = importDocuments(sources) { source ->
                    documentRepository.importDocument(
                        source = source,
                        importedAtEpochMillis = System.currentTimeMillis(),
                    ).id
                }
                dispatchBatchImportResult(importResult, importedCallback, errorCallback)
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (throwable: Throwable) {
                errorCallback(throwable.message ?: "Failed to import Google Drive document.")
            } finally {
                clearDriveCallbacks()
            }
        }
    }

    val googleDriveAuthorizationLauncher = rememberLauncherForActivityResult(StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            clearDriveCallbacks()
            return@rememberLauncherForActivityResult
        }
        try {
            val pickerResult = authorizationClient
                ?.getAuthorizationResultFromIntent(result.data)
                ?.toGoogleDrivePickerResult()
                ?: error("Google Drive authorization is unavailable.")
            handleGoogleDrivePickerResult(pickerResult)
        } catch (throwable: Throwable) {
            errorCallback(throwable.message ?: "Failed to open Google Drive.")
            clearDriveCallbacks()
        }
    }

    val filesLauncher = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val result = importDocuments(uris) { uri ->
                importUri(
                    context = context,
                    uri = uri,
                    grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    documentRepository = documentRepository,
                    documentFileSource = documentFileSource,
                )
            }
            dispatchBatchImportResult(result, importedCallback, errorCallback)
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val documentUris = withContext(Dispatchers.IO) {
                    resolveSupportedTreeDocumentUris(context, uri)
                }
                val result = importDocuments(documentUris) { documentUri ->
                    importUri(
                        context = context,
                        uri = documentUri,
                        grantFlags = 0,
                        documentRepository = documentRepository,
                        documentFileSource = documentFileSource,
                    )
                }
                dispatchBatchImportResult(result, importedCallback, errorCallback)
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (throwable: Throwable) {
                errorCallback(throwable.message ?: "Failed open selected document.")
            }
        }
    }

    return remember {
        object : DocumentImporter {
            override val supportsGoogleDrivePicker: Boolean = activity != null && authorizationClient != null

            override fun openFiles(
                onImported: (List<DocumentId>) -> Unit,
                onError: (String) -> Unit,
            ) {
                importedCallback = onImported
                errorCallback = onError
                filesLauncher.launch(AndroidPickerMimeTypes)
            }

            override fun openFolder(
                onImported: (List<DocumentId>) -> Unit,
                onError: (String) -> Unit,
            ) {
                importedCallback = onImported
                errorCallback = onError
                folderLauncher.launch(null)
            }

            override fun openGoogleDrive(
                onImported: (List<DocumentId>) -> Unit,
                onError: (String) -> Unit,
            ) {
                val client = authorizationClient
                if (activity == null || client == null) {
                    onError("Google Drive is unavailable on this device.")
                    return
                }

                importedCallback = onImported
                errorCallback = onError
                scope.launch {
                    try {
                        val authorizationResult = client.awaitAuthorize(buildGoogleDriveAuthorizationRequest())
                        if (authorizationResult.hasResolution()) {
                            val pendingIntent = authorizationResult.pendingIntent
                                ?: error("Google Drive authorization did not return a resolution.")
                            googleDriveAuthorizationLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                            )
                        } else {
                            handleGoogleDrivePickerResult(authorizationResult.toGoogleDrivePickerResult())
                        }
                    } catch (cancellationException: CancellationException) {
                        throw cancellationException
                    } catch (throwable: Throwable) {
                        onError(throwable.message ?: "Failed to open Google Drive.")
                        clearDriveCallbacks()
                    }
                }
            }

            override fun importExternal(
                request: ExternalDocumentImportRequest,
                onImported: (DocumentId) -> Unit,
                onError: (String) -> Unit,
            ) {
                scope.launch {
                    try {
                        onImported(
                            importExternalRequest(
                                context = context,
                                request = request,
                                documentRepository = documentRepository,
                                documentFileSource = documentFileSource,
                            ),
                        )
                    } catch (cancellationException: CancellationException) {
                        throw cancellationException
                    } catch (throwable: Throwable) {
                        onError(throwable.message ?: "Failed open selected document.")
                    }
                }
            }
        }
    }
}

/**
 * Imports the single document an [ExternalDocumentImportRequest] describes, forcing
 * [importUri]'s `materializeInAppStorage` to true. Unlike a document picked through SAF — whose
 * `content://` URI stays valid as long as the persisted read-permission grant lasts — a document
 * delivered through an incoming `VIEW`/`SEND` intent may only be backed by a transient grant or a
 * sender-owned temp file that can vanish once the delivering app's process ends, so it is copied
 * into app storage immediately rather than trusted to remain readable later.
 *
 * @param context used to reach the [ContentResolver] that resolves and reads the document.
 * @param request the externally delivered document to import, supplying override metadata already
 *   resolved from the original intent.
 * @param documentRepository imports the resolved
 *   [com.tedd.teddreader.core.domain.repository.DocumentImportSource].
 * @param documentFileSource materializes the document into app storage.
 * @return the imported document's [DocumentId].
 */
private suspend fun importExternalRequest(
    context: Context,
    request: ExternalDocumentImportRequest,
    documentRepository: DocumentRepository,
    documentFileSource: AndroidDocumentFileSource,
): DocumentId = importUri(
    context = context,
    uri = Uri.parse(request.sourceUri),
    grantFlags = request.grantFlags,
    documentRepository = documentRepository,
    documentFileSource = documentFileSource,
    overrideDisplayName = request.displayName,
    overrideMimeType = request.mimeType,
    overrideSizeBytes = request.sizeBytes,
    materializeInAppStorage = true,
)

/**
 * Imports a single document identified by a `content://` [Uri], the shared routine behind every
 * Android import path: a direct SAF pick, a document found while walking a picked folder, and an
 * externally delivered document (through [importExternalRequest]).
 *
 * Persists the read-URI-permission grant when [grantFlags] carries it, so the resulting
 * [DocumentLocation] can still be opened after the launching activity call or intent delivery that
 * originally granted it has ended — without this, the grant would be revoked once that short-lived
 * context goes away and a later attempt to reopen the imported document would fail.
 *
 * For an EPUB or CBZ, [bytes] is deliberately left null instead of being read here: both formats
 * are zip archives a parser opens and seeks around in on demand, so reading one fully into memory
 * just to hand those bytes to a parser that immediately writes them back out to a temp file (or,
 * for a CBZ, never even needed the bytes to begin with) is wasted work — the largest books pay the
 * most for it. `DocumentFormatDetector` resolves the format from `displayName`/`mimeType` alone, so
 * `bytes = null` costs it nothing, and `DocumentRepositoryImpl`'s progressive import streams its
 * own local copy from [DocumentLocation] instead, the same way CBZ import already worked before this
 * applied to EPUB too.
 *
 * When [materializeInAppStorage] is requested, the zip formats are copied by
 * [AndroidDocumentFileSource.materializeFromSource] — streaming from the source URI directly,
 * without needing [bytes] — while every other format is copied via
 * [AndroidDocumentFileSource.materialize] from the bytes already read into memory; when it is not
 * requested, [location] itself is stored as-is, keeping the original `content://` URI as the
 * document's file source.
 *
 * @param context used to reach the [ContentResolver] that resolves and reads the document.
 * @param uri the `content://` URI to import.
 * @param grantFlags the originating intent's flags, checked for
 *   `Intent.FLAG_GRANT_READ_URI_PERMISSION` to decide whether to persist the grant.
 * @param documentRepository imports the resulting
 *   [com.tedd.teddreader.core.domain.repository.DocumentImportSource].
 * @param documentFileSource materializes the document into app storage when
 *   [materializeInAppStorage] is true.
 * @param overrideDisplayName a display name to prefer over one resolved from [uri]'s own metadata,
 *   used when the caller (e.g. an external import request) already resolved a more reliable one.
 * @param overrideMimeType a MIME type to prefer over one resolved from [uri]'s own metadata, for
 *   the same reason as [overrideDisplayName].
 * @param overrideSizeBytes a size to prefer over one resolved from [uri]'s own metadata, for the
 *   same reason as [overrideDisplayName].
 * @param materializeInAppStorage whether to copy the document into app-private storage rather than
 *   keep referencing the original `content://` URI; true for an externally delivered document,
 *   false for a document picked directly through SAF.
 * @return the imported document's [DocumentId].
 * @throws IllegalStateException if [uri] cannot be opened for reading when bytes are required.
 */
private suspend fun importUri(
    context: Context,
    uri: Uri,
    grantFlags: Int,
    documentRepository: DocumentRepository,
    documentFileSource: AndroidDocumentFileSource,
    overrideDisplayName: String? = null,
    overrideMimeType: String? = null,
    overrideSizeBytes: Long? = null,
    materializeInAppStorage: Boolean = false,
): DocumentId {
    return withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        if ((grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }

        val metadata = resolver.queryDocumentMetadata(uri)
        val location = DocumentLocation(
            sourceUri = uri.toString(),
            displayName = overrideDisplayName ?: metadata.displayName ?: uri.lastPathSegment ?: uri.toString(),
            mimeType = overrideMimeType ?: resolver.getType(uri),
            sizeBytes = overrideSizeBytes ?: metadata.sizeBytes ?: 0L,
        )
        val extension = location.displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val mimeType = location.mimeType?.lowercase()
        val isCbzImport = extension == "cbz" || mimeType == "application/vnd.comicbook+zip" || mimeType == "application/x-cbz"
        val isEpubImport = extension == "epub" || mimeType == "application/epub+zip"
        val bytes = if (isCbzImport || isEpubImport) {
            null
        } else {
            resolver.openInputStream(uri)?.use { input -> input.readBytes() }
                ?: error("Cannot open document: $uri")
        }
        val persistedLocation = when {
            !materializeInAppStorage -> location
            isCbzImport || isEpubImport -> documentFileSource.materializeFromSource(location)
            else -> documentFileSource.materialize(location, requireNotNull(bytes))
        }
        val document = documentRepository.importDocument(
            source = DocumentImportSource(location = persistedLocation, bytes = bytes),
            importedAtEpochMillis = System.currentTimeMillis(),
        )
        document.id
    }
}

/**
 * Copies a Google-Drive-downloaded source into app storage, the Drive equivalent of what
 * [importUri] does for its `materializeInAppStorage` branch: a document imported through this
 * app needs a stable local file it can be re-opened and re-read from later, not just the in-memory
 * [bytes] the initial download produced, which does not outlive this import.
 *
 * @receiver the Drive-downloaded source to materialize, already carrying its full content in
 *   [DocumentImportSource.bytes].
 * @param documentFileSource performs the actual copy into app storage.
 * @return a copy of this source pointing at the newly materialized [DocumentLocation].
 */
private suspend fun DocumentImportSource.copyMaterialized(
    documentFileSource: AndroidDocumentFileSource,
): DocumentImportSource {
    val sourceBytes = bytes
    return DocumentImportSource(
        location = if (sourceBytes != null) documentFileSource.materialize(location, sourceBytes) else documentFileSource.materializeFromSource(location),
        bytes = sourceBytes,
    )
}

/**
 * One file found while walking a picked folder tree, carrying just enough to sort the results
 * deterministically in [resolveSupportedTreeDocumentUris] before the display name is discarded and
 * only the [Uri] is imported.
 *
 * @property displayName the file's display name as `DocumentsContract` reports it, used only for
 *   sort order.
 * @property uri the document tree [Uri] to import.
 */
private data class AndroidTreeDocument(
    val displayName: String,
    val uri: Uri,
)

/**
 * Recursively walks a folder picked through `OpenDocumentTree`, collecting every supported
 * document it finds anywhere in the tree.
 *
 * @param context used to reach the [ContentResolver] that lists each directory's children.
 * @param treeUri the root tree [Uri] granted by the folder picker.
 * @return the supported documents found in the tree, sorted by lowercase display name and then by
 *   URI, so a folder import's resulting order is stable regardless of the order the content
 *   provider happens to return children in.
 */
private fun resolveSupportedTreeDocumentUris(
    context: Context,
    treeUri: Uri,
): List<Uri> {
    val resolver = context.contentResolver
    val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    val results = mutableListOf<AndroidTreeDocument>()

    fun visit(documentUri: Uri) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            documentUri,
            DocumentsContract.getDocumentId(documentUri),
        )
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val childId = cursor.getString(idIndex)
                val displayName = cursor.getString(nameIndex) ?: childId
                val mimeType = cursor.getString(mimeIndex)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    visit(childUri)
                } else if (isSupportedDocument(displayName, mimeType)) {
                    results += AndroidTreeDocument(displayName = displayName, uri = childUri)
                }
            }
        }
    }

    visit(rootDocumentUri)
    return results
        .sortedWith(compareBy<AndroidTreeDocument> { it.displayName.lowercase() }.thenBy { it.uri.toString() })
        .map(AndroidTreeDocument::uri)
}

/**
 * Whether a file found while walking a picked folder tree is one this app parses, checked by
 * either its reported MIME type or its file-name extension so a provider that reports a wrong or
 * missing MIME type does not hide an otherwise-importable file from a folder import.
 *
 * @param displayName the file's display name, used for extension-based detection.
 * @param mimeType the file's reported MIME type, or null when the provider did not report one.
 * @return true when either signal matches a supported format.
 */
private fun isSupportedDocument(displayName: String, mimeType: String?): Boolean {
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
    return mimeType in SupportedDocumentMimeTypes || extension.lowercase() in SupportedDocumentExtensions
}

/**
 * Turns a completed [DocumentImportBatchResult] into the pair of [DocumentImporter] callbacks
 * every multi-file Android import path (`openFiles`, `openFolder`, and the Google Drive flow)
 * needs to report, so each path does not repeat the same "call `onImported` only if something
 * succeeded, then call `onError` if anything failed" logic on its own.
 *
 * @param result the completed batch to report.
 * @param onImported invoked with the successfully imported ids, only when there is at least one.
 * @param onError invoked with the message [DocumentImportBatchResult.toImportErrorMessage] decides
 *   on, only when it returns non-null.
 */
private fun dispatchBatchImportResult(
    result: DocumentImportBatchResult,
    onImported: (List<DocumentId>) -> Unit,
    onError: (String) -> Unit,
) {
    if (result.importedDocumentIds.isNotEmpty()) {
        onImported(result.importedDocumentIds)
    }
    result.toImportErrorMessage()?.let(onError)
}

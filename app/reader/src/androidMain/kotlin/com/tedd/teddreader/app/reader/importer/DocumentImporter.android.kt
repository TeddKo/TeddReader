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
import com.tedd.teddreader.core.common.model.SupportedDocumentExtensions
import com.tedd.teddreader.core.common.model.SupportedDocumentMimeTypes
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.usecase.OpenDocumentUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin

private val AndroidPickerMimeTypes = (SupportedDocumentMimeTypes + "application/zip").toTypedArray()

@Composable
internal actual fun rememberDocumentImporter(
    googleDrivePickerBridge: GoogleDrivePickerBridge?,
): DocumentImporter {
    val context = LocalContext.current.applicationContext
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()
    val openDocumentUseCase = getKoin().get<OpenDocumentUseCase>()
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
                )
                val importResult = importDocuments(sources) { source ->
                    openDocumentUseCase(
                        source = source,
                        openedAtEpochMillis = System.currentTimeMillis(),
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
                    openDocumentUseCase = openDocumentUseCase,
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
                        openDocumentUseCase = openDocumentUseCase,
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
                                openDocumentUseCase = openDocumentUseCase,
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

private suspend fun importExternalRequest(
    context: Context,
    request: ExternalDocumentImportRequest,
    openDocumentUseCase: OpenDocumentUseCase,
): DocumentId = importUri(
    context = context,
    uri = Uri.parse(request.sourceUri),
    grantFlags = request.grantFlags,
    openDocumentUseCase = openDocumentUseCase,
    overrideDisplayName = request.displayName,
    overrideMimeType = request.mimeType,
    overrideSizeBytes = request.sizeBytes,
)

private suspend fun importUri(
    context: Context,
    uri: Uri,
    grantFlags: Int,
    openDocumentUseCase: OpenDocumentUseCase,
    overrideDisplayName: String? = null,
    overrideMimeType: String? = null,
    overrideSizeBytes: Long? = null,
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
        val bytes = resolver.openInputStream(uri)?.use { input -> input.readBytes() }
            ?: error("Cannot open document: $uri")
        val document = openDocumentUseCase(
            source = DocumentImportSource(location = location, bytes = bytes),
            openedAtEpochMillis = System.currentTimeMillis(),
        )
        document.id
    }
}

private data class AndroidTreeDocument(
    val displayName: String,
    val uri: Uri,
)

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

private fun isSupportedDocument(displayName: String, mimeType: String?): Boolean {
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
    return mimeType in SupportedDocumentMimeTypes || extension.lowercase() in SupportedDocumentExtensions
}

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

package com.tedd.teddreader.app.reader.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.SupportedDocumentMimeTypes
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.usecase.OpenDocumentUseCase
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

private val AndroidPickerMimeTypes = SupportedDocumentMimeTypes.toTypedArray()

@Composable
internal actual fun rememberDocumentImporter(): DocumentImporter {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val openDocumentUseCase = getKoin().get<OpenDocumentUseCase>()
    var importedCallback by remember { mutableStateOf<(DocumentId) -> Unit>({}) }
    var errorCallback by remember { mutableStateOf<(String) -> Unit>({}) }

    val launcher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                importUri(
                    context = context,
                    uri = uri,
                    grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    openDocumentUseCase = openDocumentUseCase,
                )
            }.onSuccess { documentId ->
                importedCallback(documentId)
            }.onFailure { throwable ->
                errorCallback(throwable.message ?: "Failed open selected document.")
            }
        }
    }

    return remember {
        object : DocumentImporter {
            override fun open(
                onImported: (DocumentId) -> Unit,
                onError: (String) -> Unit,
            ) {
                importedCallback = onImported
                errorCallback = onError
                launcher.launch(AndroidPickerMimeTypes)
            }

            override fun importExternal(
                request: ExternalDocumentImportRequest,
                onImported: (DocumentId) -> Unit,
                onError: (String) -> Unit,
            ) {
                scope.launch {
                    runCatching {
                        importExternalRequest(
                            context = context,
                            request = request,
                            openDocumentUseCase = openDocumentUseCase,
                        )
                    }.onSuccess(onImported)
                        .onFailure { throwable -> onError(throwable.message ?: "Failed open selected document.") }
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
    return document.id
}

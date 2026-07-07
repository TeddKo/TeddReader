package com.tedd.teddreader.app.reader.importer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.data.storage.IosDocumentFileSource
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.usecase.OpenDocumentUseCase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import platform.Foundation.NSURL
import platform.Foundation.lastPathComponent
import platform.Foundation.pathExtension
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject

private val IosPickerTypeIdentifiers = listOf(
    "public.plain-text",
    "com.adobe.pdf",
    "org.idpf.epub-container",
)

@Composable
internal actual fun rememberDocumentImporter(): DocumentImporter {
    val scope = rememberCoroutineScope()
    val openDocumentUseCase = getKoin().get<OpenDocumentUseCase>()
    return remember(scope, openDocumentUseCase) {
        IosDocumentImporter(
            scope = scope,
            openDocumentUseCase = openDocumentUseCase,
            fileSource = IosDocumentFileSource(),
        )
    }
}

private class IosDocumentImporter(
    private val scope: CoroutineScope,
    private val openDocumentUseCase: OpenDocumentUseCase,
    private val fileSource: IosDocumentFileSource,
) : DocumentImporter {
    private var delegate: IosDocumentPickerDelegate? = null

    override fun open(
        onImported: (DocumentId) -> Unit,
        onError: (String) -> Unit,
    ) {
        val presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
        if (presenter == null) {
            onError("Cannot open iOS document picker.")
            return
        }

        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = IosPickerTypeIdentifiers.mapNotNull(UTType::typeWithIdentifier),
            asCopy = true,
        )
        val pickerDelegate = IosDocumentPickerDelegate(
            scope = scope,
            openDocumentUseCase = openDocumentUseCase,
            fileSource = fileSource,
            onImported = onImported,
            onError = onError,
        )
        delegate = pickerDelegate
        picker.delegate = pickerDelegate
        picker.allowsMultipleSelection = false
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    override fun importExternal(
        request: ExternalDocumentImportRequest,
        onImported: (DocumentId) -> Unit,
        onError: (String) -> Unit,
    ) {
        onError("iOS external document import is not connected yet.")
    }
}

private class IosDocumentPickerDelegate(
    private val scope: CoroutineScope,
    private val openDocumentUseCase: OpenDocumentUseCase,
    private val fileSource: IosDocumentFileSource,
    private val onImported: (DocumentId) -> Unit,
    private val onError: (String) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        if (url == null) {
            onError("No iOS document selected.")
            return
        }

        scope.launch {
            runCatching { importUrl(url) }
                .onSuccess(onImported)
                .onFailure { throwable -> onError(throwable.message ?: "Failed open selected document.") }
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = Unit

    private suspend fun importUrl(url: NSURL): DocumentId {
        val accessed = url.startAccessingSecurityScopedResource()
        return try {
            val sourcePath = url.path ?: error("Cannot read selected iOS document path.")
            val displayName = url.lastPathComponent ?: "document"
            val sandboxLocation = fileSource.copyIntoAppContainer(
                sourcePath = sourcePath,
                displayName = displayName,
                mimeType = url.pathExtension?.lowercase()?.let(::mimeTypeForExtension),
            )
            val document = openDocumentUseCase(
                source = DocumentImportSource(
                    location = sandboxLocation,
                    bytes = fileSource.readBytes(sandboxLocation),
                ),
                openedAtEpochMillis = currentTimeMillis(),
            )
            document.id
        } finally {
            if (accessed) url.stopAccessingSecurityScopedResource()
        }
    }
}

private fun mimeTypeForExtension(extension: String): String? = when (extension) {
    "txt" -> "text/plain"
    "pdf" -> "application/pdf"
    "epub" -> "application/epub+zip"
    else -> null
}

@OptIn(ExperimentalForeignApi::class)
private fun currentTimeMillis(): Long = platform.posix.time(null) * 1000L

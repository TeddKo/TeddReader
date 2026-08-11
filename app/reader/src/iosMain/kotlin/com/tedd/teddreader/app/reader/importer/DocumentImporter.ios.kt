package com.tedd.teddreader.app.reader.importer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.SupportedDocumentExtensions
import com.tedd.teddreader.core.data.storage.IosDocumentFileSource
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.usecase.OpenDocumentUseCase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.URLByAppendingPathComponent
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

    override fun openFiles(
        onImported: (List<DocumentId>) -> Unit,
        onError: (String) -> Unit,
    ) {
        presentPicker(
            picker = UIDocumentPickerViewController(
                forOpeningContentTypes = IosPickerTypeIdentifiers.mapNotNull(UTType::typeWithIdentifier),
                asCopy = true,
            ).apply {
                allowsMultipleSelection = true
            },
            onImported = onImported,
            onError = onError,
            importUrls = { urls -> importDocuments(urls) { url -> importUrl(url) } },
        )
    }

    override fun openFolder(
        onImported: (List<DocumentId>) -> Unit,
        onError: (String) -> Unit,
    ) {
        presentPicker(
            picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOfNotNull(UTType.typeWithIdentifier("public.folder")),
                asCopy = false,
            ).apply {
                allowsMultipleSelection = true
            },
            onImported = onImported,
            onError = onError,
            importUrls = { urls -> importFolders(urls) },
        )
    }

    override fun importExternal(
        request: ExternalDocumentImportRequest,
        onImported: (DocumentId) -> Unit,
        onError: (String) -> Unit,
    ) {
        onError("iOS external document import is not connected yet.")
    }

    private fun presentPicker(
        picker: UIDocumentPickerViewController,
        onImported: (List<DocumentId>) -> Unit,
        onError: (String) -> Unit,
        importUrls: suspend (List<NSURL>) -> DocumentImportBatchResult,
    ) {
        val presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
        if (presenter == null) {
            onError("Cannot open iOS document picker.")
            return
        }

        val pickerDelegate = IosDocumentPickerDelegate(
            scope = scope,
            onImported = onImported,
            onError = onError,
            importUrls = importUrls,
        )
        delegate = pickerDelegate
        picker.delegate = pickerDelegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    private suspend fun importFolders(urls: List<NSURL>): DocumentImportBatchResult {
        val importedDocumentIds = mutableListOf<DocumentId>()
        var failedCount = 0

        urls.forEach { rootUrl ->
            val accessed = rootUrl.startAccessingSecurityScopedResource()
            try {
                val documentUrls = collectSupportedDocumentUrls(rootUrl)
                    .sortedWith(compareBy<NSURL> { (it.lastPathComponent ?: "").lowercase() }.thenBy { it.path ?: "" })
                val result = importDocuments(documentUrls) { url -> importUrl(url, manageSecurityScope = false) }
                importedDocumentIds += result.importedDocumentIds
                failedCount += result.failedCount
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (_: Throwable) {
                failedCount += 1
            } finally {
                if (accessed) rootUrl.stopAccessingSecurityScopedResource()
            }
        }

        return DocumentImportBatchResult(
            importedDocumentIds = importedDocumentIds,
            failedCount = failedCount,
        )
    }

    private fun collectSupportedDocumentUrls(rootUrl: NSURL): List<NSURL> {
        val rootPath = rootUrl.path ?: return emptyList()
        return NSFileManager.defaultManager.subpathsAtPath(rootPath)
            ?.filterIsInstance<String>()
            ?.mapNotNull { subpath ->
                subpath.takeIf(::isSupportedDocumentPath)?.let(rootUrl::URLByAppendingPathComponent)
            }
            .orEmpty()
    }

    private suspend fun importUrl(
        url: NSURL,
        manageSecurityScope: Boolean = true,
    ): DocumentId {
        val accessed = if (manageSecurityScope) url.startAccessingSecurityScopedResource() else false
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

private class IosDocumentPickerDelegate(
    private val scope: CoroutineScope,
    private val onImported: (List<DocumentId>) -> Unit,
    private val onError: (String) -> Unit,
    private val importUrls: suspend (List<NSURL>) -> DocumentImportBatchResult,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val urls = didPickDocumentsAtURLs.filterIsInstance<NSURL>()
        if (urls.isEmpty()) {
            onError("No iOS document selected.")
            return
        }

        scope.launch {
            try {
                val result = importUrls(urls)
                if (result.importedDocumentIds.isNotEmpty()) {
                    onImported(result.importedDocumentIds)
                }
                result.toImportErrorMessage()?.let(onError)
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (throwable: Throwable) {
                onError(throwable.message ?: "Failed open selected document.")
            }
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = Unit
}

private fun isSupportedDocumentPath(path: String): Boolean =
    path.substringAfterLast('.', missingDelimiterValue = "").lowercase() in SupportedDocumentExtensions

private fun mimeTypeForExtension(extension: String): String? = when (extension) {
    "txt" -> "text/plain"
    "pdf" -> "application/pdf"
    "epub" -> "application/epub+zip"
    else -> null
}

@OptIn(ExperimentalForeignApi::class)
private fun currentTimeMillis(): Long = platform.posix.time(null) * 1000L

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
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.compose.getKoin
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.URLByAppendingPathComponent
import platform.Foundation.lastPathComponent
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.pathExtension
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import platform.posix.memcpy
import platform.posix.time
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val IosPickerTypeIdentifiers = listOf(
    "public.plain-text",
    "com.adobe.pdf",
    "org.idpf.epub-container",
    "public.zip-archive",
    "public.jpeg",
    "public.png",
    "org.webmproject.webp",
    "com.compuserve.gif",
    "com.microsoft.bmp",
)

@Composable
internal actual fun rememberDocumentImporter(
    googleDrivePickerBridge: GoogleDrivePickerBridge?,
): DocumentImporter {
    val scope = rememberCoroutineScope()
    val openDocumentUseCase = getKoin().get<OpenDocumentUseCase>()
    return remember(scope, openDocumentUseCase, googleDrivePickerBridge) {
        IosDocumentImporter(
            scope = scope,
            openDocumentUseCase = openDocumentUseCase,
            fileSource = IosDocumentFileSource(),
            googleDrivePickerBridge = googleDrivePickerBridge,
        )
    }
}

private class IosDocumentImporter(
    private val scope: CoroutineScope,
    private val openDocumentUseCase: OpenDocumentUseCase,
    private val fileSource: IosDocumentFileSource,
    private val googleDrivePickerBridge: GoogleDrivePickerBridge?,
) : DocumentImporter {
    override val supportsGoogleDrivePicker: Boolean
        get() = googleDrivePickerBridge?.isConfigured == true

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

    override fun openGoogleDrive(
        onImported: (List<DocumentId>) -> Unit,
        onError: (String) -> Unit,
    ) {
        val bridge = googleDrivePickerBridge
        if (bridge?.isConfigured != true) {
            onError("Google Drive is unavailable on this device.")
            return
        }

        bridge.open(
            onPicked = { result ->
                scope.launch {
                    try {
                        val sources = result.fileIds.map { fileId ->
                            fetchGoogleDriveImportSource(
                                fileId = fileId,
                                accessToken = result.accessToken,
                                fileSource = fileSource,
                            )
                        }
                        val importResult = importDocuments(sources) { source ->
                            openDocumentUseCase(
                                source = source,
                                openedAtEpochMillis = currentTimeMillis(),
                            ).id
                        }
                        if (importResult.importedDocumentIds.isNotEmpty()) {
                            onImported(importResult.importedDocumentIds)
                        }
                        importResult.toImportErrorMessage()?.let(onError)
                    } catch (cancellationException: CancellationException) {
                        throw cancellationException
                    } catch (throwable: Throwable) {
                        onError(throwable.message ?: "Failed to import Google Drive document.")
                    }
                }
            },
            onCancelled = {},
            onError = onError,
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
        var firstFailureReason: String? = null

        urls.forEach { rootUrl ->
            val accessed = rootUrl.startAccessingSecurityScopedResource()
            try {
                val documentUrls = collectSupportedDocumentUrls(rootUrl)
                    .sortedWith(compareBy<NSURL> { (it.lastPathComponent ?: "").lowercase() }.thenBy { it.path ?: "" })
                val result = importDocuments(documentUrls) { url -> importUrl(url, manageSecurityScope = false) }
                importedDocumentIds += result.importedDocumentIds
                failedCount += result.failedCount
                if (firstFailureReason == null) firstFailureReason = result.firstFailureReason
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (throwable: Throwable) {
                failedCount += 1
                if (firstFailureReason == null) firstFailureReason = throwable.importFailureReason()
            } finally {
                if (accessed) rootUrl.stopAccessingSecurityScopedResource()
            }
        }

        return DocumentImportBatchResult(
            importedDocumentIds = importedDocumentIds,
            failedCount = failedCount,
            firstFailureReason = firstFailureReason,
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
            val extension = url.pathExtension?.lowercase()
            val sandboxLocation = fileSource.copyIntoAppContainer(
                sourcePath = sourcePath,
                displayName = displayName,
                mimeType = extension?.let(::mimeTypeForExtension),
            )
            // Every format already gets stream-copied into the sandbox above; reading an EPUB fully
            // into memory here too, just to hand those bytes to a parser that immediately opens the
            // sandboxed copy as a zip anyway, is wasted work — DocumentRepositoryImpl's progressive
            // import streams its own local copy from sandboxLocation instead. DocumentFormatDetector
            // resolves the format from displayName/mimeType alone, so bytes=null costs it nothing.
            val bytes = if (extension == "epub") null else fileSource.readBytes(sandboxLocation)
            val document = openDocumentUseCase(
                source = DocumentImportSource(
                    location = sandboxLocation,
                    bytes = bytes,
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

private suspend fun fetchGoogleDriveImportSource(
    fileId: String,
    accessToken: String,
    fileSource: IosDocumentFileSource,
): DocumentImportSource {
    val metadata = fetchGoogleDriveMetadata(fileId = fileId, accessToken = accessToken)
    check(metadata.canDownload) { "Google Drive file cannot be downloaded: ${metadata.name}" }
    check(metadata.isImportSupported()) { "Unsupported Google Drive document: ${metadata.name}" }
    val bytes = downloadGoogleDriveFile(fileId = fileId, accessToken = accessToken)
    check(bytes.isNotEmpty()) { "Google Drive file is empty: ${metadata.name}" }
    val source = metadata.toDocumentImportSource(bytes)
    return DocumentImportSource(
        location = fileSource.materialize(source.location, bytes),
        bytes = bytes,
    )
}

private suspend fun fetchGoogleDriveMetadata(
    fileId: String,
    accessToken: String,
): GoogleDriveFileMetadata =
    parseDriveFileMetadata(
        executeGoogleDriveRequest(
            urlString = googleDriveMetadataUrl(fileId),
            accessToken = accessToken,
        ).decodeToString(),
    )

private suspend fun downloadGoogleDriveFile(
    fileId: String,
    accessToken: String,
): ByteArray = executeGoogleDriveRequest(
    urlString = googleDriveDownloadUrl(fileId),
    accessToken = accessToken,
)

private suspend fun executeGoogleDriveRequest(
    urlString: String,
    accessToken: String,
): ByteArray {
    val url = NSURL.URLWithString(urlString) ?: error("Invalid Google Drive URL.")
    val request = NSMutableURLRequest.requestWithURL(url).apply {
        setHTTPMethod("GET")
        setValue("Bearer $accessToken", forHTTPHeaderField = "Authorization")
        setValue("application/json, application/octet-stream", forHTTPHeaderField = "Accept")
    }
    val response = NSURLSession.sharedSession.awaitResponse(request)
    if (response.statusCode !in 200..299) {
        throw errorWithStatus(response.statusCode)
    }
    return response.data.toByteArray()
}

private suspend fun NSURLSession.awaitResponse(request: NSURLRequest): IosHttpResponse =
    suspendCancellableCoroutine { continuation ->
        val task = dataTaskWithRequest(request) { data: NSData?, response: NSURLResponse?, error: NSError? ->
            if (!continuation.isActive) return@dataTaskWithRequest
            when {
                error != null -> continuation.resumeWithException(error.toThrowable())
                data == null -> continuation.resumeWithException(error("Google Drive response body was empty."))
                response !is NSHTTPURLResponse ->
                    continuation.resumeWithException(error("Google Drive response was invalid."))
                else -> continuation.resume(IosHttpResponse(statusCode = response.statusCode.toInt(), data = data))
            }
        }
        continuation.invokeOnCancellation { task.cancel() }
        task.resume()
    }

private data class IosHttpResponse(
    val statusCode: Int,
    val data: NSData,
)

private fun NSError.toThrowable(): Throwable =
    if (code.toInt() == -999) CancellationException(localizedDescription)
    else error(localizedDescription)

private fun errorWithStatus(statusCode: Int): Throwable =
    when (statusCode) {
        401 -> error("Google Drive session expired (HTTP 401). Please try again.")
        else -> error("Google Drive request failed with HTTP $statusCode.")
    }

private fun googleDriveMetadataUrl(fileId: String): String =
    "https://www.googleapis.com/drive/v3/files/$fileId" +
        "?fields=id,name,mimeType,size,capabilities(canDownload)&supportsAllDrives=true"

private fun googleDriveDownloadUrl(fileId: String): String =
    "https://www.googleapis.com/drive/v3/files/$fileId" +
        "?alt=media&supportsAllDrives=true"

private fun isSupportedDocumentPath(path: String): Boolean =
    path.substringAfterLast('.', missingDelimiterValue = "").lowercase() in SupportedDocumentExtensions

private fun mimeTypeForExtension(extension: String): String? = when (extension) {
    "txt" -> "text/plain"
    "pdf" -> "application/pdf"
    "epub" -> "application/epub+zip"
    "cbz" -> "application/vnd.comicbook+zip"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    else -> null
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val result = ByteArray(size)
    if (size == 0) return result
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, size.convert())
    }
    return result
}

@OptIn(ExperimentalForeignApi::class)
private fun currentTimeMillis(): Long = time(null) * 1000L

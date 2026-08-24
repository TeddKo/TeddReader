package com.tedd.teddreader.app.reader.importer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.SupportedDocumentExtensions
import com.tedd.teddreader.core.data.storage.IosDocumentFileSource
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
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

/**
 * The Uniform Type Identifiers `UIDocumentPickerViewController` is opened with, one per format
 * this app parses plus `public.zip-archive` so an EPUB or CBZ a provider exposes only as a generic
 * zip is still selectable, the same reasoning the Android and Google Drive pickers apply for
 * `application/zip`.
 */
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

/**
 * Builds iOS's [DocumentImporter], backed by `UIDocumentPickerViewController` for on-device picks
 * and by whichever [GoogleDrivePickerBridge] the Swift host app supplies for Drive — unlike
 * Android, iOS has no equivalent to the Identity `AuthorizationClient` SDK, so Drive support is
 * necessarily delegated to native code outside this Kotlin module.
 *
 * @param googleDrivePickerBridge the Swift-side Drive picker bridge, or null when Drive import is
 *   not configured for this build.
 * @return an iOS-backed [DocumentImporter] remembered for the composition's lifetime.
 */
@Composable
internal actual fun rememberDocumentImporter(
    googleDrivePickerBridge: GoogleDrivePickerBridge?,
): DocumentImporter {
    val scope = rememberCoroutineScope()
    val documentRepository = getKoin().get<DocumentRepository>()
    return remember(scope, documentRepository, googleDrivePickerBridge) {
        IosDocumentImporter(
            scope = scope,
            documentRepository = documentRepository,
            fileSource = IosDocumentFileSource(),
            googleDrivePickerBridge = googleDrivePickerBridge,
        )
    }
}

/**
 * iOS's [DocumentImporter] implementation, wrapping `UIDocumentPickerViewController` for files and
 * folders and forwarding to a [GoogleDrivePickerBridge] for Drive. Every picker presentation keeps
 * its own [IosDocumentPickerDelegate] alive in [delegate], since `UIDocumentPickerViewController`
 * holds only a weak reference to its delegate and the delegate would otherwise be freed before the
 * user finishes picking.
 *
 * @property scope the coroutine scope import work is launched on, tied to the composition that
 *   created this importer.
 * @property documentRepository imports a resolved
 *   [com.tedd.teddreader.core.domain.repository.DocumentImportSource] into the library.
 * @property fileSource copies picked documents into the app's sandbox container.
 * @property googleDrivePickerBridge the Swift-side Drive picker bridge, or null when Drive import
 *   is unavailable.
 */
private class IosDocumentImporter(
    private val scope: CoroutineScope,
    private val documentRepository: DocumentRepository,
    private val fileSource: IosDocumentFileSource,
    private val googleDrivePickerBridge: GoogleDrivePickerBridge?,
) : DocumentImporter {
    /**
     * True only when the Swift host app supplied a [GoogleDrivePickerBridge] and that bridge
     * reports itself configured — this build never has anything else to check Drive support
     * against, unlike Android where the Identity SDK's availability can be probed directly.
     */
    override val supportsGoogleDrivePicker: Boolean
        get() = googleDrivePickerBridge?.isConfigured == true

    /**
     * The delegate for whichever `UIDocumentPickerViewController` is currently presented, held
     * here rather than only as a local variable so it survives for as long as the picker itself
     * does — see the class-level note on why the delegate must be kept alive.
     */
    private var delegate: IosDocumentPickerDelegate? = null

    /**
     * Presents the file picker with `asCopy = true`, so the system hands back a URL to a
     * temporary copy of each picked file that this app already owns, rather than the original
     * security-scoped URL a folder pick returns — a whole file can be copied this way, so there is
     * no need for the explicit `startAccessingSecurityScopedResource` dance [importFolders] has to
     * do for a folder's contents.
     */
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

    /**
     * Presents the folder picker with `asCopy = false`, since an entire folder cannot be handed
     * back as a single copied file the way [openFiles] receives one — the picker returns the
     * original, security-scoped folder URL instead, and [importFolders] is responsible for
     * accessing and walking it.
     */
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

    /**
     * Delegates to [googleDrivePickerBridge] for the native picker/authorization UI, then
     * downloads and imports whatever the user picks the same way the Android implementation does.
     */
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
                            documentRepository.importDocument(
                                source = source,
                                importedAtEpochMillis = currentTimeMillis(),
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

    /**
     * Not yet implemented on iOS: unlike Android, which resolves an external document straight
     * from the `Intent` that delivered it, this platform has no external-delivery path wired up
     * yet, so every call reports the same fixed failure regardless of [request].
     */
    override fun importExternal(
        request: ExternalDocumentImportRequest,
        onImported: (DocumentId) -> Unit,
        onError: (String) -> Unit,
    ) {
        onError("iOS external document import is not connected yet.")
    }

    /**
     * Presents a configured `UIDocumentPickerViewController` and wires its delegate to run
     * [importUrls] over whatever the user picks, converging both [openFiles] and [openFolder] on
     * this one presentation/import/callback plumbing.
     *
     * @param picker the picker to present, already configured with its content types and
     *   `asCopy`/`allowsMultipleSelection` settings.
     * @param onImported forwarded to the resulting [IosDocumentPickerDelegate].
     * @param onError called immediately if there is no view controller available to present from,
     *   or forwarded to the delegate for a failure discovered during import.
     * @param importUrls imports the URLs the user picked; differs between [openFiles] (imports
     *   each URL as a document) and [openFolder] (imports every supported document found inside
     *   each picked folder).
     */
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

    /**
     * Imports every supported document found inside each picked folder root, one folder at a
     * time, merging their [DocumentImportBatchResult]s into a single batch result the same way
     * [importDocuments] merges individual document failures.
     *
     * Each root URL is a security-scoped resource — the picker returned the original folder
     * location rather than a copy (see [openFolder]) — so access is explicitly started before
     * walking it and stopped afterward regardless of outcome; the individual files found inside
     * are then imported with `manageSecurityScope = false` in [importUrl], since the root's own
     * access grant already covers everything beneath it.
     *
     * @param urls the folder root URLs the user picked.
     * @return the combined result across every picked folder.
     */
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

    /**
     * Lists every file under a security-scoped folder root whose extension this app parses,
     * using `NSFileManager.subpathsAtPath` to recurse through the whole tree in one call rather
     * than walking it directory by directory.
     *
     * @param rootUrl the already-access-started folder root to search.
     * @return the supported document URLs found anywhere under [rootUrl], or empty if the root's
     *   path could not be resolved.
     */
    private fun collectSupportedDocumentUrls(rootUrl: NSURL): List<NSURL> {
        val rootPath = rootUrl.path ?: return emptyList()
        return NSFileManager.defaultManager.subpathsAtPath(rootPath)
            ?.filterIsInstance<String>()
            ?.mapNotNull { subpath ->
                subpath.takeIf(::isSupportedDocumentPath)?.let(rootUrl::URLByAppendingPathComponent)
            }
            .orEmpty()
    }

    /**
     * Imports a single document from a picked or discovered [NSURL], the shared routine behind
     * both [openFiles] and the per-file step of [importFolders].
     *
     * Every format is copied into the app's own sandbox container via [fileSource] first — the
     * picked URL itself, whether a temporary copy ([openFiles]) or a security-scoped original
     * location ([openFolder]), is not guaranteed to remain valid or accessible for as long as the
     * imported document needs to stay readable. For an EPUB, [fileSource]'s bytes are then
     * deliberately left null rather than read again here: the sandbox copy already exists as a
     * real file on disk, so reading it fully into memory just to hand those bytes to a parser that
     * immediately reopens that same sandboxed copy as a zip anyway would be wasted work —
     * `DocumentFormatDetector` resolves the format from `displayName`/`mimeType` alone, so
     * `bytes = null` costs it nothing, and `DocumentRepositoryImpl`'s progressive import streams
     * its own local copy from `sandboxLocation` instead.
     *
     * @param url the document URL to import.
     * @param manageSecurityScope whether this call must itself start and stop security-scoped
     *   access to [url]; true when called directly from [openFiles], false when called from
     *   [importFolders] for a file whose containing folder root already holds that access.
     * @return the imported document's [DocumentId].
     * @throws IllegalStateException if [url]'s path cannot be read.
     */
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
            val bytes = if (extension == "epub") null else fileSource.readBytes(sandboxLocation)
            val document = documentRepository.importDocument(
                source = DocumentImportSource(
                    location = sandboxLocation,
                    bytes = bytes,
                ),
                importedAtEpochMillis = currentTimeMillis(),
            )
            document.id
        } finally {
            if (accessed) url.stopAccessingSecurityScopedResource()
        }
    }
}

/**
 * The `UIDocumentPickerDelegateProtocol` conformance behind one presented
 * `UIDocumentPickerViewController`, bridging its Objective-C callback-based delegate methods into
 * the coroutine-based [importUrls] import step and the [DocumentImporter] callback pair a picker
 * call was opened with.
 *
 * @property scope the coroutine scope the import work resulting from a pick is launched on.
 * @property onImported called with every successfully imported document once import finishes.
 * @property onError called with a user-facing message if any import failed or nothing was picked.
 * @property importUrls imports the URLs the user picked; supplied by [IosDocumentImporter] to
 *   differ between a files pick and a folder pick.
 */
private class IosDocumentPickerDelegate(
    private val scope: CoroutineScope,
    private val onImported: (List<DocumentId>) -> Unit,
    private val onError: (String) -> Unit,
    private val importUrls: suspend (List<NSURL>) -> DocumentImportBatchResult,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    /**
     * Called by UIKit once the user finishes picking. Launches [importUrls] on [scope] and
     * reports the result through [onImported]/[onError]; reports an error immediately, without
     * launching anything, if the picker somehow completed with no URLs at all.
     *
     * @param controller the picker that completed.
     * @param didPickDocumentsAtURLs the picked URLs, typed `List<*>` because that is the
     *   Objective-C bridge's signature; filtered down to [NSURL] before use.
     */
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

    /**
     * Called by UIKit when the user dismisses the picker without picking anything; deliberately a
     * no-op, since dismissing a picker without choosing a document is not itself a failure worth
     * reporting through [onError].
     *
     * @param controller the picker that was cancelled.
     */
    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = Unit
}

/**
 * Fetches, validates, downloads, and materializes one Google Drive file into app storage — the
 * iOS equivalent of the Android importer's Drive fetch-then-`copyMaterialized` pair, done here in
 * a single function since iOS's Drive flow only ever imports through [IosDocumentImporter], with
 * no separate download step shared with anything else.
 *
 * @param fileId the Drive file id to fetch.
 * @param accessToken the bearer token authorizing the request.
 * @param fileSource materializes the downloaded bytes into the app's sandbox container.
 * @return a [DocumentImportSource] pointing at the materialized copy, still carrying the full
 *   bytes.
 * @throws IllegalStateException if the file cannot be downloaded, is not an importable format, or
 *   downloads empty.
 */
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

/**
 * Requests and parses one Drive file's metadata via the `files.get` REST endpoint.
 *
 * @param fileId the Drive file id to describe.
 * @param accessToken the bearer token authorizing the request.
 * @return the parsed [GoogleDriveFileMetadata].
 */
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

/**
 * Downloads one Drive file's full content via the `files.get?alt=media` REST endpoint.
 *
 * @param fileId the Drive file id to download.
 * @param accessToken the bearer token authorizing the request.
 * @return the file's raw bytes.
 */
private suspend fun downloadGoogleDriveFile(
    fileId: String,
    accessToken: String,
): ByteArray = executeGoogleDriveRequest(
    urlString = googleDriveDownloadUrl(fileId),
    accessToken = accessToken,
)

/**
 * Runs one authenticated `GET` request against the Google Drive REST API on iOS's native URL
 * loading system, used for both the metadata and download endpoints.
 *
 * @param urlString the full request URL, built by [googleDriveMetadataUrl] or
 *   [googleDriveDownloadUrl].
 * @param accessToken the bearer token to send in the `Authorization` header.
 * @return the response body's raw bytes.
 * @throws IllegalStateException if [urlString] is not a valid URL.
 * @throws Throwable the error [NSURLSession.awaitResponse] or [errorWithStatus] produces if the
 *   request fails or returns a non-2xx status.
 */
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

/**
 * Adapts `NSURLSession`'s callback-based `dataTaskWithRequest` into a suspend call, cancelling the
 * underlying task if the coroutine is cancelled first and ignoring a callback that arrives after
 * the coroutine already stopped waiting.
 *
 * @receiver the session to run the request on.
 * @param request the request to execute.
 * @return the response's status code and body, wrapped as [IosHttpResponse].
 * @throws Throwable [NSError.toThrowable]'s result if the task failed, or an
 *   [IllegalStateException] if the response body or headers were missing or of the wrong type.
 */
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

/**
 * The minimal shape [NSURLSession.awaitResponse] needs from a completed HTTP response, decoupling
 * the rest of this file from Foundation's richer `NSHTTPURLResponse` type.
 *
 * @property statusCode the HTTP status code the response carried.
 * @property data the response body.
 */
private data class IosHttpResponse(
    val statusCode: Int,
    val data: NSData,
)

/**
 * Converts a Foundation `NSError` from a failed URL session task into a Kotlin [Throwable],
 * recognizing `NSURLErrorCancelled` (`-999`) specifically as a [CancellationException] so a
 * cancelled request propagates as a coroutine cancellation rather than as an ordinary failure.
 *
 * @receiver the error the task failed with.
 * @return the equivalent [Throwable].
 */
private fun NSError.toThrowable(): Throwable =
    if (code.toInt() == -999) CancellationException(localizedDescription)
    else error(localizedDescription)

/**
 * Builds the [Throwable] to report for a non-2xx Google Drive HTTP response, giving `401`
 * specifically a session-expiry message since that is the one status this app's Drive flow can
 * meaningfully explain to the user.
 *
 * @param statusCode the HTTP status code the request failed with.
 * @return the [Throwable] to raise.
 */
private fun errorWithStatus(statusCode: Int): Throwable =
    when (statusCode) {
        401 -> error("Google Drive session expired (HTTP 401). Please try again.")
        else -> error("Google Drive request failed with HTTP $statusCode.")
    }

/**
 * Builds the `files.get` metadata URL for one Drive file, requesting only the fields
 * [GoogleDriveFileMetadata] needs and `supportsAllDrives=true` so a file living in a shared drive
 * resolves the same as one in the user's own Drive.
 *
 * @param fileId the Drive file id.
 * @return the full metadata request URL.
 */
private fun googleDriveMetadataUrl(fileId: String): String =
    "https://www.googleapis.com/drive/v3/files/$fileId" +
        "?fields=id,name,mimeType,size,capabilities(canDownload)&supportsAllDrives=true"

/**
 * Builds the `files.get?alt=media` download URL for one Drive file's content, with the same
 * shared-drive support as [googleDriveMetadataUrl].
 *
 * @param fileId the Drive file id.
 * @return the full download request URL.
 */
private fun googleDriveDownloadUrl(fileId: String): String =
    "https://www.googleapis.com/drive/v3/files/$fileId" +
        "?alt=media&supportsAllDrives=true"

/**
 * Whether a file found while walking a picked folder tree is one this app parses, by extension
 * alone — [collectSupportedDocumentUrls] has only a file-system path to check, with no MIME type
 * available the way the Android tree walker's `DocumentsContract` query provides one.
 *
 * @param path the file's path or subpath, as `NSFileManager.subpathsAtPath` reports it.
 * @return true when the path's extension is a supported one.
 */
private fun isSupportedDocumentPath(path: String): Boolean =
    path.substringAfterLast('.', missingDelimiterValue = "").lowercase() in SupportedDocumentExtensions

/**
 * Maps a lowercased file extension to the MIME type [IosDocumentFileSource.copyIntoAppContainer]
 * records for the sandboxed copy, since a picked iOS file's `UTType` does not directly hand back
 * the plain MIME type string the rest of the app's format detection expects.
 *
 * @param extension the lowercased file extension, without the leading dot.
 * @return the corresponding MIME type, or null when [extension] is not one of the formats this
 *   app recognizes.
 */
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

/**
 * Copies this Foundation `NSData`'s bytes into a Kotlin [ByteArray], since Google Drive's
 * downloaded response body arrives as `NSData` and the rest of the import pipeline works with
 * plain byte arrays.
 *
 * @receiver the data to copy.
 * @return a new [ByteArray] with the same contents, empty when this data is empty.
 */
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

/**
 * The current wall-clock time in epoch milliseconds, read from POSIX `time()` since
 * `kotlin.time`/`Clock` was not otherwise in use here; used to stamp a Google-Drive-imported
 * document's import time the same way Android stamps one with `System.currentTimeMillis()`.
 *
 * @return the current time in milliseconds since the Unix epoch, at one-second resolution.
 */
@OptIn(ExperimentalForeignApi::class)
private fun currentTimeMillis(): Long = time(null) * 1000L

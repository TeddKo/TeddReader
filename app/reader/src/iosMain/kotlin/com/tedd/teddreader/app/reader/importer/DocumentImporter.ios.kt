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
 * `UIDocumentPickerViewController`를 열 때 사용하는 Uniform Type Identifier다. 이 앱이 파싱하는
 * 형식마다 하나씩 포함하고 `public.zip-archive`를 추가하여 provider가 EPUB 또는 CBZ를 일반 zip으로만
 * 노출해도 선택할 수 있게 한다. Android와 Google Drive 선택기에 `application/zip`을 적용하는 이유와
 * 같다.
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
 * iOS의 [DocumentImporter]를 구성한다. 기기 내 선택에는 `UIDocumentPickerViewController`를 사용하고,
 * Drive에는 Swift 호스트 앱이 제공한 [GoogleDrivePickerBridge]를 사용한다. Android와 달리 iOS에는
 * Identity `AuthorizationClient` SDK에 대응하는 기능이 없으므로 Drive 지원은 이 Kotlin 모듈 밖의
 * 네이티브 코드에 위임해야 한다.
 *
 * @param googleDrivePickerBridge Swift 측 Drive 선택기 브리지다. 이 빌드에 Drive 가져오기가 구성되지
 *   않았으면 null이다.
 * @return 컴포지션 수명 동안 기억되는 iOS 기반 [DocumentImporter]다.
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
 * 파일과 폴더에는 `UIDocumentPickerViewController`를 감싸고 Drive에는
 * [GoogleDrivePickerBridge]로 전달하는 iOS [DocumentImporter] 구현이다. 각 선택기를 표시할 때 자체
 * [IosDocumentPickerDelegate]를 [delegate]에 보관한다. `UIDocumentPickerViewController`는 delegate의
 * 약한 참조만 보관하므로 이렇게 하지 않으면 사용자가 선택을 끝내기 전에 delegate가 해제된다.
 *
 * @property scope 이 임포터를 만든 컴포지션에 연결되어 가져오기 작업을 실행하는 코루틴 범위다.
 * @property documentRepository 해석된
 *   [com.tedd.teddreader.core.domain.repository.DocumentImportSource]를 라이브러리로 가져온다.
 * @property fileSource 선택한 문서를 앱의 샌드박스 컨테이너로 복사한다.
 * @property googleDrivePickerBridge Swift 측 Drive 선택기 브리지이며 Drive 가져오기를 사용할 수 없으면
 *   null이다.
 */
private class IosDocumentImporter(
    private val scope: CoroutineScope,
    private val documentRepository: DocumentRepository,
    private val fileSource: IosDocumentFileSource,
    private val googleDrivePickerBridge: GoogleDrivePickerBridge?,
) : DocumentImporter {
    /**
     * Swift 호스트 앱이 [GoogleDrivePickerBridge]를 제공하고 해당 브리지가 구성되었다고 보고할 때만
     * true다. Identity SDK의 사용 가능 여부를 직접 확인할 수 있는 Android와 달리 이 빌드에는 Drive
     * 지원을 확인할 다른 대상이 없다.
     */
    override val supportsGoogleDrivePicker: Boolean
        get() = googleDrivePickerBridge?.isConfigured == true

    /**
     * 현재 표시 중인 `UIDocumentPickerViewController`의 delegate다. 지역 변수로만 두지 않고 여기에
     * 보관하여 선택기 자체와 같은 기간 동안 유지한다. delegate를 살려 둬야 하는 이유는 클래스 수준
     * 설명을 참고한다.
     */
    private var delegate: IosDocumentPickerDelegate? = null

    /**
     * 파일 선택기를 `asCopy = true`로 표시하여, 폴더 선택이 반환하는 원본 보안 범위 URL 대신 앱이
     * 이미 소유한 각 선택 파일의 임시 복사본 URL을 시스템에서 받는다. 파일 전체는 이 방식으로 복사할
     * 수 있으므로, 폴더 콘텐츠에 [importFolders]가 수행해야 하는 명시적
     * `startAccessingSecurityScopedResource` 절차가 필요 없다.
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
     * 폴더 전체는 [openFiles]가 받는 것처럼 복사된 파일 하나로 반환할 수 없으므로 폴더 선택기를
     * `asCopy = false`로 표시한다. 선택기는 원본 보안 범위 폴더 URL을 반환하며 [importFolders]가
     * 접근과 탐색을 담당한다.
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
     * 네이티브 선택기/인증 UI는 [googleDrivePickerBridge]에 위임한 뒤, Android 구현과 같은 방식으로
     * 사용자가 선택한 항목을 다운로드하여 가져온다.
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
     * iOS에는 아직 구현되지 않았다. 전달한 `Intent`에서 외부 문서를 바로 해석하는 Android와 달리 이
     * 플랫폼에는 외부 전달 경로가 아직 연결되지 않아 [request]와 관계없이 모든 호출이 같은 고정
     * 실패를 보고한다.
     */
    override fun importExternal(
        request: ExternalDocumentImportRequest,
        onImported: (DocumentId) -> Unit,
        onError: (String) -> Unit,
    ) {
        onError("iOS external document import is not connected yet.")
    }

    /**
     * 구성된 `UIDocumentPickerViewController`를 표시하고 사용자가 선택한 항목에 [importUrls]를
     * 실행하도록 delegate를 연결한다. [openFiles]와 [openFolder]가 이 하나의 표시/가져오기/콜백
     * 처리로 합류한다.
     *
     * @param picker 콘텐츠 타입과 `asCopy`/`allowsMultipleSelection` 설정을 이미 구성하여 표시할
     *   선택기다.
     * @param onImported 생성한 [IosDocumentPickerDelegate]에 전달한다.
     * @param onError 표시를 시작할 view controller가 없으면 즉시 호출하고, 가져오기 중 발견한 실패에는
     *   delegate로 전달한다.
     * @param importUrls 사용자가 선택한 URL을 가져온다. [openFiles]에서는 각 URL을 문서로 가져오고,
     *   [openFolder]에서는 선택한 각 폴더 안에서 찾은 지원 문서를 모두 가져온다.
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
     * 선택한 각 폴더 루트 안에서 찾은 지원 문서를 한 폴더씩 모두 가져오고, 각각의
     * [DocumentImportBatchResult]를 [importDocuments]가 개별 문서 실패를 병합하는 것과 같은 방식으로
     * 하나의 배치 결과로 병합한다.
     *
     * 각 루트 URL은 보안 범위 리소스다. 선택기가 복사본이 아닌 원본 폴더 위치를 반환했으므로
     * ([openFolder] 참고) 탐색 전에 명시적으로 접근을 시작하고 결과와 관계없이 이후 중지한다. 루트의
     * 접근 권한이 아래의 모든 항목을 이미 포괄하므로, 내부에서 찾은 개별 파일은 [importUrl]에서
     * `manageSecurityScope = false`로 가져온다.
     *
     * @param urls 사용자가 선택한 폴더 루트 URL이다.
     * @return 선택한 모든 폴더의 결합 결과다.
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
     * 보안 범위 폴더 루트 아래에서 이 앱이 파싱하는 확장자를 가진 모든 파일을 나열한다. 디렉터리를
     * 하나씩 탐색하는 대신 `NSFileManager.subpathsAtPath` 호출 하나로 전체 트리를 재귀 탐색한다.
     *
     * @param rootUrl 접근을 이미 시작한 검색 대상 폴더 루트다.
     * @return [rootUrl] 아래 어디에서든 찾은 지원 문서 URL이며, 루트 경로를 해석할 수 없으면 빈
     *   목록이다.
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
     * 선택하거나 탐색으로 찾은 [NSURL]에서 문서 하나를 가져온다. [openFiles]와 [importFolders]의
     * 파일별 단계가 공유하는 루틴이다.
     *
     * 모든 형식은 먼저 [fileSource]를 통해 앱 자체 샌드박스 컨테이너로 복사한다. 선택한 URL이
     * 임시 복사본([openFiles])이든 보안 범위 원본 위치([openFolder])든 가져온 문서를 읽어야 하는
     * 기간 내내 유효하거나 접근 가능하다고 보장되지 않기 때문이다. EPUB과 CBZ 압축 파일에서는
     * [fileSource]의 바이트를 여기서 다시 읽지 않고 의도적으로 null로 둔다. 샌드박스 복사본이 이미
     * 디스크의 실제 파일이므로, 전체를 메모리로 읽어 곧바로 임시 복사본을 zip으로 다시 여는 파서에
     * 전달하는 것은 낭비다. `DocumentFormatDetector`는 `displayName`/`mimeType`만으로 두 형식을
     * 해석하고, `DocumentRepositoryImpl`은 압축 파일을 열기 전에 `sandboxLocation`에서
     * [DocumentFileSource.copyTo]를 통해 복사한다.
     *
     * @param url 가져올 문서 URL이다.
     * @param manageSecurityScope 이 호출 자체에서 [url]의 보안 범위 접근을 시작하고 중지해야 하는지
     *   나타낸다. [openFiles]에서 직접 호출하면 true이고, 폴더 루트가 이미 접근 권한을 보유한 파일을
     *   [importFolders]에서 호출하면 false다.
     * @return 가져온 문서의 [DocumentId]다.
     * @throws IllegalStateException [url]의 경로를 읽을 수 없을 때 발생한다.
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
            val isArchiveFormat = extension == "epub" || extension == "cbz"
            val bytes = if (isArchiveFormat) null else fileSource.readBytes(sandboxLocation)
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
 * 표시된 `UIDocumentPickerViewController` 하나를 위한 `UIDocumentPickerDelegateProtocol` 구현이다.
 * Objective-C의 콜백 기반 delegate 메서드를 코루틴 기반 [importUrls] 가져오기 단계와 선택기를 열 때
 * 전달받은 [DocumentImporter] 콜백 쌍으로 연결한다.
 *
 * @property scope 선택 결과의 가져오기 작업을 실행하는 코루틴 범위다.
 * @property onImported 가져오기가 끝나면 성공한 모든 문서와 함께 호출한다.
 * @property onError 가져오기가 실패했거나 아무것도 선택하지 않았을 때 사용자에게 표시할 메시지와
 *   함께 호출한다.
 * @property importUrls 사용자가 선택한 URL을 가져온다. 파일 선택과 폴더 선택을 다르게 처리하도록
 *   [IosDocumentImporter]가 제공한다.
 */
private class IosDocumentPickerDelegate(
    private val scope: CoroutineScope,
    private val onImported: (List<DocumentId>) -> Unit,
    private val onError: (String) -> Unit,
    private val importUrls: suspend (List<NSURL>) -> DocumentImportBatchResult,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    /**
     * 사용자가 선택을 끝내면 UIKit이 호출한다. [scope]에서 [importUrls]를 실행하고 결과를
     * [onImported]/[onError]로 보고한다. 선택기가 URL 없이 완료되는 경우에는 아무 작업도 실행하지 않고
     * 즉시 오류를 보고한다.
     *
     * @param controller 완료된 선택기다.
     * @param didPickDocumentsAtURLs 선택한 URL이다. Objective-C 브리지 시그니처에 따라 `List<*>`로
     *   타입이 지정되며 사용 전에 [NSURL]만 남긴다.
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
     * 사용자가 아무것도 선택하지 않고 선택기를 닫으면 UIKit이 호출한다. 문서를 고르지 않고 선택기를
     * 닫은 것은 [onError]로 보고할 실패가 아니므로 의도적으로 아무 작업도 하지 않는다.
     *
     * @param controller 취소된 선택기다.
     */
    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = Unit
}

/**
 * Google Drive 파일 하나를 가져오고 검증하고 다운로드하여 앱 저장소에 구체화한다. Android 임포터의
 * Drive 가져오기 후 `copyMaterialized` 쌍에 대응하는 iOS 처리다. iOS의 Drive 흐름은
 * [IosDocumentImporter]를 통해서만 가져오고 다른 처리와 공유하는 별도 다운로드 단계가 없으므로 한
 * 함수에서 수행한다.
 *
 * @param fileId 가져올 Drive 파일 id다.
 * @param accessToken 요청을 인증하는 bearer 토큰이다.
 * @param fileSource 다운로드한 바이트를 앱의 샌드박스 컨테이너에 구체화한다.
 * @return 구체화한 복사본을 가리키면서 전체 바이트도 담고 있는 [DocumentImportSource]다.
 * @throws IllegalStateException 파일을 다운로드할 수 없거나 가져올 수 없는 형식이거나 다운로드
 *   결과가 비어 있으면 발생한다.
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
 * `files.get` REST 엔드포인트로 Drive 파일 하나의 메타데이터를 요청하고 파싱한다.
 *
 * @param fileId 설명할 Drive 파일 id다.
 * @param accessToken 요청을 인증하는 bearer 토큰이다.
 * @return 파싱한 [GoogleDriveFileMetadata]다.
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
 * `files.get?alt=media` REST 엔드포인트로 Drive 파일 하나의 전체 콘텐츠를 다운로드한다.
 *
 * @param fileId 다운로드할 Drive 파일 id다.
 * @param accessToken 요청을 인증하는 bearer 토큰이다.
 * @return 파일의 원본 바이트다.
 */
private suspend fun downloadGoogleDriveFile(
    fileId: String,
    accessToken: String,
): ByteArray = executeGoogleDriveRequest(
    urlString = googleDriveDownloadUrl(fileId),
    accessToken = accessToken,
)

/**
 * iOS의 네이티브 URL 로딩 시스템에서 Google Drive REST API에 인증된 `GET` 요청 하나를 실행한다.
 * 메타데이터와 다운로드 엔드포인트에 모두 사용한다.
 *
 * @param urlString [googleDriveMetadataUrl] 또는 [googleDriveDownloadUrl]이 구성한 전체 요청 URL이다.
 * @param accessToken `Authorization` 헤더에 보낼 bearer 토큰이다.
 * @return 응답 본문의 원본 바이트다.
 * @throws IllegalStateException [urlString]이 유효한 URL이 아니면 발생한다.
 * @throws Throwable 요청이 실패하거나 2xx가 아닌 상태를 반환했을 때 [NSURLSession.awaitResponse] 또는
 *   [errorWithStatus]가 만든 오류다.
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
 * `NSURLSession`의 콜백 기반 `dataTaskWithRequest`를 suspend 호출로 변환한다. 코루틴이 먼저 취소되면
 * 내부 task를 취소하고, 코루틴이 대기를 끝낸 뒤 도착한 콜백은 무시한다.
 *
 * @receiver 요청을 실행할 session이다.
 * @param request 실행할 요청이다.
 * @return 응답 상태 코드와 본문을 [IosHttpResponse]로 감싼 값이다.
 * @throws Throwable task가 실패하면 [NSError.toThrowable]의 결과를 던진다. 응답 본문이나 헤더가
 *   없거나 타입이 잘못되었으면 [IllegalStateException]을 던진다.
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
 * 완료된 HTTP 응답에서 [NSURLSession.awaitResponse]에 필요한 최소 형태다. 이 파일의 나머지 부분을
 * Foundation의 더 풍부한 `NSHTTPURLResponse` 타입과 분리한다.
 *
 * @property statusCode 응답이 전달한 HTTP 상태 코드다.
 * @property data 응답 본문이다.
 */
private data class IosHttpResponse(
    val statusCode: Int,
    val data: NSData,
)

/**
 * 실패한 URL session task의 Foundation `NSError`를 Kotlin [Throwable]로 변환한다.
 * `NSURLErrorCancelled`(`-999`)는 특별히 [CancellationException]으로 인식하여 취소된 요청이 일반
 * 실패가 아니라 코루틴 취소로 전파되게 한다.
 *
 * @receiver task가 실패하며 전달한 오류다.
 * @return 동등한 [Throwable]이다.
 */
private fun NSError.toThrowable(): Throwable =
    if (code.toInt() == -999) CancellationException(localizedDescription)
    else error(localizedDescription)

/**
 * 2xx가 아닌 Google Drive HTTP 응답에 보고할 [Throwable]을 구성한다. 이 앱의 Drive 흐름에서
 * 사용자에게 의미 있게 설명할 수 있는 상태인 `401`에는 특별히 세션 만료 메시지를 사용한다.
 *
 * @param statusCode 요청이 실패한 HTTP 상태 코드다.
 * @return 발생시킬 [Throwable]이다.
 */
private fun errorWithStatus(statusCode: Int): Throwable =
    when (statusCode) {
        401 -> error("Google Drive session expired (HTTP 401). Please try again.")
        else -> error("Google Drive request failed with HTTP $statusCode.")
    }

/**
 * Drive 파일 하나의 `files.get` 메타데이터 URL을 구성한다. [GoogleDriveFileMetadata]에 필요한
 * 필드만 요청하고 `supportsAllDrives=true`를 사용하여 공유 drive의 파일도 사용자 자신의 Drive
 * 파일과 같은 방식으로 해석한다.
 *
 * @param fileId Drive 파일 id다.
 * @return 전체 메타데이터 요청 URL이다.
 */
private fun googleDriveMetadataUrl(fileId: String): String =
    "https://www.googleapis.com/drive/v3/files/$fileId" +
        "?fields=id,name,mimeType,size,capabilities(canDownload)&supportsAllDrives=true"

/**
 * Drive 파일 하나의 콘텐츠를 위한 `files.get?alt=media` 다운로드 URL을 구성한다.
 * [googleDriveMetadataUrl]과 동일하게 공유 drive를 지원한다.
 *
 * @param fileId Drive 파일 id다.
 * @return 전체 다운로드 요청 URL이다.
 */
private fun googleDriveDownloadUrl(fileId: String): String =
    "https://www.googleapis.com/drive/v3/files/$fileId" +
        "?alt=media&supportsAllDrives=true"

/**
 * 선택한 폴더 트리를 탐색하며 찾은 파일을 확장자만으로 이 앱이 파싱할 수 있는지 판단한다.
 * [collectSupportedDocumentUrls]에는 확인할 파일 시스템 경로만 있고 Android 트리 탐색기의
 * `DocumentsContract` 조회처럼 사용할 MIME 타입은 없다.
 *
 * @param path `NSFileManager.subpathsAtPath`가 보고한 파일 경로나 하위 경로다.
 * @return 경로의 확장자가 지원 대상이면 true다.
 */
private fun isSupportedDocumentPath(path: String): Boolean =
    path.substringAfterLast('.', missingDelimiterValue = "").lowercase() in SupportedDocumentExtensions

/**
 * 소문자 파일 확장자를 [IosDocumentFileSource.copyIntoAppContainer]가 샌드박스 복사본에 기록할 MIME
 * 타입으로 매핑한다. 선택한 iOS 파일의 `UTType`은 앱의 나머지 형식 감지가 요구하는 일반 MIME 타입
 * 문자열을 직접 반환하지 않는다.
 *
 * @param extension 앞의 점을 제외한 소문자 파일 확장자다.
 * @return 대응하는 MIME 타입이며 [extension]이 이 앱이 인식하는 형식이 아니면 null이다.
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
 * Foundation `NSData`의 바이트를 Kotlin [ByteArray]로 복사한다. Google Drive에서 다운로드한 응답
 * 본문은 `NSData`로 도착하지만 가져오기 파이프라인의 나머지 부분은 일반 바이트 배열을 사용한다.
 *
 * @receiver 복사할 데이터다.
 * @return 같은 콘텐츠를 담은 새 [ByteArray]이며 이 데이터가 비어 있으면 빈 배열이다.
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
 * POSIX `time()`에서 읽은 epoch 밀리초 단위의 현재 실제 시각이다. 여기서는 달리
 * `kotlin.time`/`Clock`을 사용하지 않으므로, Google Drive에서 가져온 문서에 Android가
 * `System.currentTimeMillis()`로 기록하는 것과 같은 방식으로 가져오기 시각을 기록하는 데 사용한다.
 *
 * @return Unix epoch 이후 현재 시각의 밀리초 값이며 1초 해상도다.
 */
@OptIn(ExperimentalForeignApi::class)
private fun currentTimeMillis(): Long = time(null) * 1000L

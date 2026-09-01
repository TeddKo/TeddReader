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
 * Android의 `OpenMultipleDocuments` 선택기로 "파일 열기" 진입점을 열 때 사용하는 MIME 타입
 * 필터다. [SupportedDocumentMimeTypes]가 인식하는 모든 형식에 `application/zip`을 더해, content
 * provider가 EPUB 또는 CBZ를 일반 zip 압축 파일로만 보고해도 선택할 수 있게 한다. Drive에서
 * [GoogleDriveSupportedDocumentMimeTypes]를 적용하는 이유와 같다.
 */
private val AndroidPickerMimeTypes = (SupportedDocumentMimeTypes + "application/zip").toTypedArray()

/**
 * [GoogleDriveSupportedDocumentMimeTypes]를 [List]로 표현한 값이다.
 * `buildGoogleDriveAuthorizationRequest`의 `PICKER_MIMETYPES` 리소스 매개변수는 [Set]이 아니라 쉼표
 * 구분 문자열로 연결할 순서 있는 컬렉션이 필요하다.
 */
internal val AndroidGoogleDriveMimeTypes = GoogleDriveSupportedDocumentMimeTypes.toList()

/**
 * Android의 [DocumentImporter]다. 기기 내 선택에는 SAF(`OpenMultipleDocuments`/`OpenDocumentTree`)를,
 * Google Drive에는 Google Play Services Identity의 `AuthorizationClient`를 사용한다. Android에는
 * iOS와 같은 브리지 기반 Drive 연동이 없으므로 [googleDrivePickerBridge]는 `expect` 선언과 공유하는
 * 시그니처를 맞추기 위해 받기만 하고 읽지 않는다.
 *
 * 각 선택기는 `rememberLauncherForActivityResult`를 통해 한 번 등록한
 * [androidx.activity.result.ActivityResultLauncher]다. 결과 콜백은 이를 실행한 [DocumentImporter]
 * 메서드 호출과 독립적으로 비동기 실행된다. launcher의 콜백은 등록 시 고정되므로 호출자가
 * [openFiles], [openFolder], [openGoogleDrive]에 전달한 `onImported`/`onError` 콜백을 launcher
 * 클로저에서 직접 캡처할 수 없다. 대신 각 `launch()` 직전에 `importedCallback`/`errorCallback`
 * 가변 상태에 저장하여 launcher 콜백이 가장 최근 호출에서 설정한 쌍을 읽게 한다. 시도가 끝나면 두
 * 값을 다시 빈 동작으로 초기화하여 이후의 잘못된 콜백 호출이 완료된 시도의 콜백을 다시 실행하지
 * 못하게 한다.
 *
 * Google Drive 흐름에는 launcher가 두 개 필요하다. Play Services Identity의 `authorize()` 호출은
 * UI를 항상 표시하지 않기 때문이다. 계정이 이미 인증되었으면 `hasResolution()`이 없는 결과를
 * 반환하여 즉시 처리한다. 그렇지 않으면 `pendingIntent`를 `googleDriveAuthorizationLauncher`로
 * 실행하고, 이 launcher의 결과도 같은 [GoogleDrivePickerResult] 형태로 변환하여 두 경로가 동일한
 * 다운로드/가져오기 단계로 합류한다.
 *
 * @param googleDrivePickerBridge `expect fun` 시그니처를 충족하기 위해서만 받는다. Android의 Drive
 *   연동은 자체 완결적이며 플랫폼 브리지를 사용하지 않는다.
 * @return 컴포지션 수명 동안 기억되는 Android 기반 [DocumentImporter]다.
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
 * [ExternalDocumentImportRequest]가 설명하는 단일 문서를 가져오며 [importUri]의
 * `materializeInAppStorage`를 true로 강제한다. SAF로 선택한 문서는 지속된 읽기 권한이 유지되는 동안
 * `content://` URI가 유효하지만, 수신 `VIEW`/`SEND` 인텐트로 전달된 문서는 일시적 권한이나 전달 앱의
 * 프로세스가 끝나면 사라지는 임시 파일에만 기반할 수 있다. 따라서 나중에도 읽을 수 있으리라
 * 가정하지 않고 즉시 앱 저장소에 복사한다.
 *
 * @param context 문서를 해석하고 읽는 [ContentResolver]에 접근하는 데 사용한다.
 * @param request 원본 인텐트에서 이미 해석한 대체 메타데이터를 제공하는 외부 전달 문서다.
 * @param documentRepository 해석된
 *   [com.tedd.teddreader.core.domain.repository.DocumentImportSource]를 가져온다.
 * @param documentFileSource 문서를 앱 저장소에 구체화한다.
 * @return 가져온 문서의 [DocumentId]다.
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
 * `content://` [Uri]로 식별한 문서 하나를 가져온다. 직접 SAF로 선택한 문서, 선택한 폴더를 탐색하며
 * 찾은 문서, [importExternalRequest]를 통한 외부 전달 문서 등 모든 Android 가져오기 경로가 공유하는
 * 루틴이다.
 *
 * [grantFlags]에 읽기 URI 권한이 있으면 이를 지속하여, 원래 권한을 부여한 짧은 activity 호출이나
 * 인텐트 전달이 끝난 뒤에도 결과 [DocumentLocation]을 열 수 있게 한다. 이 처리가 없으면 해당
 * 컨텍스트가 끝날 때 권한이 취소되어 나중에 가져온 문서를 다시 열지 못한다.
 *
 * EPUB 또는 CBZ에서는 [bytes]를 여기서 읽지 않고 의도적으로 null로 둔다. 두 형식 모두 파서가
 * 필요에 따라 열고 탐색하는 zip 압축 파일이므로, 전체를 메모리로 읽은 뒤 파서가 즉시 임시 파일로
 * 다시 쓰게 하는 것은 낭비다. CBZ는 애초에 바이트가 필요하지도 않았으며 파일이 클수록 비용이 크다.
 * `DocumentFormatDetector`는 `displayName`/`mimeType`만으로 형식을 해석하므로 `bytes = null`이어도
 * 손실이 없다. 대신 `DocumentRepositoryImpl`의 점진적 가져오기가 CBZ 가져오기에서 기존에 하던 것과
 * 같은 방식으로 [DocumentLocation]의 자체 로컬 복사본을 스트리밍한다. 이 방식은 EPUB에도 적용된다.
 *
 * [materializeInAppStorage]가 요청되면 zip 형식은 [bytes] 없이 소스 URI에서 직접 스트리밍하는
 * [AndroidDocumentFileSource.materializeFromSource]로 복사하고, 나머지 형식은 이미 메모리에 읽은
 * 바이트를 [AndroidDocumentFileSource.materialize]에 전달해 복사한다. 요청되지 않으면 [location]
 * 자체를 그대로 저장하여 원본 `content://` URI를 문서 파일 소스로 유지한다.
 *
 * @param context 문서를 해석하고 읽는 [ContentResolver]에 접근하는 데 사용한다.
 * @param uri 가져올 `content://` URI다.
 * @param grantFlags 원본 인텐트의 플래그다. `Intent.FLAG_GRANT_READ_URI_PERMISSION`을 검사하여 권한을
 *   지속할지 결정한다.
 * @param documentRepository 생성한
 *   [com.tedd.teddreader.core.domain.repository.DocumentImportSource]를 가져온다.
 * @param documentFileSource [materializeInAppStorage]가 true일 때 문서를 앱 저장소에 구체화한다.
 * @param overrideDisplayName [uri] 자체의 메타데이터에서 해석한 값보다 우선할 표시 이름이다. 외부
 *   가져오기 요청처럼 호출자가 이미 더 신뢰할 값을 해석한 경우 사용한다.
 * @param overrideMimeType [uri] 자체의 메타데이터에서 해석한 값보다 우선할 MIME 타입이다.
 *   [overrideDisplayName]과 같은 이유로 사용한다.
 * @param overrideSizeBytes [uri] 자체의 메타데이터에서 해석한 값보다 우선할 크기다.
 *   [overrideDisplayName]과 같은 이유로 사용한다.
 * @param materializeInAppStorage 원본 `content://` URI를 계속 참조하지 않고 앱 전용 저장소로 문서를
 *   복사할지 나타낸다. 외부에서 전달된 문서는 true, SAF로 직접 선택한 문서는 false다.
 * @return 가져온 문서의 [DocumentId]다.
 * @throws IllegalStateException 바이트가 필요할 때 [uri]를 읽기용으로 열 수 없으면 발생한다.
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
 * Google Drive에서 다운로드한 소스를 앱 저장소로 복사한다. [importUri]의
 * `materializeInAppStorage` 분기에 대응하는 Drive 처리다. 이 앱으로 가져온 문서는 나중에 다시 열어
 * 읽을 수 있는 안정적인 로컬 파일이 필요하며, 최초 다운로드가 만든 메모리 내 [bytes]는 가져오기
 * 이후까지 유지되지 않는다.
 *
 * @receiver 전체 콘텐츠를 [DocumentImportSource.bytes]에 담고 있는, Drive에서 다운로드한 구체화할
 *   소스다.
 * @param documentFileSource 앱 저장소에 실제 복사를 수행한다.
 * @return 새로 구체화한 [DocumentLocation]을 가리키는 이 소스의 복사본이다.
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
 * 선택한 폴더 트리를 탐색하다 찾은 파일 하나다. 표시 이름을 버리고 [Uri]만 가져오기 전에
 * [resolveSupportedTreeDocumentUris]에서 결과를 결정론적으로 정렬하는 데 필요한 정보만 담는다.
 *
 * @property displayName `DocumentsContract`가 보고한 파일 표시 이름이며 정렬에만 사용한다.
 * @property uri 가져올 문서 트리 [Uri]다.
 */
private data class AndroidTreeDocument(
    val displayName: String,
    val uri: Uri,
)

/**
 * `OpenDocumentTree`로 선택한 폴더를 재귀적으로 탐색하여 트리 어디에 있든 지원하는 모든 문서를
 * 수집한다.
 *
 * @param context 각 디렉터리의 자식 목록을 조회하는 [ContentResolver]에 접근하는 데 사용한다.
 * @param treeUri 폴더 선택기가 권한을 부여한 루트 트리 [Uri]다.
 * @return 트리에서 찾은 지원 문서다. content provider가 자식을 반환하는 순서와 관계없이 폴더
 *   가져오기 결과 순서가 안정적이도록 소문자 표시 이름과 URI 순으로 정렬한다.
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
 * 선택한 폴더 트리를 탐색하다 찾은 파일을 이 앱이 파싱할 수 있는지 판단한다. 보고된 MIME 타입이나
 * 파일 이름 확장자 중 하나를 검사하므로 provider가 잘못된 MIME 타입을 보고하거나 생략해도 가져올
 * 수 있는 파일이 폴더 가져오기에서 숨겨지지 않는다.
 *
 * @param displayName 확장자 기반 감지에 사용할 파일 표시 이름이다.
 * @param mimeType 파일이 보고한 MIME 타입이며 provider가 보고하지 않았으면 null이다.
 * @return 어느 신호든 지원 형식과 일치하면 true다.
 */
private fun isSupportedDocument(displayName: String, mimeType: String?): Boolean {
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
    return mimeType in SupportedDocumentMimeTypes || extension.lowercase() in SupportedDocumentExtensions
}

/**
 * 완료된 [DocumentImportBatchResult]를 모든 다중 파일 Android 가져오기 경로(`openFiles`,
 * `openFolder`, Google Drive 흐름)가 보고하는 [DocumentImporter] 콜백 쌍으로 변환한다. 각 경로에서
 * "성공 항목이 있을 때만 `onImported`를 호출하고, 실패가 있으면 `onError`를 호출한다"는 로직을
 * 반복하지 않게 한다.
 *
 * @param result 보고할 완료된 배치다.
 * @param onImported 성공적으로 가져온 id가 하나 이상일 때만 해당 id와 함께 호출한다.
 * @param onError [DocumentImportBatchResult.toImportErrorMessage]가 결정한 메시지가 null이 아닐 때만
 *   해당 메시지와 함께 호출한다.
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

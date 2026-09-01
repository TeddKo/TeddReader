package com.tedd.teddreader.app.reader.importer

import androidx.compose.runtime.Composable
import com.tedd.teddreader.core.common.model.DocumentId
import kotlinx.coroutines.CancellationException

/**
 * 앱 자체 선택기 밖에서 전달된 문서를 나타낸다. 수신 Android `Intent`(`VIEW`/`SEND`)나 OS 수준의
 * "다음으로 열기"/공유 대상에서 [DocumentImporter.importExternal]이 사용자 선택 문서와 같은 방식으로
 * 해석하고 앱 저장소에 구체화하여 가져오는 데 필요한 정보만 담는다.
 *
 * @property sourceUri 문서를 가리키는 불투명한 플랫폼별 위치다. 예를 들면 문자열로 표현한 Android
 *   `content://` URI이며 비어 있으면 안 된다.
 * @property displayName 소스가 보고한 파일 이름이다. 사용자 표시와 [mimeType]이 없을 때 확장자 기반
 *   형식 감지의 대체값으로 사용한다. 호출자가 해석하지 못했으면 null이다.
 * @property mimeType 소스가 보고한 MIME 타입이다. 호출자가 해석하지 못해 형식 감지가
 *   [displayName]의 확장자를 사용해야 하면 null이다.
 * @property sizeBytes 알려진 경우 문서의 바이트 크기이며 기본값은 `0L`이다. 0 이상이어야 한다.
 * @property grantFlags 원본 인텐트가 전달한 Android URI 권한 부여 플래그다(예:
 *   `FLAG_GRANT_READ_URI_PERMISSION`). Android 임포터가 해당 인텐트의 수명 이후에도 [sourceUri] 읽기
 *   권한을 유지할 수 있도록 전달한다. 이 개념이 없는 플랫폼에서는 항상 `0`이다.
 * @throws IllegalArgumentException [sourceUri]가 비어 있거나 [sizeBytes]가 음수일 때 발생한다.
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
 * 문서를 라이브러리로 가져오는 컴포지션 루트의 단일 진입점이다. 각 플랫폼은 이 인터페이스 뒤에
 * 자체 선택기 UI, 권한 모델, 파일 구체화 전략을 숨긴다. 화면은 이 메서드만 호출하고 플랫폼 파일
 * API에 직접 접근하지 않는다. `rememberDocumentImporter`는 실행 중인 플랫폼에 맞춰 Android
 * SAF/Intent 기반 구현 또는 iOS `UIDocumentPickerViewController` 기반 구현을 제공한다.
 *
 * 모든 진입점은 suspend 값을 반환하지 않고 `onImported`/`onError` 콜백으로 결과를 전달한다. 선택기
 * 열기는 호출보다 오래 지속되는 본질적으로 비동기적인 UI 상호작용이기 때문이다(Android
 * `ActivityResultLauncher` 콜백, iOS delegate 콜백). 동기적으로 반환할 값이 없다.
 */
interface DocumentImporter {
    /**
     * 현재 플랫폼/기기에서 [openGoogleDrive]가 실제로 동작할 수 있는지 나타낸다. 예를 들어 Android
     * 인증 흐름을 호스팅할 activity가 없거나 플랫폼의 [GoogleDrivePickerBridge]가 구성되지 않았으면
     * false다. 호출자는 항상 실패할 Google Drive 진입점을 표시하지 않도록 이 값을 사용한다.
     */
    val supportsGoogleDrivePicker: Boolean

    /**
     * 플랫폼 선택기를 열어 하나 이상의 개별 문서 파일을 선택하고 각각 가져온다.
     *
     * @param onImported 가져오기에 성공한 모든 파일의 [DocumentId]와 함께 한 번 호출한다. 사용자가
     *   아무것도 선택하지 않았거나 모든 선택 항목이 실패하면 호출하지 않는다.
     * @param onError 배치에 실패 항목이 있거나 플랫폼 선택기 자체를 열지 못했을 때 사용자에게 표시할
     *   메시지와 함께 호출한다.
     */
    fun openFiles(
        onImported: (List<DocumentId>) -> Unit,
        onError: (String) -> Unit,
    )

    /**
     * 플랫폼 선택기를 열어 폴더 전체를 선택하고 그 안에서 찾은 지원 문서를 모두 가져온다. 플랫폼이
     * 하위 폴더 탐색을 허용하면 재귀적으로 탐색한다.
     *
     * @param onImported 폴더에서 가져온 모든 문서의 [DocumentId]와 함께 한 번 호출한다. 가져온 문서가
     *   없으면 호출하지 않는다.
     * @param onError 폴더 안의 문서 가져오기가 실패했거나 폴더 선택기 자체를 열지 못했을 때 사용자에게
     *   표시할 메시지와 함께 호출한다.
     */
    fun openFolder(
        onImported: (List<DocumentId>) -> Unit,
        onError: (String) -> Unit,
    )

    /**
     * 필요하면 먼저 인증한 뒤 파일을 선택하는 Google Drive 선택기 흐름을 시작하고 사용자가 고른
     * 항목을 가져온다. 호출자는 무조건 호출하지 말고 [supportsGoogleDrivePicker]에 따라 이 진입점의
     * 표시 여부를 결정해야 한다.
     *
     * @param onImported 가져온 모든 Drive 파일의 [DocumentId]와 함께 한 번 호출한다.
     * @param onError 인증, 다운로드 또는 가져오기가 실패했을 때 사용자에게 표시할 메시지와 함께
     *   호출한다. [supportsGoogleDrivePicker]가 false라서 플랫폼이 요청을 즉시 거부한 경우도 포함한다.
     */
    fun openGoogleDrive(
        onImported: (List<DocumentId>) -> Unit,
        onError: (String) -> Unit,
    )

    /**
     * 사용자가 앱 안에서 선택한 문서가 아니라 OS가 앱에 전달한 [ExternalDocumentImportRequest] 하나를
     * 가져온다.
     *
     * @param request 수신 인텐트나 공유 대상에서 해석한 가져올 문서다.
     * @param onImported 성공 시 가져온 문서의 [DocumentId]와 함께 한 번 호출한다.
     * @param onError 가져오기가 실패했거나 이 플랫폼에 아직 외부 가져오기가 연결되지 않았을 때
     *   사용자에게 표시할 메시지와 함께 호출한다.
     */
    fun importExternal(
        request: ExternalDocumentImportRequest,
        onImported: (DocumentId) -> Unit,
        onError: (String) -> Unit,
    )
}

/**
 * 현재 컴포지션 범위에 맞는 플랫폼 [DocumentImporter] 구현을 해석한다. Android에서는 Google Drive
 * OAuth를 포함한 SAF/Intent 기반 선택기를, iOS에서는 보안 범위 리소스 처리를 포함한
 * `UIDocumentPickerViewController`를 사용한다. `TeddReaderApp`에서 한 번 호출하며, 생성한 임포터는
 * 내비게이션 호스트와 가져오기를 실행하는 화면에 전달한다.
 *
 * @param googleDrivePickerBridge Google Drive 선택기/인증 흐름의 플랫폼 브리지다. 이 빌드에 Drive
 *   가져오기가 구성되지 않았으면 null이며, 플랫폼 구현이
 *   [DocumentImporter.supportsGoogleDrivePicker]를 결정할 수 있도록 전달한다.
 * @return 컴포지션 수명 동안 기억되는 플랫폼 [DocumentImporter]다.
 */
@Composable
internal expect fun rememberDocumentImporter(
    googleDrivePickerBridge: GoogleDrivePickerBridge? = null,
): DocumentImporter

/**
 * 함께 선택한 문서 배치의 가져오기 결과다. 여러 파일 선택이나 선택한 폴더 안의 모든 지원 파일 중
 * 라이브러리에 들어온 항목, 실패한 개수, [toImportErrorMessage]가 보고할 첫 실패 이유를 담는다.
 *
 * @property importedDocumentIds [importDocuments]가 처리한 순서대로 가져오기에 성공한 모든 문서의
 *   [DocumentId]다.
 * @property failedCount 배치에서 가져오기에 실패한 항목 수다.
 * @property firstFailureReason 첫 실패 항목이 제공한 이유다([Throwable.importFailureReason] 참고).
 *   실패가 없으면 null이다. 첫 번째 이유만 보존한다. 실패 개수만으로 충분하지 않은 이유는
 *   [importDocuments]를 참고한다.
 */
internal data class DocumentImportBatchResult(
    val importedDocumentIds: List<DocumentId>,
    val failedCount: Int,
    val firstFailureReason: String? = null,
)

/**
 * 배치의 항목을 한 번에 하나씩 가져오며, 한 항목의 실패가 전체 배치를 중단하지 않도록 다음 항목을
 * 계속 처리한다. 따라서 10개 파일이 있는 폴더에서 읽을 수 없는 파일 하나 때문에 나머지 9개까지
 * 가져오지 못하는 일을 막는다.
 *
 * [importItem]이 던진 [CancellationException]은 실패로 집계하지 않고 항상 다시 던진다. 예를 들어
 * 가져오기 도중 화면을 떠나 바깥 코루틴이 취소되면 여러 실패 중 하나로 흡수되지 않고 즉시 취소된다.
 *
 * 첫 실패 이유만 반환 결과의 `firstFailureReason`에 보존한다. 실패 개수만 보고하면 읽을 수 없는 파일,
 * 잘못된 형식, 빈 파일을 구분할 방법이 없었고 세 경우 모두 개수가 같게 보였으며 별도 로그도 없었다.
 * 따라서 개수만 표시하지 않고 첫 구체적 이유를 노출한다. 같은 배치의 이후 실패 원인은 다를 수
 * 있지만, 첫 이유만으로도 사용자에게 조치 가능한 문제가 발생했음을 알릴 수 있다.
 *
 * @param items 반복 순서대로 가져올 항목이다.
 * @param importItem 항목 하나를 가져와 결과 [DocumentId]를 반환한다. 실패 시 예외를 던질 수 있으며,
 *   전체 배치를 취소하는 [CancellationException]도 포함한다.
 * @return 성공 항목, 실패 개수, 첫 실패 이유를 요약한 [DocumentImportBatchResult]다.
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
 * 이 throwable이 나타내는 가져오기 실패를 사람이 읽을 수 있게 설명하는 최선의 이유다. 비어 있지
 * 않은 자체 메시지를 우선 사용하고, 없으면 예외의 단순 클래스 이름을 사용하며, 익명 또는 로컬 예외
 * 타입처럼 클래스 이름도 없으면 고정된 "unknown error" 문자열을 사용한다.
 *
 * @receiver 문서 하나를 가져오다가 잡은 throwable이다.
 * @return 사용자에게 표시하거나 [DocumentImportBatchResult.firstFailureReason]에 포함해도 안전한 비어
 *   있지 않은 문자열이다.
 */
internal fun Throwable.importFailureReason(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "unknown error"

/**
 * 완료된 가져오기 배치에 표시할 사용자 메시지가 있는지 결정한다.
 *
 * @receiver 요약할 배치 결과다.
 * @return 빈 폴더나 사용 가능한 파일을 하나도 반환하지 않은 선택기처럼 선택 항목도 실패도 없으면
 *   `"No supported documents found."`를 반환한다. 모든 항목을 성공적으로 가져왔으면 오류 메시지가
 *   필요 없으므로 null을 반환한다. 그 외에는 실패 개수를 반환하고, 알려진
 *   [DocumentImportBatchResult.firstFailureReason]이 있으면 덧붙여 실패한 파일 수뿐 아니라 실제 원인을
 *   설명한다.
 */
internal fun DocumentImportBatchResult.toImportErrorMessage(): String? = when {
    importedDocumentIds.isEmpty() && failedCount == 0 -> "No supported documents found."
    failedCount == 0 -> null
    firstFailureReason == null -> "$failedCount documents failed to import."
    else -> "$failedCount documents failed to import. $firstFailureReason"
}

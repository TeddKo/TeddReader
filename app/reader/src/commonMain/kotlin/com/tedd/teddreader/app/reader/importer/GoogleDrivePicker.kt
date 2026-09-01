package com.tedd.teddreader.app.reader.importer

import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.SupportedDocumentExtensions
import com.tedd.teddreader.core.common.model.SupportedDocumentMimeTypes
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 완료된 Google Drive 선택기/인증 흐름이 반환하는 플랫폼별 결과다. 다운로드에 사용할 OAuth 토큰과
 * 사용자가 선택한 Drive 파일의 id를 담는다. Android의 Identity `AuthorizationClient` 흐름과 iOS의
 * [GoogleDrivePickerBridge]는 각각 플랫폼별 다운로드 단계가 시작되기 전에 이 결과를 만든다. 이후
 * 다운로드 단계가 각 파일의 메타데이터와 바이트를 가져와
 * [GoogleDriveFileMetadata]/`DocumentImportSource`로 변환한다.
 *
 * @property accessToken [fileIds]의 메타데이터와 콘텐츠를 가져오는 Google Drive REST 호출에 붙일
 *   bearer 토큰이다. 비어 있으면 안 된다.
 * @property fileIds 사용자가 선택한 Drive 파일 id다. 비어 있지 않아야 하며 각 항목도 비어 있으면 안
 *   된다.
 * @throws IllegalArgumentException [accessToken]이 비었거나 [fileIds]가 비었거나 [fileIds] 안에 빈
 *   항목이 있을 때 발생한다.
 */
public class GoogleDrivePickerResult(
    val accessToken: String,
    val fileIds: List<String>,
) {
    init {
        require(accessToken.isNotBlank()) { "accessToken must not be blank." }
        require(fileIds.isNotEmpty()) { "fileIds must not be empty." }
        require(fileIds.all(String::isNotBlank)) { "fileIds must not contain blank values." }
    }
}

/**
 * 플랫폼이 실제로 사용하는 네이티브 Google Drive 선택기 UI와 OAuth 흐름을 공유 리더 코드에
 * 제공하는 브리지다. 공유 코드는 플랫폼 SDK 대신 이 작은 인터페이스에만 의존한다. iOS는 보통
 * 네이티브 Google Sign-In UI 기반 구현을 이 모듈 밖에서 제공한다. Android 선택기는 이 인터페이스
 * 대신 `DocumentImporter.android.kt` 안에서 Identity `AuthorizationClient`로 직접 구성한다. 각
 * 플랫폼에서 Drive 가져오기 가능 여부를 결정하는 방식은
 * [com.tedd.teddreader.app.reader.importer.DocumentImporter.supportsGoogleDrivePicker]를 참고한다.
 */
public interface GoogleDrivePickerBridge {
    /**
     * 이 브리지가 실제 선택기를 여는 데 필요한 client id와 네이티브 SDK 설정을 모두 갖췄는지
     * 나타낸다. false면 [open]이 즉시 실패하므로 호출자는 Drive 진입점을 호출하지 말고 완전히 숨겨야
     * 한다.
     */
    val isConfigured: Boolean

    /**
     * 네이티브 Google Drive 선택기/인증 UI를 연다.
     *
     * @param onPicked 사용자가 선택을 완료하면 결과 액세스 토큰과 선택한 파일 id를 전달하여 호출한다.
     * @param onCancelled 사용자가 아무것도 선택하지 않고 흐름을 닫으면 호출한다.
     * @param onError 흐름을 시작하지 못했거나 실패했을 때 사용자에게 표시할 메시지와 함께 호출한다.
     */
    fun open(
        onPicked: (GoogleDrivePickerResult) -> Unit,
        onCancelled: () -> Unit,
        onError: (String) -> Unit,
    )
}

/**
 * 파일을 가져올 수 있는지 결정하고 이를 나타내는
 * [com.tedd.teddreader.core.domain.repository.DocumentImportSource]를 구성하는 데 임포터가 필요한 Google
 * Drive 파일 메타데이터의 일부다. Drive REST API의 `files.get` JSON 응답을
 * [parseDriveFileMetadata]가 파싱하여 만든다.
 *
 * @property id 메타데이터 및 다운로드 URL을 구성하는 데 사용하는 Drive 파일 id다.
 * @property name 파일 표시 이름이다. 가져온 문서의 표시 이름과 [isImportSupported]의 확장자 기반 형식
 *   감지에 모두 사용한다.
 * @property mimeType Drive가 보고한 파일의 MIME 타입이다. Drive가 보고하지 않았으면 null이며 이때
 *   [isImportSupported]는 [name]의 확장자를 사용한다.
 * @property sizeBytes Drive가 보고한 파일의 바이트 크기다. Drive가 필드를 생략했으면 `0L`이다.
 * @property canDownload 로그인한 계정의 Drive 권한으로 이 파일의 콘텐츠를 실제로 다운로드할 수
 *   있는지 나타낸다. false면 형식과 관계없이 다운로드 단계가 실패하므로 [isImportSupported]는 해당
 *   파일을 가져올 수 있다고 판단하지 않는다.
 */
internal data class GoogleDriveFileMetadata(
    val id: String,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val canDownload: Boolean,
)

/**
 * Android `AuthorizationResult`의 선택기 부가 정보가 전달하는 쉼표로 연결된 Drive 파일 id 목록을
 * 분리하고 중복을 제거한다. id가 처음 나타난 순서를 유지하며 빈 항목은 버린다.
 *
 * @param rawValue 인증 결과에서 읽은 원본 쉼표 구분 id 문자열이다.
 * @return 원래 순서를 유지한 중복 없는 비어 있지 않은 파일 id다.
 */
internal fun parsePickedFileIds(rawValue: String): List<String> {
    val seen = linkedSetOf<String>()
    rawValue.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .forEach(seen::add)
    return seen.toList()
}

/**
 * 각 플랫폼의 메타데이터 요청이
 * `fields=id,name,mimeType,size,capabilities(canDownload)`로 받은 Drive REST API `files.get` JSON
 * 응답을 [GoogleDriveFileMetadata]로 파싱한다.
 *
 * @param json 원본 JSON 응답 본문이다.
 * @return 파싱한 메타데이터다.
 * @throws IllegalStateException 응답에 필수 `id` 또는 `name` 필드가 없을 때 발생한다.
 */
internal fun parseDriveFileMetadata(json: String): GoogleDriveFileMetadata {
    val root = Json.parseToJsonElement(json).jsonObject
    val capabilities = root["capabilities"]?.jsonObject
    return GoogleDriveFileMetadata(
        id = root.requiredString("id"),
        name = root.requiredString("name"),
        mimeType = root["mimeType"]?.jsonPrimitive?.contentOrNull,
        sizeBytes = root["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
        canDownload = capabilities?.get("canDownload")?.jsonPrimitive?.booleanOrNull == true,
    )
}

/**
 * 이 Drive 파일을 실제로 가져올 수 있는지 판단한다. 계정이 파일을 다운로드할 수 있어야 하며,
 * 보고된 MIME 타입이나 파일 이름 확장자 중 하나를 TeddReader가 파싱할 수 있어야 한다.
 *
 * @receiver 검사할 파일 메타데이터다.
 * @return [GoogleDriveFileMetadata.canDownload]가 true이고 파일의 MIME 타입이나 확장자가 지원되면
 *   true다.
 */
internal fun GoogleDriveFileMetadata.isImportSupported(): Boolean {
    if (!canDownload) return false
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return mimeType in SupportedDocumentMimeTypes || extension in SupportedDocumentExtensions
}

/**
 * 이 Drive 파일의 메타데이터와 이미 다운로드한 콘텐츠를 공유
 * [com.tedd.teddreader.core.domain.repository.DocumentRepository] 가져오기 경로가 요구하는
 * [DocumentImportSource]로 감싼다. 실제 Drive 파일에는 플랫폼 파일 시스템 URI가 없으므로 합성
 * `gdrive://` 위치를 사용한다.
 *
 * @receiver 감쌀 파일 메타데이터다.
 * @param bytes 다운로드한 파일의 전체 콘텐츠다.
 * @return `DocumentRepository.importDocument`에 전달할 준비가 된 [DocumentImportSource]다.
 */
internal fun GoogleDriveFileMetadata.toDocumentImportSource(bytes: ByteArray): DocumentImportSource =
    DocumentImportSource(
        location = DocumentLocation(
            sourceUri = "gdrive://$id",
            displayName = name,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
        ),
        bytes = bytes,
    )

/**
 * 파싱한 Drive 메타데이터 JSON 객체에서 비어 있지 않은 필수 문자열 필드를 읽는다.
 * [parseDriveFileMetadata]가 빈 id나 이름을 가진 [GoogleDriveFileMetadata]를 조용히 만들지 않고 즉시
 * 실패하게 한다.
 *
 * @receiver 값을 읽을 파싱된 JSON 객체다.
 * @param key 비어 있지 않은 문자열 값을 담아야 하는 필드 이름이다.
 * @return 필드의 문자열 값이다.
 * @throws IllegalStateException [key]가 없거나 문자열이 아니거나 비어 있을 때 발생한다.
 */
private fun kotlinx.serialization.json.JsonObject.requiredString(key: String): String =
    get(key)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: error("Google Drive metadata missing $key.")

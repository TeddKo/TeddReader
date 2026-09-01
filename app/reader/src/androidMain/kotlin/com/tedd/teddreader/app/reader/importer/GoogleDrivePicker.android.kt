package com.tedd.teddreader.app.reader.importer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google Drive 인증에 요청하는 OAuth 범위 `drive.file`이다. 사용자의 Drive 전체를 노출하는 더 넓은
 * `drive`/`drive.readonly` 범위와 달리, 사용자가 이 앱을 통해 명시적으로 선택하거나 만든 파일에만
 * 접근 권한을 부여한다.
 */
private const val GoogleDriveScope = "https://www.googleapis.com/auth/drive.file"

/**
 * Drive 선택기가 고른 파일 id가 [AuthorizationResult]의 `tokenResponseParams`에서 반환되는 키다.
 * [buildGoogleDriveAuthorizationRequest]에 설정한 `PICKER_OAUTH_TRIGGER`/선택기 리소스 매개변수와
 * 일치한다.
 */
private const val PickedFileIdsKey = "picked_file_ids"

/**
 * Android의 통합 Google Drive 인증 및 선택기 흐름을 시작하는 요청을 구성한다.
 * [GoogleDriveScope] 범위를 요청하고, 이전 권한을 조용히 재사용하지 않고 항상 동의와 계정 선택을
 * 다시 요청하며, 선택기 리소스 매개변수를 켠다. 따라서 한 번의 `authorize()` 호출로 인증과 파일
 * 선택을 모두 수행하고 [AndroidGoogleDriveMimeTypes]로 필터링한다.
 *
 * @return 구성한 [AuthorizationRequest]다.
 */
internal fun buildGoogleDriveAuthorizationRequest(): AuthorizationRequest =
    AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(GoogleDriveScope)))
        .setOptOutIncludingGrantedScopes(true)
        .setPrompt(AuthorizationRequest.Prompt.CONSENT or AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
        .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_MULTIPLE, "true")
        .addResourceParameter(
            AuthorizationRequest.ResourceParameter.PICKER_MIMETYPES,
            AndroidGoogleDriveMimeTypes.joinToString(separator = ","),
        )
        .build()

/**
 * Play Services Identity 자체의 결과 타입을 임포터의 나머지 부분이 사용하는 공유 플랫폼 독립
 * [GoogleDrivePickerResult]로 변환한다.
 *
 * @receiver `authorize()`가 직접 반환했거나 해석 activity의 결과 인텐트에서 디코딩한 원본 인증
 *   결과다.
 * @return 동등한 [GoogleDrivePickerResult]다.
 * @throws IllegalStateException 결과에 액세스 토큰이 없을 때 발생한다.
 */
internal fun AuthorizationResult.toGoogleDrivePickerResult(): GoogleDrivePickerResult {
    val accessToken = accessToken?.takeIf(String::isNotBlank)
        ?: error("Google Drive authorization did not return an access token.")
    val fileIds = parsePickedFileIds(tokenResponseParams?.getString(PickedFileIdsKey).orEmpty())
    return GoogleDrivePickerResult(accessToken = accessToken, fileIds = fileIds)
}

/**
 * Play Services Identity의 비동기 `authorize()` [com.google.android.gms.tasks.Task]가 끝날 때까지
 * 중단하며 콜백 기반 API를 코루틴으로 변환한다.
 *
 * @receiver 인증을 수행할 client다.
 * @param request 전송할 인증 요청이다.
 * @return 결과 [AuthorizationResult]다.
 */
internal suspend fun AuthorizationClient.awaitAuthorize(
    request: AuthorizationRequest,
): AuthorizationResult = authorize(request).await()

/**
 * 이전에 발급된 Google Drive 액세스 토큰을 취소한다. 해당 토큰으로 인증한 요청이 `HTTP 401`을
 * 반환한 뒤 오래되어 만료된 토큰이 재시도에서도 같은 방식으로 계속 실패하지 않게 한다.
 *
 * @receiver 토큰을 발급한 client다.
 * @param token 취소할 액세스 토큰이다.
 */
internal suspend fun AuthorizationClient.clearAccessToken(token: String) {
    clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
}

/**
 * 완료된 Google Drive 선택에서 고른 모든 파일을 다운로드한다. Drive 토큰은 수명이 짧아 배치
 * 가져오기보다 먼저 만료될 수 있으므로 다운로드가 인증 실패를 반환하면 액세스 토큰을 지우고 명확한
 * 메시지를 발생시킨다.
 *
 * @param authorizationClient 토큰을 발급한 client이며 401 응답에서 토큰을 지우는 데 사용한다.
 * @param pickerResult 액세스 토큰과 다운로드할 id를 담은 완료된 선택 결과다.
 * @return [GoogleDrivePickerResult.fileIds]에 나열된 순서대로 선택한 파일마다 하나의
 *   [com.tedd.teddreader.core.domain.repository.DocumentImportSource]를 반환한다.
 * @throws java.io.IOException 다운로드가 실패하면 발생한다. 토큰이 만료된 `HTTP 401`에는 설명
 *   메시지를 포함한다.
 */
internal suspend fun fetchGoogleDriveImportSources(
    authorizationClient: AuthorizationClient,
    pickerResult: GoogleDrivePickerResult,
): List<com.tedd.teddreader.core.domain.repository.DocumentImportSource> = withContext(Dispatchers.IO) {
    pickerResult.fileIds.map { fileId ->
        try {
            fetchGoogleDriveImportSource(fileId = fileId, accessToken = pickerResult.accessToken)
        } catch (exception: GoogleDriveHttpException) {
            if (exception.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                authorizationClient.clearAccessToken(pickerResult.accessToken)
                throw IOException("Google Drive session expired (HTTP 401). Please try again.", exception)
            }
            throw exception
        }
    }
}

/**
 * Drive 파일 하나의 메타데이터와 콘텐츠를 가져오고 다운로드를 확정하기 전에 둘 다 검증한다. 계정이
 * 다운로드할 수 없는 파일이나 앱이 파싱하지 않는 형식을 일찍 거부하여 전체를 다운로드한 뒤 버리는
 * 비용을 피한다. 빈 다운로드도 거부하여 본문 없는 Drive 응답을 0바이트 문서로 조용히 가져오지
 * 않는다.
 *
 * @param fileId 가져올 Drive 파일 id다.
 * @param accessToken 요청을 인증하는 bearer 토큰이다.
 * @return 가져올 준비가 된 [com.tedd.teddreader.core.domain.repository.DocumentImportSource]로 감싼
 *   파일이다.
 * @throws IllegalStateException 파일을 다운로드할 수 없거나 가져올 수 없는 형식이거나 다운로드
 *   결과가 비어 있으면 발생한다.
 */
private fun fetchGoogleDriveImportSource(
    fileId: String,
    accessToken: String,
): com.tedd.teddreader.core.domain.repository.DocumentImportSource {
    val metadata = fetchGoogleDriveMetadata(fileId = fileId, accessToken = accessToken)
    check(metadata.canDownload) { "Google Drive file cannot be downloaded: ${metadata.name}" }
    check(metadata.isImportSupported()) { "Unsupported Google Drive document: ${metadata.name}" }
    val bytes = downloadGoogleDriveFile(fileId = fileId, accessToken = accessToken)
    check(bytes.isNotEmpty()) { "Google Drive file is empty: ${metadata.name}" }
    return metadata.toDocumentImportSource(bytes)
}

/**
 * `files.get` REST 엔드포인트로 Drive 파일 하나의 메타데이터를 요청하고 파싱한다.
 *
 * @param fileId 설명할 Drive 파일 id다.
 * @param accessToken 요청을 인증하는 bearer 토큰이다.
 * @return 파싱한 [GoogleDriveFileMetadata]다.
 */
private fun fetchGoogleDriveMetadata(
    fileId: String,
    accessToken: String,
): GoogleDriveFileMetadata =
    parseDriveFileMetadata(
        executeGoogleDriveRequest(
            url = googleDriveMetadataUrl(fileId),
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
private fun downloadGoogleDriveFile(
    fileId: String,
    accessToken: String,
): ByteArray = executeGoogleDriveRequest(
    url = googleDriveDownloadUrl(fileId),
    accessToken = accessToken,
)

/**
 * Google Drive REST API에 인증된 `GET` 요청 하나를 실행한다. 메타데이터와 다운로드 엔드포인트는
 * URL만 다르고 둘 다 단순한 bearer 인증 `GET`이므로 함께 사용한다.
 *
 * @param url [googleDriveMetadataUrl] 또는 [googleDriveDownloadUrl]이 구성한 전체 요청 URL이다.
 * @param accessToken `Authorization` 헤더에 보낼 bearer 토큰이다.
 * @return 응답 본문의 원본 바이트다.
 * @throws GoogleDriveHttpException 응답 상태가 200-299 범위 밖이면 발생한다.
 */
private fun executeGoogleDriveRequest(
    url: String,
    accessToken: String,
): ByteArray {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Authorization", "Bearer $accessToken")
        setRequestProperty("Accept", "application/json, application/octet-stream")
        instanceFollowRedirects = true
    }

    return connection.useAndDisconnect { httpConnection ->
        val statusCode = httpConnection.responseCode
        if (statusCode !in 200..299) {
            throw GoogleDriveHttpException(statusCode = statusCode)
        }
        httpConnection.inputStream.use { inputStream -> inputStream.readBytes() }
    }
}

/**
 * Drive 파일 하나의 `files.get` 메타데이터 URL을 구성한다. [GoogleDriveFileMetadata]에 필요한
 * 필드만 요청하고 `supportsAllDrives=true`를 사용하여 공유 drive의 파일도 사용자 자신의 Drive
 * 파일과 같은 방식으로 해석한다.
 *
 * @param fileId [encodeGoogleDriveFileId]로 URL 인코딩할 Drive 파일 id다.
 * @return 전체 메타데이터 요청 URL이다.
 */
private fun googleDriveMetadataUrl(fileId: String): String =
    "https://www.googleapis.com/drive/v3/files/${encodeGoogleDriveFileId(fileId)}" +
        "?fields=id,name,mimeType,size,capabilities(canDownload)&supportsAllDrives=true"

/**
 * Drive 파일 하나의 콘텐츠를 위한 `files.get?alt=media` 다운로드 URL을 구성한다.
 * [googleDriveMetadataUrl]과 동일하게 공유 drive를 지원한다.
 *
 * @param fileId [encodeGoogleDriveFileId]로 URL 인코딩할 Drive 파일 id다.
 * @return 전체 다운로드 요청 URL이다.
 */
private fun googleDriveDownloadUrl(fileId: String): String =
    "https://www.googleapis.com/drive/v3/files/${encodeGoogleDriveFileId(fileId)}" +
        "?alt=media&supportsAllDrives=true"

/**
 * Drive 파일 id를 URL 경로 세그먼트에 안전하게 사용하도록 URL 인코딩한다.
 *
 * @param fileId 원본 Drive 파일 id다.
 * @return 인코딩한 id다.
 */
private fun encodeGoogleDriveFileId(fileId: String): String =
    URLEncoder.encode(fileId, Charsets.UTF_8.name())

/**
 * Play Services [Task]의 콜백 기반 완료를 suspend 호출로 변환한다. 이 파일의 나머지 부분에서 다른
 * suspend 함수와 같은 방식으로 Task에 `await()`를 사용할 수 있게 한다.
 *
 * @receiver 기다릴 task다.
 * @return task의 성공 결과다.
 * @throws Exception task가 실패하며 전달한 예외 또는 task가 취소되었을 때의
 *   [java.util.concurrent.CancellationException]이다.
 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { exception -> continuation.resumeWithException(exception) }
    addOnCanceledListener { continuation.resumeWithException(java.util.concurrent.CancellationException()) }
}

/**
 * 이 연결에서 블록을 실행하고 결과와 관계없이 이후 연결을 끊는다. [HttpURLConnection] 자체에는
 * `Closeable`/`use` 지원이 없다.
 *
 * @receiver 블록을 실행한 뒤 연결을 끊을 connection이다.
 * @param block 연결이 열린 동안 수행할 작업이다.
 * @return [block]이 반환한 값이다.
 */
private inline fun <T> HttpURLConnection.useAndDisconnect(block: (HttpURLConnection) -> T): T =
    try {
        block(this)
    } finally {
        disconnect()
    }

/**
 * Google Drive REST 호출이 2xx가 아닌 상태를 반환했음을 나타낸다. 상태 코드를 담아
 * [fetchGoogleDriveImportSources]가 `HTTP 401`을 별도 처리하여 실패를 보고하기 전에 오래된 액세스
 * 토큰을 지울 수 있게 한다.
 *
 * @property statusCode 요청이 실패한 HTTP 상태 코드다.
 */
private class GoogleDriveHttpException(
    val statusCode: Int,
) : IOException("Google Drive request failed with HTTP $statusCode.")

/**
 * [Context]의 `ContextWrapper` 체인을 따라 호스팅 [Activity]를 찾는다. Compose 계층의
 * `LocalContext.current`는 대개 [Activity] 자체가 아니라 activity를 감싼 컨텍스트(예: 테마를
 * 재정의하거나 view가 감싼 컨텍스트)이며, Google Drive 인증 흐름을 실행하려면 실제 [Activity]가
 * 필요하다.
 *
 * @receiver 검색을 시작할 context다.
 * @return 호스팅 [Activity]다. 감싼 컨텍스트 중 activity가 없으면 null이며, 예를 들면 뒤에
 *   activity가 없는 애플리케이션 [Context]가 해당한다.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

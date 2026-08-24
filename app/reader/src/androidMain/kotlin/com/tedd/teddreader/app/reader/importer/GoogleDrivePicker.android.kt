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
 * The OAuth scope requested for Google Drive authorization: `drive.file`, which only grants access
 * to files the user explicitly picks or creates through this app, rather than the broader
 * `drive`/`drive.readonly` scopes that would expose the user's entire Drive.
 */
private const val GoogleDriveScope = "https://www.googleapis.com/auth/drive.file"

/**
 * The key under which the Drive picker's chosen file ids come back in an
 * [AuthorizationResult]'s `tokenResponseParams`, matching the `PICKER_OAUTH_TRIGGER`/picker
 * resource parameters set in [buildGoogleDriveAuthorizationRequest].
 */
private const val PickedFileIdsKey = "picked_file_ids"

/**
 * Builds the request that starts Android's combined Google Drive authorization-and-picker flow:
 * asks for the [GoogleDriveScope] scope, always re-prompts for consent and account selection
 * rather than silently reusing a prior grant, and turns on the picker resource parameters so the
 * same `authorize()` call both authorizes and lets the user pick files, filtered to
 * [AndroidGoogleDriveMimeTypes], in one step.
 *
 * @return the configured [AuthorizationRequest].
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
 * Converts Play Services Identity's own result type into the shared, platform-independent
 * [GoogleDrivePickerResult] the rest of the importer works with.
 *
 * @receiver the raw authorization result, whether returned directly from `authorize()` or decoded
 *   from the resolution activity's result intent.
 * @return the equivalent [GoogleDrivePickerResult].
 * @throws IllegalStateException if the result carries no access token.
 */
internal fun AuthorizationResult.toGoogleDrivePickerResult(): GoogleDrivePickerResult {
    val accessToken = accessToken?.takeIf(String::isNotBlank)
        ?: error("Google Drive authorization did not return an access token.")
    val fileIds = parsePickedFileIds(tokenResponseParams?.getString(PickedFileIdsKey).orEmpty())
    return GoogleDrivePickerResult(accessToken = accessToken, fileIds = fileIds)
}

/**
 * Suspends until Play Services Identity's asynchronous `authorize()` [com.google.android.gms.tasks.Task]
 * completes, adapting its callback-based API to a coroutine.
 *
 * @receiver the client to authorize through.
 * @param request the authorization request to send.
 * @return the resulting [AuthorizationResult].
 */
internal suspend fun AuthorizationClient.awaitAuthorize(
    request: AuthorizationRequest,
): AuthorizationResult = authorize(request).await()

/**
 * Revokes a previously issued Google Drive access token, used after a request authenticated with
 * it comes back `HTTP 401` so a stale, expired token is not kept around to fail the same way
 * again on a retry.
 *
 * @receiver the client the token was issued through.
 * @param token the access token to revoke.
 */
internal suspend fun AuthorizationClient.clearAccessToken(token: String) {
    clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
}

/**
 * Downloads every file a completed Google Drive pick selected, clearing the access token and
 * raising a clear message if any download comes back unauthorized, since Drive tokens are
 * short-lived and a batch import can easily outlast one.
 *
 * @param authorizationClient the client the token was issued through, used to clear it on a
 *   401 response.
 * @param pickerResult the completed pick, carrying the access token and the ids to download.
 * @return one [com.tedd.teddreader.core.domain.repository.DocumentImportSource] per picked file,
 *   in the order [GoogleDrivePickerResult.fileIds] listed them.
 * @throws java.io.IOException if any download fails, including with a clarifying message when the
 *   token had expired (`HTTP 401`).
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
 * Fetches one Drive file's metadata and content and validates both before committing to a
 * download: refusing early on a file the account cannot download or a format this app does not
 * parse avoids paying for a full download only to discard it, and refusing an empty download
 * catches a Drive response that came back with no body without silently importing a zero-byte
 * document.
 *
 * @param fileId the Drive file id to fetch.
 * @param accessToken the bearer token authorizing the request.
 * @return the file wrapped as a [com.tedd.teddreader.core.domain.repository.DocumentImportSource]
 *   ready to import.
 * @throws IllegalStateException if the file cannot be downloaded, is not an importable format, or
 *   downloads empty.
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
 * Requests and parses one Drive file's metadata via the `files.get` REST endpoint.
 *
 * @param fileId the Drive file id to describe.
 * @param accessToken the bearer token authorizing the request.
 * @return the parsed [GoogleDriveFileMetadata].
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
 * Downloads one Drive file's full content via the `files.get?alt=media` REST endpoint.
 *
 * @param fileId the Drive file id to download.
 * @param accessToken the bearer token authorizing the request.
 * @return the file's raw bytes.
 */
private fun downloadGoogleDriveFile(
    fileId: String,
    accessToken: String,
): ByteArray = executeGoogleDriveRequest(
    url = googleDriveDownloadUrl(fileId),
    accessToken = accessToken,
)

/**
 * Runs one authenticated `GET` request against the Google Drive REST API — used for both the
 * metadata and download endpoints, since both are simple bearer-authenticated `GET`s that only
 * differ by URL.
 *
 * @param url the full request URL, built by [googleDriveMetadataUrl] or [googleDriveDownloadUrl].
 * @param accessToken the bearer token to send in the `Authorization` header.
 * @return the response body's raw bytes.
 * @throws GoogleDriveHttpException if the response status is outside the 200-299 range.
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
 * Builds the `files.get` metadata URL for one Drive file, requesting only the fields
 * [GoogleDriveFileMetadata] needs and `supportsAllDrives=true` so a file living in a shared drive
 * resolves the same as one in the user's own Drive.
 *
 * @param fileId the Drive file id, URL-encoded via [encodeGoogleDriveFileId].
 * @return the full metadata request URL.
 */
private fun googleDriveMetadataUrl(fileId: String): String =
    "https://www.googleapis.com/drive/v3/files/${encodeGoogleDriveFileId(fileId)}" +
        "?fields=id,name,mimeType,size,capabilities(canDownload)&supportsAllDrives=true"

/**
 * Builds the `files.get?alt=media` download URL for one Drive file's content, with the same
 * shared-drive support as [googleDriveMetadataUrl].
 *
 * @param fileId the Drive file id, URL-encoded via [encodeGoogleDriveFileId].
 * @return the full download request URL.
 */
private fun googleDriveDownloadUrl(fileId: String): String =
    "https://www.googleapis.com/drive/v3/files/${encodeGoogleDriveFileId(fileId)}" +
        "?alt=media&supportsAllDrives=true"

/**
 * URL-encodes a Drive file id for safe use as a URL path segment.
 *
 * @param fileId the raw Drive file id.
 * @return the encoded id.
 */
private fun encodeGoogleDriveFileId(fileId: String): String =
    URLEncoder.encode(fileId, Charsets.UTF_8.name())

/**
 * Adapts a Play Services [Task]'s callback-based completion into a suspend call, so the rest of
 * this file can `await()` a Task the same way it awaits any other suspend function.
 *
 * @receiver the task to await.
 * @return the task's successful result.
 * @throws Exception whatever exception the task failed with, or a
 *   [java.util.concurrent.CancellationException] if the task was cancelled.
 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { exception -> continuation.resumeWithException(exception) }
    addOnCanceledListener { continuation.resumeWithException(java.util.concurrent.CancellationException()) }
}

/**
 * Runs a block against this connection and disconnects it afterward regardless of outcome, since
 * [HttpURLConnection] has no `Closeable`/`use` support of its own.
 *
 * @receiver the connection to run the block against and then disconnect.
 * @param block the work to perform while the connection is open.
 * @return whatever [block] returns.
 */
private inline fun <T> HttpURLConnection.useAndDisconnect(block: (HttpURLConnection) -> T): T =
    try {
        block(this)
    } finally {
        disconnect()
    }

/**
 * Signals that a Google Drive REST call returned a non-2xx status, carrying the status code so
 * [fetchGoogleDriveImportSources] can special-case `HTTP 401` to clear the stale access token
 * before reporting the failure.
 *
 * @property statusCode the HTTP status code the request failed with.
 */
private class GoogleDriveHttpException(
    val statusCode: Int,
) : IOException("Google Drive request failed with HTTP $statusCode.")

/**
 * Walks a [Context]'s `ContextWrapper` chain to find the hosting [Activity], since
 * `LocalContext.current` in a Compose hierarchy is often an activity-wrapping context (e.g. a
 * theme-overridden or view-wrapped context) rather than the [Activity] itself, and the Google
 * Drive authorization flow needs a real [Activity] to launch through.
 *
 * @receiver the context to search from.
 * @return the hosting [Activity], or null if none of the wrapped contexts is one — for example, an
 *   application [Context] with no activity behind it.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

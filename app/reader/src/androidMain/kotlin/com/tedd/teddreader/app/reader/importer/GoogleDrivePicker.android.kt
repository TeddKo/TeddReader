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

private const val GoogleDriveScope = "https://www.googleapis.com/auth/drive.file"
private const val PickedFileIdsKey = "picked_file_ids"

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

internal fun AuthorizationResult.toGoogleDrivePickerResult(): GoogleDrivePickerResult {
    val accessToken = accessToken?.takeIf(String::isNotBlank)
        ?: error("Google Drive authorization did not return an access token.")
    val fileIds = parsePickedFileIds(tokenResponseParams?.getString(PickedFileIdsKey).orEmpty())
    return GoogleDrivePickerResult(accessToken = accessToken, fileIds = fileIds)
}

internal suspend fun AuthorizationClient.awaitAuthorize(
    request: AuthorizationRequest,
): AuthorizationResult = authorize(request).await()

internal suspend fun AuthorizationClient.clearAccessToken(token: String) {
    clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
}

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

private fun downloadGoogleDriveFile(
    fileId: String,
    accessToken: String,
): ByteArray = executeGoogleDriveRequest(
    url = googleDriveDownloadUrl(fileId),
    accessToken = accessToken,
)

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

private fun googleDriveMetadataUrl(fileId: String): String =
    "https://www.googleapis.com/drive/v3/files/${encodeGoogleDriveFileId(fileId)}" +
        "?fields=id,name,mimeType,size,capabilities(canDownload)&supportsAllDrives=true"

private fun googleDriveDownloadUrl(fileId: String): String =
    "https://www.googleapis.com/drive/v3/files/${encodeGoogleDriveFileId(fileId)}" +
        "?alt=media&supportsAllDrives=true"

private fun encodeGoogleDriveFileId(fileId: String): String =
    URLEncoder.encode(fileId, Charsets.UTF_8.name())

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { exception -> continuation.resumeWithException(exception) }
    addOnCanceledListener { continuation.resumeWithException(java.util.concurrent.CancellationException()) }
}

private inline fun <T> HttpURLConnection.useAndDisconnect(block: (HttpURLConnection) -> T): T =
    try {
        block(this)
    } finally {
        disconnect()
    }

private class GoogleDriveHttpException(
    val statusCode: Int,
) : IOException("Google Drive request failed with HTTP $statusCode.")

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

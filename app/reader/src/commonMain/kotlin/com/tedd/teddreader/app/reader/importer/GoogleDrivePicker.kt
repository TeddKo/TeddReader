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
 * What a completed Google Drive picker/authorization flow hands back, still platform-specific:
 * the OAuth token good for downloading, and the ids of whichever Drive files the user picked. Both
 * Android's Identity `AuthorizationClient` flow and iOS's [GoogleDrivePickerBridge] produce one of
 * these before their own platform-specific download step, which fetches each file's metadata and
 * bytes and turns them into a [GoogleDriveFileMetadata]/`DocumentImportSource`, takes over.
 *
 * @property accessToken the bearer token to attach to the Google Drive REST calls that fetch
 *   metadata and content for [fileIds]. Must not be blank.
 * @property fileIds the Drive file ids the user selected. Must be non-empty, and no entry may be
 *   blank.
 * @throws IllegalArgumentException if [accessToken] is blank, [fileIds] is empty, or any entry in
 *   [fileIds] is blank.
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
 * A platform-supplied bridge to whatever native Google Drive picker UI and OAuth flow that
 * platform actually uses, so the shared reader code depends only on this small surface instead of
 * a platform SDK. iOS supplies its implementation (typically backed by native Google Sign-In UI)
 * from outside this module; on Android the picker itself is built directly on the Identity
 * `AuthorizationClient` inside `DocumentImporter.android.kt` rather than through this interface —
 * see [com.tedd.teddreader.app.reader.importer.DocumentImporter.supportsGoogleDrivePicker] for how
 * each platform decides whether Drive import is available at all.
 */
public interface GoogleDrivePickerBridge {
    /**
     * Whether this bridge has everything it needs — client id, native SDK setup — to actually open
     * a picker. False means [open] would fail immediately, so callers should hide the Drive entry
     * point entirely rather than invoke it.
     */
    val isConfigured: Boolean

    /**
     * Opens the native Google Drive picker/authorization UI.
     *
     * @param onPicked called with the resulting access token and selected file ids once the user
     *   completes picking.
     * @param onCancelled called if the user dismisses the flow without picking anything.
     * @param onError called with a user-facing message if the flow could not start or failed.
     */
    fun open(
        onPicked: (GoogleDrivePickerResult) -> Unit,
        onCancelled: () -> Unit,
        onError: (String) -> Unit,
    )
}

/**
 * The subset of a Google Drive file's metadata the importer needs to decide whether the file is
 * importable and to build the [com.tedd.teddreader.core.domain.repository.DocumentImportSource]
 * that represents it, parsed from the Drive REST API's `files.get` JSON response by
 * [parseDriveFileMetadata].
 *
 * @property id the Drive file id, used to build the metadata and download URLs.
 * @property name the file's display name, used both for the imported document's display name and
 *   for extension-based format detection in [isImportSupported].
 * @property mimeType the MIME type Drive reports for the file, or null when Drive did not report
 *   one; [isImportSupported] falls back to [name]'s extension in that case.
 * @property sizeBytes the file's size in bytes as Drive reports it, or `0L` when Drive omitted the
 *   field.
 * @property canDownload whether the signed-in account's Drive permissions actually allow
 *   downloading this file's content; [isImportSupported] refuses to treat a file as importable
 *   when this is false, since the download step would fail regardless of format.
 */
internal data class GoogleDriveFileMetadata(
    val id: String,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val canDownload: Boolean,
)

/**
 * Splits and de-duplicates the comma-joined list of Drive file ids the Android
 * `AuthorizationResult`'s picker extras carry, preserving the order ids first appeared in and
 * discarding any blank entries.
 *
 * @param rawValue the raw comma-separated id string as read from the authorization result.
 * @return the distinct, non-blank file ids in their original order.
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
 * Parses a Drive REST API `files.get` JSON response — as requested with
 * `fields=id,name,mimeType,size,capabilities(canDownload)` by each platform's metadata fetch —
 * into a [GoogleDriveFileMetadata].
 *
 * @param json the raw JSON response body.
 * @return the parsed metadata.
 * @throws IllegalStateException if the response is missing the required `id` or `name` fields.
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
 * Whether this Drive file can actually be imported: the account must be able to download it, and
 * either its reported MIME type or its file-name extension must be one TeddReader parses.
 *
 * @receiver the file metadata to check.
 * @return true when [GoogleDriveFileMetadata.canDownload] is true and the file's MIME type or
 *   extension is supported.
 */
internal fun GoogleDriveFileMetadata.isImportSupported(): Boolean {
    if (!canDownload) return false
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return mimeType in SupportedDocumentMimeTypes || extension in SupportedDocumentExtensions
}

/**
 * Wraps this Drive file's metadata and already-downloaded content into the
 * [DocumentImportSource] the shared [com.tedd.teddreader.core.domain.repository.DocumentRepository]
 * import path expects, using a synthetic `gdrive://` locator since a real Drive file has no
 * platform file-system URI of its own.
 *
 * @receiver the file metadata to wrap.
 * @param bytes the file's full downloaded content.
 * @return a [DocumentImportSource] ready to hand to `DocumentRepository.importDocument`.
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
 * Reads a required, non-blank string field out of a parsed Drive metadata JSON object, failing
 * loudly instead of letting [parseDriveFileMetadata] silently produce a [GoogleDriveFileMetadata]
 * with a blank id or name.
 *
 * @receiver the parsed JSON object to read from.
 * @param key the field name expected to hold a non-blank string value.
 * @return the field's string value.
 * @throws IllegalStateException if [key] is absent, not a string, or blank.
 */
private fun kotlinx.serialization.json.JsonObject.requiredString(key: String): String =
    get(key)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: error("Google Drive metadata missing $key.")

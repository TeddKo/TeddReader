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

public interface GoogleDrivePickerBridge {
    val isConfigured: Boolean

    fun open(
        onPicked: (GoogleDrivePickerResult) -> Unit,
        onCancelled: () -> Unit,
        onError: (String) -> Unit,
    )
}

internal data class GoogleDriveFileMetadata(
    val id: String,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val canDownload: Boolean,
)

internal fun parsePickedFileIds(rawValue: String): List<String> {
    val seen = linkedSetOf<String>()
    rawValue.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .forEach(seen::add)
    return seen.toList()
}

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

internal fun GoogleDriveFileMetadata.isImportSupported(): Boolean {
    if (!canDownload) return false
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return mimeType in SupportedDocumentMimeTypes || extension in SupportedDocumentExtensions
}

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

private fun kotlinx.serialization.json.JsonObject.requiredString(key: String): String =
    get(key)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: error("Google Drive metadata missing $key.")

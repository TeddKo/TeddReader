package com.tedd.teddreader.app.reader.importer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

internal fun externalDocumentUriString(
    action: String?,
    dataUri: String?,
    streamUri: String?,
): String? = when (action) {
    Intent.ACTION_VIEW -> dataUri
    Intent.ACTION_SEND -> streamUri
    else -> null
}

@Suppress("DEPRECATION")
internal fun Intent.externalDocumentUri(): Uri? {
    val streamUri = getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    return externalDocumentUriString(
        action = action,
        dataUri = data?.toString(),
        streamUri = streamUri?.toString(),
    )?.let(Uri::parse)
}

fun androidExternalDocumentImportRequest(
    intent: Intent,
    context: Context,
): ExternalDocumentImportRequest? {
    val uri = intent.externalDocumentUri() ?: return null
    val resolver = context.contentResolver
    val metadata = resolver.queryDocumentMetadata(uri)
    return ExternalDocumentImportRequest(
        sourceUri = uri.toString(),
        displayName = metadata.displayName ?: uri.lastPathSegment,
        mimeType = intent.type ?: resolver.getType(uri),
        sizeBytes = metadata.sizeBytes ?: 0L,
        grantFlags = intent.flags,
    )
}

internal data class AndroidDocumentMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
)

internal fun ContentResolver.queryDocumentMetadata(uri: Uri): AndroidDocumentMetadata = query(
    uri,
    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
    null,
    null,
    null,
)?.use { cursor ->
    if (cursor.moveToFirst()) {
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        AndroidDocumentMetadata(
            displayName = nameIndex.takeIf { it >= 0 }?.let(cursor::getString),
            sizeBytes = sizeIndex.takeIf { it >= 0 }?.let(cursor::getLong),
        )
    } else {
        AndroidDocumentMetadata(displayName = null, sizeBytes = null)
    }
} ?: AndroidDocumentMetadata(displayName = null, sizeBytes = null)

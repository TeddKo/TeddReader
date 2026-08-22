package com.tedd.teddreader.app.reader.importer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Picks the URI string that actually identifies the document an incoming intent carries, given
 * only the plain string/action fields rather than a real [Intent], so the two supported intent
 * shapes' resolution logic can be unit-tested without constructing an Android [Intent] at all.
 *
 * @param action the intent's action, expected to be [Intent.ACTION_VIEW] or [Intent.ACTION_SEND];
 *   any other value (including a plain launcher intent) yields no document.
 * @param dataUri the intent's `data` URI as a string, used for [Intent.ACTION_VIEW] — "open with
 *   TeddReader" from another app or a file browser.
 * @param streamUri the intent's `EXTRA_STREAM` URI as a string, used for [Intent.ACTION_SEND] — a
 *   share-sheet target.
 * @return the URI string identifying the document, or null when [action] is neither supported
 *   action or the relevant URI field was itself null.
 */
internal fun externalDocumentUriString(
    action: String?,
    dataUri: String?,
    streamUri: String?,
): String? = when (action) {
    Intent.ACTION_VIEW -> dataUri
    Intent.ACTION_SEND -> streamUri
    else -> null
}

/**
 * Resolves this intent's attached document [Uri], if any, by applying
 * [externalDocumentUriString]'s action-based rule to this intent's real `data`/`EXTRA_STREAM`
 * fields.
 *
 * Suppressed for the deprecated single-argument `getParcelableExtra`: the type-safe two-argument
 * overload that replaces it only exists from API 33, and this module's `minSdk` is 24, so the
 * deprecated reified overload is the only one available on every device this app supports.
 *
 * @receiver the incoming intent to inspect.
 * @return the document [Uri] the intent carries, or null if it carries none.
 */
@Suppress("DEPRECATION")
internal fun Intent.externalDocumentUri(): Uri? {
    val streamUri = getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    return externalDocumentUriString(
        action = action,
        dataUri = data?.toString(),
        streamUri = streamUri?.toString(),
    )?.let(Uri::parse)
}

/**
 * Builds the [ExternalDocumentImportRequest] an incoming intent describes, resolving its display
 * name, MIME type, and size through [ContentResolver.queryDocumentMetadata] the same way the rest
 * of the Android importer resolves metadata for a user-picked document, so an externally delivered
 * document is treated identically to one picked from inside the app.
 *
 * @param intent the intent that started or retargeted the hosting activity.
 * @param context used to reach the [ContentResolver] that resolves the document's metadata and
 *   MIME type.
 * @return the resolved import request, or null when [intent] carries no document (see
 *   [Intent.externalDocumentUri]).
 */
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

/**
 * The document metadata [ContentResolver.queryDocumentMetadata] can read back from a content
 * provider's `OpenableColumns` projection, before any of the fallbacks
 * [androidExternalDocumentImportRequest] and the rest of the Android importer apply when a
 * provider omits a column.
 *
 * @property displayName the provider-reported file name, or null when the provider has no
 *   `DISPLAY_NAME` column or returned no row.
 * @property sizeBytes the provider-reported size in bytes, or null when the provider has no `SIZE`
 *   column, returned no row, or reported a null size.
 */
internal data class AndroidDocumentMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
)

/**
 * Queries a content [Uri] for its display name and size through the standard
 * [OpenableColumns] projection every Android content provider is expected to support, tolerating a
 * provider that returns no cursor, no rows, or omits either column.
 *
 * @receiver the resolver to query with.
 * @param uri the content URI to describe.
 * @return the metadata the provider reported, with either or both fields null wherever the
 *   provider did not supply them.
 */
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

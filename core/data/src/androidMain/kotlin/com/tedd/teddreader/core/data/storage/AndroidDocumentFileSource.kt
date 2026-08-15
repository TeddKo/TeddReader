package com.tedd.teddreader.core.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tedd.teddreader.core.common.model.DocumentLocation
import java.io.File

class AndroidDocumentFileSource(
    context: Context,
) : DocumentFileSource {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    override suspend fun readBytes(location: DocumentLocation): ByteArray =
        when (Uri.parse(location.sourceUri).scheme) {
            "file" -> File(Uri.parse(location.sourceUri).path ?: error("Cannot open document: ${location.sourceUri}"))
                .readBytes()

            else -> contentResolver.openInputStream(Uri.parse(location.sourceUri))
                ?.use { input -> input.readBytes() }
                ?: error("Cannot open document: ${location.sourceUri}")
        }

    override suspend fun materialize(location: DocumentLocation, bytes: ByteArray): DocumentLocation {
        val directory = File(appContext.filesDir, "documents").apply { mkdirs() }
        val displayName = location.displayName.substringAfterLast('/').ifBlank { "document" }
        val prefix = displayName.substringBeforeLast('.', displayName).take(40).ifBlank { "document" }
        val suffix = displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
        val file = File.createTempFile(prefix.safePrefix(), suffix, directory).apply { writeBytes(bytes) }
        return location.copy(
            sourceUri = Uri.fromFile(file).toString(),
            sizeBytes = bytes.size.toLong(),
        )
    }

    fun persistReadPermission(sourceUri: String, grantFlags: Int) {
        val readFlag = grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readFlag != 0) {
            contentResolver.takePersistableUriPermission(Uri.parse(sourceUri), readFlag)
        }
    }
}

private fun String.safePrefix(): String = filter(Char::isLetterOrDigit)
    .takeIf { it.length >= 3 }
    ?: "doc"

package com.tedd.teddreader.core.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tedd.teddreader.core.common.model.DocumentLocation

class AndroidDocumentFileSource(
    context: Context,
) : DocumentFileSource {
    private val contentResolver = context.applicationContext.contentResolver

    override suspend fun readBytes(location: DocumentLocation): ByteArray =
        contentResolver.openInputStream(Uri.parse(location.sourceUri))
            ?.use { input -> input.readBytes() }
            ?: error("Cannot open document: ${location.sourceUri}")

    fun persistReadPermission(sourceUri: String, grantFlags: Int) {
        val readFlag = grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readFlag != 0) {
            contentResolver.takePersistableUriPermission(Uri.parse(sourceUri), readFlag)
        }
    }
}

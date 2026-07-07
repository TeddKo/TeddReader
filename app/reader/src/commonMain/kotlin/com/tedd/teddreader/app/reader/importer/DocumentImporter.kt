package com.tedd.teddreader.app.reader.importer

import androidx.compose.runtime.Composable
import com.tedd.teddreader.core.common.model.DocumentId

data class ExternalDocumentImportRequest(
    val sourceUri: String,
    val displayName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
    val grantFlags: Int = 0,
) {
    init {
        require(sourceUri.isNotBlank()) { "sourceUri must not be blank." }
        require(sizeBytes >= 0L) { "sizeBytes must be positive." }
    }
}

interface DocumentImporter {
    fun open(
        onImported: (DocumentId) -> Unit,
        onError: (String) -> Unit,
    )

    fun importExternal(
        request: ExternalDocumentImportRequest,
        onImported: (DocumentId) -> Unit,
        onError: (String) -> Unit,
    )
}

@Composable
internal expect fun rememberDocumentImporter(): DocumentImporter

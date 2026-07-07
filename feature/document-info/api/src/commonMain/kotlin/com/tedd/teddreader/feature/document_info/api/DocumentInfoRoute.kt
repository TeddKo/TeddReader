package com.tedd.teddreader.feature.document_info.api

import kotlinx.serialization.Serializable

@Serializable
data class DocumentInfoRoute(
    val documentId: String,
)

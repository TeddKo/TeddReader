package com.tedd.teddreader.feature.reader.api

import kotlinx.serialization.Serializable

@Serializable
data class ReaderRoute(
    val documentId: String,
)

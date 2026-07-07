package com.tedd.teddreader.feature.search.api

import kotlinx.serialization.Serializable

@Serializable
data class SearchRoute(
    val documentId: String,
)

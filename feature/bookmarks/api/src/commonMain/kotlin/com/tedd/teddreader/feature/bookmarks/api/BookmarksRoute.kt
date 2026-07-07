package com.tedd.teddreader.feature.bookmarks.api

import kotlinx.serialization.Serializable

@Serializable
data class BookmarksRoute(
    val documentId: String,
)

package com.tedd.teddreader.feature.home.api

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data class LibraryRoute(
    val folderId: String? = null,
)

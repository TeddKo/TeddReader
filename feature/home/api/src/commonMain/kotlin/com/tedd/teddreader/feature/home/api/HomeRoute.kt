package com.tedd.teddreader.feature.home.api

import kotlinx.serialization.Serializable

/** Navigates to the app's home screen — the entry point shown after launch. */
@Serializable
data object HomeRoute

/**
 * Navigates to the document library.
 *
 * @property folderId the folder to show, or null to show the whole library rather than any single
 *   folder.
 */
@Serializable
data class LibraryRoute(
    val folderId: String? = null,
)

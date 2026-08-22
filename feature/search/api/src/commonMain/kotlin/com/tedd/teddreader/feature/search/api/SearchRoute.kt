package com.tedd.teddreader.feature.search.api

import kotlinx.serialization.Serializable

/**
 * Navigates to the in-document search screen for one document.
 *
 * @property documentId the document being searched, as
 *   [com.tedd.teddreader.core.common.model.DocumentId] stores it (its `.value`), carried as a plain
 *   String since a navigation key must stay [kotlinx.serialization.Serializable].
 */
@Serializable
data class SearchRoute(
    val documentId: String,
)

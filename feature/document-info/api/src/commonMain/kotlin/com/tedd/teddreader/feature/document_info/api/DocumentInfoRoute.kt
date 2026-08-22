package com.tedd.teddreader.feature.document_info.api

import kotlinx.serialization.Serializable

/**
 * Navigates to the document details screen for one document.
 *
 * @property documentId the target document's id, as
 *   [com.tedd.teddreader.core.common.model.DocumentId] stores it (its `.value`), carried as a plain
 *   String since a navigation key must stay [kotlinx.serialization.Serializable].
 */
@Serializable
data class DocumentInfoRoute(
    val documentId: String,
)

package com.tedd.teddreader.feature.reader.api

import kotlinx.serialization.Serializable

/**
 * Navigates to the reader screen for one document.
 *
 * @property documentId the document to open, as
 *   [com.tedd.teddreader.core.common.model.DocumentId] stores it (its `.value`), carried as a plain
 *   String since a navigation key must stay [kotlinx.serialization.Serializable].
 */
@Serializable
data class ReaderRoute(
    val documentId: String,
)

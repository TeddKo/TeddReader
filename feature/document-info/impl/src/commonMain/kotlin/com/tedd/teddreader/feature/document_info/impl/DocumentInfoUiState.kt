package com.tedd.teddreader.feature.document_info.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReadingStats
import com.tedd.teddreader.core.domain.repository.ReadingSession
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * The document-info screen's full snapshot, as [DocumentInfoViewModel] publishes it and
 * [DocumentInfoScreen] renders it: which document is described, what is known about it, and the
 * loading/error state around fetching that.
 *
 * [metadata], [pageIndex], and [stats] arrive together from one `GetDocumentInfoUseCase` call and
 * only apply once that load's [documentId] still matches the one this state was asked to
 * describe, while [sessions] comes from its own independently observed stream — a document whose
 * sessions are still being recorded can have [sessions] update on its own, without the rest of
 * the snapshot re-loading.
 *
 * @property documentId The document this state describes, set as soon as
 *   `DocumentInfoViewModel.setDocument` is called — before any of the fields below have loaded —
 *   and used to guard against reloading a document already loaded.
 * @property metadata The document's stored metadata, or null before the initial load completes
 *   or if it failed.
 * @property pageIndex The document's last saved reading position, or null when nothing has been
 *   saved for it yet.
 * @property stats The document's aggregated reading totals, or null before the initial load
 *   completes or if it failed.
 * @property sessions The document's individual reading sessions, in the order the underlying
 *   stream emits them.
 * @property isLoading True until the initial metadata/page/stats load finishes, successfully or
 *   not; unaffected by [sessions], which loads on its own separate stream.
 * @property errorMessage Non-null when the initial load or the sessions stream most recently
 *   failed.
 */
@Immutable
data class DocumentInfoUiState(
    val documentId: String = "",
    val metadata: DocumentMetadata? = null,
    val pageIndex: PageIndex? = null,
    val stats: ReadingStats? = null,
    val sessions: ImmutableList<ReadingSession> = persistentListOf(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

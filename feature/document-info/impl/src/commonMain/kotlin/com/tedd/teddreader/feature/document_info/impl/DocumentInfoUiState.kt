package com.tedd.teddreader.feature.document_info.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReadingStats
import com.tedd.teddreader.core.domain.repository.ReadingSession

@Immutable
data class DocumentInfoUiState(
    val documentId: String = "",
    val metadata: DocumentMetadata? = null,
    val pageIndex: PageIndex? = null,
    val stats: ReadingStats? = null,
    val sessions: List<ReadingSession> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

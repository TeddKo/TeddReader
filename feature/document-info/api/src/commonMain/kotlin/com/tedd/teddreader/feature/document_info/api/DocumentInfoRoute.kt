package com.tedd.teddreader.feature.document_info.api

import kotlinx.serialization.Serializable

/**
 * 단일 문서의 문서 상세 화면으로 이동하는 탐색 키다.
 *
 * @property documentId 대상 문서의 ID다. 탐색 키가 [kotlinx.serialization.Serializable]을
 *   유지해야 하므로 [com.tedd.teddreader.core.common.model.DocumentId]가 보관하는 값(`.value`)을
 *   일반 String으로 전달한다.
 */
@Serializable
data class DocumentInfoRoute(
    val documentId: String,
)

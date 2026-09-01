package com.tedd.teddreader.feature.search.api

import kotlinx.serialization.Serializable

/**
 * 하나의 문서에 대한 문서 내 검색 화면으로 이동하는 탐색 키이다.
 *
 * @property documentId 검색할 문서로,
 *   [com.tedd.teddreader.core.common.model.DocumentId]가 보관하는 값(`.value`)이다. 탐색 키는
 *   [kotlinx.serialization.Serializable]을 유지해야 하므로 일반 String으로 전달한다.
 */
@Serializable
data class SearchRoute(
    val documentId: String,
)

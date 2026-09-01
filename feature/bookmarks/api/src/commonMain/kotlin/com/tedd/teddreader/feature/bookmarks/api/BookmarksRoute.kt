package com.tedd.teddreader.feature.bookmarks.api

import kotlinx.serialization.Serializable

/**
 * 단일 문서의 북마크 화면으로 이동할 때 사용하는 경로이다.
 *
 * @property documentId 대상 문서의 ID이다.
 *   [com.tedd.teddreader.core.common.model.DocumentId]가 보관하는 값(즉 `.value`)을 그대로 사용하며,
 *   내비게이션 키가 [kotlinx.serialization.Serializable]이어야 하므로 일반 String으로 전달한다.
 */
@Serializable
data class BookmarksRoute(
    val documentId: String,
)

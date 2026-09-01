package com.tedd.teddreader.feature.reader.api

import kotlinx.serialization.Serializable

/**
 * 문서 하나에 대한 리더 화면으로 이동한다.
 *
 * @property documentId 열려는 문서를 [com.tedd.teddreader.core.common.model.DocumentId]가 저장하는 형태(그
 *   `.value`)로 나타낸 값. 내비게이션 키는 [kotlinx.serialization.Serializable]이어야 하므로 일반 String으로
 *   전달한다.
 */
@Serializable
data class ReaderRoute(
    val documentId: String,
)

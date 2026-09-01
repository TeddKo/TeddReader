package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.SearchResult

/**
 * 이미 저장된 문서의 검색을 제공한다.
 *
 * 쓰기는 의도적으로 제공하지 않는다. 검색이 읽는 테이블은 모든 문서의 섹션 텍스트와 블록 구조를 보관하고
 * 문서를 가져올 때 한 번 기록되는 곳과 같다. 따라서 여기의 두 번째 쓰기 경로는 저장된 문서를 더 적은 내용으로
 * 덮어쓸 수밖에 없다.
 */
interface SearchRepository {
    /**
     * 문서 하나의 저장된 텍스트에서 [query]가 나타나는 위치를 찾는다.
     *
     * 모든 호출자가 아니라 여기서 입력 그대로의 검색어를 정규화한다. 앞뒤 공백을 제거하고, 그 결과 비어 있으면
     * 전체가 아니라 아무것도 일치하지 않으며, 1보다 작은 [limit]은 1로 해석한다. 검색을 요청한 호출자는 결과를
     * 요청한 것이다. 두 보장은 다른 동작을 하지 않는 유스 케이스에 있었으므로 같은 3줄이 같은 호출을 두 번
     * 보호했다. 검색 자체의 규칙이므로 이 인터페이스를 직접 호출해도 적용돼야 한다.
     *
     * @param documentId 검색할 문서. 저장된 텍스트가 없으면 아무것도 반환하지 않는다.
     * @param query 독자가 입력한 검색어.
     * @param limit 문서 전체에서 반환할 최대 일치 항목 수. 섹션별 수가 아니다.
     * @return 읽기 순서의 일치 항목. 각 항목은 이동할 위치와 강조할 정확한 범위를 포함한다. 빈 검색어나 일치할
     * 내용이 없는 문서에서는 비어 있다.
     */
    suspend fun findInDocument(
        documentId: DocumentId,
        query: String,
        limit: Int = 50,
    ): List<SearchResult>
}

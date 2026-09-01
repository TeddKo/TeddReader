package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.data.mapper.toSearchResults
import com.tedd.teddreader.core.domain.repository.SearchRepository
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import org.koin.core.annotation.Single

/**
 * Room의 전문 검색 인덱스로 뒷받침되는 [SearchRepository]: [searchIndexDao] 자체는 저장된 섹션
 * 행을 필터링하고 정렬하는 법만 알 뿐 그 안의 발생 위치는 모르므로, 트리밍·빈 쿼리·최소 limit
 * 보장을 실제로 지키는 구현이 [SearchRepository] 계약이 호출자에게 약속하는 것이다.
 *
 * @property searchIndexDao 쿼리가 매칭되는 대상인 섹션별 저장 텍스트의 출처.
 */
@Single(binds = [SearchRepository::class])
class SearchRepositoryImpl(
    private val searchIndexDao: SearchIndexDao,
) : SearchRepository {
    /**
     * [documentId]에 저장된 텍스트에서 [query]의 모든 발생 위치를 읽는 순서대로 찾는다.
     *
     * [query]는 다른 무엇보다 먼저 트리밍되며, 트리밍 후 빈 문자열이 된 쿼리는 "필터 없음"으로
     * 취급되어 모든 것과 매칭되는 대신 아무것도 매칭하지 않는다 — [searchIndexDao]에 아예
     * 도달하지도 않는다. [searchIndexDao]에는 최대 `limit`개의 매칭되는 *섹션*을 요청하며,
     * 각 섹션은 여러 발생 위치를 담을 수 있다. 각 섹션은 아직 채워지지 않은 문서 전체의 결과
     * 예산만큼만 받고, 그 예산이 0에 도달하는 순간 스캔이 멈춘다, 그래서 밀도 높은 첫 챕터가
     * 최종 `take(limit)`이 버릴 수천 개의 [SearchResult]와 스니펫을 할당하는 일이 없다. 반환된
     * 모든 섹션은 최소 하나의 SQL 매치를 담고 있으므로, `limit`개의 섹션을 요청하는 것만으로도
     * 그만큼의 발생 위치가 존재하는 한 같은 크기의 발생 위치 limit를 채우기에 충분하다.
     *
     * @param documentId 검색할 문서.
     * @param query 검색할 텍스트; 앞뒤 공백은 무시된다.
     * @param limit 반환할 발생 위치의 최대 개수; 1 미만인 값은 1로 읽히는데, 검색을 요청하는
     *   호출자는 최소 하나의 결과를 요청하는 것이기 때문이다.
     * @return 문서 순서대로의 매칭된 [SearchResult]들, 또는 트리밍 후 [query]가 비어 있거나
     *   아무것도 매칭하지 않으면 빈 목록.
     */
    override suspend fun findInDocument(
        documentId: DocumentId,
        query: String,
        limit: Int,
    ): List<SearchResult> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return emptyList()

        val effectiveLimit = limit.coerceAtLeast(1)
        val entries = searchIndexDao.search(documentId.value, trimmedQuery, effectiveLimit)
        return buildList(capacity = minOf(effectiveLimit, 16)) {
            for (entry in entries) {
                addAll(entry.toSearchResults(trimmedQuery, limit = effectiveLimit - size))
                if (size >= effectiveLimit) break
            }
        }
    }
}

package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.SearchRepository
import org.koin.core.annotation.Single

/** 검색 한 번이 구체화할 수 있는 최대 결과 수로, 공개 기본값보다 큰 요청도 같은 메모리 예산을 사용하게 한다. */
private const val MaxSearchResultLimit = 50

/** SQL과 섹션 문자열 검색에 전달할 수 있는 최대 검색어 길이이다. */
private const val MaxSearchQueryLength = 256

/**
 * 앞뒤 공백을 제거한 검색어를 [MaxSearchQueryLength]자 이내로 자르되 잘린 끝이 서로게이트 쌍의 앞 절반이면 그 글자를 버리고, 잘린 뒤 생긴 끝 공백도 제거한다.
 *
 * 깨진 서로게이트가 남으면 LIKE 검색이 어떤 본문과도 일치하지 않고, 끝 공백이 남으면 검색창에 되돌려 쓰는 정규화 질의가 사용자 입력과 어긋나기 때문이다.
 *
 * @receiver 이미 앞뒤 공백을 제거한 사용자 입력.
 * @return 길이 예산 안에서 서로게이트 쌍이 온전하고 끝 공백이 없는 검색어.
 */
private fun String.normalizedSearchQuery(): String {
    if (length <= MaxSearchQueryLength) return this
    val cut = take(MaxSearchQueryLength)
    return (if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut).trimEnd()
}

/**
 * 일반적인 빈 결과와 형식상 검색 불가를 구분하면서 실제 검색에 사용한 정규화된 질의를 전달한다.
 *
 * @property query 앞뒤 공백과 최대 길이 제한을 적용해 저장소에 전달한 검색어.
 * @property results 문서 순서의 검색 결과로, 요청한 결과 예산을 넘지 않는다.
 * @property isUnsupported 문서 형식이 텍스트 검색을 제공하지 않으면 `true`.
 */
data class SearchDocumentResult(
    val query: String,
    val results: List<SearchResult>,
    val isUnsupported: Boolean,
)

/**
 * 문서 형식과 입력 예산을 확인한 뒤 텍스트 검색 저장소에 안전한 검색 요청만 전달한다.
 *
 * @property documentRepository 대상 문서의 존재와 검색 가능 형식을 확인하는 저장소.
 * @property searchRepository 정규화된 검색어와 결과 상한으로 문서 본문을 검색하는 저장소.
 */
@Single
class SearchDocumentUseCase(
    private val documentRepository: DocumentRepository,
    private val searchRepository: SearchRepository,
) {
    /**
     * 문서가 텍스트 검색을 지원하면 앞뒤 공백과 입력 예산을 정규화한 검색어로 제한된 수의 결과를 찾는다.
     *
     * @param documentId 검색할 문서.
     * @param query 사용자 입력 검색어. 앞뒤 공백을 제거한 뒤 최대 [MaxSearchQueryLength]자만 사용한다.
     * @param limit 요청할 최대 결과 수. 1 미만은 1, [MaxSearchResultLimit] 초과는 해당 상한으로 제한한다.
     * @return 정규화한 검색어와 결과, 또는 시각 문서라 검색할 수 없다는 표시.
     */
    suspend operator fun invoke(
        documentId: DocumentId,
        query: String,
        limit: Int = MaxSearchResultLimit,
    ): SearchDocumentResult {
        val trimmedQuery = query.trim().normalizedSearchQuery()
        val metadata = documentRepository.getDocument(documentId)
            ?: return SearchDocumentResult(query = trimmedQuery, results = emptyList(), isUnsupported = false)
        if (metadata.format.isVisualPageFormat()) {
            return SearchDocumentResult(query = trimmedQuery, results = emptyList(), isUnsupported = true)
        }
        if (trimmedQuery.isBlank()) return SearchDocumentResult(query = "", results = emptyList(), isUnsupported = false)
        return SearchDocumentResult(
            query = trimmedQuery,
            results = searchRepository.findInDocument(
                documentId,
                trimmedQuery,
                limit.coerceIn(1, MaxSearchResultLimit),
            ),
            isUnsupported = false,
        )
    }
}

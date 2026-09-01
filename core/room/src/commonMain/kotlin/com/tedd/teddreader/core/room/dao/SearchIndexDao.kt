package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.SearchIndexEntity

/**
 * 문서의 저장된 텍스트를 섹션마다 한 행으로 보관하며 검색과 리더가 함께 읽는 테이블입니다.
 *
 * 검색에 필요한 것보다 더 많은 정보를 의도적으로 담습니다. 같은 행에 리더가 배치할 텍스트, 스타일을 지정하는
 * 블록 구조, 책 제목과 목차, 이를 작성한 파서 버전이 들어 있습니다. 하나의 테이블을 사용하므로 책을 열 때
 * 조인 대신 쿼리 한 번이면 되고, 점진적 가져오기는 파싱한 섹션을 계속 추가할 수 있습니다.
 *
 * 열을 분리했으므로 이 구성이 경제적입니다. 큰 책에서는 `blocksJson`이 다른 모든 열보다 훨씬 크므로
 * [getDocumentSectionsWithoutBlocks]는 이를 제외하고, [getSectionBlocksJson]은 곧 그릴 섹션에 대해서만
 * 다시 가져옵니다.
 */
@Dao
interface SearchIndexDao {
    /**
     * 저장된 섹션을 기록하거나 교체합니다. 점진적 가져오기가 배치마다 호출하므로 책의 나머지 부분을 파싱하는
     * 동안에도 읽을 수 있습니다.
     *
     * @param entries 저장할 섹션 행입니다.
     */
    @Upsert
    suspend fun upsertSearchIndex(entries: List<SearchIndexEntity>)

    /**
     * 새로 파싱한 섹션과 문서 수준의 개수/글꼴 누적값을 원자적으로 커밋합니다. 프로세스가 종료되면 이전 앞부분과
     * 이전 누적값 또는 새 앞부분과 새 누적값 중 하나만 보여야 합니다. 새 섹션과 오래된 null이 아닌 개수를 함께
     * 노출하면 재개 로직이 과소 계산된 기준값을 신뢰하게 됩니다. 프로덕션의 [documentDao]는 동일한
     * 데이터베이스 인스턴스에 속하므로 대상 갱신이 이 DAO 트랜잭션에 참여하며, 테스트 대역도 같은 계약을
     * 유지합니다.
     *
     * @param documentDao 누적값 갱신에 사용하는 데이터베이스의 문서 DAO입니다.
     * @param entries 업서트할 새로 파싱한 섹션 행입니다.
     * @param documentId [entries]와 함께 누적값이 증가할 문서입니다.
     * @param characterCount [entries] 이후 완전한 앞부분 전체의 문자 수입니다.
     * @param wordCount [entries] 이후 완전한 앞부분 전체의 단어 수입니다.
     * @param embeddedFontHrefsJson 해당 앞부분 전체의 정확히 정렬된 글꼴 href 집합을 JSON으로 인코딩한 값입니다.
     */
    @Transaction
    suspend fun upsertImportBatch(
        documentDao: DocumentDao,
        entries: List<SearchIndexEntity>,
        documentId: String,
        characterCount: Long,
        wordCount: Long,
        embeddedFontHrefsJson: String,
    ) {
        upsertSearchIndex(entries)
        documentDao.updateCountsAndFontIndex(
            documentId = documentId,
            characterCount = characterCount,
            wordCount = wordCount,
            embeddedFontHrefsJson = embeddedFontHrefsJson,
        )
    }

    /**
     * 검색 결과 위치를 찾고 문맥 일부를 만드는 데 필요한 열만 검색합니다. `blocksJson`을 제외하므로 텍스트 검색이
     * 일치하는 모든 섹션의 훨씬 큰 스타일 블록 페이로드를 구체화하지 않습니다.
     *
     * @param documentId 검색할 문서입니다.
     * @param query 일치시킬 텍스트이며 저장소에서 이미 앞뒤 공백을 제거한 값입니다.
     * @param limit 반환할 *섹션*의 최대 개수이며, 그 안의 일치 항목은 호출자가 계산합니다.
     * @return 문서 순서로 정렬된 일치 섹션 프로젝션입니다.
     */
    @Query(
        "SELECT documentId, sectionIndex, sectionTitle, text, startOffset, endOffset " +
            "FROM search_index WHERE documentId = :documentId AND text LIKE '%' || :query || '%' " +
            "ORDER BY sectionIndex LIMIT :limit",
    )
    suspend fun search(documentId: String, query: String, limit: Int): List<SearchIndexSearchEntry>

    /**
     * 문서를 여는 데 필요한 정보 중 `blocksJson`을 제외한 모든 것을 반환합니다.
     *
     * 큰 책에서는 이 열 하나가 나머지 모든 열을 합친 것보다 훨씬 큽니다. 여기서 읽으면 페이지를 하나 만들기도 전에
     * 책을 열 때마다 전체 내용을 문자열로 메모리에 불러왔습니다. [getSectionBlocksJson]은 실제로 스타일이 필요한
     * 섹션에 대해서만 이를 다시 가져옵니다.
     *
     * @param documentId 불러올 문서입니다.
     * @return 각 행에서 블록 구조를 제외하고 문서 순서로 정렬한 섹션입니다.
     */
    @Query(
        "SELECT sectionIndex, sectionTitle, text, startOffset, endOffset, documentTitle, navigationJson, parserVersion " +
            "FROM search_index WHERE documentId = :documentId ORDER BY sectionIndex",
    )
    suspend fun getDocumentSectionsWithoutBlocks(documentId: String): List<SearchIndexSectionEntry>

    /**
     * @param documentId 섹션이 속한 문서입니다.
     * @param sectionIndexes 블록 구조가 필요한 섹션입니다.
     * @return 섹션별 저장 JSON이며, 값이 없는 섹션은 제외합니다.
     */
    @Query("SELECT sectionIndex, blocksJson FROM search_index WHERE documentId = :documentId AND sectionIndex IN (:sectionIndexes)")
    suspend fun getSectionBlocksJson(documentId: String, sectionIndexes: List<Int>): List<SectionBlocksJsonEntry>

    /**
     * 이미 저장된 마지막 섹션과 텍스트가 끝나는 위치로, 점진적 가져오기를 재개하는 데 필요한 모든 정보입니다.
     *
     * 모든 섹션 대신 한 행만 읽으므로 큰 책의 후반부에서도 저렴하게 재개할 수 있습니다. 대안인
     * [getDocumentSectionsWithoutBlocks]는 끝을 찾기 위해 지금까지 가져온 텍스트 전체를 읽습니다.
     *
     * @param documentId 가져오는 중인 문서입니다.
     * @return 저장된 가장 큰 섹션과 텍스트 바로 다음 오프셋이며, 저장된 내용이 아직 없으면 null입니다.
     */
    @Query("SELECT sectionIndex, endOffset FROM search_index WHERE documentId = :documentId ORDER BY sectionIndex DESC LIMIT 1")
    suspend fun getLastSection(documentId: String): SectionOffsetEntry?

    /**
     * 저장된 섹션 하나의 이름을 바꿉니다. 책 목차의 제목이 챕터 자체 마크업에서 추측한 제목을 교체하는 방식입니다.
     *
     * 점진적 가져오기는 이 작업과 제목/내비게이션 열 기록을 마지막 배치까지 미룹니다. 모든 섹션이 존재하기 전에
     * 내비게이션과 제목을 대조하면 일부 섹션의 이름을 잘못 정하며, 독자가 보고 있는 제목이 바뀌는 것은 늦게
     * 도착하는 것보다 나쁩니다.
     *
     * @param documentId 문서입니다.
     * @param sectionIndex 이름을 바꿀 섹션입니다.
     * @param title 책 자체 내비게이션에서 가져온 제목입니다.
     */
    @Query("UPDATE search_index SET sectionTitle = :title WHERE documentId = :documentId AND sectionIndex = :sectionIndex")
    suspend fun updateSectionTitle(documentId: String, sectionIndex: Int, title: String)

    /**
     * 책 자체의 제목과 목차를 기록합니다. 책 전체에 속하는 값이지만 문서를 열 때 두 번째 쿼리 없이 텍스트와 함께
     * 읽도록 한 섹션 행에 저장합니다.
     *
     * @param documentId 문서입니다.
     * @param sectionIndex 책 전체 값을 저장할 섹션 행입니다.
     * @param documentTitle 책 제목입니다.
     * @param navigationJson 직렬화한 책의 목차입니다.
     */
    @Query(
        "UPDATE search_index SET documentTitle = :documentTitle, navigationJson = :navigationJson " +
            "WHERE documentId = :documentId AND sectionIndex = :sectionIndex",
    )
    suspend fun updateDocumentTitleAndNavigation(
        documentId: String,
        sectionIndex: Int,
        documentTitle: String,
        navigationJson: String,
    )

    /**
     * 내비게이션에서 얻은 모든 섹션 제목과 문서 수준 내비게이션 행을 하나의 트랜잭션으로 적용합니다.
     * 완료 시점에만 이 값들이 권위 있는 값이 되므로 프로세스 종료 후 부분적으로 갱신된 목차를 노출하는 것은
     * 완료 전 값을 유지하는 것보다 나쁩니다.
     *
     * @param documentId 내비게이션을 확정하는 문서입니다.
     * @param sectionIndex 문서 수준 제목 및 내비게이션 데이터를 담는 행입니다.
     * @param documentTitle 완료 시 확정한 패키지 제목입니다.
     * @param navigationJson 직렬화한 완료된 내비게이션 트리입니다.
     * @param titleUpdates 확정된 spine 인덱스를 키로 하는 섹션 제목입니다.
     */
    @Transaction
    suspend fun updateCompletedNavigation(
        documentId: String,
        sectionIndex: Int,
        documentTitle: String,
        navigationJson: String,
        titleUpdates: List<SectionTitleUpdate>,
    ) {
        titleUpdates.forEach { update ->
            updateSectionTitle(documentId, update.sectionIndex, update.title)
        }
        updateDocumentTitleAndNavigation(documentId, sectionIndex, documentTitle, navigationJson)
    }

    /**
     * @param documentId 저장된 텍스트를 삭제할 문서입니다.
     */
    @Query("DELETE FROM search_index WHERE documentId = :documentId")
    suspend fun deleteSearchIndex(documentId: String)

    /**
     * 문서의 모든 저장 섹션에 대한 원본 경로와 섹션 인덱스를 섹션 인덱스 순서로 반환합니다. [finishEpubImport]가
     * 각 섹션의 전체 텍스트를 읽지 않고 사용하는 가벼운 쿼리입니다. 내비게이션을 해석하는 데 원본 경로만 필요하고
     * 경로 맵 검증에는 섹션 수만 필요합니다.
     *
     * @param documentId 조회할 문서입니다.
     * @return 각 섹션의 인덱스와 원본 경로를 문서 순서로 반환합니다.
     */
    @Query(
        "SELECT sectionIndex, sourcePath FROM search_index WHERE documentId = :documentId ORDER BY sectionIndex",
    )
    suspend fun getSectionSourcePaths(documentId: String): List<SectionSourcePathEntry>

    /**
     * 가져오기 완료 시 내비게이션을 해석하기 위해 읽을 수 있는 첫 본문 섹션, 즉 표지(표지가 있으면 인덱스 0)가
     * 아니고 비어 있지도 않은 섹션의 인덱스와 텍스트가 비어 있지 않은 상태를 반환합니다.
     *
     * @param documentId 조회할 문서입니다.
     * @param excludeSectionIndex 제외할 섹션 인덱스이며, 일반적으로 표지 섹션입니다.
     * @return 비어 있지 않은 첫 본문 섹션의 인덱스이며, 없으면 null입니다.
     */
    @Query(
        "SELECT sectionIndex FROM search_index WHERE documentId = :documentId " +
            "AND sectionIndex != :excludeSectionIndex AND text != '' AND TRIM(text) != '' " +
            "ORDER BY sectionIndex LIMIT 1",
    )
    suspend fun getFirstReadableContentSectionIndex(documentId: String, excludeSectionIndex: Int): Int?

    /**
     * 전체 행을 불러오지 않고 finishEpubImport가 캐시된 원본 경로 맵을 검증할 때 사용하는 문서의 저장 섹션
     * 전체 개수를 반환합니다.
     *
     * @param documentId 섹션 수를 셀 문서입니다.
     * @return 저장된 섹션 수입니다.
     */
    @Query("SELECT COUNT(*) FROM search_index WHERE documentId = :documentId")
    suspend fun getSectionCount(documentId: String): Int
}

/**
 * [SearchIndexDao.getLastSection]의 결과로, 이미 저장된 모든 섹션을 읽지 않고 점진적 가져오기를 재개하기에 충분한
 * 정보입니다.
 *
 * @property sectionIndex 문서에 이미 저장된 가장 큰 섹션 인덱스입니다.
 * @property endOffset 해당 섹션 마지막 문자 바로 다음 위치이며, 다음 가져오기 배치가 재개할 위치입니다.
 */
data class SectionOffsetEntry(
    val sectionIndex: Int,
    val endOffset: Long,
)

/**
 * `blocksJson` 열을 제외한 [SearchIndexEntity]입니다. [SearchIndexDao.getDocumentSectionsWithoutBlocks]를
 * 참고하십시오.
 *
 * @property sectionIndex 문서 순서에서 섹션의 위치입니다.
 * @property sectionTitle 섹션 제목이며, 없으면 null입니다.
 * @property text 파싱할 때 줄 끝을 정규화한 섹션 텍스트입니다.
 * @property startOffset 전체 문서에서 이 텍스트가 시작하는 위치입니다.
 * @property endOffset 텍스트가 끝나는 위치 바로 다음입니다.
 * @property documentTitle 책 자체의 제목이며, 이를 기록한 섹션 행에만 있습니다.
 * @property navigationJson 책의 목차이며, 같은 방식으로 직렬화해 같은 행에 저장합니다.
 * @property parserVersion 이 행을 작성한 파서 빌드로, 리더가 파서 변경보다 앞선 저장 텍스트를 구분합니다.
 */
data class SearchIndexSectionEntry(
    val sectionIndex: Int,
    val sectionTitle: String?,
    val text: String,
    val startOffset: Long,
    val endOffset: Long,
    val documentTitle: String?,
    val navigationJson: String,
    val parserVersion: Int,
)

/**
 * [SearchIndexDao.search]가 일치 항목 매핑에 반환하는 가벼운 섹션 프로젝션입니다. 검색에는 텍스트, 절대
 * 오프셋, 결과 옆에 표시할 제목만 필요하므로 `blocksJson`, 내비게이션, 파서 메타데이터를 의도적으로 제외합니다.
 *
 * @property documentId 섹션이 속한 문서입니다.
 * @property sectionIndex 프로젝션이 원본 행을 완전히 식별하도록 유지하는 섹션 위치이며, 쿼리는 이 값으로 결과를
 * 정렬합니다.
 * @property sectionTitle 이 섹션의 일치 항목 옆에 표시할 제목이며, 없으면 null입니다.
 * @property text 일치 항목과 문맥 일부를 찾기 위해 스캔하는 일반 섹션 텍스트입니다.
 * @property startOffset 전체 문서에서 [text]가 시작하는 위치입니다.
 * @property endOffset 전체 문서에서 [text]가 끝나는 위치 바로 다음입니다.
 */
data class SearchIndexSearchEntry(
    val documentId: String,
    val sectionIndex: Int,
    val sectionTitle: String?,
    val text: String,
    val startOffset: Long,
    val endOffset: Long,
)

/**
 * 필요할 때 가져오는 한 섹션의 `blocksJson`입니다. [SearchIndexDao.getSectionBlocksJson]을 참고하십시오.
 *
 * @property sectionIndex 이 블록 구조가 속한 섹션입니다.
 * @property blocksJson 직렬화한 섹션 블록 구조입니다.
 */
data class SectionBlocksJsonEntry(
    val sectionIndex: Int,
    val blocksJson: String,
)

/**
 * 섹션의 원본 경로입니다. [SearchIndexDao.getSectionSourcePaths]를 참고하십시오.
 *
 * @property sectionIndex 문서 순서에서 섹션의 위치입니다.
 * @property sourcePath 이 섹션을 파싱한 spine 항목의 아카이브 상대 경로이며, TeddReaderMigration8To9 이전에
 * 가져온 섹션이나 EPUB이 아닌 문서에서는 null입니다.
 */
data class SectionSourcePathEntry(
    val sectionIndex: Int,
    val sourcePath: String?,
)

/**
 * 완료된 EPUB 내비게이션에서 확정한 섹션 제목 하나입니다.
 *
 * @property sectionIndex 이름을 바꿀 저장된 섹션입니다.
 * @property title 파싱 시점 제목을 교체할 내비게이션 제목입니다.
 */
data class SectionTitleUpdate(
    val sectionIndex: Int,
    val title: String,
)

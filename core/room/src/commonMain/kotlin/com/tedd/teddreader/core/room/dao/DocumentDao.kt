package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

/**
 * 존재하는 문서와 각 문서를 추가하거나 마지막으로 연 시각을 관리하는 라이브러리 테이블입니다.
 *
 * [observeRecentDocuments]는 마지막으로 연 시각을 기준으로 정렬하고 없으면 추가 시각을 사용하므로, 새로 가져온
 * 책은 읽은 적이 없어도 홈 화면 맨 위에 표시됩니다. 여기서 문서를 삭제하면 캐스케이드되는 진행 위치, 북마크,
 * 검색 인덱스, 페이지 레이아웃도 함께 삭제되므로 삭제 후 다른 곳에서 정리할 필요가 없습니다.
 */
@Dao
interface DocumentDao {
    /**
     * 라이브러리 행을 삽입하거나 기존 행을 교체합니다.
     *
     * @param document 전체 행입니다. 모든 열을 기록하므로 호출자는 읽은 값의 복사본을 수정합니다.
     */
    @Upsert
    suspend fun upsertDocument(document: DocumentEntity)

    /**
     * @param documentId 문서 id입니다.
     * @return 해당 행이며, 이 id로 가져온 문서가 없으면 null입니다.
     */
    @Query("SELECT * FROM documents WHERE id = :documentId")
    suspend fun getDocument(documentId: String): DocumentEntity?

    /**
     * @return 모든 라이브러리 행을 마지막으로 연 순서대로 제공하고, 없으면 추가 시각을 사용하는 Flow입니다.
     * 따라서 새로 가져온 책은 읽은 적이 없어도 맨 위에 표시됩니다.
     */
    @Query("SELECT * FROM documents ORDER BY COALESCE(lastOpenedAtEpochMillis, addedAtEpochMillis) DESC")
    fun observeRecentDocuments(): Flow<List<DocumentEntity>>

    /**
     * 일치하는 모든 행의 북마크 플래그를 하나의 구문으로 다시 기록합니다.
     */
    @Query("UPDATE documents SET isBookmarked = :isBookmarked WHERE id IN (:documentIds)")
    suspend fun updateBookmarked(documentIds: List<String>, isBookmarked: Boolean)

    /**
     * 일치하는 모든 행의 폴더 쌍을 하나의 구문으로 다시 기록합니다.
     */
    @Query("UPDATE documents SET folderId = :folderId, folderName = :folderName WHERE id IN (:documentIds)")
    suspend fun updateFolder(documentIds: List<String>, folderId: String?, folderName: String?)

    /**
     * 현재 [folderId]를 가진 모든 행의 폴더 이름을 변경합니다.
     */
    @Query("UPDATE documents SET folderName = :folderName WHERE folderId = :folderId")
    suspend fun renameFolder(folderId: String, folderName: String)

    /**
     * 현재 속한 모든 행에서 [folderId]를 하나의 구문으로 제거합니다.
     */
    @Query("UPDATE documents SET folderId = NULL, folderName = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)

    /**
     * 문서를 연 시각을 기록하며, 이 동작만 라이브러리 목록의 순서를 바꿉니다.
     *
     * @param documentId 연 문서입니다.
     * @param openedAtEpochMillis 문서를 연 시각입니다.
     */
    @Query("UPDATE documents SET lastOpenedAtEpochMillis = :openedAtEpochMillis WHERE id = :documentId")
    suspend fun updateLastOpenedAt(documentId: String, openedAtEpochMillis: Long)

    /**
     * 라이브러리 행과 캐스케이드되는 진행 위치, 저장 위치, 저장된 텍스트, 측정된 페이지 레이아웃을 함께 삭제합니다.
     *
     * @param documentId 삭제할 문서입니다.
     */
    @Query("DELETE FROM documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String)

    /**
     * 여러 라이브러리 행을 하나의 구문으로 삭제합니다.
     */
    @Query("DELETE FROM documents WHERE id IN (:documentIds)")
    suspend fun deleteDocuments(documentIds: List<String>)

    /**
     * 문서의 문자 수, 단어 수, 내장 글꼴 인덱스만 갱신하고 즐겨찾기, 폴더, lastOpened 등 다른 모든 열은
     * 그대로 둡니다. 가져오기 배치가 이 함수를 사용하므로, 전체 행을 읽고 다시 쓰는 가져오기가 동시에 발생한
     * 라이브러리 편집(즐겨찾기 지정, 폴더 이동)을 덮어쓰지 않습니다.
     *
     * @param documentId 갱신할 문서입니다.
     * @param characterCount 현재까지 누적된 문자 수입니다.
     * @param wordCount 현재까지 누적된 단어 수입니다.
     * @param embeddedFontHrefsJson JSON으로 인코딩된 정렬된 글꼴 href 집합이며, 인덱스를 지우려면 null입니다.
     */
    @Query(
        "UPDATE documents SET characterCount = :characterCount, wordCount = :wordCount, " +
            "embeddedFontHrefsJson = :embeddedFontHrefsJson WHERE id = :documentId",
    )
    suspend fun updateCountsAndFontIndex(
        documentId: String,
        characterCount: Long,
        wordCount: Long,
        embeddedFontHrefsJson: String?,
    )

    /**
     * 즐겨찾기, 폴더, lastOpened 열을 건드리지 않고 문서의 가져오기 완료 시각과 최종 개수를 하나의 대상 갱신으로
     * 기록합니다.
     *
     * @param documentId 완료로 표시할 문서입니다.
     * @param characterCount 최종 문자 수입니다.
     * @param wordCount 최종 단어 수입니다.
     * @param importCompletedAtEpochMillis 완료 타임스탬프입니다.
     */
    @Query(
        "UPDATE documents SET characterCount = :characterCount, wordCount = :wordCount, " +
            "importCompletedAtEpochMillis = :importCompletedAtEpochMillis WHERE id = :documentId",
    )
    suspend fun updateCountsAndMarkComplete(
        documentId: String,
        characterCount: Long,
        wordCount: Long,
        importCompletedAtEpochMillis: Long,
    )

    /**
     * 다른 값을 건드리지 않고 내장 글꼴 href 인덱스 열만 기록합니다. 첫 전체 블록 스캔 이후 레거시
     * 백필 경로에서 사용합니다.
     *
     * @param documentId 갱신할 문서입니다.
     * @param embeddedFontHrefsJson JSON으로 인코딩된 정렬된 글꼴 href 집합입니다.
     */
    @Query("UPDATE documents SET embeddedFontHrefsJson = :embeddedFontHrefsJson WHERE id = :documentId")
    suspend fun updateEmbeddedFontHrefsJson(documentId: String, embeddedFontHrefsJson: String)
}

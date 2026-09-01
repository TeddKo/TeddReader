package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.PageLayoutEntity

/**
 * 책을 다시 열 때 재측정하지 않도록 캐시한 측정된 페이지 경계입니다.
 *
 * 측정값은 문서, 글자 설정, 뷰포트 전체 조합에 대해 유효합니다. 같은 책도 글꼴이 커지거나 창이 넓어지면
 * 다른 곳에서 나뉘므로 이 조합으로 행을 식별합니다. 행은 캐시일 뿐입니다. 같은 입력에서 페이지 분할은 결정적이므로
 * 행을 삭제해도 한 번 재측정할 뿐 데이터를 잃지 않습니다.
 *
 * 테이블을 증가시키는 요인은 시간 경과가 아니라 독자가 글자 설정을 시도하는 것이므로 만료 대신
 * [trimPageLayouts]로 크기를 제한합니다.
 */
@Dao
interface PageLayoutDao {
    /**
     * 측정값을 기록하며 같은 (문서, 글자 설정, 뷰포트) 키를 가진 행을 교체합니다.
     *
     * @param layout 측정된 페이지 시작점과 그 값이 속한 키입니다.
     */
    @Upsert
    suspend fun upsertPageLayout(layout: PageLayoutEntity)

    /**
     * @param documentId 책입니다.
     * @param fontSizeSp 정확히 일치시킬 글자 크기입니다.
     * @param lineHeightMultiplier 정확히 일치시킬 줄 높이입니다.
     * @param fontFamilyName 일치시킬 글꼴 패밀리이며, `""`는 시스템 기본값을 뜻합니다(PageLayoutEntity 참고).
     * @param viewportWidthPx 정확히 일치시킬 창 너비입니다.
     * @param viewportHeightPx 정확히 일치시킬 창 높이입니다.
     * @return 이 조합과 정확히 일치하는 저장된 측정값이며, 측정한 적이 없으면 null입니다.
     */
    @Query(
        "SELECT * FROM page_layouts WHERE documentId = :documentId AND fontSizeSp = :fontSizeSp AND " +
            "lineHeightMultiplier = :lineHeightMultiplier AND fontFamilyName = :fontFamilyName AND " +
            "viewportWidthPx = :viewportWidthPx AND viewportHeightPx = :viewportHeightPx",
    )
    suspend fun getPageLayout(
        documentId: String,
        fontSizeSp: Float,
        lineHeightMultiplier: Float,
        fontFamilyName: String,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): PageLayoutEntity?

    /**
     * 아직 창 측정값이 없는 호출자를 위해 *모든* 뷰포트 중 이 글자 설정으로 만든 최신 측정값을 반환합니다.
     *
     * 뷰포트를 무시하는 것이 핵심입니다. 어떤 창 크기에서 이 글자 설정으로 측정한 행도 실제 측정값이며, 최신
     * 행은 창이 곧 보고할 값에 대해 사용 가능한 최선의 추정치입니다. 거의 항상 바로 그 물리 화면에서 측정한
     * 값입니다. 추측한 뷰포트로 페이지를 나눠 첫 실제 측정값이 곧 뒤집을 페이지 수를 게시하는 것보다 낫습니다.
     *
     * @param documentId 책입니다.
     * @param fontSizeSp 일치시킬 글자 크기입니다.
     * @param lineHeightMultiplier 일치시킬 줄 높이입니다.
     * @param fontFamilyName 일치시킬 글꼴 패밀리이며, `""`는 시스템 기본값을 뜻합니다.
     * @return 모든 뷰포트 중 이 글자 설정으로 가장 최근에 기록한 측정값이며, 없으면 null입니다.
     */
    @Query(
        "SELECT * FROM page_layouts WHERE documentId = :documentId AND fontSizeSp = :fontSizeSp AND " +
            "lineHeightMultiplier = :lineHeightMultiplier AND fontFamilyName = :fontFamilyName " +
            "ORDER BY writtenAtEpochMillis DESC LIMIT 1",
    )
    suspend fun getNewestPageLayoutForStyle(
        documentId: String,
        fontSizeSp: Float,
        lineHeightMultiplier: Float,
        fontFamilyName: String,
    ): PageLayoutEntity?

    /**
     * 책 하나의 모든 측정값을 삭제합니다. 한 번 재측정할 뿐 데이터를 잃지 않습니다.
     *
     * @param documentId 측정값을 버릴 책입니다.
     */
    @Query("DELETE FROM page_layouts WHERE documentId = :documentId")
    suspend fun deletePageLayouts(documentId: String)

    /**
     * 문서에서 가장 최근에 기록한 행 [keep]개만 유지하고 오래된 측정값을 버립니다.
     *
     * 독자가 정착하기 전에 여러 글꼴 크기나 줄 높이를 시도할 때마다 새 레이아웃을 측정하므로, 이 제한이 없으면
     * 시도한 모든 조합마다 행이 하나씩 늘어납니다. 독자가 도달한 설정은 책을 다시 열 때 사용할 설정이므로 최신순으로
     * 유지하는 것이 맞습니다.
     *
     * @param documentId 크기를 제한할 책입니다.
     * @param keep 가장 최근에 기록한 측정값 중 유지할 개수입니다.
     */
    @Query(
        "DELETE FROM page_layouts WHERE documentId = :documentId AND rowid NOT IN " +
            "(SELECT rowid FROM page_layouts WHERE documentId = :documentId " +
            "ORDER BY writtenAtEpochMillis DESC LIMIT :keep)",
    )
    suspend fun trimPageLayouts(documentId: String, keep: Int)

    /**
     * 문서의 모든 부분 레이아웃 행을 삭제합니다. 문서가 늘어날 때, 즉 새 가져오기 배치가 저장될 때 사용하여
     * 이전 앞부분을 대상으로 한 오래된 부분 측정값이 나중에 열 때 잘못 복원되지 않게 합니다.
     *
     * @param documentId 부분 행을 버릴 문서입니다.
     */
    @Query("DELETE FROM page_layouts WHERE documentId = :documentId AND isPartial = 1")
    suspend fun deletePartialPageLayouts(documentId: String)

    /**
     * 부분 레이아웃 행의 `isPartial` 플래그를 `0`으로 설정해 완전한 행으로 승격합니다. 가져오기가 완료되고 행의
     * 기존 문자 수가 측정값이 전체 문서를 포함함을 증명할 때 한 번 호출합니다.
     *
     * @param documentId 부분 행을 승격할 문서입니다.
     * @param characterCount 승격하려는 행이 가져야 하는 정확한 문자 수입니다. `characterCount`가 이미 일치하는
     *   행만 변경하므로 이전 앞부분의 오래된 행을 실수로 승격하지 않습니다.
     */
    @Query(
        "UPDATE page_layouts SET isPartial = 0 WHERE documentId = :documentId " +
            "AND characterCount = :characterCount AND isPartial = 1",
    )
    suspend fun promotePartialLayouts(documentId: String, characterCount: Long)
}

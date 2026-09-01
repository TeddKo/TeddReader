package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

/**
 * 각 문서의 독서 위치를 관리합니다. 문서마다 정확히 하나의 행이 있으며 페이지를 넘길 때마다 교체됩니다.
 *
 * 두 호출자의 요구가 달라 suspend 읽기와 Flow를 모두 제공합니다. 책을 열 때는 레이아웃을 만들기 전에 위치가 한
 * 번 필요하고, 진행 상황을 표시하는 화면은 위치를 계속 관찰해야 합니다. 페이지를 넘길 때마다 한 번 기록하는 앱의
 * 가장 빈번한 쓰기이며, 데이터베이스가 WAL 모드를 유지하는 이유 중 하나입니다.
 */
@Dao
interface ReadingProgressDao {
    /**
     * 문서의 위치를 기록하고 해당 문서의 단일 기존 행을 교체합니다.
     *
     * @param progress 저장할 행입니다.
     */
    @Upsert
    suspend fun upsertProgress(progress: ReadingProgressEntity)

    /**
     * @param documentId 열고 있는 문서입니다.
     * @return 저장된 위치이며, 책을 한 번도 열지 않았으면 null입니다.
     */
    @Query("SELECT * FROM reading_progress WHERE documentId = :documentId")
    suspend fun getProgress(documentId: String): ReadingProgressEntity?

    /**
     * @param documentId 관찰할 문서입니다.
     * @return 문서 위치의 Flow이며, 책을 한 번도 열지 않았으면 null을 방출합니다.
     */
    @Query("SELECT * FROM reading_progress WHERE documentId = :documentId")
    fun observeProgress(documentId: String): Flow<ReadingProgressEntity?>

    /**
     * @param documentId 위치를 잊을 문서이며, 다음에 열 때 처음부터 시작합니다.
     */
    @Query("DELETE FROM reading_progress WHERE documentId = :documentId")
    suspend fun deleteProgress(documentId: String)
}

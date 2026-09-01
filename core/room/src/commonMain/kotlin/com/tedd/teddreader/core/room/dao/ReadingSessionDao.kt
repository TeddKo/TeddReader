package com.tedd.teddreader.core.room.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tedd.teddreader.core.room.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 문서별 독서 세션과 합산된 활성 시간을 관리합니다.
 *
 * [getTotalActiveMillis]는 SQL에서 합산하고 세션이 없는 문서에 null 대신 0을 반환하도록 COALESCE 처리합니다. 현재는
 * 세션을 기록하는 코드가 없으므로 모든 문서가 이에 해당합니다(ReadingStatsRepository 참고).
 */
@Dao
interface ReadingSessionDao {
    /**
     * 독서 세션을 기록하며, 열린 세션을 닫을 수 있도록 같은 id의 세션을 교체합니다.
     *
     * @param session 저장할 행입니다.
     */
    @Upsert
    suspend fun upsertSession(session: ReadingSessionEntity)

    /**
     * @param documentId 독서 이력을 관찰할 문서입니다.
     * @return 세션을 최신순으로 제공하는 Flow입니다. 현재는 기록하는 코드가 없어 비어 있습니다.
     */
    @Query("SELECT * FROM reading_sessions WHERE documentId = :documentId ORDER BY startedAtEpochMillis DESC")
    fun observeSessions(documentId: String): Flow<List<ReadingSessionEntity>>

    /**
     * @param documentId 활성 시간을 합산할 문서입니다.
     * @return 합산된 활성 독서 시간입니다. 세션이 없는 문서에 null 대신 0을 반환하도록 COALESCE 처리합니다.
     */
    @Query("SELECT COALESCE(SUM(activeMillis), 0) FROM reading_sessions WHERE documentId = :documentId")
    suspend fun getTotalActiveMillis(documentId: String): Long
}

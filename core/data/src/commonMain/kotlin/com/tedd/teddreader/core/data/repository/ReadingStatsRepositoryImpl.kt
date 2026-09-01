package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReadingStats
import com.tedd.teddreader.core.data.mapper.toReadingSession
import com.tedd.teddreader.core.data.mapper.toReadingSessionEntity
import com.tedd.teddreader.core.domain.repository.ReadingSession
import com.tedd.teddreader.core.domain.repository.ReadingStatsRepository
import com.tedd.teddreader.core.room.dao.DocumentDao
import com.tedd.teddreader.core.room.dao.ReadingSessionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

/**
 * Room으로 뒷받침되는 [ReadingStatsRepository]: 읽기 세션의 원시 로그를, 독자가 책을 얼마나
 * 읽었는지에 대해 보게 되는 숫자로 바꾼다. 두 개의 Room 테이블을 끌어온다 — [readingSessionDao]가
 * 보관하는 세션 로그와, 문서가 임포트될 때 한 번 기록된 [documentDao]의 문서별 문자/단어 수 —
 * "얼마나 많은 텍스트에 대해 얼마나 많은 시간을"이라는 질문의 두 절반 중 어느 테이블도 혼자서는 둘 다
 * 갖고 있지 않기 때문이다.
 *
 * @property readingSessionDao 개별 읽기 세션들과 그 합산된 활성 시간의 출처.
 * @property documentDao 문서 자신의 문자 수와 단어 수의 출처.
 */
@Single(binds = [ReadingStatsRepository::class])
class ReadingStatsRepositoryImpl(
    private val readingSessionDao: ReadingSessionDao,
    private val documentDao: DocumentDao,
) : ReadingStatsRepository {
    /**
     * [documentId]에 기록된 모든 읽기 세션. 최신순이며, 새 세션이 생길 때마다 갱신된다.
     *
     * @param documentId 세션 이력을 관찰할 문서.
     * @return 문서의 [ReadingSession] 목록에 대한 [Flow].
     */
    override fun observeSessions(documentId: DocumentId): Flow<List<ReadingSession>> =
        readingSessionDao.observeSessions(documentId.value).map { sessions ->
            sessions.map { session -> session.toReadingSession() }
        }

    /**
     * [session]을 문서의 읽기 이력에 추가한다. 여기서 세션은 결코 병합되거나 덮어써지지 않는다;
     * 이 함수를 호출할 때마다 [observeSessions]와 [getStats] 둘 다 읽는 로그에 항목이 하나씩
     * 더해질 뿐이다.
     *
     * @param session 기록할, 완료되었거나(또는 진행 중인) 세션.
     */
    override suspend fun recordSession(session: ReadingSession) {
        readingSessionDao.upsertSession(session.toReadingSessionEntity())
    }

    /**
     * [documentId]의 읽기 활동을 하나의 스냅샷으로 집계한다: 읽는 데 쓴 총 활성 시간, 그리고 그
     * 시간이 문서의 얼마만큼을 커버하는지. 활성 시간은 읽을 때마다 바뀌므로 저장되지 않고 매번 모든
     * 세션에 걸쳐 즉석에서 합산된다; 문자 수와 단어 수는 문서 자신의 값으로, 임포트 시점에 한 번
     * 측정되어 그 후로는 고정이며, 문서 행을 찾을 수 없을 때는(예: 호출자가 id를 읽은 시점과 이
     * 함수를 호출하는 시점 사이에 삭제된 경우) `0`으로 기본값 처리된다.
     *
     * @param documentId 요약할 문서.
     * @return 실시간 활성 시간과 문서의 고정된 크기를 결합한 [ReadingStats].
     */
    override suspend fun getStats(documentId: DocumentId): ReadingStats {
        val document = documentDao.getDocument(documentId.value)
        return ReadingStats(
            documentId = documentId,
            activeMillis = readingSessionDao.getTotalActiveMillis(documentId.value),
            charactersRead = document?.characterCount ?: 0L,
            wordsRead = document?.wordCount ?: 0L,
        )
    }
}

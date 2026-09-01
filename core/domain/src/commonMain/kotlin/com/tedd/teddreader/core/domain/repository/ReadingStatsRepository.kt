package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReadingStats
import kotlinx.coroutines.flow.Flow

/**
 * 한 문서의 한 번의 읽기 구간으로, 시작 시각과 시작 위치, 독자가 실제로 읽은 시간을 담는다.
 *
 * 실제 경과 시간에는 화면 잠금과 백그라운드 상태처럼 아무도 읽지 않은 시간이 포함되므로 시작·종료 시각에서
 * 계산하지 않고 활성 시간을 저장한다. 시작·종료 시각은 정렬과 표시를 위해 함께 보관한다.
 *
 * 현재는 이 값을 기록하는 곳이 없으므로([ReadingStatsRepository] 참고) 앱이 표시하는 모든 읽기 시간은 0이다.
 *
 * @property id 세션 자체의 식별자. 열린 세션은 진행에 따라 갱신하고 종료할 때 닫을 수 있다.
 * @property documentId 읽고 있는 문서.
 * @property startedAtEpochMillis 세션 시작 시각. 문서 이력을 최신순으로 정렬한다.
 * @property endedAtEpochMillis 종료 시각. 아직 열려 있으면 null이며, 이를 통해 충돌이나 강제 종료를 정상
 * 종료와 구분한다.
 * @property activeMillis 독자가 실제로 읽은 시간. 통계가 합산하는 값이다.
 * @property startLocation 문서에서 세션을 시작한 위치.
 * @property endLocation 종료한 위치. 세션이 열려 있으면 null.
 * @throws IllegalArgumentException [id]가 비어 있거나, 시각 중 하나가 음수이거나,
 * [endedAtEpochMillis]가 [startedAtEpochMillis]보다 앞서거나, [activeMillis]가 음수인 경우.
 */
data class ReadingSession(
    val id: String,
    val documentId: DocumentId,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val activeMillis: Long,
    val startLocation: ReaderLocation,
    val endLocation: ReaderLocation? = null,
) {
    init {
        require(id.isNotBlank()) { "ReadingSession id must not be blank." }
        require(startedAtEpochMillis >= 0L) { "startedAtEpochMillis must be positive." }
        require(endedAtEpochMillis == null || endedAtEpochMillis >= startedAtEpochMillis) {
            "endedAtEpochMillis must be after startedAtEpochMillis."
        }
        require(activeMillis >= 0L) { "activeMillis must be positive." }
    }
}

/**
 * 한 문서의 읽기 이력과 여기서 계산한 합계다.
 *
 * 합계는 저장된 행이 아니라 집계 결과이다. 읽기 시간은 세션을 합산하고, 글자와 단어 수는 문서 자체에서 가져온다.
 * 따라서 다시 파싱해 책 길이가 바뀌어도 통계 마이그레이션 없이 반영된다.
 *
 * **쓰기 쪽에는 호출자가 없다.** [recordSession]은 구현돼 데이터베이스까지 도달하지만 앱 어디에서도 세션을
 * 시작하거나 종료하거나 저장하지 않는다. 따라서 [observeSessions]는 항상 비어 있고 [getStats]의 활성
 * 시간은 항상 0이며, 문서 정보 화면은 그 0을 그대로 표시한다. 실제 경과 시간에서 유휴 구간을 빼
 * [ReadingSession.activeMillis]를 계산하던 로직도 같은 이유로 유스 케이스 계층과 함께 제거됐다. 이 기능을
 * 연결할 때 다시 작성하지 말고 Git 이력에서 복구해야 한다.
 */
interface ReadingStatsRepository {
    /**
     * 문서 하나의 읽기 이력을 관찰한다.
     *
     * @param documentId 세션을 관찰할 문서.
     * @return 세션을 최신순으로 제공하고 변경마다 다시 방출하는 플로우. 현재는 기록하는 곳이 없어 비어 있다.
     */
    fun observeSessions(documentId: DocumentId): Flow<List<ReadingSession>>

    /**
     * 세션을 저장하며 같은 식별자의 세션이 있으면 교체해 종료할 때 갱신할 수 있게 한다.
     *
     * @param session 저장할 세션.
     */
    suspend fun recordSession(session: ReadingSession)

    /**
     * 문서 하나의 읽기 합계를 계산한다.
     *
     * @param documentId 합계를 계산할 문서.
     * @return 활성 읽기 시간 합계와 책 자체의 글자 및 단어 수. 세션이 없는 문서의 시간은 0이며, 현재 모든 문서가
     * 이에 해당한다.
     */
    suspend fun getStats(documentId: DocumentId): ReadingStats
}

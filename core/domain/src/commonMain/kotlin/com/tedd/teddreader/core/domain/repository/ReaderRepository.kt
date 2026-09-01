package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderLocation
import kotlinx.coroutines.flow.Flow

/**
 * 한 문서에서 독자가 마지막으로 읽던 위치이며, 다음에 열 때 돌아갈 지점이다.
 *
 * 페이지 번호는 특정 화면의 특정 글자 크기에서만 의미가 있으므로 [location]을 중심으로 구성한다. 문서 자체
 * 텍스트의 절대 위치로 표현해야 글꼴 크기 변경, 다시 가져오기, 화면이 다른 기기에서도 위치를 유지할 수 있다.
 * [pageIndex]는 화면이 아직 레이아웃을 만들기 전에 번호를 표시할 수 있도록 함께 저장할 뿐이며, 두 값이 달라도
 * 된다. 재개할 때 사용하는 값은 [location]이다.
 *
 * 아직 페이지 나누기 결과가 없는 호출자는 페이지 번호를 오프셋인 것처럼 저장하지 말고 아예 저장하지 않아야
 * 한다. 실제 위치 위에 이를 기록하면 독자를 책의 첫 페이지로 돌려보낸다.
 *
 * @property documentId 이 위치가 속한 문서. 문서마다 위치 하나를 보관한다.
 * @property location 문서 자체 기준의 영속 위치([ReaderLocation] 참고). 재개할 때 이 값을 기준점으로 삼는다.
 * @property pageIndex 독자가 보던 당시 표시된 페이지. 진행률 표시에만 쓰고 재개에는 사용하지 않는다. `total`은
 * "당시 알려진 페이지"이며 이후 페이지 나누기 결과가 이를 넘을 수 있다.
 * @property updatedAtEpochMillis 이 위치를 기록한 시각. 값을 그대로 저장하며 **현재는 갱신하지 않는다**.
 * 리더의 저장 경로가 0을 전달하므로 "마지막으로 읽은" 순으로 책을 정렬하려면 먼저 이 값 기록을 시작해야 한다.
 * @throws IllegalArgumentException [updatedAtEpochMillis]가 음수인 경우.
 */
data class ReadingProgress(
    val documentId: DocumentId,
    val location: ReaderLocation,
    val pageIndex: PageIndex,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(updatedAtEpochMillis >= 0L) { "updatedAtEpochMillis must be positive." }
    }
}

/**
 * 모든 화면이 책이 열린 위치에 동의하도록 읽기 위치를 보관하는 단일 장소다.
 *
 * 리더는 페이지를 넘길 때마다 여기에 쓰고 책을 열 때 한 번 읽는다. 라이브러리와 문서 정보 화면도 같은 행을
 * 읽어 진행률을 표시한다. "이 책에서 내 위치"에는 답이 하나이고 위치 이력은 요구된 적이 없으므로 문서마다
 * 정확히 하나만 존재하며 [saveProgress]가 이를 교체한다.
 *
 * [getProgress]와 [observeProgress]의 null은 한 번도 열지 않은 책을 뜻하며 첫 페이지가 열린 책과는 다르다.
 * 호출자는 이 차이로 페이지 나누기에 기준으로 삼을 재개 위치가 있는지 판단한다.
 */
interface ReaderRepository {
    /**
     * 진행률이 변하는 동안 표시하는 화면을 위해 문서 하나의 읽기 위치를 관찰한다.
     *
     * @param documentId 관찰할 문서.
     * @return 현재 저장된 위치와 이후 변경마다 다시 방출하는 플로우. 이 책을 한 번도 열지 않았다면 null을 방출한다.
     */
    fun observeProgress(documentId: DocumentId): Flow<ReadingProgress?>

    /**
     * 책을 열어 레이아웃을 만들기 전에 문서 하나의 위치를 한 번 읽는다.
     *
     * @param documentId 열고 있는 문서.
     * @return 저장된 위치. 이 책을 한 번도 열지 않았다면 null이며, 이 경우 리더는 기준점이 아니라 처음부터 시작한다.
     */
    suspend fun getProgress(documentId: DocumentId): ReadingProgress?

    /**
     * 이 문서에 저장된 위치를 교체한다.
     *
     * @param progress 저장할 위치. [ReadingProgress.location]은 실제 페이지 나누기에서 나온 값이어야 한다.
     * 조작한 값을 저장하는 것이 아예 저장하지 않는 것보다 더 나쁜 이유는 [ReadingProgress]를 참고한다.
     */
    suspend fun saveProgress(progress: ReadingProgress)

    /**
     * 책의 위치를 잊어 다음에 열 때 처음부터 시작하게 한다.
     *
     * @param documentId 위치를 삭제할 문서.
     */
    suspend fun deleteProgress(documentId: DocumentId)
}

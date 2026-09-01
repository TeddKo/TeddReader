package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.data.mapper.toReadingProgress
import com.tedd.teddreader.core.data.mapper.toReadingProgressEntity
import com.tedd.teddreader.core.room.dao.ReadingProgressDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

/**
 * Room으로 뒷받침되는 [ReaderRepository]: 문서의 읽기 위치가 저장소에서 읽히거나 저장소에 쓰이는
 * 유일한 곳. 여기 있는 모든 메서드는 같은 두 가지 일을 한다 — [DocumentId]를 Room이 저장하는 평범한
 * 문자열 키로 변환하고, Room의 progress 엔티티와 도메인 [ReadingProgress] 사이를
 * [toReadingProgress]/[toReadingProgressEntity]로 변환하는 것 — 그래서 이 계층 위(뷰 모델, 유스
 * 케이스)의 어떤 것도 Room의 존재를 알 필요가 없다.
 *
 * @property progressDao 이 저장소가 모든 읽기/쓰기를 위임하는 DAO.
 */
@Single(binds = [ReaderRepository::class])
class ReaderRepositoryImpl(
    private val progressDao: ReadingProgressDao,
) : ReaderRepository {
    /**
     * [documentId]에 저장된 읽기 위치의 실시간 뷰. 다시 저장될 때마다 갱신된다.
     *
     * @param documentId 진행 상황을 관찰할 문서.
     * @return 현재 [ReadingProgress]에 대한 [Flow], 아직 아무것도 저장된 적이 없다면 `null`.
     */
    override fun observeProgress(documentId: DocumentId): Flow<ReadingProgress?> =
        progressDao.observeProgress(documentId.value).map { it?.toReadingProgress() }

    /**
     * [documentId]에 대해 마지막으로 저장된 읽기 위치. 관찰이 아니라 한 번만 읽는다.
     *
     * @param documentId 조회할 문서.
     * @return 저장된 [ReadingProgress], 또는 이 문서가 저장된 적이 없다면 `null`.
     */
    override suspend fun getProgress(documentId: DocumentId): ReadingProgress? =
        progressDao.getProgress(documentId.value)?.toReadingProgress()

    /**
     * [progress]를 그 문서의 현재 읽기 위치로 저장하며, 그 전에 저장되어 있던 것을 대체한다.
     *
     * @param progress 저장할 위치; `documentId`가 어느 문서의 행이 대체되는지를 결정한다.
     */
    override suspend fun saveProgress(progress: ReadingProgress) {
        progressDao.upsertProgress(progress.toReadingProgressEntity())
    }

    /**
     * [documentId]에 저장된 읽기 위치를 삭제한다. 예를 들어 그 문서 자체가 삭제되었을 때.
     *
     * @param documentId 저장된 진행 상황을 폐기할 문서.
     */
    override suspend fun deleteProgress(documentId: DocumentId) {
        progressDao.deleteProgress(documentId.value)
    }
}

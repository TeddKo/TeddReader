package com.tedd.teddreader.feature.document_info.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReadingStats
import com.tedd.teddreader.core.domain.repository.ReadingSession
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * [DocumentInfoViewModel]이 발행하고 [DocumentInfoScreen]이 렌더링하는 문서 정보 화면의 전체
 * 스냅샷이다. 어떤 문서를 설명하는지, 해당 문서에 관해 무엇을 알고 있는지, 이를 가져오는
 * 과정의 로딩 및 오류 상태를 담는다.
 *
 * [metadata], [pageIndex], [stats]는 한 번의 `GetDocumentInfoUseCase` 호출에서 함께 도착하며,
 * 해당 로드의 [documentId]가 이 상태에 요청된 문서와 여전히 일치할 때만 적용된다. [sessions]는
 * 별도로 관찰하는 자체 스트림에서 오므로 세션이 계속 기록되는 문서는 나머지 스냅샷을 다시
 * 로드하지 않고 [sessions]만 갱신될 수 있다.
 *
 * @property documentId 이 상태가 설명하는 문서다. `DocumentInfoViewModel.setDocument`가 호출되는
 *   즉시 아래 필드가 로드되기 전에 설정되며, 이미 로드한 문서의 재로드를 막는 데 사용된다.
 * @property metadata 저장된 문서 메타데이터다. 최초 로드가 완료되기 전이거나 실패한 경우 null이다.
 * @property pageIndex 마지막으로 저장된 문서 읽기 위치다. 아직 저장된 위치가 없으면 null이다.
 * @property stats 문서의 집계된 읽기 합계다. 최초 로드가 완료되기 전이거나 실패한 경우 null이다.
 * @property sessions 기반 스트림이 내보내는 순서로 정렬된 문서의 개별 읽기 세션이다.
 * @property isLoading 최초 메타데이터, 페이지, 통계 로드가 성공 여부와 관계없이 끝날 때까지
 *   true다. 별도 스트림에서 자체적으로 로드되는 [sessions]의 영향은 받지 않는다.
 * @property errorMessage 최초 로드 또는 세션 스트림이 가장 최근에 실패했으면 null이 아니다.
 */
@Immutable
data class DocumentInfoUiState(
    val documentId: String = "",
    val metadata: DocumentMetadata? = null,
    val pageIndex: PageIndex? = null,
    val stats: ReadingStats? = null,
    val sessions: ImmutableList<ReadingSession> = persistentListOf(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

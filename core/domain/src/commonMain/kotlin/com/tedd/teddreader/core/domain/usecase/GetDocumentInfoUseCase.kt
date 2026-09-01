package com.tedd.teddreader.core.domain.usecase

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.ReadingStats
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReadingStatsRepository

/**
 * 문서 정보 화면이 문서 하나에 관해 표시하는 모든 내용을 하나의 결과로 모은다.
 *
 * @property metadata 라이브러리에 저장된 문서 기록. 해당 식별자로 저장된 것이 없으면 null.
 * @property pageIndex 리더가 마지막으로 본 페이지. 한 번도 열지 않은 책은 null. 표시에만 사용하며 재개할 때는
 * 저장된 위치를 사용한다(ReadingProgress 참고).
 * @property stats 읽기 합계. null이 아니며 기록된 세션이 없는 문서에서는 활성 시간이 0이다. 현재 모든 문서가
 * 이에 해당한다.
 */
data class DocumentInfo(
    val metadata: DocumentMetadata?,
    val pageIndex: PageIndex?,
    val stats: ReadingStats,
)

/**
 * 답의 3분의 1씩을 보유한 세 공급원을 조합해 "이 문서는 무엇인가"에 답한다.
 *
 * 이 구성 요소가 없으면 호출자는 함께 있어야만 의미가 있는 세 번의 읽기를 위해 저장소 세 개를 주입해야 하며,
 * 그중 두 개는 각각 정확히 한 호출 지점에서만 사용했다. 여기서 조합하면 화면은 조합된 읽기를 위한 협력자 하나만
 * 갖는다. 저장소 메서드를 그대로 반복하는 시그니처의 유스 케이스라면 이 프로젝트가 이미 6개 제거한 단순
 * 단순 전달이었겠지만, 이는 계층 자체를 위한 추가가 아니라 실제 의존성 축소다.
 *
 * 읽기는 순차 실행하고 예외는 전파하므로 하나라도 실패하면 호출자는 어느 결과도 적용하지 않는다. 화면은 절반만
 * 채운 패널 대신 오류를 표시한다.
 *
 * 세 저장소를 받는 최상위 함수로 만들면 세 저장소가 모두 호출자의 생성자에 남아 아무것도
 * 축소되지 않으므로 주입할 수 있는 클래스로 만든다.
 *
 * @property documentRepository 라이브러리 기록의 출처.
 * @property readerRepository 마지막으로 표시한 페이지의 출처.
 * @property readingStatsRepository 읽기 합계의 출처.
 */
class GetDocumentInfoUseCase(
    private val documentRepository: DocumentRepository,
    private val readerRepository: ReaderRepository,
    private val readingStatsRepository: ReadingStatsRepository,
) {
    /**
     * @param documentId 설명할 문서.
     * @return 메타데이터, 마지막으로 표시한 페이지, 읽기 합계.
     * @throws Throwable 세 읽기 중 하나가 던지는 모든 예외. 어떤 값도 부분적으로 적용하지 않는다.
     */
    suspend operator fun invoke(documentId: DocumentId): DocumentInfo = DocumentInfo(
        metadata = documentRepository.getDocument(documentId),
        pageIndex = readerRepository.getProgress(documentId)?.pageIndex,
        stats = readingStatsRepository.getStats(documentId),
    )
}

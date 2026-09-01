package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ViewportSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 앱에 전달되어 곧 가져올 문서로, 문서의 출처와 이미 메모리에 있는 경우 그 바이트를 담는다.
 *
 * [bytes]는 메모리에 올라간 책 전체이며 값 동등성 비교 시 수 메가바이트를 비교하게 되므로 데이터 클래스가 아니다.
 *
 * @property location 문서의 출처이자 형식 감지에 필요한 유일한 값. 콘텐츠가 아니라 이름과 MIME 유형으로
 * 형식을 판별한다.
 * @property bytes 호출자가 이미 보유한 문서 콘텐츠이며, null은 "[location]에서 직접 읽음"을 뜻한다.
 * EPUB과 CBZ에서는 null이 일반적이다. 둘 다 파서가 열고 탐색하는 ZIP 압축 파일이므로, 여기서 전체를 메모리에 읽은 뒤
 * 임시 파일에 다시 쓰는 것은 가장 큰 책에서 작업만 낭비한다.
 * @throws IllegalArgumentException [bytes]가 있지만 비어 있어 읽을 수 없는 문서가 읽을 수 있는 것처럼 전달된 경우.
 */
class DocumentImportSource(
    val location: DocumentLocation,
    val bytes: ByteArray?,
) {
    init {
        require(bytes == null || bytes.isNotEmpty()) { "Document bytes must not be empty." }
    }
}

/**
 * [DocumentRepository.importNextSections]의 진행 결과로, 호출자가 다음 단계를 실행할지 판단할 수 있게 한다.
 *
 * @property isComplete 문서 가져오기가 완료되었는지 여부. `documents.importCompletedAtEpochMillis`가
 * null이 아니게 되는 것과 같은 조건이다.
 * @property sectionsImported 이 호출 한 번으로 실제 파싱하고 저장한 새 섹션 수. [isComplete]가 true이면서
 * 0이면 가져오기가 이미 끝났거나, 해당 문서 형식이 가져오기를 여러 단계로 나누지 않는다는 뜻이다.
 */
data class ImportProgress(
    val isComplete: Boolean,
    val sectionsImported: Int,
)

/**
 * 라이브러리 목록, 책 한 권의 파싱된 텍스트, 리더가 그리는 페이지 레이아웃 등 앱이 문서에 관해 아는 모든 것을
 * 제공한다.
 *
 * 읽기 경로마다 비용 차이가 매우 크므로 의도적으로 분리한다. [observeRecentDocuments]와 [getDocument]는
 * 메타데이터만 다루므로 서가의 책을 나열할 때 본문을 불러오지 않는다. [getReaderDocument]는 책 본문을
 * 불러온다. [getPageWindows]는 측정을 수행하는 고비용 경로이며, 그 형태 전체가 중복 측정을 피하도록 설계됐다.
 *
 * 오래 실행되는 두 작업은 이 인터페이스가 소유하는 백그라운드 작업이 아니라 "한 단계 진행" 호출로 노출한다.
 * 아직 파싱 중인 책에는 [importNextSections]를, 아직 측정 중인 스타일에는 [continuePagination]을 사용한다.
 * 화면은 자체 코루틴 범위에서 이들을 구동하므로 리더를 나가면 멈추고, 다음에 열 때 저장된 지점부터 재개한다.
 * 별도 하위 시스템도 없고, 작업을 원한 화면보다 오래 살아남는 작업도 없다. 호출자는 [isImportComplete]와
 * [isPaginationComplete]로 계속 단계를 진행할지 판단한다.
 *
 * 여기의 기본 구현은 이런 작업이 없는 저장소, 즉 테스트용 가짜 구현이나 가져오기와 페이지 나누기가 한 번에 끝나는 형식을
 * 위한 응답이다. 따라서 "할 일 없음"과 "이 문서가 아님"은 모두 오류 대신 `isComplete = true`를 반환한다.
 */
interface DocumentRepository {
    /**
     * 라이브러리 피드다.
     *
     * @return 가져온 모든 문서를 최근에 연 순으로 제공하고, 변경될 때마다 다시 방출하는 플로우. 아직 한 번도 읽지 않은
     * 새 책도 맨 위에 표시되도록 연 시간이 없으면 추가 시간으로 정렬한다.
     */
    fun observeRecentDocuments(): Flow<List<DocumentMetadata>>

    /**
     * 본문을 건드리지 않고 문서 하나의 메타데이터를 읽는다.
     *
     * @param documentId 읽을 문서.
     * @return 해당 메타데이터. 이 식별자로 가져온 문서가 없으면 null.
     */
    suspend fun getDocument(documentId: DocumentId): DocumentMetadata?

    /**
     * 라이브러리 목록에서 이미지를 불러오지 않도록 [getDocument]와 분리된 문서 표지를 읽는다.
     *
     * @param documentId 표지를 읽을 문서.
     * @return 인코딩된 이미지 바이트. 문서에 표지가 없으면 null이며, 표지를 담을 수 없는 모든 형식도 포함한다.
     */
    suspend fun getDocumentCover(documentId: DocumentId): ByteArray? = null

    /**
     * PDF, CBZ, 단일 이미지처럼 재배치 가능한 텍스트가 전혀 없는 형식의 페이지 이미지를 읽는다.
     *
     * 이미지는 텍스트보다 몇 자릿수 이상 크므로 책 단위가 아니라 페이지 단위로 요청한다. 리더는 곧 그릴 범위를
     * 요청한다.
     *
     * @param documentId 이미지를 읽을 문서.
     * @param pageIndexes 원하는 0 기반 페이지들.
     * @return 페이지 인덱스별 인코딩된 이미지 바이트. 이미지가 없는 페이지는 제외한다.
     */
    suspend fun getVisualPageImages(
        documentId: DocumentId,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> = emptyMap()

    /**
     * 재배치 가능한 텍스트 내부에서 참조한 이미지를 문서 자체가 사용한 참조 경로로 읽는다.
     *
     * [getVisualPageImages]와 같은 이유다. 삽화가 있는 책의 이미지는 텍스트보다 훨씬 크므로 문서와 함께가 아니라
     * 화면에 표시되는 페이지에 맞춰 가져온다.
     *
     * @param documentId 이미지를 읽을 문서.
     * @param hrefs 문서 자체 블록에 나타나는 이미지 경로.
     * @return 참조 경로별 인코딩된 이미지 바이트. 컨테이너에 없는 항목은 제외한다.
     */
    suspend fun getEmbeddedImages(
        documentId: DocumentId,
        hrefs: Set<String>,
    ): Map<String, ByteArray> = emptyMap()

    /**
     * 내장 EPUB 글꼴 파일을 문서 자체가 사용한 참조 경로별로 재사용 가능한 로컬 파일 경로로 변환한다.
     *
     * [getEmbeddedImages]와 달리 바이트 대신 파일 경로를 반환한다. 렌더러와 페이지 구분기에는 나중에 플랫폼
     * 텍스트 스택이 열 수 있는 대상만 필요하며, 전체 글꼴 바이트 배열을 사용자 인터페이스 상태에 두면 이득 없이 큰 바이너리
     * 데이터를 메모리에 붙잡아 두기 때문이다.
     *
     * @param documentId 글꼴을 읽을 문서.
     * @param hrefs 문서 자체의 블록/범위 스타일에 나타나는 글꼴 경로.
     * @return 참조 경로별 재사용 가능한 로컬 파일 경로. 컨테이너에 없는 참조 경로는 제외한다.
     */
    suspend fun getEmbeddedFontFiles(
        documentId: DocumentId,
        hrefs: Set<String>,
    ): Map<String, String> = emptyMap()

    /**
     * 리더가 읽을 파싱된 문서, 즉 섹션과 텍스트, 스타일을 지정하는 블록 구조를 불러온다.
     *
     * @param documentId 불러올 문서.
     * @return 지금까지 파싱된 문서. 점진적으로 가져오는 책은 배치 사이에 내용이 늘어나므로 리더가 가져오기 배치마다
     * 다시 읽는다. 해당 식별자로 저장된 문서가 없으면 null.
     */
    suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument?

    /**
     * [documentId]를 [style]과 [viewportSize]에 맞춰 페이지로 배치한다. 리더가 화면을 구성할 때 사용하는
     * 호출이며, 텍스트를 측정하는 유일한 호출이다.
     *
     * 저장된 측정값은 항상 재측정보다 우선하므로 정확히 같은 글꼴 설정과 뷰포트로 디스크에 저장된 레이아웃,
     * 새 측정 순으로 해석한다. 실제 [pageBreaker]가 없는 호출자도 추정값 대신 저장된 결과를 받는 이유다.
     *
     * 책 전체를 새로 측정하는 비용은 실제 기기에서 204개와 528개 섹션 책에 각각 6.4초와 13.0초로 눈에 띌 만큼
     * 크다. 따라서 첫 단계에서는 리더가 머무는 섹션만 측정하고 지금까지 알려진 페이지라고 정확히 표시한 그
     * 페이지들만 반환한다. [continuePagination]이 그 지점부터 바깥쪽으로 확장하며, 도달하지 않은 섹션을 대신한
     * 추정값으로 무언가를 구성하지 않는다.
     *
     * @param documentId 배치할 문서.
     * @param style 읽기 스타일. 레이아웃에 영향을 주는 필드만 결과를 바꾼다(`ReaderStyle.layoutKey` 참고).
     * @param viewportSize 페이지가 배치될 상자. 호출자에게 아직 화면 영역 측정값이 없으면 null이다. 이 경우 거의 항상
     * 저장된 값과 다른 추정 뷰포트로 페이지를 나누는 대신, 이 글꼴 설정으로 어떤 뷰포트에서든 가장 최근에
     * 저장된 레이아웃을 반환한다.
     * @param pageBreaker 측정에 사용할 실제 텍스트 레이아웃. 이미 저장된 결과만 원하는 호출자는 null이다.
     * @param anchorOffset 리더가 머무는 절대 오프셋으로, 첫 단계에서 어느 섹션을 측정할지 결정한다. null이면 첫
     * 콘텐츠 섹션에 고정하며, 재개할 위치가 없는 새로 가져온 책은 여기서 시작한다.
     * @return 이 문서의 해당 글꼴 설정과 뷰포트에서 알려진 페이지를 읽기 순서로 반환한다. 재배치 가능한 텍스트가
     * 없거나 저장된 내용이 전혀 없는 문서는 비어 있다.
     */
    suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: ReaderPageBreaker? = null,
        anchorOffset: Long? = null,
        viewportDensity: Float = 1f,
    ): List<PageWindow>

    /**
     * [style]로 가장 최근에 저장된 레이아웃을 측정한 뷰포트를 알려준다. null 뷰포트에 대해
     * [getPageWindows]가 내부적으로 수행하는 것과 같은 해석을 노출하여, 이를 요청한 호출자가 결과를 알고
     * 채택할 수 있게 한다.
     *
     * @param documentId 조회할 문서.
     * @param style 레이아웃에 영향을 주는 필드(글꼴 크기, 줄 높이, 글꼴 모음)를 맞출 스타일.
     * @return 해당 뷰포트. 이 글꼴 설정으로 저장된 레이아웃이 없으면 null.
     */
    suspend fun resolveViewportSizeForStyle(documentId: DocumentId, style: ReaderStyle): ViewportSize? = null

    /**
     * [getPageWindows]나 [getReaderDocument]가 가장 최근에 불러온 문서에서 [sectionIndexes]의 블록
     * 구조를 디코딩하며, 아직 디코딩하지 않은 섹션만 가져온다.
     *
     * 디코딩하지 않은 섹션으로 구성한 페이지는 들여쓰기나 제목 없이 일반 텍스트로 렌더링된다. 따라서 페이지를
     * 게시하려는 호출자는 먼저 그 페이지의 섹션을 준비한다.
     *
     * @param documentId 섹션이 속한 문서. 다른 문서면 아무 작업도 하지 않는다.
     * @param sectionIndexes 디코딩할 섹션.
     * @return 이 호출이 실제로 디코딩한 섹션 수. [documentId]가 불러온 문서와 다르거나, 저장소에서 문서를
     * 불러오지 않았거나, 요청한 모든 섹션이 이미 디코딩됐다면 0이다. 따라서 호출자는 변경이 없을 때 다시 게시하지
     * 않을 수 있다.
     */
    suspend fun warmSectionBlocks(documentId: DocumentId, sectionIndexes: Set<Int>): Int = 0

    /**
     * [documentId]의 블록이나 범위가 문서 전체에서 참조하는 모든 내장 글꼴 참조 경로다.
     *
     * 이를 통해 리더는 페이지가 마운트될 때마다 페이지 범위별로 글꼴을 발견하는 대신 책의 글꼴을 처음에 한 번만
     * 해석할 수 있다. 글꼴을 발견할 때마다 글꼴 의존 레이아웃 키가 바뀌고 책 전체를 재측정했으며, 이는 페이지의
     * 글꼴 설정이 표시 중에 바뀔 때 독자가 보는 깜빡임과 잘림의 연쇄를 정확히 일으켰다.
     *
     * @param documentId 검사할 문서. 현재 불러온 문서가 아니면 빈 집합을 반환한다.
     * @return 문서 어디에서든 참조하는 서로 다른 내장 글꼴 참조 경로들.
     */
    suspend fun getReferencedEmbeddedFontHrefs(documentId: DocumentId): Set<String> = emptySet()

    /**
     * [source]가 가리키는 대상을 가져오고 리더가 열 수 있는 문서를 반환한다.
     *
     * 라이브러리에 이미 있는 책을 열 때 다시 가져오면 안 된다. 다시 가져오면 저장된 텍스트와 페이지 레이아웃을
     * 버리고 전체 가져오기 비용을 다시 지불한다. 528개 챕터로 된 책에서는 즉시 열리는 것과 기다리는 것의 차이다.
     * 완료되지 않은 가져오기는 다르다. 처음부터 다시 시작하지 않고 [importNextSections]가 중단된 지점부터 이어간다.
     *
     * @param source 문서 위치와 선택적인 문서 바이트.
     * @param importedAtEpochMillis 가져온 시각. 책을 처음 열기 전까지 라이브러리 순서를 정한다.
     * @return 파싱된 문서. 점진적으로 가져오는 형식에서는 첫 단계만 담긴다.
     */
    suspend fun importDocument(source: DocumentImportSource, importedAtEpochMillis: Long): ReaderDocument

    /**
     * 편집한 메타데이터를 다시 기록한다. 라이브러리의 이름 변경, 즐겨찾기, 폴더 동작이 모두 이곳에 반영된다.
     *
     * @param document 저장할 전체 메타데이터 행. 여기에 없는 필드도 덮어쓰므로 호출자는 읽어 온 값의 복사본을
     * 편집한다.
     */
    suspend fun upsertDocument(document: DocumentMetadata)

    /**
     * [documentIds]의 북마크 여부를 한 번의 배치로 다시 기록한다.
     *
     * 기본 구현은 식별자를 순회하며 실제 변경이 필요한 행만 다시 기록해 기존 가짜 구현이 계속 동작하게 한다. 실제
     * 저장소는 하나의 SQL 갱신문으로 재정의한다.
     *
     * @param documentIds 다시 기록할 문서들.
     * @param isBookmarked 적용할 북마크 상태.
     */
    suspend fun setDocumentsBookmarked(documentIds: Collection<DocumentId>, isBookmarked: Boolean) {
        documentIds.forEach { documentId ->
            val document = getDocument(documentId) ?: return@forEach
            if (document.isBookmarked != isBookmarked) {
                upsertDocument(document.copy(isBookmarked = isBookmarked))
            }
        }
    }

    /**
     * [documentIds]의 폴더 소속을 한 번의 배치로 다시 기록한다.
     *
     * 모든 호출자가 저장 모델과 같은 불변 조건을 적용받도록 여기서 폴더 쌍을 검증한다. 두 값이 모두 있거나 모두
     * 없어야 한다. 기본 구현은 각 문서를 읽고 복사본을 다시 기록해 기존 가짜 구현이 계속 동작하게 하며, 실제 저장소는
     * 하나의 SQL 갱신문으로 재정의한다.
     *
     * @param documentIds 이동할 문서들.
     * @param folderId 문서를 옮길 폴더. 폴더에서 빼려면 null.
     * @param folderName 해당 폴더의 표시 이름. [folderId]가 있을 때만 존재한다.
     */
    suspend fun setDocumentsFolder(
        documentIds: Collection<DocumentId>,
        folderId: String?,
        folderName: String?,
    ) {
        require((folderId == null) == (folderName == null)) {
            "folderId and folderName must both be null or both be non-null."
        }
        require(folderId == null || folderId.isNotBlank()) { "folderId must not be blank." }
        require(folderName == null || folderName.isNotBlank()) { "folderName must not be blank." }
        documentIds.forEach { documentId ->
            val document = getDocument(documentId) ?: return@forEach
            if (document.folderId != folderId || document.folderName != folderName) {
                upsertDocument(document.copy(folderId = folderId, folderName = folderName))
            }
        }
    }

    /**
     * 현재 [folderId]에 속한 모든 문서의 폴더 이름을 바꾼다.
     *
     * 기존 메모리 가짜 구현도 올바르게 동작하도록 기본 구현은 활성 라이브러리 플로우를 사용하며, 실제 저장소는 하나의
     * SQL 갱신문으로 재정의할 수 있다.
     *
     * @param folderId 이름을 바꿀 폴더.
     * @param folderName 새 표시 이름.
     */
    suspend fun renameFolder(folderId: String, folderName: String) {
        require(folderId.isNotBlank()) { "folderId must not be blank." }
        require(folderName.isNotBlank()) { "folderName must not be blank." }
        val members = observeRecentDocuments().first().filter { it.folderId == folderId }.map(DocumentMetadata::id)
        setDocumentsFolder(members, folderId, folderName)
    }

    /**
     * 현재 모든 구성원에서 해당 소속을 지워 [folderId]를 제거한다.
     *
     * 플로우만 노출하는 테스트 대역도 계속 동작하도록 기본 구현은 [renameFolder]와 같은 일회성 관찰 방식을
     * 사용한다.
     *
     * @param folderId 비울 폴더.
     */
    suspend fun clearFolder(folderId: String) {
        require(folderId.isNotBlank()) { "folderId must not be blank." }
        val members = observeRecentDocuments().first().filter { it.folderId == folderId }.map(DocumentMetadata::id)
        setDocumentsFolder(members, null, null)
    }

    /**
     * 문서를 열었다고 기록하며, 이 값으로 라이브러리 순서를 다시 정한다.
     *
     * @param documentId 연 문서.
     * @param openedAtEpochMillis 문서를 연 시각.
     */
    suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long)

    /**
     * 문서와 여기서 파생된 모든 것을 제거한다. 텍스트, 검색 인덱스, 저장한 위치, 진행률, 측정된 페이지 레이아웃도
     * 모두 함께 제거한다.
     *
     * @param documentId 제거할 문서.
     */
    suspend fun deleteDocument(documentId: DocumentId)

    /**
     * 실제 저장소가 더 효율적으로 처리할 수 없는 경우 [deleteDocument]로 하나씩 [documentIds]를 제거한다.
     *
     * @param documentIds 제거할 문서들.
     */
    suspend fun deleteDocuments(documentIds: Collection<DocumentId>) {
        for (documentId in documentIds) deleteDocument(documentId)
    }

    /**
     * 점진적으로 가져오는 문서(현재는 EPUB만 해당)의 남은 스파인 항목을 스파인 순서대로 최대 [count]개 더
     * 파싱하고 저장한다.
     *
     * 추가 작업은 저장된 내용에 덧붙이기만 하고 이미 게시된 오프셋을 옮기지 않으므로 리더가 보고 있는 페이지의
     * 텍스트가 유지된다. [pageBreaker]가 실제 값이고 이 글꼴 설정과 뷰포트의 레이아웃이 이미 저장돼 있으면
     * 새 섹션도 측정하고 페이지 시작점을 덧붙여, 이미 표시된 페이지의 정확한 경계가 유지된다.
     *
     * @param documentId 진행할 문서.
     * @param count 이 단계에서 파싱할 최대 섹션 수.
     * @param style 현재 읽기 스타일. 새 섹션도 측정할 때만 사용한다.
     * @param viewportSize 현재 뷰포트. 새 섹션도 측정할 때만 사용한다.
     * @param pageBreaker 실제 텍스트 레이아웃. 측정 없이 파싱하려면 null.
     * @return 가져오기 진행 결과로, [ImportProgress]를 참고한다. 가져오기가 이미 끝난 문서나 가져오기를 여러 단계로
     * 나누지 않는 형식에서는 `isComplete = true`인 채 아무 작업도 하지 않는다.
     */
    suspend fun importNextSections(
        documentId: DocumentId,
        count: Int,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float = 1f,
    ): ImportProgress = ImportProgress(isComplete = true, sectionsImported = 0)

    /**
     * 문서가 완전히 파싱되었는지를 나타낸다.
     *
     * @param documentId 상태를 확인할 문서.
     * @return 가져오기가 끝났으면 true. 애초에 여러 단계로 나누지 않은 문서도 포함한다. 앞으로 생길 섹션이 아직
     * 파싱되지 않은 동안에만 false다.
     */
    suspend fun isImportComplete(documentId: DocumentId): Boolean = true

    /**
     * [getPageWindows]가 시작했지만 한 번의 호출로 끝내지 못한 점진적 페이지 나누기를 계속하여, 제한된 다음
     * 배치를 실제로 측정한다.
     *
     * 순회는 리더가 재개한 섹션부터 바깥쪽으로 확장한다. 먼저 섹션 0까지 뒤로 진행해 재개한 페이지 자체의
     * 번호가 더 움직이지 않게 하고, 마지막 섹션까지 앞으로 진행해 전체 페이지 수를 늘린다.
     *
     * @param documentId 측정 중인 문서.
     * @param style 진행 중인 측정이 속한 스타일. 다른 스타일에는 계속할 작업이 없다.
     * @param viewportSize 해당 측정이 속한 뷰포트.
     * @param pageBreaker 측정에 사용할 실제 텍스트 레이아웃. null이면 아무 작업도 하지 않는다.
     * @return 측정 진행 결과로, [PaginationProgress]를 참고한다. 실제 페이지 구분기가 없거나, 다른 문서이거나, 순회가
     * 이미 끝나 측정할 것이 없으면 `isComplete = true`인 채 아무 작업도 하지 않는다.
     */
    suspend fun continuePagination(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float = 1f,
    ): PaginationProgress = PaginationProgress(isComplete = true, sectionsMeasured = 0)

    /**
     * [getPageWindows]가 가장 최근에 점진적으로 측정한 글꼴 설정과 뷰포트에서 모든 콘텐츠 섹션에 실제
     * 측정값이 있는지를 나타낸다.
     *
     * @param documentId 상태를 확인할 문서.
     * @return 측정이 끝났거나 필요하지 않았으면 true. 저장소에서 복원했거나, 한 번의 호출로 전체를 측정했거나,
     * 저장소가 페이지 나누기를 여러 단계로 나누지 않는 경우다. **[isImportComplete]가 false인 동안에는 절대
     * true가 아니다.** 책에 생길 섹션이 아직 파싱 중이므로 지금까지 파싱한 내용의 측정은 책 전체의 측정이 아니다.
     * [continuePagination]을 계속 실행할지 판단하려고 이를 호출한 호출자는 가져오기가 진행되는 동안 계속 실행해야
     * 한다.
     */
    suspend fun isPaginationComplete(documentId: DocumentId): Boolean = true
}

/**
 * [DocumentRepository.continuePagination]의 진행 결과로, 호출자가 다음 단계를 실행할지 판단할 수 있게 한다.
 *
 * @property isComplete 측정이 완료되었는지 여부. [DocumentRepository.isPaginationComplete]가 true가 되는
 * 것과 같은 조건이다.
 * @property sectionsMeasured 이 호출 한 번으로 실제 측정한 섹션 수. [isComplete]가 true이면서 0이면 측정할
 * 내용이 남지 않았거나, 측정에 사용할 실제 [ReaderPageBreaker]가 없었다는 뜻이다.
 */
data class PaginationProgress(
    val isComplete: Boolean,
    val sectionsMeasured: Int,
)

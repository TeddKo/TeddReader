package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.suspendRunCatching
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderObjectReplacementChar
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderNavigation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderLayoutKey
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.layoutKey
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import com.tedd.teddreader.core.common.model.rebasedBy
import com.tedd.teddreader.core.common.model.wordCount
import com.tedd.teddreader.core.data.mapper.CurrentReaderParserVersion
import com.tedd.teddreader.core.data.mapper.toDocumentEntity
import com.tedd.teddreader.core.data.mapper.toDocumentMetadata
import com.tedd.teddreader.core.data.mapper.toSearchIndexEntity
import com.tedd.teddreader.core.data.pagination.RestoredPageWindows
import com.tedd.teddreader.core.data.pagination.SectionPageStarts
import com.tedd.teddreader.core.data.pagination.ReaderPageMeasureDispatcher
import com.tedd.teddreader.core.data.pagination.TextPageLayoutEngine
import com.tedd.teddreader.core.data.parser.ComicBookDocumentParser
import com.tedd.teddreader.core.data.parser.ComicArchive
import com.tedd.teddreader.core.data.parser.DocumentFormatDetector
import com.tedd.teddreader.core.data.parser.EpubDocumentParser
import com.tedd.teddreader.core.data.parser.EpubImportContainer
import com.tedd.teddreader.core.data.parser.EpubParsedSection
import com.tedd.teddreader.core.data.parser.ImageDocumentParser
import com.tedd.teddreader.core.data.parser.PdfDocumentParser
import com.tedd.teddreader.core.data.parser.SectionSeparatorLength
import com.tedd.teddreader.core.data.parser.TxtDocumentParser
import com.tedd.teddreader.core.data.parser.TxtTextDecoder
import com.tedd.teddreader.core.data.parser.buildEpubCoverSection
import com.tedd.teddreader.core.data.parser.fillIntrinsicImageSizes
import com.tedd.teddreader.core.data.parser.openEpubImportContainer
import com.tedd.teddreader.core.data.parser.parseEpubSpineItem
import com.tedd.teddreader.core.data.parser.resolveEpubNavigationAtCompletion
import com.tedd.teddreader.core.data.parser.systemFileSystem
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ImportProgress
import com.tedd.teddreader.core.domain.repository.PaginationProgress
import com.tedd.teddreader.core.room.dao.DocumentDao
import com.tedd.teddreader.core.room.dao.PageLayoutDao
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import com.tedd.teddreader.core.room.dao.SectionSourcePathEntry
import com.tedd.teddreader.core.room.dao.SectionTitleUpdate
import com.tedd.teddreader.core.room.entity.DocumentEntity
import com.tedd.teddreader.core.room.entity.PageLayoutEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.TimeSource
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip
import okio.buffer
import org.koin.core.annotation.Single

/**
 * 유일한 [DocumentRepository] 구현체: 임포트된 파일을 [ReaderDocument]로 변환하고, 이를
 * [documentDao](서가 메타데이터), [searchIndexDao](섹션별 텍스트/블록/검색 인덱스),
 * [pageLayoutDao](측정된 페이지 시작 레이아웃)에 걸쳐 영속화하며, 아래에서 각 필드와 함께 문서화된
 * 소규모 인메모리 캐시 집합을 통해 다시 제공한다. 포맷 감지와 포맷별 파싱은 [formatDetector]와
 * `*DocumentParser`들에 위임되며, 텍스트는 [textPageLayoutEngine]에 의해 페이지로 레이아웃된다.
 * EPUB 임포트는 점진적으로 이루어지므로(자세한 내용은 [importEpubPhase0]와 [importNextSections]
 * 참고), 이 클래스는 진행 중인 페이지네이션 세션과 현재 디스크에서 읽고 있는 EPUB의 스크래치
 * 사본도 함께 추적한다.
 *
 * @property documentDao 서가 수준 메타데이터: 제목, 포맷, 타임스탬프, 즐겨찾기/폴더 상태, 그리고
 *   점진적 EPUB 임포트가 실제로 완료되기 전까지 설정되지 않는 `importCompletedAtEpochMillis`
 *   스탬프([isImportComplete] 참고).
 * @property searchIndexDao 섹션별 저장소: 텍스트, 문자 범위, 디코딩된 블록 JSON, 그리고 섹션 0에
 *   실려 있는 문서 수준 내비게이션/제목([getStoredSections] 참고).
 * @property pageLayoutDao 스타일과 뷰포트를 키로 하는 측정된 페이지 시작 레이아웃
 *   ([restorePageWindows]/[storePageWindows] 참고).
 * @property formatDetector [DocumentImportSource]의 위치/바이트로부터 [DocumentFormat]을 판별한다.
 * @property txtDocumentParser 일반 텍스트 임포트와 TXT 복구를 파싱한다.
 * @property epubDocumentParser EPUB 임포트와 복구를 파싱하며, OPF가 없는 책에 사용되는 비점진적
 *   폴백 챕터 경로도 포함한다.
 * @property pdfDocumentParser PDF 임포트를 파싱하고 PDF 표지를 추출한다.
 * @property comicBookDocumentParser CBZ 임포트, 표지, 페이지별 이미지를 파싱한다.
 * @property imageDocumentParser 단일 이미지 임포트를 파싱한다.
 * @property textPageLayoutEngine 섹션들을 [PageWindow]로 레이아웃하고, 저장된 레이아웃을 재측정
 *   없이 다시 윈도우로 재구성한다.
 * @property documentFileSource [DocumentMetadata.location]에 대한 원본 파일 바이트를 읽거나
 *   복사한다. 플랫폼이 파일 접근을 전혀 제공하지 않으면(일부 테스트 포함) null이며, 이를 필요로
 *   하는 모든 경로는 null일 때 예외를 던지는 대신 빈 결과를 반환하는 쪽으로 완화되어 있다. 단,
 *   점진적 EPUB 임포트는 예외로 이 값을 반드시 필요로 한다.
 */
@Single([DocumentRepository::class])
class DocumentRepositoryImpl(
    private val documentDao: DocumentDao,
    private val searchIndexDao: SearchIndexDao,
    private val pageLayoutDao: PageLayoutDao,
    private val formatDetector: DocumentFormatDetector,
    private val txtDocumentParser: TxtDocumentParser,
    private val epubDocumentParser: EpubDocumentParser,
    private val pdfDocumentParser: PdfDocumentParser,
    private val comicBookDocumentParser: ComicBookDocumentParser,
    private val imageDocumentParser: ImageDocumentParser,
    private val textPageLayoutEngine: TextPageLayoutEngine,
    private val documentFileSource: DocumentFileSource?,
) : DocumentRepository {
    /** 텍스트 컬럼으로 저장되는 블록 목록과 내비게이션을 인코딩/디코딩할 때 사용하는 JSON 코덱. */
    private val json = Json

    /**
     * 이 클래스의 캐시 적중/측정/임포트 진단을 위해 `"Pagination"` 태그가 붙은 구조화 로거.
     */
    private val logger = Logger.withTag("Pagination")

    /**
     * 문서 표지 이미지에 관한 모든 것 — 캐시 파일과, 아직 캐시된 것이 없는 EPUB나 PDF에서 표지를
     * 추출하는 작업 — 을 담당한다. 이것이 이 클래스에 흩어진 네 개의 메서드가 아니라 별도의
     * 협력 객체인 이유는 [DocumentCoverStore] 문서를 참고.
     *
     * CBZ 표지는 예외로, 여기 [getDocumentCover]에 그대로 남아 있다. 만화의 표지는 페이지 요청이
     * 읽는 것과 동일한 뮤텍스로 보호되는 슬롯인 [cbzArchive]에서 나오므로, 그 락을 함께 옮기지
     * 않는 한 이동시킬 수 없다.
     */
    private val coverStore = DocumentCoverStore(
        epubDocumentParser = epubDocumentParser,
        pdfDocumentParser = pdfDocumentParser,
        documentFileSource = documentFileSource,
    )

    /**
     * [cachedDocumentId], [cachedReaderDocument], [cachedSectionBlocks], 그 아래의 페이지 윈도우
     * 캐시 필드들([cachedPageWindowKey], [cachedPageWindows], [cachedPageWindowsAreMeasured],
     * [paginationSession]), 그리고 [documentCacheGeneration]을 보호한다 — 이 필드들에 대한 모든
     * 읽기·쓰기는 `documentCacheLock.withLock` 블록 안에서 이루어진다.
     */
    private val documentCacheLock = Mutex()

    /**
     * 현재 [cachedReaderDocument]/[cachedSectionBlocks]가 보유하고 있는 단 하나의 문서 id, 캐시된
     * 것이 없으면 null. 문서를 다시 읽어 들이려면 데이터베이스에서 모든 섹션의 텍스트를 로드하고
     * 섹션마다 블록 목록을 디코딩해야 한다. 책을 여는 동작은 정확히 그 작업을 두 번 요구했다 —
     * 한 번은 직접, 또 한 번은 [getPageWindows] 내부에서 — 그리고 재페이지네이션이 일어날 때마다
     * 다시 요구했다. 리더가 동시에 여는 책은 항상 하나뿐이므로 책 하나만 캐시하며, 그 책이
     * 다시 쓰이거나 삭제되는 순간 캐시는 버려진다([invalidateDocumentCache] 참고).
     */
    private var cachedDocumentId: DocumentId? = null

    /** [cachedDocumentId]에 대해 캐시된 [ReaderDocument] — 왜 하나만 유지하는지는 그 프로퍼티의 문서를 참고. */
    private var cachedReaderDocument: ReaderDocument? = null

    /**
     * 같은 책의 섹션별 블록 디코더로, 복원된 페이지 레이아웃이 [cachedReaderDocument]의 블록
     * 목록 전체에 책 한 권을 통째로 디코딩하도록 강제하는 대신 섹션 하나의 블록만 요청할 수
     * 있도록 [cachedReaderDocument]와 나란히 유지된다. 캐시된 문서가 저장소가 아니라 복구
     * 패스에서 온 경우에는 null이다 — 그 문서는 이미 모든 블록을 메모리에 들고 있으므로 필요할
     * 때 따로 조회할 것이 없기 때문이다.
     */
    private var cachedSectionBlocks: SectionBlocksCache? = null

    /**
     * [cachedPageWindows]가 답하고 있는 스타일/뷰포트. 책을 레이아웃하는 작업은 리더가 하는 일
     * 중 가장 비용이 크며, 동일한 질문이 반복해서 들어온다. 회전 후 다시 돌아왔을 때 패널이 다시
     * 같은 크기를 보고하거나, 설정 시트가 글자 크기를 건드리지 않고 열렸다 닫히거나, 리더가 방금
     * 떠났던 책으로 되돌아오는 경우 등이다. 한 번에 한 책이 한 크기로만 레이아웃되므로 답은
     * 하나만 유지한다.
     */
    private var cachedPageWindowKey: PageWindowKey? = null

    /** [cachedPageWindowKey]에 대해 캐시된 페이지 윈도우 — 해당 프로퍼티의 문서를 참고. */
    private var cachedPageWindows: List<PageWindow> = emptyList()

    /**
     * [cachedPageWindows]가 브레이커가 없는 호출자가 받는 추정치가 아니라 실제 측정(복원, 또는
     * 실제 [ReaderPageBreaker]로 측정한 세션)에서 나온 것인지 여부. 측정된 답을 특별히 원하는
     * 호출자가 캐시를 재사용해도 되는지를 결정한다 — [getPageWindows] 참고.
     */
    private var cachedPageWindowsAreMeasured: Boolean = false

    /**
     * [cachedPageWindowKey]에 대해 진행 중인 점진적 페이지네이션 — [PaginationSession] 자체의
     * 문서를 참고. 더 이상 측정 중인 것이 없으면 null이다: [cachedPageWindows]가 이미 모든
     * 콘텐츠 섹션을 커버하고 있거나(실제 브레이커로 측정된 경우 이미 `page_layouts`에
     * 기록되어 있음), 아니면 [getPageWindows]가 아직 이 문서를 한 번도 측정할 필요가 없었던
     * 경우이다.
     */
    private var paginationSession: PaginationSession? = null

    /**
     * [invalidateDocumentCache] 호출마다 증가하며, 그 호출이 지목한 문서가 실제로 그 시점에
     * 캐시되어 있던 문서였는지와 무관하게 증가한다 — [getReaderDocument]가 락 밖에서 방금 계산을
     * 마친 [loadReaderDocument] 결과를 여전히 안전하게 공개해도 되는지 판단하는 데 쓰는
     * 신호이다. 어떤 무효화보다 먼저 시작됐지만 그 무효화가 이미 끝난 뒤에야 자신의 공개
     * 단계에 도달하는 로드는, 그렇지 않으면 무효화가 캐시를 비운 직후 무효화 이전 스냅샷을
     * 캐시에 다시 써넣어 그 무효화를 조용히 무효로 만들어 버릴 것이다. 대신
     * [getReaderDocument]는 첫 번째 락 구간에서 이 값을 캡처해 두고, 두 번째 락 구간에서 값이
     * 여전히 같을 때만 캐시에 쓴다. 값이 달라졌다면 로드가 진행되는 동안 어떤 쓰기 작업이
     * 캐시를 무효화했다는 뜻이므로, 새로 로드한 문서는 캐시에 넣지 않은 채 호출자에게 그대로
     * 반환하고, 다음 호출은 너무 늦게 도착한 스냅샷을 신뢰하는 대신 다시 로드한다.
     */
    private var documentCacheGeneration: Long = 0L

    /**
     * [continuePagination]을 자기 자신에 대해 직렬화한다 — 이것이 방지하는 중복 측정과 이중
     * append에 대해서는 해당 함수 자체의 문서를 참고. 의도적으로 [documentCacheLock]을 쓰지
     * 않는다: 이 락은 섹션 하나 전체의 측정 동안 유지되는데, 그 락은 리더가
     * 프레임을 그리러 가는 길에 읽는 페이지 목록을 보호하는 락이기 때문이다.
     */
    private val paginationContinuationLock = Mutex()

    /**
     * [epubScratchDocumentId], [epubScratchPath], [epubScratchContainer],
     * [epubEmbeddedFontFilesByHref]를 보호한다. 스크래치 사본 파일에 대한 모든 I/O
     * 작업 — 내장 이미지나 폰트를 읽는 것, 점진적 임포트 컨테이너를 위해 ZIP을 여는 것 — 은
     * 읽는 동안 반드시 이 락을 쥐고 있어야 하며, 최소한 경로를 건드리기 전에 `withLock` 블록
     * 안에서 [epubScratchDocumentId]를 다시 확인해야 한다. [invalidateCaches]는 스크래치
     * 파일을 삭제하기 위해 이 락을 획득하므로, 락을 쥐고 있으면 읽는 도중 파일이 사라지는
     * 것을 막을 수 있다.
     *
     * 이것은 재진입 불가능한 [Mutex]이다: 이미 [epubScratchCopy](내부에서 락을 획득함)를
     * 호출하는 쪽은 그 호출을 자신의 `withLock`으로 감싸면 안 된다 — 대신 먼저
     * [epubScratchCopy]를 호출한 뒤, 경로를 사용하기 위해 락을 다시 획득해야 한다.
     */
    private val epubScratchLock = Mutex()
    /** 아래의 [epubNextSpineCursorByDocumentId]를 보호한다. */
    private val epubImportCursorLock = Mutex()

    /**
     * 단 하나의 CBZ 스크래치 사본과 열려 있는 아카이브
     * ([cbzScratchDocumentId]/[cbzScratchPath]/[cbzArchive])의 생성, 사용, 교체, 삭제를
     * 직렬화한다. 아카이브에 대한 모든 읽기, 다른 문서의 아카이브로의 모든 전환, 모든
     * 무효화는 `cbzScratchLock.withLock` 블록 안에서 일어나므로, 페이지 윈도우 요청이 다른
     * 요청이 삭제하고 있는 스크래치 파일을 읽는 일은 결코 일어날 수 없다.
     */
    private val cbzScratchLock = Mutex()

    /**
     * [cbzScratchPath]가 스크래치 사본인 문서의 id, 사본이 없으면 null. 예전에는 CBZ 페이지
     * 윈도우 요청이 매번 아카이브 전체를 새 임시 파일로 복사하고, ZIP으로 열고, 엔트리 목록을
     * 만들고 자연 정렬하는 작업을 반복했다 — 즉 페이지를 넘길 때마다 책을 여는 비용을 다시
     * 치렀다. 이제는 사본 하나와 열린 [cbzArchive] 하나를 유지하여 같은 문서에 대한 이후의
     * 모든 페이지/표지 요청에 재사용하며, 다른 문서에 대한 요청이 오면 둘 다 교체한다.
     */
    private var cbzScratchDocumentId: DocumentId? = null

    /** [cbzScratchDocumentId]에 대한 CBZ 스크래치 사본의 파일시스템 경로 — 해당 프로퍼티의 문서를 참고. */
    private var cbzScratchPath: Path? = null

    /** [cbzScratchPath] 위에 열린 아카이브로, ZIP 인덱스는 한 번만 구축되어 재사용된다 — [cbzScratchDocumentId] 참고. */
    private var cbzArchive: ComicArchive? = null

    /**
     * [epubScratchPath]가 스크래치 사본인 문서의 id, 사본이 없으면 null. 이미지를 꺼내 올 EPUB은
     * 한 번 압축 해제되어 유지된다. 예전에는 호출마다 파일 전체를 메모리로 읽어 들이고 그림 하나에
     * 도달하기 위해 새 스크래치 사본을 매번 새로 썼기 때문에, 삽화가 있는 페이지로 넘어가는
     * 비용이 책을 여는 비용만큼이나 컸다.
     */
    private var epubScratchDocumentId: DocumentId? = null

    /** [epubScratchDocumentId]에 대한 스크래치 사본의 파일시스템 경로 — 해당 프로퍼티의 문서를 참고. */
    private var epubScratchPath: Path? = null

    /**
     * [invalidateCaches]가 EPUB 스크래치 상태를 몇 번이나 해체했는지 센다. 이를 통해
     * [epubScratchCopy]는 [epubScratchLock] 밖에서 책을 복사하는 동안 삭제가 끼어들었는지
     * 판단할 수 있다.
     *
     * 카운터가 필요한 이유는 상태만으로는 이 질문에 답할 수 없기 때문이다: 스크래치 슬롯이
     * 이미 비어 있는 상태에서 문서가 삭제되면 삭제 전후로 [epubScratchDocumentId]는 계속
     * null이므로, 나중에 완료되는 복사 작업은 더 이상 존재하지 않는 문서에 대한 스크래치
     * 사본을 아무렇지 않게 설치해 버릴 것이다. 이 카운터가 막는 것은 바로 이런 부활이다.
     *
     * 카운트는 복사 중인 문서와 일치하는 무효화뿐 아니라 모든 무효화마다 증가한다. 그 결과
     * 복사 도중 발생한 무관한 삭제도 설치를 중단시키지만, 이는 호출자가 다음 요청에서 다시
     * 시도하면 되는 빈 결과 하나의 비용일 뿐이다 — 서가가 커질수록 함께 커질 문서별 무효화
     * 상태를 추적하는 것보다 훨씬 저렴하다.
     */
    private var epubScratchInvalidationCount = 0L
    /** 현재 보유 중인 스크래치 사본에 대해 열린 임포트 컨테이너로, 점진적 배치들 사이에서 재사용된다. */
    private var epubScratchContainer: EpubImportContainer? = null
    /** 현재 EPUB에 대해 추출된 임시 폰트 파일들을, 추출 대상이었던 href를 키로 하여 재사용할 수 있도록 보관한다. */
    private val epubEmbeddedFontFilesByHref = linkedMapOf<String, Path>()
    /** 점진적으로 임포트되는 EPUB별로 아직 읽지 않은 다음 리니어 스파인 위치를 캐시하여, 이전 항목을 다시 처리하지 않도록 한다. */
    private val epubNextSpineCursorByDocumentId = mutableMapOf<DocumentId, Int>()
    /** 같은 프로세스 내 점진적 임포트를 위해 저장된 섹션 인덱스별 섹션 소스 경로로, 무효화/완료 시 초기화된다. */
    private val epubSectionPathByIndexByDocumentId = mutableMapOf<DocumentId, MutableMap<Int, String>>()

    /** 서가를 실시간으로: [documentDao]가 알고 있는 모든 문서를, 해당 테이블이 변경될 때마다 다시 방출한다. */
    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> =
        documentDao.observeRecentDocuments().map { documents -> documents.map { it.toDocumentMetadata() } }

    /**
     * [documentId]에 대한 서가 메타데이터.
     *
     * @param documentId 조회할 문서.
     * @return 저장된 [DocumentMetadata], 해당 id의 문서가 서가에 없으면 null.
     */
    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        documentDao.getDocument(documentId.value)?.toDocumentMetadata()

    /**
     * [documentId]의 표지 이미지 바이트. 다시 추출하는 비용을 치르기보다는 (임포트 시점에,
     * [importDocument]/[persistParsedDocument] 참고) [coverFilePath]가 이미 기록해 둔 파일을
     * 우선 사용한다.
     *
     * 아직 캐시된 것이 없다는 것은 이 책이 임포트 시점에 표지를 기록하는 기능이 생기기 전에
     * 임포트되었거나, [EpubDocumentParser]처럼 표지 바이트를 이미 손에 쥐고 있지 않은 파서를
     * 쓰는 PDF/CBZ라는 뜻이다. 이 경우 지금 당장의 파일 전체 추출로 한 번 폴백하고, 이후의
     * 어떤 열기에서도 다시 이 비용을 치르지 않도록 결과를 기록한다.
     *
     * @param documentId 표지를 가져올 문서.
     * @return 표지 바이트, 해당 포맷에 표지 개념이 없거나(TXT/IMAGE/UNKNOWN), [documentFileSource]를
     *   사용할 수 없거나, 읽기/추출이 실패하면 null.
     */
    override suspend fun getDocumentCover(documentId: DocumentId): ByteArray? = withContext(Dispatchers.Default) {
        val metadata = getDocument(documentId) ?: return@withContext null
        when (metadata.format) {
            DocumentFormat.TXT,
            DocumentFormat.IMAGE,
            DocumentFormat.UNKNOWN -> null
            DocumentFormat.EPUB,
            DocumentFormat.PDF,
            DocumentFormat.CBZ,
                -> {
                val fileSource = documentFileSource ?: return@withContext null
                coverStore.cached(documentId)?.let {
                    logger.d { "cover: served ${it.size} B from file for ${metadata.location.displayName}" }
                    return@withContext it
                }
                logger.d {
                    "cover: no cached file at ${coverStore.pathFor(documentId)} for ${metadata.location.displayName}, extracting"
                }
                val extracted = when (metadata.format) {
                    DocumentFormat.CBZ -> cbzScratchLock.withLock {
                        cbzArchiveLocked(metadata, fileSource).coverImageBytes()
                    }
                    else -> coverStore.extract(metadata)
                }
                logger.d { "cover: extraction gave ${extracted?.size ?: -1} B for ${metadata.location.displayName}" }
                extracted?.also { coverStore.store(documentId, it) }
            }
        }
    }

    /**
     * CBZ의 페이지 이미지들로, 메모리나 [searchIndexDao]에 보관하는 대신 요청 시점에 아카이브에서
     * 곧바로 디코딩한다 — 만화의 페이지는 인덱싱할 텍스트가 아니라 래스터 이미지이기 때문이다.
     *
     * @param documentId 페이지를 가져올 문서. CBZ가 아닌 모든 포맷에 대해서는 아무 일도 하지 않고
     *   빈 맵을 반환한다.
     * @param pageIndexes 디코딩할 페이지들.
     * @return 실제로 발견된 페이지 인덱스를 키로 하는 디코딩된 바이트, [pageIndexes]가 비어 있거나
     *   포맷이 CBZ가 아니거나 [documentFileSource]를 사용할 수 없으면 빈 맵.
     */
    override suspend fun getVisualPageImages(
        documentId: DocumentId,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> = withContext(Dispatchers.Default) {
        if (pageIndexes.isEmpty()) return@withContext emptyMap()
        val metadata = getDocument(documentId) ?: return@withContext emptyMap()
        if (metadata.format != DocumentFormat.CBZ) return@withContext emptyMap()
        val fileSource = documentFileSource ?: return@withContext emptyMap()
        cbzScratchLock.withLock {
            cbzArchiveLocked(metadata, fileSource).pageImageBytes(pageIndexes)
        }
    }

    /**
     * EPUB의 인라인 이미지 바이트로, 이미지마다 파일 전체를 새로 읽는 대신 공유되는
     * [epubScratchCopy]를 통해 추출한다([epubScratchLock] 자체의 문서 참고).
     *
     * 추출은 전적으로 [epubScratchLock] 안에서 이루어지므로, 이 호출이 스크래치 파일을 읽는
     * 동안 동시에 일어나는 문서 삭제나 교체가 그 파일을 제거할 수 없다. 그 대가로, 대량의
     * 이미지 배치는 ZIP을 읽는 동안 다른 모든 스크래치 사본 소비자(점진적 임포트, 폰트 추출,
     * 또는 다른 이미지 요청)와 직렬화된다. 실제로는 페이지당 요청되는 이미지가 작고 개수도
     * 적어서 락을 쥐는 시간은 수십 밀리초 이내에 머무른다. 병적인 경우(한 호출에 크고 많은
     * 이미지가 있는 경우)에는 다음 스크래치 사본 소비자를 그만큼 지연시킬 수 있다.
     *
     * 문서 ID는 스크래치 사본이 확립된 뒤 락 안에서 다시 검증된다: 만약 다른 코루틴이
     * [epubScratchCopy]가 내부 락을 해제한 시점과 이 호출이 다시 락을 획득하는 시점 사이에
     * 스크래치를 교체했다면, 오래된 경로는 사용하지 않고 대신 빈 맵을 반환한다.
     *
     * @param documentId 이미지를 추출할 EPUB. 그 외의 다른 포맷에 대해서는 아무 일도 하지 않고 빈
     *   맵을 반환한다.
     * @param hrefs 추출할 이미지들의 아카이브 상대 경로. 사용 전에 다듬고(trim) 중복을 제거한다.
     * @return 실제로 발견된 href를 키로 하는 추출된 바이트, [hrefs]가 (다듬은 후) 비어 있거나
     *   포맷이 EPUB이 아니거나 [documentFileSource]를 사용할 수 없거나, 추출이 시작되기 전에
     *   동시에 발생한 삭제로 스크래치 사본이 무효화되었으면 빈 맵.
     */
    override suspend fun getEmbeddedImages(
        documentId: DocumentId,
        hrefs: Set<String>,
    ): Map<String, ByteArray> = withContext(Dispatchers.Default) {
        if (hrefs.isEmpty()) return@withContext emptyMap()
        val metadata = getDocument(documentId) ?: return@withContext emptyMap()
        if (metadata.format != DocumentFormat.EPUB) return@withContext emptyMap()
        val fileSource = documentFileSource ?: return@withContext emptyMap()
        val normalizedHrefs = hrefs.map(String::trim).filterTo(mutableSetOf(), String::isNotEmpty)
        if (normalizedHrefs.isEmpty()) return@withContext emptyMap()
        epubScratchCopy(metadata, fileSource)
        epubScratchLock.withLock {
            val path = epubScratchPath
            if (epubScratchDocumentId != documentId || path == null) return@withLock emptyMap()
            epubDocumentParser.extractEmbeddedImageBytes(path = path, hrefs = normalizedHrefs)
        }
    }

    /**
     * 내장된 EPUB 폰트 파일들로, href마다 한 번씩 재사용 가능한 임시 파일로 추출되어 이 문서가
     * 현재 유효한 동안 재사용된다.
     *
     * 요청된 각 ZIP 엔트리를 한 번에 하나의 href씩, 각자의 임시 파일로 곧바로 스트리밍한다. 그
     * 덕분에 오래 유지되는 캐시와 추출 시 피크 메모리 모두 폰트 바이트 배열 전체가 아니라
     * 파일 경로와 작은 복사 버퍼 수준으로 유지된다. 요청된 href만 건드린다.
     *
     * 추출은 전적으로 [epubScratchLock] 안에서 실행되므로, 이 호출이 스트리밍하는 동안 동시에
     * 발생하는 문서 삭제나 교체가 스크래치 파일을 제거할 수 없다. 문서 ID는 락 안에서 다시
     * 검증된다: [epubScratchCopy]가 내부 락을 해제한 시점과 여기서 다시 락을 획득하는 시점
     * 사이에 스크래치가 무효화되었다면, 오래된 경로는 사용하지 않고 빈 맵을 반환한다. 락 이전에
     * 캡처해 둔 경로 변수 대신 락이 걸린 [epubScratchPath]를 직접 사용함으로써, 동시에 발생하는
     * [invalidateCaches]가 캡처된 변수가 여전히 가리키는 파일을 삭제할 수 있는 틈을 없앤다.
     *
     * 그 대가로 폰트 스트리밍은 실행되는 동안 다른 스크래치 사본 소비자들과 직렬화된다. 실제로는
     * 문서당 요청되는 폰트 파일이 몇 개뿐이고 각각 수백 킬로바이트 정도이므로, 락을 쥐는 시간은
     * 작다.
     *
     * @param documentId 폰트를 추출할 EPUB. 그 외의 다른 포맷에 대해서는 아무 일도 하지 않고 빈
     *   맵을 반환한다.
     * @param hrefs 추출할 폰트 파일들의 아카이브 상대 경로. 사용 전에 다듬고 중복을 제거한다.
     * @return 발견된 href를 키로 하는 추출된 임시 폰트의 파일 경로, 일치하는 것이 없거나 스크래치
     *   사본이 동시에 무효화되었으면 빈 맵.
     */
    override suspend fun getEmbeddedFontFiles(
        documentId: DocumentId,
        hrefs: Set<String>,
    ): Map<String, String> = withContext(Dispatchers.Default) {
        if (hrefs.isEmpty()) return@withContext emptyMap()
        val metadata = getDocument(documentId) ?: return@withContext emptyMap()
        if (metadata.format != DocumentFormat.EPUB) return@withContext emptyMap()
        val fileSource = documentFileSource ?: return@withContext emptyMap()
        val normalizedHrefs = hrefs.map(String::trim).filterTo(linkedSetOf(), String::isNotEmpty)
        if (normalizedHrefs.isEmpty()) return@withContext emptyMap()
        epubScratchCopy(metadata, fileSource)
        epubScratchLock.withLock {
            val path = epubScratchPath
            if (epubScratchDocumentId != documentId || path == null) return@withLock emptyMap()
            val missingHrefs = normalizedHrefs.filter { href ->
                epubEmbeddedFontFilesByHref[href]?.let(systemFileSystem()::exists) != true
            }.toSet()
            if (missingHrefs.isNotEmpty()) {
                val zip = systemFileSystem().openZip(path)
                missingHrefs.forEach { href ->
                    streamEmbeddedFontScratchFile(zip = zip, href = href)?.let { fontPath ->
                        epubEmbeddedFontFilesByHref[href] = fontPath
                    }
                }
                deleteAbandonedEmbeddedFontScratchFiles(keep = epubEmbeddedFontFilesByHref.values.toSet())
            }
            normalizedHrefs.mapNotNull { href ->
                epubEmbeddedFontFilesByHref[href]?.takeIf(systemFileSystem()::exists)?.let { href to it.toString() }
            }.toMap()
        }
    }

    /**
     * [documentId]에 대한 전체 [ReaderDocument] — 섹션, 블록, 내비게이션 — 으로, 이미 이 id를
     * 가리키고 있으면 [cachedReaderDocument]를 그대로 제공하고, 그렇지 않으면
     * [loadReaderDocument]를 통해 로드한 뒤 그 결과로 캐시를 교체한다(그 결과가 null인 경우에도
     * 마찬가지로 교체하여, 로드에 실패한 문서가 다른 무언가가 캐시를 무효화하기 전까지 매
     * 호출마다 다시 시도되지 않도록 한다).
     *
     * 로드 자체는 의도적으로 [documentCacheLock] 밖에서 실행된다(그렇지 않으면 모든 문서 로드가
     * 뮤텍스 하나 뒤에서 직렬화될 것이므로, 그 이유는 해당 프로퍼티 자체의 문서를 참고).
     * 이는 이 호출의 [loadReaderDocument]가 아직 진행 중인 동안 [invalidateDocumentCache]가
     * 실행될 수 있는 틈을 남긴다. 아래 첫 번째 락 구간에서 캡처하는 [documentCacheGeneration]이
     * 바로 이 틈을 닫는 역할을 한다: 두 번째 락 구간은 그 세대 값이 여전히 최신일 때만 방금
     * 로드한 결과를 캐시에 공개한다. 따라서 무효화보다 먼저 시작됐지만 그 이후에야 끝나는
     * 로드는 결코 오래된 스냅샷으로 무효화를 덮어쓰지 않는다 — 그저 캐시에 넣지 않은 채 이
     * 호출의 호출자에게 그대로 반환될 뿐이며, 다음 호출은 다시 로드한다.
     *
     * @param documentId 로드할 문서.
     * @return 문서, 서가에 없거나 로드에 실패하면 null.
     */
    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? {
        val generation = documentCacheLock.withLock {
            if (cachedDocumentId == documentId) return cachedReaderDocument
            documentCacheGeneration
        }
        val loaded = loadReaderDocument(documentId)
        documentCacheLock.withLock {
            if (documentCacheGeneration == generation) {
                cachedDocumentId = documentId
                cachedReaderDocument = loaded?.document
                cachedSectionBlocks = loaded?.sectionBlocks
            }
        }
        return loaded?.document
    }

    /**
     * [documentId]를 저장소에서 로드하되, 저장된 행이 현재 파서라면 담아냈을 무언가를 빠뜨리고
     * 있으면 먼저 복구한다.
     *
     * 섹션이 아예 없거나 텍스트가 잘못 디코딩된 섹션을 가진 TXT 문서([hasBrokenText] 참고)는
     * [repairTxtDocument]를 통해 원본 파일에서 다시 읽는다. 더 오래된 [EpubDocumentParser]
     * 버전이 섹션을 기록했거나 내비게이션이 한 번도 해석된 적 없는 EPUB은 [repairEpubDocument]를
     * 통해 다시 읽는다: 더 오래된 파서가 기록한 저장된 텍스트에는 리더가 지금 필요로 하는
     * 것들 — 이미지 비율, 블록 스타일, 문장 안에 유지되는 그림 — 이 빠져 있으며, 유일한 복구
     * 방법은 파일을 다시 읽는 것뿐이다. 어느 파서가 이 행들을 기록했는지 물어보는 것은 정수
     * 하나의 비용이면 충분하다. 예전 방식, 즉 블록들을 뒤져 오래된 코드의 흔적을 찾는 방식은
     * 어떤 책의 528개 챕터 중 293개를 열 때마다 매번 디코딩한 뒤에야 답할 수 있었다.
     *
     * @param documentId 로드할 문서.
     * @return 로드된 문서와 그 온디맨드 블록 캐시, 서가에 없으면 null.
     */
    private suspend fun loadReaderDocument(documentId: DocumentId): LoadedReaderDocument? {
        val metadata = getDocument(documentId) ?: return null
        val storedSections = getStoredSections(documentId)
        if (metadata.format == DocumentFormat.TXT && (storedSections.sections.isEmpty() || storedSections.sections.hasBrokenText())) {
            repairTxtDocument(metadata)?.let { return LoadedReaderDocument(it, sectionBlocks = null) }
        }
        if (
            metadata.format == DocumentFormat.EPUB &&
            (storedSections.parserVersion < CurrentReaderParserVersion || storedSections.navigationJson.isBlank())
        ) {
            repairEpubDocument(metadata)?.let { return LoadedReaderDocument(it, sectionBlocks = null) }
        }
        return LoadedReaderDocument(metadata.toReaderDocument(storedSections), storedSections.sectionBlocks)
    }

    /**
     * [documentId]를 [style]과 [viewportSize]로 레이아웃한 페이지 윈도우들 — "이 책의 페이지가
     * 지금 어떻게 생겼는지"를 묻는 리더의 주된 방법이다. 문서를 레이아웃하는 것은 페이지네이션
     * 중 비용이 큰 절반이므로 메인 디스패처 밖에서 실행한다. 메인 디스패처 밖이면 리더가 그리고
     * 있는 프레임을 더 이상 멈추게 하지 않으므로, 폰트나 줄 높이 변경 직후에 한 페이지 넘김이
     * 발생해도 드롭되지 않고 페이저에 도달한다.
     *
     * [viewportSize]가 null이라는 것은 호출자가 아직 실제 패널 측정값을 가지고 있지 않다는
     * 뜻이다. 이 정확한 스타일에 대해 지금까지 저장된 가장 최신 레이아웃은 — 측정 당시의
     * 뷰포트가 무엇이었든 — 호출자들이 예전에 직접 넘기던 하드코딩된 [DefaultViewportSize]
     * 추측값보다 훨씬 나은 첫 답이다. 저장된 것이 아무것도 없을 때만 같은 추측값으로
     * 폴백하므로, 저장된 레이아웃이 전혀 없는 갓 임포트된 책도 측정 없이는 결코 측정되지 않을
     * 패널을 기다리는 대신 첫 페이지네이션 패스를 받게 된다.
     *
     * [cachedPageWindowKey]에 캐시된 답(아래 `cachedPageWindowKey == key` 검사)은 키가
     * 일치하고 이미 측정되었거나 이번 호출이 자신만의 [pageBreaker]를 가져오지 않은 경우
     * 재사용된다: 측정된 답이 더 나은 답이므로, 브레이커 없이 온 호출자에게도 그 답을
     * 제공한다. 반대의 경우만 거부된다. 대신 요청을 키로 삼았다면 매번 열 때마다 같은 복원된
     * 레이아웃을 호출자 종류마다 한 번씩, 두 번 가져와 다시 만들었을 것이다.
     *
     * 그것도 실패하면 다음으로 [restorePageWindows]를 시도한다: 디스크에 있는 레이아웃은 언제나
     * 실제 측정 결과이므로([storePageWindows] 참고), 이 호출이 자신만의 브레이커를 가져왔든
     * 아니든 다시 측정하는 것보다 낫다 — 추정치 호출도 측정이 주었을 정확한 결과를 공짜로
     * 얻는다. 성공하면 `restored != null` 검사 아래의 두 디버그 로그가 먼저 몇 페이지가
     * 돌아왔는지와 복원에 걸린 시간을, 그다음 무엇이 그 복원을 저렴하게 만들었는지를 보고한다:
     * 책의 모든 블록과 모든 페이지가 아니라 실제로 디코딩된 섹션([SectionBlocksCache]에 의해)과
     * 실제로 만들어진 페이지([RestoredPageWindows]에 의해)만.
     *
     * 이 스타일에 대해 아직 아무것도 저장되어 있지 않을 때, 리더가 무언가를 보기 전에 모든
     * 섹션을 레이아웃하는 것은 실제 기기에서 측정한 결과 6.4초/13.0초가 걸렸다(204/528섹션
     * 책 기준). 그래서 이 함수는 ([anchorPositionFor]를 통해) 리더가 머물러 있는 섹션을 먼저
     * 측정하고, 앵커 페이지 이후로 적어도 [InitialForwardPaginationPages] 페이지가 이미
     * 알려질 때까지 [InitialForwardPaginationSections] 섹션 한도 내에서 계속 앞으로 측정해
     * 나간다. 재개된 페이지가 자신이 속한 섹션의 첫 페이지일 때는 바로 이전 섹션도 먼저
     * 측정하여, 뒤로 한 번 넘기는 것도 준비되도록 한다. 이후 [continuePagination]이 나머지를
     * 확장한다: 먼저 위치 0을 향해 뒤로(재개된 페이지의 번호가 더 이상 움직이지 않도록), 그
     * 다음 스파인 순서로 앞으로(총 페이지 수가 그렇게 되도록). 페이지는 오직 실제로 측정된
     * 섹션에서만 만들어지며 — 아직 도달하지 않은 섹션을 대신하는 추정치로는 결코 만들어지지
     * 않는다 — 따라서 이 함수가 반환하는 총계는 정직하다: "지금까지 측정된 페이지 수"이지,
     * 답인 척 꾸며진 추측이 아니다. 표지 감지는 복원([restorePageWindows] 참고)과 마찬가지로
     * 섹션 0을 즉시 필요로 하므로, 아래의 `prewarm(setOf(0))` 호출은
     * [TextPageLayoutEngine.resolveSections]보다 먼저 실행된다.
     *
     * [pageBreaker]가 실재하고 페이지가 하나 이상 존재할 때마다 완전히 측정된 세션은
     * [pageLayoutDao]에 기록된다. 완료된 임포트는 [storePageWindows]를 사용하고, 미완료
     * 임포트는 문자 수 버전이 정확한 저장된 접두사를 식별해 주는 [storePartialPageWindows]를
     * 사용한다. 이후 [importNextSections]는 그 버전이 일치할 때만 새 섹션을 추가하고,
     * 브레이커 없는 배치가 그 행을 오래된 것으로 만들면 삭제하며, 임포트가 완료되면 최종
     * 일치 행으로 승격한다. [continuePagination]도 자신이 기록하는 지점에서 동일한 완전/부분
     * 선택을 적용한다.
     *
     * @param documentId 레이아웃할 문서.
     * @param style 페이지를 측정할 폰트/줄 높이/글꼴.
     * @param viewportSize 레이아웃할 패널 크기, 또는 이 함수가 스스로 해석하도록 하려면 null(위
     *   설명 참고).
     * @param pageBreaker 사용할 실제 페이지 분할 측정기, 또는 새로 측정을 강제하지 않고 이용 가능한
     *   캐시나 복원된 답을 그대로 받아들이는 추정 전용 호출이면 null.
     * @param anchorOffset 새 측정이 필요할 때 재개할 문자 오프셋, 또는 첫 콘텐츠 섹션부터
     *   시작하려면 null.
     * @return 이 책/스타일/뷰포트에 대해 알려진 페이지 윈도우들 — 복원되었거나, 캐시되었거나,
     *   앵커 섹션과 바로 이전 페이지 및 최소 [InitialForwardPaginationPages]개의 다음 페이지를
     *   커버하는 데 필요한 한정된 이웃 섹션들에 대해 새로 측정된 것 — 또는 문서를 로드할 수
     *   없거나 이 함수가 절대 페이지네이션하지 않는 시각적 페이지 포맷(CBZ/IMAGE/PDF)이면 빈
     *   리스트.
     */
    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize?,
        pageBreaker: ReaderPageBreaker?,
        anchorOffset: Long?,
        viewportDensity: Float,
    ): List<PageWindow> = withContext(Dispatchers.Default) {
        val layoutKey = style.layoutKey()
        val resolvedViewportSize = viewportSize
            ?: newestStoredViewportSize(documentId, layoutKey)
            ?: DefaultViewportSize
        val key = PageWindowKey(
            documentId = documentId,
            layoutKey = layoutKey,
            viewportSize = resolvedViewportSize,
        )
        val wantsMeasured = pageBreaker != null
        documentCacheLock.withLock {
            val activeSession = paginationSession?.takeIf { it.key == key }
            if (activeSession == null && cachedPageWindowKey == key && (cachedPageWindowsAreMeasured || !wantsMeasured)) {
                return@withContext cachedPageWindows
            }
        }
        paginationContinuationLock.withLock {
            val session = documentCacheLock.withLock {
                paginationSession?.takeIf { it.key == key && (it.hasMeasuredPages || !wantsMeasured) }
            }
            if (session != null) {
                val windows = session.snapshotWindows(textPageLayoutEngine)
                documentCacheLock.withLock {
                    cachedPageWindowKey = key
                    cachedPageWindows = windows
                    cachedPageWindowsAreMeasured = session.hasMeasuredPages
                }
                return@withContext windows
            }
        }
        val document = getReaderDocument(documentId) ?: return@withContext emptyList()
        if (document.format.isVisualPageFormat()) return@withContext emptyList()

        val restoreStarted = TimeSource.Monotonic.markNow()
        val restored = suspendRunCatching { restorePageWindows(documentId, document, key) }
            .onFailure { error -> logger.w(error) { "Failed to restore stored page layout for $documentId" } }
            .getOrNull()
        if (restored != null) {
            val windows = restored.windows
            logger.d {
                "${document.title.orEmpty().take(12)}: ${windows.size} pages from ${document.sections.size} sections " +
                    "restored from storage in ${restoreStarted.elapsedNow().inWholeMilliseconds} ms"
            }
            logger.d {
                val decodedSections = restored.sectionBlocksCache?.decodedSectionCount ?: document.sections.size
                val builtWindows = (windows as? RestoredPageWindows)?.builtCount ?: windows.size
                "${document.title.orEmpty().take(12)}: on-demand pagination decoded $decodedSections/${document.sections.size} " +
                    "sections and built $builtWindows/${windows.size} windows to open"
            }
            documentCacheLock.withLock {
                cachedPageWindowKey = key
                cachedPageWindows = windows
                cachedPageWindowsAreMeasured = true
            }
            return@withContext windows
        }

        val started = TimeSource.Monotonic.markNow()
        val sectionBlocksCache = documentCacheLock.withLock { cachedSectionBlocks.takeIf { cachedDocumentId == documentId } }
        val fallbackSectionBlocks: (ReaderSection) -> List<ReaderBlock> = if (sectionBlocksCache != null) {
            { section -> sectionBlocksCache.blocksFor(section.index) }
        } else {
            textPageLayoutEngine.defaultSectionBlocks(document)
        }
        sectionBlocksCache?.prewarm(setOf(0))
        val resolved = textPageLayoutEngine.resolveSections(document, fallbackSectionBlocks)
        val anchorPosition = anchorPositionFor(resolved.contentSections, anchorOffset)
        val session = PaginationSession(
            key = key,
            format = document.format,
            coverPage = resolved.coverPage,
            contentSections = resolved.contentSections,
            sectionBlocksCache = sectionBlocksCache,
            fallbackSectionBlocks = fallbackSectionBlocks,
            lowPosition = anchorPosition,
            highPosition = anchorPosition,
            hasMeasuredPages = wantsMeasured,
        )
        if (resolved.contentSections.isNotEmpty()) {
            val anchorSection = resolved.contentSections[anchorPosition]
            val anchorStarts = measuredPageStartsForSection(
                section = anchorSection,
                sectionBlocks = session.blocksFor(anchorSection),
                style = style,
                viewportSize = resolvedViewportSize,
                viewportDensity = viewportDensity,
                pageBreaker = pageBreaker,
            )
            session.putMeasured(anchorPosition, anchorStarts)

            val anchorPageIndex = pageIndexContaining(anchorStarts.offsets, anchorSection.range, anchorOffset)
            if (anchorPageIndex == 0 && anchorPosition > 0) {
                val previousSection = resolved.contentSections[anchorPosition - 1]
                session.putMeasured(
                    anchorPosition - 1,
                    measuredPageStartsForSection(
                        section = previousSection,
                        sectionBlocks = session.blocksFor(previousSection),
                        style = style,
                        viewportSize = resolvedViewportSize,
                        viewportDensity = viewportDensity,
                        pageBreaker = pageBreaker,
                    ),
                )
            }
            var nextPosition = anchorPosition + 1
            var forwardSectionsMeasured = 0
            while (
                nextPosition <= resolved.contentSections.lastIndex &&
                forwardSectionsMeasured < InitialForwardPaginationSections &&
                session.pagesAfter(anchorPosition, anchorPageIndex) < InitialForwardPaginationPages
            ) {
                val nextSection = resolved.contentSections[nextPosition]
                session.putMeasured(
                    nextPosition,
                    measuredPageStartsForSection(
                        section = nextSection,
                        sectionBlocks = session.blocksFor(nextSection),
                        style = style,
                        viewportSize = resolvedViewportSize,
                        viewportDensity = viewportDensity,
                        pageBreaker = pageBreaker,
                    ),
                )
                nextPosition += 1
                forwardSectionsMeasured += 1
            }
        }
        val pageWindows = session.snapshotWindows(textPageLayoutEngine)
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        logger.d {
            "${document.title.orEmpty().take(12)}: measured section ${anchorPosition + 1}/" +
                "${resolved.contentSections.size.coerceAtLeast(1)} (${pageWindows.size} pages so far) " +
                "in $elapsedMs ms, measured=$wantsMeasured, complete=${session.isComplete}"
        }
        documentCacheLock.withLock {
            cachedPageWindowKey = key
            cachedPageWindows = pageWindows
            cachedPageWindowsAreMeasured = wantsMeasured
            paginationSession = session.takeUnless { it.isComplete }
        }
        if (session.isComplete && pageBreaker != null && pageWindows.isNotEmpty()) {
            val importComplete = isImportComplete(documentId)
            if (importComplete) {
                storePageWindows(documentId, document, key, session)
            } else {
                storePartialPageWindows(documentId, document, key, session)
            }
        }
        pageWindows
    }

    /**
     * [documentId]/[style]/[viewportSize]에 대해 진행 중인 [paginationSession]을 한정된 배치만큼
     * 더 많은 콘텐츠 섹션으로 확장하며, [paginationContinuationLock] 아래에서 그 섹션들을
     * 원자적으로 선점하고 커밋한다.
     *
     * 한 번에 섹션 하나씩 선점하고 커밋한다. 스타일 변경의 일반적인 과정에서는 두 개의 연속
     * 패스가 겹친다 — `updateStyle`이 하나를 시작하고, 새 스타일에 대한 패널의 첫 브레이커
     * 보고가 또 하나를 시작한다 — 그리고 락 없이 [PaginationSession.lowPosition]/
     * [PaginationSession.highPosition]을 읽으면 둘 다 같은 위치를 선점하고, 측정하고, 두 번
     * append하게 된다. 그런 식으로 책 전체를 훑은 패스는 결국 책의 페이지 수를 정확히 두 배로
     * 들고 있었고, 그대로 저장했다.
     * [paginationContinuationLock]은 커밋만이 아니라 측정 자체에 걸쳐서 유지되는데, 선점이
     * 안전하려면 그것이 곧 옮기려는 위치들을 다른 어떤 것도 읽을 수 없어야 하기 때문이다.
     * 리더 자신의 경로에 있는 그 무엇도 이 락을 잡지 않는다 — 연속(continuation)은 자기
     * 자신에 대해서만 직렬화되는 백그라운드 작업이며, 리더가 기다리고 있는 페이지(이는
     * [documentCacheLock] 아래 [cachedPageWindows]에서 제공된다)에 대해서는 결코 직렬화되지
     * 않는다.
     *
     * 확장 방향은 [PaginationSession.lowPosition]에 따라 번갈아 바뀐다: 그 값이 아직 0보다
     * 크면 다음에 선점되는 섹션은 바로 그 앞 섹션이다(재개된 섹션 자신의 페이지가 먼저
     * 안정되도록). 0에 도달하면 이후의 모든 호출은 대신 [PaginationSession.highPosition]에서
     * 앞으로 확장한다. 이 경로는 이제 측정된 섹션 시작점을 살아 있는 세션에 append하기만
     * 하며, 매 배치 후 전체 페이지 윈도우 스냅샷을 다시 만들지 않는다. 스냅샷/캐시의 실체화는
     * 호출자가 [getPageWindows]를 통해 요청하거나 세션이 완료될 때까지 미뤄진다. 세션이
     * 완료되면 그 윈도우들은 [pageLayoutDao]에 기록된다. 끝난 임포트는 완전한 행을 얻고,
     * 진행 중인 임포트는 [appendMeasuredPageStarts]가 이 접두사를 다시 측정하지 않고도 이후
     * 섹션으로 확장할 수 있는, 문자 수로 버전이 매겨진 부분 행을 얻는다.
     *
     * @param documentId 확장할 진행 중인 세션이 속한 문서.
     * @param style 확장되려면 진행 중인 세션이 일치해야 하는 스타일.
     * @param viewportSize 확장되려면 진행 중인 세션이 일치해야 하는 뷰포트.
     * @param pageBreaker 새로 선점된 섹션에 사용할 실제 페이지 분할 측정기, 또는 아무것도 측정하지
     *   않고 즉시 완료를 보고하려면 null.
     * @return 일치하는 진행 중인 세션이 없거나, 세션이 이미 완료됐거나, [pageBreaker]가 null이면
     *   [PaginationProgress.isComplete]가 true이고 `sectionsMeasured = 0`. 그 외의 경우 이 호출이
     *   측정한 섹션들에 대한 진행 상황.
     */
    override suspend fun continuePagination(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float,
    ): PaginationProgress = withContext(Dispatchers.Default) {
        if (pageBreaker == null) return@withContext PaginationProgress(isComplete = true, sectionsMeasured = 0)
        paginationContinuationLock.withLock {
            val key = PageWindowKey(documentId = documentId, layoutKey = style.layoutKey(), viewportSize = viewportSize)
            val session = documentCacheLock.withLock { paginationSession?.takeIf { it.key == key } }
                ?: return@withContext PaginationProgress(isComplete = true, sectionsMeasured = 0)
            if (session.isComplete) return@withContext PaginationProgress(isComplete = true, sectionsMeasured = 0)
            session.hasMeasuredPages = true

            var sectionsMeasured = 0
            while (sectionsMeasured < PaginationContinuationBatchSize && !session.isComplete) {
                val extendingBackward = session.lowPosition > 0
                val nextPosition = if (extendingBackward) session.lowPosition - 1 else session.highPosition + 1
                val nextSection = session.contentSections[nextPosition]
                session.putMeasured(
                    nextPosition,
                    measuredPageStartsForSection(
                        section = nextSection,
                        sectionBlocks = session.blocksFor(nextSection),
                        style = style,
                        viewportSize = viewportSize,
                        viewportDensity = viewportDensity,
                        pageBreaker = pageBreaker,
                    ),
                )
                sectionsMeasured += 1
            }
            val isComplete = session.isComplete
            if (isComplete) {
                val windows = session.snapshotWindows(textPageLayoutEngine)
                documentCacheLock.withLock {
                    cachedPageWindowKey = key
                    cachedPageWindows = windows
                    cachedPageWindowsAreMeasured = true
                    paginationSession = null
                }
                if (windows.isNotEmpty()) {
                    val importComplete = isImportComplete(documentId)
                    if (importComplete) {
                        getReaderDocument(documentId)?.let { document -> storePageWindows(documentId, document, key, session) }
                    } else {
                        getReaderDocument(documentId)?.let { document -> storePartialPageWindows(documentId, document, key, session) }
                    }
                }
            } else {
                documentCacheLock.withLock {
                    paginationSession = session
                }
            }
            PaginationProgress(isComplete = isComplete, sectionsMeasured = sectionsMeasured)
        }
    }

    /**
     * [documentId]의 페이지네이션이 그 책이 현재 가지고 있는 모든 콘텐츠 섹션을 측정했는지 여부.
     *
     * 임포트가 아직 실행 중인 동안에는 이 값이 결코 true가 될 수 없다: 임포트가 실행되는 동안에는
     * 책이 앞으로 가지게 될 섹션 중 아직 파싱조차 되지 않은 것들이 있으므로, 현재 세션이 아무리
     * 멀리까지 훑었어도 그 측정이 완료됐다고 할 수 없다. 그래서 [isImportComplete]를 먼저
     * 확인하여 false로 단락 평가한다. [paginationSession]만으로 답하면 어떤 배치가 방금 그것을
     * null로 만든 순간마다("complete"로) 답했을 것이고([invalidateDocumentCache] 참고), 호출자는
     * 연속 작업을 계속 실행할지 결정하기 위해 이 함수를 호출하므로(`ReaderViewModel.refreshPaginationCompleteness`
     * 참고) 그런 답은 페이지 수를 늘리는 유일한 수단을 은퇴시켜, 마지막 재로드가 측정한 섹션
     * 하나에 총계를 고정시켜 버렸을 것이다.
     *
     * [isImportComplete] 확인은 의도적으로 [documentCacheLock] 밖에서 이루어진다: 이는 저장소를
     * 읽는 작업이며, 그 동안 캐시 락을 쥐고 있으면 리더가 기다리는 페이지를 막게 된다.
     *
     * @param documentId 확인할 문서.
     * @return 임포트가 끝났고, 이 문서에 대한 활성 세션이 완료됐거나, 활성 세션이 없고 이 문서에
     *   대한 캐시된 윈도우가 추정 전용 열기가 아니라 실제 측정에서 나온 것이면 true.
     */
    override suspend fun isPaginationComplete(documentId: DocumentId): Boolean {
        if (!isImportComplete(documentId)) return false
        return documentCacheLock.withLock {
            paginationSession?.let { it.key.documentId != documentId || it.isComplete }
                ?: if (cachedPageWindowKey?.documentId == documentId) cachedPageWindowsAreMeasured else true
        }
    }

    /** [anchorOffset]을 포함하는 섹션이 [contentSections]에서 차지하는 위치 — 섹션은 오름차순이고
     * 겹치지 않으므로, 그 지점에서 시작하거나 그 이전에 시작하는 마지막 섹션이다. [anchorOffset]이
     * null이거나 모든 섹션의 시작보다 앞서면 첫 콘텐츠 섹션을 기본값으로 하는데, 이는 재개할 곳이
     * 없는 갓 임포트된 책이 시작하는 것과 같은 위치이다. */
    private fun anchorPositionFor(contentSections: List<ReaderSection>, anchorOffset: Long?): Int {
        if (contentSections.isEmpty() || anchorOffset == null) return 0
        var low = 0
        var high = contentSections.lastIndex
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (contentSections[mid].range.start <= anchorOffset) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result.coerceIn(0, contentSections.lastIndex)
    }

    /** [sectionStarts]에서 [anchorOffset]을 포함하는 페이지, null이거나 범위 밖이면 첫 페이지를 기본값으로 한다. */
    private fun pageIndexContaining(sectionStarts: LongArray, sectionRange: TextRange, anchorOffset: Long?): Int {
        if (sectionStarts.isEmpty() || anchorOffset == null) return 0
        var lo = 0
        var hi = sectionStarts.lastIndex
        var result = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sectionStarts[mid] <= anchorOffset) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val start = sectionStarts[result]
        val end = sectionStarts.getOrNull(result + 1) ?: sectionRange.end
        return if (anchorOffset in start until end) result else 0
    }

    /**
     * 호출자가 실제 [ReaderPageBreaker]로 [getPageWindows]를 호출하기 전에 [documentId]/[style]에
     * 대해 측정해야 할 뷰포트 — 뷰포트가 주어지지 않았을 때 [getPageWindows] 자신이 폴백하는
     * 것과 동일한 조회이다.
     *
     * @param documentId 저장된 뷰포트를 조회할 문서.
     * @param style 저장된 레이아웃의 뷰포트를 재사용할 스타일.
     * @return 이 스타일에 대해 가장 최신으로 저장된 레이아웃의 뷰포트, 아직 저장된 것이 없으면 null.
     */
    override suspend fun resolveViewportSizeForStyle(documentId: DocumentId, style: ReaderStyle): ViewportSize? =
        withContext(Dispatchers.Default) { newestStoredViewportSize(documentId, style.layoutKey()) }

    /**
     * [documentId]에 대해 캐시된 [SectionBlocksCache]에 [sectionIndexes]의 블록들을 즉시
     * 디코딩해 넣는다. 어떤 섹션을 곧 보여줄지 알고 있는 호출자는 지연 처리로 나중에 따라잡게
     * 두는 대신, 그것을 필요로 하는 페이지보다 먼저 그 비용을 치를 수 있다.
     *
     * @param documentId 섹션-블록 캐시를 예열할 문서. 현재 캐시된 문서가 아니면 아무 일도 하지
     *   않고 0을 반환한다.
     * @param sectionIndexes 디코딩할 섹션들.
     * @return 이 호출로 실제로 새로 디코딩된 섹션 수.
     */
    override suspend fun warmSectionBlocks(documentId: DocumentId, sectionIndexes: Set<Int>): Int {
        if (sectionIndexes.isEmpty()) return 0
        val cache = documentCacheLock.withLock {
            cachedSectionBlocks.takeIf { cachedDocumentId == documentId }
        } ?: return 0
        return withContext(Dispatchers.Default) { cache.prewarm(sectionIndexes) }
    }

    /**
     * [documentId]가 참조하는 모든 퍼블리셔 폰트 href를 해석한다. 새 임포트는 블록 JSON을
     * 디코딩하지 않고 정확히 영속화된 href 인덱스에서 답한다. 레거시로 인덱스가 null인 경우
     * 캐시된 섹션 블록을 한 번 스캔하여 결과를 영속화하고, 이후의 모든 호출이 인덱스 경로를
     * 쓰도록 만든다.
     *
     * @param documentId 참조된 퍼블리셔 폰트가 필요한 EPUB.
     * @return 참조된 고유 href들, 문서나 로드된 블록 캐시를 사용할 수 없으면 빈 집합.
     */
    override suspend fun getReferencedEmbeddedFontHrefs(documentId: DocumentId): Set<String> {
        val entity = documentDao.getDocument(documentId.value) ?: return emptySet()
        val indexed = entity.embeddedFontHrefsJson
        if (indexed != null) {
            val started = TimeSource.Monotonic.markNow()
            val result = runCatching { json.decodeFromString<List<String>>(indexed).toSet() }.getOrDefault(emptySet())
            logger.d {
                "${entity.name.take(12)}: font index served ${result.size} hrefs from stored JSON " +
                    "in ${started.elapsedNow().inWholeMilliseconds} ms (O(F), no blocks DAO read)"
            }
            return result
        }
        val started = TimeSource.Monotonic.markNow()
        val cache = documentCacheLock.withLock {
            cachedSectionBlocks.takeIf { cachedDocumentId == documentId }
        } ?: return emptySet()
        return withContext(Dispatchers.Default) {
            val snapshot = cache.snapshotAllBlocks()
            val hrefs = snapshot.values
                .asSequence()
                .flatMap { blocks -> blocks.asSequence() }
                .flatMap { block ->
                    sequenceOf(block.style?.fontHref)
                        .plus(block.spans.asSequence().map { span -> span.styleDelta?.fontHref })
                }
                .filterNotNull()
                .toMutableSet()
            val sortedHrefs = hrefs.sorted()
            val hrefsJson = json.encodeToString(sortedHrefs)
            documentDao.updateEmbeddedFontHrefsJson(documentId.value, hrefsJson)
            logger.d {
                "${entity.name.take(12)}: legacy font scan found ${hrefs.size} hrefs, backfilled index " +
                    "in ${started.elapsedNow().inWholeMilliseconds} ms"
            }
            hrefs
        }
    }

    /**
     * 행이 존재한다면 [PageLayoutDao.getNewestPageLayoutForStyle]이 [layoutKey]에 대해 해석하는 뷰포트.
     *
     * @param documentId 저장된 레이아웃을 조회할 문서.
     * @param layoutKey 일치시킬 스타일(폰트 크기/줄 높이/글꼴).
     * @return 일치하는 가장 최신 저장 레이아웃의 뷰포트, 존재하지 않으면 null.
     */
    private suspend fun newestStoredViewportSize(documentId: DocumentId, layoutKey: ReaderLayoutKey): ViewportSize? {
        val stored = pageLayoutDao.getNewestPageLayoutForStyle(
            documentId = documentId.value,
            fontSizeSp = layoutKey.fontSizeSp,
            lineHeightMultiplier = layoutKey.lineHeightMultiplier,
            fontFamilyName = layoutKey.fontFamilyName.orEmpty(),
        ) ?: return null
        return ViewportSize(widthPx = stored.viewportWidthPx, heightPx = stored.viewportHeightPx)
    }

    /**
     * [cachedPageWindows]의 영속화된 대응물: 이전에 열었을 때 실제 측정으로 만들어진 페이지
     * 시작점들로, 프로세스 수명보다 오래 유지되어 같은 책을 같은 타입과 뷰포트로 다음에 열
     * 때는 단 한 줄도 다시 측정하지 않도록 한다.
     *
     * `characterCount`가 더 이상 [document]와 일치하지 않는 저장된 행은 거부하고(삭제한다):
     * 문서를 다시 파싱하면 그 안의 모든 문자 오프셋이 움직일 수 있으며, 문자 수가 더 이상
     * 일치하지 않는 행을 거부하는 것이 바로 복구 패스가 가리키던 책을 다시 쓴 뒤 책갈피나
     * 읽던 위치가 조용히 엉뚱한 텍스트에 놓이는 것을 막아준다. 오직 blob(`pageStartsBlob`)만
     * 디코딩된다 — `pageStartsJson`은 스키마상의 이유로 남겨진 레거시 저장소이다
     * ([PageLayoutEntity] 참고). `TeddReaderMigration7To8` 이전에 기록된 행에는 blob이 없으며,
     * 저장된 행이 아예 없는 것과 동일하게 취급된다. 그 마이그레이션은 정확히 이 이유로 그런
     * 모든 행을 삭제하므로, 이 값이 null인 경우는 마이그레이션 자체보다 오래된 데이터베이스에서만
     * 있어야 한다.
     *
     * 디코딩된 페이지 시작점이 엄격하게 오름차순이 아닌 행도 거부한다(삭제한다): 페이지는
     * 읽는 순서대로 기록되므로 그 시작점은 오름차순일 수밖에 없다. 이를 어기는 행은 지금
     * 상태의 책에 대한 온전한 측정으로 기록된 것이 아니며, 그로부터 페이지를 재구성하면 리더를
     * 그 행이 말하는 곳이 아닌 텍스트에 올려놓게 된다 — 그래서 신뢰하는 대신 버리고 다시
     * 측정한다. 이 검사는 이미 같은 배열을 훑은 디코딩 바로 다음에 이어지는, 수천 개의 long
     * 값에 대한 한 번의 순회에 불과하며, 이것이 있기에 어떤 기록자 버그로 손상된 행을 가진
     * 기기가 영원히 잘못된 페이지를 읽는 대신 다음에 열 때 스스로 치유될 수 있다.
     *
     * 섹션-블록 캐시는 저장소에서 실제로 로드된 문서에 대해서만 존재한다. 복구 패스에서 막
     * 나온 문서는 이미 모든 블록을 메모리에 들고 있으므로, [TextPageLayoutEngine.reconstruct]는
     * 그 경우 아무것도 두 번 디코딩하지 않고 자체 기본값으로 폴백한다. 캐시를 사용할 수 있을
     * 때는, 표지 감지가 지연이 아니라 즉시 섹션 0을 들여다보기 때문에(`reconstruct` 자신이
     * 반환하기 전에 그 내부에서 호출하는 `TextPageLayoutEngine.findCoverSection` 참고)
     * 재구성 전에 섹션 0을 예열한다 — 즉 나중에 어느 페이지가 우연히 만들어지기 전이 아니라,
     * `reconstruct`가 실행되기 전에 이미 섹션 0이 디코딩되어 있어야 한다.
     *
     * @param documentId 저장된 레이아웃을 복원할 문서.
     * @param document 저장된 레이아웃이 여전히 일치해야 하는, 방금 로드한 문서.
     * @param key 저장된 레이아웃이 측정되었어야 할 스타일/뷰포트.
     * @return 재구성된 윈도우들과 그것들에 답한 섹션-블록 캐시, 저장된 것이 없거나 저장된
     *   행이 위의 일관성 검사를 통과하지 못했거나 디코딩할 페이지 시작점 blob이 없으면 null.
     */
    private suspend fun restorePageWindows(
        documentId: DocumentId,
        document: ReaderDocument,
        key: PageWindowKey,
    ): RestoredPageWindowsResult? {
        val stored = pageLayoutDao.getPageLayout(
            documentId = documentId.value,
            fontSizeSp = key.layoutKey.fontSizeSp,
            lineHeightMultiplier = key.layoutKey.lineHeightMultiplier,
            fontFamilyName = key.layoutKey.fontFamilyName.orEmpty(),
            viewportWidthPx = key.viewportSize.widthPx,
            viewportHeightPx = key.viewportSize.heightPx,
        ) ?: return null
        if (stored.characterCount != document.characterCount ||
            document.sections.any { section -> !textPageLayoutEngine.canMeasureSection(section) }
        ) {
            pageLayoutDao.deletePageLayouts(documentId.value)
            return null
        }
        val pageStartsBlob = stored.pageStartsBlob ?: return null
        val pageStarts = decodePageStartsBlob(pageStartsBlob)
        if (!pageStarts.isStrictlyAscending()) {
            logger.w { "Discarding a stored page layout for $documentId whose page starts do not ascend" }
            pageLayoutDao.deletePageLayouts(documentId.value)
            return null
        }
        val sectionBlocksCache = documentCacheLock.withLock {
            cachedSectionBlocks.takeIf { cachedDocumentId == documentId }
        }
        val windows = if (sectionBlocksCache != null) {
            sectionBlocksCache.prewarm(setOf(0))
            textPageLayoutEngine.reconstruct(
                document = document,
                contentPageStarts = pageStarts,
                sectionBlocks = { section -> sectionBlocksCache.blocksFor(section.index) },
                isSectionReady = sectionBlocksCache::isReady,
            )
        } else {
            textPageLayoutEngine.reconstruct(document, pageStarts)
        }
        return RestoredPageWindowsResult(windows, sectionBlocksCache)
    }

    /**
     * 완료된 페이지네이션 세션의 측정된 모든 시작점을 최종 재사용 가능한 레이아웃으로 영속화한다.
     *
     * @param documentId 완료된 레이아웃을 저장할 문서.
     * @param document 그 문자 수로 행의 버전을 매기는 완전한 문서.
     * @param key 정확한 스타일과 뷰포트 측정 키.
     * @param session 모든 콘텐츠 페이지 시작점을 제공하는 완료된 세션.
     */
    private suspend fun storePageWindows(
        documentId: DocumentId,
        document: ReaderDocument,
        key: PageWindowKey,
        session: PaginationSession,
    ) {
        if (!session.isFullyMeasured) return
        storePageStarts(documentId, document, key, session.allMeasuredStarts())
    }

    /**
     * 부분 페이지 레이아웃을 저장한다 — 아직 임포트가 완료되지 않은 문서에 대해 현재까지 알려진
     * 접두사에 대해 측정한 레이아웃이다. 부분 행은 [PageLayoutEntity.isPartial] = true로 완전한
     * 행과 구분되며, 임포트가 끝나고 최종 characterCount가 일치하면 승격된다.
     *
     * @param documentId 부분 레이아웃을 저장할 문서.
     * @param document 현재의 접두사 상태에 있는 문서.
     * @param key 레이아웃이 측정된 스타일/뷰포트.
     * @param session 현재 접두사에 대한 완료된 페이지네이션 세션.
     */
    private suspend fun storePartialPageWindows(
        documentId: DocumentId,
        document: ReaderDocument,
        key: PageWindowKey,
        session: PaginationSession,
    ) {
        if (!session.isFullyMeasured) return
        pageLayoutDao.upsertPageLayout(
            PageLayoutEntity(
                documentId = documentId.value,
                fontSizeSp = key.layoutKey.fontSizeSp,
                lineHeightMultiplier = key.layoutKey.lineHeightMultiplier,
                fontFamilyName = key.layoutKey.fontFamilyName.orEmpty(),
                viewportWidthPx = key.viewportSize.widthPx,
                viewportHeightPx = key.viewportSize.heightPx,
                characterCount = document.characterCount,
                pageStartsBlob = encodePageStartsBlob(session.allMeasuredStarts()),
                writtenAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                isPartial = true,
            ),
        )
    }

    /**
     * 최종 페이지 시작점을 기록하고, 스타일 변경으로 저장소가 한없이 커지지 않도록 오래된 레이아웃
     * 변형들을 잘라낸다.
     *
     * @param documentId 최종 페이지 시작점을 저장할 문서.
     * @param document 그 문자 수로 행의 버전을 매기는 완전한 문서.
     * @param key 정확한 스타일과 뷰포트 측정 키.
     * @param contentPageStarts 엄격하게 증가하는 읽기 순서로 측정된 시작점들.
     */
    private suspend fun storePageStarts(
        documentId: DocumentId,
        document: ReaderDocument,
        key: PageWindowKey,
        contentPageStarts: LongArray,
    ) {
        pageLayoutDao.upsertPageLayout(
            PageLayoutEntity(
                documentId = documentId.value,
                fontSizeSp = key.layoutKey.fontSizeSp,
                lineHeightMultiplier = key.layoutKey.lineHeightMultiplier,
                fontFamilyName = key.layoutKey.fontFamilyName.orEmpty(),
                viewportWidthPx = key.viewportSize.widthPx,
                viewportHeightPx = key.viewportSize.heightPx,
                characterCount = document.characterCount,
                pageStartsBlob = encodePageStartsBlob(contentPageStarts),
                writtenAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        pageLayoutDao.trimPageLayouts(documentId.value, keep = MaxStoredPageLayoutsPerDocument)
    }

    /**
     * [source]를 새 문서로 임포트하거나, 이미 서가에 있고 완전히 임포트된 상태라면 기존 문서를
     * 그대로 돌려준다.
     *
     * 이미 서가에 있고 끝까지 임포트된 책은 다시 임포트하는 대신 그냥 연다: 다른 앱이 이 책을
     * 넘겨주는 경우("다른 앱으로 열기", 공유 등)가 매번 여기로 들어오는데, 다시 임포트하면
     * 리더가 이미 읽고 있던 책의 저장된 텍스트와 페이지 레이아웃이 버려져서, 파일 관리자에서
     * 528챕터짜리 책을 여는 것만으로도 임포트 전체 비용을 다시 치르게 되었다. 끝나지 않은
     * 임포트는 이런 식으로 건너뛰지 않는다 — [importNextSections]가 멈춘 지점부터 이어받으므로,
     * 아래의 `existingDocument != null && isImportComplete(id)` 검사는 진짜로 완료된 책에
     * 대해서만 단락 평가된다.
     *
     * 아래에서 EPUB은 특별히 취급된다: 점진적 임포트([importEpubPhase0])는 호출자가 파일 전체를
     * 메모리로 읽어 들이지 않으려고 의도적으로 바이트를 보류했을 때만 그 자체로 이득이
     * 있다(`DocumentImporter.android/ios.kt`를 참고. 이제는 선택된 EPUB에 대해 `bytes=null`을
     * 넘긴다). 이미 바이트를 가지고 있는 호출자 — 기존 테스트나 이미 네트워크 비용을 치른 구글
     * 드라이브 다운로드 — 는 나머지 스파인을 미루는 것에서 아무 이득도 얻지 못하므로, EPUB
     * 임포트가 항상 해왔던 것과 같은 동기적 전체 파싱([importEpubFullyFromBytes])을 그대로
     * 받는다. `bytes=null` 경로만이 [importEpubPhase0]의 단계적 경로를 타며, 이 경로는 되돌아갈
     * 바이트가 없으므로 스트리밍할 실제 파일 소스를 필요로 한다.
     *
     * @param source 선택된 파일의 위치와, 있다면 이미 읽어 들인 바이트.
     * @param importedAtEpochMillis 이 임포트가 일어난 시각으로, (진짜로 새 문서인 경우)
     *   `addedAtEpochMillis`와 `lastOpenedAtEpochMillis`에 스탬프를 찍는 데 쓰인다.
     * @return 임포트된(또는 이미 서가에 있던) 문서.
     * @throws IllegalStateException EPUB이 바이트 없이 임포트되었는데 [documentFileSource]가
     *   구성되어 있지 않거나, CBZ가 바이트 없이 임포트되었는데 [documentFileSource]가 없을 때.
     * @throws IllegalArgumentException [formatDetector]가 포맷을 인식할 수 없을 때.
     */
    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument {
        val id = DocumentId(source.location.sourceUri)
        val existingDocument = getDocument(id)
        if (existingDocument != null && isImportComplete(id)) {
            getReaderDocument(id)?.let { return it }
        }
        val format = formatDetector.detect(source.location, source.bytes)
        val document = when (format) {
            DocumentFormat.TXT -> txtDocumentParser.parse(
                id = id,
                title = source.location.displayName,
                text = TxtTextDecoder.decode(requireDocumentBytes(source)),
            )

            DocumentFormat.EPUB -> return source.bytes?.let { bytes ->
                importEpubFullyFromBytes(id, source, existingDocument, importedAtEpochMillis, bytes)
            } ?: importEpubPhase0(
                id = id,
                source = source,
                existingDocument = existingDocument,
                importedAtEpochMillis = importedAtEpochMillis,
                fileSource = documentFileSource ?: error("Cannot import EPUB without a file source when no bytes are provided."),
            )

            DocumentFormat.PDF -> pdfDocumentParser.parse(
                id = id,
                title = source.location.displayName,
                location = source.location,
                bytes = source.bytes,
            )

            DocumentFormat.CBZ -> source.bytes?.let { bytes ->
                comicBookDocumentParser.parse(
                    id = id,
                    title = source.location.displayName,
                    bytes = bytes,
                )
            } ?: run {
                val fileSource = documentFileSource ?: error("Cannot import CBZ without file source.")
                withTemporarySourceCopy(fileSource, source.location) { path ->
                    comicBookDocumentParser.parse(
                        id = id,
                        title = source.location.displayName,
                        path = path,
                    )
                }
            }

            DocumentFormat.IMAGE -> imageDocumentParser.parse(
                id = id,
                title = source.location.displayName,
            )

            DocumentFormat.UNKNOWN -> throw IllegalArgumentException(
                "Unsupported document format: ${source.location.displayName}",
            )
        }

        persistParsedDocument(
            metadata = DocumentMetadata(
                id = id,
                location = source.location,
                format = document.format,
                addedAtEpochMillis = existingDocument?.addedAtEpochMillis ?: importedAtEpochMillis,
                lastOpenedAtEpochMillis = importedAtEpochMillis,
                pageCount = document.pageCount,
                characterCount = document.characterCount,
                wordCount = document.wordCount,
                isBookmarked = existingDocument?.isBookmarked ?: false,
                folderId = existingDocument?.folderId,
                folderName = existingDocument?.folderName,
            ),
            document = document,
        )
        return document
    }

    /**
     * 이미 서가에 있는 문서에 대한 평범한 메타데이터 편집 — 즐겨찾기 토글, 폴더 이동 — 을
     * 기록하되, 그 쓰기 동안 `importCompletedAtEpochMillis` 스탬프는 보존한다.
     *
     * [DocumentMetadata]에는 그 컬럼에 대응하는 필드가 없고([DocumentEntity] 참고), Room의
     * upsert는 행 전체를 교체한다 — 그러지 않으면 즐겨찾기 토글 같은 평범한 편집이 null을 다시
     * 써넣어, 나중의 점진적 임포트 단계가 신뢰해야 할 타임스탬프를 지워버릴 것이다. 저장된 값을
     * 읽어서 그대로 이어가는 것이 더 작은 수정이다. 그 컬럼을 도메인 모델까지 관통시키는 것은
     * 아직 아무도 읽지 않는 값 하나를 위해 그 모든 호출 지점을 건드려야 할 것이다.
     *
     * @param document 기록할 메타데이터. `importCompletedAtEpochMillis`를 제외한 모든 필드가
     *   저장된 행을 덮어쓰며, 그 필드만은 대신 저장소에서 그대로 이어받는다.
     */
    override suspend fun upsertDocument(document: DocumentMetadata) {
        val importCompletedAtEpochMillis = documentDao.getDocument(document.id.value)?.importCompletedAtEpochMillis
        documentDao.upsertDocument(
            document.toDocumentEntity().copy(importCompletedAtEpochMillis = importCompletedAtEpochMillis),
        )
    }

    override suspend fun setDocumentsBookmarked(documentIds: Collection<DocumentId>, isBookmarked: Boolean) {
        if (documentIds.isEmpty()) return
        documentDao.updateBookmarked(documentIds.map(DocumentId::value), isBookmarked)
    }

    override suspend fun setDocumentsFolder(
        documentIds: Collection<DocumentId>,
        folderId: String?,
        folderName: String?,
    ) {
        require((folderId == null) == (folderName == null)) {
            "folderId and folderName must both be null or both be non-null."
        }
        require(folderId == null || folderId.isNotBlank()) { "folderId must not be blank." }
        require(folderName == null || folderName.isNotBlank()) { "folderName must not be blank." }
        if (documentIds.isEmpty()) return
        documentDao.updateFolder(documentIds.map(DocumentId::value), folderId, folderName)
    }

    override suspend fun renameFolder(folderId: String, folderName: String) {
        require(folderId.isNotBlank()) { "folderId must not be blank." }
        require(folderName.isNotBlank()) { "folderName must not be blank." }
        documentDao.renameFolder(folderId, folderName)
    }

    override suspend fun clearFolder(folderId: String) {
        require(folderId.isNotBlank()) { "folderId must not be blank." }
        documentDao.clearFolder(folderId)
    }

    /**
     * [documentId]가 [openedAtEpochMillis]에 열렸다고 스탬프를 찍는다. 이는 서가의 "최근" 정렬과
     * 읽기 위치 불변조건(AGENTS.md의 Reader Invariants 참고)이 둘 다 참조하는 기준점이다.
     *
     * @param documentId 열린 문서.
     * @param openedAtEpochMillis 열린 시각.
     */
    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) {
        documentDao.updateLastOpenedAt(documentId.value, openedAtEpochMillis)
    }

    /**
     * [documentId]를 서가에서 완전히 제거한다: Room이 행을 제거하기 전에 저장된 위치를 캡처해
     * 두고, 그다음 모든 인메모리/스크래치/레이아웃 캐시, 표지 파일, 앱이 소유한 materialize된
     * 소스를 제거한다. [DocumentFileSource]가 플랫폼 디렉터리 경계를 강제하므로, 외부의 원본
     * URI는 결코 삭제되지 않는다.
     *
     * @param documentId 삭제할 문서.
     */
    override suspend fun deleteDocument(documentId: DocumentId) {
        val storedLocation = documentDao.getDocument(documentId.value)?.toDocumentMetadata()?.location
        documentDao.deleteDocument(documentId.value)
        invalidateCaches(documentId)
        coverStore.delete(documentId)
        if (storedLocation != null) documentFileSource?.deleteMaterialized(storedLocation)
    }

    /**
     * 선택된 모든 문서와 앱이 소유한 그 영구적/일시적 산출물 전부를 제거한다.
     *
     * 배치 Room 삭제 전에 위치를 읽어 두는 이유는, 제거되는 행이 materialize된 파일로 돌아갈 수
     * 있는 유일한 영구적 매핑이기 때문이다. 존재하지 않는 행은 위치를 제공하지 않으며 무해하게
     * 남는다.
     *
     * @param documentIds 삭제 대상으로 선택된 문서들. 빈 컬렉션이면 아무 일도 하지 않는다.
     */
    override suspend fun deleteDocuments(documentIds: Collection<DocumentId>) {
        if (documentIds.isEmpty()) return
        val storedLocations = documentIds.mapNotNull { documentId ->
            documentDao.getDocument(documentId.value)?.toDocumentMetadata()?.location
        }
        documentDao.deleteDocuments(documentIds.map(DocumentId::value))
        documentIds.forEach { documentId ->
            invalidateCaches(documentId)
            coverStore.delete(documentId)
        }
        documentFileSource?.let { fileSource ->
            storedLocations.forEach { location -> fileSource.deleteMaterialized(location) }
        }
    }

    /**
     * 문서가 다시 쓰이거나(복구, 재임포트) 완전히 삭제될 때마다 사용되는 [documentId]에 대한
     * 전체 캐시 해체: [invalidateDocumentCache]를 통한 인메모리 캐시, 저장된 페이지 레이아웃,
     * 그리고 현재 보유 중인 것이 이 문서의 것이라면 EPUB 스크래치 사본까지.
     *
     * 저장된 레이아웃은 텍스트를 절대 오프셋으로 주소 지정하는데, 문서를 다시 파싱하는 것이
     * 정확히 그 오프셋들을 움직이는 일이다. 문서의 섹션을 다시 쓰는 모든 경로는 먼저 여기를
     * 거치므로, 저장된 레이아웃이 오래되었다는 것을 알아야 하는 곳은 이 한 곳뿐이다.
     *
     * @param documentId 캐시와 저장된 레이아웃을 버릴 문서.
     * @param keepScratchCopy 나머지 캐시들을 버리는 동안에도 이 문서에 대해 현재 보유 중인
     *   EPUB 스크래치 사본을 유지하려면 true — 다음 백그라운드 배치가 여전히 같은 사본을
     *   필요로 하는 0단계 점진적 임포트에서만 사용된다.
     */
    private suspend fun invalidateCaches(
        documentId: DocumentId,
        keepScratchCopy: Boolean = false,
    ) {
        invalidateDocumentCache(documentId)
        pageLayoutDao.deletePageLayouts(documentId.value)
        epubImportCursorLock.withLock {
            epubNextSpineCursorByDocumentId.remove(documentId)
            epubSectionPathByIndexByDocumentId.remove(documentId)
        }
        epubScratchLock.withLock {
            if (!keepScratchCopy) {
                epubScratchInvalidationCount += 1
                if (epubScratchDocumentId == documentId) {
                    epubScratchPath?.let { path -> runCatching { systemFileSystem().delete(path) } }
                    clearEmbeddedFontScratchFilesLocked()
                    epubScratchDocumentId = null
                    epubScratchPath = null
                    epubScratchContainer = null
                }
            }
        }
        cbzScratchLock.withLock {
            if (cbzScratchDocumentId == documentId) {
                cbzScratchPath?.let { path -> runCatching { systemFileSystem().delete(path) } }
                cbzArchive = null
                cbzScratchDocumentId = null
                cbzScratchPath = null
            }
        }
    }

    /**
     * [invalidateCaches]의 인메모리 절반만 — 저장된 페이지 레이아웃이나 EPUB 스크래치 사본은
     * 건드리지 않고 캐시된 문서, 그 섹션-블록 캐시, 캐시된 페이지 윈도우 답만 버린다. 점진적
     * 임포트의 완료 경로들([finishEpubImport], [finishNonProgressiveEpubImport])은
     * [invalidateCaches] 대신 이것을 호출한다: 문서는 실제로 커졌고 다음 읽기가 그것을
     * 봐야 하지만, 저장된 페이지 레이아웃은 정확히 [importNextSections]가 제자리에서 확장하고
     * 있는 대상이고, 스크래치 사본은 정확히 그것이 여전히 읽고 있는 대상이다 — 임포트 도중
     * 둘 중 하나라도 삭제하면 오래된 데이터가 아니라 실제 진행 상황을 버리는 셈이 된다.
     *
     * [documentId]가 현재 캐시된 것과 일치하지 않을 때도 항상 [documentCacheGeneration]을
     * 증가시킨다: 같은 문서에 대해 이미 진행 중인 [getReaderDocument] 로드는 자기 자신의
     * 호출 내부에서 그것을 알 방법이 없으며, 이 증가가 바로 이 무효화보다 먼저 시작된
     * 스냅샷을 그 이후에 캐시에 공개하지 말라고 알려주는 신호이다.
     *
     * @param documentId 현재 캐시된 문서라면 그 인메모리 캐시를 버릴 문서.
     */
    private suspend fun invalidateDocumentCache(documentId: DocumentId) {
        documentCacheLock.withLock {
            documentCacheGeneration += 1
            if (cachedDocumentId == documentId) {
                cachedDocumentId = null
                cachedReaderDocument = null
                cachedSectionBlocks = null
            }
            if (cachedPageWindowKey?.documentId == documentId) {
                cachedPageWindowKey = null
                cachedPageWindows = emptyList()
                cachedPageWindowsAreMeasured = false
            }
            if (paginationSession?.key?.documentId == documentId) {
                paginationSession = null
            }
        }
    }

    /**
     * [metadata]의 문서가 담고 있는 EPUB의 스크래치 사본으로, 한 번 만들어져 이후의 모든 내장
     * 이미지/폰트 추출과 점진적 임포트 배치에 재사용된다.
     *
     * 오직 하나만 유지한다: 리더는 책 한 권만 열려 있으며, 이전 책의 두 번째 사본을 디스크에
     * 들고 있어 봐야 얻는 것이 없다. 이렇게 오래 사는 사본은 `finally`에서 제거할 수 없으므로,
     * 프로세스는 그 경로를 [epubScratchPath]에 들고 있다가 다음 책이 그것을 대체할 때 삭제한다.
     * 프로세스가 죽으면 그 경로는 사라지지만 그 경로가 가리키던 사본은 사라지지 않는다 — 실행당
     * 하나씩, 책 전체 크기만 한 방치된 사본이 남는다. 여기서 더 이상 이름 붙여지지 않은
     * 사본들을 [deleteAbandonedScratchCopies]가 청소하는 것이 큰 책들로 이루어진 서가가 캐시를
     * 채우는 것을 막아준다.
     *
     * **수명 계약.** 반환되는 경로는 스크래치를 교체하거나 삭제하는 동시 코루틴이
     * [epubScratchLock]을 다시 획득하지 않는 동안에만 유효하다([invalidateCaches] 참고). 그
     * 경로를 I/O에 실제로 *사용*해야 하는 호출자는 이 호출이 반환된 직후 곧바로
     * [epubScratchLock]을 다시 획득하고 파일을 건드리기 전에 [epubScratchDocumentId]를 다시
     * 검증해야 한다 — 그렇지 않으면 그 경로가 더 이상 존재하지 않는 파일을 가리킬 수 있음을
     * 받아들여야 한다. [getEmbeddedImages]와 [getEmbeddedFontFiles]는 이 패턴을 따르고,
     * [openEpubScratchContainer]는 락으로 감쌀 수 없는 구간을 위해 ZIP 열기 주위에
     * `runCatching`을 추가한다.
     *
     * **복사가 락 밖에서 실행되는 이유.** 책을 복사하는 것은 여기서 진짜로 느린 유일한
     * 단계이다 — 안드로이드의 SAF를 통해 들어오는 큰 EPUB은 몇 초가 걸린다 — 그 동안
     * [epubScratchLock]을 쥐고 있으면 다른 모든 스크래치 소비자가 그 시간 내내 멈추게 되어,
     * 처음 열 때 삽화 페이지로 넘어가는 것이 복사가 끝날 때까지 막혀버렸다. 그래서 복사는
     * 락 없이 수행되고 결과는 락 아래에서 *설치*되는데, 이 설치는 구분해야 할 세 가지 경우가
     * 있다:
     *
     * - 이 복사가 실행되는 동안 다른 코루틴이 이미 같은 문서에 대해 사용 가능한 스크래치를
     *   확립한 경우: 방금 복사한 파일은 삭제되고 그 확립된 경로가 반환되어, 두 호출자 모두
     *   슬롯을 두고 다투는 대신 하나의 사본으로 수렴한다.
     * - 복사 도중 [invalidateCaches]가 실행된 경우로, 이는 [epubScratchInvalidationCount]가
     *   감지한다: 아무것도 설치되지 않고 복사된 파일은 삭제된다. 경로는 여전히 반환되며, 이후
     *   [epubScratchLock] 안에서 이루어지는 호출자 자신의 재검증이 그 슬롯이 이 문서를
     *   가리키지 않음을 보고 포기한다 — 이것이 삭제된 문서가 부활한 스크래치 사본이 아니라
     *   빈 결과를 내는 방식이다.
     * - 그 외의 경우 복사는 슬롯이 들고 있던 것을 무엇이든 대체하며 설치되는데, 이는 완전히
     *   락으로 감쌌던 버전이 가졌던 것과 동일한 "마지막 쓰기가 이긴다" 동작이다.
     *
     * 이미 확립된 사본을 그저 *재사용*하기만 하는 호출자는 애초에 락을 벗어나지도 않는다: 그
     * 검사가 이 함수가 하는 첫 번째 일이며, 복사에 도달하지도 않고 반환된다.
     *
     * @param metadata EPUB이 속한 문서. 같은 id에 대해 이미 보유 중인 스크래치 사본이 디스크에
     *   여전히 존재하면 그대로 재사용된다.
     * @param fileSource 새 사본이 필요할 때 원본 파일 바이트를 복사해 올 곳.
     * @return 스크래치 사본의 경로. 무효화가 설치를 중단시킨 경우, 이는 이미 삭제된 파일을
     *   가리키며, [epubScratchLock] 아래에서 이루어지는 호출자의 재검증이 그것을 빈 결과로
     *   바꿔준다.
     */
    private suspend fun epubScratchCopy(
        metadata: DocumentMetadata,
        fileSource: DocumentFileSource,
    ): Path {
        epubScratchLock.withLock {
            epubScratchPath?.takeIf { epubScratchDocumentId == metadata.id && systemFileSystem().exists(it) }
                ?.let { return it }
        }

        val invalidationsBeforeCopy = epubScratchLock.withLock { epubScratchInvalidationCount }
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "tedd-reader-epub-open-${Random.nextLong().toString(16)}.epub"
        fileSource.copyTo(metadata.location, path)

        return epubScratchLock.withLock {
            epubScratchPath?.takeIf { epubScratchDocumentId == metadata.id && systemFileSystem().exists(it) }
                ?.let { established ->
                    runCatching { systemFileSystem().delete(path) }
                    return@withLock established
                }

            if (epubScratchInvalidationCount != invalidationsBeforeCopy) {
                runCatching { systemFileSystem().delete(path) }
                return@withLock path
            }

            epubScratchPath?.let { previous -> runCatching { systemFileSystem().delete(previous) } }
            clearEmbeddedFontScratchFilesLocked(keepDocumentId = metadata.id)
            epubScratchContainer = null
            deleteAbandonedScratchCopies(keep = path)
            deleteAbandonedEmbeddedFontScratchFiles(keep = epubEmbeddedFontFilesByHref.values.toSet())
            epubScratchDocumentId = metadata.id
            epubScratchPath = path
            path
        }
    }

    /**
     * [path]에 있는 [documentId]의 스크래치 사본에 대한 [EpubImportContainer]를 반환한다(없으면
     * 만들어서 캐시한다). 점진적 임포트가 배치마다 OPF를 다시 파싱하지 않고 스파인 항목을
     * 순회하는 데 사용한다.
     *
     * 캐시된 컨테이너가 일치하면 즉시 반환된다. 캐시 미스인 경우, 컨테이너는 OPF/매니페스트
     * 파싱 동안 뮤텍스를 쥐고 있지 않도록 [epubScratchLock] 밖에서 만들어진다. 이 파싱은 수천
     * 개의 스파인 항목을 가진 EPUB에서는 상당한 시간이 걸릴 수 있다. 문서 ID와 경로 재검증이
     * 양쪽을 보호한다:
     *
     * - ZIP을 열기 전: [epubScratchDocumentId]와 [epubScratchPath]가 여전히 [documentId]와
     *   [path]에 일치해야 하며, 이는 스크래치 파일이 동시에 발생한 [invalidateCaches]에 의해
     *   삭제되거나 교체되지 않았음을 확인한다.
     * - 컨테이너를 만든 뒤: 같은 검사로 결과를 캐시할 가치가 있는지 결정한다(파싱 도중 동시에
     *   무효화가 실행되면 컨테이너가 오래된 것이 된다).
     *
     * OPF가 발견되지 않은 경우(비점진적 폴백)와 진행 중에 스크래치 사본이 무효화된 경우 모두
     * null을 반환한다 — 호출자([importNextSections])는 두 경우 모두 "더 이상 점진적으로
     * 임포트할 것이 없다"로 취급하고 [finishNonProgressiveEpubImport]를 통해 문서를 완료한다.
     *
     * @param documentId 컨테이너를 열거나 재사용할 문서.
     * @param path 이 문서에 대해 [epubScratchCopy]가 반환한 스크래치 사본 경로.
     * @param title OPF에 제목이 없을 때 컨테이너에 쓸 대체 제목.
     * @return 컨테이너, OPF가 없거나 스크래치가 무효화되었으면 null.
     */
    private suspend fun openEpubScratchContainer(
        documentId: DocumentId,
        path: Path,
        title: String,
    ): EpubImportContainer? {
        epubScratchLock.withLock {
            if (epubScratchDocumentId == documentId && epubScratchPath == path) {
                epubScratchContainer?.let { return it }
            }
            if (epubScratchDocumentId != documentId || epubScratchPath != path) return null
        }
        val zip = runCatching { systemFileSystem().openZip(path) }.getOrNull() ?: return null
        val container = openEpubImportContainer(zip, title)
        epubScratchLock.withLock {
            if (epubScratchDocumentId == documentId && epubScratchPath == path) {
                epubScratchContainer = container
            }
        }
        return container
    }

    private suspend fun clearEpubScratchContainer(documentId: DocumentId) {
        epubScratchLock.withLock {
            if (epubScratchDocumentId == documentId) epubScratchContainer = null
        }
    }

    /**
     * [metadata]의 CBZ에 대한 [ComicArchive]로, 한 번 만들어져 같은 문서에 대한 이후의 모든
     * 페이지/표지 요청에 재사용된다. 생성, 사용, 교체가 직렬화된 상태로 유지되도록
     * [cbzScratchLock]이 걸린 상태에서 호출해야 한다 — 이것이 페이지 윈도우 요청이 문서
     * 전환이나 무효화가 삭제하고 있는 스크래치 파일을 결코 읽을 수 없는 이유이다.
     *
     * 다른 문서(또는 디스크에서 사라진 스크래치 사본)는 사본과 열린 아카이브 둘 다를
     * 교체한다: 이전 스크래치 파일이 삭제되고, [DocumentFileSource.copyTo]를 통해 새 사본이
     * 스트리밍되며, [deleteAbandonedComicScratchCopies]가 이전 프로세스가 남긴 사본(프로세스가
     * 죽으면 그 경로는 사라지지만 파일은 사라지지 않는다)을 청소하고, 새 아카이브가 새 사본
     * 위에서 열린다. 같은 문서는 보유 중인 사본과 아카이브를 그대로 재사용한다.
     *
     * @param metadata 아카이브를 열 CBZ. 같은 id에 대해 이미 보유 중인 사본이 디스크에 여전히
     *   존재하면 재사용된다.
     * @param fileSource 새 사본이 필요할 때 원본 파일 바이트를 복사해 올 곳.
     * @return [metadata]에 대해 재사용 가능한 [ComicArchive].
     */
    private suspend fun cbzArchiveLocked(
        metadata: DocumentMetadata,
        fileSource: DocumentFileSource,
    ): ComicArchive {
        cbzArchive?.takeIf { cbzScratchDocumentId == metadata.id && cbzScratchPath?.let(systemFileSystem()::exists) == true }
            ?.let { return it }

        cbzScratchPath?.let { previous -> runCatching { systemFileSystem().delete(previous) } }
        cbzArchive = null
        cbzScratchDocumentId = null
        cbzScratchPath = null
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "$ComicScratchCopyPrefix${Random.nextLong().toString(16)}.cbz"
        deleteAbandonedComicScratchCopies(keep = path)
        return try {
            fileSource.copyTo(metadata.location, path)
            val archive = comicBookDocumentParser.openArchive(path)
            cbzScratchDocumentId = metadata.id
            cbzScratchPath = path
            cbzArchive = archive
            archive
        } catch (throwable: Throwable) {
            runCatching { systemFileSystem().delete(path) }
            throw throwable
        }
    }

    private fun clearEmbeddedFontScratchFilesLocked(keepDocumentId: DocumentId? = null) {
        if (keepDocumentId != null && epubScratchDocumentId == keepDocumentId) return
        epubEmbeddedFontFilesByHref.values.forEach { path -> runCatching { systemFileSystem().delete(path) } }
        epubEmbeddedFontFilesByHref.clear()
    }

    private fun streamEmbeddedFontScratchFile(
        zip: FileSystem,
        href: String,
    ): Path? {
        val suffix = href.substringAfterLast('/', missingDelimiterValue = href)
            .takeIf(String::isNotBlank)
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?: "font.bin"
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "$EmbeddedFontScratchPrefix${Random.nextLong().toString(16)}-$suffix"
        val written = runCatching {
            val source = zip.source(href.toPath()).buffer()
            try {
                val sink = systemFileSystem().sink(path).buffer()
                try {
                    var totalBytes = 0L
                    while (true) {
                        val read = source.read(sink.buffer, 8_192)
                        if (read == -1L) break
                        totalBytes += read
                        if (totalBytes > MAX_EPUB_FONT_BYTES) throw IllegalStateException("Embedded font too large: $href")
                        sink.emitCompleteSegments()
                    }
                    sink.flush()
                } finally {
                    sink.close()
                }
            } finally {
                source.close()
            }
        }.isSuccess
        if (!written) {
            runCatching { systemFileSystem().delete(path) }
            return null
        }
        return path
    }

    /**
     * [documentId]의 저장된 모든 섹션을, 각 섹션의 블록 JSON은 뺀 채로 로드한다 — 그 JSON은
     * 실제로 무언가가 요청하는 섹션에 대해서만 [SectionBlocksCache]가 다시 가져온다.
     * `blocksJson`은 이 행에서 의도적으로 제외되어 있다([SearchIndexDao.getDocumentSectionsWithoutBlocks]
     * 참고): 큰 책에서는 그것이 다른 모든 컬럼을 합친 것보다 훨씬 크며, 예전에는 책을 여는
     * 것만으로 페이지 하나 만들기도 전에 그 전부를 문자열로 메모리에 끌어왔다.
     *
     * @param documentId 저장된 섹션을 로드할 문서.
     * @return 저장된 섹션들, 그 온디맨드 블록 캐시, 그리고 섹션 0에 실려 있는 문서 수준의
     *   제목/내비게이션/파서 버전.
     */
    private suspend fun getStoredSections(documentId: DocumentId): StoredReaderDocument {
        val readStarted = TimeSource.Monotonic.markNow()
        val entries = searchIndexDao.getDocumentSectionsWithoutBlocks(documentId.value)
        val readMs = readStarted.elapsedNow().inWholeMilliseconds
        logger.d {
            "stored sections: ${entries.size} rows read in $readMs ms, ${entries.sumOf { it.text.length }} chars"
        }
        return StoredReaderDocument(
            sections = entries.map { entry ->
                ReaderSection(
                    index = entry.sectionIndex,
                    text = entry.text,
                    range = TextRange(entry.startOffset, entry.endOffset),
                    title = entry.sectionTitle,
                )
            },
            sectionBlocks = SectionBlocksCache(documentId, entries.map { it.sectionIndex }, searchIndexDao, ::decodeBlocks),
            title = entries.firstNotNullOfOrNull { it.documentTitle },
            navigationJson = entries.firstOrNull()?.navigationJson.orEmpty(),
            parserVersion = entries.firstOrNull()?.parserVersion ?: CurrentReaderParserVersion,
        )
    }

    /**
     * [metadata]의 파일을 처음부터 다시 읽어 TXT 문서로 다시 영속화한다 — 저장된 섹션이 비어
     * 있거나 깨진 텍스트를 담고 있을 때([hasBrokenText] 참고) [loadReaderDocument]가 폴백하는
     * 복구 방법이다.
     *
     * @param metadata 복구할 서가 항목. 그 위치가 파일을 다시 읽어 올 곳이다.
     * @return 새로 파싱된 문서, [documentFileSource]를 사용할 수 없거나 다시 읽기/파싱이
     *   실패하면 null.
     */
    private suspend fun repairTxtDocument(metadata: DocumentMetadata): ReaderDocument? {
        val fileSource = documentFileSource ?: return null
        return suspendRunCatching {
            val document = txtDocumentParser.parse(
                id = metadata.id,
                title = metadata.location.displayName,
                text = TxtTextDecoder.decode(fileSource.readBytes(metadata.location)),
            )
            persistParsedDocument(
                metadata = metadata.copy(
                    format = document.format,
                    pageCount = document.pageCount,
                    characterCount = document.characterCount,
                    wordCount = document.wordCount,
                ),
                document = document,
            )
            document
        }.getOrNull()
    }

    /**
     * 더 오래된 파서가 저장된 텍스트를 기록한 책(CurrentReaderParserVersion 참고)을, 갓 선택된
     * EPUB이 받는 것과 똑같은 단계적 임포트에 넘겨서 다시 읽는다: 표지와 첫 챕터가 파싱되어
     * 커밋되고, 그것이 호출자에게 주어지는 것이며, 나머지 스파인은 새 임포트 이후와 정확히
     * 똑같은 방식으로 [importNextSections]가 백그라운드에서 이어 붙이도록 남겨진다.
     *
     * 예전에는 이것이 파일 전체를 메모리로 읽어 들이고 리더가 무언가를 그리도록 허용되기 전에
     * 모든 챕터를 파싱했다 — 리더가 이미 읽고 있던 책을 다음에 열 때 20~40초 동안 아무것도
     * 보여주지 못했으며, 이것이 파서 버전을 1에 고정시키고 파서의 모든 개선이 이미 서가에
     * 있는 책들에게는 미치지 못하게 만든 원인이었다.
     *
     * 바이트가 없는 [DocumentImportSource]가 바로 단계적 경로를 선택하게 만드는 것이며([importDocument]
     * 참고), 기존 [metadata]를 "기존 문서"로 그대로 넘기는 것이 리더가 인식하는 서가 항목을
     * 유지시켜 준다: 언제 추가됐는지, 즐겨찾기인지, 어느 폴더에 속하는지. 복구는 새 임포트가
     * 아니므로, 아래로 전달되는 `importedAtEpochMillis`는 지금 이 순간이 아니라 리더 자신의
     * `lastOpenedAtEpochMillis` 이력이다. 어떤 이유로든 그것을 한 번도 기록한 적 없는 문서만
     * 지금 시각으로 폴백한다.
     *
     * @param metadata 복구할 서가 항목. 그 위치가 파일을 다시 읽어 올 곳이다.
     * @return 새로 임포트된(0단계) 문서, [documentFileSource]를 사용할 수 없거나 다시
     *   읽기/파싱이 실패하면 null.
     */
    private suspend fun repairEpubDocument(metadata: DocumentMetadata): ReaderDocument? {
        val fileSource = documentFileSource ?: return null
        return suspendRunCatching {
            importEpubPhase0(
                id = metadata.id,
                source = DocumentImportSource(location = metadata.location, bytes = null),
                existingDocument = metadata,
                importedAtEpochMillis = metadata.lastOpenedAtEpochMillis
                    ?: Clock.System.now().toEpochMilliseconds(),
                fileSource = fileSource,
            )
        }.getOrNull()
    }

    /**
     * 호출자가 이미 파일 전체를 메모리에 가지고 있을 때의 EPUB 임포트 경로 — 동기적인 전체
     * 파싱으로, 점진적 임포트가 존재하기 전에 EPUB 임포트가 하던 것과 정확히 같다. 바이트가
     * 이미 손에 있는 이상 피해야 할 스트리밍 임포트 비용이 남아 있지 않으므로, 이것을
     * 단계화해서 얻는 것이 없다. [persistParsedDocument]의 기본
     * `importCompletedAtEpochMillis`(지금)는 이 함수가 항상 한 번의 호출로 책 전체를
     * 끝내기 때문에 그대로 정확하다.
     *
     * @param id 임포트할 대상의 id.
     * @param source 임포트 소스. 그 위치는 표시 제목과 영속화되는 [DocumentMetadata.location]에
     *   사용된다.
     * @param existingDocument [id]에 대해 이미 기록된 서가 항목이 있다면 그것 — 그
     *   `addedAtEpochMillis`, 즐겨찾기 상태, 폴더가 그대로 이어진다.
     * @param importedAtEpochMillis 이 임포트가 일어난 시각.
     * @param bytes 이미 메모리에 있는 EPUB 파일 전체.
     * @return 완전히 파싱된 문서.
     */
    private suspend fun importEpubFullyFromBytes(
        id: DocumentId,
        source: DocumentImportSource,
        existingDocument: DocumentMetadata?,
        importedAtEpochMillis: Long,
        bytes: ByteArray,
    ): ReaderDocument {
        val parsed = epubDocumentParser.parseWithCover(id = id, title = source.location.displayName, bytes = bytes)
        persistParsedDocument(
            metadata = DocumentMetadata(
                id = id,
                location = source.location,
                format = parsed.document.format,
                addedAtEpochMillis = existingDocument?.addedAtEpochMillis ?: importedAtEpochMillis,
                lastOpenedAtEpochMillis = importedAtEpochMillis,
                pageCount = parsed.document.pageCount,
                characterCount = parsed.document.characterCount,
                wordCount = parsed.document.wordCount,
                isBookmarked = existingDocument?.isBookmarked ?: false,
                folderId = existingDocument?.folderId,
                folderName = existingDocument?.folderName,
            ),
            document = parsed.document,
            coverBytes = parsed.coverBytes,
        )
        return parsed.document
    }

    /**
     * 점진적 EPUB 임포트의 0/1단계(뒤따르는 배치들은 [importNextSections], 마지막 단계는
     * [finishEpubImport] 참고): 선택된 파일을 앱 전용 저장소로 한 번 스트리밍하고
     * ([epubScratchCopy]를 통해), 컨테이너/OPF만 파싱하고 표지 결정을 확정한다 — 이를 나중에
     * 결정하면 그 이후의 모든 오프셋이 밀린다 — 널 스파인 항목은 건너뛰고 미리읽기를
     * [InitialReadAheadMaxSpineItems] 스파인 슬롯으로 제한하면서 적어도
     * [InitialReadAheadMinimumContentChars]개의 읽을 수 있는 비공백/비객체 문자가 버퍼링될
     * 때까지 파싱한 뒤, 문서 행과 그 초기 섹션들을 커밋한다. 스파인 전체가 이 한정된
     * 미리읽기에 다 들어가는 것으로 밝혀지지 않는 한 [DocumentMetadata.characterCount]는
     * null로 남고 `documents.importCompletedAtEpochMillis`도 설정되지 않은 채로 남아서, 결코
     * 임포트를 끝내지 못하는 책이 잘못된 것이 아니라 미완료로 읽히도록 한다. `bytes=null`일
     * 때만 도달한다([importDocument] 참고) — 이 경우 스트리밍할 실제 [fileSource]가 항상
     * 있다.
     *
     * EPUB에 OPF가 아예 없을 때(`container == null`), 기존의 폴백 챕터 파싱
     * ([EpubDocumentParser.parseWithCover])이 바로 이 같은 스크래치 사본에서 찾을 수 있는
     * 모든 챕터를 이미 읽고 레이아웃하므로, 스트리밍할 스파인이 남아 있지 않고 이 분기에는
     * 점진적일 것이 전혀 없다 — 다른 어떤 포맷과 마찬가지로 한 번의 호출로 완전히
     * 임포트된 것으로 취급된다.
     *
     * 그렇지 않으면 표지 섹션(있다면)과, [InitialReadAheadMinimumContentChars]만큼의 실제
     * 텍스트에 도달하기에 충분한 만큼의 읽을 수 있는 스파인 섹션들만 파싱한다
     * ([InitialReadAheadMaxSpineItems] 스파인 슬롯으로 제한됨). 이는 나중에
     * [importNextSections]의 배치가 자신이 맡은 스파인 조각에 대해 하는 것과 정확히
     * 같다 — 다만 이 첫 호출은 표지 결정과 문서의 초기 제목/내비게이션 대역도 함께
     * 확정한다는 점이 다르다. 그 한정된 미리읽기로 스파인 전체가 소진됐다면 이미 책
     * 전체를 커버한 것이며 — 저장되는 내용 면에서는 항상 한 번에 임포트하는 다른 어떤
     * 포맷과 다를 바 없다. `isFullyImported`가 정확히 그것을 포착한다.
     *
     * @param id 임포트할 대상의 id.
     * @param source 임포트 소스. 그 위치는 표시 제목과 영속화되는 [DocumentMetadata.location]에
     *   사용된다.
     * @param existingDocument [id]에 대해 이미 기록된 서가 항목이 있다면 그것 — 그
     *   `addedAtEpochMillis`, 즐겨찾기 상태, 폴더가 그대로 이어진다.
     * @param importedAtEpochMillis 이 임포트(또는 복구)가 일어난 시각.
     * @param fileSource 원본 EPUB 바이트를 스트리밍해 올 곳.
     * @return 이 첫 단계 이후 알려진 문서 — 책 전체가 그 안에 들어가지 않는 한 표지와/또는
     *   첫 번째 한정된 읽을 수 있는 스파인 섹션들뿐이며, 다 들어간 경우에는 완전한 문서이다.
     */
    private suspend fun importEpubPhase0(
        id: DocumentId,
        source: DocumentImportSource,
        existingDocument: DocumentMetadata?,
        importedAtEpochMillis: Long,
        fileSource: DocumentFileSource,
    ): ReaderDocument {
        val title = source.location.displayName
        val scratchMetadata = DocumentMetadata(
            id = id,
            location = source.location,
            format = DocumentFormat.EPUB,
            addedAtEpochMillis = existingDocument?.addedAtEpochMillis ?: importedAtEpochMillis,
        )
        val path = epubScratchCopy(scratchMetadata, fileSource)
        val container = openEpubScratchContainer(id, path, title)

        val isFullyImported: Boolean
        val document: ReaderDocument
        val coverBytes: ByteArray?
        var phase0NextSpinePosition = 0
        var phase0SectionPaths: Map<Int, String> = emptyMap()
        if (container == null) {
            val parsed = epubDocumentParser.parseWithCover(id = id, title = title, path = path, fileSystem = systemFileSystem())
            document = parsed.document
            coverBytes = parsed.coverBytes
            isFullyImported = true
        } else {
            val sections = mutableListOf<ReaderSection>()
            val blocks = mutableListOf<ReaderBlock>()
            val sectionPathByIndex = mutableMapOf<Int, String>()
            val coverSectionIndex = 0.takeIf { container.coverDecision.hasCoverSection }
            buildEpubCoverSection(container.coverDecision, container.documentTitle)?.let { cover ->
                sections += cover.section
                blocks += cover.blocks
                container.coverDecision.coverHref?.let { sectionPathByIndex[cover.section.index] = it }
            }
            var spinePosition = 0
            var bufferedContentChars = 0
            var spineItemsReadAhead = 0
            while (
                spinePosition < container.linearSpineItems.size &&
                spineItemsReadAhead < InitialReadAheadMaxSpineItems &&
                bufferedContentChars < InitialReadAheadMinimumContentChars
            ) {
                parseEpubSpineItem(
                    container = container,
                    spinePosition = spinePosition,
                    sectionIndex = sections.size,
                    baseOffset = sections.lastOrNull()?.let { it.range.end + SectionSeparatorLength } ?: 0L,
                )?.let { parsed ->
                    sections += parsed.section
                    blocks += parsed.blocks
                    sectionPathByIndex[parsed.section.index] = container.linearSpineItems[spinePosition].path
                    bufferedContentChars += parsed.section.text.count { char ->
                        !char.isWhitespace() && char != ReaderObjectReplacementChar
                    }
                }
                spinePosition += 1
                spineItemsReadAhead += 1
            }
            phase0NextSpinePosition = spinePosition
            fillIntrinsicImageSizes(blocks, container.zip, container.coverDecision.coverHref, container.coverDecision.coverBytes)
            isFullyImported = spinePosition >= container.linearSpineItems.size
            val navigation = if (isFullyImported) {
                resolveEpubNavigationAtCompletion(
                    container = container,
                    sectionPathByIndex = sectionPathByIndex,
                    coverSectionIndex = coverSectionIndex,
                    firstReadableContentSectionIndex = sections.firstOrNull {
                        it.index != coverSectionIndex && it.text.isNotBlank()
                    }?.index,
                )
            } else {
                ReaderNavigation()
            }
            val titledSections = if (navigation.items.isEmpty()) {
                sections
            } else {
                val titlesByIndex = navigation.items
                    .asSequence()
                    .filter { it.offset == 0L }
                    .associate { it.spineIndex to it.title }
                sections.map { section ->
                    titlesByIndex[section.index]?.let { section.copy(title = it) } ?: section
                }
            }
            document = ReaderDocument(
                id = id,
                format = DocumentFormat.EPUB,
                title = container.documentTitle,
                sections = titledSections,
                blocks = blocks,
                navigation = navigation,
            )
            phase0SectionPaths = sectionPathByIndex
            coverBytes = container.coverDecision.coverBytes
        }

        persistParsedDocument(
            metadata = DocumentMetadata(
                id = id,
                location = source.location,
                format = DocumentFormat.EPUB,
                addedAtEpochMillis = existingDocument?.addedAtEpochMillis ?: importedAtEpochMillis,
                lastOpenedAtEpochMillis = importedAtEpochMillis,
                pageCount = document.pageCount.takeIf { isFullyImported },
                characterCount = document.characterCount,
                wordCount = document.wordCount,
                isBookmarked = existingDocument?.isBookmarked ?: false,
                folderId = existingDocument?.folderId,
                folderName = existingDocument?.folderName,
            ),
            document = document,
            coverBytes = coverBytes,
            importCompletedAtEpochMillis = if (isFullyImported) Clock.System.now().toEpochMilliseconds() else null,
            keepScratchCopy = true,
            embeddedFontHrefsJson = json.encodeToString(extractFontHrefs(document.blocks)),
            sectionSourcePaths = phase0SectionPaths,
        )
        if (isFullyImported) {
            clearEpubScratchContainer(id)
        } else {
            rememberNextSpineCursor(id, phase0NextSpinePosition)
            rememberSectionPaths(id, phase0SectionPaths)
        }
        return document
    }

    /**
     * [documentId]의 임포트가 완전히 끝났는지 여부 — 모든 EPUB 스파인 항목이 파싱되어
     * 저장되었거나, 다른 어떤 포맷의 일회성 임포트가 이미 완료되었는지.
     *
     * @param documentId 확인할 문서.
     * @return 이 문서에 대해 `documents.importCompletedAtEpochMillis`가 설정되어 있으면 true.
     */
    override suspend fun isImportComplete(documentId: DocumentId): Boolean =
        documentDao.getDocument(documentId.value)?.importCompletedAtEpochMillis != null

    /**
     * 완료된 접두사 텍스트를 다시 읽지 않고 점진적 임포트가 확장해 나가는, 영속화된 빌드
     * 정보.
     *
     * @property characterCount 다음 배치 전에 저장된 모든 섹션의 문자 수.
     * @property wordCount 다음 배치 전에 저장된 모든 섹션의 단어 수.
     * @property embeddedFontHrefs 다음 배치 전에 저장된 모든 블록이 참조하는 정확한 폰트 href들.
     */
    private data class ImportBuildState(
        val characterCount: Long,
        val wordCount: Long,
        val embeddedFontHrefs: Set<String>,
    )

    /**
     * 새 임포트 배치가 시작하는 누산값들을 해석한다. 버전 9 임포트는 문서 행에서 그것들을
     * 직접 읽는다. 더 오래된 스키마에서 중단된 문서는 누산값이 null이므로, 마이그레이션
     * 경계에서 재개하는 이 한 번만 새 섹션이 추가되기 전에 저장된 섹션과 블록으로부터
     * 그것들을 재구성한다. 이후의 모든 배치는 인덱싱된 경로로 돌아간다.
     *
     * @param documentId 재개되는 점진적 EPUB.
     * @param entity 새 배치가 저장되기 전의 행.
     * @return 안전한 append 계산을 위한 완전한 접두사 개수와 폰트 참조.
     */
    private suspend fun resolveImportBuildState(
        documentId: DocumentId,
        entity: DocumentEntity,
    ): ImportBuildState {
        val indexedFonts = entity.embeddedFontHrefsJson?.let { encoded ->
            runCatching { json.decodeFromString<List<String>>(encoded).toSet() }.getOrNull()
        }
        val indexedCharacterCount = entity.characterCount
        val indexedWordCount = entity.wordCount
        if (indexedCharacterCount != null && indexedWordCount != null && indexedFonts != null) {
            return ImportBuildState(indexedCharacterCount, indexedWordCount, indexedFonts)
        }
        val document = getReaderDocument(documentId)
        val fontHrefs = indexedFonts ?: getReferencedEmbeddedFontHrefs(documentId)
        return ImportBuildState(
            characterCount = indexedCharacterCount ?: document?.characterCount ?: 0L,
            wordCount = indexedWordCount ?: document?.wordCount ?: 0L,
            embeddedFontHrefs = fontHrefs,
        )
    }

    /**
     * 새 임포트 배치가 도착하기 전에 존재하는 문서 접두사에 대해 측정된 레이아웃을 완성하고
     * 저장한다. 살아 있는 페이지네이션 세션을 재사용하면 패널이 이미 측정한 섹션들이
     * 보존된다. 이 함수가 반환하고 나면, 새 배치는 그 접두사를 다시 측정하지 않고 자신의
     * 페이지 시작점만 append할 수 있다.
     *
     * @param documentId 현재 접두사가 저장된 레이아웃을 가지고 있어야 하는 문서.
     * @param style 들어오는 배치와 공유하는 레이아웃 스타일.
     * @param viewportSize 들어오는 배치와 공유하는 측정된 뷰포트.
     * @param viewportDensity 페이지네이션 내부의 추정 전용 헬퍼가 사용하는 밀도.
     * @param pageBreaker 이 스타일과 뷰포트에 대한 실제 텍스트 측정기.
     * @param expectedCharacterCount 현재 저장된 접두사의 정확한 문자 수.
     */
    private suspend fun ensurePartialLayoutForCurrentPrefix(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        viewportDensity: Float,
        pageBreaker: ReaderPageBreaker,
        expectedCharacterCount: Long,
    ) {
        val layoutKey = style.layoutKey()
        val stored = pageLayoutDao.getPageLayout(
            documentId = documentId.value,
            fontSizeSp = layoutKey.fontSizeSp,
            lineHeightMultiplier = layoutKey.lineHeightMultiplier,
            fontFamilyName = layoutKey.fontFamilyName.orEmpty(),
            viewportWidthPx = viewportSize.widthPx,
            viewportHeightPx = viewportSize.heightPx,
        )
        if (stored?.isPartial == true && stored.characterCount == expectedCharacterCount) return
        if (stored != null) pageLayoutDao.deletePageLayouts(documentId.value)

        getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
            viewportDensity = viewportDensity,
            pageBreaker = pageBreaker,
            anchorOffset = null,
        )
        while (true) {
            val progress = continuePagination(
                documentId = documentId,
                style = style,
                viewportSize = viewportSize,
                viewportDensity = viewportDensity,
                pageBreaker = pageBreaker,
            )
            if (progress.isComplete) return
        }
    }

    /**
     * 점진적 EPUB 임포트의 한 배치: 마지막 배치(또는 [importEpubPhase0])가 멈춘 지점부터
     * 최대 [count]개의 스파인 항목을 더 파싱하고, 그것들을 저장하고, 책 전체를 다시 측정하는
     * 대신 저장된 페이지 레이아웃을 제자리에서([appendMeasuredPageStarts]를 통해) 확장하며,
     * 스파인 전체가 마침내 소진됐을 때만 [finishEpubImport]를 실행해 내비게이션을 해석하고
     * 문서를 완료로 스탬프 찍는다. 각 배치는 문자/단어 수와 정확한 폰트 href들을 점진적으로
     * 영속화한다. 목차 항목이 어떤 스파인 항목이든 가리킬 수 있으므로, 내비게이션과 섹션
     * 제목 해석만은 완료될 때까지 기다린다.
     *
     * [documentId]가 서가에 없거나, 그 임포트가 이미 완료됐거나, EPUB이 아니거나,
     * [documentFileSource]를 사용할 수 없으면 이미 완료된 것으로 보고하는 아무 일도 하지
     * 않는 호출이다. EPUB에 OPF가 아예 없을 때는 [importEpubPhase0]의 폴백 챕터 분기가 이미
     * 임포트해야 할 모든 것을 한 번에 임포트했으므로, 여기 남은 유일한 일은 그 분기가
     * 건너뛴 완료 스탬프뿐이며, 이는 [finishNonProgressiveEpubImport]가 처리한다.
     *
     * 아래 파싱 루프에서: `parsed` 결과가 null인 경우(순수 표지 건너뜀, 또는 읽을 수 없는
     * 항목)는 [importEpubPhase0]의 일회성 루프와 마찬가지로 섹션이 되지 않은 채 스파인
     * 슬롯 하나를 소비한다. `relativeBlocks`는 파싱된 블록들을 이 지점부터 섹션 상대적으로
     * 저장되도록 옮기는데, 이는 [persistParsedDocument] 자신의 섹션들과 같다
     * (`TextPageLayoutEngine.sectionPageRanges` 참고) — 아래 [appendMeasuredPageStarts]는
     * 이제 [parseEpubSpineItem]이 돌려주는 절대 형태가 아니라 바로 그 상대 형태를 기대한다.
     *
     * @param documentId 계속 임포트할 문서.
     * @param count 이 호출에서 더 파싱할 스파인 항목 수.
     * @param style 새로 임포트되는 섹션들의 페이지를 측정할 스타일.
     * @param viewportSize 새로 임포트되는 섹션들의 페이지를 측정할 뷰포트.
     * @param pageBreaker 저장된 레이아웃을 확장하는 데 쓸 실제 페이지 분할 측정, 또는 저장된
     *   레이아웃을 확장하지 않고 텍스트만 임포트하려면 null.
     * @return 임포트가 이제 완료됐는지 여부와, 이 호출이 실제로 임포트한 섹션 수.
     */
    override suspend fun importNextSections(
        documentId: DocumentId,
        count: Int,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float,
    ): ImportProgress = withContext(Dispatchers.Default) {
        val entity = documentDao.getDocument(documentId.value)
            ?: return@withContext ImportProgress(isComplete = true, sectionsImported = 0)
        val fileSource = documentFileSource
        if (entity.importCompletedAtEpochMillis != null || entity.format != DocumentFormat.EPUB.name || fileSource == null) {
            return@withContext ImportProgress(isComplete = true, sectionsImported = 0)
        }

        val path = epubScratchCopy(entity.toDocumentMetadata(), fileSource)
        val container = openEpubScratchContainer(documentId, path, entity.name)
            ?: return@withContext finishNonProgressiveEpubImport(documentId, entity)
        var buildState = resolveImportBuildState(documentId, entity)
        if (pageBreaker != null) {
            ensurePartialLayoutForCurrentPrefix(
                documentId = documentId,
                style = style,
                viewportSize = viewportSize,
                viewportDensity = viewportDensity,
                pageBreaker = pageBreaker,
                expectedCharacterCount = buildState.characterCount,
            )
        }

        val lastSection = searchIndexDao.getLastSection(documentId.value)
        var sectionIndex = (lastSection?.sectionIndex?.plus(1)) ?: 0
        var offset = lastSection?.endOffset?.plus(SectionSeparatorLength) ?: 0L
        var spinePosition = resolveNextSpineCursor(documentId, container, sectionIndex)

        val newEntries = mutableListOf<SearchIndexEntity>()
        val newSections = mutableListOf<Pair<ReaderSection, List<ReaderBlock>>>()
        val sectionPathByIndex = mutableMapOf<Int, String>()
        var sectionsImported = 0
        while (sectionsImported < count && spinePosition < container.linearSpineItems.size) {
            val parsed = parseEpubSpineItem(container, spinePosition, sectionIndex, offset)
            spinePosition += 1
            if (parsed == null) continue
            val blocks = parsed.blocks.toMutableList()
            fillIntrinsicImageSizes(blocks, container.zip, container.coverDecision.coverHref, container.coverDecision.coverBytes)
            val relativeBlocks = blocks.rebasedBy(parsed.section.range.start)
            val spinePath = container.linearSpineItems[spinePosition - 1].path
            sectionPathByIndex[parsed.section.index] = spinePath
            newEntries += parsed.section.toSearchIndexEntity(
                documentId = documentId,
                blocks = relativeBlocks,
                json = json,
                sourcePath = spinePath,
            )
            newSections += parsed.section to relativeBlocks
            offset = parsed.section.range.end + SectionSeparatorLength
            sectionIndex += 1
            sectionsImported += 1
        }

        if (newEntries.isNotEmpty()) {
            val batchStarted = TimeSource.Monotonic.markNow()
            val expectedExistingCharacterCount = buildState.characterCount
            val batchCharCount = newSections.sumOf { (section, _) -> section.text.length.toLong() }
            val batchWordCount = newSections.sumOf { (section, _) -> section.text.wordCount().toLong() }
            val batchFontHrefs = extractFontHrefs(newSections.flatMap { (_, blocks) -> blocks })
            val mergedFontHrefs = buildState.embeddedFontHrefs + batchFontHrefs
            buildState = ImportBuildState(
                characterCount = expectedExistingCharacterCount + batchCharCount,
                wordCount = buildState.wordCount + batchWordCount,
                embeddedFontHrefs = mergedFontHrefs,
            )
            searchIndexDao.upsertImportBatch(
                documentDao = documentDao,
                entries = newEntries,
                documentId = documentId.value,
                characterCount = buildState.characterCount,
                wordCount = buildState.wordCount,
                embeddedFontHrefsJson = json.encodeToString(mergedFontHrefs.sorted()),
            )
            rememberSectionPaths(documentId, sectionPathByIndex)
            appendMeasuredPageStarts(
                documentId = documentId,
                style = style,
                viewportSize = viewportSize,
                viewportDensity = viewportDensity,
                pageBreaker = pageBreaker,
                newSections = newSections,
                expectedExistingCharacterCount = expectedExistingCharacterCount,
            )
            logger.d {
                "${entity.name.take(12)}: import batch $sectionsImported sections, " +
                    "+$batchCharCount chars, ${buildState.embeddedFontHrefs.size} fonts indexed " +
                    "in ${batchStarted.elapsedNow().inWholeMilliseconds} ms"
            }
        }
        rememberNextSpineCursor(documentId, spinePosition)

        val isComplete = spinePosition >= container.linearSpineItems.size
        if (!isComplete) return@withContext ImportProgress(isComplete = false, sectionsImported = sectionsImported)

        finishEpubImport(documentId, entity, container, buildState)
        ImportProgress(isComplete = true, sectionsImported = sectionsImported)
    }

    /**
     * [TextPageLayoutEngine.pageStartsForSection]를, 실제 측정이 허용되는 단 하나의
     * 디스패처([ReaderPageMeasureDispatcher]) 위에서 실행한다 — 측정하는 모든 호출 지점이
     * 거쳐 가는 단일 병목이므로, 어떤 플랫폼의 텍스트 스택 스레딩 규칙도 어느 호출자가
     * 측정했는지에 좌우되지 않는다. 추정 전용 호출([pageBreaker]가 null)은 텍스트를
     * 레이아웃하지 않고 호출자의 디스패처에 그대로 머무른다.
     *
     * @param section 페이지 시작점이 필요한 콘텐츠 섹션.
     * @param sectionBlocks 측정된 스타일을 담고 있는 섹션 상대적 블록들.
     * @param style 측정에 사용할 리더 타이포그래피.
     * @param viewportSize 줄 바꿈에 사용할 패널 크기.
     * @param pageBreaker 실제 텍스트 측정기, 또는 추정 전용 시작점이면 null.
     * @param viewportDensity 텍스트 레이아웃 엔진이 사용하는 패널 밀도.
     * @return 절대 페이지 시작점들과, 실제 브레이커가 만들어낸 것인지 여부 — 이를 통해
     *   영속화 단계가 지금 열려 있는 화면이 쓰고 있는 페이지들을 버리지 않으면서도 한정된
     *   추정치는 거부할 수 있다.
     */
    private suspend fun measuredPageStartsForSection(
        section: ReaderSection,
        sectionBlocks: List<ReaderBlock>,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float = 1f,
    ): SectionPageStarts = if (pageBreaker == null) {
        textPageLayoutEngine.pageStartsForSection(section, sectionBlocks, style, viewportSize, null, viewportDensity)
    } else {
        withContext(ReaderPageMeasureDispatcher) {
            textPageLayoutEngine.pageStartsForSection(section, sectionBlocks, style, viewportSize, pageBreaker, viewportDensity)
        }
    }

    /**
     * 버전이 일치하는 부분 레이아웃을 [newSections]만큼만 확장한다. 없거나, 완전하거나,
     * 버전이 맞지 않는 행은 삭제되고 지금 시점의 접두사를 한 번 측정하여 대체된다. 새로
     * 저장된 텍스트를 정확하게 append할 수 없으므로 [pageBreaker]가 null이면 부분 행을
     * 삭제한다. 이는 영속화된 모든 행이 그 `characterCount`가 가리키는 정확한 접두사와
     * 계속 일치하도록 지켜준다.
     *
     * @param documentId 부분 행이 확장되고 있는 점진적 EPUB.
     * @param style 저장된 행과 새 측정이 공유하는 스타일.
     * @param viewportSize 저장된 행과 새 측정이 공유하는 뷰포트.
     * @param viewportDensity 페이지 분할기가 사용하는 밀도.
     * @param pageBreaker 실제 텍스트 측정기, 또는 정확한 append가 불가능하면 null.
     * @param newSections 새로 영속화된 섹션들과 그 섹션 상대적 블록들.
     * @param expectedExistingCharacterCount 이 섹션들을 append하기 전에 행이 이미 가지고
     *   있어야 하는 접두사 버전.
     */
    private suspend fun appendMeasuredPageStarts(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        viewportDensity: Float,
        pageBreaker: ReaderPageBreaker?,
        newSections: List<Pair<ReaderSection, List<ReaderBlock>>>,
        expectedExistingCharacterCount: Long,
    ) {
        if (newSections.isEmpty()) {
            if (pageBreaker == null) {
                pageLayoutDao.deletePartialPageLayouts(documentId.value)
            }
            return
        }
        if (pageBreaker == null) {
            pageLayoutDao.deletePartialPageLayouts(documentId.value)
            return
        }
        val layoutKey = style.layoutKey()
        val stored = pageLayoutDao.getPageLayout(
            documentId = documentId.value,
            fontSizeSp = layoutKey.fontSizeSp,
            lineHeightMultiplier = layoutKey.lineHeightMultiplier,
            fontFamilyName = layoutKey.fontFamilyName.orEmpty(),
            viewportWidthPx = viewportSize.widthPx,
            viewportHeightPx = viewportSize.heightPx,
        )
        if (stored != null && stored.isPartial && stored.characterCount == expectedExistingCharacterCount) {
            val existingStarts = stored.pageStartsBlob?.let(::decodePageStartsBlob) ?: run {
                pageLayoutDao.deletePageLayouts(documentId.value)
                return
            }
            val addedCharacterCount = newSections.sumOf { (section, _) -> section.text.length.toLong() }
            val appendedResults = newSections.map { (section, blocks) ->
                measuredPageStartsForSection(section, blocks, style, viewportSize, pageBreaker, viewportDensity)
            }
            if (appendedResults.any { result -> !result.isMeasured } ||
                appendedResults.all { result -> result.offsets.isEmpty() }
            ) {
                pageLayoutDao.deletePageLayouts(documentId.value)
                return
            }
            pageLayoutDao.upsertPageLayout(
                stored.copy(
                    characterCount = stored.characterCount + addedCharacterCount,
                    pageStartsBlob = encodePageStartsBlob(
                        concatPageStarts(existingStarts, appendedResults.map { result -> result.offsets }),
                    ),
                    writtenAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                    isPartial = true,
                ),
            )
        } else {
            pageLayoutDao.deletePageLayouts(documentId.value)
            invalidateDocumentCache(documentId)
            ensurePartialLayoutForCurrentPrefix(
                documentId = documentId,
                style = style,
                viewportSize = viewportSize,
                viewportDensity = viewportDensity,
                pageBreaker = pageBreaker,
                expectedCharacterCount = expectedExistingCharacterCount +
                    newSections.sumOf { (section, _) -> section.text.length.toLong() },
            )
        }
    }

    /**
     * 이미 저장된 각 섹션 인덱스가 어느 스파인 경로에서 왔는지를, 스파인 파싱을 재현하여
     * 순수 표지, 누락, 읽을 수 없는 항목들이 임포트 당시와 정확히 같게 건너뛰어지도록
     * 계산한다.
     *
     * @param container 리니어 스파인 항목 경로들을 얻기 위한, EPUB의 파싱된 컨테이너.
     * @param coverSectionIndex 합성된 표지 섹션의 섹션 인덱스, 이 책에 표지 섹션이 없으면 null.
     * @param storedSectionCount 이 문서에 대해 저장된 섹션 수.
     * @return 저장된 모든 섹션 인덱스를 그 소스 경로에 매핑한 것: 표지 섹션(있는 경우)은
     *   스파인 항목이 아니라 표지 자신의 href에 매핑되며, 그 외의 모든 섹션은 그것이 나온
     *   리니어 스파인 항목의 아카이브 상대 경로에 매핑된다.
     */
    private fun buildSectionPathByIndex(
        container: EpubImportContainer,
        coverSectionIndex: Int?,
        storedSectionCount: Int,
    ): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        val coverHref = container.coverDecision.coverHref
        if (coverSectionIndex != null && coverHref != null) map[coverSectionIndex] = coverHref
        var sectionIndex = if (coverSectionIndex != null) 1 else 0
        var offset = buildEpubCoverSection(container.coverDecision, container.documentTitle)
            ?.section
            ?.range
            ?.end
            ?.plus(SectionSeparatorLength)
            ?: 0L
        var spinePosition = 0
        while (sectionIndex < storedSectionCount && spinePosition < container.linearSpineItems.size) {
            val parsed = parseEpubSpineItem(container, spinePosition, sectionIndex, offset)
            spinePosition += 1
            if (parsed == null) continue
            map[sectionIndex] = container.linearSpineItems[spinePosition - 1].path
            sectionIndex += 1
            offset = parsed.section.range.end + SectionSeparatorLength
        }
        return map
    }

    private fun consumedSpinePositionForStoredSections(
        container: EpubImportContainer,
        storedSectionCount: Int,
    ): Int {
        var sectionIndex = if (container.coverDecision.hasCoverSection) 1 else 0
        var offset = buildEpubCoverSection(container.coverDecision, container.documentTitle)
            ?.section
            ?.range
            ?.end
            ?.plus(SectionSeparatorLength)
            ?: 0L
        var spinePosition = 0
        while (sectionIndex < storedSectionCount && spinePosition < container.linearSpineItems.size) {
            val parsed = parseEpubSpineItem(container, spinePosition, sectionIndex, offset)
            spinePosition += 1
            if (parsed == null) continue
            sectionIndex += 1
            offset = parsed.section.range.end + SectionSeparatorLength
        }
        return spinePosition
    }

    private suspend fun resolveNextSpineCursor(
        documentId: DocumentId,
        container: EpubImportContainer,
        storedSectionCount: Int,
    ): Int {
        epubImportCursorLock.withLock {
            epubNextSpineCursorByDocumentId[documentId]?.let { return it }
        }
        val replayed = consumedSpinePositionForStoredSections(container, storedSectionCount)
        return epubImportCursorLock.withLock {
            epubNextSpineCursorByDocumentId.getOrPut(documentId) { replayed }
        }
    }

    private suspend fun rememberNextSpineCursor(documentId: DocumentId, spinePosition: Int) {
        epubImportCursorLock.withLock {
            epubNextSpineCursorByDocumentId[documentId] = spinePosition
        }
    }

    private suspend fun rememberSectionPaths(documentId: DocumentId, sectionPathByIndex: Map<Int, String>) {
        if (sectionPathByIndex.isEmpty()) return
        epubImportCursorLock.withLock {
            val existing = epubSectionPathByIndexByDocumentId.getOrPut(documentId) { mutableMapOf() }
            existing.putAll(sectionPathByIndex)
        }
    }

    /**
     * 점진적 EPUB 임포트의 마지막 단계로, 마지막 배치가 스파인을 소진했을 때만
     * [importNextSections]에 의해 실행된다: 이제 모든 섹션이 알려졌으므로 내비게이션을
     * 해석하고, 목차가 이름 붙인 섹션들의 제목을 다시 붙이고, 누적된 개수를 스탬프 찍고,
     * 일치하는 부분 레이아웃을 승격하고, 문서를 완료로 표시한다. 개수와 폰트 href는 이미
     * 배치마다 누적되어 있었으므로, 완료 단계는 예전의 전체 섹션 텍스트 조회를 피하는 반면,
     * 내비게이션은 항목이 어떤 섹션이든 가리킬 수 있으므로 여전히 전체 스파인을 기다린다.
     *
     * [invalidateDocumentCache]는 완료 스탬프가 쓰이기 전에 실행되며, 그 뒤가 아니다:
     * 스탬프를 먼저 쓰면 `documents.importCompletedAtEpochMillis`가 이미 [isImportComplete]에
     * 보이는 반면 [getReaderDocument]는 여전히 완료 이전의 캐시된 문서를 제공하는 틈이
     * 생긴다 — 그 틈에 걸린 리더가 본 빈 목차는, 그 이후로는 아무것도 캐시를 다시
     * 무효화하지 않으므로 다음 앱 재실행까지 그대로 남을 것이다. 먼저 무효화하면 그 틈을
     * 닫는다: 스탬프가 보이는 시점에는 [getReaderDocument]가 바로 위에서 해석한
     * 내비게이션보다 앞선 캐시 항목으로는 더 이상 답할 수 없다.
     *
     * @param documentId 완료 처리 중인 문서.
     * @param entity 누적된 개수와 완료 스탬프를 얹어 그대로 이어지는, 문서의 현재 저장된 행.
     * @param container 내비게이션을 해석할 대상인, EPUB의 파싱된 컨테이너.
     * @param buildState 마지막 배치까지 포함된 최종 개수와 정확한 내장 폰트 href 집합.
     */
    private suspend fun finishEpubImport(
        documentId: DocumentId,
        entity: DocumentEntity,
        container: EpubImportContainer,
        buildState: ImportBuildState,
    ) {
        val finishStarted = TimeSource.Monotonic.markNow()
        val coverSectionIndex = 0.takeIf { container.coverDecision.hasCoverSection }
        val sectionCount = searchIndexDao.getSectionCount(documentId.value)
        val firstReadableContentSectionIndex = searchIndexDao.getFirstReadableContentSectionIndex(
            documentId = documentId.value,
            excludeSectionIndex = coverSectionIndex ?: -1,
        )
        val cachedSectionPaths = epubImportCursorLock.withLock {
            epubSectionPathByIndexByDocumentId.remove(documentId)?.toMap()
        }
        val sectionPathByIndex: Map<Int, String> = if (cachedSectionPaths != null && cachedSectionPaths.size >= sectionCount) {
            cachedSectionPaths
        } else {
            val storedPaths = searchIndexDao.getSectionSourcePaths(documentId.value)
            val hasAllPaths = storedPaths.all { it.sourcePath != null }
            if (hasAllPaths) {
                storedPaths.associate { it.sectionIndex to it.sourcePath.orEmpty() }
            } else {
                buildSectionPathByIndex(container, coverSectionIndex, sectionCount)
            }
        }
        val navigation = resolveEpubNavigationAtCompletion(
            container = container,
            sectionPathByIndex = sectionPathByIndex,
            coverSectionIndex = coverSectionIndex,
            firstReadableContentSectionIndex = firstReadableContentSectionIndex,
        )
        val titleUpdates = navigation.items.filter { it.offset == 0L }
        searchIndexDao.updateCompletedNavigation(
            documentId = documentId.value,
            sectionIndex = 0,
            documentTitle = container.documentTitle,
            navigationJson = json.encodeToString(navigation),
            titleUpdates = titleUpdates.map { item -> SectionTitleUpdate(item.spineIndex, item.title) },
        )
        invalidateDocumentCache(documentId)
        val finalCharCount = buildState.characterCount
        val finalWordCount = buildState.wordCount
        documentDao.updateCountsAndMarkComplete(
            documentId = documentId.value,
            characterCount = finalCharCount,
            wordCount = finalWordCount,
            importCompletedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
        )
        pageLayoutDao.promotePartialLayouts(documentId.value, finalCharCount)
        logger.d {
            "${entity.name.take(12)}: finishEpubImport completed in " +
                "${finishStarted.elapsedNow().inWholeMilliseconds} ms, " +
                "$sectionCount sections, ${titleUpdates.size} title updates, " +
                "no full section text query"
        }
        epubImportCursorLock.withLock {
            epubNextSpineCursorByDocumentId.remove(documentId)
            epubSectionPathByIndexByDocumentId.remove(documentId)
        }
        clearEpubScratchContainer(documentId)
    }

    /**
     * [importNextSections]를 위한 방어적 폴백: EPUB에 OPF가 아예 없어서 [importEpubPhase0]가
     * 이미 한 번에 끝냈어야 하는 경우인데도 문서의 임포트가 어쩐 일인지 한 번도 완료로
     * 스탬프 찍히지 않았을 때만 도달한다. [finishEpubImport]의 마지막 단계와 마찬가지로
     * 영속화된 개수를 해석하거나 레거시로 채워 넣고 완료를 스탬프 찍지만, 애초에 훑을
     * 스파인이 없었으므로 해석할 내비게이션은 없다.
     *
     * [finishEpubImport]와 마찬가지로 [invalidateDocumentCache]는 완료 스탬프가 쓰이기
     * 전에 실행되며, 그 뒤가 아니다 — 그렇지 않으면 두 구문 사이에 걸린 리더가
     * [isImportComplete]는 true를 답하는데 [getReaderDocument]는 여전히 완료 이전의
     * 캐시된 문서를 제공하는 것을 볼 것이다.
     *
     * @param documentId 완료 처리 중인 문서.
     * @param entity 누적된 개수와 완료 스탬프를 얹어 그대로 이어지는, 문서의 현재 저장된 행.
     * @return `sectionsImported = 0`인 완료 진행 상황 — 이 호출은 새로 임포트하는 것이
     *   없고, 이미 완전히 임포트된 책을 스탬프 찍기만 하기 때문이다.
     */
    private suspend fun finishNonProgressiveEpubImport(documentId: DocumentId, entity: DocumentEntity): ImportProgress {
        val buildState = resolveImportBuildState(documentId, entity)
        invalidateDocumentCache(documentId)
        val finalCharCount = buildState.characterCount
        val finalWordCount = buildState.wordCount
        documentDao.updateCountsAndMarkComplete(
            documentId = documentId.value,
            characterCount = finalCharCount,
            wordCount = finalWordCount,
            importCompletedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
        )
        epubImportCursorLock.withLock {
            epubNextSpineCursorByDocumentId.remove(documentId)
            epubSectionPathByIndexByDocumentId.remove(documentId)
        }
        clearEpubScratchContainer(documentId)
        return ImportProgress(isComplete = true, sectionsImported = 0)
    }

    /**
     * [metadata]의 문서에 저장된 모든 흔적을 [document]의 섹션들로 교체하며, 다시 쓰기 전후
     * 양쪽에서 모든 캐시를 무효화하여 어떤 것도 예전 콘텐츠나 찢긴 콘텐츠를 그 캐시에서
     * 읽어 들이는 일이 없도록 한다.
     *
     * 섹션들은 페이지네이션이 페이지를 주소 지정하는 절대 오프셋이 아니라, 각 섹션 자신의
     * 시작에 상대적으로 옮겨진 블록들과 함께 저장된다 — `TextPageLayoutEngine.sectionPageRanges`를
     * 참고하라, 이 함수는 예전에 정확히 이 이동을, 여기서 한 번 하는 대신 모든 페이지네이션
     * 패스마다 모든 블록과 모든 스팬에 대해 다시 하곤 했다.
     *
     * [importCompletedAtEpochMillis]는 기본값이 지금인데, 이는 기존의 모든 호출자가 문서
     * 전체를 한 번에 파싱하고 저장하기 때문이다 — 그러므로 "지금 당장 완료"가 그들 모두에게
     * 올바른 기본값이다 — TXT/PDF/CBZ/IMAGE 임포트, 그리고 *원래* 임포트가 한 번도 끝나지
     * 않은 문서에 대해서도 책 전체를 동기적으로 다시 파싱하는 EPUB 복구가 그렇다.
     * [importEpubPhase0]만 이것을 null로 재정의한다: 첫 섹션(들)만 영속화하고 나머지는
     * [importNextSections]에게 맡긴다. 공개 [upsertDocument](즐겨찾기 토글 같은 평범한
     * 메타데이터 편집을 위해 이미 저장된 것을 그대로 보존하는 함수)를 우회하는 것이 바로
     * 이 함수가 그 값을 물려받는 대신 명확히 결정할 수 있게 해주는 지점이다.
     *
     * 앞쪽의 [invalidateCaches] 호출은 이 함수가 실행되기 전에 시작되어 실행되는 동안
     * 여전히 진행 중인 로드만을 방어한다 — [getReaderDocument]가 스스로 닫는 것과 같은
     * [documentCacheGeneration] 틈이다. 이 호출 *이후에* 시작되는 로드에는 아무 소용이
     * 없다: `documentDao.upsertDocument`가 그 행을(그와 함께 [isImportComplete]도) 즉시
     * 보이게 만들지만, 그다음 `searchIndexDao.deleteSearchIndex`가 모든 섹션 행을 비우며,
     * 아래의 `searchIndexDao.upsertSearchIndex`가 끝날 때까지 다시 쓰이지 않는다. 그 틈
     * 어디에서든 시작되는 [getReaderDocument] 로드는 섹션 0개를 읽는다 — 같은 함수와
     * 경합하는 EPUB 복구의 경우에는 빈 내비게이션까지도 — 그리고 [finishEpubImport]와
     * 달리 그 찢긴 읽기는 단순히 캐시되지 않는 데 그치지 않는다: 그 이후로는 아무것도 캐시를
     * 다시 무효화하지 않으므로, 그 로드 자신의 세대 검사가 여전히 일치한다면(그 사이에 다른
     * 무효화가 끼어들지 않았다면) 빈 스냅샷을 공개해 버리고 그것을 지울 것이 다시는 없다 —
     * 이 캐시 세대 메커니즘 전체가 막으려던 바로 그 버그가, 읽는 쪽이 아니라 쓰는 쪽을 통해
     * 다시 열리는 것이다.
     *
     * 뒤쪽의 [invalidateDocumentCache] 호출이 바로 그 두 번째 구멍을 닫는다. 이 함수가
     * 건드리는 모든 행이 쓰인 뒤, 무조건 실행되므로 `finally`가 깜빡 잊힐 수 있는 것과 달리
     * 이른 반환에 의해 건너뛰어지지 않는다. 이 호출보다 앞서 공개된 어떤 로드든 — 위의 빈
     * 읽기와 이 호출 사이에 걸린 것 — 자신이 공개할 때 [metadata]의 id를 지목했으므로,
     * 이 호출 자신의 `cachedDocumentId == documentId` 검사([invalidateDocumentCache] 참고)가
     * 여전히 일치하여 한 문장 뒤에 그것을 지워낸다. 앞선 읽기가 바로잡히지는 않지만, 그것이
     * 캐시된 답으로 결코 살아남지 않는다는 것은 보장된다. 이 호출 이후에 시작되는 로드는
     * 그저 방금 쓰인 행들을 보게 되므로 아무 방어도 필요 없다. 이는 [finishEpubImport]
     * 순서의 거울상이지, 같은 수정을 반복하는 것이 아니다: 거기서는 먼저 무효화하는 것이
     * 안전한데, [invalidateDocumentCache]가 보호하는 모든 필드가 그 무효화가 실행되기
     * 전에 쓰이기 때문이다(그 함수 자신의 문서 참고). 여기서는 이 함수 *자체가* 다시
     * 쓰기이므로, 그 이후에 무효화도 실행될 때까지는 그 무엇도 안전하지 않다.
     *
     * [coverBytes]가 주어지면, 호출자가 이미 그것을 디코딩해 둔 지금 표지 파일에 곧바로
     * 쓴다 — 이는 이후의 모든 열기가 그렇지 않으면 [getDocumentCover]가 되풀이했을
     * 파일 전체 읽기를 아끼게 해준다(이 클래스 자신의 문서 참고). 표지 파일은
     * [ReaderDocument] 완전히 바깥에 있으므로, 뒤쪽 무효화의 어느 쪽에 걸리는지는
     * 상관없다.
     *
     * @param metadata 기록할 메타데이터 행.
     * @param document 그 섹션/블록/내비게이션을 저장할, 파싱된 문서.
     * @param coverBytes 호출자가 이미 디코딩해 둔 것이 있다면 함께 기록할 표지 이미지 바이트.
     * @param importCompletedAtEpochMillis 기록할 완료 스탬프, 또는 임포트를 미완료로
     *   남겨두려면 null(위 설명 참고).
     * @param keepScratchCopy 저장소를 다시 쓰는 동안 이 문서에 대한 인메모리 EPUB 스크래치
     *   바인딩을 보존하려면 true — 0단계에서 이를 사용하여 연속 작업이 같은 복사된 파일을
     *   계속 읽을 수 있도록 한다.
     * @param embeddedFontHrefsJson 직접 조회를 위해 인코딩된 정확한 참조 폰트 인덱스, 또는
     *   그것을 제공하지 않는 포맷과 레거시 기록이면 null.
     * @param sectionSourcePaths 완료 시점에 모든 스파인 항목을 다시 재생하지 않고 내비게이션을
     *   해석하는 데 쓰이는, 각 EPUB 섹션 인덱스의 아카이브 상대 소스 경로.
     */
    private suspend fun persistParsedDocument(
        metadata: DocumentMetadata,
        document: ReaderDocument,
        coverBytes: ByteArray? = null,
        importCompletedAtEpochMillis: Long? = Clock.System.now().toEpochMilliseconds(),
        keepScratchCopy: Boolean = false,
        embeddedFontHrefsJson: String? = null,
        sectionSourcePaths: Map<Int, String> = emptyMap(),
    ) {
        invalidateCaches(metadata.id, keepScratchCopy = keepScratchCopy)
        documentDao.upsertDocument(
            metadata.toDocumentEntity().copy(
                importCompletedAtEpochMillis = importCompletedAtEpochMillis,
                embeddedFontHrefsJson = embeddedFontHrefsJson,
            ),
        )
        searchIndexDao.deleteSearchIndex(metadata.id.value)
        if (document.sections.isNotEmpty()) {
            val blocksPerSection = distributeBlocksIntoSections(
                sections = document.sections.map { it.range },
                blocks = document.blocks,
            )
            val firstSectionIndex = document.sections.first().index
            searchIndexDao.upsertSearchIndex(
                document.sections.mapIndexed { position, section ->
                    section.toSearchIndexEntity(
                        documentId = metadata.id,
                        blocks = blocksPerSection[position],
                        documentTitle = document.title.takeIf { section.index == firstSectionIndex },
                        navigation = document.navigation.takeIf { section.index == firstSectionIndex },
                        json = json,
                        sourcePath = sectionSourcePaths[section.index],
                    )
                },
            )
        }
        if (coverBytes != null) {
            coverStore.store(metadata.id, coverBytes)
        }
        invalidateDocumentCache(metadata.id)
    }

    /**
     * 이 서가 메타데이터와 [document]의 방금 로드된 섹션들을, 앱의 나머지 부분이 읽는
     * [ReaderDocument]로 바꾼다. `blocks`는 평범한 리스트가 아니라 [LazyFlattenedBlocks]이다:
     * 책 안의 모든 블록은 그것을 만드는 비용으로서가 아니라 무언가가 실제로 그 리스트를
     * 읽는 첫 순간에 디코딩된다 — 페이지네이션 자체는 결코 그러지 않는다, [SectionBlocksCache]
     * 참고.
     *
     * @receiver [document]의 콘텐츠와 결합할 서가 메타데이터.
     * @param document 방금 로드된, 저장된 섹션들, 온디맨드 블록 캐시, 내비게이션 JSON.
     * @return 결합된 [ReaderDocument].
     */
    private fun DocumentMetadata.toReaderDocument(document: StoredReaderDocument): ReaderDocument = ReaderDocument(
        id = id,
        format = format,
        title = document.title ?: location.displayName,
        sections = document.sections,
        pageCount = pageCount,
        blocks = LazyFlattenedBlocks(document.sections, document.sectionBlocks),
        navigation = decodeNavigation(document.navigationJson),
    )

    /**
     * 섹션의 저장된 블록 JSON을 디코딩하며, 디코딩 실패 시 예외를 전파하는 대신 빈 리스트로
     * 답함으로써 관대하게 처리한다 — [SectionBlocksCache]가 아직 가져오지 않은 섹션에 대해
     * 문서화한 것과 같은 "이미지/서식만 빠짐" 저하이다.
     *
     * @param blocksJson 디코딩할 저장된 JSON.
     * @return 디코딩된 블록들, 디코딩이 실패하면 빈 리스트.
     */
    private fun decodeBlocks(blocksJson: String): List<ReaderBlock> =
        runCatching { json.decodeFromString<List<ReaderBlock>>(blocksJson) }.getOrDefault(emptyList())

    /**
     * 문서의 저장된 내비게이션 JSON을 디코딩한다.
     *
     * @param navigationJson 디코딩할 저장된 JSON, 또는 내비게이션이 한 번도 해석된 적이 없으면 공백.
     * @return 디코딩된 내비게이션, [navigationJson]이 공백이거나 디코딩에 실패하면 null.
     */
    private fun decodeNavigation(navigationJson: String): ReaderNavigation? =
        navigationJson.takeIf(String::isNotBlank)
            ?.let { runCatching { json.decodeFromString<ReaderNavigation>(it) }.getOrNull() }
}

/**
 * 모든 오프셋이 바로 앞의 것보다 큰지 여부 — 읽는 순서로 기록된 페이지 목록이 항상
 * 만족하는 불변조건이며, [DocumentRepositoryImpl.restorePageWindows]가 저장된 행으로부터
 * 페이지를 만들기 전에 그 행에 대해 확인하는 것이다. 빈 레이아웃이나 페이지 하나짜리
 * 레이아웃은 공허하게 오름차순을 만족한다.
 */
private fun LongArray.isStrictlyAscending(): Boolean {
    for (index in 1 until size) if (this[index] <= this[index - 1]) return false
    return true
}

/**
 * [PageLayoutEntity.pageStartsBlob]을 오프셋당 리틀엔디안 Int32로 표현한 것. 오프셋은 `Int`
 * 안에 여유 있게 들어간다 — 이 리더가 여는 실제 책 중 가장 큰 것도 350만 문자이다 — 따라서
 * 이는 [DocumentRepositoryImpl.storePageWindows]가 이미 만드는 바로 그 `LongArray`를, JSON
 * 숫자 대신 항목당 4바이트로 표현한 것이다. Room을 거치지 않고 직접 테스트할 수 있도록
 * ([DocumentRepositoryImpl.restorePageWindows]/[DocumentRepositoryImpl.storePageWindows]의
 * 왕복, PageStartsBlobCodecTest 참고) private이 아니라 internal이다.
 *
 * @param pageStarts 인코딩할 페이지 시작점들; 각 값은 `Int`에 들어가야 한다.
 * @return 인코딩된 blob, 항목당 [Int.SIZE_BYTES] 바이트.
 */
internal fun encodePageStartsBlob(pageStarts: LongArray): ByteArray {
    val blob = ByteArray(pageStarts.size * Int.SIZE_BYTES)
    for (index in pageStarts.indices) {
        val value = pageStarts[index].toInt()
        val offset = index * Int.SIZE_BYTES
        blob[offset] = value.toByte()
        blob[offset + 1] = (value ushr 8).toByte()
        blob[offset + 2] = (value ushr 16).toByte()
        blob[offset + 3] = (value ushr 24).toByte()
    }
    return blob
}

/**
 * [encodePageStartsBlob]의 역함수.
 *
 * @param blob 디코딩할 인코딩된 blob.
 * @return 디코딩된 페이지 시작점들.
 */
internal fun decodePageStartsBlob(blob: ByteArray): LongArray {
    val count = blob.size / Int.SIZE_BYTES
    return LongArray(count) { index ->
        val offset = index * Int.SIZE_BYTES
        val value = (blob[offset].toInt() and 0xFF) or
            ((blob[offset + 1].toInt() and 0xFF) shl 8) or
            ((blob[offset + 2].toInt() and 0xFF) shl 16) or
            ((blob[offset + 3].toInt() and 0xFF) shl 24)
        value.toLong()
    }
}

/**
 * 어떤 섹션의 텍스트든 잘못 디코딩되었는지 여부 — 유니코드 대체 문자, 또는 이미 깨진
 * 바이트에 같은 깨진 디코딩 단계가 실행되었을 때 나타나는 이중 인코딩된 모지바케
 * 문자열이다. 이런 모양의 섹션은 그대로 리더에게 보여지는 대신
 * [DocumentRepositoryImpl.loadReaderDocument]의 TXT 복구 경로를 유발한다.
 *
 * @receiver 확인할 저장된 섹션들.
 * @return 적어도 하나의 섹션 텍스트가 깨진 디코딩의 흔적을 담고 있으면 true.
 */
private fun List<ReaderSection>.hasBrokenText(): Boolean = any { section ->
    section.text.contains('\uFFFD') || section.text.contains("ï¿½")
}

/**
 * 비점진적 임포트(TXT, PDF)가 이미 손에 쥐고 있어야 하는 바이트 — 이 포맷들은 단계적/
 * 스트리밍 경로가 없으므로 이것 없이는 진행할 수 없다.
 *
 * @param source 바이트를 요구할 임포트 소스.
 * @return 소스의 바이트.
 * @throws IllegalStateException [source]가 바이트를 담고 있지 않을 때.
 */
private fun requireDocumentBytes(source: DocumentImportSource): ByteArray =
    source.bytes ?: error("Document bytes required for ${source.location.displayName}")

/**
 * [blocks] 어디에서든 참조되는 내장 폰트 href들의 고유 집합을, `block.style.fontHref`와
 * 각 `span.styleDelta.fontHref`를 합집합하여 추출한다. 이는 최적화 계약이 명시하는 정확한
 * 계산이다 — OPF 상위집합 추정이 아니다.
 *
 * @param blocks 훑을 블록 목록.
 * @return 발견된 모든 고유 폰트 href의 집합, 결정론적 JSON 인코딩을 위해 정렬됨.
 */
private fun extractFontHrefs(blocks: List<ReaderBlock>): List<String> =
    blocks.asSequence()
        .flatMap { block ->
            sequenceOf(block.style?.fontHref)
                .plus(block.spans.asSequence().map { span -> span.styleDelta?.fontHref })
        }
        .filterNotNull()
        .toMutableSet()
        .sorted()

/**
 * [documentId]의 표지가 캐시되는 위치. id 자체가 아니라 id의 해시로 이름 붙인다 — 문서
 * id는 책의 전체 소스 URI로, 임의로 길 수 있고 파일시스템이 경로 구성요소로 거부하는
 * 문자를 담을 수 있다 — 그리고 이 해시가 서로 다른 두 id가 같은 파일에 쓰는 일이 결코
 * 없도록 보장한다. 이 경로에 파일이 존재한다는 것 *자체가* 캐시이다([DocumentRepositoryImpl]
 * 자신의 문서 참고): 이를 기록하는 데이터베이스 컬럼은 없다. 테스트가 파일이 실제로
 * 쓰이고 실제로 제거되는지 단언할 수 있도록(DocumentRepositoryImplTest 참고) private이
 * 아니라 internal이며, 이는 위의 [encodePageStartsBlob]/[decodePageStartsBlob]이
 * internal인 것과 같은 이유이다.
 *
 * @param fileSource 표지가 속한 앱 전용 디렉터리를 해석해 오는 곳.
 * @param documentId 표지 경로를 계산할 문서.
 * @return [documentId]의 표지가 캐시되어 있거나 캐시될 경로.
 */
internal fun coverFilePath(fileSource: DocumentFileSource, documentId: DocumentId): Path =
    fileSource.appPrivateDirectory() / "covers" / "${documentId.value.encodeUtf8().sha1().hex()}.img"

/**
 * [DocumentRepositoryImpl.cachedPageWindows] 답의 신원: 어느 문서를, 어느 스타일로, 어느
 * 패널 크기로 레이아웃했는지. 키가 같은 두 호출은 캐시되거나 저장된 레이아웃 하나를
 * 공유할 수 있다; 무엇이든 다르면 — 같은 폰트라도 패널 크기가 다시 조정됐다면 — 공유할
 * 수 없다.
 *
 * @property documentId 레이아웃 대상 문서.
 * @property layoutKey 레이아웃이 측정된(또는 측정될) 폰트/줄 높이/글꼴.
 * @property viewportSize 레이아웃이 측정된(또는 측정될) 패널 크기.
 */
private data class PageWindowKey(
    val documentId: DocumentId,
    val layoutKey: ReaderLayoutKey,
    val viewportSize: ViewportSize,
)

/**
 * [DocumentRepositoryImpl.getPageWindows]가 처음 측정했을 때 저장된 레이아웃이 아예 없던
 * 하나의 (문서, 스타일, 뷰포트) [key]에 대해 진행 중인 점진적 페이지네이션. 한 번에 콘텐츠
 * 섹션 하나씩 — 리더가 재개해 들어간 섹션에서 위치 0까지 뒤로, 그다음 마지막 콘텐츠
 * 섹션까지 앞으로 — [DocumentRepositoryImpl.continuePagination]을 통해 자라나므로, 재개된
 * 섹션 자신의 페이지가 항상 가장 먼저 측정되며, 한번 만들어지면 다시는 움직이지 않는다:
 * 한 섹션의 페이지는 오직 그 섹션에만 의존한다(TextPageLayoutEngine.paginateSection 참고).
 *
 * [lowPosition]/[highPosition]은 [ReaderSection.index]가 아니라 [contentSections] 안에서의
 * 위치이다 — 둘은 책에 표지 섹션이 있을 때만 다른데, [contentSections]는 이미 그것을
 * 제외한다.
 *
 * 이 세션이 만들어진 순간 [DocumentRepositoryImpl]이 우연히 캐시하고 있던 것에 대한 맨
 * 클로저 대신 [sectionBlocksCache]를 직접 소유한다 — 그 필드는 이 세션이 여전히 측정
 * 중인 동안 나중의, 관련 없는 캐시 무효화에 의해 교체될 수 있으며, 교체 전에 캡처된
 * 클로저는 결코 예열되지 않은 다른 캐시를 읽는 동안 고아가 된 캐시를 예열하게 될 것이다.
 *
 * @property key 이 세션이 측정하고 있는 문서/스타일/뷰포트.
 * @property format 문서의 포맷으로, [TextPageLayoutEngine.paginateSection]까지 그대로
 *   전달된다.
 * @property coverPage 문서의 표지 페이지, 있다면 — 결코 다시 측정되지 않고 그저 실려
 *   다닐 뿐이다.
 * @property contentSections 이 세션이 훑는, 스파인 순서의 문서의 표지가 아닌 섹션들.
 * @property sectionBlocksCache 저장소에서 로드되었을 때의 문서의 온디맨드 블록 캐시 —
 *   [blocksFor] 참고.
 * @property lowPosition 지금까지 측정된 [contentSections] 중 가장 낮은 위치.
 * @property highPosition 지금까지 측정된 [contentSections] 중 가장 높은 위치.
 */
private class PaginationSession(
    val key: PageWindowKey,
    val format: DocumentFormat,
    val coverPage: PageWindow?,
    val contentSections: List<ReaderSection>,
    private val sectionBlocksCache: SectionBlocksCache?,
    private val fallbackSectionBlocks: (ReaderSection) -> List<ReaderBlock>,
    var lowPosition: Int,
    var highPosition: Int,
    var hasMeasuredPages: Boolean,
) {
    /** 방문한 모든 섹션의 페이지 시작점, [contentSections] 안에서의 위치를 키로 한다. */
    private val measuredPageStarts = mutableMapOf<Int, LongArray>()
    private var cachedSnapshot: List<PageWindow>? = null
    private var snapshotDirty = true

    /**
     * 이 세션의 모든 페이지 시작점이 한정된 추정 기하가 아니라 실제 브레이커에서
     * 나왔는지 여부. 추정된 윈도우는 지금 열려 있는 화면에서는 여전히 쓸 수 있지만
     * 결코 저장된 측정 레이아웃이 되어서는 안 된다.
     */
    var isFullyMeasured: Boolean = true
        private set

    /** 이제 모든 콘텐츠 섹션이 방문되었는지 여부 — 성장 순서는 클래스 문서 참고. */
    val isComplete: Boolean
        get() = contentSections.isEmpty() || (lowPosition == 0 && highPosition == contentSections.lastIndex)

    /**
     * 한 섹션의 페이지 시작점을 추가하고, 그 측정 출처를 [isFullyMeasured]에 접어 넣는다.
     *
     * @param position [contentSections] 안에서의 섹션 위치.
     * @param result 페이지 시작점들과, 실제 브레이커가 만들어낸 것인지 여부.
     */
    fun putMeasured(position: Int, result: SectionPageStarts) {
        measuredPageStarts[position] = result.offsets
        isFullyMeasured = isFullyMeasured && result.isMeasured
        if (position < lowPosition) lowPosition = position
        if (position > highPosition) highPosition = position
        snapshotDirty = true
    }

    fun measuredSections(): List<ReaderSection> = (lowPosition..highPosition).mapNotNull { position ->
        measuredPageStarts[position]?.let { contentSections[position] }
    }

    fun measuredStarts(): List<LongArray> = (lowPosition..highPosition).mapNotNull(measuredPageStarts::get)

    fun pagesAfter(anchorPosition: Int, anchorPageIndex: Int): Int =
        (measuredPageStarts[anchorPosition]?.size ?: 0) - anchorPageIndex - 1 +
            ((anchorPosition + 1)..highPosition).sumOf { position -> measuredPageStarts[position]?.size ?: 0 }

    fun allMeasuredStarts(): LongArray {
        val ordered = measuredStarts()
        val total = ordered.sumOf { it.size }
        var offset = 0
        return LongArray(total).also { flattened ->
            ordered.forEach { starts ->
                starts.copyInto(flattened, destinationOffset = offset)
                offset += starts.size
            }
        }
    }

    /**
     * [section]의 블록들로, [sectionBlocksCache]가 있으면 거기서, 없으면 [fallbackSectionBlocks]에서
     * 가져온다. [fallbackSectionBlocks]는 캐시가 아예 없을 때만 참조된다 — 복구 패스에서 나와 이미
     * 메모리 전체에 올라와 있는 문서인 경우([LoadedReaderDocument] 참고) — 따라서 거기서의 책 전체
     * 그루핑 패스는 복구가 이미 치른 작업 위에 얹히는 일회성 비용이지, 그것의 반복이 아니다.
     *
     * @param section 블록을 가져올 섹션.
     * @return 그 섹션의 블록들.
     */
    suspend fun blocksFor(section: ReaderSection): List<ReaderBlock> {
        val cache = sectionBlocksCache ?: return fallbackSectionBlocks(section)
        cache.prewarm(setOf(section.index))
        return cache.blocksFor(section.index)
    }

    fun blocksForSync(section: ReaderSection): List<ReaderBlock> =
        sectionBlocksCache?.blocksFor(section.index) ?: fallbackSectionBlocks(section)

    fun isSectionReady(sectionIndex: Int): Boolean = sectionBlocksCache?.isReady(sectionIndex) ?: true

    fun snapshotWindows(textPageLayoutEngine: TextPageLayoutEngine): List<PageWindow> {
        val existing = cachedSnapshot
        if (existing != null && !snapshotDirty) return existing
        return textPageLayoutEngine.reconstructMeasuredSections(
            format = format,
            coverPage = coverPage,
            contentSections = measuredSections(),
            sectionPageStarts = measuredStarts(),
            sectionBlocks = ::blocksForSync,
            isSectionReady = ::isSectionReady,
        ).also {
            cachedSnapshot = it
            snapshotDirty = false
        }
    }
}

/**
 * [PageLayoutDao.trimPageLayouts]가 가장 오래된 것을 버리기 전까지 [storePageWindows]가 문서당
 * 유지하는 측정된 레이아웃 개수. 아직 크기를 정하지 못한 리더는 한 번 앉은 자리에서 그중 몇 개를
 * 시도해 본다 — 폰트를 한 단계 키우고, 줄이고, 어쩌면 줄 높이나 서체도 바꿔 보고 — 하나에
 * 정착하기 전까지. 저장된 행은 이제 JSON 배열이 아니라 페이지 시작점 blob이므로
 * ([PageLayoutEntity] 참고), 1만 6천 페이지짜리 책이어도 겨우 수십 KB 수준으로 저렴해서, 몇 개
 * 더 유지하는 비용이 리더가 되돌아온 레이아웃을 다시 측정하는 것과 맞바꿀 만큼 크지 않다.
 */
private const val MaxStoredPageLayoutsPerDocument = 5
private const val PaginationContinuationBatchSize = 8
private const val InitialReadAheadMinimumContentChars = 8_192
private const val InitialReadAheadMaxSpineItems = 16
private const val InitialForwardPaginationPages = 4
private const val InitialForwardPaginationSections = 8

/**
 * null 호출자가 [DocumentRepositoryImpl.getPageWindows]로부터 얻는 뷰포트 — 그 스타일에 대해
 * 아직 아무것도 저장되어 있지 않을 때이며, `getPageWindows`가 스스로 하나를 해석할 수 있게 되기
 * 전에 `ReaderViewModel`이 직접 넘기던 것과 같은 추측값이다.
 */
private val DefaultViewportSize = ViewportSize(widthPx = 320, heightPx = 560)

/**
 * 이전 실행이 남긴 스크래치 사본들을 제거하며, [keep]과 여전히 쓰이고 있는 것은 남긴다.
 *
 * 이렇게 오래 사는 사본은 `finally`에서 제거할 수 없으므로,
 * [DocumentRepositoryImpl.epubScratchCopy]는 그 경로를 들고 있다가 다음 책이 그것을 대체할 때
 * 삭제한다. 프로세스가 죽으면 그 경로는 사라지지만 그 경로가 가리키던 사본은 사라지지 않는다 —
 * 실행당 하나씩, 책 전체 크기만 한 방치된 사본이 남는다. 더 이상 [keep]이 지목하지 않는 사본들을
 * 청소하는 이 작업이 큰 책들로 이루어진 서가가 캐시를 채우는 것을 막아준다.
 *
 * @param keep 현재 쓰이고 있어서 이 청소에서 살아남아야 하는 스크래치 사본.
 */
private fun deleteAbandonedScratchCopies(keep: Path) {
    val fileSystem = systemFileSystem()
    val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
    runCatching { fileSystem.list(directory) }.getOrNull()?.forEach { candidate ->
        if (candidate == keep) return@forEach
        if (!candidate.name.startsWith(ScratchCopyPrefix)) return@forEach
        runCatching { fileSystem.delete(candidate) }
    }
}

/** 모든 EPUB 스크래치 사본이 기록될 때 붙는 파일명 접두사로, [deleteAbandonedScratchCopies]가
 * 임시 디렉터리 안의 다른 것들 사이에서 그것을 알아볼 수 있게 해준다. */
private const val ScratchCopyPrefix = "tedd-reader-epub-open-"

/**
 * 이전 실행이 남긴 CBZ 스크래치 사본들을 제거하며, [keep]과 여전히 쓰이고 있는 것은 남긴다.
 *
 * [DocumentRepositoryImpl.cbzArchiveLocked]가 들고 있는 단 하나의 CBZ 스크래치 사본은
 * `finally`에서 제거할 수 없다 — 여러 페이지 윈도우 요청에 걸쳐 계속 열려 있어야 한다 — 그래서
 * 프로세스는 그 경로를 들고 있다가 다음 문서가 그것을 대체할 때 삭제한다. 프로세스가 죽으면 그
 * 경로는 사라지지만 그 경로가 가리키던 사본은 사라지지 않는다 — 실행당 하나씩, 아카이브 전체
 * 크기만 한 방치된 사본이 남는다. 더 이상 [keep]이 지목하지 않는 사본들을 청소하는 이 작업이 큰
 * 만화들로 이루어진 서가가 캐시를 채우는 것을 막아준다.
 *
 * @param keep 현재 쓰이고 있어서 이 청소에서 살아남아야 하는 스크래치 사본.
 */
private fun deleteAbandonedComicScratchCopies(keep: Path) {
    val fileSystem = systemFileSystem()
    val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
    runCatching { fileSystem.list(directory) }.getOrNull()?.forEach { candidate ->
        if (candidate == keep) return@forEach
        if (!candidate.name.startsWith(ComicScratchCopyPrefix)) return@forEach
        runCatching { fileSystem.delete(candidate) }
    }
}

/** 모든 CBZ 스크래치 사본이 기록될 때 붙는 파일명 접두사로, [deleteAbandonedComicScratchCopies]가
 * 임시 디렉터리 안의 다른 것들 사이에서 그것을 알아볼 수 있게 해준다. */
private const val ComicScratchCopyPrefix = "tedd-reader-comic-open-"

/** 고아가 된 내장 폰트 스크래치 파일들을 제거하며, [keep]에 있는 여전히 살아 있는 집합만 남긴다. */
private fun deleteAbandonedEmbeddedFontScratchFiles(keep: Set<Path>) {
    val fileSystem = systemFileSystem()
    val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
    runCatching { fileSystem.list(directory) }.getOrNull()?.forEach { candidate ->
        if (candidate in keep) return@forEach
        if (!candidate.name.startsWith(EmbeddedFontScratchPrefix)) return@forEach
        runCatching { fileSystem.delete(candidate) }
    }
}

/** 모든 내장 폰트 스크래치 파일이 기록될 때 붙는 파일명 접두사. */
private const val EmbeddedFontScratchPrefix = "tedd-reader-epub-font-"
/** 스크래치로 스트리밍하는 동안 강제되는, 내장 폰트 하나의 추출된 크기 상한. */
private const val MAX_EPUB_FONT_BYTES = 64L * 1024 * 1024

/**
 * [location]을 [block]이 실행되는 동안만 쓸 새 임시 파일로 복사하고, [block]이 성공하든
 * 예외를 던지든 그 뒤에 삭제한다 — 메모리 안의 바이트가 아니라 실제로 읽어 들일 [Path]가
 * 필요하지만 작업이 끝나면 아무것도 남겨서는 안 되는 파서를 위한 것이다.
 *
 * @param fileSource 원본 파일 바이트를 복사해 올 곳.
 * @param location 원본 파일의 위치.
 * @param block 임시 사본의 경로로 수행할 작업.
 * @return [block]이 반환하는 것.
 */
private suspend fun <T> withTemporarySourceCopy(
    fileSource: DocumentFileSource,
    location: com.tedd.teddreader.core.common.model.DocumentLocation,
    block: suspend (Path) -> T,
): T {
    val fileSystem = systemFileSystem()
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "tedd-reader-document-${Random.nextLong().toString(16)}-${location.displayName.substringAfterLast('/').ifBlank { "document" }}"
    return try {
        fileSource.copyTo(location, path)
        block(path)
    } finally {
        runCatching { fileSystem.delete(path) }
    }
}

/**
 * [DocumentRepositoryImpl.getStoredSections]가 한 문서에 대해 로드한 것: 그 섹션들, 그것들의
 * 온디맨드 블록 캐시, 그리고 섹션 0에 실려 있는 문서 수준의 사실들([title], [navigationJson],
 * [parserVersion]).
 *
 * @property sections 스파인 순서의 저장된 섹션들.
 * @property sectionBlocks [sections]를 위해 만들어진 온디맨드 블록 캐시.
 * @property title 어떤 섹션이든 기록해 둔 것이 있다면 문서의 제목.
 * @property navigationJson 여전히 JSON으로 인코딩된, 문서의 저장된 내비게이션, 한 번도 해석된
 *   적이 없으면 공백.
 * @property parserVersion 섹션들이 기록될 당시의 파서 버전 —
 *   [com.tedd.teddreader.core.data.mapper.CurrentReaderParserVersion] 참고.
 */
private class StoredReaderDocument(
    val sections: List<ReaderSection>,
    val sectionBlocks: SectionBlocksCache,
    val title: String?,
    val navigationJson: String,
    val parserVersion: Int,
)

/**
 * [DocumentRepositoryImpl.loadReaderDocument]가 찾아낸 것: 문서와 그 온디맨드 블록 캐시.
 *
 * @property document 로드된 문서.
 * @property sectionBlocks 문서의 온디맨드 블록 캐시, [document]가 복구 패스에서 나와 이미 모든
 *   블록을 메모리에 들고 있으면 null.
 */
private class LoadedReaderDocument(
    val document: ReaderDocument,
    val sectionBlocks: SectionBlocksCache?,
)

/**
 * 복원이 만들어낸 것과, 그것에 답했던 캐시 — [DocumentRepositoryImpl.getPageWindows] 참고.
 *
 * @property windows 재구성된 페이지 윈도우들.
 * @property sectionBlocksCache 재구성이 근거로 삼은 온디맨드 블록 캐시, 문서가 저장소 대신
 *   복구 패스에서 왔으면 null.
 */
private class RestoredPageWindowsResult(
    val windows: List<PageWindow>,
    val sectionBlocksCache: SectionBlocksCache?,
)

/**
 * 섹션의 블록들로, [searchIndexDao]에서 가져와 무언가가 실제로 그 섹션을 요청하는 첫 순간에
 * 디코딩되고, 그 이후로는 기억된다. 이것이 이미 리더에게 보여진 페이지가 의존하는 보장이다:
 * 한번 섹션이 디코딩되면 이 캐시의 수명 동안 [decoded]에 디코딩된 채로 남으므로, 그것으로
 * 만들어진 페이지는 리더 밑에서 이미지나 블록 스타일이 다시 "아직 디코딩되지 않음"으로
 * 사라지는 일이 결코 없다.
 *
 * [blocksFor]는 동기적으로 호출된다 — 페이지가 만들어지는 동안 `RestoredPageWindows.get`
 * 내부에서, 때로는 페이지를 넘기는 메인 스레드에서 — 따라서 결코 suspend할 수 없고 데이터베이스
 * 자체를 건드려서는 안 된다. 오직 [decoded]에서만 답하며, 실제 가져오기는 [prewarm]에서 일어나고,
 * 호출자가 곧 필요할 것을 아는 섹션들에 대해 미리 호출된다. 아직 아무것도 가져오지 않은 섹션은
 * [prewarm](또는 첫 페이지 공개 뒤에 따라오는 백그라운드 채우기)이 따라잡을 때까지 진짜 빈
 * 섹션처럼 빈 답을 준다 — 여기서 빈 답이 안전한 이유는 `ReaderViewModel.openDocument`를
 * 참고하라: 그것은 기껏해야 페이지의 이미지/서식을 잠시 빠뜨릴 뿐, 애초에 블록에 의존한 적이
 * 없는 텍스트는 결코 건드리지 않는다. [isSectionReady]는 특히 *복원된* 페이지 목록에 대해 이
 * 안전성 논증이 의존하는 답이다: 그 페이지가 실제로 필요로 하는 섹션이 이미 디코딩됐는지를
 * [TextPageLayoutEngine.reconstruct]에게 알려주어, reconstruct가 아직 가져오지 않은 모든 섹션을
 * 조용히 "진짜로 블록이 없음"으로 취급하는 대신 그 둘을 구별할 수 있게 한다.
 *
 * 공개된 캐시([decoded])는 페이지네이션의 작업 집합이 무한히 커지지 않도록 [MaxWarmSectionsRetained]
 * 항목으로 한정된다. [prewarm]은 가져온 뒤 항상 잘라낸다. 전체 문서 스캔이 모든 섹션을 필요로 할
 * 때 — [DocumentRepositoryImpl.getReferencedEmbeddedFontHrefs]의 레거시 내장 폰트 href 추출처럼 —
 * [snapshotAllBlocks]는 같은 [lock] 아래에서 디코딩된 모든 블록의 완전하고 독립적인 사본을
 * 반환한 뒤, 공개된 캐시를 다시 [MaxWarmSectionsRetained]로 잘라낸다. 스냅샷은 어떤 잘라내기보다
 * 오래 살아남으므로, 호출자는 캐시를 영구적으로 부풀리지 않고 정확하게 스캔할 수 있다.
 *
 * [blocksFor]는 절대 문서 오프셋이 아니라 섹션 자신의 시작을 기준으로 답한다 — 이는
 * [DocumentRepositoryImpl.persistParsedDocument]가 이제 `blocksJson`을 쓰는 방식이다(이유는 거기
 * 참고). [TextPageLayoutEngine]은 정확히 그 형태를 원한다. [LazyFlattenedBlocks]처럼 대신 문서의
 * 통상적인 절대 주소 지정을 원하는 호출자는 스스로 그것을 다시 되돌려야 한다.
 *
 * @property documentId 이 섹션들이 속한 문서.
 * @param sectionIndexes 이 문서가 실제로 가지고 있는 모든 섹션 인덱스로, [prewarm]이 결코 존재하지
 *   않을 섹션에 대한 요청을 데이터베이스에 묻는 대신 걸러낼 수 있게 해준다.
 * @property searchIndexDao 섹션의 블록 JSON을 가져오는 곳.
 * @property decode 섹션의 저장된 블록 JSON을 [ReaderBlock]들로 바꾸는 방법.
 */
private class SectionBlocksCache(
    private val documentId: DocumentId,
    sectionIndexes: List<Int>,
    private val searchIndexDao: SearchIndexDao,
    private val decode: (String) -> List<ReaderBlock>,
) {
    /** [sectionIndexes] 생성자 파라미터에 따른, 이 문서가 실제로 가진 모든 섹션 인덱스. */
    private val knownSections: Set<Int> = sectionIndexes.toSet()

    /** [knownSections]를 노출한 것으로, 전체 문서 스캔이 예열해야 할 모든 섹션을 지목할 수 있게 해준다. */
    val knownSectionIndexes: Set<Int> get() = knownSections

    /**
     * 지금까지 디코딩된 모든 섹션. [blocksFor]의 동기적이며 메인 스레드일 수도 있는 호출에서
     * 읽히고, [prewarm]의 suspend 호출에서 — 배치를 가져온 어떤 백그라운드 디스패처에서든 —
     * 쓰인다. 서로 다른 두 스레드이며 어느 쪽도 다른 쪽을 결코 락으로 막지 않는다. 이미 공개된
     * 맵을 변형하는 대신 가져올 때마다 맵 전체를 교체하는 것이, 이 필드에 대한 동시 읽기가
     * 항상 완전한 맵이나 그 이전 맵을 보게 하고 절반만 채워진 맵을 결코 보지 않게 만드는
     * 방법이다.
     */
    @Volatile
    private var decoded: Map<Int, List<ReaderBlock>> = emptyMap()
    private val lock = Mutex()

    /**
     * @param sectionIndex 블록을 가져올 섹션.
     * @return 섹션 자신의 시작을 기준으로 한 그 섹션의 디코딩된 블록들, 아직 디코딩되지
     *   않았으면 빈 리스트(그것이 안전한 이유는 클래스 문서 참고).
     */
    fun blocksFor(sectionIndex: Int): List<ReaderBlock> = decoded[sectionIndex].orEmpty()

    /**
     * [sectionIndex]의 블록들이 지금 바로 그 섹션의 실제 디코딩된 답인지 여부 — 이미 [decoded]에
     * 있는 섹션과, 이 문서가 아예 가지고 있지 않은 섹션 둘 다에 대해 true인데, 후자의 경우에는
     * 기다릴 것이 애초에 없기 때문이다.
     *
     * @param sectionIndex 확인할 섹션.
     * @return 지금 바로 호출한다면 [blocksFor]가 이 섹션의 실제 콘텐츠로 답할지 여부.
     */
    fun isReady(sectionIndex: Int): Boolean = sectionIndex !in knownSections || sectionIndex in decoded

    /**
     * [sectionIndexes] 중 아직 디코딩되지 않은 것들을 한 번의 쿼리로 가져와 디코딩한다. 이
     * 문서가 가지고 있지 않은 섹션은 결코 존재하지 않을 행을 데이터베이스에 묻는 대신
     * 걸러진다. 병합 후에는 페이지네이션 작업 집합이 무한히 커지지 않도록 공개된 캐시를 가장
     * 최근의 [MaxWarmSectionsRetained] 항목으로 잘라낸다 — 공개된 캐시를 건드리지 않고 모든
     * 섹션이 필요한 전체 문서 스캔이라면 대신 [snapshotAllBlocks]를 써야 한다.
     *
     * @param sectionIndexes 디코딩되어 있음을 보장할 섹션들.
     * @return 이 호출이 실제로 디코딩한 섹션 수로,
     *   [DocumentRepositoryImpl.warmSectionBlocks]가 호출자에게 다시 공개할 가치가 있는지
     *   알려줄 수 있게 해준다.
     */
    suspend fun prewarm(sectionIndexes: Collection<Int>): Int {
        return lock.withLock {
            val current = decoded
            val requestedKnown = sectionIndexes.filterTo(linkedSetOf()) { it in knownSections }
            val missing = requestedKnown.filterTo(linkedSetOf()) { it !in current }
            val merged = LinkedHashMap<Int, List<ReaderBlock>>(current.size + missing.size)
            current.forEach { (index, blocks) ->
                if (index !in requestedKnown) merged[index] = blocks
            }
            requestedKnown.forEach { index ->
                current[index]?.let { merged[index] = it }
            }
            if (missing.isEmpty()) {
                decoded = merged
                return@withLock 0
            }
            val rows = searchIndexDao.getSectionBlocksJson(documentId.value, missing.toList())
            if (rows.isEmpty()) {
                decoded = merged
                return@withLock 0
            }

            rows.forEach { row ->
                merged.remove(row.sectionIndex)
                merged[row.sectionIndex] = decode(row.blocksJson)
            }
            while (merged.size > MaxWarmSectionsRetained) {
                val oldest = merged.entries.firstOrNull()?.key ?: break
                merged.remove(oldest)
            }
            decoded = merged
            rows.size
        }
    }

    /**
     * [lock] 아래에서 알려진 모든 섹션의 블록을 가져와, 레거시 내장 폰트 href 추출 같은 전체
     * 문서 스캔을 위한 완전한 스냅샷을 만든 다음, 페이지네이션의 작업 집합 불변조건이
     * 회복되도록 공개된 [decoded] 맵을 다시 [MaxWarmSectionsRetained]로 잘라낸다. 반환되는
     * 맵은 이후의 어떤 [prewarm]이나 잘라내기보다도 오래 사는 독립적인 사본이다 — 호출자는
     * 캐시 자신의 축출과 경합하지 않고 자유롭게 그것을 순회할 수 있다.
     *
     * 원자성: 동시에 발생하는 [prewarm]은 가져오기와 스냅샷 사이에 끼어들 수 없는데, 둘 다
     * 같은 [lock] 획득 안에서 일어나기 때문이다. 따라서 스냅샷은 항상 이 문서가 가진 모든
     * 섹션의 완전하고 일관된 뷰를 반영한다.
     *
     * @return 알려진 모든 섹션 인덱스를 각 섹션 자신의 시작을 기준으로 한 디코딩된 블록들에
     *   매핑한 것([blocksFor]와 같은 좌표 공간).
     */
    suspend fun snapshotAllBlocks(): Map<Int, List<ReaderBlock>> {
        return lock.withLock {
            val current = decoded
            val missing = knownSections.filterTo(linkedSetOf()) { it !in current }
            val full = LinkedHashMap<Int, List<ReaderBlock>>(knownSections.size)
            knownSections.forEach { index ->
                current[index]?.let { full[index] = it }
            }
            if (missing.isNotEmpty()) {
                val rows = searchIndexDao.getSectionBlocksJson(documentId.value, missing.toList())
                rows.forEach { row ->
                    full[row.sectionIndex] = decode(row.blocksJson)
                }
            }
            val trimmed = LinkedHashMap<Int, List<ReaderBlock>>(minOf(full.size, MaxWarmSectionsRetained))
            val entries = full.entries.toList()
            val start = maxOf(0, entries.size - MaxWarmSectionsRetained)
            for (i in start until entries.size) {
                trimmed[entries[i].key] = entries[i].value
            }
            decoded = trimmed
            full
        }
    }

    /** 실제로 디코딩된 고유 섹션 수 — 열기 로그가 절약분을 보여주기 위해 쓰는 값. */
    val decodedSectionCount: Int get() = decoded.size

    private companion object {
        const val MaxWarmSectionsRetained = 24
    }
}

/**
 * 저장소에서 로드된 문서를 위한 [ReaderDocument.blocks]: 책 안의 모든 블록으로, 여는
 * 비용으로서가 아니라 무언가가 실제로 이 리스트를 읽는 순간에만 — 복구 검사나 문서 전체를
 * 원하는 호출자 — 평탄화된다. 페이지네이션 자체는 결코 이것을 건드리지 않는다; 대신
 * [SectionBlocksCache]에게 섹션 하나씩 요청한다.
 *
 * @property sections 평탄화될 때 [sectionBlocks]의 섹션별 답이 어떻게 정렬되고 오프셋되는지를
 *   정의하는, 스파인 순서의 문서 섹션들.
 * @property sectionBlocks 평탄화할 온디맨드 블록 캐시.
 */
private class LazyFlattenedBlocks(
    private val sections: List<ReaderSection>,
    private val sectionBlocks: SectionBlocksCache,
) : AbstractList<ReaderBlock>() {
    /**
     * 모든 섹션의 블록들을 스파인 순서로 이어붙이고 문서의 절대 오프셋으로 다시 옮긴 것.
     * [sectionBlocks]의 `blocksFor`는 각 섹션 자신의 시작을 기준으로 답하는 반면,
     * [ReaderDocument.blocks]는 문서의 나머지와 같은 절대 오프셋을 주소로 삼는다고
     * 문서화되어 있으므로, 각 섹션의 답은 합쳐지기 전에 다시 옮겨진다 — 그러지 않으면 두
     * 섹션의 블록들이 이어붙여졌을 때 책의 실제 오름차순 오프셋이 아니라 같은 작은
     * 숫자들에 놓이게 될 것이다.
     */
    private val flattened: List<ReaderBlock> by lazy {
        sections.flatMap { section -> sectionBlocks.blocksFor(section.index).rebasedBy(-section.range.start) }
    }

    /** [flattened]된 책의 총 블록 수. */
    override val size: Int get() = flattened.size

    /** [flattened]에서 가져온, 책 전체에서 [index]번째 블록. */
    override fun get(index: Int): ReaderBlock = flattened[index]
}

package com.tedd.teddreader.feature.reader.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tedd.teddreader.core.common.ByteArrayLruCache
import com.tedd.teddreader.core.common.suspendRunCatching
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.PaginatedDocument
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import com.tedd.teddreader.core.common.model.layoutKey
import com.tedd.teddreader.core.common.model.withThemeMode
import com.tedd.teddreader.core.domain.reader.ImportBatchSectionCount
import com.tedd.teddreader.core.domain.reader.canReportPaginationComplete
import com.tedd.teddreader.core.domain.reader.needsPaginationContinuation
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.domain.repository.BookmarkRepository
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReaderSettings
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import com.tedd.teddreader.core.domain.usecase.OpenReaderDocumentUseCase
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import kotlin.time.Clock

/**
 * 열려 있는 리더 문서 하나를 처음부터 끝까지 소유한다: 문서를 로드하고, 페이지로 나누고, 읽기
 * 위치와 설정을 동기 상태로 유지하며, [ReaderUiState]가 이를 렌더링하는 데 필요한 모든 것을
 * 발행한다.
 *
 * [currentDocumentId]는 이 인스턴스가 현재 열려 있다고 여기는 문서를 가리키며, 아래의 모든
 * suspend 함수는 매 suspension point를 지날 때마다, [_uiState]나 다른 어떤 필드를 건드리기
 * 전에 자신의 파라미터와 이 값을 다시 대조한다. 이 재확인이 존재하는 이유는 `Job.cancel()`이
 * 이미 진행 중인 데이터베이스 읽기나 디코딩을 멈출 수 없기 때문이다: [openDocumentJob](또는 아래
 * 백그라운드 continuation job들 중 어느 것)을 취소해도 코루틴이 자신의 *다음* suspension
 * point에 도달하는 것만 막을 뿐, 이미 실행되어 곧 완료될 읽기를 되돌리지는 못한다. 재확인이
 * 없다면 그 완료되는 읽기는 결과를 그대로 발행해버릴 것이다 — 리더가 이미 다른 책으로 넘어간
 * 뒤에도 낡은 문서의 메타데이터, 페이지, 블록이 UI에 도달하는 식으로. 이는 컴파일러가 강제하는
 * 것이 아니라 관례다 — 이 확인 없이 새로운 suspend 함수를 여기 추가하는 것을 막는 장치는 없으며,
 * 그런 일이 생기면 재발하는 결함은 조용해서 크래시가 아니다: 앱이 이따금 화면에 있는 책이 아니라
 * 리더가 떠난 책에 속한 페이지나 제목을 보여줄 뿐이다. 아래에서 suspend하는 모든 함수는 이런
 * 이유로 자신의 재확인을 문서화하며, 이것이 없는 새 함수는 버그로 취급해야 한다.
 *
 * @property documentRepository 문서의 메타데이터, 구조, 페이지 나누기, 이미지가 오는 곳.
 * @property bookmarkRepository 저장된 읽기 위치가 저장되고 관찰되는 곳.
 * @property readerSettingsRepository 리더의 영속화된 활자·동작 설정이 저장되고 관찰되는 곳.
 * @property readerRepository 리더의 문서별 읽기 진행률이 저장되는 곳.
 */
@KoinViewModel
class ReaderViewModel(
    private val documentRepository: DocumentRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val readerSettingsRepository: ReaderSettingsRepository,
    private val readerRepository: ReaderRepository,
    private val openReaderDocumentUseCase: OpenReaderDocumentUseCase,
) : ViewModel() {
    /** [ReaderScreen]이 렌더링하는 상태; 이 인스턴스 자신의 `update`/`value =`를 통해서만 변경된다. */
    private val _uiState = MutableStateFlow(ReaderUiState())

    /** [ReaderScreen]이 수집하는 읽기 전용 스트림 — [_uiState]가 발행하는 것과 같은 flow. */
    val uiState: StateFlow<ReaderUiState> = _uiState

    /**
     * 이 인스턴스가 현재 열려 있다고 여기는 문서, 첫 [openDocument] 호출 전에는 null. 아래 모든
     * suspend 함수가 이 필드에 대해 지는 재확인 관례는 이 클래스 자체의 문서를 참고한다.
     */
    private var currentDocumentId: DocumentId? = null

    /**
     * 이 리더의 가장 최근 페이지 나누기 결과 — [documentRepository]가 측정한 페이지 창(window)과
     * 그것이 배치된 대상 섹션들을 하나의 [PaginatedDocument]로 함께 묶어 둔 것으로, 페이지의 챕터
     * 제목과 section-tail 플래그가 언제나 페이지 나누기가 실제로 그것들을 만들어낸 섹션 목록을
     * 기준으로 읽히도록 한다. [openDocument]가 새 문서를 열 때마다 빈 [PaginatedDocument]로
     * 리셋되며, 열리는 시점에 [loadOpenState]가 한 번, 그리고 페이지 나누기가 책을 더 측정할
     * 때마다 [reloadPages]가 재할당한다. [reloadPages] 자체는 이 같은 필드에 발행하는 최대 네
     * 개의 서로 다른 코루틴에서 동시에 실행될 수 있다: viewport reload
     * ([updatePageBreaker]의 [viewportReloadJob]), import 배치([continueImportIfIncomplete]의
     * [importContinuationJob]), 페이지 나누기 배치([continuePaginationIfIncomplete]의
     * [paginationContinuationJob]), 그리고 [updateStyle] 내부의 style 변경 자체의 launch — 그래서
     * 페이지 목록으로부터 warm하거나 발행하려는 호출자는 이 필드를 다시 읽는 대신 방금 자신이 측정한
     * 로컬 쌍을 우선해야 한다(같은 이유는 [warmMountWindow] 자체의 문서를 참고).
     */
    private var paginated: PaginatedDocument = PaginatedDocument()

    /**
     * [paginated]의 페이지 창들이 실제로 측정된 style, 또는 문서가 방금 리셋되어 아직 아무것도
     * 페이지로 나누지 않았을 때는 null. [paginated] 자체와 같은 문장 그룹에서 기록되며,
     * [paginated]가 기록되는 모든 곳 중 그 측정 대상 style이 달라질 수 있는 이유가 있는 곳에서만
     * 함께 쓰인다: [openDocument]의 리셋, [loadOpenState]의 채택, [reloadPages]의 새 측정.
     * [refreshEpubPages], [republishSurroundingPages], [moveToPageInternal], [publishFirstFrame]은
     * 이 필드가 이미 나타내는 페이지 나누기를 다시 알릴 뿐이므로 쓰지 않고 읽기만 한다.
     *
     * 이는 [ReaderUiState.pageLayoutStyle]이 그대로 반영하는 필드로, 레이아웃에 영향을 주는 style
     * 변경(활자, 크기, 줄 간격, 굵기)이 [paginated]가 여전히 이전 style로 잘린 조각을 보유한
     * 상태에서 새 style을 동기적으로 발행하는 창을 닫기 위해 존재한다: [paginated]의 페이지를
     * 발행하는 모든 `_uiState.update`는 같은 update 안에서 `pageLayoutStyle = paginatedStyle`도
     * 함께 발행해야 하며, 그렇지 않으면 페이지 서피스가 그 여전히 낡은 조각을 새 style의 활자
     * 설정으로 그려서 줄이 잘리거나 페이지 하단에 빈틈이 생긴다. 이는 컴파일러가 검사하는 것이
     * 아니라 이 클래스가 리뷰로 강제하는 관례다 — 앞으로 이 없이 추가되는 발행 지점은 같은
     * 결함의 축소판을 다시 연다.
     *
     * [openDocument]의 리셋은 바로 옆의 `paginated` 리셋과의 방어적 대칭이며, 어떤 테스트도
     * 이를 고정하지 않는다: [ReaderUiState.pageLayoutStyle]의 기본값은 null이고 [openDocument]가
     * 어차피 상태 객체 전체를 교체하므로, 테스트가 관찰할 수 있는 모든 순간에 이미 발행된 상태는
     * 이 필드가 리셋되었든 아니든 null을 읽는다. 이 리셋이 실제로 지키는 것은 [loadOpenState]가
     * 자신의 첫 번째 가드에서 반환하여 이 필드를 절대 재할당하지 않는 경로다 — 로드에 실패한 책,
     * 또는 빠른 앞뒤 전환 — 그 뒤에 나중 발행이 이전 책의 활자 설정을 새 책에 고정해버릴 것이다.
     * 테스트에서 이에 도달하려면 가짜 저장소들이 제공하지 않는 결함 주입이 필요하므로, 이 줄은
     * 테스트가 아니라 코드 읽기로 지켜지고 있다.
     */
    private var paginatedStyle: ReaderStyle? = null

    /**
     * 읽기 위치를 절대 텍스트 오프셋으로 나타낸 것으로, 어느 한 페이지 나누기 패스에도
     * 종속되지 않는다.
     *
     * 페이지 번호는 그것을 만들어낸 하나의 (style, viewport) 페이지 나누기에 대해서만 의미가
     * 있으므로, 재 페이지 나누기 — 활자 변경, viewport 크기 변경, import 배치가 섹션을 덧붙이는
     * 경우 — 를 견뎌내야 하는 위치는 대신 여기서 원시 오프셋으로 추적된다. [reloadPages]가 방금
     * 측정한 새 [PaginatedDocument]를 기준으로 이를 다시 페이지 인덱스로 해석한다.
     */
    private var anchorOffset: Long? = null

    /**
     * 페이지 나누기와 페이지 레이아웃 저장이 키로 삼는 viewport — px가 아니라 sp. [loadOpenState]가
     * 저장된 레이아웃의 viewport를 채택할 때, 그리고 [updatePageBreaker]가 pane이 진짜 새로운
     * 크기를 보고할 때 기록한다(단위가 pane의 실제 픽셀 박스 — 이는 대신 [pageBreakerSize]가
     * 담는다 — 가 아니라 sp인 이유는 그 함수 자체의 문서를 참고).
     */
    private var viewportSize: ViewportSize = DefaultViewportSize

    /**
     * [viewportSize]가 측정된 pane의 sp당 픽셀 수, pane이 보고하기 전까지는 1. 페이지 나누기
     * geometry는 실제 픽셀 박스를 기준으로 키가 걸린다(밀도가 다른 두 디스플레이는 같은 sp
     * 박스로 반올림되어도 줄바꿈은 다르게 될 수 있다), 이것이 그 값을 estimator가 계산에 쓰는
     * sp로 다시 변환해 준다.
     */
    private var paneDensity: Float = 1f

    /** 이 클래스 전반에 흩뿌려진 진단용 trace에 쓰이는, 이 리더의 태그 붙은 logger. */
    private val logger = co.touchlab.kermit.Logger.withTag("Reader")

    /**
     * [updatePageBreaker]가 가장 최근에 받아들인, 렌더링된 텍스트 레이아웃 측정값 — 페이지
     * 나누기가 기준으로 측정하는 대상 — 또는 어떤 pane도 크기를 보고하기 전에는 null이며,
     * [loadOpenState]가 저장된 레이아웃의 viewport만 [viewportSize]와 [pageBreakerStyle]에
     * 채택했을 뿐 여기에 넣을 실제 [ReaderPageBreaker] 인스턴스가 아직 없을 때도 그렇다.
     * 항상 [pageBreakerFor]를 통해서만 읽어야 하고 직접 읽어서는 안 되는데, breaker는 자신이
     * 측정된 [pageBreakerStyle]의 페이지만을 나타내기 때문이다.
     */
    private var pageBreaker: ReaderPageBreaker? = null

    /**
     * [pageBreaker]가 측정된 style, 또는 [pageBreaker]가 아직 null인 동안 저장된 레이아웃이
     * 채택된 style. [loadOpenState]와 [updatePageBreaker]가 [pageBreakerSize]/[viewportSize]와
     * 함께 기록한다 — 절대 이들 중 하나만 따로 기록하지 않는데, 셋이 어긋나면 이전 문서나
     * 이전 style의 답이 이번 것으로 조용히 통과할 수 있기 때문이다.
     */
    private var pageBreakerStyle: ReaderStyle? = null

    /**
     * [pageBreaker] 뒤에 있는, pane의 실제 측정된 픽셀 박스 — sp가 아니라 px — [updatePageBreaker]가
     * 이미 답한 보고([PaneReportOutcome.Ignore])를 알아보는 용도로만 유지된다; 페이지 나누기와
     * 페이지 레이아웃 저장이 실제로 키로 삼는 단위는 [viewportSize]다.
     */
    private var pageBreakerSize: ViewportSize? = null

    /**
     * [updatePageBreaker]가 가장 최근에 받아들인 보고에 대해 시작한, 진행 중인 재 페이지 나누기;
     * 취소되고 교체될 뿐 절대 await되지 않으므로, 더 새로운 보고는 여전히 실행 중인 더 오래된
     * reload를 항상 이긴다.
     */
    private var viewportReloadJob: Job? = null

    /**
     * [currentDocumentId]에 대해 진행 중인 [openDocument] 코루틴; 더 새로운 [openDocument]
     * 호출이 다른 문서를 열기 시작하는 순간 취소된다.
     */
    private var openDocumentJob: Job? = null

    /**
     * 점진적 EPUB import의 2단계 이후를 진행시킨다([continueImportIfIncomplete] 참고) — 새로운
     * 서브시스템이 아니라 평범한 [viewModelScope] job이다: 리더가 이 문서를 떠나는 순간 멈추며,
     * 다음에 열릴 때 저장된 행들이 말하는 지점부터 import를 다시 이어받는다.
     */
    private var importContinuationJob: Job? = null

    /**
     * 진행 중인 페이지 나누기 패스의 나머지를 진행시킨다([continuePaginationIfIncomplete] 참고) —
     * [importContinuationJob]과 같은 모양이며, 다만 아직 import되지 않은 섹션을 파싱하는 대신
     * 아직 측정되지 않은 style을 측정한다.
     */
    private var paginationContinuationJob: Job? = null

    /** 전체 페이지 수가 여전히 늘어나는 동안 보류되는, 한도 있는 "다음" 요청 하나. */
    private var pendingMoveNextStep: Int? = null

    /** 진행 중인 가장 최신 진행률 저장; 오래된 쓰기가 뒤늦게 이기지 않도록 취소되고 교체된다. */
    private var saveProgressJob: Job? = null

    /** import가 완료된 뒤의 최종 글자 수; 텍스트 import가 아직 미완료인 동안은 null. */
    private var finalCharacterCount: Long? = null

    /**
     * 현재 문서의 저장된 읽기 위치들로, [observeSavedPlaces]에 의해 [bookmarkRepository]와
     * 동기 상태를 유지한다; [toggleSavedPlace]와 [isPageSaved]가 읽는다.
     */
    private var savedPlaces: List<Bookmark> = emptyList()

    /**
     * [observeSavedPlaces]가 [bookmarkRepository]에 대해 보유하는 구독; [openDocument]가 새
     * 문서를 열 때마다 취소되고 다시 시작된다.
     */
    private var savedPlacesJob: Job? = null

    /** 이 열린 리더를 앱 전역에 영속화된 설정과 맞춰 두는, 살아있는 구독. */
    private var readerSettingsJob: Job? = null

    /**
     * [visualPageCache]에서 현재 빠져 있는 페이지들에 대해 [loadVisualPagesAround]가 시작한,
     * 진행 중인 fetch; 다음 호출로 취소되고 교체된다.
     */
    private var visualPageLoadJob: Job? = null

    /**
     * CBZ 문서에 대해 디코딩된 페이지 이미지들로, 페이지 인덱스를 키로 한다; 24 MiB 예산으로
     * 제한되며 [loadVisualPagesAround]가 현재 마운트 창을 축출로부터 보호해 둔다.
     */
    private val visualPageCache = ByteArrayLruCache<Int>(VisualPageCacheBudgetBytes)

    /**
     * [loadVisualPagesAround]가 이미 디코딩을 시도했다가 실패한 페이지 인덱스들로, 리더가 근처를
     * 넘길 때마다 이들을 계속 다시 요청하지 않게 한다.
     */
    private val failedVisualPages = linkedSetOf<Int>()

    /**
     * [embeddedImageCache]에서 현재 빠져 있는 href들에 대해 [loadEmbeddedImagesAround]가 시작한,
     * 진행 중인 fetch; 다음 호출로 취소되고 교체된다.
     */
    private var embeddedImageLoadJob: Job? = null

    /** 현재 상태에서 빠져 있는 href들에 대해 [loadAllEmbeddedFonts]가 시작한, 진행 중인 fetch. */
    private var embeddedFontLoadJob: Job? = null

    /**
     * EPUB 문서에 대해 디코딩된 내장 이미지들로, href를 키로 한다; 16 MiB 예산으로 제한되며
     * [loadEmbeddedImagesAround]가 현재 마운트 창이 여전히 필요로 하는 모든 href를 보호한다.
     */
    private val embeddedImageCache = ByteArrayLruCache<String>(EmbeddedImageCacheBudgetBytes)

    /** 해석된 내장 EPUB 폰트 파일들로, href를 키로 하며 바이트가 아니라 임시 파일 경로로 유지된다. */
    private var embeddedFontFiles: Map<String, String> = emptyMap()

    /**
     * [loadEmbeddedImagesAround]가 이미 디코딩을 시도했다가 실패한 href들로, 리더가 근처를 넘길
     * 때마다 이들을 계속 다시 요청하지 않게 한다.
     */
    private val failedEmbeddedImageHrefs = linkedSetOf<String>()

    /** [loadAllEmbeddedFonts]가 이미 해석을 시도했다가 실패한 href들. */
    private val failedEmbeddedFontHrefs = linkedSetOf<String>()

    /**
     * 이 문서가 어디선가 참조하는 모든 내장 폰트가 *완전히 import된* 책을 기준으로 해석되었는지(또는
     * 실패했는지) 여부. 일단 true가 되면 [loadAllEmbeddedFonts]는 아무 일도 하지 않는다 — 폰트
     * 집합, 그리고 그와 함께 layout key는 이 문서에 대해 다시는 바뀔 수 없으며, 이것이 책이 읽는
     * 도중 스스로를 다시 측정하지 않도록 막아준다. 점진적 import가 여전히 진행 중인 동안은 의도적으로
     * false다: 그 상태의 스캔은 책의 일부만 보게 되며, 그 부분적인 답을 최종이라고 해버리면 책이
     * 폰트 없는 상태로 굳어버린다.
     */
    private var allEmbeddedFontsResolved = false

    /**
     * *지금까지* 알려진 섹션들이 참조하는 폰트가 로드되었는지(또는 실패했는지) 여부. 이것이 점진적
     * import 도중 측정 게이트가 타는 값이다 — import된 섹션들을 그들이 참조하는 폰트로 측정하는
     * 것은 책 전체가 존재하기 전이라도 유효하다; 완료 패스가 이후 집합을 확정하고, 실제로 커졌을
     * 때만 최대 한 번 다시 측정한다.
     */
    private var embeddedFontsSettled = false

    /**
     * [documentIdValue]를 이 리더가 보여줄 문서로 열어, 이전에 열려 있던 것을 무엇이든 대체한다.
     *
     * [documentIdValue]가 이미 [currentDocumentId]를 가리키고 있으면 아무 일도 하지 않는다 —
     * 그렇지 않으면 같은 문서를 다시 여는 것이 아래의 모든 job을 헛되이 취소하고 다시 시작하게
     * 만들 것이다. 그 외의 경우 이 함수는 이 인스턴스가 이전 문서에 대해 실행 중이었을 모든 job을
     * ([openDocumentJob]과, 그것이나 그 continuation들이 시작한 모든 백그라운드
     * continuation/preload job) 동기적으로 취소하고, [paginated], [anchorOffset], 두 이미지
     * 캐시를 모두 비우며, 어떤 비동기 작업이 시작되기도 전에 빈 로딩 [ReaderUiState]를
     * 발행한다 — 그래서 첫 번째 문서가 여전히 로딩 중인 동안 두 번째 문서를 여는 호출자는 떠나는
     * 문서의 낡은 프레임을 절대 보지 않는다(테스트 스위트의
     * `openingAnotherDocumentImmediatelyClearsPreviousReaderContent` 속성이 이를 고정한다).
     *
     * 실제 로드는 그런 다음 [OpenState] 자체의 문서가 설명하는 네 단계로 실행된다: [loadOpenState]가
     * 열기에 필요한 모든 것을 모으고, [publishFirstFrame]이 리더가 보게 될 첫 프레임을 발행하며,
     * [startContinuations]가 나머지 열기 과정이 필요로 할 수 있는 모든 백그라운드 job을 시작하고,
     * [publishRest]가 열기를 기록하고 첫 프레임이 필요로 하지 않았던 것을 발행한다. 앞의 세 단계는
     * 각각 [currentDocumentId]가 더 이상 [documentId]를 가리키지 않는 순간 — 더 새로운
     * [openDocument] 호출이 이미 리더를 다른 책으로 옮겨놓았기 때문에 — 일찍 반환할 수 있다(null인
     * [OpenState], 또는 [publishFirstFrame]이 false를 답하는 식으로); 그런 일이 생기면 나머지
     * 단계는 이 호출에 대해 그냥 실행되지 않는다.
     *
     * 단계들을 빠져나가는 [CancellationException]은 에러 상태로 바뀌지 않고 다시 던져지므로,
     * [openDocumentJob]을 취소하는 것은 다른 코루틴을 취소하는 것과 똑같이 동작한다. 그 밖의
     * [Throwable]은 [ReaderUiState] 에러로 바뀐다 — 다만 [currentDocumentId]를 마지막으로 한
     * 번 더 재확인한 뒤에만 그렇게 되어서, 리더가 이미 다른 문서로 넘어간 뒤에 도착한 실패가 그
     * 문서의 상태를 이 실패로 덮어쓰지 않는다.
     *
     * @param documentIdValue 열려는 문서의 원본 id.
     */
    fun openDocument(documentIdValue: String) {
        val documentId = DocumentId(documentIdValue)
        val currentState = _uiState.value
        if (
            currentDocumentId == documentId &&
            !currentState.isLoading &&
            currentState.errorMessage == null
        ) return
        currentDocumentId = documentId
        openDocumentJob?.cancel()
        viewportReloadJob?.cancel()
        visualPageLoadJob?.cancel()
        embeddedImageLoadJob?.cancel()
        embeddedFontLoadJob?.cancel()
        importContinuationJob?.cancel()
        paginationContinuationJob?.cancel()
        readerSettingsJob?.cancel()
        paginated = PaginatedDocument()
        paginatedStyle = null
        anchorOffset = null
        pageBreaker = null
        pageBreakerStyle = null
        pageBreakerSize = null
        pendingMoveNextStep = null
        saveProgressJob?.cancel()
        finalCharacterCount = null
        visualPageCache.clear()
        failedVisualPages.clear()
        embeddedImageCache.clear()
        failedEmbeddedImageHrefs.clear()
        embeddedFontFiles = emptyMap()
        failedEmbeddedFontHrefs.clear()
        allEmbeddedFontsResolved = false
        embeddedFontsSettled = false
        _uiState.value = ReaderUiState(documentTitle = documentId.value)
        observeSavedPlaces(documentId)

        openDocumentJob = viewModelScope.launch {
            try {
                val state = loadOpenState(documentId) ?: return@launch
                if (!publishFirstFrame(state)) return@launch
                observeReaderSettings(state.documentId)
                if (state.documentFormat == DocumentFormat.EPUB) loadAllEmbeddedFonts()
                publishRest(state)
                startContinuations(state)
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (throwable: Throwable) {
                if (currentDocumentId != documentId) return@launch
                _uiState.value = ReaderUiState(
                    documentTitle = documentId.value,
                    isLoading = false,
                    errorMessage = throwable.message ?: "Failed to open document.",
                )
            }
        }
    }

    /**
     * [openDocument] 한 번의 호출이 열리는 문서에 대해 알아낸 모든 것으로, [loadOpenState]로부터
     * [publishFirstFrame], [startContinuations], [publishRest]까지 실려 간다 — 예전에는 하나의
     * 코루틴에서 실행되는 215줄짜리 함수 본문 하나였던 것과 같은 네 단계다. 그 본문을 여러 단계로
     * 나누어도 리더가 보는 것은 전혀 달라지지 않는다: 이는 그 하나의 코루틴이 이미 자신의
     * suspension point들을 가로질러 실어 나르던 로컬 변수들을 그대로 담는 단순한 보관소이며, 다른
     * 점은 어느 단계가 어떤 상태 조각을 읽거나 쓸 수 있는지가 이제 예전 함수 본문을 통째로 읽어야만
     * 알 수 있던 사실이 아니라 시그니처에 명시된 사실이라는 것이다.
     *
     * [loadOpenState]가 방금 측정한 페이지 창 목록인 [paginated]를 실어 나르므로, 의도적으로
     * `data class`가 아니다 — 복원된 페이지 창 목록을 인덱싱하는 것은 부수 효과로 페이지를 만들고
     * 캐시할 수 있어서, 생성된 `equals`/`hashCode`/`toString`을 느리고 불순하게 만든다(같은 이유는
     * [PaginatedDocument] 자체의 문서를 참고).
     *
     * @property documentId 이 open이 대상으로 하는 문서.
     * @property metadata 문서의 저장된 메타데이터 행, 저장소에 없으면 null.
     * @property readerDocument 문서의 파싱된 구조(섹션, navigation), 저장소에 아직 없으면 null.
     * @property settings 이 open에 적용되는 리더 설정.
     * @property documentFormat 문서의 형식; [metadata]가 null이면 [DocumentFormat.UNKNOWN].
     * @property documentUri 문서 자체의 소스 URI로, UI가 visual 페이지 이미지를 해석하는 데 쓰도록
     *   변경 없이 그대로 실려 간다.
     * @property paginated [loadOpenState]가 측정한 페이지 창들을, [readerDocument]의 섹션과 짝지은
     *   것.
     * @property isImportComplete 점진적 EPUB import가 모든 섹션을 파싱했으면 true; false면
     *   [startContinuations]가 계속 진행시켜야 한다.
     * @property isPaginationMeasured visual 문서이거나, 이 style에 대해 저장소가 확인한 완전한
     *   레이아웃이 [paginated]를 뒷받침하면 true — [isImportComplete]와는 다른 사실인데,
     *   완전히 import된 책도 현재 style에 대한 저장된 레이아웃은 없을 수 있기 때문이다.
     * @property totalPages 이 open의 첫 프레임이 발행하는 페이지 수.
     * @property currentPage 리더가 재개할 페이지로, 이미 `0 until totalPages`로 clamp되어 있다.
     */
    private class OpenState(
        val documentId: DocumentId,
        val metadata: DocumentMetadata?,
        val readerDocument: ReaderDocument?,
        val settings: ReaderSettings,
        val documentFormat: DocumentFormat,
        val documentUri: String?,
        val paginated: PaginatedDocument,
        val isImportComplete: Boolean,
        val isPaginationMeasured: Boolean,
        val totalPages: Int,
        val currentPage: Int,
    ) {
        /** [documentFormat]이 [DocumentFormat.PDF]인지 여부 — [openDocument]가 항상 그랬듯 페이지 텍스트를 억제한다. */
        val isPdfMode: Boolean get() = documentFormat == DocumentFormat.PDF

        /** [documentFormat]이 어떤 visual 페이지 형식이든 해당하는지 여부([isVisualPageFormat] 참고). */
        val isVisualMode: Boolean get() = documentFormat.isVisualPageFormat()
    }

    /**
     * [openDocument] 한 번의 호출이 무언가를 보여주기 전에 필요로 하는 모든 것을 로드한다: 문서의
     * 저장된 메타데이터, 섹션들, 저장된 읽기 진행률, 리더 설정, 점진적 EPUB import가 끝났는지 여부,
     * 그리고 이 style/viewport/breaker 조합이 측정하는 페이지 창들. 이전에 저장된 레이아웃의
     * viewport를 [viewportSize]/[pageBreakerStyle]에 채택한다 — 하나만이 아니라 항상 두 필드를
     * 함께 — 둘 중 하나만 남겨두면 이전 문서의 답이 살아남아 이 문서에 대한 pane의 첫 보고가 그와
     * 일치해버려서, 문서가 실제로 필요로 하는 reload를 건너뛰게 되기 때문이다 — 그리고 방금 측정된
     * 쌍을 [paginated]에, 재개할 오프셋을 [anchorOffset]에 기록한다. 이 쌍을 채택하는 것은 또한,
     * 같은 물리적 화면이므로 거의 항상 같은 크기인 pane의 첫 실제 보고가
     * [updatePageBreaker]에 의해 이미 답해진 것으로([PaneReportOutcome.RecordOnly]) 인식되어,
     * [DocumentRepository.getPageWindows]가 방금 바로 이 답을 캐시해 둔 것을 그저 되풀이할 뿐인
     * reload를 launch하지 않게 됨을 뜻한다.
     *
     * 여기서 읽는 로컬 `isImportComplete`는 점진적 EPUB import가 아직 끝나지 않은 문서를 제외한
     * 모든 문서에 대해 true다(`ReaderUiState.isPaginationComplete` 참고); 첫 발행 전에 여기서
     * 읽히므로, 이 open이 만들어내는 바로 그 첫 프레임이 이미 자신이 담은 페이지 수가 최종인지에
     * 대해 진실을 말한다. `hasReportedPaneSize`는 이 ViewModel 인스턴스에 속한 어떤 pane도 아직
     * 크기를 보고하지 않았을 때 정확히 true다 — [pageBreaker]는 오직 [updatePageBreaker]에
     * 의해서만 설정된다 — 그리고 이 값이 false일 때는 짐작으로 만든 [DefaultViewportSize] 대신
     * `viewportSize = null`을 [DocumentRepository.getPageWindows]에 넘겨서, 그 호출이 짐작값을
     * 기준으로 페이지를 나누는 대신 — 그런 짐작은 저장된 값과 거의 일치하지 않는다 — 정확히 이
     * style에 대해 지금까지 저장된 가장 최신 레이아웃을 해석하게 한다; 이것이 없던 때는 그 불일치가
     * 전체 estimate 패스로 새어 들어가 잘못된 페이지 수를 첫 프레임으로 발행했고, pane이 실제로
     * 측정을 마친 뒤에야 바로잡혔다. [DocumentRepository.getPageWindows]를 — 그 실제 pane 보고를
     * 먼저 기다리는 대신 — 여기서 무조건 호출하는 것은 또한, 저장된 어떤 행도 스스로는 해결할 수
     * 없는 교착 상태를 깨는 방법이기도 하다: 페이지가 없으면 pager는 어떤 슬롯도 마운트하지 않고,
     * 슬롯이 없으면 pane을 측정할 대상이 전혀 없으며, pane만이 유일하게 크기를 보고하는 존재인데,
     * 이것이 정확히 갓 import된 책이 시작하는 상태다. 더 아래의 `isPaginationMeasured`는 현재
     * style/viewport가 이미 실제 저장된 측정값을 가지고 있는지만을 반영하며, 이는
     * `isImportComplete`와는 다른 사실이다: 완전히 import된 책도 정확히 이 style에 대한 저장된
     * 레이아웃은 없을 수 있고, 그 경우 아래의 [DocumentRepository.getPageWindows] 호출은 재개할
     * 섹션만 측정했을 뿐이며 `isPaginationMeasured` 역시 false다. 이 함수가 마지막에 남기는 디버그
     * 로그는 `totalPages`를 낱장 페이지로 표시하는데, 이는 넓은 화면에서 리더 자신의 카운터가
     * 보여주는 것과 다르다 — 그쪽은 두 페이지 spread를 센다(`ReaderScreen`의
     * `readerSpreadPageIndex` 참고) — 그래서 8977을 보고하는 로그가 4489라고 읽는 표시줄 아래
     * 놓여도 실제로는 아무것도 잘못된 것이 아닐 수 있다.
     *
     * 여기서의 모든 suspend 읽기는 취소된 [Job]보다 오래 살아남을 수 있으므로(`Job.cancel()`은
     * 이미 진행 중인 데이터베이스 읽기를 멈출 수 없다), 이 함수는 더 새로운 [openDocument] 호출과
     * 경합할 수 있는 두 번의 읽기 이후 — 메타데이터/섹션/진행률/설정/import-완료 여부 배치, 그리고
     * 페이지 창 측정 — [currentDocumentId]를 재확인하고, 리더가 이미 떠난 문서를 위한 [OpenState]를
     * 만드는 대신 예외가 아니라 예측 가능한 부재로서 null을 답한다.
     *
     * @param documentId 열리는 중인 문서.
     * @return 나머지 세 단계가 필요로 하는 상태, 또는 로드 도중 [documentId]가 현재 문서가 아니게
     *   되었으면 null.
     */
    private suspend fun loadOpenState(documentId: DocumentId): OpenState? {
        val open = openReaderDocumentUseCase(
            documentId = documentId,
            hasReportedPaneSize = pageBreaker != null,
            viewportSize = viewportSize,
            viewportDensity = paneDensity,
            pageBreaker = pageBreaker,
            pageBreakerStyle = pageBreakerStyle,
        )
        if (currentDocumentId != documentId) return null
        if (!open.isVisualMode && pageBreaker == null) {
            viewportSize = open.rememberedViewportSize ?: DefaultViewportSize
            pageBreakerStyle = open.settings.style.takeIf { open.rememberedViewportSize != null }
        }
        paginated = open.paginated
        paginatedStyle = open.settings.style
        finalCharacterCount = open.metadata?.characterCount.takeIf { open.isImportComplete }
        logger.d {
            "opening total=${open.totalPages} single pages from windows=${open.pageWindows.size}, " +
                    "metadata=${open.metadata?.pageCount}, progress=${open.progress?.pageIndex?.total}, " +
                    "paginationMeasured=${open.isPaginationMeasured}"
        }
        anchorOffset = open.anchorOffset

        return OpenState(
            documentId = documentId,
            metadata = open.metadata,
            readerDocument = open.readerDocument,
            settings = open.settings,
            documentFormat = open.documentFormat,
            documentUri = open.documentUri,
            paginated = open.paginated,
            isImportComplete = open.isImportComplete,
            isPaginationMeasured = open.isPaginationMeasured,
            totalPages = open.totalPages,
            currentPage = open.currentPage,
        )
    }

    /**
     * 리더가 보게 될 첫 프레임 — style, 전체 페이지 수, 현재 페이지, 그 텍스트와 블록, 제목 — 을
     * 발행한다. [ReaderUiState.isLoading]을 지우기 전에 [ReaderUiState]가 필요로 하는 유일한 상태다.
     * `ReaderScreen`의 인디케이터는 그 플래그 위에 지연되어 있으므로, 이 발행은 여전히 랜딩 페이지
     * 자체가 필요로 하는 것만 실어 나른다; 이 open이 여전히 해야 할 나머지 모든 일 — opened-at
     * 쓰기, 아웃라인, 즐겨찾기/저장된 위치 플래그, 이웃 페이지 슬롯 — 은 나중에
     * [startContinuations]와 [publishRest]에서 일어나므로, 같은 함수에 속해 있다는 것 외의 이유로
     * 첫 프레임 앞을 가로막는 일은 없다.
     *
     * 그 페이지의 UI를 만들기 전에, pager가 곧 마운트하려는 바로 그 페이지 창에 대한 블록
     * 스타일링을 데워둔다([warmMountWindow]/[pagerMountWindow]). 섹션 0의 블록은 이 함수가
     * 실행되는 시점에 이미 준비되어 있지만([DocumentRepository.getPageWindows]가 표지 감지를
     * 위해 내부적으로 그것을 데운다 — `DocumentRepositoryImpl.restorePageWindows` 참고) 재개된
     * 페이지 자신의 섹션은 그렇지 않고, 그 이웃들도 마찬가지다; 어떤 페이지 UI든 만들기 전에
     * 정확히 `pageSlots()`가 마운트하는 창을 데우는 것이, 첫 프레임이 이미지나 챕터 제목 서식이
     * 여전히 빠진 채로 페이지를 그리지 않도록 지켜준다. 따로 짐작한 반경 대신 [pagerMountWindow] —
     * `pageSlots()`가 쓰는 것과 같은 범위 — 를 재사용하는 것이 핵심이다: `pageSlots()`가 실제로
     * 만들게 될 것이 무엇이든, 이는 이미 데워져 있다.
     *
     * 블록을 데우는 suspension 직후 한 번, 그리고 발행 직전에 다시 한 번 [currentDocumentId]를
     * 재확인한다. 두 번째 확인은 첫 번째와의 사이에 suspension이 없으므로 오늘날 실제로는
     * 불필요하다 — 그럼에도 유지하는 이유는 그것을 없애려면 이 분리 자체가 가지지 못한 정당화가
     * 필요하기 때문이다.
     *
     * @param state 이 open을 위해 [loadOpenState]가 만든 상태.
     * @return 첫 프레임이 발행되었으면 true; 데우는 동안 [state]의 문서가 현재 문서가 아니게
     *   되었으면 false.
     */
    private suspend fun publishFirstFrame(state: OpenState): Boolean {
        val pageWindows = state.paginated.pageWindows
        if (!state.isVisualMode && pageWindows.isNotEmpty()) {
            warmMountWindow(state.documentId, state.currentPage)
        }
        if (currentDocumentId != state.documentId) return false

        val pageIndex = PageIndex(current = state.currentPage, total = state.totalPages)
        val currentPageUi = currentReaderPageUi(
            pageUiContext(
                pageIndex = pageIndex,
                documentUri = state.documentUri,
                isPdfMode = state.isPdfMode,
                paginatedOverride = state.paginated,
            )
        )
        val documentTitle = state.readerDocument?.title
            ?: state.metadata?.location?.displayName
            ?: state.documentId.value

        if (currentDocumentId != state.documentId) return false
        _uiState.update { uiState ->
            uiState.copy(
                documentTitle = documentTitle,
                documentUri = state.documentUri,
                documentFormat = state.documentFormat,
                pageText = currentPageUi.text,
                pageIndex = pageIndex,
                readProgressPercent = readProgressPercentFor(
                    pageIndex = pageIndex,
                    isVisualMode = state.isVisualMode,
                    currentPercent = uiState.readProgressPercent,
                    paginatedDocument = state.paginated,
                ),
                currentPage = currentPageUi,
                style = styleWithPublisherFontKey(state.settings.style, state.documentFormat),
                pageLayoutStyle = paginatedStyle,
                publisherPageMargins = epubPageContainerMarginsEm(currentPageUi),
                areEmbeddedFontsResolved = state.documentFormat != DocumentFormat.EPUB || embeddedFontsSettled,
                pageTurnMode = state.settings.pageTurnMode,
                pageAnimation = state.settings.pageAnimation,
                autoScrollConfig = state.settings.autoScrollConfig.copy(enabled = false),
                isPdfMode = state.isPdfMode,
                isControlsVisible = true,
                isLoading = false,
                isPaginationComplete = state.isImportComplete && state.isPaginationMeasured,
            )
        }
        return true
    }

    /**
     * [publishRest]가 열리는 시점에 포착한 모든 것을 이미 발행한 지금, 이 open이 필요로 할 수 있는
     * 모든 콘텐츠 continuation을 시작한다: [OpenState.currentPage] 주변의 visual-page/내장 이미지
     * preload, 그리고 점진적 import-또는-페이지 나누기 continuation. 이 중 어느 것도 이미
     * [publishFirstFrame]이 알린 `pageIndex`, `pageText`, `currentPage`를 건드릴 수 없다.
     *
     * [openDocument]는 이를 [publishRest] 전이 아니라 후에 의도적으로 호출한다:
     * [continueImportIfIncomplete]는 점진적 EPUB import가 완료되면 스스로 아웃라인을 다시 발행할
     * 수 있는데, 그 완료 발행이 둘 중 더 이른 쪽이 되어 [publishRest] 자신의 더 오래된, open 시점
     * 스냅샷에 덮어써지는 일은 절대 없어야 한다. continuation들이 [publishRest]가 이미 완료까지
     * 실행된(자신의 suspend하는 [DocumentRepository.markDocumentOpened] 쓰기를 포함해) 뒤에만
     * 시작되므로, 완료 재발행은 오직 [publishRest]의 발행보다 나중에만 도달할 수 있으며 절대 그
     * 전에는 도달할 수 없다 — 예전에는 빠르게 해결되는 완료(이미 완료된 import, 남은 spine 항목이
     * 거의 없는 경우, 캐시된 레이아웃)가 신선한 아웃라인을 먼저 발행했다가, 잠시 후 [publishRest]가
     * 재개되어 조용히 낡은 것을 그 위에 다시 써버리는 순서였다. [publishRest] 뒤로 순서를 바꾸는
     * 것은 이 함수 자신이 읽는 것에는 영향을 주지 않는다: 이 함수가 쓰는 모든 값은 [_uiState]가
     * 아니라 [state]에서 오며, 둘 중 어느 발행이 실행되기도 전에 이미 [loadOpenState]가 완전히
     * 계산해 둔 것이다.
     *
     * 의도적으로 non-suspend로 선언되었다: 이 함수가 대체하는 코드 — 분리되지 않은 [openDocument]의
     * 첫 발행과 두 번째 발행 사이 블록 — 에는 suspension point가 전혀 없으며, 이 함수를
     * non-suspend로 유지하는 것이 컴파일러가 그 사실을 강제하게 만든다. 앞으로 여기에 삽입되는
     * suspend 호출은 두 발행의 순서를 조용히 바꾸는 대신 컴파일에 실패한다.
     *
     * [continuePaginationIfIncomplete]를 시작하는 것은 실제 breaker가 첫 섹션을 측정하고 나서야
     * 할 가치가 있다 — [pageBreakerFor]가 null을 답한다는 것은 첫 프레임이 실었던 페이지 나누기가
     * 추정치일 뿐이며, [updatePageBreaker]가 스스로 촉발하는 실제 측정으로 한두 프레임 안에
     * 대체된다는 뜻이고, 그 측정이 자신의 점진적 패스를 시작한다.
     *
     * 내장 폰트 해석은 [publishFirstFrame] 직후 별도로 시작되며, [publishRest]의 opened-at 쓰기
     * 이전이다. 아웃라인 상태를 덮어쓸 수 없고, 문서 전체를 훑는 인덱스 조회가 그 쓰기 뒤에서
     * 기다리는 대신 겹쳐 실행되어야 하기 때문이다. Import continuation은 그 완료된 아웃라인이 오직
     * [publishRest]의 더 오래된 스냅샷 뒤에만 발행될 수 있도록 여기 남아 있다.
     *
     * @param state 이 open을 위해 [loadOpenState]가 만든 상태.
     */
    private fun startContinuations(state: OpenState) {
        if (state.documentFormat == DocumentFormat.CBZ) loadVisualPagesAround(state.currentPage)
        if (state.documentFormat == DocumentFormat.EPUB) loadEmbeddedImagesAround(state.currentPage)
        if (!state.isImportComplete) {
            paginationContinuationJob?.cancel()
            continueImportIfIncomplete(state.documentId)
        } else if (!state.isPaginationMeasured && pageBreakerFor(state.settings.style) != null) {
            continuePaginationIfIncomplete(state.documentId, state.settings.style)
        }
    }

    /**
     * open을 기록한 다음, 첫 프레임이 필요로 하지 않았던 모든 것을 발행한다: 아웃라인, 즐겨찾기와
     * 저장된 위치 플래그, 그리고([republishSurroundingPages]를 통한) 이웃 페이지 슬롯 —
     * [refreshEpubPages]가 내장 이미지에 대해 이미 하는 것과 같은 방식으로 채워지고 다시
     * 알려진다. `pageIndex`, `pageText`, `currentPage`를 건드려서는 안 된다 —
     * [publishFirstFrame]이 이미 그것들을 알렸고, 여기서 다시 건드리면 이 두 번째, 더 나중 발행이
     * 조용히 리더를 움직여버릴 것이다.
     *
     * [markDocumentOpened][DocumentRepository] 쓰기 직후 — 이 단계의 유일한 suspension — 다른
     * 무엇을 건드리기 전에 [currentDocumentId]를 한 번 재확인한다.
     *
     * @param state 이 open을 위해 [loadOpenState]가 만든 상태.
     */
    private suspend fun publishRest(state: OpenState) {
        documentRepository.markDocumentOpened(
            documentId = state.documentId,
            openedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
        )
        if (currentDocumentId != state.documentId) return
        publishOutline(
            documentId = state.documentId,
            format = state.metadata?.format,
            readerDocument = state.readerDocument,
            totalPages = state.totalPages,
        )
        republishSurroundingPages()
        _uiState.update { uiState ->
            uiState.copy(
                isFavorite = state.metadata?.isBookmarked == true,
                isCurrentPageSaved = isPageSaved(uiState.pageIndex, uiState.isVisualMode),
            )
        }
    }

    /**
     * [readerDocument]로부터 [ReaderUiState.outlineHeading]과 [ReaderUiState.outlineItems]를
     * 다시 발행한다. 둘 다 [readerOutlineItems]를 거치는 단 하나의 지점이어서, open과 그 이후의
     * 재발행이 절대 아웃라인을 두 가지 다른 방식으로 도출하지 않게 한다. [publishRest]가 open
     * 시점에 한 번 이를 호출하고, [continueImportIfIncomplete]의 완료 분기가 점진적 EPUB import가
     * 끝나면 다시 한 번 호출한다 — 그 두 번째 호출이 다음 재실행 전까지 아웃라인이 비어 있는 채로
     * 남는 문제의 온전한 해결책이다: EPUB navigation은 책을 완성하는 import 배치에서만 저장된
     * 문서로 해석되므로(`DocumentRepositoryImpl.importEpubPhase0`/`finishEpubImport`), 이
     * 인스턴스가 열릴 때 가지고 있던 [ReaderDocument]는 책이 완전히 import된 뒤에도 무언가가 이를
     * 다시 읽고 재발행하기 전까지는 여전히 빈 `ReaderNavigation`을 담고 있을 수 있다.
     *
     * 이 클래스 자체의 재확인 관례에 따라, [_uiState]를 건드리기 전에 [currentDocumentId]를
     * [documentId]와 대조해 재확인한다 — [readerDocument]는 리더가 그 뒤로 떠난 문서에 대해 읽힌
     * 것일 수 있는데, 여기의 모든 호출자가 최소 하나의 suspension point를 지난 뒤 이 함수에
     * 도달하기 때문이다.
     *
     * @param documentId [readerDocument]가 읽힌 대상 문서.
     * @param format 문서의 형식, 아직 알려지지 않았으면 null; 변경 없이 그대로
     *   [readerOutlineItems]에 전달된다.
     * @param readerDocument navigation/섹션이 아웃라인을 뒷받침하는, 방금 읽은 문서, 또는 null.
     * @param totalPages visual 형식에 대해 아웃라인이 다뤄야 할 페이지 수; [readerDocument]가
     *   새로고침되기 전에 포착된 것이 아니라 이 호출 시점에 정확한 전체 값이어야 한다.
     */
    private fun publishOutline(
        documentId: DocumentId,
        format: DocumentFormat?,
        readerDocument: ReaderDocument?,
        totalPages: Int,
    ) {
        if (currentDocumentId != documentId) return
        val outlineItems = readerOutlineItems(
            format = format,
            readerDocument = readerDocument,
            totalPages = totalPages,
        )
        _uiState.update { uiState ->
            uiState.copy(
                outlineHeading = readerDocument?.navigation?.heading,
                outlineItems = outlineItems.toImmutableList(),
            )
        }
    }

    /**
     * 페이지 나누기가 반드시 일치해야 하는, 렌더링된 텍스트 레이아웃과 그것이 측정된 style을
     * 함께 담는다. 페이지 나누기는 현재 style과 맞는 breaker를 기다리는데, 이전 측정값에 대고 새
     * 폰트 크기로 재 페이지 나누기를 하는 것이 정확히 마지막 줄을 잘라내는 원인이기 때문이다.
     *
     * 이는 유일한 측정 트리거다 — pane은 예전에 별도의 viewport 콜백을 통해서도 보고했는데, 그
     * 콜백과 이것이 둘 다 자신만의 reload를 launch했기 때문에 리사이즈 한 번에 `getPageWindows`
     * 호출이 두 번 일어났다(`Job.cancel()`은 이미 진행 중인 DB 읽기를 멈출 수 없다). 이제 pane은
     * 자신의 크기를 한 번, 두 값으로 나누어 보고한다: [viewportSp]는 페이지 나누기와 페이지 레이아웃
     * 저장이 키로 삼는 sp 값 — PageLayoutEntity의 viewportWidthPx/viewportHeightPx 컬럼이
     * 이름과 달리 실제로 담고 있는 것과 같은 단위 — 이며 아래의 [viewportSize]가 된다;
     * [measuredSizePx]는 실제 픽셀 박스로, 리더가 이미 답한 보고를 알아보는 용도로만 유지된다.
     */
    fun updatePageBreaker(
        style: ReaderStyle,
        viewportSp: ViewportSize,
        measuredSizePx: ViewportSize,
        breaker: ReaderPageBreaker,
        measuredWithFinalFonts: Boolean = true,
    ) {
        // 페이지 나누기는 (텍스트, style, 폰트 집합, pane 픽셀)의 순수 함수다. 문서의 폰트 집합이
        // 확정되기 전에 만들어진 breaker는 대체 활자로 측정되며, 그 측정값이 최종 키로 저장되면
        // 이후의 모든 open을 오염시킨다 — 유효한 것처럼 복원되어, 실제 활자가 더 길게 뻗는 모든
        // 페이지를 잘라낸다. 모든 측정값이 지나가는 이 한 지점에서 거부하는 것이, 화면의
        // recomposition 전반에 걸쳐 composition 타이밍을 신뢰하던 방식을 대체한다.
        if (_uiState.value.documentFormat == DocumentFormat.EPUB &&
            (!measuredWithFinalFonts || !embeddedFontsSettled)
        ) {
            logger.d { "breaker report rejected: embedded fonts not settled yet" }
            return
        }
        // 페이지 나누기는 sp 박스가 아니라 실제 픽셀 박스를 기준으로 키가 걸린다: 밀도가 다른 두
        // pane(폴더블의 내부 화면과 커버 화면)은 같은 sp 크기로 반올림되어도 줄바꿈은 다르게 될 수
        // 있으며, 한쪽의 페이지 나누기를 다른 쪽에 재사용하면 다른 쪽의 마지막 줄이 잘렸다.
        val outcome = paneReportOutcome(
            reportedStyle = style,
            reportedSizePx = measuredSizePx,
            reportedViewportSp = measuredSizePx,
            currentBreakerStyle = pageBreakerStyle,
            currentBreakerSizePx = pageBreakerSize,
            hasBreaker = pageBreaker != null,
            currentViewportSp = viewportSize,
        )
        if (outcome == PaneReportOutcome.Ignore) {
            logger.d { "breaker report ignored, already measured for $measuredSizePx" }
            return
        }
        if (outcome == PaneReportOutcome.RecordOnly) {
            logger.d { "breaker report accepted without reload, viewport already answered by $viewportSp" }
            pageBreaker = breaker
            pageBreakerStyle = style
            pageBreakerSize = measuredSizePx
            return
        }
        logger.d { "breaker report accepted for $measuredSizePx, previously $pageBreakerSize" }
        pageBreaker = breaker
        pageBreakerStyle = style
        pageBreakerSize = measuredSizePx
        viewportSize = measuredSizePx
        paneDensity = (measuredSizePx.widthPx.toFloat() / viewportSp.widthPx.toFloat()).takeIf { it > 0f && it.isFinite() } ?: 1f
        viewportReloadJob?.cancel()
        viewportReloadJob = viewModelScope.launch {
            reloadPages(style = _uiState.value.style)
            currentDocumentId?.let { documentId ->
                refreshPaginationCompleteness(
                    documentId,
                    style,
                    isImportComplete = documentRepository.isImportComplete(documentId)
                )
            }
        }
    }

    /**
     * 점진적 EPUB import의 2단계 이후: 저장소에 완료를 보고할 때까지, spine 순서로 책을 한도 있는
     * 배치만큼 더 파싱하고 측정하도록 반복해서 요청한다. 중간 배치들은 [reloadPages]를 다시
     * 실행하지 않는다; import는 완료될 때까지 파싱을 계속하고 pageIndex.total은 그대로 둔다.
     * import가 실제로 끝나면 마지막 [reloadPages] 한 번이 이미 발행된 페이지를 전혀 건드리지 않고
     * 커진 페이지 목록을 발행한다(TextPageLayoutEngine/DocumentRepositoryImpl.importNextSections
     * 참고: 덧붙이기는 항상 저장된 페이지 시작점만 늘릴 뿐이다).
     * 이 ViewModel 자신의 scope에서 실행되므로, 리더를 떠나면 그저 멈춘다; 다음에 열릴 때 저장된
     * 행들이 말하는 완료 지점부터 재개한다 — 별도의 scope도, 새로운 서브시스템도 없다.
     *
     * 무언가를 import한 배치는 (새로 import된 섹션들의 것뿐 아니라) 저장소가 디코딩해 둔
     * section-blocks 캐시 전체도 무효화한다(`DocumentRepositoryImpl.importNextSections` 참고) —
     * 그래서 리더가 이미 보고 있는 페이지도, 아직 아무것도 디코딩되지 않은 갓 열린 책과 똑같이
     * 조용히 자신의 블록을 잃는다. [reloadPages] 자체가 발행하려는 마운트 창을 데우므로(자체
     * 문서 참고), 여기서 그것이 만드는 어떤 발행이든 이미 페이지 고유의 스타일링을 담고 있어서
     * 한 박자 늦게 도착하는 두 번째 보정 발행이 필요 없다. 같은 [reloadPages] 호출은 또한 리더가
     * 재개해 들어간 섹션만 다시 측정하는데([DocumentRepository.getPageWindows] 자체의 fallback
     * 참고), 위의 이 배치의 캐시 무효화가 진행 중이던 페이지 나누기 세션을 무엇이든 지워버렸기
     * 때문이며, 이것이 import가 완료될 때까지 페이지 나누기가 다시 이어지지 않는 이유다. 이 분리는
     * import와 페이지 나누기가 겹치지 않게 하면서도, 한도 있는 보류 중 "다음" 요청 하나가 새로
     * import된 이웃이 존재하게 되는 즉시 드러나게 해 준다.
     *
     * import가 끝난다고 해서 그 자체로 페이지 나누기도 끝났다는 뜻은 아니다: 책은 여전히 이
     * style에 대한 저장된 레이아웃이 없을 수 있고, 그 경우 [DocumentRepository.getPageWindows]는
     * 재개된 섹션만 측정했을 뿐이며 [refreshPaginationCompleteness]가 이어서 진행할 것이 더 있다.
     *
     * 완료 분기는 또한 [DocumentRepository.getDocument]와 [DocumentRepository.getReaderDocument]를
     * 다시 읽고 [publishOutline]을 통해 아웃라인을 재발행한다 — 이 인스턴스가 열릴 때 가지고 있던
     * [ReaderDocument]는 책 전체가 import된 뒤에도 여전히 빈 navigation을 담고 있을 수 있는데,
     * EPUB navigation은 책을 완성하는 배치에서만 해석되기 때문이며, 이 재발행이 없다면 리더가 앱을
     * 다시 실행할 때까지 드로어가 비어 있는 채로 남았다. [publishOutline]의 `format` 인자는 방금
     * 다시 읽은 문서의 메타데이터가 아니라 [_uiState]의 이미 발행된 [ReaderUiState.documentFormat]에서
     * 읽힌다: 그 메타데이터 행은 import 배치가 완료되고 이 분기가 실행되기 사이에 삭제되었을 수
     * 있는데(import가 여전히 끝나가는 동안 서가에서 제거된 책), 그렇게 되면 `format`이 null이 되어
     * [readerOutlineItems]가 자신의 EPUB 분기에서 벗어나고, 해석된 navigation의 heading을 그와
     * 맞지 않는 [ReaderLocation.TextOffset] 위치들로 만들어진 fallback 섹션 목록 위에 재발행하게
     * 될 것이다. [publishFirstFrame]은 [startContinuations]가 이 코루틴을 시작할 수 있기 전에
     * 무조건 [ReaderUiState.documentFormat]을 발행하므로, 이 분기가 그것을 읽는 시점에는 항상
     * 현재 열린 문서에 대해 설정되어 있다.
     */
    private fun continueImportIfIncomplete(documentId: DocumentId) {
        importContinuationJob?.cancel()
        paginationContinuationJob?.cancel()
        importContinuationJob = viewModelScope.launch {
            while (currentDocumentId == documentId) {
                val style = _uiState.value.style
                val progress = documentRepository.importNextSections(
                    documentId = documentId,
                    count = ImportBatchSectionCount,
                    style = style,
                    viewportSize = viewportSize,
                    viewportDensity = paneDensity,
                    pageBreaker = pageBreakerFor(style),
                )
                if (currentDocumentId != documentId) return@launch
                if (progress.isComplete) {
                    val completedMetadata = documentRepository.getDocument(documentId)
                    finalCharacterCount = completedMetadata?.characterCount
                    if (currentDocumentId != documentId) return@launch
                    // 이제 책 전체가 존재하므로 폰트 집합을 마침내 최종이라고 부를 수 있다 — import
                    // 도중 실행된 스캔은 책의 일부만 보았으므로 플래그를 의도적으로 내려 두었다.
                    loadAllEmbeddedFonts()
                    reloadPages(style)
                    if (currentDocumentId != documentId) return@launch
                    val totalPagesAfterReload = _uiState.value.pageIndex.total
                    publishOutline(
                        documentId = documentId,
                        format = _uiState.value.documentFormat,
                        readerDocument = documentRepository.getReaderDocument(documentId),
                        totalPages = totalPagesAfterReload,
                    )
                    refreshPaginationCompleteness(documentId, style, isImportComplete = true)
                    return@launch
                }
            }
        }
    }

    /**
     * [DocumentRepository.getPageWindows]가 시작했지만 한 번의 호출로 끝내지 못한 점진적 페이지 나누기
     * 패스의 나머지를 진행시킨다 — 그 함수의 anchorOffset 문서를 참고한다.
     * [continueImportIfIncomplete]를 그대로 반영한다: 저장소에 또 다른 제한된 배치를 측정하도록 반복해서
     * 요청하며, 대기 중인 다음 페이지 요청이 이제 충족될 수 있거나 패스가 완료를 보고할 때만
     * [reloadPages]를 다시 실행한다. [style]이 더 이상 현재 것이 아니게 되는 순간 스스로 멈추므로, 측정
     * 도중 시작된 활자체 변경은 이를 위해 경쟁하는 대신 자신의 새 패스가 ui의 isPaginationComplete
     * 플래그를 직접 소유하게 한다.
     *
     * 여전히 자라나는 중인 배치는 이 continuation이 걷고 있는 바로 그 페이지 나누기 세션을 무효화할 수
     * 있고(`DocumentRepositoryImpl.invalidateDocumentCache` 참고), 그렇게 되면
     * [DocumentRepository.continuePagination]은 `sectionsMeasured = 0`인 채로 `isComplete = true`를
     * 응답한다 — 이는 "책이 끝났다"가 아니라 "이 walk가 더 할 말이 없다"는 신호다. 그것만으로
     * `isPaginationComplete`를 발행하면 import가 아직 그 아래에서 섹션을 추가하는 중인데도 페이지 나누기가
     * 끝났다고 리더에게 알리게 된다. import continuation과 페이지 나누기 continuation은 서로 배타적이며,
     * [DocumentRepository.isImportComplete]가 동의할 때만 "완료"를 신뢰하는 것이 오래된 세션이 거짓 종료
     * 상태를 발행하는 일을 막아준다.
     */
    private fun continuePaginationIfIncomplete(documentId: DocumentId, style: ReaderStyle) {
        paginationContinuationJob?.cancel()
        paginationContinuationJob = viewModelScope.launch {
            while (currentDocumentId == documentId && _uiState.value.style.layoutKey() == style.layoutKey()) {
                val breaker = pageBreakerFor(style) ?: return@launch
                val progress = documentRepository.continuePagination(
                    documentId = documentId,
                    style = style,
                    viewportSize = viewportSize,
                    viewportDensity = paneDensity,
                    pageBreaker = breaker,
                )
                if (currentDocumentId != documentId) return@launch
                if (pendingMoveNextStep != null || progress.isComplete) reloadPages(style)
                if (progress.isComplete) {
                    if (currentDocumentId == documentId && documentRepository.isImportComplete(
                            documentId
                        )
                    ) {
                        _uiState.update { state -> state.copy(isPaginationComplete = true) }
                    }
                    return@launch
                }
            }
        }
    }

    /**
     * [reloadPages]가 [style]에 대해 가장 최근에 측정한 페이지 나누기가 실제로 끝났는지 확인하고, 아니면
     * 백그라운드에서 이를 계속 진행시킨다([continuePaginationIfIncomplete] 참고). 이미 import된 문서에서
     * 진짜로 새로운 측정 패스를 시작할 수 있는 모든 이벤트 뒤에 호출된다: pane의 어떤 style에 대한 첫
     * 실제 보고, 그리고 폰트/줄 간격/활자체 변경.
     *
     * [isImportComplete]는 이 함수가 스스로 저장소에 물어보는 값이 아니라 호출자가 넘겨주는 사실이다,
     * 왜냐하면 [continueImportIfIncomplete]의 완료 분기는 이를 호출하는 시점에 이미
     * [ImportProgress.isComplete]에 있는 가장 최신의 답을 가지고 있기 때문이다 — 다시 물어봐야 실제
     * 프로덕션에서는 같은 답을 반복할 뿐이고, importNextSections()의 반환값과 별개로
     * isImportComplete()를 모델링하는 테스트 더블에는 둘이 일치하리라고 보장할 근거가 없다.
     */
    private suspend fun refreshPaginationCompleteness(
        documentId: DocumentId,
        style: ReaderStyle,
        isImportComplete: Boolean
    ) {
        if (currentDocumentId != documentId) return
        if (!isImportComplete) {
            _uiState.update { state -> state.copy(isPaginationComplete = false) }
            return
        }
        val isPaginationMeasured = documentRepository.isPaginationComplete(documentId)
        if (needsPaginationContinuation(
                isPaginationMeasured,
                hasMeasurementForStyle = pageBreakerFor(style) != null
            )
        ) {
            continuePaginationIfIncomplete(documentId, style)
            return
        }
        if (canReportPaginationComplete(isImportComplete, isPaginationMeasured)) {
            _uiState.update { state -> state.copy(isPaginationComplete = true) }
        }
    }

    /** [style]에 대해 만들어진 측정값만이 [style]이 실제로 렌더링할 페이지들을 설명한다. */
    private fun pageBreakerFor(style: ReaderStyle): ReaderPageBreaker? =
        pageBreaker.takeIf { pageBreakerStyle?.layoutKey() == style.layoutKey() }

    /**
     * 리더의 chrome(상단·하단 바)이 보이는지 여부를 토글한다 — 예컨대 화면 중앙 영역을 탭했을 때
     * 호출된다.
     */
    fun toggleControls() {
        _uiState.update { state -> state.copy(isControlsVisible = !state.isControlsVisible) }
    }

    /** [sheet]를 활성 리더 옵션 시트로 연다 — 표시되고 있던 시트가 있다면 그것을 대체한다. */
    fun showSheet(sheet: ReaderOptionSheet) {
        _uiState.update { state -> state.copy(activeSheet = sheet) }
    }

    /** 현재 표시 중인 리더 옵션 시트를 무엇이든 닫는다. */
    fun dismissSheet() {
        _uiState.update { state -> state.copy(activeSheet = null) }
    }

    /**
     * 현재 문서의 즐겨찾기 플래그를 뒤집어, 새 상태를 즉시 발행하고 백그라운드에서 영속화한다.
     *
     * 발행은 낙관적이다: [documentRepository]가 무엇도 확인해주기 전에 별이 먼저 뒤집히는데,
     * 일반적인 경로에서는 기다릴 것이 없기 때문이다. 쓰기가 실패하면 — 가장 흔하게는 문서 자신의
     * 행이 이미 사라졌기 때문에(테스트 스위트의 `togglingFavoriteRollsBackWhenTheDocumentRowIsGone`
     * 참고) — 플래그는 탭하기 전 상태로 롤백되며, 이는 오직 [currentDocumentId]가 여전히 이
     * 문서를 가리킬 때만이다, 그래서 리더가 이미 다른 책으로 넘어간 뒤에 도착한 실패가 그 책의
     * 플래그를 대신 뒤집는 일은 없다.
     */
    fun toggleFavorite() {
        val documentId = currentDocumentId ?: return
        val wasFavorite = _uiState.value.isFavorite
        _uiState.update { it.copy(isFavorite = !wasFavorite) }
        viewModelScope.launch {
            suspendRunCatching {
                val document = requireNotNull(documentRepository.getDocument(documentId))
                documentRepository.upsertDocument(document.copy(isBookmarked = !wasFavorite))
            }.onFailure {
                if (currentDocumentId == documentId) {
                    _uiState.update { it.copy(isFavorite = wasFavorite) }
                }
            }
        }
    }

    /**
     * 현재 페이지를 저장 위치로 저장하거나, 이미 저장되어 있었다면 제거한다 — [toggleFavorite]가
     * 쓰는 것과 같은 낙관적-후-영속화 모양으로 새 상태를 즉시 발행하며, 쓰기가 실패하면 롤백한다.
     *
     * 저장된 위치의 id는 항상 `"${documentId.value}:${location.asStorageString()}"` — 문서 id와
     * 위치의 저장 문자열을 콜론으로 이어붙인 것이다. 이 형식 덕분에 같은 페이지를 두 번 저장해도
     * 행이 추가되는 대신 하나로 대체되며, 이는 오직 여기서만 만들어진다; 앞으로 생성된 id로 바뀌는
     * 변경이 생기면 이 토글은 소리 없이 추가(append)로 바뀔 것이며, 이것이 테스트 스위트의
     * `savedPlaceIdIsDocumentIdAndLocationStorageString`이 고정하는 바다.
     */
    fun toggleSavedPlace() {
        val documentId = currentDocumentId ?: return
        val pageIndex = _uiState.value.pageIndex
        val location = currentLocation(pageIndex)
        val existing = savedPlaces.firstOrNull { it.location == location }
        val savedPlace = Bookmark(
            id = "${documentId.value}:${location.asStorageString()}",
            documentId = documentId,
            location = location,
            label = null,
            createdAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
        )

        savedPlaces = if (existing == null) savedPlaces + savedPlace else savedPlaces - existing
        _uiState.update { it.copy(isCurrentPageSaved = existing == null) }
        viewModelScope.launch {
            suspendRunCatching {
                if (existing == null) {
                    bookmarkRepository.saveBookmark(savedPlace)
                } else {
                    bookmarkRepository.deleteBookmark(existing.id)
                }
            }.onFailure {
                savedPlaces =
                    if (existing == null) savedPlaces - savedPlace else savedPlaces + existing
                updateSavedPlaceState()
            }
        }
    }

    /** 이 리더가 화면에 떠 있는 동안 기기 화면을 계속 켜 둘지 여부를 발행한다. */
    fun updateKeepScreenOn(enabled: Boolean) {
        _uiState.update { state -> state.copy(keepScreenOn = enabled) }
    }

    /** 리더가 시스템 바를 숨기고 edge-to-edge로 그릴지 여부를 발행한다. */
    fun updateFullscreen(enabled: Boolean) {
        _uiState.update { state -> state.copy(fullscreen = enabled) }
    }

    /** 읽기 진행률 표시기를 보여줄지 여부를 발행한다. */
    fun updateShowProgress(enabled: Boolean) {
        _uiState.update { state -> state.copy(showProgress = enabled) }
    }

    /**
     * [updateStyle]을 통해 새 폰트 크기를 적용한다 — 실제로 책을 다시 흘려보낼지는 그 함수가
     * 결정한다.
     */
    fun updateFontSize(fontSizeSp: Float) {
        updateStyle(_uiState.value.style.copy(fontSizeSp = fontSizeSp))
    }

    /**
     * [updateStyle]을 통해 새 줄 간격 배율을 적용한다 — 실제로 책을 다시 흘려보낼지는 그 함수가
     * 결정한다.
     */
    fun updateLineHeight(lineHeightMultiplier: Float) {
        updateStyle(_uiState.value.style.copy(lineHeightMultiplier = lineHeightMultiplier))
    }

    /**
     * [updateStyle]을 통해 새 폰트 패밀리를 적용한다 — 실제로 책을 다시 흘려보낼지는 그 함수가
     * 결정한다.
     */
    fun updateFontFamily(fontFamilyName: String?) {
        updateStyle(_uiState.value.style.copy(fontFamilyName = fontFamilyName))
    }

    /**
     * [updateStyle]을 통해 새 폰트 굵기를 적용한다 — 실제로 책을 다시 흘려보낼지는 그 함수가
     * 결정한다. 더 굵거나 더 얇은 굵기는 폰트 패밀리 변경과 마찬가지로 글리프 advance를
     * 바꾸므로 줄바꿈을 옮기며, 같은 reflow 판단을 거친다.
     */
    fun updateFontWeight(fontWeight: Int) {
        updateStyle(_uiState.value.style.copy(fontWeight = fontWeight))
    }

    /**
     * [updateStyle]을 통해 새 테마 모드를 적용한다 — 실제로 책을 다시 흘려보낼지는 그 함수가
     * 결정한다.
     */
    fun updateThemeMode(mode: ReaderThemeMode) {
        updateStyle(_uiState.value.style.withThemeMode(mode))
    }

    /**
     * 새 페이지 넘김 모드를 발행하고 영속화한다. [ReaderStyle.layoutKey]의 일부가 아니므로 —
     * 페이지가 어떻게 넘어가든 텍스트는 같은 자리에서 끊기므로 — [updateStyle]과 달리 이는 절대
     * 재 페이지 나누기를 촉발하지 않는다.
     */
    fun updatePageTurnMode(mode: PageTurnMode) {
        _uiState.update { state -> state.copy(pageTurnMode = mode) }
        saveReaderSettings { readerSettingsRepository.updatePageTurnMode(mode) }
    }

    /**
     * 새 페이지 넘김 애니메이션을 발행하고 영속화한다. [ReaderStyle.layoutKey]의 일부가 아니므로 —
     * 페이지가 어떻게 애니메이션되든 텍스트는 같은 자리에서 끊기므로 — [updateStyle]과 달리 이는
     * 절대 재 페이지 나누기를 촉발하지 않는다.
     */
    fun updatePageAnimation(animation: PageAnimation) {
        _uiState.update { state -> state.copy(pageAnimation = animation) }
        saveReaderSettings { readerSettingsRepository.updatePageAnimation(animation) }
    }

    /**
     * [updateAutoScroll]을 통해 자동 스크롤을 켜거나 끈다 — 실행되는 동안 리더 chrome도 함께
     * 숨긴다.
     */
    fun updateAutoScrollEnabled(enabled: Boolean) {
        updateAutoScroll(_uiState.value.autoScrollConfig.copy(enabled = enabled))
    }

    /** [updateAutoScroll]을 통해 자동 스크롤 모드를 바꾼다. */
    fun updateAutoScrollMode(mode: AutoScrollMode) {
        updateAutoScroll(_uiState.value.autoScrollConfig.copy(mode = mode))
    }

    /**
     * [updateAutoScroll]을 통해 자동 스크롤 속도를 바꾼다 — [AutoScrollConfig.clampSpeed]로
     * clamp된다.
     */
    fun updateAutoScrollSpeed(speed: Float) {
        updateAutoScroll(
            _uiState.value.autoScrollConfig.copy(
                speed = AutoScrollConfig.clampSpeed(
                    speed
                )
            )
        )
    }

    /**
     * 자동 스크롤이 현재 실행 중이면 끈다; 아니면 아무 일도 하지 않으므로, 호출자가 미리 확인할
     * 필요는 없다.
     */
    fun stopAutoScroll() {
        if (!_uiState.value.autoScrollConfig.enabled) return
        updateAutoScrollEnabled(false)
    }

    /**
     * 새 밝기 오버레이 불투명도를 `0f..0.8f`로 clamp하여 발행한다 — 오버레이는 절대 완전히
     * 불투명해지지 않는다.
     */
    fun updateBrightnessOverlayAlpha(alpha: Float) {
        _uiState.update { state -> state.copy(brightnessOverlayAlpha = alpha.coerceIn(0f, 0.8f)) }
    }

    /**
     * 리더가 지금 있는 곳에서 [step] 페이지만큼 뒤로 이동하며, 첫 페이지로 clamp된다.
     *
     * 이미 해석된 목표 인덱스가 아니라 오직 step만 받는데, 상대 이동은 호출자가 이동을 결정한
     * 시점의 페이지 나누기가 아니라 실제로 실행되는 시점에 현재인 페이지 나누기를 기준으로 해석되어야
     * 하기 때문이다. 호출자가 대신 페이지 인덱스를 미리 계산해 [moveToPage]에 넘긴다면, 그 사이에
     * 일어난 폰트나 줄 간격 변경의 재 페이지 나누기가 그 인덱스를 낡게 만들고, [moveToPage]는 그것을
     * 새로, 더 짧아진 문서 안으로 clamp해버려서 — 의도했던 다음 페이지가 아니라 마지막 페이지에
     * 도달하게 된다. 호출 시점에 읽은 `_uiState.value.pageIndex`를 기준으로 여기서 step을
     * 해석하는 것이, 그 사이의 재 페이지 나누기가 잘못 배치할 수 없는 이유다.
     *
     * @param step 뒤로 이동할 페이지 수; 최소 1로 coerce된다.
     */
    fun movePrevious(step: Int = 1) {
        val pageIndex = _uiState.value.pageIndex
        if (pageIndex.total <= 0) return
        val target = (pageIndex.current - step.coerceAtLeast(1)).coerceAtLeast(0)
        if (target != pageIndex.current) moveToPage(target)
    }

    /**
     * 리더가 지금 있는 곳에서 [step] 페이지만큼 앞으로 이동한다. 목표 페이지가 이미 알려져
     * 있으면 즉시 그곳으로 이동한다; 리더가 여전히 자라나는 중인 문서의 알려진 끝에 있다면, 한도
     * 있는 보류 중 요청 하나가 그 step만 보관해 두었다가 다음 reload 이후 재시도한다. 그 외의
     * 경우 요청은 마지막 페이지로 clamp되는 대신 버려진다 — 오직 step만, 해석된 목표 인덱스가
     * 아니라, 이것이 무엇을 기준으로 해석하는지에 대한 이유는 [movePrevious] 자체의 문서를 참고.
     *
     * @param step 앞으로 이동할 페이지 수; 최소 1로 coerce된다.
     */
    fun moveNext(step: Int = 1) {
        val pageIndex = _uiState.value.pageIndex
        val normalizedStep = step.coerceAtLeast(1)
        val target = pageIndex.current + normalizedStep
        if (target in 0 until pageIndex.total) {
            pendingMoveNextStep = null
            moveToPage(target)
            return
        }
        if (!_uiState.value.isPaginationComplete) {
            pendingMoveNextStep = maxOf(pendingMoveNextStep ?: 0, normalizedStep)
        }
    }

    /**
     * 아웃라인, 검색, 또는 북마크에서 선택된 [location]을 보여주는 페이지로 이동한다.
     *
     * [readerOutlineItems]의 visual-형식 분기는 아웃라인 항목을 오직 [ReaderLocation.PdfPage]로만
     * 만들며, [PaginatedDocument.pageOf]는 그 variant를 null로 해석한다 — [absoluteOffsetOf]
     * 자체가 글자 오프셋이 아니라 페이지 번호에 대해서는 null을 답하므로, 줄 절대 오프셋이 없기
     * 때문이다. 그래서 아래의 [ReaderLocation.PdfPage] 분기가 PDF/CBZ 아웃라인 탭을 실제로
     * 어딘가로 이동시키는 유일한 경로다; 이를 그 아래 줄의 `paginated.pageOf(location)` 호출로
     * 합쳐버리면 모든 visual-문서 아웃라인 탭이 영구히 아무 일도 하지 않게 될 것이며, 이는 정확히
     * AGENTS.md의 리더 불변 조건 — UI가 제공하는 페이지 넘김은 반드시 진행해야 한다 — 이 금지하는
     * 바다.
     *
     * @param location 이동할 읽기 위치.
     */
    fun moveToLocation(location: ReaderLocation) {
        val page = when (location) {
            is ReaderLocation.PdfPage -> location.pageIndex
            else -> paginated.pageOf(location) ?: _uiState.value.pageIndex.current
        }
        moveToPage(page)
    }

    /**
     * [style]을 즉시 발행하고 영속화하며, 변경이 실제로 페이지 구분을 옮길 때만 책을 다시
     * 레이아웃한다.
     *
     * [ReaderStyle.layoutKey]에 대한 변경 — 활자에 영향을 주는 필드들 — 만이 페이지가 어디서
     * 끊기는지를 옮길 수 있다; 색상이나 배경 변경은 여전히 저장되고 현재 페이지를 다시 그리지만,
     * 그것을 위해 책 전체를 다시 레이아웃하는 것은 리더가 화면의 단 한 픽셀에서도 확인하지 못할
     * 작업을 소모한다. layout key가 실제로 바뀔 때, [reloadPages] 자체는 갓 실행된 [openDocument]가
     * 그러듯 리더가 현재 있는 섹션만 측정하는데, 아직 아무도 이 활자로 이 책을 읽은 적이 없어서
     * 대신 쓸 저장된 레이아웃이 없기 때문이다; 나머지 측정을 마무리하는 일은 백그라운드의
     * [refreshPaginationCompleteness]에 맡겨진다.
     *
     * layout-key 변경은 어떤 재측정도 일어나기 전에, 동기적으로 여기서 [style]을
     * [ReaderUiState.style]에 발행한다 — [reloadPages]는 그 뒤에야, [saveReaderSettings]가
     * launch한 코루틴 안에서 시작되며, 그마저도 pane이 새 key에 대해 recompose하고 다시 측정하고
     * breaker를 보고한 뒤에만 reload할 수 있다. 그것이 도착하기 전까지 [ReaderUiState.currentPage]와
     * 그 이웃들은 여전히 *이전* style로 잘린 페이지 조각을 담고 있다. 리더는 그 조각들을 실제로
     * 측정되었던 활자 그대로 — [ReaderUiState.style] 자신이 아니라 [ReaderUiState.pageDrawStyle]로 —
     * 정확히 그 창 동안 계속 그리므로, 폰트·크기·줄 간격 변경이 아직 재측정되지 않은 페이지의 줄을
     * 자르거나 하단에 빈틈을 여는 일은 없다. [paginatedStyle]이 바로 그 고정된 활자를 재측정과
     * 원자적으로 만들어 주는 것이다: 이는 [paginated]와 같은 문장 그룹에서 바뀌므로, 새 조각을
     * 처음 보여주는 프레임이 곧 새 활자로 그것들을 그리기 시작하는 프레임과 같다.
     *
     * @param style 발행하고 영속화할 style.
     */
    private fun updateStyle(style: ReaderStyle) {
        val previousStyle = _uiState.value.style
        _uiState.update { state ->
            state.copy(style = styleWithPublisherFontKey(style, state.documentFormat))
        }
        saveReaderSettings {
            readerSettingsRepository.updateStyle(style)
            if (previousStyle.layoutKey() != style.layoutKey()) {
                pendingMoveNextStep = null
                reloadPages(style)
                currentDocumentId?.let { documentId ->
                    refreshPaginationCompleteness(
                        documentId,
                        style,
                        isImportComplete = documentRepository.isImportComplete(documentId)
                    )
                }
            }
        }
    }

    /**
     * [config]를 정규화해 발행하고 영속화하며, 자동 스크롤이 켜지는 순간 리더 chrome을 숨긴다 —
     * 그러지 않으면 chrome이 이제 스스로 움직이는 페이지와 주의를 다투며 화면에 남아 있을 것이다.
     *
     * @param config 발행할 자동 스크롤 설정; 호출자가 이미 무엇을 적용했든 상관없이 그 속도는
     *   [AutoScrollConfig.clampSpeed]를 통해 다시 clamp되므로, clamp되지 않은 사용자 입력으로
     *   조립된 값으로 호출해도 안전하다.
     */
    private fun updateAutoScroll(config: AutoScrollConfig) {
        val normalizedConfig = config.copy(speed = AutoScrollConfig.clampSpeed(config.speed))
        _uiState.update { state ->
            state.copy(
                autoScrollConfig = normalizedConfig,
                isControlsVisible = state.isControlsVisible && !normalizedConfig.enabled,
            )
        }
        saveReaderSettings { readerSettingsRepository.updateAutoScrollConfig(normalizedConfig) }
    }

    private fun observeReaderSettings(documentId: DocumentId) {
        readerSettingsJob?.cancel()
        readerSettingsJob = viewModelScope.launch {
            var isInitialEmission = true
            readerSettingsRepository.settings.collect { settings ->
                if (currentDocumentId != documentId) return@collect
                applyReaderSettings(
                    settings = settings,
                    preserveAutoScrollEnabled = isInitialEmission,
                )
                isInitialEmission = false
            }
        }
    }

    private fun applyReaderSettings(
        settings: ReaderSettings,
        preserveAutoScrollEnabled: Boolean = false,
    ) {
        val before = _uiState.value
        val style = styleWithPublisherFontKey(settings.style, before.documentFormat)
        if (before.style.layoutKey() != style.layoutKey()) pendingMoveNextStep = null
        val autoScrollConfig = if (preserveAutoScrollEnabled) {
            settings.autoScrollConfig.copy(enabled = before.autoScrollConfig.enabled)
        } else {
            settings.autoScrollConfig
        }
        _uiState.update { state ->
            state.copy(
                style = styleWithPublisherFontKey(settings.style, state.documentFormat),
                pageTurnMode = settings.pageTurnMode,
                pageAnimation = settings.pageAnimation,
                autoScrollConfig = autoScrollConfig,
                isControlsVisible = state.isControlsVisible && !autoScrollConfig.enabled,
            )
        }
    }

    /**
     * 옵션 시트를 막거나 재배열하지 않고 리더 설정 하나를 영속화한다. 저장소의 flow가 공유되는
     * 단일 진실 공급원이다; 실패한 낙관적 리더 업데이트는 그로부터 복원된다.
     */
    private fun saveReaderSettings(block: suspend () -> Unit) {
        viewModelScope.launch {
            val failure = suspendRunCatching { block() }.exceptionOrNull() ?: return@launch
            logger.w(failure) { "Failed to save reader settings" }
            suspendRunCatching { readerSettingsRepository.settings.first() }
                .onSuccess { settings ->
                    applyReaderSettings(settings, preserveAutoScrollEnabled = true)
                }
        }
    }

    /**
     * [pageIndex]가 가리키는 현재 페이지의 UI 준비된 뷰로, [pageUi]가 실제 뷰를 만들 수 없을 때
     * (범위를 벗어난 인덱스, 또는 아직 창이 없을 때) 빈 [ReaderPageUi]로 대체된다.
     *
     * @param pageIndex 현재 페이지를 읽어올 페이지 나누기.
     * @param documentUri 이미지를 렌더링하는 페이지를 위해 그대로 실려 가는, 문서 자신의 URI.
     * @param isPdfMode 문서가 visual 페이지 형식인지 여부로, 페이지 텍스트를 억제한다.
     * @return 현재 페이지의 UI 뷰, 실제 것이거나 빈 것.
     */
    private fun pageUiContext(
        pageIndex: PageIndex,
        documentUri: String?,
        isPdfMode: Boolean,
        paginatedOverride: PaginatedDocument = paginated,
    ): ReaderPageUiContext = ReaderPageUiContext(
        pageIndex = pageIndex,
        documentUri = documentUri,
        isPdfMode = isPdfMode,
        paginated = paginatedOverride,
        embeddedImages = embeddedImageCache.snapshot(),
        embeddedFontFiles = embeddedFontFiles,
        failedEmbeddedImageHrefs = failedEmbeddedImageHrefs,
        failedEmbeddedFontHrefs = failedEmbeddedFontHrefs,
    )

    /**
     * [currentPage] 주변에서 [pagerMountWindow]가 건드리는 블록들을 데운다 — 이 로직이 사는
     * 유일한 곳이어서, openDocument의 첫 발행, [moveToPage], [reloadPages] 모두가 각자 자기만의
     * 반경을 짐작하는 대신 정확히 자신이 곧 그것을 기준으로 만들 대상을 데운다. [paginated]는
     * 기본값으로 이 ViewModel 자신의 같은 이름 필드를 쓰지만, [reloadPages]는 자신의 로컬 쌍을
     * 명시적으로 넘긴다: 두 개의 reload가 동시에 실행될 수 있고(import 배치, viewport reload,
     * style 변경), 각각 자신의 페이지/섹션 쌍으로부터 발행하려 하므로, 데우는 작업은 데우는
     * 시점에 필드가 우연히 담고 있는 값이 아니라 자신의 발행이 읽게 될 바로 그 쌍을 건드려야
     * 한다. 여기서 복원된 페이지 목록을 읽는 것은 그것이 아직 준비되지 않은 섹션의 페이지를
     * 만들기는 하지만 캐시하지는 않더라도 안전하다(DocumentRepositoryImpl의 RestoredPageWindows)
     * — 준비되지 않은 페이지는 바로 다음 읽기에서 다시 만들어지는데, 이는 이 warm이 완료되는
     * 즉시 일어나는 바로 그 일이다.
     *
     * @param documentId 섹션을 데울 문서.
     * @param currentPage [pagerMountWindow]가 데우는 범위를 중심에 두는 페이지.
     * @param paginated 건드릴 섹션을 도출할 페이지/섹션 쌍; 기본값은 이 ViewModel 자신의
     *   필드지만, 위의 동시성 이유로 호출자의 로컬 쌍도 받는다.
     */
    private suspend fun warmMountWindow(
        documentId: DocumentId,
        currentPage: Int,
        paginated: PaginatedDocument = this.paginated,
    ) {
        val touchedSections = paginated.sectionIndexesFor(pagerMountWindow(currentPage))
        if (touchedSections.isNotEmpty()) documentRepository.warmSectionBlocks(
            documentId,
            touchedSections
        )
    }

    /**
     * [page]로 리더를 이동시키며, 현재 페이지 나누기의 범위 안으로 clamp하고, 어떤 suspend
     * 작업이 시작되기 전에 pager key가 바뀌도록 즉시 발행한다.
     *
     * 아래의 무엇이든 suspend하기 전에 — 동기적으로 — [anchorOffset]을 목표 페이지 자신의
     * 시작으로 갱신하고, 새 마주보는 페이지들을 곧바로 발행한 다음, 백그라운드에서 목표 페이지
     * 주변의 마운트된 창을 데운다. 그 warm이 반환되면, 가드된 재발행이 이 문서·위치·페이지가
     * 여전히 현재일 때만 실시간 현재 페이지를 새로고침하므로, 낡은 코루틴이 더 빠른 나중
     * 내비게이션 이후 리더를 뒤로 움직이는 일은 없다. 또한 [saveProgress]를 통해 새 읽기
     * 진행률을 저장하고, 새 위치 주변의 visual 페이지와 내장 이미지 preload를 시작한다.
     *
     * @param page 목표 페이지 인덱스; `0..total-1`로 coerce되며, 문서에 아직 페이지가 없으면
     *   아무 일도 하지 않는다.
     */
    fun moveToPage(page: Int) {
        pendingMoveNextStep = null
        moveToPageInternal(page)
    }

    private fun moveToPageInternal(page: Int) {
        val state = _uiState.value
        val total = state.pageIndex.total
        if (total <= 0) return
        val lastPage = (total - 1).coerceAtLeast(0)
        val nextPage = page.coerceIn(0, lastPage)
        anchorOffset = paginated.pageWindows.getOrNull(nextPage)?.textRange?.start
        val nextIndex = PageIndex(current = nextPage, total = total)
        _uiState.update {
            val facing = readerPageFacingUi(
                pageUiContext(
                    pageIndex = nextIndex,
                    documentUri = it.documentUri,
                    isPdfMode = it.isPdfMode,
                ),
            )
            it.copy(
                pageIndex = nextIndex,
                pageText = facing.current.text,
                style = styleWithPublisherFontKey(it.style, it.documentFormat),
                pageLayoutStyle = paginatedStyle,
                readProgressPercent = readProgressPercentFor(
                    pageIndex = nextIndex,
                    isVisualMode = it.isVisualMode,
                    currentPercent = it.readProgressPercent,
                ),
                previousPage = facing.previous,
                currentPage = facing.current,
                nextPage = facing.next,
                pageSlots = facing.slots,
                isCurrentPageSaved = isPageSaved(nextIndex, it.isVisualMode),
            )
        }
        val expectedLocation = currentLocation(nextIndex, state.isVisualMode)
        val documentId = currentDocumentId
        viewModelScope.launch {
            if (documentId != null && !state.isVisualMode) warmMountWindow(documentId, nextPage)
            if (currentDocumentId != documentId) return@launch
            val liveState = _uiState.value
            if (liveState.pageIndex.current != nextPage) return@launch
            if (currentLocation(
                    liveState.pageIndex,
                    liveState.isVisualMode
                ) != expectedLocation
            ) return@launch
            _uiState.update {
                val facing = readerPageFacingUi(
                    pageUiContext(
                        pageIndex = liveState.pageIndex,
                        documentUri = it.documentUri,
                        isPdfMode = it.isPdfMode,
                    ),
                )
                it.copy(
                    pageText = facing.current.text,
                    pageLayoutStyle = paginatedStyle,
                    previousPage = facing.previous,
                    currentPage = facing.current,
                    nextPage = facing.next,
                    pageSlots = facing.slots,
                    isCurrentPageSaved = isPageSaved(liveState.pageIndex, it.isVisualMode),
                )
            }
            saveProgress(liveState.pageIndex)
            loadVisualPagesAround(liveState.pageIndex.current)
            loadEmbeddedImagesAround(liveState.pageIndex.current)
            loadAllEmbeddedFonts()
        }
    }

    /**
     * live pageIndex를 기준으로 previousPage, nextPage, 그리고 모든 [pageSlots] 이웃을 다시
     * 알린다 — pageIndex, pageText, currentPage는 건드리지 않으며, 이들은 오직 실제
     * 내비게이션([moveToPage]) 또는 이미 그것들을 알린 첫 발행(openDocument 참고)을 통해서만
     * 바뀐다. 이전에 캡처된 pageIndex가 아니라 live 상태에서 위치 기준으로 읽는다 —
     * updatePageBreaker의 reload는 자신만의 코루틴에서 실행되며 이 함수가 실행되는 시점에 이미
     * 측정된 재 페이지 나누기를 발행해 두었을 수 있다; 여기서 더 이전의 로컬 값을 쓰면 조용히
     * 낡은 페이지 나누기를 되돌려놓게 될 것이다.
     */
    private fun republishSurroundingPages() {
        _uiState.update { state ->
            val livePageIndex = state.pageIndex
            val facing = readerPageFacingUi(
                pageUiContext(
                    pageIndex = livePageIndex,
                    documentUri = state.documentUri,
                    isPdfMode = state.isPdfMode,
                ),
            )
            state.copy(
                previousPage = facing.previous,
                nextPage = facing.next,
                pageSlots = facing.slots,
                style = styleWithPublisherFontKey(state.style, state.documentFormat),
                pageLayoutStyle = paginatedStyle,
            )
        }
    }

    /**
     * 현재 문서에 대해 [style]의 페이지들을 다시 측정하고 리더가 있는 페이지를 다시 발행한다 —
     * 폰트·줄 간격·활자체 변경 뒤, pane이 어떤 style에 대해 처음으로 실제 보고를 할 때, 또는
     * 문서를 더 키운 import/페이지 나누기 배치 뒤에 호출된다.
     *
     * [paginated] 안의 섹션 목록은 호출마다 저장소에서 다시 읽히는데, 점진적 EPUB import가
     * 백그라운드에서 섹션을 덧붙이고([DocumentRepository.importNextSections]) 완료 reload가
     * 그래도 최종 [ReaderDocument] 스냅샷으로 바꿔 끼우기 때문이다 — 이 재읽기가 없다면
     * [paginated]의 섹션을 읽는 다른 모든 독자([pageUi] 안의 챕터 제목 조회와
     * [PaginatedDocument.isSectionTail] 플래그)는 세션의 나머지 동안 [openDocument]가 open
     * 시점에 본 섹션 목록을 조용히 계속 기준으로 삼게 될 것이다. 위의
     * [DocumentRepository.getPageWindows] 호출은 캐시가 무효화되었을 때 이미 같은 문서의
     * [ReaderDocument]를 다시 로드해 두었으므로, 아래의 읽기는 두 번째 요청을 보내는 대신 바로
     * 그 메모리상 복사본으로부터 답한다.
     *
     * [paginated]는 여기서 같은 두 단계로, 그리고 예전에 분리되어 있던
     * `currentPageWindows`/`currentSections` 필드 쓰기가 있었던 것과 같은 순서로 쓰인다: 새
     * 페이지 목록이 먼저, 새 섹션 목록이 두 번째. 그 섹션 읽기 자체가 위에서 설명한 suspend
     * 호출이며, [paginated]를 읽는 다른 모든 독자 — [moveToPage], [saveProgress],
     * [currentLocation], [loadEmbeddedImagesAround], 그리고 [pageUi] 자신의 기본 인자 — 는 그
     * 읽기가 끝나기를 기다리는 대신 새 페이지 목록이 존재하는 순간 그것을 보아야 한다. 두 쓰기를
     * 하나의 대입으로 합치면 그 가시성이 정확히 섹션 읽기의 길이만큼 지연될 것이며, 이는
     * 단순화가 아니라 관찰 가능한 동작 변경이다. 그런 다음 [warmMountWindow]에는 [paginated]
     * 자신이 아니라 이 함수가 곧 그것을 기준으로 발행하려는 바로 그 페이지/섹션 쌍이 넘겨지는데,
     * 두 번째 reload(import 배치, viewport reload, 또 다른 style 변경)가 이미 같은 필드에 대해
     * 진행 중일 수 있기 때문이다.
     *
     * 복원된 페이지 목록이 만든 페이지 중 섹션의 블록이 아직 디코딩되지 않은 페이지는 스타일이
     * 없는 텍스트로 렌더링된다 — `DocumentRepositoryImpl.SectionBlocksCache.blocksFor`에서 온 빈
     * 블록 목록. 중간 import 배치는 이제 활성 prefix 캐시를 그대로 두지만, 완료 reload는 그래도
     * 나중 섹션이 아직 디코딩되지 않은 새 최종 스냅샷으로부터 다시 빌드하고 있을 수 있으므로,
     * `pageSlots()`가 곧 마운트하려는 바로 그 창을 [warmMountWindow]를 통해 아래의 발행 전에
     * 데우는 것이, 그 발행이 한 박자 늦게 바로잡는 대신 페이지 자신의 스타일링을 처음부터 싣고
     * 나가게 만든다.
     *
     * @param style 측정하고 페이지를 배치할 style.
     */
    private suspend fun reloadPages(style: ReaderStyle) {
        val documentId = currentDocumentId ?: return
        if (_uiState.value.isVisualMode) return

        if (pageBreaker != null && pageBreakerFor(style) == null) {
            logger.d { "reload skipped: measurement belongs to another type" }
            return
        }

        val pageWindows = documentRepository.getPageWindows(
            documentId = documentId,
            style = style,
            viewportSize = viewportSize,
            viewportDensity = paneDensity,
            pageBreaker = pageBreakerFor(style),
            anchorOffset = anchorOffset,
        )
        if (pageWindows.isEmpty()) return
        if (currentDocumentId != documentId || _uiState.value.style.layoutKey() != style.layoutKey()) return

        val currentPage =
            anchorOffset?.let { offset -> PaginatedDocument(pageWindows).pageOf(offset) }
                ?: _uiState.value.pageIndex.current.coerceIn(0, pageWindows.lastIndex)
        paginated = paginated.withPages(pageWindows)
        paginatedStyle = style
        val freshSections = documentRepository.getReaderDocument(documentId)?.sections
        if (currentDocumentId != documentId || _uiState.value.style.layoutKey() != style.layoutKey()) return
        if (freshSections != null) paginated = paginated.withSections(freshSections)
        val reloaded = PaginatedDocument(pageWindows, freshSections ?: paginated.sections)
        warmMountWindow(documentId, currentPage, reloaded)
        if (currentDocumentId != documentId || _uiState.value.style.layoutKey() != style.layoutKey()) return
        val pageIndex = PageIndex(current = currentPage, total = pageWindows.size)
        _uiState.update {
            val facing = readerPageFacingUi(
                pageUiContext(
                    pageIndex = pageIndex,
                    documentUri = it.documentUri,
                    isPdfMode = false,
                    paginatedOverride = reloaded,
                ),
            )
            it.copy(
                pageIndex = pageIndex,
                pageText = facing.current.text,
                style = styleWithPublisherFontKey(it.style, it.documentFormat),
                pageLayoutStyle = paginatedStyle,
                readProgressPercent = readProgressPercentFor(
                    pageIndex = pageIndex,
                    isVisualMode = false,
                    currentPercent = it.readProgressPercent,
                    paginatedDocument = reloaded,
                ),
                previousPage = facing.previous,
                currentPage = facing.current,
                nextPage = facing.next,
                pageSlots = facing.slots,
                isCurrentPageSaved = isPageSaved(pageIndex, false),
            )
        }
        loadEmbeddedImagesAround(currentPage)
        loadAllEmbeddedFonts()
        consumePendingMoveNextIfPossible()
    }

    private fun consumePendingMoveNextIfPossible() {
        val step = pendingMoveNextStep ?: return
        val pageIndex = _uiState.value.pageIndex
        val target = pageIndex.current + step
        if (target !in 0 until pageIndex.total) return
        pendingMoveNextStep = null
        moveToPageInternal(target)
    }

    /**
     * [pageIndex]를 재개할 읽기 위치로 영속화하며, 저장할 진짜 값이 아직 없으면 그렇게 하지
     * 않는다.
     *
     * 텍스트 문서에 아직 페이지 나누기가 없을 때는(`paginated.pageWindows`가 비어 있고 visual
     * 문서가 아닐 때) 저장을 거부한다: 페이지 나누기가 없는 텍스트 문서는 리더가 실제로 어디에
     * 있는지 말할 수 없으며, 그런데도 저장하면 [currentLocation]의 fallback — 글자 오프셋으로
     * 꾸며진 페이지 번호 — 을 리더가 실제로 멈춘 자리 위에 덮어써서, 다음 open 때 책의 첫
     * 페이지로 되돌려 보낼 것이다. 이 페이지-나누기-없는 상태 이전부터 이미 저장되어 있던
     * 진행률 행이 무엇이든, 그 fallback보다는 나은 답이므로, 이 함수는 그저 그것을 그대로 둔다.
     *
     * @param pageIndex 현재 읽기 위치로 저장할 페이지.
     */
    private fun saveProgress(pageIndex: PageIndex) {
        val documentId = currentDocumentId ?: return
        if (!_uiState.value.isVisualMode && paginated.pageWindows.isEmpty()) return
        saveProgressJob?.cancel()
        saveProgressJob = viewModelScope.launch {
            readerRepository.saveProgress(
                ReadingProgress(
                    documentId = documentId,
                    location = currentLocation(pageIndex),
                    pageIndex = pageIndex,
                    updatedAtEpochMillis = 0L,
                ),
            )
        }
    }

    private fun readProgressPercentFor(
        pageIndex: PageIndex,
        isVisualMode: Boolean,
        currentPercent: Int,
        paginatedDocument: PaginatedDocument = paginated,
    ): Int =
        if (isVisualMode) {
            readerVisualReadProgressPercent(pageIndex)
        } else {
            readerReadProgressPercent(
                location = anchorOffset?.let(ReaderLocation::TextOffset)
                    ?: paginatedDocument.locationAt(pageIndex.current),
                characterCount = finalCharacterCount,
                currentPercent = currentPercent,
            )
        }

    /**
     * [savedPlaces]를 [documentId]에 대한 [bookmarkRepository]의 실시간 북마크 목록에 구독시키며,
     * [openDocument]가 문서를 열 때마다 구독을 다시 시작한다(그 동안 [savedPlaces]도 비운다).
     * [openDocument] 자신으로부터 open마다 한 번씩 호출된다.
     *
     * @param documentId 저장된 위치를 관찰할 문서.
     */
    private fun observeSavedPlaces(documentId: DocumentId) {
        savedPlacesJob?.cancel()
        savedPlaces = emptyList()
        savedPlacesJob = viewModelScope.launch {
            bookmarkRepository.observeBookmarks(documentId).collect { bookmarks ->
                savedPlaces = bookmarks
                updateSavedPlaceState()
            }
        }
    }

    /** 지금 [_uiState]에 live한 페이지가 무엇이든 그에 대해 [ReaderUiState.isCurrentPageSaved]를 다시 도출한다. */
    private fun updateSavedPlaceState() {
        _uiState.update { state ->
            state.copy(isCurrentPageSaved = isPageSaved(state.pageIndex, state.isVisualMode))
        }
    }

    /**
     * [pageIndex]가 이미 저장된 위치를 가지고 있는지 여부로, [currentLocation]을 거쳐 해석되어
     * 저장된 글자 오프셋과 저장된 페이지 모두 "이 페이지의 위치"라는 올바른 개념을 기준으로
     * 비교되게 한다.
     *
     * @param pageIndex 확인할 페이지.
     * @param isVisualMode 문서가 visual 페이지 형식인지 여부로, [currentLocation]에 그대로
     *   전달된다.
     * @return [savedPlaces]가 이미 이 페이지의 위치를 담고 있으면 true.
     */
    private fun isPageSaved(pageIndex: PageIndex, isVisualMode: Boolean): Boolean =
        savedPlaces.any { it.location == currentLocation(pageIndex, isVisualMode) }

    /**
     * [pageIndex]가 현재 보여주는 읽기 위치로, 진행률을 저장하고 그 페이지에 저장된 위치가
     * 있는지 확인하는 데 쓰인다.
     *
     * 이 중 페이지 나누기 쪽 절반은 [PaginatedDocument.locationAt]을 통해 해석되며, 이는 페이지
     * 자신의 창으로부터 답하고 [paginated]가 아직 그 창을 가지고 있지 않은 페이지에 대해서는
     * null이다; fallback — [ReaderLocation.TextOffset]으로 꾸며진 페이지 번호 — 은
     * [PaginatedDocument]로 옮겨지는 대신 여기 남아 있는데, 이는 도메인 타입이 답해야 할 페이지
     * 나누기 사실이 아니라 "아직 알려진 실제 위치가 없음"을 나타내는 UI 수준의 자리표시자이기
     * 때문이다.
     *
     * @param pageIndex 위치를 해석할 페이지.
     * @param isVisualMode 문서가 visual 페이지 형식인지 여부로, 이 경우 위치는 항상 글자
     *   오프셋이 아니라 [ReaderLocation.PdfPage]다.
     */
    private fun currentLocation(
        pageIndex: PageIndex,
        isVisualMode: Boolean = _uiState.value.isVisualMode,
    ): ReaderLocation {
        if (isVisualMode) {
            return ReaderLocation.PdfPage(pageIndex.current)
        }

        return paginated.locationAt(pageIndex.current)
            ?: ReaderLocation.TextOffset(pageIndex.current.toLong())
    }

    /**
     * [pagerMountWindow]가 [centerPage] 주변에 필요로 하는, 아직 [visualPageCache]나
     * [failedVisualPages]에 없는 CBZ 페이지 이미지들을 가져온 다음, 병합된 캐시를 발행한다.
     * [DocumentFormat.CBZ]가 아닌 문서, 또는 아직 페이지 수를 모르는 동안은 아무 일도 하지
     * 않는다.
     *
     * 성공하면, [visualPageCache]는 요청된 마운트 창의 모든 페이지를 보호하면서 더 오래된
     * 바이트를 24 MiB 예산 아래로 잘라낸다; 실패하면, 빠진 페이지들은 끝없이 다시 요청되지
     * 않도록 [failedVisualPages]에 기록된다.
     *
     * @param centerPage [pagerMountWindow]가 fetch 창을 중심에 두는 페이지.
     */
    private fun loadVisualPagesAround(centerPage: Int) {
        val documentId = currentDocumentId ?: return
        val state = _uiState.value
        if (state.documentFormat != DocumentFormat.CBZ || state.pageIndex.total <= 0) return
        val requestedPages = pagerMountWindow(centerPage)
            .filterTo(linkedSetOf()) { it in 0 until state.pageIndex.total }
        val cachedPages = visualPageCache.snapshot()
        val missingPages = requestedPages - cachedPages.keys - failedVisualPages
        if (missingPages.isEmpty()) {
            _uiState.update {
                it.copy(
                    visualPageImages = cachedPages.filterKeys(requestedPages::contains)
                        .toImmutableMap(),
                    failedVisualPages = failedVisualPages.toImmutableSet(),
                )
            }
            return
        }

        visualPageLoadJob?.cancel()
        visualPageLoadJob = viewModelScope.launch {
            try {
                val loadedPages = documentRepository.getVisualPageImages(documentId, missingPages)
                if (currentDocumentId != documentId) return@launch
                loadedPages.forEach { (page, bytes) ->
                    visualPageCache.put(page, bytes, protectedKeys = requestedPages)
                }
                failedVisualPages += missingPages - loadedPages.keys
                val visualSnapshot = visualPageCache.snapshot()
                _uiState.update {
                    it.copy(
                        visualPageImages = visualSnapshot.filterKeys(requestedPages::contains)
                            .toImmutableMap(),
                        failedVisualPages = failedVisualPages.toImmutableSet(),
                    )
                }
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (_: Throwable) {
                if (currentDocumentId == documentId) {
                    failedVisualPages += missingPages
                    val visualSnapshot = visualPageCache.snapshot()
                    _uiState.update {
                        it.copy(
                            visualPageImages = visualSnapshot.filterKeys(requestedPages::contains)
                                .toImmutableMap(),
                            failedVisualPages = failedVisualPages.toImmutableSet(),
                        )
                    }
                }
            }
        }
    }

    /**
     * [pagerMountWindow]가 [centerPage] 주변에 필요로 하는, 아직 [embeddedImageCache]나
     * [failedEmbeddedImageHrefs]에 없는 EPUB 내장 이미지들을 가져온 다음, 그것들이 필요했던
     * 페이지를 [refreshEpubPages]를 통해 다시 발행한다. [DocumentFormat.EPUB]가 아닌 문서, 또는
     * 아직 페이지 수를 모르는 동안은 아무 일도 하지 않는다 — 다만 [refreshEpubPages]는 빠진
     * 것이 없어도 여전히 실행되므로, 모든 href가 이미 캐시되어 있음을 발견한 호출도 페이지를
     * 다시 알린다.
     *
     * 이 함수가 href를 읽어오는 창은 `pageSlots()`가 실제로 마운트하는 창과 일치하므로
     * ([pagerMountWindow] 참고), 리더가 미리보기로 스와이프할 수 있는 모든 슬롯이 바로
     * 이웃뿐 아니라 이미 자신의 이미지를 요청받은 상태다. 성공하면, [embeddedImageCache]는
     * 현재 창이 여전히 필요로 하는 모든 href를(예를 들어, 나중에 다시 방문하는 표지) 보존하고,
     * 캐시가 16 MiB 예산을 넘으면 나머지 중 가장 오래된 것만 축출한다 — 단순한 삽입 순서 LRU는
     * 여전히 필요한 이미지(표지는 항상 처음 로드된 것이었다)를 오직 그 이후 더 새로운 이미지가
     * 캐시되었다는 이유만으로 축출해버렸다. 아카이브 미스는 끝없이 다시 요청되지 않도록
     * [failedEmbeddedImageHrefs]에 기록되지만, 일시적인 fetch 실패는 다음 preload에서 재시도할
     * 수 있도록 href를 그대로 남겨 둔다.
     *
     * @param centerPage [pagerMountWindow]가 fetch 창을 중심에 두는 페이지.
     */
    private fun loadEmbeddedImagesAround(centerPage: Int) {
        val documentId = currentDocumentId ?: return
        val state = _uiState.value
        if (state.documentFormat != DocumentFormat.EPUB || state.pageIndex.total <= 0) return
        val relevantHrefs = paginated.imageHrefsIn(pagerMountWindow(centerPage))
        val missingHrefs =
            relevantHrefs - embeddedImageCache.snapshot().keys - failedEmbeddedImageHrefs
        if (missingHrefs.isEmpty()) {
            refreshEpubPages()
            return
        }

        embeddedImageLoadJob?.cancel()
        embeddedImageLoadJob = viewModelScope.launch {
            try {
                val loadedImages = documentRepository.getEmbeddedImages(documentId, missingHrefs)
                if (currentDocumentId != documentId) return@launch
                loadedImages.forEach { (href, bytes) ->
                    embeddedImageCache.put(href, bytes, protectedKeys = relevantHrefs)
                }
                failedEmbeddedImageHrefs += missingHrefs - loadedImages.keys
                refreshEpubPages()
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (_: Throwable) {
                if (currentDocumentId == documentId) refreshEpubPages()
            }
        }
    }

    /**
     * 문서 전체가 참조하는 모든 내장 폰트를 한 번 해석한 다음, 활자체와 페이지 나누기가 완전한
     * map으로부터 다시 빌드되도록 EPUB 페이지 상태를 다시 발행한다.
     *
     * 의도적으로 문서 전체를 대상으로 한다: 폰트 집합이 layout key에 반영되는데, 창 단위로
     * 발견하면 리더가 본 적 없는 폰트를 이름 붙인 섹션에 도달할 때마다 그 key가 바뀌었다 —
     * 변경마다 책 전체를 다시 측정했고, 리더는 이를 정착되는 동안 페이지가 깜박이고 다시
     * 스타일링되고 잘리는 것으로 경험했다. 전체 집합을 한 번에 해석하면 key는 문서당 최대 한
     * 번만 바뀌며, [refreshEpubPages]는 실제로 그것이 바뀌었을 때만 호출할 가치가 있다.
     */
    private fun loadAllEmbeddedFonts() {
        val documentId = currentDocumentId ?: return
        val state = _uiState.value
        if (state.documentFormat != DocumentFormat.EPUB || state.pageIndex.total <= 0) return
        if (allEmbeddedFontsResolved) return

        embeddedFontLoadJob?.cancel()
        embeddedFontLoadJob = viewModelScope.launch {
            var missingHrefs = emptySet<String>()
            try {
                // 폰트 집합은 책 전체가 파싱된 뒤에만 최종이라고 부를 수 있다: 점진적인 (재)import
                // 도중의 스캔은 지금까지 저장된 섹션만 — 또는 복구 도중이면 아무것도 — 보지
                // 못하며, 그 빈 답을 "해석됨"이라고 부르면 책이 영원히 폰트 없는 채로 굳어버렸다.
                val isImportComplete = documentRepository.isImportComplete(documentId)
                val referencedHrefs = documentRepository.getReferencedEmbeddedFontHrefs(documentId)
                if (currentDocumentId != documentId) return@launch
                missingHrefs = referencedHrefs - embeddedFontFiles.keys - failedEmbeddedFontHrefs
                if (missingHrefs.isNotEmpty()) {
                    val loadedFonts = documentRepository.getEmbeddedFontFiles(documentId, missingHrefs)
                    if (currentDocumentId != documentId) return@launch
                    embeddedFontFiles = embeddedFontFiles + loadedFonts
                    failedEmbeddedFontHrefs += missingHrefs - loadedFonts.keys
                }
                allEmbeddedFontsResolved = isImportComplete
                embeddedFontsSettled = true
                // 폰트 key가 바뀌지 않았을 때도(내장 폰트가 전혀 없는 책) 다시 발행된다: 측정
                // 게이트는 areEmbeddedFontsResolved를 기다리며, 이는 오직 발행과 함께만 전달된다.
                refreshEpubPages()
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (_: Throwable) {
                if (currentDocumentId == documentId) {
                    // 보류 상태로 남기는 대신 실패로 표시한다: 보류 중인 참조 폰트는 측정된
                    // 페이지 나누기를 영원히 막는데(canMeasureEpubPage 참고), 이는 이번 open에
                    // 리더 자신의 폰트로 되돌아가는 것보다 더 나쁘다. resolved 플래그는 내려간
                    // 채로 남아, 다음 트리거가 (이제는 저렴해진) 스캔을 다시 실행해 여전히
                    // 제대로 결론 낼 수 있게 한다.
                    failedEmbeddedFontHrefs += missingHrefs
                    refreshEpubPages()
                }
            }
        }
    }

    /**
     * live 페이지 나누기를 기준으로 현재 페이지와 그 이웃들을 다시 알린다 —
     * [republishSurroundingPages]가 쓰는 것과 같은 이웃-만 모양에, [ReaderUiState.currentPage]
     * 자신을 더한 것 — [loadEmbeddedImagesAround]나 [loadAllEmbeddedFonts]가 내장 이미지/폰트
     * 캐시나 실패 집합이 담고 있는 것을 바꾼 뒤 호출되므로, 이미지나 폰트 로딩이 방금
     * 끝났거나(또는 실패한) 페이지가 그 결과와 함께 다시 렌더링된다.
     */
    private fun refreshEpubPages() {
        _uiState.update {
            val pageIndex = it.pageIndex
            val facing = readerPageFacingUi(
                pageUiContext(
                    pageIndex = pageIndex,
                    documentUri = it.documentUri,
                    isPdfMode = it.isPdfMode,
                ),
            )
            it.copy(
                previousPage = facing.previous,
                currentPage = facing.current,
                nextPage = facing.next,
                pageSlots = facing.slots,
                style = styleWithPublisherFontKey(it.style, it.documentFormat),
                pageLayoutStyle = paginatedStyle,
                // 찾아지면 그대로 유지: 여전히 디코딩 중인 페이지는 컨테이너를 전혀 가지고 있지
                // 않으며, 그것을 0으로 떨어뜨리면 pane의 패딩이 뒤집혀 아무 의미 없이 책을 다시
                // 측정하게 될 것이다.
                publisherPageMargins = it.publisherPageMargins.takeUnless(ReaderPageMarginsEm::isZero)
                    ?: epubPageContainerMarginsEm(facing.current),
                areEmbeddedFontsResolved = it.documentFormat != DocumentFormat.EPUB || embeddedFontsSettled,
                embeddedFontFiles = embeddedFontFiles.toImmutableMap(),
                failedEmbeddedFontHrefs = failedEmbeddedFontHrefs.toImmutableSet(),
            )
        }
    }

    private fun styleWithPublisherFontKey(
        style: ReaderStyle,
        documentFormat: DocumentFormat
    ): ReaderStyle =
        style.copy(publisherFontKey = publisherFontKey(documentFormat))

    private fun publisherFontKey(documentFormat: DocumentFormat): String? {
        if (documentFormat != DocumentFormat.EPUB) return null
        val loaded = embeddedFontFiles.keys.sorted().map { href -> "$href=loaded" }
        val failed = failedEmbeddedFontHrefs.sorted().map { href -> "$href=failed" }
        return (loaded + failed).takeIf(List<String>::isNotEmpty)?.joinToString(separator = "|")
    }
}

/**
 * [loadOpenState]가 어떤 pane도 아직 실제 크기를 보고하기 전에 기준으로 페이지를 나누는
 * viewport.
 *
 * 리더는 자신의 viewport를 px가 아니라 sp로 보고하므로, 이 placeholder도 sp 기준으로 폰
 * 크기다 — 여기에 px 크기의 값을 쓰면 대략 9배 더 거칠게 페이지를 나눌 것인데, sp 값은 실제
 * 기기에서 그에 대응하는 픽셀 치수보다 수치상 훨씬 작기 때문이다.
 */
private val DefaultViewportSize = ViewportSize(widthPx = 320, heightPx = 560)

/**
 * [loadVisualPagesAround]가 현재 마운트 창을 축출로부터 보호하면서 [visualPageCache]에
 * 유지하는, 디코딩된 CBZ 페이지 이미지의 바이트 예산.
 */
private const val VisualPageCacheBudgetBytes = 24 * 1024 * 1024

/**
 * [loadEmbeddedImagesAround]가 현재 마운트 창이 여전히 필요로 하는 href를 보호하면서
 * [embeddedImageCache]에 유지하는, 디코딩된 EPUB 내장 이미지의 바이트 예산.
 */
private const val EmbeddedImageCacheBudgetBytes = 16 * 1024 * 1024

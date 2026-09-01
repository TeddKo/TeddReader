package com.tedd.teddreader.feature.reader.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.isImagePageFormat
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import com.tedd.teddreader.core.common.model.withLayoutFieldsOf
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * 렌더링된 페이지 한 장 분량의 콘텐츠와 렌더링 힌트로, [ReaderUiState]가 이를 페이지 서피스
 * (`ReaderPageSurface`, [com.tedd.teddreader.feature.reader.impl.EpubPageSurface], `ImagePageSurface`,
 * `PdfPageSurface`)에 넘겨준다.
 *
 * 페이지 슬롯이 존재하는 이유는, 리더가 현재 페이지와 그 바로 이웃(EPUB이라면 페이지 나누기가 지금까지
 * 만들어낸 배치 전체)을 보유하면서도 서피스들이 페이지 나누기, 저장, import 상태에 대해 아무것도 알
 * 필요가 없도록 하기 위해서다 — 서피스는 언제나 이 중 하나만 읽는다.
 *
 * @property page 이 콘텐츠가 페이지로 나뉜 기준이 된, 0-기반 인덱스. 호출자는 목록 위치가 아니라 이
 *   값으로 조회를 키잉한다(`ReaderUiState.pageSlot`), 슬롯이 항상 연속적이지는 않기 때문이다.
 * @property text 순수 텍스트로 렌더링되는 형식(TXT, 그리고 더 풍부한 블록 렌더링이 대체하기 전의
 *   EPUB)의 순수 텍스트 페이지 콘텐츠; [blocks]가 실제 콘텐츠를 담고 있으면 비어 있다.
 * @property isPdf 이 슬롯이 텍스트가 아니라 렌더링된 PDF 페이지 이미지일 때 true.
 * @property documentUri 이 특정 페이지가 근거로 삼는 소스 URI. 페이지가 현재 열린 문서와 다른 문서를
 *   가리킬 수 있을 때 쓰인다(PDF 페이지 렌더링이 사용).
 * @property textRange 이 페이지의 [text]가 대응하는, 문서 전체 텍스트 안의 반열린 오프셋 범위. 페이지에
 *   대해 읽기 위치와 location을 해석하는 데 쓰인다.
 * @property blocks 이 페이지가 나뉜 구조화된 EPUB 콘텐츠(단락, 이미지, 구분선). 순수 텍스트 형식에서는
 *   비어 있다.
 * @property embeddedImages 이 책에 내장된 EPUB 이미지들로, href를 키로 하며 디코딩되어 그릴 준비가
 *   되어 있다. 이 페이지가 실제로 참조하는 이미지만 존재가 보장된다.
 * @property embeddedFontFiles 이 책에 내장된 EPUB 폰트 파일들로, href를 키로 하며 재사용 가능한 로컬
 *   파일 경로로 해석되어 있다. 이 페이지가 실제로 참조하는 폰트만 존재가 보장된다.
 * @property failedEmbeddedImageHrefs [blocks] 중 이미지 바이트를 디코딩할 수 없었던 href들로, 서피스가
 *   이미 실패한 요청을 재시도하는 대신 라벨을 보여줄 수 있게 한다.
 * @property failedEmbeddedFontHrefs [blocks]나 span CSS 중 폰트 파일을 해석할 수 없었던 href들로,
 *   렌더러가 그것을 더 이상 기다리지 않을 수 있게 한다.
 * @property chapterTitle 리더 chrome이 페이지 본문 바깥에 표시해야 할 때를 위한, 이 페이지가 속한
 *   섹션의 표제.
 * @property chapterPageIndex 이 페이지가 속한 챕터 안에서의 0-기반 위치와 전체 페이지 수. 표지, 제목
 *   없는 페이지, 챕터 경계를 알 수 없는 페이지에서는 null.
 * @property isSectionTail 이 페이지가 자신의 EPUB 섹션의 마지막 페이지일 때 true — `EpubPageSurface`가
 *   짧은 페이지를 중앙에 놓는 기준으로 삼는, 채우기 위해 렌더링된 시트의 양 대신 쓰는 정직하고 구성상
 *   보장되는 신호다(활자 설정이 실제로 측정되기 전, 추정된 페이지에서 렌더링된-높이 신호가 왜 깨졌는지는
 *   그 composable 자체의 문서를 참고).
 */
@Immutable
data class ReaderPageUi(
    val page: Int = 0,
    val text: String = "",
    val isPdf: Boolean = false,
    val documentUri: String? = null,
    val textRange: TextRange? = null,
    val blocks: ImmutableList<ReaderBlock> = persistentListOf(),
    val embeddedImages: ImmutableMap<String, ByteArray> = persistentMapOf(),
    val embeddedFontFiles: ImmutableMap<String, String> = persistentMapOf(),
    val failedEmbeddedImageHrefs: ImmutableSet<String> = persistentSetOf(),
    val failedEmbeddedFontHrefs: ImmutableSet<String> = persistentSetOf(),
    val chapterTitle: String? = null,
    val chapterPageIndex: PageIndex? = null,
    val isSectionTail: Boolean = false,
)

/**
 * 리더 화면의 전체 상태로,
 * [ReaderViewModel][com.tedd.teddreader.feature.reader.impl.ReaderViewModel]이 발행하고
 * [ReaderScreen]이 렌더링한다. `ReaderScreen`은 이 상태와 view model이 노출하는 콜백에 대한 순수한
 * 전달자다 — 화면에 표시되거나 제공되는 모든 값과 상호작용은 여기 있는 어떤 property로 거슬러
 * 올라간다.
 *
 * @property documentTitle 상단 바와 상태 표시줄에 표시되는 제목.
 * @property documentUri 열린 문서의 소스 URI. 페이지 슬롯을 거치지 않고 바이트를 직접 다시 읽어야 하는
 *   페이지 서피스(PDF, 이미지)가 사용한다.
 * @property documentFormat 열린 문서의 형식으로, 어떤 페이지 서피스가 [currentPage]/[pageSlots]를
 *   렌더링할지, 어떤 visual 모드([isVisualMode], [isImageMode])가 적용될지를 결정한다.
 * @property pageText 현재 페이지에 대한 순수 텍스트 대체값으로, 페이지 슬롯이 존재하기 전이나 슬롯을
 *   채우지 않는 형식에 쓰인다.
 * @property pageIndex 현재 페이지 위치와 지금까지 알려진 페이지 수. import나 측정이 여전히 진행 중인
 *   동안 "지금까지 알려진"이 무엇을 뜻하는지는 [isPaginationComplete]를 참고한다 — `total`은 문서가
 *   열려 있는 동안 오직 늘어나기만 하며 절대 줄어들지 않는다.
 * @property previousPage [currentPage] 바로 앞의 페이지 슬롯. 인접 페이지 전환이 새 로드를 기다리지
 *   않도록 유지된다.
 * @property currentPage 실제로 화면에 있는 페이지 슬롯.
 * @property nextPage [currentPage] 바로 다음의 페이지 슬롯. [previousPage]와 같은 이유로 유지된다.
 * @property pageSlots view model이 현재/이전/다음 세 개를 넘어 추가로 보유 중인 전체 페이지 슬롯
 *   집합 — pager가 더 먼 곳을 봐야 할 때 쓰인다.
 * @property style 페이지가 렌더링되는 기준이 되는 활성 활자·테마 style — 리더가 방금 선택한 것이며,
 *   모든 선택기·미리보기·확정값 표시가 보여주는 것이다. 페이지 서피스가 실제로 무엇으로 그리는지는
 *   항상 같은 값은 아니므로 [pageDrawStyle]을 참고한다.
 * @property pageLayoutStyle [currentPage], [previousPage], [nextPage], [pageSlots]가 실제로 그 아래
 *   페이지가 나뉜 style, 또는 이들이 이미 [style]이 말하는 그대로의 style 아래 나뉘었다면 null — 문서가
 *   열리는 순간부터 다른 발행이 [style]을 바꿔 그것이 촉발하는 재 페이지 나누기가 일어나기 전까지는 이
 *   흔한 경우에 해당한다.
 *   [ReaderViewModel][com.tedd.teddreader.feature.reader.impl.ReaderViewModel]이 자신의 `paginated`
 *   필드와 발맞춰 기록하며, 이 클래스 밖에서 직접 읽는 일은 없다 — 렌더 경로의 모든 소비자가 대신
 *   읽어야 할 값은 [pageDrawStyle]이다.
 * @property isControlsVisible 상단 바, 하단 바, 상태 표시줄이 보이는지 여부; 페이지 영역을 탭하면
 *   토글된다.
 * @property isLoading 문서가 아직 열리는 중인 동안 true; 이 값이 true인 동안 `ReaderScreen`은 전체
 *   화면 로딩 인디케이터를 보여주고 그 외에는 아무것도 렌더링하지 않는다.
 * @property errorMessage 문서 열기가 실패했을 때 non-null; 페이지 콘텐츠 대신 표시된다.
 * @property activeSheet 현재 열려 있는 옵션 시트(view/font/theme/page-turn 등), 아무 시트도 표시되지
 *   않으면 null.
 * @property pageTurnMode 페이지가 가로축과 세로축 중 어느 쪽으로 넘어가는지.
 * @property pageAnimation 현재 선택된 페이지 넘김 애니메이션.
 * @property autoScrollConfig 자동 스크롤 활성화 여부, 모드, 속도.
 * @property outlineHeading 열린 문서의 목차 표제 텍스트, 있다면.
 * @property outlineItems 열린 문서의 목차 항목들.
 * @property brightnessOverlayAlpha 디스플레이 자체의 최소 밝기 아래로 어둡게 하는 것을 흉내 내기 위해
 *   화면 전체에 그려지는 검은 오버레이의 alpha.
 * @property pdfZoom 현재 PDF/visual 확대 배율.
 * @property pdfRotationDegrees 현재 PDF 페이지 회전.
 * @property keepScreenOn 읽는 동안 화면이 잠들지 않도록 막아야 하는지 여부.
 * @property fullscreen 리더가 시스템 바를 완전히 숨기는지 여부.
 * @property showProgress 하단 바가 페이지 위치 슬라이더와 라벨을 보여주는지 여부.
 * @property isPdfMode 열린 문서가 PDF일 때 true. 텍스트/EPUB 문서에 비해 페이지가 측정되고 렌더링되는
 *   방식이 달라진다.
 * @property visualPageImages visual 형식(CBZ, 이미지)에 대해 디코딩된 페이지 이미지들로, 페이지
 *   인덱스를 키로 한다.
 * @property failedVisualPages 이미지를 디코딩할 수 없었던 visual 페이지 인덱스들.
 * @property embeddedFontFiles href를 키로 하는, 해석된 EPUB 폰트 파일 경로들. 화면 범위로 발행되어,
 *   활자체를 다시 만들거나 page breaker를 다시 만드는 호출자가 폰트 바이트를 계속 붙들고 있지 않아도
 *   되게 한다.
 * @property failedEmbeddedFontHrefs 이미 해석에 실패한 EPUB 폰트 href들로, UI가 더 이상 이들을 기다리지
 *   않을 수 있게 한다.
 * @property isFavorite 열린 문서가 라이브러리에 즐겨찾기로 표시되어 있는지 여부.
 * @property isCurrentPageSaved 현재 페이지/위치가 자동으로 저장되는 읽기 위치와는 별개로, 명시적으로
 *   위치로 저장되었는지 여부.
 * @property isPaginationComplete 점진적으로 import되는 EPUB이 백그라운드에서 여전히 파싱·측정되고
 *   있는 동안(
 *   [ReaderViewModel.continueImportIfIncomplete][com.tedd.teddreader.feature.reader.impl.ReaderViewModel]
 *   참고), 또는 현재 style/줄 간격/활자체가 이 책에 대해 한 번도 측정된 적이 없어 여전히 섹션 단위로
 *   레이아웃되고 있는 동안(`continuePaginationIfIncomplete` 참고) false. 그러므로 이 값이 false인
 *   동안 [pageIndex]의 `total`은 책 전체가 아니라 "지금까지 알려진 페이지 수"다 — UI는 조용히 계속
 *   늘어나는 합계를 보여주는 대신 이를 밝혀야 한다(페이지 수 뒤의 "+", 비활성화되었지만 보이는
 *   슬라이더). 둘 다 필요 없는 문서를 포함해, 둘 다 끝나면 true가 된다.
 */
@Immutable
data class ReaderUiState(
    val documentTitle: String = "Reader",
    val documentUri: String? = null,
    val documentFormat: DocumentFormat = DocumentFormat.UNKNOWN,
    val pageText: String = "",
    val pageIndex: PageIndex = PageIndex(current = 0, total = 0),
    val readProgressPercent: Int = 0,
    val previousPage: ReaderPageUi? = null,
    val currentPage: ReaderPageUi = ReaderPageUi(),
    val nextPage: ReaderPageUi? = null,
    val pageSlots: ImmutableList<ReaderPageUi> = persistentListOf(),
    val style: ReaderStyle = ReaderStyle(),
    val pageLayoutStyle: ReaderStyle? = null,
    /**
     * 열린 책 자체의 `html`/`body` 스타일링이 요구하는 페이지 여백으로, view model이 — 가능하면 첫
     * 프레임부터 — 한 번만 해석해 두어서 텍스트 영역이 이미 측정된 뒤에는 pane의 padding이 절대
     * 뒤바뀌지 않게 한다; 뒤늦은 변경은 열 때 책 전체를 재 페이지 나누기 하게 만든다.
     */
    val publisherPageMargins: ReaderPageMarginsEm = ReaderPageMarginsEm.Zero,
    /**
     * 이 문서가 어디서든 참조하는 모든 내장 폰트가 해석되었거나 실패했는지 여부 — 측정 게이트가
     * 필요로 하는, 문서 전체에 걸친 사실이다. 페이지 측정은 책 전체에 걸친 행위이므로 책 전체의 폰트를
     * 기다려야 한다: 현재 페이지의 폰트만으로 게이트를 걸면 폰트가 없는 표지 페이지가 게이트를 열어버려
     * 책 전체가 대체 활자로 측정되고, 실제 활자가 더 길게 뻗는 모든 페이지가 잘려나갔다. 내장 폰트가
     * 필요 없는 형식과 활자 오버라이드에서는 처음부터 true다.
     */
    val areEmbeddedFontsResolved: Boolean = false,
    val isControlsVisible: Boolean = true,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val activeSheet: ReaderOptionSheet? = null,
    val pageTurnMode: PageTurnMode = PageTurnMode.HORIZONTAL,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val autoScrollConfig: AutoScrollConfig = AutoScrollConfig(),
    val outlineHeading: String? = null,
    val outlineItems: ImmutableList<ReaderOutlineItem> = persistentListOf(),
    val brightnessOverlayAlpha: Float = 0f,
    val pdfZoom: Float = 1f,
    val pdfRotationDegrees: Float = 0f,
    val keepScreenOn: Boolean = false,
    val fullscreen: Boolean = false,
    val showProgress: Boolean = true,
    val isPdfMode: Boolean = false,
    val visualPageImages: ImmutableMap<Int, ByteArray> = persistentMapOf(),
    val failedVisualPages: ImmutableSet<Int> = persistentSetOf(),
    val embeddedFontFiles: ImmutableMap<String, String> = persistentMapOf(),
    val failedEmbeddedFontHrefs: ImmutableSet<String> = persistentSetOf(),
    val isFavorite: Boolean = false,
    val isCurrentPageSaved: Boolean = false,
    val isPaginationComplete: Boolean = true,
) {
    /** PDF나, 텍스트가 아니라 이미지 전체로 페이지가 나뉘는 다른 모든 문서 형식이면 true. */
    val isVisualMode: Boolean get() = isPdfMode || documentFormat.isVisualPageFormat()

    /** 페이지마다 디코딩된 이미지 하나로 나뉘는 문서 형식(CBZ)이면 true. */
    val isImageMode: Boolean get() = documentFormat.isImagePageFormat()

    /**
     * [currentPage]/[previousPage]/[nextPage]/[pageSlots]를 실제로 그릴 때 쓸 style:
     * [pageLayoutStyle]의 활자 설정 — 활자 패밀리, 크기, 줄 간격, 굵기 — 을 [style]의 색상·테마·배경
     * 이미지 위에 얹은 것, 또는 [pageLayoutStyle]이 null이고 둘이 이미 일치하면 [style] 자체.
     *
     * 바로 이것이 레이아웃에 영향을 주는 설정 변경이 이미 화면에 있는 페이지를 잘리거나 빈틈이 생기게
     * 하는 것을 막아준다. 활자, 크기, 줄 간격, 굵기를 바꾸면 새 [style]이 동기적으로 이 상태에
     * 발행되지만, [currentPage]와 그 이웃이 보유한 페이지 조각은 그 변경 *이전에* 적용 중이던 style로
     * 잘려 있다 — pane은 비동기적으로만 다시 측정하고 결과를 보고하며, 그때까지는 보여줄 새 조각이
     * 없다. 두 페이지 그리기 호출 지점에서 [style]을 직접 읽으면 그 오래된 조각을 새 활자 설정으로
     * 그리게 된다: 어느 에뮬레이터 실행에서는 2641px 페이지에 대해 최대 167px 더 높은 페이지(하단 줄
     * 잘림)나 최대 434px의 채워지지 않은 페이지가 나왔다. 대신 이 getter를 통해 읽으면 같은 오래된
     * 조각을 실제로 잘려나간 활자 설정으로 그리며, [pageLayoutStyle]이 갓 재측정된 조각과 같은 상태
     * 업데이트에서 발행되므로 새 활자 설정으로의 전환은 원자적이다 — `ReaderScreen.kt`의
     * `ReaderPagePane`를 참고한다. 이 composable만이 두 그리기 호출에서는 [style] 대신 이 값을
     * 읽어야 하고, 측정과 측정 대상 박스를 정의하는 padding에는 [style] 자체를 계속 읽어야 한다.
     *
     * @return [pageLayoutStyle]의 레이아웃 필드로 교체된 [style], 또는 [pageLayoutStyle]이 null이면
     *   변경 없는 [style].
     */
    val pageDrawStyle: ReaderStyle
        get() = pageLayoutStyle?.let { style.withLayoutFieldsOf(it) } ?: style
}

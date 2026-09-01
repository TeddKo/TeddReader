package com.tedd.teddreader.core.data.pagination

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.blocksIn
import com.tedd.teddreader.core.common.model.isStandalone
import com.tedd.teddreader.core.common.model.readerImageSize
import com.tedd.teddreader.core.common.model.rebasedBy
import com.tedd.teddreader.core.common.model.standaloneBlocks
import kotlin.math.ceil
import org.koin.core.annotation.Single

/**
 * 파싱된 문서를 리더가 넘기는 페이지들로 바꾸며, 페이지 경계가 결정되는 유일한 장소다.
 *
 * 여기 있는 모든 진입점은 한 가지 규칙에 기댄다: **페이지는 절대 두 섹션에 걸치지 않는다.** EPUB spine
 * 아이템 하나는 그 자체로 독립된 문서이며, 어떤 리딩 시스템도 두 개를 화면 하나에 함께 표시하지
 * 않는다 — readium-css와 foliate-js 모두 리소스 단위로 페이지를 나눈다. 섹션 단위로 페이지를
 * 나누기 때문에 챕터 제목이 이전 챕터 마지막 페이지 중간이 아니라 새 페이지 맨 위에 오게 되고,
 * 여기서 나머지 모든 것도 이 규칙 덕분에 가능해진다: 한 섹션은 다른 섹션의 경계를 건드리지 않고
 * 측정, 저장, 복원, 추가될 수 있다.
 *
 * 이 클래스는 상태를 갖지 않는다; `@Single`인 이유는 오직 Koin이 같은 인스턴스를 계속 넘겨주게
 * 하기 위해서다.
 */
@Single
class TextPageLayoutEngine {
    /**
     * 문서 전체를 페이지로 배치하며, 실제 breaker가 주어지면 텍스트를 측정한다.
     *
     * 문서에 커버 이미지가 있으면 어떤 콘텐츠를 측정하기도 전에 그 자체로 한 페이지가 되고,
     * 나머지는 섹션 단위로 측정된 뒤 마지막에 다시 번호가 매겨진다.
     *
     * 측정은 책 전체가 아니라 챕터 단위로 상한이 걸린다: 책 전체를 기준으로 상한을 잡으면 긴
     * 책은 전부 측정 대상에서 제외되고, 추정치는 그 책 자체 스타일시트가 정한 줄 높이를 알 수
     * 없어 페이지가 실제로 그리는 것보다 절반 더 많은 줄을 채워 넣고 나머지는 하단에서 잘려
     * 나갔다. 챕터 하나씩 레이아웃하는 것이 페이지가 담고 있다고 말하는 것을 실제로 담게 하는
     * 대가다.
     *
     * @param document 파싱된 문서. 그 섹션들은 지금까지 임포트된 부분만일 수 있다.
     * @param style 리딩 스타일; 그 타입이 줄바꿈 위치를 결정한다.
     * @param viewportSize 페이지가 배치될 상자.
     * @param pageBreaker 리더 자체의 텍스트 레이아웃. null이면 추정치로 대체되는데, 정직하지만
     * 거칠다 — 실제 측정값이 생기면 호출자가 다시 페이지를 나눌 것으로 기대된다.
     * @param sectionBlocks 한 섹션의 블록 구조를 얻는 방법. 기본값은 문서 자체의 즉시 로드된
     * 블록 목록을 한 번 그룹핑하는데, 실제 측정이 어차피 모든 섹션의 텍스트를 건드리기 때문에
     * 여기서는 저렴한 선택이다; [reconstruct]는 나머지를 디코딩하지 않고 한 섹션에 대해서만
     * 답할 수 있는 조회로 이를 오버라이드한다.
     * @return 읽는 순서대로의 페이지들, 0부터 번호가 매겨지며 커버 페이지가 있으면 맨 앞에 온다.
     */
    fun paginate(
        document: ReaderDocument,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker? = null,
        viewportDensity: Float = 1f,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock> = defaultSectionBlocks(document),
    ): List<PageWindow> {
        val coverSection = findCoverSection(document, sectionBlocks)
        val coverPage = buildCoverPage(document, coverSection, sectionBlocks)

        val layout = pageLayout(style, viewportSize, viewportDensity)
        val contentSections = contentSections(document, coverSection)

        val contentPages = contentSections.flatMap { section ->
            val sectionBlockList = sectionBlocks(section)
            sectionPageRanges(
                section = section,
                sectionBlocks = sectionBlockList,
                layout = layout,
                style = style,
                pageBreaker = pageBreaker?.takeIf { canMeasureSection(section) },
            ).ranges.map { range -> buildPageWindow(document.format, section, sectionBlockList, range) }
        }
        return assemblePages(coverPage, contentPages)
    }

    /**
     * 실제 측정으로 [paginate]가 만들어냈을 페이지 목록을, 이전에 측정 패스가 만들어 저장해둔
     * 페이지 시작 지점들을 이용해 그대로 재구성한다: 콘텐츠 페이지마다 절대 문서 오프셋 하나씩,
     * [paginate]가 내보내는 순서 그대로이며, 커버 페이지는 제외된다(커버는 항상 정확히 첫 번째
     * 섹션이고 재구성하는 데 측정이 전혀 필요 없다). 여기서는 텍스트를 전혀 측정하지 않는다 —
     * 저장된 시작 지점은 지난번에 렌더러가 그 페이지를 놓은 바로 그 자리이고, 페이지가 절대 두
     * 섹션에 걸치지 않으므로 각 섹션 자체의 경계만으로 그 페이지들이 어디서 끝나는지 알 수 있다.
     *
     * 이 함수가 반환하는 목록은 페이지를 요청 시점에 만들고 가장 최근에 읽힌 창들만 유지한다.
     * 리더는 보이는 페이지 주변의 몇 장만 들여다보므로, 오래된 창은 책 한 권 분량의 페이지
     * 객체를 계속 살려두는 대신 다시 만들면 된다.
     *
     * @param document 저장된 시작 지점들이 측정된 대상인 파싱된 문서. 같은 문서라는 사실은
     * 호출자가 이미 확인해두어야 한다 — 저장된 레이아웃은 문서의 문자 수가 바뀌지 않은 동안에만
     * 유효하다(DocumentRepositoryImpl.restorePageWindows 참고).
     * @param contentPageStarts 콘텐츠 페이지마다 절대 문서 오프셋 하나씩, 오름차순, 커버 제외.
     * @param sectionBlocks 한 섹션의 블록을 얻는 방법; 여기서는 요청 시점 조회가 핵심이다.
     * @param isSectionReady `sectionBlocks(section)`이 지금 그 섹션의 실제 디코딩된 답인지,
     * 아니면 백그라운드 페치가 아직 진행 중일 때 반환되는 임시 대역인지(DocumentRepositoryImpl.SectionBlocksCache
     * 참고). 임시 대역으로 만들어진 페이지는 실제 블록이 도착하면 다시 만들 수 있도록 자유로운
     * 상태를 유지해야 한다, 임시 대역을 영원히 고정해버리는 대신에; 다른 모든 호출자는 이미
     * 완전히 디코딩된 문서를 넘기므로 기본값인 "항상 준비됨"은 그들에게 아무 영향도 주지 않는다.
     * @return [paginate]가 만들었을 것과 같은 페이지 목록. 각 페이지는 처음 읽힐 때 만들어진다.
     */
    fun reconstruct(
        document: ReaderDocument,
        contentPageStarts: LongArray,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock> = defaultSectionBlocks(document),
        isSectionReady: (Int) -> Boolean = { true },
    ): List<PageWindow> {
        val coverSection = findCoverSection(document, sectionBlocks)
        val coverPage = buildCoverPage(document, coverSection, sectionBlocks)
        val contentSections = contentSections(document, coverSection)
        return RestoredPageWindows(
            coverPage = coverPage,
            contentSections = contentSections,
            contentPageStarts = contentPageStarts,
            format = document.format,
            sectionBlocks = sectionBlocks,
            buildPage = ::buildPageWindow,
            isSectionReady = isSectionReady,
        )
    }

    /**
     * [reconstruct]와 같은 지연 재구성이지만, spine 순서로 이미 그룹핑된 섹션별 페이지 시작
     * 지점들로부터 만든다. 리더가 실제로 그 페이지들을 요청하기 전에 측정된 모든 섹션을 하나의
     * 큰 페이지 목록으로 평탄화하는 일을 피하기 위해 점진적 페이지네이션이 사용한다.
     */
    internal fun reconstructMeasuredSections(
        format: DocumentFormat,
        coverPage: PageWindow?,
        contentSections: List<ReaderSection>,
        sectionPageStarts: List<LongArray>,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock>,
        isSectionReady: (Int) -> Boolean = { true },
    ): RestoredPageWindows = RestoredPageWindows(
        coverPage = coverPage,
        contentSections = contentSections,
        contentPageStarts = null,
        sectionPageStarts = sectionPageStarts,
        format = format,
        sectionBlocks = sectionBlocks,
        buildPage = ::buildPageWindow,
        isSectionReady = isSectionReady,
    )

    /**
     * [section]만 완전히 독립적으로 측정했을 때 [paginate]가 부여했을 절대 문서 오프셋들 —
     * DocumentRepositoryImpl.importNextSections가 이미 저장된 pageStartsBlob에 덧붙이는 단위이며,
     * 이 덕분에 점진적으로 임포트된 섹션은 매 배치마다 책 전체를 처음부터 다시 측정하는 대신
     * 정확히 한 번만 측정된다. 페이지가 절대 두 섹션에 걸치지 않으므로([paginate] 참고) 안전하고,
     * 한 섹션의 경계는 다른 어떤 섹션의 경계에도 의존하거나 그것을 움직이지 않는다.
     *
     * @param section 측정할 섹션.
     * @param sectionBlocks 그 섹션의 블록들, 이미 그 섹션 기준으로 리베이스됨.
     * @param style 측정 대상 리딩 스타일.
     * @param viewportSize 측정 대상 상자.
     * @param pageBreaker 리더 자체 레이아웃; null이면 추정된 시작 지점을 내며, 측정 상한보다 긴
     * 섹션은 breaker가 주어져도 추정된다.
     * @return 섹션의 페이지 시작 지점들과, 그것이 [pageBreaker]에서 나온 것인지 여부. 호출자가
     * 상한 초과 추정치를 재사용 가능한 측정 레이아웃으로 영구 저장하는 일이 없도록 한다.
     */
    internal fun pageStartsForSection(
        section: ReaderSection,
        sectionBlocks: List<ReaderBlock>,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float = 1f,
    ): SectionPageStarts {
        val layout = pageLayout(style, viewportSize, viewportDensity)
        val result = sectionPageRanges(
            section = section,
            sectionBlocks = sectionBlocks,
            layout = layout,
            style = style,
            pageBreaker = pageBreaker?.takeIf { canMeasureSection(section) },
        )
        return SectionPageStarts(
            offsets = LongArray(result.ranges.size) { index -> result.ranges[index].start },
            isMeasured = result.isMeasured,
        )
    }

    /**
     * 한 번의 측정 상한을 넘지 않고 실제 [ReaderPageBreaker]에 [section]을 넘길 수 있는지 여부.
     * 더 긴 섹션을 담은 저장된 레이아웃은 측정 출처 추적 이전 것이라 신뢰할 수 없다, 그 경로는
     * 과거에 측정된 키 아래 추정치를 대신 넣은 적이 있기 때문이다.
     *
     * @param section 텍스트 길이가 측정 상한을 좌우하는 섹션.
     * @return [section]이 실제 breaker 패스를 돌리기에 충분히 짧으면 true.
     */
    internal fun canMeasureSection(section: ReaderSection): Boolean =
        section.text.length <= MaxMeasuredContentLengthChars

    /**
     * [paginate]가 이 문서에 커버 이미지 전용 첫 페이지를 부여할지 여부.
     *
     * @param document 파싱된 문서.
     * @param sectionBlocks 섹션의 블록을 얻는 방법; 커버 후보만 검사된다.
     * @return 문서의 첫 섹션이 커버 이미지이고 그 자체로 한 페이지가 되면 true.
     */
    fun hasCoverPage(
        document: ReaderDocument,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock> = defaultSectionBlocks(document),
    ): Boolean = findCoverSection(document, sectionBlocks) != null

    /**
     * [paginate]의 커버 페이지 판별과 콘텐츠 섹션 분리를, 콘텐츠 섹션을 하나씩 측정하고 싶은
     * 호출자를 위해 한 번만 해서 넘겨준다 — DocumentRepositoryImpl의 점진적 페이지네이션 참고,
     * 그것은 리더가 이어서 읽던 섹션을 다른 무엇보다 먼저 측정하며 그 섹션이 어느 것인지, 커버
     * 페이지가 있다면 어디서 끝나는지 알기 위해 정확히 이것이 필요하다.
     *
     * @param document 파싱된 문서.
     * @param sectionBlocks 섹션의 블록을 얻는 방법.
     * @return 커버 페이지(있다면)와 문서 순서대로의 콘텐츠 섹션들.
     */
    fun resolveSections(
        document: ReaderDocument,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock> = defaultSectionBlocks(document),
    ): PaginationSections {
        val coverSection = findCoverSection(document, sectionBlocks)
        return PaginationSections(
            coverPage = buildCoverPage(document, coverSection, sectionBlocks),
            contentSections = contentSections(document, coverSection),
        )
    }

    /**
     * [paginate] 자체의 섹션별 측정을 독립적으로 꺼내둔 것으로, 리더가 첫 페이지를 보기 전에
     * 모든 섹션을 다 배치하는 대신 점진적 페이지네이션이 결과를 섹션 하나씩 늘려갈 수 있게 한다
     * (DocumentRepositoryImpl.getPageWindows/continuePagination 참고).
     *
     * @param format 문서의 포맷. 페이지 위치가 어떻게 표현되는지를 결정한다.
     * @param section 측정할 섹션.
     * @param sectionBlocks 그 섹션의 블록들.
     * @param style 측정 대상 리딩 스타일.
     * @param viewportSize 측정 대상 상자.
     * @param pageBreaker 리더 자체 레이아웃; null이거나 상한 초과 섹션이면 추정치를 낸다.
     * @return 이 섹션의 페이지들, [assemblePages]가 다시 번호를 매기기 전 [paginate] 자체의
     * 섹션별 패스가 남기는 그대로 (0, 0)으로 번호가 매겨져 있음 — 호출자는 지금까지 측정한
     * 섹션 수를 알게 되면 한 번에 다시 번호를 매긴다.
     */
    fun paginateSection(
        format: DocumentFormat,
        section: ReaderSection,
        sectionBlocks: List<ReaderBlock>,
        style: ReaderStyle,
        viewportSize: ViewportSize,
        pageBreaker: ReaderPageBreaker?,
        viewportDensity: Float = 1f,
    ): List<PageWindow> {
        val layout = pageLayout(style, viewportSize, viewportDensity)
        return sectionPageRanges(
            section = section,
            sectionBlocks = sectionBlocks,
            layout = layout,
            style = style,
            pageBreaker = pageBreaker?.takeIf { canMeasureSection(section) },
        ).ranges.map { range -> buildPageWindow(format, section, sectionBlocks, range) }
    }

    /**
     * [paginate]/[reconstruct]에 필요한 모든 블록을, 문서 자체의 즉시 로드된 목록으로부터 한 번
     * 그룹핑하고 각 섹션 자체를 기준으로 상대적으로 읽히도록 이동시킨 것 — 저장소에서 불러온
     * 문서에 대해 [SectionBlocksCache.blocksFor]가 넘겨주는 것과 같은 모양이라서
     * (DocumentRepositoryImpl.persistParsedDocument 참고) [buildPageWindow]는 섹션의 블록이 어느
     * 출처에서 왔는지 전혀 알 필요가 없다. private이 아니라 internal인 이유는
     * DocumentRepositoryImpl이 이것을 스스로 한 번만 계산해서 매번 책 전체를 다시 그룹핑하는
     * 대신 여러 [paginateSection] 호출에 걸쳐 같은 클로저를 재사용할 수 있게 하기 위해서다.
     *
     * @param document 그 자체의 즉시 로드된 블록 목록이 그룹핑될 파싱된 문서.
     * @return 섹션에서 그 섹션의 블록으로의 조회. 섹션 자체 시작 지점 기준으로 리베이스됨.
     */
    internal fun defaultSectionBlocks(document: ReaderDocument): (ReaderSection) -> List<ReaderBlock> {
        val grouped = groupBlocksBySection(document.sections, document.blocks)
        return { section -> grouped[section.index].orEmpty().rebasedBy(section.range.start) }
    }

    /**
     * 책의 커버인 섹션을 찾는다, 있다면.
     *
     * 책에 커버가 있다면 그것은 항상 첫 번째 섹션 자체의 그림이므로, 이 함수는 다른 어떤
     * 섹션도 들여다보거나 디코딩할 필요가 없다; 복원 경로에서는 섹션당 데이터베이스 읽기를
     * 절약해준다.
     *
     * `sectionBlocks(section)`은 섹션 자체 시작 지점을 기준으로 상대적으로 읽으므로, 여기서
     * 검사되는 범위는 섹션의 절대 범위가 아니라 같은 프레임 안에서의 `0..섹션 자체 길이`다.
     *
     * @param document 파싱된 문서.
     * @param sectionBlocks 섹션의 블록을 얻는 방법.
     * @return 커버 섹션, 또는 첫 섹션이 커버 이미지가 아니면 null.
     */
    private fun findCoverSection(
        document: ReaderDocument,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock>,
    ): ReaderSection? =
        document.sections.firstOrNull()?.takeIf { section ->
            val sectionLength = (section.range.end - section.range.start).coerceAtLeast(1L)
            sectionBlocks(section).any { block ->
                block.kind == ReaderBlockKind.COVER_IMAGE &&
                    block.range.start >= 0L &&
                    block.range.end <= sectionLength
            }
        }

    /**
     * 커버 자체의 페이지를 만든다.
     *
     * 그 블록들은 섹션 자체의 상대 프레임 안에서 필터링된 뒤 `PageWindow.blocks`가 다른 모든
     * 곳에서 쓰는 절대 오프셋으로 다시 이동된다([buildPageWindow] 참고) — 범위를 그대로 넘기지
     * 않고 이렇게 작성한 이유는, 커버 섹션의 시작이 항상 0이라는 사실이 정확성의 근거가 되지
     * 않도록 하기 위해서다.
     *
     * @param document 파싱된 문서.
     * @param coverSection 커버 섹션, 또는 커버가 없는 책이면 null.
     * @param sectionBlocks 그 섹션의 블록을 얻는 방법.
     * @return (0, 1)로 번호가 매겨진 커버 페이지 — [assemblePages] 또는 RestoredPageWindows가
     * 총합을 보정한다 — 또는 커버가 없으면 null.
     */
    private fun buildCoverPage(
        document: ReaderDocument,
        coverSection: ReaderSection?,
        sectionBlocks: (ReaderSection) -> List<ReaderBlock>,
    ): PageWindow? =
        coverSection?.let { section ->
            val coverRange = TextRange(section.range.start, section.range.end.coerceAtLeast(section.range.start + 1))
            PageWindow(
                pageIndex = PageIndex(current = 0, total = 1),
                location = ReaderLocation.EpubOffset(section.index, 0),
                text = section.text,
                textRange = coverRange,
                blocks = sectionBlocks(section)
                    .blocksIn(coverRange.start - section.range.start, coverRange.end - section.range.start)
                    .rebasedBy(-section.range.start),
            )
        }

    /**
     * @param document 파싱된 문서.
     * @param coverSection 커버 페이지가 이미 차지한 섹션, 또는 null.
     * @return 아직 페이지네이션되어야 할 모든 섹션, 문서 순서대로.
     */
    private fun contentSections(document: ReaderDocument, coverSection: ReaderSection?): List<ReaderSection> =
        document.sections.filter { section -> coverSection == null || section.index != coverSection.index }

    /**
     * 측정되었거나 복원된 범위로부터 페이지 하나를 만든다.
     *
     * 페이지의 위치는 그 포맷이 위치를 명명하는 방식대로 표현된다: EPUB 페이지는 spine 아이템과
     * 섹션 상대 오프셋을 함께 갖고, 그 외에는 절대 텍스트 오프셋을 갖는다.
     *
     * `sectionBlocks`는 섹션 자체 시작 지점을 기준으로 상대적으로 읽으므로([defaultSectionBlocks]
     * 참고), 필터링은 그 프레임 안에서 일어나고 결과는 절대 좌표로 다시 이동된다 — 페이지의
     * 블록은 항상 자기 자신의 `textRange`와 같은 오프셋을 가리켜 왔으며, ReaderSemanticText는
     * 이를 근거로 페이지 텍스트 안에서 블록의 위치를 찾는다.
     *
     * @param format 문서의 포맷. 위치의 형태를 결정한다.
     * @param section 이 페이지가 속한 섹션; 페이지는 절대 둘에 걸치지 않는다.
     * @param sectionBlocks 그 섹션의 블록들, 섹션 자체 프레임 안에서.
     * @param range 페이지의 절대 범위.
     * @return 페이지, 호출자가 다시 번호를 매기기 전까지는 (0, 0)으로 번호가 매겨져 있음.
     */
    private fun buildPageWindow(
        format: DocumentFormat,
        section: ReaderSection,
        sectionBlocks: List<ReaderBlock>,
        range: TextRange,
    ): PageWindow = PageWindow(
        pageIndex = PageIndex(current = 0, total = 0),
        location = if (format == DocumentFormat.EPUB) {
            ReaderLocation.EpubOffset(section.index, range.start - section.range.start)
        } else {
            ReaderLocation.TextOffset(range.start)
        },
        text = section.text.substring(
            (range.start - section.range.start).toInt(),
            (range.end - section.range.start).toInt(),
        ),
        textRange = range,
        blocks = sectionBlocks
            .blocksIn(range.start - section.range.start, range.end - section.range.start)
            .rebasedBy(-section.range.start),
    )

    /**
     * 페이지 목록에 번호를 매긴다: 있으면 커버가 먼저, 그다음 콘텐츠, 각 페이지는 목록 자체의
     * 총합을 갖는다.
     *
     * private이 아니라 internal인 이유는 DocumentRepositoryImpl이 재번호 매기기를 두 번
     * 중복하는 대신, 점진적 페이지네이션의 지금까지의 페이지들을 [paginate]가 책 전체 패스에
     * 번호를 매기는 방식 그대로([resolveSections] / [paginateSection] 참고) 다시 번호 매길 수
     * 있게 하기 위해서다.
     *
     * @param coverPage 커버 페이지, 또는 null.
     * @param contentPages 읽는 순서대로의 콘텐츠 페이지들.
     * @return 번호가 매겨진 페이지들, 또는 아무것도 없으면 빈 목록.
     */
    internal fun assemblePages(coverPage: PageWindow?, contentPages: List<PageWindow>): List<PageWindow> {
        if (contentPages.isEmpty() && coverPage == null) return emptyList()
        val pages = if (coverPage != null) listOf(coverPage) + contentPages else contentPages
        return pages.mapIndexed { index, page ->
            page.copy(pageIndex = PageIndex(current = index, total = pages.size))
        }
    }

    /**
     * 각 섹션이 소유한 블록들, 문서를 한 번 훑어서 수집한다.
     *
     * 대신 섹션마다 [blocksIn]을 한 번씩 호출하면 모든 섹션에 대해 모든 블록을 훑게 되는데,
     * 챕터가 수백 개이고 블록이 수만 개인 책에서는 페이지네이션이 하는 일 중 가장 느린 부분이
     * 된다.
     */
    private fun groupBlocksBySection(
        sections: List<ReaderSection>,
        blocks: List<ReaderBlock>,
    ): Map<Int, List<ReaderBlock>> {
        if (sections.isEmpty() || blocks.isEmpty()) return emptyMap()
        val ordered = sections.sortedBy { it.range.start }
        val grouped = LinkedHashMap<Int, MutableList<ReaderBlock>>(ordered.size)
        var sectionIndex = 0
        blocks.sortedBy { it.range.start }.forEach { block ->
            while (sectionIndex < ordered.lastIndex && block.range.start >= ordered[sectionIndex].range.end) {
                sectionIndex += 1
            }
            val section = ordered[sectionIndex]
            if (block.range.start < section.range.start || block.range.start > section.range.end) return@forEach
            grouped.getOrPut(section.index) { mutableListOf() } += block
        }
        return grouped
    }

    /**
     * 한 섹션 안의 페이지 경계들, 문서 절대 범위로 표현됨.
     *
     * 읽을 수 있는 텍스트가 없는 섹션도 그릴 것이 있으면 여전히 페이지 하나를 낸다 — 전면
     * 삽화 챕터가 정확히 그런 경우다 — 그래서 그림이 조용히 누락되지 않는다.
     *
     * `sectionBlocks`는 이미 이 섹션 자체 시작 지점을 기준으로 상대적으로 읽으므로
     * ([defaultSectionBlocks]와 DocumentRepositoryImpl.persistParsedDocument 참고), 이제 매
     * 페이지네이션 패스마다 여기서 리베이스가 일어나지 않는다; 그 이동은 이제 섹션이 쓰여질
     * 때 한 번만 일어난다.
     *
     * @param section 나눌 섹션.
     * @param sectionBlocks 그 블록들, 자체 프레임 안에서.
     * @param layout 추정된 페이지 기하값, 측정이 없을 때만 사용됨.
     * @param style 리딩 스타일, 추정치에서만 사용됨.
     * @param pageBreaker 실제 측정값, 또는 추정하려면 null.
     * @return 절대 페이지 범위들과 그것이 실제 breaker에서 나온 것인지 여부, 또는 보여줄 것이
     * 없는 섹션이면 정확히 빈 범위들.
     */
    private fun sectionPageRanges(
        section: ReaderSection,
        sectionBlocks: List<ReaderBlock>,
        layout: PageLayout,
        style: ReaderStyle,
        pageBreaker: ReaderPageBreaker?,
    ): SectionPageRanges {
        val text = section.text
        val base = section.range.start
        if (text.isEmpty()) return SectionPageRanges(emptyList(), isMeasured = true)
        if (text.isBlank() && sectionBlocks.none { it.kind.isStandalone() }) {
            return SectionPageRanges(emptyList(), isMeasured = true)
        }

        val measuredPageStarts = pageBreaker
            ?.pageStarts(text, sectionBlocks)
            ?.takeIf { it.isNotEmpty() }
        val relativeRanges = if (measuredPageStarts != null) {
            measuredPageRanges(pageStarts = measuredPageStarts, textLength = text.length)
        } else {
            splitPageRanges(
                text = text,
                widthUnitsPerLine = layout.widthUnitsPerLine,
                linesPerPage = layout.linesPerPage,
                standaloneHeights = standaloneBlockLineHeights(
                    blocks = sectionBlocks,
                    layout = layout,
                    style = style,
                ),
            )
        }
        val absoluteRanges = if (relativeRanges.isEmpty()) {
            listOf(TextRange(base, base + text.length))
        } else {
            relativeRanges.map { range -> TextRange(base + range.start, base + range.end) }
        }
        return SectionPageRanges(absoluteRanges, isMeasured = measuredPageStarts != null)
    }

    /**
     * 스타일과 뷰포트에 대한 추정 페이지 기하값: 한 줄에 얼마만큼의 글리프 너비가 들어가는지,
     * 한 페이지에 몇 줄이 들어가는지. 오직 추정치만 이것을 사용한다 — 측정 패스는 경계를
     * 렌더러로부터 얻는다.
     *
     * @param style 리딩 스타일.
     * @param viewportSize 페이지가 배치될 상자.
     * @return 추정된 기하값. 퇴화된 스타일에서도 그릴 수 있는 페이지가 나오도록 하한이 걸림.
     */
    private fun pageLayout(style: ReaderStyle, viewportSize: ViewportSize, viewportDensity: Float): PageLayout {
        val emWidth = style.fontSizeSp.coerceAtLeast(1f)
        val lineHeight = (style.fontSizeSp * style.lineHeightMultiplier).coerceAtLeast(1f)
        // 스타일의 크기는 sp 단위이므로, 뷰포트도 호출자가 측정할 때 쓴 밀도로 sp 단위로
        // 환산한다. 아직 실제 패널이 없는 호출자는 밀도 1로 sp 단위 추정치를 넘긴다.
        val widthSp = viewportSize.widthPx / viewportDensity.coerceAtLeast(0.01f)
        val heightSp = viewportSize.heightPx / viewportDensity.coerceAtLeast(0.01f)
        return PageLayout(
            widthUnitsPerLine = (widthSp * WideGlyphUnits / emWidth).toInt()
                .coerceAtLeast(WideGlyphUnits),
            linesPerPage = (heightSp / lineHeight).toInt().coerceAtLeast(1),
        )
    }

    /**
     * 렌더러 자체의 페이지 시작 지점들을 범위로 바꾼다. UI가 실제 패널을 대상으로 측정한
     * 것이므로 각 페이지가 정확히 그것을 채운다.
     *
     * @param pageStarts 모든 페이지의 섹션 상대 시작 지점, 오름차순.
     * @param textLength 섹션의 길이. 마지막 범위를 닫는 데 쓰인다.
     * @return 페이지당 하나의 범위, 읽는 순서대로.
     */
    private fun measuredPageRanges(pageStarts: IntArray, textLength: Int): List<TextRange> =
        pageStarts.mapIndexed { index, start ->
            val end = pageStarts.getOrNull(index + 1) ?: textLength
            TextRange(start.toLong(), end.toLong())
        }

    /**
     * 독립 블록(이미지 또는 구분선) 하나가 실제로 그려졌을 때 차지하는 줄 수.
     *
     * 렌더러는 독립 이미지를 전체 컬럼 너비에 맞추고, 높이는 이미지 자체의 종횡비에서 가져와
     * 페이지 높이로 상한을 둔다 — ReaderSemanticText의 `placeholderFor` 참고. 이 함수는 그
     * 규칙을 페이지네이션이 세는 줄 단위로 그대로 옮긴 것이라, 이미지를 담은 페이지는 그것을
     * 위해 실제로 자리를 남겨둔다. 이게 없으면 추정기는 이미지를 그것이 담고 있는 개행 문자
     * 하나로만 취급해 그 주위에 텍스트를 한 페이지 가득 채워 넣었고, 이미지는 넘친 패널에
     * 의해 잘려나갔다. 추정 경로에서만 쓰인다; 측정 페이지네이션은 플레이스홀더를 실제로
     * 배치한다.
     */
    private fun standaloneBlockLineHeights(
        blocks: List<ReaderBlock>,
        layout: PageLayout,
        style: ReaderStyle,
    ): Map<Int, Int> {
        val columnWidthEm = layout.widthUnitsPerLine.toFloat() / WideGlyphUnits
        val pageHeightEm = layout.linesPerPage * style.lineHeightMultiplier
        val lineHeightEm = style.lineHeightMultiplier.coerceAtLeast(0.1f)
        return blocks
            .standaloneBlocks()
            .associate { block ->
                val size = block.readerImageSize(
                    columnWidthEm = columnWidthEm,
                    maxHeightEm = pageHeightEm,
                    emInPx = style.fontSizeSp,
                )
                val lines = ceil(size.heightEm / lineHeightEm).toInt().coerceAtLeast(1)
                block.range.start.toInt() to lines.coerceAtMost(layout.linesPerPage)
            }
    }

    /**
     * 추정 분할: 텍스트를 훑으며 글리프 너비와 줄 수를 세고, 페이지가 다 차면 닫는다.
     *
     * 렌더러가 그렇게 하기 때문에 공백에서 줄바꿈한다 — 대신 단어 중간에서 채워 넣으면
     * 렌더러 기준으로 페이지당 한 줄만큼의 텍스트가 더 들어가 버렸다. 끝에 붙은 공백은
     * 줄바꿈하지 않고 가장자리 너머로 매달리게 두므로, 새 줄을 시작시키지 않고 다음 줄바꿈이
     * 놓일 수 있는 지점만 기록한다. 이미지는 절대 페이지에 걸쳐 나뉘지 않는다: 이 페이지의
     * 남은 공간에 들어가지 않으면 정확히 렌더러가 밀어낼 그 지점에서 다음 페이지를 시작한다.
     *
     * @param text 섹션의 텍스트.
     * @param widthUnitsPerLine 한 줄에 들어가는 글리프 너비.
     * @param linesPerPage 한 페이지에 들어가는 줄 수.
     * @param standaloneHeights 각 독립 블록이 차지하는 줄 수, 시작 오프셋을 키로 함.
     * @return 페이지당 하나의 섹션 상대 범위; 모든 페이지는 최소 한 글자를 담는다.
     */
    private fun splitPageRanges(
        text: String,
        widthUnitsPerLine: Int,
        linesPerPage: Int,
        standaloneHeights: Map<Int, Int> = emptyMap(),
    ): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        var start = 0
        while (start < text.length) {
            var index = start
            var usedLines = 0
            var usedWidthUnits = 0
            var end = start

            var lastWrapOpportunity = -1

            while (index < text.length && usedLines < linesPerPage) {
                val standaloneLines = standaloneHeights[index]
                if (standaloneLines != null) {
                    if (usedLines > 0 && usedLines + standaloneLines > linesPerPage) break
                    usedLines += standaloneLines
                    usedWidthUnits = 0
                    lastWrapOpportunity = -1
                    index += 1
                    end = index
                    continue
                }

                val char = text[index]
                if (char == '\n') {
                    usedLines += 1
                    usedWidthUnits = 0
                    lastWrapOpportunity = -1
                    index += 1
                    end = index
                    continue
                }

                val widthUnits = char.widthUnits()
                if (char == ' ') {
                    usedWidthUnits += widthUnits
                    index += 1
                    end = index
                    lastWrapOpportunity = index
                    continue
                }

                if (usedWidthUnits + widthUnits > widthUnitsPerLine) {
                    if (lastWrapOpportunity > start) {
                        index = lastWrapOpportunity
                        end = index
                    }
                    usedLines += 1
                    usedWidthUnits = 0
                    lastWrapOpportunity = -1
                    if (usedLines >= linesPerPage) break
                    continue
                }
                usedWidthUnits += widthUnits
                index += 1
                end = index
            }

            if (end <= start) end = (start + 1).coerceAtMost(text.length)
            ranges += TextRange(start.toLong(), end.toLong())
            start = end
        }
        return ranges
    }

    /**
     * @receiver 측정할 문자.
     * @return em의 1/100 단위로 표현한 그 문자의 폭 — [WideGlyphUnits]와 [NarrowGlyphUnits] 참고.
     */
    private fun Char.widthUnits(): Int = if (isWideGlyph()) WideGlyphUnits else NarrowGlyphUnits

    /**
     * @receiver 분류할 문자.
     * @return 전각 글리프면 true: 한글, 가나, CJK 표의문자와 전각 형태 — 한국어, 일본어, 중국어
     * 책을 읽는 독자가 실제로 마주치는 범위들이다.
     */
    private fun Char.isWideGlyph(): Boolean = this in '\u1100'..'\u11FF' ||
        this in '\u2E80'..'\u303F' ||
        this in '\u3040'..'\u30FF' ||
        this in '\u3100'..'\u312F' ||
        this in '\u3130'..'\u318F' ||
        this in '\u31A0'..'\u31EF' ||
        this in '\u31F0'..'\u4DBF' ||
        this in '\u4E00'..'\u9FFF' ||
        this in '\uA960'..'\uA97F' ||
        this in '\uAC00'..'\uD7FF' ||
        this in '\uF900'..'\uFAFF' ||
        this in '\uFE30'..'\uFE4F' ||
        this in '\uFF01'..'\uFF60' ||
        this in '\uFFE0'..'\uFFE6'

}

/**
 * [TextPageLayoutEngine.resolveSections]의 응답: 책에 커버가 있으면 그 커버 페이지, 그리고
 * [TextPageLayoutEngine.paginateSection]이 서로 독립적으로 어떤 순서로든 측정할 수 있는 섹션들.
 *
 * @property coverPage 문서의 이미 만들어진 커버 페이지, 또는 문서에 커버가 없으면 null —
 *   [TextPageLayoutEngine.buildCoverPage] 참고.
 * @property contentSections 아직 페이지네이션되어야 할 섹션들, 문서 순서대로, 커버 섹션은 이미
 *   제외됨 — [TextPageLayoutEngine.contentSections] 참고.
 */
data class PaginationSections(
    val coverPage: PageWindow?,
    val contentSections: List<ReaderSection>,
)

/**
 * [TextPageLayoutEngine.reconstruct]가 돌려주는 페이지 목록: 실제 측정이 이미 배치해둔 모든
 * [PageWindow]가, 무언가 [get]으로 그것을 처음 읽을 때 만들어지고 그 뒤로는 유지된다. [size]와
 * 페이지 순서는 전부 [contentPageStarts](long 몇 개)에서 나오므로, 단 하나의 섹션 블록도
 * 디코딩되기 전에 총 페이지 수가 정확하다.
 *
 * @property coverPage 문서의 이미 만들어진 커버 페이지, 또는 없으면 null. 인덱스 0에 그대로
 *   돌려주되 `pageIndex`만 이 목록의 실제 [size]에 맞게 보정된다 — 재구성하는 데 측정이 전혀
 *   필요 없다([TextPageLayoutEngine.reconstruct] 자체 문서 참고).
 * @property contentSections 문서의 비-커버 섹션들, spine 순서대로. [get]은 저장된 페이지 시작
 *   지점이 어느 섹션에 속하는지 찾기 위해 (`sectionOwning`을 통해) 이것을 이진 탐색하고,
 *   [buildAt]은 나중에 저장된 시작 지점이 페이지를 이미 닫지 않으면 섹션 자체의 끝으로
 *   대체한다.
 * @property contentPageStarts 콘텐츠 페이지마다 절대 문서 오프셋 하나씩, 오름차순, 커버 제외 —
 *   [TextPageLayoutEngine.reconstruct]에 넘겨진 것과 같은 배열. [size]와 [get] 안의 모든
 *   페이지의 섹션·범위는 아무것도 측정하지 않고 오직 이 배열에서만 나온다.
 * @property format 문서의 포맷. [buildPage]로 전달되어 복원된 페이지의 위치가 방금 측정된
 *   페이지와 같은 방식으로 표현되게 한다.
 * @property sectionBlocks 한 섹션의 블록을 얻는 방법. [buildAt]에서만, 그 섹션의 페이지가 실제로
 *   처음 읽힐 때 호출된다 — 이 덕분에 이 목록은 단 하나의 섹션 블록도 디코딩되기 전에 자신의
 *   [size]에 답할 수 있다.
 * @property buildPage [TextPageLayoutEngine.buildPageWindow], 참조로 전달되어 이 클래스가 엔진
 *   자체의 private 헬퍼에 손대지 않고도 페이지를 만들 수 있게 한다.
 * @property isSectionReady 방금 만들어진 페이지가 속한 섹션이 이미 진짜로 디코딩되었는지, 아니면
 *   아직 임시 대역으로 답하고 있는지. [get]은 이것을 이용해 만들어진 페이지를 [built]에 영구히
 *   기억해도 되는지, 아니면 실제 블록이 도착하면 나중에 읽을 때 다시 만들 수 있도록 자유로운
 *   상태를 유지해야 하는지 결정한다(클래스 문서와 DocumentRepositoryImpl.SectionBlocksCache 참고).
 */
internal class RestoredPageWindows(
    private val coverPage: PageWindow?,
    private val contentSections: List<ReaderSection>,
    private val contentPageStarts: LongArray?,
    private val sectionPageStarts: List<LongArray>? = null,
    private val format: DocumentFormat,
    private val sectionBlocks: (ReaderSection) -> List<ReaderBlock>,
    private val buildPage: (DocumentFormat, ReaderSection, List<ReaderBlock>, TextRange) -> PageWindow,
    private val isSectionReady: (Int) -> Boolean = { true },
) : AbstractList<PageWindow>() {
    /** [coverPage]가 존재해 인덱스 0을 차지하면 1, 아니면 0 — [get]이 조회하는 모든 콘텐츠 인덱스를 이만큼 이동시킨다. */
    private val coverOffset = if (coverPage != null) 1 else 0

    /** 커버 페이지(있다면)와, 저장된 콘텐츠 페이지 시작 지점마다 항목 하나씩 — 어떤 섹션이 디코딩되기 전에도 정확함. */
    override val size: Int = coverOffset + (contentPageStarts?.size ?: sectionPageStarts.orEmpty().sumOf { it.size })

    /** [get]이 이미 만든 페이지들. 요청 시점 재구성이 무한정 자라지 않도록 상한이 걸림. */
    private val built = HashMap<Int, PageWindow>()
    private val builtOrder = ArrayDeque<Int>()

    /** 측정된 섹션당 페이지 수의 누적합. 그룹핑된 점진적 경로에서만 필요함. */
    private val sectionEndIndexes: IntArray? = sectionPageStarts?.let { starts ->
        IntArray(starts.size).also { endIndexes ->
            var total = 0
            starts.forEachIndexed { index, sectionStarts ->
                total += sectionStarts.size
                endIndexes[index] = total
            }
        }
    }

    /** 상한이 걸린 페이지 캐시가 현재 담고 있는 페이지 수 — 절약분을 보여주기 위해 로깅됨. */
    val builtCount: Int get() = built.size

    /**
     * [index]의 페이지를 처음 읽을 때 만들고, 그 섹션의 블록이 진짜가 되면 기억해둔다.
     *
     * 커버 페이지는 더 기다릴 것이 없다 — 이 목록이 넘겨지기 전에 그 섹션은 항상 미리 준비되어
     * 있는데, 커버 판별이 그것을 즉시 필요로 하기 때문이다(DocumentRepositoryImpl.restorePageWindows
     * 참고) — 하지만 그 `pageIndex`는 여전히 실제 총합에 맞게 보정되어야 한다: `buildCoverPage`는
     * 단독 `PageIndex(0, 1)`을 넘기는데, `assemblePages`가 측정 경로에서 다시 쓰는 것과 같은
     * 방식이며, 이 보정을 건너뛰면 같은 목록의 다른 모든 페이지는 실제 총합을 갖는데 복원된
     * 커버 페이지의 총합만 1에 고정된 채로 남게 된다.
     *
     * 자기 섹션의 블록이 아직 임시 대역인 상태로 만들어진 페이지는 일부러 기억되지 **않는다**:
     * 이 인덱스의 다음 읽기는 실제 블록이 도착한 이후일 수 있다. 섹션이 준비되면 그 페이지는
     * 최종이며 다시는 바뀌지 않아야 한다(SectionBlocksCache 참고).
     *
     * @param index 읽을 페이지, 커버 포함.
     * @return 그 페이지, 목록의 실제 총합을 가짐.
     * @throws IndexOutOfBoundsException [index]가 `0 until size` 범위를 벗어나면.
     */
    override fun get(index: Int): PageWindow {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index: $index, size: $size")
        built[index]?.let {
            rememberBuilt(index, it)
            return it
        }
        if (coverPage != null && index == 0) {
            val page = coverPage.copy(pageIndex = PageIndex(current = 0, total = size))
            rememberBuilt(0, page)
            return page
        }
        val contentIndex = index - coverOffset
        val (section, page) = if (contentPageStarts != null) {
            val section = contentSections.sectionOwning(contentPageStarts[contentIndex])
            section to buildAt(index, contentIndex, section, contentPageStarts, contentIndex + 1)
        } else {
            val endIndexes = requireNotNull(sectionEndIndexes)
            val sectionPosition = endIndexes.firstIndexGreaterThan(contentIndex)
            val section = contentSections[sectionPosition]
            val starts = requireNotNull(sectionPageStarts)[sectionPosition]
            val sectionContentIndex = contentIndex - if (sectionPosition == 0) 0 else endIndexes[sectionPosition - 1]
            section to buildAt(index, sectionContentIndex, section, starts, sectionContentIndex + 1)
        }
        if (isSectionReady(section.index)) rememberBuilt(index, page)
        return page
    }

    /**
     * 저장된 시작 지점으로부터 콘텐츠 페이지 하나를 만든다.
     *
     * 페이지는 다음 저장된 시작 지점에서 끝나되, 그 시작 지점이 이 섹션 다음 섹션에 속하면 —
     * 대신 자기 자신 섹션의 끝까지 이어진다, 페이지가 절대 두 섹션에 걸치지 않으므로
     * [TextPageLayoutEngine.paginate]의 섹션별 순회와 정확히 같다.
     *
     * @param index 커버를 포함한 전체 목록에서 이 페이지의 인덱스, `pageIndex`용.
     * @param contentIndex 콘텐츠 페이지들 사이에서 같은 페이지의 인덱스, 저장된 시작 지점용.
     * @param section 이 페이지를 소유하는 섹션.
     * @return 만들어진 페이지, 목록의 실제 총합에 맞게 번호가 매겨짐.
     */
    private fun buildAt(
        index: Int,
        contentIndex: Int,
        section: ReaderSection,
        starts: LongArray,
        nextIndex: Int,
    ): PageWindow {
        val start = starts[contentIndex]
        val nextStart = starts.getOrNull(nextIndex)
        val end = if (nextStart != null && nextStart < section.range.end) nextStart else section.range.end
        val page = buildPage(format, section, sectionBlocks(section), TextRange(start, end))
        return page.copy(pageIndex = PageIndex(current = index, total = size))
    }

    private companion object {
        const val BuiltCacheMaxEntries = 16
    }

    private fun rememberBuilt(index: Int, page: PageWindow) {
        if (built.containsKey(index)) {
            builtOrder.remove(index)
        }
        built[index] = page
        builtOrder.addLast(index)
        while (builtOrder.size > BuiltCacheMaxEntries) {
            built.remove(builtOrder.removeFirst())
        }
    }
}

/**
 * 저장된 페이지 시작 지점이 속한 범위를 가진 섹션.
 *
 * 스캔이 아니라 이진 탐색: 복원된 페이지는 요청 시점에 만들어지므로 이 함수는 한 번 열 때가
 * 아니라 페이지를 읽을 때마다 실행되며, 500개 섹션짜리 책을 스캔하면 페이지를 넘길 때마다
 * 티가 났을 것이다.
 *
 * @receiver 콘텐츠 섹션들, 오름차순이며 서로 겹치지 않음.
 * @param offset 페이지가 시작하는 절대 문서 오프셋.
 * @return [offset]과 같거나 그보다 앞에서 시작하는 마지막 섹션; [offset]이 그것들 모두보다
 * 앞이면 첫 번째 섹션.
 */
private fun List<ReaderSection>.sectionOwning(offset: Long): ReaderSection {
    var lo = 0
    var hi = lastIndex
    var result = this[0]
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val candidate = this[mid]
        if (candidate.range.start <= offset) {
            result = candidate
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return result
}

private fun IntArray.firstIndexGreaterThan(value: Int): Int {
    var lo = 0
    var hi = lastIndex
    var result = lastIndex
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid] > value) {
            result = mid
            hi = mid - 1
        } else {
            lo = mid + 1
        }
    }
    return result
}

/**
 * 한 섹션의 페이지 시작 지점들과 그 출처. [isMeasured]는 호출자가 breaker를 제공했음에도
 * 엔진이 추정 기하값을 써야 했을 때 false이며, 이는 저장소가 그 추정치를 측정된 레이아웃 키
 * 아래 쓰는 것을 막아준다.
 *
 * @property offsets 섹션 안 모든 페이지 시작 지점의 절대 문서 오프셋.
 * @property isMeasured 실제 [ReaderPageBreaker]가 시작 지점들을 만들었는지, 아니면 섹션이
 * 정확히 비어 있어 측정이 필요 없었는지.
 */
internal data class SectionPageStarts(
    val offsets: LongArray,
    val isMeasured: Boolean,
)

/**
 * 즉시 페이지네이션과 저장용 시작 지점 생성이 공유하는 내부 페이지 범위와 출처.
 *
 * @property ranges 읽는 순서대로의 절대 페이지 범위들.
 * @property isMeasured 실제 breaker가 [ranges]를 만들었는지, 아니면 보여줄 콘텐츠가 없어
 * 필요 없었는지.
 */
private data class SectionPageRanges(
    val ranges: List<TextRange>,
    val isMeasured: Boolean,
)

/**
 * 한 페이지의 추정 기하값.
 *
 * @property widthUnitsPerLine 한 줄에 들어가는 글리프 너비, em의 1/100 단위.
 * @property linesPerPage 한 페이지에 들어가는 줄 수.
 */
private data class PageLayout(
    val widthUnitsPerLine: Int,
    val linesPerPage: Int,
)

/**
 * em의 1/100 단위로 표현한 글리프 폭; 전각(CJK) 글리프는 정확히 1em이다.
 *
 * ponytail: 보정된 상수; 실제 텍스트 측정만이 폰트에 정확히 맞는 채움을 준다.
 */
private const val WideGlyphUnits = 100

/**
 * 비례폭 반각 글리프의 폭 — 절반 em이 아니라: 렌더링된 리더(산세리프 라틴 텍스트, 393 sp
 * 패널, 18 sp 텍스트)에서 측정하면 ~0.44em이다.
 *
 * 줄 모델이 렌더러처럼 공백에서 줄바꿈하므로 이것은 추가 여유 없는 순수한 폭이다; 렌더러가
 * 다음 줄로 밀어낼 자리에 모델이 단어가 들어간다고 절대 주장하지 않도록 약간 비관적으로
 * 유지된다.
 */
private const val NarrowGlyphUnits = 45

/** 이 함수가 실제로 배치하는 가장 긴 챕터. 이를 넘으면 추정치가 대신하며, 부정확하지만 상한은 있다. */
private const val MaxMeasuredContentLengthChars = 200_000

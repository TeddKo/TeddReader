package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import com.tedd.teddreader.core.common.model.layoutKey

/**
 * pager가 [currentPage] 주변에 준비해 두는 페이지들: 뒤로 둘, 앞으로 셋.
 *
 * 정의를 하나로 둔 이유는 세 가지 서로 다른 관심사가 이 창(window)이 같은 범위여야 한다는 데 의존하기
 * 때문이다 — pager가 마운트하는 슬롯, 페이지가 발행되기 전에 블록 스타일링을 미리 데워두는 섹션, 화면에
 * 있는 페이지들을 위해 가져오는 이미지. 이 중 하나가 자기만의 리터럴 범위를 썼다면, 반경이 바뀔 때 나머지가
 * 조용히 뒤처졌을 것이다.
 *
 * 의도적으로 clamp하지 않는다: 책 시작 부근의 호출자는 음수 절반을 받아 자신의 목록에 대고 필터링하며,
 * 이는 이 함수가 문서 길이에 대한 어떤 개념도 갖지 않도록 해준다.
 *
 * @param currentPage 리더가 현재 있는 페이지.
 * @return 준비해 둘 페이지의 양 끝을 포함하는 범위.
 */
internal fun pagerMountWindow(currentPage: Int): IntRange = currentPage - 2..currentPage + 3

/**
 * 문서에 대해 보여줄 목차이며, 형식이 실제로 제공할 수 있는 것에 따라 달라진다.
 *
 * visual 문서는 표제가 없으므로 아웃라인은 페이지당 항목 하나이며, 모든 항목이 [ReaderLocation.PdfPage]다.
 * 이는 그런 아웃라인이 만들어내는 유일한 위치 종류이며, text-offset 조회로는 이를 해석할 수 없다 —
 * 그래서 리더는 어떤 위치로 이동할 때 이를 위한 전용 분기를 둔다. 아무 데도 해석되지 않는 아웃라인 항목은
 * 조용히 아무 일도 하지 않는 탭이 되는데, 이는 리더 자체의 불변 조건이 금지하는 일이다.
 *
 * 내비게이션을 가진 EPUB은 그것을, 책이 선언한 레벨과 함께 보여준다. 그것이 없으면 섹션 자체가 아웃라인이
 * 되므로 문서는 항상 하나를 갖는다.
 *
 * @param format 문서의 형식, 아직 알려지지 않았다면 null.
 * @param readerDocument 파싱된 문서로, 그 navigation과 sections가 항목이 된다. null이면 텍스트 형식에
 * 대해 빈 아웃라인을 낳는다.
 * @param totalPages 아웃라인이 다뤄야 할 페이지 수로, visual 케이스에서만 쓰인다.
 * @return 읽는 순서로 정렬된 아웃라인 항목들.
 */
internal fun readerOutlineItems(
    format: DocumentFormat?,
    readerDocument: ReaderDocument?,
    totalPages: Int,
): List<ReaderOutlineItem> {
    if (format?.isVisualPageFormat() == true) {
        return (0 until totalPages).map { page ->
            ReaderOutlineItem(
                title = "Page ${page + 1}",
                location = ReaderLocation.PdfPage(page),
            )
        }
    }
    val navigationItems = readerDocument?.navigation?.items.orEmpty()
    if (format == DocumentFormat.EPUB && navigationItems.isNotEmpty()) {
        return navigationItems.map { item ->
            ReaderOutlineItem(
                title = item.title,
                location = ReaderLocation.EpubOffset(item.spineIndex, item.offset),
                level = item.level,
            )
        }
    }
    val sections = readerDocument?.sections.orEmpty()
    return sections.map { section ->
        ReaderOutlineItem(
            title = section.title ?: "Section ${section.index + 1}",
            location = when (format) {
                DocumentFormat.EPUB -> ReaderLocation.EpubOffset(section.index, 0)
                else -> ReaderLocation.TextOffset(section.range.start)
            },
            level = 1,
        )
    }
}

/**
 * pane의 측정 보고에 대해 리더가 해야 할 일.
 *
 * boolean이 아니라 세 가지 결과로 둔 이유는, 측정값을 기록하는 것과 그에 따라 행동하는 것이 별개의
 * 결정이기 때문이다: 가운데 케이스는 정확히, 재측정 비용을 치르지 않고도 보고를 믿을 수 있게 해주기 위해
 * 존재한다.
 */
internal enum class PaneReportOutcome {
    /** 보고가 이미 보유 중인 측정값을 그대로 나타낸다; 아무것도 바뀌지 않는다. */
    Ignore,

    /** 측정값은 기록하되 책을 다시 레이아웃하지는 않는다 — 이미 있는 페이지들이 그것을 대신한다. */
    RecordOnly,

    /** 진짜로 새로운 측정값이다: 기록하고 책을 다시 레이아웃한다. */
    RecordAndReload,
}

/**
 * pane의 측정 보고가 무엇을 의미하는지 결정한다. 어느 인스턴스가 이를 실어 왔는지가 아니라 그 측정값이
 * *나타내는 것*을 비교한다.
 *
 * 보고하는 pane은 페이지를 넘길 때마다 다른 composition 슬롯으로 옮겨가고, page effect는 애니메이션되는
 * 동안 페이지를 두 번 compose할 수도 있다. 이런 새 인스턴스들을 새로운 측정값으로 취급했다면 넘길 때마다
 * 문서 전체를 다시 레이아웃하게 됐을 것이며, 그래서 첫 번째 케이스는 그 대신 style과 픽셀 박스를
 * 비교한다.
 *
 * [RecordOnly] 케이스는 채택된 레이아웃의 확인이다. 문서를 열면 어떤 pane도 아직 보고하기 전에 저장된
 * 레이아웃의 viewport와 그것이 측정된 style을 채택하는데, 그 시점에는 breaker 자체가 아직 없다. pane의
 * 첫 실제 보고는 같은 물리적 화면이므로 거의 항상 같은 sp 크기이며, 그래서 그 크기 아래 이미 캐시된
 * 페이지들이 이를 대신하고 다시 로드해봐야 같은 것을 또 물어보는 셈이 될 뿐이다.
 *
 * @param reportedStyle pane이 측정에 사용한 style.
 * @param reportedSizePx pane의 실제 측정된 픽셀 박스.
 * @param reportedViewportSp 같은 박스를 sp 단위로 나타낸 것으로, 페이지 나누기와 저장된 페이지 레이아웃이
 * 기준으로 삼는 단위다.
 * @param currentBreakerStyle 보유 중인 측정값이 만들어진 style. 한 번도 보유되거나 채택된 적이 없으면
 * null.
 * @param currentBreakerSizePx 보유 중인 측정값의 픽셀 박스. 아직 어떤 pane도 보고하지 않았으면 null.
 * @param hasBreaker 실제 page breaker를 보유하고 있는지 여부 — 저장된 레이아웃만 채택된 상태라면 false.
 * @param currentViewportSp 현재 적용 중인 sp viewport로, 채택된 레이아웃이 기록한 값이다.
 * @return 호출자가 취해야 할 세 가지 동작 중 무엇인지.
 */
internal fun paneReportOutcome(
    reportedStyle: ReaderStyle,
    reportedSizePx: ViewportSize,
    reportedViewportSp: ViewportSize,
    currentBreakerStyle: ReaderStyle?,
    currentBreakerSizePx: ViewportSize?,
    hasBreaker: Boolean,
    currentViewportSp: ViewportSize,
): PaneReportOutcome {
    val sameStyle = currentBreakerStyle?.layoutKey() == reportedStyle.layoutKey()
    if (sameStyle && currentBreakerSizePx == reportedSizePx) return PaneReportOutcome.Ignore
    if (!hasBreaker && sameStyle && currentViewportSp == reportedViewportSp) return PaneReportOutcome.RecordOnly
    return PaneReportOutcome.RecordAndReload
}

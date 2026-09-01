package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PaginatedDocument
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderSpanStyle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet

internal data class ReaderPageUiContext(
    val pageIndex: PageIndex,
    val documentUri: String?,
    val isPdfMode: Boolean,
    val paginated: PaginatedDocument,
    val embeddedImages: Map<String, ByteArray>,
    val embeddedFontFiles: Map<String, String>,
    val failedEmbeddedImageHrefs: Set<String>,
    val failedEmbeddedFontHrefs: Set<String>,
)

internal data class ReaderPageFacingUi(
    val previous: ReaderPageUi?,
    val current: ReaderPageUi,
    val next: ReaderPageUi?,
    val slots: ImmutableList<ReaderPageUi>,
)

internal fun currentReaderPageUi(context: ReaderPageUiContext): ReaderPageUi =
    readerPageUi(context.pageIndex.current, context) ?: ReaderPageUi(
        page = context.pageIndex.current,
        isPdf = context.isPdfMode,
        documentUri = context.documentUri,
    )

internal fun readerPageFacingUi(context: ReaderPageUiContext): ReaderPageFacingUi {
    val currentPage = context.pageIndex.current
    return ReaderPageFacingUi(
        previous = readerPageUi(currentPage - 1, context),
        current = currentReaderPageUi(context),
        next = readerPageUi(currentPage + 1, context),
        slots = pagerMountWindow(currentPage).mapNotNull { page -> readerPageUi(page, context) }.toImmutableList(),
    )
}

internal fun readerPageUi(page: Int, context: ReaderPageUiContext): ReaderPageUi? {
    if (context.pageIndex.total <= 0 || page !in 0 until context.pageIndex.total) return null
    val pageWindow = context.paginated.pageWindows.getOrNull(page)
    val blocks = pageWindow?.blocks.orEmpty()
    val resources = pageResourceSnapshot(blocks, context)
    return ReaderPageUi(
        page = page,
        text = if (context.isPdfMode) "" else pageWindow?.text.orEmpty(),
        isPdf = context.isPdfMode,
        documentUri = context.documentUri,
        textRange = pageWindow?.textRange,
        blocks = blocks.toImmutableList(),
        embeddedImages = resources.embeddedImages,
        embeddedFontFiles = resources.embeddedFontFiles,
        failedEmbeddedImageHrefs = resources.failedEmbeddedImageHrefs,
        failedEmbeddedFontHrefs = resources.failedEmbeddedFontHrefs,
        chapterTitle = context.paginated.chapterTitleAt(page),
        chapterPageIndex = context.paginated.chapterPageIndexAt(page),
        isSectionTail = context.paginated.isSectionTail(page),
    )
}

/**
 * 한 페이지의 블록들이 참조하는 내장 이미지 바이트, 내장 폰트 파일 경로, 그리고 두 종류 모두에 대한 로드
 * 실패 href — [readerPageUi]가 문서 컨테이너 안에 있는 리소스에 대해 [ReaderPageUi]에 넘겨야 할 모든 것.
 *
 * 이는 매퍼가 같은 목록을 다섯 번 독립적으로 순회하던 기존 방식 대신, 페이지의 블록(그리고 폰트의 경우
 * 각 블록의 span)을 한 번의 선형 순회로 훑어서 이미지와 폰트 참조를 해석하도록 하기 위해 존재한다. 실제
 * 블록과 인라인 span을 담은 페이지에서는 이 반복 순회가 매퍼의 주요 비용이었다; 이를 한 번의 순회로
 * 접으면 눈에 보이는 계약은 그대로 유지하면서 각 블록과 span을 한 번씩만 건드리게 된다.
 *
 * 네 개의 멤버는 [ReaderPageUi] 자체 필드가 가지는 정확한 의미를 그대로 유지하며, 단일 순회가 보존해야
 * 하는 미묘한 두 가지도 포함한다:
 * - [embeddedFontFiles]와 [failedEmbeddedFontHrefs]는 [ReaderPageUiContext.embeddedFontFiles]와
 *   [ReaderPageUiContext.failedEmbeddedFontHrefs]라는 두 개의 독립된 입력에 대해 각각 해석되므로, 어떤
 *   폰트가 로드된 파일도 있고 실패로 표시도 되어 있으면 같은 href가 둘 다에 나타날 수 있다; 이 스냅샷은
 *   둘 중 하나가 다른 하나를 절대 억누르지 않도록 한다.
 * - [embeddedFontFiles]와 [failedEmbeddedFontHrefs] 안에서는 블록 자체의 style 폰트가 그 블록의 span
 *   폰트들보다 먼저 제공되고, 블록은 목록 순서대로 방문되므로, 처음 나타난 순서는 예전의
 *   `listOfNotNull(block.style?.fontHref) + block.spans…` flat-map이 만들어내던 순서와 일치한다.
 *
 * 삽입 순서는 끝까지 보존된다: 내부 map과 set은 [LinkedHashMap]과 [LinkedHashSet]이므로, 결과를
 * 순회하면 처음 참조된 순서가 그대로 재현된다. 페이지에 대응하는 리소스가 없으면 해당 멤버는 공유되는 빈
 * persistent 컬렉션이 되므로, 이미지도 폰트도 없는 페이지는 중간 builder조차 전혀 할당하지 않는다.
 *
 * @param blocks 호출자가 이미 (비어 있을 수도 있는) 목록으로 해석해 둔, 한 페이지의 블록들.
 * @param context [ReaderPageUiContext.embeddedImages], [ReaderPageUiContext.embeddedFontFiles],
 *   [ReaderPageUiContext.failedEmbeddedImageHrefs], [ReaderPageUiContext.failedEmbeddedFontHrefs]를
 *   가진, 참조를 조회하는 데 쓰이는 매핑 컨텍스트.
 * @return 해석이 끝난 페이지별 리소스 스냅샷; [blocks]가 컨텍스트가 아는 것을 아무것도 참조하지 않으면
 *   모든 멤버가 비어 있다.
 */
private fun pageResourceSnapshot(
    blocks: List<ReaderBlock>,
    context: ReaderPageUiContext,
): PageResourceSnapshot {
    if (blocks.isEmpty()) return PageResourceSnapshot.Empty

    val builder = PageResourceSnapshotBuilder(context)
    for (block in blocks) {
        block.imageHref?.let(builder::offerImage)
        block.style?.fontHref?.let(builder::offerFont)
        for (span in block.spans) {
            span.styleDelta?.fontHref?.let(builder::offerFont)
        }
    }
    return builder.build()
}

/**
 * [pageResourceSnapshot]이 페이지의 블록들을 순회하면서 채우는 단일 페이지 리소스 수집기로, 제공되는 각
 * href를 네 개의 [PageResourceSnapshot] 컬렉션 중 알맞은 것에 넣는다.
 *
 * 지연 할당되는 [LinkedHashMap]/[LinkedHashSet] 백엔드 덕분에, 알려진 이미지나 폰트를 참조하지 않는
 * 페이지는 builder 컬렉션을 전혀 할당하지 않으며, 첫 참조가 href의 위치를 결정하므로 완성된 결과를
 * 순회하면 참조가 도착한 순서가 그대로 재현된다.
 *
 * @property context 제공된 href가 해석되는 대상 조회 소스. 각 map과 set이 무엇을 의미하는지는
 *   [ReaderPageUiContext]의 멤버를 참고한다.
 */
private class PageResourceSnapshotBuilder(private val context: ReaderPageUiContext) {
    /** 처음 참조된 순서로 정렬된, 로드된 이미지 바이트. 일치하는 첫 href가 나온 뒤에만 할당된다. */
    private var embeddedImages: LinkedHashMap<String, ByteArray>? = null

    /** 처음 참조된 순서로 정렬된, 로드된 폰트 파일. 일치하는 첫 href가 나온 뒤에만 할당된다. */
    private var embeddedFonts: LinkedHashMap<String, String>? = null

    /** 처음 참조된 순서로 정렬된, 실패한 이미지 href. 첫 실패 href가 나온 뒤에만 할당된다. */
    private var failedImages: LinkedHashSet<String>? = null

    /** 처음 참조된 순서로 정렬된, 실패한 폰트 href. 첫 실패 href가 나온 뒤에만 할당된다. */
    private var failedFonts: LinkedHashSet<String>? = null

    /**
     * 블록 이미지 [href] 하나를 컨텍스트에 대해 해석한다: 문서가 그 바이트를 가지고 있으면 기록하고,
     * importer가 실패로 표시했으면 실패로 마크한다 — 이 둘은 독립적이므로, href가 값도 가지고 있고
     * 실패로도 표시되어 있으면 양쪽 모두에 남는다.
     *
     * @param href 블록의 [ReaderBlock.imageHref].
     */
    fun offerImage(href: String) {
        context.embeddedImages[href]?.let { bytes ->
            val images = embeddedImages ?: LinkedHashMap<String, ByteArray>().also { embeddedImages = it }
            images[href] = bytes
        }
        if (href in context.failedEmbeddedImageHrefs) {
            val failed = failedImages ?: LinkedHashSet<String>().also { failedImages = it }
            failed.add(href)
        }
    }

    /**
     * 블록 style이나 span의 폰트 [href] 하나를 컨텍스트에 대해 해석한다: 문서가 그 디스크 파일을 가지고
     * 있으면 기록하고, importer가 실패로 표시했으면 실패로 마크한다 — 이 둘은 독립적이므로, href가 값도
     * 가지고 있고 실패로도 표시되어 있으면 양쪽 모두에 남는다.
     *
     * @param href [ReaderBlockStyle.fontHref] 또는 [ReaderSpanStyle.fontHref].
     */
    fun offerFont(href: String) {
        context.embeddedFontFiles[href]?.let { file ->
            val fonts = embeddedFonts ?: LinkedHashMap<String, String>().also { embeddedFonts = it }
            fonts[href] = file
        }
        if (href in context.failedEmbeddedFontHrefs) {
            val failed = failedFonts ?: LinkedHashSet<String>().also { failedFonts = it }
            failed.add(href)
        }
    }

    /**
     * 지금까지 제공된 모든 것의 스냅샷. 그 종류로 기록된 것이 하나도 없으면 해당 멤버는 공유되는 빈
     * persistent 컬렉션이 된다.
     *
     * @return 불변인, 페이지별 리소스 스냅샷.
     */
    fun build(): PageResourceSnapshot = PageResourceSnapshot(
        embeddedImages = embeddedImages?.toImmutableMap() ?: persistentMapOf(),
        embeddedFontFiles = embeddedFonts?.toImmutableMap() ?: persistentMapOf(),
        failedEmbeddedImageHrefs = failedImages?.toImmutableSet() ?: persistentSetOf(),
        failedEmbeddedFontHrefs = failedFonts?.toImmutableSet() ?: persistentSetOf(),
    )
}

/**
 * 페이지 하나가 자신의 [ReaderPageUi]에 기여하는 네 가지 리소스 컬렉션으로, [pageResourceSnapshot]이
 * 함께 만들어내어 매퍼가 페이지의 블록을 한 번만 순회하며 이들을 해석하도록 한다.
 *
 * @property embeddedImages 문서가 실제로 가지고 있는 모든 블록 이미지 href의 이미지 바이트, 블록 순서대로.
 * @property embeddedFontFiles 문서가 실제로 가지고 있는 모든 블록 style/span 폰트 href의 디스크 폰트
 *   파일, 처음 참조된 순서대로이며 각 블록의 style 폰트가 그 span 폰트들보다 앞선다.
 * @property failedEmbeddedImageHrefs importer가 로드 실패로 표시한 블록 이미지 href.
 * @property failedEmbeddedFontHrefs importer가 로드 실패로 표시한 블록 style·span 폰트 href;
 *   [embeddedFontFiles]와 독립적이므로 href가 양쪽 모두에 나타날 수 있다.
 */
private data class PageResourceSnapshot(
    val embeddedImages: ImmutableMap<String, ByteArray>,
    val embeddedFontFiles: ImmutableMap<String, String>,
    val failedEmbeddedImageHrefs: ImmutableSet<String>,
    val failedEmbeddedFontHrefs: ImmutableSet<String>,
) {
    /** 알려진 이미지나 폰트 참조가 없는 페이지가 재사용하는 공유 스냅샷인 [Empty]를 담는다. */
    companion object {
        /** 검사할 블록이 없는 페이지에서 재사용되는, 할당이 전혀 없는 결과. */
        val Empty = PageResourceSnapshot(
            embeddedImages = persistentMapOf(),
            embeddedFontFiles = persistentMapOf(),
            failedEmbeddedImageHrefs = persistentSetOf(),
            failedEmbeddedFontHrefs = persistentSetOf(),
        )
    }
}

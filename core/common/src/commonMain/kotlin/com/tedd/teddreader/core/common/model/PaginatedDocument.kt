package com.tedd.teddreader.core.common.model

/**
 * 한 번의 페이지 나누기로 알아낸 문서의 모든 정보, 즉 페이지 윈도와 그 페이지가 배치된 섹션을 하나로 묶어 리더의 도메인 질의가 사용하는 단일 값으로 만든다.
 *
 * 두 목록은 같은 상태를 유지해야 한다. 페이지의 장 제목과 [isSectionTail] 플래그는 모두 [pageWindows]를 [sections]와 대조해 얻으며, 다른 페이지 나누기에서 나온 섹션 목록으로 페이지를 읽으면 한 측정의 페이지에 다른 측정의 장 제목이 붙는다. 이 타입은 그 쌍에 이름만 붙이며 짝을 강제하지 않는다. [withPages]와 [withSections]가 의도적으로 따로 존재하는 이유는 다시 불러오기가 새 섹션 목록보다 새 페이지 목록을 먼저 게시하기 때문이다(`ReaderViewModel.reloadPages`가 반영하는 두 할당 순서 참고). 따라서 이 타입은 주 호출자가 의존하는 찢어진 중간 상태를 허용해야 한다.
 *
 * @property pageWindows 이 페이지 나누기에서 생성한 페이지이다. **인덱스 접근이 캐시에 부수 효과를 주는 지연 목록일 수 있다**(`DocumentRepositoryImpl`의 `RestoredPageWindows`: 실제 기기에서 204/528-섹션 책의 모든 섹션을 미리 배치했을 때 6.4s/13.0s가 걸렸다). 이 목록을 순회·비교·출력하지 않는다. 이 타입의 모든 질의는 실제 필요한 `O(log n)` 또는 일정 개수의 인덱스만 접근한다. 바로 이 때문에 이 타입에는 `data` 수정자가 없다. 생성된 `equals`/`hashCode`/`toString`은 전체 목록을 순회하며 캐시를 변경하므로 느리고 `equals`로서 순수하지도 않다.
 * @property sections 이 페이지가 배치된 섹션으로, 오름차순이며 서로 겹치지 않는다.
 */
class PaginatedDocument(
    val pageWindows: List<PageWindow> = emptyList(),
    val sections: List<ReaderSection> = emptyList(),
) {
    /**
     * 이 페이지 나누기가 지금까지 게시한 페이지 수이다.
     */
    val pageCount: Int get() = pageWindows.size

    /**
     * [sections]는 유지하고 새 페이지 목록을 담은 이 문서이다.
     *
     * @param pageWindows 보관할 새 페이지 목록.
     * @return [pageWindows]와 이 인스턴스의 [sections]를 담은 새 인스턴스.
     */
    fun withPages(pageWindows: List<PageWindow>): PaginatedDocument = PaginatedDocument(pageWindows, sections)

    /**
     * [pageWindows]는 유지하고 새 섹션 목록을 담은 이 문서이다.
     *
     * @param sections 보관할 새 섹션 목록.
     * @return 이 인스턴스의 [pageWindows]와 [sections]를 담은 새 인스턴스.
     */
    fun withSections(sections: List<ReaderSection>): PaginatedDocument = PaginatedDocument(pageWindows, sections)

    /**
     * [PageWindow.textRange]가 [offset]을 포함하는 페이지의 인덱스이다.
     *
     * 페이지 윈도는 인덱스로 처음 읽을 때만 생성되고 해당 섹션의 블록도 그때 디코딩된다([pageWindows] 자체 문서 참고). 따라서 [pageWindows]를 순서대로 순회하면 한 오프셋의 위치만 찾기 위해 일치까지의 모든 페이지를 생성하게 된다. 대신 페이지 자체의 시작 오프셋을 이진 검색하면 실제 검색하는 `O(log n)`개 페이지만 접근한다. 이 타입이 지켜야 할 핵심 불변식은 이 질문에 답하려고 [pageWindows]를 처음부터 끝까지 순회하는 호출자를 절대 추가하지 않는 것이다.
     *
     * @param offset 절대 문서 오프셋.
     * @return [offset]을 포함하는 페이지. 어떤 페이지 범위에도 포함되지 않으면 `null`이다. 검색이 도달한 페이지에 아직 [PageWindow.textRange]가 없는 경우도 포함하며, 이때 계속 검색하지 않고 종료한다.
     */
    fun pageOf(offset: Long): Int? {
        var low = 0
        var high = pageWindows.lastIndex
        while (low <= high) {
            val mid = (low + high) / 2
            val range = pageWindows[mid].textRange ?: return null
            when {
                offset < range.start -> high = mid - 1
                offset >= range.end -> low = mid + 1
                else -> return mid
            }
        }
        return null
    }

    /**
     * [absoluteOffsetOf]를 통해 해석한 [location]을 표시하는 페이지의 인덱스이다.
     *
     * @param location 독서 위치. [ReaderLocation.PdfPage]는 [absoluteOffsetOf]가 절대 오프셋을 제공할 수 없으므로 여기서 항상 `null`로 해석된다. 시각 문서를 읽는 호출자는 이 메서드에 의존하지 않고 해당 경우를 위한 자체 분기를 유지한다.
     * @return [location]을 표시하는 페이지이며 [sections]와 [pageWindows]에서 해석할 수 없으면 `null`.
     */
    fun pageOf(location: ReaderLocation): Int? =
        absoluteOffsetOf(location, sections)?.let { offset -> pageOf(offset) }

    /**
     * 페이지를 다시 나눠도 유지되는 독서 위치로 나타낸 [page]의 시작점이다.
     *
     * @param page 페이지 인덱스.
     * @return [page] 자체 [PageWindow.location]이며 [page]에 아직 윈도가 없으면 `null`.
     */
    fun locationAt(page: Int): ReaderLocation? = pageWindows.getOrNull(page)?.location

    /**
     * 절대 범위가 [offset]을 포함하는 섹션이다. [sections]가 오름차순이며 겹치지 않으므로 오프셋 이전 또는 같은 위치에서 시작하는 마지막 섹션이다.
     *
     * @param offset 절대 문서 오프셋.
     * @return 포함하는 섹션. [sections]가 비었거나 [offset] 이전 또는 같은 위치에서 시작하는 섹션이 없을 때만 `null`.
     */
    fun sectionContaining(offset: Long): ReaderSection? =
        sectionPositionAtOrBefore(offset)?.let(sections::get)

    /**
     * 절대 범위가 [offset]을 포함하는 섹션의 인덱스로, [sectionContaining]과 같은 포함 관계를 해석한다.
     *
     * @param offset 절대 문서 오프셋.
     * @return 포함하는 섹션의 인덱스이며 [sectionContaining]이 `null`을 반환할 때만 `null`.
     */
    fun sectionIndexContaining(offset: Long): Int? = sectionContaining(offset)?.index

    /**
     * [pages]의 페이지가 닿는 모든 섹션 인덱스이다. 이미 윈도가 있는 페이지마다 한 번 조회하며, 백그라운드 준비가 다음에 디코딩할 섹션의 블록을 결정할 때 사용한다.
     *
     * @param pages 확인할 페이지. 마운트 윈도를 요청하는 호출자가 알려진 마지막 페이지를 정당하게 넘어갈 수 있으므로 [pageWindows] 끝을 지난 인덱스는 오류로 처리하지 않고 건너뛴다.
     * @return 해당 페이지가 시작하는 서로 다른 섹션 인덱스.
     */
    fun sectionIndexesFor(pages: IntRange): Set<Int> =
        pages.mapNotNull { page -> pageWindows.getOrNull(page)?.textRange?.start }
            .mapNotNull(::sectionIndexContaining)
            .toSet()

    /**
     * [page]가 화면에 있을 때 표시할 장 제목이다.
     *
     * 표지 페이지에는 제목을 표시하지 않는다. 자체 제목이 없는 섹션은 "제목 없음"이 아니라 이전 또는 같은 위치에서 제목이 있는 마지막 섹션의 제목을 상속한다. 따라서 장의 첫 페이지뿐 아니라 모든 페이지에 장 제목이 고정된다. 이 상속 때문에 단순한 `sectionContaining(start)?.title`이 아니다. 시작이 가장 큰 섹션을 선택하기 *전에* 제목이 있는 섹션만 필터링하므로, 제목 있는 두 섹션 사이의 제목 없는 섹션도 `null`이 아니라 앞 제목을 표시한다.
     *
     * @param page 페이지 인덱스.
     * @return 상속한 장 제목. [page]에 윈도가 없거나, 표지 이미지로 시작하거나, 아직 [PageWindow.textRange]가 없거나, 이전 또는 같은 위치의 어떤 섹션에도 제목이 없으면 `null`.
     */
    fun chapterTitleAt(page: Int): String? {
        val pageWindow = pageWindows.getOrNull(page) ?: return null
        if (pageWindow.blocks.any { block -> block.kind == ReaderBlockKind.COVER_IMAGE }) return null
        val start = pageWindow.textRange?.start ?: return null
        var sectionIndex = sectionPositionAtOrBefore(start) ?: return null
        while (sectionIndex >= 0) {
            sections[sectionIndex].title?.let { return it }
            sectionIndex--
        }
        return null
    }

    /**
     * [page]를 포함하는 장 안에서 0부터 시작하는 페이지 위치와 페이지 수이다.
     *
     * 장 경계는 [chapterTitleAt]을 따른다. 제목 없는 섹션은 이전 제목 있는 섹션에 속하고 다음 제목 있는 섹션이 다음 장을 시작한다. 페이지 조회는 로그 시간으로 유지되므로 지연으로 생성되는 [pageWindows] 목록을 순회하지 않는다.
     */
    fun chapterPageIndexAt(page: Int): PageIndex? {
        val pageWindow = pageWindows.getOrNull(page) ?: return null
        if (pageWindow.blocks.any { block -> block.kind == ReaderBlockKind.COVER_IMAGE }) return null
        val start = pageWindow.textRange?.start ?: return null
        val chapterSection = titledSectionPositionAtOrBefore(sectionPositionAtOrBefore(start) ?: return null)
            ?: return null
        val chapterStartPage = pageOf(sections[chapterSection].range.start) ?: return null
        val nextChapterSection = (chapterSection + 1..sections.lastIndex)
            .firstOrNull { index -> sections[index].title != null }
        val chapterEndPage = nextChapterSection
            ?.let { index -> pageOf(sections[index].range.start) }
            ?: pageCount
        return PageIndex(current = page - chapterStartPage, total = chapterEndPage - chapterStartPage)
    }

    private fun titledSectionPositionAtOrBefore(sectionPosition: Int): Int? {
        var position = sectionPosition
        while (position >= 0) {
            if (sections[position].title != null) return position
            position--
        }
        return null
    }

    private fun sectionPositionAtOrBefore(offset: Long): Int? {
        var low = 0
        var high = sections.lastIndex
        var result: Int? = null
        while (low <= high) {
            val mid = (low + high) / 2
            when {
                sections[mid].range.start <= offset -> {
                    result = mid
                    low = mid + 1
                }
                else -> high = mid - 1
            }
        }
        return result
    }

    /**
     * [page]가 해당 섹션의 마지막 페이지인지 나타낸다.
     *
     * 렌더링된 텍스트가 시트를 얼마나 채웠는지가 아니라 페이지 나누기가 이 페이지 자체 경계를 놓은 위치가 `true` 여부를 결정한다. 실제로 측정하지 않은 모든 페이지를 추정 페이지 나누기가 덜 채우므로, 그렇지 않으면 새 설치에서 실제 측정이 대체하기 전까지 모든 페이지가 짧아 보인다.
     *
     * @param page 페이지 인덱스.
     * @return [page] 자체 [PageWindow.textRange]의 끝이 이를 포함하는 섹션 끝과 정확히 같으면 `true`이다. [page]에 윈도가 없거나, 아직 [PageWindow.textRange]가 없거나, 섹션 끝에 도달하지 않으면 `false`.
     */
    fun isSectionTail(page: Int): Boolean {
        val range = pageWindows.getOrNull(page)?.textRange ?: return false
        return sectionContaining(range.start)?.range?.end == range.end
    }

    /**
     * [pages]의 페이지에 있는 블록이 참조하는 내장 이미지 href로, 미리 가져오기 도구가 해당 윈도를 가져오도록 저장소에 요청하는 값이다.
     *
     * @param pages 확인할 페이지. [pageWindows] 밖의 인덱스는 오류로 처리하지 않고 건너뛴다.
     * @return 해당 페이지의 블록이 참조하는 서로 다른 이미지 href.
     */
    fun imageHrefsIn(pages: IntRange): Set<String> =
        pages.filter { page -> page in pageWindows.indices }
            .flatMap { page -> pageWindows[page].blocks.mapNotNull { block -> block.imageHref } }
            .toSet()

    /**
     * [pages]의 페이지가 블록 수준과 인라인 CSS에서 참조하는 내장 글꼴 href이다.
     *
     * @param pages 확인할 페이지. [pageWindows] 밖의 인덱스는 오류로 처리하지 않고 건너뛴다.
     * @return 해당 페이지가 참조하는 서로 다른 글꼴 href.
     */
    fun fontHrefsIn(pages: IntRange): Set<String> =
        pages.asSequence()
            .filter { page -> page in pageWindows.indices }
            .flatMap { page ->
                pageWindows[page].blocks.asSequence().flatMap { block ->
                    sequenceOf(block.style?.fontHref)
                        .plus(block.spans.asSequence().map { span -> span.styleDelta?.fontHref })
                }
            }
            .filterNotNull()
            .toSet()
}

/**
 * [sections]를 기준으로 해석한 [location]의 절대 문서 오프셋이다.
 *
 * 호출자는 아직 [PaginatedDocument] 자체에 속하지 않은 섹션 목록을 기준으로 위치를 해석해야 할 수 있으므로 멤버가 아닌 최상위 함수이다. 문서를 여는 `ReaderViewModel`은 페이지 나누기가 존재하기 전에 새로 불러온 문서의 섹션을 기준으로 재개 오프셋을 해석한다.
 *
 * @param location 독서 위치.
 * @param sections [ReaderLocation.EpubOffset]을 해석할 섹션 목록.
 * @return [location]이 나타내는 오프셋. [ReaderLocation.TextOffset]이면 자체 값, [ReaderLocation.EpubOffset]이면 스파인 항목의 시작과 자체 오프셋의 합이다. [sections]에 해당 항목이 아직 없으면 스파인 항목의 시작을 0으로 간주하여 점진적 가져오기 중에도 EPUB 위치를 일찍 해석할 수 있다. 문자 오프셋이 아니라 페이지 번호를 나타내는 [ReaderLocation.PdfPage]이면 `null`이다.
 */
fun absoluteOffsetOf(location: ReaderLocation, sections: List<ReaderSection>): Long? =
    when (location) {
        is ReaderLocation.TextOffset -> location.offset
        is ReaderLocation.EpubOffset -> {
            val sectionStart = sections
                .firstOrNull { section -> section.index == location.spineIndex }
                ?.range
                ?.start
                ?: 0L
            sectionStart + location.offset
        }
        is ReaderLocation.PdfPage -> null
    }

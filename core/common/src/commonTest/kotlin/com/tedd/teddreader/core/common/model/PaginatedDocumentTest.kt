package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PaginatedDocument]가 답하는 모든 도메인 질의를 고정한다. 이 타입이 생기기 전 `ReaderViewModel` 자체의 `pageOfOffset`, `absoluteOffset`, `sectionContaining`, `sectionIndexContaining`, `pageUi` 내부 장 제목/`isSectionTail` 파생과 정확히 같은 의미 및 경계 사례를 재현한다. 예를 들어 이진 검색은 블록을 아직 디코딩하지 않은 페이지에 도달하는 순간 포기하고, 장 제목은 페이지 시작점이 실제로 속한 섹션이 아니라 제목이 있는 마지막 섹션에서 상속한다.
 *
 * 또한 이 타입 선언의 B4 결정을 고정한다. [PaginatedDocument]는 `data class`가 아니라 일반 `class`이므로 지연으로 생성되는 [PaginatedDocument.pageWindows] 목록을 아무것도 순회하지 않는다. 누군가 "도움이 되도록" `data`를 다시 추가하는 순간 [equalContentInstancesAreNotEqual]이 실패한다.
 */
class PaginatedDocumentTest {

    /**
     * 질의가 실제로 읽는 값만 담은 페이지 윈도를 만든다. 20개 테스트가 공유하는 하나의 픽스처에 전제를 숨기지 않고 각 경우가 자체 조건을 드러내게 한다.
     *
     * @param range 페이지의 텍스트 범위. `null`은 블록을 아직 디코딩하지 않은 페이지를 뜻하며, 오프셋 검색은 이를 건너뛰지 않고 그 상태에서 멈춰야 한다.
     * @param blocks 페이지의 블록. 표지 블록이 있으면 [PaginatedDocument.chapterTitleAt]은 `null`을 반환하고, [PaginatedDocument.imageHrefsIn]은 이미지 블록을 수집한다.
     * @return 이 질의들이 의존하지 않는 페이지 인덱스, 위치, 텍스트에는 대체 문구를 사용한 윈도.
     */
    private fun page(
        range: TextRange? = null,
        blocks: List<ReaderBlock> = emptyList(),
    ): PageWindow = PageWindow(
        pageIndex = PageIndex(current = 0, total = 1),
        location = ReaderLocation.TextOffset(0L),
        text = "",
        textRange = range,
        blocks = blocks,
    )

    /**
     * `start until end`를 차지하는 섹션을 만든다. 이 질의들이 읽는 섹션 정보는 이것뿐이다.
     *
     * @param index 섹션 조회가 반환하는 섹션 자체 인덱스.
     * @param start 섹션의 첫 절대 오프셋.
     * @param end 섹션의 배타적 끝 오프셋. 페이지가 정확히 여기서 끝나면 섹션 끝이다.
     * @param title 섹션 제목. EPUB에서 `null`은 실제 상태이며 장 제목을 상속해야 하는 경우다.
     * @return 이 질의들이 텍스트를 읽지 않으므로 빈 텍스트를 가진 섹션.
     */
    private fun section(
        index: Int,
        start: Long,
        end: Long,
        title: String? = null,
    ): ReaderSection = ReaderSection(index = index, text = "", range = TextRange(start, end), title = title)

    /**
     * 페이지가 없는 문서는 어떤 오프셋에도 페이지가 없으며 0번 페이지를 반환하지 않는다.
     */
    @Test
    fun pageOfOffsetOnAnEmptyPageListIsNull() {
        val document = PaginatedDocument()

        assertNull(document.pageOf(0L))
    }

    /**
     * 페이지는 첫 오프셋부터 다음 페이지의 첫 오프셋 직전까지 범위를 소유한다.
     */
    @Test
    fun pageOfOffsetFindsAPageAtItsFirstAndLastOffset() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = TextRange(10, 20)),
                page(range = TextRange(20, 30)),
            ),
        )

        assertEquals(0, document.pageOf(0L))
        assertEquals(0, document.pageOf(9L))
        assertEquals(2, document.pageOf(20L))
        assertEquals(2, document.pageOf(29L))
    }

    /**
     * 현재까지 측정한 모든 범위를 지난 오프셋에는 아직 페이지가 없다. 부분 측정된 책의 끝에서 재개하지 않도록 마지막 페이지와 다른 답을 반환한다.
     */
    @Test
    fun pageOfOffsetPastTheEndIsNull() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = TextRange(10, 20)),
            ),
        )

        assertNull(document.pageOf(20L))
    }

    /**
     * 검색은 디코딩하지 않은 페이지를 건너뛰지 않고 포기한다.
     *
     * 세 페이지의 이진 검색은 인덱스 1을 먼저 방문한다. 오프셋 25는 실제로 페이지 2에 속하므로 `null` 범위를 "계속 진행"으로 처리하면 이를 찾는다. 하지만 그러면 신뢰할 수 없는 페이지 목록으로 답하게 된다. 이 경계 사례를 포함해 `ReaderViewModel.pageOfOffset`을 정확히 재현하는 것이 기존 구현을 대체하지 않고 이 타입으로 옮긴 이유다.
     */
    @Test
    fun pageOfOffsetStopsAtAPageWithNoTextRangeInsteadOfSkippingIt() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = null),
                page(range = TextRange(20, 30)),
            ),
        )

        assertNull(document.pageOf(25L))
    }

    /**
     * 일반 텍스트 오프셋은 이미 절대값이므로 섹션 문맥이 필요 없다.
     */
    @Test
    fun pageOfLocationResolvesATextOffsetThroughAbsoluteOffsetOf() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = TextRange(10, 20)),
                page(range = TextRange(20, 30)),
            ),
        )

        assertEquals(1, document.pageOf(ReaderLocation.TextOffset(15L)))
    }

    /**
     * EPUB 오프셋은 스파인 항목 기준 상대값이므로 섹션 목록이 해당 항목의 시작 위치를 제공한 뒤에야 의미가 생긴다. 이 때문에 페이지와 섹션을 하나의 타입에 함께 전달한다.
     */
    @Test
    fun pageOfLocationResolvesAnEpubOffsetAgainstItsSpineItemsSectionStart() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = TextRange(10, 20)),
                page(range = TextRange(20, 30)),
            ),
            sections = listOf(section(index = 0, start = 0, end = 30, title = "Spine 0")),
        )

        assertEquals(1, document.pageOf(ReaderLocation.EpubOffset(spineIndex = 0, offset = 15L)))
    }

    /**
     * PDF 페이지 번호는 텍스트 오프셋이 아니므로 이 질의는 답할 수 없다. 호출자가 위치 유형에 따라 분기해야 하며, 그래서 `ReaderViewModel.moveToLocation`은 모든 이동을 여기로 보내지 않고 명시적 `PdfPage` 분기를 유지한다.
     */
    @Test
    fun pageOfLocationOnAPdfPageIsAlwaysNull() {
        val document = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(0, 10))),
            sections = listOf(section(index = 0, start = 0, end = 10)),
        )

        assertNull(document.pageOf(ReaderLocation.PdfPage(0)))
    }

    /**
     * 페이지에 저장할 위치는 범위에서 파생하지 않고 윈도 자체 위치를 사용한다.
     */
    @Test
    fun locationAtReturnsTheWindowsOwnLocationWhenThePageExists() {
        val location = ReaderLocation.TextOffset(42L)
        val document = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(0, 10)).copy(location = location)),
        )

        assertEquals(location, document.locationAt(0))
    }

    /**
     * 측정이 아직 도달하지 않은 페이지를 요청하면 대체 위치가 아니라 값이 없다고 답한다.
     */
    @Test
    fun locationAtIsNullWhenThePageHasNoWindow() {
        val document = PaginatedDocument(pageWindows = listOf(page(range = TextRange(0, 10))))

        assertNull(document.locationAt(5))
    }

    /**
     * 섹션은 자체 시작부터 다음 섹션 전까지 모든 오프셋을 소유하므로, 조회는 범위에 포함되는 첫 섹션이 아니라 해당 오프셋 이전 또는 같은 위치에서 시작하는 마지막 섹션을 선택한다.
     */
    @Test
    fun sectionContainingFindsTheLastSectionStartingAtOrBeforeTheOffset() {
        val sectionA = section(index = 0, start = 10, end = 20, title = "Intro")
        val sectionB = section(index = 1, start = 20, end = 40, title = "Chapter 1")
        val document = PaginatedDocument(sections = listOf(sectionA, sectionB))

        assertEquals(sectionA, document.sectionContaining(15L))
        assertEquals(sectionB, document.sectionContaining(20L))
        assertEquals(sectionB, document.sectionContaining(35L))
    }

    /**
     * 간격도 해당 오프셋 이전 또는 같은 위치에서 시작하는 마지막 섹션에 속하며, 기존 조회와 일치한다.
     */
    @Test
    fun sectionContainingUsesTheLastSectionStartEvenAcrossAGap() {
        val intro = section(index = 0, start = 10, end = 20, title = "Intro")
        val chapter = section(index = 1, start = 30, end = 40, title = "Chapter 1")
        val document = PaginatedDocument(sections = listOf(intro, chapter))

        assertEquals(intro, document.sectionContaining(25L))
        assertEquals(0, document.sectionIndexContaining(25L))
    }

    /**
     * 앞 내용은 오프셋 0 이후에 시작할 수 있으며, 그보다 앞선 오프셋은 어떤 섹션에도 속하지 않는다.
     */
    @Test
    fun sectionContainingIsNullForAnOffsetBeforeTheFirstSection() {
        val document = PaginatedDocument(
            sections = listOf(section(index = 0, start = 10, end = 20, title = "Intro")),
        )

        assertNull(document.sectionContaining(5L))
        assertNull(document.sectionIndexContaining(5L))
    }

    /**
     * 인덱스 형태도 같은 조회를 사용하므로 어느 섹션을 선택할지 두 결과가 다를 수 없다.
     */
    @Test
    fun sectionIndexContainingMatchesSectionContainingsIndex() {
        val document = PaginatedDocument(
            sections = listOf(
                section(index = 0, start = 10, end = 20, title = "Intro"),
                section(index = 1, start = 20, end = 40, title = "Chapter 1"),
            ),
        )

        assertEquals(1, document.sectionIndexContaining(35L))
    }

    /**
     * 페이지 범위는 측정된 범위로 제한하므로 독서 위치 주변의 고정 크기 윈도를 요청해도 가져오기 중간에 실패하지 않는다. 이 덕분에 블록 준비가 페이지 범위로 요청할 수 있다.
     */
    @Test
    fun sectionIndexesForIgnoresPagesPastTheEndOfTheKnownList() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = TextRange(10, 20)),
            ),
            sections = listOf(
                section(index = 0, start = 0, end = 10),
                section(index = 1, start = 10, end = 20),
            ),
        )

        assertEquals(setOf(0, 1), document.sectionIndexesFor(0..5))
    }

    @Test
    fun fontHrefsInCollectsBlockAndInlineFontReferencesOnce() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(
                    range = TextRange(0, 10),
                    blocks = listOf(
                        ReaderBlock(
                            kind = ReaderBlockKind.PARAGRAPH,
                            range = TextRange(0, 10),
                            style = ReaderBlockStyle(fontHref = "fonts/body.otf"),
                            spans = listOf(
                                ReaderSpan(
                                    range = TextRange(0, 4),
                                    styleDelta = ReaderSpanStyle(fontHref = "fonts/inline.otf"),
                                ),
                                ReaderSpan(
                                    range = TextRange(5, 9),
                                    styleDelta = ReaderSpanStyle(fontHref = "fonts/body.otf"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(setOf("fonts/body.otf", "fonts/inline.otf"), document.fontHrefsIn(0..0))
    }

    /**
     * 요청 범위에 측정된 것이 없으면 준비할 것도 없으며 빈 집합을 요청하지 않는다.
     */
    @Test
    fun sectionIndexesForIsEmptyWhenTheWholeRangeIsPastTheEnd() {
        val document = PaginatedDocument(pageWindows = listOf(page(range = TextRange(0, 10))))

        assertEquals(emptySet(), document.sectionIndexesFor(5..10))
    }

    /**
     * 표지는 어떤 장에도 속하지 않으므로, 표지 오프셋이 우연히 어떤 섹션에 들어가더라도 리더 상단 표시줄에는 해당 제목을 표시하지 않고 비워 둔다.
     */
    @Test
    fun chapterTitleAtIsNullForACoverPage() {
        val coverBlock = ReaderBlock(
            kind = ReaderBlockKind.COVER_IMAGE,
            range = TextRange(0, 1),
            imageHref = "cover.jpg",
        )
        val document = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(0, 10), blocks = listOf(coverBlock))),
            sections = listOf(section(index = 0, start = 0, end = 100, title = "Book")),
        )

        assertNull(document.chapterTitleAt(0))
    }

    /**
     * 제목 없는 섹션은 앞의 마지막 제목을 상속한다. 따라서 장 첫 스파인 항목뿐 아니라 장 전체에서 상단 표시줄에 장 이름을 유지한다.
     *
     * 첫 단언이 핵심 함정이다. 페이지 시작점이 실제로 속한 섹션에는 자체 제목이 없으므로 단순한 `sectionContaining(start)?.title`은 여기서 `null`을 반환한다.
     */
    @Test
    fun chapterTitleAtIsInheritedByAnUntitledSectionFromTheLastTitledSectionBeforeIt() {
        val preface = section(index = 0, start = 0, end = 50, title = "Preface")
        val untitledChapter = section(index = 1, start = 50, end = 100, title = null)
        val document = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(60, 70))),
            sections = listOf(preface, untitledChapter),
        )

        assertNull(document.sectionContaining(60L)?.title)
        assertEquals("Preface", document.chapterTitleAt(0))
    }
    /**
     * 제목 상속은 위치가 지정된 섹션에서 뒤로 이동하여 제목이 있는 섹션을 찾는다.
     */
    @Test
    fun chapterTitleAtKeepsTheLastTitleAcrossUntitledSectionsAndGaps() {
        val preface = section(index = 0, start = 0, end = 50, title = "Preface")
        val untitledBridge = section(index = 1, start = 80, end = 100, title = null)
        val document = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(90, 95))),
            sections = listOf(preface, untitledBridge),
        )

        assertEquals("Preface", document.chapterTitleAt(0))
    }

    /**
     * 상속은 제목을 만들어내지 않는다. 모든 섹션이 제목 없는 책은 아무것도 표시하지 않는다.
     */
    @Test
    fun chapterTitleAtIsNullWhenNoSectionAtOrBeforeThePageHasEverCarriedATitle() {
        val document = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(0, 10))),
            sections = listOf(section(index = 0, start = 0, end = 10, title = null)),
        )

        assertNull(document.chapterTitleAt(0))
    }

    /**
     * 페이지 번호는 전체 문서 시작이 아니라 장 시작부터 센다.
     */
    @Test
    fun chapterPageIndexAtCountsWithinTheCurrentChapter() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = TextRange(10, 20)),
                page(range = TextRange(20, 30)),
                page(range = TextRange(30, 40)),
                page(range = TextRange(40, 50)),
            ),
            sections = listOf(
                section(index = 0, start = 0, end = 20, title = "One"),
                section(index = 1, start = 20, end = 50, title = "Two"),
            ),
        )

        assertEquals(PageIndex(current = 0, total = 3), document.chapterPageIndexAt(2))
        assertEquals(PageIndex(current = 2, total = 3), document.chapterPageIndexAt(4))
    }

    /**
     * 자체 섹션 끝과 범위 끝이 만나는 페이지만 끝이다. 리더는 이를 통해 장을 끝내는 페이지와 단순히 내부에 있는 페이지를 구별한다.
     */
    @Test
    fun isSectionTailIsTrueExactlyWhenThePagesEndMatchesItsSectionsEnd() {
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10)),
                page(range = TextRange(10, 20)),
            ),
            sections = listOf(section(index = 0, start = 0, end = 20)),
        )

        assertFalse(document.isSectionTail(0))
        assertTrue(document.isSectionTail(1))
    }

    /**
     * 측정된 범위가 없거나 윈도 자체가 없으면 추정하지 않고 `false`를 반환한다.
     */
    @Test
    fun isSectionTailIsFalseWithoutATextRangeOrAWindow() {
        val document = PaginatedDocument(pageWindows = listOf(page(range = null)))

        assertFalse(document.isSectionTail(0))
        assertFalse(document.isSectionTail(5))
    }

    /**
     * 이미지를 페이지 범위별로 수집하고 중복을 제거하므로, 독서 위치 주변을 미리 가져오기할 때 각 파일을 한 번만 요청하고 독자가 가까이 있지 않은 페이지는 요청하지 않는다.
     */
    @Test
    fun imageHrefsInCollectsDistinctHrefsFromTheGivenPagesOnly() {
        val firstPageImage = ReaderBlock(kind = ReaderBlockKind.IMAGE, range = TextRange(0, 1), imageHref = "a.png")
        val textBlock = ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(1, 5))
        val secondPageImage = ReaderBlock(kind = ReaderBlockKind.IMAGE, range = TextRange(0, 1), imageHref = "b.png")
        val document = PaginatedDocument(
            pageWindows = listOf(
                page(range = TextRange(0, 10), blocks = listOf(firstPageImage, textBlock)),
                page(range = TextRange(10, 20), blocks = listOf(secondPageImage)),
            ),
        )

        assertEquals(setOf("a.png", "b.png"), document.imageHrefsIn(0..1))
        assertEquals(setOf("a.png"), document.imageHrefsIn(0..0))
        assertEquals(emptySet(), document.imageHrefsIn(5..10))
    }

    /**
     * 페이지를 다시 측정해도 섹션 목록은 유지한다. 페이지 재분할은 페이지 경계를 바꾸지만 책이 나뉜 방식은 바꾸지 않기 때문이다.
     */
    @Test
    fun withPagesReplacesOnlyThePageList() {
        val originalSections = listOf(section(index = 0, start = 0, end = 10, title = "Only"))
        val original = PaginatedDocument(pageWindows = listOf(page(range = TextRange(0, 10))), sections = originalSections)
        val freshPages = listOf(page(range = TextRange(0, 20)))

        val updated = original.withPages(freshPages)

        assertEquals(freshPages, updated.pageWindows)
        assertEquals(originalSections, updated.sections)
    }

    /**
     * 반대 경우로, 가져오기가 책의 더 뒤쪽을 파싱하면 지금까지 측정된 페이지는 그대로 유지하면서 섹션을 교체한다. 두 갱신은 별도로 도착하므로 계속 분리한다.
     */
    @Test
    fun withSectionsReplacesOnlyTheSectionList() {
        val originalPages = listOf(page(range = TextRange(0, 10)))
        val original = PaginatedDocument(
            pageWindows = originalPages,
            sections = listOf(section(index = 0, start = 0, end = 10, title = "Old")),
        )
        val freshSections = listOf(section(index = 0, start = 0, end = 20, title = "New"))

        val updated = original.withSections(freshSections)

        assertEquals(originalPages, updated.pageWindows)
        assertEquals(freshSections, updated.sections)
    }

    /**
     * 내용이 같은 인스턴스도 의도적으로 동등하지 않으며, 이 타입을 일반 `class`로 유지하게 하는 보호 장치이다.
     *
     * 첫 두 단언은 두 인스턴스가 실제로 내용이 같은 목록을 지녔음을 확인한다. 마지막 단언은 그래도 두 값이 같지 않음을 보여준다. [PaginatedDocument]에는 생성된 `equals`도 직접 작성한 `equals`도 없기 때문이다. `data`를 다시 추가하면 첫 두 단언은 만족하지만 세 번째가 실패한다. 또한 모든 `==`가 인덱스 접근 시 페이지를 생성하고 캐시하는 페이지 목록을 순회하게 되어 비교가 저렴하지도 부수 효과에서 자유롭지도 않게 된다.
     */
    @Test
    fun equalContentInstancesAreNotEqual() {
        val first = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(0, 10))),
            sections = listOf(section(index = 0, start = 0, end = 10, title = "Same")),
        )
        val second = PaginatedDocument(
            pageWindows = listOf(page(range = TextRange(0, 10))),
            sections = listOf(section(index = 0, start = 0, end = 10, title = "Same")),
        )

        assertEquals(first.pageWindows, second.pageWindows)
        assertEquals(first.sections, second.sections)
        assertNotEquals(first, second)
    }
}

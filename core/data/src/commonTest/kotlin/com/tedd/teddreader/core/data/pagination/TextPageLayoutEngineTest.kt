package com.tedd.teddreader.core.data.pagination

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderPageBreaker
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderSpan
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.blocksIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TextPageLayoutEngine]의 페이지네이션 계약을 고정한다: 한 페이지는 두 섹션에 걸쳐 있을 수 없고, 표지 이미지는
 * 자신만의 페이지를 가지며, 추정치는 넓은 글리프·줄바꿈된 단어·인라인 이미지를 위해 렌더러와 동일한 방식으로
 * 실제 공간을 확보하고, 실측값이 있으면 추정치 대신 그대로 사용되며, [TextPageLayoutEngine.reconstruct]는
 * 저장된 페이지 시작 지점으로부터 [TextPageLayoutEngine.paginate]가 만들어낸 것과 정확히 같은 페이지 목록을
 * 재구성한다 — 호출자의 블록 조회가 절대 범위로 답하든 섹션 상대 범위로 답하든 상관없이. 이 테스트들 중 몇몇은
 * 실제로 이런 보장 중 하나가 프로덕션에서 깨졌기 때문에 존재한다: 잘려나간 인라인 이미지, 페이지 중간에 붕
 * 뜬 챕터 제목, 혹은 저장소가 섹션의 블록을 섹션 상대로 넘기기 시작하면서 이중 리베이스로 손상된 블록들.
 */
class TextPageLayoutEngineTest {
    /** 테스트 대상 페이지네이션 엔진. */
    private val engine = TextPageLayoutEngine()

    /**
     * [TextPageLayoutEngine.defaultSectionBlocks]는 [ReaderPageBreaker]에 넘기기 전에 섹션의 블록들을 그
     * 섹션 자신의 시작 지점으로 리베이스한다 — 이 테스트는 그 리베이스가 블록의 바깥 범위뿐 아니라 블록 자신의
     * [ReaderSpan]들에까지 미치는지 고정한다. 섹션이 0번이 아닐 때(여기서는 표지 이미지가 섹션 0을 차지하므로,
     * 본문 섹션의 절대 시작 지점은 0이 아니라 2다) 이 확인이 성립해야 한다.
     */
    @Test
    fun pageBreakerBlockShiftAlsoShiftsInlineSpanRangesAfterCover() {
        val document = ReaderDocument(
            id = DocumentId("epub-span-shift"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                ReaderSection(1, text = "plain bold text", range = TextRange(2, 17), title = "Body"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg"),
                ReaderBlock(
                    kind = ReaderBlockKind.PARAGRAPH,
                    range = TextRange(2, 17),
                    spans = listOf(
                        com.tedd.teddreader.core.common.model.ReaderSpan(
                            range = TextRange(8, 12),
                            style = com.tedd.teddreader.core.common.model.ReaderInlineStyle.BOLD,
                        ),
                    ),
                ),
            ),
        )
        var measuredBlocks: List<ReaderBlock> = emptyList()
        val breaker = ReaderPageBreaker { _, blocks ->
            measuredBlocks = blocks
            intArrayOf(0)
        }

        engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = breaker,
        )

        assertEquals(TextRange(0, 15), measuredBlocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.range)
        assertEquals(
            TextRange(6, 10),
            measuredBlocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.spans.single().range,
        )
    }

    /**
     * 페이지네이션에 사용할 실측값이 없을 때 — 여기서는 [ReaderPageBreaker]가 주어지지 않았기 때문이며,
     * [TextPageLayoutEngine]의 측정 상한을 넘긴 섹션이 폴백하는 것과 같은 추정치 — 키가 큰 인라인 이미지는
     * 실제 세로 공간을 확보해야 한다. 예전에는 이미지가 텍스트 안에 갖고 있는 개행 문자 한 글자로만 취급되어,
     * 추정치는 이미지 주위로 한 페이지 분량의 텍스트를 통째로 채웠고 렌더러는 이미지가 넘친 만큼 페이지에서
     * 잘라냈다. 이 테스트는 이미지가 자기 페이지에서 개행 하나가 아니라 실제 높이를 차지하게 되었으므로, 이미지
     * 페이지가 순수 텍스트 페이지보다 훨씬 적은 텍스트를 담게 됨을 고정한다. 픽스처의 이미지는 세로가 가로의
     * 두 배인 세로형 삽화판(`imageAspectRatio = 0.5f`)으로, 텍스트와 한 페이지를 공유하기에는 일부러 너무
     * 크게 만들었다.
     */
    @Test
    fun estimatedPaginationReservesRoomForATallInlineImage() {
        val paragraph = "가".repeat(400)
        val text = "$paragraph\n \n$paragraph"
        val imageOffset = paragraph.length + 1
        val document = ReaderDocument(
            id = DocumentId("epub-tall-image"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(ReaderSection(0, text = text, range = TextRange(0, text.length.toLong()))),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, paragraph.length.toLong())),
                ReaderBlock(
                    kind = ReaderBlockKind.IMAGE,
                    range = TextRange(imageOffset.toLong(), imageOffset + 1L),
                    imageHref = "Images/plate.jpg",
                    imageAspectRatio = 0.5f,
                ),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1.5f),
            viewportSize = ViewportSize(widthPx = 400, heightPx = 600),
            pageBreaker = null,
        )

        val imagePage = pages.single { page ->
            val range = page.textRange ?: return@single false
            imageOffset >= range.start && imageOffset < range.end
        }
        val imagePageLength = (imagePage.textRange!!.end - imagePage.textRange!!.start).toInt()
        val textOnlyPageLength = pages
            .filter { it !== imagePage }
            .maxOf { (it.textRange!!.end - it.textRange!!.start).toInt() }

        assertTrue(
            imagePageLength < textOnlyPageLength,
            "image page held $imagePageLength chars, text page held $textOnlyPageLength",
        )
    }

    /**
     * 표지 이미지 섹션은, [ReaderPageBreaker]가 주어지지 않아 페이지네이션이 추정치로 폴백하는 경우에도,
     * [ReaderLocation.EpubOffset] `(0, 0)`에서 독자적인 페이지 0이 된다.
     */
    @Test
    fun coverSectionGetsItsOwnFirstPageWithoutPageBreaker() {
        val document = ReaderDocument(
            id = DocumentId("epub-cover"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                ReaderSection(1, text = "Body text", range = TextRange(2, 11), title = "Body"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg"),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(2, 11)),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
        )

        assertEquals(ReaderBlockKind.COVER_IMAGE, pages.first().blocks.single().kind)
        assertEquals(ReaderLocation.EpubOffset(0, 0), pages.first().location)
        assertTrue(pages[1].text.startsWith("Body"))
    }

    /**
     * [coverSectionGetsItsOwnFirstPageWithoutPageBreaker]와 같은 "표지는 독자적인 페이지" 분할을, 이번에는
     * 실제 [ReaderPageBreaker]로 확인한다: 표지는 여전히 독자적인 페이지 0이 되고, 첫 실측 콘텐츠 페이지는
     * 문서의 절대 오프셋이 아니라 본문 섹션 자신의 상대 오프셋 0([ReaderLocation.EpubOffset] `(1, 0)`)에서
     * 시작한다.
     */
    @Test
    fun coverSectionGetsItsOwnFirstPageWithPageBreaker() {
        val document = ReaderDocument(
            id = DocumentId("epub-cover-breaker"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                ReaderSection(1, text = "abcdef", range = TextRange(2, 8), title = "Body"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg"),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(2, 8)),
            ),
            navigation = null,
        )
        val breaker = ReaderPageBreaker { measured, _ ->
            intArrayOf(0, 3)
        }

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = breaker,
        )

        assertEquals(listOf(" ", "abc", "def"), pages.map { it.text })
        assertEquals(ReaderLocation.EpubOffset(1, 0), pages[1].location)
    }

    /**
     * 기본 확인: 한 페이지에 담기에 너무 긴 평문 TXT 문서는 둘 이상으로 나뉘고, 첫 페이지는 0번이며, 그
     * 위치는 [ReaderLocation.TextOffset] `0`이다.
     */
    @Test
    fun paginatesTextByViewportAndStyle() {
        val document = ReaderDocument(
            id = DocumentId("txt-1"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = "a".repeat(200), range = TextRange(0, 200)),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
        )

        assertTrue(pages.size > 1)
        assertEquals(0, pages.first().pageIndex.current)
        assertEquals(ReaderLocation.TextOffset(0), pages.first().location)
    }

    /**
     * 인접한 페이지들은 틈이 벌어지거나 겹치면 안 된다: 각 페이지의 [PageWindow.textRange]는 정확히 다음
     * 페이지가 시작하는 지점에서 끝나고, 모든 페이지의 텍스트를 순서대로 이어 붙이면 섹션의 원본 텍스트를
     * 그대로 재현해야 한다.
     */
    @Test
    fun paginatedPagesKeepTextContinuous() {
        val text = "abcdefghijklmnopqrstuvwxyz".repeat(20)
        val document = ReaderDocument(
            id = DocumentId("txt-continuous"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 18f),
            viewportSize = ViewportSize(widthPx = 80, heightPx = 100),
        )

        pages.zipWithNext().forEach { (current, next) ->
            assertEquals(current.textRange?.end, next.textRange?.start)
        }
        assertEquals(text, pages.joinToString(separator = "") { page -> page.text })
    }
    /**
     * 줄 높이가 1배일 때, 폭이 넓은(CJK) 글리프로 채운 페이지는 같은 뷰포트라도 폭이 좁은 라틴 문자로 채운
     * 페이지보다 훨씬 적은 글자만 담는다: 추정치는 전각 글리프에게 라틴 문자가 받는 부분적인 advance가 아니라
     * 한 줄 너비 예산 전체를 부과해야 한다. 그래서 100x100 뷰포트, 20sp에서 전각 글리프 페이지의 상한을
     * 25자로 확인한다.
     */
    @Test
    fun usesConservativePageSizeForWideGlyphs() {
        val text = "가".repeat(100)
        val document = ReaderDocument(
            id = DocumentId("txt-wide"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
        )

        assertTrue(pages.first().text.length <= 25)
    }

    /**
     * 폭이 좁은 글리프의 추정 advance는 반 em이 아니라 실측된 em의 비례 분수(~0.45em)다. 480px/20sp/줄 높이
     * 1에서 한 줄은 24em을 담을 수 있다: 전각 글리프는 한 em 전체를, 반각 글리프는 0.45em을 차지하므로, 첫
     * 페이지는 5줄에 걸쳐 반각(영문) 문자 265개와 전각(한글) 문자 120개를 담는다 — 예전의 반 em 예산이었다면
     * 영문은 240자에서 멈췄을 것이다.
     */
    @Test
    fun narrowGlyphsUseTheProportionalAdvanceInsteadOfHalfAnEm() {
        val english = "a".repeat(400)
        val korean = "가".repeat(400)
        val style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f)
        val viewportSize = ViewportSize(widthPx = 480, heightPx = 100)

        fun paginate(text: String) = engine.paginate(
            document = ReaderDocument(
                id = DocumentId(text.first().toString()),
                format = DocumentFormat.TXT,
                title = "Book",
                sections = listOf(
                    ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
                ),
            ),
            style = style,
            viewportSize = viewportSize,
        )

        val englishPages = paginate(english)
        val koreanPages = paginate(korean)

        assertEquals(265, englishPages.first().text.length)
        assertEquals(120, koreanPages.first().text.length)
        assertEquals(english, englishPages.joinToString(separator = "") { page -> page.text })
        assertEquals(korean, koreanPages.joinToString(separator = "") { page -> page.text })
    }

    /**
     * 추정치는 렌더러와 같은 방식으로 공백에서 줄을 바꾼다: 폭이 좁은 글리프 기준 한 줄에 10단위일 때,
     * "aaaa bbbb cccc ..."는 단어 중간이 아니라 "aaaa bbbb" 뒤에서 줄이 바뀐다. 그래서 추정치는 줄이 나뉠
     * 뻔했던 단어를 붙잡아 두었다가 다음 줄을 그 단어로 시작해야 하며, 어떤 페이지도 줄바꿈이 남긴 선행
     * 공백으로 시작해서는 안 된다.
     */
    @Test
    fun estimatedLinesWrapAtSpacesLikeTheRendererDoes() {
        val text = List(20) { index -> ('a' + index % 26).toString().repeat(4) }.joinToString(" ")
        val document = ReaderDocument(
            id = DocumentId("txt-wrap"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 90, heightPx = 40),
        )

        assertEquals("aaaa bbbb ", pages.first().text.take(10))
        assertTrue(pages.all { page -> page.text.isEmpty() || !page.text.startsWith(" ") })
        assertEquals(text, pages.joinToString(separator = "") { page -> page.text })
    }

    /**
     * 실제 [ReaderPageBreaker]는 그것이 보고하는 페이지 나눔 그대로 사용된다 — 리더 자신의 텍스트 레이아웃을
     * 대신하는 가짜가 150자마다 페이지 나눔을 보고하도록 여기서 모델링했다. 그래서 마지막을 뺀 모든 페이지는
     * 정확히 그 길이로 나오고, 모든 페이지를 다시 이어 붙이면 원본 텍스트를 손대지 않고 그대로 재현한다.
     */
    @Test
    fun measuredPageBreaksAreUsedVerbatim() {
        val text = "abcdefghij".repeat(60)
        val document = ReaderDocument(
            id = DocumentId("txt-measured"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )
        val renderedPageLength = 150
        val pageBreaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + renderedPageLength - 1) / renderedPageLength) { page ->
                page * renderedPageLength
            }
        }

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = pageBreaker,
        )

        assertTrue(pages.dropLast(1).all { it.text.length == renderedPageLength })
        assertEquals(text, pages.joinToString(separator = "") { page -> page.text })
    }

    /**
     * 실측값의 페이지 시작 지점은 어떤 산술적 줄 수와도 맞아떨어질 필요가 없다 — 여기서 가짜 [ReaderPageBreaker]는
     * 137자마다 나눔을 보고하도록, 실제 레이아웃이라면 절대 만들어내지 않을 만큼 일부러 간격을 벌려서, 실측값이
     * 존재하는 한 추정치는 전혀 관여하지 않음을 증명한다: 8sp/1배 스타일로 요청하든 40sp/3배 스타일로 요청하든
     * 페이지네이션은 정확히 같은 페이지 범위를 내놓는다.
     */
    @Test
    fun measuredPagesIgnoreTheEstimatedLineCountAcrossFontSizes() {
        val text = "abcdefghij".repeat(60)
        val document = ReaderDocument(
            id = DocumentId("txt-measured-ignores-style"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )
        val renderedPageLength = 137
        val pageBreaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + renderedPageLength - 1) / renderedPageLength) { page ->
                page * renderedPageLength
            }
        }
        val viewportSize = ViewportSize(widthPx = 100, heightPx = 100)

        fun paginate(style: ReaderStyle) = engine.paginate(
            document = document,
            style = style,
            viewportSize = viewportSize,
            pageBreaker = pageBreaker,
        )

        val smallFontPages = paginate(ReaderStyle(fontSizeSp = 8f, lineHeightMultiplier = 1f))
        val largeFontPages = paginate(ReaderStyle(fontSizeSp = 40f, lineHeightMultiplier = 3f))

        assertEquals(
            smallFontPages.map { it.textRange },
            largeFontPages.map { it.textRange },
        )
    }

    /**
     * [TextPageLayoutEngine]의 측정 상한(200,000자)을 넘는 섹션은 주어진 [ReaderPageBreaker]에 전혀 도달하지
     * 않는다 — 페이지네이션은 곧바로 추정치로 폴백하며, 그래도 텍스트 전체를 하나도 빠뜨리지 않고 커버한다.
     */
    @Test
    fun oversizedContentSkipsPageBreakerAndFallsBackToEstimatedRanges() {
        val text = "a".repeat(200_001)
        val document = ReaderDocument(
            id = DocumentId("txt-oversized-measured"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )
        var breakerCalled = false
        val pageBreaker = ReaderPageBreaker { _, _ ->
            breakerCalled = true
            intArrayOf(0)
        }

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 2_000, heightPx = 2_000),
            pageBreaker = pageBreaker,
        )

        assertFalse(breakerCalled)
        assertTrue(pages.isNotEmpty())
        assertEquals(text, pages.joinToString(separator = "") { page -> page.text })

        val starts = engine.pageStartsForSection(
            section = document.sections.single(),
            sectionBlocks = emptyList(),
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 2_000, heightPx = 2_000),
            pageBreaker = pageBreaker,
        )
        assertFalse(starts.isMeasured)
        assertTrue(starts.offsets.isNotEmpty())
    }

    /**
     * 명시적인 `\n`은 줄바꿈으로 감싼 줄과 똑같이 한 줄로 취급된다: 소스 텍스트의 실제 개행에서 나왔든
     * 줄바꿈에서 나왔든, 어떤 페이지도 자신의 추정 줄 수용량보다 많은 비어있지 않은 줄을 담아서는 안 된다.
     */
    @Test
    fun explicitLineBreaksDoNotOverflowPageLineCapacity() {
        val text = (1..20).joinToString(separator = "\n") { "x" }
        val document = ReaderDocument(
            id = DocumentId("txt-lines"),
            format = DocumentFormat.TXT,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = text, range = TextRange(0, text.length.toLong())),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
        )

        assertTrue(pages.first().text.lines().count { line -> line.isNotEmpty() } <= 5)
        assertEquals(text, pages.joinToString(separator = "") { page -> page.text })
    }

    /**
     * 뷰포트가 아무리 넉넉해도 챕터는 앞 챕터와 페이지를 공유하지 않는다 — [TextPageLayoutEngine]이 모든
     * 진입점을 이 규칙 위에 둔다: EPUB spine 항목 하나는 그 자체로 하나의 문서이고, 어떤 리딩 시스템도 둘을
     * 한 화면에 함께 띄우지 않는다. 책 전체를 하나의 긴 문자열처럼 페이지네이션하면 챕터 제목이 앞 챕터의
     * 마지막 페이지 중간에 놓이곤 했는데, 목차가 독자를 정확히 그 지점으로 점프시키는 바람에 문제가 됐다.
     * 여기서는 뷰포트를 일부러 측정만으로 책 전체가 한 화면에 들어갈 만큼 크게 잡았고, 그래도 챕터는 반드시
     * 서로 다른 페이지에 놓여야 한다.
     */
    @Test
    fun everyChapterStartsItsOwnPageSoItsHeadingSitsAtTheTop() {
        val first = "먼저 읽는 장의 본문"
        val second = "2화 기회\n뒤에 오는 장의 본문"
        val document = ReaderDocument(
            id = DocumentId("epub-chapter-breaks"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = first, range = TextRange(0, first.length.toLong()), title = "1화"),
                ReaderSection(
                    1,
                    text = second,
                    range = TextRange(first.length + 1L, first.length + 1L + second.length),
                    title = "2화 기회",
                ),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, first.length.toLong())),
                ReaderBlock(
                    kind = ReaderBlockKind.HEADING,
                    level = 1,
                    range = TextRange(first.length + 1L, first.length + 1L + 5),
                ),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f, lineHeightMultiplier = 1f),
            viewportSize = ViewportSize(widthPx = 4_000, heightPx = 4_000),
        )

        assertEquals(listOf(first, second), pages.map { it.text })
        val chapterPage = pages[1]
        assertEquals(ReaderLocation.EpubOffset(1, 0), chapterPage.location)
        assertTrue(chapterPage.text.startsWith("2화 기회"), "chapter page began with '${chapterPage.text.take(12)}'")
        assertEquals(ReaderBlockKind.HEADING, chapterPage.blocks.first().kind)
    }

    /**
     * 페이지의 [PageWindow.blocks]는 그 페이지의 [PageWindow.textRange]와 겹치는 블록만 정확히 담는다 —
     * 두 페이지의 경계에 걸쳐 있는 블록도 어느 한쪽에서 빠지지 않고 양쪽 페이지의 목록에 모두 나타난다.
     */
    @Test
    fun pageWindowsKeepOnlyIntersectingBlocks() {
        val text = "abcdefghij"
        val blocks = listOf(
            ReaderBlock(
                kind = ReaderBlockKind.HEADING,
                range = TextRange(0, 4),
            ),
            ReaderBlock(
                kind = ReaderBlockKind.QUOTE,
                range = TextRange(4, 8),
            ),
            ReaderBlock(
                kind = ReaderBlockKind.SEPARATOR,
                range = TextRange(8, 9),
            ),
        )
        val document = ReaderDocument(
            id = DocumentId("epub-blocks"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(ReaderSection(0, text = text, range = TextRange(0, text.length.toLong()))),
            blocks = blocks,
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = ReaderPageBreaker { _, _ -> intArrayOf(0, 5) },
        )

        assertEquals(listOf(blocks[0], blocks[1]), pages[0].blocks)
        assertEquals(listOf(blocks[1], blocks[2]), pages[1].blocks)
    }

    /**
     * [TextPageLayoutEngine.reconstruct]는 실측값이 만들어낸 절대 페이지 시작 지점만으로,
     * [TextPageLayoutEngine.paginate]가 준 것과 정확히 같은 페이지 목록을 재구성한다 — 저장된 레이아웃이
     * 실제로 견뎌내야 하는 형태의 책, 즉 이미지 한 장뿐인 표지 섹션 뒤에 평범한 챕터 두 개가 이어지는 책을
     * 대상으로 한다. 측정에 쓰인 가짜 [ReaderPageBreaker](측정 대상 섹션의 3자마다 페이지 나눔을 만들며,
     * 리더 자신의 텍스트 레이아웃을 대신한다)는 콘텐츠 페이지의 시작 지점만 만들어낸다. 표지 페이지는 애초에
     * 저장되지 않는다 — 측정 없이 항상 같은 방식으로 재구성될 뿐이다 — 그런데도
     * [TextPageLayoutEngine.reconstruct]는 여전히 이를 동일하게 재현해야 한다.
     */
    @Test
    fun reconstructFromStoredStartsMatchesMeasuredPaginateExactly() {
        val document = ReaderDocument(
            id = DocumentId("epub-reconstruct"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                ReaderSection(1, text = "abcdef", range = TextRange(2, 8), title = "Chapter 1"),
                ReaderSection(2, text = "ghijklmno", range = TextRange(9, 18), title = "Chapter 2"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg"),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(2, 8)),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(9, 18)),
            ),
        )
        val breaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + 2) / 3) { page -> page * 3 }
        }

        val measuredPages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = breaker,
        )
        val contentPageStarts = measuredPages.drop(1).map { it.textRange!!.start }.toLongArray()

        val reconstructedPages = engine.reconstruct(document, contentPageStarts)

        assertEquals(measuredPages, reconstructedPages)
    }

    /**
     * [TextPageLayoutEngine.reconstruct]는 실제로 읽힌 페이지가 속한 섹션의 블록만 디코딩한다. 표지 없이
     * 평범한 챕터 셋, 챕터당 한 페이지면 세 번째 페이지("Chapter 3", `contentPageStarts[2]`에 속함)를 읽는
     * 것이 그 사이의 "Chapter 2"까지 디코딩해서는 안 됨을 증명하기에 충분하다 — 모든 섹션을 즉시 디코딩하는
     * 버그는, 오직 자기 섹션만 필요로 하는 페이지에서는 올바른 동작과 구분이 안 될 것이다. `sectionBlocks`는
     * 이름으로 넘긴다. [TextPageLayoutEngine.reconstruct]가 후행 함수형 매개변수를 두 개 받기 때문에, 이름
     * 없는 람다는 이 테스트가 지켜보는 블록 조회가 아니라 `isSectionReady`에 바인딩될 것이다. 표지 판정은
     * 항상 섹션 0을 앞서 확인한다 — 이 시점에 다른 것은 아직 요청된 적이 없다 — 그래서 목록을 만드는 것만으로도
     * 어떤 페이지도 읽기 전에 이미 `{0}`을 디코딩한다.
     */
    @Test
    fun reconstructOnlyDecodesSectionsItsRequestedPagesTouch() {
        val document = ReaderDocument(
            id = DocumentId("epub-lazy-sections"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = "aaa", range = TextRange(0, 3), title = "Chapter 1"),
                ReaderSection(1, text = "bbb", range = TextRange(4, 7), title = "Chapter 2"),
                ReaderSection(2, text = "ccc", range = TextRange(8, 11), title = "Chapter 3"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 3)),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(4, 7)),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(8, 11)),
            ),
        )
        val contentPageStarts = longArrayOf(0L, 4L, 8L)
        val decodedSections = mutableSetOf<Int>()
        val windows = engine.reconstruct(
            document = document,
            contentPageStarts = contentPageStarts,
            sectionBlocks = { section ->
                decodedSections += section.index
                document.blocks.blocksIn(section.range.start, section.range.end)
            },
        )

        assertEquals(setOf(0), decodedSections, "constructing the list must not decode beyond cover detection")

        windows[2]
        assertEquals(setOf(0, 2), decodedSections, "chapter 2 was never asked for and must stay undecoded")
    }

    /**
     * 섹션 상대 블록 저장([DocumentRepositoryImpl.persistParsedDocument] 참고)은 각 섹션의 블록들을
     * [TextPageLayoutEngine.paginate]/[TextPageLayoutEngine.reconstruct]에 넘기기 전에 그 섹션 자신의
     * 시작 지점을 기준으로 읽히도록 이동시킨다 — 이 테스트와 뒤이은 세 테스트는 변경 이전 코드로는 반드시
     * 실패했어야 했던 바로 그 테스트들이다: `sectionPageRanges`는 자신의 `sectionBlocks` 인자가 항상
     * 절대값이라고 가정하고 스스로 리베이스했었는데, 그래서 이미 섹션 상대로 도착한 블록이 두 번째로
     * 리베이스되어 손상됐다.
     *
     * [TextPageLayoutEngine.paginate]는 `sectionBlocks` 조회가 어떤 형태로 답하든 상관없이 블록을 항상
     * 절대 문서 오프셋으로 돌려줘야 한다. 여기서 섹션 1의 절대 시작 지점은 0이 아니라 6이다: 표지 섹션은
     * 항상 0에서 시작하는데, 그곳은 잊혀진 리베이스 누락이 그래도 우연히 맞아 보일 수 있는 유일한 지점이므로,
     * 이 테스트는 일부러 버그를 드러낼 섹션을 다룬다. 페이지의 블록은 절대값을 유지해야 하는데,
     * `ReaderSemanticText`가 블록의 `range.start`에서 페이지 자신의 절대 `textRange.start`를 빼서
     * `page.text` 안에서 블록의 위치를 찾기 때문이다.
     */
    @Test
    fun paginateReturnsAbsoluteBlockRangesEvenWhenSectionBlocksArriveSectionRelative() {
        val document = ReaderDocument(
            id = DocumentId("relative-input-absolute-output"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = "intro", range = TextRange(0, 5), title = "Intro"),
                ReaderSection(1, text = "plain bold text", range = TextRange(6, 21), title = "Body"),
            ),
        )
        val sectionRelativeBlocks = mapOf(
            0 to listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 5))),
            1 to listOf(
                ReaderBlock(
                    kind = ReaderBlockKind.PARAGRAPH,
                    range = TextRange(0, 15),
                    spans = listOf(ReaderSpan(range = TextRange(6, 10), style = ReaderInlineStyle.BOLD)),
                ),
            ),
        )

        val pages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = ReaderPageBreaker { _, _ -> intArrayOf(0) },
        ) { section -> sectionRelativeBlocks.getValue(section.index) }

        val bodyPage = pages.single { it.text == "plain bold text" }
        val bodyBlock = bodyPage.blocks.single()
        assertEquals(TextRange(6, 21), bodyBlock.range, "a page's blocks must stay absolute even when fed section-relative input")
        assertEquals(TextRange(12, 16), bodyBlock.spans.single().range, "a span has to shift with its block, not stay behind")
    }

    /**
     * [TextPageLayoutEngine.paginate]의 기본 경로 — 명시적인 `sectionBlocks` 람다 없이 [ReaderDocument.blocks]를
     * 스스로 그룹화하는 경로로, 새로 파싱했을 때는 여전히 절대 범위로 넘어온다 — 와, 저장된 책이 밟는
     * 캐시 기반 경로 — 이미 섹션 상대로 넘어오는 경로 — 는 어느 쪽이든 정확히 같은 페이지를 만들어내야 한다.
     * 표지 섹션을 일부러 포함시켰다: 표지는 항상 절대 오프셋 0에서 시작하는데, 이는 잊혀진 리베이스가 그래도
     * 우연히 맞아 보일 수 있는 바로 그 한 경우이므로, 여기서 동등함을 증명하는 것이 부수적인 게 아니라 핵심이다.
     */
    @Test
    fun paginateProducesIdenticalPageWindowsWhetherSectionBlocksAreSectionRelativeOrTheDefaultGroupingPath() {
        val document = ReaderDocument(
            id = DocumentId("relative-vs-default"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                ReaderSection(1, text = "chapter one text", range = TextRange(2, 18), title = "Chapter 1"),
                ReaderSection(2, text = "chapter two text", range = TextRange(19, 35), title = "Chapter 2"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg"),
                ReaderBlock(kind = ReaderBlockKind.HEADING, level = 1, range = TextRange(2, 11)),
                ReaderBlock(kind = ReaderBlockKind.HEADING, level = 1, range = TextRange(19, 28)),
            ),
        )
        val style = ReaderStyle(fontSizeSp = 20f)
        val viewportSize = ViewportSize(widthPx = 400, heightPx = 400)

        val defaultPages = engine.paginate(document = document, style = style, viewportSize = viewportSize)

        val relativeBySection = mapOf(
            0 to listOf(ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg")),
            1 to listOf(ReaderBlock(kind = ReaderBlockKind.HEADING, level = 1, range = TextRange(0, 9))),
            2 to listOf(ReaderBlock(kind = ReaderBlockKind.HEADING, level = 1, range = TextRange(0, 9))),
        )
        val relativePages = engine.paginate(
            document = document,
            style = style,
            viewportSize = viewportSize,
        ) { section -> relativeBySection.getValue(section.index) }

        assertEquals(defaultPages, relativePages)
    }

    /**
     * [paginateProducesIdenticalPageWindowsWhetherSectionBlocksAreSectionRelativeOrTheDefaultGroupingPath]와
     * 같은 보장을 [TextPageLayoutEngine.reconstruct]에 대해서도 확인한다: 기본 그룹화 경로(절대 블록)와 이미
     * 섹션 상대로 답하는 조회는, 표지 섹션을 포함해서 정확히 같은 페이지를 재구성해야 한다.
     */
    @Test
    fun reconstructProducesIdenticalPageWindowsWhetherSectionBlocksAreSectionRelativeOrTheDefaultGroupingPath() {
        val document = ReaderDocument(
            id = DocumentId("reconstruct-relative-vs-default"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                ReaderSection(1, text = "abcdef", range = TextRange(2, 8), title = "Chapter 1"),
                ReaderSection(2, text = "ghijklmno", range = TextRange(9, 18), title = "Chapter 2"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg"),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(2, 8)),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(9, 18)),
            ),
        )
        val contentPageStarts = longArrayOf(2L, 5L, 9L, 14L)

        val defaultReconstructed = engine.reconstruct(document, contentPageStarts)

        val relativeBySection = mapOf(
            0 to listOf(ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg")),
            1 to listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 6))),
            2 to listOf(ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(0, 9))),
        )
        val relativeReconstructed = engine.reconstruct(
            document = document,
            contentPageStarts = contentPageStarts,
            sectionBlocks = { section -> relativeBySection.getValue(section.index) },
        )

        assertEquals(defaultReconstructed, relativeReconstructed)
    }

    /**
     * 저장된 페이지 시작 경계로부터 문서를 복원해도 전체 페이지 수나 어떤 문자 오프셋이 어느 페이지에
     * 놓이는지는 바뀌면 안 된다: 문서 전체의 모든 오프셋에 대해,
     * [TextPageLayoutEngine.reconstruct]로 페이지를 재구성한 결과는 방금 실측한 페이지들을 선형 탐색했을 때와
     * 같은 페이지 인덱스에 도달해야 하며, 이는 여기서 [pageOfOffset]으로 확인한다 —
     * `ReaderViewModel.pageOfOffset`이 책의 페이지 윈도우에 대해 실행하는 것과 같은 이진 탐색이다.
     */
    @Test
    fun reconstructTotalPageCountAndOffsetLookupMatchMeasuredPagination() {
        val document = ReaderDocument(
            id = DocumentId("epub-reconstruct-lookup"),
            format = DocumentFormat.EPUB,
            title = "Book",
            sections = listOf(
                ReaderSection(0, text = " ", range = TextRange(0, 1), title = "Cover"),
                ReaderSection(1, text = "abcdef", range = TextRange(2, 8), title = "Chapter 1"),
                ReaderSection(2, text = "ghijklmno", range = TextRange(9, 18), title = "Chapter 2"),
            ),
            blocks = listOf(
                ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = TextRange(0, 1), imageHref = "cover.jpg"),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(2, 8)),
                ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(9, 18)),
            ),
        )
        val breaker = ReaderPageBreaker { measured, _ ->
            IntArray((measured.length + 2) / 3) { page -> page * 3 }
        }
        val measuredPages = engine.paginate(
            document = document,
            style = ReaderStyle(fontSizeSp = 20f),
            viewportSize = ViewportSize(widthPx = 100, heightPx = 100),
            pageBreaker = breaker,
        )
        val contentPageStarts = measuredPages.drop(1).map { it.textRange!!.start }.toLongArray()

        val windows = engine.reconstruct(document, contentPageStarts)

        assertEquals(measuredPages.size, windows.size, "restoring from stored boundaries must not change the page count")
        for (offset in 0L until 18L) {
            val expected = measuredPages.indexOfFirst { page ->
                val range = page.textRange!!
                offset >= range.start && offset < range.end
            }.takeIf { it >= 0 }
            assertEquals(expected, windows.pageOfOffset(offset), "offset $offset landed on a different page after reconstruct")
        }
    }

}

/**
 * [offset]을 포함하는 [PageWindow.textRange]를 가진 페이지의 인덱스를, 이진 탐색으로 찾는다 — 수신자의
 * 범위가 오름차순이고 겹치지 않기 때문에 가능하며, `ReaderViewModel`이 "이 오프셋이 어느 페이지에 있는가"를
 * 답하기 위해 책의 페이지 윈도우에 대해 실행하는 것과 같은 조회다.
 *
 * @receiver 탐색할 페이지 윈도우들. 오름차순이고 겹치지 않는 [PageWindow.textRange] 순서여야 한다.
 * @param offset 찾을 절대 문서 오프셋.
 * @return [offset]을 포함하는 페이지의 인덱스, 또는 어떤 페이지의 범위도 이를 커버하지 못하면 null([PageWindow.textRange]가
 * 전혀 없는 페이지이거나, 모든 페이지 바깥의 오프셋).
 */
private fun List<PageWindow>.pageOfOffset(offset: Long): Int? {
    var low = 0
    var high = lastIndex
    while (low <= high) {
        val mid = (low + high) / 2
        val range = this[mid].textRange ?: return null
        when {
            offset < range.start -> high = mid - 1
            offset >= range.end -> low = mid + 1
            else -> return mid
        }
    }
    return null
}

package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderFontFamily
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [parseXhtmlContent]가 챕터의 CSS 캐스케이드([EpubCss])를 자신이 만드는 블록에 어떻게
 * 적용하는지를 고정한다: 정렬, 폰트 스타일링, 스타일시트 대 마크업 우선순위, 그리고 예전에는
 * 완전히 놓쳤던 경우까지 — 클래스가 전혀 없는 헤딩에 도달하는 순수 태그 규칙.
 */
class EpubXhtmlCssTest {
    private fun parse(xhtml: String, vararg sheets: String) =
        parseXhtmlContent(xhtml = xhtml, css = EpubCss.parse(sheets.toList()))

    /**
     * 회귀 방지: 순수 태그 규칙(`h1 { text-align: center }`)이 클래스가 전혀 없는 헤딩에
     * 도달한다 — 이 책들이 정확히 그렇게 하는데, 이 리더는 예전에 그것을 완전히 놓쳤었다.
     */
    @Test
    fun aTagRuleCentresTheChapterTitleTheBookCentres() {
        val content = parse("<h1>제1장</h1><p>본문</p>", "h1 { text-align: center; text-indent: 0 }")

        val heading = content.blocks.single { it.kind == ReaderBlockKind.HEADING }
        assertEquals(ReaderTextAlign.CENTER, heading.align)
        assertNull(content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.align)
    }

    /** 클래스 규칙의 정렬, 폰트 배율, 들여쓰기가 모두 그 클래스를 가진 문단에 도달한다. */
    @Test
    fun aClassRuleReachesTheParagraphCarryingIt() {
        val content = parse(
            """<p class="dedi">인용</p>""",
            ".dedi { font-size: 0.8em; text-indent: 0em; text-align: right }",
        )

        val block = content.blocks.single()
        assertEquals(ReaderTextAlign.END, block.align)
        assertEquals(0.8f, block.style?.fontScale)
        assertEquals(0f, block.style?.textIndentEm)
    }

    /**
     * 자손을 대상으로 하는 규칙(`.quotebox p`)은 클래스가 있는 래퍼 자체뿐 아니라 그 안에
     * 중첩된 문단에도 도달한다.
     */
    @Test
    fun aRuleOnAWrapperReachesTheParagraphInside() {
        val content = parse(
            """<div class="quotebox"><p>안쪽</p></div>""",
            ".quotebox p { font-style: italic; text-align: center }",
        )

        val block = content.blocks.single()
        assertEquals(true, block.style?.italic)
        assertEquals(ReaderTextAlign.CENTER, block.align)
    }

    /**
     * 인라인 `style` 속성의 정렬은 충돌하는 스타일시트 규칙을 이긴다, 브라우저가 부여하는 것과
     * 같은 우선순위다.
     */
    @Test
    fun markupWrittenOnTheElementBeatsTheStylesheet() {
        val content = parse(
            """<p style="text-align: right">본문</p>""",
            "p { text-align: center }",
        )

        assertEquals(ReaderTextAlign.END, content.blocks.single().align)
    }

    /**
     * 폰트 굵기, 폰트 패밀리, 줄 높이가 모두 캐스케이드에서 블록의 스타일로 실린다.
     */
    @Test
    fun weightAndFamilyAndLineHeightAreCarried() {
        val content = parse(
            """<p class="code">본문</p>""",
            ".code { font-weight: 700; font-family: 'Courier New', monospace; line-height: 1.8 }",
        )

        val style = content.blocks.single().style
        assertEquals(true, style?.bold)
        assertEquals(ReaderFontFamily.MONOSPACE, style?.fontFamily)
        assertEquals(1.8f, style?.lineHeightScale)
    }

    /**
     * 자기 자신의 번들 폰트 페이스 이름을 대는 책은 추측된 대체물이 아니라 리더 자체의 폰트로
     * 대체된다.
     */
    @Test
    fun aBookNamingItsOwnBundledFaceKeepsTheReadersFont() {
        val content = parse("""<p class="g">본문</p>""", ".g { font-family: G }")

        assertNull(content.blocks.single().style?.fontFamily)
    }

    /** 어떤 규칙도 대상으로 삼지 않는 블록은, 비어 있지만 존재하는 스타일이 아니라 스타일이 아예 없다. */
    @Test
    fun aBlockTheStylesheetSaysNothingAboutCarriesNoStyle() {
        val content = parse("<p>본문</p>", "h1 { text-align: center }")

        assertNull(content.blocks.single().style)
    }

    /** 두 규칙이 모두 같은 블록에 적용될 수 있을 때는 더 구체적인 것이 이긴다. */
    @Test
    fun aMoreSpecificRuleWinsForTheSameBlock() {
        val content = parse(
            """<h1 class="plain">제목</h1>""",
            "h1 { text-align: center } h1.plain { text-align: left }",
        )

        assertEquals(ReaderTextAlign.START, content.blocks.single().align)
    }

    /**
     * 회귀 방지: `<h1 class="img_full"><img/></h1>` — 이 책들이 헤딩에 감싼 전면 도판을 담아
     * 내보내는 방식 — 은 여전히 헤딩이 아니라 그림으로 읽힌다.
     */
    @Test
    fun anImageWrappedInAHeadingStillReadsAsAPicture() {
        val content = parse(
            """<h1 class="img_full"><img src="p.jpg" alt=""/></h1>""",
            ".img_full { width: 90%; text-align: center }",
        )

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertEquals("p.jpg", image.imageHref)
        assertTrue(content.text.isNotEmpty())
    }

    /** 중첩된 `em` 폰트 크기는 CSS가 정의하는 방식대로 조상들을 거치며 곱해진다: 0.8 × 0.8 = 0.64. */
    @Test
    fun aNestedEmFontSizeCompoundsThroughItsAncestors() {
        val content = parse(
            """<div class="outer"><p>본문</p></div>""",
            ".outer { font-size: 0.8em } p { font-size: 0.8em }",
        )

        assertEquals(0.64f, content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.style?.fontScale ?: 0f, 0.0001f)
    }

    /**
     * 단위 없는 `line-height`는 *배율*로 상속된다: 더 큰 글자로 설정된 헤딩은 body의 크기가
     * 아니라 자기 자신의 크기에 그 배율을 곱한 값을 얻는다. 이것을 고정된 길이로 붕괴시키면
     * 예전처럼 큰 글자 블록마다 줄 간격이 글리프보다 좁게 설정되어 버린다.
     */
    @Test
    fun aUnitlessLineHeightRemultipliesEachElementsOwnSize() {
        val content = parse(
            "<body><h1>제목</h1><p>본문</p></body>",
            "body { line-height: 1.6 } h1 { font-size: 2em }",
        )

        assertEquals(3.2f, content.blocks.single { it.kind == ReaderBlockKind.HEADING }.style?.lineHeightScale)
        assertEquals(1.6f, content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.style?.lineHeightScale)
    }

    /**
     * `line-height: 0`(또는 음수)은 이 렌더러가 그릴 수 없는 인쇄용 CSS 붕괴 트릭이다; 0 이하의
     * 배율 때문에 임포트 전체가 실패하는 대신 선언되지 않은 것으로 읽혀 상속된다.
     */
    @Test
    fun aNonPositiveLineHeightFactorFallsBackToTheInheritedValue() {
        val content = parse(
            """<body><p class="squash">본문</p><p>다음</p></body>""",
            "body { line-height: 1.6 } .squash { line-height: 0 }",
        )

        val blocks = content.blocks.filter { it.kind == ReaderBlockKind.PARAGRAPH }
        assertEquals(1.6f, blocks[0].style?.lineHeightScale)
        assertEquals(1.6f, blocks[1].style?.lineHeightScale)
    }

    /** 길이로 명시된 `line-height`는 그것을 선언한 요소에서 한 번 계산되고 고정되어 상속된다. */
    @Test
    fun aLineHeightLengthComputesOnceAndInheritsFixed() {
        val content = parse(
            """<div class="lead"><p>본문</p></div>""",
            ".lead { font-size: 1.25em; line-height: 1.2em } p { font-size: 0.8em }",
        )

        // 1.2em × 1.25 = 1.5 base-em, 래퍼에서 계산됨; 문단은 배율이 아니라 그 크기를 상속한다.
        assertEquals(1.5f, content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.style?.lineHeightScale)
    }

    /** `em` 마진은 CSS가 정의하는 대로 요소 자체의 폰트 크기를 기준으로 해석된다. */
    @Test
    fun anEmMarginResolvesAgainstTheElementsOwnSize() {
        val content = parse(
            "<p>본문</p>",
            "p { font-size: 2em; margin-bottom: 1em }",
        )

        assertEquals(2f, content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.style?.marginBottomEm)
    }

    /**
     * `text-decoration`은 상속되는 게 아니라 자손들에 걸쳐 칠해진다: 밑줄 있는 래퍼 안의 문단은
     * 밑줄이 그어지고, 취소선만 선언한 요소는 조상의 밑줄을 유지하며, 링크의 `none`은 그
     * 링크에 대해서만 칠을 끈다.
     */
    @Test
    fun textDecorationPaintsAcrossDescendants() {
        val content = parse(
            """<div class="u"><p>본문 <a href="n.xhtml">링크</a></p></div>""",
            ".u { text-decoration: underline } p { text-decoration: line-through } a { text-decoration: none }",
        )

        val paragraph = content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }
        assertEquals(true, paragraph.style?.underline)
        assertEquals(true, paragraph.style?.lineThrough)
        val link = paragraph.spans.single()
        assertEquals(false, link.styleDelta?.underline)
        assertEquals(false, link.styleDelta?.lineThrough)
    }

    /**
     * 스팬은 블록에 대한 *델타*만 싣는다: 블록이 이미 적용한 상속된 폰트 배율이 스팬에도 함께
     * 실리면 안 되는데, 렌더러의 중첩 em 해석이 그것을 두 번 적용해버리기 때문이다.
     */
    @Test
    fun aSpanCarriesOnlyItsDeltaAgainstTheBlock() {
        val content = parse(
            """<div class="s"><p>본문 <span style="font-size: 0.9em">작게</span></p></div>""",
            ".s { font-size: 0.9em }",
        )

        val paragraph = content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }
        assertEquals(0.9f, paragraph.style?.fontScale)
        assertEquals(0.9f, paragraph.spans.single().styleDelta?.fontScale)
    }

    /**
     * 인라인 시작 마진과 패딩은 모든 블록 레벨 래퍼로부터 문단의 인셋으로 누적된다 —
     * `<div>`로 들여쓴 인용문이 들여쓰기를 유지하는 방식이다.
     */
    @Test
    fun wrapperInsetsAccumulateIntoTheParagraph() {
        val content = parse(
            """<div class="q"><p style="margin-left: 1em">인용</p></div>""",
            ".q { margin-left: 2em; padding-left: 1em }",
        )

        assertEquals(4f, content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.style?.insetStartEm)
    }

    /**
     * `body`의 간격은 문단별 인셋이 아니라 페이지 마진이 된다 — 그것도 누적하면 같은 공간을
     * 두 번 적용하게 된다.
     */
    @Test
    fun bodySpacingStaysOutOfParagraphInsets() {
        val content = parse(
            "<html><body><p>본문</p></body></html>",
            "body { margin: 2em }",
        )

        assertNull(content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }.style?.insetStartEm)
        val body = content.blocks.single { it.isPageContainer }
        assertEquals(2f, body.style?.marginStartEm)
    }

    /**
     * `<p><br/></p>`는 빈 문단이 아니라 빈 *줄* 문단이다: 브라우저는 이것을 한 줄 높이로
     * 그리며, 이 책들이 챕터 제목 상자와 본문 사이에 공간을 두는 방식이다. 이것을 빈 것으로
     * 취급해 버리면 둘이 붙어버렸다.
     */
    @Test
    fun aParagraphOfOnlyLineBreaksKeepsItsBlankLines() {
        val content = parse("<p>본문</p><p><br/></p><p><br/></p><p>다음</p>", "")

        val paragraphs = content.blocks.filter { it.kind == ReaderBlockKind.PARAGRAPH }
        assertEquals(4, paragraphs.size)
        val blank = paragraphs[1]
        assertEquals("\n", content.text.substring(blank.range.start.toInt(), blank.range.end.toInt()))
    }

    /** 자기만의 블록 태그 없이 스타일 있는 래퍼 안에 직접 놓인 텍스트도 여전히 그 스타일링을 취한다. */
    @Test
    fun bareTextInsideAStyledWrapperTakesItsStyling() {
        val content = parse(
            """<div class="w">본문</div>""",
            ".w { color: #011689 }",
        )

        // div는 그 자체로 블록이다; 암묵적 블록 경로를 타도록 대신 래퍼를 중립적인 태그로 만든다.
        val neutral = parse(
            """<figure class="w">본문</figure>""",
            ".w { color: #011689; font-size: 0.9em }",
        )
        val block = neutral.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }
        assertEquals(0.9f, block.style?.fontScale)
        assertTrue(content.blocks.isNotEmpty())
    }

    /**
     * 계약: CONTAINER 블록은 항상 진짜 래퍼다. 정확히 하나의 텍스트 런을 감싸는 스타일 있는
     * 블록 요소는 자신의 스타일 전체를 — 박스 포함해서 — 리프 블록 하나에만 싣는다; 그 곁에
     * 같은 범위·같은 스타일의 CONTAINER 쌍둥이를 기록하면 모든 렌더러가 간격을 이중으로 세지
     * 않으려고 그 중복을 다시 탐지해야 했는데, 이 억제는 정확히 그 종류의 버그를 제거한다.
     */
    @Test
    fun aStyledBlockElementRecordsNoContainerTwin() {
        val content = parse(
            """<p class="boxed">본문</p>""",
            ".boxed { border: 1px solid black; padding: 1em; margin-bottom: 2em }",
        )

        assertTrue(content.blocks.none { it.kind == ReaderBlockKind.CONTAINER })
        val leaf = content.blocks.single { it.kind == ReaderBlockKind.PARAGRAPH }
        assertEquals(2f, leaf.style?.marginBottomEm)
        assertTrue(leaf.style?.boxStyle?.borderTop != null)
    }

    /** 둘 이상의 블록을 감싸는 래퍼는 자기 자신의 CONTAINER를 유지한다 — 그 박스는 진짜로 자기 것이다. */
    @Test
    fun aWrapperAroundSeveralParagraphsKeepsItsContainer() {
        val content = parse(
            """<div class="frame"><p>하나</p><p>둘</p></div>""",
            ".frame { border: 1px solid black; padding: 1em }",
        )

        val container = content.blocks.single { it.kind == ReaderBlockKind.CONTAINER }
        val paragraphs = content.blocks.filter { it.kind == ReaderBlockKind.PARAGRAPH }
        assertEquals(2, paragraphs.size)
        assertTrue(container.range.start <= paragraphs.first().range.start)
        assertTrue(container.range.end >= paragraphs.last().range.end)
    }

    /**
     * 범위가 단지 하나뿐인 자식과 우연히 겹치는 진짜 래퍼 — 헤딩 하나를 담은 챕터 제목 상자 —
     * 는 리프와 *다른* 스타일을 가지며, 자신의 CONTAINER를 유지해야 한다: 그 패딩과 테두리는
     * 헤딩 자체의 것이 아니라 책이 헤딩 주위에 그린 상자다.
     */
    @Test
    fun aWrapperWithItsOwnStyleKeepsItsContainerEvenOverOneChild() {
        val content = parse(
            """<div class="titlebox"><h1>제목</h1></div>""",
            ".titlebox { border: 1px solid black; padding: 1em }",
        )

        assertTrue(content.blocks.any { it.kind == ReaderBlockKind.CONTAINER })
        assertTrue(content.blocks.any { it.kind == ReaderBlockKind.HEADING })
    }

    /** html/body 페이지 컨테이너는 항상 기록된다 — 페이지 마진과 배경이 거기서 읽힌다. */
    @Test
    fun pageContainersAreAlwaysRecorded() {
        val content = parse(
            """<html><body><p>본문</p></body></html>""",
            "body { margin: 2em }",
        )

        assertTrue(content.blocks.any { it.kind == ReaderBlockKind.CONTAINER && it.isPageContainer })
    }
}

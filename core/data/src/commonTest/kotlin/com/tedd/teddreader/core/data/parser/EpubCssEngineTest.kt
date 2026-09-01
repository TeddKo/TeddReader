package com.tedd.teddreader.core.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EpubCss]의 셀렉터 매칭과 캐스케이드 해석을, 이 렌더러가 실제로 지원하는 CSS 기능들에 고정한다:
 * 태그/클래스/id 셀렉터와 그 명시도, 자손(그리고 자식/형제도 자손으로 취급하는) 결합자, 명시도가
 * 동률일 때 선언 순서로 승부를 가르는 규칙, 프로퍼티 상속, 인식되는 길이 단위, `margin` 축약형, 그리고
 * 추측하는 대신 버려야 할 것들(이 렌더러가 그릴 수 없는 프로퍼티, at-rule, 의사 클래스, 속성 셀렉터).
 */
class EpubCssEngineTest {
    private fun css(vararg sheets: String) = EpubCss.parse(sheets.toList())

    private fun element(tag: String, vararg classes: String, id: String? = null) =
        CssElement(tag = tag, classes = classes.toSet(), id = id)

    /**
     * 회귀 방지: 단순 태그 규칙(`h1 { text-align: center }`)은 그 태그를 가진 모든 요소에만 스타일을
     * 적용해야 한다 — 이 리더가 예전에는 완전히 무시하던 규칙이지만, 책들이 챕터 제목을 이런 식으로
     * 흔히 표현한다.
     */
    @Test
    fun aTagRuleStylesThatTag() {
        val sheet = css("h1 { text-align: center; text-indent: 0; }")

        assertEquals("center", sheet.declarationsFor(listOf(element("h1"))).textAlign)
        assertNull(sheet.declarationsFor(listOf(element("p"))).textAlign)
    }

    /** 클래스 셀렉터는 태그와 무관하게 그 클래스를 가진 모든 요소에 스타일을 적용한다. */
    @Test
    fun aClassRuleStylesAnyTagCarryingIt() {
        val sheet = css(".dedi { font-size: 0.8em }")

        assertEquals(CssLength.Em(0.8f), sheet.declarationsFor(listOf(element("p", "dedi"))).fontSize)
    }

    /**
     * 회귀 방지: 명시도 — 태그+클래스가 클래스 단독을 이기고, 클래스 단독이 태그 단독을 이긴다 — 는
     * 규칙이 작성된 순서와 무관하게 승자를 정한다.
     */
    @Test
    fun aMoreSpecificRuleWinsRegardlessOfOrder() {
        val sheet = css(
            """
            h1.title { text-align: right }
            h1 { text-align: center }
            .title { text-align: justify }
            """.trimIndent(),
        )

        assertEquals("right", sheet.declarationsFor(listOf(element("h1", "title"))).textAlign)
    }

    /** id 셀렉터는 CSS 명시도 규칙에 따라 클래스와 태그의 어떤 조합보다도 우선한다. */
    @Test
    fun anIdOutranksClassesAndTags() {
        val sheet = css("#lead { color: red } p.lead.big { color: blue }")

        assertEquals("red", sheet.declarationsFor(listOf(element("p", "lead", "big", id = "lead"))).color)
    }

    /** 두 규칙의 명시도가 동률이면 시트에서 나중에 선언된 쪽이 이긴다. */
    @Test
    fun theLaterOfTwoEquallySpecificRulesWins() {
        val sheet = css(".a { color: red }", ".a { color: green }")

        assertEquals("green", sheet.declarationsFor(listOf(element("p", "a"))).color)
    }

    /**
     * 자손 셀렉터(`.quote p`)는 요구되는 조상이 요소 위쪽 어딘가에 실제로 존재할 때만 매칭된다.
     */
    @Test
    fun aDescendantSelectorNeedsTheAncestorToBePresent() {
        val sheet = css(".quote p { font-style: italic }")
        val inside = listOf(element("div", "quote"), element("blockquote"), element("p"))
        val outside = listOf(element("div"), element("p"))

        assertEquals("italic", sheet.declarationsFor(inside).fontStyle)
        assertNull(sheet.declarationsFor(outside).fontStyle)
    }

    /**
     * `>` 자식 결합자는 직계 부모로 제한되지 않고 일반 자손 매칭으로 존중된다.
     */
    @Test
    fun aChildCombinatorIsHonouredAsADescendant() {
        val sheet = css(".box > img { width: 50% }")

        assertEquals(
            CssLength.Percent(0.5f),
            sheet.declarationsFor(listOf(element("div", "box"), element("img"))).width,
        )
    }

    /**
     * [CssDeclarations.inheritable]을 거쳐 살아남는 것은 CSS에서 상속되는 프로퍼티 부분집합(텍스트
     * 정렬, 글꼴, 색상)뿐이다; margin과 width는 그것을 선언한 박스 자신에게 속할 뿐 그 안의 내용에는
     * 속하지 않으므로 둘 다 제거된다.
     */
    @Test
    fun onlyInheritedPropertiesPassToAChild() {
        val declarations = CssDeclarations(
            textAlign = "center",
            fontSize = CssLength.Em(1.2f),
            color = "red",
            marginTop = CssLength.Em(2f),
            width = CssLength.Percent(0.5f),
        )

        val inherited = declarations.inheritable()
        assertEquals("center", inherited.textAlign)
        assertEquals("red", inherited.color)
        assertNull(inherited.marginTop)
        assertNull(inherited.width)
    }

    /** `%`, `em`, `px` 길이는 각각 선언에서 실제로 사용한 단위 그대로 읽힌다. */
    @Test
    fun lengthsAreReadInTheUnitsTheseBooksUse() {
        val sheet = css("p { font-size: 90%; text-indent: 1.5em; margin-top: 12px }")
        val declarations = sheet.declarationsFor(listOf(element("p")))

        assertEquals(CssLength.Percent(0.9f), declarations.fontSize)
        assertEquals(CssLength.Em(1.5f), declarations.textIndent)
        assertEquals(CssLength.Px(12f), declarations.marginTop)
    }

    /**
     * 단위 없는 `line-height`는 길이가 아니라 *배수*로 남아야 한다: 상속받는 각 요소 자신의 글꼴
     * 크기에 다시 곱해져야 하는데, 이는 길이로는 할 수 없는 일이다. 단위가 명시된 값은 길이로 남는다.
     */
    @Test
    fun aUnitlessLineHeightIsAFactorOfTheFontSize() {
        assertEquals(
            CssLineHeight.Factor(1.6f),
            css("p { line-height: 1.6 }").declarationsFor(listOf(element("p"))).lineHeight,
        )
        assertEquals(
            CssLineHeight.Length(CssLength.Em(1.6f)),
            css("p { line-height: 1.6em }").declarationsFor(listOf(element("p"))).lineHeight,
        )
    }

    /**
     * `margin` 축약형의 1개, 2개, 4개 값 형태는 각각 올바른 top/bottom 값으로 해석된다.
     */
    @Test
    fun theMarginShorthandGivesUpItsVerticalHalves() {
        val one = css("p { margin: 1em }").declarationsFor(listOf(element("p")))
        val two = css("p { margin: 1em 2em }").declarationsFor(listOf(element("p")))
        val four = css("p { margin: 1em 2em 3em 4em }").declarationsFor(listOf(element("p")))

        assertEquals(CssLength.Em(1f), one.marginTop)
        assertEquals(CssLength.Em(1f), one.marginBottom)
        assertEquals(CssLength.Em(1f), two.marginTop)
        assertEquals(CssLength.Em(1f), two.marginBottom)
        assertEquals(CssLength.Em(1f), four.marginTop)
        assertEquals(CssLength.Em(3f), four.marginBottom)
    }

    /**
     * `float`는 이 파서가 이제 이미지 폴백 신호로 사용하는 값으로 유지되고, 아직 필드가 없는
     * 프로퍼티(`display`)는 조용히 버려진다. 같은 규칙 안의 그릴 수 있는 선언(`text-align`)도
     * 그대로 살아남는다.
     */
    @Test
    fun floatAndDisplayAndPublisherBoxPropertiesAreKept() {
        val sheet = css(
            """
            p {
              float: left !important;
              display: none;
              text-align: center;
              color: #011689;
              background-color: rgba(255,255,255,0);
              border-top: 2px solid #011689;
              border-bottom-width: 2px;
              border-bottom-color: rgb(1, 22, 137);
              border-radius: 50%;
            }
            """.trimIndent(),
        )
        val declarations = sheet.declarationsFor(listOf(element("p")))

        assertEquals("left", declarations.float)
        assertEquals("none", declarations.display)
        assertEquals("center", declarations.textAlign)
        assertEquals("#011689", declarations.color)
        assertEquals("rgba(255,255,255,0)", declarations.backgroundColor)
        assertEquals(CssLength.Px(2f), declarations.borderTop?.width)
        assertEquals("#011689", declarations.borderTop?.color)
        assertEquals(CssLength.Px(2f), declarations.borderBottom?.width)
        assertEquals("rgb(1, 22, 137)", declarations.borderBottom?.color)
        assertEquals(CssLength.Percent(0.5f), declarations.borderRadius)
    }

    @Test
    fun fontFaceResolvesRelativeToItsLinkedCssPath() {
        val sheet = EpubCss.parseSources(
            listOf(
                CssStyleSheetSource(
                    path = "OPS/css/book.css",
                    css = "@font-face { font-family: 'KoPub'; src: url('../fonts/KoPub.otf'); } .title { font-family: 'KoPub', serif; }",
                ),
            ),
        )

        val declarations = sheet.declarationsFor(listOf(element("p", "title")))
        assertEquals("'KoPub', serif", declarations.fontFamily)
        assertEquals("OPS/fonts/KoPub.otf", sheet.resolvedFontHref(declarations.fontFamily))
    }

    @Test
    fun laterBorderNoneAndZeroWidthClearEarlierBorder() {
        val none = css("p { border: 2px solid red; border: none; }").declarationsFor(listOf(element("p")))
        val zero = css("p { border-top: 2px solid red; border-top-width: 0; }").declarationsFor(listOf(element("p")))

        assertEquals(CssLength.Px(0f), none.borderTop?.width)
        assertEquals(CssLength.Px(0f), none.borderRight?.width)
        assertEquals(CssLength.Px(0f), none.borderBottom?.width)
        assertEquals(CssLength.Px(0f), none.borderLeft?.width)
        assertEquals(CssLength.Px(0f), zero.borderTop?.width)
    }

    @Test
    fun borderStyleWithoutWidthFallsBackToCssMediumWidth() {
        val declarations = css("p { border-top: solid blue; }").declarationsFor(listOf(element("p")))

        assertEquals(CssLength.Px(3f), declarations.borderTop?.width)
        assertEquals("blue", declarations.borderTop?.color)
    }

    @Test
    fun uppercaseBorderNoneStillClearsEarlierBorder() {
        val declarations = css("p { border: 2px solid red; border: NONE; }").declarationsFor(listOf(element("p")))

        assertEquals(CssLength.Px(0f), declarations.borderTop?.width)
        assertEquals(CssLength.Px(0f), declarations.borderRight?.width)
        assertEquals(CssLength.Px(0f), declarations.borderBottom?.width)
        assertEquals(CssLength.Px(0f), declarations.borderLeft?.width)
    }

    /**
     * 회귀 방지: at-rule 본문, 의사 클래스, 속성 셀렉터는 느슨하게 매칭되지 않고 완전히 버려져야 한다.
     * 그래야 print 전용/상태 전용 스타일링이 모든 페이지로 새어나가지 않는다.
     */
    @Test
    fun aRuleThisCannotJudgeIsDroppedInsteadOfMisapplied() {
        val sheet = css(
            """
            @media print { p { text-align: right } }
            a:hover { color: red }
            input[type="text"] { color: blue }
            """.trimIndent(),
        )

        assertNull(sheet.declarationsFor(listOf(element("a"))).color)
        assertNull(sheet.declarationsFor(listOf(element("input"))).color)
    }

    /**
     * 값을 받는 모든 프로퍼티에서, 단독 `0`은 길이다.
     *
     * `margin: 0`은 거의 모든 EPUB가 리셋을 표현하는 방식이며, 이를 "아무것도 지정하지 않음"으로
     * 읽는 것은 사소한 실수가 아니다: 그럴 경우 리더는 자체 기본 간격으로 폴백하여, 들여쓰기로 구분되는
     * 흐르는 산문 한 권을 원래 조판된 페이지 수의 약 두 배로 퍼뜨리게 된다.
     */
    @Test
    fun aBareZeroIsAZeroLength() {
        val declarations = css("p { margin: 0; text-indent: 0; padding: 0 }").declarationsFor(listOf(element("p")))

        assertEquals(CssLength.Px(0f), declarations.marginTop)
        assertEquals(CssLength.Px(0f), declarations.marginBottom)
        assertEquals(CssLength.Px(0f), declarations.textIndent)
        assertEquals(CssLength.Px(0f), declarations.paddingLeft)
    }

    /** 0이 아닌 단위 없는 숫자는 길이가 아니며, 추측하는 대신 버려진다. */
    @Test
    fun aBareNonZeroNumberIsNotALength() {
        assertNull(css("p { margin-top: 12 }").declarationsFor(listOf(element("p"))).marginTop)
    }

    /** 포인트는 1/72인치, CSS 픽셀은 1/96인치이므로, `12pt`는 책이 의도한 16px이 된다. */
    @Test
    fun pointsResolveAgainstThePixelTheyAreDefinedBy() {
        val declarations = css("p { font-size: 12pt; margin-top: 9pt }").declarationsFor(listOf(element("p")))

        assertEquals(CssLength.Px(16f), declarations.fontSize)
        assertEquals(CssLength.Px(12f), declarations.marginTop)
    }

    /** `margin`과 `padding` 축약형은 CSS의 1~4개 값 형태로 네 변 모두에 확장된다. */
    @Test
    fun theBoxShorthandsExpandToEverySide() {
        val three = css("p { margin: 1em 2em 3em }").declarationsFor(listOf(element("p")))
        val four = css("blockquote { padding: 1em 0 2em 1.5em }").declarationsFor(listOf(element("blockquote")))

        assertEquals(CssLength.Em(2f), three.marginLeft)
        assertEquals(CssLength.Em(2f), three.marginRight)
        assertEquals(CssLength.Em(1f), four.paddingTop)
        assertEquals(CssLength.Px(0f), four.paddingRight)
        assertEquals(CssLength.Em(2f), four.paddingBottom)
        assertEquals(CssLength.Em(1.5f), four.paddingLeft)
    }

    /**
     * `a:link`와 `a:visited`는 평범한 링크를 가리키므로, 그 규칙은 링크에 적용되어야 한다.
     *
     * 링크 밑줄을 끄는 책은 정확히 이렇게 작성하며, 이 규칙을 버리면 — 이 매처가 판단할 수 없는
     * 셀렉터는 모두 버려진다는 원칙대로 — 책의 모든 링크에 밑줄이 그려지게 된다.
     */
    @Test
    fun theLinkPseudoClassesStillStyleLinks() {
        val sheet = css("a:link, a:visited { text-decoration: none; color: #000000 }")

        val declarations = sheet.declarationsFor(listOf(element("a")))
        assertEquals("none", declarations.textDecoration)
        assertEquals("#000000", declarations.color)
    }

    /**
     * 빈 시트, 빈 문자열, 선언 본문이 빈 규칙은 모두 [EpubCss.isEmpty]로 귀결된다.
     */
    @Test
    fun anEmptySheetCostsNothing() {
        assertTrue(EpubCss.parse(emptyList()).isEmpty())
        assertTrue(css("").isEmpty())
        assertTrue(css("p { }").isEmpty())
    }

    /**
     * 이 테스트가 대체한 평면 정규식 추출 방식에 대한 회귀 방지: 적용되지 않는 at-rule의 *본문*이
     * 그 내부 규칙을 일반 캐스케이드로 새어나가게 해서는 안 된다. `@media print { p { … } }`는
     * 예전에는 내부의 `p { … }`를 일반 규칙처럼 매칭시켜 화면에서도 그 문단들을 숨겨버렸다.
     */
    @Test
    fun aPrintMediaBodyNeverLeaksIntoTheScreenCascade() {
        val sheet = css(
            """
            @media print { p { display: none; color: red } }
            p { text-align: center }
            """.trimIndent(),
        )

        val declarations = sheet.declarationsFor(listOf(element("p")))
        assertNull(declarations.display)
        assertNull(declarations.color)
        assertEquals("center", declarations.textAlign)
    }

    /** `screen`/`all`/`only screen` 쿼리는 중첩된 `@font-face`를 포함해 본문 전체를 적용한다. */
    @Test
    fun aScreenMediaBodyApplies() {
        val sheet = css(
            """
            @media screen { p { color: red } }
            @media only screen { h1 { color: blue } }
            @media all { h2 { color: green } }
            """.trimIndent(),
        )

        assertEquals("red", sheet.declarationsFor(listOf(element("p"))).color)
        assertEquals("blue", sheet.declarationsFor(listOf(element("h1"))).color)
        assertEquals("green", sheet.declarationsFor(listOf(element("h2"))).color)
    }

    /**
     * 기능 조건(`(min-width: …)`)은 이 파싱 시점 엔진이 갖고 있지 않은 뷰포트를 필요로 하므로,
     * 추측하는 대신 그 분기를 건너뛴다 — 와이드스크린 전용 오버라이드를 모든 휴대폰에 적용하는 것은
     * print 스타일을 화면에 적용하는 것과 같은 부류의 누출이다. 판단 가능한 콤마 분기는 여전히
     * 블록을 적용한다.
     */
    @Test
    fun aFeatureConditionedMediaBranchIsSkippedButAJudgeableBranchStillApplies() {
        val skipped = css("@media screen and (min-width: 60em) { p { color: red } }")
        val applied = css("@media print, screen { p { color: blue } }")

        assertNull(skipped.declarationsFor(listOf(element("p"))).color)
        assertEquals("blue", applied.declarationsFor(listOf(element("p"))).color)
    }

    /** 다른 블록 at-rule은 통째로 건너뛴다; 그 *뒤*의 규칙들은 순서대로 여전히 파싱된다. */
    @Test
    fun otherAtRuleBlocksAreSkippedWithoutShiftingLaterRules() {
        val sheet = css(
            """
            @supports (display: flex) { p { color: red } }
            @keyframes fade { from { color: red } to { color: blue } }
            @page { margin: 5em }
            p { color: green }
            """.trimIndent(),
        )

        val declarations = sheet.declarationsFor(listOf(element("p")))
        assertEquals("green", declarations.color)
        assertNull(declarations.marginTop)
        assertNull(sheet.declarationsFor(listOf(element("from"))).color)
    }

    /** 문(statement) at-rule(`@import`, `@charset`, `@namespace`)은 자신의 `;`에서 끝나고 그 외에는 아무 비용도 들지 않는다. */
    @Test
    fun statementAtRulesAreSkippedToTheirSemicolon() {
        val sheet = css(
            """
            @charset "utf-8";
            @import url("other.css");
            p { color: green }
            """.trimIndent(),
        )

        assertEquals("green", sheet.declarationsFor(listOf(element("p"))).color)
    }

    /** 적용되는 `@media` 안의 `@font-face`도 여전히 자신의 폰트 패밀리를 등록한다. */
    @Test
    fun aFontFaceInsideAnApplyingMediaBlockStillRegisters() {
        val sheet = css(
            """
            @media screen {
                @font-face { font-family: 'KoPub'; src: url('fonts/KoPub.otf'); }
            }
            """.trimIndent(),
        )

        assertEquals("fonts/KoPub.otf", sheet.resolvedFontHref("'KoPub', serif"))
    }

    /** 엉뚱한 `}`나 닫히지 않은 블록은 주변 규칙의 위치를 흔들지 않고 조용히 실패한다. */
    @Test
    fun malformedBracesFailSoft() {
        val strayCloser = css("} p { color: green }")
        val unclosed = css("p { color: green } .broken { color: red")

        assertEquals("green", strayCloser.declarationsFor(listOf(element("p"))).color)
        assertEquals("green", unclosed.declarationsFor(listOf(element("p"))).color)
    }

    /** 인용된 문자열 안의 중괄호는 블록 스캔의 개수를 결코 어긋나게 하지 않는다. */
    @Test
    fun aBraceInsideAQuotedStringDoesNotBreakTheScan() {
        val sheet = css("""p { font-family: "Weird{Name" } h1 { color: red }""")

        assertEquals("red", sheet.declarationsFor(listOf(element("h1"))).color)
    }

    /**
     * 터미널 태그 인덱스에 대한 회귀 방지: 태그 없는 클래스 셀렉터, 태그 없는 id 셀렉터, 단순 태그
     * 셀렉터가 같은 요소를 두고 경쟁할 때도, 인덱싱하지 않은 엔진이 만들어냈을 정확한
     * `(specificity, order)` 캐스케이드로 여전히 귀결되어야 한다. 태그 규칙은 자신의 태그 버킷에,
     * 클래스와 id 규칙은 태그 없는 폴백에 산다; 두 목록을 병합할 때 명시도가 낮은 태그 규칙이
     * 명시도가 높은 클래스 규칙을 덮어써서는 안 되며, id는 여전히 둘 모두를 이겨야 한다.
     */
    @Test
    fun taglessAndTagRulesCompeteInCascadeOrderAcrossTheIndex() {
        val sheet = css(
            """
            p { color: red; text-align: left }
            .lead { color: green }
            #first { color: blue }
            """.trimIndent(),
        )

        val onlyTag = sheet.declarationsFor(listOf(element("p")))
        assertEquals("red", onlyTag.color)
        assertEquals("left", onlyTag.textAlign)

        val tagAndClass = sheet.declarationsFor(listOf(element("p", "lead")))
        assertEquals("green", tagAndClass.color)
        assertEquals("left", tagAndClass.textAlign)

        val all = sheet.declarationsFor(listOf(element("p", "lead", id = "first")))
        assertEquals("blue", all.color)
        assertEquals("left", all.textAlign)
    }

    /**
     * 회귀 방지: 태그 버킷 규칙(`span`)과 태그 없는 폴백 규칙(`.mark`)이 같은 요소에 둘 다 매칭될 때,
     * 어느 인덱스 목록에서 왔는지가 아니라 명시도로 해결되어야 한다. 클래스 규칙은 시트가 어떤
     * 순서로 작성하든 태그 규칙을 이기므로, 두 개별 목록의 병합이 시트 순서가 명시도를 덮어쓰게
     * 해서는 안 된다.
     */
    @Test
    fun aTagBucketAndTheFallbackResolveBySpecificityNotListMembership() {
        val classThenTag = css(
            """
            .mark { color: red }
            span { color: green }
            """.trimIndent(),
        )
        val tagThenClass = css(
            """
            span { color: green }
            .mark { color: red }
            """.trimIndent(),
        )

        assertEquals("red", classThenTag.declarationsFor(listOf(element("span", "mark"))).color)
        assertEquals("red", tagThenClass.declarationsFor(listOf(element("span", "mark"))).color)
    }

    /**
     * 회귀 방지: 터미널 화합물(compound)이 태그를 가진(`.quote em`) 자손 셀렉터는 그 터미널
     * 태그 아래에 버킷팅되어야 하고, 전체 조상 체인을 통해 여전히 매칭되어야 하며, 동시에 같은
     * 셀렉터가 왼쪽에 명시한 조상 태그(`.quote`) 자체에는 발동하지 않아야 한다. 이는 인덱스가
     * 셀렉터 안의 아무 태그가 아니라 *터미널* 화합물의 태그로 키를 잡는다는 것을 증명한다.
     */
    @Test
    fun aDescendantSelectorIsBucketedByItsTerminalTag() {
        val sheet = css(".quote em { font-style: italic }")
        val inside = listOf(element("div", "quote"), element("em"))
        val theQuoteItself = listOf(element("div", "quote"))

        assertEquals("italic", sheet.declarationsFor(inside).fontStyle)
        assertNull(sheet.declarationsFor(theQuoteItself).fontStyle)
    }

    /**
     * 성능에 민감한 회귀 방지: 관련 없는 단일 태그 규칙이 많은 시트를 대상으로 `<p>` 하나에
     * 스타일을 적용하는 일이, 그 관련 없는 규칙들을 요소에 대해 아예 평가하지 않아야 한다. 엔진이
     * 실제로 테스트하는 각 후보 규칙은 [CssSelector.matches]를 통해 조상 목록을(적어도 마지막
     * 요소를) 읽는다; 개수를 세는 [AbstractList]가 모든 [get] 호출을 기록한다. 터미널 태그
     * 인덱스가 온전하다면, 소수의 `p`/클래스/id 후보 규칙만 목록에 손을 대므로, 시트가 `h1`…`h6`,
     * `span`, `div`, … 규칙을 아무리 많이 갖고 있어도 그 개수는 작은 상수로 유지된다. 인덱스를
     * 무력화(모든 규칙을 매칭 평가)하면 이 개수는 전체 규칙 목록에 비례해 커져서 이 단언이 실패할
     * 것이다 — 이렇게 이 테스트의 민감도가 확인되었다.
     */
    @Test
    fun unrelatedTagRulesAreNeverMatchEvaluated() {
        val unrelatedTags = listOf("h1", "h2", "h3", "h4", "h5", "h6", "span", "div", "a", "li", "td", "th")
        val sheet = css(
            buildString {
                unrelatedTags.forEach { tag -> append(tag).append(" { color: red }\n") }
                append("p { color: green }\n")
            },
        )

        val accesses = AccessCountingList(listOf(element("p", "body")))
        val declarations = sheet.declarationsFor(accesses)

        assertEquals("green", declarations.color)
        assertTrue(
            accesses.getCount <= 4,
            "expected only the p-tag candidate to read the ancestry, but it was read ${accesses.getCount} times",
        )
    }

    /**
     * [backing] 위에 놓인 [AbstractList]로, 모든 위치 기반 [get]을 집계하여, 테스트가 캐스케이드
     * 해석기가 실제로 요소의 조상 목록에 몇 번 접근했는지 단언할 수 있게 한다. 터미널 태그와
     * 매칭될 수 없는 규칙들이 조상 접근 이전에 인덱스로 걸러지는지, 아니면 하나씩 매칭 평가되는지를
     * 증명하는 데 쓰인다.
     *
     * @param backing 요소 단위로 그대로 반환되는, 이 목록이 대신하는 조상 체인.
     * @property getCount 생성 이후 [get]이 호출된 횟수.
     */
    private class AccessCountingList(private val backing: List<CssElement>) : AbstractList<CssElement>() {
        var getCount: Int = 0
            private set

        override val size: Int get() = backing.size

        override fun get(index: Int): CssElement {
            getCount += 1
            return backing[index]
        }
    }
}

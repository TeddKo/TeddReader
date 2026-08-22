package com.tedd.teddreader.core.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [EpubCss]'s selector matching and cascade resolution against the CSS features this renderer
 * actually supports: tag/class/id selectors and their specificity, the descendant (and child/sibling,
 * read as descendant) combinator, declaration order as a specificity tiebreaker, property inheritance,
 * the recognized length units, the `margin` shorthand, and what must be dropped rather than guessed at
 * (a property this renderer cannot draw, an at-rule, a pseudo-class, an attribute selector).
 */
class EpubCssEngineTest {
    private fun css(vararg sheets: String) = EpubCss.parse(sheets.toList())

    private fun element(tag: String, vararg classes: String, id: String? = null) =
        CssElement(tag = tag, classes = classes.toSet(), id = id)

    /**
     * Regression guard: a bare-tag rule (`h1 { text-align: center }`) styles every element with that
     * tag and no other — the rule this reader used to ignore entirely, even though books commonly
     * state chapter titles this way.
     */
    @Test
    fun aTagRuleStylesThatTag() {
        val sheet = css("h1 { text-align: center; text-indent: 0; }")

        assertEquals("center", sheet.declarationsFor(listOf(element("h1"))).textAlign)
        assertNull(sheet.declarationsFor(listOf(element("p"))).textAlign)
    }

    /** A class selector styles any element carrying that class, regardless of its tag. */
    @Test
    fun aClassRuleStylesAnyTagCarryingIt() {
        val sheet = css(".dedi { font-size: 0.8em }")

        assertEquals(CssLength.Em(0.8f), sheet.declarationsFor(listOf(element("p", "dedi"))).fontSize)
    }

    /**
     * Regression guard: specificity — tag+class beats class alone, which beats tag alone — decides the
     * winner regardless of the order the rules were written in.
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

    /** An id selector outranks any combination of classes and tags, per CSS specificity rules. */
    @Test
    fun anIdOutranksClassesAndTags() {
        val sheet = css("#lead { color: red } p.lead.big { color: blue }")

        assertEquals("red", sheet.declarationsFor(listOf(element("p", "lead", "big", id = "lead"))).color)
    }

    /** When two rules tie on specificity, the one declared later in the sheet wins. */
    @Test
    fun theLaterOfTwoEquallySpecificRulesWins() {
        val sheet = css(".a { color: red }", ".a { color: green }")

        assertEquals("green", sheet.declarationsFor(listOf(element("p", "a"))).color)
    }

    /**
     * A descendant selector (`.quote p`) only matches when the required ancestor is actually present
     * somewhere above the element.
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
     * The `>` child combinator is honoured as a plain descendant match, not restricted to a direct parent.
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
     * Only the CSS-inherited subset of properties (text alignment, font, color) survives
     * [CssDeclarations.inheritable]; a margin and a width belong to the box that declared them, never
     * to what it contains, so both are dropped.
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

    /** `%`, `em`, and `px` lengths are each read in the unit the declaration actually used. */
    @Test
    fun lengthsAreReadInTheUnitsTheseBooksUse() {
        val sheet = css("p { font-size: 90%; text-indent: 1.5em; margin-top: 12px }")
        val declarations = sheet.declarationsFor(listOf(element("p")))

        assertEquals(CssLength.Percent(0.9f), declarations.fontSize)
        assertEquals(CssLength.Em(1.5f), declarations.textIndent)
        assertEquals(CssLength.Px(12f), declarations.marginTop)
    }

    /**
     * A unitless `line-height` stays a *factor* rather than becoming a length: it must re-multiply each
     * inheriting element's own font size, which a length cannot do. A stated length stays a length.
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
     * The `margin` shorthand's 1-, 2-, and 4-value forms each resolve to the correct top and bottom values.
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
     * `float` is kept as the image-fallback signal this parser now uses, while a property it still has
     * no field for (`display`) is silently dropped. The drawable declaration (`text-align`) in the same
     * rule still survives too.
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
     * Regression guard: an at-rule body, a pseudo-class, and an attribute selector must each be
     * dropped entirely rather than matched loosely, so print-only and state-only styling never leaks
     * onto every page.
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
     * A bare `0` is a length, on every property that takes one.
     *
     * `margin: 0` is how nearly every EPUB writes its reset, and reading it as "stated nothing" is not a
     * small miss: the reader then falls back to its own default gap and spreads a book of running,
     * indent-separated prose over roughly twice the pages the book was set in.
     */
    @Test
    fun aBareZeroIsAZeroLength() {
        val declarations = css("p { margin: 0; text-indent: 0; padding: 0 }").declarationsFor(listOf(element("p")))

        assertEquals(CssLength.Px(0f), declarations.marginTop)
        assertEquals(CssLength.Px(0f), declarations.marginBottom)
        assertEquals(CssLength.Px(0f), declarations.textIndent)
        assertEquals(CssLength.Px(0f), declarations.paddingLeft)
    }

    /** A unitless number that is not zero is not a length, and is dropped rather than guessed at. */
    @Test
    fun aBareNonZeroNumberIsNotALength() {
        assertNull(css("p { margin-top: 12 }").declarationsFor(listOf(element("p"))).marginTop)
    }

    /** A point is 1/72in and a CSS pixel 1/96in, so `12pt` is the 16px a book means by it. */
    @Test
    fun pointsResolveAgainstThePixelTheyAreDefinedBy() {
        val declarations = css("p { font-size: 12pt; margin-top: 9pt }").declarationsFor(listOf(element("p")))

        assertEquals(CssLength.Px(16f), declarations.fontSize)
        assertEquals(CssLength.Px(12f), declarations.marginTop)
    }

    /** The `margin` and `padding` shorthands expand to all four sides, in CSS's 1..4 value forms. */
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
     * `a:link` and `a:visited` describe an ordinary link, so their rules apply to links.
     *
     * A book turning its link underlines off writes exactly that, and dropping the rule — as any selector
     * this matcher cannot judge is dropped — draws an underline under every link the book has.
     */
    @Test
    fun theLinkPseudoClassesStillStyleLinks() {
        val sheet = css("a:link, a:visited { text-decoration: none; color: #000000 }")

        val declarations = sheet.declarationsFor(listOf(element("a")))
        assertEquals("none", declarations.textDecoration)
        assertEquals("#000000", declarations.color)
    }

    /**
     * An empty sheet, an empty string, and a rule with an empty declaration body all resolve to
     * [EpubCss.isEmpty].
     */
    @Test
    fun anEmptySheetCostsNothing() {
        assertTrue(EpubCss.parse(emptyList()).isEmpty())
        assertTrue(css("").isEmpty())
        assertTrue(css("p { }").isEmpty())
    }

    /**
     * Regression guard for the flat-regex extraction this replaced: the *body* of a non-applying
     * at-rule must not leak its inner rules into the ordinary cascade. `@media print { p { … } }` used
     * to match the inner `p { … }` as a normal rule and hide those paragraphs on screen.
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

    /** A `screen`/`all`/`only screen` query applies its whole body, nested `@font-face` included. */
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
     * A feature condition (`(min-width: …)`) needs a viewport this parse-time engine does not have, so
     * the branch is skipped rather than guessed at — applying a wide-screen override to every phone is
     * the same class of leak as applying print styling to the screen. A comma branch that *can* be
     * judged still applies the block.
     */
    @Test
    fun aFeatureConditionedMediaBranchIsSkippedButAJudgeableBranchStillApplies() {
        val skipped = css("@media screen and (min-width: 60em) { p { color: red } }")
        val applied = css("@media print, screen { p { color: blue } }")

        assertNull(skipped.declarationsFor(listOf(element("p"))).color)
        assertEquals("blue", applied.declarationsFor(listOf(element("p"))).color)
    }

    /** Other block at-rules are skipped whole; the rules *after* them still parse in order. */
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

    /** Statement at-rules (`@import`, `@charset`, `@namespace`) end at their `;` and cost nothing else. */
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

    /** A `@font-face` inside an applying `@media` still registers its family. */
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

    /** A stray `}` and an unclosed block fail soft without shifting the rules around them. */
    @Test
    fun malformedBracesFailSoft() {
        val strayCloser = css("} p { color: green }")
        val unclosed = css("p { color: green } .broken { color: red")

        assertEquals("green", strayCloser.declarationsFor(listOf(element("p"))).color)
        assertEquals("green", unclosed.declarationsFor(listOf(element("p"))).color)
    }

    /** A brace inside a quoted string never miscounts the block scan. */
    @Test
    fun aBraceInsideAQuotedStringDoesNotBreakTheScan() {
        val sheet = css("""p { font-family: "Weird{Name" } h1 { color: red }""")

        assertEquals("red", sheet.declarationsFor(listOf(element("h1"))).color)
    }
}

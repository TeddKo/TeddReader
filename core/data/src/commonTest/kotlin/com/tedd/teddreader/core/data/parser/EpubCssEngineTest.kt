package com.tedd.teddreader.core.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpubCssEngineTest {
    private fun css(vararg sheets: String) = EpubCss.parse(sheets.toList())

    private fun element(tag: String, vararg classes: String, id: String? = null) =
        CssElement(tag = tag, classes = classes.toSet(), id = id)

    @Test
    fun aTagRuleStylesThatTag() {
        // The rule this reader used to ignore entirely: books state chapter titles this way.
        val sheet = css("h1 { text-align: center; text-indent: 0; }")

        assertEquals("center", sheet.declarationsFor(listOf(element("h1"))).textAlign)
        assertNull(sheet.declarationsFor(listOf(element("p"))).textAlign)
    }

    @Test
    fun aClassRuleStylesAnyTagCarryingIt() {
        val sheet = css(".dedi { font-size: 0.8em }")

        assertEquals(CssLength.Em(0.8f), sheet.declarationsFor(listOf(element("p", "dedi"))).fontSize)
    }

    @Test
    fun aMoreSpecificRuleWinsRegardlessOfOrder() {
        val sheet = css(
            """
            h1.title { text-align: right }
            h1 { text-align: center }
            .title { text-align: justify }
            """.trimIndent(),
        )

        // tag+class beats class alone, which beats tag alone, whatever order they were written in.
        assertEquals("right", sheet.declarationsFor(listOf(element("h1", "title"))).textAlign)
    }

    @Test
    fun anIdOutranksClassesAndTags() {
        val sheet = css("#lead { color: red } p.lead.big { color: blue }")

        assertEquals("red", sheet.declarationsFor(listOf(element("p", "lead", "big", id = "lead"))).color)
    }

    @Test
    fun theLaterOfTwoEquallySpecificRulesWins() {
        val sheet = css(".a { color: red }", ".a { color: green }")

        assertEquals("green", sheet.declarationsFor(listOf(element("p", "a"))).color)
    }

    @Test
    fun aDescendantSelectorNeedsTheAncestorToBePresent() {
        val sheet = css(".quote p { font-style: italic }")
        val inside = listOf(element("div", "quote"), element("blockquote"), element("p"))
        val outside = listOf(element("div"), element("p"))

        assertEquals("italic", sheet.declarationsFor(inside).fontStyle)
        assertNull(sheet.declarationsFor(outside).fontStyle)
    }

    @Test
    fun aChildCombinatorIsHonouredAsADescendant() {
        val sheet = css(".box > img { width: 50% }")

        assertEquals(
            CssLength.Percent(0.5f),
            sheet.declarationsFor(listOf(element("div", "box"), element("img"))).width,
        )
    }

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
        // A margin and a width belong to the box that declared them, never to what it contains.
        assertNull(inherited.marginTop)
        assertNull(inherited.width)
    }

    @Test
    fun lengthsAreReadInTheUnitsTheseBooksUse() {
        val sheet = css("p { font-size: 90%; text-indent: 1.5em; margin-top: 12px }")
        val declarations = sheet.declarationsFor(listOf(element("p")))

        assertEquals(CssLength.Percent(0.9f), declarations.fontSize)
        assertEquals(CssLength.Em(1.5f), declarations.textIndent)
        assertEquals(CssLength.Px(12f), declarations.marginTop)
    }

    @Test
    fun aUnitlessLineHeightIsAMultipleOfTheFontSize() {
        val sheet = css("p { line-height: 1.6 }")

        assertEquals(CssLength.Em(1.6f), sheet.declarationsFor(listOf(element("p"))).lineHeight)
    }

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

    @Test
    fun propertiesThisRendererCannotDrawAreIgnoredRatherThanGuessedAt() {
        val sheet = css("p { float: left; display: flex; text-align: center }")
        val declarations = sheet.declarationsFor(listOf(element("p")))

        // The one drawable declaration survives; the rest simply do not exist here.
        assertEquals("center", declarations.textAlign)
        assertTrue(sheet.declarationsFor(listOf(element("div"))).isEmpty())
    }

    @Test
    fun aRuleThisCannotJudgeIsDroppedInsteadOfMisapplied() {
        // Print-only and state-only styling must not leak onto every page.
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

    @Test
    fun anEmptySheetCostsNothing() {
        assertTrue(EpubCss.parse(emptyList()).isEmpty())
        assertTrue(css("").isEmpty())
        assertTrue(css("p { }").isEmpty())
    }
}

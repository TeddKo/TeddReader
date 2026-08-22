package com.tedd.teddreader.core.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [parseEpubStyleSheet]/[EpubStyleSheet.widthFor] against how real EPUBs actually declare picture
 * width through CSS classes rather than markup attributes: percent and em widths keyed by a
 * container's or an image's own class, the image-relative-to-container resolution when both are
 * declared, cascade layering across sheets, the bare `img{width:…}` fallback that sizes every
 * unclassed picture, and what must be ignored (`max-width`/`min-width`, commented-out rules).
 */
class EpubStyleSheetTest {
    /**
     * Regression guard: a percent width declared on a container class, and one declared on an
     * image-descendant class, are each read correctly and independently; a class declaring no width at all
     * keeps the picture's own size (null).
     */
    @Test
    fun readsPercentWidthsKeyedByClass() {
        val sheet = parseEpubStyleSheet(
            """
            .img_full{margin:0 auto;width:95%;}
            .img_blockh,.illust .img_blockh{margin-left:12.5%;width:75%;}
            .img_blockw1{text-align:center;margin:0 0 0.5em;display:block;}
            """.trimIndent(),
        )

        assertEquals(CssWidth.Percent(0.95f), sheet.widthFor(listOf("img_full")))
        assertEquals(CssWidth.Percent(0.75f), sheet.widthFor(listOf("img_blockh")))
        assertNull(sheet.widthFor(listOf("img_blockw1")))
    }

    /**
     * A later stylesheet's width for the same class overrides an earlier one's, mirroring the book's own
     * `<link>` order.
     */
    @Test
    fun aLaterSheetWinsTheCascade() {
        val base = parseEpubStyleSheet(".img_full{width:95%;}")
        val merged = parseEpubStyleSheet(".img_full{width:90%;}", base)

        assertEquals(CssWidth.Percent(0.9f), merged.widthFor(listOf("img_full")))
    }

    /**
     * Regression guard: a percent width on `.class img` resolves relative to `.class`'s own em width, not
     * the full column — `100%` of `6.5em` is `6.5em`.
     */
    @Test
    fun aWidthOnTheImageResolvesAgainstItsContainer() {
        val sheet = parseEpubStyleSheet(
            """
            .img_britg{margin:0 auto;width:6.5em;display:inline-block;}
            .img_britg img{width:100%;}
            """.trimIndent(),
        )

        assertEquals(CssWidth.Em(6.5f), sheet.widthFor(listOf("img_britg")))
    }

    /** An em width declared on a descendant `img` selector (`h1.ap img`) is read correctly. */
    @Test
    fun readsEmWidthFromADescendantImageSelector() {
        val sheet = parseEpubStyleSheet("h1.ap img{text-align:center;width:2.5em;margin:0 auto;}")

        assertEquals(CssWidth.Em(2.5f), sheet.widthFor(listOf("ap")))
    }

    /** When a picture's own ancestors both declare a width, the nearest ancestor's value wins. */
    @Test
    fun nearestAncestorWins() {
        val sheet = parseEpubStyleSheet(
            """
            .outer{width:100%;}
            .inner{width:40%;}
            """.trimIndent(),
        )

        assertEquals(CssWidth.Percent(0.4f), sheet.widthFor(listOf("inner", "outer")))
    }

    /**
     * Regression guard: a commented-out rule must not be parsed, and `max-width`/`min-width` must never be
     * read as `width`.
     */
    @Test
    fun ignoresMaxWidthAndCommentedRules() {
        val sheet = parseEpubStyleSheet(
            """
            /* .ghost{width:50%;} */
            .capped{max-width:80%;min-width:10%;}
            """.trimIndent(),
        )

        assertNull(sheet.widthFor(listOf("ghost")))
        assertNull(sheet.widthFor(listOf("capped")))
    }

    /**
     * Regression guard: plenty of books state their picture sizes with a bare `img{width:…}` rule and
     * no class at all; keying every rule by a class name used to throw those away and fall every one
     * of their images back to the full column as though the book had said nothing. A class that does
     * size itself must still win over that blanket rule.
     */
    @Test
    fun aBareImgRuleSizesEveryPictureTheClassesDoNotSizeThemselves() {
        val sheet = parseEpubStyleSheet(
            """
            img{width:80%;}
            .thumb{width:25%;}
            """.trimIndent(),
        )

        assertEquals(CssWidth.Percent(0.8f), sheet.widthFor(emptyList()))
        assertEquals(CssWidth.Percent(0.8f), sheet.widthFor(listOf("unstyled")))
        assertEquals(CssWidth.Percent(0.25f), sheet.widthFor(listOf("thumb")))
    }

    /** `max-width` on a bare `img` rule is not a declared width and must leave the sheet empty. */
    @Test
    fun aBareImgMaxWidthIsStillNotAWidth() {
        val sheet = parseEpubStyleSheet("img{max-width:100%;height:auto;}")

        assertNull(sheet.widthFor(emptyList()))
        assertTrue(sheet.isEmpty())
    }
}

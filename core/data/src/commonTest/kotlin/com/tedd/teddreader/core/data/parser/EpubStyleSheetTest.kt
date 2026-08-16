package com.tedd.teddreader.core.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpubStyleSheetTest {
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
        // Declares no width at all, so the picture keeps its own size.
        assertNull(sheet.widthFor(listOf("img_blockw1")))
    }

    @Test
    fun aLaterSheetWinsTheCascade() {
        val base = parseEpubStyleSheet(".img_full{width:95%;}")
        val merged = parseEpubStyleSheet(".img_full{width:90%;}", base)

        assertEquals(CssWidth.Percent(0.9f), merged.widthFor(listOf("img_full")))
    }

    @Test
    fun aWidthOnTheImageResolvesAgainstItsContainer() {
        val sheet = parseEpubStyleSheet(
            """
            .img_britg{margin:0 auto;width:6.5em;display:inline-block;}
            .img_britg img{width:100%;}
            """.trimIndent(),
        )

        // 100% of a 6.5em box is 6.5em, not the full column.
        assertEquals(CssWidth.Em(6.5f), sheet.widthFor(listOf("img_britg")))
    }

    @Test
    fun readsEmWidthFromADescendantImageSelector() {
        val sheet = parseEpubStyleSheet("h1.ap img{text-align:center;width:2.5em;margin:0 auto;}")

        assertEquals(CssWidth.Em(2.5f), sheet.widthFor(listOf("ap")))
    }

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

    @Test
    fun aBareImgRuleSizesEveryPictureTheClassesDoNotSizeThemselves() {
        // Plenty of books state their picture sizes with `img{width:…}` and no class at all. Keying
        // every rule by a class name threw those away, and each of their images fell back to the full
        // column as though the book had said nothing.
        val sheet = parseEpubStyleSheet(
            """
            img{width:80%;}
            .thumb{width:25%;}
            """.trimIndent(),
        )

        assertEquals(CssWidth.Percent(0.8f), sheet.widthFor(emptyList()))
        assertEquals(CssWidth.Percent(0.8f), sheet.widthFor(listOf("unstyled")))
        // A class that does size itself still wins over the blanket rule.
        assertEquals(CssWidth.Percent(0.25f), sheet.widthFor(listOf("thumb")))
    }

    @Test
    fun aBareImgMaxWidthIsStillNotAWidth() {
        val sheet = parseEpubStyleSheet("img{max-width:100%;height:auto;}")

        assertNull(sheet.widthFor(emptyList()))
        assertTrue(sheet.isEmpty())
    }
}

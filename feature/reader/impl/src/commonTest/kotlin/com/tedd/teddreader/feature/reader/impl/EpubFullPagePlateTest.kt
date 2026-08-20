package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderObjectReplacementChar
import com.tedd.teddreader.core.common.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpubFullPagePlateTest {
    private fun image(start: Long, href: String = "Images/plate.png") = ReaderBlock(
        kind = ReaderBlockKind.IMAGE,
        range = TextRange(start, start + 1),
        imageHref = href,
    )

    private fun cover(start: Long) = ReaderBlock(
        kind = ReaderBlockKind.COVER_IMAGE,
        range = TextRange(start, start + 1),
        imageHref = "Images/cover.jpg",
    )

    private fun paragraph(start: Long, end: Long) =
        ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(start, end))

    @Test
    fun aPageHoldingOnlyAPictureIsGivenOverToIt() {
        val plate = image(0)

        assertEquals(plate, epubFullPagePlate("$ReaderObjectReplacementChar\n", listOf(plate)))
    }

    @Test
    fun aCoverIsStillDrawnFullPage() {
        val coverBlock = cover(0)

        assertEquals(coverBlock, epubFullPagePlate("$ReaderObjectReplacementChar", listOf(coverBlock)))
    }

    @Test
    fun aPictureThatSharesItsPageWithTextStaysInTheFlow() {
        val plate = image(9)

        assertNull(
            epubFullPagePlate(
                text = "Read this$ReaderObjectReplacementChar",
                blocks = listOf(paragraph(0, 9), plate),
            ),
        )
    }

    @Test
    fun twoPicturesOnOnePageAreLeftToTheTextLayoutToStack() {
        assertNull(
            epubFullPagePlate(
                text = "$ReaderObjectReplacementChar\n$ReaderObjectReplacementChar",
                blocks = listOf(image(0), image(2, href = "Images/second.png")),
            ),
        )
    }

    @Test
    fun aPageOfPlainTextHasNoPlate() {
        assertNull(epubFullPagePlate("Just prose.", listOf(paragraph(0, 11))))
    }
}

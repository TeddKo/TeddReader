package com.tedd.teddreader.core.common.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins how [readerImageSize] sizes a picture, case by case, against real figures taken from books this
 * reader opens.
 *
 * The cases exist because each one was once wrong on a device: a hairline rule drawn as a thick band, a
 * small logo blown up to a poster, a tall plate clipped at the page edge. Every expectation here is in em
 * at 22 CSS pixels per em, the conversion the render side uses, so a number can be compared against what
 * a page actually shows.
 */
class ReaderImageSizeTest {
    /** An image block with only the measurements a case cares about stated, everything else unknown. */
    private fun imageBlock(
        aspectRatio: Float? = null,
        naturalWidthPx: Int? = null,
        widthPercent: Float? = null,
        widthEm: Float? = null,
    ) = ReaderBlock(
        kind = ReaderBlockKind.IMAGE,
        range = TextRange(0, 1),
        imageHref = "Images/plate.jpg",
        imageAspectRatio = aspectRatio,
        imageNaturalWidthPx = naturalWidthPx,
        imageWidthPercent = widthPercent,
        imageWidthEm = widthEm,
    )

    /** Float comparison with a tolerance, since these sizes come out of divisions rather than constants. */
    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.01f) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    /** A stylesheet width wins over the picture's own: `.img_full{width:90%}` of a 20em column is 18em. */
    @Test
    fun stylesheetPercentSizesTheImageAgainstTheColumn() {
        val size = imageBlock(aspectRatio = 0.663f, naturalWidthPx = 630, widthPercent = 0.9f)
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 40f, emInPx = 22f)

        assertClose(18f, size.widthEm)
        assertClose(18f / 0.663f, size.heightEm)
    }

    /**
     * A picture with no declared width keeps its own proportions rather than being stretched to the
     * column: `old_line1.png` is 640x25, so a 25.6:1 rule has to stay under a single line of text.
     */
    @Test
    fun aHairlineRuleKeepsItsOwnHeightInsteadOfFillingTheColumn() {
        val size = imageBlock(aspectRatio = 640f / 25f, naturalWidthPx = 640)
            .readerImageSize(columnWidthEm = 17f, maxHeightEm = 30f, emInPx = 22f)

        assertClose(17f, size.widthEm)
        assertTrue(size.heightEm < 1f, "a 25.6:1 rule must stay under one line, was ${size.heightEm}")
    }

    /** `max-width` only ever shrinks: 110 CSS px is 5em at 22px per em, well under the column, so it stays 5em. */
    @Test
    fun aSmallPictureIsNotBlownUpPastItsNaturalSize() {
        val size = imageBlock(aspectRatio = 1f, naturalWidthPx = 110)
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 40f, emInPx = 22f)

        assertClose(5f, size.widthEm)
        assertClose(5f, size.heightEm)
    }

    /**
     * A portrait plate twice as tall as it is wide cannot fill the column on a short page, so the page cap
     * takes over: 95% of the page — the `max-height: 95vh` Readium's stylesheet puts on any image, which
     * leaves the line box holding it somewhere to sit — and the width shrinks with it so the proportions
     * hold.
     */
    @Test
    fun aTallPlateIsScaledDownToThePageAndKeepsItsProportions() {
        val size = imageBlock(aspectRatio = 0.5f, naturalWidthPx = 2000)
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 24f, emInPx = 22f)

        assertClose(22.8f, size.heightEm)
        assertClose(11.4f, size.widthEm)
        assertClose(0.5f, size.widthEm / size.heightEm)
    }

    /**
     * Nothing states this picture's proportions, so its box is square rather than the whole page. Handing
     * it the page strands a small illustration in a screenful of blank space and pushes the text around it
     * off the page — and the picture keeps its true shape when it is drawn anyway.
     */
    @Test
    fun anImageWithUnreadableProportionsIsSquaredOffRatherThanGivenThePage() {
        val size = imageBlock()
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 30f, emInPx = 22f)

        assertTrue(size.heightEm < 30f, "an image must leave room on the page, was ${size.heightEm}")
        assertClose(20f, size.widthEm)
        assertClose(20f, size.heightEm)
    }

    /** The square fallback is still bound by the page: 9.5em is 95% of a 10em page, same cap as a measured box. */
    @Test
    fun anUnmeasurableImageStillShrinksToFitAShortPage() {
        val size = imageBlock()
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 10f, emInPx = 22f)

        assertClose(9.5f, size.heightEm)
    }

    /** An em width from the book's stylesheet is taken as stated, ahead of the picture's intrinsic width. */
    @Test
    fun emWidthFromTheStylesheetIsUsedVerbatim() {
        val size = imageBlock(aspectRatio = 1f, naturalWidthPx = 800, widthEm = 2.5f)
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 40f, emInPx = 22f)

        assertClose(2.5f, size.widthEm)
    }

    /** A horizontal rule is not an image: it spans the column at the fixed height the renderer draws. */
    @Test
    fun aSeparatorIsOneRuleWide() {
        val size = ReaderBlock(kind = ReaderBlockKind.SEPARATOR, range = TextRange(0, 1))
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 40f, emInPx = 22f)

        assertEquals(20f, size.widthEm)
        assertClose(1.25f, size.heightEm)
    }
}
